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
package net.sourceforge.ganttproject.fork

import biz.ganttproject.core.calendar.AlwaysWorkingTimeCalendarImpl
import biz.ganttproject.core.calendar.WeekendCalendarImpl
import biz.ganttproject.core.calendar.GPCalendarCalc
import biz.ganttproject.core.chart.canvas.Canvas
import biz.ganttproject.core.chart.canvas.Painter
import biz.ganttproject.core.option.DefaultFontOption
import biz.ganttproject.core.option.DefaultIntegerOption
import biz.ganttproject.core.option.FontSpec
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.core.time.impl.GPTimeUnitStack
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.chart.ChartModelImpl
import net.sourceforge.ganttproject.chart.TaskRendererImpl2
import net.sourceforge.ganttproject.chart.gantt.ITaskActivity
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
 * ═══ DREI TERMINE, EINE ZEILE, DREI BALKEN ═══
 *
 * What the collapsed row of a recurrence series draws today is ONE bar across the whole span: a
 * summary task takes its start from the earliest child and its end from the latest, and its
 * activities are then filled across all of it. Twelve days of work spread over a year read as a
 * twelve-month block. These checks are about the one sentence that repairs: THE DATES OF A
 * COLLAPSED SERIES ARE DRAWN WHERE THEY ARE, ON THE GROUP'S OWN ROW.
 *
 * ═══ THROUGH THE REAL CHART, NOT THROUGH A DOUBLE ═══
 *
 * Built like `UrlaubsstreifenTest`, and for the same reason: a real [ChartModelImpl] over a real
 * `TaskManager`, a real [TaskRendererImpl2], and what is asserted is what a `Painter` would be
 * handed. The hard part of this package is not the arithmetic — it is that the row of a chart has
 * always drawn the activities of exactly ONE task, and a check against a hand-built list of
 * activities would prove the list and leave that untested.
 *
 * ═══ WHY THE CHECKS RUN ON A CALENDAR WITHOUT WEEKENDS ═══
 *
 * [AlwaysWorkingTimeCalendarImpl], deliberately, and it is not laziness about dates. A row can
 * carry several rectangles TODAY: `TaskActivitiesAlgorithm` cuts every task at the boundaries of
 * working and non-working time, so a bar across a weekend is three rectangles and one existing
 * check in the original (`TaskRendererImplTest`) pins exactly that. Counting rectangles on a
 * weekend calendar would therefore count two different things at once — the dates of the series
 * and the weekends between them — and "three bars" would stop meaning "three dates". The last
 * check in this file puts the weekends back, to show that nothing here depends on their absence.
 *
 * ═══ WHAT IS DELIBERATELY NOT CHECKED HERE ═══
 *
 * Progress per date, absence stripes per bar, the comparison band, the labels, and the first click
 * expanding the group again. None of those is built yet (S4–S9 of the measurement report of
 * 11.09.2026), which is why the setting this file switches on is OFF by default.
 */
class SerieEineReiheTest {

