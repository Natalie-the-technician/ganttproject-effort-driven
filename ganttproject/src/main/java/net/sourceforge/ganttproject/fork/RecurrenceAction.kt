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
package net.sourceforge.ganttproject.fork

import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.action.GPAction
import net.sourceforge.ganttproject.storage.ProjectDatabase
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.undo.GPUndoManager
import java.awt.event.ActionEvent

/**
 * Der Menuepunkt "Serienvorgaenge anlegen …".
 *
 * Wie die beiden anderen Hilfsmittel dieses Forks: er FRAGT vorher, die Vorschau nennt auch, was
 * NICHT passiert, und alles ist EIN Rueckgaengig-Schritt.
 *
 * BEI EINEM FEHLER IN EINER REGEL WIRD GAR NICHTS ANGELEGT, auch nicht fuer die lesbaren Serien.
 * Das ist Absicht: haette der Lauf die Haelfte angelegt, waere nach dem Beheben des Tippfehlers
 * nicht mehr zu erkennen, was schon existiert -- und ein zweiter Lauf muesste raten.
 */
class RecurrenceAction(
  private val taskManager: TaskManager,
  private val taskProperties: CustomPropertyManager,
  private val projectDatabase: ProjectDatabase,
  private val undoManager: GPUndoManager,
  private val report: (Boolean, String) -> Unit,
  private val ask: AskBeforeWriting
) : GPAction("recurrence.run") {

  override fun getLocalizedName(): String = forkText("fork.recurrence.run")

  override fun actionPerformed(event: ActionEvent?) {
    // Die Spalte anlegen, falls es sie noch nicht gibt: eine Eigenschaft, die man nicht sieht,
    // kann man auch nicht eintragen. Hier und nicht beim Start des Programms -- eine Spalte, die
    // im Konstruktor entsteht, macht jede Datei unladbar, die dieselbe Spalte enthaelt.
    findOrCreateRecurrence(taskProperties)
    projectDatabase.onCustomColumnChange(taskProperties)

    val plan = planRecurrences(taskManager, taskProperties)

    if (plan.hasErrors) {
      val text = StringBuilder()
      plan.errors.forEach { (name, fehler) ->
        text.append(forkText("fork.recurrence.error.title", name)).appendLine()
        fehler.forEach { text.append("  • ").append(it).appendLine() }
      }
      text.appendLine().append(forkText("fork.recurrence.error.consequence"))
      report(true, text.toString())
      return
    }
    if (plan.seriesCount == 0) {
      // Zwei verschiedene Faelle, zwei verschiedene Meldungen: "es steht nichts drin" und "es
      // steht etwas drin, ist aber schon angelegt" fuehlen sich am Bildschirm gleich an und
      // haben voellig verschiedene naechste Schritte.
      val gibtEsRegeln = taskManager.tasks.any { it.recurrenceText(taskProperties) != null }
      report(false, forkText(
        if (gibtEsRegeln) "fork.recurrence.nothingNew" else "fork.recurrence.none"))
      return
    }

    val message = StringBuilder(
      forkText("fork.recurrence.preview", plan.seriesCount, plan.occurrences.size))
    if (plan.truncated.isNotEmpty()) {
      message.appendLine().appendLine().append(forkText(
        "fork.recurrence.truncated", plan.truncated.size, RecurrenceRule.MAX_OCCURRENCES))
      plan.truncated.take(5).forEach { message.appendLine().append("  • ").append(it) }
    }
    ask.ask(message.toString()) { confirmed ->
      if (!confirmed) {
        return@ask
      }
      val angelegt = applyRecurrencesAsSingleEdit(plan, taskManager, taskProperties,
        projectDatabase, undoManager, forkText("fork.recurrence.undo"))
      report(false, forkText("fork.recurrence.done", angelegt))
    }
  }
}
