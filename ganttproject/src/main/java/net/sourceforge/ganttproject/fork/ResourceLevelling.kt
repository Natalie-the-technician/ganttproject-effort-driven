/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
Kapazitaetsverteilung (levelling). Siehe CLAUDE-NOTES.md, Abschnitt 3, Punkt 2.

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

import java.time.LocalDate

/**
 * Spreads Tasks over time so that one person does not have to deliver more than 100 % at once.
 *
 * WHY THIS IS NEEDED: GanttProject schedules exclusively by dependencies and places every Task as
 * early as those allow. Resources do not occur in the scheduler at all -- `SchedulerImpl` does not
 * mention them a single time. Twenty Tasks of the same person at the same time simply overlap; the
 * resource chart colours it red, nothing is tidied up. The effort-driven duration of this fork
 * changes nothing about that either: it computes every Task on its own.
 *
 * NO GANTTPROJECT TYPES HERE, and that is deliberate. The lesson from stage 1 is in the notes: two
 * bugs hid behind 300 green tests, because the wiring was not checkable. This file computes with
 * `LocalDate` and plain values and can be checked without a running program. The calendar comes in
 * as a function.
 *
 * DECISIONS, taken on 17.08.2026 and recorded here so that they are not read later as
 * assumptions:
 *  - Order on a tie: priority first, then the order in the plan.
 *  - Simultaneous work is allowed as long as the sum of the utilisations does not exceed 100 %.
 *  - Fixed dates stay. If they do not fit, the conflict is REPORTED, not resolved -- moving a
 *    deadline silently hides the problem.
 *  - Milestones are NOT fixed: they carry no date of their own but follow their dependencies.
 *    (Objected afterwards, and the objection is right.)
 */

/** A Task in the form levelling needs it. */
data class LevelTask(
  val id: String,

  /** Order in the plan, from top to bottom. Decides on equal priority. */
  val orderInPlan: Int,

  /**
   * Larger means more important.
   *
   * MIND THE CONVERSION: the priority values GanttProject stores in the project file are NOT
   * ordered by importance -- `LOWEST("3")`, `LOW("0")`, `NORMAL("1")`, `HIGH("2")`,
   * `HIGHEST("4")`. Sorting by that number puts the lowest priority between HIGH and HIGHEST.
   * The right order is the one in the enum (`Priority.ordinal`), and that is what belongs in
   * here.
   */
  val priority: Int,

  /** Duration in working days. Comes from the effort-driven calculation already. */
  val durationDays: Int,

  /** Utilisation of this assignment in per cent. 100 means: the person is fully committed. */
  val loadPercent: Int,

  /** Predecessors, finish-to-start. */
  val predecessors: List<String> = emptyList(),

  /** "Date fixed": the Task stays on this date, even when it gets tight. */
  val fixedStart: LocalDate? = null,

  /** "Earliest begin": not before this date, but later is allowed. */
  val earliestStart: LocalDate? = null,
  /**
   * The people whose capacity this Task occupies.
   *
   * WHY THIS IS NECESSARY: until then levelling had ONE capacity pool. With two people it would
   * have laid their work one after another, as though they could not work at the same time --
   * quietly and looking plausible. That only one person plans in trial use must not be the reason
   * why it looks right.
   *
   * Empty means: nobody is assigned. These Tasks share a common pool -- they occupy time, only
   * one does not know whose.
   */
  val resourceIds: List<String> = emptyList(),
  /**
   * Finished or begun work: stays exactly where it lies.
   *
   * WHY THIS IS NECESSARY: without this field levelling was a ONE-OFF TOOL. On the second run it
   * would have re-laid Tasks already ticked off and rewritten the past. Frozen Tasks keep
   * occupying their capacity -- otherwise levelling would plan begun work twice.
   *
   * The date they lie on is in [fixedStart]: for frozen work today's date is by definition the
   * fixed one. A field of its own would be a second truth about the same matter.
   */
  val frozen: Boolean = false,
  /**
   * Latest finish. Is NOT enforced -- enforcing a deadline only moves the problem to a place
   * where nobody sees it. If it cannot be met, it is reported.
   */
  val deadline: LocalDate? = null
)

