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
package net.sourceforge.ganttproject.chart.gantt;

import biz.ganttproject.app.InternationalizationCoreKt;
import biz.ganttproject.core.calendar.GPCalendarCalc;
import biz.ganttproject.core.chart.canvas.Canvas;
import biz.ganttproject.core.chart.canvas.Canvas.Polygon;
import biz.ganttproject.core.chart.canvas.Canvas.Rectangle;
import biz.ganttproject.core.chart.grid.OffsetList;
import biz.ganttproject.core.chart.scene.gantt.*;
import biz.ganttproject.core.model.task.TaskDefaultColumn;
import biz.ganttproject.core.time.TimeDuration;
import biz.ganttproject.core.time.TimeUnit;
import biz.ganttproject.customproperty.CustomPropertyManager;
import net.sourceforge.ganttproject.GanttPreviousStateTask;
import net.sourceforge.ganttproject.fork.ChartComparison;
import net.sourceforge.ganttproject.fork.ChartComparisonKt;
import net.sourceforge.ganttproject.fork.ComparisonResult;
import net.sourceforge.ganttproject.gui.options.OptionsPageBuilder;
import net.sourceforge.ganttproject.task.*;

import java.awt.*;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Predicate;

/**
 * Renders task rectangles, dependency lines and all task-related text strings
 * in the gantt chart
 */
public class GanttChartSceneBuilder {
  public interface InputApi {
    int getHeaderHeight();
    int getWidth();
    int getLabelsFontSize();
    int getVerticalOffset();
    OffsetList getTasksUnitOffsets();
    TimeUnit getProgressBarTimeUnit();
    VerticalPartitioning getVerticalPartitioning();
    List<ITask> getVisibleTasks();
    List<ITaskSceneTask> getVisibleTaskSceneTasks();
    List<ITaskSceneTask> getTasksInDocumentOrder();
    List<GanttPreviousStateTask> getBaseline();

    /** [Fork change] What the band underneath the bar compares. See {@link ChartComparison}. */
    ChartComparison getComparison();

    /**
     * [Fork change] The task's original effort estimate in hours, or null. By row id rather than
     * by task, because ITaskSceneTask deliberately knows nothing about custom columns.
     */
    Double getOriginalEffortHours(int rowId);

    /** [Fork change] The task's recorded hours, or null. */
    Double getActualEffortHours(int rowId);
    TaskActivitySceneBuilder.ChartApi getChartApi(TaskLabelSceneBuilder<ITaskSceneTask> labelsRenderer);
    GPCalendarCalc getCalendar();
    Date getStartDate();
    TimeDuration createLength(TimeUnit timeUnit, Date startDate, Date endDate);
    TimeDuration createLength(int duration);
    CustomPropertyManager getCustomPropertyManager();
  }

  private final Canvas canvas;
  private final InputApi input;
  private final TaskLabelSceneInput<ITaskSceneTask> taskLabelSceneApi;

  public final TaskLabelSceneBuilder<ITaskSceneTask> myLabelsRenderer;

  private final TaskActivitySceneBuilder.TaskApi<ITaskSceneTask, ITaskActivity<ITaskSceneTask>> myTaskApi = new TaskActivitySceneTaskApi();

  private final TaskActivitySceneBuilder<ITaskSceneTask, ITaskActivity<ITaskSceneTask>> myTaskActivityRenderer;
  private final TaskActivitySceneBuilder<ITaskSceneTask, ITaskActivity<ITaskSceneTask>> myBaselineActivityRenderer;

  private final Canvas myLabelsLayer;
  private final TaskActivitySceneBuilder.ChartApi myChartApi;
  private final TaskActivitySplitter mySplitter;

  public GanttChartSceneBuilder(InputApi input) {
    this(input, new Canvas());
  }

