/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.

This file is part of GanttProject, an opensource project management tool.
Licensed under the GNU General Public License, version 3 or later.
*/
package net.sourceforge.ganttproject.fork

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate

class RecurrenceTest {

  private val montagBisFreitag: (LocalDate) -> Boolean = {
    it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY
  }
  private val montag = LocalDate.of(2026, 8, 17)

  private fun regel(text: String) = RecurrenceRule.parse(text)

  // ---- Parsing --------------------------------------------------------------------------

  @Test
  fun `ein leeres feld ist kein fehler`() {
    val ergebnis = regel("")
    assertNull(ergebnis.rule, "keine Wiederholung")
    assertTrue(ergebnis.errors.isEmpty(), "und das ist auch kein Fehler")
  }

  @Test
  fun `die vier einheiten, deutsch und englisch`() {
    assertEquals(RecurrenceUnit.TAG, regel("taeglich; Anzahl 2").rule?.unit)
    assertEquals(RecurrenceUnit.WOCHE, regel("wöchentlich; Anzahl 2").rule?.unit)
    assertEquals(RecurrenceUnit.MONAT, regel("monthly; count 2").rule?.unit)
    assertEquals(RecurrenceUnit.JAHR, regel("jährlich; Anzahl 2").rule?.unit)
  }

  @Test
  fun `abstand und begrenzung`() {
    val r = regel("woechentlich; alle 2; bis 2027-12-31").rule
    assertNotNull(r)
    assertEquals(2, r!!.interval)
    assertEquals(LocalDate.of(2027, 12, 31), r.until)
    assertNull(r.count)
  }

  @Test
  fun `ohne begrenzung wird abgelehnt`() {
    // The decision from the class comment: a series without an end would have to invent a number.
    val ergebnis = regel("monatlich")
    assertNull(ergebnis.rule, "unbrauchbar, nicht halb brauchbar")
    assertEquals(1, ergebnis.errors.size)
  }

  @Test
  fun `zwei begrenzungen zusammen werden abgelehnt`() {
    assertNull(regel("monatlich; bis 2027-01-01; Anzahl 5").rule)
  }

  @Test
  fun `ohne einheit, unbekanntes wort, kaputte zahlen`() {
    assertNull(regel("bis 2027-01-01").rule, "es fehlt, wie oft")
    assertNull(regel("monatlich; blau; Anzahl 3").rule, "unbekanntes Feld")
    assertNull(regel("monatlich; alle null; Anzahl 3").rule)
    assertNull(regel("monatlich; Anzahl 0").rule, "null Termine sind keine Serie")
    assertNull(regel("monatlich; bis 31.12.2027").rule, "deutsches Datum ist mehrdeutig")
  }

  @Test
  fun `der eigene text laesst sich wieder lesen`() {
    listOf("monatlich; bis 2027-12-31", "woechentlich; alle 2; Anzahl 10",
      "taeglich; Anzahl 3", "jaehrlich; bis 2030-01-01").forEach { text ->
      val einmal = regel(text).rule!!
      val nochmal = RecurrenceRule.parse(einmal.toString())
      assertTrue(nochmal.errors.isEmpty(), "eigener Text unlesbar: $einmal")
      assertEquals(einmal, nochmal.rule, "Hin und zurueck fuer $text")
    }
  }

  // ---- Dates ----------------------------------------------------------------------------

  @Test
  fun `der erste termin ist immer dabei`() {
    val termine = occurrences(regel("monatlich; Anzahl 3").rule!!, montag, montagBisFreitag)
    assertEquals(3, termine.size)
    assertEquals(montag, termine.first(), "der Vorgang, der schon im Plan steht")
  }

