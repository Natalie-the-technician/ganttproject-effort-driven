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

import biz.ganttproject.core.calendar.GPCalendar
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.customproperty.CustomPropertyClass
import biz.ganttproject.customproperty.CustomPropertyDefinition
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.storage.ProjectDatabase
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskImpl
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import net.sourceforge.ganttproject.task.algorithm.availableHoursPerDay
import net.sourceforge.ganttproject.task.algorithm.capacitySchedule
import net.sourceforge.ganttproject.task.algorithm.effortHours
import net.sourceforge.ganttproject.task.algorithm.findEffortDefinition
import net.sourceforge.ganttproject.task.algorithm.hoursPerDay
import net.sourceforge.ganttproject.undo.GPUndoManager
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * Connects the project model to the pure calculations in [levelTasks] and [proposeBackfill].
 *
 * Kept separate, for the reason recorded in the notes: in stage 1 two bugs hid behind 300 green
 * tests, because precisely this wiring was not checkable. The calculations know no GanttProject
 * types and are checked without the program; here everything that touches the model sits in one
 * place and is thereby surveyable.
 */

/** The "Date fixed" checkbox. [fork change] */
const val TASK_DATE_FIXED = "date_fixed"

/**
 * The "Waiting" checkbox. [fork change]
 *
 * A waiting Task costs NO working time but does determine the order: processing at an authority,
 * a delivery period, an official decision. Without this distinction every waiting period occupies
 * the person as though they sat at it the whole time -- in the plan that is 12 Tasks with 1092
 * days between them. Proposed afterwards, and the proposal hits exactly the point.
 */
const val TASK_WAIT_ONLY = "wait_only"

fun findOrCreateWaitOnly(manager: CustomPropertyManager): CustomPropertyDefinition =
  manager.findEffortDefinition(TASK_WAIT_ONLY)
    ?: manager.createDefinition(TASK_WAIT_ONLY, CustomPropertyClass.BOOLEAN.iD,
                                forkText("fork.column.waitOnly"), null)

/** Is the Task pure waiting time? */
fun Task.isWaitOnly(manager: CustomPropertyManager): Boolean {
  val def = manager.findEffortDefinition(TASK_WAIT_ONLY) ?: return false
  val raw = this.customValues.getValue(def) ?: return false
  return raw as? Boolean ?: raw.toString().equals("true", ignoreCase = true)
}

/**
 * Latest finish. [fork change]
 *
 * The hard dates in the plan are END dates -- advance VAT return by the 10th, annual accounts,
 * application deadlines. "Date fixed", by contrast, holds a START. Both are needed, and both mean
 * something different.
 */
const val TASK_DEADLINE = "deadline"

fun findOrCreateDeadline(manager: CustomPropertyManager): CustomPropertyDefinition =
  manager.findEffortDefinition(TASK_DEADLINE)
    ?: manager.createDefinition(TASK_DEADLINE, CustomPropertyClass.DATE.iD,
                                forkText("fork.column.deadline"), null)

/**
 * Enters the deadline.
 *
 * IT HAS TO BE A `GregorianCalendar`, not a `Date`. A column of type date rejects a `Date`:
 * "value class=class java.util.Date, column class=class java.util.GregorianCalendar". Measured on
 * the machine, when the recurring Tasks were supposed to pass their deadline on.
 */
fun Task.setDeadline(manager: CustomPropertyManager, date: LocalDate) {
  this.customValues.setValue(findOrCreateDeadline(manager),
    CalendarFactory.createGanttCalendar(date.toModelDate()))
}

/** The deadline that is entered, or null. */
fun Task.deadlineDate(manager: CustomPropertyManager): LocalDate? {
  val def = manager.findEffortDefinition(TASK_DEADLINE) ?: return null
  return when (val raw = this.customValues.getValue(def)) {
    null -> null
    is LocalDate -> raw
    is java.util.Date -> raw.toModelLocalDate()
    is java.util.GregorianCalendar -> raw.time.toModelLocalDate()
    else -> runCatching { LocalDate.parse(raw.toString()) }.getOrNull()
  }
}

