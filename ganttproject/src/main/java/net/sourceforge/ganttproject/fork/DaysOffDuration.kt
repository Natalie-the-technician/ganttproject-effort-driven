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

import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.algorithm.capacitySchedule
import net.sourceforge.ganttproject.task.algorithm.contributesEffort
import net.sourceforge.ganttproject.task.algorithm.effortHours
import java.time.LocalDate

/**
 * [fork change] Duration derived from effort, daily availability AND the days off of the assigned
 * people.
 *
 * The difference from [net.sourceforge.ganttproject.task.algorithm.computeDurationDays] is that a
 * day off still occupies a day of the task but contributes no hours. Five days of work with one
 * day off in the middle is six days long, not five.
 *
 * [fork change] AXIS A REACHES IN HERE AS WELL, since 03.09.2026: on a day on which somebody
 * marked `isBlocking` is away, NOBODY contributes to this task. The hours of everybody else go
 * back into their own pot instead of being spent on work that cannot proceed without the missing
 * person. Such a day still occupies a day of the task, exactly as any other day off does.
 *
 * THE BOUNDARY MATTERS MORE THAN THE RULE. This holds for `isBlocking` and for nothing else: the
 * ordinary holiday of a person who is not indispensable works as it always did -- their hours drop
 * out, the day stays, the task grows -- and the sentence about the six days above is unchanged by
 * it. Were the two lumped together, every holiday would stop every task.
 *
 * SAFE FOR EVERY PLAN THAT EXISTS TODAY: `isBlocking` defaults to `false` and no file written
 * before this fork carries the attribute, so the blocking set is empty and the rule never fires.
 * Measured, together with the boundary, in `BlockingAbsenceDurationTest`.
 *
 * THE END OF AN INTERVAL IS EXCLUSIVE. This is not a decision taken here, it is what the
 * surrounding code does, measured in four independent places:
 *
 *  * `DateInterval.createFromVisibleDates` stores `adjustRight(lastVisibleDay)`, and
 *    `GanttDialogPerson` hands exactly that to `GanttDaysOff` — so the dialog's "27 ... 28"
 *    becomes `GanttDaysOff(27, 29)`.
 *  * `DateInterval.createFromModelDates` reads it back with `jumpLeft(end)`, so the round trip
 *    through the dialog is stable.
 *  * `ProjectFileImporterImpl` builds a single day off as `GanttDaysOff(day, adjustRight(day))`.
 *  * `LoadDistribution.processDaysOff` turns the interval into a `Load(start, finish)`, the same
 *    half-open shape it uses for task activities.
 *
 * `GanttDaysOff.isADayOff` reads the end INCLUSIVELY and contradicts all four — but it has no
 * caller anywhere in the program, so it settles nothing. That contradiction is finding F24 and is
 * deliberately not fixed here.
 */

/** The days off of this person as half-open [start, endExclusive) ranges. */
fun HumanResource.daysOffRanges(): List<Pair<LocalDate, LocalDate>> {
  val list = this.daysOff
  return (0 until list.size).mapNotNull { i ->
    val interval = list.getElementAt(i) ?: return@mapNotNull null
    interval.start.time.toModelLocalDate() to interval.finish.time.toModelLocalDate()
  }
}

private fun List<Pair<LocalDate, LocalDate>>.covers(day: LocalDate): Boolean =
  this.any { (from, toExclusive) -> !day.isBefore(from) && day.isBefore(toExclusive) }

/**
 * One assignment, prepared for the day-by-day walk.
 *
 * [fork change] INTERNAL RATHER THAN PRIVATE since 03.09.2026, and that is not a loosening for
 * its own sake: levelling asks for the duration of the same task once per candidate day, and
 * building this list anew every time would read every person's days off out of the model tens of
 * thousands of times per run. `LevellingAdapter.durationAtStart` therefore builds it ONCE per
 * task and per run and hands it to [daysNeededWithDaysOff]. See there for the measurement.
 */
