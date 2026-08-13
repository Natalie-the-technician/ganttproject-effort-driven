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
import java.time.LocalDate
import kotlin.math.abs

class TimeEntryMatchingTest {

  private fun entry(
    id: Long = 1L,
    description: String,
    hours: Double = 1.0,
    projectName: String? = null
  ) = TogglTimeEntry(
    id = id,
    description = description,
    start = LocalDate.of(2026, 3, 2),
    durationSeconds = (hours * 3600).toLong(),
    projectId = null,
    projectName = projectName,
    tags = emptyList()
  )

  private val tasks = listOf(
    TaskCandidate("9", "Create draft of architecture"),
    TaskCandidate("10", "Prepare construction documents"),
    TaskCandidate("13", "Furniture selection"),
    TaskCandidate("15", "Furniture"),
    TaskCandidate("7", "Construction phase", isLeaf = false)
  )

  // -------------------------------------------------------------- Matching

  @Test
  fun `an explicit task reference is a certain match`() {
    val outcome = Matching.suggestTasks(entry(description = "Working on #13 all morning"), tasks)
    assertEquals("13", outcome.best?.taskId)
    assertEquals(MatchReason.TASK_ID_REFERENCE, outcome.best?.reason)
    assertTrue(outcome.isCertain)
  }

  @Test
  fun `bracket and Nr notations are recognised too`() {
    assertEquals("13", Matching.suggestTasks(entry(description = "[13] furniture"), tasks).best?.taskId)
    assertEquals("10", Matching.suggestTasks(entry(description = "Nr. 10 documents"), tasks).best?.taskId)
  }

  @Test
  @DisplayName("a bare number is NOT treated as a task number")
  fun `a bare number is not a task reference`() {
    // This is the rule that keeps the feature from becoming a silent
    // mis-booking machine. "Meeting 13" must not book onto task 13.
    val outcome = Matching.suggestTasks(entry(description = "Meeting 13"), tasks)
    assertTrue(
      outcome.suggestions.none { it.reason == MatchReason.TASK_ID_REFERENCE },
      "a bare number was accepted as a task number: ${outcome.suggestions}"
    )
    assertFalse(outcome.isCertain)
  }

  @Test
  fun `a year in the description is not a task reference`() {
    val outcome = Matching.suggestTasks(entry(description = "Invoice 2024 preparation"), tasks)
    assertTrue(outcome.suggestions.none { it.reason == MatchReason.TASK_ID_REFERENCE })
  }

  @Test
  fun `a learned key produces a certain match`() {
    val candidates = listOf(
      TaskCandidate("13", "Furniture selection", matchKeys = listOf("moebelhaus termin"))
    )
    val outcome = Matching.suggestTasks(entry(description = "Möbelhaus Termin"), candidates)
    assertEquals("13", outcome.best?.taskId)
    assertEquals(MatchReason.LEARNED_KEY, outcome.best?.reason)
    assertTrue(outcome.isCertain)
  }

  @Test
  fun `an identical name matches exactly`() {
    val outcome = Matching.suggestTasks(entry(description = "Furniture selection"), tasks)
    assertEquals("13", outcome.best?.taskId)
    assertEquals(MatchReason.EXACT_NAME, outcome.best?.reason)
  }

  @Test
  fun `summary tasks are never suggested`() {
    // Booking hours on a parent double-counts them against its children.
    val outcome = Matching.suggestTasks(entry(description = "Construction phase"), tasks)
    assertTrue(
      outcome.suggestions.none { it.taskId == "7" },
      "a summary task was suggested: ${outcome.suggestions}"
    )
  }

  @Test
  fun `word overlap alone is never certain`() {
    val outcome = Matching.suggestTasks(entry(description = "some construction work today"), tasks)
    assertFalse(outcome.isCertain, "weak evidence must always ask the user")
    assertTrue(outcome.suggestions.all { it.score < Matching.CERTAIN_THRESHOLD })
  }

  @Test
  fun `two equally strong candidates are not certain`() {
    // "Furniture" is contained in both task names, so neither wins clearly.
    val outcome = Matching.suggestTasks(entry(description = "Furniture"), tasks)
    assertFalse(outcome.isCertain, "an ambiguous match must be confirmed by a human")
  }

  @Test
  fun `an empty description yields nothing certain`() {
    val outcome = Matching.suggestTasks(entry(description = "   "), tasks)
    assertFalse(outcome.isCertain)
    assertNull(outcome.suggestions.firstOrNull { it.score >= Matching.CERTAIN_THRESHOLD })
  }

  @Test
  fun `suggestion order is stable for equal scores`() {
    val ambiguous = listOf(TaskCandidate("b", "Design"), TaskCandidate("a", "Design"))
    repeat(5) {
      assertEquals(
        listOf("a", "b"),
        Matching.suggestTasks(entry(description = "Design"), ambiguous).suggestions.map { it.taskId }
      )
    }
  }

  @Test
  fun `normalisation folds case and umlauts`() {
    assertEquals("moebel fuer buero", Matching.normalize("Möbel für Büro"))
    assertEquals("a b", Matching.normalize("  A---B  "))
  }

  // ------------------------------------------------------------- Splitting

