/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.abs

/*
 * The hour journal: the time log as a file that a version control system can
 * show a readable difference for.
 *
 * The hours live in the `.gan`, encoded by [TimeLogCodec] into one very long
 * attribute value per task. That file is authoritative and stays so. It is,
 * however, useless as a history: adding one record changes a single line of
 * several thousand characters, and no diff of that is worth reading. The
 * journal is the same records written so a person can see what changed.
 *
 * **The journal is derived, never a second truth.** Nothing reads it back
 * during normal use. It is read only to restore, and [TimeRecord.id] is what
 * makes that safe: merging a journal into an existing log keys on the record
 * id, so restoring the same journal twice does not book the hours twice.
 *
 * ------------------------------------------------------------------------
 * THE FORMAT, version 1 -- written out in full because a second, independent
 * implementation has to match it byte for byte.
 * ------------------------------------------------------------------------
 *
 * A journal is a UTF-8 text file. Lines end with a single LF. The file always
 * ends with an LF, including the empty journal, so that appending a line
 * cannot damage the line before it.
 *
 * **Line 1 is the header** and is exactly [HEADER]. A file whose first line is
 * anything else is refused, not guessed at: refusing names the problem, while
 * a partial read of a file in some other format loses hours silently. When the
 * first line begins with [HEADER_PREFIX] but does not match, the file is a
 * journal of a version this code does not know, and is reported as such.
 *
 * The header names the fields, so the file explains itself to whoever opens it
 * in a browser. It carries the version for the same reason: a format change
 * changes the header, and every reader notices at once.
 *
 * **Every later line is one record**: eight fields separated by `|`. The
 * fields, in this order, are exactly the eight of [TimeLogCodec.encodeRecord]:
 *
 *   1. `record`  - the record id. Never empty.
 *   2. `task`    - the task uid. Never empty. Never the task *id*; see the
 *                  note on [TimeRecord].
 *   3. `start`   - when the work began, with its offset.
 *   4. `seconds` - the duration in seconds, a decimal integer, never rounded.
 *   5. `source`  - one of the [TimeSource] names.
 *   6. `person`  - may be empty.
 *   7. `note`    - the description, may be empty.
 *   8. `created` - when the record was made.
 *
 * **Escaping is [TimeLogCodec]'s, unchanged**: `\` becomes `\\`, tab `\t`,
 * LF `\n`, CR `\r`, `|` becomes `\p` and `;` becomes `\s`. Sharing the table
 * rather than inventing a second one means a journal line and a `.gan` chunk
 * are the same text, and there is one escaping to get right instead of two.
 * Because LF is escaped, no value can ever break a line in two.
 *
 * A reader ignores fields beyond the eighth and defaults missing optional
 * ones. New fields are appended at the end and nowhere else, so an older
 * reader drops what it cannot use instead of refusing the file.
 *
 * **The order of the lines is fixed, and this is the point of the whole
 * design.** The same set of records must produce the same bytes, whatever
 * order they arrive in -- otherwise every save is a commit, and a real change
 * cannot be told from noise. Records are sorted by:
 *
 *   1. the **instant** of `start`, ascending. The instant, not the local time:
 *      two records an hour apart in different offsets must not swap places
 *      because one of them was booked in another country.
 *   2. ties broken by the **encoded line, compared as UTF-8 bytes**, unsigned,
 *      byte by byte, the shorter one first on a common prefix.
 *
 * Rule 2 is a total order -- two records whose lines are equal are the same
 * bytes anyway -- so the result cannot depend on the input order even when the
 * same record id appears twice, which is exactly the case a merge accident
 * produces. Since a line starts with the record id, the tie-break reads as
 * "then by id", which is what anyone looking at the file expects.
 *
 * **Why UTF-8 bytes and not the string's own order.** On the JVM a `String`
 * compares by UTF-16 code unit, which puts characters above U+FFFF *below*
 * the range U+E000..U+FFFF, because they are stored as surrogates. Byte order
 * and code-point order agree; UTF-16 order disagrees with both. Anything that
 * has to match these bytes from another language would get it wrong for the
 * price of one emoji in a note. The shared fixture contains that pair
 * deliberately.
 *
 * **A duplicate record id is written, not repaired.** Two lines with one id
 * mean two devices merged badly, and the journal's job is to make that
 * visible; [validateLog] is what reports it, and restoring collapses it.
 */

/** Why a journal could not be read at all. Null in [JournalRead] means it was fine. */
enum class JournalProblem {
  /** The first line is not a journal header. Probably not a journal. */
  NO_HEADER,

  /** A journal, but of a version this code does not know. */
  UNKNOWN_VERSION
}

