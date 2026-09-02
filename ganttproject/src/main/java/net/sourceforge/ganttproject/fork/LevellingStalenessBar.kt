/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
The permanently visible message that says the capacity has to be redistributed.

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
package net.sourceforge.ganttproject.fork

import biz.ganttproject.FXUtil
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.HBox

/**
 * THE MESSAGE, IN THE MIDDLE OF THE STATUS BAR.
 *
 * WHY THERE. Measured on 28.08.2026 (`2026-08-28-verteilung-veraltet-messung.md`, section 3): the
 * middle of the status bar is the ONE permanently visible area of the window that is empty today.
 * It is a plain `Pane` used as a spacer, carrying nothing (`GanttProjectFxApp.kt`). The two
 * alternatives were measured and rejected in the same place: the notification bubbles disappear
 * and were already found unreadable once (`GanttProject.java`, the comment on
 * `EstimateQualityAction`), and the settings dialog is wider than a 1024x768 screen, so its Ok
 * button cannot be reached.
 *
 * WHAT IT LOOKS LIKE WHEN THERE IS NOTHING TO SAY: exactly like today. [node] is both invisible
 * and UNMANAGED when the mark is clear, so it takes no width at all and the status bar keeps the
 * layout it has now. The growing spacer stays where it is, behind the message, so the notification
 * buttons are hard right in both states.
 *
 * THE BUTTON RUNS THE MENU ITEM, it does not repeat it. [runLevelling] is meant to be a call into
 * the very same `LevellingAction` instance that hangs in the resources menu — one path, one set of
 * questions before writing, one undo step. Copying its body here would be a second levelling that
 * would drift from the first.
 *
 * THREADING. The mark is set from task and resource events, which arrive on the Swing event
 * thread; this node lives on the JavaFX thread. [FXUtil.runLater] does the hop and runs inline
 * when it is already on the right thread, so nothing is queued needlessly. The button goes the
 * other way: `LevellingAction` is a Swing action that opens Swing dialogs, so [runLevelling] has
 * to be given something that gets itself onto the Swing thread — this class does not assume it.
 *
 * WHAT IS NOT HERE: the second half of the message, "tasks are not in the baseline". It is not
 * built, and the report of 02.09.2026 says why — it needs a decision that is not a measurement.
 */
class LevellingStalenessBar(
  private val staleness: LevellingStaleness,
  private val runLevelling: () -> Unit
) {
  val label: Label = Label(forkText("fork.staleness.message"))

  val button: Button = Button(forkText("fork.staleness.button")).also {
    it.setOnAction { runLevelling() }
  }

  /**
   * Put this in front of the spacer `Pane`, and leave the spacer's `HBox.hgrow` alone.
   *
   * DELIBERATELY NOT GROWING ITSELF. Two growing children would share the free width between them,
   * so the message would sit in a box wider than its text with the notification buttons still hard
   * right -- readable, but the gap would move with the window. Letting only the spacer grow keeps
   * the message next to the cloud lock, where it is in the same place whatever the window size.
   */
  val node: HBox = HBox(8.0, label, button).also {
    it.alignment = Pos.CENTER_LEFT
    it.padding = Insets(0.0, 8.0, 0.0, 8.0)
  }

  private val onChange: (Boolean) -> Unit = { FXUtil.runLater { zeige(it) } }

  init {
    zeige(staleness.isStale)
    staleness.addListener(onChange)
  }

  fun detach() {
    staleness.removeListener(onChange)
  }

  private fun zeige(sichtbar: Boolean) {
    node.isVisible = sichtbar
    // AND managed, not only visible: an invisible but managed node still occupies its width, and
    // the status bar would silently get a gap in it that nobody could explain.
    node.isManaged = sichtbar
  }
}