  public GanttChartSceneBuilder(InputApi input, Canvas canvas) {
    this.input = input;
    this.canvas = canvas;

    getPrimitiveContainer().setOffset(0, input.getHeaderHeight());
    getPrimitiveContainer().newLayer();
    getPrimitiveContainer().newLayer();
    getPrimitiveContainer().newLayer();
    myLabelsLayer = getPrimitiveContainer().newLayer();

    var topLabelOption = new TaskColumnEnumerationOption("taskLabelUp", input.getCustomPropertyManager().getDefinitions());
    var bottomLabelOption = new TaskColumnEnumerationOption("taskLabelDown", input.getCustomPropertyManager().getDefinitions());
    var leftLabelOption = new TaskColumnEnumerationOption("taskLabelLeft", input.getCustomPropertyManager().getDefinitions());
    var rightLabelOption = new TaskColumnEnumerationOption("taskLabelRight", input.getCustomPropertyManager().getDefinitions());
    var allOptions = List.of(topLabelOption, bottomLabelOption, leftLabelOption, rightLabelOption);
    allOptions.forEach(option -> option.setValueLocalizer(id -> {
      var column = option.pubStringToObject(id);
      if (column == null || column.getID().isEmpty()) {
        return "";
      }
      var defaultColumn = TaskDefaultColumn.find(column.getID());
      if (defaultColumn != null) {
        return defaultColumn.getName();
      }
      var customProperty = input.getCustomPropertyManager().getCustomPropertyDefinition(column.getID());
      if (customProperty != null) {
        return customProperty.getName();
      }
      return InternationalizationCoreKt.getRootLocalizer().formatText(OptionsPageBuilder.I18N.getCanonicalOptionValueLabelKey(id));
    }));

    input.getCustomPropertyManager().addListener(event -> {
      allOptions.forEach(option -> option.reload(input.getCustomPropertyManager().getDefinitions()));
    });

    taskLabelSceneApi = new TaskLabelSceneInput<>(
      topLabelOption, bottomLabelOption, leftLabelOption, rightLabelOption,
      input.getLabelsFontSize(), input.getBaseline() != null,
      ITaskSceneTask::getProperty
    );

    myLabelsRenderer = new TaskLabelSceneBuilder<>(taskLabelSceneApi, myLabelsLayer);
    myChartApi = input.getChartApi(myLabelsRenderer);
    this.mySplitter = new TaskActivitySplitter<ITask>(
      input::getStartDate,
      myChartApi::getEndDate,
      input::createLength
    );
    myTaskActivityRenderer = createTaskActivitySceneBuilder(getPrimitiveContainer(), myChartApi,
        new TaskActivitySceneBuilder.Style(0));
    myBaselineActivityRenderer = createTaskActivitySceneBuilder(
        getPrimitiveContainer().getLayer(2), myChartApi,
        new TaskActivitySceneBuilder.Style(getRectangleHeight()));
  }

  public Canvas render() {
    getPrimitiveContainer().clear();
    getPrimitiveContainer().getLayer(0).clear();
    getPrimitiveContainer().getLayer(1).clear();
    getPrimitiveContainer().getLayer(2).clear();
    getPrimitiveContainer().setOffset(0, input.getHeaderHeight() - input.getVerticalOffset());
    getPrimitiveContainer().getLayer(2).setOffset(0, input.getHeaderHeight() - input.getVerticalOffset());

    VerticalPartitioning vp = input.getVerticalPartitioning();
    vp.build(input.getTasksInDocumentOrder());
    OffsetList defaultUnitOffsets = input.getTasksUnitOffsets();

    renderVisibleTasks(input.getVisibleTaskSceneTasks(), defaultUnitOffsets);
    renderTasksAboveAndBelowViewport(vp.getAboveViewport(), vp.getBelowViewport(), defaultUnitOffsets);
    renderDependencies();

    return getPrimitiveContainer();
  }

  public TaskLabelSceneInput getTaskLabelSceneApi() {
    return taskLabelSceneApi;
  }

  private Canvas getPrimitiveContainer() {
    return canvas;
  }