/**
 * What reading a journal produced.
 *
 * [skippedLines] is part of the result rather than a log line, for the same
 * reason it is in [TimeLogCodec.DecodeResult]: a record that quietly vanished
 * is worse than a visible gap, because nobody goes looking for hours they do
 * not know are missing.
 */
data class JournalRead(
  val records: List<TimeRecord>,
  val skippedLines: Int,
  val problem: JournalProblem?
)

/**
 * What writing a journal produced.
 *
 * [refused] holds the records that were left out because they could not have
 * been read back -- a blank record id or a blank task uid. Writing them would
 * produce a file that loses records on the next read, and dropping them
 * without saying so would hide it. Everything else is written, including
 * records [validateLog] would complain about: a backup that silently omits
 * the suspect entries is not a backup.
 */
data class JournalWrite(val text: String, val refused: List<TimeRecord>)

// ------------------------------------------------------------- Change list

/** What happened to a record between two states of the log. */
enum class ChangeKind { ADDED, REMOVED, CHANGED }

/**
 * One line of the change list: everything that happened to one task, for one
 * person, on one day.
 *
 * Grouped rather than per record, because a timer started five times in an
 * afternoon is one thing that happened, not five.
 */
data class ChangeLine(
  val kind: ChangeKind,
  /** Signed: positive for added, negative for removed, the delta for changed. */
  val seconds: Long,
  val label: String,
  val person: String?,
  val day: LocalDate
)

/**
 * The short change list Natalie asked a commit to carry.
 *
 * Structured rather than text, so the wording stays out of this module and
 * can be replaced -- the same reason [TimeLogProblem] is types and not
 * messages. [TimeJournal.renderChangeList] is one way to render it.
 *
 * [omittedLines] and [omittedSeconds] are what the cap left out. They are
 * carried rather than dropped so the rendering can say so; a list that is
 * short because it lied is not short, it is wrong.
 */
data class ChangeSummary(
  val lines: List<ChangeLine>,
  val omittedLines: Int,
  val omittedSeconds: Long
) {
  val isEmpty: Boolean get() = lines.isEmpty() && omittedLines == 0
}

object TimeJournal {

  /** The exact first line of a version 1 journal. */
  const val HEADER = "#gp-timelog 1 record|task|start|seconds|source|person|note|created"

  /** What every journal header starts with, whatever its version. */
  const val HEADER_PREFIX = "#gp-timelog "

  // ------------------------------------------------------------- Writing

  /**
   * Orders records exactly as the format demands, comparing the encoded line
   * as UTF-8 bytes. See the note at the top of the file for why not `String`.
   */
  private fun compareUtf8(a: String, b: String): Int {
    val x = a.toByteArray(Charsets.UTF_8)
    val y = b.toByteArray(Charsets.UTF_8)
    val shared = minOf(x.size, y.size)
    for (i in 0 until shared) {
      val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
      if (d != 0) return d
    }
    return x.size - y.size
  }

  fun write(records: List<TimeRecord>): JournalWrite {
    val refused = mutableListOf<TimeRecord>()
    val kept = mutableListOf<Pair<TimeRecord, String>>()
    for (record in records) {
      // Exactly the two things a reader cannot recover from, and nothing
      // else: a blank id or task makes decodeRecord return null.
      if (record.id.isBlank() || record.taskUid.isBlank()) {
        refused.add(record)
        continue
      }
      kept.add(record to TimeLogCodec.encodeRecord(record))
    }
    val ordered = kept.sortedWith(
      compareBy<Pair<TimeRecord, String>> { it.first.start.toInstant() }
        .thenComparator { a, b -> compareUtf8(a.second, b.second) }
    )
    val text = buildString {
      append(HEADER).append('\n')
      ordered.forEach { append(it.second).append('\n') }
    }
    return JournalWrite(text, refused)
  }

  // ------------------------------------------------------------- Reading

  fun read(text: String?): JournalRead {
    if (text == null) return JournalRead(emptyList(), 0, JournalProblem.NO_HEADER)
    // Split on LF and strip a trailing CR, so a file that travelled through a
    // Windows checkout still reads. Only LF is ever written.
    val lines = text.split('\n').map { it.removeSuffix("\r") }
    val first = lines.firstOrNull().orEmpty()
    if (first != HEADER) {
      return JournalRead(
        emptyList(),
        0,
        if (first.startsWith(HEADER_PREFIX)) JournalProblem.UNKNOWN_VERSION
        else JournalProblem.NO_HEADER
      )
    }
    var skipped = 0
    val records = lines.drop(1)
      .filter { it.isNotBlank() }
      .mapNotNull { line -> TimeLogCodec.decodeRecord(line).also { if (it == null) skipped++ } }
    return JournalRead(records, skipped, null)
  }