/**
 * The originally estimated effort. [fork change]
 *
 * Set EXACTLY ONCE and never touched again -- that is the whole point. Anyone who improves an
 * estimate afterwards compares the actual hours against the improved figure and learns nothing
 * more about their estimating quality: the deviation disappears at exactly the moment it is
 * noticed.
 *
 * The point about this, recorded on 17.08.2026: that 15 hours were needed in the end instead of
 * 9 is the statement -- over what period the hours were spread does not matter for it.
 */
const val TASK_EFFORT_ORIGINAL = "effort_original_hours"

fun findOrCreateOriginalEffort(manager: CustomPropertyManager): CustomPropertyDefinition =
  manager.findEffortDefinition(TASK_EFFORT_ORIGINAL)
    ?: manager.createDefinition(TASK_EFFORT_ORIGINAL, CustomPropertyClass.DOUBLE.iD,
                                forkText("fork.column.effortOriginal"), null)

/** The original estimate, or null. */
fun Task.originalEffortHours(manager: CustomPropertyManager): Double? {
  val def = manager.findEffortDefinition(TASK_EFFORT_ORIGINAL) ?: return null
  val raw = this.customValues.getValue(def) ?: return null
  return (raw as? Number)?.toDouble() ?: raw.toString().toDoubleOrNull()
}

/**
 * Records today's estimate, if none has been recorded yet.
 *
 * @return true when something was written.
 */
fun Task.rememberOriginalEffort(manager: CustomPropertyManager): Boolean {
  if (this.originalEffortHours(manager) != null) {
    return false
  }
  val heute = this.effortHours(manager) ?: return false
  this.customValues.setValue(findOrCreateOriginalEffort(manager), heute)
  return true
}

fun findOrCreateDateFixed(manager: CustomPropertyManager): CustomPropertyDefinition =
  manager.findEffortDefinition(TASK_DATE_FIXED)
    ?: manager.createDefinition(TASK_DATE_FIXED, CustomPropertyClass.BOOLEAN.iD,
                                forkText("fork.column.dateFixed"), null)

/**
 * Does the Task carry the "Date fixed" checkbox?
 *
 * The search goes by id OR name -- the same trap as with the effort: a column created by the user
 * carries the typed text as its name and a generated id (`tpc0`).
 */
fun Task.isDateFixed(manager: CustomPropertyManager): Boolean {
  val def = manager.findEffortDefinition(TASK_DATE_FIXED) ?: return false
  val raw = this.customValues.getValue(def) ?: return false
  return raw as? Boolean ?: raw.toString().equals("true", ignoreCase = true)
}

/**
 * Tasks that lie in the past and that nobody has worked on yet.
 *
 * They are the reason for the question asked before levelling: leaving them alone would be a lie
 * about the plan, moving them unasked would be a rewriting of the past.
 */
fun unstartedInThePast(taskManager: TaskManager, today: LocalDate = LocalDate.now()): List<Task> {
  val hierarchy = taskManager.taskHierarchy
  return taskManager.tasks.filter { task ->
    hierarchy.getNestedTasks(task).isEmpty() &&
      task.completionPercentage == 0 &&
      task.start.time.toModelLocalDate() < today
  }
}

/**
 * Tasks that carry an effort but whose ORIGINAL effort has not been recorded yet.
 *
 * WHAT FOR: the plan this fork was developed against had 162 Tasks with an effort before the
 * "Original effort (h)" column existed at all. Without a one-off catch-up the "estimated against
 * needed" evaluation would NEVER have a basis for those Tasks -- it would report for all time
 * that there is nothing to compare.
 *
 * The catch-up happens only on explicit request, not when opening: it is a write to the plan, and
 * that does not belong in a load.
 */
