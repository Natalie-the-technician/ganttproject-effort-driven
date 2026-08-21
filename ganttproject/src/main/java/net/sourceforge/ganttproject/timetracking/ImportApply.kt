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

import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.GPLogger
import net.sourceforge.ganttproject.storage.ProjectDatabase
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import net.sourceforge.ganttproject.task.algorithm.actualEffortHours
import net.sourceforge.ganttproject.task.algorithm.findEffortDefinition
import net.sourceforge.ganttproject.task.algorithm.effortHours

/**
 * Turning a finished assignment ("this entry belongs to that task") into changes on the tasks.
 *
 * Split into two halves on purpose:
 *
 * - [planTaskImport] only COMPUTES. It touches nothing, so the result can be shown as a preview
 *   and thrown away if the user says no. Everything the preview needs to display is in the
 *   returned objects.
 * - [applyTaskImport] WRITES, and nothing else decides anything.
 *
 * What is never touched, per the specification: completion, status, the planned duration and the
 * planned effort. Recorded time is a record, not a plan. Where the recorded hours exceed the plan
 * this is reported ([TaskImportChange.exceedsPlanned]) and left to the user — a message, not a
 * correction.
 */

/** One task's share of an import, computed but not yet written. */
data class TaskImportChange(
  val task: Task,
  /** Hours this run adds. Zero when everything on this task was imported before. */
  val addedHours: Double,
  /** What the recorded hours become. */
  val newActualHours: Double,
  /** What this task records about the Toggl entries it received. */
  val newLedger: Map<Long, Double>,
  /** The planned effort, only so the preview can point out an overrun. Null when none is set. */
  val plannedHours: Double?
) {
  /** True when the recorded hours pass the plan. A hint for the user, never a reason to change it. */
  val exceedsPlanned: Boolean
    get() = plannedHours != null && newActualHours > plannedHours + SPLIT_TOLERANCE_HOURS

  /** Nothing to write: every entry for this task was already imported with the same hours. */
  val isEmpty: Boolean get() = addedHours == 0.0
}

/**
 * Computes what an import would change, without changing anything.
 *
 * [assignments] says which entry goes to which task; that decision comes from the matching dialog
 * and is deliberately not made here. [alreadyImported] is the project-wide view from
 * [projectImportLedger] — project-wide, not per task, so that an entry which landed on a
 * different task last time is still recognised.
 *
 * [Fork-Aenderung] EINE BUCHUNG, EIN VORGANG — und warum das hier abgewiesen statt gerechnet wird:
 *
 * Diese Funktion kennt je Zuordnung nur den Eintrag und den Vorgang, also **keine Anteile**. Würde
 * derselbe Eintrag zweimal auftauchen, bekäme jeder betroffene Vorgang seine **vollen** Stunden:
 * `hoursDelta()` liefert für einen neuen Eintrag `entry.hours`, und `ledgerAfterImport` schreibt
 * ebenfalls die vollen Stunden in die Buchführung jedes Vorgangs. Aus vier Stunden würden acht —
 * in den Vorgängen und in der Buchführung. Genau das, was dieses Feature verhindern soll, und
 * still.
 *
 * Eine Aufteilung liesse sich hier nicht raten: gleichmässig? nach Aufwand? Das ist eine
 * Entscheidung der Bedienung, keine Rechenregel. Deshalb **laut abweisen** statt falsch rechnen.
 *
 * **Für Schritt 6:** `validateSplit(...)` liefert bereits `SplitPart(taskId, hours)`. Wenn die
 * Aufteilung gebaut wird, braucht diese Funktion je Zuordnung **einen eigenen Stundenwert** (etwa
 * `EntryAssignment(entry, task, hours)` statt eines Paars). Dann bekommt jeder Vorgang seinen
 * Anteil, und `mergeLedgers` zählt die Teile wieder zusammen — dafür ist das Zusammenzählen dort
 * gedacht (`ImportLedgerTest.testMergeAddsUpAnEntrySplitOverTwoTasks`). Bis dahin gilt diese
 * Vorbedingung.
 */
fun planTaskImport(
  assignments: List<Pair<TogglTimeEntry, Task>>,
  alreadyImported: Map<Long, Double>,
  taskProperties: CustomPropertyManager
): List<TaskImportChange> {
  val assignedTwice = assignments.groupingBy { it.first.id }.eachCount().filterValues { it > 1 }.keys
  require(assignedTwice.isEmpty()) {
    "A Toggl entry may be assigned once per import; splitting is not supported yet. " +
      "Assigned more than once: $assignedTwice"
  }

  val decisionByEntry = planImport(assignments.map { it.first }, alreadyImported)
    .associateBy { it.entry.id }

  return assignments.groupBy({ it.second }, { it.first }).map { (task, entries) ->
    val decisions = entries.mapNotNull { decisionByEntry[it.id] }
    val added = decisions.sumOf { it.hoursDelta() }
    val currentActual = task.actualEffortHours(taskProperties) ?: 0.0
    TaskImportChange(
      task = task,
      addedHours = added,
      newActualHours = currentActual + added,
      newLedger = ledgerAfterImport(task.importedEntries(taskProperties), decisions),
      plannedHours = task.effortHours(taskProperties))
  }
}

