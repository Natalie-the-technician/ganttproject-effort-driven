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

import biz.ganttproject.core.calendar.AlwaysWorkingTimeCalendarImpl
import biz.ganttproject.core.calendar.GPCalendarCalc
import biz.ganttproject.core.option.BooleanOption
import biz.ganttproject.core.option.ColorOption
import biz.ganttproject.core.option.DefaultBooleanOption
import biz.ganttproject.core.time.TimeUnitStack
import biz.ganttproject.core.time.impl.GPTimeUnitStack
import biz.ganttproject.customproperty.CustomColumnsManager
import net.sourceforge.ganttproject.gui.NotificationManager
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.task.TaskManagerConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.awt.Color
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * [fork change] The hour journal, desktop side, format version 3 -- and the
 * guard that keeps this implementation and the Android one from drifting
 * apart.
 *
 * The seven `shared-*.timelog` files under `ganttproject-tester/resources`
 * are byte for byte the ones in `android/gantt-core/src/test/resources`.
 * Neither implementation produced them: they were written by hand from the
 * format specification, so a fixture can disagree with an implementation --
 * which is the only way it can catch one. The digests below are pinned
 * identically in the app's `TimeJournalTest`, so a tree that edits its own
 * copy turns its own side red.
 *
 * `shared-v1.timelog` is the version 1 file byte for byte as version 1 wrote
 * it, so that "version 1 still reads" is provable against real bytes. The
 * three `shared-v3-*.timelog` files are the version 3 ones and do the same
 * job for the version that added `recordedBy`: `shared-v3-empty` and
 * `shared-v3-one` are `shared-empty` and `shared-one` one version on, so the
 * upgrade can be checked against bytes rather than against this side's own
 * idea of it, and `shared-v3-many` is `shared-many` plus the two records only
 * version 3 can hold -- one span of work, two people, each entered by
 * somebody else.
 */
class TimeJournalTest {

  private val zone: ZoneId = ZoneId.of("Europe/Berlin")

  private fun fixtureBytes(name: String): ByteArray =
    checkNotNull(javaClass.getResourceAsStream("/$name")) {
      "$name missing from the test classpath"
    }.readBytes()

  private fun fixture(name: String): String = fixtureBytes(name).toString(Charsets.UTF_8)

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  // ------------------------------------------------------- The shared fixture

  /**
   * The digests both sides carry. Copied into the desktop suite verbatim;
   * this is the whole mechanism by which the two are kept from drifting.
   */
  private val sharedDigests = linkedMapOf(
    "shared-empty.timelog" to "6a00802f93d814f60874776e74bbccf4bc506463f93864325ed1769b3468f10b",
    "shared-one.timelog" to "cd702086dd564dc42b574cfe88e71584fdfc3f3faeaad91d78319bf42e545be2",
    "shared-many.timelog" to "ea07d77eda9970a23253f36738910888e9b4cc1da3bb488674c1fbd624c6e1c8",
    "shared-v1.timelog" to "cf837134829e3c2a5c8c58b4bde4a4267bfcc4239a507405936a8a2b743d5ee0",
    "shared-v3-empty.timelog" to "1fd1318efc779089532b938bf68d469ef7c31075c502a962eb5dea207bdde0b0",
    "shared-v3-one.timelog" to "af06ccb46cea4dbc540462ecfbd57e329024740d90265b51b00dc6621e89359a",
    "shared-v3-many.timelog" to "ab4c632a9d26a56d545e646aac6c886ee858fb6918e4ad3694d1cafa4c5266e4"
  )

  @Test
  @DisplayName("the shared fixture is byte for byte the one the app side has")
  fun `the shared fixture is the agreed one`() {
    sharedDigests.forEach { (name, expected) ->
      assertEquals(expected, sha256(fixtureBytes(name)), "$name has been edited on this side only")
    }
  }

  @Test
  @DisplayName("the version 3 header names the recorder and the marks, in that order")
  fun `the header is version 3`() {
    assertEquals(
      "#gp-timelog 3 record|task|start|seconds|source|person|note|created|recordedby|tags",
      TimeJournal.HEADER
    )
    assertEquals(3, TimeJournal.VERSION)
    // The older headers are bytes in files that already exist. They are
    // pinned here rather than merely used, because a typo in one of them
    // turns "still read" into "refused" for every journal ever written.
    assertEquals(
      "#gp-timelog 2 record|task|start|seconds|source|person|note|created|tags",
      TimeJournal.HEADER_V2
    )
    assertEquals(
      "#gp-timelog 1 record|task|start|seconds|source|person|note|created",
      TimeJournal.HEADER_V1
    )
    assertTrue(fixture("shared-v3-many.timelog").startsWith(TimeJournal.HEADER + "\n"))
    assertTrue(fixture("shared-many.timelog").startsWith(TimeJournal.HEADER_V2 + "\n"))
    // `tags` is the last field named in both, and `recordedby` went in front
    // of it rather than after it. That is what moved the marks along a field
    // and why the version had to rise; see the note in TimeJournal.kt.
    assertTrue(TimeJournal.HEADER.endsWith("|recordedby|tags"))
    assertTrue(TimeJournal.HEADER_V2.endsWith("|created|tags"))
  }

  @Test
  @DisplayName("reading the shared journal yields the records and the marks it names")
  fun `the shared journal reads`() {
    val read = TimeJournal.read(fixture("shared-many.timelog"))
    assertNull(read.problem, "the fixture should be a valid journal")
    assertEquals(2, read.version, "the fixture is a version 2 journal")
    assertEquals(0, read.skippedLines, "no line of the fixture may be dropped")
    assertEquals(9, read.entries.size, "nine record lines")
    assertEquals(8, read.entries.map { it.record.id }.toSet().size, "eight distinct ids, one twice")

    // Named individually: "about right" is not a result.
    val byId = read.entries.groupBy { it.record.id }
    assertEquals("Dach decken", byId.getValue("r-001").single().record.note)
    assertEquals(9000L, byId.getValue("r-001").single().record.durationSeconds)
    assertEquals("Berg|mann", byId.getValue("r-002").single().record.person, "a separator in a name")
    assertEquals(
      "Clara\nZweitname", byId.getValue("r-003").single().record.person, "a line break in a name"
    )
    assertEquals("Pfad C:\\temp;Notiz\tEnde", byId.getValue("r-004").single().record.note)
    assertEquals(2, byId.getValue("r-005").size, "the same id twice, kept and not merged away")
    assertNull(byId.getValue("r-009").single().record.person, "an empty person reads back as none")
    assertEquals("", byId.getValue("r-009").single().record.note)
    assertEquals(
      OffsetDateTime.parse("2026-09-10T05:00:30.123456789Z"),
      byId.getValue("r-008").single().record.start,
      "nanoseconds and a Z offset"
    )
    assertEquals(
      ZoneOffset.ofHours(1),
      byId.getValue("r-007").single().record.start.offset,
      "the record's own offset is kept, not normalised away"
    )

    // The marks, each one named.
    assertEquals(listOf("V203"), byId.getValue("r-001").single().tags)
    assertEquals(listOf("V203", "Ümlaut"), byId.getValue("r-007").single().tags, "a non-ASCII mark")
    assertEquals(listOf("V203", "V204"), byId.getValue("r-002").single().tags, "two marks")
    assertEquals(listOf("A;B", "C|D"), byId.getValue("r-004").single().tags, "separators in a mark")
    assertEquals(emptyList<String>(), byId.getValue("r-009").single().tags)
    assertEquals(emptyList<String>(), byId.getValue("r-003").single().tags)
    assertEquals(emptyList<String>(), byId.getValue("r-008").single().tags)
  }

