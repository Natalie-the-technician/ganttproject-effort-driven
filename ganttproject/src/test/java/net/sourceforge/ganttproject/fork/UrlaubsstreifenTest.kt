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

import biz.ganttproject.core.calendar.GanttDaysOff
import biz.ganttproject.core.calendar.WeekendCalendarImpl
import biz.ganttproject.core.chart.canvas.Canvas
import biz.ganttproject.core.chart.canvas.Painter
import biz.ganttproject.core.chart.grid.Offset
import biz.ganttproject.core.option.DefaultFontOption
import biz.ganttproject.core.option.DefaultIntegerOption
import biz.ganttproject.core.option.FontSpec
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.core.time.GanttCalendar
import biz.ganttproject.core.time.impl.GPTimeUnitStack
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.chart.ChartModelImpl
import net.sourceforge.ganttproject.chart.TaskRendererImpl2
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
 * ═══ TAG 3 VON 5, UND NUR TAG 3 ═══
 *
 * Natalie: „Ich hatte gedacht den vorgangsbalken mit einem Urlaubsstreifen. Er muss ja nur zeigen
 * das an dem Tag jemand nicht da ist. Also nicht der ganze Vorgang soll den streifen bekommen,
 * sondern nur der teil, wenn jemand an Tag 3 von 5 fehlt soll nur Tag 3 den streifen haben."
 *
 * That sentence is one check, and it is the first one in this file. Everything after it guards it.
 *
 * ═══ THROUGH THE REAL CHART, NOT THROUGH A DOUBLE ═══
 *
 * The checks build a real [ChartModelImpl] over a real `TaskManager` with real people, real
 * assignments and real days off, and then ask the real [TaskRendererImpl2] to render. What is
 * asserted is what a `Painter` would be handed — the same path the screen takes.
 *
 * That matters more here than it would elsewhere. The hard part of this package was never the
 * arithmetic; it was that A TASK BAR DID NOT KNOW WHO WAS WORKING ON IT (`git grep assignments`
 * over the renderer was empty). A check against a hand-built list of day ranges would have proved
 * the arithmetic and left the missing wire untested — and the wire is the package.
 *
 * ═══ WHAT IS DELIBERATELY NOT MEASURED HERE ═══
 *
 * The pixels. A style is only a promise, and the promise is cashed in `UrlaubsstreifenBildTest`,
 * which runs the real `StyledPainterImpl` onto a real image and reads the colours back out.
 */
class UrlaubsstreifenTest {

