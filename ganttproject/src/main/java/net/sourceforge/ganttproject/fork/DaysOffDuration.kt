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

/** One assignment, prepared for the day-by-day walk. */
private class Share(
  val schedule: CapacitySchedule,
  val load: Double,
  val daysOff: List<Pair<LocalDate, LocalDate>>,
) {
  fun hoursOn(day: LocalDate): Double =
    if (daysOff.covers(day)) 0.0 else schedule.hoursOn(day) * load
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
  if (shares.isEmpty()) {
    return null
  }
  // Nobody can contribute anything on any day -- do not invent a duration for that.
  if (shares.all { it.schedule.base <= 0.0 && it.schedule.changes.all { c -> c.hoursPerDay <= 0.0 } }) {
    return null
  }
  var remaining = effort
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
      if (!blockingDaysOff.covers(day)) {
        remaining -= shares.sumOf { it.hoursOn(day) }
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