  private void renderDependencies() {
    DependencySceneBuilder.ChartApi chartApi = () -> getRectangleHeight();
    var taskApi = new DependencySceneTaskApi(input.getVisibleTasks(), mySplitter);
    DependencySceneBuilder<ITask, BarChartConnectorImpl> dependencyRenderer = new DependencySceneBuilder<>(
        getPrimitiveContainer(), getPrimitiveContainer().getLayer(1), taskApi, chartApi);
    dependencyRenderer.build();
  }

  private void renderTasksAboveAndBelowViewport(List<ITaskSceneTask> tasksAboveViewport, List<ITaskSceneTask> tasksBelowViewport,
      OffsetList defaultUnitOffsets) {
    for (ITaskSceneTask nextAbove : tasksAboveViewport) {
      List<ITaskActivity<ITaskSceneTask>> activities = /*nextAbove.isMilestone() ? Collections.<TaskActivity> singletonList(new MilestoneTaskFakeActivity(
          nextAbove)) : */nextAbove.getActivities();
      for (Canvas.Shape s : renderActivities(-1, nextAbove, activities, defaultUnitOffsets, false)) {
        s.setVisible(false);
      }
    }
    for (ITaskSceneTask nextBelow : tasksBelowViewport) {
      List<ITaskActivity<ITaskSceneTask>> activities = /*nextBelow.isMilestone() ? Collections.<TaskActivity> singletonList(new MilestoneTaskFakeActivity(
          nextBelow)) : */nextBelow.getActivities();
      List<Polygon> rectangles = renderActivities(input.getVisibleTasks().size() + 1, nextBelow, activities,
          defaultUnitOffsets, false);
      for (Polygon nextRectangle : rectangles) {
        nextRectangle.setVisible(false);
      }
    }
  }

  private void renderVisibleTasks(List<ITaskSceneTask> visibleTasks, OffsetList defaultUnitOffsets) {
    List<Polygon> boundPolygons = new ArrayList<>();
    int rowNum = 0;
    for (ITaskSceneTask t : visibleTasks) {
      boundPolygons.clear();
      List<ITaskActivity<ITaskSceneTask>> activities = t.getActivities();
      activities = mySplitter.split(activities, Integer.MAX_VALUE);
      List<Polygon> rectangles = renderActivities(rowNum, t, activities, defaultUnitOffsets, true);
      for (Polygon p : rectangles) {
        if (p.getModelObject() != null) {
          boundPolygons.add(p);
        }
      }
      renderLabels(boundPolygons);
      renderComparisonBand(t, rowNum, defaultUnitOffsets);
      rowNum++;
      Canvas.Line nextLine = getPrimitiveContainer().createLine(0, rowNum * getRowHeight(),
          input.getWidth(), rowNum * getRowHeight());
      nextLine.setForegroundColor(Color.GRAY);
    }
  }

  public int getRowHeight() {
    return myChartApi.getRowHeight();
  }

  /**
   * [Fork change] Draws the band underneath the task bar -- depending on the selected view either
   * the baseline comparison (dates) or the effort comparison. See {@link ChartComparison} for why
   * there are two of them rather than one.
   */
  private void renderComparisonBand(ITaskSceneTask t, int rowNum, OffsetList defaultUnitOffsets) {
    if (input.getComparison() == ChartComparison.EFFORT) {
      renderEffortBand(t, rowNum, defaultUnitOffsets);
    } else {
      renderBaseline(t, rowNum, defaultUnitOffsets);
    }
  }

  /**
   * [Fork change] The effort band: recorded hours against the original estimate.
   *
   * It needs NO baseline -- both numbers sit on the task itself. The band therefore lies exactly
   * underneath the task bar and is exactly as long as it is; the statement is carried by the
   * colour alone. A length of its own would be a second statement and would only confuse: how
   * long a task takes in the calendar has nothing to do with the hours spent on it -- and that
   * separation is the whole reason this view exists.
   */
  private void renderEffortBand(ITaskSceneTask t, int rowNum, OffsetList defaultUnitOffsets) {
    ComparisonResult result = ChartComparisonKt.compareEffort(
        input.getOriginalEffortHours(t.getRowId()),
        input.getActualEffortHours(t.getRowId()));
    if (result == ComparisonResult.NO_BAND) {
      return;
    }
    List<ITaskActivity<ITaskSceneTask>> activities = t.getActivities();
    if (activities.isEmpty()) {
      return;
    }
    paintBand(rowNum, defaultUnitOffsets, mySplitter.split(activities, Integer.MAX_VALUE),
        result, t.isMilestone());
  }

