/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
The dropdown that selects the comparison view in the chart toolbar.

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
 * [Fork change] Selects the comparison shown by the band underneath a task bar.
 * See [ChartComparison] for why there is more than one.
 *
 * WHY A CONTROL OF ITS OWN AND NOT A SETTING IN THE BASELINE DIALOG: the effort view needs no
 * baseline at all. Hiding it inside a dialog that one only opens because of baselines would be
 * the wrong place.
 *
 * WHY A DROPDOWN AND NOT A BUTTON THAT CYCLES, changed on 25 August 2026. Until then there were
 * two views and one button that swapped between them; its caption always named the view on
 * screen. With three views a cycling button stops working: the caption still says where one IS,
 * but no longer where the next press LEADS, so finding a particular view means pressing until it
 * appears. A dropdown shows all of them at once and still names the current one when closed.
 *
 * THE ENTRIES KEEP THE "Compare:" PREFIX even though it repeats down an open list. Closed — which
 * is how the control spends nearly all of its time — the dropdown shows only the selected entry,
 * and a bare "Dates" next to a "Baselines" button says nothing about what is being compared. The
 * redundancy costs a moment when the list is open; dropping it would cost clarity permanently.
 */
sealed class ChartComparisonAction(
  private val uiFacade: UIFacade,
  actionId: String
) : GPAction(actionId) {

  /**
   * The view this entry selects. Read ONLY from [actionPerformed], never from
   * [getLocalizedName] — `GPAction` calls that one from its own constructor, that is before the
   * subclass has assigned its fields.
   */
  protected abstract val comparison: ChartComparison

  override fun actionPerformed(event: ActionEvent?) {
    val chart = uiFacade.ganttChart
    if (chart.comparison == comparison) {
      return
    }
    chart.comparison = comparison
    uiFacade.refresh()
  }

  companion object {
    /**
     * The entries, in the order they appear in the dropdown. THE ORDER IS FIXED and does not
     * depend on which view is selected — see `DropdownVisitor`, which takes the starting entry as
     * a parameter for exactly that reason.
     */
    fun all(uiFacade: UIFacade): List<ChartComparisonAction> = listOf(
      DatesComparisonAction(uiFacade),
      EffortComparisonAction(uiFacade),
      DurationsComparisonAction(uiFacade),
      DatesAndDurationsComparisonAction(uiFacade)
    )

    /** The index [all] gives to [comparison]. */
    fun indexOf(comparison: ChartComparison): Int = when (comparison) {
      ChartComparison.DATES -> 0
      ChartComparison.EFFORT -> 1
      ChartComparison.DURATIONS -> 2
      ChartComparison.DATES_AND_DURATIONS -> 3
    }
  }
}

/** "Am I on schedule?" — end date now against end date in the baseline. */
class DatesComparisonAction(uiFacade: UIFacade) :
  ChartComparisonAction(uiFacade, "chart.comparison.dates") {
  override val comparison = ChartComparison.DATES
  override fun getLocalizedName(): String = forkText("fork.comparison.dates")
}

/** "Did this take more or less working time than I thought?" — recorded hours against estimate. */
class EffortComparisonAction(uiFacade: UIFacade) :
  ChartComparisonAction(uiFacade, "chart.comparison.effort") {
  override val comparison = ChartComparison.EFFORT
  override fun getLocalizedName(): String = forkText("fork.comparison.effort")
}

/** "Does the work still take as long as it was planned to?" — length against length. */
class DurationsComparisonAction(uiFacade: UIFacade) :
  ChartComparisonAction(uiFacade, "chart.comparison.durations") {
  override val comparison = ChartComparison.DURATIONS
  override fun getLocalizedName(): String = forkText("fork.comparison.durations")
}

/**
 * Both questions at once, in one band split into two halves.
 *
 * THE ENTRY'S NAME CARRIES THE ONLY EXPLANATION THERE IS of which half is which. The legend in
 * the baseline dialog cannot say it: it explains colours, and "upper half" is not a colour; and
 * it has to hold in all four views, so a sentence that is true only here does not belong in it.
 * That is why this caption names the two axes in the order they appear, top first.
 */
class DatesAndDurationsComparisonAction(uiFacade: UIFacade) :
  ChartComparisonAction(uiFacade, "chart.comparison.datesAndDurations") {
  override val comparison = ChartComparison.DATES_AND_DURATIONS
  override fun getLocalizedName(): String = forkText("fork.comparison.dates_and_durations")
}
