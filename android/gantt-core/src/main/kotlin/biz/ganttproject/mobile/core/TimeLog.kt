/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/*
 * The time log: individual records of work done on a task.
 *
 * This is deliberately NOT a report and NOT a timesheet. It is the raw record,
 * and everything that interprets it — rounding, caps, classification, period
 * boundaries, form layout — belongs to whoever consumes the export, not here.
 *
 * The reason is not purity. Interpretation rules differ per funding scheme,
 * per country and per client, and they change while records have to be kept
 * for years. Anything baked in here is a rule that will be wrong for somebody
 * and cannot be corrected after the fact, because the original value is gone.
 *
 * Everything in this file is a pure function over plain data: no UI, no
 * network, no Android, no clock. A record's timestamps are supplied by the
 * caller so that the same input always produces the same output.
 */

// ------------------------------------------------------------------ Record

/** Where a record came from. Kept because a hand-typed hour and a measured one are not the same claim. */
enum class TimeSource {
  /** Typed in by a person, after the fact or during the work. */
  MANUAL,

  /** Produced by a running timer that was started and stopped. */
  TIMER,

  /** Booked with one tap, e.g. from the home-screen widget. */
  QUICK,

  /** Taken over from an external tracker. */
  IMPORTED
}

/**
 * One stretch of work on one task.
 *
 * **Keyed by [taskUid], never by the task id.** The two are not
 * interchangeable:
 *
 * - `uid` is a random UUID (`TaskManagerImpl.java:382`), written to the file
 *   and globally unique. Upstream pins its behaviour in a named test,
 *   `ClipboardTaskProcessorTest.testUidInClipboardOperations`: moving a task
 *   keeps the uid, cut-and-paste keeps it, and copy-and-paste deliberately
 *   assigns a new one. That is exactly what a record of work needs — a
 *   reorganised task keeps its hours, a copied task does not inherit them.
 * - `id` is a per-project counter (`myMaxID.getAndIncrement()`). Task 3 exists
 *   in every project ever created, so the moment two projects' records meet in
 *   one export, they collide silently.
 *
 * @param id identifies the record itself, so the same work is not counted
 *   twice when two exports are merged. Supplied by the caller rather than
 *   generated here, so that tests and re-runs are reproducible.
 * @param start when the work began, **with its UTC offset**. See [dateIn] for
 *   why a bare local date is not enough.
 * @param durationSeconds raw, exactly as measured. Never rounded — see the
 *   note at the top of this file.
 * @param person who did the work, or `null` when that is not modelled. Not an
 *   identity: whatever string the caller uses to tell people apart.
 */
data class TimeRecord(
  val id: String,
  val taskUid: String,
  val start: OffsetDateTime,
  val durationSeconds: Long,
  val description: String,
  val person: String? = null,
  val source: TimeSource = TimeSource.MANUAL,
  val createdAt: OffsetDateTime = start
) {
  /**
   * End of the stretch, computed rather than stored.
   *
   * Storing start, end *and* duration means three values that can contradict
   * each other, and something has to decide which one wins. Two values cannot
   * disagree.
   */
  val end: OffsetDateTime get() = start.plusSeconds(durationSeconds)

  val hours: Double get() = durationSeconds / 3600.0

  /**
   * The calendar day this record counts towards, in [zone].
   *
   * The zone is a parameter and has no default **on purpose**. Work recorded
   * at 23:30 falls on a different day depending on which zone the question is
   * asked in, and month boundaries are exactly where reporting periods end.
   * A device that travels must not silently move hours into the neighbouring
   * month, so the reporting zone is a decision the caller has to make and
   * cannot inherit from wherever the phone happens to be.
   */
  fun dateIn(zone: ZoneId): LocalDate = start.atZoneSameInstant(zone).toLocalDate()

  /** True when this record and [other] cover any of the same instant. */
  fun overlaps(other: TimeRecord): Boolean =
    start < other.end && other.start < end
}

// -------------------------------------------------------------- Validation

/**
 * What is wrong with a record or a set of them. Types rather than messages, so
 * that the wording stays out of this module and can be localised.
 */
sealed interface TimeLogProblem {
  /** A record without a task cannot be attributed to anything. */
  data class NoTask(val recordId: String) : TimeLogProblem

  /** Zero or negative duration. A stretch of work that takes no time is a mistake, not a record. */
  data class NonPositiveDuration(val recordId: String, val seconds: Long) : TimeLogProblem

  /** A record without an id cannot be deduplicated on merge. */
  data object NoRecordId : TimeLogProblem

  /** The same id twice. Merging would silently drop one of them. */
  data class DuplicateId(val recordId: String) : TimeLogProblem

  /**
   * Two records of the same person cover the same instant.
   *
   * Reported, never corrected: this is usually a timer left running, but it can
   * also be a legitimate correction the user has not finished making. Deciding
   * what to do is not this module's business — but staying silent about a
   * contradiction would be.
   */
  data class Overlap(val firstId: String, val secondId: String, val person: String?) : TimeLogProblem
}