  @Test
  @DisplayName("writing the version 3 journal back out produces the same bytes")
  fun `the shared journal round trips through bytes`() {
    val original = fixtureBytes("shared-v3-many.timelog")
    val entries = TimeJournal.read(original.toString(Charsets.UTF_8)).entries
    val written = TimeJournal.write(entries)
    assertEquals(emptyList<JournalEntry>(), written.refused, "nothing in the fixture may be refused")
    assertEquals(
      original.toString(Charsets.UTF_8),
      written.text,
      "the written journal differs from the shared fixture"
    )
    assertTrue(original.contentEquals(written.text.toByteArray(Charsets.UTF_8)), "bytes differ")
  }

  @Test
  @DisplayName("the empty and the single-record version 3 journals round trip as well")
  fun `the small shared journals round trip`() {
    listOf("shared-v3-empty.timelog", "shared-v3-one.timelog").forEach { name ->
      val original = fixture(name)
      val entries = TimeJournal.read(original).entries
      assertEquals(original, TimeJournal.write(entries).text, "$name does not come back unchanged")
    }
    assertEquals(0, TimeJournal.read(fixture("shared-v3-empty.timelog")).entries.size)
    val one = TimeJournal.read(fixture("shared-v3-one.timelog")).entries.single()
    assertEquals("r-001", one.record.id)
    assertEquals(listOf("V203"), one.tags, "the single-record fixture carries a mark")
    assertNull(one.record.recordedBy, "and Anna entered her own hours")
  }

  @Test
  @DisplayName("writing a version 2 journal back out produces the shared version 3 bytes")
  fun `the version 2 fixtures upgrade to the version 3 ones`() {
    // Not "the upgrade looks plausible": the version 3 files were written by
    // hand from the specification, so this compares what this code produces
    // against bytes neither implementation made.
    listOf("shared-empty.timelog" to "shared-v3-empty.timelog",
      "shared-one.timelog" to "shared-v3-one.timelog").forEach { (old, new) ->
      val entries = TimeJournal.read(fixture(old)).entries
      assertEquals(fixture(new), TimeJournal.write(entries).text, "$old does not upgrade to $new")
    }
    // `shared-v3-many` is `shared-many` one version on plus the two records
    // that only version 3 can carry. Take those two out and what is left is
    // exactly what upgrading the version 2 file has to produce.
    val entries = TimeJournal.read(fixture("shared-many.timelog")).entries
    val expected = fixture("shared-v3-many.timelog")
      .lines()
      .filterNot { it.startsWith("r-010|") || it.startsWith("r-011|") }
      .joinToString("\n")
    assertTrue(expected.length < fixture("shared-v3-many.timelog").length, "two lines were dropped")
    assertEquals(expected, TimeJournal.write(entries).text, "shared-many does not upgrade cleanly")
  }

  // --------------------------------------------------------------- The marks

  @Test
  @DisplayName("a record with a mark keeps it through a round trip")
  fun `a mark survives a round trip`() {
    val entry = JournalEntry(sample("m-1"), listOf("V203"))
    val written = TimeJournal.write(listOf(entry))
    assertTrue(written.text.trimEnd('\n').endsWith("|V203"), "expected the mark last:\n$written")
    val back = TimeJournal.read(written.text)
    assertEquals(listOf("V203"), back.entries.single().tags)
    assertEquals(entry.record, back.entries.single().record, "the record itself must not move")
    assertEquals(0, back.unmarked, "a marked record is not a gap")
    assertEquals(emptyList<JournalEntry>(), written.unmarked)
  }

  @Test
  @DisplayName("a record without a mark is accepted AND counted")
  fun `a record without a mark goes through and is counted`() {
    val entry = JournalEntry(sample("m-2"))
    val written = TimeJournal.write(listOf(entry))

    // Accepted. Natalie's decision: a break makes the proof incomplete, a
    // report makes it visibly incomplete, and only the second can be closed.
    assertEquals(emptyList<JournalEntry>(), written.refused, "an unmarked record must not be refused")
    val back = TimeJournal.read(written.text)
    assertEquals(1, back.entries.size, "the record has to be in the file")
    assertEquals(entry.record, back.entries.single().record)
    assertEquals(emptyList<String>(), back.entries.single().tags)

    // And counted. Going through WITHOUT being counted is exactly the silent
    // loss this whole format exists to prevent.
    assertEquals(listOf(entry), written.unmarked, "the write result has to name it")
    assertEquals(1, back.unmarked, "the read result has to count it")
  }

  @Test
  @DisplayName("three records without a mark count as three, not two")
  fun `the count of unmarked records is exact`() {
    val read = TimeJournal.read(fixture("shared-many.timelog"))
    assertEquals(9, read.entries.size, "otherwise this test proves nothing")
    assertEquals(3, read.unmarked, "the fixture holds exactly three records without a mark")
    assertEquals(
      listOf("r-003", "r-008", "r-009"),
      read.entries.filter { it.tags.isEmpty() }.map { it.record.id }.sorted(),
      "and these are the three"
    )
    val written = TimeJournal.write(read.entries)
    assertEquals(3, written.unmarked.size, "the write result counts the same three")
    assertEquals(
      listOf("r-003", "r-008", "r-009"),
      written.unmarked.map { it.record.id }.sorted()
    )
    // Six of the nine DO have a mark: a count that just returned the total
    // would pass the assertion above.
    assertEquals(6, read.entries.count { it.tags.isNotEmpty() })
  }

  @Test
  @DisplayName("special characters in a mark survive a round trip")
  fun `a mark with special characters survives`() {
    val awkward = listOf("A;B", "C|D", "E\\F", "G\tH", "I\nJ", "Ümlaut", "😀", "ﬀ")
    val entry = JournalEntry(sample("m-3"), awkward)
    val written = TimeJournal.write(listOf(entry))
    assertEquals(1, written.text.trimEnd('\n').lines().size - 1, "still exactly one record line")
    val back = TimeJournal.read(written.text).entries.single()
    assertEquals(awkward.size, back.tags.size, "no mark may be lost or split")
    assertEquals(awkward.toSet(), back.tags.toSet(), "every mark comes back exactly as it went in")
    assertEquals(0, TimeJournal.read(written.text).skippedLines, "the line must still be readable")
    // The escaped separator, spelled out: a mark containing ';' must not look
    // like two marks.
    assertTrue(written.text.contains("A\\sB"), "';' inside a mark must be escaped:\n$written")
    assertTrue(written.text.contains("C\\pD"), "'|' inside a mark must be escaped:\n$written")
  }

