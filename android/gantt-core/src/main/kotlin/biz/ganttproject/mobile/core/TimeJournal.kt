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
 * WHAT VERSION 2 ADDED, AND WHY -- 09.09.2026
 * ------------------------------------------------------------------------
 *
 * A version 1 journal cannot say which undertaking an hour belongs to. Its
 * eight fields carry what was done, when, for how long and by whom, and
 * nothing about what the work was booked against. A journal is meant to be a
 * record somebody can be held to, and a record that needs the `.gan` beside it
 * to be understood is not one: the moment the two are apart, the hours are
 * unattributable.
 *
 * Version 2 therefore adds a ninth field, [JournalEntry.tags] -- a list of
 * marks. **The journal stores them and does not understand them.** Whether
 * `V203` denotes a funded undertaking, a customer or a shift is decided by
 * whoever reads the file, exactly as [TimeLogExport.toTogglV2Json] already
 * says of the same strings. Nothing in this file may ever branch on the
 * content of a mark.
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
 * relies on it. It allows a foreign tag beside exactly one undertaking tag
 * (`("V306", "abends")` is accepted), and it treats two undertaking tags on
 * one entry as a *finding to report* (`("V306", "V309")`). Both cases need a
 * journal that can hold more than one mark: with room for only one, this file
 * would have to choose which tag to keep, and choosing means deciding what a
 * mark means -- the one thing it must not do. The second case is also the
 * familiar rule of this format: a contradiction is made visible, not repaired,
 * exactly as a duplicate record id is.
 *
 * **The marks never touch the `.gan`.** They live on [JournalEntry], beside
 * the record, never on [TimeRecord] itself. If they were a field of the
 * record, saving a project and reading it back would drop them silently,
 * because the `.gan` has nowhere to put them -- and a silent loss is what this
 * whole module exists to prevent.
 *
 * ------------------------------------------------------------------------
 * THE FORMAT, version 2 -- written out in full because a second, independent
 * implementation has to match it byte for byte.
 * ------------------------------------------------------------------------
 *
 * A journal is a UTF-8 text file. Lines end with a single LF. The file always
 * ends with an LF, including the empty journal, so that appending a line
 * cannot damage the line before it.
 *
 * **Line 1 is the header** and is exactly [HEADER]. A file whose first line is
 * [HEADER_V1] is a version 1 journal and is **still read**; see below. Any
 * other first line is refused, not guessed at: refusing names the problem,
 * while a partial read of a file in some other format loses hours silently.
 * When the first line begins with [HEADER_PREFIX] but matches no known header,
 * the file is a journal of a version this code does not know, and is reported
 * as such, with the version number it claims.
 *
 * The header names the fields, so the file explains itself to whoever opens it
 * in a browser. It carries the version for the same reason: a format change
 * changes the header, and every reader notices at once.
 *
 * **Every later line is one record**: nine fields separated by `|`. The first
 * eight are exactly the eight of [TimeLogCodec.encodeRecord]:
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
 *   9. `tags`    - the marks, `;`-separated. May be empty.
 *
 * **Escaping is [TimeLogCodec]'s, unchanged**: `\` becomes `\\`, tab `\t`,
 * LF `\n`, CR `\r`, `|` becomes `\p` and `;` becomes `\s`. Sharing the table
 * rather than inventing a second one means a journal line and a `.gan` chunk
 * are the same text, and there is one escaping to get right instead of two.
 * Because LF is escaped, no value can ever break a line in two.
 *
 * **Field nine is escaped mark by mark, then joined with a raw `;`.** The
 * separator is safe precisely because `;` is in the escape table already: a
 * mark that contains one is written `\s` and can never be mistaken for the
 * separator. It is also visible, which a tab would not be, and it is not a
 * comma, which free text is full of.
 *
 * A reader ignores fields beyond the ninth and defaults missing ones. New
 * fields are appended at the end and nowhere else, so an older reader drops
 * what it cannot use instead of refusing the file. **Version 2 is what that
 * promise was for**, and keeping it is why version 1 is still read.
 *
 * **The marks of one record are a set, written in one canonical order**: a
 * blank mark is not a mark and is dropped, a repeated mark is written once,
 * and what is left is sorted by the same UTF-8 byte rule the lines use. Two
 * devices that produce the same marks in different orders must produce the
 * same bytes, or every save is a commit -- the same reason the lines are
 * sorted at all. Nothing is trimmed: a mark is a string the journal does not
 * interpret, so ` V203 ` and `V203` are two different marks, while a mark of
 * nothing but whitespace has no content to keep.
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
 * A consequence worth naming: writing a version 1 journal back out produces a
 * version 2 file whose ninth fields are all empty, which turns "could not say"
 * into "says nothing". That is unavoidable -- a version 2 file has to write
 * *something* there -- and it is why the upgrade is loud: every record shows
 * up in [JournalWrite.unmarked].
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
 *      byte by byte, the shorter one first on a common prefix. The whole line,
 *      marks included.
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
 * deliberately, in a note and in a mark.
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
 * A record together with the marks the journal carries for it.
 *
 * The marks sit here rather than on [TimeRecord] on purpose. A [TimeRecord]
 * travels through the `.gan`, which has no field for a mark; a mark stored
 * there would be dropped by the next save-and-reopen without anybody noticing.
 * Keeping them beside the record also says what they are: not a property of
 * the work, but of what the work is booked against, which is a different kind
 * of fact and can be decided by a different person.
 *
 * @param tags the marks, as strings the journal never interprets. May be
 *   empty, which is a gap somebody has to close, not an error; see the note
 *   at the top of this file.
 */
