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
 * Which entries the import may take without asking.
 *
 * The whole point of these tests is the NEGATIVE side: an entry that does not clearly name its
 * task must NOT be imported. Writing hours onto the wrong task is silent — nobody notices until
 * the calibration is wrong — so "imports less" has to beat "guesses".
 */
class ImportSelectionTest : TestCase() {

  private fun entry(id: Long, description: String, tags: List<String> = emptyList(), seconds: Long = 3600) =
    TogglTimeEntry(
      id = id,
      start = OffsetDateTime.parse("2026-08-10T09:00:00+02:00"),
      durationSeconds = seconds,
      description = description,
      projectId = null,
      projectName = null,
      tags = tags)

  private val tasks = setOf(332, 333)

  fun testAnEntryNamingItsTaskIsImported() {
    val selection = selectUnambiguousImports(listOf(entry(1, "#332 Firmware")), tasks)

    assertEquals(1, selection.assignments.size)
    assertEquals(332, selection.assignments[0].second)
    assertTrue(selection.withoutNumber.isEmpty())
    assertTrue(selection.unknownNumber.isEmpty())
  }

  /** The number may sit in a tag rather than in the text. */
  fun testTheNumberMayComeFromATag() {
    val selection = selectUnambiguousImports(listOf(entry(1, "Firmware", listOf("#333"))), tasks)

    assertEquals(333, selection.assignments.single().second)
  }

  /**
   * THE case this narrow rule exists for. "Firmware" resembles a task name, was perhaps booked on
   * the right day, maybe even imported to that task before — all of that is a guess, and a guess
   * writes hours onto a task nobody chose.
   */
  fun testAnEntryWithoutANumberIsNotImported() {
    val selection = selectUnambiguousImports(listOf(entry(1, "Firmware")), tasks)

    assertTrue("an entry was assigned without anybody choosing", selection.assignments.isEmpty())
    assertEquals(1, selection.withoutNumber.size)
  }

  /**
   * A number naming no task is reported SEPARATELY: it is almost certainly a typo in Toggl, and
   * that is worth telling apart from "needs the matching dialog".
   */
  fun testANumberThatNamesNoTaskIsReportedApart() {
    val selection = selectUnambiguousImports(listOf(entry(1, "#999 Firmware")), tasks)

    assertTrue(selection.assignments.isEmpty())
    assertEquals(1, selection.unknownNumber.size)
    assertTrue("a typo was lumped in with the entries needing the dialog",
      selection.withoutNumber.isEmpty())
  }

  /** A running entry has a negative duration and no end. Importing it would credit negative hours. */
  fun testARunningEntryIsNotImported() {
    val selection = selectUnambiguousImports(listOf(entry(1, "#332 Firmware", seconds = -1)), tasks)

    assertTrue("a running entry was imported", selection.assignments.isEmpty())
  }

  fun testTheThreeBucketsTogetherAccountForEveryEntry() {
    val entries = listOf(
      entry(1, "#332 Firmware"),
      entry(2, "Firmware"),
      entry(3, "#999 Firmware"),
      entry(4, "#333 Test"))

    val selection = selectUnambiguousImports(entries, tasks)

    assertEquals("entries went missing between the buckets", entries.size,
      selection.assignments.size + selection.unknownNumber.size + selection.withoutNumber.size)
    assertEquals(2, selection.assignments.size)
    assertTrue(selection.hasAnythingToImport)
  }

  // --- Uebersprungene Eintraege benennen ---

  /**
   * Reporting only a COUNT tells the user that something is missing but not which — and without
   * that they cannot act. The line has to carry enough to find the entry again in Toggl: the day
   * it was booked, its text, and its hours.
   */
  fun testASkippedEntryIsNamedWithDateTextAndHours() {
    val line = describeEntry(entry(1, "Firmware", seconds = 5400))

    assertTrue("das Datum fehlt: $line", line.contains("2026-08-10"))
    assertTrue("der Text fehlt: $line", line.contains("Firmware"))
    assertTrue("die Stunden fehlen: $line", line.contains("1,50") || line.contains("1.50"))
  }

