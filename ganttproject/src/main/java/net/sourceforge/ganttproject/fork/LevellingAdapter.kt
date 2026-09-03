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
import net.sourceforge.ganttproject.GPLogger
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

/**
 * The days off of the people as a function, for [levelTasks].
 *
 * THE PERSON IS NAMED BY THE RESOURCE ID, as a string -- the same key [toLevelTask] builds
 * [LevelTask.loads] from, and hence the same one the capacity pools carry. That is the joint the
 * whole pass-through hangs on: if one half spoke of ids and the other of names, the answer would
 * be correct in itself and would be about nobody. `LevellingDaysOffTest` therefore does not write
 * the key out but takes it from `collectLevelTasks`.
 *
 * READ ONCE, NOT PER QUESTION. The window search asks this per person and working day and runs
 * over up to 50 000 days -- reading `daysOff` out of the model that often would turn levelling
 * into a waiting game. The days off are therefore fetched here, once, and the returned function
 * only looks things up. The price is that it does not see later changes; it is built anew for
 * each run of levelling, which is exactly its lifetime.
 *
 * UNKNOWN KEYS COUNT AS AVAILABLE. Two of them actually occur: [SHARED_POOL], the shared pool of
 * the unassigned Tasks, which is not a person and can have no holiday, and a resource deleted
 * between conversion and calculation. Neither is an absence, and inventing one out of a gap in
 * knowledge would be the worse answer.
 *
 * The interval end is EXCLUSIVE. That is not decided here -- [daysOffRanges] reads the model, and
 * the reasoning, together with the five places it was measured at, is in `DaysOffDuration.kt`.
 */
