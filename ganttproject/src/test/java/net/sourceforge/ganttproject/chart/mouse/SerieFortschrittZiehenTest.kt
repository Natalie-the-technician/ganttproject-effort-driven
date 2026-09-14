/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.

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
package net.sourceforge.ganttproject.chart.mouse

import biz.ganttproject.core.calendar.AlwaysWorkingTimeCalendarImpl
import biz.ganttproject.core.chart.canvas.Canvas
import biz.ganttproject.core.option.DefaultFontOption
import biz.ganttproject.core.option.DefaultIntegerOption
import biz.ganttproject.core.option.FontSpec
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.core.time.impl.GPTimeUnitStack
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.chart.ChartModelImpl
import net.sourceforge.ganttproject.chart.TaskChartModelFacade
import net.sourceforge.ganttproject.chart.TaskRendererImpl2
import net.sourceforge.ganttproject.fork.findOrCreateRecurrenceOf
import net.sourceforge.ganttproject.fork.recurrenceGroupMark
import net.sourceforge.ganttproject.fork.recurrenceMark
import net.sourceforge.ganttproject.fork.toModelDate
import net.sourceforge.ganttproject.gui.UIConfiguration
import net.sourceforge.ganttproject.task.Task
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Dimension
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * ═══ EINE ZEILE OHNE EIGENES RECHTECK BRINGT DEN FORTSCHRITTSZIEHER ZUM ABSTURZ ═══
 *
 * S7 of the measurement report of 11.09.2026, and it is not a precaution — it is a
 * `NullPointerException` with a path to it that is written down.
 *
 * [TaskRendererImpl2.getTaskRectangles] rebuilds a task's activities and looks each one up among
 * the shapes that were actually DRAWN. With the series switch on and the group collapsed, the row
 * of that group draws the bars of its DATES; nothing is drawn for the group's own activities. The
 * lookup returns `null` for every one of them, and the old code put those nulls into the list:
 *
 *     Canvas.Shape graphicPrimitive = chartModel.getGraphicPrimitive(activity);
 *     assert graphicPrimitive != null : "Got null for activity=" + activity;
 *     result.add((Rectangle) graphicPrimitive);
 *
 * The two `assert`s beside it only bite with `-ea`, which is the default of a Gradle test JVM and
 * NOT the default of the running program. What the program gets is a list full of nulls, and the
 * very first consumer of that list dereferences element 0 without asking:
 *
 *     GanttChartController:125  ->  ChangeTaskProgressRuler:57-58
 *
 * ═══ WHY THIS FILE LIVES IN `chart.mouse` AND NOT BESIDE THE OTHER SERIES CHECKS ═══
 *
 * Because [ChangeTaskProgressRuler] is package private, and this check is worth nothing if it
 * reaches the crash by a shorter road than the program does. The facade handed in below is the
 * very same lambda `GanttChartController` builds at line 125:
 * `t -> TaskRendererImpl2.getTaskRectangles(t, myChartModel)`.
 */
class SerieFortschrittZiehenTest {

  init {
    // GanttCalendar needs this; without it every check here dies in CalendarFactory with a
    // NullPointerException that has nothing to do with what is being checked.
    object : CalendarFactory() {
      init {
        setLocaleApi(object : CalendarFactory.LocaleApi {
          override fun getLocale(): Locale = Locale.GERMANY
          override fun getShortDateFormat(): DateFormat =
            DateFormat.getDateInstance(DateFormat.SHORT, Locale.GERMANY)
        })
      }
    }
  }

  private val TAGBREITE = 20
  private val ZEILENHOEHE = 20

  /** Monday, 7 September 2026. */
  private val montag = LocalDate.of(2026, 9, 7)
  private val termine = listOf(montag, montag.plusDays(7), montag.plusDays(14))

