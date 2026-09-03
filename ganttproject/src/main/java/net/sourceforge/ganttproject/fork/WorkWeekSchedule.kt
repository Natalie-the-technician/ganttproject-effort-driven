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

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The working week of a person over time.
 *
 * WHAT FOR: until now ONE working calendar applied to everybody, `taskManager.calendar`. Somebody
 * who works Mon, Tue, Fri, Sat is planned by it as though they worked Mon to Fri -- two days of
 * work that do not exist and two days off that are not taken. The distinction belongs to the
 * person, and it has to be able to change over time: from 1 March somebody works three days only.
 *
 * Sister of [CapacitySchedule], and deliberately NOT part of it. That one answers HOW MANY HOURS a
 * person delivers on a day; this one answers WHETHER the day is a working day for them at all.
 * They are two questions -- somebody who drops from eight hours to six has not thereby stopped
 * working on Fridays -- and putting them into one text would mean neither could be entered without
 * the other. (Whether the two should nevertheless share a file one day is discussed in the report;
 * they are not merged here.)
 *
 * Pure calculation, no GanttProject types -- checkable without a running program. Where the answer
 * meets `isWorkingDay` is not decided here.
 *
 * FORMAT, as it stands in the "Work week" column:
 *
 *     1,2,5,6; 2026-03-01: 1,2,3
 *
 * ISO weekday numbers, 1 = Monday to 7 = Sunday. Sections are separated by `;` or a line break,
 * date and days by `:` or `=`, the days themselves by `,` -- the same construction as
 * [CapacitySchedule.parse], so that whoever has filled in one column knows the other.
 *
 * WHY NUMBERS AND NOT "Mo,Di,Fr": the same file is opened with `ui.language=de_DE` and with
 * `en_US`. Weekday names would be nonsense in the second session and, worse, would be read as an
 * empty week without anybody being able to see it. What is stored carries no language.
 *
 * WHY THE ISO DATE and not `01.03.2026`: `03.04.05` is three different days in three countries.
 * The same reasoning, and the same error message, as in [CapacitySchedule].
 *
 * AN ENTRY WITHOUT A DATE applies from the beginning of the plan. The plain case -- "works Mon,
 * Tue, Fri, Sat, and always has" -- would otherwise have to invent a start date, and every plan
 * would carry a boundary standing for nothing.
 *
 * NOTHING ENTERED IS NOT "MONDAY TO FRIDAY". It is "no statement", and [worksOn] says so by
 * returning `null`. Neither `false` nor `true`: the first would silently rewrite every existing
 * plan the moment somebody swaps the version, the second would turn every weekend into working
 * time. What a FRESH entry is preset to is a matter for the user interface.
 *
 * ERRORS ARE NOT SWALLOWED, for the reason recorded in [CapacityParseResult]: a typo that quietly
 * becomes a different week gives a plan that looks plausible and is wrong. [parse] returns them.
 */
data class WorkWeekChange(
  /** From this day on [days] applies, inclusive. `null` means: from the beginning of the plan. */
  val from: LocalDate?,
  /** The weekdays worked. Empty means: no day -- see [WorkWeekSchedule.parse]. */
  val days: Set<DayOfWeek>
)

class WorkWeekSchedule(changes: List<WorkWeekChange> = emptyList()) {
  /** Sorted by date, the undated entry first. On a duplicate date the later entry of the input wins. */
  val changes: List<WorkWeekChange> = changes
    .associateBy { it.from }.values.sortedBy { it.from ?: LocalDate.MIN }

  /**
   * Does the person work on [day]?
   *
   * @return `null` when no section covers [day] -- NO STATEMENT, and the caller has to ask the
   * project calendar. Not `false`.
   */
  fun worksOn(day: LocalDate): Boolean? = sectionOn(day)?.days?.contains(day.dayOfWeek)

  /** The section in force on [day], or null when none is. */
  fun sectionOn(day: LocalDate): WorkWeekChange? =
    changes.lastOrNull { it.from == null || !it.from.isAfter(day) }

  /** Whether anything is entered at all. Without sections everything computes as before. */
  val isEmpty: Boolean get() = changes.isEmpty()

  override fun toString(): String = changes.joinToString("; ") { format(it) }

  companion object {
    /** ISO: 1 is Monday. */
    const val FIRST_DAY = 1

    /** ISO: 7 is Sunday -- and Sunday is a weekday like any other here. */
    const val LAST_DAY = 7

    /**
     * Reads the column text. Always returns a usable schedule AND the errors, never null -- for the
     * reason recorded in [CapacityParseResult].
     *
     * A section that cannot be read WHOLE is dropped whole, and outside it whatever applied before
     * goes on applying. „2026-03-01: 1,8" with the 8 quietly discarded would leave a person working
     * Mondays only; that looks like a deliberate entry and is a typo.
     *
     * THE EMPTY SECTION -- „2026-05-01:", no day at all -- IS NOT DECIDED. It is accepted here and
     * means "works on no day from that date on". This is the one place that would have to change to
     * make it an error instead; what it costs the two day-by-day walks is measured in
     * `WorkWeekEmptySectionTest`.
     */
    fun parse(text: String?): WorkWeekParseResult {
      val entries = (text ?: "").split(';', '\n')
        .map { it.trim() }.filter { it.isNotEmpty() }
      if (entries.isEmpty()) {
        return WorkWeekParseResult(WorkWeekSchedule(), emptyList())
      }
      val changes = mutableListOf<WorkWeekChange>()
      val errors = mutableListOf<String>()
      entries.forEach { entry ->
        val separator = entry.indexOfFirst { it == ':' || it == '=' }
        val dateText = if (separator < 0) "" else entry.substring(0, separator).trim()
        val daysText = if (separator < 0) entry else entry.substring(separator + 1).trim()
        val from = if (dateText.isEmpty()) null else try {
          LocalDate.parse(dateText)
        } catch (e: DateTimeParseException) {
          errors.add(forkText("fork.workweek.error.date", dateText))
          return@forEach
        }
        val days = mutableSetOf<DayOfWeek>()
        var readable = true
        daysText.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { token ->
          val number = token.toIntOrNull()
          if (number == null || number < FIRST_DAY || number > LAST_DAY) {
            errors.add(forkText("fork.workweek.error.day", token))
            readable = false
          } else {
            days.add(DayOfWeek.of(number))
          }
        }
        if (readable) {
          changes.add(WorkWeekChange(from, days))
        }
      }
      return WorkWeekParseResult(WorkWeekSchedule(changes), errors)
    }

    /**
     * One section as text. The shapes are chosen so that [parse] reads back what this writes,
     * including the two degenerate ones: a dated section with no day keeps its colon, an undated
     * one with no day is a bare colon -- without them the section would vanish on re-reading and
     * "works on no day" would silently become "no statement".
     */
    private fun format(change: WorkWeekChange): String {
      val days = change.days.sortedBy { it.value }.joinToString(",") { it.value.toString() }
      return when {
        change.from == null && days.isEmpty() -> ":"
        change.from == null -> days
        days.isEmpty() -> "${change.from}:"
        else -> "${change.from}: $days"
      }
    }
  }
}

/**
 * Result of parsing: always a usable schedule AND the errors.
 *
 * Both together, so that the caller has the choice -- a column display can carry on with the
 * incomplete week, a computation refuses the work on errors. Same shape and same reasoning as
 * [CapacityParseResult].
 */
data class WorkWeekParseResult(val schedule: WorkWeekSchedule, val errors: List<String>) {
  val hasErrors: Boolean get() = errors.isNotEmpty()
}
