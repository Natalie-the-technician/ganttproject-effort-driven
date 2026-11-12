/*
Copyright 2026 BarD Software s.r.o

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
package net.sourceforge.ganttproject.importer

import biz.ganttproject.app.OPTION_PANE_STYLESHEET
import biz.ganttproject.app.OptionElementData
import biz.ganttproject.app.OptionPaneBuilder
import biz.ganttproject.app.RootLocalizer
import biz.ganttproject.app.dialog
import javafx.application.Platform
import javafx.event.ActionEvent
import javafx.scene.control.ButtonType
import java.util.Optional
import java.util.concurrent.SynchronousQueue

/**
 * Shows one dialog which lists all [collidingNames] of an import and asks what to do with them.
 * Calls [handleAnswer] exactly once: with the chosen value if OK was clicked, or with null if the
 * dialog was closed in any other way (Escape, the window close button).
 *
 * OptionPaneBuilder.showDialog is not used because it calls its handler only on OK.
 */
fun showBaselineNameCollisionDialog(collidingNames: List<String>, handleAnswer: (BaselineNameCollision?) -> Unit) {
  var answered = false
  val optionPane = OptionPaneBuilder<BaselineNameCollision>().apply {
    i18n = RootLocalizer.createWithRootKey(rootKey = "importer.baselineNameCollision")
    titleHelpString?.update(collidingNames.joinToString(", "))
    elements = listOf(
      OptionElementData("rename", BaselineNameCollision.RENAME, true),
      OptionElementData("replace", BaselineNameCollision.REPLACE),
      OptionElementData("keepBoth", BaselineNameCollision.KEEP_BOTH)
    )
  }
  dialog(title = optionPane.titleString.value) { controller ->
    controller.setHeader(optionPane.createHeader())
    controller.setContent(optionPane.buildPane().apply { styleClass.add("option-pane") })
    controller.addStyleSheet(OPTION_PANE_STYLESHEET)
    controller.addStyleClass("option-pane-padding")
    controller.setEscCloseEnabled(true)
    controller.setupButton(ButtonType.OK) { btn ->
      btn.styleClass.add("btn-attention")
      btn.addEventHandler(ActionEvent.ACTION) {
        answered = true
        handleAnswer(optionPane.toggleGroup.selectedToggle?.userData as? BaselineNameCollision)
      }
    }
    // onClosed is also called after OK
    controller.onClosed = {
      if (!answered) handleAnswer(null)
    }
  }
}

/**
 * Shows the dialog and waits until it is answered or closed. Returns null if it was closed.
 * Must not be called on the JavaFX application thread, because the dialog is shown there.
 */
fun askBaselineNameCollision(collidingNames: List<String>): BaselineNameCollision? {
  check(!Platform.isFxApplicationThread()) { "This would block the JavaFX application thread" }
  val queue = SynchronousQueue<Optional<BaselineNameCollision>>()
  showBaselineNameCollisionDialog(collidingNames) { queue.put(Optional.ofNullable(it)) }
  return queue.take().orElse(null)
}
