/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

/**
 * Undo and redo, by keeping whole copies of the document.
 *
 * ## Why snapshots and not inverse commands
 *
 * The usual way to build undo is to have every edit record how to reverse
 * itself. That is the right design when a document is large or a command is
 * cheap to invert — and the wrong one here, twice over:
 *
 *  - A `.gan` project is small. The sample that ships with GanttProject is
 *    about 25 kB; a real one is tens of kB. Thirty of those cost less memory
 *    than a single screenshot.
 *  - The edits are heterogeneous — a custom property that may or may not have
 *    existed, an allocation that carries a responsible flag, an import that
 *    touches hours, ledger and learned keys in one go. Every one of those
 *    needs its own inverse, and an inverse that is subtly wrong corrupts the
 *    file instead of failing loudly.
 *
 * A snapshot cannot be subtly wrong. It is the bytes that were there.
 *
 * ## The two limits
 *
 * Depth and total bytes, because either alone is the wrong guard: thirty
 * copies of a huge project would be too much memory, and a byte budget alone
 * would give a tiny project a history hundreds of steps deep that no one can
 * reason about. Whichever bites first drops the oldest states — the ones
 * furthest from what the user still remembers doing.
 */
class UndoHistory(
  private val depth: Int = DEFAULT_DEPTH,
  private val byteBudget: Long = DEFAULT_BYTE_BUDGET
) {
  private val undoStack = ArrayDeque<ByteArray>()
  private val redoStack = ArrayDeque<ByteArray>()

  val canUndo: Boolean get() = undoStack.isNotEmpty()
  val canRedo: Boolean get() = redoStack.isNotEmpty()

  /** How many steps back are available. Exposed for tests and diagnostics. */
  val undoDepth: Int get() = undoStack.size
  val redoDepth: Int get() = redoStack.size

  /**
   * Remembers the state *before* an edit that is about to be applied.
   *
   * Clears the redo stack: once the user changes something after undoing,
   * the branch they undid is unreachable, and keeping a redo button that
   * would jump to a state their current work never passed through is how an
   * undo implementation loses data.
   */
  fun record(before: ByteArray) {
    undoStack.addLast(before)
    redoStack.clear()
    trim()
  }

  /**
   * Steps back one state.
   *
   * @param current the document as it is right now, which becomes the state
   *   redo returns to
   * @return the state to restore, or null if there is nothing to undo
   */
  fun undo(current: ByteArray): ByteArray? {
    val previous = undoStack.removeLastOrNull() ?: return null
    redoStack.addLast(current)
    return previous
  }

  /** The exact inverse of [undo]. */
  fun redo(current: ByteArray): ByteArray? {
    val next = redoStack.removeLastOrNull() ?: return null
    undoStack.addLast(current)
    return next
  }

  /** Forgets everything. Used when a different project is opened. */
  fun clear() {
    undoStack.clear()
    redoStack.clear()
  }

  /**
   * Drops the oldest undo states until both limits hold.
   *
   * Only the undo stack is trimmed. The redo stack can only ever be as deep
   * as the number of undos the user just performed, so it is bounded by the
   * undo stack and needs no separate guard.
   */
  private fun trim() {
    while (undoStack.size > depth) undoStack.removeFirst()
    // Always keep at least one state, whatever the budget says: a single
    // project larger than the whole budget would otherwise mean no undo at
    // all, which is worse than briefly holding one copy of it.
    while (undoStack.size > 1 && undoStack.sumOf { it.size.toLong() } > byteBudget) {
      undoStack.removeFirst()
    }
  }

  companion object {
    /**
     * Far enough back to cover a work session on a phone, short enough that
     * the user can still say what the button will do.
     */
    const val DEFAULT_DEPTH = 30

    /** 8 MB. Roughly 300 copies of a typical project. */
    const val DEFAULT_BYTE_BUDGET = 8L * 1024 * 1024
  }
}

/**
 * Loads [snapshot] but keeps the fold state the user is looking at now.
 *
 * Folding is view state, not content ([GanttDocument.setTaskExpanded] writes
 * the same `expand` attribute the desktop uses, and the app deliberately does
 * not treat it as an edit). Restoring an older snapshot wholesale would
 * therefore make groups pop open or shut as a side effect of undoing a
 * progress change — a jump the user did not ask for and cannot undo again.
 *
 * @param expandedStates fold state to re-apply, as task id to expanded
 */
fun restoreWithViewState(
  snapshot: ByteArray,
  expandedStates: Map<String, Boolean>
): GanttDocument {
  val document = GanttDocument.load(snapshot)
  // Tasks that no longer exist in the snapshot, and leaf tasks that never
  // had the attribute, are simply refused by setTaskExpanded.
  for ((taskId, expanded) in expandedStates) {
    document.setTaskExpanded(taskId, expanded)
  }
  return document
}

/** The current fold state, in the form [restoreWithViewState] expects. */
fun ProjectModel.expandedStates(): Map<String, Boolean> =
  flatTasks.associate { it.id to it.isExpanded }
