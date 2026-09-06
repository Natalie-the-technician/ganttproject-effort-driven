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
   * SINCE 06.09.2026 THIS SET ALSO DECIDES WHICH DAYS [loads] IS BOOKED ON, and that is worth
   * saying plainly next to the sentence above rather than leaving it to be discovered. The two
   * sets still say two different things -- WHO has to be there against WHOSE day is spent -- and
   * neither has become a subset of the other. What has changed is that the answer to the first
   * question now gates the second: on a day this set is not satisfied, nothing in [loads] is
   * booked at all, not even for people who are there. See [contributingDays] for why, and
   * `LevellingBlockedDayCapacityTest` for the case that makes it matter -- a person who has to be
   * present at a load of 0, whose absence frees the day for everybody else.
   *
   * EMPTY IS THE DEFAULT, and the direction is chosen: an empty set means "nobody blocks", which
   * is what the program did before this stage and what an unmarked assignment means. A Task that
   * nobody blocks is laid exactly as it was laid before -- `ResourceLevellingTest` and
   * `LevellingDaysOffTest` pin that down without a single expectation of theirs being touched.
   */
  val blocking: Set<String> = emptySet(),
  /**
   * [fork change] B3: does this Task need somebody ON THE PREMISES?
   *
   * CONDITION (a) OF THE HOME-WORK RULE, and the reason it has to be a field of the Task rather
   * than something the availability channel could answer. The rule has two conditions -- this Task
   * needs somebody here, AND one of [blocking] is at home that day -- and `isAvailable` has no
   * Task in its signature at all. It could express the second and never the first.
   *
   * ASKED OF [blocking] AND OF NO OTHER SET. Natalie's sentence hangs on „zwingend notwendig": the
   * person who has to be there. Somebody merely assigned to the Task who works from home is not
   * what stops it; they simply do not deliver their hours to it, which is a statement about the
   * DURATION and is made in `DaysOffDuration.kt`, not here.
   *
   * DEFAULT `false`, for the same reason [blocking] defaults to empty: every Task in every plan
   * written before this fork is in this state, and in it the new half of the window search is
   * never evaluated. Together with the default of `isAtWorkplace` in [levelTasks] that is two
   * independent reasons why nothing changes for a plan that says nothing.
   */
  val requiresPresence: Boolean = false,
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

  /**
   * On this day the sum of the Tasks demands more than 100 %. Arises only from fixed dates.
   *
   * [fork change] A TASK THAT CANNOT PROCEED ON THE DAY IS IN NEITHER [percent] NOR [ids], since
   * 06.09.2026. On a day on which somebody indispensable is missing nobody works on that Task, so
   * nobody is overloaded by it -- measured on 05.09.2026, where of four reported overloads on one
   * plan exactly the one on the blocked day was the one that was not true. What that measurement
   * also showed is that the three remaining ones are untouched: this removes a wrong sentence, it
   * does not make the report quieter.
   */
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
   * The window search found no date for this Task at all, and [id] therefore sits on its earliest
   * possible date rather than on a date the calculation chose. That date is wrong, and this is
   * what says so -- together with EVERY reason the search had for giving up.
   *
   * ONE REPORT PER TASK, CARRYING BOTH REASONS, and that is the decision of 27.08.2026. Two kinds
   * stood here before it, `BlockingIntersectionEmpty` (P5, the impossible intersection) and
   * `NoDayWithCapacity` (P6, the capacity dead end), and a Task that hit both appeared in BOTH --
   * twice in the preview, saying two things about ONE date. The measurement behind the merge is
   * P6's: over a grid of 80 constructed plans 48 searches gave up, 30 on absence alone, 18 on
   * both, and NOT ONE on capacity alone. The frequent case was therefore exactly the one the
   * split served worst.
   *
   * WHY THE TWO REASONS ARE TWO FIELDS AND NOT A KIND EACH. They are not alternatives: relieving
   * EITHER of them can be enough, and in all 18 measured double cases removing the booking alone
   * made the plan layable without touching a single marking. A reader who is shown one reason
   * cannot know that; a reader shown both can pick the cheaper way out. Keeping them apart inside
   * one report is what lets the text name a remedy PER REASON -- which is the other thing the
   * split could not do, because P5's remedy sentence sat in a heading that also stood over Tasks
   * whose real way out was capacity.
   *
   * IT REPORTS, IT DOES NOT RESOLVE. The Task lies exactly where it lay before any of these three
   * kinds existed -- pinned from both sides by `testAnImpossibleIntersectionEndsInsteadOfHanging`
   * and `testTheCapacityReportDoesNotMoveTheTask`, both of which hold written-out dates, and
   * neither of which was touched when the two kinds were merged into this one.
   *
   * AT LEAST ONE OF THE TWO LISTS IS NON-EMPTY. An exhausted search with neither reason recorded
   * produces no conflict at all, exactly as it produced none before -- see [findEarliestWindow]'s
   * caller. Which list is filled is what the two old kinds used to encode in their type.
   *
   * @property blocking the people whose joint presence the Task demands, ALL of them and not only
   * those seen to be away, and EMPTY when no absence was among the reasons. The statement being
   * made is "no day satisfies this set", and that is a statement about the whole set: with `P` and
   * `Q` never overlapping and `R` always there, `R` is not the culprit but is part of what could
   * not be satisfied, and dropping them would hide one of the two markings a person may want to
   * change. Sorted, so the same plan always yields the same message.
   *
   * @property fullFor the people whose booked-up days the search bounced off -- ONLY those, not
   * every person the Task claims capacity from, and EMPTY when no full day was among the reasons.
   * This differs from [blocking] beside it on purpose, and the difference follows from what the
   * two statements are. "No day satisfies this SET" is a statement about the whole set of blocking
   * people, so all of them are named there. "This day was full" is a statement about ONE person on
   * ONE day, so naming a person the search never bounced off would put a name in front of somebody
   * who has nothing to change. Sorted, for the same reason. [SHARED_POOL] can be in here: it is
   * the pool of the Tasks nobody is assigned to, and the text names it as such.
   */
  data class NoPossibleDate(
    val id: String, val blocking: List<String>, val fullFor: List<String>,
    /**
     * [fork change] B3, and this is decision E2 of 04.09.2026: WHY the people in [blocking] were
     * in the way -- because they were away, because they were working from home, or both.
     *
     * A MARK ON THE EXISTING FIELD AND NOT A THIRD LIST BESIDE IT, and the reasoning is the one
     * written above [fullFor], carried one step further rather than stepped over. [blocking] is a
     * list because „no day satisfies this SET" is a statement about the whole marked set, which
     * the Task carries already. [fullFor] is a list because „this day was full" is about ONE
     * person on ONE day and is not derivable afterwards. „Away or at home" is a statement of the
     * SAME shape as the first: it is about the same set of people, and the set does not change
     * with the reason. A third list would therefore repeat the names of the second, and a reader
     * would have to work out that the two lists are one.
     *
     * A SET AND NOT AN ENUM, because both can be true at once: one candidate day rejected for an
     * absence and another for a home-office day is one Task with two reasons, and dropping either
     * would point somebody at the wrong thing to change.
     *
     * EMPTY EXACTLY WHEN [blocking] IS EMPTY. The two are filled from the same condition, so a
     * non-empty [blocking] with no reason would be a message that names people and says nothing
     * about them.
     */
    val blockingReasons: Set<AbsenceKind> = emptySet()
  ) : LevelConflict
}

