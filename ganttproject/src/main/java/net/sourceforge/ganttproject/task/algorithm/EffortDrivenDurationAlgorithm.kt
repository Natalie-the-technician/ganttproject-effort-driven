/*
Copyright 2026

NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.
Kern der aufwandsgetriebenen Terminplanung (Upstream-Issue #83).

This file is part of GanttProject, an opensource project management tool.

GanttProject is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

GanttProject is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with GanttProject.  If not, see <http://www.gnu.org/licenses/>.
*/
package net.sourceforge.ganttproject.task.algorithm

import biz.ganttproject.customproperty.CustomPropertyClass
import biz.ganttproject.customproperty.CustomPropertyDefinition
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.fork.forkText
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskContainmentHierarchyFacade
import net.sourceforge.ganttproject.task.TaskManager
import java.util.function.Supplier
import net.sourceforge.ganttproject.fork.CapacityChange
import net.sourceforge.ganttproject.fork.CapacityParseResult
import net.sourceforge.ganttproject.fork.CapacitySchedule
import net.sourceforge.ganttproject.fork.daysNeeded
import net.sourceforge.ganttproject.fork.utilisationPercent
import net.sourceforge.ganttproject.fork.toModelLocalDate
import net.sourceforge.ganttproject.fork.workingDayTest
import kotlin.math.ceil

/**
 * Effort-driven scheduling: derives task duration from the effort and the daily availability
 * of the assigned resources.
 *
 *     duration [days] = ceil(effort [hours] / available hours per day)
 *     available hours per day = sum over assignments of (resource hours per day * load / 100)
 *
 * Example: 20 hours of effort at 2 h/day gives 10 days; changing the resource to 4 h/day
 * gives 5 days.
 *
 * COMPATIBILITY: effort and daily hours are stored as *custom properties*, not as new
 * attributes of the file format. Stock GanttProject reads and writes custom properties as a
 * regular feature, so a file can travel fork -> stock -> fork without losing the data.
 * A new XML attribute would be silently dropped by the stock saver, which writes a fixed
 * list of known attributes only.
 *
 * This algorithm does NOT do resource levelling. It computes the duration of each task on its
 * own; it does not notice that twenty tasks all claim the same person at the same time.
 */
object EffortDrivenProperties {
  /** Effort of a task, in hours. Custom property on tasks. */
  const val TASK_EFFORT_HOURS = "effort_hours"

  /** Daily availability of a resource, in hours. Custom property on resources. */
  const val RESOURCE_HOURS_PER_DAY = "hours_per_day"

  /**
   * Effort ACTUALLY spent on a task, in hours. Custom property on tasks. [Fork-Aenderung]
   *
   * Purely a record. It never influences the duration, the completion percentage or the status
   * of a task — see [Task.actualEffortHours]. Consumed outside this program by the planning
   * chain, which derives two separate figures from it: the estimation error (actual / planned
   * hours) and the capacity ratio (all actual hours / hours assumed available in the period).
   */
  const val TASK_EFFORT_ACTUAL_HOURS = "effort_actual_hours"

  /** Used when a resource carries no explicit value. */
  const val DEFAULT_HOURS_PER_DAY = 8.0

  /**
   * [Fork-Aenderung] Die Anzeigenamen kommen aus dem Textbuendel dieses Forks, denn sie erscheinen
   * als Spaltenkopf. Gesucht wird dagegen ueber die technische id ([TASK_EFFORT_HOURS] usw.), die
   * bewusst NICHT uebersetzt wird — sonst faende [findEffortDefinition] die Spalte nach einem
   * Sprachwechsel nicht mehr wieder.
   */
  fun findOrCreateTaskEffort(manager: CustomPropertyManager): CustomPropertyDefinition =
    manager.findEffortDefinition(TASK_EFFORT_HOURS)
      ?: manager.createDefinition(TASK_EFFORT_HOURS, CustomPropertyClass.DOUBLE.iD,
                                  forkText("fork.column.effort"), null)

  /** [Fork-Aenderung] Counterpart of [findOrCreateTaskEffort] for the recorded actual effort. */
  fun findOrCreateTaskActualEffort(manager: CustomPropertyManager): CustomPropertyDefinition =
    manager.findEffortDefinition(TASK_EFFORT_ACTUAL_HOURS)
      ?: manager.createDefinition(TASK_EFFORT_ACTUAL_HOURS, CustomPropertyClass.DOUBLE.iD,
                                  forkText("fork.column.actualEffort"), null)

  /**
   * [Fork-Aenderung] Zeitabhaengige Tagesleistung als Text, siehe [net.sourceforge.ganttproject
   * .fork.CapacitySchedule]. Leer heisst: es gilt durchgehend [RESOURCE_HOURS_PER_DAY].
   */
  const val RESOURCE_HOURS_SCHEDULE = "hours_schedule"

