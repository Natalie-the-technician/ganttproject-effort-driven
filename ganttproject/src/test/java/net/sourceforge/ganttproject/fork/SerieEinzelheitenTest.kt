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
import biz.ganttproject.core.calendar.GanttDaysOff
import biz.ganttproject.core.chart.canvas.Canvas
import biz.ganttproject.core.chart.canvas.Painter
import biz.ganttproject.core.chart.scene.IdentifiableRow
import biz.ganttproject.core.option.DefaultFontOption
import biz.ganttproject.core.option.DefaultIntegerOption
import biz.ganttproject.core.option.FontSpec
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.core.time.GanttCalendar
import biz.ganttproject.core.time.impl.GPTimeUnitStack
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.chart.ChartModelImpl
import net.sourceforge.ganttproject.chart.TaskRendererImpl2
import net.sourceforge.ganttproject.chart.gantt.ITaskActivity
import net.sourceforge.ganttproject.gui.UIConfiguration
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.task.Task
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Dimension
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * ═══ AUF EINER ZEILE MIT ZWÖLF BALKEN GEHÖRT JEDE EINZELHEIT ZU IHREM BALKEN ═══
 *
 * Steps S4, S5 and S6a of the measurement report of 11.09.2026. The previous session put the bars
 * of a collapsed series on the group's row; what hangs OFF a bar did not follow them, and this file
 * is about the three things that did not:
 *
 *  * **S4 — the progress bar.** `renderProgressBar` takes the owner of the FIRST rectangle as "the"
 *    task and then spends that one task's completion across the whole row. With twelve dates on one
 *    row, 50 % would fill the first six bars completely and leave the last six empty — which looks
 *    right as long as a series is worked through in order and lies the moment somebody does March
 *    before February. Each date carries its own percentage and has to show its own.
 *  * **S5 — the absence stripe.** `renderAbsenceStripes` looked the days off up once per ROW, by
 *    the row's task id. On a merged row that is the GROUP, which has no assignments of its own.
 *  * **S6a — the comparison band.** It belongs to the row's task and describes the group's span
 *    over the whole year; the bars belong to the dates. Drawing both puts two different things on
 *    one row with nothing to say which is which.
 *
 * ═══ NOTHING HERE IS A REPAIR, ALL OF IT IS AN ADDITION ═══
 *
 * Measured before a line was written, and it corrects the measurement report: `renderActivities`
 * draws stripe and progress only when
 * `areVisible && !hasNestedTasks(t) && !isMilestone() && !isProjectTask()`, and the row of a
 * collapsed recurrence group is a SUMMARY task. **On that row neither of the two has ever been
 * drawn**, with the switch or without it. So there is no previous picture to preserve and no
 * regression to avoid — and a check that compared against "how it was before" would be worth
 * nothing here, because before there was nothing. Every check below is written against the SUBJECT.
 *
 * ═══ THE CALENDAR HAS NO WEEKENDS, AND THAT IS ON PURPOSE ═══
 *
 * Same reason as in [SerieEineReiheTest]: `TaskActivitiesAlgorithm` cuts every task at the
 * boundaries of working time, so on a weekend calendar "one bar per date" and "one rectangle per
 * stretch of working time" are two different counts. Here it would also split the progress bar of a
 * single date into several rectangles and make "one progress rectangle per date" untrue for a
 * reason that has nothing to do with series.
 */
class SerieEinzelheitenTest {

  init {
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

  /** The three dates of the series, one week apart, so there are real gaps between them. */
  private val termine = listOf(montag, montag.plusDays(7), montag.plusDays(14))

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

    /** The row id of the task a BAR belongs to — a bar's model object is an activity. */
    val balkenBesitzer: Int? get() = (modell as? ITaskActivity<*>)?.owner?.rowId

    /**
     * The row id a progress rectangle is bound to. A different question from [balkenBesitzer] and
     * it needs its own accessor: `renderProgressBar` binds its rectangle to the TASK, not to an
     * activity, so reading it as an activity would quietly yield null and every check about which
     * date a progress bar belongs to would pass for the wrong reason.
     */
    val gebundeneZeile: Int? get() = (modell as? IdentifiableRow)?.rowId
  }

