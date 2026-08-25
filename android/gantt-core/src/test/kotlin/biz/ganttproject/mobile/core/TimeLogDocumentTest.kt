/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The time log where it meets the file.
 *
 * Uses the real desktop sample rather than a hand-written miniature, for the
 * same reason [GanttDocumentTest] does: a fixture written here would contain
 * exactly what the writer already knows about and would prove nothing about
 * surviving a round trip.
 */
class TimeLogDocumentTest {

  /** Task 0 in the sample; the uid is what the log keys on. */
  private val designUid = "11366eb6d1e34238866b347d1f40062d"

  /** Task 9, a leaf under it. */
  private val draftUid = "5fd8ed5da21241e99a89179e6edb4fe1"

  private fun sample(): String =
    checkNotNull(javaClass.getResourceAsStream("/HouseBuildingSample.gan")) {
      "sample project missing from the test classpath"
    }.readBytes().toString(Charsets.UTF_8)

  private fun load() = GanttDocument.parse(sample())

  private fun at(day: Int, hour: Int): OffsetDateTime =
    OffsetDateTime.of(2026, 7, day, hour, 0, 0, 0, ZoneOffset.ofHours(2))

  private fun record(
    id: String = "r1",
    taskUid: String = designUid,
    start: OffsetDateTime = at(3, 9),
    seconds: Long = 3600,
    description: String = "Drew the elevation"
  ) = TimeRecord(id, taskUid, start, seconds, description)

  // ----------------------------------------------------------- The basics

  @Test
  fun `a project without records has an empty log`() {
    val log = load().timeLog()
    assertEquals(0, log.records.size)
    assertFalse(log.hasLosses)
  }

  @Test
  fun `a record can be added and read back`() {
    val doc = load()
    assertTrue(doc.addTimeRecord(record()))
    assertEquals(listOf("r1"), doc.timeLog().records.map { it.id })
    assertEquals(designUid, doc.timeLog().records.single().taskUid)
  }

  @Test
  @DisplayName("a record survives being written to the file and read back")
  fun `the log round-trips through the XML`() {
    // Goes through the real serialiser and parser, so anything the storage
    // layer mangles -- escaping, quoting, element placement -- shows up here.
    //
    // It is NOT the guard for the separators, and that is worth knowing:
    // measured, this module's XmlWriter and the JDK serialiser both escape a
    // tab as a character reference, so this test passes with a tab separator
    // too. What protects the encoding is the assertion in TimeLogTest that the
    // encoded text contains no tab, LF or CR at all -- a property, rather than
    // a round trip that happens to be lossless here.
    val doc = load()
    doc.addTimeRecord(
      record(id = "a", description = "tab\there | pipe ; semicolon\nand a newline")
    )
    doc.addTimeRecord(record(id = "b", taskUid = draftUid, start = at(4, 9), seconds = 5400))

    val reloaded = GanttDocument.parse(doc.toXmlString())
    val log = reloaded.timeLog()

    assertEquals(0, log.skippedLines, "nothing may be lost on the way")
    assertEquals(listOf("a", "b"), log.records.map { it.id })
    assertEquals(
      "tab\there | pipe ; semicolon\nand a newline",
      log.records.first { it.id == "a" }.description
    )
    assertEquals(5400L, log.records.first { it.id == "b" }.durationSeconds)
    assertEquals(draftUid, log.records.first { it.id == "b" }.taskUid)
  }

  @Test
  fun `records of different tasks stay apart`() {
    val doc = load()
    doc.addTimeRecord(record(id = "a", taskUid = designUid))
    doc.addTimeRecord(record(id = "b", taskUid = draftUid, start = at(4, 9)))

    assertEquals(listOf("a"), doc.timeLogOfTask(designUid).records.map { it.id })
    assertEquals(listOf("b"), doc.timeLogOfTask(draftUid).records.map { it.id })
    assertEquals(2, doc.timeLog().records.size)
  }

  @Test
  @DisplayName("writing the same record twice is a retry, not two stretches of work")
  fun `the same id replaces rather than joins`() {
    val doc = load()
    doc.addTimeRecord(record(id = "a", seconds = 3600))
    doc.addTimeRecord(record(id = "a", seconds = 7200))

    assertEquals(1, doc.timeLog().records.size)
    assertEquals(7200L, doc.timeLog().records.single().durationSeconds)
  }

