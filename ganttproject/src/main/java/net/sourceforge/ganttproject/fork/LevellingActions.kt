/*
Copyright 2026 Noctuvo

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
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.storage.ProjectDatabase
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.undo.GPUndoManager
import java.awt.event.ActionEvent
import java.time.LocalDate
import java.time.ZoneId

/**
 * Die beiden Menuepunkte der Kapazitaetsverteilung.
 *
 * BEIDE FRAGEN VORHER. Das ist keine Hoeflichkeit: die Verteilung kann 167 von 226 Vorgaengen
 * verschieben, das Befuellen 226 anfassen. Beides ungefragt auszufuehren waere genau die stille
 * Aenderung, die dieser Fork an mehreren Stellen bereits gefunden hat. Und beides ist EIN
 * Rueckgaengig-Schritt.
 *
 * Die Vorschau nennt auch, was NICHT passiert und warum -- uebersprungene Vorgaenge mit Grund,
 * Konflikte mit Datum. Eine Vorschau, die nur die Erfolgszahl zeigt, verschweigt das Wesentliche.
 */

/**
 * Was der Aufrufer anzeigen soll: Text, und ein Rueckruf mit der Antwort.
 *
 * `fun interface` statt `typealias`, weil die Verdrahtung in `GanttProject.java` liegt: ein
 * Kotlin-typealias auf einen Funktionstyp ist aus Java nicht ansprechbar.
 *
 * Der Rueckruf statt eines Rueckgabewerts ist ebenfalls kein Zierrat: `showOptionDialog` blockiert
 * nicht, die Antwort kommt also spaeter.
 */
fun interface AskBeforeWriting {
  fun ask(message: String, answer: (Boolean) -> Unit)
}

/** Aufwand aus der Dauer ableiten und alles einer Person zuordnen. */
class BackfillAction(
  private val taskManager: TaskManager,
  private val resourceManager: HumanResourceManager,
  private val taskProperties: CustomPropertyManager,
  private val resourceProperties: CustomPropertyManager,
  private val projectDatabase: ProjectDatabase,
  private val undoManager: GPUndoManager,
  private val report: (Boolean, String) -> Unit,
  private val ask: AskBeforeWriting
) : GPAction("levelling.backfill") {

  // Die Beschriftung kommt aus dem Buendel dieses Forks: GPAction kennt nur die Schluessel des
  // Originals und wuerde sonst den nackten Schluessel anzeigen.
  override fun getLocalizedName(): String = forkText("fork.levelling.backfill")

  override fun actionPerformed(event: ActionEvent?) {
    val resources = resourceManager.resources
    if (resources.isEmpty()) {
      report(true, forkText("fork.levelling.noResource"))
      return
    }
    // Bei genau einer Person ist die Zuordnung eindeutig. Bei mehreren wird nicht geraten.
    if (resources.size > 1) {
      report(true, forkText("fork.levelling.manyResources", resources.size))
      return
    }
    val resource = resources[0]
    val hoursPerDay = resource.dailyHours(resourceProperties)
    val proposal = proposeBackfill(collectBackfillTasks(taskManager, taskProperties), hoursPerDay)

    if (proposal.changeCount == 0) {
      report(false, forkText("fork.levelling.backfill.nothing"))
      return
    }
    val skipped = proposal.skipped.values.groupingBy { it }.eachCount()
    val message = forkText("fork.levelling.backfill.preview",
      proposal.effortHours.size, proposal.assignTo.size, resource.name, hoursPerDay,
      skipped[BackfillSkip.CONTAINER] ?: 0,
      skipped[BackfillSkip.MILESTONE] ?: 0,
      skipped[BackfillSkip.ALREADY_HAS_EFFORT] ?: 0)
    ask.ask(message) { confirmed ->
      if (!confirmed) {
        return@ask
      }
      val touched = applyBackfillAsSingleEdit(proposal, resource, taskManager, taskProperties,
        projectDatabase, undoManager, forkText("fork.levelling.backfill.undo"))
      report(false, forkText("fork.levelling.backfill.done", touched))
    }
  }
}

/** Die Vorgaenge so verteilen, dass niemand mehr als 100 % gleichzeitig leisten muss. */
class LevellingAction(
  private val taskManager: TaskManager,
  private val taskProperties: CustomPropertyManager,
  private val resourceProperties: CustomPropertyManager,
  private val undoManager: GPUndoManager,
  private val report: (Boolean, String) -> Unit,
  private val ask: AskBeforeWriting
) : GPAction("levelling.run") {

  override fun getLocalizedName(): String = forkText("fork.levelling.run")

  override fun actionPerformed(event: ActionEvent?) {
    val tasks = collectLevelTasks(taskManager, taskProperties, resourceProperties)
    if (tasks.isEmpty()) {
      report(false, forkText("fork.levelling.noTasks"))
      return
    }
    val projectStart = taskManager.projectStart?.toInstant()
      ?.atZone(ZoneId.systemDefault())?.toLocalDate() ?: LocalDate.now()
    val result = levelTasks(tasks, projectStart, workingDayTest(taskManager.calendar))

    val cycles = result.conflicts.filterIsInstance<LevelConflict.Cycle>()
    if (cycles.isNotEmpty()) {
      report(true, forkText("fork.levelling.cycle"))
      return
    }

    val moved = result.starts.count { (id, start) ->
      val task = taskManager.getTask(id.toIntOrNull() ?: return@count false) ?: return@count false
      task.start.time.toInstant().atZone(ZoneId.systemDefault()).toLocalDate() != start
    }
    if (moved == 0 && result.conflicts.isEmpty()) {
      report(false, forkText("fork.levelling.nothing"))
      return
    }

    val unreachable = result.conflicts.filterIsInstance<LevelConflict.FixedDateNotReachable>()
    val overloads = result.conflicts.filterIsInstance<LevelConflict.Overload>()
    val message = StringBuilder(forkText("fork.levelling.preview", moved, tasks.size))
    if (unreachable.isNotEmpty()) {
      message.append("\n\n").append(forkText("fork.levelling.unreachable", unreachable.size))
      unreachable.take(5).forEach {
        val name = taskManager.getTask(it.id.toIntOrNull() ?: 0)?.name ?: it.id
        message.append("\n  • ").append(
          forkText("fork.levelling.unreachable.row", name, it.fixedStart, it.earliestPossible))
      }
      if (unreachable.size > 5) {
        message.append("\n  … ").append(forkText("fork.levelling.more", unreachable.size - 5))
      }
    }
    if (overloads.isNotEmpty()) {
      message.append("\n\n").append(forkText("fork.levelling.overload", overloads.size))
    }
    ask.ask(message.toString()) { confirmed ->
      if (!confirmed) {
        return@ask
      }
      val written = applyLevellingAsSingleEdit(result.starts, taskManager, undoManager,
        forkText("fork.levelling.undo"))
      report(false, forkText("fork.levelling.done", written))
    }
  }
}
