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
package net.sourceforge.ganttproject.timetracking

import net.sourceforge.ganttproject.GPLogger
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlin.math.abs

/*
 * [fork change] The hour journal, desktop side.
 *
 * The same file format the Android app writes, implemented again rather than
 * shared, and the duplication is deliberate.
 *
 * **The two builds share no code, on purpose.** `android/settings.gradle.kts`
 * says so in as many words -- the Android build is standalone so that the
 * desktop keeps building on machines with no SDK -- and the desktop core
 * could not be used from Android anyway, because it depends on
 * `kotlinx-coroutines-javafx` and `-swing`. Reaching across would undo that.
 *
 * What is shared instead is the **specification and the test data**: the
 * `shared-*.timelog` files in the tester's resources are byte for byte the
 * files in the app's test resources, and both suites pin the same digests.
 * Two implementations of one written format, held together by a fixture
 * neither of them produced. If either side drifts, its own tests go red.
 *
 * ------------------------------------------------------------------------
 * WHAT THE DESKTOP CAN AND CANNOT DO WITH THIS TODAY -- measured 09.09.2026
 * ------------------------------------------------------------------------
 *
 * The desktop has **no per-record time log**. It keeps a per-task total in
 * `effort_actual_hours` and an import ledger of entry id to hours
 * ([encodeImportLedger]), and that ledger holds neither a start time nor a
 * person. So the desktop can read any journal in full, and can write one from
 * records it is handed, but it **cannot reconstruct records from a `.gan` it
 * opens** -- that information was never stored on this side. Records can only
 * be produced here at import time, while the tracker's entries are still in
 * hand.
 *
 * This module is therefore the format and the storage, and nothing above
 * them. Wiring it to a source of records on the desktop is a separate job,
 * and it needs the desktop to grow a per-record log first.
 *
 * ------------------------------------------------------------------------
 * THE FORMAT, version 1
 * ------------------------------------------------------------------------
 *
 * UTF-8 text. Lines end with a single LF, and the file always ends with one,
 * including the empty journal, so appending cannot damage the previous line.
 *
 * **Line 1 is exactly [HEADER].** Anything else is refused rather than
 * guessed at; a first line that starts with [HEADER_PREFIX] but does not
 * match is a journal of an unknown version and is reported as that.
 *
 * **Every later line is one record**, eight `|`-separated fields:
 * `record`, `task`, `start`, `seconds`, `source`, `person`, `note`,
 * `created`. Fields past the eighth are ignored and missing optional ones
 * defaulted, so new fields may be appended at the end -- and nowhere else.
 *
 * **Escaping:** `\` to `\\`, tab to `\t`, LF to `\n`, CR to `\r`, `|` to
 * `\p`, `;` to `\s`. An unknown escape keeps both characters rather than
 * guessing. Because LF is escaped, no value can break a line in two.
 *
 * **The order of the lines is fixed**, and that is the point of the design:
 * the same records must give the same bytes whatever order they arrive in,
 * or every save looks like a change. Sorted by
 *
 *   1. the **instant** of `start`, ascending -- the instant, not the local
 *      time, so a record booked in another offset does not jump the queue;
 *   2. ties broken by the **encoded line as UTF-8 bytes**, unsigned, the
 *      shorter first on a common prefix.
 *
 * Rule 2 is a total order, so the result cannot depend on the input order
 * even when one record id appears twice -- which is what a bad merge
 * produces, and which the journal shows rather than repairs.
 *
 * **UTF-8 bytes, not Kotlin's `String` order.** A JVM `String` compares by
 * UTF-16 code unit, which sorts everything above U+FFFF below the
 * U+E000..U+FFFF range because of the surrogates. Byte order and code-point
 * order agree with each other and not with that. The shared fixture carries
 * exactly that pair of characters so the mistake cannot pass unnoticed.
 */

/** Where a record came from. The names are part of the file format. */
enum class JournalSource { MANUAL, TIMER, QUICK, IMPORTED }

/**
 * One stretch of work, as the journal represents it.
 *
 * Named for the journal rather than for the domain because the desktop has no
 * time log of its own: this is what a line of the file means, not a model the
 * rest of the program keeps. Keyed by the task **uid**, never the task id --
 * the id is a per-project counter and collides the moment two projects meet.
 */
data class JournalRecord(
  val id: String,
  val taskUid: String,
  val start: OffsetDateTime,
  val durationSeconds: Long,
  val source: JournalSource = JournalSource.MANUAL,
  val person: String? = null,
  val note: String = "",
  val createdAt: OffsetDateTime = start
) {
  /** The calendar day this counts towards. The zone has no default: see below. */
  fun dateIn(zone: ZoneId): LocalDate = start.atZoneSameInstant(zone).toLocalDate()
}

/** Why a journal could not be read at all. Null means it was fine. */
enum class JournalProblem { NO_HEADER, UNKNOWN_VERSION }

/** What reading produced. [skippedLines] is carried, never swallowed. */
data class JournalRead(
  val records: List<JournalRecord>,
  val skippedLines: Int,
  val problem: JournalProblem?
)