  @Test
  fun `a record can be removed again`() {
    val doc = load()
    doc.addTimeRecord(record(id = "a"))
    doc.addTimeRecord(record(id = "b", start = at(4, 9)))

    assertTrue(doc.removeTimeRecord("a"))
    assertEquals(listOf("b"), doc.timeLog().records.map { it.id })
    assertFalse(doc.removeTimeRecord("nothing-like-this"))
  }

  @Test
  @DisplayName("removing the last record clears the value but leaves the column behind")
  fun `an emptied log keeps its column definition`() {
    // Worth pinning because it is visible to the user and surprises people:
    // the stored *value* goes, but the property *definition* stays in the file
    // header, exactly as it does for every other custom property in
    // GanttProject. The desktop therefore keeps showing an empty "time_log"
    // column after the last record is deleted.
    //
    // That is one of the costs of the custom-property interim, and it is a
    // reason to move the log to a model field rather than something to paper
    // over here: removing the definition would also remove a column the user
    // may have arranged deliberately.
    val doc = load()
    doc.addTimeRecord(record(id = "a", description = "Drew the elevation"))
    doc.removeTimeRecord("a")

    val xml = doc.toXmlString()
    assertEquals(0, doc.timeLog().records.size)
    assertFalse(xml.contains("Drew the elevation"), "the value must be gone")
    assertTrue(xml.contains("time_log"), "the column definition stays, as it does for any property")

    val reloaded = GanttDocument.parse(xml)
    assertEquals(0, reloaded.timeLog().records.size)
    assertFalse(reloaded.timeLog().hasLosses, "an emptied log must not read as a damaged one")
  }

  @Test
  @DisplayName("a record reassigned to another task moves rather than being duplicated")
  fun `the same id does not survive on two tasks`() {
    // The case a second import hits when an entry is put on a different task
    // the second time round. Leaving the first copy behind would count the
    // same hours twice, under two different tasks, with nothing to show for it.
    val doc = load()
    doc.addTimeRecord(record(id = "same", taskUid = designUid, seconds = 3600))
    doc.addTimeRecord(record(id = "same", taskUid = draftUid, seconds = 3600))

    assertEquals(1, doc.timeLog().records.size)
    assertEquals(draftUid, doc.timeLog().records.single().taskUid)
    assertEquals(0, doc.timeLogOfTask(designUid).records.size, "nothing left behind")
    assertTrue(
      validateLog(doc.timeLog().records).none { it is TimeLogProblem.DuplicateId }
    )
  }

  // ------------------------------------------------------------- Refusals

  @Test
  @DisplayName("a record for a task that is not there is refused, not dropped")
  fun `an unknown task is refused`() {
    // Returning false rather than writing nowhere: hours that cannot be
    // attached must not vanish quietly, and only the caller can decide what
    // to do instead.
    val doc = load()
    assertFalse(doc.addTimeRecord(record(taskUid = "no-such-uid")))
    assertEquals(0, doc.timeLog().records.size)
  }

  @Test
  fun `a record that does not validate is refused`() {
    val doc = load()
    assertFalse(doc.addTimeRecord(record(seconds = 0)))
    assertFalse(doc.addTimeRecord(record(id = "")))
    assertEquals(0, doc.timeLog().records.size)
  }

  @Test
  fun `tasks without a uid are named rather than silently skipped`() {
    // The sample has a uid on every task, so the list is empty here. The point
    // of the call is a file from an older version, where it would not be.
    assertEquals(emptyList<String>(), load().taskIdsWithoutUid())
  }

  // --------------------------------------------------------------- Labels

  @Test
  fun `labels are stored per task and come back as a map`() {
    val doc = load()
    assertTrue(doc.setTaskLabels(designUid, listOf("V306", "internal")))

    assertEquals(listOf("V306", "internal"), doc.taskLabels(designUid))
    assertEquals(mapOf(designUid to listOf("V306", "internal")), doc.labelsByTaskUid())
    assertEquals(emptyList<String>(), doc.taskLabels(draftUid))
  }