  private inner class Diagramm {
    val builder = TestSetupHelper.newTaskManagerBuilder().withCalendar(AlwaysWorkingTimeCalendarImpl())
    val taskManager = builder.build()
    val resourceManager = builder.resourceManager
    val props: CustomPropertyManager = taskManager.customPropertyManager
    val chartModel: ChartModelImpl
    val renderer: TaskRendererImpl2
    private var naechsteId = 0

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
      // A bare ChartModelImpl has a row height of ZERO; see the note in SerieEineReiheTest.
      chartModel.setRowHeight(ZEILENHOEHE)
      chartModel.startDate = montag.minusDays(7).toModelDate()
      renderer = TaskRendererImpl2(chartModel)
    }

    fun person(name: String): HumanResource = resourceManager.create(name, naechsteId++)

    fun vorgang(name: String, start: LocalDate, tage: Int = 1, elternteil: Task? = null): Task =
      taskManager.newTaskBuilder()
        .withName(name)
        .withStartDate(start.toModelDate())
        .withDuration(taskManager.createLength(tage.toLong()))
        .let { if (elternteil == null) it else it.withParent(elternteil) }
        .build()

    /** A collapsed recurrence group, markers exactly as `RecurrenceAdapter` writes them. */
    fun serie(name: String, daten: List<LocalDate>): Pair<Task, List<Task>> {
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

    fun rechtecke(sichtbar: List<Task>): List<Rechteck> {
      chartModel.setVisibleTasks(sichtbar)
      renderer.render()
      val sammler = Sammler()
      renderer.primitiveContainer.paint(sammler)
      renderer.primitiveContainer.layers.forEach { it.paint(sammler) }
      return sammler.rechtecke
    }

    /** Only the task bars: what a row draws for work, whoever it belongs to. */
    fun balken(sichtbar: List<Task>): List<Rechteck> =
      rechtecke(sichtbar).filter { it.stil?.startsWith("task") == true && it.balkenBesitzer != null }

    fun schalterAn() { renderer.seriesOneRowOption.value = true }
  }

  private fun HumanResource.urlaubAm(tag: LocalDate) {
    // The end of a day-off interval is EXCLUSIVE -- see the head comment of DaysOffDuration.kt.
    this.addDaysOff(GanttDaysOff(
      GanttCalendar.fromLocalDate(tag), GanttCalendar.fromLocalDate(tag.plusDays(1))))
  }

  private fun Task.zuordnen(person: HumanResource) {
    this.assignmentCollection.addAssignment(person).apply { load = 100f }
  }

  private fun List<Rechteck>.fortschritt() = filter { it.stil?.startsWith("task.progress") == true }
  private fun List<Rechteck>.streifen() = filter { it.stil == STYLE_ABSENCE }
  private fun List<Rechteck>.baender() = filter { it.stil == "previousStateTask" }

  // =============================================================================================
  // S4 — the progress bar belongs to the date, not to the row.
  // =============================================================================================

  /**
   * THE CHECK S4 EXISTS FOR. Three dates at 0 %, 50 % and 100 % give THREE progress rectangles of
   * three different widths, each one on its own date's bar.
   *
   * WHY THE PERCENTAGES ARE IN THIS ORDER — 0, 50, 100 from left to right is exactly the picture
   * the old "spend it across the row" arithmetic produces by accident for a half-done series. The
   * middle one at 50 % is what tells the two apart: spread over the row, 50 % of the group would
   * fill bar one and a half of bar two and leave bar three empty. Per date it is 0, half, full.
   */
  @Test
  fun `drei termine mit verschiedenem fortschritt ergeben drei verschiedene balken`() {
    val d = Diagramm()
    val (gruppe, kinder) = d.serie("Umsatzsteuervoranmeldung", termine)
    kinder[0].completionPercentage = 0
    kinder[1].completionPercentage = 50
    kinder[2].completionPercentage = 100
    d.schalterAn()

    val alle = d.rechtecke(listOf(gruppe))
    val balken = alle.filter { it.stil?.startsWith("task") == true && it.balkenBesitzer != null }
    val fortschritt = alle.fortschritt()

    assertEquals(3, balken.size, "drei Termine, drei Balken: $balken")
    assertEquals(3, fortschritt.size,
      "je Termin ein eigenes Fortschrittsrechteck, nicht eines ueber die Zeile: $fortschritt")
    assertEquals(kinder.map { it.taskID }, fortschritt.map { it.gebundeneZeile },
      "und jedes haengt an seinem eigenen Termin: $fortschritt")

    // 0 % of a 20 px bar is 0 px, 50 % is 10, 100 % is 20. Three different rectangles.
    assertEquals(listOf(0, TAGBREITE / 2, TAGBREITE), fortschritt.map { it.breite },
      "null, halb, ganz -- nicht der Betrag der Gruppe ueber die Zeile verteilt: $fortschritt")
    assertEquals(balken.map { it.links }, fortschritt.map { it.links },
      "jedes beginnt am linken Rand seines eigenen Balkens: $fortschritt gegen $balken")
  }

