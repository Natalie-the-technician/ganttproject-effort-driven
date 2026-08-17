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

import kotlin.math.ceil

/**
 * Leitet Aufwand aus der vorhandenen Dauer ab und schlaegt Zuordnungen vor.
 *
 * WOZU: Die aufwandsgetriebene Rechnung und die Kapazitaetsverteilung brauchen beides -- Aufwand
 * am Vorgang und eine Zuordnung zu einer Person. Der Plan, an dem dieser Fork entwickelt wurde,
 * hat 226 Vorgaenge, **null** Zuordnungen und keine Aufwandsspalte; das alles von Hand
 * einzutragen ist Tagesarbeit. Diese Ableitung nimmt sie ab.
 *
 * DIE SICHERHEITSEIGENSCHAFT, und sie ist der Grund, warum die Ableitung genau so gewaehlt ist:
 *
 *     Aufwand := Dauer x Stunden pro Tag
 *     die Rueckrechnung ergibt   ceil(Aufwand / Stunden pro Tag) = Dauer
 *
 * Nach dem Befuellen sieht der Plan also **exakt aus wie vorher**. Das Befuellen selbst
 * verschiebt nichts. Was danach verschiebt, ist die Kapazitaetsverteilung -- und die wird
 * getrennt und mit Vorschau ausgeloest. Ein Hilfsmittel, das beim Ausfuellen heimlich Termine
 * aendert, waere genau die Art stiller Aenderung, die dieser Fork an mehreren Stellen schon
 * gefunden hat.
 *
 * Reine Rechnung, keine GanttProject-Typen: so pruefbar ohne laufendes Programm.
 */

/** Ein Vorgang, so wie die Ableitung ihn sieht. */
data class BackfillTask(
  val id: String,
  val name: String,
  /** Dauer in Arbeitstagen. */
  val durationDays: Int,
  /** Hat Kinder: dann leitet GanttProject die Dauer ab, und Aufwand gehoert nicht hierher. */
  val isContainer: Boolean = false,
  /** Meilenstein: Dauer null, es gibt nichts zu leisten. */
  val isMilestone: Boolean = false,
  /** Reine Wartezeit: dauert, kostet aber keine Arbeit. */
  val isWaitOnly: Boolean = false,
  /** Bereits eingetragener Aufwand. Wird nie ueberschrieben. */
  val existingEffortHours: Double? = null,
  /** Anzahl vorhandener Zuordnungen. */
  val assignmentCount: Int = 0
)

/** Warum ein Vorgang uebersprungen wurde. Wird dem Menschen gezeigt, nicht verschluckt. */
enum class BackfillSkip {
  CONTAINER,          // Gruppe: Dauer wird abgeleitet
  MILESTONE,          // Meilenstein: nichts zu leisten
  WAIT_ONLY,          // reine Wartezeit: dauert, ist aber keine Arbeit
  ALREADY_HAS_EFFORT, // schon gepflegt, bleibt unangetastet
  NO_DURATION         // Dauer 0 oder kleiner: es gibt nichts abzuleiten
}

data class BackfillProposal(
  /** Vorgang -> vorgeschlagener Aufwand in Stunden. */
  val effortHours: Map<String, Double>,
  /** Vorgaenge, die eine Zuordnung bekommen sollen. */
  val assignTo: List<String>,
  /** Uebersprungene Vorgaenge mit Grund. */
  val skipped: Map<String, BackfillSkip>
) {
  val changeCount: Int get() = effortHours.size + assignTo.size
}

/**
 * @param hoursPerDay Tagesleistung der Person, die zugeordnet werden soll.
 * @param alreadyAssignedKeepsIts wenn true, bekommen bereits zugeordnete Vorgaenge keine zweite
 * Zuordnung. Das ist der Normalfall: eine zweite Person zu ergaenzen wuerde die verfuegbaren
 * Stunden pro Tag verdoppeln und damit die Dauer halbieren -- eine stille Planaenderung.
 */
fun proposeBackfill(
  tasks: List<BackfillTask>,
  hoursPerDay: Double,
  alreadyAssignedKeepsIts: Boolean = true
): BackfillProposal {
  require(hoursPerDay > 0.0) { "Stunden pro Tag muss groesser als 0 sein, war $hoursPerDay" }

  val effort = mutableMapOf<String, Double>()
  val assign = mutableListOf<String>()
  val skipped = mutableMapOf<String, BackfillSkip>()

  for (task in tasks) {
    val reason = when {
      task.isContainer -> BackfillSkip.CONTAINER
      task.isMilestone -> BackfillSkip.MILESTONE
      task.isWaitOnly -> BackfillSkip.WAIT_ONLY
      task.existingEffortHours != null -> BackfillSkip.ALREADY_HAS_EFFORT
      task.durationDays <= 0 -> BackfillSkip.NO_DURATION
      else -> null
    }
    if (reason != null) {
      skipped[task.id] = reason
    } else {
      effort[task.id] = task.durationDays * hoursPerDay
    }
    // Zuordnen auch dort, wo der Aufwand schon gepflegt ist: ohne Zuordnung kennt die
    // Kapazitaetsverteilung den Vorgang nicht, und der Aufwand allein bewirkt nichts.
    val zuordnen = !task.isContainer && !task.isMilestone && !task.isWaitOnly &&
      (task.assignmentCount == 0 || !alreadyAssignedKeepsIts)
    if (zuordnen) {
      assign.add(task.id)
    }
  }
  return BackfillProposal(effort, assign, skipped)
}

/**
 * Die Dauer, die die aufwandsgetriebene Rechnung aus einem Aufwand ableiten wuerde.
 *
 * Steht hier, damit sich die Sicherheitseigenschaft aus dem Klassenkommentar pruefen laesst,
 * ohne die eigentliche Rechnung mitsamt Projektmodell hochzuziehen. Die Formel ist dieselbe wie
 * in [net.sourceforge.ganttproject.task.algorithm.EffortDrivenDurationAlgorithm].
 */
fun durationFromEffort(effortHours: Double, availableHoursPerDay: Double): Int =
  ceil(effortHours / availableHoursPerDay).toInt().coerceAtLeast(1)