  @Test
  fun `labels survive the round trip too`() {
    val doc = load()
    doc.setTaskLabels(designUid, listOf("V306"))
    val reloaded = GanttDocument.parse(doc.toXmlString())
    assertEquals(listOf("V306"), reloaded.taskLabels(designUid))
  }

  // ------------------------------------------------- The derived total

  @Test
  @DisplayName("a difference between the log and the stored total is reported, not fixed")
  fun `drift is reported`() {
    // effort_actual_hours is an ordinary editable column on the desktop and
    // the widget writes it from another process. A difference is as likely to
    // be a deliberate correction as a bug, so nothing is overwritten behind
    // the user's back.
    val doc = load()
    doc.addTimeRecord(record(id = "a", seconds = 5400))

    val drift = doc.actualHoursDrift()
    assertEquals(1, drift.size)
    assertEquals(designUid, drift.single().taskUid)
    assertNull(drift.single().storedHours)
    assertEquals(1.5, drift.single().loggedHours)

    // still untouched
    assertNull(doc.read().task("0")!!.actualEffortHours)
  }

  @Test
  fun `applying the log writes the total and then there is no drift left`() {
    val doc = load()
    doc.addTimeRecord(record(id = "a", seconds = 5400))
    doc.addTimeRecord(record(id = "b", seconds = 1800, start = at(4, 9)))

    assertEquals(1, doc.applyActualHoursFromLog())
    assertEquals(2.0, doc.read().task("0")!!.actualEffortHours)
    assertEquals(emptyList<GanttDocument.ActualHoursDrift>(), doc.actualHoursDrift())
  }

  @Test
  fun `a hand-typed total that the log does not explain shows up as drift`() {
    val doc = load()
    doc.setTaskActualEffortHours("0", 8.0)
    val drift = doc.actualHoursDrift()
    assertEquals(8.0, drift.single().storedHours)
    assertEquals(0.0, drift.single().loggedHours)
  }

  @Test
  fun `removing every record clears the derived total`() {
    val doc = load()
    doc.addTimeRecord(record(id = "a", seconds = 3600))
    doc.applyActualHoursFromLog()
    assertEquals(1.0, doc.read().task("0")!!.actualEffortHours)

    doc.removeTimeRecord("a")
    doc.applyActualHoursFromLog()
    assertNull(doc.read().task("0")!!.actualEffortHours, "not left standing at the old value")
  }

  // ----------------------------------------------------------- Amendments

  private fun changeAt(hour: Int) =
    OffsetDateTime.of(2026, 8, 10, hour, 0, 0, 0, ZoneOffset.ofHours(2))

  @Test
  @DisplayName("changing a name rewrites the records and leaves a dated note")
  fun `a person change is recorded`() {
    val doc = load()
    doc.addTimeRecord(record(id = "a", description = "x").copy(person = "nat"))
    doc.addTimeRecord(record(id = "b", start = at(4, 9)).copy(person = "nat"))
    doc.addTimeRecord(record(id = "c", start = at(5, 9)).copy(person = "chris"))

    val touched = doc.changePersonInLog("nat", "Natalie", "legal name", changeAt(9), "am-1")
    assertEquals(2, touched)

    assertEquals(
      listOf("Natalie", "Natalie", "chris"),
      doc.timeLog().records.map { it.person }
    )

    val note = doc.logAmendments().single()
    assertEquals("nat", note.from, "the previous value has to stay establishable")
    assertEquals("Natalie", note.to)
    assertEquals(listOf("a", "b"), note.recordIds)
    assertEquals("legal name", note.reason)
    assertEquals(changeAt(9), note.at)
    assertEquals(AmendmentKind.PERSON, note.kind)
  }

  @Test
  @DisplayName("removing a name is a change like any other and is recorded too")
  fun `removing a person leaves a note`() {
    val doc = load()
    doc.addTimeRecord(record(id = "a").copy(person = "nat"))

    assertEquals(1, doc.changePersonInLog("nat", null, "asked to be removed", changeAt(9), "am-1"))
    assertNull(doc.timeLog().records.single().person)

    val note = doc.logAmendments().single()
    assertEquals("nat", note.from)
    assertNull(note.to)
  }

