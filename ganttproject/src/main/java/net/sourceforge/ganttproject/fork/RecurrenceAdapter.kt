/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.

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
package net.sourceforge.ganttproject.fork

import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.customproperty.CustomPropertyClass
import biz.ganttproject.customproperty.CustomPropertyDefinition
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.storage.ProjectDatabase
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import net.sourceforge.ganttproject.task.algorithm.effortHours
import net.sourceforge.ganttproject.task.algorithm.findEffortDefinition
import net.sourceforge.ganttproject.undo.GPUndoManager
import java.time.LocalDate

/**
 * Recurring Tasks in the project model: one Task with a recurrence becomes many.
 *
 * THREE PROPERTIES that make this tool usable rather than dangerous:
 *
 * 1. **It is repeatable.** Every occurrence created carries the id of its source Task AND its
 *    date. A second call therefore creates nothing twice -- neither after a change to the plan nor
 *    after an abort halfway through. A tool that produces 40 duplicate Tasks on the second click
 *    gets used exactly once.
 * 2. **It does not touch what already exists.** Neither the source Task nor occurrences already
 *    created: whoever has moved or ticked one of them off keeps that.
 * 3. **It hangs the occurrences NEXT TO the source Task**, in the same group. Hanging them under
 *    the source Task would turn it into a group -- and a group derives its dates from its
 *    children, whereby the original Task would lose its own duration.
 */

/** The recurrence rule as text. [fork change] */
const val TASK_RECURRENCE = "recurrence"

/**
 * Marker of a created occurrence: "id of the source Task @ date".
 *
 * WHY THE DATE IS IN IT as well and not only the id: without it there would be no way to tell
 * WHICH date already exists. After a change to the rule -- "bis 2027" becomes "bis 2030" -- the
 * missing dates have to be added without duplicating the ones that are there.
 */
const val TASK_RECURRENCE_OF = "recurrence_of"

fun findOrCreateRecurrence(manager: CustomPropertyManager): CustomPropertyDefinition =
  manager.findEffortDefinition(TASK_RECURRENCE)
    ?: manager.createDefinition(TASK_RECURRENCE, CustomPropertyClass.TEXT.iD,
                                forkText("fork.column.recurrence"), null)

fun findOrCreateRecurrenceOf(manager: CustomPropertyManager): CustomPropertyDefinition =
  manager.findEffortDefinition(TASK_RECURRENCE_OF)
    ?: manager.createDefinition(TASK_RECURRENCE_OF, CustomPropertyClass.TEXT.iD,
                                forkText("fork.column.recurrenceOf"), null)

/** The recurrence text that is entered, or null. */
fun Task.recurrenceText(manager: CustomPropertyManager): String? {
  val def = manager.findEffortDefinition(TASK_RECURRENCE) ?: return null
  return this.customValues.getValue(def)?.toString()?.takeIf { it.isNotBlank() }
}

/** The marker of a created occurrence, or null. */
fun Task.recurrenceOf(manager: CustomPropertyManager): String? {
  val def = manager.findEffortDefinition(TASK_RECURRENCE_OF) ?: return null
  return this.customValues.getValue(def)?.toString()?.takeIf { it.isNotBlank() }
}

/**
 * Is this Task the collecting group of a series?
 *
 * NECESSARY BECAUSE BOTH USE THE SAME FIELD: the group and its dates carry their marker in the
 * same column. Checking only for "marker present" mistakes the group for a date -- and then reads
 * its DERIVED duration as that of a Task. Four tests of our own reported exactly that at once
 * (26 days instead of 3).
 */
fun Task.isRecurrenceGroup(manager: CustomPropertyManager): Boolean =
  this.recurrenceOf(manager)?.endsWith("@Serie") == true

/** The marker, when this Task is a single occurrence of a series (not the group). */
fun Task.recurrenceOccurrenceOf(manager: CustomPropertyManager): String? =
  this.recurrenceOf(manager)?.takeIf { !it.endsWith("@Serie") }

/** How a created occurrence is marked. */
fun recurrenceMark(sourceTaskId: Int, date: LocalDate): String = "$sourceTaskId@$date"

/**
 * Marker of the collecting group of a series.
 *
 * WHAT A GROUP IS FOR: without it every date stands as its own row with the same text in the
 * table -- monthly over two years gives 24 rows of the same name. The requirement for it,
 * recorded on 17.08.2026: created side by side, with only one text on the left.
 *
 * A single strand of bars in ONE row is not possible: the bars of a Task arise exclusively from
 * the calendar (`TaskActivitiesAlgorithm.recalculateActivities` asks
 * `calendar.getActivities(start, end)`), a Task with gaps of its own is not provided for in the
 * model. A group achieves the same thing for the eye: collapsed one row, expanded all the dates.
 *
 * The marker makes the second run unambiguous -- it finds the group again instead of creating a
 * second one.
 */