  fun findOrCreateResourceSchedule(manager: CustomPropertyManager): CustomPropertyDefinition =
    manager.findEffortDefinition(RESOURCE_HOURS_SCHEDULE)
      ?: manager.createDefinition(RESOURCE_HOURS_SCHEDULE, CustomPropertyClass.TEXT.iD,
                                  forkText("fork.column.hoursSchedule"), null)

  fun findOrCreateResourceHours(manager: CustomPropertyManager): CustomPropertyDefinition =
    manager.findEffortDefinition(RESOURCE_HOURS_PER_DAY)
      ?: manager.createDefinition(RESOURCE_HOURS_PER_DAY, CustomPropertyClass.DOUBLE.iD,
                                  forkText("fork.column.hoursPerDay"), null)
}

/**
 * Finds a custom property by its id OR by its name.
 *
 * Both are needed, and looking at the id alone is a trap: a property that WE create carries
 * [EffortDrivenProperties.TASK_EFFORT_HOURS] as its id, but a column that the USER creates in the
 * column manager gets a generated id (`tpc0`, `tpc1`, …) and keeps the typed text as its *name*
 * only — see `ColumnManager.kt`, which calls `createDefinition(type, title, defaultValue)`.
 *
 * Verified by hand: with an id-only lookup, a resource column named `hours_per_day` was ignored
 * and every task fell back to the default of 8 hours per day.
 */
fun CustomPropertyManager.findEffortDefinition(idOrName: String): CustomPropertyDefinition? =
  getCustomPropertyDefinition(idOrName) ?: definitions.firstOrNull { it.name == idOrName }

/**
 * Reads the effort of a task, or null when the task has none. A task without effort keeps
 * whatever duration it has: this feature is opt-in, per task.
 */
fun Task.effortHours(manager: CustomPropertyManager): Double? {
  val def = manager.findEffortDefinition(EffortDrivenProperties.TASK_EFFORT_HOURS) ?: return null
  val raw = this.customValues.getValue(def) ?: return null
  val value = (raw as? Number)?.toDouble() ?: raw.toString().toDoubleOrNull() ?: return null
  return if (value > 0.0) value else null
}

/**
 * Reads the recorded actual effort of a task, or null when none was recorded. [Fork-Aenderung]
 *
 * Deliberately NOT used anywhere in the scheduling path. Time spent is not the same as work
 * finished: letting it drive the duration would make a task rewrite its own plan while it is
 * still being worked on. [EffortDrivenDurationAlgorithm] must never call this.
 */
fun Task.actualEffortHours(manager: CustomPropertyManager): Double? {
  val def = manager.findEffortDefinition(EffortDrivenProperties.TASK_EFFORT_ACTUAL_HOURS)
    ?: return null
  val raw = this.customValues.getValue(def) ?: return null
  val value = (raw as? Number)?.toDouble() ?: raw.toString().toDoubleOrNull() ?: return null
  return if (value > 0.0) value else null
}

/**
 * Daily hours of a resource. Falls back to [EffortDrivenProperties.DEFAULT_HOURS_PER_DAY] when
 * the resource carries no value, so that an untouched project keeps working.
 */
fun HumanResource.hoursPerDay(manager: CustomPropertyManager): Double {
  val def = manager.findEffortDefinition(EffortDrivenProperties.RESOURCE_HOURS_PER_DAY)
    ?: return EffortDrivenProperties.DEFAULT_HOURS_PER_DAY
  val raw = this.getCustomField(def) ?: return EffortDrivenProperties.DEFAULT_HOURS_PER_DAY
  val value = (raw as? Number)?.toDouble() ?: raw.toString().toDoubleOrNull()
    ?: return EffortDrivenProperties.DEFAULT_HOURS_PER_DAY
  return if (value > 0.0) value else EffortDrivenProperties.DEFAULT_HOURS_PER_DAY
}

/**
 * [Fork-Aenderung] Der Stundenplan dieser Person: die Tagesleistung ueber die Zeit.
 *
 * Die Fehler des Textes werden MITGELIEFERT und nicht verschluckt -- wer rechnet, soll die Wahl
 * haben, bei einem Tippfehler die Arbeit zu verweigern, statt still mit der alten Zahl
 * weiterzurechnen.
 */
