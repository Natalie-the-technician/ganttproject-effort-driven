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
import biz.ganttproject.core.chart.canvas.Canvas
import biz.ganttproject.core.chart.canvas.Painter
import biz.ganttproject.core.chart.canvas.TextMetrics
import biz.ganttproject.core.model.task.TaskDefaultColumn
import biz.ganttproject.core.option.DefaultFontOption
import biz.ganttproject.core.option.DefaultIntegerOption
import biz.ganttproject.core.option.EnumerationOption
import biz.ganttproject.core.option.FontSpec
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.core.time.impl.GPTimeUnitStack
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.chart.ChartModelImpl
import net.sourceforge.ganttproject.chart.gantt.ITaskActivity
import net.sourceforge.ganttproject.gui.UIConfiguration
import net.sourceforge.ganttproject.task.Task
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * ═══ S9: WAS AUF EINER ZEILE MIT ZWOELF BALKEN LINKS UND RECHTS STEHT ═══
 *
 * S9 of the measurement report of 11.09.2026 is written down as a DECISION with „0 to 20 lines"
 * beside it, not as a defect. This file is the measurement the decision was made on, and the
 * decision it led to was TO CHANGE NOTHING. There is no production code belonging to S9, which is
 * exactly why these checks exist: „we left it alone" is worth no more than the measurement that
 * says what was left alone, and a later change that quietly moves a label will turn them red.
 *
 * ═══ WIE DIE BESCHRIFTUNG GEBAUT IST ═══
 *
 * `TaskLabelSceneBuilder.renderLabels` is handed the rectangles of a WHOLE ROW, once. It writes the
 * left label beside `activityRectangles[0]` and the right, top and bottom labels beside
 * `activityRectangles[last]` — four labels for a row, however many bars the row carries. The text
 * of each comes from the OWNER of the rectangle it is put beside.
 *
 * On a merged row that owner is the individual date (S2 sets it), so each label is a statement
 * about the bar it touches: the left one about the first date, the right one about the last. That
 * is what is measured below, and it is the reason nothing needed changing.
 *
 * ═══ DIE DREI FASSUNGEN, DIE VERWORFEN WURDEN ═══
 *
 *  * ONE SET OF LABELS PER BAR. Twelve monthly dates at a year's zoom are bars of two or three
 *    pixels; twelve labels beside them are one smear. Nothing in this chart avoids text collisions
 *    (`git grep` over the label builder finds no such thing), so the fix would be a regression at
 *    every zoom a series is actually looked at.
 *  * THE LABELS TAKEN FROM THE GROUP. That is what the row did BEFORE this package, and it is the
 *    lie the package exists to stop: the group's duration is the distance from the first date to
 *    the last. The first check below pins exactly that difference.
 *  * NO LABELS AT ALL ON A MERGED ROW, as S6a does with the comparison band. The band was dropped
 *    because it describes the ROW and the row is the group; a label describes the BAR it is drawn
 *    beside, and those bars are true. Dropping them would remove the only true statement left.
 */
class SerieBeschriftungTest {

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
  private val montag = LocalDate.of(2026, 9, 7)
  private val termine = listOf(montag, montag.plusDays(7), montag.plusDays(14))

  /**
   * THE LABEL USED THROUGHOUT IS THE ID, `tpd10`.
   *
   * Not out of indifference: it is the one label whose text passes through no localizer and no
   * date format (`DefaultTaskColumnFormatter` answers `"# " + taskID` and nothing else), so a red
   * check here is about the label's PLACE and OWNER and can be about nothing else. What a label
   * says once the owner is right is `DefaultTaskColumnFormatter`'s business and is already checked
   * where that class lives.
   */
  private val LABEL_ID = TaskDefaultColumn.ID.stub.id

  private data class Rechteck(val stil: String?, val links: Int, val rechts: Int, val modell: Any?) {
    val besitzerId: Int? get() = (modell as? ITaskActivity<*>)?.owner?.rowId
  }

  private data class Beschriftung(val text: String, val links: Int, val ausrichtung: Canvas.HAlignment)

  /** Fixed metrics: this file measures where a label is anchored, never how wide a font is. */
  private object FesteSchrift : TextMetrics {
    override fun getTextLength(text: String) = text.length * 7
    override fun getTextHeight(text: String) = 12
    override fun getTextHeight(f: Font?, string: String?) = 12
    override fun getState(): Any = "fest"
  }