sealed interface LevelConflict {
  /**
   * A fixed date lies before the earliest possible one. The date is kept -- what is reported is
   * that it is not cleanly reachable.
   */
  data class FixedDateNotReachable(
    val id: String, val fixedStart: LocalDate, val earliestPossible: LocalDate) : LevelConflict

  /** On this day the sum of the Tasks demands more than 100 %. Arises only from fixed dates. */
  data class Overload(
    val day: LocalDate, val percent: Int, val ids: List<String>,
    /** Whose capacity is exceeded. Empty: the pool of the unassigned Tasks. */
    val resourceId: String = ""
  ) : LevelConflict

  /** The Tasks hang in a cycle. They are not levelled. */
  data class Cycle(val ids: List<String>) : LevelConflict

  /**
   * A deadline cannot be met with the capacity available.
   *
   * @property missingDays this many working days too late. The number is given because "too late"
   * on its own allows no decision: two days are solved differently from four months.
   */
  data class DeadlineMissed(
    val id: String, val deadline: LocalDate, val actualEnd: LocalDate, val missingDays: Int
  ) : LevelConflict
}

data class LevelResult(
  val starts: Map<String, LocalDate>,
  val conflicts: List<LevelConflict>,
  /**
   * The duration the Task was actually laid with.
   *
   * It can DIFFER from [LevelTask.durationDays] as soon as the daily rate is time-dependent: the
   * same effort needs more days in a section of four hours than in one of eight. Whoever writes
   * the dates back has to use THIS duration -- otherwise an end would stand in the plan that does
   * not match the computed occupancy.
   */
  val durations: Map<String, Int> = emptyMap()
)

/**
 * @param projectStart the earliest time at all.
 * @param isWorkingDay the calendar, as a function. That keeps the calculation checkable without
 * the program, and holidays come from GanttProject's own calendar rather than from a second
 * weekend logic.
 */
/**
 * @param capacityOf how much of a working day may be planned for this person. 100 means: every
 * day filled to the brim. A plan that fills every day to 100 % breaks at the first disturbance --
 * this plan reaches to 2063, and there is a disturbance every week in it.
 *
 * A FUNCTION and not a number, because the utilisation belongs to the person: somebody who still
 * has their main job plans differently from somebody working full time. The value affects ONLY
 * the search for a free window; fixed dates and frozen work stay where they are.
 */
/** Upper bound of the window search in working days -- about 200 years. Reaching it means not a
 * rounding error but an endless loop. */
private const val MAX_SEARCH_DAYS = 50_000