  @Test
  @DisplayName("several marks on one record are all kept")
  fun `several marks are kept`() {
    val entry = JournalEntry(sample("m-4"), listOf("V203", "V204", "abends"))
    val back = TimeJournal.read(TimeJournal.write(listOf(entry)).text).entries.single()
    assertEquals(listOf("V203", "V204", "abends"), back.tags)
    assertEquals(0, TimeJournal.read(TimeJournal.write(listOf(entry)).text).unmarked)
  }

  @Test
  @DisplayName("the marks are written in one canonical order, whatever order they arrive in")
  fun `marks are canonical`() {
    val canonical = TimeJournal.write(listOf(JournalEntry(sample("m-5"), listOf("V203", "V204")))).text
    val orders = linkedMapOf(
      "reversed" to listOf("V204", "V203"),
      "duplicated" to listOf("V204", "V203", "V203"),
      "with a blank" to listOf("", "V204", "   ", "V203"),
      "duplicated and reversed" to listOf("V204", "V204", "V203")
    )
    orders.forEach { (label, tags) ->
      assertEquals(
        canonical,
        TimeJournal.write(listOf(JournalEntry(sample("m-5"), tags))).text,
        "marks given '$label' produced other bytes"
      )
    }
    // Byte order, not UTF-16: the same trap the record lines carry.
    val mixed = TimeJournal.write(listOf(JournalEntry(sample("m-6"), listOf("😀", "ﬀ")))).text
    assertTrue(mixed.contains("ﬀ;😀"), "marks must sort by UTF-8 bytes, got:\n$mixed")
  }

  @Test
  @DisplayName("an empty mark is not a mark")
  fun `a blank mark does not count`() {
    val entry = JournalEntry(sample("m-7"), listOf("", "   "))
    val written = TimeJournal.write(listOf(entry))
    assertEquals(1, written.unmarked.size, "a record whose only mark is blank has no mark")
    val back = TimeJournal.read(written.text).entries.single()
    assertEquals(emptyList<String>(), back.tags)
    // And it is a fixed point: writing what was read gives the same bytes.
    assertEquals(written.text, TimeJournal.write(listOf(back)).text)
  }

  @Test
  @DisplayName("a version 2 line that stops early reads as a record without a mark")
  fun `a truncated version 2 line has no marks`() {
    val full = fixture("shared-one.timelog")
    val truncated = full.trimEnd('\n').removeSuffix("|V203") + "\n"
    val read = TimeJournal.read(truncated)
    assertNull(read.problem)
    assertEquals(1, read.entries.size, "the record itself is still readable")
    assertEquals(emptyList<String>(), read.entries.single().tags)
    assertEquals(1, read.unmarked, "and it counts as a gap")
  }

  // ------------------------------------------------------------- Version 3

  @Test
  @DisplayName("the version 3 journal reads: the recorder and the marks each in their own field")
  fun `the version 3 shared journal reads`() {
    val read = TimeJournal.read(fixture("shared-v3-many.timelog"))
    assertNull(read.problem, "the fixture should be a valid journal")
    assertEquals(3, read.version, "the fixture is a version 3 journal")
    assertEquals(0, read.skippedLines, "no line of the fixture may be dropped")
    assertEquals(11, read.entries.size, "eleven record lines")
    assertEquals(10, read.entries.map { it.record.id }.toSet().size, "ten distinct ids, one twice")

    val byId = read.entries.groupBy { it.record.id }
    // Everything version 2 already carried is still read the same way. If the
    // recorder had shifted a field, these would be the first to go wrong.
    assertEquals("Berg|mann", byId.getValue("r-002").single().record.person, "a separator in a name")
    assertEquals("Pfad C:\\temp;Notiz\tEnde", byId.getValue("r-004").single().record.note)
    assertEquals(listOf("A;B", "C|D"), byId.getValue("r-004").single().tags, "separators in a mark")
    assertEquals(listOf("V203", "Ümlaut"), byId.getValue("r-007").single().tags, "a non-ASCII mark")

    // One span of work, two people, and neither of them typed it in.
    val dora = byId.getValue("r-010").single()
    val emil = byId.getValue("r-011").single()
    assertEquals("Dora", dora.record.person)
    assertEquals("Emil", emil.record.person)
    assertEquals(
      dora.record.start.toInstant(),
      emil.record.start.toInstant(),
      "the same stretch of the afternoon, for two people"
    )
    assertEquals("Anna", dora.record.recordedBy, "Anna entered Dora's hours")
    assertTrue(dora.record.isThirdParty)
    // A recorder's name carrying both separators at once. Nothing may treat
    // either of them as structure here: they are inside one field and inside
    // one name.
    assertEquals("Berg|mann;X", emil.record.recordedBy, "both separators in a recorder's name")
    assertTrue(emil.record.isThirdParty)
    assertEquals(listOf("V203"), emil.tags, "and the marks are still the marks")

    // Everything else is self-recorded, and says so by saying nothing.
    val others = read.entries.filterNot { it.record.id == "r-010" || it.record.id == "r-011" }
    assertEquals(9, others.size)
    assertTrue(others.all { it.record.recordedBy == null }, "an empty field nine is not a name")
    assertTrue(others.none { it.record.isThirdParty })

    assertEquals(3, read.unmarked, "r-003, r-008 and r-009 carry no mark")
  }

  @Test
  @DisplayName("the line order is the byte order, with the recorder in the line")
  fun `the version 3 lines are in byte order`() {
    val body = fixture("shared-v3-many.timelog").lines().drop(1).filter { it.isNotBlank() }
    // The two notes are U+FB00 and U+1F600. As UTF-8 bytes the first is
    // ef ac 80 and the second f0 9f 98 80, so the ligature comes first. As
    // Kotlin Strings compare -- UTF-16 code units -- the emoji's leading
    // surrogate D83D sorts BELOW FB00 and they would come out the other way
    // round. The fixture pins the byte order, so this cannot pass unnoticed.
    val five = body.filter { it.startsWith("r-005|") }
    assertEquals(2, five.size, "the same id twice, one with a ligature and one with an emoji")
    assertTrue(five[0].contains("\uFB00"), "the ligature line comes first in byte order")
    assertTrue(five[1].contains("\uD83D\uDE00"), "and the emoji line second")
    assertTrue(five[0] > five[1], "which is the opposite of what String order would give")

    // Two lines, one instant, telling apart on their bytes: r-010 before
    // r-011. The recorder is part of the line the tie-break looks at.
    assertTrue(
      body.indexOfFirst { it.startsWith("r-010|") } <
        body.indexOfFirst { it.startsWith("r-011|") },
      "the tie-break runs over the whole line"
    )
  }