  /**
   * The dates band: today's end date against the end date in the baseline.
   *
   * [Fork change] ---- begin ----
   *
   * THIS METHOD CARRIED A DIFFERENT RULE FOR A WHILE. From 17 to 20 August 2026 it compared
   * DURATIONS instead of end dates, because the legend in the baseline dialog talks about the
   * duration and a merely shifted task would otherwise turn red.
   *
   * WHY THAT WAS WITHDRAWN, measured on 20 August 2026: in this fork a task's duration is not an
   * input but a computed value derived from effort and daily availability. On a real plan the
   * availability was set from 8 to 1.8 hours a day -- 163 of 163 tasks carrying an effort grew by
   * the factor 4.44, 194 of 276 rows turned red, and not a single effort had changed by so much
   * as an hour. A duration comparison therefore answers neither "am I on schedule" nor "did I
   * misjudge the work".
   *
   * Both questions now have a view of their own. HERE the original's rule applies again -- the
   * end date -- and with it the legend in the dialog is correct again, which talks about
   * finishing.
   *
   * What stays: a band is drawn as soon as the end date is a different one. The same end date
   * means on schedule and leaves the row empty.
   *
   * [Fork change] ---- end ----
   */
  private void renderBaseline(ITaskSceneTask t, int rowNum, OffsetList defaultUnitOffsets) {
    TaskActivitiesSceneAlgorithm alg = new TaskActivitiesSceneAlgorithm(
      input.getCalendar(),
      (Date s, Date e) -> input.createLength(t.getDuration().getTimeUnit(), s, e)
    );
    List<GanttPreviousStateTask> baseline = input.getBaseline();
    if (baseline == null) {
      return;
    }
    for (GanttPreviousStateTask taskBaseline : baseline) {
      if (taskBaseline.getId() != t.getRowId()) {
        continue;
      }
      Date startDate = taskBaseline.getStart().getTime();
      TimeDuration duration = input.createLength(taskBaseline.getDuration());
      Date endDate = input.getCalendar().shiftDate(startDate, duration);
      ComparisonResult result = ChartComparisonKt.compareDates(endDate, t.getEnd().getTime());
      if (result == ComparisonResult.NO_BAND) {
        return;
      }
      List<ITaskActivity<ITaskSceneTask>> baselineActivities = new ArrayList<ITaskActivity<ITaskSceneTask>>();
      if (t.isMilestone()) {
        baselineActivities.add(
          new TaskSceneMilestoneActivity(t, startDate, endDate, input.createLength(1))
        );
      } else {
        alg.recalculateActivities(t, baselineActivities, startDate, endDate);
      }
      paintBand(rowNum, defaultUnitOffsets, baselineActivities, result, t.isMilestone());
      return;
    }
  }

  /**
   * [Fork change] Shared painting of the two bands. This block used to sit inside the baseline
   * branch only; it was pulled out so that the effort view does not have to spell out the same
   * chain of styles a second time -- two copies would be two chances for them to drift apart.
   */
  private void paintBand(int rowNum, OffsetList defaultUnitOffsets,
                         List<ITaskActivity<ITaskSceneTask>> activities,
                         ComparisonResult result, boolean isMilestone) {
    List<Polygon> bandRectangles = myBaselineActivityRenderer.renderActivities(rowNum, activities,
        defaultUnitOffsets);
    String colourStyle = ChartComparisonKt.styleName(result);
    for (int i = 0; i < bandRectangles.size(); i++) {
      Polygon r = bandRectangles.get(i);
      r.setStyle("previousStateTask");
      if (isMilestone) {
        r.addStyle("milestone");
      }
      if (colourStyle != null) {
        r.addStyle(colourStyle);
      }
      if (i == 0) {
        r.addStyle("start");
      }
      if (i == bandRectangles.size() - 1) {
        r.addStyle("end");
      }
    }
  }

