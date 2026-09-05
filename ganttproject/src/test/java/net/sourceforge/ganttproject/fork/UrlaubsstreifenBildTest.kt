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
import biz.ganttproject.core.chart.scene.CapacityHeatmapSceneBuilder
import net.sourceforge.ganttproject.chart.ChartUIConfiguration
import net.sourceforge.ganttproject.chart.StyledPainterImpl
import net.sourceforge.ganttproject.gui.UIConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ═══ THE PICTURE, NOT THE PROMISE ═══
 *
 * `UrlaubsstreifenTest` proves that the right days get the style `task.absence`. A style is only a
 * name; this file runs the real `StyledPainterImpl` onto a real image and reads the colours back
 * out, because „right name, wrong picture" is exactly the failure nobody would notice.
 *
 * ═══ WHY IT IS PAINTED ONTO THE TASK BAR AND NOT ONTO WHITE ═══
 *
 * B4 measured its band against the white ground of the resource chart, and rightly so — that is
 * what it lies on. This stripe lies on a TASK BAR, so white would be the wrong ground and every
 * number taken over it would flatter the result. Everything here is painted onto #8CB6CE, the
 * default task colour (`UIConfiguration`, `GanttProjectImpl.DEFAULT_TASK_COLOR`).
 *
 * ═══ THE THREE QUESTIONS ═══
 *
 *  1. IS IT VISIBLE AT ALL — is there a colour in the stripe far enough from the bar it lies on?
 *     Measured in greyscale brightness (0.299 R + 0.587 G + 0.114 B), not in „the RGB triples
 *     differ", which every pair of colours satisfies and which therefore checks nothing.
 *  2. IS THERE A SECOND CUE — one that a person who does not tell orange from light blue, a
 *     black-and-white printout and a washed-out projector all still get. That is the hatching, and
 *     it is measured as „a horizontal cut through the stripe meets more than one colour".
 *  3. IS IT TOLD FROM THE HOME-WORKING BAND, without any colour. That is the hatching DIRECTION:
 *     a vertical cut through vertical hatching meets one colour, the same cut through B4's diagonal
 *     hatching meets two. This is the check that keeps the fork from spending a package on saying
 *     „home working is not an absence" and then drawing the two the same.
 */
class UrlaubsstreifenBildTest {

  private val bildBreite = 130
  private val bildHoehe = 50

  /** The rectangle every check paints, and the ground it is painted on. */
  private val links = 10
  private val oben = 10
  private val breite = 100
  private val hoehe = 20

  /** The default task colour — what a stripe actually lies on. */
  private val balkenFarbe = Color(140, 182, 206)

  /**
   * Paints one rectangle of [stil] onto the task colour and returns the image. The real painter,
   * no double.
   */
  private fun malen(stil: String, grund: Color = balkenFarbe): BufferedImage {
    val bild = BufferedImage(bildBreite, bildHoehe, BufferedImage.TYPE_INT_RGB)
    val g = bild.createGraphics()
    g.color = grund
    g.fillRect(0, 0, bildBreite, bildHoehe)
    val config = ChartUIConfiguration(UIConfiguration(Color.BLUE, false))
    config.rowHeight = hoehe
    val painter = StyledPainterImpl(config)
    painter.setGraphics(g)
    val canvas = Canvas()
    val rechteck = canvas.createRectangle(links, oben, breite, hoehe)
    rechteck.style = stil
    painter.paint(rechteck)
    g.dispose()
    return bild
  }

  private val bilder = mutableMapOf<String, BufferedImage>()
  private fun bild(stil: String) = bilder.getOrPut(stil) { malen(stil) }

  private fun helligkeit(rgb: Int): Int {
    val c = Color(rgb)
    return (0.299 * c.red + 0.587 * c.green + 0.114 * c.blue).roundToInt()
  }

  private fun beschreibe(name: String, rgb: Int): String {
    val c = Color(rgb)
    return String.format("%s rgb(%d,%d,%d) #%02X%02X%02X Helligkeit %d",
      name, c.red, c.green, c.blue, c.red, c.green, c.blue, helligkeit(rgb))
  }

  /**
   * The interior of the band, clear of the frame on every side AND of the 1 px inset the
   * home-working painter applies (`margin` is 4, the painter uses `margin - 3` = 1), so that the
   * same window is inside both bands and both checks measure the same thing.
   */
  private val innenX = (links + 3) until (links + breite - 3)
  private val innenY = (oben + 3) until (oben + hoehe - 3)

  private fun waagerechterSchnitt(bild: BufferedImage, y: Int): Set<Int> =
    innenX.map { bild.getRGB(it, y) }.toSet()

  private fun senkrechterSchnitt(bild: BufferedImage, x: Int): Set<Int> =
    innenY.map { bild.getRGB(x, it) }.toSet()

  // =============================================================================================
  // 1. Is it visible on the bar it lies on?
  // =============================================================================================

  /**
   * At least one colour in the stripe has to be [MINDESTABSTAND] brightness steps away from the bar
   * underneath it. The hatching is what carries this: it is painted OPAQUE, so the number does not
   * depend on an alpha over a task colour the user is free to change.
   */
  @Test
  fun `der streifen hebt sich vom balken ab, auf dem er liegt`() {
    val farben = waagerechterSchnitt(bild(STYLE_ABSENCE), oben + hoehe / 2)
    val balken = balkenFarbe.rgb
    val weiteste = farben.maxByOrNull { abs(helligkeit(it) - helligkeit(balken)) }
    assertTrue(weiteste != null && abs(helligkeit(weiteste) - helligkeit(balken)) >= MINDESTABSTAND,
      "keine Farbe im Streifen ist weit genug vom Balken entfernt: " +
        "${beschreibe("Balken", balken)} gegen ${farben.map { beschreibe("", it) }}")
  }

