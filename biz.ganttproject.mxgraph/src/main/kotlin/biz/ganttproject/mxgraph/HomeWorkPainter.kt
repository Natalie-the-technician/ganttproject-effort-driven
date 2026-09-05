/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.

This file is part of GanttProject, an open-source project management tool.

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
package biz.ganttproject.mxgraph

import biz.ganttproject.core.chart.canvas.Canvas
import com.mxgraph.util.mxConstants
import net.sourceforge.ganttproject.chart.ChartUIConfiguration
import java.awt.Color

/**
 * [fork change] B4 — the home-working band in the mxGraph export, beside [DayoffPainter].
 *
 * WHY THIS FILE EXISTS AT ALL. `MxGraphPainter` picks its painter out of a map by the style name
 * and falls through to `Style.getStyle(chartProperties, …)` when it finds none. `chart.properties`
 * knows nothing of `homework`, so without an entry here the band would come out of the export with
 * NO fill — invisible, and silently so. A chart that shows the home office on screen and hides it
 * in the exported picture is worse than one that never showed it, because nobody would go looking.
 *
 * THE SECOND CUE IS A DASHED BORDER HERE, not the hatching of the on-screen painter. mxGraph paints
 * a rectangle as one shape and has no hatch fill; drawing the diagonals as separate line shapes
 * would put a dozen extra elements into the exported model for every home-working day. A dashed
 * outline against the day-off band's solid one carries the same information — striped or plain,
 * without any colour — at one entry in the style map. THE TWO CUES ARE THEREFORE NOT IDENTICAL
 * between screen and export, which is worth knowing before comparing a screenshot with an SVG.
 */
internal class HomeWorkPainter(
    private val mxPainterImpl: MxPainterImpl,
    private val uiConfig: ChartUIConfiguration
) : MxGraphPainter.RectanglePainter {

  override fun paint(rectangle: Canvas.Rectangle) {
    val margin = uiConfig.margin - 3
    val color = uiConfig.homeWorkColor
    val mxStyle = mapOf(
        mxConstants.STYLE_FILLCOLOR to color.toHexString(),
        mxConstants.STYLE_STROKECOLOR to Color.BLACK.toHexString(),
        mxConstants.STYLE_DASHED to 1,
        // 67 %, the same firmness the on-screen band has (alpha 170 of 255). See
        // `StyledPainterImpl.HOME_WORK_ALPHA` for why it is firmer than the day-off band's 40 %.
        mxConstants.STYLE_OPACITY to 67
    )
    mxPainterImpl.paintRectangle(
        rectangle.leftX, rectangle.topY + margin, rectangle.width, rectangle.height - 2 * margin,
        mxStyle, rectangle.attributes
    )
  }
}