fun availabilityTest(resourceManager: HumanResourceManager): (String, LocalDate) -> Boolean {
  val daysOff: Map<String, List<Pair<LocalDate, LocalDate>>> = resourceManager.resources
    .associate { it.id.toString() to it.daysOffRanges() }
    .filterValues { it.isNotEmpty() }
  if (daysOff.isEmpty()) {
    // Nobody has anything entered -- then no lookup has to take place at all.
    return { _, _ -> true }
  }
  return { person, day ->
    daysOff[person]?.none { (from, toExclusive) ->
      !day.isBefore(from) && day.isBefore(toExclusive)
    } ?: true
  }
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
  // [fork change] PER TASK, not once for everybody -- see [WorkWeekWorkingDays]. Built here and
  // not per task on purpose: it reads each person's working week once and remembers it, and this
  // object's lifetime is exactly one pass.
  val workingDays = WorkWeekWorkingDays(taskManager.calendar, resourceProperties)

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
        workingDays.forTask(task), moveUnstartedPast))
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
  // [fork change] per task; see [WorkWeekWorkingDays].
  val workingDays = WorkWeekWorkingDays(taskManager.calendar, resourceProperties)
  val unreachable = mutableListOf<String>()
  if (errors.isEmpty()) {
    taskManager.tasks.forEach { task ->
      val effort = task.effortHours(taskProperties) ?: return@forEach
      val schedule = task.capacitySchedule(resourceProperties)
      if (schedule.schedule.isConstant) return@forEach
      val start = task.start?.time?.toModelLocalDate() ?: return@forEach
      if (daysNeeded(effort, start, schedule.schedule,
          isWorkingDay = workingDays.forTask(task)) == null) {
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
  // [fork change] per task; see [WorkWeekWorkingDays].
  val workingDays = WorkWeekWorkingDays(taskManager.calendar, resourceProperties)
  return fabrik@{ levelTask, start ->
    val task = taskManager.getTask(levelTask.id.toIntOrNull() ?: return@fabrik levelTask.durationDays)
      ?: return@fabrik levelTask.durationDays
    val effort = task.effortHours(taskProperties) ?: return@fabrik levelTask.durationDays
    val schedule = task.capacitySchedule(resourceProperties)
    if (schedule.hasErrors || schedule.schedule.isConstant) {
      return@fabrik levelTask.durationDays
    }
    daysNeeded(effort, start, schedule.schedule, isWorkingDay = workingDays.forTask(task))
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
  // AXIS A: whose absence takes this Task with it. Read straight off the assignment; P1 carried
  // the value and nobody read it, and this is the place where it starts to matter.
  //
  // READ SEPARATELY FROM THE LOADS ABOVE, not folded into them, and the case that motivates the
  // whole undertaking is the reason: an assignment at load 0 is the person who has to be present
  // without working on the Task -- supervision, an instruction, an acceptance. In [loads] they
  // appear with a 0 and cost no capacity; in here they appear all the same and move the Task.
  // Filtering by load first would drop exactly that person.
  //
  // THE SAME KEY AS THE CAPACITY POOLS, the resource id as a string. That is the joint the whole
  // thing hangs on: `availabilityTest` is asked with these strings, and if this half spoke of
  // ids while that one spoke of names, every answer would be correct in itself and about
  // nobody.
  //
  // MILESTONES AND WAITING PERIODS KEEP THEIR MARKING, although they cost no working time. A
  // milestone is typically an acceptance, and an acceptance without the person accepting is
  // exactly the case axis A is for. They lose their LOAD above, not their people.
  val blocking: Set<String> = this.assignments
    .filter { it.isBlocking }
    .mapNotNull { it.resource?.id?.toString() }
    .toSet()
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
    blocking = blocking,
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
 * Writes the levelled dates, as ONE undo step, and reports afterwards that a levelling run has
 * ended -- see [LevellingRunNotifier] for why the report has to come after the writing and not
 * from inside it.
 *
 * The write-back itself is unchanged and lives in [writeLevellingBack]; this function only puts the
 * notification around it, so that the ordering cannot be got wrong at a call site.
 *
 * @return the number of Tasks actually moved. Nothing to move means: no entry in the undo list.
 * An empty step that looks as though something had happened is worse than none -- the same rule
 * as in the Toggl import. The run is reported in that case too: a levelling that found nothing to
 * move still leaves a plan that agrees with the calculation.
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
  durations: Map<String, Int> = emptyMap(),
  /** Defaults to the shared instance; a test passes its own so that no state travels between tests. */
  notifier: LevellingRunNotifier = levellingRunNotifier,
  /**
   * [fork change] Where the working weeks are read from. `null` means: the project calendar alone,
   * exactly as before this parameter existed.
   *
   * WHY IT HAS TO BE HERE AT ALL, and why it is not scope for its own sake: [durationAtStart]
   * computes the DURATION on the day grid of the people on the task. The write-back turns that
   * duration into an END, and it did so on the project calendar. For somebody who works Mon, Tue,
   * Fri, Sat those are two different grids -- four working days from Monday end on Saturday by the
   * one and on Thursday by the other. Levelling would then compute correctly and write a date that
   * does not match. The two have to read the same grid or neither should.
   *
   * LAST IN THE PARAMETER LIST, and optional, so that every existing positional call site keeps
   * compiling and keeps its old behaviour.
   */
  resourceProperties: CustomPropertyManager? = null
): Int = notifier.runAndReport {
  writeLevellingBack(starts, taskManager, undoManager, editName, durations, resourceProperties)
}

/** The write-back proper. Unchanged; only the notification in [applyLevellingAsSingleEdit] is new. */
private fun writeLevellingBack(
  starts: Map<String, LocalDate>,
  taskManager: TaskManager,
  undoManager: GPUndoManager,
  editName: String,
  durations: Map<String, Int>,
  resourceProperties: CustomPropertyManager? = null
): Int {
  // [fork change] The project calendar alone when no working weeks are to hand -- the behaviour
  // this function had before working weeks existed.
  val projectOnly = workingDayTest(taskManager.calendar)
  val workingDays = resourceProperties?.let { WorkWeekWorkingDays(taskManager.calendar, it) }
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
            endAfterWorkingDays(newStart, keepDays,
              workingDays?.forTask(task) ?: projectOnly).toLegacyDate()))
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
 * Upper bound of the day search in [endAfterWorkingDays] -- 50 000 calendar days, some 136 years.
 * [fork change]
 *
 * THE SAME NUMBER AS `MAX_SEARCH_DAYS` IN `ResourceLevelling`, and the equality is the point, not
 * a coincidence. This function exists to repeat levelling's own date arithmetic on the write-back
 * side, so that "both sides occupy the same days" (see the doc comment below). A tighter bound
 * here would cut a plan that levelling itself had legitimately placed far out, and the two sides
 * would then disagree about the very days they are supposed to agree on; a wider one buys nothing,
 * because levelling never hands out a date beyond its own bound. Reaching it means not a rounding
 * error but an endless loop.
 *
 * A SEPARATE CONSTANT AND NOT A SHARED ONE, because `MAX_SEARCH_DAYS` is private to its file and
 * making it public would suggest the two bounds must move together. They need not; they only
 * happen to be right at the same value today, and this comment is where that is written down.
 */
private const val MAX_END_SEARCH_DAYS = 50_000

/**
 * The log channel of the levelling write-back. [fork change]
 *
 * THE LOGGER AND NOT `System.err`: GanttProject redirects the error stream, so a line written
 * there is seen by nobody -- the same trap that made the endless loop recorded below invisible in
 * the first place.
 */
private val LOG = GPLogger.create("Fork.Levelling.WriteBack")

/**
 * The end of a Task that begins on [start] and lasts [days] working days.
 *
 * GanttProject keeps the end EXCLUSIVE: the first day after. The same calculation as in
 * levelling, so that both sides occupy the same days.
 *
 * ALL THREE LOOPS ARE BOUNDED [fork change], and this is the whole reason the function looks as busy as it
 * does. An [isWorkingDay] that never says true -- a person whose working week has a section with
 * no day ticked, a broken weekend setting, an empty holiday calendar -- lets every one of them
 * run for ever, and the write-back is called from the levelling menu item: the program would hang
 * with no dialog and no message. The same failure has already been measured on this machine, and
 * `ResourceLevelling.nextWorkingDay` records it: "the test runner was cleared away by the
 * operating system, without a single message". The rule taken from it holds here too -- better a
 * visibly wrong date than a hanging program.
 *
 * THE MIDDLE LOOP IS THE ONE THAT LOOKS SAFE AND IS NOT. It carries a `break`, but the condition
 * it breaks on is `counted`, and `counted` only ever grows on a working day. Without working days
 * the break is unreachable, exactly like the two `while (!isWorkingDay(...))` loops around it.
 *
 * EACH BOUND RETURNS ITS OWN FALLBACK rather than one shared one, because the three loops fail
 * having learned different amounts, and each fallback is the smallest end that is still an end:
 *  - the first loop found no working day at all, so nothing is known beyond [start]: one day.
 *  - the middle loop found some working days but not enough, so the last one it did find is the
 *    best answer available: the day after it.
 *  - the last loop already has the Task's days and only fails to push the exclusive end onto a
 *    working day, so the unpushed end is returned unchanged -- the same choice
 *    `ResourceLevelling.nextWorkingDay` makes when it returns `from`.
 *
 * WHY NOT THE DAY THE SEARCH GAVE UP ON, which would be the more literal answer: it lies 50 000
 * days out, and `mutator.setEnd` of a date 136 years away is precisely the disaster the long
 * comment at the call site records -- Tasks that occupied years instead of days and produced 1204
 * overloaded days at 600 %. A fallback must not be worse than the bug it guards against.
 *
 * VISIBLE INSTEAD OF `private`, AND THAT IS A DELIBERATE WIDENING [fork change]. A bound nobody
 * has watched bite is not a bound, it is a comment; and the only way to make all three of these
 * loops bite is to hand in an `isWorkingDay` that never says true, which no reachable public
 * caller can be talked into doing -- the write-back takes its calendar from the TaskManager.
 * `internal` was tried first and is not enough: `ganttproject-tester` is a Gradle project of its
 * own, hence a Kotlin module of its own, and the compiler answered "Cannot access
 * 'endAfterWorkingDays': it is internal in file". So the choice was between a test that cannot
 * reach the loops and a name in the fork package that one test uses. The wider name is the
 * cheaper of the two: `EndAfterWorkingDaysTest` is the only caller besides the write-back.
 */
fun endAfterWorkingDays(
  start: LocalDate, days: Int, isWorkingDay: (LocalDate) -> Boolean): LocalDate {
  var day = start
  var guard = 0
  while (!isWorkingDay(day)) {
    if (guard++ > MAX_END_SEARCH_DAYS) {
      LOG.error(
        "Levelling write-back: no working day at all within $MAX_END_SEARCH_DAYS days after " +
          "$start, so the end of a Task of $days working days cannot be computed. Falling back " +
          "to a one-day Task ending ${start.plusDays(1)}. The calendar or a working week is " +
          "empty -- the plan is wrong, not just this date.")
      return start.plusDays(1)
    }
    day = day.plusDays(1)
  }
  var counted = 0
  // The last day that actually counted. A working day by construction: the loop above only ends
  // on one, and every later assignment happens under the same test.
  var lastWorking = day
  guard = 0
  while (true) {
    if (isWorkingDay(day)) {
      counted++
      lastWorking = day
    }
    if (counted >= maxOf(days, 1)) {
      break
    }
    if (guard++ > MAX_END_SEARCH_DAYS) {
      LOG.error(
        "Levelling write-back: only $counted of ${maxOf(days, 1)} working days found within " +
          "$MAX_END_SEARCH_DAYS days after $start. Falling back to an end of " +
          "${lastWorking.plusDays(1)}, after the last working day there was. The calendar or a " +
          "working week is empty -- the plan is wrong, not just this date.")
      return lastWorking.plusDays(1)
    }
    day = day.plusDays(1)
  }
  var end = day.plusDays(1)
  guard = 0
  while (!isWorkingDay(end)) {
    if (guard++ > MAX_END_SEARCH_DAYS) {
      LOG.error(
        "Levelling write-back: the end after $start plus ${maxOf(days, 1)} working days cannot " +
          "be moved onto a working day within $MAX_END_SEARCH_DAYS days. Keeping the unmoved " +
          "end ${day.plusDays(1)}. The calendar or a working week is empty -- the plan is " +
          "wrong, not just this date.")
      return day.plusDays(1)
    }
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

/**
 * The working week of a person, as a property of the person. [fork change]
 *
 * WHAT FOR: until now ONE working calendar applied to everybody, `taskManager.calendar`. Whoever
 * works Mon, Tue, Fri, Sat is planned by it as though they worked Mon to Fri -- two days of work
 * that do not exist, and two days off that are not taken. The distinction has to be made per
 * person, and it has to be able to change over time: from 1 March somebody works three days only.
 *
 * STORED AS TEXT, in the format of [WorkWeekSchedule]: `1,2,5,6; 2026-03-01: 1,2,3`. ISO weekday
 * numbers and nothing else -- the same file is opened with `ui.language=de_DE` and with `en_US`,
 * and „Mo,Di,Fr" would be nonsense in the second session.
 *
 * NOTHING ENTERED DOES NOT MEAN „MONDAY TO FRIDAY". It means „no statement -- the project calendar
 * applies", and that is why the query is `Boolean?`. Anything else would change the behaviour of
 * every existing plan the moment somebody swaps the version, without anybody having entered
 * anything. What a fresh entry is PRESET to is a matter for the user interface and does not belong
 * here.
 *
 * This is the storage and nothing else. Nothing in this fork asks the question yet; the place
 * where the answer meets `isWorkingDay` is a separate piece of work.
 */
const val RESOURCE_WORK_WEEK = "work_week"

fun findOrCreateWorkWeek(manager: CustomPropertyManager): CustomPropertyDefinition =
  manager.findEffortDefinition(RESOURCE_WORK_WEEK)
    ?: manager.createDefinition(RESOURCE_WORK_WEEK, CustomPropertyClass.TEXT.iD,
                                forkText("fork.column.workWeek"), null)

/**
 * The working week of this person, together with whatever could not be read.
 *
 * The errors are DELIVERED ALONG and not swallowed, exactly as in
 * [net.sourceforge.ganttproject.task.algorithm.capacitySchedule]: a column display can carry on
 * with the usable remainder, a computation should be able to refuse the work over a typo instead
 * of quietly planning with a week nobody entered.
 */
fun HumanResource.workWeek(manager: CustomPropertyManager): WorkWeekParseResult {
  val def = manager.findEffortDefinition(RESOURCE_WORK_WEEK)
    ?: return WorkWeekParseResult(WorkWeekSchedule(), emptyList())
  return WorkWeekSchedule.parse(this.getCustomField(def)?.toString())
}

/**
 * Does this person work on [day]?
 *
 * `null` means NO STATEMENT: nothing is entered for that day, and the project calendar decides.
 * Neither `false` nor `true` -- see [RESOURCE_WORK_WEEK].
 */
fun HumanResource.worksOn(manager: CustomPropertyManager, day: LocalDate): Boolean? =
  this.workWeek(manager).schedule.worksOn(day)

/** Writes the working week back in the format [WorkWeekSchedule.toString] produces. */
fun HumanResource.setWorkWeek(manager: CustomPropertyManager, schedule: WorkWeekSchedule) {
  this.setValue(findOrCreateWorkWeek(manager), schedule.toString())
}