fun tasksMissingOriginalEffort(
  taskManager: TaskManager, taskProperties: CustomPropertyManager
): List<Task> {
  val hierarchy = taskManager.taskHierarchy
  return taskManager.tasks.filter { task ->
    hierarchy.getNestedTasks(task).isEmpty() &&
      task.effortHours(taskProperties) != null &&
      task.originalEffortHours(taskProperties) == null
  }
}

/** Working days from [from] (inclusive) to [to] (exclusive). */
internal fun workingDaysBetween(
  from: LocalDate, to: LocalDate, isWorkingDay: (LocalDate) -> Boolean
): Int {
  var tage = 0
  var tag = from
  var schutz = 0
  while (tag < to && schutz++ < 100_000) {
    if (isWorkingDay(tag)) tage++
    tag = tag.plusDays(1)
  }
  return tage
}

/** The project calendar as a function, so the calculation can use it without the model. */
fun workingDayTest(calendar: GPCalendar): (LocalDate) -> Boolean = { day ->
  calendar.getDayMask(day.toLegacyDate()) and GPCalendar.DayMask.WORKING != 0
}

// The conversion lives in LegacyDates.kt and EXPLICITLY does NOT use java.time: GanttProject
// bends the default time zone at startup, and java.time does not see the bending. The reasoning
// together with the measurement is there.
private fun LocalDate.toLegacyDate(): Date = this.toModelDate()

private fun Date.toLocalDate(): LocalDate = this.toModelLocalDate()

/**
 * Collects the leaf Tasks for levelling.
 *
 * LEAVES ONLY, and that is not a simplification: groups derive their dates from their children.
 * Assigning a start to a group would be discarded silently by the model -- the same reason why
 * the effort hangs off leaves only.
 */
fun collectLevelTasks(
  taskManager: TaskManager,
  taskProperties: CustomPropertyManager,
  resourceProperties: CustomPropertyManager,
  /** Today. Everything before it is the past. */
  today: LocalDate = LocalDate.now(),
  /**
   * Whether unstarted Tasks from the past may be moved forward.
   *
   * THIS ANSWER COMES FROM THE PERSON, not from the algorithm. A Task with no recorded time that
   * lies in the past has been left lying -- leaving it alone would be a lie about the plan,
   * moving it unasked would be a rewriting of the past.
   */
  moveUnstartedPast: Boolean = true
): List<LevelTask> {
  val hierarchy = taskManager.taskHierarchy
  val isWorkingDay = workingDayTest(taskManager.calendar)

  // Id -> the leaves beneath it. For a leaf itself, for a group all of its leaves.
  //
  // WHY THIS IS NECESSARY: in a plan that has grown, dependencies also point at GROUPS ("after
  // chapter 3 is finished"). Levelling, however, computes with leaves only -- a group has no date
  // of its own. Without this resolution such a link silently drops out.
  //
  // MEASURED ON THE MACHINE, before this stood here: 46 of 162 Tasks were pushed back again by
  // the scheduler after levelling, one of them by 10644 days. Levelling had not seen the link,
  // the scheduler enforced it afterwards -- and the capacity calculation was thereby worthless,
  // although it had computed correctly in itself.
  val leavesUnder = mutableMapOf<String, List<String>>()
  fun collectLeaves(task: Task): List<String> {
    val nested = hierarchy.getNestedTasks(task)
    // MILESTONES COUNT, although they are not work.
    //
    // MEASURED ON THE MACHINE, when they were excluded here: 46 of 162 Tasks were moved again by
    // the scheduler after levelling, one of them from 2027 to 2056. Its predecessors were two
    // milestones -- the link was missing from the calculation and was enforced afterwards.
    // 23 of the 28 milestones in the plan have successors.
    //
    // They come in with utilisation 0: they order without costing capacity.
    val leaves = if (nested.isEmpty()) {
      listOf(task.taskID.toString())
    } else {
      nested.flatMap { collectLeaves(it) }
    }
    leavesUnder[task.taskID.toString()] = leaves
    return leaves
  }
  hierarchy.getNestedTasks(hierarchy.rootTask).forEach { collectLeaves(it) }

  val result = mutableListOf<LevelTask>()
  var order = 0
  fun walk(task: Task) {
    val nested = hierarchy.getNestedTasks(task)
    if (nested.isEmpty()) {
      result.add(task.toLevelTask(order++, taskProperties, resourceProperties, leavesUnder, today,
        isWorkingDay, moveUnstartedPast))
    } else {
      nested.forEach { walk(it) }
    }
  }
  hierarchy.getNestedTasks(hierarchy.rootTask).forEach { walk(it) }
  return result
}

