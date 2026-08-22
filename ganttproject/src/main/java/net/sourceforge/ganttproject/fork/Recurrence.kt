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
import java.time.temporal.ChronoUnit

/**
 * Recurring Tasks: "this happens again every month".
 *
 * WHAT FOR: the plan contains work that repeats -- advance VAT return, annual accounts, recurring
 * reports. Until now such a thing either does not appear in the plan at all (then the capacity for
 * it is missing, and levelling computes too optimistically) or it was duplicated by hand (then it
 * stops being right at the first replanning).
 *
 * Pure calculation, no GanttProject types -- checkable without a running program.
 *
 * FORMAT, as it stands in the "Recurrence" column. The keywords are the ones the parser accepts
 * and are therefore given verbatim:
 *
 *     monatlich; bis 2027-12-31
 *     woechentlich; alle 2; Anzahl 10
 *     jaehrlich; bis 2030-01-01
 *
 * Allowed are `taeglich`, `woechentlich`, `monatlich`, `jaehrlich` (also `daily`, `weekly`,
 * `monthly`, `yearly`), plus `alle N` and exactly one limit: `bis YYYY-MM-DD` or `Anzahl N`.
 *
 * TWO DECISIONS that could also have been taken differently -- which is why they stand here:
 *
 * 1. **A limit is mandatory.** A series without an end would be a quiet trap in a plan that has an
 *    end date: some number would have to be invented, and it would be written down nowhere. If the
 *    limit is missing, the entry is rejected and reported.
 * 2. **If a date falls on a non-working day, it moves FORWARD, not back.** Moving back could slip
 *    behind the previous date of the same series or before its beginning; moving forward cannot.
 *    The order of the series is thereby preserved in every case.
 */
enum class RecurrenceUnit { TAG, WOCHE, MONAT, JAHR }