  @Test
  @DisplayName("field nine is the recorder in version 3 and the marks in version 2 -- only the version says which")
  fun `the field layout comes from the version and never from the field count`() {
    // Nine fields, nothing wrong with any of them, and nothing in the line
    // that says which version it belongs to. That is the point: a version 2
    // line carrying a mark and a version 3 line carrying none are the same
    // shape. Counting fields cannot tell them apart, so nothing may try.
    val nine = "r-001|t-roof|2026-09-09T08:00+02:00|9000|MANUAL|Anna|Dach decken|" +
      "2026-09-09T08:00+02:00|V203"
    assertEquals(9, nine.split("|").size, "nine fields, which either version can produce")

    val asTwo = checkNotNull(TimeJournal.decodeLine(nine, version = 2))
    assertEquals(listOf("V203"), asTwo.tags, "in version 2 field nine is the mark")
    assertNull(asTwo.record.recordedBy, "and version 2 has no recorder at all")
    assertFalse(asTwo.record.isThirdParty)

    val asThree = checkNotNull(TimeJournal.decodeLine(nine, version = 3))
    assertEquals("V203", asThree.record.recordedBy, "in version 3 field nine is the recorder")
    assertEquals(emptyList<String>(), asThree.tags, "and this line has no marks field")

    // The same trap the other way round: ten fields, read as version 2, and
    // the recorder becomes the booking. Neither reading complains, both
    // produce a record, and exactly one of them states something nobody
    // wrote. Nothing but the header can decide which.
    val ten = "r-001|t-roof|2026-09-09T08:00+02:00|9000|MANUAL|Anna|Dach decken|" +
      "2026-09-09T08:00+02:00|Anna|V203"
    val misread = checkNotNull(TimeJournal.decodeLine(ten, version = 2))
    assertEquals(listOf("Anna"), misread.tags, "a version 2 reader books these hours against Anna")
    assertNull(misread.record.recordedBy)
    val right = checkNotNull(TimeJournal.decodeLine(ten, version = 3))
    assertEquals("Anna", right.record.recordedBy)
    assertEquals(listOf("V203"), right.tags)

    // And the reader that matters takes the version from the file, not from a
    // parameter: the version 2 fixture read whole keeps its marks.
    val whole = TimeJournal.read(fixture("shared-many.timelog"))
    assertEquals(2, whole.version)
    assertTrue(whole.entries.all { it.record.recordedBy == null }, "no version 2 line has a recorder")
    assertEquals(
      listOf("V203", "V204"),
      whole.entries.single { it.record.id == "r-002" }.tags,
      "and its marks are still marks"
    )
  }

  @Test
  @DisplayName("a recorder survives a round trip, separators and all")
  fun `a recorder survives a round trip`() {
    val entry = JournalEntry(sample("m-1").copy(recordedBy = "Berg|mann;X\tZ"), listOf("V203"))
    val written = TimeJournal.write(listOf(entry))
    val back = TimeJournal.read(written.text).entries.single()
    assertEquals(3, TimeJournal.read(written.text).version)
    assertEquals("Berg|mann;X\tZ", back.record.recordedBy, "the recorder comes back unmangled")
    assertEquals(listOf("V203"), back.tags, "and the marks did not absorb it")
    assertEquals("Anna", back.record.person, "whose hours they are is a different question")
    assertEquals(entry.record, back.record)
    // Written twice, the same bytes -- a recorder must not make a save look
    // like a change.
    assertEquals(written.text, TimeJournal.write(listOf(back)).text)
  }

  @Test
  @DisplayName("an empty recorder reads back as none, not as a name of nothing")
  fun `an empty recorder is none`() {
    val written = TimeJournal.write(listOf(JournalEntry(sample("m-1"), listOf("V203"))))
    assertTrue(
      written.text.contains("|2026-09-09T08:00+02:00||V203\n"),
      "the field is written even when empty, or the marks would move"
    )
    val back = TimeJournal.read(written.text).entries.single()
    assertNull(back.record.recordedBy)
    assertFalse(back.record.isThirdParty)
    // A recorder of nothing but blanks is not a recorder either -- the same
    // rule the person field has kept since version 1.
    val blank = TimeJournal.write(listOf(JournalEntry(sample("m-2").copy(recordedBy = "   "))))
    assertNull(TimeJournal.read(blank.text).entries.single().record.recordedBy)
  }

  @Test
  @DisplayName("upgrading a version 2 journal says self-recorded, and says nothing about it")
  fun `the version 2 upgrade is silent about the recorder`() {
    val read = TimeJournal.read(fixture("shared-many.timelog"))
    assertEquals(2, read.version)
    val written = TimeJournal.write(read.entries)
    val again = TimeJournal.read(written.text)
    assertEquals(3, again.version)
    assertTrue(again.entries.all { it.record.recordedBy == null }, "every record was self-recorded")
    // The marks upgrade is loud because "could not say" became "says
    // nothing". This one is not, because empty here is not a gap: before the
    // field existed there was no way to enter anybody else's hours, so these
    // records really were self-recorded. Nothing became less certain.
    assertEquals(3, written.unmarked.size, "the same three gaps as before, and no more")
    assertEquals(read.unmarked, written.unmarked.size, "the upgrade invents no gap")
    assertEquals(
      read.entries.map { it.record.copy(recordedBy = null) },
      again.entries.map { it.record },
      "and it moves no record"
    )
  }

  @Test
  @DisplayName("re-attributing an entry is a change, even though no hours moved")
  fun `a changed recorder is a change`() {
    val before = listOf(JournalEntry(sample("c-1"), listOf("V203")))
    val after = listOf(JournalEntry(sample("c-1").copy(recordedBy = "Anna"), listOf("V203")))
    val summary = TimeJournal.changeSummary(before, after, zone)
    assertEquals(1, summary.lines.size, "who is answerable for an entry changing is a change")
    assertEquals(ChangeKind.CHANGED, summary.lines.single().kind)
    assertEquals(0L, summary.lines.single().seconds, "and no hours moved")
  }

  // ------------------------------------------------------------- Version 1

  @Test
  @DisplayName("a version 1 journal is still read, and every record in it has no mark")
  fun `version one still reads`() {
    val read = TimeJournal.read(fixture("shared-v1.timelog"))
    assertNull(read.problem, "version 1 is read, not refused")
    assertEquals(1, read.version, "and the reader says which version it was")
    assertEquals(9, read.entries.size, "all nine records")
    assertEquals(0, read.skippedLines)
    assertTrue(read.entries.all { it.tags.isEmpty() }, "version 1 has nowhere to put a mark")
    assertEquals(9, read.unmarked, "so all nine are gaps")
    // The records themselves must be exactly what version 1 meant.
    val byId = read.entries.groupBy { it.record.id }
    assertEquals("Dach decken", byId.getValue("r-001").single().record.note)
    assertEquals("Berg|mann", byId.getValue("r-002").single().record.person)
  }