/**
 * What is wrong with the hours schedule of the people involved.
 *
 * WHY A PASS OF ITS OWN: on a faulty hours schedule the calculation itself falls back to the
 * fixed number of hours -- it cannot open a dialog in the middle of a run. But that very fallback
 * is the dangerous spot: the plan would look right and would not be. So the question is asked
 * BEFORE the work, and the person decides.
 */
data class CapacityProblems(
  /** Parse errors in the text, once per person. */
  val errors: Map<String, List<String>>,
  /** Tasks that never finish with the daily rate entered (a section with 0 h). */
  val unreachable: List<String>
) {
  val hasErrors: Boolean get() = errors.isNotEmpty()
}

fun capacityProblems(
  taskManager: TaskManager,
  taskProperties: CustomPropertyManager,
  resourceManager: HumanResourceManager,
  resourceProperties: CustomPropertyManager
): CapacityProblems {
  val errors = mutableMapOf<String, List<String>>()
  resourceManager.resources.forEach { resource ->
    val result = resource.capacitySchedule(resourceProperties)
    if (result.hasErrors) {
      errors[resource.name ?: resource.id.toString()] = result.errors
    }
  }
  val isWorkingDay = workingDayTest(taskManager.calendar)
  val unreachable = mutableListOf<String>()
  if (errors.isEmpty()) {
    taskManager.tasks.forEach { task ->
      val effort = task.effortHours(taskProperties) ?: return@forEach
      val schedule = task.capacitySchedule(resourceProperties)
      if (schedule.schedule.isConstant) return@forEach
      val start = task.start?.time?.toModelLocalDate() ?: return@forEach
      if (daysNeeded(effort, start, schedule.schedule, isWorkingDay = isWorkingDay) == null) {
        unreachable.add(task.name ?: task.taskID.toString())
      }
    }
  }
  return CapacityProblems(errors, unreachable)
}

/**
 * The duration of a Task when it starts on a particular day.
 *
 * Without a time-dependent daily rate this is always the same number, and levelling behaves as
 * before. With sections the duration depends on the starting day -- hence a function and not a
 * number in [LevelTask].
 */
fun durationAtStart(
  taskManager: TaskManager,
  taskProperties: CustomPropertyManager,
  resourceProperties: CustomPropertyManager
): (LevelTask, LocalDate) -> Int {
  val isWorkingDay = workingDayTest(taskManager.calendar)
  return fabrik@{ levelTask, start ->
    val task = taskManager.getTask(levelTask.id.toIntOrNull() ?: return@fabrik levelTask.durationDays)
      ?: return@fabrik levelTask.durationDays
    val effort = task.effortHours(taskProperties) ?: return@fabrik levelTask.durationDays
    val schedule = task.capacitySchedule(resourceProperties)
    if (schedule.hasErrors || schedule.schedule.isConstant) {
      return@fabrik levelTask.durationDays
    }
    daysNeeded(effort, start, schedule.schedule, isWorkingDay = isWorkingDay)
      ?: levelTask.durationDays
  }
}