fun levelTasks(
  tasks: List<LevelTask>,
  projectStart: LocalDate,
  isWorkingDay: (LocalDate) -> Boolean,
  durationAt: (LevelTask, LocalDate) -> Int = { task, _ -> task.durationDays },
  capacityOf: (String) -> Int = { 100 }
): LevelResult {
  val byId = tasks.associateBy { it.id }
  val conflicts = mutableListOf<LevelConflict>()

  val order = topologicalOrder(tasks)
  if (order == null) {
    return LevelResult(emptyMap(), listOf(LevelConflict.Cycle(tasks.map { it.id })))
  }

  // Occupancy per PERSON and working day, in per cent. Only days on which something lies are in
  // it. The key "" is the pool of the unassigned Tasks.
  val used = mutableMapOf<String, MutableMap<LocalDate, Int>>()
  val starts = mutableMapOf<String, LocalDate>()
  val durations = mutableMapOf<String, Int>()
  val ends = mutableMapOf<String, LocalDate>()

  // Enter the frozen work FIRST, before anything else: it occupies capacity that is no longer
  // available for the rest. If it were processed in the normal order, a movable Task could lay
  // itself on the same day beforehand.
  tasks.filter { it.frozen }.forEach { task ->
    val liegtAuf = nextWorkingDay(task.fixedStart ?: projectStart, isWorkingDay)
    val days = workingDays(liegtAuf, durationAt(task, liegtAuf), isWorkingDay)
    task.pools.forEach { pool ->
      val belegung = used.getOrPut(pool) { mutableMapOf() }
      days.forEach { belegung[it] = (belegung[it] ?: 0) + task.loadPercent }
    }
    starts[task.id] = days.first()
    durations[task.id] = days.size
    ends[task.id] = nextWorkingDay(days.last().plusDays(1), isWorkingDay)
  }

  for (id in order) {
    val task = byId.getValue(id)
    if (task.frozen) {
      continue
    }

    var earliest = maxOf(projectStart, task.earliestStart ?: projectStart)
    for (p in task.predecessors) {
      ends[p]?.let { earliest = maxOf(earliest, it) }
    }
    earliest = nextWorkingDay(earliest, isWorkingDay)

    val days: List<LocalDate>
    if (task.fixedStart != null) {
      // Keep the date, even when it lies too early or bursts the capacity. Both are reported --
      // that was the explicit decision: a deadline is a deadline.
      val start = nextWorkingDay(task.fixedStart, isWorkingDay)
      if (start < earliest) {
        conflicts.add(LevelConflict.FixedDateNotReachable(id, start, earliest))
      }
      days = workingDays(start, durationAt(task, start), isWorkingDay)
    } else {
      days = findEarliestWindow(earliest, task, durationAt, used, isWorkingDay, capacityOf)
    }

    task.pools.forEach { pool ->
      val belegung = used.getOrPut(pool) { mutableMapOf() }
      days.forEach { belegung[it] = (belegung[it] ?: 0) + task.loadPercent }
    }
    starts[id] = days.first()
    durations[id] = days.size
    ends[id] = nextWorkingDay(days.last().plusDays(1), isWorkingDay)
  }

  // Deadlines: reported, not enforced. What is checked is the END, because a deadline is an end date.
  tasks.forEach { task ->
    val frist = task.deadline ?: return@forEach
    val ende = ends[task.id] ?: return@forEach
    // ends[] is the first working day AFTER the Task; the last working day lies before it.
    val letzterTag = generateSequence(ende.minusDays(1)) { it.minusDays(1) }
      .first { isWorkingDay(it) || it < projectStart }
    if (letzterTag.isAfter(frist)) {
      val fehlend = workingDays(nextWorkingDay(frist.plusDays(1), isWorkingDay),
        1, isWorkingDay).let {
        var tage = 0
        var tag = nextWorkingDay(frist.plusDays(1), isWorkingDay)
        while (!tag.isAfter(letzterTag) && tage < 100_000) {
          if (isWorkingDay(tag)) tage++
          tag = tag.plusDays(1)
        }
        tage
      }
      conflicts.add(LevelConflict.DeadlineMissed(task.id, frist, letzterTag, fehlend))
    }
  }

  // After levelling, overload can only remain where fixed dates have forced it.
  used.toSortedMap().forEach { (pool, belegung) ->
    belegung.filterValues { it > capacityOf(pool) }.toSortedMap().forEach { (day, percent) ->
      val onThatDay = tasks.filter { t ->
        if (!t.pools.contains(pool)) return@filter false
        val s = starts[t.id] ?: return@filter false
        workingDays(s, durations[t.id] ?: t.durationDays, isWorkingDay).contains(day)
      }.map { it.id }
      conflicts.add(LevelConflict.Overload(day, percent, onThatDay, pool))
    }
  }

  return LevelResult(starts, conflicts, durations)
}

/** The capacity pools this Task occupies. Without an assignment the common pool "". */
internal val LevelTask.pools: List<String>
  get() = if (resourceIds.isEmpty()) listOf("") else resourceIds

/**
 * Processing order: only Tasks whose predecessors already lie, and among those the most
 * important one, on a tie the one higher up in the plan.
 *
 * @return null when the dependencies run in a cycle.
 */
private fun topologicalOrder(tasks: List<LevelTask>): List<String>? {
  val open = tasks.associateBy { it.id }.toMutableMap()
  val placed = mutableSetOf<String>()
  val result = mutableListOf<String>()
  while (open.isNotEmpty()) {
    val ready = open.values
      .filter { t -> t.predecessors.all { it in placed || it !in open } }
      // Most important first; on a tie the one higher up in the plan.
      .sortedWith(compareByDescending<LevelTask> { it.priority }.thenBy { it.orderInPlan })
    val next = ready.firstOrNull() ?: return null
    result.add(next.id)
    placed.add(next.id)
    open.remove(next.id)
  }
  return result
}

