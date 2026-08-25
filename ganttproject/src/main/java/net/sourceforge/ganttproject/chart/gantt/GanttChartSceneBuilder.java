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
import net.sourceforge.ganttproject.fork.Vergleichsbefund;
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

    /** [Fork-Aenderung] Was das Band unter dem Balken vergleicht. Siehe {@link ChartComparison}. */
    ChartComparison getComparison();

    /**
     * [Fork-Aenderung] Die urspruengliche Aufwandsschaetzung des Vorgangs in Stunden, oder null.
     * Ueber die Zeilennummer statt ueber den Vorgang, weil ITaskSceneTask bewusst nichts von
     * Sonderspalten weiss.
     */
    Double getOriginalEffortHours(int rowId);

    /** [Fork-Aenderung] Die erfassten Ist-Stunden des Vorgangs, oder null. */
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
   * [Fork-Aenderung] Zeichnet das Band unter dem Vorgangsbalken -- je nach gewaehlter Ansicht
   * den Basisplanvergleich (Termin) oder den Aufwandsvergleich. Siehe {@link ChartComparison}
   * fuer die Begruendung, warum es zwei sind und nicht eine.
   */
  private void renderComparisonBand(ITaskSceneTask t, int rowNum, OffsetList defaultUnitOffsets) {
    if (input.getComparison() == ChartComparison.AUFWAND) {
      renderEffortBand(t, rowNum, defaultUnitOffsets);
    } else {
      renderBaseline(t, rowNum, defaultUnitOffsets);
    }
  }

  /**
   * [Fork-Aenderung] Das Aufwandsband: erfasste Ist-Stunden gegen die urspruengliche Schaetzung.
   *
   * Es braucht KEINEN Basisplan -- beide Zahlen stehen am Vorgang selbst. Das Band liegt deshalb
   * genau unter dem Vorgangsbalken und ist genauso lang wie er; die Aussage steckt allein in der
   * Farbe. Eine eigene Laenge waere eine zweite Aussage und wuerde nur verwirren: wie lange ein
   * Vorgang im Kalender dauert, hat mit den gebrauchten Stunden nichts zu tun -- genau das ist
   * die Trennung, wegen der es diese Ansicht gibt.
   */
  private void renderEffortBand(ITaskSceneTask t, int rowNum, OffsetList defaultUnitOffsets) {
    Vergleichsbefund befund = ChartComparisonKt.aufwandVergleich(
        input.getOriginalEffortHours(t.getRowId()),
        input.getActualEffortHours(t.getRowId()));
    if (befund == Vergleichsbefund.KEIN_BAND) {
      return;
    }
    List<ITaskActivity<ITaskSceneTask>> activities = t.getActivities();
    if (activities.isEmpty()) {
      return;
    }
    paintBand(rowNum, defaultUnitOffsets, mySplitter.split(activities, Integer.MAX_VALUE),
        befund, t.isMilestone());
  }

  /**
   * Das Terminband: das Ende von heute gegen das Ende im Basisplan.
   *
   * [Fork-Aenderung] ---- Anfang ----
   *
   * DIESE METHODE HATTE ZWISCHENZEITLICH EINE ANDERE REGEL. Vom 17.08. bis zum 20.08.2026
   * verglich sie die DAUERN statt der Enddaten, weil die Beschriftung im Basisplan-Dialog von
   * der Dauer spricht und ein bloss verschobener Vorgang sonst rot wurde.
   *
   * WARUM DAS ZURUECKGENOMMEN IST, gemessen am 20.08.2026: in diesem Fork ist die Dauer eines
   * Vorgangs keine Eingabe, sondern ein Rechenergebnis aus Aufwand und Tagesleistung. An einem
   * echten Plan wurde die Tagesleistung von 8 auf 1,8 Stunden gesetzt -- 163 von 163 Vorgaengen
   * mit Aufwand wurden dadurch um den Faktor 4,44 laenger, 194 von 276 Zeilen wurden rot, und
   * kein einziger Aufwand hatte sich um eine Stunde geaendert. Ein Dauervergleich beantwortet
   * also weder "liege ich im Zeitplan" noch "habe ich mich verschaetzt".
   *
   * Beide Fragen haben jetzt ihre eigene Ansicht. HIER gilt wieder die Regel des Originals --
   * das Ende --, und damit stimmt auch die Beschriftung im Dialog wieder, die vom Fertigwerden
   * spricht.
   *
   * Was bleibt: gezeichnet wird, sobald das Ende ein anderes ist. Gleiches Ende heisst
   * planmaessig und laesst die Zeile leer.
   *
   * [Fork-Aenderung] ---- Ende ----
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
      Vergleichsbefund befund = ChartComparisonKt.terminVergleich(endDate, t.getEnd().getTime());
      if (befund == Vergleichsbefund.KEIN_BAND) {
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
      paintBand(rowNum, defaultUnitOffsets, baselineActivities, befund, t.isMilestone());
      return;
    }
  }

  /**
   * [Fork-Aenderung] Gemeinsames Malen der beiden Baender. Vorher stand dieser Block nur einmal
   * im Basisplanzweig; er ist herausgezogen, damit die Aufwandsansicht nicht dieselbe Stilkette
   * ein zweites Mal beschreiben muss -- zwei Kopien waeren zwei Gelegenheiten, sie
   * auseinanderlaufen zu lassen.
   */
  private void paintBand(int rowNum, OffsetList defaultUnitOffsets,
                         List<ITaskActivity<ITaskSceneTask>> activities,
                         Vergleichsbefund befund, boolean isMilestone) {
    List<Polygon> bandRectangles = myBaselineActivityRenderer.renderActivities(rowNum, activities,
        defaultUnitOffsets);
    String farbstil = ChartComparisonKt.stilName(befund);
    for (int i = 0; i < bandRectangles.size(); i++) {
      Polygon r = bandRectangles.get(i);
      r.setStyle("previousStateTask");
      if (isMilestone) {
        r.addStyle("milestone");
      }
      if (farbstil != null) {
        r.addStyle(farbstil);
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
