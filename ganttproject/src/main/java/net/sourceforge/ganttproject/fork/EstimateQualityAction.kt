/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.

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
 * The "effort: estimated against needed" menu item.
 *
 * It WRITES NOTHING. That is the difference from the other three tools and the reason why it does
 * not ask beforehand: it reads, computes and shows. Whoever then wants to change a number does so
 * themselves -- an evaluation that also corrects straight away takes away the very decision that
 * is at stake here.
 */
class EstimateQualityAction(
  private val taskManager: TaskManager,
  private val taskProperties: CustomPropertyManager,
  private val report: (Boolean, String) -> Unit
) : GPAction("estimate.quality") {

  override fun getLocalizedName(): String = forkText("fork.estimate.run")

  override fun actionPerformed(event: ActionEvent?) {
    val hierarchy = taskManager.taskHierarchy
    // Leaves only: a group carries no effort of its own, it would count twice.
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

    // The extrapolation is the point of the whole evaluation: it carries the measured estimating
    // quality over to what is still to come. Explicitly named as an extrapolation -- it is not a
    // measurement but the measured factor applied to work not yet done.
    if (bericht.remainingPlannedHours > 0.0) {
      text.appendLine().appendLine().append(forkText("fork.estimate.forecast",
        bericht.remainingPlannedHours, bericht.remainingExpectedHours,
        bericht.remainingExpectedHours - bericht.remainingPlannedHours))
    }
    report(false, text.toString())
  }
}