private fun Task.toLevelTask(
  order: Int, taskProperties: CustomPropertyManager, resourceProperties: CustomPropertyManager,
  leavesUnder: Map<String, List<String>>, today: LocalDate, isWorkingDay: (LocalDate) -> Boolean,
  moveUnstartedPast: Boolean
): LevelTask {
  // What this Task claims of each person's day, PER PERSON.
  //
  // NOT ONE NUMBER FOR THE WHOLE TASK, and that is the whole point: the previous version summed
  // all assignment loads into a single figure and levelling then booked that figure against every
  // person on the Task. Two people at 50 % each were each recorded as fully committed. See
  // [LevelTask.loads].
  //
  // The loads of one person are added up: the model does not stop the same person from being
  // assigned to the same Task twice.
  val proPerson: Map<String, Int> = this.assignments
    .mapNotNull { a -> a.resource?.id?.toString()?.let { it to a.load.toDouble() } }
    .groupBy({ it.first }, { it.second })
    // A negative load is clamped to 0 rather than refused. The model does not prevent one --
    // `HumanResource.setLoad(float)` validates nothing -- and a negative occupancy would let
    // levelling hand out capacity that does not exist.
    .mapValues { (_, werte) -> werte.sum().toInt().coerceAtLeast(0) }

  // Three situations, and lumping any two of them together is how this went wrong once already.
  //
  // 1. Milestones and waiting periods cost no working time -- but they keep their pool
  //    membership, so that a report can still name whom they belong to.
  //
  // 2. NO ASSIGNMENT AT ALL: the shared pool at [SHARED_POOL_LOAD]. The Task occupies the day
  //    even when nobody is entered; treating it as free would be the more dangerous assumption
  //    -- it consumes time that the plan then does not know about.
  //
  // 3. ASSIGNMENTS EXIST: their entered loads are the answer, INCLUDING a deliberate 0.
  //
  // Cases 2 and 3 used to share one branch, `if (sum <= 0) 100`. That branch was written for
  // case 2 and its reasoning is sound -- but the condition also caught case 3, and a person
  // entered at 0 % is exactly the one who attends without working on it: supervision, an
  // acceptance to be witnessed, a hand-over. Their 0 was turned into its own opposite, silently.
  // Both cases are pinned down in `LevellingWriteBackTest`.
  val loads: Map<String, Int> = when {
    this.isMilestone || this.isWaitOnly(taskProperties) ->
      proPerson.mapValues { 0 }.ifEmpty { mapOf(SHARED_POOL to 0) }
    else -> proPerson.ifEmpty { mapOf(SHARED_POOL to SHARED_POOL_LOAD) }
  }
  // THREE CASES, and they are not the same thing. The rule for it, fixed on 17.08.2026:
  //
  //   A Task that has not been begun at all, that therefore carries no time, has to be deferred
  //   -- but that requires the question beforehand whether this is the case. What must not happen
  //   under any circumstances: changing something that is already finished. And if twice as much
  //   time is available from today on, the Tasks already begun finish sooner as well.
  //
  // 1. FINISHED (100 %): untouchable. Neither date nor duration is touched -- not the duration
  //    either, because it is measured past and no longer a forecast.
  // 2. BEGUN (0 < % < 100): the START stands, it is the past. The REMAINDER is computed with
  //    today's daily rate: more hours per day means finished earlier.
  // 3. NOT BEGUN (0 %): may be moved. If such a Task lies in the past, the action ASKS
  //    beforehand -- rewriting the past unasked would be exactly what must not happen.
  val startTag = this.start.time.toLocalDate()
  val fertig = this.completionPercentage >= 100
  val angefangen = this.completionPercentage > 0
  val available = this.availableHoursPerDay(resourceProperties)
  val effort = this.effortHours(taskProperties)
  val duration = when {
    // Case 1: measured past, no calculation.
    fertig -> this.duration.length.coerceAtLeast(1)
    // Case 2: the elapsed part plus the remainder at today's daily rate.
    angefangen && effort != null && available > 0.0 -> {
      val restAnteil = (100 - this.completionPercentage).coerceIn(0, 100) / 100.0
      val verstrichen = if (startTag < today) workingDaysBetween(startTag, today, isWorkingDay) else 0
      verstrichen + durationFromEffort(effort * restAnteil, available)
    }
    effort != null && available > 0.0 -> durationFromEffort(effort, available)
    else -> this.duration.length.coerceAtLeast(1)
  }
  // The date is fixed in cases 1 and 2; in case 3 only when the "Date fixed" checkbox is set --
  // or when the person has decided NOT to move work that was left lying forward.
  val liegengeblieben = !angefangen && startTag < today
  val bleibtLiegen = liegengeblieben && !moveUnstartedPast
  val fixed = if (this.isDateFixed(taskProperties) || angefangen || bleibtLiegen) startTag else null
  val earliest = if (this.thirdDateConstraint == TaskImpl.EARLIESTBEGIN && this.third != null) {
    this.third.time.toLocalDate()
  } else {
    null
  }
  return LevelTask(
    id = this.taskID.toString(),
    orderInPlan = order,
    // Priority.ordinal, NOT the stored value: that one is not ordered by importance.
    priority = this.priority.ordinal,
    durationDays = duration,
    loads = loads,
    // A dependency on a group means: after ALL the leaves beneath it.
    predecessors = this.dependenciesAsDependant.toArray()
      .mapNotNull { it.dependee?.taskID?.toString() }
      .flatMap { leavesUnder[it] ?: listOf(it) }
      .distinct(),
    fixedStart = fixed,
    earliestStart = earliest,
    frozen = angefangen || bleibtLiegen,
    deadline = this.deadlineDate(taskProperties),
  )
}

