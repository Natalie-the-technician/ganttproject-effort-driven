/*
Copyright 2003-2012 Dmitry Barashev, GanttProject Team

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
package net.sourceforge.ganttproject.chart;

import net.sourceforge.ganttproject.GanttPreviousState;
import net.sourceforge.ganttproject.fork.ChartComparison;
import biz.ganttproject.core.option.ColorOption;
import biz.ganttproject.core.option.EnumerationOption;
import biz.ganttproject.core.option.GPOptionGroup;

public interface GanttChart extends TimelineChart {
  void setBaseline(GanttPreviousState ganttPreviousState);

  GanttPreviousState getBaseline();

  /** [Fork change] What the band underneath a task bar compares. */
  ChartComparison getComparison();

  /** [Fork change] Sets the comparison view and adjusts the row height. */
  void setComparison(ChartComparison comparison);

  GPOptionGroup getBaselineColorOptions();
  ColorOption getTaskDefaultColorOption();

  GPOptionGroup getTaskLabelOptions();
}
