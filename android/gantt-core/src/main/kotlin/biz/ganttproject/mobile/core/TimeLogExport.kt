/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/*
 * Exporting the time log.
 *
 * The export is the artefact the app hands over; what happens to it afterwards
 * is none of the app's business. It therefore contains facts and nothing else:
 * no rounding, no caps, no classification, no aggregation. Whoever reads it
 * applies their own rules, and there is exactly one place where rounding
 * happens rather than two producing a third number.
 *
 * The shape is the one Toggl Track's Reports API v2 returns, because that is
 * what existing tooling already reads. Choosing an established shape over an
 * invented one is the whole reason this file is short.
 */

/** A period of days, both ends included, in a stated zone. */
data class ExportPeriod(val from: LocalDate, val to: LocalDate, val zone: ZoneId) {
  init {
    require(!to.isBefore(from)) { "period ends before it begins: $from .. $to" }
  }

  operator fun contains(record: TimeRecord): Boolean {
    val day = record.dateIn(zone)
    return !day.isBefore(from) && !day.isAfter(to)
  }
}

/**
 * The records of one period.
 *
 * Selection, not interpretation — but the zone still matters, because a record
 * at 23:30 belongs to a different day depending on where the question is asked.
 * Hence the zone lives in [ExportPeriod] and has to be stated.
 */
fun recordsInPeriod(records: List<TimeRecord>, period: ExportPeriod): List<TimeRecord> =
  records.filter { it in period }

/**
 * The months that actually contain records, newest first.
 *
 * Offered so a caller can let somebody pick a month instead of assuming one.
 * Assuming the previous month is right for the ordinary rhythm and wrong the
 * moment a month is missed: without this, a month that was never exported
 * becomes unreachable, and the hours in it cannot be shown to anybody.
 */
fun monthsWithRecords(records: List<TimeRecord>, zone: ZoneId): List<YearMonth> =
  records.filter { it.durationSeconds > 0L }
    .map { YearMonth.from(it.dateIn(zone)) }
    .distinct()
    .sortedDescending()

/** The whole of one month, in the stated zone. */
fun monthPeriod(month: YearMonth, zone: ZoneId): ExportPeriod =
  ExportPeriod(month.atDay(1), month.atEndOfMonth(), zone)

object TimeLogExport {

  /**
   * Writes the log in the shape of a Toggl Reports API v2 detailed report.
   *
   * ```json
   * { "total_count": 2,
   *   "data": [ { "id": "...", "description": "...", "start": "...",
   *               "end": "...", "dur": 3600000, "tags": ["V203"] } ] }
   * ```
   *
   * Two things in here are easy to get wrong and expensive afterwards:
   *
   * - **`dur` is in MILLISECONDS.** Readers of this shape divide by 1000.
   *   Writing seconds produces a report with a thousandth of the hours —
   *   numbers that are small but not impossible, so no plausibility check
   *   catches it. This is the single most likely serious defect of the export
   *   and has its own test.
   * - **`total_count` is what makes completeness provable.** Without it a
   *   reader can only take the file's word that nothing was cut off. It is
   *   always written, even when the log is empty.
   *
   * @param tagsByTaskUid labels per task, passed through verbatim. The app
   *   attaches no meaning to them: whether `V203` denotes a funded project or
   *   a customer is decided by whoever reads the export, never here.
   * @param records written sorted by start and then by id, so the same log
   *   always produces the same file. A file that differs on every run is
   *   indistinguishable from one that changed.
   */
  fun toTogglV2Json(
    records: List<TimeRecord>,
    tagsByTaskUid: Map<String, List<String>> = emptyMap()
  ): String {
    val sorted = records.sortedWith(compareBy({ it.start }, { it.id }))
    val entries = sorted.joinToString(",\n") { entryJson(it, tagsByTaskUid[it.taskUid].orEmpty()) }
    return buildString {
      append("{\n")
      append("  \"total_count\": ").append(sorted.size).append(",\n")
      append("  \"data\": [")
      if (sorted.isNotEmpty()) append("\n").append(entries.prependIndent("    ")).append("\n  ")
      append("]\n")
      append("}\n")
    }
  }

  private fun entryJson(record: TimeRecord, tags: List<String>): String = buildString {
    append("{")
    append("\"id\": ").append(quote(record.id))
    append(", \"description\": ").append(quote(record.description))
    append(", \"start\": ").append(quote(record.start.toString()))
    append(", \"end\": ").append(quote(record.end.toString()))
    // Milliseconds. See the note above.
    append(", \"dur\": ").append(record.durationSeconds * 1000L)
    append(", \"tags\": [").append(tags.joinToString(", ") { quote(it) }).append("]")
    // Toggl calls this field "user"; it carries the person a reporting scheme
    // has to name. Omitted rather than written as null when unknown, so that a
    // reader can tell "not modelled" from "nobody".
    record.person?.let { append(", \"user\": ").append(quote(it)) }
    // Not part of the Toggl shape: the task this belongs to, so a line in a
    // report can be traced back to the plan it came from. Readers that do not
    // know the field ignore it.
    append(", \"task_uid\": ").append(quote(record.taskUid))
    append(", \"source\": ").append(quote(record.source.name))
    append("}")
  }

  /**
   * JSON string literal.
   *
   * Control characters are written as `\uXXXX` rather than passed through:
   * a raw newline inside a string is invalid JSON, and a description is free
   * text that will eventually contain one.
   */
  private fun quote(text: String): String = buildString(text.length + 2) {
    append('"')
    for (ch in text) {
      when {
        ch == '"' -> append("\\\"")
        ch == '\\' -> append("\\\\")
        ch == '\n' -> append("\\n")
        ch == '\r' -> append("\\r")
        ch == '\t' -> append("\\t")
        ch < ' ' -> append("\\u").append(ch.code.toString(16).padStart(4, '0'))
        else -> append(ch)
      }
    }
    append('"')
  }
}