/** What [applyTaskImport] found when it read the values back. */
data class ImportWriteResult(
  val written: List<Task>,
  /** Tasks whose value did NOT arrive. Empty is the good case. */
  val failed: List<Task>
) {
  val isComplete: Boolean get() = failed.isEmpty()
}

/**
 * Writes the computed changes.
 *
 * Three things here are not decoration:
 *
 * - The mirror columns are brought in line ONCE, before anything is written, and by this function
 *   itself. The import runs from its own menu item, so it cannot lean on the sync that the task
 *   properties dialog does. Without it the first write fails with `Column "..." not found`, and
 *   because `MutatorImpl.commit()` only logs database errors the failure would pass unnoticed.
 * - Nothing is run from inside another mutator's commit. A re-entered mutator's `commit()` does
 *   nothing, so values written then are silently dropped. The caller must therefore invoke this
 *   from a menu action, not from an event handler that fires during a commit.
 * - Afterwards every value is read back — but from the TASK, not from the database. That catches
 *   a value that never made it into the model; it does NOT prove that the mirror table received
 *   it. Verified by counter-test: with the column sync deleted, this check still reported
 *   success, because the mutator updates the task in memory whatever the database says. The
 *   mirror is secured by the sync above plus `ImportApplyTest.the write creates its own mirror
 *   columns`, which queries H2 directly.
 *
 * The caller is expected to wrap the call in a single undoable edit, so that one import is undone
 * in one step.
 */
fun applyTaskImport(
  changes: List<TaskImportChange>,
  taskProperties: CustomPropertyManager,
  projectDatabase: ProjectDatabase
): ImportWriteResult {
  val toWrite = changes.filterNot { it.isEmpty }
  if (toWrite.isEmpty()) {
    return ImportWriteResult(emptyList(), emptyList())
  }

  val actualDef = EffortDrivenProperties.findOrCreateTaskActualEffort(taskProperties)
  val ledgerDef = TimeTrackingProperties.findOrCreateImportedEntries(taskProperties)
  // Both definitions exist now; the mirror table needs its columns before the first write.
  projectDatabase.onCustomColumnChange(taskProperties)

  toWrite.forEach { change ->
    val values = change.task.customValues.copyOf()
    values.setValue(actualDef, change.newActualHours)
    values.setValue(ledgerDef, encodeImportLedger(change.newLedger))
    change.task.createMutator().also { it.setCustomProperties(values) }.commit()
  }

  // [Fork-Aenderung] Die Kontrolle liest den ROHWERT, nicht actualEffortHours.
  //
  // Jene Funktion liefert fuer 0.0 bewusst null -- fuer die Anzeige richtig, fuer eine
  // Erfolgskontrolle falsch: ein korrekt geschriebener Nullwert waere hier als Schreibfehler
  // gezaehlt worden. Null ist ein gueltiger Ist-Aufwand, etwa wenn ein Eintrag zurueckgenommen
  // wird.
  val failed = toWrite.filter { change ->
    val stored = change.task.storedActualHours(taskProperties)
    val abweichung = stored == null || kotlin.math.abs(stored - change.newActualHours) > SPLIT_TOLERANCE_HOURS
    // [Fork-Aenderung] Ins Protokoll, und zwar JEDER Fall.
    //
    // Die Meldung am Bildschirm sagt "Einzelheiten stehen im Protokoll" -- bis hierher stand
    // dort nichts, weil dieser Pfad keine einzige Zeile schrieb. Wer einen Fehlschlag sah,
    // konnte weder erkennen, welcher Vorgang betroffen war, noch mit welchen Zahlen. Am
    // 20.08.2026 an einem echten Plan aufgefallen: 0 geschrieben, 2 fehlgeschlagen, Protokoll
    // leer.
    if (abweichung) {
      GPLogger.log(
        "Toggl import: Schreiben fehlgeschlagen fuer Vorgang ${change.task.taskID}" +
        " \"${change.task.name}\" -- erwartet ${change.newActualHours} h," +
        " zurueckgelesen ${stored ?: "nichts"}"
      )
    } else {
      GPLogger.log(
        "Toggl import: Vorgang ${change.task.taskID} geschrieben, ${change.newActualHours} h"
      )
    }
    abweichung
  }.map { it.task }

  return ImportWriteResult(toWrite.map { it.task } - failed.toSet(), failed)
}

/**
 * [Fork-Aenderung] Liest den gespeicherten Ist-Aufwand ROH, ohne die Null-Sonderbehandlung.
 *
 * `actualEffortHours` gibt fuer 0.0 null zurueck, damit die Anzeige einen leeren Wert von einer
 * gemessenen Null unterscheiden kann. Fuer die Kontrolle nach dem Schreiben ist genau das falsch:
 * dort heisst null "der Wert kam nicht an", und eine geschriebene Null waere faelschlich als
 * Schreibfehler gezaehlt worden.
 */
private fun Task.storedActualHours(manager: CustomPropertyManager): Double? {
  val def = manager.findEffortDefinition(EffortDrivenProperties.TASK_EFFORT_ACTUAL_HOURS) ?: return null
  val raw = this.customValues.getValue(def) ?: return null
  return (raw as? Number)?.toDouble() ?: raw.toString().toDoubleOrNull()
}
