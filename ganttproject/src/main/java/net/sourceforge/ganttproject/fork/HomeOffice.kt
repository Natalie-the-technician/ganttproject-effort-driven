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
 * [fork change] WHEN A PERSON WORKS FROM HOME — as a weekly pattern AND as a period.
 *
 * ═══ HOME OFFICE IS NOT AN ABSENCE ═══
 *
 * This is the whole point of the type and the sentence most likely to be lost the next time
 * somebody builds on it, so it stands here first. A person in the home office IS WORKING. Their
 * hours are there, their day counts, the plan may spend them. What they cannot do is a Task that
 * requires being on the premises — a machine has to be touched, a delivery taken in, somebody met.
 *
 * That is exactly the difference from [net.sourceforge.ganttproject.resource.HumanResource.getDaysOff]
 * and from [WorkWeekSchedule]:
 *
 *  * A DAY OFF removes the person's hours from the day. Home office does not.
 *  * A NON-WORKING WEEKDAY removes the day from the person's grid. Home office does not.
 *  * HOME OFFICE narrows WHICH Tasks the person may be given on that day, and nothing else.
 *
 * So NOTHING IN THIS FILE MAY EVER REACH `isAvailable` OR `isWorkingDay`. Whoever finds themselves
 * bending one of those from here has confused an absence with a place of work, and the plan will
 * quietly lose working days that the person actually worked. Which Tasks are excluded is marked on
 * the Task (package B2) and what it does to the planning is package B3; neither is decided here.
 *
 * ═══ BOTH MODELS, BECAUSE BOTH EXIST ═══
 *
 * Natalie's words: „ich würde sagen beides, es gibt ja beide Modelle in der Praxis". So there are
 * two halves and a person may use either, both, or neither:
 *
 *  * [HomeOfficeWeek] — the WEEKLY PATTERN. „Every Friday at home", and it may change over time.
 *  * [HomeOfficePeriods] — the PERIODS. „From 1 to 3 June at home", a stretch of days.
 *
 * [HomeOffice] is the two together, and the answer is their UNION: a day is a home-office day when
 * the pattern names it OR a period covers it. A day named by both is one home-office day and not
 * two — [worksFromHome] is a `Boolean` and [daysFromHome] counts days, not reasons.
 *
 * ═══ NOTHING ENTERED MEANS NO HOME OFFICE ═══
 *
 * Not „unknown", not „ask the project calendar" — NO home office, on every day. [worksFromHome]
 * therefore returns a plain `Boolean` and never `null`, and THAT IS THE DELIBERATE DIFFERENCE from
 * [WorkWeekSchedule.worksOn], which answers `Boolean?` because for a working week „nothing
 * entered" genuinely does mean „the project calendar applies".
 *
 * The asymmetry is not an inconsistency, it follows from what the two questions are for. A person
 * has a working week whether or not anybody wrote it down, so the working week needs a way to say
 * „no statement". Nobody works from home by accident: an arrangement that was never made does not
 * exist. And it has a consequence worth stating outright — FOR EVERY PLAN THAT EXISTS TODAY
 * NOTHING CHANGES, because no file written before this fork carries either property and every
 * person therefore answers `false` everywhere.
 *
 * ═══ THE FORMAT ═══
 *
 * Language-independent, exactly as [WorkWeekSchedule] and [CapacitySchedule] are, and for the same
 * reason: the same file is opened with `ui.language=de_DE` and with `en_US`. „Mo,Di,Fr" would be
 * nonsense in the second session and — worse — would read as an empty pattern without anybody
 * being able to see it. ISO weekday numbers and ISO dates carry no language.
 *
 *     pattern:  5                        every Friday, from the beginning of the plan
 *               1,5; 2026-03-01: 5       Mon and Fri, and Fri only from 1 March
 *     periods:  2026-06-01..2026-06-04   1, 2 and 3 June — SEE BELOW
 *
 * ═══ THE END OF A PERIOD IS EXCLUSIVE ═══
 *
 * `2026-06-01..2026-06-04` is THREE days: the 1st, 2nd and 3rd. The 4th is not one of them.
 *
 * This is not a decision taken here. It is what the surrounding program already does with a date
 * range, decided by Natalie on 03.09.2026 with six witnesses recorded under „Befund 1.3" — from
 * `DateInterval.createFromVisibleDates` storing `adjustRight(lastVisibleDay)` down to the sample
 * project's `<vacation start="2009-02-02" end="2009-02-09"/>`, two Mondays that read exclusively
 * are exactly one week. A period that read its end inclusively would be a THIRD convention in the
 * same file, and the day-off tab and the home-office tab of the SAME dialog would then mean
 * different things by the same two dates.
 *
 * The field is called [HomeOfficePeriod.endExclusive] and not `end` so that the mistake cannot be
 * made silently in code. What the user is SHOWN is another matter and is not decided here: the
 * days-off tab shows the last day inclusively (`DateInterval.visibleEnd`), and the panel does the
 * same.
 */
