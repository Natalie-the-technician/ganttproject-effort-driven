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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * THE MODEL of the home office: weekly pattern, periods, and the union of the two.
 *
 * Pure calculation, no GanttProject types and no screen — the same shape as [WorkWeekScheduleTest].
 * What is measured here is the ARITHMETIC of the answer; where it is stored is
 * [HomeOfficeStorageTest] and how it is entered is [HomeOfficePanelTest].
 *
 * THE TWO SENTENCES THIS FILE EXISTS TO HOLD DOWN:
 *
 *  1. NOTHING ENTERED MEANS NO HOME OFFICE — `false`, on every day, and never `null`. This is the
 *     deliberate difference from [WorkWeekSchedule.worksOn], and it is the one an unwary change
 *     would undo by „making the two consistent".
 *  2. THE END OF A PERIOD IS EXCLUSIVE. `2026-06-01..2026-06-04` is the 1st, 2nd and 3rd. Both
 *     boundary days are named in the tests below, because an off-by-one shows only at the edges.
 */
class HomeOfficeTest {

  /** Mon 1 June 2026 to Sun 7 June 2026 — the week every period test below lives in. */
  private val MO = LocalDate.of(2026, 6, 1)
  private val DI = LocalDate.of(2026, 6, 2)
  private val MI = LocalDate.of(2026, 6, 3)
  private val DO = LocalDate.of(2026, 6, 4)
  private val FR = LocalDate.of(2026, 6, 5)

  private fun muster(text: String) = HomeOfficeWeek.parse(text).week
  private fun zeitraeume(text: String) = HomeOfficePeriods.parse(text).periods
  private fun heimarbeit(musterText: String = "", zeitraumText: String = "") =
    HomeOffice(muster(musterText), zeitraeume(zeitraumText))

  // ═══ 1. NICHTS EINGETRAGEN ═══