  @Test
  @DisplayName("a stray ninth field in a version 1 file is ignored, not read as a mark")
  fun `version one ignores a ninth field`() {
    // Version 1 says "fields past the eighth are ignored". A version 2 reader
    // has to keep that promise, or "version 1 could not say" becomes a lie
    // the moment somebody hand-edits an old file.
    val text = fixture("shared-v1.timelog").replace(
      "|2026-09-09T08:00+02:00\n", "|2026-09-09T08:00+02:00|V203\n"
    )
    assertTrue(text.contains("|V203"), "the test data has to actually carry the stray field")
    val read = TimeJournal.read(text)
    assertEquals(1, read.version)
    assertEquals(9, read.entries.size)
    assertTrue(read.entries.all { it.tags.isEmpty() }, "version 1 has no marks, whatever the line says")
    assertEquals(9, read.unmarked)
    // And not as a recorder either. Field nine is the marks in version 2 and
    // the recorder in version 3; in version 1 it is neither, because version 1
    // says fields past the eighth are not looked at.
    assertTrue(
      read.entries.all { it.record.recordedBy == null },
      "version 1 has no recorder, whatever the line says"
    )
  }

  @Test
  @DisplayName("writing a version 1 journal back produces version 3 and says how many marks are missing")
  fun `version one is upgraded loudly`() {
    val read = TimeJournal.read(fixture("shared-v1.timelog"))
    val written = TimeJournal.write(read.entries)
    assertTrue(written.text.startsWith(TimeJournal.HEADER + "\n"), "the upgrade writes version 3")
    assertEquals(9, written.unmarked.size, "and it is loud about what the old file could not say")
    // Same records, two versions up: the difference is the header and two
    // empty fields, the marks and the recorder.
    val again = TimeJournal.read(written.text)
    assertEquals(3, again.version)
    assertTrue(again.entries.all { it.record.recordedBy == null }, "nobody recorded these for anybody")
    assertEquals(
      read.entries.map { it.record },
      again.entries.map { it.record },
      "the upgrade must not move a single record"
    )
  }

  @Test
  @DisplayName("a version this code does not know is refused by name")
  fun `a newer version is refused by name`() {
    val text = fixture("shared-v3-many.timelog").replaceFirst("#gp-timelog 3 ", "#gp-timelog 4 ")
    val read = TimeJournal.read(text)
    assertEquals(JournalProblem.UNKNOWN_VERSION, read.problem)
    assertEquals(emptyList<JournalEntry>(), read.entries)
    assertEquals(4, read.version, "the reader can still say which version it was handed")
  }

  @Test
  @DisplayName("a file without the header is refused rather than guessed at")
  fun `a headerless file is refused`() {
    val body = fixture("shared-many.timelog").lines().drop(1).joinToString("\n")
    val read = TimeJournal.read(body)
    assertEquals(JournalProblem.NO_HEADER, read.problem)
    assertEquals(emptyList<JournalEntry>(), read.entries, "a refused file must yield no records")
    assertNull(read.version, "there is no version to name")
  }

  // ------------------------------------------------------------- Stability

  @Test
  @DisplayName("the order the records arrive in does not change a single byte")
  fun `the same records in another order write the same bytes`() {
    val entries = TimeJournal.read(fixture("shared-many.timelog")).entries
    val expected = TimeJournal.write(entries).text
    // Without this the test passes against anything that returns a constant.
    assertEquals(9, entries.size)
    assertEquals(10, expected.trimEnd('\n').lines().size, "header and nine records")

    val orders = linkedMapOf(
      "reversed" to entries.reversed(),
      "by id" to entries.sortedBy { it.record.id },
      "by description" to entries.sortedBy { it.record.note },
      "by mark" to entries.sortedBy { it.tags.joinToString(";") },
      "rotated" to (entries.drop(4) + entries.take(4)),
      // A fixed shuffle rather than a random one: a test that fails only
      // sometimes cannot be acted on.
      "fixed shuffle" to entries.shuffled(kotlin.random.Random(20260909L))
    )
    orders.forEach { (label, shuffled) ->
      assertEquals(expected, TimeJournal.write(shuffled).text, "order '$label' produced other bytes")
    }
  }

  @Test
  @DisplayName("writing the same records twice produces the same bytes")
  fun `writing twice is byte identical`() {
    val entries = TimeJournal.read(fixture("shared-many.timelog")).entries
    assertEquals(9, entries.size)
    assertEquals(TimeJournal.write(entries).text, TimeJournal.write(entries).text)
    assertTrue(TimeJournal.write(entries).text.length > TimeJournal.HEADER.length + 100)
  }

  @Test
  @DisplayName("a non-canonical timestamp is normalised on write")
  fun `writing normalises`() {
    // Same instant, seconds spelled out. The journal has one spelling per
    // instant, otherwise two writers produce two files for one state.
    val r = JournalRecord(
      id = "n-1",
      taskUid = "t-1",
      start = OffsetDateTime.parse("2026-09-09T08:00:00+02:00"),
      durationSeconds = 60,
      note = "",
      createdAt = OffsetDateTime.parse("2026-09-09T08:00:00+02:00")
    )
    val text = TimeJournal.write(listOf(JournalEntry(r, listOf("V203")))).text
    assertTrue(text.contains("2026-09-09T08:00+02:00"), "expected the short form, got:\n$text")
    assertEquals(text, TimeJournal.write(TimeJournal.read(text).entries).text, "not a fixed point")
  }

  // ------------------------------------------------------------ Idempotence

  @Test
  @DisplayName("the same journal read twice does not double the hours, and keeps the marks")
  fun `restoring twice does not double the hours`() {
    val text = fixture("shared-many.timelog")
    val once = TimeJournal.merge(TimeJournal.read(text).entries, emptyList())
    val twice = TimeJournal.merge(TimeJournal.read(text).entries, once)
    val thrice = TimeJournal.merge(TimeJournal.read(text).entries, twice)

    assertEquals(once.size, twice.size, "a second restore added records")
    assertEquals(once, twice, "a second restore changed the records")
    assertEquals(once, thrice, "a third restore changed the records")

    assertEquals(
      TimeJournal.secondsByTaskUid(once),
      TimeJournal.secondsByTaskUid(twice),
      "the hours per task changed on the second restore"
    )
    // The duplicate id is what makes this test worth anything: nine lines,
    // eight records, and the total must be the eight.
    assertEquals(8, once.size, "the duplicate id must collapse to one record on restore")
    // A restore that dropped the marks would be a silent loss of exactly the
    // thing version 2 was built for.
    assertEquals(listOf("V203", "V204"), once.first { it.record.id == "r-002" }.tags)
    // r-005 is the duplicated id and BOTH its lines carry V203, so collapsing
    // it removes no gap: all three survive the merge.
    assertEquals(3, once.count { it.tags.isEmpty() }, "the marks must survive a restore")
  }