fun recurrenceGroupMark(sourceTaskId: Int): String = "$sourceTaskId@Serie"

/** A date that is to be created. */
data class PlannedOccurrence(
  val sourceTaskId: Int,
  val sourceName: String,
  val date: LocalDate,
  val mark: String
)

/** What creating would yield, BEFORE anything is written. */
data class RecurrencePlan(
  val occurrences: List<PlannedOccurrence>,
  /** Series with an unreadable rule: Task name -> error. */
  val errors: Map<String, List<String>>,
  /** Series that were truncated at the upper bound. */
  val truncated: List<String>,
  /** Number of series that have something to contribute. */
  val seriesCount: Int
) {
  val hasErrors: Boolean get() = errors.isNotEmpty()
}

/**
 * Collects what would have to be created. Writes nothing.
 *
 * Occurrences that already exist are recognised by their marker -- which is why a second call has
 * no consequences.
 */
fun planRecurrences(
  taskManager: TaskManager,
  taskProperties: CustomPropertyManager,
  /**
   * [fork change] The resource properties, so that the dates can be moved on the day grid of the
   * people on the series rather than on the project calendar alone -- see [WorkWeekWorkingDays].
   *
   * OPTIONAL AND LAST, so that every existing positional call keeps compiling and keeps the
   * behaviour it had; the same shape [applyLevellingAsSingleEdit] uses for the same reason.
   * Without it the project calendar decides for every series, which is what this function did
   * until 04.09.2026.
   */
  resourceProperties: CustomPropertyManager? = null
): RecurrencePlan {
  // The project calendar alone -- the answer for a series whose people have entered nothing, and
  // the whole answer when no resource properties are to hand.
  val projectOnly = workingDayTest(taskManager.calendar)
  val workingDays = resourceProperties?.let { WorkWeekWorkingDays(taskManager.calendar, it) }
  val vorhanden = taskManager.tasks.mapNotNull { it.recurrenceOf(taskProperties) }.toSet()

  val geplant = mutableListOf<PlannedOccurrence>()
  val errors = mutableMapOf<String, List<String>>()
  val truncated = mutableListOf<String>()
  var serien = 0

  taskManager.tasks.forEach { task ->
    val text = task.recurrenceText(taskProperties) ?: return@forEach
    val gelesen = RecurrenceRule.parse(text)
    if (gelesen.hasErrors || gelesen.rule == null) {
      errors[task.name ?: task.taskID.toString()] = gelesen.errors
      return@forEach
    }
    val start = task.start?.time?.toModelLocalDate() ?: return@forEach
    // [fork change] THE GRID OF THIS SERIES, not one grid for all of them. A date that falls on a
    // non-working day moves forward to the next working day, and which day that is depends on the
    // people on the series: a Saturday is the next working day for somebody who works Saturdays
    // and is not one for anybody else.
    val isWorkingDay = workingDays?.forTask(task) ?: projectOnly
    val termine = occurrences(gelesen.rule, start, isWorkingDay)
    if (isTruncated(gelesen.rule, start, isWorkingDay)) {
      truncated.add(task.name ?: task.taskID.toString())
    }
    // The first date IS the source Task -- it is not created a second time.
    val fehlend = termine.drop(1)
      .map { PlannedOccurrence(task.taskID, task.name.orEmpty(), it, recurrenceMark(task.taskID, it)) }
      .filter { it.mark !in vorhanden }
    if (fehlend.isNotEmpty()) {
      serien++
      geplant.addAll(fehlend)
    }
  }
  return RecurrencePlan(geplant, errors, truncated, serien)
}

/**
 * Creates the planned occurrences, as ONE undo step.
 *
 * @return the number of Tasks created.
 */