  /**
   * THE LIE THE OLD ARITHMETIC TELLS, as a check of its own: the LAST date done and the first two
   * not. Spread across the row, a completion of any kind starts at the left. Per date, the paint
   * is on the right where the work was done.
   *
   * Without this one, the check above would also pass for an implementation that merely handed the
   * row's total to the bars in order.
   */
  @Test
  fun `der zuletzt erledigte termin traegt die farbe, nicht der erste`() {
    val d = Diagramm()
    val (gruppe, kinder) = d.serie("Umsatzsteuervoranmeldung", termine)
    kinder[2].completionPercentage = 100
    d.schalterAn()

    val alle = d.rechtecke(listOf(gruppe))
    val voll = alle.fortschritt().filter { it.breite > 0 }
    assertEquals(1, voll.size, "genau ein Termin ist erledigt: ${alle.fortschritt()}")
    assertEquals(kinder[2].taskID, voll.single().gebundeneZeile,
      "und zwar der dritte: ${voll.single()}")
  }

  /**
   * NOTHING CHANGES ON AN ORDINARY ROW. A plain task keeps exactly one progress bar bound to
   * itself, and a summary task that is not a series keeps having none at all.
   */
  @Test
  fun `eine gewoehnliche zeile behaelt ihren einen fortschrittsbalken`() {
    val d = Diagramm()
    val einzeln = d.vorgang("etwas anderes", montag, 4)
    einzeln.completionPercentage = 25
    val sammel = d.vorgang("Sammelgruppe", montag)
    d.vorgang("Kind", montag, 2, sammel)
    d.taskManager.algorithmCollection.adjustTaskBoundsAlgorithm.run(sammel)
    sammel.expand = false
    d.schalterAn()

    val alle = d.rechtecke(listOf(einzeln, sammel))
    val fortschritt = alle.fortschritt()
    assertEquals(1, fortschritt.size, "nur der gewoehnliche Vorgang hat einen: $fortschritt")
    assertEquals(einzeln.taskID, fortschritt.single().gebundeneZeile)
    assertEquals(TAGBREITE, fortschritt.single().breite, "25 % von vier Tagen ist ein Tag")
  }

  // =============================================================================================
  // S5 — the absence stripe belongs to the bar, not to the row.
  // =============================================================================================

  /**
   * THE CHECK S5 EXISTS FOR, and it is written so that it can fail in BOTH directions.
   *
   * Two people, and neither of them is away on their own date by accident:
   *
   *  * **Anna** is assigned to date 1 and away on the day of date 3. Nobody on date 1 is away, so
   *    date 1 carries no stripe — and date 3 must not carry one either, because Anna does not work
   *    on it. That is the half a row-wide lookup would get wrong the other way round: pooling the
   *    row's days off would paint Anna's holiday onto somebody else's date.
   *  * **Bert** is assigned to date 2 and away on the day of date 2. That is the one stripe there
   *    is, and looking the days off up by the ROW's task id would miss it altogether, because the
   *    row is the group and the group has no assignments.
   */
  @Test
  fun `der fehlstreifen liegt auf dem termin zu dem er gehoert`() {
    val d = Diagramm()
    val (gruppe, kinder) = d.serie("Umsatzsteuervoranmeldung", termine)
    val anna = d.person("Anna")
    val bert = d.person("Bert")
    anna.urlaubAm(termine[2])
    bert.urlaubAm(termine[1])
    kinder[0].zuordnen(anna)
    kinder[1].zuordnen(bert)
    d.schalterAn()

    val alle = d.rechtecke(listOf(gruppe))
    val balken = alle.filter { it.stil?.startsWith("task") == true && it.balkenBesitzer != null }
    val streifen = alle.streifen()

    assertEquals(1, streifen.size,
      "genau ein Streifen -- Berts Urlaub am zweiten Termin: $streifen")
    val zweiterBalken = balken.single { it.balkenBesitzer == kinder[1].taskID }
    assertEquals(zweiterBalken.links, streifen.single().links,
      "er liegt am linken Rand des zweiten Balkens: ${streifen.single()} gegen $zweiterBalken")
    assertEquals(zweiterBalken.breite, streifen.single().breite,
      "und ist genau so breit wie dieser eine Tag")
    val dritterBalken = balken.single { it.balkenBesitzer == kinder[2].taskID }
    assertTrue(streifen.none { it.links == dritterBalken.links },
      "Annas Urlaub faellt auf den dritten Termin, aber sie arbeitet dort nicht: $streifen")
  }

