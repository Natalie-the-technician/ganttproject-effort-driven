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

/**
 * Geschaetzt gegen gebraucht: wie gut die eigenen Schaetzungen sind.
 *
 * NATALIES EIGENE BESCHREIBUNG DES ZWECKS, am 17.08.2026, und sie bestimmt den ganzen Aufbau:
 *
 *   "Nur wenn etwas mehr Aufwand ist als geplant soll man es sehen. Wenn ich zum Beispiel 15 statt
 *    9 Stunden brauche, ist ja egal, über welchen Zeitraum die Stunden verteilt waren."
 *
 * DARAUS FOLGT DREIERLEI:
 *
 * 1. **Verglichen werden STUNDEN, nicht Termine.** Ein Termin-Basisplan zeigt in der Zukunft vor
 *    allem eines: dass sich alles verschoben hat, sobald sich die Tagesleistung aendert. Das ist
 *    keine Abweichung, sondern Neuplanung. Die Abweichung sind die Stunden.
 * 2. **Der Zeitraum ist gleichgueltig.** Ob die 15 Stunden an einem Tag oder ueber drei Wochen
 *    anfielen, aendert an der Schaetzung nichts. Diese Rechnung kennt deshalb kein Datum.
 * 3. **Verglichen wird gegen die URSPRUENGLICHE Schaetzung.** Wer eine Schaetzung nachtraeglich
 *    korrigiert, vergleicht sonst gegen die korrigierte und lernt nichts mehr -- die Abweichung
 *    verschwindet in dem Moment, in dem man sie bemerkt. Dafuer gibt es die Spalte
 *    "Aufwand urspruenglich", die genau einmal gesetzt und danach nie wieder angefasst wird.
 *
 * Reine Rechnung, keine GanttProject-Typen.
 */

/** Ein Vorgang, so wie die Auswertung ihn sieht. */
data class EstimateRow(
  val id: String,
  val name: String,
  /** Die urspruengliche Schaetzung in Stunden. */
  val originalHours: Double,
  /** Tatsaechlich erfasste Stunden. */
  val actualHours: Double,
  /** Fertigstellung in Prozent. Nur abgeschlossene Vorgaenge sagen etwas ueber die Schaetzung. */
  val completionPercent: Int
) {
  val isFinished: Boolean get() = completionPercent >= 100
  /** Wie viel mehr gebraucht wurde. 1,0 heisst: genau getroffen. */
  val factor: Double get() = if (originalHours > 0.0) actualHours / originalHours else 0.0
  val extraHours: Double get() = actualHours - originalHours
}

data class EstimateReport(
  /** Abgeschlossene Vorgaenge mit Schaetzung UND erfassten Stunden -- die Grundlage. */
  val finished: List<EstimateRow>,
  /** Abgeschlossene, die mehr gebraucht haben als geschaetzt, absteigend nach Mehraufwand. */
  val overruns: List<EstimateRow>,
  /** Angefangene, die JETZT SCHON ueber der Schaetzung liegen -- eine Warnung, kein Urteil. */
  val runningOver: List<EstimateRow>,
  /** Summe geschaetzt / Summe gebraucht ueber die abgeschlossenen. */
  val overallFactor: Double,
  /** Noch offener Aufwand laut Plan, in Stunden. */
  val remainingPlannedHours: Double
) {
  val hasBasis: Boolean get() = finished.isNotEmpty()
  /** Der offene Aufwand, hochgerechnet mit dem gemessenen Faktor. */
  val remainingExpectedHours: Double get() = remainingPlannedHours * overallFactor
}

/**
 * @param rows alle Vorgaenge mit urspruenglicher Schaetzung.
 * @param remainingPlannedHours geplanter Aufwand aller noch nicht abgeschlossenen Vorgaenge.
 *
 * DER GESAMTFAKTOR WIRD AUS SUMMEN GEBILDET, nicht als Mittelwert der Einzelfaktoren. Sonst zaehlt
 * ein Vorgang mit einer halben Stunde genauso viel wie einer mit vierzig -- und ein einziger
 * kleiner Ausreisser ("20 Minuten geschaetzt, 2 Stunden gebraucht", Faktor 6) verdreht das ganze
 * Bild. Summen gewichten mit dem, worum es geht: Arbeitszeit.
 */
fun buildEstimateReport(
  rows: List<EstimateRow>,
  remainingPlannedHours: Double
): EstimateReport {
  val fertig = rows.filter { it.isFinished && it.originalHours > 0.0 && it.actualHours > 0.0 }
  val ueber = fertig.filter { it.extraHours > 0.0 }.sortedByDescending { it.extraHours }
  val laufendUeber = rows
    .filter { !it.isFinished && it.originalHours > 0.0 && it.actualHours > it.originalHours }
    .sortedByDescending { it.extraHours }
  val summeGeschaetzt = fertig.sumOf { it.originalHours }
  val summeGebraucht = fertig.sumOf { it.actualHours }
  val faktor = if (summeGeschaetzt > 0.0) summeGebraucht / summeGeschaetzt else 1.0
  return EstimateReport(fertig, ueber, laufendUeber, faktor, remainingPlannedHours)
}