  private static Predicate<Polygon> REMOVE_SUPERTASK_ENDINGS = shape -> !shape.hasStyle("task.ending");

  private List<Polygon> renderActivities(final int rowNum, ITaskSceneTask t, List<ITaskActivity<ITaskSceneTask>> activities,
      OffsetList defaultUnitOffsets, boolean areVisible) {
    List<Polygon> rectangles = myTaskActivityRenderer.renderActivities(rowNum, activities, defaultUnitOffsets);
    if (areVisible && !myTaskApi.hasNestedTasks(t) && !t.isMilestone() && !t.isProjectTask()) {
      renderProgressBar(rectangles.stream().filter(REMOVE_SUPERTASK_ENDINGS).toList());
    }
    if (areVisible && myTaskApi.hasNotes(t)) {
      Rectangle notes = getPrimitiveContainer().createRectangle(input.getWidth() - 24, rowNum * getRowHeight() + getRowHeight()/2 - 8, 16, 16);
      notes.setStyle("task.notesMark");
      getPrimitiveContainer().bind(notes, t);
    }
    return rectangles;
  }

  private void renderLabels(List<Polygon> rectangles) {
    if (!rectangles.isEmpty()) {
      myLabelsRenderer.renderLabels(rectangles);
    }
  }

  private void renderProgressBar(List<Polygon> rectangles) {
    if (rectangles.isEmpty()) {
      return;
    }
    final Canvas container = getPrimitiveContainer().getLayer(0);
    final TimeUnit timeUnit = input.getProgressBarTimeUnit();
    final ITaskSceneTask task = ((ITaskActivity<ITaskSceneTask>) rectangles.get(0).getModelObject()).getOwner();
    float length = task.getDuration().getLength(timeUnit);
    float completed = task.getCompletionPercentage() * length / 100f;
    Polygon lastProgressRectangle = null;

    for (Polygon nextRectangle : rectangles) {
      final ITaskActivity<ITaskSceneTask> nextActivity = (ITaskActivity<ITaskSceneTask>) nextRectangle.getModelObject();
      final float nextLength = nextActivity.getDuration().getLength(timeUnit);

      final int nextProgressBarLength;
      if (completed > nextLength || nextActivity.getIntensity() == 0f) {
        nextProgressBarLength = nextRectangle.getWidth();
        if (nextActivity.getIntensity() > 0f) {
          completed -= nextLength;
        }
      } else {
        nextProgressBarLength = (int) (nextRectangle.getWidth() * (completed / nextLength));
        completed = 0f;
      }

      final Rectangle nextProgressBar = container.createRectangle(nextRectangle.getLeftX(),
          nextRectangle.getMiddleY() - 1, nextProgressBarLength, 3);
      nextProgressBar.setStyle(completed == 0f ? "task.progress.end" : "task.progress");
      getPrimitiveContainer().getLayer(0).bind(nextProgressBar, task);
      if (completed == 0) {
        lastProgressRectangle = nextRectangle;
        break;
      }
    }
    if (lastProgressRectangle == null) {
      lastProgressRectangle = rectangles.get(rectangles.size() - 1);
    }
    // createDownSideText(lastProgressRectangle);
  }

  private int getRectangleHeight() {
    return myLabelsRenderer.getFontHeight();
  }

  public Canvas getLabelLayer() {
    return myLabelsLayer;
  }

  private TaskActivitySceneBuilder<ITaskSceneTask, ITaskActivity<ITaskSceneTask>> createTaskActivitySceneBuilder(
      Canvas canvas, TaskActivitySceneBuilder.ChartApi chartApi, TaskActivitySceneBuilder.Style style) {
    return new TaskActivitySceneBuilder<>(myTaskApi, chartApi, canvas, myLabelsRenderer, style);
  }
}