  private class Sammler : Painter {
    val rechtecke = mutableListOf<Rechteck>()
    val texte = mutableListOf<Beschriftung>()
    override fun prePaint() {}
    override fun paint(rectangle: Canvas.Rectangle) {
      if (rectangle.isVisible) {
        rechtecke.add(Rechteck(rectangle.style, rectangle.leftX, rectangle.rightX, rectangle.modelObject))
      }
    }
    override fun paint(line: Canvas.Line) {}
    override fun paint(next: Canvas.Text) {
      next.getLabels(FesteSchrift).forEach { texte.add(Beschriftung(it.text, next.leftX, next.hAlignment)) }
    }
    override fun paint(textGroup: Canvas.TextGroup) {}
    override fun paint(rhombus: Canvas.Rhombus) {}
  }

  private inner class Diagramm {
    val taskManager = TestSetupHelper.newTaskManagerBuilder()
      .withCalendar(AlwaysWorkingTimeCalendarImpl()).build()
    val props: CustomPropertyManager = taskManager.customPropertyManager
    val chartModel: ChartModelImpl

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
      chartModel.setRowHeight(20)
      chartModel.startDate = montag.minusDays(7).toModelDate()
    }

    fun vorgang(name: String, start: LocalDate, tage: Int = 1, elternteil: Task? = null): Task =
      taskManager.newTaskBuilder()
        .withName(name)
        .withStartDate(start.toModelDate())
        .withDuration(taskManager.createLength(tage.toLong()))
        .let { if (elternteil == null) it else it.withParent(elternteil) }
        .build()

    fun serie(name: String, daten: List<LocalDate> = termine): Pair<Task, List<Task>> {
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

    fun schalterAn() { chartModel.taskRenderer.seriesOneRowOption.value = true }

    private fun option(id: String): EnumerationOption =
      chartModel.taskRenderer.labelOptions.options.first { it.id == id } as EnumerationOption

    fun beschrifte(seite: String, spalte: String) { option(seite).value = spalte }

    fun zeichnen(sichtbar: List<Task>): Sammler {
      chartModel.setVisibleTasks(sichtbar)
      val renderer = chartModel.taskRenderer
      renderer.render()
      val sammler = Sammler()
      renderer.primitiveContainer.paint(sammler)
      renderer.primitiveContainer.layers.forEach { it.paint(sammler) }
      return sammler
    }
  }

  private fun balken(s: Sammler) =
    s.rechtecke.filter { it.stil?.startsWith("task") == true && it.besitzerId != null }

  // =============================================================================================

  /**
   * THE DIFFERENCE THE PACKAGE ALREADY MADE, FOR FREE. With the switch off the collapsed group's
   * row carries ONE bar and the right label says the GROUP's id. With it on the row carries three
   * and the right label says the id of the LAST DATE — because the label is read off the owner of
   * the rectangle it is put beside, and S2 made that owner the date.
   *
   * Both halves are rendered over the SAME plan in the same test, so a change that broke the
   * owner would show up as the two answers becoming equal.
   */
  @Test
  fun `die rechte beschriftung nennt den letzten termin statt der gruppe`() {
    val d = Diagramm()
    val (gruppe, kinder) = d.serie("Umsatzsteuervoranmeldung")
    d.beschrifte("taskLabelRight", LABEL_ID)

    val ohne = d.zeichnen(listOf(gruppe))
    assertEquals(listOf("# ${gruppe.taskID}"), ohne.texte.map { it.text },
      "ausgeschaltet gehoert die Zeile der Gruppe und die Beschriftung sagt es")

    d.schalterAn()
    val mit = d.zeichnen(listOf(gruppe))
    assertEquals(listOf("# ${kinder.last().taskID}"), mit.texte.map { it.text },
      "eingeschaltet steht rechts der letzte Termin -- der Balken, neben dem sie steht")
  }

