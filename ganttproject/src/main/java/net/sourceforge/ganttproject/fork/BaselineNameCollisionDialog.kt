/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.

The one dialog of the "einmal fragen, für alle" decision: it lists every colliding baseline name
of one import together and asks ONE question about all of them, with the three choices Natalie
named on 08.10.2026.

WHY NOT [biz.ganttproject.app.OptionPaneBuilder.showDialog]. That convenience method exists and is
used elsewhere in this codebase for a structurally similar choice
([net.sourceforge.ganttproject.gui.projectopen.DocumentForkDialog]), but it only fires its handler
on OK -- closing the dialog any other way (Escape, the window's close button) calls nothing at
all. For the calendar question of Teil B that is fine, because that question is never "closed",
it sits on a wizard page that stays open until answered. This one is a real popup that CAN be
dismissed without an answer, and "ohne Antwort passiert nichts" has to hold for THAT path too, not
only for the OK path -- so this file builds its own, small dialog with an explicit onClosed hook,
instead of the convenience method that has none.

WHY NOT [biz.ganttproject.app.RootLocalizer.createWithRootKey], which is what
[DocumentForkDialog] uses for its own texts. That localizer reads GanttProject's own translation
bundle, which lives in the `biz.ganttproject.app.localization` git submodule -- a repository this
fork cannot write to (see the comment on [ForkI18n] for the full reason). A key added only here
would have no text there. [forkLocalizerWithRootKey] below is this file's equivalent: it prefixes
the same way but reads the fork's OWN bundle, the one [ForkI18n]/[forkText] already use for the
calendar question's "-- please choose --" label.

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

import biz.ganttproject.app.LocalizedString
import biz.ganttproject.app.Localizer
import biz.ganttproject.app.OptionElementData
import biz.ganttproject.app.OptionPaneBuilder
import biz.ganttproject.app.dialog
import javafx.application.Platform
import javafx.event.ActionEvent
import javafx.scene.control.ButtonType
import java.util.Optional
import java.util.concurrent.SynchronousQueue

/**
 * A [Localizer] that prefixes every key with [rootKey] and reads the fork's own bundle
 * ([ForkLocalizer]), the way [biz.ganttproject.app.DefaultLocalizer.createWithRootKey] prefixes
 * into GanttProject's shared one. See the file comment for why this cannot simply be that method.
 */
private class ForkPrefixedLocalizer(private val rootKey: String) : Localizer {
  override fun create(key: String): LocalizedString = LocalizedString(key, this)
  override fun formatTextOrNull(key: String, vararg args: Any): String? =
    ForkLocalizer.formatTextOrNull("$rootKey.$key", *args)
}

/**
 * Shows the one dialog of an import's baseline-name question and calls [handleAnswer] exactly
 * once: with the chosen [BaselineCollisionAnswer] if the OK button was clicked, or with null for
 * every other way the dialog can end (Escape, the window's close control). Never calls it twice.
 *
 * NOT BLOCKING. This schedules the dialog via [biz.ganttproject.app.dialog], which itself
 * schedules via `Platform.runLater` and returns immediately -- [handleAnswer] runs later, on the
 * JavaFX application thread, when the user is done with the dialog. A caller that needs to wait
 * synchronously for the answer uses [askBaselineNameCollisionBlocking] instead of this function.
 */
fun showBaselineNameCollisionDialog(collidingNames: List<String>, handleAnswer: (BaselineCollisionAnswer?) -> Unit) {
  var answered = false
  val optionPane = OptionPaneBuilder<BaselineCollisionAnswer>().apply {
    i18n = ForkPrefixedLocalizer("fork.import.baselineCollision")
    titleHelpString?.update(collidingNames.joinToString(", "))
    elements = listOf(
      // "keep" IS PRE-SELECTED, "overwrite" IS NOT -- the task is explicit that overwriting must
      // never be a silent default. Pre-selecting SOMETHING is still needed: a radio group with no
      // selection and no later click would otherwise hand the OK handler a null toggle.
      OptionElementData("keep", BaselineCollisionAnswer.KEEP_BOTH, true),
      OptionElementData("rename", BaselineCollisionAnswer.RENAME_IMPORTED),
      OptionElementData("overwrite", BaselineCollisionAnswer.OVERWRITE_EXISTING)
    )
  }
  dialog(title = optionPane.titleString.value) { controller ->
    controller.setHeader(optionPane.createHeader())
    controller.setContent(optionPane.buildPane().apply { styleClass.add("option-pane") })
    controller.addStyleSheet(biz.ganttproject.app.OPTION_PANE_STYLESHEET)
    controller.addStyleClass("option-pane-padding")
    controller.setEscCloseEnabled(true)
    controller.setupButton(ButtonType.OK) { btn ->
      btn.styleClass.add("btn-attention")
      btn.addEventHandler(ActionEvent.ACTION) {
        answered = true
        handleAnswer(optionPane.toggleGroup.selectedToggle?.userData as? BaselineCollisionAnswer)
      }
    }
    // THE SAFETY NET "ohne Antwort passiert nichts" NEEDS: onClosed fires for EVERY way the
    // dialog ends, OK included, which is why `answered` guards it -- otherwise OK would call
    // handleAnswer twice, once with the real answer and once with null right after.
    controller.onClosed = {
      if (!answered) handleAnswer(null)
    }
  }
}

/**
 * Shows the dialog and BLOCKS THE CALLING THREAD until it is answered or closed, returning null
 * in the latter case.
 *
 * MUST NOT BE CALLED ON THE JAVAFX APPLICATION THREAD -- the dialog is shown via `Platform.runLater`,
 * which only runs its queued task once the current one returns; blocking that same thread while
 * waiting for it would deadlock the whole application, not just this dialog. The only caller this
 * fork has, [net.sourceforge.ganttproject.importer.ImporterFromGanttFile], runs on the import
 * wizard's background coroutine, not the FX thread -- see the report of 12.10.2026 for how that
 * was established. A future caller that is on the FX thread must use
 * [showBaselineNameCollisionDialog] directly and continue from its callback instead.
 */
fun askBaselineNameCollisionBlocking(collidingNames: List<String>): BaselineCollisionAnswer? {
  check(!Platform.isFxApplicationThread()) {
    "askBaselineNameCollisionBlocking() must not be called on the JavaFX application thread -- it would deadlock."
  }
  val queue = SynchronousQueue<Optional<BaselineCollisionAnswer>>()
  showBaselineNameCollisionDialog(collidingNames) { answer ->
    queue.put(Optional.ofNullable(answer))
  }
  return queue.take().orElse(null)
}
