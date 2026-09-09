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
 * [fork change] The hour journal, desktop side, format version 2.
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
 * WHAT VERSION 2 ADDED, AND WHY -- 09.09.2026
 * ------------------------------------------------------------------------
 *
 * A version 1 journal cannot say which undertaking an hour belongs to. Its
 * eight fields carry what was done, when, for how long and by whom, and
 * nothing about what the work was booked against. A journal is meant to be a
 * record somebody can be held to, and a record that needs the plan file beside
 * it to be understood is not one: the moment the two are apart, the hours are
 * unattributable.
 *
 * Version 2 therefore adds a ninth field, [JournalEntry.tags] -- a list of
 * marks. **The journal stores them and does not understand them.** Whether
 * `V203` denotes a funded undertaking, a customer or a shift is decided by
 * whoever reads the file. Nothing in this file may ever branch on the content
 * of a mark.
 *
 * **A record without a mark is written, and reported.** It is not refused.
 * Refusing would make the journal *incomplete*; accepting and reporting makes
 * it *visibly incomplete*, and only the second can be repaired -- somebody can
 * go and add the missing mark, whereas hours that were never written are gone.
 * The count is carried in [JournalRead.unmarked] and [JournalWrite.unmarked]
 * and, where nobody can miss it, in the commit message; see
 * [TimeJournal.renderUnmarkedNote].
 *
 * **Why the marks are a list and not one string.** Measured, not assumed: the
 * reporting tool this feeds already treats an entry's tags as a list and
 * relies on it. It allows a foreign tag beside exactly one undertaking tag,
 * and it treats two undertaking tags on one entry as a *finding to report*.
 * Both cases need a journal that can hold more than one mark: with room for
 * only one, this file would have to choose which tag to keep, and choosing
 * means deciding what a mark means -- the one thing it must not do. The second
 * case is also the familiar rule of this format: a contradiction is made
 * visible, not repaired, exactly as a duplicate record id is.
 *
 * **The marks never touch the project file.** They live on [JournalEntry],
 * beside the record, never on [JournalRecord] itself, so nothing that carries
 * a record around can drop a mark on the way.
 *
 * ------------------------------------------------------------------------
 * WHAT THE DESKTOP CAN AND CANNOT DO WITH THIS TODAY -- measured 09.09.2026
 * ------------------------------------------------------------------------
 *
 * The desktop has **no per-record time log**. It keeps a per-task total in
 * `effort_actual_hours` and an import ledger of entry id to hours
 * ([encodeImportLedger]), and that ledger holds neither a start time nor a
 * person. So the desktop can read any journal in full, and can write one from
 * records it is handed, but it **cannot reconstruct records from a plan file
 * it opens** -- that information was never stored on this side. Records can
 * only be produced here at import time, while the tracker's entries are still
 * in hand.
 *
 * That is also where a mark would come from on this side: the tracker's own
 * tags, passed straight through. Nothing here invents one.
 *
 * ------------------------------------------------------------------------
 * THE FORMAT, version 2
 * ------------------------------------------------------------------------
 *
 * UTF-8 text. Lines end with a single LF, and the file always ends with one,
 * including the empty journal, so appending cannot damage the previous line.
 *
 * **Line 1 is exactly [HEADER].** A first line of [HEADER_V1] is a version 1
 * journal and is **still read**; see below. Anything else is refused rather
 * than guessed at, and a first line that starts with [HEADER_PREFIX] but
 * matches no known header is a journal of an unknown version, reported as
 * that, with the version number it claims.
 *
 * **Every later line is one record**, nine `|`-separated fields:
 * `record`, `task`, `start`, `seconds`, `source`, `person`, `note`,
 * `created`, `tags`. Fields past the ninth are ignored and missing ones
 * defaulted, so new fields may be appended at the end -- and nowhere else.
 * **Version 2 is what that promise was for**, and keeping it is why version 1
 * is still read.
 *
 * **Escaping:** `\` to `\\`, tab to `\t`, LF to `\n`, CR to `\r`, `|` to
 * `\p`, `;` to `\s`. An unknown escape keeps both characters rather than
 * guessing. Because LF is escaped, no value can break a line in two.
 *
 * **Field nine is escaped mark by mark, then joined with a raw `;`.** The
 * separator is safe precisely because `;` is in the escape table already: a
 * mark that contains one is written `\s` and can never be mistaken for the
 * separator. It is also visible, which a tab would not be, and it is not a
 * comma, which free text is full of.
 *
 * **The marks of one record are a set, written in one canonical order**: a
 * blank mark is not a mark and is dropped, a repeated mark is written once,
 * and what is left is sorted by the same UTF-8 byte rule the lines use. Two
 * devices that produce the same marks in different orders must produce the
 * same bytes, or every save is a commit. Nothing is trimmed: a mark is a
 * string the journal does not interpret, so ` V203 ` and `V203` are two
 * different marks, while a mark of nothing but whitespace has no content.
 *
 * **What "empty" and "absent" mean, and how they are told apart.** A ninth
 * field that is present and empty says "this record carries no marks". A line
 * with no ninth field at all -- every line of a version 1 file, or a truncated
 * line of a version 2 one -- says nothing about marks, because there was
 * nowhere to say it. Both read as an empty list and both count towards
 * [JournalRead.unmarked], which is the number that has to go to zero either
 * way. The difference is carried **once, for the whole file**, by
 * [JournalRead.version]: version 1 could not say, version 2 chose not to. It
 * belongs to the file and not to the record, because it is a property of the
 * format the file was written in.
 *
 * Writing a version 1 journal back out therefore produces a version 2 file
 * whose ninth fields are all empty, turning "could not say" into "says
 * nothing". That is unavoidable -- a version 2 file has to write *something*
 * there -- and it is why the upgrade is loud: every record shows up in
 * [JournalWrite.unmarked].
 *
 * **The order of the lines is fixed**, and that is the point of the design:
 * the same records must give the same bytes whatever order they arrive in,
 * or every save looks like a change. Sorted by
 *
 *   1. the **instant** of `start`, ascending -- the instant, not the local
 *      time, so a record booked in another offset does not jump the queue;
 *   2. ties broken by the **encoded line as UTF-8 bytes**, unsigned, the
 *      shorter first on a common prefix. The whole line, marks included.
 *
 * Rule 2 is a total order, so the result cannot depend on the input order
 * even when one record id appears twice -- which is what a bad merge
 * produces, and which the journal shows rather than repairs.
 *
 * **UTF-8 bytes, not Kotlin's `String` order.** A JVM `String` compares by
 * UTF-16 code unit, which sorts everything above U+FFFF below the
 * U+E000..U+FFFF range because of the surrogates. Byte order and code-point
 * order agree with each other and not with that. The shared fixture carries
 * exactly that pair of characters, in a note and in a mark, so the mistake
 * cannot pass unnoticed.
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

/**
 * A record together with the marks the journal carries for it.
 *
 * The marks sit here rather than on [JournalRecord] so that what happened and
 * what it is booked against stay separate: they are a different kind of fact,
 * they can be decided by a different person, and nothing that passes a record
 * around can lose one on the way. The app side keeps exactly the same shape,
 * for the same reason and so that the two can be read side by side.
 *
 * @param tags the marks, as strings the journal never interprets. May be
 *   empty, which is a gap somebody has to close, not an error.
 */
data class JournalEntry(val record: JournalRecord, val tags: List<String> = emptyList()) {

  /** The marks as the file carries them: blank-free, duplicate-free, ordered. */
  fun canonicalTags(): List<String> = TimeJournal.canonicalTags(tags)

  /** True when this record says nothing about what it is booked against. */
  val isUnmarked: Boolean get() = canonicalTags().isEmpty()
}

/** Why a journal could not be read at all. Null means it was fine. */
enum class JournalProblem { NO_HEADER, UNKNOWN_VERSION }

/**
 * What reading produced. [skippedLines] and [unmarked] are carried, never
 * swallowed: something that quietly vanished is worse than a visible gap,
 * because nobody goes looking for hours -- or for an attribution -- they do
 * not know are missing.
 *
 * [version] is the format version of the file that was read, or null when
 * there was no readable header. It is also set when the version is one this
 * code does not know, so a message can name it.
 */
data class JournalRead(
  val entries: List<JournalEntry>,
  val skippedLines: Int,
  val problem: JournalProblem?,
  val version: Int?,
  /** How many of [entries] carry no mark at all. */
  val unmarked: Int
)

/**
 * What writing produced. [refused] holds records that could not have been
 * read back -- blank record id or blank task uid. Writing them would produce
 * a file that loses records on the next read; dropping them quietly would
 * hide it.
 *
 * [unmarked] holds the entries that **were** written but carry no mark, in
 * the order the file has them. Named and not merely counted, because a gap is
 * closed by going to the records it is in. A refused record never appears
 * here: it is not in the file at all, so it is not a gap in the file.
 */
data class JournalWrite(
  val text: String,
  val refused: List<JournalEntry>,
  val unmarked: List<JournalEntry>
)

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

  /** The format version this code writes. */
  const val VERSION = 2

  /** The exact first line of a version 2 journal. */
  const val HEADER = "#gp-timelog 2 record|task|start|seconds|source|person|note|created|tags"

  /** The exact first line of a version 1 journal. Still read, never written. */
  const val HEADER_V1 = "#gp-timelog 1 record|task|start|seconds|source|person|note|created"

  /** What every journal header starts with, whatever its version. */
  const val HEADER_PREFIX = "#gp-timelog "

  private val KNOWN_HEADERS = mapOf(HEADER_V1 to 1, HEADER to 2)

  private const val FIELD = '|'
  private const val RECORD_IN_GAN = ';'

  /**
   * What separates two marks inside field nine.
   *
   * Deliberately the character that is already escaped as the record
   * separator of a plan-file chunk: because it is in the escape table, a mark
   * containing one is written `\s` and can never be read as a separator. A
   * comma would be unsafe -- free text is full of them -- and a tab would be
   * invisible to whoever opens the file to read it.
   */
  private const val TAG_SEPARATOR = RECORD_IN_GAN

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

  /**
   * The marks of one record in the one form the file may hold them in.
   *
   * Blank marks dropped -- a mark of nothing is not a mark, and keeping one
   * would make a record look attributed when it is not. Repeats dropped --
   * the same mark twice says nothing twice. Then sorted by the format's byte
   * rule, so the same set of marks always produces the same bytes whichever
   * order they were handed over in.
   */
  fun canonicalTags(tags: List<String>): List<String> =
    tags.filter { it.isNotBlank() }.distinct().sortedWith(::compareUtf8)

  private fun encodeTags(tags: List<String>): String =
    canonicalTags(tags).joinToString(TAG_SEPARATOR.toString()) { escape(it) }

  /** The eight fields a record has carried since version 1. */
  private fun encodeRecordFields(record: JournalRecord): String = listOf(
    record.id,
    record.taskUid,
    record.start.toString(),
    record.durationSeconds.toString(),
    record.source.name,
    record.person.orEmpty(),
    record.note,
    record.createdAt.toString()
  ).joinToString(FIELD.toString()) { escape(it) }

  /** One line of the file: the record's eight fields, then the marks. */
  fun encodeLine(entry: JournalEntry): String =
    encodeRecordFields(entry.record) + FIELD + encodeTags(entry.tags)

  fun write(entries: List<JournalEntry>): JournalWrite {
    val refused = mutableListOf<JournalEntry>()
    val kept = mutableListOf<Pair<JournalEntry, String>>()
    for (entry in entries) {
      // Exactly the two things a reader cannot recover from. A missing mark
      // is NOT one of them -- see the note at the top.
      if (entry.record.id.isBlank() || entry.record.taskUid.isBlank()) {
        refused.add(entry)
        continue
      }
      kept.add(entry to encodeLine(entry))
    }
    val ordered = kept.sortedWith(
      compareBy<Pair<JournalEntry, String>> { it.first.record.start.toInstant() }
        .thenComparator { a, b -> compareUtf8(a.second, b.second) }
    )
    val text = buildString {
      append(HEADER).append('\n')
      ordered.forEach { append(it.second).append('\n') }
    }
    // Taken from the ordered list, not the input, so the report comes out in
    // the order the file has -- the order somebody will read it in.
    return JournalWrite(text, refused, ordered.map { it.first }.filter { it.isUnmarked })
  }

  // ---------------------------------------------------------------- Reading

  /**
   * One line, or null when it cannot be read. Null, not an exception: one
   * damaged line must not take a whole journal down.
   *
   * [version] decides whether a ninth field is looked at. In a version 1 file
   * it is not: version 1 promised that fields past the eighth are ignored, and
   * a version 2 reader keeps that promise, or "version 1 could not say" would
   * become untrue the moment somebody hand-edited an old file.
   */
  fun decodeLine(line: String, version: Int = VERSION): JournalEntry? {
    if (line.isBlank()) return null
    // Split on the raw separator: an escaped '|' is written '\p' and never
    // appears here, so the split is unambiguous. The marks field is kept as
    // it stands, because its own separators have to be found before the
    // escapes inside the individual marks are undone.
    val raw = line.split(FIELD)
    val fields = raw.map { unescape(it) }
    if (fields.size < 4) return null

    val id = fields[0]
    val taskUid = fields[1]
    if (id.isBlank() || taskUid.isBlank()) return null
    val start = parseTime(fields[2]) ?: return null
    val seconds = fields[3].trim().toLongOrNull() ?: return null

    val source = fields.getOrNull(4)
      ?.let { name -> JournalSource.entries.firstOrNull { it.name == name } }
      ?: JournalSource.MANUAL
    val record = JournalRecord(
      id = id,
      taskUid = taskUid,
      start = start,
      durationSeconds = seconds,
      source = source,
      person = fields.getOrNull(5)?.takeIf { it.isNotBlank() },
      note = fields.getOrNull(6).orEmpty(),
      createdAt = fields.getOrNull(7)?.let { parseTime(it) } ?: start
    )
    if (version < 2) return JournalEntry(record)
    return JournalEntry(record, decodeTags(raw.getOrNull(8).orEmpty()))
  }

  private fun decodeTags(raw: String): List<String> =
    canonicalTags(raw.split(TAG_SEPARATOR).map { unescape(it) })

  private fun parseTime(text: String): OffsetDateTime? =
    try {
      OffsetDateTime.parse(text.trim())
    } catch (e: DateTimeParseException) {
      null
    }

  /** The version a header claims, whether or not this code knows it. */
  private fun claimedVersion(header: String): Int? =
    header.removePrefix(HEADER_PREFIX).substringBefore(' ').trim().toIntOrNull()

  fun read(text: String?): JournalRead {
    if (text == null) return JournalRead(emptyList(), 0, JournalProblem.NO_HEADER, null, 0)
    // A trailing CR is stripped so a file that went through a Windows
    // checkout still reads. Only LF is ever written.
    val lines = text.split('\n').map { it.removeSuffix("\r") }
    val first = lines.firstOrNull().orEmpty()
    val version = KNOWN_HEADERS[first]
    if (version == null) {
      return if (first.startsWith(HEADER_PREFIX)) {
        JournalRead(emptyList(), 0, JournalProblem.UNKNOWN_VERSION, claimedVersion(first), 0)
      } else {
        JournalRead(emptyList(), 0, JournalProblem.NO_HEADER, null, 0)
      }
    }
    var skipped = 0
    val entries = lines.drop(1)
      .filter { it.isNotBlank() }
      .mapNotNull { line -> decodeLine(line, version).also { if (it == null) skipped++ } }
    return JournalRead(entries, skipped, null, version, entries.count { it.isUnmarked })
  }

  // ---------------------------------------------------------------- Merging

  /**
   * Combines two sets, keeping one copy of each record id, [preferred]
   * winning. This is what makes restoring safe: the same journal read twice
   * does not book the hours twice.
   *
   * A whole entry wins or loses, marks included -- taking the record from one
   * side and the marks from the other would invent an attribution nobody made.
   */
  fun merge(preferred: List<JournalEntry>, other: List<JournalEntry>): List<JournalEntry> {
    val byId = LinkedHashMap<String, JournalEntry>()
    other.forEach { byId[it.record.id] = it }
    preferred.forEach { byId[it.record.id] = it }
    return byId.values.sortedWith(
      compareBy<JournalEntry> { it.record.start.toInstant() }
        .thenComparator { a, b -> compareUtf8(encodeLine(a), encodeLine(b)) }
    )
  }

  /** Seconds booked per task. The only way a per-task total should be made. */
  fun secondsByTaskUid(entries: List<JournalEntry>): Map<String, Long> =
    entries.map { it.record }
      .filter { it.durationSeconds > 0L && it.taskUid.isNotBlank() }
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
   * "Content" is the whole encoded line, so **a record whose mark changed is
   * a change** even though no hours moved: re-booking an hour from one
   * undertaking to another is exactly the kind of thing a record somebody has
   * to vouch for must not do quietly. It shows up as a change of nought hours.
   *
   * Grouped by kind, task, person and day, ordered by size so the cap keeps
   * what matters, then cut to [maxLines]. Ties break on day, label and person
   * so the result never depends on the order the records arrived in.
   */
  fun changeSummary(
    before: List<JournalEntry>,
    after: List<JournalEntry>,
    zone: ZoneId,
    maxLines: Int = 3,
    taskName: (String) -> String? = { null }
  ): ChangeSummary {
    val beforeById = before.groupBy { it.record.id }
    val afterById = after.groupBy { it.record.id }

    fun representative(group: List<JournalEntry>): JournalEntry =
      group.minWithOrNull { a, b -> compareUtf8(encodeLine(a), encodeLine(b)) } ?: group.first()

    fun sameContent(a: List<JournalEntry>, b: List<JournalEntry>): Boolean =
      a.map { encodeLine(it) }.sortedWith(::compareUtf8) ==
        b.map { encodeLine(it) }.sortedWith(::compareUtf8)

    data class Key(val kind: ChangeKind, val taskUid: String, val person: String?, val day: LocalDate)

    val grouped = LinkedHashMap<Key, Pair<Long, JournalEntry>>()
    fun add(kind: ChangeKind, seconds: Long, sample: JournalEntry) {
      val key = Key(kind, sample.record.taskUid, sample.record.person, sample.record.dateIn(zone))
      val existing = grouped[key]
      grouped[key] =
        if (existing == null) seconds to sample else (existing.first + seconds) to existing.second
    }

    (beforeById.keys + afterById.keys).forEach { id ->
      val old = beforeById[id]
      val new = afterById[id]
      when {
        old == null && new != null ->
          add(ChangeKind.ADDED, new.sumOf { it.record.durationSeconds }, representative(new))
        new == null && old != null ->
          add(ChangeKind.REMOVED, -old.sumOf { it.record.durationSeconds }, representative(old))
        old != null && new != null && !sameContent(old, new) ->
          add(
            ChangeKind.CHANGED,
            new.sumOf { it.record.durationSeconds } - old.sumOf { it.record.durationSeconds },
            representative(new)
          )
      }
    }

    val all = grouped.entries
      .map { (key, value) ->
        val sample = value.second.record
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
   *
   * A change of exactly nought is written as "0" without a sign, and it does
   * occur: a record whose mark changed moved no hours at all.
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
   * One of two places in this file with words a person reads, and they are
   * German because that is the language of the people whose hours these are.
   * Anything needing other wording can render [ChangeSummary] itself.
   *
   * The form is unchanged from version 1, on purpose: it is the form Natalie
   * agreed to, and adding the marks to it was not asked for. A mark that
   * changed still shows up, as a change of nought hours.
   */
  fun renderChangeList(summary: ChangeSummary): String {
    val body = summary.lines.map { render(it) }
    val tail =
      if (summary.omittedLines == 0) emptyList()
      else listOf("… und ${summary.omittedLines} weitere (${hoursText(summary.omittedSeconds)} h)")
    return (body + tail).joinToString("\n")
  }

  /**
   * How the journal says that some of its records carry no mark. Null when
   * none do -- there is nothing to report, and a line saying "0" every time
   * would train everybody to skip the place the real number appears.
   *
   * The word is "Marke" and not "Vorhaben" deliberately. The journal does not
   * know that a mark names an undertaking; calling it one here would be this
   * file deciding what the string means, which is the one thing it must not
   * do. "Marke" is also the word Natalie used.
   */
  fun renderUnmarkedNote(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "1 Satz ohne Marke"
    else -> "$count Sätze ohne Marke"
  }

  /**
   * Subject with the timestamp, then the list, then -- when there are any --
   * how many records carry no mark. Null when there is nothing to commit,
   * which is the ordinary outcome of saving a project whose hours did not
   * change; the caller has to be able to tell, or the repository fills with
   * empty commits.
   *
   * [unmarkedRecords] is the count for the **whole journal being written**,
   * not just for what changed. The commit records the state of the file, and
   * the question the note answers -- "is this proof complete?" -- is about the
   * file. It goes here, rather than only into [JournalWrite], because this is
   * the one place nobody can avoid seeing it: it lands in the history of the
   * repository the proof is kept in and stays there.
   *
   * [at] is passed in rather than read from a clock, so the same input always
   * gives the same output.
   */
  fun commitMessage(
    at: OffsetDateTime,
    summary: ChangeSummary,
    zone: ZoneId,
    unmarkedRecords: Int = 0
  ): String? {
    if (summary.isEmpty) return null
    val local = at.atZoneSameInstant(zone)
    val subject = "Stunden %02d.%02d.%04d %02d:%02d".format(
      local.dayOfMonth, local.monthValue, local.year, local.hour, local.minute
    )
    val note = renderUnmarkedNote(unmarkedRecords)?.let { "\n$it\n" }.orEmpty()
    return subject + "\n\n" + renderChangeList(summary) + "\n" + note
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