  /**
   * NOTHING ENTERED IS NO HOME OFFICE — not „unknown", not the project calendar.
   *
   * The answer is a plain `Boolean`, which is why this test cannot even ASK for null: the type
   * says it. What it can check is that the answer is `false` and not `true`, on days of every
   * shape — a weekday, a weekend, far in the past, far in the future.
   */
  @Test
  fun `ohne eintrag arbeitet niemand zu hause, an keinem tag`() {
    val nichts = heimarbeit()
    assertTrue(nichts.isEmpty, "ohne Eintrag muss das Modell leer sein")
    listOf(MO, DI, MI, DO, FR,
           LocalDate.of(2026, 6, 6), LocalDate.of(2026, 6, 7),
           LocalDate.of(1970, 1, 1), LocalDate.of(2099, 12, 31)).forEach { tag ->
      assertFalse(nichts.worksFromHome(tag),
        "ohne Eintrag darf kein Tag Heimarbeit sein, $tag (${tag.dayOfWeek}) ist es aber")
    }
    assertEquals(0, nichts.daysFromHome(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)),
      "ohne Eintrag darf in einem ganzen Jahr kein Heimarbeitstag herauskommen")
  }

  /**
   * The counterpart, and the reason the sentence above is worth a test of its own: the SISTER type
   * answers `null` for the same situation. If somebody ever „unifies" the two, this pair goes red.
   */
  @Test
  fun `nichts eingetragen heisst hier false und bei der arbeitswoche null`() {
    assertFalse(heimarbeit().worksFromHome(FR),
      "Heimarbeit ohne Eintrag muss false sein - nicht unbekannt")
    assertEquals(null, WorkWeekSchedule.parse("").schedule.worksOn(FR),
      "die Arbeitswoche ohne Eintrag muss weiterhin null sein - sonst ist die andere Seite kaputt")
  }

  // ═══ 2. DAS WOCHENMUSTER ═══

  /** „Every Friday at home" — the Friday is, the Thursday is not. */
  @Test
  fun `jeden freitag zu hause - der freitag ist es, der donnerstag nicht`() {
    val jedenFreitag = heimarbeit(musterText = "5")
    assertTrue(jedenFreitag.worksFromHome(FR), "Freitag, der 5.6.2026, muss Heimarbeit sein")
    assertFalse(jedenFreitag.worksFromHome(DO), "Donnerstag, der 4.6.2026, darf es nicht sein")
    // Not merely one Friday: the pattern is weekly, so the next one is one too.
    assertTrue(jedenFreitag.worksFromHome(FR.plusWeeks(1)), "auch der Freitag darauf")
    // plusWeeks and NOT plusYears: plusYears keeps the DATE, so 2029-06-05 is a Tuesday and the
    // assertion would be asking whether a Tuesday is a Friday. Caught by the break run of
    // 04.09.2026, where this went red for a reason that had nothing to do with the break.
    assertTrue(jedenFreitag.worksFromHome(FR.plusWeeks(160)),
      "und ein Freitag gut drei Jahre spaeter (${FR.plusWeeks(160)}) - ein Muster hat kein Ende")
    assertFalse(jedenFreitag.worksFromHome(DO.plusWeeks(1)), "der Donnerstag darauf aber nicht")
  }

  /** Sunday is a weekday like any other here — 7 is a number the pattern accepts. */
  @Test
  fun `auch der sonntag laesst sich als heimarbeitstag eintragen`() {
    val sonntags = heimarbeit(musterText = "7")
    assertTrue(sonntags.worksFromHome(LocalDate.of(2026, 6, 7)), "Sonntag, der 7.6.2026")
    assertFalse(sonntags.worksFromHome(LocalDate.of(2026, 6, 6)), "Samstag, der 6.6.2026")
  }

  /**
   * A SECTION TAKES EFFECT ON ITS BOUNDARY DAY ITSELF — not a day early, not a day late.
   *
   * Two Fridays are asked about, the one before the boundary and the one after; and then the
   * boundary day itself, which is a Monday and belongs to the new section from its first instant.
   */
  @Test
  fun `ein abschnitt greift am grenztag selbst`() {
    // No home office at all until 1 June; from 1 June every Friday. 1 June 2026 is a Monday.
    val abGrenztag = heimarbeit(musterText = ":; 2026-06-01: 5")
    assertEquals(LocalDate.of(2026, 6, 1), MO, "Voraussetzung: der 1.6.2026 ist der Montag oben")

    assertFalse(abGrenztag.worksFromHome(FR.minusWeeks(1)),
      "der Freitag VOR dem Grenztag (${FR.minusWeeks(1)}) faellt noch unter den leeren Abschnitt")
    assertTrue(abGrenztag.worksFromHome(FR),
      "der erste Freitag NACH dem Grenztag ($FR) muss schon zum neuen Abschnitt gehoeren")

    // And the boundary day itself: it is a Monday, so it is not a home-office day here -- but the
    // section in force ON it must already be the new one. Asked directly, so that a section which
    // began a day late would be caught even where the weekday hides it.
    assertEquals(setOf(DayOfWeek.FRIDAY), abGrenztag.week.sectionOn(MO)?.days,
      "am Grenztag selbst muss bereits der neue Abschnitt gelten")
    assertEquals(emptySet<DayOfWeek>(), abGrenztag.week.sectionOn(MO.minusDays(1))?.days,
      "einen Tag vor dem Grenztag muss noch der alte Abschnitt gelten")
  }

  /**
   * A SECTION WITH NO DAY ENDS AN ARRANGEMENT, and that is the opposite of what an empty section
   * means for a working week. „Every Friday at home, and from 1 June no longer."
   */
  @Test
  fun `ein abschnitt ohne tag beendet die heimarbeit statt sie unbekannt zu machen`() {
    val beendet = heimarbeit(musterText = "5; 2026-06-01:")
    assertTrue(beendet.worksFromHome(LocalDate.of(2026, 5, 29)), "Freitag vor dem 1.6.")
    assertFalse(beendet.worksFromHome(FR), "Freitag nach dem 1.6. - die Heimarbeit ist beendet")
    assertFalse(beendet.isEmpty,
      "der beendende Abschnitt ist ein Eintrag und darf nicht als „nichts eingetragen\" gelten")
  }

  // ═══ 3. DIE ZEITRAEUME, UND DAS EXKLUSIVE ENDE ═══

  /**
   * MON TO WED AT HOME — three days, and THURSDAY IS NOT ONE OF THEM.
   *
   * The single most important arithmetic in this file. `2026-06-01..2026-06-04` names the 4th and
   * the 4th is NOT in the period; both boundary days are asked about by name, because an
   * off-by-one is invisible everywhere else.
   */
  @Test
  fun `montag bis mittwoch sind drei tage und der donnerstag gehoert nicht dazu`() {
    val zeitraum = heimarbeit(zeitraumText = "2026-06-01..2026-06-04")

    assertTrue(zeitraum.worksFromHome(MO), "der Montag 1.6. ist der erste Tag und gehoert dazu")
    assertTrue(zeitraum.worksFromHome(DI), "der Dienstag 2.6.")
    assertTrue(zeitraum.worksFromHome(MI), "der Mittwoch 3.6. ist der letzte Tag")
    assertFalse(zeitraum.worksFromHome(DO),
      "der Donnerstag 4.6. steht im Text, das Ende ist aber EXKLUSIV - er darf NICHT dazugehoeren")
    assertFalse(zeitraum.worksFromHome(LocalDate.of(2026, 5, 31)),
      "der Sonntag vor dem Anfang gehoert nicht dazu")

    assertEquals(3, zeitraum.daysFromHome(LocalDate.of(2026, 5, 25), LocalDate.of(2026, 6, 15)),
      "es muessen genau drei Tage sein")
    assertEquals(3, zeitraeume("2026-06-01..2026-06-04").periods.single().days,
      "auch der Zeitraum selbst muss drei Tage zaehlen")
  }

  /** The last day a person is SHOWN is the third, not the fourth. Stored exclusive, shown inclusive. */
  @Test
  fun `der letzte tag eines zeitraums ist der dritte, nicht der vierte`() {
    assertEquals(MI, zeitraeume("2026-06-01..2026-06-04").periods.single().lastDay,
      "gezeigt wird der letzte Tag, und der ist der 3.6.")
  }

  /** A bare date is the single day it names, and a start equal to the end is the same shorthand. */
  @Test
  fun `ein einzelnes datum und ein gleiches ende sind derselbe eine tag`() {
    listOf("2026-06-01", "2026-06-01..2026-06-01").forEach { text ->
      val ergebnis = HomeOfficePeriods.parse(text)
      assertFalse(ergebnis.hasErrors, "„$text\" soll ohne Klage gelesen werden: ${ergebnis.errors}")
      val einTag = HomeOffice(periods = ergebnis.periods)
      assertTrue(einTag.worksFromHome(MO), "„$text\" muss den 1.6. umfassen")
      assertFalse(einTag.worksFromHome(DI), "„$text\" darf den 2.6. nicht umfassen")
      assertEquals(1, ergebnis.periods.periods.single().days, "„$text\" ist genau ein Tag")
    }
  }

  /**
   * A REVERSED PERIOD IS REPORTED AND THE LINE IS DROPPED WHOLE — the readable lines around it
   * stand. Correcting it silently would be correcting a value nobody can check.
   */
  @Test
  fun `ein verdrehter zeitraum wird gemeldet und die zeile ganz verworfen`() {
    val ergebnis = HomeOfficePeriods.parse("2026-06-01..2026-06-04; 2026-07-10..2026-07-01")
    assertTrue(ergebnis.hasErrors, "der verdrehte Zeitraum muss gemeldet werden")
    assertEquals(1, ergebnis.periods.periods.size,
      "nur der gute Zeitraum darf uebrig bleiben: ${ergebnis.periods}")
    assertEquals(MO, ergebnis.periods.periods.single().start, "und zwar der erste")
    assertFalse(HomeOffice(periods = ergebnis.periods).worksFromHome(LocalDate.of(2026, 7, 5)),
      "aus dem verdrehten Zeitraum darf nicht heimlich ein Zeitraum werden")
  }

  /** What is written is read back unchanged — the exclusive form survives its own round trip. */
  @Test
  fun `was geschrieben wird, liest sich unveraendert zurueck`() {
    listOf("2026-06-01..2026-06-04", "2026-06-01..2026-06-04; 2026-08-03..2026-08-08").forEach { text ->
      val einmal = zeitraeume(text)
      assertEquals(text, einmal.toString(), "die Ausgabe muss der Eingabe gleichen")
      assertEquals(einmal.toString(), zeitraeume(einmal.toString()).toString(),
        "und ein zweiter Durchgang darf nichts mehr veraendern")
    }
    // The forgiving shapes become the canonical one after one pass and then stand still.
    assertEquals("2026-06-01..2026-06-02", zeitraeume("2026-06-01").toString())
    assertEquals("2026-06-01..2026-06-04", zeitraeume("2026-06-01 .. 2026-06-04").toString())
  }

  // ═══ 4. DIE VEREINIGUNG ═══

  /**
   * PATTERN AND PERIOD AT ONCE: the UNION holds, and a day lying in BOTH counts ONCE.
   *
   * The counting is what gives this test teeth. „Every Friday" plus „Mon 1 to Wed 3 June" plus
   * „Thu 4 to Fri 5 June" — the Friday 5 June is in the pattern AND in the second period. Over the
   * week Mon 1 to Sun 7 that makes 1., 2., 3., 4., 5. = FIVE days, not six.
   */
  @Test
  fun `muster und zeitraum zugleich - die vereinigung gilt und ein tag zaehlt einmal`() {
    val beides = heimarbeit(
      musterText = "5",
      zeitraumText = "2026-06-01..2026-06-04; 2026-06-04..2026-06-06")

    assertTrue(beides.worksFromHome(MO), "Mo 1.6. nur aus dem Zeitraum")
    assertTrue(beides.worksFromHome(DI), "Di 2.6. nur aus dem Zeitraum")
    assertTrue(beides.worksFromHome(MI), "Mi 3.6. nur aus dem Zeitraum")
    assertTrue(beides.worksFromHome(DO), "Do 4.6. nur aus dem zweiten Zeitraum")
    assertTrue(beides.worksFromHome(FR), "Fr 5.6. aus BEIDEN - Muster und Zeitraum")
    assertFalse(beides.worksFromHome(LocalDate.of(2026, 6, 6)),
      "Sa 6.6. ist das exklusive Ende des zweiten Zeitraums und gehoert nicht dazu")
    assertFalse(beides.worksFromHome(LocalDate.of(2026, 6, 7)), "So 7.6. gehoert nirgends dazu")

    assertEquals(5, beides.daysFromHome(MO, MO.plusDays(7)),
      "in der Woche vom 1.6. muessen es FUENF Tage sein - der Freitag liegt in beidem und zaehlt einmal")

    // And the week after, where only the pattern still applies: exactly one day, the Friday.
    assertEquals(1, beides.daysFromHome(MO.plusWeeks(1), MO.plusWeeks(2)),
      "in der Woche darauf gilt nur noch das Muster, also genau ein Tag")
  }

  /**
   * The same day named twice by two OVERLAPPING PERIODS is also one day. Counting reasons instead
   * of days would show up here first.
   */
  @Test
  fun `zwei ueberlappende zeitraeume ergeben keinen doppelten tag`() {
    val ueberlappend = heimarbeit(zeitraumText = "2026-06-01..2026-06-04; 2026-06-02..2026-06-05")
    assertEquals(4, ueberlappend.daysFromHome(LocalDate.of(2026, 5, 25), LocalDate.of(2026, 6, 15)),
      "1., 2., 3. und 4.6. - vier Tage, nicht sieben")
  }

  /** Half-open counting: the range asked about has an exclusive end too. */
  @Test
  fun `auch der gezaehlte bereich hat ein exklusives ende`() {
    val jedenFreitag = heimarbeit(musterText = "5")
    assertEquals(0, jedenFreitag.daysFromHome(FR, FR), "ein leerer Bereich zaehlt nichts")
    assertEquals(1, jedenFreitag.daysFromHome(FR, FR.plusDays(1)), "der Freitag allein")
    assertEquals(1, jedenFreitag.daysFromHome(MO, FR.plusDays(1)), "Mo bis einschliesslich Fr")
    assertEquals(0, jedenFreitag.daysFromHome(MO, FR), "Mo bis AUSSCHLIESSLICH Fr - kein Freitag darin")
  }
}