  /**
   * A plan with a real chart over it. Same shape as the one in `SerieEineReiheTest`, and kept
   * separate rather than shared because that one is `private` to its own package and lifting it out
   * would make two files move whenever either check needs a different chart.
   */
  private inner class Diagramm {
    val taskManager = TestSetupHelper.newTaskManagerBuilder()
      .withCalendar(AlwaysWorkingTimeCalendarImpl()).build()
    val chartModel: ChartModelImpl
    val renderer: TaskRendererImpl2

    init {
      val projectConfig = UIConfiguration(Color.BLUE, false)
      projectConfig.chartFontOption =
        DefaultFontOption("foo", FontSpec("Foo", FontSpec.Size.NORMAL), emptyList())
      projectConfig.dpiOption = DefaultIntegerOption("bar", 96)
      chartModel = ChartModelImpl(taskManager, GPTimeUnitStack(), projectConfig)
      chartModel.setBounds(Dimension(1600, 400))
      chartModel.setTopTimeUnit(GPTimeUnitStack.WEEK)
      chartModel.setBottomTimeUnit(GPTimeUnitStack.DAY)
      chartModel.setBottomUnitWidth(TAGBREITE)
      chartModel.setRowHeight(ZEILENHOEHE)
      chartModel.startDate = montag.minusDays(7).toModelDate()
      renderer = TaskRendererImpl2(chartModel)
      // MUST BE REGISTERED, and leaving it out cost this file its first red run -- on all four
      // checks at once, which is the sign of a broken check rather than of broken code.
      // `ChartModelImpl.getGraphicPrimitive` walks the model's OWN renderer list; a renderer that
      // merely holds the chart model is not in it, so every lookup came back null and the assert
      // inside `getTaskRectangles` fired even for an ordinary task. `TaskRendererImplTest:208`
      // does the same thing for the same reason.
      chartModel.addRenderer(renderer)
    }

    fun vorgang(name: String, start: LocalDate, tage: Int = 1, elternteil: Task? = null): Task =
      taskManager.newTaskBuilder()
        .withName(name)
        .withStartDate(start.toModelDate())
        .withDuration(taskManager.createLength(tage.toLong()))
        .let { if (elternteil == null) it else it.withParent(elternteil) }
        .build()

    /** A collapsed recurrence group with one child per date, markers exactly as RecurrenceAdapter writes them. */
    fun serie(name: String, daten: List<LocalDate>): Pair<Task, List<Task>> {
      val props = taskManager.customPropertyManager
      val quelleId = 4711
      val gruppe = vorgang(name, daten.first())
      val kinder = daten.map { datum ->
        vorgang(name, datum, 1, gruppe).also {
          it.customValues.setValue(findOrCreateRecurrenceOf(props), recurrenceMark(quelleId, datum))
        }
      }
      gruppe.customValues.setValue(findOrCreateRecurrenceOf(props), recurrenceGroupMark(quelleId))
      taskManager.algorithmCollection.adjustTaskBoundsAlgorithm.run(gruppe)
      gruppe.expand = false
      return gruppe to kinder
    }

    fun zeichnen(sichtbar: List<Task>) {
      chartModel.setVisibleTasks(sichtbar)
      renderer.render()
    }

    fun schalterAn() { renderer.seriesOneRowOption.value = true }

    /** THE VERY LAMBDA of `GanttChartController:125`, and the reason this check is worth anything. */
    fun fassade(): TaskChartModelFacade =
      TaskChartModelFacade { t -> TaskRendererImpl2.getTaskRectangles(t, chartModel) }
  }

  // =============================================================================================
  // 1. The crash itself.
  // =============================================================================================

  /**
   * THE CHECK S7 EXISTS FOR. Dragging the progress of the collapsed group must not take the program
   * down, and the ruler must come out of it able to answer.
   *
   * SEEN RED FIRST, on the code as the previous session left it: with `-ea` the run died inside
   * `getTaskRectangles` on `assert graphicPrimitive != null`; with assertions off — the way the
   * program actually runs — it died one frame further out, at `ChangeTaskProgressRuler:58`, on
   * `taskRectangles.get(0).getLeftX()`. Both verbatim in the report of 14.09.2026.
   */
  @Test
  fun `der fortschrittszieher ueberlebt eine gruppe ohne eigenes rechteck`() {
    val d = Diagramm()
    val (gruppe, _) = d.serie("Umsatzsteuervoranmeldung", termine)
    d.schalterAn()
    d.zeichnen(listOf(gruppe))

    val rechtecke: List<Canvas.Rectangle?> = TaskRendererImpl2.getTaskRectangles(gruppe, d.chartModel)
    assertFalse(rechtecke.contains(null),
      "kein Nachschlag darf als null in der Liste landen: $rechtecke")

    // The road of GanttChartController:125. Constructing the ruler is what crashed.
    val zieher = ChangeTaskProgressRuler(gruppe, d.fassade())
    val fortschritt = zieher.getProgress(500)
    assertEquals(gruppe.completionPercentage, fortschritt.toPercents(),
      "ohne einen einzigen Balken auf dem Schirm gibt es keine Pixelskala -- " +
        "der Fortschritt darf durch das Ziehen nicht verstellt werden")
  }

