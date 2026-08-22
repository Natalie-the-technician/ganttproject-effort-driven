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
import java.util.Date
import java.util.GregorianCalendar

/**
 * Conversion between [LocalDate] and the [Date] values of the project model.
 *
 * **`ZoneId.systemDefault()` MUST NOT BE USED HERE.** The reason is a quirk of the original, and
 * it costs a day:
 *
 * `GanttLanguage.setLocale` replaces the JVM's default time zone at startup --
 * `TimeZone.getTimeZone("UTC")`, then `setRawOffset(local offset)`. The id thereby stays "UTC",
 * but the offset is the local one. The old `TimeZone` interface then returns the bent offset,
 * whereas `java.time` resolves the id "UTC" and returns **null**.
 *
 * MEASURED ON THE MACHINE (`ZeitzoneProbeTest`), language German, summer time:
 *
 *     TimeZone.getDefault():  id "UTC",      offset  2 h
 *     ZoneId.systemDefault(): UTC,           offset  0 h
 *     midnight of 4.11.2026 according to GanttProject: 1793743200000
 *     midnight of 4.11.2026 according to java.time:    1793750400000   (2 h later)
 *     the GanttProject date read as a LocalDate:       2026-11-03      (one day too EARLY)
 *
 * WHAT THAT CAUSED before this file came into being: capacity levelling did not find the
 * project's holidays (`myOneOffEvents` is keyed by the exact instant, and that was two hours
 * out). It computed holidays as working days and thereupon wrote ends that lay too early. In the
 * plan four Tasks lost duration through this -- 11 days became 8, 26 became 16. Measured against
 * the effort, 24 and 80 hours of work respectively were missing that the plan had known before.
 * No test of the pure calculation could find that: it computed correctly, it was given wrong
 * calendar answers.
 *
 * WHY `GregorianCalendar` and not a `ZoneOffset` from `rawOffset`: GanttProject folds summer time
 * into the fixed offset. Anyone computing it themselves has to rebuild that folding and is wrong
 * as soon as it is missing (in tests without a language set, for instance). `GregorianCalendar`
 * takes exactly the path that `CalendarFactory` takes too, and is therefore right in both cases.
 *
 * For the same purpose the original has `DateParser.toJavaDate`/`toLocalDate` and `GanttCalendar
 * .toLocalDate`; they go through an ISO string and are therefore correct as well. But they use a
 * SHARED, unguarded `SimpleDateFormat` (`DateParser.java:287`) -- during levelling these
 * conversions run thousands of times, and `SimpleDateFormat` is not thread-safe. The path here
 * needs no shared state.
 */

/** Midnight of this day, in the time reckoning of the project model. */
fun LocalDate.toModelDate(): Date =
  GregorianCalendar(this.year, this.monthValue - 1, this.dayOfMonth).time

/** The calendar day this instant falls on in the project model. */
fun Date.toModelLocalDate(): LocalDate {
  val calendar = GregorianCalendar()
  calendar.time = this
  return LocalDate.of(
    calendar.get(GregorianCalendar.YEAR),
    calendar.get(GregorianCalendar.MONTH) + 1,
    calendar.get(GregorianCalendar.DAY_OF_MONTH))
}
