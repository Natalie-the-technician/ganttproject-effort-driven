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
import biz.ganttproject.core.option.DefaultFontOption
import biz.ganttproject.core.option.DefaultIntegerOption
import biz.ganttproject.core.option.FontSpec
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.core.time.impl.GPTimeUnitStack
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.chart.ChartModelImpl
import net.sourceforge.ganttproject.chart.StyledPainterImpl
import net.sourceforge.ganttproject.gui.UIConfiguration
import net.sourceforge.ganttproject.task.Task
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.File
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale
import javax.imageio.ImageIO

/**
 * ═══ DAS BILD, AUF DAS DIE GANZE KETTE HINAUSLAEUFT ═══
 *
 * Section 4.2 of the measurement report of 11.09.2026 names the one thing collapsing cannot do:
 * the collapsed bar of a monthly series runs from `min(start)` to `max(end)` and therefore SAYS
 * that work went on without a break from January to December, where twelve single days were
 * worked. Every check written for this package so far has read rectangles. This one paints them
 * with the real [StyledPainterImpl] and counts pixels, because „twelve rectangles at the right x"
 * and „the row no longer claims a year of work" are not the same statement, and only the second
 * one is what the package was built for.
 *
 * ═══ WIE GEMESSEN WIRD ═══
 *
 * Over the SAME plan, twice: once with the switch off, once on. Then, for each picture, the number
 * of PIXEL COLUMNS that carry a MARK — a pixel that is neither the white ground nor the row
 * separator.
 *
 * WHY THE SEPARATOR IS NAMED AND NOT GUESSED AT. `renderVisibleTasks` draws one grey line the full
 * width of the chart under every row (`Color.GRAY`, that is exactly 128/128/128), so a measure of
 * „not white" would answer 100 % for both pictures and check nothing.
 *
 * THE FIRST VERSION OF THIS MEASURE WAS WRONG AND THE PICTURE CAUGHT IT. It counted „carries any
 * colour", `r != g || g != b`, which excludes every grey — and the collapsed summary bar is painted
 * in BLACK (`task.supertask`, `containerRectanglePainter`). So the very bar this file exists to
 * measure counted as empty, and the check failed for a reason that had nothing to do with the
 * program. Written down because it is the failure mode of every pixel measure: the rule that keeps
 * the background out keeps the subject out too.
 *
 * The two pictures are written to `build/serie-bilder/` beside the test report so that the numbers
 * below can be looked at rather than believed.
 */
class SerieBildTest {

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

  /** Three pixels per day: a whole year fits across 1100 px, which is a real chart width. */
  private val TAGBREITE = 3
  private val BREITE = 1200
  private val HOEHE = 120

