/*
Copyright 2026

NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.
Der Umschalter zwischen Termin- und Aufwandsansicht in der Werkzeugleiste des Diagramms.

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

import net.sourceforge.ganttproject.action.GPAction
import net.sourceforge.ganttproject.gui.UIFacade
import java.awt.event.ActionEvent

/**
 * [Fork-Aenderung] Schaltet das Band unter dem Vorgangsbalken zwischen den beiden Vergleichen um.
 * Siehe [ChartComparison] fuer die Begruendung, warum es zwei sind.
 *
 * WARUM EIN EIGENER KNOPF UND KEINE EINSTELLUNG IM BASISPLAN-DIALOG: die Aufwandsansicht braucht
 * gar keinen Basisplan. Sie in einem Dialog zu verstecken, den man nur wegen der Basisplaene
 * oeffnet, waere die falsche Stelle.
 *
 * Die Beschriftung nennt IMMER die gerade gezeigte Ansicht, nicht die, zu der der Knopf fuehrt.
 * Ein Knopf, der "Aufwand" heisst, waehrend Termine zu sehen sind, ist genau die Sorte
 * Zweideutigkeit, die hier schon einmal Zeit gekostet hat.
 */
class ChartComparisonAction(private val uiFacade: UIFacade) : GPAction("chart.comparison") {
  /**
   * Der Zustand liegt am Diagramm, nicht hier. Dieses Feld ist nur die Kopie fuer die
   * Beschriftung -- ein `Boolean` und keine Aufzaehlung, weil `GPAction` schon im Konstruktor
   * der Oberklasse [getLocalizedName] aufruft, also BEVOR die Felder dieser Klasse gesetzt sind.
   * Ein primitiver Wahrheitswert ist an dieser Stelle `false` und nicht `null`.
   */
  private var zeigtAufwand = false

  override fun getLocalizedName(): String =
    if (zeigtAufwand) forkText("fork.comparison.aufwand") else forkText("fork.comparison.termin")

  override fun actionPerformed(event: ActionEvent?) {
    val chart = uiFacade.ganttChart
    val neu = when (chart.comparison) {
      ChartComparison.AUFWAND -> ChartComparison.TERMIN
      else -> ChartComparison.AUFWAND
    }
    chart.comparison = neu
    zeigtAufwand = neu == ChartComparison.AUFWAND
    // Die Beschriftung haengt am beobachtbaren Namen der Aktion; ohne diesen Aufruf bleibt am
    // Knopf die Aufschrift der vorherigen Ansicht stehen.
    updateAction()
    uiFacade.refresh()
  }
}