  /** An empty line in a list of skipped items would read as a display fault. */
  fun testAnEntryWithoutTextStillShowsSomething() {
    val line = describeEntry(entry(1, "   "))
    assertTrue("nichts Erkennbares in: $line", line.contains("ohne Text"))
  }

  /**
   * The cap must not be silent: a list that stops without saying so reads as complete, and would
   * then contradict the count in the message above it.
   */
  fun testALongListSaysHowManyItLeftOut() {
    val many = (1..20).map { entry(it.toLong(), "Eintrag $it") }

    val text = describeEntries(many, limit = 12)

    assertEquals("es muessen 12 Zeilen plus die Restzeile sein", 13, text.lines().size)
    assertTrue("die Restzeile fehlt: $text", text.contains("8 weitere"))
  }

  fun testAShortListHasNoRemainderLine() {
    val text = describeEntries(listOf(entry(1, "Firmware")), limit = 12)
    assertFalse(text.contains("weitere"))
    assertEquals(1, text.lines().size)
  }

  fun testAnEmptyListYieldsNothing() {
    assertEquals("", describeEntries(emptyList()))
  }

  // --- period of the import ---

  fun testAPlainNumberIsAccepted() {
    assertEquals(30, parseImportDays("30"))
    assertEquals(60, parseImportDays(" 60 "))
    assertEquals(MIN_IMPORT_DAYS, parseImportDays("$MIN_IMPORT_DAYS"))
    assertEquals(MAX_IMPORT_DAYS, parseImportDays("$MAX_IMPORT_DAYS"))
  }

  /**
   * The upper bound is Toggl's, not ours: `/me/time_entries` answers at most three months and
   * rejects longer periods with status 400. Pinned here because a larger value would produce a
   * field that accepts input the service is certain to refuse — seen live with 99 and 300 days.
   */
  fun testTheUpperBoundIsTheOneTogglAllows() {
    assertEquals(90, MAX_IMPORT_DAYS)
    assertEquals(90, parseImportDays("90"))
    assertNull("91 Tage sind mehr als drei Monate und werden von Toggl abgewiesen",
      parseImportDays("91"))
  }

  /**
   * Every one of these would otherwise become a period nobody meant: an empty field and letters
   * silently become the default, zero and negative numbers a range that ends before it starts.
   * Returning null lets the dialog say so instead.
   */
  /**
   * A value stored by an older version can be out of range — exactly what happened: while the
   * upper bound was wrongly 3650, the number 93 stayed in the settings. Prefilled unchanged it
   * would put a value in the field that Toggl rejects with 400, and the user would have to guess
   * why.
   */
  fun testAStoredValueOutOfRangeIsPulledIntoIt() {
    assertEquals(MAX_IMPORT_DAYS, usableImportDays(93))
    assertEquals(MAX_IMPORT_DAYS, usableImportDays(3650))
    assertEquals(MIN_IMPORT_DAYS, usableImportDays(0))
    assertEquals(MIN_IMPORT_DAYS, usableImportDays(-5))
  }

  fun testAStoredValueInRangeIsKept() {
    assertEquals(30, usableImportDays(30))
    assertEquals(DEFAULT_IMPORT_DAYS, usableImportDays(null))
  }

  fun testNonsenseIsRefusedRatherThanGuessed() {
    assertNull(parseImportDays(null))
    assertNull(parseImportDays(""))
    assertNull(parseImportDays("   "))
    assertNull(parseImportDays("dreissig"))
    assertNull(parseImportDays("30 Tage"))
    assertNull(parseImportDays("0"))
    assertNull(parseImportDays("-5"))
    assertNull(parseImportDays("2,5"))
  }

  /**
   * Beyond the upper bound Toggl may truncate the answer without saying so, and the import would
   * be silently incomplete. A bound the user can see beats an invisible one.
   */
  fun testTooLargeAPeriodIsRefused() {
    assertNull(parseImportDays("${MAX_IMPORT_DAYS + 1}"))
    assertNull(parseImportDays("999999"))
  }

  fun testNothingToImportIsReportedAsSuch() {
    val selection = selectUnambiguousImports(listOf(entry(1, "Firmware")), tasks)
    assertFalse(selection.hasAnythingToImport)

    assertFalse(selectUnambiguousImports(emptyList(), tasks).hasAnythingToImport)
  }
}
