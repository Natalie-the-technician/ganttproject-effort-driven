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


  /** Predecessors, finish-to-start. */
  val predecessors: List<String> = emptyList(),

  /** "Date fixed": the Task stays on this date, even when it gets tight. */
  val fixedStart: LocalDate? = null,

  /** "Earliest begin": not before this date, but later is allowed. */
  val earliestStart: LocalDate? = null,
  /**
   * How much of each person's working day this Task claims, in per cent, PER PERSON.
   *
   * WHY A MAP AND NOT ONE NUMBER: until then levelling had ONE capacity pool. With two people it
   * would have laid their work one after another, as though they could not work at the same time
   * -- quietly and looking plausible. That only one person plans in trial use must not be the
   * reason why it looks right.
   *
   * WHY PER PERSON AND NOT ONE NUMBER PLUS A LIST OF PEOPLE, which is what stood here before: the
   * one number was the SUM of all assignment loads, and it was entered at full value into EVERY
   * pool. Two people at 50 % each therefore booked 100 % against each of them instead of 50 %.
   * Measured: a Task was pushed a day to the right although the person still had room -- silently,
   * with no conflict reported -- and with fixed dates an overload of 150 % was reported where the
   * true figure was 100 %. Both are pinned down in `LevellingWriteBackTest`.
   *
   * Empty means: nobody is assigned. Such Tasks share the common pool [SHARED_POOL] -- they
   * occupy time, one just does not know whose -- and claim [SHARED_POOL_LOAD] of it.
   */
  val loads: Map<String, Int> = emptyMap(),
  /**
   * The people whose ABSENCE moves this Task -- axis A of an assignment.
   *
   * WHAT IT MEANS: the Task may only lie where EVERY person named here is at work. Several of
   * them therefore give the INTERSECTION of their available time, not the union: whoever has to
   * be there has to be there, and one person missing is enough.
   *
   * A SET OF ITS OWN AND NOT A FLAG INSIDE [loads], although both are about the same people.
   * Blocking and occupying are two different questions, and the case that makes this fork worth
   * building answers them differently: somebody who has to be present but does not work on the
   * Task -- supervision, an instruction, an acceptance -- blocks at a load of 0. Hanging the
   * marking on the load would tie the one to the other and lose exactly that person. The two
   * axes are independent in the model (see `ResourceAssignment.setBlocking`), and they stay
   * independent here.
   *
   * NOT NECESSARILY A SUBSET OF [loads]'s keys, even though the conversion in
   * `LevellingAdapter.kt` only ever produces names that are in there too. Nothing in the
   * calculation needs the two to agree: a name in here is asked about availability, a name in
   * [loads] is booked capacity, and a name in only one of them is answerable either way.
   *
   * EMPTY IS THE DEFAULT, and the direction is chosen: an empty set means "nobody blocks", which
   * is what the program did before this stage and what an unmarked assignment means. A Task that
   * nobody blocks is laid exactly as it was laid before -- `ResourceLevellingTest` and
   * `LevellingDaysOffTest` pin that down without a single expectation of theirs being touched.
   */
  val blocking: Set<String> = emptySet(),
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

  /**
   * There is no stretch of time in which EVERY person marked as blocking is at work for as long
   * as the Task lasts. The Task is laid at its earliest possible date all the same -- that date is
   * wrong, and this is what says so.
   *
   * WHY THIS EXISTS AS A KIND OF ITS OWN, and it is a decision rather than a piece of building
   * work. The case was measured and left silent when axis A was built: the window search runs into
   * [MAX_SEARCH_DAYS] and falls back, and nobody learns why the Task sits where it sits. Unlike
   * every other conflict here there is nothing to weigh up afterwards -- "everybody has to be
   * there" cannot be relaxed without saying the opposite of what was marked in the plan. So the
   * only useful answer is to name the Task and the people and let a person decide which marking or
   * which day off is the one to change.
   *
   * IT REPORTS, IT DOES NOT RESOLVE. The Task lies exactly where it lay before this conflict
   * existed -- pinned by `testAnImpossibleIntersectionEndsInsteadOfHanging`, which was written one
   * stage earlier, still asserts the same date and was not touched.
   *
   * THE CAPACITY FALLBACK BESIDE IT STAYS SILENT, deliberately and untouched: that is a second
   * decision and it has not been taken. The two share the bound in [findEarliestWindow] but not
   * this message -- see `absenceBlocked` there for the joint that keeps them apart.
   *
   * @property blocking the people whose joint presence the Task demands, ALL of them and not only
   * those seen to be away. The statement being made is "no day satisfies this set", and that is a
   * statement about the whole set: with `P` and `Q` never overlapping and `R` always there, `R` is
   * not the culprit but is part of what could not be satisfied, and dropping them would hide one
   * of the two markings a person may want to change. Sorted, so the same plan always yields the
   * same message.
   */
  data class BlockingIntersectionEmpty(
    val id: String, val blocking: List<String>
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
/**
 * @param isAvailable whether this person is at work on this day -- `false` means a day off:
 * holiday, an illness recorded, a training course. The person is named by the same string the
 * capacity pools are named after, that is by [LevelTask.loads]'s key, and that is the resource id
 * from the model.
 *
 * ASKED ONLY ABOUT THE PEOPLE IN [LevelTask.blocking], and only in the search for a free window.
 * That is the whole of axis A: a day on which one of them is away is as unusable for this Task as
 * a day that is full. For everybody else the day off keeps doing what it did before -- it takes
 * that person's hours out of the day and leaves the Task where it is (see `DaysOffDuration.kt`).
 *
 * P0 laid this channel and hung nothing on it; this is the stage that hangs the rule on it. What
 * did not change is the answer for an unmarked plan: with [LevelTask.blocking] empty this
 * function is never asked, and `LevellingDaysOffTest` and `ResourceLevellingTest` still pin the
 * result to be the same either way -- with "absent for everybody on every day" as the input.
 *
 * FIXED DATES AND FROZEN WORK ARE NOT ASKED, deliberately and for the reason already recorded
 * above: a fixed date is kept even when it does not fit, and begun work is the past. Axis A can
 * only act where levelling is allowed to choose, and that is the window search.
 *
 * A FUNCTION AND NOT A LIST OF DATES, for the same reason as [isWorkingDay] beside it: this file
 * deliberately knows no GanttProject types, so that the calculation stays checkable without a
 * running program. Where the answer comes from is the conversion's business.
 *
 * THE DEFAULT SAYS "AVAILABLE", not "absent", and the direction is chosen and not accidental. A
 * channel left unwired must not silently declare everybody absent -- an unfilled parameter has to
 * mean the state of things as they were.
 */
/** Upper bound of the window search in working days -- about 200 years. Reaching it means not a
 * rounding error but an endless loop. */
private const val MAX_SEARCH_DAYS = 50_000

fun levelTasks(
  tasks: List<LevelTask>,
  projectStart: LocalDate,
  isWorkingDay: (LocalDate) -> Boolean,
  durationAt: (LevelTask, LocalDate) -> Int = { task, _ -> task.durationDays },
  capacityOf: (String) -> Int = { 100 },
  isAvailable: (String, LocalDate) -> Boolean = { _, _ -> true }
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
      days.forEach { belegung[it] = (belegung[it] ?: 0) + task.loadIn(pool) }
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
      val search = findEarliestWindow(earliest, task, durationAt, used, isWorkingDay, capacityOf,
        isAvailable)
      days = search.days
      // THE ONE PLACE THE SILENT FALLBACK BECOMES A MESSAGE. Both halves of the condition are
      // needed and neither is decoration:
      //
      //  - `exhausted` alone would also catch the CAPACITY fallback beside it, and that one is to
      //    stay as silent as it is today. Reporting it would be a second decision, and it has not
      //    been taken.
      //  - `absenceBlocked` alone would fire on every plan in which a marked person is away for a
      //    day, which is the ordinary case axis A was built for and no conflict at all.
      //
      // Together they say exactly one thing: the search gave up, AND an absence was among the
      // reasons it kept failing. In a plan with no marking `absenceBlocked` cannot become true --
      // the loop over `task.blocking` runs zero times -- so nothing about the state before this
      // stage can reach this line.
      if (search.exhausted && search.absenceBlocked) {
        conflicts.add(LevelConflict.BlockingIntersectionEmpty(id, task.blocking.sorted()))
      }
    }

    task.pools.forEach { pool ->
      val belegung = used.getOrPut(pool) { mutableMapOf() }
      days.forEach { belegung[it] = (belegung[it] ?: 0) + task.loadIn(pool) }
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

/** The pool shared by all Tasks that have nobody assigned. */
const val SHARED_POOL = ""

/** What a Task with no assignment at all claims of [SHARED_POOL]. */
const val SHARED_POOL_LOAD = 100

/** The capacity pools this Task occupies. Without an assignment the common pool [SHARED_POOL]. */
internal val LevelTask.pools: List<String>
  get() = if (loads.isEmpty()) listOf(SHARED_POOL) else loads.keys.toList()

/**
 * How much of [pool]'s working day this Task claims.
 *
 * The fallback is reached in exactly one situation -- an empty [LevelTask.loads], where [pools]
 * hands out [SHARED_POOL] and nothing is stored for it. For a non-empty map [pools] returns its
 * own keys, so the lookup always hits.
 */
internal fun LevelTask.loadIn(pool: String): Int = loads[pool] ?: SHARED_POOL_LOAD

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
 * What the window search found, and how it ended.
 *
 * A TYPE OF ITS OWN INSTEAD OF THE BARE LIST OF DAYS that stood here before, and the reason is
 * that the two ways of ending look identical from the outside: a Task laid in a window it fits
 * and a Task laid at its earliest date because no window exists carry the same kind of answer, a
 * list of dates. The caller could not tell them apart, which is precisely why the impossible case
 * used to pass unnoticed.
 *
 * [days] IS UNCHANGED BY THIS, in both cases and by construction -- the two return statements
 * below compute exactly what the two return statements before them computed. This type adds
 * knowledge about the answer, not a different answer.
 */
private data class WindowSearch(
  /** The days the Task is to be laid on. */
  val days: List<LocalDate>,
  /**
   * The search ran into [MAX_SEARCH_DAYS] instead of finding room, and [days] is the fallback:
   * the Task at its earliest possible date.
   */
  val exhausted: Boolean,
  /**
   * At least one candidate day failed because a person marked as blocking was away on it.
   *
   * ALWAYS FALSE FOR A PLAN WITHOUT MARKINGS, and that is what keeps the capacity fallback exactly
   * as silent as it was: with [LevelTask.blocking] empty the loop that could set this runs zero
   * times.
   */
  val absenceBlocked: Boolean
)

/**
 * The earliest window from [earliest] on in which the Task has room throughout.
 *
 * It is NOT broken into pieces: a Task runs on consecutive working days. An interruption would be
 * packed more densely, but a plan in which one job appears three times for two days each is no
 * longer readable -- and staying readable is the point of the exercise.
 *
 * TWO REASONS A DAY CAN FAIL, and they are asked side by side: it is too full for somebody, or a
 * person marked as blocking is away on it. The second is axis A. Both are properties OF THE DAY,
 * which is what lets the search skip forward the way it does below.
 */
private fun findEarliestWindow(
  earliest: LocalDate,
  task: LevelTask,
  durationAt: (LevelTask, LocalDate) -> Int,
  used: Map<String, MutableMap<LocalDate, Int>>,
  isWorkingDay: (LocalDate) -> Boolean,
  capacityOf: (String) -> Int,
  isAvailable: (String, LocalDate) -> Boolean
): WindowSearch {
  var candidate = nextWorkingDay(earliest, isWorkingDay)
  var schutz = 0
  // Whether an absence was ever the reason a day was rejected. Read only when the search gives up
  // below; it is what tells the two ways of giving up apart. See [WindowSearch.absenceBlocked].
  var absenceBlocked = false
  while (true) {
    // PREVENT AN ENDLESS LOOP. MEASURED ON THE MACHINE: at a utilisation of 80 % and a Task with
    // 100 % load the condition "fits here" was false on EVERY day -- even on completely empty
    // ones. The search ran on without limit, levelling never came back, and on screen it looked
    // as though the menu item did nothing.
    //
    // The bound is the second safeguard; the first is the limit below, which always lets a Task
    // fit on its own. Both together, because an endless loop is the most expensive failure mode:
    // no dialog, no message, only a program that hangs.
    //
    // AXIS A ADDS A SECOND WAY TO NEVER FIND A WINDOW, and this bound is what catches it: several
    // blocking people whose available times never overlap for long enough leave no day for the
    // Task at all. Unlike the capacity case there is no limit that could rescue it -- "everybody
    // has to be there" cannot be relaxed without saying the opposite of what was marked. So the
    // search runs into the bound and the Task is laid at its earliest possible date, blocking
    // people or no.
    //
    // THAT FALLBACK IS STILL THE SAME ONE AS FOR THE CAPACITY CASE -- the same date, the same
    // days, not a line of it changed. What has changed since is that it is no longer silent: the
    // open question recorded here has been decided, and the caller turns an exhausted search with
    // `absenceBlocked` into `LevelConflict.BlockingIntersectionEmpty`. The CAPACITY half of this
    // same fallback stays silent, and stays silent on purpose -- see the caller.
    if (schutz++ > MAX_SEARCH_DAYS) {
      return WindowSearch(
        workingDays(nextWorkingDay(earliest, isWorkingDay), durationAt(task, earliest),
          isWorkingDay),
        exhausted = true, absenceBlocked = absenceBlocked)
    }
    // The duration depends on the starting day as soon as the daily rate is time-dependent -- it
    // therefore has to be asked anew FOR EVERY CANDIDATE, not once in advance.
    val window = workingDays(candidate, durationAt(task, candidate), isWorkingDay)
    // A day blocks as soon as it is too full for ONE of the people involved -- or as soon as ONE
    // of the people marked as blocking is away on it.
    val blockedAt = window.firstOrNull { day ->
      // AXIS A, and "any absent" is the same statement as "all present must be present": several
      // blocking people give the INTERSECTION of their time, not the union. Asked first because
      // it is the cheaper question and, in an unmarked plan, an empty loop.
      //
      // The answer is remembered because a day rejected for this reason is the only thing that
      // can turn the fallback above into a message. One boolean, no allocation, and false for
      // ever in a plan with no marking.
      val absent = task.blocking.any { !isAvailable(it, day) }
      if (absent) {
        absenceBlocked = true
      }
      absent ||
      task.pools.any { pool ->
        // THE DEMAND IS THE ONE ON THIS PERSON, not the sum over everybody on the Task. Asking
        // the sum here was the defect: two people at 50 % each blocked a day on which each of
        // them was only half committed.
        val gefordert = task.loadIn(pool)
        // THE LIMIT IS AT LEAST THE TASK'S OWN LOAD. A Task that on its own demands more than
        // the utilisation allows (100 % load at 80 % utilisation) otherwise fits NOWHERE -- and
        // the search never finds a window. The utilisation limits how much OTHER work fits
        // alongside; it cannot forbid a single Task.
        val grenze = maxOf(capacityOf(pool), gefordert)
        (used[pool]?.get(day) ?: 0) + gefordert > grenze
      }
    }
    if (blockedAt == null) {
      return WindowSearch(window, exhausted = false, absenceBlocked = absenceBlocked)
    }
    // Continue searching only after the blocking day: everything before it fails for the same reason.
    candidate = nextWorkingDay(blockedAt.plusDays(1), isWorkingDay)
  }
}