/**
 * Writes the levelled dates, as ONE undo step.
 *
 * @return the number of Tasks actually moved. Nothing to move means: no entry in the undo list.
 * An empty step that looks as though something had happened is worse than none -- the same rule
 * as in the Toggl import.
 */
fun applyLevellingAsSingleEdit(
  starts: Map<String, LocalDate>,
  taskManager: TaskManager,
  undoManager: GPUndoManager,
  editName: String,
  /**
   * The duration levelling computed with. If an entry is missing, the existing duration stays.
   *
   * WHY THIS IS NECESSARY: with a time-dependent daily rate the same effort needs more days in a
   * section of four hours than in one of eight. Taking the OLD duration when writing back writes
   * an end that does not match the computed occupancy -- and levelling would be worthless for the
   * days affected.
   */
  durations: Map<String, Int> = emptyMap()
): Int {
  val isWorkingDay = workingDayTest(taskManager.calendar)
  val moves = starts.mapNotNull { (id, newStart) ->
    val task = taskManager.getTask(id.toIntOrNull() ?: return@mapNotNull null)
      ?: return@mapNotNull null
    val neueDauer = durations[id] ?: task.duration.length
    if (task.start.time.toLocalDate() == newStart && neueDauer == task.duration.length) null
    else Triple(task, newStart, neueDauer.coerceAtLeast(1))
  }
  if (moves.isEmpty()) {
    return 0
  }
  undoManager.undoableEdit(editName) {
    // WHY THE SCHEDULER IS OFF MEANWHILE: otherwise every commit() triggers it again, and it
    // runs over the whole dependency graph. With 162 Tasks that turns into quadratic effort.
    // MEASURED ON THE MACHINE, before this stood here: after 767 seconds of computing time and
    // 2 GB of memory the program was still not finished and had to be aborted.
    //
    // The original uses the same pattern for bulk operations, see TaskActions.kt:207.
    val scheduler = taskManager.algorithmCollection.scheduler
    val wasEnabled = scheduler.isEnabled
    scheduler.isEnabled = false
    try {
      moves.forEach { (task, newStart, keepDays) ->
        val mutator = task.createMutator()
        val calendar = CalendarFactory.createGanttCalendar(newStart.toLegacyDate())
        mutator.setStart(calendar)
        // THE END HAS TO BE SET AS WELL, and it has to be the end, not the duration.
        //
        // setStart alone moves only the beginning and leaves the end standing -- GanttProject
        // STRETCHES the Task by that. MEASURED ON THE MACHINE: the five "Jahresblock" Tasks had a
        // duration of 3 days before and 25, 286, 545, 803 and 1060 afterwards. They occupied
        // years instead of days, and levelling produced 1204 overloaded days with a peak of
        // 600 % -- exactly what it is supposed to prevent. The bug looked like an arithmetic
        // error and lay in the wiring.
        //
        // setDuration() DOES NOT HELP HERE, and that too is measured: MutatorImpl.commit()
        // applies the duration only through `myDurationChange.ifChanged`. But the duration is
        // meant to STAY the same -- the call is therefore not a change and gets skipped. The end
        // is the value that actually changes.
        // Milestones have no duration -- setting an end would turn them into a Task. They are
        // only moved.
        if (!task.isMilestone) {
          mutator.setEnd(CalendarFactory.createGanttCalendar(
            endAfterWorkingDays(newStart, keepDays, isWorkingDay).toLegacyDate()))
        }
        // THE START ALONE DOES NOT SURVIVE THE SCHEDULER. SchedulerImpl places every Task as
        // early as the dependencies allow, and runs on every open and every change. A levelled
        // date that is set only as a start is pulled back on the next run.
        //
        // MEASURED ON THE MACHINE, before this line stood here: after levelling 1204 days
        // remained overloaded, peak 600 % -- levelling had computed and the scheduler had torn
        // part of it down again.
        //
        // The lower bound ("earliest begin") is the only thing it respects. It prevents only the
        // pulling forward: if a duration grows later, the Task still slides backwards.
        mutator.setThird(calendar, TaskImpl.EARLIESTBEGIN)
        mutator.commit()
      }
    } finally {
      scheduler.isEnabled = wasEnabled
    }
    // Once at the end: the groups have to catch up their derived dates.
    scheduler.run()
  }
  return moves.size
}