  @Test
  fun `monatlich zaehlt kalendermonate, nicht 30 tage`() {
    val termine = occurrences(regel("monatlich; Anzahl 4").rule!!,
      LocalDate.of(2026, 1, 15), montagBisFreitag)
    assertEquals(listOf(
      LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 16), // 15.2. ist ein Sonntag -> vor
      LocalDate.of(2026, 3, 16),                            // 15.3. ist ein Sonntag -> vor
      LocalDate.of(2026, 4, 15)), termine)
  }

  @Test
  fun `ein termin auf einem freien tag rueckt vor, nicht zurueck`() {
    // Saturday, 22.8.2026 -> Monday, 24.8. Moving back would be Friday the 21st, and therefore
    // before the previous date of the series: the order would be broken.
    val samstag = LocalDate.of(2026, 8, 22)
    val termine = occurrences(regel("taeglich; alle 7; Anzahl 2").rule!!, samstag, montagBisFreitag)
    assertEquals(LocalDate.of(2026, 8, 24), termine[0])
    assertEquals(LocalDate.of(2026, 8, 31), termine[1])
  }

  @Test
  fun `zwei termine auf demselben arbeitstag zaehlen einmal`() {
    // Daily from Friday to Sunday: the raw dates are Fri, Sat, Sun -- Sat and Sun both move
    // forward onto the same Monday. Two Tasks on the same Monday with the same name would not be
    // a series but duplicated work.
    val freitag = LocalDate.of(2026, 8, 21)
    val termine = occurrences(regel("taeglich; bis 2026-08-23").rule!!, freitag, montagBisFreitag)
    assertEquals(listOf(LocalDate.of(2026, 8, 21), LocalDate.of(2026, 8, 24)), termine)
  }

  @Test
  fun `Anzahl meint wirkliche vorgaenge, nicht rohtermine`() {
    // THE FIRST VERSION COUNTED RAW DATES: "Anzahl 3" from Friday yielded only two Tasks,
    // because Saturday and Sunday fell on the same Monday. Whoever enters 3 wants 3 Tasks.
    val freitag = LocalDate.of(2026, 8, 21)
    val termine = occurrences(regel("taeglich; Anzahl 3").rule!!, freitag, montagBisFreitag)
    assertEquals(3, termine.size)
    assertEquals(listOf(LocalDate.of(2026, 8, 21), LocalDate.of(2026, 8, 24),
      LocalDate.of(2026, 8, 25)), termine)
  }

  @Test
  fun `bis gilt fuer den termin vor dem verschieben`() {
    // 31.12.2027 is a Friday; the date before it falls on 30.11. (Tuesday). If the limit were
    // checked only after the moving, the end of a series would depend on whether the last date
    // happens to fall on a holiday.
    val termine = occurrences(regel("monatlich; bis 2027-12-31").rule!!,
      LocalDate.of(2027, 10, 31), montagBisFreitag)
    // 31.10. (Sun -> 1.11.), 30.11., 31.12. -- three dates.
    assertEquals(3, termine.size)
    assertEquals(LocalDate.of(2027, 11, 1), termine[0])
    assertEquals(LocalDate.of(2027, 12, 31), termine.last())
  }

  @Test
  fun `eine zu weite begrenzung wird als abgeschnitten gemeldet`() {
    val r = regel("taeglich; bis 2099-12-31").rule!!
    assertTrue(isTruncated(r, montag, montagBisFreitag),
      "statt 20.000 Vorgaenge anzulegen, muss das gemeldet werden")
    assertEquals(RecurrenceRule.MAX_OCCURRENCES, occurrenceCount(r, montag, montagBisFreitag))
  }

  @Test
  fun `eine begrenzung vor dem ersten termin ergibt nur diesen einen`() {
    // Counter-check to the upper bound: the series may also be very short.
    val termine = occurrences(regel("monatlich; bis 2026-08-17").rule!!, montag, montagBisFreitag)
    assertEquals(listOf(montag), termine)
  }

  @Test
  fun `jaehrlich trifft den 29 februar nicht daneben`() {
    // java.time places 29.2. in a non-leap year on 28.2. -- checked so that nobody later builds
    // in a date calculation of their own that gets this wrong.
    val termine = occurrences(regel("jaehrlich; Anzahl 2").rule!!,
      LocalDate.of(2028, 2, 29), montagBisFreitag)
    assertEquals(LocalDate.of(2029, 2, 28), termine[1])
  }
}

/**
 * The estimating-quality evaluation: estimated against needed.
 *
 * The rule that determines the structure: HOURS are compared against the ORIGINAL estimate, and
 * the period over which they accrued does not matter.
 */
class EstimateQualityTest {
  private fun row(id: String, geschaetzt: Double, gebraucht: Double, fertig: Int) =
    EstimateRow(id, id, geschaetzt, gebraucht, fertig)

  @Test
  fun `der gesamtfaktor kommt aus summen, nicht aus mittelwerten`() {
    // A small outlier (20 minutes estimated, 2 hours needed: factor 6) must not distort the
    // picture. From sums: (0.33 + 40) / (0.33 + 2 ... ) -- computed below.
    val bericht = buildEstimateReport(listOf(
      row("gross", 40.0, 44.0, 100),
      row("klein", 0.33, 2.0, 100)), remainingPlannedHours = 0.0)
    // Sums: estimated 40.33, needed 46.0 -> 1.14. The mean of the individual factors would be
    // (1.1 + 6.06) / 2 = 3.58 and therefore completely misleading.
    assertEquals(1.14, bericht.overallFactor, 0.01)
  }

  @Test
  fun `nur abgeschlossene zaehlen fuer den faktor`() {
    val bericht = buildEstimateReport(listOf(
      row("fertig", 10.0, 15.0, 100),
      row("laeuft", 10.0, 2.0, 50)), remainingPlannedHours = 0.0)
    assertEquals(1, bericht.finished.size)
    assertEquals(1.5, bericht.overallFactor, 0.001)
  }

  @Test
  fun `laufende ueber der schaetzung werden getrennt gemeldet`() {
    // They are a warning, not a verdict: the Task can still become more expensive, but the
    // estimate has not been refuted yet -- it is not finished, after all.
    val bericht = buildEstimateReport(listOf(
      row("laeuft", 9.0, 15.0, 50)), remainingPlannedHours = 0.0)
    assertEquals(0, bericht.finished.size, "kein Urteil ueber die Schaetzguete")
    assertEquals(1, bericht.runningOver.size, "aber sichtbar")
  }

  @Test
  fun `die hochrechnung uebertraegt den faktor auf das offene`() {
    val bericht = buildEstimateReport(listOf(
      row("a", 10.0, 20.0, 100)), remainingPlannedHours = 100.0)
    assertEquals(2.0, bericht.overallFactor, 0.001)
    assertEquals(200.0, bericht.remainingExpectedHours, 0.001)
  }

  @Test
  fun `ohne grundlage wird nichts behauptet`() {
    val bericht = buildEstimateReport(emptyList(), remainingPlannedHours = 50.0)
    assertEquals(false, bericht.hasBasis)
    assertEquals(1.0, bericht.overallFactor, 0.001, "ohne Messung wird nichts hochgerechnet")
  }

  @Test
  fun `genau getroffen ist kein ueberzug`() {
    // Counter-check: otherwise every Task would stand in the list of overruns.
    val bericht = buildEstimateReport(listOf(row("a", 8.0, 8.0, 100)), 0.0)
    assertTrue(bericht.overruns.isEmpty())
    assertEquals(1.0, bericht.overallFactor, 0.001)
  }
}