  @Test
  @DisplayName("restoring into a set that already holds the records changes nothing")
  fun `restoring over the existing records changes nothing`() {
    val entries = TimeJournal.read(fixture("shared-many.timelog")).entries
    val existing = TimeJournal.merge(entries, emptyList())
    assertEquals(8, existing.size, "otherwise this test proves nothing")
    val after = TimeJournal.merge(TimeJournal.read(TimeJournal.write(existing).text).entries, existing)
    assertEquals(existing, after)
    assertEquals(TimeJournal.write(existing).text, TimeJournal.write(after).text)
  }

  // -------------------------------------------------------------- Refusals

  @Test
  @DisplayName("an unreadable line is skipped, counted, and does not take the file down")
  fun `a damaged line is counted`() {
    val text = fixture("shared-many.timelog").trimEnd('\n') + "\nnot-a-record\n"
    val read = TimeJournal.read(text)
    assertNull(read.problem)
    assertEquals(1, read.skippedLines)
    assertEquals(9, read.entries.size, "the intact lines must still be there")
  }

  @Test
  @DisplayName("a record that could not be read back is refused rather than silently written")
  fun `an unreadable record is refused`() {
    val good = TimeJournal.read(fixture("shared-one.timelog")).entries.single()
    // Deliberately without a mark: if write() counted refused records as
    // unmarked, the assertion at the end would catch it. With a mark on them
    // it would pass for the wrong reason.
    val refusable = listOf(
      JournalEntry(good.record.copy(id = "  ")),
      JournalEntry(good.record.copy(taskUid = ""))
    )
    val written = TimeJournal.write(refusable + good)
    assertEquals(2, written.refused.size, "both broken records should be refused")
    assertEquals(1, TimeJournal.read(written.text).entries.size, "only the intact record is written")
    assertEquals(0, TimeJournal.read(written.text).skippedLines, "a written journal reads without loss")
    // A refused record is not a gap in the proof, it is not in the file at
    // all. Counting it as one would send somebody looking for a mark on a
    // record that was never written.
    assertEquals(emptyList<JournalEntry>(), written.unmarked, "a refused record is not an unmarked one")
  }

  @Test
  @DisplayName("a zero-length record is written, because it can be read back")
  fun `a zero duration is kept`() {
    val good = TimeJournal.read(fixture("shared-one.timelog")).entries.single()
    val zero = good.copy(record = good.record.copy(id = "z-1", durationSeconds = 0))
    val written = TimeJournal.write(listOf(zero))
    assertEquals(emptyList<JournalEntry>(), written.refused, "refusing it would lose it silently")
    assertEquals(zero, TimeJournal.read(written.text).entries.single())
  }

  // ------------------------------------------------------------ Change list

  private fun entries() = TimeJournal.read(fixture("shared-many.timelog")).entries

  private fun sample(id: String) = JournalRecord(
    id = id,
    taskUid = "t-roof",
    start = OffsetDateTime.parse("2026-09-09T08:00+02:00"),
    durationSeconds = 3600,
    note = "Arbeit",
    person = "Anna"
  )

  @Test
  @DisplayName("nothing changed means nothing to commit")
  fun `no change means no commit`() {
    val e = entries()
    assertEquals(9, e.size, "comparing two empty sets would prove nothing")
    val summary = TimeJournal.changeSummary(e, e, zone)
    assertTrue(summary.isEmpty, "an unchanged set produced: ${TimeJournal.renderChangeList(summary)}")
    assertNull(
      TimeJournal.commitMessage(OffsetDateTime.parse("2026-09-09T18:04+02:00"), summary, zone),
      "there must be no commit message when there is nothing to commit"
    )
    // The positive control: something that DID change must produce a message,
    // or this test passes against anything that always returns null.
    val moved = TimeJournal.changeSummary(e, e.drop(1), zone)
    assertTrue(!moved.isEmpty, "a real change has to be seen at all")
    assertNotNull(
      TimeJournal.commitMessage(OffsetDateTime.parse("2026-09-09T18:04+02:00"), moved, zone),
      "a real change has to produce a commit message"
    )
  }

  @Test
  @DisplayName("the change list names exactly what came in, in Natalie's form")
  fun `the change list names what came in`() {
    val before = entries()
    val added = JournalEntry(
      JournalRecord(
        id = "r-100",
        taskUid = "t-roof",
        start = OffsetDateTime.parse("2026-09-09T14:00+02:00"),
        durationSeconds = 9000,
        note = "Dach decken",
        person = "Anna"
      ),
      listOf("V203")
    )
    val summary = TimeJournal.changeSummary(before, before + added, zone) { uid ->
      if (uid == "t-roof") "Dach decken" else null
    }
    assertEquals(1, summary.lines.size, "exactly one thing came in")
    assertEquals(ChangeKind.ADDED, summary.lines.single().kind)
    assertEquals(9000L, summary.lines.single().seconds)
    assertEquals("+2,5 h  Dach decken (Anna)  09.09.", TimeJournal.renderChangeList(summary))

    val message = TimeJournal.commitMessage(
      OffsetDateTime.parse("2026-09-09T18:04+02:00"), summary, zone
    )
    assertEquals(
      "Stunden 09.09.2026 18:04\n\n+2,5 h  Dach decken (Anna)  09.09.\n",
      message,
      "the form Natalie agreed does not change in version 2"
    )
  }

  @Test
  @DisplayName("re-marking an hour is a change, even though no hours moved")
  fun `a changed mark is a change`() {
    val before = entries()
    val old = before.first { it.record.id == "r-001" }
    val after = before - old + old.copy(tags = listOf("V204"))
    val summary = TimeJournal.changeSummary(before, after, zone) { "Dach" }
    assertEquals(1, summary.lines.size, "moving an hour to another mark has to show up")
    assertEquals(ChangeKind.CHANGED, summary.lines.single().kind)
    assertEquals(0L, summary.lines.single().seconds, "no hours moved, only the attribution")
    assertEquals("~0 h  Dach (Anna)  09.09.", TimeJournal.renderChangeList(summary))
    assertTrue(!summary.isEmpty, "and it must produce a commit")
  }

  @Test
  @DisplayName("the commit message names the records without a mark")
  fun `the commit message reports the gaps`() {
    val before = entries()
    val added = JournalEntry(sample("r-101").copy(start = OffsetDateTime.parse("2026-09-09T14:00+02:00")))
    val summary = TimeJournal.changeSummary(before, before + added, zone) { "Dach" }
    val written = TimeJournal.write(before + added)
    assertEquals(4, written.unmarked.size, "three in the fixture and the new one")

    val message = TimeJournal.commitMessage(
      OffsetDateTime.parse("2026-09-09T18:04+02:00"), summary, zone, written.unmarked.size
    )
    assertEquals(
      "Stunden 09.09.2026 18:04\n\n+1 h  Dach (Anna)  09.09.\n\n4 Sätze ohne Marke\n",
      message,
      "the gap has to be where nobody can miss it: in the commit"
    )
    assertEquals("1 Satz ohne Marke", TimeJournal.renderUnmarkedNote(1), "singular")
    assertEquals("2 Sätze ohne Marke", TimeJournal.renderUnmarkedNote(2))
    assertNull(TimeJournal.renderUnmarkedNote(0), "nothing to say when there is no gap")
  }