  init {
    // WeekendCalendarImpl and GanttCalendar both need this; without it every check here dies in
    // CalendarFactory with a NullPointerException. Same bootstrap as UrlaubsstreifenTest.
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

  /** Pixels per day column in the checks below. */
  private val TAGBREITE = 20

  /**
   * Pixels per row.
   *
   * MUST BE SET BY HAND, and finding that out cost this file its first red run: a bare
   * [ChartModelImpl] has a row height of ZERO. Nothing in the model gives it a default -- in the
   * running program `GanttChartController` measures the task table and calls `setRowHeight` before
   * every repaint. At zero every row is drawn at the very same y, which is invisible to a check
   * that only counts rectangles and fatal to one that asks which row they landed in. The exact
   * failure was `oben=39` on all four bars of two different rows. `KollisionsbalkenZeichnerTest`
   * sets a fixed row height for the same reason.
   */
  private val ZEILENHOEHE = 20

  /** Monday, 7 September 2026. Everything in this file counts from here. */
  private val montag = LocalDate.of(2026, 9, 7)

  /** The three dates of the series: one a week apart, so there are real gaps between them. */
  private val termine = listOf(montag, montag.plusDays(7), montag.plusDays(14))

  /** Collects what the canvas would hand to a painter. The real path, not a peek into a field. */
  private class Sammler : Painter {
    val rechtecke = mutableListOf<Rechteck>()
    override fun prePaint() {}
    override fun paint(rectangle: Canvas.Rectangle) {
      if (rectangle.isVisible) {
        rechtecke.add(Rechteck(rectangle.style, rectangle.leftX, rectangle.width,
          rectangle.topY, rectangle.height, rectangle.modelObject))
      }
    }
    override fun paint(line: Canvas.Line) {}
    override fun paint(next: Canvas.Text) {}
    override fun paint(textGroup: Canvas.TextGroup) {}
    override fun paint(rhombus: Canvas.Rhombus) {}
  }

  private data class Rechteck(
    val stil: String?, val links: Int, val breite: Int, val oben: Int, val hoehe: Int,
    val modell: Any?) {

    /** The row id of the task this bar belongs to, or null when it is not a task bar. */
    val besitzerId: Int? get() = (modell as? ITaskActivity<*>)?.owner?.rowId
  }

  /**
   * A plan with a real chart over it.
   *
   * The chart starts a week BEFORE the dates so that a bar cannot accidentally be right merely
   * because it sits at pixel 0.
   */
  private inner class Diagramm(kalender: GPCalendarCalc = AlwaysWorkingTimeCalendarImpl()) {
    val taskManager = TestSetupHelper.newTaskManagerBuilder().withCalendar(kalender).build()
    val props: CustomPropertyManager = taskManager.customPropertyManager
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
      // MUST BE SET, and it is not a taste in zoom -- at 0 the offset builder never reaches the
      // right edge of the viewport and eats the heap. The reason is written out in
      // UrlaubsstreifenTest, where it was measured.
      chartModel.setBottomUnitWidth(TAGBREITE)
      chartModel.setRowHeight(ZEILENHOEHE)
      chartModel.startDate = montag.minusDays(7).toModelDate()
      renderer = TaskRendererImpl2(chartModel)
    }

    /** A task of [tage] days starting on [start]. */
    fun vorgang(name: String, start: LocalDate, tage: Int = 1, elternteil: Task? = null): Task =
      taskManager.newTaskBuilder()
        .withName(name)
        .withStartDate(start.toModelDate())
        .withDuration(taskManager.createLength(tage.toLong()))
        .let { if (elternteil == null) it else it.withParent(elternteil) }
        .build()

    /**
     * A recurrence group with one child per date in [daten], collapsed.
     *
     * THE MARKERS ARE SET BY HAND rather than by running `applyRecurrencesAsSingleEdit`, because
     * this file is about the CHART and not about how a series comes into being -- that is what
     * `RecurrenceModelTest` checks, and it needs a database to do it. What matters here is that
     * the markers are the very same ones `RecurrenceAdapter` writes: [recurrenceGroupMark] for the
     * group and [recurrenceMark] for each date.
     */
    fun serie(name: String, daten: List<LocalDate>): Pair<Task, List<Task>> {
      val quelleId = 4711
      val gruppe = vorgang(name, daten.first())
      val kinder = daten.map { datum ->
        vorgang(name, datum, 1, gruppe).also {
          it.customValues.setValue(findOrCreateRecurrenceOf(props), recurrenceMark(quelleId, datum))
        }
      }
      gruppe.customValues.setValue(findOrCreateRecurrenceOf(props), recurrenceGroupMark(quelleId))
      anpassen(gruppe)
      gruppe.expand = false
      return gruppe to kinder
    }

    /**
     * Renders exactly what the screen would render and returns every rectangle.
     *
     * SAFE TO CALL TWICE: `GanttChartSceneBuilder.render` empties the canvas and its three layers
     * before it draws, so a second render replaces the first picture rather than adding to it.
     * Checked, not assumed -- two checks here render twice on purpose, once with the switch off and
     * once on, and they count what the second run drew.
     */
    fun rechtecke(sichtbar: List<Task>): List<Rechteck> {
      chartModel.setVisibleTasks(sichtbar)
      renderer.render()
      val sammler = Sammler()
      renderer.primitiveContainer.paint(sammler)
      renderer.primitiveContainer.layers.forEach { it.paint(sammler) }
      return sammler.rechtecke
    }

    /** Only the task bars: the rectangles a row draws for work, whoever they belong to. */
    fun balken(sichtbar: List<Task>): List<Rechteck> =
      rechtecke(sichtbar).filter { it.stil?.startsWith("task") == true && it.besitzerId != null }

    fun zeilenhoehe(): Int = chartModel.chartUIConfiguration.rowHeight

    fun schalterAn() { renderer.seriesOneRowOption.value = true }

    /** Makes a summary task take the span of its children, the way the running program does. */
    fun anpassen(t: Task) {
      taskManager.algorithmCollection.adjustTaskBoundsAlgorithm.run(t)
    }
  }