fun HumanResource.capacitySchedule(manager: CustomPropertyManager): CapacityParseResult {
  // Mit dem Auslastungsgrad, aus demselben Grund wie in availableHoursPerDay.
  val grad = this.utilisationPercent(manager) / 100.0
  val base = this.hoursPerDay(manager) * grad
  val def = manager.findEffortDefinition(EffortDrivenProperties.RESOURCE_HOURS_SCHEDULE)
    ?: return CapacityParseResult(CapacitySchedule(base), emptyList())
  val gelesen = CapacitySchedule.parse(this.getCustomField(def)?.toString(), base)
  if (grad == 1.0 || gelesen.hasErrors) {
    return gelesen
  }
  return CapacityParseResult(
    CapacitySchedule(base, gelesen.schedule.changes.map { it.copy(hoursPerDay = it.hoursPerDay * grad) }),
    gelesen.errors)
}

/**
 * [Fork-Aenderung] Der Stundenplan, den ein Vorgang ueber seine Zuordnungen sieht.
 *
 * Mehrere Personen werden zusammengezaehlt, jeweils mit ihrem Anteil -- genauso, wie es
 * [Task.availableHoursPerDay] fuer den festen Wert tut.
 */
fun Task.capacitySchedule(resourceProperties: CustomPropertyManager): CapacityParseResult {
  val parts = this.assignments.mapNotNull { assignment ->
    assignment.resource?.let { it.capacitySchedule(resourceProperties) to assignment.load / 100.0 }
  }
  if (parts.isEmpty()) {
    return CapacityParseResult(CapacitySchedule(0.0), emptyList())
  }
  val errors = parts.flatMap { it.first.errors }
  if (parts.size == 1 && parts[0].second == 1.0) {
    return CapacityParseResult(parts[0].first.schedule, errors)
  }
  // Bei mehreren Zuordnungen alle Wechseltage zusammenlegen und je Tag summieren.
  val base = parts.sumOf { (result, share) -> result.schedule.base * share }
  val days = parts.flatMap { it.first.schedule.changes.map { c -> c.from } }.distinct().sorted()
  val changes = days.map { day ->
    CapacityChange(day, parts.sumOf { (result, share) -> result.schedule.hoursOn(day) * share })
  }
  return CapacityParseResult(CapacitySchedule(base, changes), errors)
}

/**
 * Hours per day available to a task through its assignments, weighted by the assignment load.
 * Returns 0.0 when nothing is assigned.
 */
fun Task.availableHoursPerDay(resourceProperties: CustomPropertyManager): Double =
  this.assignments.sumOf { assignment ->
    val resource = assignment.resource as? HumanResource ?: return@sumOf 0.0
    // [Fork-Aenderung] Der Auslastungsgrad wirkt HIER, auf die verfuegbaren Stunden -- nicht als
    // Packgrenze in der Verteilung.
    //
    // AM RECHNER GEMESSEN, als er dort sass: bei 80 % Auslastung und Vorgaengen mit 100 % Last
    // passte kein Vorgang mehr an irgendeinen Tag. Die Fenstersuche lief endlos (die Verteilung
    // kam nie zurueck), und nach dem Schliessen dieser Schleife meldete sie JEDEN Tag als
    // ueberlastet -- 37 Meldungen fuer fuenf Vorgaenge. Beides war Unsinn: "80 % Auslastung"
    // heisst nicht "ein voller Vorgang ist verboten", sondern "an einem Arbeitstag stehen 80 %
    // der Stunden fuer geplante Arbeit zur Verfuegung".
    //
    // An dieser Stelle wirkt er richtig: 8 Std./Tag bei 80 % sind 6,4 Std./Tag, ein Vorgang mit
    // 40 Std. Aufwand dauert damit 7 statt 5 Tage. Der Puffer steht im Plan, statt als Warnung
    // aufzutauchen.
    val grad = resource.utilisationPercent(resourceProperties) / 100.0
    resource.hoursPerDay(resourceProperties) * grad * assignment.load / 100.0
  }

/**
 * What the user typed into the effort field means, once interpreted.
 *
 * Kept out of the JavaFX panel on purpose: a TextField cannot be constructed without a JavaFX
 * toolkit, so logic left inside the panel could not be tested headless. This is where the actual
 * decisions live, so this is what gets tested.
 */
sealed interface EffortInput {
  /** The field was left empty: the task drops its effort and keeps whatever duration it has. */
  object Clear : EffortInput

  /** A usable number of hours. */
  data class Hours(val value: Double) : EffortInput

  /** Not a number, or not a positive one. The stored value is left untouched. */
  object Invalid : EffortInput
}

/**
 * Interprets the text of the effort field. Accepts a comma as the decimal separator, because that
 * is what a German keyboard produces and silently reading "20,5" as 20 would be a trap.
 */
fun parseEffortInput(text: String?): EffortInput {
  val trimmed = text?.trim().orEmpty()
  if (trimmed.isEmpty()) {
    return EffortInput.Clear
  }
  val hours = trimmed.replace(',', '.').toDoubleOrNull() ?: return EffortInput.Invalid
  return if (hours > 0.0 && hours.isFinite()) EffortInput.Hours(hours) else EffortInput.Invalid
}