data class HomeOfficeChange(
  /** From this day on [days] applies, inclusive. `null` means: from the beginning of the plan. */
  val from: LocalDate?,
  /**
   * The weekdays spent at home. EMPTY IS MEANINGFUL HERE and says „from this date on, no home
   * office any more" — which is the only way to END an arrangement without deleting the history of
   * it. Note that this is the opposite of [WorkWeekChange], where an empty section says „never
   * works again" and is a degenerate case the input refuses to create.
   */
  val days: Set<DayOfWeek>
)

/**
 * The weekly home-office pattern over time.
 *
 * Sections and their resolution are the same construction as [WorkWeekSchedule], deliberately so:
 * whoever has understood one column can read the other. What differs is the answer to a day no
 * section covers — see the file comment.
 */
class HomeOfficeWeek(changes: List<HomeOfficeChange> = emptyList()) {
  /** Sorted by date, the undated entry first. On a duplicate date the later entry of the input wins. */
  val changes: List<HomeOfficeChange> = changes
    .associateBy { it.from }.values.sortedBy { it.from ?: LocalDate.MIN }

  /**
   * Does the pattern put [day] in the home office?
   *
   * A PLAIN `Boolean`. No section covering [day] means no home office on it — see the file
   * comment for why this is not `null`.
   */
  fun coversWeekday(day: LocalDate): Boolean =
    sectionOn(day)?.days?.contains(day.dayOfWeek) ?: false

  /** The section in force on [day], or null when none is. */
  fun sectionOn(day: LocalDate): HomeOfficeChange? =
    changes.lastOrNull { it.from == null || !it.from.isAfter(day) }

  val isEmpty: Boolean get() = changes.isEmpty()

  override fun toString(): String = changes.joinToString("; ") { format(it) }