data class JournalEntry(val record: TimeRecord, val tags: List<String> = emptyList()) {

  /** The marks as the file carries them: blank-free, duplicate-free, ordered. */
  fun canonicalTags(): List<String> = TimeJournal.canonicalTags(tags)

  /** True when this record says nothing about what it is booked against. */
  val isUnmarked: Boolean get() = canonicalTags().isEmpty()
}

/**
 * What reading a journal produced.
 *
 * [skippedLines] and [unmarked] are part of the result rather than log lines,
 * for the same reason the skipped count is in [TimeLogCodec.DecodeResult]:
 * something that quietly vanished is worse than a visible gap, because nobody
 * goes looking for hours -- or for an attribution -- they do not know are
 * missing. A caller has to take the number in its hand to ignore it.
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
 * What writing a journal produced.
 *
 * [refused] holds the records that were left out because they could not have
 * been read back -- a blank record id or a blank task uid. Writing them would
 * produce a file that loses records on the next read, and dropping them
 * without saying so would hide it.
 *
 * [unmarked] holds the entries that **were** written but carry no mark, in the
 * order the file has them. They are named and not merely counted, because a
 * gap is closed by going to the records it is in. A refused record never
 * appears here: it is not in the file at all, so it is not a gap in the file.
 */
data class JournalWrite(
  val text: String,
  val refused: List<JournalEntry>,
  val unmarked: List<JournalEntry>
)

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

  /** The format version this code writes. */
  const val VERSION = 2

  /** The exact first line of a version 2 journal. */
  const val HEADER = "#gp-timelog 2 record|task|start|seconds|source|person|note|created|tags"

  /** The exact first line of a version 1 journal. Still read, never written. */
  const val HEADER_V1 = "#gp-timelog 1 record|task|start|seconds|source|person|note|created"

  /** What every journal header starts with, whatever its version. */
  const val HEADER_PREFIX = "#gp-timelog "

  private val KNOWN_HEADERS = mapOf(HEADER_V1 to 1, HEADER to 2)

  /**
   * What separates two marks inside field nine.
   *
   * Deliberately the character [TimeLogCodec] already escapes as the record
   * separator of a `.gan` chunk: because it is in the escape table, a mark
   * containing one is written `\s` and can never be read as a separator. A
   * comma would be unsafe -- free text is full of them -- and a tab would be
   * invisible to whoever opens the file to read it.
   */
  private const val TAG_SEPARATOR = TimeLogCodec.RECORD

  // ------------------------------------------------------------- Writing

  /**
   * Orders strings exactly as the format demands, comparing them as UTF-8
   * bytes. See the note at the top of the file for why not `String`.
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

  /**
   * The marks of one record in the one form the file may hold them in.
   *
   * Blank marks dropped -- a mark of nothing is not a mark, and keeping one
   * would make a record look attributed when it is not. Repeats dropped --
   * the same mark twice says nothing twice. Then sorted by the format's byte
   * rule, so the same set of marks always produces the same bytes whichever
   * order they were handed over in.
   */
  internal fun canonicalTags(tags: List<String>): List<String> =
    tags.filter { it.isNotBlank() }.distinct().sortedWith(::compareUtf8)

  private fun encodeTags(tags: List<String>): String =
    canonicalTags(tags).joinToString(TAG_SEPARATOR.toString()) { TimeLogCodec.escape(it) }

  /** One line of the file: the record's eight fields, then the marks. */
  fun encodeLine(entry: JournalEntry): String =
    TimeLogCodec.encodeRecord(entry.record) + TimeLogCodec.FIELD + encodeTags(entry.tags)

  fun write(entries: List<JournalEntry>): JournalWrite {
    val refused = mutableListOf<JournalEntry>()
    val kept = mutableListOf<Pair<JournalEntry, String>>()
    for (entry in entries) {
      // Exactly the two things a reader cannot recover from, and nothing
      // else: a blank id or task makes decodeRecord return null. A missing
      // mark is NOT one of them -- see the note at the top.
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

  // ------------------------------------------------------------- Reading

  /**
   * One line, or null when it cannot be read.
   *
   * [version] decides whether a ninth field is looked at. In a version 1 file
   * it is not: version 1 promised that fields past the eighth are ignored, and
   * a version 2 reader keeps that promise, or "version 1 could not say" would
   * become untrue the moment somebody hand-edited an old file.
   */
  fun decodeLine(line: String, version: Int = VERSION): JournalEntry? {
    val record = TimeLogCodec.decodeRecord(line) ?: return null
    if (version < 2) return JournalEntry(record)
    // Split on the raw separator and do NOT unescape yet: field nine is a
    // joined list, and its own separators have to be found before the escapes
    // inside the individual marks are undone.
    val raw = line.split(TimeLogCodec.FIELD)
    return JournalEntry(record, decodeTags(raw.getOrNull(8).orEmpty()))
  }

  private fun decodeTags(raw: String): List<String> =
    canonicalTags(raw.split(TAG_SEPARATOR).map { TimeLogCodec.unescape(it) })

  /** The version a header claims, whether or not this code knows it. */
  private fun claimedVersion(header: String): Int? =
    header.removePrefix(HEADER_PREFIX).substringBefore(' ').trim().toIntOrNull()

  fun read(text: String?): JournalRead {
    if (text == null) return JournalRead(emptyList(), 0, JournalProblem.NO_HEADER, null, 0)
    // Split on LF and strip a trailing CR, so a file that travelled through a
    // Windows checkout still reads. Only LF is ever written.
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

  // ------------------------------------------------------------- Merging

  /**
   * Combines two sets of entries, keeping one copy of each record id,
   * [preferred] winning.
   *
   * This is what makes restoring safe: the same journal read twice does not
   * book the hours twice. A whole entry wins or loses, marks included --
   * taking the record from one side and the marks from the other would invent
   * an attribution nobody made.
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

  // --------------------------------------------------------- Change list

  /**
   * What came in, went out or changed between two states of the log.
   *
   * Compared by record id, which is the only stable handle a record has.
   * An id present on both sides whose content differs is a change, and its
   * [ChangeLine.seconds] is the **delta**, not the new total -- a commit list
   * is about what moved.
   *
   * "Content" is the whole encoded line, so **a record whose mark changed is a
   * change** even though no hours moved: re-booking an hour from one
   * undertaking to another is exactly the kind of thing a record somebody has
   * to vouch for must not do quietly. It shows up as a change of nought hours.
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
    before: List<JournalEntry>,
    after: List<JournalEntry>,
    zone: ZoneId,
    maxLines: Int = 3,
    taskName: (String) -> String? = { null }
  ): ChangeSummary {
    val beforeById = before.groupBy { it.record.id }
    val afterById = after.groupBy { it.record.id }

    // Deterministic pick when one id carries several records: the smallest
    // line by the format's own order, so the choice does not depend on input
    // order either.
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
      grouped[key] = if (existing == null) seconds to sample else (existing.first + seconds) to existing.second
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
   *
   * A change of exactly nought is written as "0" without a sign, and it does
   * occur: a record whose mark changed moved no hours at all.
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
   * This is one of two places in this module with words a person reads, and
   * they are German because that is the language of the people whose hours
   * these are. Anything that needs other wording can render [ChangeSummary]
   * itself.
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
   * module deciding what the string means, which is the one thing it must not
   * do. "Marke" is also the word Natalie used.
   */
  fun renderUnmarkedNote(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "1 Satz ohne Marke"
    else -> "$count Sätze ohne Marke"
  }

  /**
   * The whole commit message: a subject with the timestamp, then the list,
   * then -- when there are any -- how many records carry no mark.
   *
   * Null when there is nothing to commit. That is not an edge case but the
   * ordinary outcome of saving a project whose hours did not change, and the
   * caller has to be able to tell, or the repository fills with empty
   * commits.
   *
   * [unmarkedRecords] is the count for the **whole journal being written**,
   * not just for what changed. The commit records the state of the file, and
   * the question the note answers -- "is this proof complete?" -- is about the
   * file. It goes here, rather than only into [JournalWrite], because this is
   * the one place nobody can avoid seeing it: it lands in the history of the
   * repository the proof is kept in and stays there.
   *
   * [at] is passed in rather than read from a clock, like everything else
   * here, so the same input always produces the same output.
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