  @Test
  @DisplayName("a commit with no gaps says nothing about gaps")
  fun `no gap means no note`() {
    val before = entries()
    val added = JournalEntry(sample("r-102").copy(start = OffsetDateTime.parse("2026-09-09T14:00+02:00")), listOf("V203"))
    val summary = TimeJournal.changeSummary(before, before + added, zone) { "Dach" }
    assertEquals(
      "Stunden 09.09.2026 18:04\n\n+1 h  Dach (Anna)  09.09.\n",
      TimeJournal.commitMessage(OffsetDateTime.parse("2026-09-09T18:04+02:00"), summary, zone, 0)
    )
  }

  @Test
  @DisplayName("a removed and a changed record are named as such")
  fun `removal and change are named`() {
    val before = entries()
    val gone = before.first { it.record.id == "r-001" }
    val afterRemoval = before - gone
    val removal = TimeJournal.changeSummary(before, afterRemoval, zone) { "Dach" }
    assertEquals(ChangeKind.REMOVED, removal.lines.single().kind)
    assertEquals(-9000L, removal.lines.single().seconds)
    assertEquals("-2,5 h  Dach (Anna)  09.09.", TimeJournal.renderChangeList(removal))

    val longer = afterRemoval + gone.copy(record = gone.record.copy(durationSeconds = 10800))
    val change = TimeJournal.changeSummary(before, longer, zone) { "Dach" }
    assertEquals(ChangeKind.CHANGED, change.lines.single().kind)
    assertEquals(1800L, change.lines.single().seconds, "the delta, not the new total")
    assertEquals("~+0,5 h  Dach (Anna)  09.09.", TimeJournal.renderChangeList(change))
  }

  @Test
  @DisplayName("ten new records stay a short list")
  fun `ten records stay short`() {
    val before = entries()
    val ten = (1..10).map { i ->
      JournalEntry(
        JournalRecord(
          id = "m-$i",
          taskUid = "t-$i",
          start = OffsetDateTime.parse("2026-09-11T08:00+02:00").plusHours(i.toLong()),
          durationSeconds = 3600L * i,
          note = "Arbeit $i",
          person = "Anna"
        ),
        listOf("V203")
      )
    }
    val summary = TimeJournal.changeSummary(before, before + ten, zone)
    val rendered = TimeJournal.renderChangeList(summary)

    assertEquals(3, summary.lines.size, "the list is capped")
    assertEquals(7, summary.omittedLines, "the rest is counted, not dropped")
    assertEquals(3600L * (1 + 2 + 3 + 4 + 5 + 6 + 7), summary.omittedSeconds, "and its hours are named")
    assertEquals(4, rendered.lines().size, "three lines and the tail")
    assertTrue(rendered.lines().last().startsWith("… und 7 weitere"), "got: ${rendered.lines().last()}")
    // The point of the cap: it has to stay readable in a commit list.
    assertTrue(rendered.length < 200, "still ${rendered.length} characters:\n$rendered")

    // Biggest first, so the cap keeps what matters.
    assertEquals(3600L * 10, summary.lines.first().seconds)
  }

  @Test
  @DisplayName("several records on one task and day become one line")
  fun `one task and day is one line`() {
    val before = entries()
    val five = (1..5).map { i ->
      JournalEntry(
        JournalRecord(
          id = "s-$i",
          taskUid = "t-roof",
          start = OffsetDateTime.parse("2026-09-12T08:00+02:00").plusHours(i.toLong()),
          durationSeconds = 1800,
          note = "Dach decken",
          person = "Anna"
        ),
        listOf("V203")
      )
    }
    val summary = TimeJournal.changeSummary(before, before + five, zone) { "Dach decken" }
    assertEquals(1, summary.lines.size, "five bookings on one task and day are one line")
    assertEquals(9000L, summary.lines.single().seconds)
    assertEquals("+2,5 h  Dach decken (Anna)  12.09.", TimeJournal.renderChangeList(summary))
  }

  @Test
  @DisplayName("the hours in the change list are rendered the way the commit message needs them")
  fun `hours are rendered as agreed`() {
    assertEquals("+2,5", TimeJournal.hoursText(9000))
    assertEquals("+1", TimeJournal.hoursText(3600))
    assertEquals("+0,5", TimeJournal.hoursText(1800))
    assertEquals("+0,25", TimeJournal.hoursText(900))
    assertEquals("-2,5", TimeJournal.hoursText(-9000))
    assertEquals("0", TimeJournal.hoursText(0))
    assertEquals("+8", TimeJournal.hoursText(28800))
    // Rounded to hundredths, but never down to nothing: a change list that
    // says "+0 h" for work that happened is worse than one that overstates
    // by eighteen seconds. The journal keeps the exact seconds either way.
    assertEquals("+0,01", TimeJournal.hoursText(17))
    assertEquals("+0,01", TimeJournal.hoursText(1))
  }

  @Test
  @DisplayName("the change list does not depend on the order the records arrive in")
  fun `the change list is stable`() {
    val before = entries()
    val added = before.first { it.record.id == "r-001" }
      .let { it.copy(record = it.record.copy(id = "r-200")) }
    val expected = TimeJournal.renderChangeList(
      TimeJournal.changeSummary(before, before + added, zone) { "Dach" }
    )
    assertEquals(
      expected,
      TimeJournal.renderChangeList(
        TimeJournal.changeSummary(before.reversed(), (before + added).reversed(), zone) { "Dach" }
      )
    )
  }

  // ------------------------------------------------------------- The store

  @Test
  @DisplayName("the store saves, reports no change, and is handed the message")
  fun `the file store behaves`() {
    val dir = File("build/journal-store-test").also { it.deleteRecursively(); it.mkdirs() }
    val store = FileJournalStore(File(dir, "stunden.timelog"))
    assertNull(store.load(), "an empty store has no journal yet")

    val text = fixture("shared-many.timelog")
    val first = store.save(text, "Stunden 09.09.2026 18:04")
    assertTrue(first is SaveOutcome.Saved, "expected Saved, got $first")
    assertEquals("Stunden 09.09.2026 18:04", store.lastMessage)
    assertEquals(text, store.load(), "what went in has to come back out")

    val again = store.save(text, "Stunden 09.09.2026 18:05")
    assertEquals(SaveOutcome.Unchanged, again, "nothing changed, so there is nothing to commit")
    assertEquals("Stunden 09.09.2026 18:04", store.lastMessage, "an unchanged save is not a save")
  }