fun applyRecurrencesAsSingleEdit(
  plan: RecurrencePlan,
  taskManager: TaskManager,
  taskProperties: CustomPropertyManager,
  projectDatabase: ProjectDatabase,
  undoManager: GPUndoManager,
  editName: String
): Int {
  if (plan.occurrences.isEmpty()) {
    return 0
  }
  val markDef = findOrCreateRecurrenceOf(taskProperties)
  val effortDef = EffortDrivenProperties.findOrCreateTaskEffort(taskProperties)
  // Without this call the definition exists without a database column, and every write fails.
  // Happened exactly like that in session 3.
  projectDatabase.onCustomColumnChange(taskProperties)

  var angelegt = 0
  undoManager.undoableEdit(editName) {
    // As with levelling, the scheduler rests while writing: otherwise every new Task would send
    // it running over the whole dependency graph.
    val scheduler = taskManager.algorithmCollection.scheduler
    val wasEnabled = scheduler.isEnabled
    scheduler.isEnabled = false
    try {
      // One collecting group per series: collapsed, one row instead of twelve of the same name.
      val gruppen = mutableMapOf<Int, net.sourceforge.ganttproject.task.Task>()
      plan.occurrences.map { it.sourceTaskId }.distinct().forEach { quellId ->
        val quelle = taskManager.getTask(quellId) ?: return@forEach
        val vorhandene = taskManager.tasks.firstOrNull {
          it.recurrenceOf(taskProperties) == recurrenceGroupMark(quellId)
        }
        if (vorhandene != null) {
          gruppen[quellId] = vorhandene
          return@forEach
        }
        // The group comes into being where the source Task stood -- the outline is preserved.
        val gruppe = taskManager.newTaskBuilder()
          .withName(quelle.name)
          .withStartDate(quelle.start.time)
          .withDuration(quelle.duration)
          .withParent(taskManager.taskHierarchy.getContainer(quelle))
          .build()
        gruppe.customValues.setValue(markDef, recurrenceGroupMark(quellId))
        // The source Task moves in as well: all the dates of the series stand together.
        // THE GROUP FIRST, THEN THE MOVE -- a Task that moved into itself would lose its dates.
        taskManager.taskHierarchy.move(quelle, gruppe)
        gruppen[quellId] = gruppe
      }
      plan.occurrences.forEach { termin ->
        val quelle = taskManager.getTask(termin.sourceTaskId) ?: return@forEach
        val neu = taskManager.newTaskBuilder()
          .withName(quelle.name)
          .withStartDate(termin.date.toModelDate())
          .withDuration(quelle.duration)
          .withParent(gruppen[termin.sourceTaskId]
            ?: taskManager.taskHierarchy.getContainer(quelle))
          .withColor(quelle.color)
          .withPriority(quelle.priority)
          .build()
        neu.customValues.setValue(markDef, termin.mark)
        // Take effort and assignment along: without both, capacity levelling does not know the
        // occurrence, and the whole purpose -- making the recurring work visible -- would be
        // missed.
        quelle.effortHours(taskProperties)?.let { neu.customValues.setValue(effortDef, it) }
        quelle.assignments.forEach { zuordnung ->
          zuordnung.resource?.let { person ->
            neu.assignmentCollection.addAssignment(person).load = zuordnung.load
          }
        }
        // TAKE THE DATE BINDING AND THE KIND ALONG. A question raised on 17.08.2026 uncovered
        // the gap: an advance VAT return is due by the 10th, not "some time in the month".
        // Without these lines capacity levelling would have pushed it to the next free day --
        // and the series would have become worthless.
        //
        // What the source Task carries, its occurrences carry too:
        if (quelle.isDateFixed(taskProperties)) {
          neu.customValues.setValue(findOrCreateDateFixed(taskProperties), true)
        }
        if (quelle.isWaitOnly(taskProperties)) {
          neu.customValues.setValue(findOrCreateWaitOnly(taskProperties), true)
        }
        // THE DEADLINE MOVES ALONG, at the same distance from the start. Simply copying it would
        // be wrong: then all twelve monthly Tasks would have the same deadline in January. The
        // DISTANCE is what repeats -- "three days after the start" stays the same in every month.
        val quellStart = quelle.start?.time?.toModelLocalDate()
        val quellFrist = quelle.deadlineDate(taskProperties)
        if (quellStart != null && quellFrist != null) {
          val abstand = java.time.temporal.ChronoUnit.DAYS.between(quellStart, quellFrist)
          neu.setDeadline(taskProperties, termin.date.plusDays(abstand))
        }
        // The occurrence itself carries NO recurrence rule -- otherwise the next run would
        // produce occurrences of occurrences.
        angelegt++
      }
    } finally {
      scheduler.isEnabled = wasEnabled
    }
    scheduler.run()
  }
  return angelegt
}

/** For the preview only: a date as GanttProject displays it. */
internal fun LocalDate.forDisplay(): String =
  CalendarFactory.createGanttCalendar(this.toModelDate()).toString()