  @Test
  fun `a split adding up exactly is valid`() {
    val result = validateSplit(3.0, listOf(SplitPart("9", 1.0), SplitPart("13", 2.0)))
    assertEquals(SplitValidation.Valid, result)
  }

  @Test
  fun `a split with a wrong sum is rejected`() {
    // Without this check an entry can be booked onto two tasks in full,
    // doubling the hours with nothing visible in the UI.
    val result = validateSplit(3.0, listOf(SplitPart("9", 2.0), SplitPart("13", 2.0)))
    assertTrue(result is SplitValidation.SumMismatch, "was $result")
    result as SplitValidation.SumMismatch
    assertEquals(3.0, result.expectedHours)
    assertEquals(4.0, result.actualHours)
  }

  @Test
  fun `an empty split is rejected`() {
    assertEquals(SplitValidation.NoParts, validateSplit(3.0, emptyList()))
  }

  @Test
  fun `a zero or negative share is rejected`() {
    assertTrue(validateSplit(3.0, listOf(SplitPart("9", 3.0), SplitPart("13", 0.0)))
      is SplitValidation.NonPositivePart)
    assertTrue(validateSplit(3.0, listOf(SplitPart("9", 4.0), SplitPart("13", -1.0)))
      is SplitValidation.NonPositivePart)
  }

  @Test
  fun `the same task twice is rejected`() {
    val result = validateSplit(3.0, listOf(SplitPart("9", 1.0), SplitPart("9", 2.0)))
    assertTrue(result is SplitValidation.DuplicateTask, "was $result")
  }

  @Test
  fun `rounding within half a minute is tolerated`() {
    assertEquals(SplitValidation.Valid, validateSplit(1.0, listOf(SplitPart("9", 0.995))))
  }

  // ---------------------------------------------------------------- Import

  @Test
  fun `a fresh import books the full hours`() {
    val plan = planImport(
      listOf(ImportAssignment(100L, "13", 2.5), ImportAssignment(101L, "9", 1.5)),
      emptyMap()
    )
    assertEquals(4.0, plan.totalHours, 1e-9)
    assertEquals(2.5, plan.hoursPerTask["13"]!!, 1e-9)
    assertEquals(1.5, plan.hoursPerTask["9"]!!, 1e-9)
    assertTrue(plan.skippedEntryIds.isEmpty())
  }

  @Test
  @DisplayName("running the same import twice adds nothing the second time")
  fun `double import is prevented`() {
    val assignments = listOf(ImportAssignment(100L, "13", 2.5))
    val first = planImport(assignments, emptyMap())
    val ledger = first.lines.filter { !it.isSkipped }.associate { it.entryId to it.hoursToAdd }

    val second = planImport(assignments, ledger)
    assertEquals(0.0, second.totalHours, 1e-9, "the second run must add nothing")
    assertTrue(second.isEmpty)
    assertTrue(second.skippedEntryIds.contains(100L))
  }

  @Test
  fun `an entry reassigned to another task is still not booked twice`() {
    // The ledger is keyed by entry id alone. Were it keyed by (entry, task),
    // moving the entry to a different task would let the hours in a second
    // time — the exact hole this design closes.
    val ledger = mapOf(100L to 2.5)
    val plan = planImport(listOf(ImportAssignment(100L, "different-task", 2.5)), ledger)
    assertEquals(0.0, plan.totalHours, 1e-9)
  }

  @Test
  fun `only the growth of an entry is booked`() {
    // The entry ran longer in the tracker since the last import: 2.5 h were
    // booked, it is now 4.0 h, so 1.5 h are missing.
    val plan = planImport(listOf(ImportAssignment(100L, "13", 4.0)), mapOf(100L to 2.5))
    assertEquals(1.5, plan.totalHours, 1e-9)
    assertEquals(2.5, plan.lines.single().hoursAlreadyImported)
  }

  @Test
  fun `a split entry distributes proportionally and sums exactly`() {
    val plan = planImport(
      listOf(ImportAssignment(100L, "9", 1.0), ImportAssignment(100L, "13", 3.0)),
      mapOf(100L to 2.0)
    )
    // 4.0 requested, 2.0 already booked, so 2.0 remain, split 1:3.
    assertEquals(2.0, plan.totalHours, 1e-9)
    assertEquals(0.5, plan.hoursPerTask["9"]!!, 1e-9)
    assertEquals(1.5, plan.hoursPerTask["13"]!!, 1e-9)
    assertTrue(
      abs(plan.hoursPerTask.values.sum() - 2.0) < 1e-9,
      "shares must add up exactly, no rounding residue"
    )
  }

  @Test
  fun `several entries on the same task are summed`() {
    val plan = planImport(
      listOf(ImportAssignment(1L, "13", 1.25), ImportAssignment(2L, "13", 2.75)),
      emptyMap()
    )
    assertEquals(4.0, plan.hoursPerTask["13"]!!, 1e-9)
  }

  @Test
  fun `an already fully imported entry produces a line but adds nothing`() {
    val plan = planImport(listOf(ImportAssignment(100L, "13", 2.0)), mapOf(100L to 2.0))
    assertEquals(1, plan.lines.size, "the line stays visible so the user sees it was skipped")
    assertTrue(plan.lines.single().isSkipped)
    assertTrue(plan.hoursPerTask.isEmpty())
  }
}
