/*
Copyright 2026

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
// NEW FILE IN THIS FORK
package net.sourceforge.ganttproject.timetracking

import junit.framework.TestCase
import java.time.LocalDate
import java.time.OffsetDateTime

class TimeEntryMatchingTest : TestCase() {

  private fun entry(
    id: Long = 1,
    description: String = "",
    hours: Double = 2.0,
    projectId: Long? = null,
    tags: List<String> = emptyList(),
    date: String = "2026-08-17"
  ) = TogglTimeEntry(
    id = id,
    start = OffsetDateTime.parse("${date}T09:00:00+02:00"),
    durationSeconds = (hours * 3600).toLong(),
    description = description,
    projectId = projectId,
    projectName = null,
    tags = tags)

  private val vorgaenge = listOf(
    MatchableTask(332, "Firmware Prototype board: Sensorik, Energiemanagement, Provisionierung",
      LocalDate.parse("2026-08-01"), LocalDate.parse("2026-09-30")),
    MatchableTask(333, "Begleit-App Prototype board: Einrichtung, Anzeige, Benachrichtigungen",
      LocalDate.parse("2026-10-01"), LocalDate.parse("2026-11-30")),
    MatchableTask(101, "Office paperwork: business registration",
      LocalDate.parse("2026-08-10"), LocalDate.parse("2026-08-25")))

  // --- task number in the text ---

  fun testTaskNumberInTheText() {
    assertEquals(332, explicitTaskNumber("#332 Firmware Sensorik"))
  }

  fun testTaskNumberInATag() {
    assertEquals(101, explicitTaskNumber("Behoerdengang", listOf("#101")))
  }

  /** A bare number must NOT count: "8 Stunden Doku" is not task 8. */
  fun testBareNumberIsNotATaskNumber() {
    assertNull(explicitTaskNumber("8 Stunden Doku geschrieben"))
  }

  fun testNoNumberAtAll() {
    assertNull(explicitTaskNumber("Irgendwas ohne Nummer"))
  }

  fun testExplicitNumberWinsWithoutAskingTheUser() {
    val s = suggestTasks(entry(description = "#332 an der Firmware"), vorgaenge)
    assertEquals(1, s.size)
    assertEquals(332, s[0].task.id)
    assertTrue(s[0].isCertain)
  }

  /** A number naming no task must not silently pick something else — but must still suggest. */
  fun testUnknownNumberFallsBackToSuggestions() {
    val s = suggestTasks(entry(description = "#999 Office paperwork"), vorgaenge)
    assertTrue(s.none { it.isCertain })
    assertTrue("the ordinary suggestions must still work", s.isNotEmpty())
  }

  // --- learned assignment ---

  fun testLearnedKeyIsSuggestedFirst() {
    val gelernt = vorgaenge.map {
      if (it.id == 333) it.copy(learnedKeys = listOf("Auto Programm Logik integriert")) else it
    }
    val s = suggestTasks(entry(description = "Auto Programm Logik integriert"), gelernt)
    assertEquals(333, s[0].task.id)
    assertTrue(s[0].reasons.contains(MatchReason.LEARNED))
  }

  /** Learned keys must survive different spelling — otherwise learning is useless. */
  fun testLearnedKeyIgnoresCaseAndPunctuation() {
    val gelernt = vorgaenge.map {
      if (it.id == 333) it.copy(learnedKeys = listOf("Auto-Programm, Logik integriert")) else it
    }
    val s = suggestTasks(entry(description = "auto programm logik integriert"), gelernt)
    assertTrue(s.first().reasons.contains(MatchReason.LEARNED))
  }

  // --- text similarity ---

  fun testSimilarTextIsSuggested() {
    val s = suggestTasks(entry(description = "Office paperwork prepared"), vorgaenge)
    assertEquals(101, s[0].task.id)
  }

  /** Short words must not create matches: otherwise everything matches everything. */
  fun testShortWordsDoNotMatch() {
    assertEquals(0.0, textSimilarity("und der die das", "und der die das"), 0.001)
  }

  fun testUnrelatedTextGivesNoSimilarity() {
    assertEquals(0.0, textSimilarity("Steuerberater angerufen", "Firmware Sensorik"), 0.001)
  }

  // --- plausibility in time ---

  fun testDateInsideTheTaskWindowCounts() {
    val s = suggestTasks(entry(description = "irgendwas", date = "2026-08-17"), vorgaenge)
    assertTrue(s.any { it.reasons.contains(MatchReason.PLAUSIBLE_DATE) })
  }

  fun testDateOutsideEveryWindowGivesNothing() {
    val s = suggestTasks(entry(description = "voellig anderes", date = "2027-05-05"), vorgaenge)
    assertTrue(s.isEmpty())
  }

  // --- project link ---

  fun testProjectLinkRanksItsGroupHigher() {
    val s = suggestTasks(
      entry(description = "irgendwas", projectId = 42, date = "2026-08-17"),
      vorgaenge,
      groupOfTogglProject = { if (it == 42L) "3" else null },
      groupOfTask = { if (it == 332 || it == 333) "3" else "1" })
    assertTrue(s[0].reasons.contains(MatchReason.PROJECT_LINK))
  }

  // --- splitting ---

  fun testSplitThatAddsUpIsAccepted() {
    val r = validateSplit(4.0, listOf(SplitPart(332, 2.5), SplitPart(333, 1.5)))
    assertTrue(r is SplitResult.Ok)
  }

  fun testSplitThatDoesNotAddUpIsRejected() {
    val r = validateSplit(4.0, listOf(SplitPart(332, 2.0), SplitPart(333, 1.0)))
    assertTrue(r is SplitResult.Invalid)
    // The KEY, not the wording: a reworded message must not break this test, a renamed key must.
    assertEquals(SPLIT_ERROR_SUM_MISMATCH, (r as SplitResult.Invalid).reasonKey)
    // Both figures have to reach the message, otherwise it cannot say what is wrong.
    assertEquals(listOf(3.0, 4.0), r.args)
  }

  fun testSplitRejectsZeroAndNegativeParts() {
    assertTrue(validateSplit(4.0, listOf(SplitPart(332, 4.0), SplitPart(333, 0.0)))
      is SplitResult.Invalid)
    assertTrue(validateSplit(4.0, listOf(SplitPart(332, 5.0), SplitPart(333, -1.0)))
      is SplitResult.Invalid)
  }

  fun testSplitRejectsTheSameTaskTwice() {
    val r = validateSplit(4.0, listOf(SplitPart(332, 2.0), SplitPart(332, 2.0)))
    assertTrue(r is SplitResult.Invalid)
    assertEquals(SPLIT_ERROR_DUPLICATE_TASK, (r as SplitResult.Invalid).reasonKey)
  }

  fun testSplitToleratesRounding() {
    assertTrue(validateSplit(1.0, listOf(SplitPart(1, 0.333), SplitPart(2, 0.333),
      SplitPart(3, 0.334))) is SplitResult.Ok)
  }

  // --- double import: the most dangerous bug of this feature ---

  fun testNewEntryIsImported() {
    val d = planImport(listOf(entry(id = 7, hours = 3.0)), emptyMap())
    assertTrue(d[0].isNew)
    assertEquals(3.0, d[0].hoursDelta(), 0.001)
  }

  /** THE test: importing the same data twice must not add hours a second time. */
  fun testSecondImportAddsNothing() {
    val e = entry(id = 7, hours = 3.0)
    val ersterLauf = planImport(listOf(e), emptyMap())
    val gespeichert = ersterLauf.associate { it.entry.id to it.entry.hours }
    val zweiterLauf = planImport(listOf(e), gespeichert)
    assertEquals(DuplicateHandling.SKIP, zweiterLauf[0].handling)
    assertEquals("a second import must add nothing", 0.0, zweiterLauf[0].hoursDelta(), 0.001)
  }

  /** A changed entry replaces its earlier hours instead of adding to them. */
  fun testChangedEntryIsUpdatedNotAdded() {
    val d = planImport(listOf(entry(id = 7, hours = 5.0)), mapOf(7L to 3.0))
    assertEquals(DuplicateHandling.UPDATE, d[0].handling)
    assertEquals("only the difference may be added", 2.0, d[0].hoursDelta(), 0.001)
  }

  fun testShortenedEntryGivesANegativeDelta() {
    val d = planImport(listOf(entry(id = 7, hours = 1.0)), mapOf(7L to 3.0))
    assertEquals(-2.0, d[0].hoursDelta(), 0.001)
  }
}
