/*
Copyright 2026

NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.

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

import biz.ganttproject.app.dialog
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.VBox
import net.sourceforge.ganttproject.fork.forkText
import java.util.function.Consumer

/**
 * Asks how far back the import should look.
 *
 * WHY A NUMBER OF DAYS AND NOT A TICKBOX for "also older entries": "older" has no end. A tickbox
 * would either mean "everything since the account exists" — one request whose answer Toggl may
 * truncate without saying so, leaving a silently incomplete import — or a hidden number nobody
 * can see. A field shows exactly what will be asked for.
 *
 * WHY IT IS ASKED EVERY TIME rather than hidden in a settings page: the period is the one decision
 * that changes per run — the first import wants months, the daily one wants a week. The value is
 * remembered, so the usual case is pressing Enter.
 *
 * The settings page would also have needed GanttProject's own translation files for its labels,
 * which this fork cannot write to. Here every label comes from the fork bundle.
 */
fun interface ImportPeriodAsker {
  /** Calls [answer] with the chosen number of days. Not calling it means the import is off. */
  fun ask(suggestedDays: Int, answer: Consumer<Int>)
}

val ASK_FOR_THE_PERIOD = ImportPeriodAsker { suggestedDays, answer ->
  dialog(title = forkText("fork.toggl.period.title"), id = "togglImportPeriod") { dlg ->
    val field = TextField(suggestedDays.toString())
    val error = Label("").also { it.isVisible = false; it.isWrapText = true }

    dlg.setContent(VBox(8.0).also { box ->
      box.children.add(Label(forkText("fork.toggl.period.what")).also { it.isWrapText = true })
      box.children.add(field)
      box.children.add(Label(forkText("fork.toggl.period.hint", MAX_IMPORT_DAYS)).also {
        it.isWrapText = true
      })
      box.children.add(error)
    })

    dlg.setupButton(ButtonType.OK) { button ->
      button.onAction = javafx.event.EventHandler {
        val days = parseImportDays(field.text)
        if (days == null) {
          // Deliberately NOT falling back to the default: silently importing a different period
          // than the one on screen is exactly the kind of surprise this dialog exists to avoid.
          error.text = forkText("fork.toggl.period.invalid", MIN_IMPORT_DAYS, MAX_IMPORT_DAYS)
          error.isVisible = true
        } else {
          answer.accept(days)
          dlg.hide()
        }
      }
    }

    dlg.setupButton(ButtonType.CANCEL) { button ->
      button.onAction = javafx.event.EventHandler { dlg.hide() }
    }
  }
}
