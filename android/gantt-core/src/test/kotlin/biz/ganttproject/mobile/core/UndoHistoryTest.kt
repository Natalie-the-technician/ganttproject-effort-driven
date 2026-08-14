/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Undo is the one feature where a bug destroys work rather than merely
 * annoying: a wrong step back silently replaces the document with something
 * the user never had. So the ordering, the branch-cutting and the limits are
 * all pinned down, not just the happy path.
 */
class UndoHistoryTest {

  private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)
  private fun text(b: ByteArray?) = b?.toString(Charsets.UTF_8)

  private fun sample(): String =
    checkNotNull(javaClass.getResourceAsStream("/HouseBuildingSample.gan")) {
      "sample project missing from the test classpath"
    }.readBytes().toString(Charsets.UTF_8)

  // ------------------------------------------------------------- Basic order

  @Test
  fun `a fresh history can do neither`() {
    val history = UndoHistory()
    assertFalse(history.canUndo)
    assertFalse(history.canRedo)
    assertNull(history.undo(bytes("now")))
    assertNull(history.redo(bytes("now")))
  }

  @Test
  fun `undo walks back in reverse order`() {
    val history = UndoHistory()
    history.record(bytes("v0"))
    history.record(bytes("v1"))
    history.record(bytes("v2"))
    // Current state is v3; stepping back must yield v2, then v1, then v0.
    assertEquals("v2", text(history.undo(bytes("v3"))))
    assertEquals("v1", text(history.undo(bytes("v2"))))
    assertEquals("v0", text(history.undo(bytes("v1"))))
    assertNull(history.undo(bytes("v0")))
  }

  @Test
  fun `redo walks forward again`() {
    val history = UndoHistory()
    history.record(bytes("v0"))
    history.record(bytes("v1"))

    assertEquals("v1", text(history.undo(bytes("v2"))))
    assertEquals("v0", text(history.undo(bytes("v1"))))
    assertTrue(history.canRedo)
    assertEquals("v1", text(history.redo(bytes("v0"))))
    assertEquals("v2", text(history.redo(bytes("v1"))))
    assertFalse(history.canRedo)
  }

  @Test
  @DisplayName("undo, redo, undo again all step to the same place")
  fun `redo restores the ability to undo`() {
    // Found by deliberately breaking redo so it did not push onto the undo
    // stack: every test above still passed, because none of them undid
    // anything *after* a redo. Without this the button goes dead mid-session.
    val history = UndoHistory()
    history.record(bytes("v0"))

    assertEquals("v0", text(history.undo(bytes("v1"))))
    assertEquals("v1", text(history.redo(bytes("v0"))))
    assertTrue(history.canUndo, "after redoing, stepping back must be possible again")
    assertEquals("v0", text(history.undo(bytes("v1"))))
  }

  @Test
  @DisplayName("undo then redo returns exactly where you started")
  fun `a full round trip is a no-op`() {
    val history = UndoHistory()
    history.record(bytes("before"))
    val restored = history.undo(bytes("after"))
    assertEquals("before", text(restored))
    assertEquals("after", text(history.redo(bytes("before"))))
  }

  // ------------------------------------------------------------ Branch cutting

  @Test
  @DisplayName("editing after an undo drops the redo branch")
  fun `a new edit clears redo`() {
    val history = UndoHistory()
    history.record(bytes("v0"))
    history.undo(bytes("v1"))
    assertTrue(history.canRedo)

    // The user now edits from v0, so v1 is on a branch nothing leads to.
    history.record(bytes("v0"))
    assertFalse(history.canRedo, "redo must not survive a new edit")
    assertNull(history.redo(bytes("v0-edited")))
  }

  @Test
  fun `the dropped branch cannot come back through a later undo`() {
    val history = UndoHistory()
    history.record(bytes("v0"))
    history.undo(bytes("v1"))
    history.record(bytes("v0"))
    // Stepping back from the new branch reaches v0, never the abandoned v1.
    assertEquals("v0", text(history.undo(bytes("v0-edited"))))
    assertNull(history.undo(bytes("v0")))
  }

  // -------------------------------------------------------------------- Limits

  @Test
  fun `the oldest states fall off once the depth is reached`() {
    val history = UndoHistory(depth = 3)
    repeat(6) { history.record(bytes("v$it")) }
    assertEquals(3, history.undoDepth)
    // v0..v2 are gone; the reachable ones are v5, v4, v3.
    assertEquals("v5", text(history.undo(bytes("v6"))))
    assertEquals("v4", text(history.undo(bytes("v5"))))
    assertEquals("v3", text(history.undo(bytes("v4"))))
    assertNull(history.undo(bytes("v3")))
  }

  @Test
  fun `the byte budget also drops the oldest states`() {
    val history = UndoHistory(depth = 100, byteBudget = 30)
    repeat(10) { history.record(ByteArray(10)) }
    assertTrue(history.undoDepth <= 3, "held ${history.undoDepth} states over budget")
    assertTrue(history.undoDepth >= 1)
  }

  @Test
  @DisplayName("a project bigger than the whole budget still gets one step back")
  fun `the budget never removes the last state`() {
    // Otherwise the one case where undo matters most — a huge project — is
    // the one case where the button does nothing.
    val history = UndoHistory(depth = 10, byteBudget = 8)
    history.record(ByteArray(1000))
    assertTrue(history.canUndo)
    assertEquals(1, history.undoDepth)
    assertNotNull(history.undo(ByteArray(1000)))
  }

  @Test
  fun `clear forgets both directions`() {
    val history = UndoHistory()
    history.record(bytes("v0"))
    history.undo(bytes("v1"))
    history.clear()
    assertFalse(history.canUndo)
    assertFalse(history.canRedo)
  }

  // ------------------------------------------------------- Restoring a document

  @Test
  fun `restoring a snapshot brings back the old content`() {
    val document = GanttDocument.parse(sample())
    val before = document.toXmlBytes()
    val taskId = document.read().flatTasks.first { it.isCompletionEditable }.id
    val originalCompletion = document.read().flatTasks.first { it.id == taskId }.completion

    document.setTaskCompletion(taskId, 42)
    assertEquals(42, document.read().flatTasks.first { it.id == taskId }.completion)

    val restored = restoreWithViewState(before, emptyMap())
    assertEquals(
      originalCompletion,
      restored.read().flatTasks.first { it.id == taskId }.completion
    )
  }

  @Test
  @DisplayName("undoing an edit does not fold or unfold anything")
  fun `the fold state of the moment is kept`() {
    val document = GanttDocument.parse(sample())
    val group = document.read().flatTasks.first { !it.isLeaf }.id

    // Snapshot taken while the group was open.
    val before = document.toXmlBytes()

    // The user folds it up — view state, deliberately not part of an edit —
    // and only then makes a change worth undoing.
    document.setTaskExpanded(group, false)
    val foldState = document.read().expandedStates()
    assertFalse(foldState.getValue(group))

    val restored = restoreWithViewState(before, foldState)
    assertFalse(
      restored.read().flatTasks.first { it.id == group }.isExpanded,
      "the group must stay folded across an undo"
    )
  }

  @Test
  fun `a fold state naming tasks that are not there is harmless`() {
    val document = GanttDocument.parse(sample())
    val restored = restoreWithViewState(
      document.toXmlBytes(),
      mapOf("no-such-task" to false, "another-ghost" to true)
    )
    assertEquals(document.read().flatTasks.size, restored.read().flatTasks.size)
  }

  @Test
  @DisplayName("a restored document is still byte-identical to the snapshot")
  fun `restoring does not rewrite the file`() {
    // The whole promise of the app is that it does not disturb parts of the
    // file it does not touch. Undo must not become the exception.
    val document = GanttDocument.parse(sample())
    val before = document.toXmlBytes()
    val restored = restoreWithViewState(before, emptyMap())
    assertEquals(
      before.toString(Charsets.UTF_8),
      restored.toXmlBytes().toString(Charsets.UTF_8)
    )
  }
}
