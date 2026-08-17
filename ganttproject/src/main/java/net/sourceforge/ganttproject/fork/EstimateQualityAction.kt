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
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.task.algorithm.actualEffortHours
import net.sourceforge.ganttproject.task.algorithm.effortHours
import java.awt.event.ActionEvent

/**
 * Der Menuepunkt "Aufwand: geschaetzt gegen gebraucht".
 *
 * Er SCHREIBT NICHTS. Das ist der Unterschied zu den anderen drei Hilfsmitteln und der Grund,
 * warum er nicht vorher fragt: er liest, rechnet und zeigt. Wer eine Zahl daraufhin aendern will,
 * tut das selbst -- eine Auswertung, die auch gleich korrigiert, nimmt einem die Entscheidung ab,
 * um die es hier gerade geht.
 */
class EstimateQualityAction(
  private val taskManager: TaskManager,
  private val taskProperties: CustomPropertyManager,
  private val report: (Boolean, String) -> Unit
) : GPAction("estimate.quality") {

  override fun getLocalizedName(): String = forkText("fork.estimate.run")

  override fun actionPerformed(event: ActionEvent?) {
    val hierarchy = taskManager.taskHierarchy
    // Nur Blaetter: eine Gruppe traegt keinen eigenen Aufwand, sie wuerde doppelt zaehlen.
    val blaetter = taskManager.tasks.filter { hierarchy.getNestedTasks(it).isEmpty() }

    val rows = blaetter.mapNotNull { task ->
      val original = task.originalEffortHours(taskProperties) ?: return@mapNotNull null
      EstimateRow(
        id = task.taskID.toString(),
        name = task.name.orEmpty(),
        originalHours = original,
        actualHours = task.actualEffortHours(taskProperties) ?: 0.0,
        completionPercent = task.completionPercentage)
    }
    val offen = blaetter.filter { it.completionPercentage < 100 }
      .sumOf { it.effortHours(taskProperties) ?: 0.0 }
    val bericht = buildEstimateReport(rows, offen)

    if (!bericht.hasBasis) {
      report(false, forkText("fork.estimate.noBasis"))
      return
    }

    val text = StringBuilder()
    text.append(forkText("fork.estimate.summary",
      bericht.finished.size,
      bericht.finished.sumOf { it.originalHours },
      bericht.finished.sumOf { it.actualHours },
      bericht.overallFactor))
    text.appendLine().appendLine()

    if (bericht.overruns.isEmpty()) {
      text.append(forkText("fork.estimate.noOverrun"))
    } else {
      text.append(forkText("fork.estimate.overruns", bericht.overruns.size))
      bericht.overruns.take(10).forEach {
        text.appendLine().append("  - ").append(forkText("fork.estimate.row",
          it.name, it.originalHours, it.actualHours, it.factor))
      }
      if (bericht.overruns.size > 10) {
        text.appendLine().append("  ... ").append(
          forkText("fork.levelling.more", bericht.overruns.size - 10))
      }
    }

    if (bericht.runningOver.isNotEmpty()) {
      text.appendLine().appendLine()
        .append(forkText("fork.estimate.running", bericht.runningOver.size))
      bericht.runningOver.take(5).forEach {
        text.appendLine().append("  - ").append(forkText("fork.estimate.row",
          it.name, it.originalHours, it.actualHours, it.factor))
      }
    }

    // Die Hochrechnung ist der Punkt der ganzen Auswertung: sie uebertraegt die gemessene
    // Schaetzguete auf das, was noch kommt. Ausdruecklich als Hochrechnung benannt -- es ist
    // keine Messung, sondern der gemessene Faktor auf ungetane Arbeit angewandt.
    if (bericht.remainingPlannedHours > 0.0) {
      text.appendLine().appendLine().append(forkText("fork.estimate.forecast",
        bericht.remainingPlannedHours, bericht.remainingExpectedHours,
        bericht.remainingExpectedHours - bericht.remainingPlannedHours))
    }
    report(false, text.toString())
  }
}
