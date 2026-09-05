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

import biz.ganttproject.core.calendar.GPCalendarCalc;
import biz.ganttproject.core.chart.canvas.Canvas;
import biz.ganttproject.core.chart.canvas.Canvas.Rectangle;
import biz.ganttproject.core.chart.grid.OffsetList;
import biz.ganttproject.core.chart.scene.gantt.TaskActivitySceneBuilder;
import biz.ganttproject.core.chart.scene.gantt.TaskLabelSceneBuilder;
import biz.ganttproject.core.chart.scene.gantt.TaskLabelSceneInput;
import biz.ganttproject.core.option.GPOption;
import biz.ganttproject.core.option.GPOptionGroup;
import biz.ganttproject.core.time.TimeDuration;
import biz.ganttproject.core.time.TimeUnit;
import biz.ganttproject.customproperty.CustomPropertyManager;
import com.google.common.collect.ImmutableList;
import net.sourceforge.ganttproject.GanttPreviousStateTask;
import net.sourceforge.ganttproject.fork.AbsenceRun;
import net.sourceforge.ganttproject.fork.AbsenceStripeKt;
import net.sourceforge.ganttproject.fork.ChartComparison;
import net.sourceforge.ganttproject.fork.ChartComparisonKt;
import net.sourceforge.ganttproject.fork.LevellingAdapterKt;
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenDurationAlgorithmKt;
import net.sourceforge.ganttproject.chart.gantt.*;
import net.sourceforge.ganttproject.task.*;

import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

import static net.sourceforge.ganttproject.chart.gantt.TaskActivitySceneApiAdapterKt.mapTaskSceneTask2Task;

/**
 * Renders task rectangles, dependency lines and all task-related text strings
 * in the gantt chart
 */
public class TaskRendererImpl2 extends ChartRendererBase {
  private final GanttChartSceneBuilder chartRenderer;

  private final ChartModelImpl myModel;

  private final GPOptionGroup myLabelOptions;

  class GanttChartSceneApi implements GanttChartSceneBuilder.InputApi {
    @Override
    public int getHeaderHeight() {
      return myModel.getChartUIConfiguration().getHeaderHeight();
    }

    @Override
    public int getWidth() {
      return (int) getChartModel().getBounds().getWidth();
    }

    @Override
    public int getLabelsFontSize() {
      return getChartModel().getChartUIConfiguration().getBaseFontSize();
    }

    @Override
    public int getVerticalOffset() {
      return myModel.getVerticalOffset();
    }

    @Override
    public OffsetList getTasksUnitOffsets() {
      return getChartModel().getDefaultUnitOffsets();
    }

    @Override
    public TimeUnit getProgressBarTimeUnit() {
      return getChartModel().getTimeUnitStack().getDefaultTimeUnit();
    }

    @Override
    public net.sourceforge.ganttproject.chart.gantt.VerticalPartitioning getVerticalPartitioning() {
      TaskContainmentHierarchyFacade containment = myModel.getTaskManager().getTaskHierarchy();
      Map<ITaskSceneTask, Task> tasksMap = mapTaskSceneTask2Task(containment.getTasksInDocumentOrder(), myModel);
      List<ITaskSceneTask> rows = getVisibleTaskSceneTasks();
      // What the task table shows is exactly these rows: a filter or a named view removes a task
      // from the tree, so it never becomes a row at all. Everything in the document order that is
      // not a row is therefore HIDDEN and must land in no partition. Without this the tasks hidden
      // at the head and at the tail of the document order end up in aboveViewport/belowViewport,
      // get an invisible rectangle at row -1 or n+1 and keep their dependency lines, which then run
      // to the edge of the chart -- see the doc of VerticalPartitioning.isHidden.
      //
      // THE PRECONDITION: this list is the FULL row list of the table, never a scroll window.
      // Checked at every caller of ChartModelImpl.setVisibleTasks -- GanttChartController.paintChart,
      // GanttChartController.asPrintChartApi and ChartImageBuilder, all fed from
      // TaskTableChartConnector.visibleTasks. Should that ever change, this set difference would
      // take the scrolled-away tasks for hidden ones and the two partitions would lose their point.
      Set<ITaskSceneTask> rowSet = new HashSet<>(rows);
      return new net.sourceforge.ganttproject.chart.gantt.VerticalPartitioning(
        rows,
        (ITaskSceneTask t1, ITaskSceneTask t2) -> containment.areUnrelated(tasksMap.get(t1), tasksMap.get(t2)),
        t -> !rowSet.contains(t)
      );
    }

    @Override
    public List<ITask> getVisibleTasks() {
      TaskContainmentHierarchyFacade containment = myModel.getTaskManager().getTaskHierarchy();
      Map<Task, ITask> tasks2itasks = DependencySceneApiAdapterKt.tasks2itasks(containment.getTasksInDocumentOrder());
      return myModel.getVisibleTasks().stream().map(tasks2itasks::get).collect(Collectors.toList());
    }

    @Override
    public List<ITaskSceneTask> getVisibleTaskSceneTasks() {
      return ImmutableList.copyOf(
        mapTaskSceneTask2Task(TaskRendererImpl2.this.getVisibleTasks(), myModel).keySet()
      );
    }

    @Override
    public List<ITaskSceneTask> getTasksInDocumentOrder() {
      TaskContainmentHierarchyFacade containment = myModel.getTaskManager().getTaskHierarchy();
      return ImmutableList.copyOf(
        mapTaskSceneTask2Task(containment.getTasksInDocumentOrder(), myModel).keySet()
      );
    }

    @Override
    public List<GanttPreviousStateTask> getBaseline() {
      return myModel.getBaseline();
    }