internal class Share(
  val schedule: CapacitySchedule,
  val load: Double,
  val daysOff: List<Pair<LocalDate, LocalDate>>,
) {
  fun hoursOn(day: LocalDate): Double =
    if (daysOff.covers(day)) 0.0 else schedule.hoursOn(day) * load

  /**
   * The MOST this share can deliver on any one day -- its highest daily rate, days off aside.
   *
   * [fork change] AN UPPER BOUND, not an answer, and that is what it is for: with it the walk
   * below can tell in one multiplication whether the effort is deliverable AT ALL, instead of
   * finding it out by taking ten thousand steps. See there.
   *
   * THE LOAD IS PART OF IT. An assignment at 0 % -- the person who has to be present without
   * working, supervision or an acceptance -- carries a perfectly ordinary daily rate and still
   * contributes nothing. A bound that read the daily rate alone would call that share able to
   * deliver and would have to walk after all, in what is an everyday case rather than an exotic
   * one.
   */
  val bestHoursPerDay: Double
    get() = load * maxOf(schedule.base, schedule.changes.maxOfOrNull { it.hoursPerDay } ?: 0.0)
}

/**
 * [fork change] Everything the day-by-day walk needs out of the model, BOTH AXES TOGETHER.
 *
 * They travel together because they are read from the SAME assignment list and the ORDER of the
 * two readings is the point -- see [Task.effortInputs]. A caller who could take the shares alone
 * would silently lose axis A, and every check on either side would stay green; the type is what
 * stops that.
 */
internal class EffortInputs(
  /** AXIS B: who contributes how many hours, and when are they away. */
  val shares: List<Share>,
  /** AXIS A: whose ABSENCE stops the whole day, flattened into one list of ranges. */
  val blockingDaysOff: List<Pair<LocalDate, LocalDate>>,
)

/**
 * [fork change] The inputs of a task: who contributes how many hours, when are they away, and
 * whose absence stops the task outright.
 *
 * READ ONCE, NOT PER QUESTION -- the same rule, and for the same reason, as
 * `LevellingAdapter.availabilityTest`. Everything in here comes out of the model and none of it
 * depends on the day the task starts, so a caller who asks for the duration many times over may
 * and should keep the result.
 *
 * THE TWO AXES ARE READ IN THIS ORDER, AND THE ORDER IS THE POINT. See the two comments in the
 * body: axis A is taken off the UNFILTERED assignment list, before axis B has dropped anybody.
 */
internal fun Task.effortInputs(resourceProperties: CustomPropertyManager): EffortInputs {
  // [fork change] AXIS A: whose ABSENCE takes the whole task with it, as opposed to whose hours
  // it removes. Flattened into ONE list of ranges, because the only question asked of it is
  // "is any of them away on this day" -- two indispensable people away on the same day therefore
  // cost that day once, not twice.
  //
  // "any absent" is the same statement as "all present must be present", and it is the same
  // sentence `ResourceLevelling.findEarliestWindow` already makes about the same set. Until
  // 03.09.2026 the two disagreed: levelling knew axis A and this computation did not.
  //
  // READ BEFORE THE AXIS B FILTER BELOW, AND THAT ORDER IS THE POINT. The person who has to be
  // present without doing any of the work -- supervision, an acceptance, a hand-over -- carries
  // axis B as well, and the filter below drops their assignment whole. Asking axis A afterwards
  // would drop exactly the case that justifies the axis. The two are independent, which is what
  // `ResourceAssignment` says of them.
  //
  // EMPTY IN EVERY PLAN THAT HAS NEVER TICKED THE BOX, and then it costs one empty `any` per
  // working day.
  val blockingDaysOff: List<Pair<LocalDate, LocalDate>> = this.assignments
    .filter { it.isBlocking }
    .mapNotNull { it.resource as? HumanResource }
    .flatMap { it.daysOffRanges() }
  // [fork change] AXIS B. An assignment marked `no-effort` is dropped whole, not merely set to
  // zero hours -- and dropping it whole is what also takes that person's days off out of the
  // walk. That is the same answer either way, and it is the RIGHT one FOR THE HOURS: somebody who
  // contributes no hours cannot have hours taken away from them by a holiday, so their day off
  // does not lengthen the task on that account.
  //
  // Whether that person's absence should STOP the task is a different question entirely -- that
  // is axis A -- and it IS asked, a few lines above, on the assignment list before this filter
  // has touched it.
  val shares = this.assignments.filter { it.contributesEffort }.mapNotNull { assignment ->
    (assignment.resource as? HumanResource)?.let { resource ->
      Share(
        resource.capacitySchedule(resourceProperties).schedule,
        assignment.load / 100.0,
        resource.daysOffRanges())
    }
  }
  return EffortInputs(shares, blockingDaysOff)
}

