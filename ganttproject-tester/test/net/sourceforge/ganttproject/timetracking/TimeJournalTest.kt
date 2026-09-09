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
import org.junit.jupiter.api.Assertions.assertNotEquals
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
 * [fork change] The hour journal, desktop side -- and the guard that keeps
 * this implementation and the Android one from drifting apart.
 *
 * The three `shared-*.timelog` files under `ganttproject-tester/resources`
 * are byte for byte the ones in `android/gantt-core/src/test/resources`.
 * Neither implementation produced them: they were written by hand from the
 * format specification, so a fixture can disagree with an implementation --
 * which is the only way it can catch one. The digests below are pinned
 * identically in the app's `TimeJournalTest`, so a tree that edits its own
 * copy turns its own side red.
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

  /** The same three digests the app side carries. This is the whole mechanism. */
  private val sharedDigests = linkedMapOf(
    "shared-empty.timelog" to "31e9d97814a6b4d63bda09bbebbd2739d472c4e56d22ac4c9bccb098d954967a",
    "shared-one.timelog" to "0af0fae5f16484b98fdaa2ef63865867d1b7c61e3c551a62a54c8206d9857bf5",
    "shared-many.timelog" to "cf837134829e3c2a5c8c58b4bde4a4267bfcc4239a507405936a8a2b743d5ee0"
  )

  // ------------------------------------------------------- The shared fixture

  @Test
  @DisplayName("the shared fixture is byte for byte the one the app side has")
  fun `the shared fixture is the agreed one`() {
    sharedDigests.forEach { (name, expected) ->
      assertEquals(expected, sha256(fixtureBytes(name)), "$name has been edited on this side only")
    }
  }

  @Test
  @DisplayName("reading the shared journal yields the records it names")
  fun `the shared journal reads`() {
    val read = TimeJournal.read(fixture("shared-many.timelog"))
    assertNull(read.problem, "the fixture should be a valid journal")
    assertEquals(0, read.skippedLines, "no line of the fixture may be dropped")
    assertEquals(9, read.records.size, "nine record lines")
    assertEquals(8, read.records.map { it.id }.toSet().size, "eight distinct ids, one of them twice")

    val byId = read.records.groupBy { it.id }
    assertEquals("Dach decken", byId.getValue("r-001").single().note)
    assertEquals(9000L, byId.getValue("r-001").single().durationSeconds)
    assertEquals("Berg|mann", byId.getValue("r-002").single().person, "a separator inside a name")
    assertEquals("Clara\nZweitname", byId.getValue("r-003").single().person, "a line break inside a name")
    assertEquals("Pfad C:\\temp;Notiz\tEnde", byId.getValue("r-004").single().note)
    assertEquals(JournalSource.IMPORTED, byId.getValue("r-004").single().source)
    assertEquals(2, byId.getValue("r-005").size, "the same id twice, kept and not merged away")
    assertNull(byId.getValue("r-009").single().person, "an empty person reads back as none")
    assertEquals("", byId.getValue("r-009").single().note)
    assertEquals(
      OffsetDateTime.parse("2026-09-10T05:00:30.123456789Z"),
      byId.getValue("r-008").single().start,
      "nanoseconds and a Z offset"
    )
    assertEquals(
      ZoneOffset.ofHours(1),
      byId.getValue("r-007").single().start.offset,
      "the record's own offset is kept, not normalised away"
    )
  }

  @Test
  @DisplayName("writing what the shared journal says produces the shared journal, byte for byte")
  fun `the shared journal round trips through bytes`() {
    val original = fixtureBytes("shared-many.timelog")
    val records = TimeJournal.read(original.toString(Charsets.UTF_8)).records
    val written = TimeJournal.write(records)
    assertEquals(emptyList<JournalRecord>(), written.refused, "nothing in the fixture may be refused")
    assertEquals(
      original.toString(Charsets.UTF_8),
      written.text,
      "the written journal differs from the shared fixture"
    )
    assertTrue(original.contentEquals(written.text.toByteArray(Charsets.UTF_8)), "bytes differ")
  }

  @Test
  @DisplayName("the empty and the single-record journals round trip as well")
  fun `the small shared journals round trip`() {
    listOf("shared-empty.timelog", "shared-one.timelog").forEach { name ->
      val original = fixture(name)
      val records = TimeJournal.read(original).records
      assertEquals(original, TimeJournal.write(records).text, "$name does not come back unchanged")
    }
    assertEquals(0, TimeJournal.read(fixture("shared-empty.timelog")).records.size)
    assertEquals(1, TimeJournal.read(fixture("shared-one.timelog")).records.size)
  }

  // --------------------------------------------------------------- Stability

  @Test
  @DisplayName("the order the records arrive in does not change a single byte")
  fun `the same records in another order write the same bytes`() {
    val records = TimeJournal.read(fixture("shared-many.timelog")).records
    val expected = TimeJournal.write(records).text
    assertEquals(9, records.size)
    assertEquals(10, expected.trimEnd('\n').lines().size, "header and nine records")

    val orders = linkedMapOf(
      "reversed" to records.reversed(),
      "by id" to records.sortedBy { it.id },
      "by note" to records.sortedBy { it.note },
      "rotated" to (records.drop(4) + records.take(4)),
      // Fixed, not random: a test that fails only sometimes cannot be acted on.
      "fixed shuffle" to records.shuffled(kotlin.random.Random(20260909L))
    )
    orders.forEach { (label, shuffled) ->
      assertEquals(expected, TimeJournal.write(shuffled).text, "order '$label' produced other bytes")
    }
  }

  @Test
  @DisplayName("writing the same records twice produces the same bytes")
  fun `writing twice is byte identical`() {
    val records = TimeJournal.read(fixture("shared-many.timelog")).records
    assertEquals(9, records.size)
    assertEquals(TimeJournal.write(records).text, TimeJournal.write(records).text)
    assertTrue(TimeJournal.write(records).text.length > TimeJournal.HEADER.length + 100)
  }

  @Test
  @DisplayName("a non-canonical timestamp is normalised on write")
  fun `writing normalises`() {
    val r = JournalRecord(
      id = "n-1",
      taskUid = "t-1",
      start = OffsetDateTime.parse("2026-09-09T08:00:00+02:00"),
      durationSeconds = 60,
      createdAt = OffsetDateTime.parse("2026-09-09T08:00:00+02:00")
    )
    val text = TimeJournal.write(listOf(r)).text
    assertTrue(text.contains("2026-09-09T08:00+02:00"), "expected the short form, got:\n$text")
    assertEquals(text, TimeJournal.write(TimeJournal.read(text).records).text, "not a fixed point")
  }

  // -------------------------------------------------------------- Idempotence

  @Test
  @DisplayName("the same journal read twice does not double the hours")
  fun `restoring twice does not double the hours`() {
    val text = fixture("shared-many.timelog")
    val once = TimeJournal.merge(TimeJournal.read(text).records, emptyList())
    val twice = TimeJournal.merge(TimeJournal.read(text).records, once)
    val thrice = TimeJournal.merge(TimeJournal.read(text).records, twice)

    assertEquals(once.size, twice.size, "a second restore added records")
    assertEquals(once, twice, "a second restore changed the records")
    assertEquals(once, thrice, "a third restore changed the records")
    assertEquals(
      TimeJournal.secondsByTaskUid(once),
      TimeJournal.secondsByTaskUid(twice),
      "the hours per task changed on the second restore"
    )
    // The duplicate id is what makes this worth anything: nine lines, eight
    // records, and the total must be the eight.
    assertEquals(8, once.size, "the duplicate id must collapse to one record on restore")
  }

  @Test
  @DisplayName("restoring into a set that already holds the records changes nothing")
  fun `restoring over the existing records changes nothing`() {
    val records = TimeJournal.read(fixture("shared-many.timelog")).records
    val existing = TimeJournal.merge(records, emptyList())
    assertEquals(8, existing.size, "otherwise this test proves nothing")
    val after = TimeJournal.merge(TimeJournal.read(TimeJournal.write(existing).text).records, existing)
    assertEquals(existing, after)
    assertEquals(TimeJournal.write(existing).text, TimeJournal.write(after).text)
  }

  // ----------------------------------------------------------------- Refusals

  @Test
  @DisplayName("a file without the header is refused rather than guessed at")
  fun `a headerless file is refused`() {
    val body = fixture("shared-many.timelog").lines().drop(1).joinToString("\n")
    val read = TimeJournal.read(body)
    assertEquals(JournalProblem.NO_HEADER, read.problem)
    assertEquals(emptyList<JournalRecord>(), read.records, "a refused file must yield no records")
  }

  @Test
  @DisplayName("a journal from a newer version is recognised as such")
  fun `a newer version is refused by name`() {
    val text = fixture("shared-many.timelog").replaceFirst("#gp-timelog 1 ", "#gp-timelog 2 ")
    val read = TimeJournal.read(text)
    assertEquals(JournalProblem.UNKNOWN_VERSION, read.problem)
    assertEquals(emptyList<JournalRecord>(), read.records)
  }

  @Test
  @DisplayName("an unreadable line is skipped, counted, and does not take the file down")
  fun `a damaged line is counted`() {
    val text = fixture("shared-many.timelog").trimEnd('\n') + "\nnot-a-record\n"
    val read = TimeJournal.read(text)
    assertNull(read.problem)
    assertEquals(1, read.skippedLines)
    assertEquals(9, read.records.size, "the intact lines must still be there")
  }

  @Test
  @DisplayName("a record that could not be read back is refused rather than silently written")
  fun `an unreadable record is refused`() {
    val good = TimeJournal.read(fixture("shared-one.timelog")).records.single()
    val written = TimeJournal.write(listOf(good.copy(id = "  "), good.copy(taskUid = ""), good))
    assertEquals(2, written.refused.size, "both broken records should be refused")
    assertEquals(1, TimeJournal.read(written.text).records.size, "only the intact record is written")
    assertEquals(0, TimeJournal.read(written.text).skippedLines, "a written journal reads without loss")
  }

  @Test
  @DisplayName("a zero-length record is written, because it can be read back")
  fun `a zero duration is kept`() {
    val good = TimeJournal.read(fixture("shared-one.timelog")).records.single()
    val zero = good.copy(id = "z-1", durationSeconds = 0)
    val written = TimeJournal.write(listOf(zero))
    assertEquals(emptyList<JournalRecord>(), written.refused, "refusing it would lose it silently")
    assertEquals(zero, TimeJournal.read(written.text).records.single())
  }

  // --------------------------------------------------------------- Change list

  private fun records() = TimeJournal.read(fixture("shared-many.timelog")).records

  @Test
  @DisplayName("nothing changed means nothing to commit")
  fun `no change means no commit`() {
    val r = records()
    val summary = TimeJournal.changeSummary(r, r, zone)
    assertTrue(summary.isEmpty, "an unchanged set produced: ${TimeJournal.renderChangeList(summary)}")
    assertNull(
      TimeJournal.commitMessage(OffsetDateTime.parse("2026-09-09T18:04+02:00"), summary, zone),
      "there must be no commit message when there is nothing to commit"
    )
  }

  @Test
  @DisplayName("the change list names exactly what came in, in Natalie's form")
  fun `the change list names what came in`() {
    val before = records()
    val added = JournalRecord(
      id = "r-100",
      taskUid = "t-roof",
      start = OffsetDateTime.parse("2026-09-09T14:00+02:00"),
      durationSeconds = 9000,
      note = "Dach decken",
      person = "Anna"
    )
    val summary = TimeJournal.changeSummary(before, before + added, zone) { uid ->
      if (uid == "t-roof") "Dach decken" else null
    }
    assertEquals(1, summary.lines.size, "exactly one thing came in")
    assertEquals(ChangeKind.ADDED, summary.lines.single().kind)
    assertEquals(9000L, summary.lines.single().seconds)
    assertEquals("+2,5 h  Dach decken (Anna)  09.09.", TimeJournal.renderChangeList(summary))

    assertEquals(
      "Stunden 09.09.2026 18:04\n\n+2,5 h  Dach decken (Anna)  09.09.\n",
      TimeJournal.commitMessage(OffsetDateTime.parse("2026-09-09T18:04+02:00"), summary, zone)
    )
  }

  @Test
  @DisplayName("a removed and a changed record are named as such")
  fun `removal and change are named`() {
    val before = records()
    val gone = before.first { it.id == "r-001" }
    val afterRemoval = before - gone
    val removal = TimeJournal.changeSummary(before, afterRemoval, zone) { "Dach" }
    assertEquals(ChangeKind.REMOVED, removal.lines.single().kind)
    assertEquals(-9000L, removal.lines.single().seconds)
    assertEquals("-2,5 h  Dach (Anna)  09.09.", TimeJournal.renderChangeList(removal))

    val longer = afterRemoval + gone.copy(durationSeconds = 10800)
    val change = TimeJournal.changeSummary(before, longer, zone) { "Dach" }
    assertEquals(ChangeKind.CHANGED, change.lines.single().kind)
    assertEquals(1800L, change.lines.single().seconds, "the delta, not the new total")
    assertEquals("~+0,5 h  Dach (Anna)  09.09.", TimeJournal.renderChangeList(change))
  }

  @Test
  @DisplayName("ten new records stay a short list")
  fun `ten records stay short`() {
    val before = records()
    val ten = (1..10).map { i ->
      JournalRecord(
        id = "m-$i",
        taskUid = "t-$i",
        start = OffsetDateTime.parse("2026-09-11T08:00+02:00").plusHours(i.toLong()),
        durationSeconds = 3600L * i,
        note = "Arbeit $i",
        person = "Anna"
      )
    }
    val summary = TimeJournal.changeSummary(before, before + ten, zone)
    val rendered = TimeJournal.renderChangeList(summary)

    assertEquals(3, summary.lines.size, "the list is capped")
    assertEquals(7, summary.omittedLines, "the rest is counted, not dropped")
    assertEquals(3600L * (1 + 2 + 3 + 4 + 5 + 6 + 7), summary.omittedSeconds, "and its hours are named")
    assertEquals(4, rendered.lines().size, "three lines and the tail")
    assertTrue(rendered.lines().last().startsWith("… und 7 weitere"), "got: ${rendered.lines().last()}")
    assertTrue(rendered.length < 200, "still ${rendered.length} characters:\n$rendered")
    assertEquals(3600L * 10, summary.lines.first().seconds, "biggest first, so the cap keeps what matters")
  }

  @Test
  @DisplayName("several records on one task and day become one line")
  fun `one task and day is one line`() {
    val before = records()
    val five = (1..5).map { i ->
      JournalRecord(
        id = "s-$i",
        taskUid = "t-roof",
        start = OffsetDateTime.parse("2026-09-12T08:00+02:00").plusHours(i.toLong()),
        durationSeconds = 1800,
        note = "Dach decken",
        person = "Anna"
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
    // Rounded to hundredths, but never down to nothing.
    assertEquals("+0,01", TimeJournal.hoursText(17))
    assertEquals("+0,01", TimeJournal.hoursText(1))
  }

  @Test
  @DisplayName("the change list does not depend on the order the records arrive in")
  fun `the change list is stable`() {
    val before = records()
    val added = before.first { it.id == "r-001" }.copy(id = "r-200")
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

  // ---------------------------------------------------------------- The store

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

  // ------------------------------------- The journal changes no project data

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
    val log = TimeJournal.read(fixture("shared-many.timelog")).records
    assertTrue(log.isNotEmpty(), "there must be something to journal")
    val journal = TimeJournal.write(log)
    assertEquals(0, journal.refused.size, "the guard is worthless if nothing was journalled")
    val summary = TimeJournal.changeSummary(emptyList(), log, zone)
    assertTrue(summary.lines.isNotEmpty(), "and worthless if the change list was empty too")
    TimeJournal.commitMessage(OffsetDateTime.parse("2026-09-09T18:04+02:00"), summary, zone)
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

    // Three separate changes, each of a kind the journal path could plausibly
    // make by accident. If the fingerprint could not see all three, it could
    // not be trusted about any of them. Six earlier sittings were caught out
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
  }
}