  /**
   * WITHOUT THE SWITCH THERE IS STILL NOTHING, and that is the honest statement of what S5 is: an
   * addition. The group has no assignments, so its own row has never had a stripe and still has
   * none. Nothing was broken and nothing was restored.
   */
  @Test
  fun `ohne den schalter hat die zugeklappte gruppe keinen streifen`() {
    val d = Diagramm()
    val (gruppe, kinder) = d.serie("Umsatzsteuervoranmeldung", termine)
    val bert = d.person("Bert")
    bert.urlaubAm(termine[1])
    kinder[1].zuordnen(bert)

    assertEquals(emptyList<Rechteck>(), d.rechtecke(listOf(gruppe)).streifen(),
      "die Zeile der Gruppe hatte noch nie einen Streifen")
  }

  // =============================================================================================
  // S6a — no comparison band on a merged row.
  // =============================================================================================

  /**
   * THE CHECK S6a EXISTS FOR. In the durations view a task without a baseline still gets a neutral
   * band under its bar — that is the device that keeps "no deviation" apart from "not compared".
   * On a merged row that band would describe the GROUP's span while the bars above it describe the
   * dates: two different things on one row, and nothing on screen to say so.
   *
   * THE SAME PLAN WITH THE SWITCH OFF STILL HAS ITS BAND. Without that half the check would also
   * pass for a build in which the durations view had stopped drawing anything at all.
   */
  @Test
  fun `auf einer zusammengelegten zeile ist kein vergleichsband`() {
    val d = Diagramm()
    val (gruppe, _) = d.serie("Umsatzsteuervoranmeldung", termine)
    d.chartModel.setComparison(ChartComparison.DURATIONS)

    val ohneSchalter = d.rechtecke(listOf(gruppe)).baender()
    assertTrue(ohneSchalter.isNotEmpty(),
      "zugeklappt und ohne Schalter zeichnet die Dauernansicht ihr Band wie bisher")

    d.schalterAn()
    assertEquals(emptyList<Rechteck>(), d.rechtecke(listOf(gruppe)).baender(),
      "mit Schalter gehoert die Zeile den Terminen, und das Band gehoert der Gruppe")
  }

  /**
   * AND EVERY OTHER ROW KEEPS ITS BAND while the switch is on. The suppression has to be about the
   * one row that was merged and not about the chart.
   */
  @Test
  fun `die nachbarzeile behaelt ihr band`() {
    val d = Diagramm()
    val davor = d.vorgang("etwas anderes", montag, 3)
    val (gruppe, _) = d.serie("Umsatzsteuervoranmeldung", termine)
    d.chartModel.setComparison(ChartComparison.DURATIONS)
    d.schalterAn()

    val baender = d.rechtecke(listOf(davor, gruppe)).baender()
    assertTrue(baender.isNotEmpty(), "der gewoehnliche Vorgang hat sein Band: $baender")
    val obenDavor = d.balken(listOf(davor, gruppe)).single { it.balkenBesitzer == davor.taskID }.oben
    assertTrue(baender.all { it.oben < obenDavor + ZEILENHOEHE },
      "und alle Baender liegen auf seiner Zeile, keines auf der der Serie: $baender")
  }
}
