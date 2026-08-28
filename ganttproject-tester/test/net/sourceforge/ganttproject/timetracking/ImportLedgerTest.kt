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

import junit.framework.TestCase
import java.time.OffsetDateTime

/**
 * Tests the bookkeeping that keeps a second import from counting the same hours twice.
 *
 * The arithmetic is tested here on its own; where the text ends up being stored is a separate
 * decision and does not affect these tests.
 */
class ImportLedgerTest : TestCase() {

  private fun entry(id: Long, hours: Double) = TogglTimeEntry(
    id = id,
    start = OffsetDateTime.parse("2026-08-11T09:00:00+02:00"),
    durationSeconds = (hours * 3600).toLong(),
    description = "egal",
    projectId = null,
    projectName = null,
    tags = emptyList())

  // --- encoding ---

  fun testEmptyLedgerIsEmptyText() {
    assertEquals("", encodeImportLedger(emptyMap()))
  }

  fun testRoundTrip() {
    val ledger = mapOf(1234L to 1.5, 5678L to 2.0)
    assertEquals(ledger, decodeImportLedger(encodeImportLedger(ledger)))
  }

  /**
   * The decimal point must not depend on the locale: the file travels between machines, and a
   * comma would turn 1.5 hours into something else when read elsewhere.
   */
  fun testHoursUseADecimalPointNotAComma() {
    val text = encodeImportLedger(mapOf(1L to 1.5))
    assertTrue("expected a decimal point in <$text>", text.contains("1.5"))
    assertFalse("a comma would change meaning on another machine", text.contains(","))
  }

  /** Same content must give the same text, otherwise every save churns the file. */
  fun testOrderIsDeterministic() {
    val a = encodeImportLedger(linkedMapOf(9L to 1.0, 1L to 2.0, 5L to 3.0))
    val b = encodeImportLedger(linkedMapOf(5L to 3.0, 9L to 1.0, 1L to 2.0))
    assertEquals(a, b)
  }

  // --- decoding is robust, because the column is editable in stock GanttProject ---

  fun testNullAndEmptyDecodeToNothing() {
    assertTrue(decodeImportLedger(null).isEmpty())
    assertTrue(decodeImportLedger("").isEmpty())
  }

  fun testGarbageIsSkippedInsteadOfThrowing() {
    // Someone typed into the column by hand. The readable pair must survive.
    val decoded = decodeImportLedger("abc;12:;:3;7:x;4:2.5;;9")
    assertEquals(mapOf(4L to 2.5), decoded)
  }

  fun testNonPositiveHoursAreIgnored() {
    assertTrue(decodeImportLedger("1:0;2:-3").isEmpty())
  }

  // --- assembling the project-wide view ---

  fun testMergeUnionsSeveralTasks() {
    val merged = mergeLedgers(listOf("1:2", "2:3.5", null, ""))
    assertEquals(mapOf(1L to 2.0, 2L to 3.5), merged)
  }

  /**
   * One Toggl entry can be split across several tasks. The guard asks how many hours of it are
   * recorded ANYWHERE, so the parts have to be added. Taking one of them would let a second run
   * import the rest again.
   */
  fun testMergeAddsUpAnEntrySplitOverTwoTasks() {
    assertEquals(mapOf(42L to 3.0), mergeLedgers(listOf("42:1", "42:2")))
  }

  // --- what the ledger looks like after an import ---

  fun testNewEntryIsRecordedWithItsFullHours() {
    val decisions = planImport(listOf(entry(1L, 2.0)), emptyMap())
    assertEquals(mapOf(1L to 2.0), ledgerAfterImport(emptyMap(), decisions))
  }

  fun testUnchangedEntryKeepsItsRecord() {
    val previous = mapOf(1L to 2.0)
    val decisions = planImport(listOf(entry(1L, 2.0)), previous)
    assertEquals(previous, ledgerAfterImport(previous, decisions))
  }

  /** A changed entry is recorded with its NEW total, not with the difference. */
  fun testChangedEntryIsRecordedWithTheNewTotal() {
    val previous = mapOf(1L to 2.0)
    val decisions = planImport(listOf(entry(1L, 3.5)), previous)
    assertEquals(mapOf(1L to 3.5), ledgerAfterImport(previous, decisions))
  }

  /** Entries of earlier runs that are not part of this import must survive. */
  fun testUnrelatedEntriesSurvive() {
    val previous = mapOf(99L to 1.0)
    val decisions = planImport(listOf(entry(1L, 2.0)), previous)
    assertEquals(mapOf(99L to 1.0, 1L to 2.0), ledgerAfterImport(previous, decisions))
  }

  /**
   * The point of the whole file: importing the same data twice must not change the recorded
   * hours, and must add nothing to the tasks.
   */
  fun testSecondImportOfTheSameDataAddsNothing() {
    val entries = listOf(entry(1L, 2.0), entry(2L, 1.5))

    val firstRun = planImport(entries, emptyMap())
    val afterFirst = ledgerAfterImport(emptyMap(), firstRun)
    val addedFirst = firstRun.sumOf { it.hoursDelta() }

    val secondRun = planImport(entries, afterFirst)
    val afterSecond = ledgerAfterImport(afterFirst, secondRun)
    val addedSecond = secondRun.sumOf { it.hoursDelta() }

    assertEquals(3.5, addedFirst, 0.001)
    assertEquals("a second run must add nothing", 0.0, addedSecond, 0.001)
    assertEquals(afterFirst, afterSecond)
  }
}
