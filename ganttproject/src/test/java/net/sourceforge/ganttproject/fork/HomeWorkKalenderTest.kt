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

import biz.ganttproject.core.chart.canvas.Canvas
import biz.ganttproject.core.chart.canvas.Painter
import biz.ganttproject.core.chart.grid.Offset
import biz.ganttproject.core.chart.scene.CapacityHeatmapSceneBuilder
import biz.ganttproject.core.time.impl.GPTimeUnitStack
import net.sourceforge.ganttproject.chart.ChartUIConfiguration
import net.sourceforge.ganttproject.chart.StyledPainterImpl
import net.sourceforge.ganttproject.gui.UIConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.time.LocalDate
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ═══ ONE CAN SEE THAT IT IS NOT A HOLIDAY ═══
 *
 * Natalie's sentence has two halves and B3 only answered the first: „das wäre wie ein Tag Urlaub zu
 * handhaben, NUR IM KALENDER SOLL ES ANDERST DARGESTELLT WERDEN, andere Farbe oder so." This is the
 * check on the second half, and it asks the question at the two levels where an answer can be had:
 *
 *  * THE STYLE. The scene builder puts a name on every rectangle, and the name is what decides
 *    which painter gets it. A home-working day and a holiday must not carry the same one.
 *  * THE PIXELS. A style is only a promise. The real painter is run onto a real image and the
 *    colours are read back out of it, because two different styles CAN be painted the same and
 *    „different name, same picture" is precisely the failure nobody would notice.
 *
 * ═══ WHY THE PIXEL CHECKS MEASURE BRIGHTNESS AND NOT ONLY THE COLOUR ═══
 *
 * Two colours that differ in hue can be the same shade of grey, and then they are one colour to a
 * person who does not tell them apart, on a black-and-white printout and on a washed-out projector.
 * So the distance is measured in the greyscale brightness the eye actually weights
 * (0.299 R + 0.587 G + 0.114 B) and not in „the RGB triples are unequal", which every pair of
 * colours satisfies and which therefore checks nothing.
 *
 * The threshold is 40 of 255. Not a rule from anywhere — it is a step that stays visible after a
 * projector and a photocopier, and it is worth stating that everything the resource chart shows
 * TODAY fails it against each other: white background 255, weekend column 238, holiday band 243.
 * The band added here sits at 141.
 *
 * ═══ AND THE SECOND CUE ═══
 *
 * Colour alone is a weak sign. `die schraffur` below is the check that the home-working band
 * carries a second one — diagonal hatching — and that the holiday band deliberately does not, so
 * that „striped or plain" answers the question with no colour at all.
 */
class HomeWorkKalenderTest {

  private val montag = LocalDate.of(2026, 9, 7)

  // =============================================================================================
  // Level 1 — the styles the scene builder hands out.
  // =============================================================================================

  /** Collects what the canvas would hand to a painter. The real path, not a peek into a field. */
  private class Sammler : Painter {
    val rechtecke = mutableListOf<Rechteck>()
    var texte = 0
    override fun prePaint() {}
    override fun paint(rectangle: Canvas.Rectangle) {
      rechtecke.add(Rechteck(rectangle.style, rectangle.leftX, rectangle.width))
    }
    override fun paint(line: Canvas.Line) {}
    override fun paint(next: Canvas.Text) { texte++ }
    override fun paint(textGroup: Canvas.TextGroup) {}
    override fun paint(rhombus: Canvas.Rhombus) {}
  }

  private data class Rechteck(val stil: String?, val links: Int, val breite: Int)

  private val tagBreite = 20
  private val zeilenHoehe = 20

  /** One offset per day, 14 days from the Sunday before [montag]. See `OffsetLookup.getBounds`. */
  private val ersterOffsetTag = montag.minusDays(1)

  private val offsets: List<Offset> = (0 until 14).map { i ->
    val start = ersterOffsetTag.plusDays(i.toLong())
    Offset.createFullyClosed(
      GPTimeUnitStack.DAY, ersterOffsetTag.toModelDate(),
      start.toModelDate(), start.plusDays(1).toModelDate(),
      i * tagBreite, (i + 1) * tagBreite, 0)
  }

