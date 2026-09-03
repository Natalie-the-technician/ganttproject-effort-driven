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

import biz.ganttproject.core.calendar.GanttDaysOff
import biz.ganttproject.core.time.GanttCalendar
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("VacationInterval")

/**
 * [fork change] The `<vacation>` element of a `.gan` file, turned into a [GanttDaysOff].
 *
 * THE END OF A VACATION INTERVAL IS EXCLUSIVE — `GanttDaysOff(wed, thu)` is ONE day off, not two.
 * That is not a decision taken here, it is what the surrounding code already does; the witnesses
 * are listed in [net.sourceforge.ganttproject.fork.daysOffRanges] and in `DaysOffDuration.kt`.
 * The sample project that ships with the program says the same thing:
 * `<vacation start="2009-02-02" end="2009-02-09"/>`, two Mondays, read exclusively exactly one
 * week.
 *
 * Read that way a file with `start == end` is ZERO days of absence, and the plan is then computed
 * as if the vacation were not there at all — silently, because nothing in the program complains
 * about an empty interval. Such a file is not made up: the comment in `ResourceLoader` shows
 * precisely that shape, `<vacation start="2005-04-14" end="2005-04-14" resourceid="0"/>`, and a
 * file written by hand or by an older version can carry it.
 *
 * NOBODY NEEDS A ZERO-DAY VACATION. When a file says `start == end`, it means the one day named,
 * so it is loaded as the half-open `[d, d+1)`.
 *
 * WHY HERE AND NOT IN THE CONSTRUCTOR OF [GanttDaysOff]: the ambiguity sits at the border between
 * file and model. `GanttDaysOff` lives in `biz.ganttproject.core` and is built by callers that
 * never come from a file — the person dialog and the MS-Project import both already hand in a
 * proper exclusive end. Widening the interval there would quietly change what the type means for
 * all of them.
 *
 * The program has TWO independent readers for one file format — [ResourceLoader] for the desktop
 * and [XmlProjectImporter] for the cloud — and both call this function, so the two halves of the
 * program cannot disagree about the same file.
 *
 * `end < start` is NOT touched here. It is passed through exactly as before, because what should
 * happen to it has not been decided; it is only written to the log so that it stops being
 * invisible.
 */
fun daysOffFromFile(start: GanttCalendar, end: GanttCalendar, resourceId: Int): GanttDaysOff {
  val startDay = start.toLocalDate()
  val endDay = end.toLocalDate()
  if (startDay == endDay) {
    val endExclusive = GanttCalendar.fromLocalDate(startDay.plusDays(1))
    LOGGER.warn(
      "Vacation of resource {} says start == end == {}. The end of a vacation is exclusive, so " +
        "this would be zero days of absence. Reading it as the single day {}.",
      resourceId, startDay, startDay)
    return GanttDaysOff(start, endExclusive)
  }
  if (endDay.isBefore(startDay)) {
    // Behaviour deliberately unchanged -- only made visible.
    LOGGER.warn(
      "Vacation of resource {} ends ({}) before it starts ({}). Loading it as written; it covers " +
        "no day.", resourceId, endDay, startDay)
  }
  return GanttDaysOff(start, end)
}
