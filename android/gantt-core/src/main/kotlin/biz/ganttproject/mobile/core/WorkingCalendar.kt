/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The working calendar taken from the file's `<calendars>` section.
 *
 * This matters more than it looks. `<task duration="...">` counts WORKING
 * days, not calendar days: a 10-day bar starting on a Monday ends on the
 * Friday of the following week, not on the Wednesday. Every bar in the chart
 * and every date label would be wrong without this.
 *
 * @param weekendDays days of the week that are non-working
 * @param holidays individual non-working dates
 */
class WorkingCalendar(
  val weekendDays: Set<DayOfWeek>,
  val holidays: Set<LocalDate>
) {
  fun isWorkingDay(date: LocalDate): Boolean =
    date.dayOfWeek !in weekendDays && date !in holidays

  /** The first working day at or after [date]. */
  fun nextWorkingDay(date: LocalDate): LocalDate {
    var d = date
    var guard = 0
    while (!isWorkingDay(d)) {
      d = d.plusDays(1)
      // Guard against a pathological calendar where every day is off; without
      // it a malformed file would hang the UI thread rather than draw badly.
      if (++guard > MAX_SCAN_DAYS) return date
    }
    return d
  }

  /**
   * The last working day occupied by a task starting at [start] and lasting
   * [durationWorkingDays] working days, inclusive.
   *
   * A duration of 0 (a milestone) occupies its start day only.
   */
  fun lastWorkingDay(start: LocalDate, durationWorkingDays: Int): LocalDate {
    if (durationWorkingDays <= 0) return nextWorkingDay(start)
    var d = nextWorkingDay(start)
    var counted = 1
    var guard = 0
    while (counted < durationWorkingDays) {
      d = d.plusDays(1)
      if (isWorkingDay(d)) counted++
      if (++guard > MAX_SCAN_DAYS * durationWorkingDays) break
    }
    return d
  }

  /** Every working day a task occupies, in order. */
  fun workingDaysOf(start: LocalDate, durationWorkingDays: Int): List<LocalDate> {
    if (durationWorkingDays <= 0) return listOf(nextWorkingDay(start))
    val out = mutableListOf<LocalDate>()
    var d = nextWorkingDay(start)
    var guard = 0
    while (out.size < durationWorkingDays) {
      if (isWorkingDay(d)) out.add(d)
      d = d.plusDays(1)
      if (++guard > MAX_SCAN_DAYS * durationWorkingDays) break
    }
    return out
  }

  /** Number of working days from [from] to [toInclusive], both inclusive. */
  fun countWorkingDays(from: LocalDate, toInclusive: LocalDate): Int {
    if (toInclusive.isBefore(from)) return 0
    var d = from
    var n = 0
    while (!d.isAfter(toInclusive)) {
      if (isWorkingDay(d)) n++
      d = d.plusDays(1)
    }
    return n
  }

  companion object {
    /** A calendar with no weekends and no holidays. */
    val ALL_DAYS = WorkingCalendar(emptySet(), emptySet())

    /** The common default: Saturday and Sunday are non-working. */
    val DEFAULT = WorkingCalendar(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), emptySet())

    private const val MAX_SCAN_DAYS = 400
  }
}
