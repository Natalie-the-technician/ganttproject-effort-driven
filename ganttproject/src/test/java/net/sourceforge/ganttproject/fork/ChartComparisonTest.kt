/*
Copyright 2026

NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.

This file is part of GanttProject, an opensource project management tool.

GanttProject is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

GanttProject is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
*/
package net.sourceforge.ganttproject.fork

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * Die beiden Vergleichsregeln des Bandes unter dem Vorgangsbalken.
 *
 * Die Zahlen im Aufwandsteil sind KEINE ausgedachten: sie stammen aus dem Plan, an dem der
 * Befund vom 20.08.2026 gemessen wurde — Tagesleistung 1,8 Stunden gegen die Vorgabe von 8,
 * Faktor 4,44. Genau dieser Fall darf im Aufwandsvergleich NICHT mehr rot werden, denn der
 * Aufwand hat sich dabei um keine Stunde geaendert.
 */
class ChartComparisonTest {

  private fun tag(iso: String): Date =
    Date.from(LocalDate.parse(iso).atStartOfDay(ZoneId.systemDefault()).toInstant())

  // ---- Termin: Ende gegen Ende ------------------------------------------------------------

  @Test
  fun `gleiches Ende heisst planmaessig und zeichnet nichts`() {
    assertEquals(Vergleichsbefund.KEIN_BAND, terminVergleich(tag("2026-08-15"), tag("2026-08-15")))
  }

  @Test
  fun `spaeteres Ende ist eine Verspaetung`() {
    assertEquals(Vergleichsbefund.MEHR, terminVergleich(tag("2026-08-15"), tag("2026-08-22")))
  }

  @Test
  fun `frueheres Ende ist ein Vorsprung`() {
    assertEquals(Vergleichsbefund.WENIGER, terminVergleich(tag("2026-08-15"), tag("2026-08-08")))
  }

  @Test
  fun `nur verschoben zaehlt als Verspaetung, denn das Ende ist ein anderes`() {
    // Die Regel vom 17.08.2026 haette hier "gleiche Dauer, also neutral" gesagt. Das war der
    // Versuch, mit einer Anzeige zwei Fragen zu beantworten. Fuer "liege ich im Zeitplan" ist
    // ein um eine Woche spaeteres Ende eine Verspaetung, ganz gleich wie lang der Vorgang ist.
    assertEquals(Vergleichsbefund.MEHR, terminVergleich(tag("2026-08-07"), tag("2026-08-14")))
  }

  // ---- Aufwand: Ist-Stunden gegen die urspruengliche Schaetzung ----------------------------

  @Test
  fun `ohne urspruengliche Schaetzung gibt es keinen Massstab`() {
    assertEquals(Vergleichsbefund.KEIN_BAND, aufwandVergleich(null, 12.0))
    assertEquals(Vergleichsbefund.KEIN_BAND, aufwandVergleich(0.0, 12.0))
  }

  @Test
  fun `Schaetzung ohne erfasste Zeit ist neutral, nicht planmaessig`() {
    assertEquals(Vergleichsbefund.NEUTRAL, aufwandVergleich(8.0, null))
    assertEquals(Vergleichsbefund.NEUTRAL, aufwandVergleich(8.0, 0.0))
  }

  @Test
  fun `mehr Stunden gebraucht`() {
    assertEquals(Vergleichsbefund.MEHR, aufwandVergleich(9.0, 15.0))
  }

  @Test
  fun `weniger Stunden gebraucht`() {
    assertEquals(Vergleichsbefund.WENIGER, aufwandVergleich(24.0, 0.368))
  }

  @Test
  fun `gleich viele Stunden zeichnen nichts`() {
    assertEquals(Vergleichsbefund.KEIN_BAND, aufwandVergleich(16.0, 16.0))
  }

  @Test
  fun `eine halbe Minute Unterschied ist kein Unterschied`() {
    assertEquals(Vergleichsbefund.KEIN_BAND, aufwandVergleich(16.0, 16.0 + 0.5 / 60.0))
  }

  @Test
  fun `zwei Minuten Unterschied sind einer`() {
    assertEquals(Vergleichsbefund.MEHR, aufwandVergleich(16.0, 16.0 + 2.0 / 60.0))
  }

  @Test
  fun `die Kapazitaetsverteilung faerbt den Aufwandsvergleich nicht`() {
    // Der gemessene Fall: Aufwand 24 Stunden, Dauer wechselt von 3 auf 14 Tage, weil die
    // Tagesleistung von 8 auf 1,8 Stunden gesetzt wurde. Erfasst sind 24 Stunden.
    // Die Dauer hat sich vervierfacht, der Aufwand nicht — also kein Band.
    assertEquals(Vergleichsbefund.KEIN_BAND, aufwandVergleich(24.0, 24.0))
  }

  // ---- Stilnamen --------------------------------------------------------------------------

  @Test
  fun `die Stilnamen sind die des Originals`() {
    assertEquals("later", Vergleichsbefund.MEHR.stilName())
    assertEquals("earlier", Vergleichsbefund.WENIGER.stilName())
    assertNull(Vergleichsbefund.NEUTRAL.stilName())
    assertNull(Vergleichsbefund.KEIN_BAND.stilName())
  }
}