  @Test
  @DisplayName("a store that cannot write says so instead of pretending")
  fun `a failing store reports the failure`() {
    val dir = File("build/journal-store-fail").also { it.deleteRecursively(); it.mkdirs() }
    val blocked = File(dir, "sub").also { it.writeText("I am a file, not a directory") }
    val store = FileJournalStore(File(blocked, "stunden.timelog"))
    val outcome = store.save(TimeJournal.write(emptyList()).text, "egal")
    assertTrue(outcome is SaveOutcome.Failed, "expected Failed, got $outcome")
  }


  // ------------------------------- The journal changes no project data

  private fun newTaskManager(): TaskManager =
    TaskManager.Access.newInstance(null, object : TaskManagerConfig {
      override fun getDefaultColor(): Color? = null
      override fun getDefaultColorOption(): ColorOption? = null
      override fun getCalendar(): GPCalendarCalc = AlwaysWorkingTimeCalendarImpl()
      override fun getTimeUnitStack(): TimeUnitStack = GPTimeUnitStack()
      override fun getResourceManager(): HumanResourceManager? = null
      override fun getProjectDocumentURL(): URL? = null
      override fun getNotificationManager(): NotificationManager? = null
      override fun getSchedulerDisabledOption(): BooleanOption =
        DefaultBooleanOption("scheduler.disabled", false)
    })

  /**
   * Everything about the tasks that the journal must not move: name, dates,
   * completion, and every custom value -- which is where the recorded hours
   * and the import ledger live.
   */
  private fun fingerprint(tasks: List<Task>, properties: CustomColumnsManager): String =
    tasks.sortedBy { it.taskID }.joinToString("\n") { task ->
      val values = properties.definitions.sortedBy { it.id }.joinToString(",") { def ->
        "${def.name}=${task.customValues.getValue(def)}"
      }
      "${task.taskID}|${task.uid}|${task.name}|${task.start}|${task.duration}|" +
        "${task.completionPercentage}|$values"
    }

  @Test
  @DisplayName("writing the journal does not change one byte of the project data")
  fun `the journal leaves the project alone`() {
    val properties = CustomColumnsManager()
    val taskManager = newTaskManager()
    val first = taskManager.createTask()
    val second = taskManager.createTask()
    val ledgerDefinition = TimeTrackingProperties.findOrCreateImportedEntries(properties)
    first.customValues.setValue(ledgerDefinition, encodeImportLedger(mapOf(11L to 2.5)))
    second.customValues.setValue(ledgerDefinition, encodeImportLedger(mapOf(22L to 1.0)))

    val before = fingerprint(taskManager.tasks.toList(), properties)

    // Everything the connector would do on a save, short of the network. The
    // records come from the journal, because the desktop has no per-record
    // log to take them from -- which is itself the finding.
    val log = TimeJournal.read(fixture("shared-many.timelog")).entries
    assertTrue(log.isNotEmpty(), "there must be something to journal")
    val journal = TimeJournal.write(log)
    assertEquals(0, journal.refused.size, "the guard is worthless if nothing was journalled")
    assertEquals(3, journal.unmarked.size, "and it has to exercise the marks, not just the records")
    assertTrue(journal.text.contains("|V203;V204"), "including a record with two of them")
    val summary = TimeJournal.changeSummary(emptyList(), log, zone)
    assertTrue(summary.lines.isNotEmpty(), "and worthless if the change list was empty too")
    TimeJournal.commitMessage(
      OffsetDateTime.parse("2026-09-09T18:04+02:00"), summary, zone, journal.unmarked.size
    )
    FileJournalStore(File("build/journal-project-guard/stunden.timelog").also {
      it.parentFile.deleteRecursively()
    }).save(journal.text, "Stunden 09.09.2026 18:04")

    assertEquals(before, fingerprint(taskManager.tasks.toList(), properties),
      "the journal step changed the project data")
  }

  @Test
  @DisplayName("the guard above can tell changed project data from unchanged")
  fun `the project guard is not blind`() {
    val properties = CustomColumnsManager()
    val taskManager = newTaskManager()
    val task = taskManager.createTask()
    val ledgerDefinition = TimeTrackingProperties.findOrCreateImportedEntries(properties)
    task.customValues.setValue(ledgerDefinition, encodeImportLedger(mapOf(11L to 2.5)))
    val before = fingerprint(taskManager.tasks.toList(), properties)

    // Four separate changes, each of a kind the journal path could plausibly
    // make by accident. If the fingerprint could not see all four, it could
    // not be trusted about any of them. Eight earlier sittings were caught out
    // by a guard that compared two things that moved together.
    task.customValues.setValue(ledgerDefinition, encodeImportLedger(mapOf(11L to 9.5)))
    assertNotEquals(before, fingerprint(taskManager.tasks.toList(), properties),
      "the fingerprint cannot see a changed import ledger")

    val afterLedger = fingerprint(taskManager.tasks.toList(), properties)
    task.createMutator().also { it.setName("Anders"); it.commit() }
    assertNotEquals(afterLedger, fingerprint(taskManager.tasks.toList(), properties),
      "the fingerprint cannot see a renamed task")

    val afterName = fingerprint(taskManager.tasks.toList(), properties)
    taskManager.createTask()
    assertNotEquals(afterName, fingerprint(taskManager.tasks.toList(), properties),
      "the fingerprint cannot see a new task")

    // And the one version 2 adds: a mark written into a custom value would be
    // a mark leaking into the project file, which is exactly what keeping the
    // marks on JournalEntry is meant to prevent.
    val afterNew = fingerprint(taskManager.tasks.toList(), properties)
    task.customValues.setValue(ledgerDefinition, "V203")
    assertNotEquals(afterNew, fingerprint(taskManager.tasks.toList(), properties),
      "the fingerprint cannot see a mark written into the project data")
  }

  @Test
  @DisplayName("a mark never reaches the project data, so it cannot be lost there")
  fun `the project carries no marks`() {
    val properties = CustomColumnsManager()
    val taskManager = newTaskManager()
    val task = taskManager.createTask()
    val ledgerDefinition = TimeTrackingProperties.findOrCreateImportedEntries(properties)
    task.customValues.setValue(ledgerDefinition, encodeImportLedger(mapOf(11L to 2.5)))

    val entry = JournalEntry(
      JournalRecord(
        id = "guard-2",
        taskUid = task.uid,
        start = OffsetDateTime.parse("2026-09-09T08:00+02:00"),
        durationSeconds = 3600,
        note = "Wache"
      ),
      listOf("V203")
    )
    // The mark has to be in the journal and only there. Without the write
    // this passes against an implementation that writes no journal at all.
    val text = TimeJournal.write(listOf(entry)).text
    assertTrue(text.contains("|V203\n"), "the journal must carry the mark, got:\n$text")
    assertTrue(
      !fingerprint(taskManager.tasks.toList(), properties).contains("V203"),
      "a mark has no business in the project data"
    )
  }
}