  /**
   * AND THE OTHER HALF OF THE SAME QUESTION: the bars on that row DO belong to somebody, and for
   * them the lookup has to keep working. A date of the series must still find its own rectangle,
   * otherwise the fix above would have bought its safety by making the feature unusable.
   *
   * This is what the mouse actually reaches once S4 draws progress bars on that row: the progress
   * rectangle is bound to the DATE (`renderProgressBar` binds to the owner of the first bar), so
   * the chart item under the pointer names the date and not the group.
   */
  @Test
  fun `ein termin der serie findet sein eigenes rechteck`() {
    val d = Diagramm()
    val (gruppe, kinder) = d.serie("Umsatzsteuervoranmeldung", termine)
    d.schalterAn()
    d.zeichnen(listOf(gruppe))

    kinder.forEach { kind ->
      val rechtecke = TaskRendererImpl2.getTaskRectangles(kind, d.chartModel)
      assertEquals(1, rechtecke.size,
        "der Termin ${kind.taskID} ist auf der Zeile der Gruppe gezeichnet: $rechtecke")
      assertTrue(rechtecke[0].width > 0, "und er hat eine Breite: ${rechtecke[0]}")
    }
    // The ruler over a date works, and its scale starts at that date's own bar.
    val zieher = ChangeTaskProgressRuler(kinder[1], d.fassade())
    val links = TaskRendererImpl2.getTaskRectangles(kinder[1], d.chartModel)[0].leftX
    assertEquals(0, zieher.getProgress(links).toPercents(),
      "am linken Rand des eigenen Balkens sind es null Prozent")
    assertEquals(100, zieher.getProgress(links + 5 * TAGBREITE).toPercents(),
      "weit rechts davon sind es hundert")
  }

  /**
   * THE SWITCHED-OFF CASE, which is the one every other user of `getTaskRectangles` lives in:
   * nothing about the guard may change what an ordinary task returns.
   */
  @Test
  fun `ohne den schalter gibt die gruppe ihre eigenen rechtecke wie bisher`() {
    val d = Diagramm()
    val (gruppe, _) = d.serie("Umsatzsteuervoranmeldung", termine)
    d.zeichnen(listOf(gruppe))

    val rechtecke = TaskRendererImpl2.getTaskRectangles(gruppe, d.chartModel)
    assertEquals(1, rechtecke.size, "der eine Spannbalken der Gruppe: $rechtecke")
    assertEquals(15 * TAGBREITE, rechtecke[0].width,
      "fuenfzehn Tage breit -- der Fehler, den der Schalter behebt: ${rechtecke[0]}")

    val zieher = ChangeTaskProgressRuler(gruppe, d.fassade())
    assertEquals(0, zieher.getProgress(rechtecke[0].leftX).toPercents())
    assertEquals(100, zieher.getProgress(rechtecke[0].rightX).toPercents())
  }

  /**
   * A shape that is not a rectangle must not reach the list either. The cast in the old code was
   * guarded by an `assert` and nothing else; a milestone rhombus is bound to the canvas exactly as
   * a bar is, and `Canvas.getPrimitive(Object)` hands back whatever was bound.
   */
  @Test
  fun `ein meilenstein liefert keine rechtecke statt einer ClassCastException`() {
    val d = Diagramm()
    val stein = d.taskManager.newTaskBuilder()
      .withName("Abgabe")
      .withStartDate(montag.toModelDate())
      .withLegacyMilestone()
      .build()
    d.zeichnen(listOf(stein))

    val rechtecke: List<Canvas.Rectangle?> = TaskRendererImpl2.getTaskRectangles(stein, d.chartModel)
    assertFalse(rechtecke.contains(null), "kein null: $rechtecke")
  }
}