/** Collects the Tasks for deriving effort and assignment. */
fun collectBackfillTasks(
  taskManager: TaskManager, taskProperties: CustomPropertyManager
): List<BackfillTask> {
  val hierarchy = taskManager.taskHierarchy
  val result = mutableListOf<BackfillTask>()
  fun walk(task: Task) {
    val nested = hierarchy.getNestedTasks(task)
    result.add(BackfillTask(
      id = task.taskID.toString(),
      name = task.name ?: "",
      durationDays = task.duration.length,
      isContainer = nested.isNotEmpty(),
      isMilestone = task.isMilestone,
      isWaitOnly = task.isWaitOnly(taskProperties),
      existingEffortHours = task.effortHours(taskProperties),
      assignmentCount = task.assignments.size))
    nested.forEach { walk(it) }
  }
  hierarchy.getNestedTasks(hierarchy.rootTask).forEach { walk(it) }
  return result
}

/**
 * Writes effort and assignments, as ONE undo step.
 *
 * @return the number of Tasks changed.
 */
fun applyBackfillAsSingleEdit(
  proposal: BackfillProposal,
  resource: HumanResource,
  taskManager: TaskManager,
  taskProperties: CustomPropertyManager,
  projectDatabase: ProjectDatabase,
  undoManager: GPUndoManager,
  editName: String
): Int {
  // NO early exit at changeCount == 0: there can be nothing to derive and still something to do,
  // namely catching up the original effort. MEASURED ON THE MACHINE: in the plan that was exactly
  // the case -- the dialog said "bei 162 Vorgaengen wird der heutige Aufwand als urspruenglicher
  // festgehalten" (for 162 Tasks today's effort is recorded as the original one), nothing was
  // written, because the function had returned beforehand.
  if (proposal.changeCount == 0 &&
    tasksMissingOriginalEffort(taskManager, taskProperties).isEmpty()) {
    return 0
  }
  val effortDef = EffortDrivenProperties.findOrCreateTaskEffort(taskProperties)
  // Without this call the definition exists without a database column, and every write fails.
  // Happened exactly like that in session 3. What is passed is the MANAGER, not the single
  // definition -- the database reconciles all columns.
  projectDatabase.onCustomColumnChange(taskProperties)

  var touched = 0
  undoManager.undoableEdit(editName) {
    // As with levelling: the scheduler rests while writing. Otherwise every single assignment
    // would trigger the effort-driven calculation AND the scheduler over the whole graph -- with
    // 162 Tasks that takes longer than any patience.
    val scheduler = taskManager.algorithmCollection.scheduler
    val wasEnabled = scheduler.isEnabled
    scheduler.isEnabled = false
    try {
    proposal.effortHours.forEach { (id, hours) ->
      taskManager.getTask(id.toIntOrNull() ?: return@forEach)?.let { task ->
        task.customValues.setValue(effortDef, hours)
        // Record the estimate as the ORIGINAL one at the same time. Only the first time --
        // after that it is the fixed reference value for the actual hours.
        task.rememberOriginalEffort(taskProperties)
        touched++
      }
    }
    // Record the original effort for ALL Tasks that already carry one -- not only for those
    // just filled in. Otherwise a plan that came into being before this column would stay
    // unusable for the evaluation for ever.
    tasksMissingOriginalEffort(taskManager, taskProperties).forEach { task ->
      if (task.rememberOriginalEffort(taskProperties)) {
        touched++
      }
    }
    proposal.assignTo.forEach { id ->
      taskManager.getTask(id.toIntOrNull() ?: return@forEach)?.let { task ->
        if (task.assignments.none { it.resource == resource }) {
          task.assignmentCollection.addAssignment(resource).load = 100.0f
          touched++
        }
      }
    }
    } finally {
      scheduler.isEnabled = wasEnabled
    }
    scheduler.run()
  }
  return touched
}