  // =============================================================================================
  // 1. The check the package exists for.
  // =============================================================================================

  /**
   * THE SENTENCE, AS A CHECK. Three dates, one collapsed group, one row — and three separate bars
   * on it, one per date, with the same top edge.
   *
   * THE TOP EDGE IS ASSERTED AND NOT ONLY THE COUNT. "Three rectangles" alone would also pass for
   * an implementation that put each date on a row of its own, which is exactly the picture this is
   * meant to replace.
   */
  @Test
  fun `drei termine ergeben drei balken auf derselben zeile`() {
    val d = Diagramm()
    val (gruppe, kinder) = d.serie("Umsatzsteuervoranmeldung", termine)
    d.schalterAn()

    val balken = d.balken(listOf(gruppe))
    assertEquals(3, balken.size, "drei Termine, drei Balken -- gezeichnet wurde: $balken")
    assertEquals(1, balken.map { it.oben }.distinct().size,
      "alle drei muessen dieselbe Oberkante haben: $balken")
    assertEquals(kinder.map { it.taskID }.toSet(), balken.mapNotNull { it.besitzerId }.toSet(),
      "jeder Balken gehoert seinem eigenen Termin, nicht der Gruppe (Gruppe=${gruppe.taskID})")
  }

  /**
   * THE ROW IS THE GROUP'S ROW, `rowNum * getRowHeight()`. A plain task above the series pushes the
   * group into row 1, and all three of its bars have to sit EXACTLY one row height below that
   * task's bar.
   *
   * Without this the check above would also pass if the bars were drawn on the first row of the
   * chart regardless of which row the group occupies.
   *
   * THE DISTANCE IS MEASURED, NOT THE ABSOLUTE Y, and that is the second thing this file had to
   * learn the red way. The whole canvas is shifted down by the chart header --
   * `GanttChartSceneBuilder.render` sets an offset of `headerHeight - verticalOffset` -- so row 0
   * does not start at zero. The first version of this check asked for `topY < rowHeight` and found
   * the bars at 49 and 69 with a row height of 20: correctly drawn, one row apart, and outside the
   * band the check had made up. A row's distance from the row above it is what
   * `rowNum * getRowHeight()` actually promises; where the first row begins is the header's business.
   */
  @Test
  fun `die drei balken liegen eine zeile unter dem vorgang darueber`() {
    val d = Diagramm()
    val davor = d.vorgang("etwas anderes", montag, 3)
    val (gruppe, _) = d.serie("Umsatzsteuervoranmeldung", termine)
    d.schalterAn()

    val alle = d.balken(listOf(davor, gruppe))
    assertEquals(4, alle.size, "ein fremder Balken plus drei Termine: $alle")

    val obenDavor = alle.single { it.besitzerId == davor.taskID }.oben
    val serie = alle.filter { it.besitzerId != davor.taskID }
    assertEquals(3, serie.size, "die drei Termine: $alle")
    assertEquals(listOf(obenDavor + d.zeilenhoehe()), serie.map { it.oben }.distinct(),
      "alle drei genau eine Zeilenhoehe (${d.zeilenhoehe()}) unter dem Vorgang darueber ($obenDavor): $alle")
    assertEquals(0, serie.count { it.besitzerId == gruppe.taskID },
      "und keiner davon gehoert der Gruppe selbst: $serie")
  }

  // =============================================================================================
  // 2. The switch. Off is the default, and off has to be the old picture exactly.
  // =============================================================================================

  /**
   * WITH THE SWITCH OFF: ONE BAR, and it is the group's own. That single bar is also the thing the
   * package is against — it runs across the whole span, three weeks wide, for three days of work.
   * Both halves are asserted here, so the check says what is wrong as well as what is unchanged.
   */
  @Test
  fun `bei ausgeschaltetem schalter bleibt es der eine gruppenbalken`() {
    val d = Diagramm()
    val (gruppe, _) = d.serie("Umsatzsteuervoranmeldung", termine)

    val balken = d.balken(listOf(gruppe))
    assertEquals(1, balken.size, "unveraendert genau ein Balken: $balken")
    assertEquals(gruppe.taskID, balken.single().besitzerId, "und er gehoert der Gruppe")
    assertEquals(15 * TAGBREITE, balken.single().breite,
      "und er laeuft ueber die ganze Spanne -- genau das ist der Fehler, den der Schalter behebt")
  }

  /** The switch is off unless somebody turns it on. The whole division of work rests on this. */
  @Test
  fun `der schalter ist per vorgabe aus`() {
    val d = Diagramm()
    assertFalse(d.renderer.seriesOneRowOption.value,
      "die Vorgabe MUSS aus sein -- sonst aendert sich Natalies Diagramm, bevor S4 bis S9 gebaut sind")
  }

