/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Extra fields this app understands, on top of stock GanttProject.
 *
 * They are stored as GanttProject **custom properties**, never as new XML
 * attributes. This is not a style preference — it is required for round-trip
 * safety. GanttProject's `TaskSaver` writes a hard-coded list of attributes,
 * so an unknown attribute is silently dropped the next time the desktop app
 * saves the file: data gone, no error. Custom properties, by contrast, are a
 * first-class desktop feature and are written back verbatim.
 *
 * The practical consequence: a file can travel app -> desktop -> app without
 * losing these values, and stock GanttProject shows them as ordinary
 * (if inert) columns.
 */
object ForkProperties {
  /** Planned effort in hours, per task. */
  const val TASK_EFFORT_HOURS = "effort_hours"

  /** Hours actually spent, per task. */
  const val TASK_EFFORT_ACTUAL_HOURS = "effort_actual_hours"

  /** Learned match keys for the time-tracking import, per task. */
  const val TASK_TOGGL_MATCH_KEYS = "toggl_match_keys"

  /**
   * Time-entry ids already imported, and how many hours of each.
   *
   * Stored per task but **read as a union across every task** (see
   * [GanttDocument.importedHoursByEntry]), which is what makes it a
   * project-wide record despite living on individual tasks.
   *
   * That indirection is the whole trick. The guard against importing the
   * same hours twice has to be keyed by time-entry id *alone* — otherwise it
   * stops working the moment an entry is reassigned to a different task on a
   * second run. Keying by (entry, task) would break it; storing by task
   * while looking up by entry does not.
   *
   * A project-level home would be tidier, but there is none that survives:
   * desktop GanttProject writes a fixed sequence of children under
   * `<project>` and a fixed set of registered options, so any container we
   * invented there would be silently dropped on its next save. Custom
   * properties are a first-class desktop feature and are written back
   * verbatim.
   */
  const val TASK_TOGGL_IMPORTED = "toggl_imported"

  /**
   * The time log: individual records of work, encoded by [TimeLogCodec].
   *
   * Stored per task and read as a union across all of them, like
   * [TASK_TOGGL_IMPORTED] — but for a different reason. There it is a trick to
   * fake a project-wide store; here each record already names its own task, so
   * the placement is merely where it happens to sit and the record's own
   * `taskUid` is what counts.
   *
   * **This is an interim home, and the code should stay easy to move.** A
   * custom property is shown by stock GanttProject as an ordinary editable
   * column, which is a poor place for a record somebody may later have to
   * vouch for, and the whole custom-property substructure is on its way out
   * upstream. It is used anyway because it is the only thing that survives a
   * desktop round trip today: `TaskSaver` writes a fixed list of attributes
   * *and* a fixed sequence of child elements, so an invented attribute or
   * element is dropped without a word the next time the desktop saves. The
   * encoding is deliberately independent of this decision — moving the log to
   * a model field means changing where the string is put, not how it is made.
   */
  const val TASK_TIME_LOG = "time_log"

  /**
   * Dated notes about deliberate changes to records already written.
   *
   * Written to **every** task whose records a change touched, and read as a
   * union deduplicated by id. The duplication is the point: an amendment that
   * lived on one task only would disappear when that task was deleted, and a
   * note about a change is exactly the thing that must not go missing.
   */
  const val TASK_LOG_AMENDMENTS = "time_log_amendments"

  /**
   * A free label per task, passed through to the export untouched.
   *
   * Deliberately without meaning here: whether the text denotes a funded
   * project, a customer or a cost centre is decided by whoever reads the
   * export. Keeping the interpretation out is what lets the same log serve a
   * funding scheme, an invoice and a post-calculation at once.
   */
  const val TASK_LABELS = "labels"

  /** Working hours per day, per resource. */
  const val RESOURCE_HOURS_PER_DAY = "hours_per_day"

  /**
   * Fallback when a resource carries no `hours_per_day`, so that projects
   * created before this field existed keep calculating sensibly.
   */
  const val DEFAULT_HOURS_PER_DAY = 8.0

  /**
   * Separator inside `toggl_match_keys`. Deliberately not a comma: time-entry
   * descriptions contain commas all the time, and a single key must not be
   * torn into three by one.
   */
  const val MATCH_KEY_SEPARATOR = "|"

  fun encodeMatchKeys(keys: List<String>): String =
    keys.map { it.replace(MATCH_KEY_SEPARATOR, " ") }
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .distinct()
      .joinToString(MATCH_KEY_SEPARATOR)