/**
 * The next working day from [from] on, inclusive.
 *
 * WITH A BOUND, and it is not ornament: a calendar without a single working day -- through an
 * error in the weekend settings or a broken holiday list -- otherwise lets this loop run for
 * ever. Happened on the machine: the test runner was cleared away by the operating system,
 * without a single message. An endless loop is the most expensive failure mode, because nothing
 * about it is visible; better a visibly wrong date than a hanging program.
 */
private fun nextWorkingDay(from: LocalDate, isWorkingDay: (LocalDate) -> Boolean): LocalDate {
  var d = from
  var schutz = 0
  while (!isWorkingDay(d)) {
    if (schutz++ > MAX_SEARCH_DAYS) {
      return from
    }
    d = d.plusDays(1)
  }
  return d
}

/** The [count] working days from [start] on, inclusive. */
private fun workingDays(
  start: LocalDate, count: Int, isWorkingDay: (LocalDate) -> Boolean): List<LocalDate> {
  val days = mutableListOf<LocalDate>()
  var d = nextWorkingDay(start, isWorkingDay)
  var schutz = 0
  while (days.size < maxOf(count, 1)) {
    // The same bound as above, for the same reason. Without working days in the calendar this
    // loop would never finish -- and silently at that.
    if (schutz++ > MAX_SEARCH_DAYS) {
      return if (days.isEmpty()) listOf(start) else days
    }
    if (isWorkingDay(d)) {
      days.add(d)
    }
    if (days.size < maxOf(count, 1)) {
      d = d.plusDays(1)
    }
  }
  return days
}

/**
 * The earliest window from [earliest] on in which the Task has room throughout.
 *
 * It is NOT broken into pieces: a Task runs on consecutive working days. An interruption would be
 * packed more densely, but a plan in which one job appears three times for two days each is no
 * longer readable -- and staying readable is the point of the exercise.
 */
private fun findEarliestWindow(
  earliest: LocalDate,
  task: LevelTask,
  durationAt: (LevelTask, LocalDate) -> Int,
  used: Map<String, MutableMap<LocalDate, Int>>,
  isWorkingDay: (LocalDate) -> Boolean,
  capacityOf: (String) -> Int
): List<LocalDate> {
  val loadPercent = task.loadPercent
  var candidate = nextWorkingDay(earliest, isWorkingDay)
  var schutz = 0
  while (true) {
    // PREVENT AN ENDLESS LOOP. MEASURED ON THE MACHINE: at a utilisation of 80 % and a Task with
    // 100 % load the condition "fits here" was false on EVERY day -- even on completely empty
    // ones. The search ran on without limit, levelling never came back, and on screen it looked
    // as though the menu item did nothing.
    //
    // The bound is the second safeguard; the first is the limit below, which always lets a Task
    // fit on its own. Both together, because an endless loop is the most expensive failure mode:
    // no dialog, no message, only a program that hangs.
    if (schutz++ > MAX_SEARCH_DAYS) {
      return workingDays(nextWorkingDay(earliest, isWorkingDay), durationAt(task, earliest),
        isWorkingDay)
    }
    // The duration depends on the starting day as soon as the daily rate is time-dependent -- it
    // therefore has to be asked anew FOR EVERY CANDIDATE, not once in advance.
    val window = workingDays(candidate, durationAt(task, candidate), isWorkingDay)
    // A day blocks as soon as it is too full for ONE of the people involved.
    val blockedAt = window.firstOrNull { day ->
      task.pools.any { pool ->
        // THE LIMIT IS AT LEAST THE TASK'S OWN LOAD. A Task that on its own demands more than
        // the utilisation allows (100 % load at 80 % utilisation) otherwise fits NOWHERE -- and
        // the search never finds a window. The utilisation limits how much OTHER work fits
        // alongside; it cannot forbid a single Task.
        val grenze = maxOf(capacityOf(pool), loadPercent)
        (used[pool]?.get(day) ?: 0) + loadPercent > grenze
      }
    }
    if (blockedAt == null) {
      return window
    }
    // Continue searching only after the blocking day: everything before it fails for the same reason.
    candidate = nextWorkingDay(blockedAt.plusDays(1), isWorkingDay)
  }
}
