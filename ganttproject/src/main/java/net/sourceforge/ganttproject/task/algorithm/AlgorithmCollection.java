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
package net.sourceforge.ganttproject.task.algorithm;

import biz.ganttproject.core.chart.scene.gantt.ChartBoundsAlgorithm;
import net.sourceforge.ganttproject.task.TaskManagerImpl;

/**
 * Created by IntelliJ IDEA. User: bard
 */
public class AlgorithmCollection {
  private final FindPossibleDependeesAlgorithm myFindPossibleDependeesAlgorithm;

  private final RecalculateTaskScheduleAlgorithm myRecalculateTaskScheduleAlgorithm;

  private final AdjustTaskBoundsAlgorithm myAdjustTaskBoundsAlgorithm;

  private final RecalculateTaskCompletionPercentageAlgorithm myCompletionPercentageAlgorithm;

  private final ChartBoundsAlgorithm myProjectBoundsAlgorithm;

  private final CriticalPathAlgorithm myCriticalPathAlgorithm;

  // [Fork-Aenderung] Neues Feld: haelt den Algorithmus, der die Dauer aus dem Aufwand rechnet.
  // Im Original-GanttProject gibt es dieses Feld nicht.
  private final EffortDrivenDurationAlgorithm myEffortDrivenDurationAlgorithm;

  private final AlgorithmBase myScheduler;

  public AlgorithmCollection(
      TaskManagerImpl taskManager,
      FindPossibleDependeesAlgorithm myFindPossibleDependeesAlgorithm,
      RecalculateTaskScheduleAlgorithm recalculateTaskScheduleAlgorithm,
      AdjustTaskBoundsAlgorithm adjustTaskBoundsAlgorithm,
      RecalculateTaskCompletionPercentageAlgorithm completionPercentageAlgorithm,
      ChartBoundsAlgorithm projectBoundsAlgorithm, CriticalPathAlgorithm criticalPathAlgorithm,
      // [Fork-Aenderung] Neuer Konstruktorparameter (im Original nicht vorhanden).
      EffortDrivenDurationAlgorithm effortDrivenDurationAlgorithm,
      AlgorithmBase scheduler) {
    myScheduler = scheduler;
    this.myFindPossibleDependeesAlgorithm = myFindPossibleDependeesAlgorithm;
    myRecalculateTaskScheduleAlgorithm = recalculateTaskScheduleAlgorithm;
    myAdjustTaskBoundsAlgorithm = adjustTaskBoundsAlgorithm;
    myCompletionPercentageAlgorithm = completionPercentageAlgorithm;
    myProjectBoundsAlgorithm = projectBoundsAlgorithm;
    myCriticalPathAlgorithm = criticalPathAlgorithm;
    // [Fork-Aenderung] Neue Zuweisung.
    myEffortDrivenDurationAlgorithm = effortDrivenDurationAlgorithm;
  }

  public FindPossibleDependeesAlgorithm getFindPossibleDependeesAlgorithm() {
    return myFindPossibleDependeesAlgorithm;
  }

  public RecalculateTaskScheduleAlgorithm getRecalculateTaskScheduleAlgorithm() {
    return myRecalculateTaskScheduleAlgorithm;
  }

  public AdjustTaskBoundsAlgorithm getAdjustTaskBoundsAlgorithm() {
    return myAdjustTaskBoundsAlgorithm;
  }

  public RecalculateTaskCompletionPercentageAlgorithm getRecalculateTaskCompletionPercentageAlgorithm() {
    return myCompletionPercentageAlgorithm;
  }

  public ChartBoundsAlgorithm getProjectBoundsAlgorithm() {
    return myProjectBoundsAlgorithm;
  }

  public CriticalPathAlgorithm getCriticalPathAlgorithm() {
    return myCriticalPathAlgorithm;
  }

  /**
   * [Fork-Aenderung] Neuer Getter, im Original nicht vorhanden.
   *
   * Derives task durations from effort and daily resource availability. Must run BEFORE the
   * scheduler: it sets the durations, the scheduler then propagates the dates through the
   * dependency graph. Run afterwards, the propagated dates would be stale.
   */
  public EffortDrivenDurationAlgorithm getEffortDrivenDurationAlgorithm() {
    return myEffortDrivenDurationAlgorithm;
  }

  public AlgorithmBase getScheduler() {
    return myScheduler;
  }
}
