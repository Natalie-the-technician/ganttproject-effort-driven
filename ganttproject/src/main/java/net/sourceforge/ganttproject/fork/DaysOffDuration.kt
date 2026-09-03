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
 * [fork change] The shares of a task: who contributes how many hours, and when are they away.
 *
 * READ ONCE, NOT PER QUESTION -- the same rule, and for the same reason, as
 * `LevellingAdapter.availabilityTest`. Everything in here comes out of the model and none of it
 * depends on the day the task starts, so a caller who asks for the duration many times over may
 * and should keep the list.
 *
 * AXIS B. An assignment marked `no-effort` is dropped whole, not merely set to zero hours -- and
 * dropping it whole is what also takes that person's days off out of the walk. That is the same
 * answer either way, and it is the RIGHT one: somebody who contributes no hours cannot have hours
 * taken away from them by a holiday. Their absence changes nothing about how long the work takes,
 * because they were not doing the work.
 *
 * Whether that person's absence should STOP the task is a different question entirely, and it is
 * not asked here -- that is axis A (`blocking`), which levelling reads off `LevelTask.blocking`.
 */
internal fun Task.effortShares(resourceProperties: CustomPropertyManager): List<Share> =
  this.assignments.filter { it.contributesEffort }.mapNotNull { assignment ->
    (assignment.resource as? HumanResource)?.let { resource ->
      Share(
        resource.capacitySchedule(resourceProperties).schedule,
        assignment.load / 100.0,
        resource.daysOffRanges())
    }
  }

/**
 * [fork change] The day-by-day walk itself, separated from the model on 03.09.2026.
 *
 * Kept apart from [durationDaysWithDaysOff] for one reason only: levelling has to ask this
 * question once per candidate starting day, and it must not pay for [effortShares] each time. The
 * arithmetic is not touched by the separation -- the loop below is the loop that stood in
 * [durationDaysWithDaysOff] before, line for line.
 *
 * @return the number of working days, at least 1, or `null` when nothing can be derived: nobody
 * who could contribute, or an effort that cannot be worked off within [CapacitySchedule.MAX_DAYS]
 * working days. An empty answer is better than an invented number.
 */
internal fun daysNeededWithDaysOff(
  effortHours: Double,
  shares: List<Share>,
  start: LocalDate,
  isWorkingDay: (LocalDate) -> Boolean,
): Int? {
  // WHAT CANNOT BE DELIVERED AT ALL IS ANSWERED WITHOUT WALKING. Everybody at their highest
  // daily rate, every day a working day, nobody ever away: if even that best of all cases does
  // not work the effort off within [CapacitySchedule.MAX_DAYS], no walk can do better.
  //
  // THE ANSWER IS UNCHANGED, only its price. The loop below lowers `remaining` by at most
  // `best` per working day -- days off and lower sections only lower it by less -- so after
  // MAX_DAYS days something would be left over and the loop would return the same `null`. The
  // comparison is written against the same tolerance the loop uses, so that the two cannot
  // disagree about a case that lands exactly on the boundary.
  //
  // TWO CASES IT COVERS, and both are everyday ones rather than exotic: nobody who could deliver
  // anything -- an assignment at 0 %, a person with no hours entered, nobody assigned at all --
  // and an effort too large for the plan. Before 03.09.2026 the first was found out by ten
  // thousand steps and the second was never asked here at all, because levelling did not walk.
  val best = shares.sumOf { it.bestHoursPerDay }
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
      remaining -= shares.sumOf { it.hoursOn(day) }
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
    effort, this.effortShares(resourceProperties), start, isWorkingDay)
}
