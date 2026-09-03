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
import java.time.LocalDate

private val LOGGER = LoggerFactory.getLogger("VacationInterval")

/**
 * One `<vacation>` element that contradicted itself and had to be corrected on the way in.
 *
 * The dates are kept as they STOOD IN THE FILE, not as they were corrected — this is what a person
 * has to search for to repair the file.
 */
data class VacationProblem(
  val resourceId: Int, val resourceName: String?, val start: LocalDate, val end: LocalDate
) {
  /**
   * How to name this person in a message. The NAME, because that is what the reader knows; the id
   * only when the file names a resource that does not exist, where a name cannot be had.
   */
  val label: String get() = resourceName?.takeIf { it.isNotBlank() } ?: "#$resourceId"
}

/**
 * [fork change] The contradictory vacations of ONE load, collected so that ONE message can be
 * shown for the whole file.
 *
 * NOT a global: a message must belong to the file it came from. The cloud
 * ([biz.ganttproject.core.io.XmlProjectImporter]) loads several projects in one process, and a
 * shared collector would blame one file for another's faults. So the collector is handed in, and
 * whoever begins a load owns it.
 *
 * IT COLLECTS, IT DOES NOT SHOW. Building the message here and showing it elsewhere is the same
 * split the fork already uses for [CapacityParseResult]: the parser stays free of any user
 * interface, and the one place that has a `UIFacade` decides whether there is anyone to tell.
 */
class VacationProblems {
  private val collected = mutableListOf<VacationProblem>()

  val problems: List<VacationProblem> get() = collected.toList()
  val isEmpty: Boolean get() = collected.isEmpty()

  fun record(problem: VacationProblem) {
    collected.add(problem)
  }

  /**
   * ONE text for the whole load, or `null` when there is nothing to say.
   *
   * `null` and not an empty string, so that a caller cannot accidentally show an empty box: a
   * message that appears after every single load is one that nobody reads any more, and it would
   * make the whole point of this message worthless.
   *
   * The text names the person, both dates AS THEY STAND IN THE FILE, and what was made of them.
   * "Something was wrong" would leave the reader with a file they cannot repair.
   *
   * The dates are written in ISO form on purpose, NOT in the display format of the current
   * language: this is the form they have in the `.gan` file, and it is what somebody opening the
   * file in an editor has to search for.
   */
  fun message(): String? {
    if (collected.isEmpty()) {
      return null
    }
    val text = StringBuilder()
    text.append(
      if (collected.size == 1) forkText("fork.vacation.reversed.one")
      else forkText("fork.vacation.reversed.many", collected.size))
    text.appendLine()
    collected.forEach {
      text.append("  • ")
        .append(forkText("fork.vacation.reversed.item", it.label, it.start, it.end))
        .appendLine()
    }
    text.appendLine().append(forkText("fork.vacation.reversed.consequence"))
    return text.toString()
  }
}

/**
 * [fork change] The `<vacation>` element of a `.gan` file, turned into a [GanttDaysOff].
 *
 * THE END OF A VACATION INTERVAL IS EXCLUSIVE — `GanttDaysOff(wed, thu)` is ONE day off, not two.
 * That is not a decision taken here, it is what the surrounding code already does; the witnesses
 * are listed in `DaysOffDuration.kt`. The sample project that ships with the program says the same:
 * `<vacation start="2009-02-02" end="2009-02-09"/>`, two Mondays, read exclusively exactly one week.
 *
 * Read that way, an interval that covers no day is silently no absence at all — the plan is then
 * computed as if the vacation were not in the file. There are two ways for a file to say that, and
 * THEY ARE NOT THE SAME KIND OF MISTAKE. That is why they are treated differently:
 *
 *  * `start == end` is a recognisable SHORTHAND. Both numbers agree with each other, and there is
 *    only one day they can mean. It is corrected in silence. The comment in `ResourceLoader` shows
 *    exactly that shape, `<vacation start="2005-04-14" end="2005-04-14" resourceid="0"/>`, so this
 *    is a form people do write.
 *  * `end < start` is CONTRADICTORY. The two numbers disagree, and at least one of them is simply
 *    wrong — but nothing in the file says which. It is corrected the same way, to one day from
 *    `start`, AND it is reported, because a correction of a value that nobody can check is a
 *    correction that has to be said out loud.
 *
 * Both become the half-open `[start, start+1)`. `start` is the date that is kept because it is the
 * one a person writes first and the only one of the pair that can still be trusted.
 *
 * WHY HERE AND NOT IN THE CONSTRUCTOR OF [GanttDaysOff]: the ambiguity sits at the border between
 * file and model. `GanttDaysOff` lives in `biz.ganttproject.core` and is built by callers that
 * never come from a file — the person dialog and the MS-Project import both already hand in a
 * proper exclusive end. Widening the interval there would quietly change what the type means for
 * all of them.
 *
 * The program has TWO independent readers for one file format — `ResourceLoader` for the desktop
 * and `XmlProjectImporter` for the cloud — and both call this function, so the two halves of the
 * program cannot disagree about the same file.
 *
 * @param resourceName the person the vacation belongs to, for the message. `null` when the file
 * names a resource that does not exist.
 * @param problems collects the contradictory intervals so that the caller can show ONE message for
 * the whole file. `null` when nobody is listening.
 */
fun daysOffFromFile(
  start: GanttCalendar,
  end: GanttCalendar,
  resourceId: Int,
  resourceName: String?,
  problems: VacationProblems? = null
): GanttDaysOff {
  val startDay = start.toLocalDate()
  val endDay = end.toLocalDate()

  if (startDay == endDay) {
    LOGGER.warn(
      "Vacation of resource {} says start == end == {}. The end of a vacation is exclusive, so " +
        "this would be zero days of absence. Reading it as the single day {}.",
      resourceId, startDay, startDay)
    return GanttDaysOff(start, oneDayAfter(startDay))
  }

  if (endDay.isBefore(startDay)) {
    // Reported, not merely logged: unlike start == end this is a contradiction, and the reader of
    // the file is the only one who can decide which of the two dates was meant.
    problems?.record(VacationProblem(resourceId, resourceName, startDay, endDay))
    LOGGER.warn(
      "Vacation of resource {} ends ({}) before it starts ({}). Reading it as the single day {}.",
      resourceId, endDay, startDay, startDay)
    return GanttDaysOff(start, oneDayAfter(startDay))
  }

  return GanttDaysOff(start, end)
}

/**
 * The exclusive end of the single day [day].
 *
 * Deliberately over the ISO date string of [GanttCalendar.fromLocalDate] and NOT over `toInstant`:
 * GanttProject bends the default time zone at startup and `java.time` does not see the bend, so an
 * instant-based route moves the date by a day.
 */
private fun oneDayAfter(day: LocalDate): GanttCalendar =
  GanttCalendar.fromLocalDate(day.plusDays(1))