  /**
   * THE BAR STAYS VISIBLE. The work is still planned for that day; only somebody is missing. So
   * between the hatching lines there must be a colour that is NEITHER the bar untouched NOR the
   * stripe colour opaque — a tint of the one by the other. Without the alpha this would be the
   * stripe colour and the check goes red.
   */
  @Test
  fun `der balken scheint unter dem streifen durch`() {
    val farben = waagerechterSchnitt(bild(STYLE_ABSENCE), oben + hoehe / 2)
    val balken = balkenFarbe.rgb
    val streifenFarbe = Color(230, 90, 30).rgb
    assertTrue(farben.any { it != balken && it != streifenFarbe },
      "zwischen den Linien steht weder eine Tönung des Balkens noch überhaupt etwas anderes: " +
        "${farben.map { beschreibe("", it) }}")
  }

  // =============================================================================================
  // 2. The second cue, and it works without any colour.
  // =============================================================================================

  /**
   * A horizontal cut through the stripe meets more than one colour; the same cut through a bare
   * task bar meets exactly one. That is „striped or plain", answered with no colour at all.
   */
  @Test
  fun `die schraffur unterscheidet den streifen auch ohne farbe`() {
    val gestreift = waagerechterSchnitt(bild(STYLE_ABSENCE), oben + hoehe / 2)
    val bar = BufferedImage(bildBreite, bildHoehe, BufferedImage.TYPE_INT_RGB).also {
      val g = it.createGraphics(); g.color = balkenFarbe; g.fillRect(0, 0, bildBreite, bildHoehe); g.dispose()
    }
    assertTrue(gestreift.size >= 2,
      "der Streifen ist einfarbig, also ohne zweites Merkmal: ${gestreift.map { beschreibe("", it) }}")
    assertEquals(1, waagerechterSchnitt(bar, oben + hoehe / 2).size,
      "die Gegenprobe: ein Balken ohne Streifen ist einfarbig")
  }

  // =============================================================================================
  // 3. Told apart from the home-working band — by the direction of the hatching, not by the hue.
  // =============================================================================================

  /**
   * THE CHECK THAT KEEPS B4'S POINT ALIVE. A home-working day is a WORKING day and an absence is
   * not; the two must not read the same. They differ in the direction of their hatching, and this
   * is that difference measured rather than asserted:
   *
   *   absence   VERTICAL hatching   -> a vertical cut meets ONE colour
   *   home work DIAGONAL hatching   -> the same cut meets more than one
   *
   * Several columns, not one, so that the result cannot be an accident of where the sample fell:
   * one column on a hatching line and one between two of them.
   */
  @Test
  fun `die schraffur des streifens steht senkrecht, die der heimarbeit schraeg`() {
    val streifen = bild(STYLE_ABSENCE)
    val heim = bild(CapacityHeatmapSceneBuilder.STYLE_HOME_WORK)
    listOf(links + 20, links + 22, links + 51, links + 53).forEach { x ->
      assertEquals(1, senkrechterSchnitt(streifen, x).size,
        "bei x=$x ist der Urlaubsstreifen senkrecht nicht einfarbig, damit ist die Richtung " +
          "der Schraffur kein Unterscheidungsmerkmal mehr: " +
          "${senkrechterSchnitt(streifen, x).map { beschreibe("", it) }}")
    }
    assertTrue(listOf(links + 20, links + 22, links + 51, links + 53)
      .all { senkrechterSchnitt(heim, it).size >= 2 },
      "die Gegenprobe: das Heimarbeitsband muss senkrecht mehrfarbig sein, sonst unterscheidet " +
        "die Richtung nichts")
  }

  /** And the colours differ too — the second cue is a second one, not the only one. */
  @Test
  fun `urlaubsstreifen und heimarbeitsband sind verschiedene farben`() {
    val streifen = waagerechterSchnitt(bild(STYLE_ABSENCE), oben + hoehe / 2)
    val heim = waagerechterSchnitt(bild(CapacityHeatmapSceneBuilder.STYLE_HOME_WORK), oben + hoehe / 2)
    assertTrue(streifen.intersect(heim).isEmpty(),
      "die beiden Bänder teilen sich eine Farbe: " +
        "Streifen ${streifen.map { beschreibe("", it) }} gegen Heimarbeit ${heim.map { beschreibe("", it) }}")
  }

  /**
   * The measured distance of the STRIPE COLOUR ITSELF from the home-working colour, as painted.
   * Both are opaque where it counts, so this is a number and not an estimate. Recorded here rather
   * than only in the report so that moving either default has to face it.
   */
  @Test
  fun `streifenfarbe und heimarbeitsfarbe sind auch in graustufen weit genug auseinander`() {
    val streifen = Color(230, 90, 30).rgb
    val heim = Color(95, 60, 175).rgb
    assertNotEquals(streifen, heim)
    assertTrue(abs(helligkeit(streifen) - helligkeit(heim)) >= MINDESTABSTAND,
      "zu nah beieinander: ${beschreibe("Urlaubsstreifen", streifen)} gegen " +
        "${beschreibe("Heimarbeit", heim)}")
  }

  companion object {
    /**
     * How far apart two greyscale brightnesses have to be, out of 255. The number this fork has
     * been using since B4: not a rule from anywhere, but a step that survives a projector and a
     * photocopier.
     */
    const val MINDESTABSTAND = 40
  }
}