  /** Twelve monthly dates of one day each, the fifteenth of every month of 2026. */
  private val termine = (0 until 12).map { LocalDate.of(2026, 1, 15).plusMonths(it.toLong()) }

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
      chartModel.setBounds(Dimension(BREITE, HOEHE))
      chartModel.setTopTimeUnit(GPTimeUnitStack.MONTH)
      chartModel.setBottomTimeUnit(GPTimeUnitStack.DAY)
      chartModel.setBottomUnitWidth(TAGBREITE)
      chartModel.setRowHeight(20)
      chartModel.startDate = LocalDate.of(2026, 1, 1).toModelDate()
    }

    fun vorgang(name: String, start: LocalDate, tage: Int = 1, elternteil: Task? = null): Task =
      taskManager.newTaskBuilder()
        .withName(name)
        .withStartDate(start.toModelDate())
        .withDuration(taskManager.createLength(tage.toLong()))
        .let { if (elternteil == null) it else it.withParent(elternteil) }
        .build()

    fun serie(name: String): Pair<Task, List<Task>> {
      val quelleId = 4711
      val gruppe = vorgang(name, termine.first())
      val kinder = termine.map { datum ->
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

    /** Renders the row and paints it with the real painter onto white. */
    fun malen(sichtbar: List<Task>): BufferedImage {
      chartModel.setVisibleTasks(sichtbar)
      val renderer = chartModel.taskRenderer
      renderer.render()
      val bild = BufferedImage(BREITE, HOEHE, BufferedImage.TYPE_INT_RGB)
      val g = bild.createGraphics()
      g.color = Color.WHITE
      g.fillRect(0, 0, BREITE, HOEHE)
      val painter = StyledPainterImpl(chartModel.chartUIConfiguration)
      painter.setGraphics(g)
      renderer.primitiveContainer.paint(painter)
      renderer.primitiveContainer.layers.forEach { it.paint(painter) }
      g.dispose()
      return bild
    }
  }

  /** The white ground every picture here is painted on. */
  private val GRUND = Color.WHITE.rgb

  /** The row separator `renderVisibleTasks` draws under every row, the full width of the chart. */
  private val TRENNLINIE = Color.GRAY.rgb

  /** The pixel columns of [bild] that carry a mark: anything but the ground and the separator. */
  private fun markierteSpalten(bild: BufferedImage): List<Int> =
    (0 until bild.width).filter { x ->
      (0 until bild.height).any { y ->
        val rgb = bild.getRGB(x, y)
        rgb != GRUND && rgb != TRENNLINIE
      }
    }

  private fun ablegen(name: String, bild: BufferedImage): File {
    val ordner = File("build/serie-bilder").also { it.mkdirs() }
    val datei = File(ordner, name)
    ImageIO.write(bild, "png", datei)
    return datei
  }

  /**
   * THE CHECK THE WHOLE PACKAGE ANSWERS TO.
   *
   * Collapsed and switched off, the row is one unbroken bar: nearly every column between the first
   * date and the last carries colour. Switched on, the very same plan on the very same row covers
   * a small fraction of that span — twelve marks with gaps between them.
   *
   * THE TWO ENDS MUST STAY WHERE THEY WERE. The series has not moved; only the claim about what
   * lies between its ends has changed. A picture that also shifted the ends would mean the bars had
   * been drawn somewhere other than on their dates, and the check would pass for the wrong reason.
   */
  @Test
  fun `zugeklappt behauptet der balken ein ganzes jahr arbeit und danach nicht mehr`() {
    val aus = Diagramm()
    val (gruppeAus, _) = aus.serie("Umsatzsteuervoranmeldung")
    val bildAus = aus.malen(listOf(gruppeAus))
    val spaltenAus = markierteSpalten(bildAus)

    val an = Diagramm()
    val (gruppeAn, kinder) = an.serie("Umsatzsteuervoranmeldung")
    an.schalterAn()
    val bildAn = an.malen(listOf(gruppeAn))
    val spaltenAn = markierteSpalten(bildAn)

    val dateiAus = ablegen("serie-zugeklappt-schalter-aus.png", bildAus)
    val dateiAn = ablegen("serie-zugeklappt-schalter-an.png", bildAn)

    assertEquals(12, kinder.size, "zwoelf Termine")
    assertTrue(spaltenAus.isNotEmpty() && spaltenAn.isNotEmpty(),
      "beide Bilder muessen ueberhaupt etwas zeigen: $dateiAus / $dateiAn")

    val spanne = spaltenAus.last() - spaltenAus.first() + 1
    val anteilAus = 100.0 * spaltenAus.size / spanne
    val anteilAn = 100.0 * spaltenAn.size / spanne
    val bericht = ("Spanne ${spanne}px (${spaltenAus.first()}..${spaltenAus.last()}), " +
      "an laeuft von ${spaltenAn.first()} bis ${spaltenAn.last()}; " +
      "ausgefuellt: aus ${spaltenAus.size}px (%.1f%%), an ${spaltenAn.size}px (%.1f%%); " +
      "Bilder: $dateiAus $dateiAn").format(anteilAus, anteilAn)

    assertTrue(anteilAus > 90.0,
      "ausgeschaltet laeuft der Balken fast ohne Luecke durch -- $bericht")
    assertTrue(anteilAn < 25.0,
      "eingeschaltet sind es zwoelf Marken mit Luecken -- $bericht")
    // EIN PIXEL TOLERANZ AM RAND, UND ZWAR EIN GEMESSENES. Measured on 14.09.2026: off ends at
    // column 1046, on at 1047. The two are painted by different painters -- the summary bar's
    // closing bracket is a `fillPolygon` whose rightmost vertex is `rect.getRightX()`
    // (`SummaryTaskRenderer:59`), an ordinary bar is a `fillRect` that covers that column. The
    // dates have not moved; one painter stops a pixel earlier than the other. Anything beyond one
    // pixel would mean the bars were drawn somewhere other than on their dates, and that is what
    // this line is for.
    assertTrue(Math.abs(spaltenAus.first() - spaltenAn.first()) <= 1 &&
        Math.abs(spaltenAus.last() - spaltenAn.last()) <= 1,
      "beide Zeilen fangen und hoeren an derselben Stelle auf, nur dazwischen sagen sie Verschiedenes -- $bericht")

    println("[SerieBildTest] $bericht")
  }

  /**
   * DIE LUECKEN SIND ZWOELF UND NICHT EINE. Counting covered columns alone would also pass for a
   * single narrow bar somewhere. What makes the row readable as a SERIES is that the covered
   * columns fall into twelve runs, and the eleven gaps between them are the eleven months in which
   * nothing was done.
   */
  @Test
  fun `die zeile zerfaellt in zwoelf marken mit elf luecken`() {
    val d = Diagramm()
    val (gruppe, _) = d.serie("Umsatzsteuervoranmeldung")
    d.schalterAn()
    val spalten = markierteSpalten(d.malen(listOf(gruppe)))

    var laeufe = 0
    var vorige = Int.MIN_VALUE
    for (x in spalten) {
      if (x != vorige + 1) { laeufe++ }
      vorige = x
    }
    assertEquals(12, laeufe,
      "zwoelf zusammenhaengende Marken erwartet, gezaehlt: $laeufe (Spalten: ${spalten.size})")
  }
}