  // --------------------------------------------------------- Change list

  /**
   * What came in, went out or changed between two states of the log.
   *
   * Compared by record id, which is the only stable handle a record has.
   * An id present on both sides whose content differs is a change, and its
   * [ChangeLine.seconds] is the **delta**, not the new total -- a commit list
   * is about what moved.
   *
   * Lines are grouped by kind, task, person and day, then ordered by size so
   * that the cap keeps what matters, and cut to [maxLines]. Ties are broken by
   * day, label and person so the result never depends on the order the
   * records arrived in.
   *
   * [taskName] turns a task uid into something a person recognises; the
   * journal itself does not know task names. Where it returns null the
   * record's own description is used, and failing that the uid, so a line is
   * never blank.
   */
  fun changeSummary(
    before: List<TimeRecord>,
    after: List<TimeRecord>,
    zone: ZoneId,
    maxLines: Int = 3,
    taskName: (String) -> String? = { null }
  ): ChangeSummary {
    val beforeById = before.groupBy { it.id }
    val afterById = after.groupBy { it.id }

    // Deterministic pick when one id carries several records: the smallest
    // line by the format's own order, so the choice does not depend on input
    // order either.
    fun representative(group: List<TimeRecord>): TimeRecord =
      group.minWithOrNull { a, b ->
        compareUtf8(TimeLogCodec.encodeRecord(a), TimeLogCodec.encodeRecord(b))
      } ?: group.first()

    fun sameContent(a: List<TimeRecord>, b: List<TimeRecord>): Boolean =
      a.map { TimeLogCodec.encodeRecord(it) }.sortedWith(::compareUtf8) ==
        b.map { TimeLogCodec.encodeRecord(it) }.sortedWith(::compareUtf8)

    data class Key(val kind: ChangeKind, val taskUid: String, val person: String?, val day: LocalDate)

    val grouped = LinkedHashMap<Key, Pair<Long, TimeRecord>>()
    fun add(kind: ChangeKind, seconds: Long, sample: TimeRecord) {
      val key = Key(kind, sample.taskUid, sample.person, sample.dateIn(zone))
      val existing = grouped[key]
      grouped[key] = if (existing == null) seconds to sample else (existing.first + seconds) to existing.second
    }

    (beforeById.keys + afterById.keys).forEach { id ->
      val old = beforeById[id]
      val new = afterById[id]
      when {
        old == null && new != null ->
          add(ChangeKind.ADDED, new.sumOf { it.durationSeconds }, representative(new))
        new == null && old != null ->
          add(ChangeKind.REMOVED, -old.sumOf { it.durationSeconds }, representative(old))
        old != null && new != null && !sameContent(old, new) ->
          add(
            ChangeKind.CHANGED,
            new.sumOf { it.durationSeconds } - old.sumOf { it.durationSeconds },
            representative(new)
          )
      }
    }

    val all = grouped.entries
      .map { (key, value) ->
        val sample = value.second
        ChangeLine(
          kind = key.kind,
          seconds = value.first,
          label = taskName(key.taskUid) ?: sample.description.ifBlank { key.taskUid },
          person = key.person,
          day = key.day
        )
      }
      .sortedWith(
        compareByDescending<ChangeLine> { abs(it.seconds) }
          .thenBy { it.day }
          .thenBy { it.label }
          .thenBy { it.person ?: "" }
          .thenBy { it.kind.ordinal }
      )

    val shown = all.take(maxOf(0, maxLines))
    val omitted = all.drop(shown.size)
    return ChangeSummary(shown, omitted.size, omitted.sumOf { it.seconds })
  }

  /**
   * Hours for the change list: signed, rounded to hundredths, decimal comma.
   *
   * **This rounds, and everything else in the time log deliberately does
   * not.** The difference is what the number is for: the journal keeps the
   * exact seconds and is what anyone would have to vouch for, while this is a
   * headline on a commit. A non-zero amount never rounds down to nothing --
   * "+0 h" beside work that happened reads as "nothing happened", which is
   * the one thing the line must not say. The overstatement is at most
   * eighteen seconds.
   */
  internal fun hoursText(seconds: Long): String {
    val sign = if (seconds < 0) "-" else if (seconds > 0) "+" else ""
    val magnitude = abs(seconds)
    // Hundredths of an hour, rounded half up.
    var hundredths = (magnitude * 100 + 1800) / 3600
    if (hundredths == 0L && magnitude > 0L) hundredths = 1L
    val whole = hundredths / 100
    val fraction = (hundredths % 100).toInt()
    val number = when {
      fraction == 0 -> "$whole"
      fraction % 10 == 0 -> "$whole,${fraction / 10}"
      else -> "$whole," + fraction.toString().padStart(2, '0')
    }
    return sign + number
  }

