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
import biz.ganttproject.core.chart.grid.OffsetLookup;
import biz.ganttproject.core.chart.scene.gantt.*;
import biz.ganttproject.core.model.task.TaskDefaultColumn;
import biz.ganttproject.core.time.TimeDuration;
import biz.ganttproject.core.time.TimeUnit;
import biz.ganttproject.customproperty.CustomPropertyManager;
import net.sourceforge.ganttproject.GanttPreviousStateTask;
import net.sourceforge.ganttproject.fork.AbsenceRun;
import net.sourceforge.ganttproject.fork.HiddenGapMark;
import net.sourceforge.ganttproject.fork.HiddenTaskGap;
import net.sourceforge.ganttproject.fork.HiddenTaskGapKt;
import net.sourceforge.ganttproject.fork.AbsenceStripeKt;
import net.sourceforge.ganttproject.fork.StripeSpan;
import net.sourceforge.ganttproject.fork.ChartComparison;
import net.sourceforge.ganttproject.fork.ComparisonAxis;
import net.sourceforge.ganttproject.fork.BothComparisons;
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

    /**
     * [Fork change] The days on which somebody assigned to this task is away, as disjoint
     * ascending half-open runs. Empty when nobody is.
     *
     * BY ROW ID AND NOT BY TASK, the same way the two effort numbers above are fetched, and for
     * the same reason: {@link ITaskSceneTask} deliberately knows nothing about the task model, and
     * assignments are exactly that. `git grep assignments` over the renderer was empty before this
     * package -- a task bar did not know who was working on it. See
     * {@link net.sourceforge.ganttproject.fork.AbsenceStripe} for whose absence counts.
     */
    List<AbsenceRun> getAbsenceRuns(int rowId);

    /**
     * [Fork change] The gaps a named view or a filter has torn into the plan, in document order.
     * Empty when the chart shows everything, which is the ordinary case.
     *
     * COMPUTED BY THE RENDERER AND NOT HERE, for the same reason as the two effort numbers and the
     * absence runs above: working out which missing task is hidden and which is merely collapsed
     * needs the task hierarchy, and {@link ITaskSceneTask} deliberately knows nothing about it.
     * The scene builder learns a list of row indices and dates and still knows nothing about views.
     */
    List<HiddenTaskGap> getHiddenTaskGaps();
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

  /**
   * [Fork change] The vertical shift {@link #render} puts on the canvas, in pixels.
   *
   * WHY IT HAS A NAME NOW. `Canvas.createRectangle` ADDS this shift to the y it is given, while
   * `Rectangle.getTopY` returns a y that already has it. Anything positioned relative to a
   * rectangle that has already been drawn -- which is what an absence stripe is -- has to take it
   * off again first, or it lands one header height too low and scrolls twice as fast as the bar it
   * belongs to. Pulled out of `render` rather than written twice so that the two cannot drift.
   *
   * The horizontal shift is not needed: `render` sets it to 0 and always has.
   */
  private int canvasDeltaY() {
    return input.getHeaderHeight() - input.getVerticalOffset();
  }

  public Canvas render() {
    getPrimitiveContainer().clear();
    getPrimitiveContainer().getLayer(0).clear();
    getPrimitiveContainer().getLayer(1).clear();
    getPrimitiveContainer().getLayer(2).clear();
    getPrimitiveContainer().setOffset(0, canvasDeltaY());
    getPrimitiveContainer().getLayer(2).setOffset(0, canvasDeltaY());

    VerticalPartitioning vp = input.getVerticalPartitioning();
    vp.build(input.getTasksInDocumentOrder());
    OffsetList defaultUnitOffsets = input.getTasksUnitOffsets();

    // [Fork change] BEFORE the bars, and that is the whole safety of it: within one canvas the
    // painting order is the order of creation, so anything a mark happens to share a pixel with --
    // the top edge of the first task bar, in the narrowest row the chart draws -- is painted over
    // it. A mark can never cover something that is visible.
    renderHiddenTaskGaps(defaultUnitOffsets);
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

  /**
   * [Fork change] THE COLLISION BAR. One mark per gap, lying on the seam between the two rows the
   * hidden tasks were taken from and stretching over the days they occupy.
   *
   * See {@link net.sourceforge.ganttproject.fork.HiddenTaskGap} for what counts as a gap and why,
   * and `hiddenGapMark` for where the mark ends up. Everything decided is decided there; what is
   * left here is measuring the two dates against the chart's own day columns -- through the very
   * same {@link OffsetLookup} a task bar is measured with, so a mark cannot drift a pixel away from
   * the days it names.
   *
   * NOT BOUND TO A TASK, bound to the GAP. `ChartModelImpl.getChartItemWithCoordinates` asks for
   * this object by style before it asks for anything else, and hands it to the tooltip. Binding it
   * to a task would be wrong twice over: there is no single task, and the tasks are exactly what
   * the view was asked to leave out.
   */
  private void renderHiddenTaskGaps(OffsetList defaultUnitOffsets) {
    List<HiddenTaskGap> gaps = input.getHiddenTaskGaps();
    if (gaps.isEmpty()) {
      return;
    }
    OffsetLookup lookup = new OffsetLookup();
    for (HiddenTaskGap gap : gaps) {
      int[] bounds = lookup.getBounds(gap.getStart(), gap.getEndExclusive(), defaultUnitOffsets);
      HiddenGapMark mark = HiddenTaskGapKt.hiddenGapMark(gap, bounds[0], bounds[1], getRowHeight());
      Rectangle rectangle = getPrimitiveContainer().createRectangle(
          mark.getLeftX(), mark.getTopY(), mark.getWidth(), mark.getHeight());
      rectangle.setStyle(HiddenTaskGapKt.STYLE_HIDDEN_GAP);
      getPrimitiveContainer().bind(rectangle, gap);
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
   * [Fork change] Draws the band underneath the task bar -- depending on the selected view the
   * date comparison, the effort comparison or the duration comparison. See {@link ChartComparison}
   * for why there is more than one of them.
   *
   * EXACTLY ONE BAND IS EVER DRAWN. The dropdown in the chart toolbar says which, and it always
   * names the view currently on screen.
   */
  private void renderComparisonBand(ITaskSceneTask t, int rowNum, OffsetList defaultUnitOffsets) {
    switch (input.getComparison()) {
      case EFFORT:
        renderEffortBand(t, rowNum, defaultUnitOffsets);
        break;
      case DURATIONS:
        renderDurationBand(t, rowNum, defaultUnitOffsets);
        break;
      case DATES_AND_DURATIONS:
        renderBothBands(t, rowNum, defaultUnitOffsets);
        break;
      default:
        renderBaseline(t, rowNum, defaultUnitOffsets);
        break;
    }
  }

  /**
   * [Fork change] The duration band: today's length against the length in the baseline.
   *
   * WHY THIS BAND HANGS OFF TODAY'S START AND NOT OFF THE PLANNED ONE, unlike
   * {@link #renderBaseline}. This is deliberate and it is not an oversight -- if the two views
   * ever get the same anchor, the test
   * `a merely shifted task -- the dates view reports a deviation, the durations view does not`
   * in `ChartComparisonTest` fails on purpose.
   *
   * The dates view asks "am I on schedule". Its band therefore lies where the task was PLANNED to
   * lie: it starts at the baseline's start and ends at the baseline's end, and the overhang shows
   * the whole displacement between plan and reality.
   *
   * The durations view asks something else: "does the work still take as long as it was planned
   * to". A shift is not part of that answer. So this band starts flush with the task bar and is
   * as long as the task was planned to be -- the overhang is then EXCLUSIVELY the difference in
   * length. A task that starts a week later but still runs five days shows a band exactly as long
   * as its bar: no overhang, no colour, nothing to see. That is the point of the view.
   *
   * Each view thus has precisely one anchor, and it is the one its question needs. Which of the
   * two is on screen is never in doubt: the dropdown in the toolbar names it, one entry apart.
   *
   * WITHOUT A BASELINE it draws a NEUTRAL band under the bar rather than staying empty, because
   * empty would be indistinguishable from "no deviation". See {@link ChartComparisonKt#compareDurations}.
   */
  private void renderDurationBand(ITaskSceneTask t, int rowNum, OffsetList defaultUnitOffsets) {
    List<ITaskActivity<ITaskSceneTask>> activities = t.getActivities();
    if (activities.isEmpty()) {
      return;
    }
    Integer baselineDuration = findBaselineDuration(t);
    ComparisonResult result = ChartComparisonKt.compareDurations(
        baselineDuration, t.getDuration().getLength());
    if (result == ComparisonResult.NO_BAND) {
      return;
    }
    if (baselineDuration == null) {
      // No yardstick. The band lies under the bar and carries no colour -- the same device the
      // effort view uses for "estimated but nothing recorded yet".
      paintBand(rowNum, defaultUnitOffsets, mySplitter.split(activities, Integer.MAX_VALUE),
          result, t.isMilestone(), ComparisonAxis.DURATIONS, false);
      return;
    }
    // The bar's own start, not a model value: the band has to be flush with what is actually
    // drawn, and that is where the first activity begins.
    Date startDate = activities.get(0).getStart();
    Date endDate = input.getCalendar().shiftDate(startDate, input.createLength(baselineDuration));
    List<ITaskActivity<ITaskSceneTask>> bandActivities = new ArrayList<ITaskActivity<ITaskSceneTask>>();
    if (t.isMilestone()) {
      bandActivities.add(new TaskSceneMilestoneActivity(t, startDate, endDate, input.createLength(1)));
    } else {
      TaskActivitiesSceneAlgorithm alg = new TaskActivitiesSceneAlgorithm(
        input.getCalendar(),
        (Date s, Date e) -> input.createLength(t.getDuration().getTimeUnit(), s, e)
      );
      alg.recalculateActivities(t, bandActivities, startDate, endDate);
    }
    paintBand(rowNum, defaultUnitOffsets, bandActivities, result, t.isMilestone(),
        ComparisonAxis.DURATIONS, false);
  }

  /** [Fork change] The task's duration in the baseline, or null if it is not in one. */
  private Integer findBaselineDuration(ITaskSceneTask t) {
    GanttPreviousStateTask taskBaseline = findBaselineTask(t);
    return taskBaseline == null ? null : taskBaseline.getDuration();
  }

  /** [Fork change] The task's entry in the selected baseline, or null if it has none. */
  private GanttPreviousStateTask findBaselineTask(ITaskSceneTask t) {
    List<GanttPreviousStateTask> baseline = input.getBaseline();
    if (baseline == null) {
      return null;
    }
    for (GanttPreviousStateTask taskBaseline : baseline) {
      if (taskBaseline.getId() == t.getRowId()) {
        return taskBaseline;
      }
    }
    return null;
  }

  /** [Fork change] The activities a band covers between two dates -- a milestone is one rhombus. */
  private List<ITaskActivity<ITaskSceneTask>> bandActivities(ITaskSceneTask t, Date startDate, Date endDate) {
    List<ITaskActivity<ITaskSceneTask>> result = new ArrayList<ITaskActivity<ITaskSceneTask>>();
    if (t.isMilestone()) {
      result.add(new TaskSceneMilestoneActivity(t, startDate, endDate, input.createLength(1)));
    } else {
      new TaskActivitiesSceneAlgorithm(
        input.getCalendar(),
        (Date s, Date e) -> input.createLength(t.getDuration().getTimeUnit(), s, e)
      ).recalculateActivities(t, result, startDate, endDate);
    }
    return result;
  }

  /**
   * [Fork change] Both questions in one row: the date comparison in the upper half of the band,
   * the duration comparison in the lower one.
   *
   * EACH HALF KEEPS THE ANCHOR OF ITS OWN VIEW. The date half begins where the task was PLANNED
   * to begin, the duration half where it begins TODAY -- and both are as long as the task was
   * planned to be. That is why this is a fourth view and not a replacement: a single band could
   * only have one anchor.
   *
   * THE CONSEQUENCE, measured on 26 August 2026: the two halves are NOT one rectangle split in
   * two. They are congruent and offset by exactly the shift, and in three of five measured cases
   * they did not overlap at all -- in the widest, 476 pixels apart. "Half" describes where a
   * strip sits in the row, not that it is half of something visible.
   *
   * WHICH HALF A LONE STRIP BELONGS TO IS NEVERTHELESS DECIDABLE, and this follows from the two
   * rules rather than from the drawing:
   *
   *   a lone DURATION strip is anchored at today's start, so it is FLUSH with the bar;
   *   a lone DATE strip means equal lengths and different ends, which forces different starts,
   *   so it can NEVER be flush with the bar.
   *
   * `ChartComparisonTest` pins that. Whoever gives the two halves the same anchor breaks it.
   *
   * A HALF WITH NOTHING TO SAY IS NOT DRAWN AT ALL. Drawing it in some fourth colour would need
   * a fourth colour; leaving a hole would read as a gap. A strip that occupies only one of the
   * two tracks is itself the statement.
   */
  private void renderBothBands(ITaskSceneTask t, int rowNum, OffsetList defaultUnitOffsets) {
    List<ITaskActivity<ITaskSceneTask>> activities = t.getActivities();
    if (activities.isEmpty()) {
      return;
    }
    GanttPreviousStateTask taskBaseline = findBaselineTask(t);
    Integer baselineDuration = taskBaseline == null ? null : taskBaseline.getDuration();
    Date baselineStart = taskBaseline == null ? null : taskBaseline.getStart().getTime();
    Date baselineEnd = taskBaseline == null ? null
        : input.getCalendar().shiftDate(baselineStart, input.createLength(baselineDuration));
    BothComparisons both = ChartComparisonKt.compareBoth(
        baselineEnd, t.getEnd().getTime(), baselineDuration, t.getDuration().getLength());

    if (taskBaseline == null) {
      // No yardstick for EITHER question. Both halves neutral, which together look exactly like
      // the full grey band the durations view draws in the same situation -- saying it once per
      // question is honest, and it keeps the row height rule the same for both views.
      List<ITaskActivity<ITaskSceneTask>> bar = mySplitter.split(activities, Integer.MAX_VALUE);
      paintBand(rowNum, defaultUnitOffsets, bar, both.getDates(), t.isMilestone(),
          ComparisonAxis.DATES, true);
      paintBand(rowNum, defaultUnitOffsets, bar, both.getDurations(), t.isMilestone(),
          ComparisonAxis.DURATIONS, true);
      return;
    }

    // A SUMMARY TASK KEEPS THE DATE AXIS AND LOSES THE DURATION ONE, decided on 26 August 2026.
    // Its band is three pixels high (see StyledPainterImpl, the "super" branch) and cannot be
    // split; and for a package of work the question that matters is whether it is on schedule,
    // not whether it grew. It is drawn at full height, exactly as in the dates view.
    if (t.getHasNestedTasks()) {
      if (both.getDates() != ComparisonResult.NO_BAND) {
        paintBand(rowNum, defaultUnitOffsets, bandActivities(t, baselineStart, baselineEnd),
            both.getDates(), t.isMilestone(), ComparisonAxis.DATES, false);
      }
      return;
    }

    if (both.getDates() != ComparisonResult.NO_BAND) {
      paintBand(rowNum, defaultUnitOffsets, bandActivities(t, baselineStart, baselineEnd),
          both.getDates(), t.isMilestone(), ComparisonAxis.DATES, true);
    }
    if (both.getDurations() != ComparisonResult.NO_BAND) {
      Date startDate = activities.get(0).getStart();
      Date endDate = input.getCalendar().shiftDate(startDate, input.createLength(baselineDuration));
      paintBand(rowNum, defaultUnitOffsets, bandActivities(t, startDate, endDate),
          both.getDurations(), t.isMilestone(), ComparisonAxis.DURATIONS, true);
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
        result, t.isMilestone(), ComparisonAxis.EFFORT, false);
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
      paintBand(rowNum, defaultUnitOffsets, baselineActivities, result, t.isMilestone(),
          ComparisonAxis.DATES, false);
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
                         ComparisonResult result, boolean isMilestone,
                         ComparisonAxis axis, boolean halfHeight) {
    List<Polygon> bandRectangles = myBaselineActivityRenderer.renderActivities(rowNum, activities,
        defaultUnitOffsets);
    String colourStyle = ChartComparisonKt.styleName(result);
    for (int i = 0; i < bandRectangles.size(); i++) {
      Polygon r = bandRectangles.get(i);
      r.setStyle("previousStateTask");
      r.addStyle(axisStyle(axis));
      if (halfHeight) {
        // WHICH half follows from the axis and from nothing else -- see renderBothBands.
        r.addStyle(axis == ComparisonAxis.DATES ? "band.upper" : "band.lower");
      }
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

  /**
   * [Fork change] The style that names the axis a band is answering for.
   *
   * The painter reads it for one thing only: the DATE axis gets a lilac ground with the
   * comparison colour hatched over it, so that "here the schedule is speaking" is visible
   * wherever it happens -- in the dates view and in the upper half of the combined one.
   */
  private static String axisStyle(ComparisonAxis axis) {
    switch (axis) {
      case DATES: return "axis.dates";
      case DURATIONS: return "axis.durations";
      default: return "axis.effort";
    }
  }

  private static Predicate<Polygon> REMOVE_SUPERTASK_ENDINGS = shape -> !shape.hasStyle("task.ending");

  private List<Polygon> renderActivities(final int rowNum, ITaskSceneTask t, List<ITaskActivity<ITaskSceneTask>> activities,
      OffsetList defaultUnitOffsets, boolean areVisible) {
    List<Polygon> rectangles = myTaskActivityRenderer.renderActivities(rowNum, activities, defaultUnitOffsets);
    if (areVisible && !myTaskApi.hasNestedTasks(t) && !t.isMilestone() && !t.isProjectTask()) {
      renderAbsenceStripes(t, rectangles, defaultUnitOffsets);
      renderProgressBar(rectangles.stream().filter(REMOVE_SUPERTASK_ENDINGS).toList());
    }
    if (areVisible && myTaskApi.hasNotes(t)) {
      Rectangle notes = getPrimitiveContainer().createRectangle(input.getWidth() - 24, rowNum * getRowHeight() + getRowHeight()/2 - 8, 16, 16);
      notes.setStyle("task.notesMark");
      getPrimitiveContainer().bind(notes, t);
    }
    return rectangles;
  }

  /**
   * [Fork change] THE HOLIDAY STRIPE, one day at a time.
   *
   * Natalie: „wenn jemand an Tag 3 von 5 fehlt soll nur Tag 3 den streifen haben." So this cannot
   * be a style on the bar -- one bar rectangle is many days -- and it is instead a rectangle of its
   * own per run of absent days, cut to the day columns of the chart.
   *
   * WHERE IT IS DRAWN DECIDES WHAT IT CAN HIDE, and this is the whole reason it sits on the BASE
   * canvas rather than on a layer. `ChartModelBase.paint` paints every renderer's base canvas
   * first and only then layer 0, layer 1, ... Within one canvas the order is the order of
   * creation. Created here, right after the bars of the same row, the stripe therefore lands
   * ON the bar -- which is what it is for, the work is still planned -- and UNDER the progress bar
   * (layer 0) and under the labels (layer 3), which stay readable without anything having to be
   * arranged for it.
   *
   * IT IS NOT BOUND TO A MODEL OBJECT, on purpose. `Canvas.getPrimitive(x, y)` returns the FIRST
   * rectangle covering a point, and the bar was created before the stripe, so clicking and
   * dragging a task still finds the bar. An unbound rectangle also never reaches
   * `TaskRendererImpl2.getTaskRectangles`.
   *
   * ONLY WHERE THERE IS WORK: an activity of intensity 0 is a stretch the plan already draws as
   * free -- a weekend, a public holiday -- and it is drawn faint. Marking somebody absent on a day
   * on which nobody was going to work says nothing and would put a stripe where Natalie counts no
   * day at all („Tag 3 von 5" counts working days). Milestones, summary tasks and the project task
   * are left out by the caller for the same reason the progress bar is: they carry no work of
   * their own.
   */
  private void renderAbsenceStripes(ITaskSceneTask t, List<Polygon> barRectangles, OffsetList offsets) {
    List<AbsenceRun> runs = input.getAbsenceRuns(t.getRowId());
    if (runs.isEmpty()) {
      return;
    }
    int deltaY = canvasDeltaY();
    for (Polygon bar : barRectangles) {
      if (!(bar instanceof Rectangle) || !(bar.getModelObject() instanceof ITaskActivity)) {
        continue;
      }
      ITaskActivity<?> activity = (ITaskActivity<?>) bar.getModelObject();
      if (activity.getIntensity() == 0f) {
        continue;
      }
      Rectangle barRect = (Rectangle) bar;
      List<StripeSpan> spans = AbsenceStripeKt.absenceStripeSpans(
          runs, activity.getStart(), activity.getEnd(),
          barRect.getLeftX(), barRect.getRightX(), offsets);
      for (StripeSpan span : spans) {
        Rectangle stripe = getPrimitiveContainer().createRectangle(
            span.getLeftX(), barRect.getTopY() - deltaY, span.getWidth(), barRect.getHeight());
        stripe.setStyle(AbsenceStripeKt.STYLE_ABSENCE);
      }
    }
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