  @Test
  fun `changing a name nobody has does nothing and writes no note`() {
    // A note about a change that did not happen is noise in a file that has to
    // stay readable years later.
    val doc = load()
    doc.addTimeRecord(record(id = "a").copy(person = "nat"))

    assertEquals(0, doc.changePersonInLog("someone else", "x", "typo", changeAt(9), "am-1"))
    assertEquals("nat", doc.timeLog().records.single().person)
    assertTrue(doc.logAmendments().isEmpty())
  }

  @Test
  @DisplayName("two changes in a row keep the original establishable")
  fun `the chain of notes reconstructs the first value`() {
    // The point of the whole mechanism. After two renames the file still says
    // what the name was to begin with, and in what order it changed.
    val doc = load()
    doc.addTimeRecord(record(id = "a").copy(person = "nat"))

    doc.changePersonInLog("nat", "Natalie", "legal name", changeAt(9), "am-1")
    doc.changePersonInLog("Natalie", "N. T.", "shortened", changeAt(11), "am-2")

    val notes = doc.logAmendments()
    assertEquals(2, notes.size)
    assertEquals(listOf("nat", "Natalie"), notes.map { it.from })
    assertEquals(listOf("Natalie", "N. T."), notes.map { it.to })
    assertEquals("nat", notes.first().from, "the original, two changes later")
  }

  @Test
  fun `repeating the same change does not add a second note`() {
    val doc = load()
    doc.addTimeRecord(record(id = "a").copy(person = "nat"))
    doc.changePersonInLog("nat", "Natalie", "legal name", changeAt(9), "am-1")
    // Same id: a retry, not a second change.
    doc.changePersonInLog("Natalie", "Natalie", "legal name", changeAt(9), "am-1")
    assertEquals(1, doc.logAmendments().size)
  }

  @Test
  fun `notes survive the round trip through the XML`() {
    val doc = load()
    doc.addTimeRecord(record(id = "a").copy(person = "nat"))
    doc.changePersonInLog("nat", null, "pipe | comma , semicolon ;", changeAt(9), "am-1")

    val note = GanttDocument.parse(doc.toXmlString()).logAmendments().single()
    assertEquals("am-1", note.id)
    assertEquals("nat", note.from)
    assertEquals("pipe | comma , semicolon ;", note.reason)
    assertEquals(listOf("a"), note.recordIds)
  }

  @Test
  @DisplayName("a note is on every task it touched, so deleting one cannot hide the change")
  fun `notes are not lost with a task`() {
    val doc = load()
    doc.addTimeRecord(record(id = "a", taskUid = designUid).copy(person = "nat"))
    doc.addTimeRecord(record(id = "b", taskUid = draftUid, start = at(4, 9)).copy(person = "nat"))
    doc.changePersonInLog("nat", "Natalie", "legal name", changeAt(9), "am-1")

    // Read as a union and deduplicated, so two copies still read as one note.
    assertEquals(1, doc.logAmendments().size)
    // and each task really does carry it
    assertTrue(doc.toXmlString().split("am-1").size - 1 >= 2)
  }

  // ------------------------------------------------------ The whole chain

  @Test
  @DisplayName("from the file to an export a reporting tool can read")
  fun `document to export`() {
    val doc = load()
    doc.setTaskLabels(designUid, listOf("V306"))
    doc.addTimeRecord(record(id = "a", seconds = 9000, description = "Drew the elevation"))
    doc.addTimeRecord(record(id = "b", taskUid = draftUid, start = at(7, 14), seconds = 2700))

    val json = TimeLogExport.toTogglV2Json(doc.timeLog().records, doc.labelsByTaskUid())
    val entries = Json.parse(json).asObject()?.get("data").asArray()!!.mapNotNull { it.asObject() }

    assertEquals(2, entries.size)
    assertEquals(9_000_000L, entries[0]["dur"].asLong())
    assertEquals(listOf("V306"), entries[0]["tags"].asArray()?.map { it.asString() })
    // the second task carries no label, and that is written as an empty list
    assertEquals(emptyList<String>(), entries[1]["tags"].asArray()?.map { it.asString() })
  }
}