    @Override
    public ChartComparison getComparison() {
      return myModel.getComparison();
    }

    /**
     * [Fork change] The renderer fetches the effort numbers here rather than in the scene
     * builder: only here are the real task and the column manager available. A task that no
     * longer exists yields null -- the effort comparison then draws nothing.
     */
    @Override
    public Double getOriginalEffortHours(int rowId) {
      Task task = myModel.getTaskManager().getTask(rowId);
      return task == null ? null
        : LevellingAdapterKt.originalEffortHours(task, myModel.getTaskManager().getCustomPropertyManager());
    }

    @Override
    public Double getActualEffortHours(int rowId) {
      Task task = myModel.getTaskManager().getTask(rowId);
      return task == null ? null
        : EffortDrivenDurationAlgorithmKt.actualEffortHours(task, myModel.getTaskManager().getCustomPropertyManager());
    }

    /**
     * [Fork change] Whose holidays fall into this task, for the stripe on its bar.
     *
     * THIS IS THE STEP THAT WAS MISSING. A task bar knew nothing about its assignments -- the
     * whole renderer did not mention them once -- because ITaskSceneTask is deliberately a
     * drawing-only view of a task. The real task, and with it its assignments and their people, is
     * reachable exactly here, in the same place and by the same means as the two effort numbers
     * above, so this is the shortest cut into foreign territory the fork can make: the scene
     * builder learns a list of day ranges and still knows nothing about resources.
     *
     * A task that no longer exists yields an empty list -- no stripe, rather than a failure while
     * painting.
     */
    @Override
    public List<AbsenceRun> getAbsenceRuns(int rowId) {
      Task task = myModel.getTaskManager().getTask(rowId);
      return task == null ? Collections.emptyList() : AbsenceStripeKt.absenceRuns(task);
    }

    @Override
    public TaskActivitySceneBuilder.ChartApi getChartApi(TaskLabelSceneBuilder<ITaskSceneTask> labelsRenderer) {
      return new TaskActivitySceneChartApi(myModel) {
        @Override
        public int getRowHeight() {
          return  myModel.getChartUIConfiguration().getRowHeight();
        }
        @Override
        public int getBarHeight() {
          return labelsRenderer.getFontHeight();
        }
      };
    }

    @Override
    public GPCalendarCalc getCalendar() {
      return TaskRendererImpl2.this.getCalendar();
    }

    @Override
    public Date getStartDate() {
      return myModel.getStartDate();
    }

    @Override
    public TimeDuration createLength(TimeUnit timeUnit, Date startDate, Date endDate) {
      return myModel.getTaskManager().createLength(timeUnit, startDate, endDate);
    }

    @Override
    public TimeDuration createLength(int duration) {
      return getChartModel().getTaskManager().createLength(duration);
    }

    @Override
    public CustomPropertyManager getCustomPropertyManager() {
      return getChartModel().getTaskManager().getCustomPropertyManager();
    }
  }

  public TaskRendererImpl2(ChartModelImpl model) {
    super(model);
    myModel = model;
    chartRenderer = new GanttChartSceneBuilder(new GanttChartSceneApi(), getPrimitiveContainer());
    TaskLabelSceneInput taskLabelSceneApi = chartRenderer.getTaskLabelSceneApi();
    myLabelOptions = new ChartOptionGroup("ganttChartDetails",
        new GPOption[] {
          taskLabelSceneApi.getTopLabelOption(), taskLabelSceneApi.getBottomLabelOption(),
          taskLabelSceneApi.getLeftLabelOption(), taskLabelSceneApi.getRightLabelOption()
        },
        model.getOptionEventDispatcher()
    );
  }

  private List<Task> getVisibleTasks() {
    return ((ChartModelImpl) getChartModel()).getVisibleTasks();
  }

  @Override
  public void render() {
    chartRenderer.render();
  }

  public GPOptionGroup getLabelOptions() {
    return myLabelOptions;
  }

  public int calculateRowHeight() {
    int rowHeight = chartRenderer.myLabelsRenderer.calculateRowHeight();
    // [Fork change] The effort view needs the same room for its band even though it works
    // without a baseline. Without this line it draws into the row below. The durations view was
    // added to the same condition on 25 August 2026 for the same reason: without a baseline it
    // draws a NEUTRAL band rather than staying empty, so it needs the room too.
    if (myModel.getBaseline() != null
        || ChartComparisonKt.needsBandRoomWithoutBaseline(myModel.getComparison())) {
      rowHeight = rowHeight + 8;
    }
    return rowHeight;
  }

  public static List<Rectangle> getTaskRectangles(Task t, ChartModelImpl chartModel) {
    List<Rectangle> result = new ArrayList<>();
    ITaskSceneTask task = new ITaskSceneTaskImpl(t, chartModel);
    List<ITaskActivity<ITaskSceneTask>> originalActivities = task.getActivities();
    TaskActivitySplitter<ITaskSceneTask> splitter = new TaskActivitySplitter<>(
        chartModel::getStartDate,
        chartModel::getEndDate,
        (u, s, e) -> chartModel.getTaskManager().createLength(u, s, e)
    );
    List<ITaskActivity<ITaskSceneTask>> splitOnBounds = splitter.split(originalActivities, Integer.MAX_VALUE);
    for (ITaskActivity<ITaskSceneTask> activity : splitOnBounds) {
      assert activity != null : "Got null activity in task="+t;
      Canvas.Shape graphicPrimitive = chartModel.getGraphicPrimitive(activity);
      assert graphicPrimitive != null : "Got null for activity="+activity;
      assert graphicPrimitive instanceof Rectangle;
      result.add((Rectangle) graphicPrimitive);
    }
    return result;

  }
}