/**
 * [fork change] B3: the two ways a person marked as blocking can be in the way of a candidate day.
 *
 * THEY ARE NOT THE SAME THING AND THE MESSAGE MUST NOT SAY THEY ARE. „These people were away" put
 * in front of somebody who was at their desk at home is not a rounding of the truth, it is the
 * wrong sentence: what has to change is the marking of the TASK, not anybody's holidays. That is
 * the whole reason this type exists rather than one boolean.
 */
enum class AbsenceKind {
  /** A day off: the person is not working. [LevelTask.blocking] alone decides this. */
  AWAY,

  /**
   * The person is working, from home, and this Task needs somebody on the premises. BOTH
   * conditions -- [LevelTask.requiresPresence] and the person's home office -- or this is not it.
   */
  AT_HOME
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
 *
 * [fork change] PER TASK, and that is a change of 04.09.2026. It used to be one
 * `(LocalDate) -> Boolean` for the whole plan. That was right as long as there was one answer --
 * and it stopped being right when a person could enter a working week of their own: somebody who
 * works Monday to Saturday has a different set of days from the project calendar, and the
 * duration was already being computed on THEIR set (`LevellingAdapter.durationAtStart`) while the
 * window search still laid those days out on the project's. For a task of six of Natalie's days
 * from a Monday that meant the levelling booked her capacity on the following Monday, which she
 * does not work on the task, and left the Saturday free, which she does. Two of six days wrong,
 * and one calendar day of error per Saturday a task runs over. Measured before the change in
 * `WorkWeekLevellingWindowTest`, which now demands the two agree instead of writing both down.
 *
 * THE SHAPE IS THE ONE [durationAt] ALREADY HAD, deliberately: how long a task is and which days
 * it may lie on are the two questions that have to be answered on the SAME grid -- that is the
 * whole of what went wrong -- so they take the same pair of arguments and can be read side by
 * side at every call site.
 *
 * THE OTHER SHAPE, a field on [LevelTask] carrying its own test, was considered and dropped. Two
 * reasons, and the second is the load-bearing one. [LevelTask] is a value: the premise of this
 * file is that it computes with `LocalDate` and plain values, and the test corpus treats it that
 * way -- `copy` appears twenty-odd times in `ResourceLevellingTest`. A lambda in it would make
 * `copy` and `toString` be about a function object. More importantly, the test has to be REMEMBERED
 * across the whole run, and who owns that memory is the caller's business; a field would be filled
 * during the conversion, in `collectLevelTasks`, before anything can decide how long it lives.
 *
 * ONE ANSWER PER TASK AND DAY, NOT ONE COMPUTATION PER QUESTION. This is asked in the tightest
 * loops there are: `findEarliestWindow` walks a whole window for every candidate day, up to
 * `MAX_SEARCH_DAYS` of them. Whoever supplies this function has to build the per-task test ONCE
 * per task and remember its answers -- `LevellingAdapter.workingDaysPerTask` does both, and the
 * reasoning is written down there. A caller that reads the model on every question turns
 * levelling into a waiting game; measured on 04.09.2026, a caller that reads the CALENDAR on every
 * question costs the same run seven times over.
 *
 * FOR A PLAN WITH ONE CALENDAR use [oneGridForAllTasks], which says so at the call site.
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
 * ASKED ONLY ABOUT THE PEOPLE IN [LevelTask.blocking], and in two places: the search for a free
 * window, which will not LAY a Task on such a day, and the booking, which will not BOOK one --
 * see [LevelTask.contributingDays]. That is the whole of axis A: a day on which one of them is
 * away is as unusable for this Task as a day that is full, and it takes nobody's capacity. For
 * everybody else the day off keeps doing what it did before -- it takes that person's hours out
 * of the day and leaves the Task where it is (see `DaysOffDuration.kt`).
 *
 * P0 laid this channel and hung nothing on it; this is the stage that hangs the rule on it. What
 * did not change is the answer for an unmarked plan: with [LevelTask.blocking] empty this
 * function is never asked, and `LevellingDaysOffTest` and `ResourceLevellingTest` still pin the
 * result to be the same either way -- with "absent for everybody on every day" as the input.
 *
 * FIXED DATES AND FROZEN WORK ARE NOT ASKED ABOUT WHERE THEY LIE, deliberately and for the
 * reason already recorded above: a fixed date is kept even when it does not fit, and begun work
 * is the past. Axis A can move a Task only where levelling is allowed to choose, and that is the
 * window search.
 *
 * THEY ARE ASKED ABOUT WHAT THEY OCCUPY, and that is the change of 06.09.2026. The sentence above
 * used to end „and that is the window search", full stop, which made this channel a question
 * about placement alone. It is now also a question about capacity: a Task pinned to a date it
 * cannot work on keeps the date and keeps its length, but stops holding everybody else's day.
 * That is where the loss was -- for a movable Task the search had already stepped past the day,
 * so there was nothing to release; the whole of the measured damage sat on the Tasks that may not
 * move (`2026-09-05-freigabe-gemessen.md`, section 2).
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
  isWorkingDay: (LevelTask, LocalDate) -> Boolean,
  durationAt: (LevelTask, LocalDate) -> Int = { task, _ -> task.durationDays },
  capacityOf: (String) -> Int = { 100 },
  isAvailable: (String, LocalDate) -> Boolean = { _, _ -> true },
  isAtWorkplace: (String, LocalDate) -> Boolean = { _, _ -> true }
): LevelResult {
  val byId = tasks.associateBy { it.id }
  val conflicts = mutableListOf<LevelConflict>()

  // [fork change] EACH TASK'S OWN GRID, BOUND ONCE, and this is the whole of what the per-task
  // test costs inside this file: one closure per task and per run, built here instead of at every
  // one of the tens of thousands of questions below.
  //
  // BOUND UP FRONT AND NOT WHERE IT IS USED, because two of the places that need it are loops
  // over all tasks -- the deadline check and the overload report -- and building a closure inside
  // them would allocate one per task per overloaded day. The rest of this file therefore keeps
  // taking a plain `(LocalDate) -> Boolean`: `nextWorkingDay`, `workingDays` and
  // `findEarliestWindow` are unchanged, they are simply handed the grid of the task they are
  // working on.
  //
  // `getValue` is safe for the same reason `byId` is: every key comes out of `tasks` itself.
  val gridOf: Map<String, (LocalDate) -> Boolean> =
    tasks.associate { task -> task.id to { day: LocalDate -> isWorkingDay(task, day) } }

  val order = topologicalOrder(tasks)
  if (order == null) {
    return LevelResult(emptyMap(), listOf(LevelConflict.Cycle(tasks.map { it.id })))
  }

  // Occupancy per PERSON and working day, in per cent. Only days on which something lies are in
  // it. The key "" is the pool of the unassigned Tasks.
  //
  // [fork change] AND ONLY THE DAYS A TASK REALLY CONTRIBUTES ON. A day inside a Task's window on
  // which somebody indispensable is missing is NOT entered here for that Task -- it is a hole in
  // the Task, not a claim on anybody. See [LevelTask.contributingDays]; both bookings below go
  // through it, and so does the overload report at the end, which reads this map.
  val used = mutableMapOf<String, MutableMap<LocalDate, Int>>()
  val starts = mutableMapOf<String, LocalDate>()
  val durations = mutableMapOf<String, Int>()
  val ends = mutableMapOf<String, LocalDate>()

  // Enter the frozen work FIRST, before anything else: it occupies capacity that is no longer
  // available for the rest. If it were processed in the normal order, a movable Task could lay
  // itself on the same day beforehand.
  tasks.filter { it.frozen }.forEach { task ->
    val grid = gridOf.getValue(task.id)
    val liegtAuf = nextWorkingDay(task.fixedStart ?: projectStart, grid)
    val days = workingDays(liegtAuf, durationAt(task, liegtAuf), grid)
    // [fork change] NO CONTRIBUTION, NO OCCUPANCY -- the release of 06.09.2026, and it is the
    // same line as the one at the movable booking below. Frozen work is booked here, and it needs
    // the rule for the same reason: begun work laid across a day on which nobody could work must
    // not hold that day against everybody else either.
    val beitragend = task.contributingDays(days, isAvailable, isAtWorkplace)
    task.pools.forEach { pool ->
      val belegung = used.getOrPut(pool) { mutableMapOf() }
      beitragend.forEach { belegung[it] = (belegung[it] ?: 0) + task.loadIn(pool) }
    }
    starts[task.id] = days.first()
    durations[task.id] = days.size
    ends[task.id] = nextWorkingDay(days.last().plusDays(1), grid)
  }

  for (id in order) {
    val task = byId.getValue(id)
    if (task.frozen) {
      continue
    }

    // [fork change] THE GRID OF THIS TASK, not of the plan. The predecessors' ends in `ends` were
    // computed on THEIR grids, which is right: an end is a statement about the task that ends.
    // What follows from here on is a statement about this one, so it is asked on this one's grid
    // -- a successor begins on a day IT works, whoever it was waiting for.
    val grid = gridOf.getValue(id)
    var earliest = maxOf(projectStart, task.earliestStart ?: projectStart)
    for (p in task.predecessors) {
      ends[p]?.let { earliest = maxOf(earliest, it) }
    }
    earliest = nextWorkingDay(earliest, grid)

    val days: List<LocalDate>
    if (task.fixedStart != null) {
      // Keep the date, even when it lies too early or bursts the capacity. Both are reported --
      // that was the explicit decision: a deadline is a deadline.
      val start = nextWorkingDay(task.fixedStart, grid)
      if (start < earliest) {
        conflicts.add(LevelConflict.FixedDateNotReachable(id, start, earliest))
      }
      days = workingDays(start, durationAt(task, start), grid)
    } else {
      val search = findEarliestWindow(earliest, task, durationAt, used, grid, capacityOf,
        isAvailable, isAtWorkplace)
      days = search.days
      // THE ONE PLACE THE SILENT FALLBACK BECOMES A MESSAGE, and since 27.08.2026 it produces AT
      // MOST ONE report per Task. `exhausted` says the search gave up and the date below is the
      // fallback, not a result; the two lists say WHY it gave up, and a Task can have both.
      //
      // Neither list is decoration. `exhausted` alone would say "this date is wrong" and stop
      // there, which is the one thing a person cannot act on. Either list alone would fire on
      // every ordinary plan in which somebody is away for a day or a day is busy -- the everyday
      // case, and no conflict at all.
      //
      // WHAT CHANGED WHEN THE TWO KINDS WERE MERGED, precisely: nothing about WHEN something is
      // reported, only about HOW MANY objects carry it. `absenceBlocked` gave one conflict and a
      // non-empty `fullFor` gave a second; the same two conditions now fill two fields of the
      // same conflict. A Task with one reason yields one report as it did before, a Task with two
      // yields one where it used to yield two. See [LevelConflict.NoPossibleDate].
      if (search.exhausted) {
        // `absenceBlocked` is set only inside `task.blocking.any { … }` below, so it implies a
        // non-empty `task.blocking` -- the emptiness of this list is therefore the same statement
        // as `!absenceBlocked`, and one flag is enough to carry it into the message.
        //
        // NO TEST HOLDS THIS CONDITION. Drop the `if` -- write `task.blocking.sorted()`
        // unconditionally -- and the whole suite stays green. That is measured, not assumed.
        //
        // What breaks without it: a Task whose search ran out on CAPACITY alone would be reported
        // with a list of blocking people who have nothing to do with it, and a reader would go
        // looking for absences that are not there. The plan stays correct; the message lies.
        //
        // The check that would catch it needs a search that exhausts on capacity alone, and that
        // takes three chained blocks reaching into the 2260s -- a free day always fits, so giving
        // up on capacity costs 50 000 solidly booked working days. Roughly 5.5 s, which would
        // double the slowest test in this file. The cheap route -- adding an always-present
        // blocking person to the existing plan -- was rejected because it waters down
        // `testAPureCapacityDeadEndIsReported`, the one check that proves the pure capacity case
        // is reachable at all.
        //
        // Decided on 02.09.2026: leave the gap, name it here. Whoever removes this `if` because
        // it looks redundant has now been told why it is not.
        // [fork change] B3: `blockedBy` is filled only inside `task.blocking.any { … }` below, so
        // a non-empty set implies a non-empty `task.blocking` -- the same statement the boolean
        // made before it. The reasons travel with the names, because a list of people without the
        // reason names the wrong remedy; see [LevelConflict.NoPossibleDate.blockingReasons].
        val blocking =
          if (search.blockedBy.isNotEmpty()) task.blocking.sorted() else emptyList()
        val fullFor = search.fullFor.sorted()
        // Exhausted with neither reason recorded stays silent, exactly as it did when these were
        // two kinds: there would be nothing to name and nothing to change.
        if (blocking.isNotEmpty() || fullFor.isNotEmpty()) {
          conflicts.add(
            LevelConflict.NoPossibleDate(id, blocking, fullFor, search.blockedBy))
        }
      }
    }

    // [fork change] NO CONTRIBUTION, NO OCCUPANCY -- see [LevelTask.contributingDays].
    //
    // `days` AND NOT `beitragend` IN THE THREE LINES BELOW, and the difference is the whole of
    // what this change is and is not. The blocked day stays IN the task: it keeps its start, its
    // length and its end, and the day is a hole inside it. What it no longer does is take
    // somebody's capacity for work that is not happening.
    //
    // FOR A TASK THE SEARCH WAS ALLOWED TO MOVE THIS FILTERS NOTHING, by construction:
    // `findEarliestWindow` has already refused every window containing such a day, so none of
    // them is in `days`. It acts on the tasks the search may not move -- a fixed date, frozen
    // work -- and that is where the loss was measured on 05.09.2026.
    val beitragend = task.contributingDays(days, isAvailable, isAtWorkplace)
    task.pools.forEach { pool ->
      val belegung = used.getOrPut(pool) { mutableMapOf() }
      beitragend.forEach { belegung[it] = (belegung[it] ?: 0) + task.loadIn(pool) }
    }
    starts[id] = days.first()
    durations[id] = days.size
    ends[id] = nextWorkingDay(days.last().plusDays(1), grid)
  }

  // Deadlines: reported, not enforced. What is checked is the END, because a deadline is an end date.
  tasks.forEach { task ->
    val frist = task.deadline ?: return@forEach
    val ende = ends[task.id] ?: return@forEach
    // [fork change] ON THIS TASK'S GRID, and it has to be: "this many working days too late" is a
    // number the person reading it will compare with the task's own duration, and both have to
    // count the same days. Counting a Saturday worker's overrun in project-calendar days would
    // report a different number from the one their plan is made of.
    val grid = gridOf.getValue(task.id)
    // ends[] is the first working day AFTER the Task; the last working day lies before it.
    val letzterTag = generateSequence(ende.minusDays(1)) { it.minusDays(1) }
      .first { grid(it) || it < projectStart }
    if (letzterTag.isAfter(frist)) {
      val fehlend = workingDays(nextWorkingDay(frist.plusDays(1), grid),
        1, grid).let {
        var tage = 0
        var tag = nextWorkingDay(frist.plusDays(1), grid)
        while (!tag.isAfter(letzterTag) && tage < 100_000) {
          if (grid(tag)) tage++
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
        // [fork change] Every task's days are re-walked on ITS OWN grid, which is the same grid
        // they were booked on a few lines up. Using one grid for all of them here would name a
        // Saturday worker on the wrong days of an overload -- the day would be right, the list of
        // who is on it would not.
        val tage = workingDays(s, durations[t.id] ?: t.durationDays, gridOf.getValue(t.id))
        // [fork change] AND THROUGH THE SAME FILTER THE BOOKING USED, for the same kind of reason
        // as the grid beside it: this walk is a THIRD place a task's days are computed, and it
        // does not read the occupancy it is reporting on. Without the filter a task released from
        // the day would still be named as one of the reasons it is too full -- the number right,
        // the names wrong, and the reader pointed at the one task on the day that is innocent.
        t.contributingDays(tage, isAvailable, isAtWorkplace).contains(day)
      }.map { it.id }
      conflicts.add(LevelConflict.Overload(day, percent, onThatDay, pool))
    }
  }

  return LevelResult(starts, conflicts, durations)
}

/**
 * [fork change] The same day grid for every task -- what [levelTasks] took before 04.09.2026.
 *
 * WHAT IT IS FOR: a calculation in which there is genuinely one calendar. Most of the checks in
 * this file's test corpus are of that kind -- they are about the window search, the ordering or
 * the capacity, and a single Monday-to-Friday week is the whole of what they need from a calendar.
 * Writing the adapter here rather than a bare `{ _, day -> … }` at each of them makes the claim
 * visible: THIS PLAN HAS ONE GRID.
 *
 * WHAT IT IS NOT FOR, and this is the point of naming it at all: the program's own levelling run.
 * `LevellingActions` must pass `LevellingAdapter.workingDaysPerTask`, because in a real plan the
 * grid is a property of the people on the task and not of the project. Wrapping the project
 * calendar in here instead would compile, would look tidy and would put the contradiction of
 * `WorkWeekLevellingWindowTest` straight back. That check is what stops it.
 */
fun oneGridForAllTasks(isWorkingDay: (LocalDate) -> Boolean): (LevelTask, LocalDate) -> Boolean =
  { _, day -> isWorkingDay(day) }

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
 * [fork change] Which of [days] this Task actually consumes capacity on. The release of
 * 06.09.2026.
 *
 * THE RULE, IN NATALIE'S WORDS: „wenn b nicht an dem Vorgang arbeitet weil a nicht da ist, kann b
 * ja in der zeit was anderes machen". Half of it was built on 03.09.2026 -- since then a day on
 * which somebody indispensable is missing delivers no hours to the Task, in the duration
 * calculation and in the window search. The booking did not know it: `used` entered
 * [loadIn] over EVERY working day of the window, so a day on which nothing happened still held
 * everybody's capacity against everybody else. This closes that half.
 *
 * WHAT IT DOES NOT DO, and it should not be read as more than it is: it keeps no per-person
 * account of hours. There is still no place in this program that says how much of somebody's day
 * is left. It makes the day BOOKABLE again -- whether anything fills it is decided by the window
 * search, exactly as for any other free day. The alternative, a real accounting in hours, was
 * measured against this one on 05.09.2026 and set aside: it moves a unit through half the file,
 * changes the wording of the overload message on screen, and breaks the load-0 person this fork
 * exists for, for no gain on the loss that was actually measured.
 *
 * THE SAME QUESTION THE WINDOW SEARCH ASKS, AND THAT IS THE POINT OF IT BEING A FUNCTION. In
 * [findEarliestWindow] the two locals `absent` and `atHome` decide whether a candidate day is
 * usable; here the same two conditions decide whether it is booked. Two spellings of one sentence
 * drift -- this file says so itself about [LevelTask.blocking] and [LevelTask.requiresPresence] --
 * so the sentence is written once. That the two ends really agree is pinned from the outside by
 * `fenstersuche und buchung sind sich einig welcher tag ein blockierter ist`, which demands that a
 * movable task never be laid on a day this function would release.
 *
 * BOTH HALVES OF THE RULE, and the second one is not in the draft the measurement proposed: that
 * one predates the home-work series. A Task that needs somebody on the premises does not run on a
 * day that person works from home -- the search refuses such a day since 04.09.2026 -- so that day
 * delivers nothing either and must not be booked. Leaving it out would have left the two ends
 * saying different things about the same Wednesday.
 *
 * THE SAME LIST BACK WHEN NOTHING IS MARKED, the very object and not a copy of it. That is what
 * makes „an unmarked plan is not touched" a property of the shape rather than of the arithmetic:
 * with [LevelTask.blocking] empty neither channel is asked at all, on any day, and there is
 * nothing to allocate. Every plan written before this fork is in that state.
 *
 * COST: nothing that rises above the machine's own noise, measured twice — predicted on
 * 05.09.2026 from the draft, and measured again on 06.09.2026 on this code, paired, on the
 * measurement stand of 03.09.2026. The worst case for the cost is a plan in which the filter runs
 * on every booked day and removes NOTHING, since removing a day only saves a map write; 300 tasks
 * with fixed dates and a marking, 21 measurements after 6 warm-up runs, gave ratios of 1.00 and
 * 1.01 over two paired passes. Per booked window day this adds one walk over a set that is empty
 * in the ordinary case; the window search asks `durationAt` 224 550 times in the same plan.
 */
internal fun LevelTask.contributingDays(
  days: List<LocalDate>,
  isAvailable: (String, LocalDate) -> Boolean,
  isAtWorkplace: (String, LocalDate) -> Boolean
): List<LocalDate> {
  if (blocking.isEmpty()) {
    return days
  }
  return days.filter { day ->
    // The order is the one in [findEarliestWindow]: the absence first, because it is the cheaper
    // question, and the home-office half behind `requiresPresence` so that it is never evaluated
    // for a Task nobody marked.
    val absent = blocking.any { !isAvailable(it, day) }
    val atHome = !absent && requiresPresence && blocking.any { !isAtWorkplace(it, day) }
    !absent && !atHome
  }
}

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
 * [fork change] The day a task laid on [start] for [count] working days is FINISHED on -- the last
 * of those days, inclusive.
 *
 * IT DELEGATES TO [workingDays] RATHER THAN COUNTING, and that is the whole reason it exists. A
 * caller outside this file holds a [LevelResult] -- starts and durations -- and wants an end out of
 * it; the obvious way is to walk the grid and count, which is a second copy of the arithmetic
 * above. The two would agree until the day they did not, and the day they did not would be the day
 * somebody was shown a project end the levelling never computed. There is one walk, and this is a
 * name for its last step.
 *
 * ON THE GRID OF THE TASK, which is why [isWorkingDay] is a parameter and not the project calendar:
 * since 04.09.2026 the duration and the window are both computed per task, and an end computed on
 * anything else would not match either.
 */
fun lastWorkingDay(start: LocalDate, count: Int, isWorkingDay: (LocalDate) -> Boolean): LocalDate =
  workingDays(start, count, isWorkingDay).last()

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
   * Why a candidate day failed on a person marked as blocking: they were away, they were at home
   * on a Task that needs somebody present, or both happened on different days.
   *
   * [fork change] B3 MADE THIS A SET WHERE IT WAS A BOOLEAN, and the boolean's own note is why:
   * it stood here to keep the two ways of giving up apart, and there are now three. The message
   * has to name the right remedy, and „change the days off" put in front of a home-office day
   * sends somebody to the wrong dialog.
   *
   * NOT ALLOCATED PER DAY. The loop below keeps two locals and this set is built at the two
   * return statements, so the cost inside the walk is what it was: two boolean writes.
   *
   * ALWAYS EMPTY FOR A PLAN WITHOUT MARKINGS: with [LevelTask.blocking] empty the loop that could
   * fill it runs zero times, and with [LevelTask.requiresPresence] false its home-office half is
   * never evaluated at all.
   */
  val blockedBy: Set<AbsenceKind>,
  /**
   * The people whose already-booked working day made a candidate day fail.
   *
   * A SET AND NOT A BOOLEAN, unlike [absenceBlocked] above, because the two answers are used for
   * different sentences. The absence side reports the whole marked set, which it has in the Task
   * already; this side has to report WHOSE day was full, and that is not derivable afterwards --
   * the days it bounced off are gone by the time the caller sees the result.
   *
   * EMPTY WHENEVER A WINDOW WAS FOUND ON THE FIRST TRY, and empty as well whenever every rejected
   * day was rejected for an absence: the capacity question is asked only where the absence
   * question has already said no. That order is the one that stood here before -- `absent || full`
   * -- and it is kept rather than tidied, because turning it round would ask a more expensive
   * question on every day of every plan for the sake of a message.
   *
   * The consequence is worth naming plainly: a day that is BOTH an absence and full is counted as
   * an absence and appears nowhere in here. So this set says "the search bounced off these
   * people's full days", not "these are all the people who were ever full".
   */
  val fullFor: Set<String>
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
 *
 * @param isWorkingDay [fork change] THE GRID OF [task], already bound by the caller. This function
 * did not change when the grid became per task: it always searched for one task at a time, so
 * being handed that one task's calendar is the same shape it always had. What changed is who
 * decides which calendar that is.
 */
private fun findEarliestWindow(
  earliest: LocalDate,
  task: LevelTask,
  durationAt: (LevelTask, LocalDate) -> Int,
  used: Map<String, MutableMap<LocalDate, Int>>,
  isWorkingDay: (LocalDate) -> Boolean,
  capacityOf: (String) -> Int,
  isAvailable: (String, LocalDate) -> Boolean,
  isAtWorkplace: (String, LocalDate) -> Boolean
): WindowSearch {
  var candidate = nextWorkingDay(earliest, isWorkingDay)
  var schutz = 0
  // Whether an absence was ever the reason a day was rejected. Read only when the search gives up
  // below; it is what tells the ways of giving up apart. See [WindowSearch.blockedBy].
  var absenceBlocked = false
  // [fork change] B3, the same for a home-office day. TWO LOCALS AND NOT A SET, so that the walk
  // allocates nothing; the set is built where the two returns are. False for ever in a plan whose
  // Tasks are unmarked.
  var homeWorkBlocked = false
  // Whose full day the search bounced off. ONE set per search and not one per candidate day: the
  // additions below happen only on days that are being rejected anyway, and a day that fits adds
  // nothing at all. See [WindowSearch.fullFor].
  val fullFor = linkedSetOf<String>()
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
    // days, not a line of it changed. What has changed since is that neither half of it is silent
    // any more: the caller turns an exhausted search into ONE `LevelConflict.NoPossibleDate`,
    // whose `blocking` list is filled when `absenceBlocked` was set and whose `fullFor` list is
    // filled when a full day was hit -- both lists at once when both applied.
    //
    // WORTH KNOWING ABOUT THE CAPACITY HALF, because the bound is what makes it rare: a day that
    // is completely free ALWAYS fits, since the limit just below is at least the Task's own load.
    // So giving up on capacity alone needs every one of 50 000 working days -- some 190 years --
    // to be booked solid. Measured: a single frozen Task cannot even do it, because `workingDays`
    // has the same bound in CALENDAR days and tops out at 35 715 working days; it took three
    // chained blocks, booking every working day into the 2260s, to see it happen once. In the
    // 578-test corpus and in a grid of 80 constructed plans it happened zero times, while
    // give-ups that had a full day among their reasons happened 18 times.
    if (schutz++ > MAX_SEARCH_DAYS) {
      return WindowSearch(
        workingDays(nextWorkingDay(earliest, isWorkingDay), durationAt(task, earliest),
          isWorkingDay),
        exhausted = true, blockedBy = blockedBy(absenceBlocked, homeWorkBlocked),
        fullFor = fullFor)
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
      // The answer is remembered because it is one of the two things that turn the fallback above
      // into a message -- the other one is `fullFor` below. One boolean, no allocation, and false
      // for ever in a plan with no marking.
      // [fork change] THE SAME QUESTION [LevelTask.contributingDays] ASKS, and the two have to
      // stay the same question: this one decides that the day is no place for the Task, that one
      // decides that the day therefore costs nobody anything. They are spelled out separately
      // here only because the message needs to know WHICH of the two reasons applied, which a
      // single boolean answer cannot carry.
      val absent = task.blocking.any { !isAvailable(it, day) }
      if (absent) {
        absenceBlocked = true
      }
      // [fork change] B3, THE ONE LINE WHERE THE WINDOW SEARCH LEARNS THE HOME-WORK RULE.
      //
      // BOTH CONDITIONS, WITH THE TASK'S ONE FIRST. `task.requiresPresence` is a field read and it
      // is false for every Task in every plan written before this fork, so `&&` short-circuits and
      // the person question is never asked. That is one of the five independent reasons an
      // unmarked plan is laid exactly as it was.
      //
      // ASKED OF `task.blocking` -- THE SAME SET, NOT A SECOND ONE. Two sets would be two truths
      // about who has to be there; one of them would drift.
      //
      // AFTER THE ABSENCE AND NOT BESIDE IT. A day that is both is counted as an absence and does
      // not appear as a home-office day, exactly as a day that is both an absence and full is
      // counted as an absence. The order is the one the message needs: somebody who is on holiday
      // is not to be told to unmark the Task.
      val atHome = !absent && task.requiresPresence && task.blocking.any { !isAtWorkplace(it, day) }
      if (atHome) {
        homeWorkBlocked = true
      }
      // WRITTEN AS `absent || full` STILL, only with the right-hand side given a name. `full` is
      // computed exactly when the old `||` would have evaluated it -- `!absent &&` in front of it
      // is the short circuit, spelled out. Not one day changes its answer by this; what is gained
      // is that the answer can be written down.
      val full = !absent && !atHome && task.pools.any { pool ->
        // THE DEMAND IS THE ONE ON THIS PERSON, not the sum over everybody on the Task. Asking
        // the sum here was the defect: two people at 50 % each blocked a day on which each of
        // them was only half committed.
        val gefordert = task.loadIn(pool)
        // THE LIMIT IS AT LEAST THE TASK'S OWN LOAD. A Task that on its own demands more than
        // the utilisation allows (100 % load at 80 % utilisation) otherwise fits NOWHERE -- and
        // the search never finds a window. The utilisation limits how much OTHER work fits
        // alongside; it cannot forbid a single Task.
        val grenze = maxOf(capacityOf(pool), gefordert)
        val voll = (used[pool]?.get(day) ?: 0) + gefordert > grenze
        if (voll) {
          // Kept because it is the one thing the caller cannot work out afterwards: WHOSE day was
          // full. `any` still short-circuits, so what is collected is the first full person of
          // each rejected day, not every full person of every day -- and that is what the message
          // claims. See [WindowSearch.fullFor].
          fullFor.add(pool)
        }
        voll
      }
      absent || atHome || full
    }
    if (blockedAt == null) {
      return WindowSearch(window, exhausted = false,
        blockedBy = blockedBy(absenceBlocked, homeWorkBlocked), fullFor = fullFor)
    }
    // Continue searching only after the blocking day: everything before it fails for the same reason.
    candidate = nextWorkingDay(blockedAt.plusDays(1), isWorkingDay)
  }
}

/**
 * [fork change] B3: the two locals of the walk, turned into the set the result carries.
 *
 * Written out here rather than inline at the two return statements so that the two cannot come to
 * disagree -- they are the only two places a [WindowSearch] is built, and a search that reported a
 * home-office day at one exit and not at the other would be a message that depends on how the
 * search ended rather than on what it found.
 */
private fun blockedBy(absence: Boolean, homeWork: Boolean): Set<AbsenceKind> = when {
  absence && homeWork -> setOf(AbsenceKind.AWAY, AbsenceKind.AT_HOME)
  absence -> setOf(AbsenceKind.AWAY)
  homeWork -> setOf(AbsenceKind.AT_HOME)
  // The common case by a wide margin, and it allocates nothing.
  else -> emptySet()
}