/**
 * What writing produced. [refused] holds records that could not have been
 * read back -- blank record id or blank task uid. Writing them would produce
 * a file that loses records on the next read; dropping them quietly would
 * hide it.
 */
data class JournalWrite(val text: String, val refused: List<JournalRecord>)

/** What happened to a record between two states of the log. */
enum class ChangeKind { ADDED, REMOVED, CHANGED }

/** Everything that happened to one task, for one person, on one day. */
data class ChangeLine(
  val kind: ChangeKind,
  /** Signed: positive added, negative removed, the delta for a change. */
  val seconds: Long,
  val label: String,
  val person: String?,
  val day: LocalDate
)

/**
 * The short list a commit carries.
 *
 * Structured rather than text, so the wording can be replaced without
 * touching what it is about. [omittedLines] and [omittedSeconds] are what the
 * cap left out, carried so the rendering can say so: a list that is short
 * because it lied is not short.
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

  private const val FIELD = '|'
  private const val RECORD_IN_GAN = ';'

  // --------------------------------------------------------------- Escaping

  private fun escape(text: String): String = buildString(text.length) {
    for (ch in text) {
      when (ch) {
        '\\' -> append("\\\\")
        '\t' -> append("\\t")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        FIELD -> append("\\p")
        RECORD_IN_GAN -> append("\\s")
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
        'p' -> append(FIELD)
        's' -> append(RECORD_IN_GAN)
        // An escape we do not know: keep both characters rather than guess.
        // Swallowing a backslash would corrupt a note for good.
        else -> { append(ch); append(next) }
      }
      i += 2
    }
  }

  /** The format's tie-break. See the note at the top for why not `compareTo`. */
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

  // ---------------------------------------------------------------- Writing

  fun encodeLine(record: JournalRecord): String = listOf(
    record.id,
    record.taskUid,
    record.start.toString(),
    record.durationSeconds.toString(),
    record.source.name,
    record.person.orEmpty(),
    record.note,
    record.createdAt.toString()
  ).joinToString(FIELD.toString()) { escape(it) }

  fun write(records: List<JournalRecord>): JournalWrite {
    val refused = mutableListOf<JournalRecord>()
    val kept = mutableListOf<Pair<JournalRecord, String>>()
    for (record in records) {
      if (record.id.isBlank() || record.taskUid.isBlank()) {
        refused.add(record)
        continue
      }
      kept.add(record to encodeLine(record))
    }
    val ordered = kept.sortedWith(
      compareBy<Pair<JournalRecord, String>> { it.first.start.toInstant() }
        .thenComparator { a, b -> compareUtf8(a.second, b.second) }
    )
    val text = buildString {
      append(HEADER).append('\n')
      ordered.forEach { append(it.second).append('\n') }
    }
    return JournalWrite(text, refused)
  }

  // ---------------------------------------------------------------- Reading

  /** One line, or null when it cannot be read. Null, not an exception: one
   *  damaged line must not take a whole journal down. */
  fun decodeLine(line: String): JournalRecord? {
    if (line.isBlank()) return null
    val fields = line.split(FIELD).map { unescape(it) }
    if (fields.size < 4) return null

    val id = fields[0]
    val taskUid = fields[1]
    if (id.isBlank() || taskUid.isBlank()) return null
    val start = parseTime(fields[2]) ?: return null
    val seconds = fields[3].trim().toLongOrNull() ?: return null

    val source = fields.getOrNull(4)
      ?.let { name -> JournalSource.entries.firstOrNull { it.name == name } }
      ?: JournalSource.MANUAL
    return JournalRecord(
      id = id,
      taskUid = taskUid,
      start = start,
      durationSeconds = seconds,
      source = source,
      person = fields.getOrNull(5)?.takeIf { it.isNotBlank() },
      note = fields.getOrNull(6).orEmpty(),
      createdAt = fields.getOrNull(7)?.let { parseTime(it) } ?: start
    )
  }

  private fun parseTime(text: String): OffsetDateTime? =
    try {
      OffsetDateTime.parse(text.trim())
    } catch (e: DateTimeParseException) {
      null
    }

  fun read(text: String?): JournalRead {
    if (text == null) return JournalRead(emptyList(), 0, JournalProblem.NO_HEADER)
    // A trailing CR is stripped so a file that went through a Windows
    // checkout still reads. Only LF is ever written.
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
      .mapNotNull { line -> decodeLine(line).also { if (it == null) skipped++ } }
    return JournalRead(records, skipped, null)
  }

  // ---------------------------------------------------------------- Merging

  /**
   * Combines two sets, keeping one copy of each record id, [preferred]
   * winning. This is what makes restoring safe: the same journal read twice
   * does not book the hours twice.
   */
  fun merge(preferred: List<JournalRecord>, other: List<JournalRecord>): List<JournalRecord> {
    val byId = LinkedHashMap<String, JournalRecord>()
    other.forEach { byId[it.id] = it }
    preferred.forEach { byId[it.id] = it }
    return byId.values.sortedWith(
      compareBy<JournalRecord> { it.start.toInstant() }
        .thenComparator { a, b -> compareUtf8(encodeLine(a), encodeLine(b)) }
    )
  }

  /** Seconds booked per task. The only way a per-task total should be made. */
  fun secondsByTaskUid(records: List<JournalRecord>): Map<String, Long> =
    records.filter { it.durationSeconds > 0L && it.taskUid.isNotBlank() }
      .groupBy { it.taskUid }
      .mapValues { (_, group) -> group.sumOf { it.durationSeconds } }

  // ------------------------------------------------------------ Change list

  /**
   * What came in, went out or changed between two states.
   *
   * Compared by record id, the only stable handle a record has. An id on both
   * sides whose content differs is a change, and its seconds are the
   * **delta** -- a commit list is about what moved, not about totals.
   *
   * Grouped by kind, task, person and day, ordered by size so the cap keeps
   * what matters, then cut to [maxLines]. Ties break on day, label and person
   * so the result never depends on the order the records arrived in.
   */
  fun changeSummary(
    before: List<JournalRecord>,
    after: List<JournalRecord>,
    zone: ZoneId,
    maxLines: Int = 3,
    taskName: (String) -> String? = { null }
  ): ChangeSummary {
    val beforeById = before.groupBy { it.id }
    val afterById = after.groupBy { it.id }

    fun representative(group: List<JournalRecord>): JournalRecord =
      group.minWithOrNull { a, b -> compareUtf8(encodeLine(a), encodeLine(b)) } ?: group.first()

    fun sameContent(a: List<JournalRecord>, b: List<JournalRecord>): Boolean =
      a.map { encodeLine(it) }.sortedWith(::compareUtf8) ==
        b.map { encodeLine(it) }.sortedWith(::compareUtf8)

    data class Key(val kind: ChangeKind, val taskUid: String, val person: String?, val day: LocalDate)

    val grouped = LinkedHashMap<Key, Pair<Long, JournalRecord>>()
    fun add(kind: ChangeKind, seconds: Long, sample: JournalRecord) {
      val key = Key(kind, sample.taskUid, sample.person, sample.dateIn(zone))
      val existing = grouped[key]
      grouped[key] =
        if (existing == null) seconds to sample else (existing.first + seconds) to existing.second
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
          label = taskName(key.taskUid) ?: sample.note.ifBlank { key.taskUid },
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
   * Hours for the change list: signed, hundredths, decimal comma.
   *
   * **This rounds, and the journal deliberately never does.** The journal is
   * what somebody may have to vouch for; this is a headline on a commit. A
   * non-zero amount never rounds down to nothing, because "+0 h" beside work
   * that happened reads as "nothing happened" -- the one thing the line must
   * not say. The overstatement is at most eighteen seconds.
   */
  fun hoursText(seconds: Long): String {
    val sign = if (seconds < 0) "-" else if (seconds > 0) "+" else ""
    val magnitude = abs(seconds)
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
   * The change list as a commit carries it.
   *
   * The only words a person reads in this file, and they are German because
   * that is the language of the people whose hours these are. Anything
   * needing other wording can render [ChangeSummary] itself.
   */
  fun renderChangeList(summary: ChangeSummary): String {
    val body = summary.lines.map { render(it) }
    val tail =
      if (summary.omittedLines == 0) emptyList()
      else listOf("… und ${summary.omittedLines} weitere (${hoursText(summary.omittedSeconds)} h)")
    return (body + tail).joinToString("\n")
  }

  /**
   * Subject with the timestamp, then the list. Null when there is nothing to
   * commit -- the ordinary outcome of saving a project whose hours did not
   * change, and the caller has to be able to tell, or the repository fills
   * with empty commits.
   *
   * [at] is passed in rather than read from a clock, so the same input always
   * gives the same output.
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
  /** The store already held exactly this. Nothing was committed. */
  data object Unchanged : SaveOutcome

  /**
   * Stored. [revision] names the version created where the store has versions
   * and is null where it does not. It is the handle a restore needs later,
   * which is why it is here from the start.
   */
  data class Saved(val revision: String?) : SaveOutcome

  data class Failed(val reason: String) : SaveOutcome
}

/**
 * Somewhere a journal can be kept.
 *
 * Deliberately this small: take this text and this message, keep it; hand it
 * back when asked. It knows nothing of the format, so it cannot form an
 * opinion about the records, and nothing of git, so a store that is not a
 * repository is not a special case.
 *
 * **The interface exists before its second implementation on purpose.** That
 * is normally the wrong way round, but the second one is not hypothetical
 * here: a repository is what works today without anybody's agreement, and the
 * time-tracking service this is meant to be offered to is what would sit
 * behind it instead. The program already has the pattern -- `Storage` carries
 * `LocalStorage`, `WebdavStorage` and `GPCloudStorage` side by side.
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
 * account. [lastMessage] is kept only so a test can see the message arrived;
 * a plain file has nowhere to put it.
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
        // cannot leave half a journal behind.
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
      GPLogger.log("Could not write the hour journal to ${file.path}: ${e.message}")
      SaveOutcome.Failed(e.message ?: e.javaClass.simpleName)
    }
}