  private fun last(von: LocalDate, bis: LocalDate, wert: Float, vorgang: Int? = null) =
    CapacityHeatmapSceneBuilder.Load(
      von.toModelDate().time, bis.toModelDate().time, wert, vorgang)

  private fun zeichnen(vararg lasten: CapacityHeatmapSceneBuilder.Load): Sammler {
    val canvas = Canvas()
    CapacityHeatmapSceneBuilder(
      object : CapacityHeatmapSceneBuilder.InputApi {
        override fun getYCanvasOffset() = 0
        override fun getRowHeight() = zeilenHoehe
        override fun getChartWidth() = offsets.size * tagBreite
        override fun getChartStartDate(): Date = ersterOffsetTag.toModelDate()
        override fun getChartEndDate(): Date = offsets.last().offsetEnd
        override fun getOffsets(): List<Offset> = offsets
      },
      listOf(CapacityHeatmapSceneBuilder.Resource(lasten.toList())),
      canvas
    ).build()
    return Sammler().also { canvas.paint(it) }
  }

  private val urlaubstag = last(LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 9),
    CapacityHeatmapSceneBuilder.DAY_OFF_LOAD)
  private val heimarbeitstag = last(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 11),
    CapacityHeatmapSceneBuilder.HOME_WORK_LOAD)
  private val arbeitstag = last(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 15), 100f, 1)

  /**
   * THE CHECK THIS PACKAGE EXISTS FOR. Everything else here guards it; this one states it.
   */
  @Test
  fun `ein heimarbeitstag traegt einen anderen stil als ein urlaubstag`() {
    val gezeichnet = zeichnen(urlaubstag, heimarbeitstag)
    val stile = gezeichnet.rechtecke.mapNotNull { it.stil }
    assertTrue(CapacityHeatmapSceneBuilder.STYLE_DAY_OFF in stile,
      "das Urlaubsband fehlt ganz; gezeichnet wurde: ${gezeichnet.rechtecke}")
    assertTrue(CapacityHeatmapSceneBuilder.STYLE_HOME_WORK in stile,
      "das Heimarbeitsband fehlt ganz; gezeichnet wurde: ${gezeichnet.rechtecke}")
    assertNotEquals(CapacityHeatmapSceneBuilder.STYLE_DAY_OFF,
      CapacityHeatmapSceneBuilder.STYLE_HOME_WORK)
  }

  @Test
  fun `die drei faelle tragen drei verschiedene stile`() {
    val gezeichnet = zeichnen(urlaubstag, heimarbeitstag, arbeitstag)
    val stilJeLinks = gezeichnet.rechtecke.associate { it.links to it.stil }
    val urlaubLinks = 2 * tagBreite
    val heimLinks = 4 * tagBreite
    val arbeitLinks = 8 * tagBreite
    assertEquals(CapacityHeatmapSceneBuilder.STYLE_DAY_OFF, stilJeLinks[urlaubLinks],
      "an der Urlaubsstelle: ${gezeichnet.rechtecke}")
    assertEquals(CapacityHeatmapSceneBuilder.STYLE_HOME_WORK, stilJeLinks[heimLinks],
      "an der Heimarbeitsstelle: ${gezeichnet.rechtecke}")
    assertTrue(stilJeLinks[arbeitLinks]?.startsWith("load.") == true,
      "an der Arbeitsstelle stand ${stilJeLinks[arbeitLinks]}: ${gezeichnet.rechtecke}")
    assertEquals(3, setOf(stilJeLinks[urlaubLinks], stilJeLinks[heimLinks], stilJeLinks[arbeitLinks]).size)
  }

  /** -2 is a marker, not a load. Printed as a percentage it would read „-200 %". */
  @Test
  fun `ein heimarbeitstag bekommt keinen prozentsatz`() {
    assertEquals(0, zeichnen(heimarbeitstag).texte)
    assertTrue(zeichnen(arbeitstag).texte > 0, "die Gegenprobe: eine echte Last schreibt ihre Zahl")
  }

  // =============================================================================================
  // The guard: a plan without home working looks exactly as it did.
  // =============================================================================================

  /**
   * NOT „the canvas equals a list I wrote down myself" — that would only check that I copied the
   * present behaviour correctly. The same scene is built TWICE, once with the home-working band and
   * once without, and the demand is that the second one is contained in the first UNCHANGED.
   *
   * That is what breaks when the marker is left in the ordinary load list: -2 is added to the 100 %
   * of the same days, the load rectangle becomes 98 % and the two runs no longer agree. A plan
   * without home working keeps its own half of the check green all the same, because it never
   * produces the marker at all.
   */
  @Test
  fun `ein heimarbeitsband nimmt der zeichnung nichts weg`() {
    val ohne = zeichnen(urlaubstag, arbeitstag).rechtecke
    val mit = zeichnen(urlaubstag, arbeitstag, heimarbeitstag).rechtecke
    ohne.forEach { vorher ->
      assertTrue(vorher in mit,
        "$vorher ist verschwunden oder verrutscht.\nohne: $ohne\nmit:  $mit")
    }
    val neu = mit.filter { it !in ohne }
    assertEquals(listOf(CapacityHeatmapSceneBuilder.STYLE_HOME_WORK), neu.map { it.stil },
      "hinzugekommen sind: $neu")
  }

  @Test
  fun `ein plan ohne heimarbeit hat kein einziges heimarbeitsrechteck`() {
    val gezeichnet = zeichnen(urlaubstag, arbeitstag)
    assertFalse(gezeichnet.rechtecke.any { it.stil == CapacityHeatmapSceneBuilder.STYLE_HOME_WORK },
      "gezeichnet wurde: ${gezeichnet.rechtecke}")
    assertTrue(gezeichnet.rechtecke.isNotEmpty(),
      "die Gegenprobe: ohne Heimarbeit wird trotzdem etwas gezeichnet")
  }

  // =============================================================================================
  // Level 2 — the pixels. A style is a promise; this is the picture.
  // =============================================================================================

  private val bildBreite = 120
  private val bildHoehe = 40

  /** Paints one rectangle of [stil] onto white and returns the image. The real painter, no double. */
  private fun malen(stil: String): BufferedImage {
    val bild = BufferedImage(bildBreite, bildHoehe, BufferedImage.TYPE_INT_RGB)
    val g = bild.createGraphics()
    g.color = Color.WHITE
    g.fillRect(0, 0, bildBreite, bildHoehe)
    val config = ChartUIConfiguration(UIConfiguration(Color.BLUE, false))
    config.rowHeight = zeilenHoehe
    val painter = StyledPainterImpl(config)
    painter.setGraphics(g)
    val canvas = Canvas()
    val rechteck = canvas.createRectangle(10, 10, 100, zeilenHoehe)
    rechteck.style = stil
    painter.paint(rechteck)
    g.dispose()
    return bild
  }

  private fun helligkeit(rgb: Int): Int {
    val c = Color(rgb)
    return (0.299 * c.red + 0.587 * c.green + 0.114 * c.blue).roundToInt()
  }

  private fun beschreibe(name: String, rgb: Int): String {
    val c = Color(rgb)
    return String.format("%s rgb(%d,%d,%d) #%02X%02X%02X Helligkeit %d",
      name, c.red, c.green, c.blue, c.red, c.green, c.blue, helligkeit(rgb))
  }

  /** The middle of the painted band, clear of the border it draws around itself. */
  private fun mitte(bild: BufferedImage) = bild.getRGB(60, 10 + zeilenHoehe / 2)

  /**
   * THE PICTURE, NOT THE PROMISE. Two styles can be painted the same; this is where that would
   * show.
   */
  @Test
  fun `urlaub und heimarbeit sind auf dem bild verschiedene farben`() {
    val urlaub = mitte(malen(CapacityHeatmapSceneBuilder.STYLE_DAY_OFF))
    val heim = mitte(malen(CapacityHeatmapSceneBuilder.STYLE_HOME_WORK))
    assertNotEquals(urlaub, heim,
      "${beschreibe("Urlaub", urlaub)} gegen ${beschreibe("Heimarbeit", heim)}")
    assertTrue(abs(helligkeit(urlaub) - helligkeit(heim)) >= MINDESTABSTAND,
      "zu nah beieinander: ${beschreibe("Urlaub", urlaub)} gegen ${beschreibe("Heimarbeit", heim)}")
  }

  @Test
  fun `heimarbeit hebt sich auch vom leeren arbeitstag ab`() {
    val leer = Color.WHITE.rgb
    val heim = mitte(malen(CapacityHeatmapSceneBuilder.STYLE_HOME_WORK))
    assertTrue(abs(helligkeit(leer) - helligkeit(heim)) >= MINDESTABSTAND,
      "zu nah beieinander: ${beschreibe("leerer Arbeitstag", leer)} gegen ${beschreibe("Heimarbeit", heim)}")
  }

  /**
   * The weekend column and the public holiday column of `chart.properties`, which the band is
   * painted over. Read from the file rather than typed in here, so that a change to it is noticed.
   */
  @Test
  fun `heimarbeit hebt sich vom wochenende und vom feiertag ab`() {
    val eigenschaften = java.util.Properties()
    val strom = checkNotNull(
      StyledPainterImpl::class.java.getResourceAsStream("/resources/chart.properties")) {
      "chart.properties liegt nicht im Klassenpfad des Tests"
    }
    strom.use { eigenschaften.load(it) }
    val heim = mitte(malen(CapacityHeatmapSceneBuilder.STYLE_HOME_WORK))
    listOf("calendar.weekend.background-color", "calendar.holiday.background-color").forEach { key ->
      val text = checkNotNull(eigenschaften.getProperty(key)) { "$key steht nicht in chart.properties" }
      val farbe = Color.decode(text.trim()).rgb
      assertTrue(abs(helligkeit(farbe) - helligkeit(heim)) >= MINDESTABSTAND,
        "zu nah beieinander: ${beschreibe(key, farbe)} gegen ${beschreibe("Heimarbeit", heim)}")
    }
  }

  /**
   * THE SECOND CUE, and it is the one that survives without any colour at all. A horizontal cut
   * through the home-working band meets the hatching and therefore more than one colour; the same
   * cut through the holiday band meets exactly one.
   */
  @Test
  fun `die schraffur unterscheidet die beiden auch ohne farbe`() {
    val y = 10 + zeilenHoehe / 2
    val heimFarben = (20 until 100).map { malenZwischenspeicher(CapacityHeatmapSceneBuilder.STYLE_HOME_WORK).getRGB(it, y) }.toSet()
    val urlaubFarben = (20 until 100).map { malenZwischenspeicher(CapacityHeatmapSceneBuilder.STYLE_DAY_OFF).getRGB(it, y) }.toSet()
    assertTrue(heimFarben.size >= 2,
      "das Heimarbeitsband ist einfarbig, also ohne zweites Merkmal: ${heimFarben.map { beschreibe("", it) }}")
    assertEquals(1, urlaubFarben.size,
      "das Urlaubsband ist nicht mehr einfarbig, damit traegt 'gestreift oder nicht' nichts mehr: " +
        "${urlaubFarben.map { beschreibe("", it) }}")
  }

  private val bilder = mutableMapOf<String, BufferedImage>()
  private fun malenZwischenspeicher(stil: String) = bilder.getOrPut(stil) { malen(stil) }

  companion object {
    /**
     * How far apart two greyscale brightnesses have to be, out of 255. See the class comment: not a
     * rule from anywhere, but a step that survives a projector and a photocopier — and one that
     * every pair of colours the chart shows today fails against each other.
     */
    const val MINDESTABSTAND = 40
  }
}