/**
 * [fork change] The day-by-day walk itself, separated from the model on 03.09.2026.
 *
 * Kept apart from [durationDaysWithDaysOff] for one reason only: levelling has to ask this
 * question once per candidate starting day, and it must not pay for [effortInputs] each time. The
 * arithmetic is not touched by the separation -- the loop below is the loop that stood in
 * [durationDaysWithDaysOff] before, line for line.
 *
 * @return the number of working days, at least 1, or `null` when nothing can be derived: nobody
 * who could contribute, or an effort that cannot be worked off within [CapacitySchedule.MAX_DAYS]
 * working days. An empty answer is better than an invented number.
 */
internal fun daysNeededWithDaysOff(
  effortHours: Double,
  inputs: EffortInputs,
  start: LocalDate,
  isWorkingDay: (LocalDate) -> Boolean,
): Int? {
  // WHAT CANNOT BE DELIVERED AT ALL IS ANSWERED WITHOUT WALKING. Everybody at their highest
  // daily rate, every day a working day, nobody ever away: if even that best of all cases does
  // not work the effort off within [CapacitySchedule.MAX_DAYS], no walk can do better.
  //
  // THE ANSWER IS UNCHANGED, only its price. The loop below lowers `remaining` by at most
  // `best` per working day -- days off, a blocked day and lower sections only lower it by less --
  // so after MAX_DAYS days something would be left over and the loop would return the same
  // `null`. The comparison is written against the same tolerance the loop uses, so that the two
  // cannot disagree about a case that lands exactly on the boundary.
  //
  // AXIS A IS NOT IN THE BOUND, on purpose: a blocked day takes hours away, so ignoring it here
  // can only make the bound MORE generous, and a generous upper bound never turns a walkable
  // effort into a `null`. A day on which somebody indispensable is away is found out by walking,
  // which is what keeps `eine unbegrenzte abwesenheit gibt auf statt endlos zu laufen` answering
  // `null` rather than a number.
  //
  // TWO CASES IT COVERS, and both are everyday ones rather than exotic: nobody who could deliver
  // anything -- an assignment at 0 %, a person with no hours entered, nobody assigned at all --
  // and an effort too large for the plan. Before 03.09.2026 the first was found out by ten
  // thousand steps and the second was never asked here at all, because levelling did not walk.
  val best = inputs.shares.sumOf { it.bestHoursPerDay }
  if (best <= 0.0 || effortHours - best * CapacitySchedule.MAX_DAYS > 1e-9) {
    return null
  }
  var remaining = effortHours
  var day = start
  var days = 0
  var guard = 0
  while (guard++ < CapacitySchedule.MAX_DAYS * 2) {
    if (isWorkingDay(day)) {
      days++
      // [fork change] AXIS A, and it takes the WHOLE day: a task that is missing somebody it
      // cannot proceed without makes no progress on that day, not through the other people
      // either. The day is counted all the same -- it occupies a day of the task, as every day
      // off has always done. What it no longer does is eat the others' hours.
      if (!inputs.blockingDaysOff.covers(day)) {
        remaining -= inputs.shares.sumOf { it.hoursOn(day) }
      }
      if (remaining <= 1e-9) {
        return days
      }
      if (days >= CapacitySchedule.MAX_DAYS) {
        return null
      }
    }
    day = day.plusDays(1)
  }
  return null
}

/**
 * The duration this task needs when the days off of its people are taken into account, or `null`
 * when nothing can be derived: no effort recorded, nobody assigned, or no availability at all.
 *
 * Returns `null` as well when the effort cannot be worked off within [CapacitySchedule.MAX_DAYS]
 * working days — an empty plan is a better answer than an arbitrary number.
 */
fun Task.durationDaysWithDaysOff(
  taskProperties: CustomPropertyManager,
  resourceProperties: CustomPropertyManager,
  start: LocalDate,
  isWorkingDay: (LocalDate) -> Boolean,
): Int? {
  val effort = this.effortHours(taskProperties) ?: return null
  if (effort <= 0.0) {
    return 1
  }
  return daysNeededWithDaysOff(
    effort, this.effortInputs(resourceProperties), start, isWorkingDay)
}