/**
 * The end of a Task that begins on [start] and lasts [days] working days.
 *
 * GanttProject keeps the end EXCLUSIVE: the first day after. The same calculation as in
 * levelling, so that both sides occupy the same days.
 */
private fun endAfterWorkingDays(
  start: LocalDate, days: Int, isWorkingDay: (LocalDate) -> Boolean): LocalDate {
  var day = start
  while (!isWorkingDay(day)) {
    day = day.plusDays(1)
  }
  var counted = 0
  while (true) {
    if (isWorkingDay(day)) {
      counted++
    }
    if (counted >= maxOf(days, 1)) {
      break
    }
    day = day.plusDays(1)
  }
  var end = day.plusDays(1)
  while (!isWorkingDay(end)) {
    end = end.plusDays(1)
  }
  return end
}

/**
 * The utilisation of a person in per cent. [fork change]
 *
 * 100 when nothing is entered. Values outside 1..100 are reset to 100 instead of applying: a typo
 * would otherwise make levelling quietly unusable -- at 5 % nothing fits together any more, and
 * the plan reached into the next century.
 */
fun HumanResource.utilisationPercent(manager: CustomPropertyManager): Int {
  val def = manager.findEffortDefinition(RESOURCE_UTILISATION) ?: return 100
  val raw = this.getCustomField(def) ?: return 100
  val wert = (raw as? Number)?.toInt() ?: raw.toString().toIntOrNull() ?: return 100
  return if (wert in 1..100) wert else 100
}

/** Utilisation, as a property of the person. [fork change] */
const val RESOURCE_UTILISATION = "utilisation_percent"

fun findOrCreateUtilisation(manager: CustomPropertyManager): CustomPropertyDefinition =
  manager.findEffortDefinition(RESOURCE_UTILISATION)
    ?: manager.createDefinition(RESOURCE_UTILISATION, CustomPropertyClass.INTEGER.iD,
                                forkText("fork.column.utilisation"), null)

/** The daily rate of the person that the derivation relies on. */
fun HumanResource.dailyHours(resourceProperties: CustomPropertyManager): Double =
  this.hoursPerDay(resourceProperties)