data class RecurrenceRule(
  val unit: RecurrenceUnit,
  /** Interval in units. `alle 2` with WEEK means: every second week. */
  val interval: Int,
  /** Last day on which a date may still lie. Exactly one of the two is set. */
  val until: LocalDate? = null,
  /** Number of dates INCLUDING the first one. */
  val count: Int? = null
) {
  override fun toString(): String {
    val einheit = when (unit) {
      RecurrenceUnit.TAG -> "taeglich"
      RecurrenceUnit.WOCHE -> "woechentlich"
      RecurrenceUnit.MONAT -> "monatlich"
      RecurrenceUnit.JAHR -> "jaehrlich"
    }
    val abstand = if (interval > 1) "; alle $interval" else ""
    val grenze = until?.let { "; bis $it" } ?: "; Anzahl $count"
    return "$einheit$abstand$grenze"
  }

  companion object {
    /** No series produces more dates than this. Beyond it, the limit holds a typo. */
    const val MAX_OCCURRENCES = 500

    fun parse(text: String?): RecurrenceParseResult {
      val teile = (text ?: "").split(';', ',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
      if (teile.isEmpty()) {
        return RecurrenceParseResult(null, emptyList())
      }
      var unit: RecurrenceUnit? = null
      var interval = 1
      var until: LocalDate? = null
      var count: Int? = null
      val errors = mutableListOf<String>()

      teile.forEach { teil ->
        val klein = teil.lowercase().replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
        when {
          klein == "taeglich" || klein == "daily" -> unit = RecurrenceUnit.TAG
          klein == "woechentlich" || klein == "weekly" -> unit = RecurrenceUnit.WOCHE
          klein == "monatlich" || klein == "monthly" -> unit = RecurrenceUnit.MONAT
          klein == "jaehrlich" || klein == "yearly" -> unit = RecurrenceUnit.JAHR
          klein.startsWith("alle ") || klein.startsWith("every ") -> {
            val zahl = klein.substringAfter(' ').trim().toIntOrNull()
            if (zahl == null || zahl < 1) {
              errors.add(forkText("fork.recurrence.error.interval", teil))
            } else {
              interval = zahl
            }
          }
          klein.startsWith("bis ") || klein.startsWith("until ") -> {
            val datum = teil.substringAfter(' ').trim()
            until = try {
              LocalDate.parse(datum)
            } catch (e: DateTimeParseException) {
              errors.add(forkText("fork.recurrence.error.date", datum))
              null
            }
          }
          klein.startsWith("anzahl ") || klein.startsWith("count ") -> {
            val zahl = klein.substringAfter(' ').trim().toIntOrNull()
            if (zahl == null || zahl < 1) {
              errors.add(forkText("fork.recurrence.error.count", teil))
            } else {
              count = zahl
            }
          }
          else -> errors.add(forkText("fork.recurrence.error.unknown", teil))
        }
      }

      val einheit = unit
      if (einheit == null) {
        errors.add(forkText("fork.recurrence.error.noUnit"))
        return RecurrenceParseResult(null, errors)
      }
      if (until == null && count == null) {
        errors.add(forkText("fork.recurrence.error.noLimit"))
        return RecurrenceParseResult(null, errors)
      }
      if (until != null && count != null) {
        errors.add(forkText("fork.recurrence.error.twoLimits"))
        return RecurrenceParseResult(null, errors)
      }
      if (errors.isNotEmpty()) {
        return RecurrenceParseResult(null, errors)
      }
      return RecurrenceParseResult(RecurrenceRule(einheit, interval, until, count), emptyList())
    }
  }
}

/**
 * Result of parsing.
 *
 * `rule == null` means: unusable. Unlike with the hours schedule there is no "half usable"
 * remainder here -- a series with an unclear unit or without an end has no meaningful partial
 * meaning. An empty field is not an error: the Task simply does not repeat.
 */
data class RecurrenceParseResult(val rule: RecurrenceRule?, val errors: List<String>) {
  val hasErrors: Boolean get() = errors.isNotEmpty()
}

/**
 * The dates of the series, starting at [first].
 *
 * The first date is ALWAYS included -- it is the Task that already stands in the plan. The callers
 * therefore create only the dates from the second one on.
 *
 * @param isWorkingDay if a date falls on a non-working day, it moves FORWARD to the next working
 * day. See decision 2 in the class comment.
 */
fun occurrences(
  rule: RecurrenceRule,
  first: LocalDate,
  isWorkingDay: (LocalDate) -> Boolean
): List<LocalDate> {
  // A SET, not a list: two raw dates can move forward onto the same working day (daily over a
  // weekend, for instance). If the duplicates were counted, the upper bound would be reached
  // before it is reached -- measured: "taeglich bis 2099" delivered 357 dates and did not report
  // itself as truncated.
  val result = linkedSetOf<LocalDate>()
  var index = 0
  while (result.size < RecurrenceRule.MAX_OCCURRENCES) {
    val roh = when (rule.unit) {
      RecurrenceUnit.TAG -> first.plusDays((index.toLong() * rule.interval))
      RecurrenceUnit.WOCHE -> first.plusWeeks((index.toLong() * rule.interval))
      RecurrenceUnit.MONAT -> first.plusMonths((index.toLong() * rule.interval))
      RecurrenceUnit.JAHR -> first.plusYears((index.toLong() * rule.interval))
    }
    // The limit applies to the RAW date, not to the moved one: otherwise the end of a series
    // would depend on whether the last date happens to fall on a holiday.
    if (rule.until != null && roh.isAfter(rule.until)) {
      break
    }
    result.add(nextWorkingDay(roh, isWorkingDay))
    index++
    if (rule.count != null && result.size >= rule.count) {
      break
    }
  }
  // Two Tasks on the same day with the same name would not be a series but duplicated work --
  // here the calendar says how many dates are really possible.
  return result.toList()
}

/** How many dates a rule yields from [first] on, without building the list. */
fun occurrenceCount(
  rule: RecurrenceRule, first: LocalDate, isWorkingDay: (LocalDate) -> Boolean
): Int = occurrences(rule, first, isWorkingDay).size

/** Whether the series was truncated at the upper bound -- then the limit is wrong. */
fun isTruncated(
  rule: RecurrenceRule, first: LocalDate, isWorkingDay: (LocalDate) -> Boolean
): Boolean = occurrences(rule, first, isWorkingDay).size >= RecurrenceRule.MAX_OCCURRENCES

private fun nextWorkingDay(from: LocalDate, isWorkingDay: (LocalDate) -> Boolean): LocalDate {
  var day = from
  var guard = 0
  while (!isWorkingDay(day) && guard++ < 400) {
    day = day.plusDays(1)
  }
  return day
}

/** For display only: the distance in days between the first and the last date. */
internal fun spanInDays(dates: List<LocalDate>): Long =
  if (dates.size < 2) 0 else ChronoUnit.DAYS.between(dates.first(), dates.last())
