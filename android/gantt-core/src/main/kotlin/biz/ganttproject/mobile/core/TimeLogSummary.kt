/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import java.time.LocalDate
import java.time.ZoneId

/*
 * Summing up a time log.
 *
 * Everything here adds seconds together and nothing rounds, caps or classifies.
 * A funding scheme, an invoice and a post-calculation all want the same sums
 * and disagree only about what to do with them, so the disagreement stays
 * outside.
 *
 * Sums are in seconds because integers add up without loss. Hours are offered
 * next to them for display, and that is the only place a `Double` appears.
 */

/** What a log adds up to. Seconds are the truth; hours are for reading. */
data class TimeLogTotals(
  val recordCount: Int,
  val totalSeconds: Long,
  val firstDay: LocalDate?,
  val lastDay: LocalDate?,
  val secondsBySource: Map<TimeSource, Long>
) {
  val totalHours: Double get() = totalSeconds / 3600.0

  val isEmpty: Boolean get() = recordCount == 0
}

/**
 * The totals of a log, counted in [zone].
 *
 * The zone decides which day the first and last record fall on, so it is a
 * parameter rather than a default — the same reason it is one everywhere else
 * in the log.
 */
fun totalsOf(records: List<TimeRecord>, zone: ZoneId): TimeLogTotals {
  val usable = records.filter { it.durationSeconds > 0L }
  return TimeLogTotals(
    recordCount = usable.size,
    totalSeconds = usable.sumOf { it.durationSeconds },
    firstDay = usable.minOfOrNull { it.dateIn(zone) },
    lastDay = usable.maxOfOrNull { it.dateIn(zone) },
    secondsBySource = usable.groupBy { it.source }
      .mapValues { (_, group) -> group.sumOf { it.durationSeconds } }
  )
}

/**
 * Seconds per person.
 *
 * Records without a person land under `null` rather than being dropped or
 * merged into somebody: "nobody said who" is a different statement from "this
 * person", and a report that silently attributes work is worse than one that
 * admits a gap.
 */
fun secondsByPerson(records: List<TimeRecord>): Map<String?, Long> =
  records.filter { it.durationSeconds > 0L }
    .groupBy { it.person }
    .mapValues { (_, group) -> group.sumOf { it.durationSeconds } }

/**
 * Seconds per label, using the task labels supplied by the caller.
 *
 * A record whose task carries several labels counts in **full** under each of
 * them. That is deliberate and the sums therefore do not add up to the total —
 * splitting the hours between labels would invent a distribution nobody
 * decided. Anything that needs a partition has to define one itself.
 *
 * Records whose task has no label land under `null`.
 */
fun secondsByLabel(
  records: List<TimeRecord>,
  labelsByTaskUid: Map<String, List<String>>
): Map<String?, Long> {
  val out = mutableMapOf<String?, Long>()
  for (record in records.filter { it.durationSeconds > 0L }) {
    val labels = labelsByTaskUid[record.taskUid].orEmpty()
    if (labels.isEmpty()) {
      out[null] = (out[null] ?: 0L) + record.durationSeconds
    } else {
      for (label in labels) out[label] = (out[label] ?: 0L) + record.durationSeconds
    }
  }
  return out
}

/** Seconds per day and task — the shape most timesheet forms ask for. */
fun secondsByDayAndTask(
  records: List<TimeRecord>,
  zone: ZoneId
): Map<Pair<LocalDate, String>, Long> =
  records.filter { it.durationSeconds > 0L }
    .groupBy { it.dateIn(zone) to it.taskUid }
    .mapValues { (_, group) -> group.sumOf { it.durationSeconds } }

// -------------------------------------------------------------------- CSV

/**
 * The log as a table, for a spreadsheet, an invoice or a post-calculation.
 *
 * Separate from the JSON export, which mimics a tracker's API so an existing
 * reporting tool can read it. This one is for a human and a spreadsheet, and
 * the columns are named accordingly.
 *
 * **Both seconds and hours are written.** Seconds because they are exact and
 * whatever reads this can round its own way; hours because that is what
 * somebody opening the file wants to see. Writing only hours would force every
 * reader to trust a rounding this module has no business choosing.
 */
object TimeLogCsv {
  val COLUMNS = listOf(
    "Date", "Start", "End", "Seconds", "Hours",
    "Task", "Labels", "Person", "Source", "Description"
  )

  /**
   * @param separator `;` suits locales where the comma is the decimal mark and
   *   is what most European spreadsheets expect; pass `,` for the other half
   *   of the world. Values are quoted either way, so the choice is about what
   *   opens cleanly, not about correctness.
   * @param decimalComma writes `1,25` instead of `1.25`. Only affects the
   *   hours column; seconds are integers and never carry a mark.
   */
  fun write(
    records: List<TimeRecord>,
    zone: ZoneId,
    labelsByTaskUid: Map<String, List<String>> = emptyMap(),
    separator: Char = ';',
    decimalComma: Boolean = false
  ): String {
    val rows = records.sortedWith(compareBy({ it.start }, { it.id }))
    return buildString {
      append(COLUMNS.joinToString(separator.toString()) { quote(it, separator) })
      for (record in rows) {
        append("\n")
        val hours = String.format(java.util.Locale.ROOT, "%.4f", record.hours)
          .let { if (decimalComma) it.replace('.', ',') else it }
        append(
          listOf(
            record.dateIn(zone).toString(),
            record.start.toString(),
            record.end.toString(),
            record.durationSeconds.toString(),
            hours,
            record.taskUid,
            labelsByTaskUid[record.taskUid].orEmpty().joinToString(" "),
            record.person.orEmpty(),
            record.source.name,
            record.description
          ).joinToString(separator.toString()) { quote(it, separator) }
        )
      }
      append("\n")
    }
  }

  /**
   * Always quotes, and doubles a quote inside a value.
   *
   * Quoting unconditionally rather than only when needed: a description is free
   * text, and deciding per value when quoting is required is exactly where CSV
   * writers go wrong. A quoted field costs two characters and cannot break the
   * row.
   */
  private fun quote(value: String, separator: Char): String {
    // A newline inside a quoted field is legal CSV but confuses plenty of
    // readers, so it becomes a space. The full text stays in the JSON export.
    val flat = value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
    return "\"" + flat.replace("\"", "\"\"") + "\""
  }
}