/**
 * Duration in days for the given effort and availability, at least one day.
 * Kept separate from the model so it can be tested on its own.
 */
fun computeDurationDays(effortHours: Double, availableHoursPerDay: Double): Int {
  require(effortHours > 0.0) { "effort must be positive, was $effortHours" }
  require(availableHoursPerDay > 0.0) { "availability must be positive, was $availableHoursPerDay" }
  return maxOf(1, ceil(effortHours / availableHoursPerDay).toInt())
}

/**
 * Walks the task hierarchy and rewrites the duration of every leaf task that has an effort value,
 * deriving it from the effort and the daily availability of the assigned resources. Modelled on
 * [RecalculateTaskCompletionPercentageAlgorithm]: recurse over the hierarchy, derive a value,
 * commit it through a mutator.
 *
 * Only *leaf* tasks are touched. GanttProject derives container durations from their children, so
 * writing a duration onto a container would be wrong.
 *
 * A leaf is left untouched when it has no effort value (the feature is opt-in per task) or when
 * nothing is assigned to it (availability 0.0 — there is no resource to spread the effort over).
 * After this algorithm has run, the existing scheduler propagates the new dates through the
 * dependency graph; this algorithm therefore has to run *before* the scheduler.
 */
abstract class EffortDrivenDurationAlgorithm(
  private val taskManager: TaskManager,
  private val taskProperties: CustomPropertyManager,
  /**
   * Resolved on every run, not once in the constructor: a project may have no resource manager at
   * all (the task manager is built with one that is null in tests and in headless imports), and the
   * algorithm is constructed before the project is fully wired.
   */
  private val resourceProperties: Supplier<CustomPropertyManager?>,
) : AlgorithmBase() {

  protected abstract fun createContainmentFacade(): TaskContainmentHierarchyFacade

  override fun run() {
    if (!isEnabled) {
      return
    }
    val resourceProps = resourceProperties.get() ?: return
    val facade = createContainmentFacade()
    recalculate(facade.rootTask, facade, resourceProps)
  }

  private fun recalculate(
    task: Task, facade: TaskContainmentHierarchyFacade, resourceProps: CustomPropertyManager) {
    val nested = facade.getNestedTasks(task)
    if (nested.isEmpty()) {
      recalculateLeaf(task, resourceProps)
    } else {
      nested.forEach { recalculate(it, facade, resourceProps) }
    }
  }

  private fun recalculateLeaf(task: Task, resourceProps: CustomPropertyManager) {
    val effort = task.effortHours(taskProperties) ?: return
    val availability = task.availableHoursPerDay(resourceProps)
    if (availability <= 0.0) {
      return
    }
    // [Fork-Aenderung] Zeitabhaengige Tagesleistung: ein Vorgang, der ueber eine Grenze laeuft,
    // wird davor mit der alten und danach mit der neuen Stundenzahl gerechnet. Ohne Abschnitte
    // ist das Ergebnis nachweislich dasselbe wie vorher (CapacityScheduleTest).
    //
    // Bei einem fehlerhaften Stundenplan bleibt es bei der festen Zahl: die Meldung gehoert an die
    // Oberflaeche, und ein Algorithmus, der bei jedem Durchlauf einen Dialog aufmacht, waere
    // unbrauchbar. Die beiden Menuepunkte der Verteilung pruefen den Text und verweigern die
    // Arbeit -- dort sieht der Mensch den Fehler.
    val schedule = task.capacitySchedule(resourceProps)
    val start = task.start?.time?.toModelLocalDate()
    val days = if (schedule.hasErrors || schedule.schedule.isConstant || start == null) {
      computeDurationDays(effort, availability)
    } else {
      daysNeeded(effort, start, schedule.schedule, isWorkingDay = workingDayTest(taskManager.calendar))
        ?: computeDurationDays(effort, availability)
    }
    val newDuration = taskManager.createLength(days.toLong())
    val current = task.duration
    // Avoid firing a change event when nothing actually changes: important once this algorithm
    // is triggered from resource events, so it does not feed itself in a loop.
    if (current.timeUnit == newDuration.timeUnit && current.length == newDuration.length) {
      return
    }
    val mutator = task.createMutator()
    mutator.setDuration(newDuration)
    mutator.commit()
    // Report through the diagnostic hook of AlgorithmBase. This is the only externally visible
    // trace of *which* tasks this algorithm decided to touch: the model silently discards a
    // duration written onto a container, so the duration alone cannot tell a caller (or a test)
    // whether the leaf check did its job.
    diagnostic.addModifiedTask(task, task.start?.time, task.end?.time)
  }
}
