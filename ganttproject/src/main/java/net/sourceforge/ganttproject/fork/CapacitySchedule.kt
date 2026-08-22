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

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The daily rate of a person over time.
 *
 * WHAT FOR: until now the daily rate was ONE number for the whole plan. Planning happens
 * alongside a main job; as soon as that is reduced, the number of hours rises. With a single
 * number there are only two options for this, and both are wrong: keep the small number (then the
 * future computes too pessimistically) or enter the large one (then the past computes too
 * favourably). Hence sections: **from a date on, a different number applies.**
 *
 * Pure calculation, no GanttProject types -- checkable without a running program.
 *
 * FORMAT, as it stands in the "Hours schedule" column:
 *
 *     2027-04-01: 6; 2027-10-01: 8
 *
 * Before the first date the person's normal daily rate applies. The separator between sections is
 * `;` or a line break, between date and hours `:` or `=`.
 *
 * WHY THE ISO DATE AND NOT `01.04.2027`: the entry is also read by the Android app and by
 * evaluations, and `03.04.05` is three different days in three countries. A format that can be
 * misunderstood is a source of error in a shared file. The error message names the expected
 * format explicitly.
 *
 * ERRORS ARE NOT SWALLOWED. A typo could otherwise lead to computing on quietly with the old
 * number -- the plan would look plausible and be wrong. [parse] returns the errors; the callers
 * display them and refuse the work.
 */
data class CapacityChange(
  /** From this day on [hoursPerDay] applies, inclusive. */
  val from: LocalDate,
  val hoursPerDay: Double
)

class CapacitySchedule(
  /** The daily rate before the first section: the person's normal entry. */
  val base: Double,
  changes: List<CapacityChange> = emptyList()
) {
  /** Sorted by date. On a duplicate date the later entry of the input wins. */
  val changes: List<CapacityChange> = changes
    .associateBy { it.from }.values.sortedBy { it.from }

  /** The hours available on this day. */
  fun hoursOn(day: LocalDate): Double =
    changes.lastOrNull { !it.from.isAfter(day) }?.hoursPerDay ?: base

  /** Whether anything is time-dependent at all. Without sections everything computes as before. */
  val isConstant: Boolean get() = changes.isEmpty()

  override fun toString(): String =
    changes.joinToString("; ") { "${it.from}: ${formatHours(it.hoursPerDay)}" }

  companion object {
    /** No person can deliver more than this in a day; beyond it, it is a typo. */
    const val MAX_HOURS_PER_DAY = 24.0

    /**
     * Upper bound for [daysNeeded]. Without it a section with 0 hours would become an endless
     * loop: the effort is never worked off. The number corresponds to about 40 years of working
     * days -- hitting it means not a rounding problem but a gap in the plan.
     */
    const val MAX_DAYS = 10_000

    fun parse(text: String?, base: Double): CapacityParseResult {
      val entries = (text ?: "").split(';', '\n')
        .map { it.trim() }.filter { it.isNotEmpty() }
      if (entries.isEmpty()) {
        return CapacityParseResult(CapacitySchedule(base), emptyList())
      }
      val changes = mutableListOf<CapacityChange>()
      val errors = mutableListOf<String>()
      entries.forEach { entry ->
        val separator = entry.indexOfFirst { it == ':' || it == '=' }
        if (separator < 0) {
          errors.add(forkText("fork.capacity.error.noSeparator", entry))
          return@forEach
        }
        val dateText = entry.substring(0, separator).trim()
        val hoursText = entry.substring(separator + 1).trim().replace(',', '.')
        val date = try {
          LocalDate.parse(dateText)
        } catch (e: DateTimeParseException) {
          errors.add(forkText("fork.capacity.error.date", dateText))
          return@forEach
        }
        val hours = hoursText.toDoubleOrNull()
        when {
          hours == null -> errors.add(forkText("fork.capacity.error.hours", hoursText))
          hours < 0.0 -> errors.add(forkText("fork.capacity.error.negative", hoursText))
          hours > MAX_HOURS_PER_DAY ->
            errors.add(forkText("fork.capacity.error.tooMany", hoursText, MAX_HOURS_PER_DAY))
          else -> changes.add(CapacityChange(date, hours))
        }
      }
      return CapacityParseResult(CapacitySchedule(base, changes), errors)
    }
  }
}

/**
 * Result of parsing: always a usable schedule AND the errors.
 *
 * Both together, so that the caller has the choice -- a column display can carry on with the
 * incomplete schedule, levelling refuses the work on errors. A `null` in this place would have
 * forced the display to fall back quietly to the old number.
 */
data class CapacityParseResult(val schedule: CapacitySchedule, val errors: List<String>) {
  val hasErrors: Boolean get() = errors.isNotEmpty()
}

/**
 * How many working days an effort needs when it begins on [start].
 *
 * This is the point at which the time sections take effect: a Task that runs across a boundary is
 * computed BEFORE the boundary with the old and afterwards with the new number of hours -- not
 * throughout with either of the two.
 *
 * @return the number of working days, at least 1, or null when the effort cannot be delivered
 * within [CapacitySchedule.MAX_DAYS] working days (in a section with 0 hours, for instance).
 */
fun daysNeeded(
  effortHours: Double,
  start: LocalDate,
  schedule: CapacitySchedule,
  loadFactor: Double = 1.0,
  isWorkingDay: (LocalDate) -> Boolean
): Int? {
  if (effortHours <= 0.0) {
    return 1
  }
  var remaining = effortHours
  var day = start
  var days = 0
  var guard = 0
  while (guard++ < CapacitySchedule.MAX_DAYS * 2) {
    if (isWorkingDay(day)) {
      days++
      remaining -= schedule.hoursOn(day) * loadFactor
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

/** Without decimal places when there are none: "8" instead of "8.0". */
internal fun formatHours(hours: Double): String =
  if (hours == hours.toLong().toDouble()) hours.toLong().toString() else hours.toString()
