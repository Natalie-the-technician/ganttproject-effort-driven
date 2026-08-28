/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
Core of effort-driven scheduling (upstream issue #83).

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
import net.sourceforge.ganttproject.task.ResourceAssignment
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
   * Effort ACTUALLY spent on a task, in hours. Custom property on tasks. [fork change]
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
   * [fork change] The display names come from this fork's text bundle, because they appear as a
   * column header. The lookup, by contrast, goes through the technical id ([TASK_EFFORT_HOURS]
   * and so on), which is deliberately NOT translated — otherwise [findEffortDefinition] would no
   * longer find the column again after a change of language.
   */
  fun findOrCreateTaskEffort(manager: CustomPropertyManager): CustomPropertyDefinition =
    manager.findEffortDefinition(TASK_EFFORT_HOURS)
      ?: manager.createDefinition(TASK_EFFORT_HOURS, CustomPropertyClass.DOUBLE.iD,
                                  forkText("fork.column.effort"), null)

  /** [fork change] Counterpart of [findOrCreateTaskEffort] for the recorded actual effort. */
  fun findOrCreateTaskActualEffort(manager: CustomPropertyManager): CustomPropertyDefinition =
    manager.findEffortDefinition(TASK_EFFORT_ACTUAL_HOURS)
      ?: manager.createDefinition(TASK_EFFORT_ACTUAL_HOURS, CustomPropertyClass.DOUBLE.iD,
                                  forkText("fork.column.actualEffort"), null)

  /**
   * [fork change] The time-dependent daily rate as text, see [net.sourceforge.ganttproject
   * .fork.CapacitySchedule]. Empty means: [RESOURCE_HOURS_PER_DAY] applies throughout.
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
 * Reads the recorded actual effort of a task, or null when none was recorded. [fork change]
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
 * [fork change] The hours schedule of this person: the daily rate over time.
 *
 * The errors in the text are DELIVERED ALONG and not swallowed -- whoever computes should have
 * the choice of refusing the work on a typo instead of quietly computing on with the old
 * number.
 */
fun HumanResource.capacitySchedule(manager: CustomPropertyManager): CapacityParseResult {
  // With the utilisation, for the same reason as in availableHoursPerDay.
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
 * [fork change] AXIS B -- does this assignment contribute working hours at all?
 *
 * `isNoEffort` is carried by every assignment and stored as the XML attribute `no-effort`. It is
 * NEGATED on purpose: `false` means "contributes", so a file that has never seen this fork, and
 * every assignment made before the tick existed, computes exactly as it did before. Do not turn
 * the name around -- the default has to stay `false`.
 *
 * WHAT IT IS FOR: the person who attends the review, holds the budget or has to sign the result
 * belongs on the task -- they are part of the plan, they show up in the load chart and in the
 * reports. But they do no work that eats into the effort, so they must not shorten the task.
 * Without the tick the only way to say that was to leave them off the task altogether, which
 * loses the information.
 *
 * This is the ONE place the question is asked, so that the three readers of the assignments
 * ([Task.availableHoursPerDay], [Task.capacitySchedule] and
 * [net.sourceforge.ganttproject.fork.durationDaysWithDaysOff]) cannot drift apart. If they did,
 * the duration would depend on which of them happened to run -- on whether an hours schedule was
 * filled in, or whether anyone had booked a day off.
 */
val ResourceAssignment.contributesEffort: Boolean get() = !this.isNoEffort

/**
 * [fork change] The hours schedule a Task sees through its assignments.
 *
 * Several people are added together, each with their share -- exactly as
 * [Task.availableHoursPerDay] does for the fixed value. Assignments that carry axis B are left
 * out here as well; see [contributesEffort].
 */
fun Task.capacitySchedule(resourceProperties: CustomPropertyManager): CapacityParseResult {
  val parts = this.assignments.filter { it.contributesEffort }.mapNotNull { assignment ->
    assignment.resource?.let { it.capacitySchedule(resourceProperties) to assignment.load / 100.0 }
  }
  if (parts.isEmpty()) {
    return CapacityParseResult(CapacitySchedule(0.0), emptyList())
  }
  val errors = parts.flatMap { it.first.errors }
  if (parts.size == 1 && parts[0].second == 1.0) {
    return CapacityParseResult(parts[0].first.schedule, errors)
  }
  // With several assignments, merge all the changeover days and sum per day.
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
 *
 * [fork change] Assignments that carry axis B contribute nothing; see [contributesEffort]. When
 * EVERY assignment carries it the result is 0.0, the same answer as for a task with nobody on it
 * -- and the callers already treat that as "there is nothing to derive a duration from" and leave
 * the task alone. A task attended only by onlookers keeps the duration it has, which is the only
 * honest answer.
 */
fun Task.availableHoursPerDay(resourceProperties: CustomPropertyManager): Double =
  this.assignments.sumOf { assignment ->
    if (!assignment.contributesEffort) {
      return@sumOf 0.0
    }
    val resource = assignment.resource as? HumanResource ?: return@sumOf 0.0
    // [fork change] The utilisation takes effect HERE, on the available hours -- not as a
    // packing limit in levelling.
    //
    // MEASURED ON THE MACHINE, when it sat there: at 80 % utilisation and Tasks with 100 % load
    // no Task fitted on any day any more. The window search ran endlessly (levelling never came
    // back), and after that loop was closed it reported EVERY day as overloaded -- 37 reports for
    // five Tasks. Both were nonsense: "80 % utilisation" does not mean "a full Task is
    // forbidden", but "on a working day 80 % of the hours are available for planned work".
    //
    // In this place it takes effect correctly: 8 h/day at 80 % is 6.4 h/day, so a Task with 40 h
    // of effort lasts 7 days instead of 5. The buffer stands in the plan instead of turning up as
    // a warning.
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
    // [fork change] Time-dependent daily rate: a Task that runs across a boundary is computed
    // with the old number of hours before it and with the new one after it. Without sections the
    // result is demonstrably the same as before (CapacityScheduleTest).
    //
    // On a faulty hours schedule the fixed number stays in force: the report belongs in the user
    // interface, and an algorithm that opens a dialog on every run would be unusable. The two
    // levelling menu items check the text and refuse the work -- that is where the person sees
    // the error.
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