/** Checks one record on its own. Empty list means nothing to complain about. */
fun validateRecord(record: TimeRecord): List<TimeLogProblem> = buildList {
  if (record.id.isBlank()) add(TimeLogProblem.NoRecordId)
  if (record.taskUid.isBlank()) add(TimeLogProblem.NoTask(record.id))
  if (record.durationSeconds <= 0L) {
    add(TimeLogProblem.NonPositiveDuration(record.id, record.durationSeconds))
  }
}

/**
 * Checks a whole log: every record on its own, plus duplicate ids and
 * overlapping stretches per person.
 *
 * Overlaps are compared **per person**, because two people working at the same
 * time is the normal case and would otherwise drown the real finding.
 */
fun validateLog(records: List<TimeRecord>): List<TimeLogProblem> = buildList {
  records.forEach { addAll(validateRecord(it)) }

  records.groupBy { it.id }
    .filter { (id, group) -> id.isNotBlank() && group.size > 1 }
    .keys.sorted()
    .forEach { add(TimeLogProblem.DuplicateId(it)) }

  records.groupBy { it.person }.forEach { (person, group) ->
    val sorted = group.filter { it.durationSeconds > 0L }.sortedBy { it.start }
    for (i in sorted.indices) {
      for (j in i + 1 until sorted.size) {
        // Sorted by start, so once one record begins at or after the end of
        // the current one, no later record can overlap it either.
        if (sorted[j].start >= sorted[i].end) break
        add(TimeLogProblem.Overlap(sorted[i].id, sorted[j].id, person))
      }
    }
  }
}

// ------------------------------------------------------------- Aggregation

/**
 * Seconds booked per task, summed from the records.
 *
 * This is the **only** way the per-task total should ever be produced. The
 * total that ends up on the task (`effort_actual_hours`) is derived from the
 * log, never maintained beside it: two places holding the same number is two
 * places that can disagree, and the disagreement would be invisible.
 */
fun secondsByTaskUid(records: List<TimeRecord>): Map<String, Long> =
  records.filter { it.durationSeconds > 0L && it.taskUid.isNotBlank() }
    .groupBy { it.taskUid }
    .mapValues { (_, group) -> group.sumOf { it.durationSeconds } }

/**
 * Hours booked per task.
 *
 * Convenience for the existing hours-based field. Note that this is where
 * exactness stops: seconds are integers and add up without loss, hours are
 * `Double`. Anything that has to be *correct* rather than *displayed* should
 * use [secondsByTaskUid].
 */
fun hoursByTaskUid(records: List<TimeRecord>): Map<String, Double> =
  secondsByTaskUid(records).mapValues { (_, seconds) -> seconds / 3600.0 }

/** Records of one task, oldest first. Ties broken by id so the order is stable. */
fun recordsOfTask(records: List<TimeRecord>, taskUid: String): List<TimeRecord> =
  records.filter { it.taskUid == taskUid }
    .sortedWith(compareBy({ it.start }, { it.id }))

/**
 * Seconds per calendar day in [zone], for one task or for all of them.
 *
 * Useful to whoever needs a per-day figure — that is what most timesheet forms
 * ask for. It is offered rather than imposed: the log itself stays per record.
 */
fun secondsByDay(
  records: List<TimeRecord>,
  zone: ZoneId,
  taskUid: String? = null
): Map<LocalDate, Long> =
  records.filter { it.durationSeconds > 0L && (taskUid == null || it.taskUid == taskUid) }
    .groupBy { it.dateIn(zone) }
    .mapValues { (_, group) -> group.sumOf { it.durationSeconds } }

// ---------------------------------------------------------------- Encoding

/**
 * Text form of the log: one record per line, tab-separated, values escaped.
 *
 * Three properties, and each is a decision:
 *
 * - **One record per line, and lines never reordered on write.** That makes the
 *   text appendable, so the same encoding works whether it ends up in a model
 *   field, in a property, or in an append-only file beside the project. An
 *   encoding that has to be rewritten as a whole rules out append-only before
 *   the storage question is even asked.
 * - **Tab as separator, everything escaped.** Descriptions contain commas,
 *   semicolons, pipes and quotes as a matter of course; the one character they
 *   reliably do not contain is a tab, and it is escaped anyway.
 * - **Unknown trailing fields are ignored, missing optional ones defaulted.**
 *   New fields are appended at the end and nowhere else. An older reader then
 *   drops what it does not understand instead of refusing the file, and a newer
 *   one can still read what an older writer produced. Inserting a field in the
 *   middle breaks both directions and must not be done.
 */
object TimeLogCodec {
  private const val FIELD = '\t'

  /** Field count as of this version. Readers must not rely on it. */
  private const val FIELDS_WRITTEN = 8