  /**
   * DIE BESCHRIFTUNG STEHT AM BALKEN, VON DEM SIE SPRICHT. The right label is anchored nine pixels
   * past the right edge of the LAST bar, the left one nine before the left edge of the FIRST.
   *
   * WHY THE PLACE AND NOT ONLY THE TEXT: „right label says the last date" would also be satisfied
   * by a label parked at the left end of the row, and that is the version that would read as a
   * statement about the whole series. The place is what makes it a statement about one bar.
   */
  @Test
  fun `links steht der erste termin am ersten balken und rechts der letzte am letzten`() {
    val d = Diagramm()
    val (gruppe, kinder) = d.serie("Umsatzsteuervoranmeldung")
    d.beschrifte("taskLabelLeft", LABEL_ID)
    d.beschrifte("taskLabelRight", LABEL_ID)
    d.schalterAn()

    val s = d.zeichnen(listOf(gruppe))
    val bars = balken(s).sortedBy { it.links }
    assertEquals(3, bars.size, "drei Termine, drei Balken -- sonst misst der Rest nichts")

    val linkeB = s.texte.single { it.ausrichtung == Canvas.HAlignment.RIGHT }
    val rechteB = s.texte.single { it.ausrichtung == Canvas.HAlignment.LEFT }

    assertEquals("# ${kinder.first().taskID}", linkeB.text, "links der erste Termin")
    assertEquals(bars.first().links - 9, linkeB.links,
      "und zwar neun Pixel vor dem ERSTEN Balken, nicht irgendwo")

    assertEquals("# ${kinder.last().taskID}", rechteB.text, "rechts der letzte Termin")
    assertEquals(bars.last().rechts + 9, rechteB.links,
      "und zwar neun Pixel hinter dem LETZTEN Balken")

    assertTrue(rechteB.links - linkeB.links > 2 * TAGBREITE,
      "die beiden stehen an den Enden der Zeile, nicht beieinander: " +
        "links=${linkeB.links} rechts=${rechteB.links}")
  }

  /**
   * EINE BESCHRIFTUNG JE ZEILE, NICHT JE BALKEN. This is the decision itself, as a check. Should
   * anyone later make `renderLabels` run per owner the way S4 made the progress bar run per owner,
   * this goes red, and that is the intention: it is not a bug to be fixed silently but a trade the
   * report of 14.09.2026 argues through.
   */
  @Test
  fun `die zwoelf balken bekommen nicht zwoelf beschriftungen`() {
    val d = Diagramm()
    // FUENF TAGE AUSEINANDER, NICHT EIN MONAT, und das ist kein Detail: zwoelf Monatstermine sind
    // bei zwanzig Pixeln je Tag 6600 px breit, die Zeichenflaeche ist 1600. Elf der zwoelf Balken
    // laegen ausserhalb und waeren unsichtbar -- die erste Fassung dieser Pruefung zaehlte deshalb
    // DREI Balken und nicht zwoelf. Gezaehlt wuerde, was hineinpasst, nicht was gezeichnet wird.
    val zwoelfDaten = (0 until 12).map { montag.plusDays(5L * it) }
    val (gruppe, _) = d.serie("Umsatzsteuervoranmeldung", zwoelfDaten)
    d.beschrifte("taskLabelRight", LABEL_ID)
    d.schalterAn()

    val s = d.zeichnen(listOf(gruppe))
    assertEquals(12, balken(s).size, "zwoelf Termine, zwoelf Balken")
    assertEquals(1, s.texte.size,
      "und genau EINE rechte Beschriftung fuer die Zeile -- bekommen: ${s.texte.map { it.text }}")
  }

  /**
   * UND PER VORGABE STEHT NIRGENDWO ETWAS. All four label options start at `null`
   * (`GPAbstractOption` keeps no initial value and `TaskColumnEnumerationOption` sets none), so a
   * fresh GanttProject draws no text around a bar at all — neither beside it nor on it; nothing in
   * `TaskActivitySceneBuilder` writes any. Whoever has never opened the settings page sees the
   * merged row exactly as the bars alone, and S9 concerns them not at all.
   */
  @Test
  fun `ohne eingestellte beschriftung steht auf der zeile gar nichts`() {
    val d = Diagramm()
    val (gruppe, _) = d.serie("Umsatzsteuervoranmeldung")
    d.schalterAn()

    val s = d.zeichnen(listOf(gruppe))
    assertEquals(3, balken(s).size, "die Balken sind da")
    assertEquals(emptyList<String>(), s.texte.map { it.text },
      "aber keine Beschriftung, weil keine eingestellt ist")
  }
}
