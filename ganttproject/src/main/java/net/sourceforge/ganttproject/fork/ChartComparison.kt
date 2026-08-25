/*
Copyright 2026

NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.
Die Vergleichsregeln fuer das Band unter dem Vorgangsbalken.

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

import java.util.Date
import kotlin.math.abs

/**
 * [Fork-Aenderung] Was das Band unter dem Vorgangsbalken vergleicht.
 *
 * WARUM ES ZWEI GIBT, festgelegt am 20.08.2026. In diesem Fork ist die DAUER eines Vorgangs
 * keine Eingabe, sondern ein Rechenergebnis: `EffortDrivenDurationAlgorithm` leitet sie aus dem
 * Aufwand und der Tagesleistung der zugeordneten Person ab. Wer die Tagesleistung aendert,
 * aendert damit jede Dauer im Plan, ohne dass sich an der Arbeit etwas geaendert haette.
 *
 * Gemessen an einem echten Plan am 20.08.2026: Tagesleistung von 8 auf 1,8 Stunden gesetzt, und
 * 163 von 163 Vorgaengen mit Aufwand wurden um den Faktor 8/1,8 = 4,44 laenger. Ein Basisplan von
 * vorher faerbte danach 194 von 276 Zeilen rot — richtig gerechnet und trotzdem ohne Aussage,
 * weil nicht zwei Planstaende verglichen wurden, sondern zwei Kapazitaetsannahmen.
 *
 * Daraus folgen zwei verschiedene Fragen, die vorher in einer Anzeige vermischt waren:
 *
 *  - [TERMIN]  "Liege ich im Zeitplan?"  -> Ende jetzt gegen Ende im Basisplan.
 *  - [AUFWAND] "Habe ich mehr oder weniger Arbeitszeit gebraucht?"
 *              -> erfasste Ist-Stunden gegen die urspruengliche Schaetzung.
 *
 * Der Aufwandsvergleich braucht KEINEN Basisplan. Seine beiden Zahlen stehen schon am Vorgang:
 * [TASK_EFFORT_ORIGINAL] wird genau einmal festgehalten und danach nie wieder angefasst, die
 * Ist-Stunden kommen aus der Zeiterfassung. Das ist der bessere Anker als ein Basisplan — er
 * ueberlebt auch das Anlegen eines zweiten Basisplans.
 */
enum class ChartComparison {
  TERMIN,
  AUFWAND
}

/**
 * Das Ergebnis eines Vergleichs. Bewusst keine Farbe: welche Farbe daraus wird, entscheidet
 * `StyledPainterImpl` anhand der drei einstellbaren Werte in `UIConfiguration`.
 */
enum class Vergleichsbefund {
  /** Es gibt nichts zu zeigen. Es wird gar kein Band gezeichnet. */
  KEIN_BAND,

  /** Es gibt eine Abweichung, aber keine in die eine oder andere Richtung. Neutrale Farbe. */
  NEUTRAL,

  /** Spaeter beziehungsweise mehr gebraucht. Die "later"-Farbe. */
  MEHR,

  /** Frueher beziehungsweise weniger gebraucht. Die "earlier"-Farbe. */
  WENIGER
}

/**
 * [ChartComparison.TERMIN]: das Ende von heute gegen das Ende im Basisplan.
 *
 * Das ist die Regel des Original-GanttProject, und sie ist hier wieder die richtige: die Frage
 * lautet "liege ich im Zeitplan", und darauf antwortet das Enddatum. Die zwischenzeitliche
 * Fassung dieses Forks (Vergleich der Dauern, eingefuehrt am 17.08.2026 in `misc-fixes`) hat
 * versucht, mit derselben Anzeige die Aufwandsfrage mitzubeantworten; das geht nicht, siehe den
 * Klassenkommentar oben. Die Aufwandsfrage hat jetzt ihre eigene Ansicht.
 *
 * Gleiches Ende heisst planmaessig — dann bleibt die Zeile leer.
 */
fun terminVergleich(basisplanEnde: Date, aktuellesEnde: Date): Vergleichsbefund = when {
  basisplanEnde == aktuellesEnde -> Vergleichsbefund.KEIN_BAND
  aktuellesEnde.after(basisplanEnde) -> Vergleichsbefund.MEHR
  else -> Vergleichsbefund.WENIGER
}

/** Unter dieser Stundenzahl gelten zwei Aufwaende als gleich. Eine Minute. */
private const val STUNDEN_TOLERANZ = 1.0 / 60.0

/**
 * [ChartComparison.AUFWAND]: die erfassten Ist-Stunden gegen die urspruengliche Schaetzung.
 *
 * Verglichen wird gegen die URSPRUENGLICHE Schaetzung, nicht gegen die heutige. Der Grund steht
 * schon bei [TASK_EFFORT_ORIGINAL]: wer eine Schaetzung nachbessert und danach gegen die
 * nachgebesserte Zahl vergleicht, sieht nie wieder eine Abweichung — sie verschwindet genau in
 * dem Moment, in dem man sie bemerkt.
 *
 * Die Faelle:
 *  - keine urspruengliche Schaetzung -> es gibt keinen Massstab, also kein Band
 *  - Schaetzung ja, aber nichts erfasst -> NEUTRAL. Das ist eine eigene Aussage
 *    ("hier ist noch keine Zeit gebucht") und ausdruecklich nicht dasselbe wie "passt".
 *  - gleich viele Stunden (auf eine Minute genau) -> kein Band
 */
fun aufwandVergleich(urspruenglicheStunden: Double?, istStunden: Double?): Vergleichsbefund {
  if (urspruenglicheStunden == null || urspruenglicheStunden <= 0.0) {
    return Vergleichsbefund.KEIN_BAND
  }
  if (istStunden == null || istStunden <= 0.0) {
    return Vergleichsbefund.NEUTRAL
  }
  if (abs(istStunden - urspruenglicheStunden) < STUNDEN_TOLERANZ) {
    return Vergleichsbefund.KEIN_BAND
  }
  return if (istStunden > urspruenglicheStunden) Vergleichsbefund.MEHR else Vergleichsbefund.WENIGER
}

/**
 * Der Stil, den der Maler auswertet, oder null fuer die neutrale Farbe.
 * Die Namen sind die des Originals und stehen so in `StyledPainterImpl`.
 */
fun Vergleichsbefund.stilName(): String? = when (this) {
  Vergleichsbefund.MEHR -> "later"
  Vergleichsbefund.WENIGER -> "earlier"
  else -> null
}
