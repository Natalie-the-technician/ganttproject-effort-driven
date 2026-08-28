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
  // [fork change] AXIS B. An assignment marked `no-effort` is dropped whole, not merely set to
  // zero hours -- and dropping it whole is what also takes that person's days off out of the
  // walk. That is the same answer either way, and it is the RIGHT one: somebody who contributes
  // no hours cannot have hours taken away from them by a holiday. Their absence changes nothing
  // about how long the work takes, because they were not doing the work.
  //
  // Whether that person's absence should STOP the task is a different question entirely, and it
  // is not asked here -- that is axis A (`blocking`), and it is not built on this branch.
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
