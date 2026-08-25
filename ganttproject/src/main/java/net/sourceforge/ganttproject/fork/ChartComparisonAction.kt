/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
The toggle between the dates view and the effort view in the chart toolbar.

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
 * [Fork change] Switches the band underneath a task bar between the two comparisons.
 * See [ChartComparison] for why there are two of them.
 *
 * WHY A BUTTON OF ITS OWN AND NOT A SETTING IN THE BASELINE DIALOG: the effort view needs no
 * baseline at all. Hiding it inside a dialog that one only opens because of baselines would be
 * the wrong place.
 *
 * The label ALWAYS names the view currently shown, never the one the button leads to. A button
 * reading "effort" while dates are on screen is exactly the kind of ambiguity that has already
 * cost time here.
 */
class ChartComparisonAction(private val uiFacade: UIFacade) : GPAction("chart.comparison") {
  /**
   * The state lives on the chart, not here. This field is only the copy used for the label — a
   * `Boolean` rather than an enum, because `GPAction` already calls [getLocalizedName] from its
   * own constructor, that is BEFORE this class's fields are assigned. A primitive boolean is
   * `false` at that point rather than `null`.
   */
  private var showsEffort = false

  override fun getLocalizedName(): String =
    if (showsEffort) forkText("fork.comparison.effort") else forkText("fork.comparison.dates")

  override fun actionPerformed(event: ActionEvent?) {
    val chart = uiFacade.ganttChart
    val next = when (chart.comparison) {
      ChartComparison.EFFORT -> ChartComparison.DATES
      else -> ChartComparison.EFFORT
    }
    chart.comparison = next
    showsEffort = next == ChartComparison.EFFORT
    // The label hangs off the action's observable name; without this call the button keeps the
    // caption of the previous view.
    updateAction()
    uiFacade.refresh()
  }
}