  init {
    // WeekendCalendarImpl and GanttCalendar both need this; without it every check here dies in
    // CalendarFactory with a NullPointerException. Same bootstrap as BlockingAbsenceDurationTest.
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

  /** Monday, 7 September 2026. Everything in this file counts from here. */
  private val montag = LocalDate.of(2026, 9, 7)
  private val dienstag = montag.plusDays(1)
  private val mittwoch = montag.plusDays(2)
  private val donnerstag = montag.plusDays(3)
  private val freitag = montag.plusDays(4)
  private val samstag = montag.plusDays(5)

  /** Collects what the canvas would hand to a painter. The real path, not a peek into a field. */
  private class Sammler : Painter {
    val rechtecke = mutableListOf<Rechteck>()
    override fun prePaint() {}
    override fun paint(rectangle: Canvas.Rectangle) {
      if (rectangle.isVisible) {
        rechtecke.add(Rechteck(rectangle.style, rectangle.leftX, rectangle.width, rectangle.topY, rectangle.height))
      }
    }
    override fun paint(line: Canvas.Line) {}
    override fun paint(next: Canvas.Text) {}
    override fun paint(textGroup: Canvas.TextGroup) {}
    override fun paint(rhombus: Canvas.Rhombus) {}
  }

  private data class Rechteck(
    val stil: String?, val links: Int, val breite: Int, val oben: Int, val hoehe: Int)

  /**
   * A plan with a real chart over it.
   *
   * The chart starts a week BEFORE the tasks so that a stripe cannot accidentally be right merely
   * because it sits at pixel 0, and so that „the holiday ends on the first day of the task" has
   * somewhere to end.
   */
  private inner class Diagramm {
    val builder = TestSetupHelper.newTaskManagerBuilder().withCalendar(WeekendCalendarImpl())
    val taskManager = builder.build()
    val resourceManager = builder.resourceManager
    val chartModel: ChartModelImpl
    private val renderer: TaskRendererImpl2
    private var naechsteId = 0

    init {
      val projectConfig = UIConfiguration(Color.BLUE, false)
      projectConfig.chartFontOption =
        DefaultFontOption("foo", FontSpec("Foo", FontSpec.Size.NORMAL), emptyList())
      projectConfig.dpiOption = DefaultIntegerOption("bar", 96)
      chartModel = ChartModelImpl(taskManager, GPTimeUnitStack(), projectConfig)
      chartModel.setBounds(Dimension(1200, 400))
      chartModel.setTopTimeUnit(GPTimeUnitStack.WEEK)
      chartModel.setBottomTimeUnit(GPTimeUnitStack.DAY)
      // MUST BE SET, and it is not a taste in zoom. `ChartModelBase.myAtomUnitPixels` starts at 0
      // and nothing in the model gives it a default; the running program sets it from the zoom
      // control. At 0 the offset builder advances the day columns by zero pixels and never
      // reaches the right edge of the viewport, so it produces offsets until the heap is gone --
      // measured, „Java heap space" out of Gradle Test Executor 1 on the first run of this file.
      chartModel.setBottomUnitWidth(TAGBREITE)
      chartModel.startDate = montag.minusDays(7).toModelDate()
      renderer = TaskRendererImpl2(chartModel)
    }

    fun person(name: String): HumanResource = resourceManager.create(name, naechsteId++)

    /** A task of [tage] days starting on [start]. */
    fun vorgang(name: String, start: LocalDate, tage: Int): Task =
      taskManager.newTaskBuilder()
        .withName(name)
        .withStartDate(start.toModelDate())
        .withDuration(taskManager.createLength(tage.toLong()))
        .build()

    /** Renders exactly what the screen would render and returns every rectangle of [stil]. */
    fun rechtecke(vorgang: Task, stil: String? = null): List<Rechteck> {
      chartModel.setVisibleTasks(listOf(vorgang))
      renderer.render()
      val sammler = Sammler()
      renderer.primitiveContainer.paint(sammler)
      renderer.primitiveContainer.layers.forEach { it.paint(sammler) }
      return if (stil == null) sammler.rechtecke else sammler.rechtecke.filter { it.stil == stil }
    }

    /** The day columns of the chart, as the renderer measures the bars against them. */
    fun offsets(): List<Offset> = chartModel.defaultUnitOffsets

    /**
     * WHICH DAYS carry a stripe. Not „which pixels" — a pixel number would pass for the wrong day
     * as soon as the zoom changed, and it would not read as Natalie's sentence.
     *
     * A day counts as striped when its whole column lies inside a stripe rectangle. The columns
     * come from the chart itself, so this cannot agree with a stripe that was measured against a
     * different grid than the bar was.
     */
    fun gestreifteTage(vorgang: Task): List<LocalDate> {
      val streifen = rechtecke(vorgang, STYLE_ABSENCE)
      return offsets()
        .filter { offset ->
          streifen.any { it.links <= offset.startPixels && it.links + it.breite >= offset.offsetPixels }
        }
        .map { it.offsetStart.toModelLocalDate() }
        .distinct()
        .sorted()
    }
  }

  private fun HumanResource.urlaub(von: LocalDate, bisAusschliesslich: LocalDate) {
    // The end of a day-off interval is EXCLUSIVE -- see the head comment of DaysOffDuration.kt.
    this.addDaysOff(
      GanttDaysOff(GanttCalendar.fromLocalDate(von), GanttCalendar.fromLocalDate(bisAusschliesslich)))
  }

  private fun HumanResource.urlaubAm(tag: LocalDate) = urlaub(tag, tag.plusDays(1))

  private fun Task.zuordnen(person: HumanResource, blockierend: Boolean = false,
                            ohneAufwand: Boolean = false) {
    this.assignmentCollection.addAssignment(person).apply {
      load = 100f
      isBlocking = blockierend
      isNoEffort = ohneAufwand
    }
  }

  // =============================================================================================
  // 1. The check the package exists for.
  // =============================================================================================

  /**
   * THE SENTENCE, AS A CHECK. Five days, one person, away on the third — and the other four days
   * must stay bare. „Tag 3 hat einen Streifen" alone would also pass for an implementation that
   * stripes the whole bar, which is exactly what Natalie asked for it NOT to do.
   */
  @Test
  fun `tag 3 von 5 traegt den streifen, und nur tag 3`() {
    val d = Diagramm()
    val a = d.person("A")
    a.urlaubAm(mittwoch)
    val v = d.vorgang("v", montag, 5).also { it.zuordnen(a) }

    assertEquals(listOf(mittwoch), d.gestreifteTage(v),
      "gezeichnet wurden: ${d.rechtecke(v, STYLE_ABSENCE)}")
  }

  /** The positive control for the one above: without it, „no stripes" would also pass for „no bar". */
  @Test
  fun `der balken wird ueberhaupt gezeichnet`() {
    val d = Diagramm()
    val a = d.person("A")
    a.urlaubAm(mittwoch)
    val v = d.vorgang("v", montag, 5).also { it.zuordnen(a) }

    val alle = d.rechtecke(v)
    assertTrue(alle.any { it.stil?.startsWith("task") == true && it.stil != STYLE_ABSENCE },
      "kein einziges Balkenrechteck: $alle")
  }

  /** The stripe sits ON the bar: same top edge, same height, and inside it horizontally. */
  @Test
  fun `der streifen liegt auf dem balken und nicht daneben`() {
    val d = Diagramm()
    val a = d.person("A")
    a.urlaubAm(mittwoch)
    val v = d.vorgang("v", montag, 5).also { it.zuordnen(a) }

    val alle = d.rechtecke(v)
    val streifen = alle.filter { it.stil == STYLE_ABSENCE }
    val balken = alle.filter { it.stil?.startsWith("task") == true && it.stil != STYLE_ABSENCE }
    assertEquals(1, streifen.size, "genau ein Streifen erwartet: $alle")
    val s = streifen.single()
    assertTrue(balken.any {
      it.oben == s.oben && it.hoehe == s.hoehe && it.links <= s.links &&
        it.links + it.breite >= s.links + s.breite
    }, "der Streifen $s deckt sich mit keinem Balken: $balken")
  }

  // =============================================================================================
  // 2. The boundaries. The end of a holiday is EXCLUSIVE, so both neighbours are checked.
  // =============================================================================================

  /**
   * A HOLIDAY THAT ENDS ON THE FIRST DAY OF THE TASK TOUCHES NOTHING. `[Freitag davor, Montag)` is
   * the Friday, the Saturday and the Sunday; the Monday is NOT in it. Read inclusively it would be,
   * and the first day of every task after a weekend of holiday would be marked wrongly.
   */
  @Test
  fun `ein urlaub der am ersten tag des vorgangs endet streift nichts`() {
    val d = Diagramm()
    val a = d.person("A")
    a.urlaub(montag.minusDays(3), montag)
    val v = d.vorgang("v", montag, 5).also { it.zuordnen(a) }

    assertEquals(emptyList<LocalDate>(), d.gestreifteTage(v),
      "gezeichnet wurden: ${d.rechtecke(v, STYLE_ABSENCE)}")
  }

  /** The neighbour of the check above: one day later the Monday IS in the holiday, and is striped. */
  @Test
  fun `ein urlaub der bis zum dienstag reicht streift den montag`() {
    val d = Diagramm()
    val a = d.person("A")
    a.urlaub(montag.minusDays(3), dienstag)
    val v = d.vorgang("v", montag, 5).also { it.zuordnen(a) }

    assertEquals(listOf(montag), d.gestreifteTage(v),
      "gezeichnet wurden: ${d.rechtecke(v, STYLE_ABSENCE)}")
  }

  /** A holiday beginning on the LAST day of the task marks that day and nothing beyond it. */
  @Test
  fun `ein urlaub der am letzten tag beginnt streift genau diesen tag`() {
    val d = Diagramm()
    val a = d.person("A")
    a.urlaub(freitag, freitag.plusDays(5))
    val v = d.vorgang("v", montag, 5).also { it.zuordnen(a) }

    assertEquals(listOf(freitag), d.gestreifteTage(v),
      "gezeichnet wurden: ${d.rechtecke(v, STYLE_ABSENCE)}")
  }

  /** The neighbour on that side: a holiday starting the day AFTER the task ends touches nothing. */
  @Test
  fun `ein urlaub der am tag nach dem vorgang beginnt streift nichts`() {
    val d = Diagramm()
    val a = d.person("A")
    a.urlaub(samstag, samstag.plusDays(5))
    val v = d.vorgang("v", montag, 5).also { it.zuordnen(a) }

    assertEquals(emptyList<LocalDate>(), d.gestreifteTage(v),
      "gezeichnet wurden: ${d.rechtecke(v, STYLE_ABSENCE)}")
  }

  @Test
  fun `ein urlaub ganz ausserhalb des vorgangs streift nichts`() {
    val d = Diagramm()
    val a = d.person("A")
    a.urlaub(montag.minusDays(30), montag.minusDays(25))
    val v = d.vorgang("v", montag, 5).also { it.zuordnen(a) }

    assertEquals(emptyList<LocalDate>(), d.gestreifteTage(v),
      "gezeichnet wurden: ${d.rechtecke(v, STYLE_ABSENCE)}")
  }

  // =============================================================================================
  // 3. Two people, one stripe.
  // =============================================================================================

  /**
   * The stripe says „somebody is missing", and that sentence is not truer twice. Two rectangles on
   * the same pixels would also paint their translucent fill twice and give a third colour that
   * stands for nothing.
   */
  @Test
  fun `zwei personen mit urlaub am selben tag ergeben EINEN streifen`() {
    val d = Diagramm()
    val a = d.person("A")
    val b = d.person("B")
    a.urlaubAm(mittwoch)
    b.urlaubAm(mittwoch)
    val v = d.vorgang("v", montag, 5).also { it.zuordnen(a); it.zuordnen(b) }

    val streifen = d.rechtecke(v, STYLE_ABSENCE)
    assertEquals(1, streifen.size, "ein Streifen erwartet, gezeichnet wurden: $streifen")
    assertEquals(listOf(mittwoch), d.gestreifteTage(v))
  }

  /** Two people away on neighbouring days become ONE rectangle over both — no seam in between. */
  @Test
  fun `zwei personen an benachbarten tagen ergeben ein durchgehendes rechteck`() {
    val d = Diagramm()
    val a = d.person("A")
    val b = d.person("B")
    a.urlaubAm(mittwoch)
    b.urlaubAm(donnerstag)
    val v = d.vorgang("v", montag, 5).also { it.zuordnen(a); it.zuordnen(b) }

    val streifen = d.rechtecke(v, STYLE_ABSENCE)
    assertEquals(1, streifen.size, "ein durchgehendes Rechteck erwartet: $streifen")
    assertEquals(listOf(mittwoch, donnerstag), d.gestreifteTage(v))
  }

  // =============================================================================================
  // 4. WHOSE absence counts. The measurement is in the report; these pin the answer.
  // =============================================================================================

  /**
   * THE ASSIGNMENT THE CALCULATION LEAVES OUT, AND THE DISPLAY DOES NOT.
   *
   * `WorkWeekEffect.isInvolvedInWorkWeek` is `contributesEffort || isBlocking`, and it deliberately
   * excludes the person who books no hours and whose absence stops nothing. Rightly so — letting
   * them shorten a plan would be a bystander changing the schedule.
   *
   * They must still be SHOWN. Natalie's sentence is „zeigen das an dem Tag jemand nicht da ist" —
   * anybody — and the other half of it, „wer da fehlt kann man dann in der detailansicht schauen",
   * settles it: the detail view lists every assignment, so a stripe built on a narrower set would
   * leave a day unmarked whose absence that view does list.
   */
  @Test
  fun `eine zuordnung ohne aufwand und ohne blockade streift trotzdem`() {
    val d = Diagramm()
    val a = d.person("A")
    a.urlaubAm(mittwoch)
    val v = d.vorgang("v", montag, 5).also { it.zuordnen(a, blockierend = false, ohneAufwand = true) }

    assertEquals(listOf(mittwoch), d.gestreifteTage(v),
      "diese Person leistet nichts und haelt nichts auf -- sie fehlt trotzdem. " +
        "Gezeichnet wurden: ${d.rechtecke(v, STYLE_ABSENCE)}")
  }

  /** The ordinary assignment, which every axis includes. */
  @Test
  fun `eine gewoehnliche zuordnung streift`() {
    val d = Diagramm()
    val a = d.person("A")
    a.urlaubAm(mittwoch)
    val v = d.vorgang("v", montag, 5).also { it.zuordnen(a) }

    assertEquals(listOf(mittwoch), d.gestreifteTage(v))
  }

  /** The person who has to be there and books no hours. */
  @Test
  fun `eine blockierende zuordnung ohne aufwand streift`() {
    val d = Diagramm()
    val a = d.person("A")
    a.urlaubAm(mittwoch)
    val v = d.vorgang("v", montag, 5).also { it.zuordnen(a, blockierend = true, ohneAufwand = true) }

    assertEquals(listOf(mittwoch), d.gestreifteTage(v))
  }

  /** Somebody else's holiday is not this task's business. */
  @Test
  fun `der urlaub einer nicht zugeordneten person streift nichts`() {
    val d = Diagramm()
    val a = d.person("A")
    val fremd = d.person("Fremd")
    fremd.urlaubAm(mittwoch)
    val v = d.vorgang("v", montag, 5).also { it.zuordnen(a) }

    assertEquals(emptyList<LocalDate>(), d.gestreifteTage(v),
      "gezeichnet wurden: ${d.rechtecke(v, STYLE_ABSENCE)}")
  }

  // =============================================================================================
  // 5. The guards.
  // =============================================================================================

  /**
   * NICHTS-AENDERT-SICH-WACHE. Not „the canvas equals a list I wrote down myself" — that would only
   * check that I copied the present behaviour correctly. The same task is rendered TWICE, once with
   * a holiday and once without, and the demand is that the plan WITHOUT one is contained in the
   * other UNCHANGED and that the only difference is the stripe.
   *
   * It is green before this package as well and proves nothing by itself. What gives it its worth
   * is that it was broken on purpose several times; the breaks and their output are in the report
   * of 05.09.2026.
   */
  @Test
  fun `ein plan ohne ausfallzeit zeichnet wie vorher`() {
    val ohne = Diagramm().let { d ->
      val a = d.person("A")
      d.rechtecke(d.vorgang("v", montag, 5).also { it.zuordnen(a) })
    }
    val mit = Diagramm().let { d ->
      val a = d.person("A")
      a.urlaubAm(mittwoch)
      d.rechtecke(d.vorgang("v", montag, 5).also { it.zuordnen(a) })
    }

    ohne.forEach { vorher ->
      assertTrue(vorher in mit, "$vorher ist verschwunden oder verrutscht.\nohne: $ohne\nmit:  $mit")
    }
    val neu = mit.filter { it !in ohne }
    assertEquals(listOf(STYLE_ABSENCE), neu.map { it.stil }, "hinzugekommen sind: $neu")
  }

  @Test
  fun `ein plan ohne ausfallzeit hat kein einziges streifenrechteck`() {
    val d = Diagramm()
    val a = d.person("A")
    val v = d.vorgang("v", montag, 5).also { it.zuordnen(a) }

    assertEquals(emptyList<Rechteck>(), d.rechtecke(v, STYLE_ABSENCE))
    assertTrue(d.rechtecke(v).isNotEmpty(), "die Gegenprobe: ohne Urlaub wird trotzdem gezeichnet")
  }

  /** A task with nobody on it falls back to nothing, and must not fail while painting. */
  @Test
  fun `ein vorgang ohne zuordnung hat keinen streifen`() {
    val d = Diagramm()
    val v = d.vorgang("v", montag, 5)

    assertEquals(emptyList<Rechteck>(), d.rechtecke(v, STYLE_ABSENCE))
  }

  /**
   * HEIMARBEIT IST KEINE ABWESENHEIT. This is the guard against the one mistake B4 names
   * explicitly: somebody working from home IS working, they merely cannot do the tasks that need
   * them on the premises. Putting that day into the same stripe would turn a working day into an
   * absence.
   *
   * The person here works from home every day of the task and has no day off at all.
   */
  @Test
  fun `heimarbeit erzeugt keinen urlaubsstreifen`() {
    val d = Diagramm()
    val a = d.person("A")
    a.setHomeOfficePeriods(
      d.resourceManager.customPropertyManager,
      HomeOfficePeriods(listOf(HomeOfficePeriod(montag, montag.plusDays(14)))))
    val v = d.vorgang("v", montag, 5).also { it.zuordnen(a) }

    assertEquals(emptyList<Rechteck>(), d.rechtecke(v, STYLE_ABSENCE),
      "ein Heimarbeitstag ist ein Arbeitstag und darf keinen Streifen bekommen")
  }

  /**
   * The counter-check to the one above, and the pair is what makes either worth anything: THE SAME
   * person, the same days at home, plus one real day off. Only that one day is striped.
   */
  @Test
  fun `neben der heimarbeit wird der eine urlaubstag trotzdem gestreift`() {
    val d = Diagramm()
    val a = d.person("A")
    a.setHomeOfficePeriods(
      d.resourceManager.customPropertyManager,
      HomeOfficePeriods(listOf(HomeOfficePeriod(montag, montag.plusDays(14)))))
    a.urlaubAm(mittwoch)
    val v = d.vorgang("v", montag, 5).also { it.zuordnen(a) }

    assertEquals(listOf(mittwoch), d.gestreifteTage(v))
  }

  // =============================================================================================
  // 6. The run arithmetic on its own. Cheap checks of the part that has no chart in it.
  // =============================================================================================

  @Test
  fun `benachbarte laeufe werden verschmolzen und nicht aneinandergereiht`() {
    assertEquals(
      listOf(AbsenceRun(montag, donnerstag)),
      mergeAbsenceRuns(listOf(montag to mittwoch, mittwoch to donnerstag)))
  }

  @Test
  fun `ueberlappende laeufe werden verschmolzen`() {
    assertEquals(
      listOf(AbsenceRun(montag, freitag)),
      mergeAbsenceRuns(listOf(montag to donnerstag, dienstag to freitag)))
  }

  @Test
  fun `ein umschlossener lauf verkuerzt den umschliessenden nicht`() {
    assertEquals(
      listOf(AbsenceRun(montag, freitag)),
      mergeAbsenceRuns(listOf(montag to freitag, mittwoch to donnerstag)))
  }

  @Test
  fun `getrennte laeufe bleiben getrennt und kommen der reihe nach`() {
    assertEquals(
      listOf(AbsenceRun(montag, dienstag), AbsenceRun(donnerstag, freitag)),
      mergeAbsenceRuns(listOf(donnerstag to freitag, montag to dienstag)))
  }

  /** A vacation covering no day is no vacation. It must not become a rectangle of width zero. */
  @Test
  fun `ein leerer lauf verschwindet`() {
    assertEquals(emptyList<AbsenceRun>(), mergeAbsenceRuns(listOf(montag to montag)))
    assertEquals(emptyList<AbsenceRun>(), mergeAbsenceRuns(listOf(dienstag to montag)))
    assertEquals(emptyList<AbsenceRun>(), mergeAbsenceRuns(emptyList()))
  }
}
