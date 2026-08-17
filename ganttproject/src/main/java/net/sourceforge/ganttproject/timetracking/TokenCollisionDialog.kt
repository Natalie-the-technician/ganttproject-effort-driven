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
import javafx.scene.layout.VBox
import net.sourceforge.ganttproject.fork.forkText

/**
 * Asks which token survives when two of them claim the same key.
 *
 * The dialog names what is LOST in each case, not just what is done. "Overwrite" and "reject" say
 * nothing about the consequence, and the consequence is that a token is gone — which only shows up
 * much later, as a connection check that suddenly fails.
 *
 * NO TOKEN IS DISPLAYED. The person recognises the situation by the address, not by comparing two
 * secrets on screen, and a token on screen is a token in a screenshot.
 */
val ASK_IN_A_DIALOG = TokenCollisionAsker { collision, apply ->
  dialog(title = forkText("fork.toggl.collision.title"), id = "togglTokenCollision") { dlg ->
    dlg.setContent(VBox(8.0).also { box ->
      box.children.add(Label(forkText("fork.toggl.collision.what", readableKey(collision.key))).also {
        it.isWrapText = true
      })
      box.children.add(Label(forkText("fork.toggl.collision.consequence")).also {
        it.isWrapText = true
      })
      // Was verloren geht, steht HIER und nicht auf den Knoepfen. Die Knoepfe haben feste Breite;
      // eine laengere Beschriftung wird mit "…" abgeschnitten, auch bei maximiertem Fenster --
      // am Bildschirm nachgewiesen. Damit waere ausgerechnet die Folge unlesbar gewesen.
      box.children.add(Label(forkText("fork.toggl.collision.choice")).also {
        it.isWrapText = true
      })
    })

    // Keep the moving token: what was stored under the key until now is lost.
    dlg.setupButton(ButtonType(forkText("fork.toggl.collision.overwrite"))) { button ->
      button.onAction = javafx.event.EventHandler {
        apply(collision.ifOverwritten)
        dlg.hide()
      }
    }

    // Keep what was already there: the moving token is dropped.
    dlg.setupButton(ButtonType(forkText("fork.toggl.collision.discard"))) { button ->
      button.onAction = javafx.event.EventHandler {
        apply(collision.ifDiscarded)
        dlg.hide()
      }
    }

    // Closing without choosing changes nothing — see TokenCollisionAsker.
    dlg.setupButton(ButtonType.CANCEL) { button ->
      button.onAction = javafx.event.EventHandler { dlg.hide() }
    }
  }
}