  private fun escape(text: String): String = buildString(text.length) {
    for (ch in text) {
      when (ch) {
        '\\' -> append("\\\\")
        '\t' -> append("\\t")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        else -> append(ch)
      }
    }
  }

  private fun unescape(text: String): String = buildString(text.length) {
    var i = 0
    while (i < text.length) {
      val ch = text[i]
      if (ch != '\\' || i == text.lastIndex) {
        append(ch)
        i++
        continue
      }
      when (val next = text[i + 1]) {
        '\\' -> append('\\')
        't' -> append('\t')
        'n' -> append('\n')
        'r' -> append('\r')
        // An escape we do not know: keep both characters rather than guess.
        // Losing a backslash silently would corrupt a description for good.
        else -> { append(ch); append(next) }
      }
      i += 2
    }
  }

  fun encodeRecord(record: TimeRecord): String = listOf(
    record.id,
    record.taskUid,
    record.start.toString(),
    record.durationSeconds.toString(),
    record.source.name,
    record.person.orEmpty(),
    record.description,
    record.createdAt.toString()
  ).joinToString(FIELD.toString()) { escape(it) }

  /**
   * Writes the whole log.
   *
   * Sorted by start and then by id, so that the same set of records always
   * produces the same text. Without that, saving an unchanged project would
   * produce a different file every time — noise in every diff, and in a vault
   * indistinguishable from a real change.
   *
   * Sorting on write does not conflict with appending: a reader does not care
   * about order, so an appended line is read correctly and tidied up the next
   * time the whole log is written.
   */
  fun encode(records: List<TimeRecord>): String =
    records.sortedWith(compareBy({ it.start }, { it.id }))
      .joinToString("\n") { encodeRecord(it) }

  /**
   * Reads one record, or `null` when the line cannot be read.
   *
   * Null rather than an exception, for the same reason the import ledger skips
   * what it cannot parse: one damaged line must not make a project
   * unopenable. Silently dropping it is not good either — hence [decode],
   * which reports how many lines were lost.
   */
  fun decodeRecord(line: String): TimeRecord? {
    if (line.isBlank()) return null
    val fields = line.split(FIELD).map { unescape(it) }
    if (fields.size < 4) return null

    val id = fields[0]
    val taskUid = fields[1]
    val start = parseTime(fields[2]) ?: return null
    val seconds = fields[3].trim().toLongOrNull() ?: return null
    if (id.isBlank() || taskUid.isBlank()) return null

    val source = fields.getOrNull(4)
      ?.let { name -> TimeSource.entries.firstOrNull { it.name == name } }
      ?: TimeSource.MANUAL
    val person = fields.getOrNull(5)?.takeIf { it.isNotBlank() }
    val description = fields.getOrNull(6).orEmpty()
    val createdAt = fields.getOrNull(7)?.let { parseTime(it) } ?: start

    return TimeRecord(
      id = id,
      taskUid = taskUid,
      start = start,
      durationSeconds = seconds,
      description = description,
      person = person,
      source = source,
      createdAt = createdAt
    )
  }

  private fun parseTime(text: String): OffsetDateTime? =
    try {
      OffsetDateTime.parse(text.trim())
    } catch (e: DateTimeParseException) {
      null
    }

  /** What a read produced, including what it had to throw away. */
  data class DecodeResult(val records: List<TimeRecord>, val skippedLines: Int) {
    val hasLosses: Boolean get() = skippedLines > 0
  }

  /**
   * Reads a whole log.
   *
   * The skipped count is part of the result rather than a log line, so that a
   * caller has to decide what to do about it. A record that quietly disappeared
   * is worse than a visible gap: hours that were worked cannot be reconstructed
   * later, and nobody goes looking for a record they do not know is missing.
   */
  fun decode(text: String?): DecodeResult {
    if (text.isNullOrBlank()) return DecodeResult(emptyList(), 0)
    var skipped = 0
    val records = text.lineSequence()
      .filter { it.isNotBlank() }
      .mapNotNull { line -> decodeRecord(line).also { if (it == null) skipped++ } }
      .toList()
    return DecodeResult(records, skipped)
  }
}

// ------------------------------------------------------------------ Merging

/**
 * Combines two logs, keeping one copy of each record id.
 *
 * Needed wherever the same log arrives twice — a project opened on two devices,
 * an export re-imported, a file restored from a backup. Deduplication is by
 * record id alone, which is why [TimeRecord.id] has to be stable.
 *
 * On conflict the record from [preferred] wins. The caller decides which side
 * that is; this function does not guess which of two versions is newer, because
 * a later `createdAt` can just as well belong to a mistaken re-entry.
 */
fun mergeLogs(preferred: List<TimeRecord>, other: List<TimeRecord>): List<TimeRecord> {
  val byId = LinkedHashMap<String, TimeRecord>()
  other.forEach { byId[it.id] = it }
  preferred.forEach { byId[it.id] = it }
  return byId.values.sortedWith(compareBy({ it.start }, { it.id }))
}