  companion object {
    /** ISO: 1 is Monday. */
    const val FIRST_DAY = 1

    /** ISO: 7 is Sunday. A day like any other here — somebody may well work a Sunday from home. */
    const val LAST_DAY = 7

    /**
     * Reads the pattern text. ALWAYS returns a usable pattern AND the errors, never null — the
     * same contract as [WorkWeekSchedule.parse] and for the same reason: a column display can carry
     * on with the usable remainder while a write refuses over a typo.
     *
     * A section that cannot be read WHOLE is dropped WHOLE. „2026-03-01: 5,8" with the 8 quietly
     * discarded would leave somebody at home on Fridays only; that looks like a deliberate entry
     * and is a typo.
     */
    fun parse(text: String?): HomeOfficeWeekParseResult {
      val entries = (text ?: "").split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
      if (entries.isEmpty()) {
        return HomeOfficeWeekParseResult(HomeOfficeWeek(), emptyList())
      }
      val changes = mutableListOf<HomeOfficeChange>()
      val errors = mutableListOf<String>()
      entries.forEach { entry ->
        val separator = entry.indexOfFirst { it == ':' || it == '=' }
        val dateText = if (separator < 0) "" else entry.substring(0, separator).trim()
        val daysText = if (separator < 0) entry else entry.substring(separator + 1).trim()
        val from = if (dateText.isEmpty()) null else try {
          LocalDate.parse(dateText)
        } catch (e: DateTimeParseException) {
          errors.add(forkText("fork.homeoffice.error.date", dateText))
          return@forEach
        }
        val days = mutableSetOf<DayOfWeek>()
        var readable = true
        daysText.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { token ->
          val number = token.toIntOrNull()
          if (number == null || number < FIRST_DAY || number > LAST_DAY) {
            errors.add(forkText("fork.homeoffice.error.day", token))
            readable = false
          } else {
            days.add(DayOfWeek.of(number))
          }
        }
        if (readable) {
          changes.add(HomeOfficeChange(from, days))
        }
      }
      return HomeOfficeWeekParseResult(HomeOfficeWeek(changes), errors)
    }

    /**
     * One section as text, in a shape [parse] reads back — including the two sections with no day,
     * which here are not degenerate but the way an arrangement is ended.
     */
    private fun format(change: HomeOfficeChange): String {
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

/** A usable pattern AND whatever could not be read. Same shape as [WorkWeekParseResult]. */
data class HomeOfficeWeekParseResult(val week: HomeOfficeWeek, val errors: List<String>) {
  val hasErrors: Boolean get() = errors.isNotEmpty()
}

/**
 * ONE stretch of home-office days, half-open: [start] is in it, [endExclusive] is NOT.
 *
 * `HomeOfficePeriod(1 June, 4 June)` is the 1st, 2nd and 3rd — three days. The name of the second
 * field is the guard against reading it the other way; see the file comment for the six witnesses
 * that make this the convention of the surrounding program rather than a choice of this fork.
 */
data class HomeOfficePeriod(val start: LocalDate, val endExclusive: LocalDate) {
  /** How many days this period covers. Never negative: [HomeOfficePeriods.parse] rejects the case. */
  val days: Int get() = maxOf(0, (endExclusive.toEpochDay() - start.toEpochDay()).toInt())

  fun covers(day: LocalDate): Boolean = !day.isBefore(start) && day.isBefore(endExclusive)

  /**
   * The LAST day of this period — what a person is shown, and never what is stored.
   *
   * The days-off tab one tab over shows its intervals the same way (`DateInterval.visibleEnd`), so
   * the two tabs of the one dialog agree about what „to" means on screen while the file goes on
   * carrying the exclusive form the rest of the program uses.
   */
  val lastDay: LocalDate get() = endExclusive.minusDays(1)

  /** The stored form. ISO, exclusive end, and read back unchanged by [HomeOfficePeriods.parse]. */
  override fun toString(): String = "$start$RANGE$endExclusive"

  companion object {
    /** What separates the two dates of a period. See [HomeOfficePeriods.parse]. */
    const val RANGE = ".."
  }
}

/** The home-office periods of one person. */
class HomeOfficePeriods(periods: List<HomeOfficePeriod> = emptyList()) {
  /** Sorted by start, then by end. Duplicates are dropped — the same period twice is once. */
  val periods: List<HomeOfficePeriod> =
    periods.distinct().sortedWith(compareBy({ it.start }, { it.endExclusive }))

  /**
   * Is [day] covered by any period?
   *
   * Overlapping periods are NOT merged, and they need not be: this is a question with a yes or a no,
   * and a day covered twice is covered. Merging would only matter to something that counted
   * periods, and nothing does.
   */
  fun covers(day: LocalDate): Boolean = periods.any { it.covers(day) }

  val isEmpty: Boolean get() = periods.isEmpty()

  override fun toString(): String = periods.joinToString("; ")

  companion object {
    /**
     * Reads the period text. Always a usable list AND the errors, never null.
     *
     * ACCEPTED SHAPES, forgiving on the way in and canonical on the way out, the same manners
     * [WorkWeekSchedule.parse] has with its `:` and `=`:
     *
     *     2026-06-01..2026-06-04     start and exclusive end
     *     2026-06-01 .. 2026-06-04   spaces around the separator
     *     2026-06-01                 a bare date is the single day 2026-06-01
     *
     * TWO KINDS OF BROKEN INTERVAL, and they are NOT the same mistake — the distinction is the one
     * [daysOffFromFile] already draws for `<vacation>`:
     *
     *  * `start == end` is a recognisable SHORTHAND. Both numbers agree and there is only one day
     *    they can mean. Read as that single day, without complaint. Somebody writing the range by
     *    hand from the day-off habit writes exactly this.
     *  * `end < start` is CONTRADICTORY. The numbers disagree and at least one is wrong, and
     *    nothing in the text says which. REPORTED, and the section dropped whole.
     *
     * WHY DROPPED HERE AND CORRECTED THERE. [daysOffFromFile] must turn a `<vacation>` element into
     * SOMETHING — the loader has no way to refuse and no one to tell. This text has an error
     * channel that reaches a panel which can refuse to write, so the honest answer is available:
     * keep the broken line, name it, and let nothing overwrite it until a person has looked.
     */
    fun parse(text: String?): HomeOfficePeriodsParseResult {
      val entries = (text ?: "").split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
      if (entries.isEmpty()) {
        return HomeOfficePeriodsParseResult(HomeOfficePeriods(), emptyList())
      }
      val periods = mutableListOf<HomeOfficePeriod>()
      val errors = mutableListOf<String>()
      entries.forEach { entry ->
        val separator = entry.indexOf(HomeOfficePeriod.RANGE)
        val startText =
          (if (separator < 0) entry else entry.substring(0, separator)).trim()
        val endText =
          (if (separator < 0) "" else entry.substring(separator + HomeOfficePeriod.RANGE.length)).trim()
        val start = try {
          LocalDate.parse(startText)
        } catch (e: DateTimeParseException) {
          errors.add(forkText("fork.homeoffice.error.date", startText))
          return@forEach
        }
        if (endText.isEmpty()) {
          // A bare date is the single day it names: [start, start+1).
          periods.add(HomeOfficePeriod(start, start.plusDays(1)))
          return@forEach
        }
        val end = try {
          LocalDate.parse(endText)
        } catch (e: DateTimeParseException) {
          errors.add(forkText("fork.homeoffice.error.date", endText))
          return@forEach
        }
        when {
          end == start -> periods.add(HomeOfficePeriod(start, start.plusDays(1)))
          end.isBefore(start) -> errors.add(forkText("fork.homeoffice.error.reversed", entry))
          else -> periods.add(HomeOfficePeriod(start, end))
        }
      }
      return HomeOfficePeriodsParseResult(HomeOfficePeriods(periods), errors)
    }
  }
}

/** A usable list of periods AND whatever could not be read. */
data class HomeOfficePeriodsParseResult(val periods: HomeOfficePeriods, val errors: List<String>) {
  val hasErrors: Boolean get() = errors.isNotEmpty()
}

/**
 * The home office of ONE person: the weekly pattern and the periods together.
 *
 * The two halves are stored in two separate properties and are parsed separately — see
 * `HomeOfficeStorage.kt` for why. This type is where they meet, and the only thing it adds is the
 * union.
 */
class HomeOffice(
  val week: HomeOfficeWeek = HomeOfficeWeek(),
  val periods: HomeOfficePeriods = HomeOfficePeriods()
) {
  /**
   * Does this person work from home on [day]?
   *
   * THE UNION of the two halves, and a `Boolean`, never `null`. A day that the pattern names AND a
   * period covers is ONE home-office day: the question is about the day, not about how many
   * reasons there are for it.
   *
   * Nothing entered answers `false` for every day there is — see the file comment.
   */
  fun worksFromHome(day: LocalDate): Boolean = week.coversWeekday(day) || periods.covers(day)

  /**
   * How many days in `[from, toExclusive)` are spent at home.
   *
   * Here for the sake of the union: a day named by the pattern and covered by a period must be
   * counted ONCE, and a count is the only shape in which that can be got wrong. The range is
   * half-open like everything else in this file.
   */
  fun daysFromHome(from: LocalDate, toExclusive: LocalDate): Int {
    var count = 0
    var day = from
    while (day.isBefore(toExclusive)) {
      if (worksFromHome(day)) {
        count++
      }
      day = day.plusDays(1)
    }
    return count
  }

  /** Whether anything at all is entered. Nothing entered is no home office, not „unknown". */
  val isEmpty: Boolean get() = week.isEmpty && periods.isEmpty
}