  /**
   * Labels share the match-key encoding on purpose: same shape, same
   * separator, same rule that the separator is stripped from values. One
   * encoding to get right instead of two that drift apart.
   */
  fun encodeLabels(labels: List<String>): String = encodeMatchKeys(labels)

  fun decodeLabels(raw: String?): List<String> = decodeMatchKeys(raw)

  fun decodeMatchKeys(raw: String?): List<String> =
    raw?.split(MATCH_KEY_SEPARATOR)
      ?.map { it.trim() }
      ?.filter { it.isNotEmpty() }
      ?.distinct()
      ?: emptyList()

  /**
   * Encodes an import ledger as `entryId=hours|entryId=hours`.
   *
   * Entries with no hours are dropped rather than written as zero: a zero
   * would claim the entry was imported and block it forever.
   */
  fun encodeImportedHours(ledger: Map<Long, Double>): String =
    ledger.entries
      .filter { it.value > 0.0 }
      .sortedBy { it.key }
      .joinToString(MATCH_KEY_SEPARATOR) { "${it.key}=${trimNumber(it.value)}" }

  /**
   * Decodes one task's ledger. Unreadable pairs are skipped rather than
   * failing the whole read — a corrupted fragment must not make a project
   * unopenable, and skipping only risks re-offering an import, never a
   * silent double booking, because the preview still shows it.
   */
  fun decodeImportedHours(raw: String?): Map<Long, Double> {
    if (raw.isNullOrBlank()) return emptyMap()
    val out = mutableMapOf<Long, Double>()
    for (pair in raw.split(MATCH_KEY_SEPARATOR)) {
      val parts = pair.split("=")
      if (parts.size != 2) continue
      val id = parts[0].trim().toLongOrNull() ?: continue
      val hours = parts[1].trim().toDoubleOrNull() ?: continue
      if (hours <= 0.0) continue
      // The same entry may appear on several tasks when it was split, so
      // the shares are summed, never overwritten.
      out[id] = (out[id] ?: 0.0) + hours
    }
    return out
  }
}

/**
 * Reads an hours value from user input.
 *
 * Accepts both the decimal comma ("7,5", common in German locales) and the
 * decimal point. Returns `null` for anything unusable so the caller can keep
 * the previous value instead of overwriting it with garbage.
 */
fun parseEffortInput(text: String?): Double? {
  val trimmed = text?.trim()?.replace(',', '.') ?: return null
  if (trimmed.isEmpty()) return null
  val value = trimmed.toDoubleOrNull() ?: return null
  if (value.isNaN() || value.isInfinite()) return null
  if (value < 0.0) return null
  return value
}

/**
 * Formats an hours value for display, trimming pointless decimals:
 * 8.0 -> "8", 7.5 -> "7.5", null -> an em dash.
 *
 * @param decimalSeparator pass ',' for locales that use the decimal comma
 */
fun formatHours(value: Double?, decimalSeparator: Char = '.'): String {
  if (value == null) return "—"
  val rounded = Math.round(value * 100.0) / 100.0
  val text = if (abs(rounded - Math.rint(rounded)) < 1e-9) {
    rounded.toLong().toString()
  } else {
    String.format(Locale.ROOT, "%.2f", rounded).trimEnd('0').trimEnd('.')
  }
  return if (decimalSeparator == '.') text else text.replace('.', decimalSeparator)
}

/**
 * The arithmetic core of effort-driven scheduling, kept free of any project
 * or UI types so it can be tested on its own.
 *
 * Rounds up because a partially used day is still an occupied day, and never
 * returns less than one day because a task spanning zero days is not a thing.
 *
 * @throws IllegalArgumentException if either argument is not positive; both
 *   would otherwise produce a silently nonsensical duration.
 */
fun computeDurationDays(effortHours: Double, availableHoursPerDay: Double): Int {
  require(effortHours > 0.0) { "effort must be > 0 but was $effortHours" }
  require(availableHoursPerDay > 0.0) {
    "availability must be > 0 but was $availableHoursPerDay"
  }
  return maxOf(1, ceil(effortHours / availableHoursPerDay).toInt())
}

/**
 * Hours per day available to a task: the sum over all assigned resources,
 * each weighted by its assignment load in percent.
 *
 * @param resourceById resolves a resource id; unknown ids contribute nothing
 *   rather than throwing, because a file may reference a deleted resource.
 */
fun availableHoursPerDay(
  allocations: List<Allocation>,
  resourceById: (String) -> ResourceNode?
): Double =
  allocations.sumOf { allocation ->
    val resource = resourceById(allocation.resourceId) ?: return@sumOf 0.0
    resource.effectiveHoursPerDay * allocation.load / 100.0
  }