  private fun render(line: ChangeLine): String {
    val marker = if (line.kind == ChangeKind.CHANGED) "~" else ""
    val who = line.person?.let { " ($it)" }.orEmpty()
    val day = "%02d.%02d.".format(line.day.dayOfMonth, line.day.monthValue)
    return "$marker${hoursText(line.seconds)} h  ${line.label}$who  $day"
  }

  /**
   * The change list as the text a commit carries.
   *
   * This is the one place in this module with words a person reads, and they
   * are German because that is the language of the people whose hours these
   * are. Anything that needs other wording can render [ChangeSummary] itself.
   */
  fun renderChangeList(summary: ChangeSummary): String {
    val body = summary.lines.map { render(it) }
    val tail =
      if (summary.omittedLines == 0) emptyList()
      else listOf("… und ${summary.omittedLines} weitere (${hoursText(summary.omittedSeconds)} h)")
    return (body + tail).joinToString("\n")
  }

  /**
   * The whole commit message: a subject with the timestamp, then the list.
   *
   * Null when there is nothing to commit. That is not an edge case but the
   * ordinary outcome of saving a project whose hours did not change, and the
   * caller has to be able to tell, or the repository fills with empty
   * commits.
   *
   * [at] is passed in rather than read from a clock, like everything else
   * here, so the same input always produces the same output.
   */
  fun commitMessage(at: OffsetDateTime, summary: ChangeSummary, zone: ZoneId): String? {
    if (summary.isEmpty) return null
    val local = at.atZoneSameInstant(zone)
    val subject = "Stunden %02d.%02d.%04d %02d:%02d".format(
      local.dayOfMonth, local.monthValue, local.year, local.hour, local.minute
    )
    return subject + "\n\n" + renderChangeList(summary) + "\n"
  }
}

// ------------------------------------------------------------------ Storage

/** What a save did. */
sealed interface SaveOutcome {
  /** The stored journal already said exactly this. Nothing was committed. */
  data object Unchanged : SaveOutcome

  /**
   * Stored. [revision] names the version that was created where the store has
   * versions -- a commit id, a version number -- and is null where it does
   * not. It is the handle a later restore needs, which is why it is here from
   * the start rather than added once something needs it.
   */
  data class Saved(val revision: String?) : SaveOutcome

  data class Failed(val reason: String) : SaveOutcome
}

/**
 * Somewhere a journal can be kept.
 *
 * Deliberately this small: take this text and this message, keep it; hand it
 * back when asked. It knows nothing about the format, so a store cannot
 * develop an opinion about the records, and nothing about git, so a store
 * that is not a repository is not a special case.
 *
 * **The interface exists before its second implementation on purpose**, which
 * is otherwise the wrong way round. The second one is not hypothetical: a
 * repository is the implementation that works today without anybody's
 * agreement, and the time-tracking service this is meant to be offered to is
 * the one that would replace it. The pattern is already in the desktop
 * program, where `Storage` carries three implementations side by side.
 */
interface JournalStore {
  /** For messages to the user. Not an address. */
  val name: String

  /** The stored journal, or null when there is none yet. */
  fun load(): String?

  /** Keep [text]; [message] describes the change for stores that record one. */
  fun save(text: String, message: String): SaveOutcome
}

/**
 * A journal in a file. No network, no history, no message.
 *
 * It exists so the whole path can be exercised without a repository or an
 * account: everything above it is the part that has to be right, and this
 * proves it works end to end. [lastMessage] is kept only so a test can see
 * that the message arrived; a plain file has nowhere to put it.
 */
class FileJournalStore(private val file: File) : JournalStore {

  override val name: String get() = file.path

  var lastMessage: String? = null
    private set

  override fun load(): String? = if (file.isFile) file.readText(Charsets.UTF_8) else null

  override fun save(text: String, message: String): SaveOutcome =
    try {
      if (file.isFile && file.readText(Charsets.UTF_8) == text) {
        SaveOutcome.Unchanged
      } else {
        file.parentFile?.mkdirs()
        // Written beside the target and renamed, so an interrupted save
        // cannot leave half a journal behind. Rename within one directory is
        // atomic on every filesystem this runs on.
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(text, Charsets.UTF_8)
        if (temporary.renameTo(file)) {
          lastMessage = message
          SaveOutcome.Saved(null)
        } else {
          temporary.delete()
          SaveOutcome.Failed("could not move the new journal into place")
        }
      }
    } catch (e: IOException) {
      SaveOutcome.Failed(e.message ?: e.javaClass.simpleName)
    }
}