  // =============================================================================================
  // 3. What must NOT change. Three ways for the new drawing to reach a row it has no business on.
  // =============================================================================================

  /**
   * A GROUP WITHOUT THE MARKER KEEPS ITS BAR, switch or no switch. An ordinary summary task is the
   * commonest thing in a plan; if the new drawing reached it, every plan would change.
   */
  @Test
  fun `eine gewoehnliche sammelgruppe bleibt ein balken`() {
    val d = Diagramm()
    val gruppe = d.vorgang("gewoehnliche Gruppe", montag)
    termine.forEach { d.vorgang("Kind", it, 1, gruppe) }
    d.anpassen(gruppe)
    gruppe.expand = false
    d.schalterAn()

    val balken = d.balken(listOf(gruppe))
    assertEquals(1, balken.size, "ohne die Marke recurrence_of=...@Serie aendert sich nichts: $balken")
    assertEquals(gruppe.taskID, balken.single().besitzerId)
  }

  /**
   * AN EXPANDED GROUP KEEPS ITS BAR. Expanded, each date is a row of its own; drawing them on the
   * group's row as well would draw every one of them twice.
   */
  @Test
  fun `eine aufgeklappte serie zeichnet ihre termine nicht doppelt`() {
    val d = Diagramm()
    val (gruppe, kinder) = d.serie("Umsatzsteuervoranmeldung", termine)
    gruppe.expand = true
    d.schalterAn()

    val balken = d.balken(listOf(gruppe) + kinder)
    assertEquals(4, balken.size, "ein Gruppenbalken plus drei Termine, jeder genau einmal: $balken")
    assertEquals(1, balken.count { it.besitzerId == gruppe.taskID }, "die Gruppe genau einmal")
    kinder.forEach { kind ->
      assertEquals(1, balken.count { it.besitzerId == kind.taskID },
        "Termin ${kind.taskID} genau einmal")
    }
  }

  /**
   * A DATE OF A SERIES IS STILL AN ORDINARY ROW. The occurrence marker is not the group marker, and
   * a single date must never start drawing anything but itself.
   */
  @Test
  fun `ein einzelner termin bleibt ein gewoehnlicher balken`() {
    val d = Diagramm()
    val (_, kinder) = d.serie("Umsatzsteuervoranmeldung", termine)
    d.schalterAn()

    val balken = d.balken(listOf(kinder.first()))
    assertEquals(1, balken.size, "ein Termin, ein Balken: $balken")
    assertEquals(kinder.first().taskID, balken.single().besitzerId)
  }

  // =============================================================================================
  // 4. The weekends, put back.
  // =============================================================================================

  /**
   * NOTHING HERE DEPENDS ON THE CALENDAR WITHOUT WEEKENDS. The same series on the real weekend
   * calendar: three dates, each on a Monday, still three bars on one row. The count is the same
   * because each date is one working day and therefore one activity — the weekends lie BETWEEN the
   * dates, where the new drawing leaves a real gap rather than a faint rectangle.
   *
   * And with the switch off the very same plan draws MORE than one rectangle, because the group's
   * bar is cut at every weekend. That is the second half of this check and the reason the counting
   * above is done on a calendar without them.
   */
  @Test
  fun `mit wochenenden bleiben es drei balken, der gruppenbalken zerfaellt`() {
    val d = Diagramm(WeekendCalendarImpl())
    val (gruppe, kinder) = d.serie("Umsatzsteuervoranmeldung", termine)

    val ohneSchalter = d.balken(listOf(gruppe))
    assertTrue(ohneSchalter.size > 1,
      "der Gruppenbalken wird an den Wochenenden zerschnitten -- sonst zaehlt die Pruefung oben " +
        "zwei verschiedene Dinge: $ohneSchalter")
    assertTrue(ohneSchalter.all { it.besitzerId == gruppe.taskID },
      "und alle Stuecke gehoeren der Gruppe: $ohneSchalter")

    d.schalterAn()
    val mitSchalter = d.balken(listOf(gruppe))
    assertEquals(3, mitSchalter.size, "drei Montage, drei Balken: $mitSchalter")
    assertEquals(1, mitSchalter.map { it.oben }.distinct().size, "auf einer Zeile: $mitSchalter")
    assertEquals(kinder.map { it.taskID }.toSet(), mitSchalter.mapNotNull { it.besitzerId }.toSet())
  }
}
