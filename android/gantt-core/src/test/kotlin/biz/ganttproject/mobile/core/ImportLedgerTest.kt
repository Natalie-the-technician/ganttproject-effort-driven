/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The record of which time entries have already been imported.
 *
 * It is stored per task but read as a union across every task. That
 * indirection is the entire design, so these tests exist to prove the union
 * actually behaves project-wide — in particular that it still blocks an entry
 * after the user moves it to a different task, which is the case a
 * per-(entry, task) key would miss.
 */
class ImportLedgerTest {

  private fun sample(): String =
    checkNotNull(javaClass.getResourceAsStream("/HouseBuildingSample.gan")) {
      "sample project missing from the test classpath"
    }.readBytes().toString(Charsets.UTF_8)

  private fun load() = GanttDocument.parse(sample())

  private fun reload(document: GanttDocument) = GanttDocument.parse(document.toXmlString())

  // ------------------------------------------------------------ Basic use

  @Test
  fun `a project that was never imported into has an empty ledger`() {
    assertTrue(load().importedHoursByEntry().isEmpty())
  }

  @Test
  fun `a recorded entry survives a save and reload`() {
    val document = load()
    assertTrue(document.recordImportedHours("13", 100L, 2.5))

    val written = document.toXmlString()
    assertTrue(written.contains("""name="toggl_imported""""), "definition must be written")
    assertTrue(written.contains("100=2.5"), "value must be written: $written")

    assertEquals(mapOf(100L to 2.5), reload(document).importedHoursByEntry())
  }

  @Test
  fun `recording the same entry twice adds up`() {
    // The entry grew in the tracker and the growth was imported later; the
    // ledger has to reflect the total actually written, not the last write.
    val document = load()
    document.recordImportedHours("13", 100L, 2.5)
    document.recordImportedHours("13", 100L, 1.5)
    assertEquals(mapOf(100L to 4.0), reload(document).importedHoursByEntry())
  }

  @Test
  fun `non-positive hours are refused`() {
    val document = load()
    assertFalse(document.recordImportedHours("13", 100L, 0.0))
    assertFalse(document.recordImportedHours("13", 100L, -1.0))
    assertTrue(document.importedHoursByEntry().isEmpty())
  }

  @Test
  fun `recording against an unknown task is refused`() {
    assertFalse(load().recordImportedHours("no-such-task", 100L, 1.0))
  }

  // ------------------------------------------------------------- The union

  @Test
  fun `entries recorded on different tasks all show up`() {
    val document = load()
    document.recordImportedHours("13", 100L, 2.0)
    document.recordImportedHours("15", 101L, 1.0)
    assertEquals(mapOf(100L to 2.0, 101L to 1.0), reload(document).importedHoursByEntry())
  }

  @Test
  fun `the union reaches tasks nested deep in the hierarchy`() {
    // Task 9 sits under task 0. A flat scan of the top level would miss it
    // and silently allow a second import of everything below the first level.
    val document = load()
    document.recordImportedHours("9", 100L, 3.0)
    assertEquals(mapOf(100L to 3.0), reload(document).importedHoursByEntry())
  }

  @Test
  fun `one entry split across two tasks is summed, not overwritten`() {
    val document = load()
    document.recordImportedHours("13", 100L, 1.0)
    document.recordImportedHours("15", 100L, 1.5)
    assertEquals(mapOf(100L to 2.5), reload(document).importedHoursByEntry())
    // Each task still knows only its own share.
    assertEquals(mapOf(100L to 1.0), reload(document).importedHoursOfTask("13"))
    assertEquals(mapOf(100L to 1.5), reload(document).importedHoursOfTask("15"))
  }

  // ----------------------------------------------- The case that motivated it

  @Test
  @DisplayName("an entry moved to another task on the second run is still blocked")
  fun `reassignment does not defeat the guard`() {
    val document = load()

    // First run: entry 100 (2.5 h) goes onto task 13.
    val first = planImport(
      listOf(ImportAssignment(100L, "13", 2.5)),
      document.importedHoursByEntry()
    )
    assertEquals(2.5, first.totalHours, 1e-9)
    for (line in first.lines.filter { !it.isSkipped }) {
      document.addTaskActualEffortHours(line.taskId, line.hoursToAdd)
      document.recordImportedHours(line.taskId, line.entryId, line.hoursToAdd)
    }

    // Second run, after a save and reload, with the entry now assigned to a
    // different task. A ledger keyed by (entry, task) would see nothing here.
    val reopened = reload(document)
    val second = planImport(
      listOf(ImportAssignment(100L, "15", 2.5)),
      reopened.importedHoursByEntry()
    )
    assertEquals(0.0, second.totalHours, 1e-9, "the hours would have been booked a second time")
    assertTrue(second.skippedEntryIds.contains(100L))

    // And the hours in the file are unchanged: 2.5 on task 13, none on 15.
    assertEquals(2.5, reopened.read().task("13")!!.actualEffortHours)
    assertEquals(null, reopened.read().task("15")!!.actualEffortHours)
  }

  @Test
  fun `running the exact same import twice writes nothing the second time`() {
    val document = load()
    val assignments = listOf(ImportAssignment(100L, "13", 2.5), ImportAssignment(101L, "15", 1.0))

    repeat(2) {
      val plan = planImport(assignments, document.importedHoursByEntry())
      for (line in plan.lines.filter { !it.isSkipped }) {
        document.addTaskActualEffortHours(line.taskId, line.hoursToAdd)
        document.recordImportedHours(line.taskId, line.entryId, line.hoursToAdd)
      }
    }

    val model = reload(document).read()
    assertEquals(2.5, model.task("13")!!.actualEffortHours, "second run doubled the hours")
    assertEquals(1.0, model.task("15")!!.actualEffortHours, "second run doubled the hours")
  }

  @Test
  fun `only the growth of an entry is imported on a later run`() {
    val document = load()
    document.addTaskActualEffortHours("13", 2.5)
    document.recordImportedHours("13", 100L, 2.5)

    // The entry now runs to 4.0 h in the tracker.
    val plan = planImport(listOf(ImportAssignment(100L, "13", 4.0)), document.importedHoursByEntry())
    assertEquals(1.5, plan.totalHours, 1e-9)
  }

  // -------------------------------------------------------------- Robustness

  @Test
  fun `a corrupt fragment is skipped and the rest still counts`() {
    // A hand-edited or truncated value must not make the project unopenable.
    // Skipping only risks re-offering an import, which the preview shows.
    assertEquals(
      mapOf(100L to 2.5, 102L to 1.0),
      ForkProperties.decodeImportedHours("100=2.5|garbage|101=|=3|102=1.0")
    )
  }

  @Test
  fun `the same entry id twice within one value is summed`() {
    // Our own writer merges before encoding, so this cannot arise from the
    // app itself — but the decoder promises to sum, and a hand-edited file or
    // a future writer could produce it. Without this test that promise was
    // unchecked: a counter-test that replaced the sum with an overwrite went
    // unnoticed by the whole suite.
    assertEquals(mapOf(100L to 2.5), ForkProperties.decodeImportedHours("100=1|100=1.5"))
    assertEquals(
      mapOf(100L to 3.0, 101L to 1.0),
      ForkProperties.decodeImportedHours("100=1|101=1|100=2")
    )
  }

  @Test
  fun `an empty or absent value decodes to an empty ledger`() {
    assertTrue(ForkProperties.decodeImportedHours(null).isEmpty())
    assertTrue(ForkProperties.decodeImportedHours("").isEmpty())
    assertTrue(ForkProperties.decodeImportedHours("   ").isEmpty())
  }

  @Test
  fun `encoding and decoding round-trips`() {
    val ledger = mapOf(100L to 2.5, 101L to 1.0, 102L to 8.0)
    assertEquals(ledger, ForkProperties.decodeImportedHours(ForkProperties.encodeImportedHours(ledger)))
  }

  @Test
  fun `a zero-hour record is never written`() {
    // Writing 0 would claim the entry was imported and block it forever.
    assertEquals("", ForkProperties.encodeImportedHours(mapOf(100L to 0.0)))
  }

  @Test
  fun `the ledger does not disturb the rest of the file`() {
    val document = load()
    document.recordImportedHours("13", 100L, 2.5)
    val written = document.toXmlString()
    assertTrue(written.contains("""<view id="resource-table">"""), "view lost")
    assertTrue(written.contains("""<role id="5" name="Roofer"/>"""), "role lost")
    assertTrue(
      written.contains("""<customproperty taskproperty-id="tpc0" value="true"/>"""),
      "an existing custom property was clobbered"
    )
  }

  @Test
  fun `the ledger and the learned match keys coexist on one task`() {
    val document = load()
    document.recordImportedHours("13", 100L, 2.5)
    document.addTaskMatchKey("13", "furniture call")
    document.setTaskActualEffortHours("13", 2.5)

    val reloaded = reload(document)
    assertEquals(mapOf(100L to 2.5), reloaded.importedHoursByEntry())
    assertEquals(listOf("furniture call"), reloaded.read().task("13")!!.togglMatchKeys)
    assertEquals(2.5, reloaded.read().task("13")!!.actualEffortHours)
  }
}
