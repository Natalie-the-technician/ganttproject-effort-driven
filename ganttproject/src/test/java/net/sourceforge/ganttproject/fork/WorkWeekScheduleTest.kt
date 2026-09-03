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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The working week of a person over time -- the pure model.
 *
 * Sister of [CapacityScheduleTest]. [CapacitySchedule] answers HOW MANY HOURS a person delivers on
 * a day; this one answers WHETHER the day is a working day for them at all. The two are separate on
 * purpose: hours and weekdays are two questions, and a person who drops from eight hours to six has
 * not thereby stopped working on Fridays.
 *
 * THE ONE DECISION THIS FILE IS BUILT AROUND: nothing entered does NOT mean "Monday to Friday". It
 * means "no statement -- ask the project calendar". Hence [WorkWeekSchedule.worksOn] returns
 * `Boolean?` and not `Boolean`. Were it `false`, every existing plan would change its behaviour the
 * moment somebody swaps the version, without anybody having entered anything; were it `true`, every
 * weekend would silently become working time. The default "Mon Tue Wed Thu Fri" is a matter for the
 * user interface and does not belong in the model.
 */
class WorkWeekScheduleTest {

  private fun plan(text: String) = WorkWeekSchedule.parse(text)

  /** 2026-03-01 is a Sunday; every date in this file is derived from that and named as such. */
  private val sonntag1Maerz: LocalDate = LocalDate.of(2026, 3, 1)

  // ---- Nothing entered ---------------------------------------------------------------------

  /**
   * THE CENTRAL CHECK. Not `false`, not `true` -- `null`.
   *
   * `assertNull` alone would also pass against a method that always returns null, so the plan with
   * a section is asked in the same breath: there the answer is a real yes/no.
   */
  @Test
  fun `ohne eintrag gibt es keine angabe, weder ja noch nein`() {
    val leer = plan("")
    assertTrue(leer.schedule.isEmpty)
    assertTrue(leer.errors.isEmpty())
    listOf(
      LocalDate.of(2026, 3, 2),  // Montag
      LocalDate.of(2026, 3, 7),  // Samstag
      sonntag1Maerz,             // Sonntag
    ).forEach { tag ->
      assertNull(leer.schedule.worksOn(tag),
        "$tag (${tag.dayOfWeek}) muss unbeantwortet bleiben: ohne Eintrag gilt der Projektkalender, " +
          "nicht Montag bis Freitag")
    }
    // Counter-check, so that the assertion above cannot be satisfied by a method that always
    // answers null: with a section there IS an answer.
    assertNotNull(plan("2026-03-01: 1").schedule.worksOn(LocalDate.of(2026, 3, 2)))
  }

  /** The same for `null` text -- a person who has never had the column filled in. */
  @Test
  fun `auch ohne spaltenwert gibt es keine angabe`() {
    assertNull(WorkWeekSchedule.parse(null).schedule.worksOn(sonntag1Maerz))
  }

  /**
   * The requirement stated verbatim: BEFORE the boundary nothing was entered, from the boundary on
   * Mon/Tue/Wed applies. Both halves in one plan.
   */
  @Test
  fun `vor dem ersten abschnitt gilt weiterhin keine angabe`() {
    val p = plan("2026-03-01: 1,2,3").schedule
    assertNull(p.worksOn(LocalDate.of(2026, 2, 23)), "ein Montag VOR dem Abschnitt")
    assertEquals(true, p.worksOn(LocalDate.of(2026, 3, 2)), "ein Montag NACH dem Abschnitt")
  }

  // ---- The boundary day ---------------------------------------------------------------------

  /**
   * Three consecutive days with three different answers -- the only shape in which a boundary can
   * be pinned from both sides.
   *
   * 2026-03-19 is a Thursday, and the section names Thursday alone. So:
   *   Wed 18.  no statement   -- were the section to take effect a day EARLY, this would be false
   *   Thu 19.  yes            -- were it to take effect a day LATE, this would be null
   *   Fri 20.  no
   */
  @Test
  fun `der abschnitt greift am grenztag selbst`() {
    val p = plan("2026-03-19: 4").schedule
    val mittwoch = LocalDate.of(2026, 3, 18)
    val donnerstag = LocalDate.of(2026, 3, 19)
    val freitag = LocalDate.of(2026, 3, 20)
    assertEquals(DayOfWeek.THURSDAY, donnerstag.dayOfWeek, "die Annahme ueber den Grenztag")

    assertNull(p.worksOn(mittwoch),
      "der Tag VOR der Grenze gehoert noch nicht dazu -- der Abschnitt greift einen Tag zu frueh")
    assertEquals(true, p.worksOn(donnerstag),
      "der Grenztag selbst gehoert schon dazu -- der Abschnitt greift einen Tag zu spaet")
    assertEquals(false, p.worksOn(freitag))
  }

  /**
   * The same question where a second section replaces a first one. Both weeks give an answer here,
   * so the mistake cannot hide behind a `null`.
   *
   * From 1.3. Mon-Fri, from Thursday 19.3. only Mon+Tue:
   *   Wed 18.  yes  -- a section taking effect a day early would say no
   *   Thu 19.  no   -- a section taking effect a day late would say yes
   */
  @Test
  fun `auch beim wechsel zwischen zwei abschnitten greift der grenztag selbst`() {
    val p = plan("2026-03-01: 1,2,3,4,5; 2026-03-19: 1,2").schedule
    assertEquals(true, p.worksOn(LocalDate.of(2026, 3, 18)), "Mittwoch, der Tag vor der Grenze")
    assertEquals(false, p.worksOn(LocalDate.of(2026, 3, 19)), "Donnerstag, der Grenztag selbst")
    assertEquals(true, p.worksOn(LocalDate.of(2026, 3, 12)), "der Donnerstag eine Woche davor")
    assertEquals(false, p.worksOn(LocalDate.of(2026, 3, 26)), "der Donnerstag eine Woche danach")
  }

  // ---- Sunday --------------------------------------------------------------------------------

  /**
   * Sunday is a weekday like any other. The model knows no weekend, and that is the point: whoever
   * works Saturday and Sunday must be able to say so.
   */
  @Test
  fun `sonntag ist ankreuzbar und wird nicht als wochenende verschluckt`() {
    assertEquals(DayOfWeek.SUNDAY, sonntag1Maerz.dayOfWeek, "die Annahme ueber den 1. Maerz 2026")
    val p = plan("2026-03-01: 6,7").schedule
    assertEquals(true, p.worksOn(sonntag1Maerz), "Sonntag, der 1. Maerz")
    assertEquals(true, p.worksOn(LocalDate.of(2026, 3, 7)), "Samstag, der 7. Maerz")
    assertEquals(true, p.worksOn(LocalDate.of(2026, 3, 8)), "Sonntag, der 8. Maerz")
    assertEquals(false, p.worksOn(LocalDate.of(2026, 3, 9)), "Montag, der 9. Maerz")
  }

  /** 7 is Sunday and 1 is Monday -- ISO, and it is written down because everything hangs on it. */
  @Test
  fun `die ziffern folgen der iso zaehlung`() {
    assertEquals(1, DayOfWeek.MONDAY.value)
    assertEquals(7, DayOfWeek.SUNDAY.value)
    val nurSonntag = plan("2026-03-01: 7").schedule
    assertEquals(true, nurSonntag.worksOn(sonntag1Maerz))
    assertEquals(false, nurSonntag.worksOn(LocalDate.of(2026, 3, 2)), "Montag ist nicht die 7")
  }

  /** The requirement from the brief, written out: Mon, Tue, Fri, Sat -- and nothing else. */
  @Test
  fun `montag dienstag freitag samstag laesst sich eintragen`() {
    val p = plan("2026-03-01: 1,2,5,6").schedule
    val erwartet = mapOf(
      DayOfWeek.MONDAY to true, DayOfWeek.TUESDAY to true, DayOfWeek.WEDNESDAY to false,
      DayOfWeek.THURSDAY to false, DayOfWeek.FRIDAY to true, DayOfWeek.SATURDAY to true,
      DayOfWeek.SUNDAY to false)
    (2..8).forEach { tagImMaerz ->
      val tag = LocalDate.of(2026, 3, tagImMaerz)
      assertEquals(erwartet[tag.dayOfWeek], p.worksOn(tag), "$tag ist ein ${tag.dayOfWeek}")
    }
  }

  // ---- Format --------------------------------------------------------------------------------

  @Test
  fun `mehrere abschnitte, auch unsortiert eingegeben`() {
    val p = plan("2026-05-01: 1,2,3; 2026-03-01: 1,2,3,4,5").schedule
    assertEquals(true, p.worksOn(LocalDate.of(2026, 3, 19)), "Donnerstag im ersten Abschnitt")
    assertEquals(false, p.worksOn(LocalDate.of(2026, 5, 7)), "Donnerstag im zweiten Abschnitt")
  }

  @Test
  fun `zeilenumbruch und gleichheitszeichen sind erlaubt`() {
    val p = plan("2026-03-01 = 1,2\n2026-05-01: 1,2,3").schedule
    assertEquals(2, p.changes.size)
    assertEquals(true, p.worksOn(LocalDate.of(2026, 5, 6)), "Mittwoch im zweiten Abschnitt")
  }

  @Test
  fun `leerzeichen zwischen den ziffern stoeren nicht`() {
    val p = plan("2026-03-01: 1, 2 ,5").schedule
    assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.FRIDAY), p.changes.single().days)
  }

  @Test
  fun `bei doppeltem datum gewinnt der spaetere eintrag`() {
    val p = plan("2026-03-01: 1,2; 2026-03-01: 5,6").schedule
    assertEquals(1, p.changes.size)
    assertEquals(false, p.worksOn(LocalDate.of(2026, 3, 2)), "Montag steht nur im ersten Eintrag")
    assertEquals(true, p.worksOn(LocalDate.of(2026, 3, 6)), "Freitag steht im spaeteren")
  }

  /**
   * An entry WITHOUT a date applies from the beginning of the plan.
   *
   * WHY THIS EXISTS: the plain case -- "this person works Mon, Tue, Fri, Sat, and always has" --
   * would otherwise have to invent a start date, and every plan would carry a boundary that stands
   * for nothing. The dated sections are the exception, not the rule.
   */
  @Test
  fun `ein eintrag ohne datum gilt von anfang an`() {
    val p = plan("1,2,5,6").schedule
    assertEquals(true, p.worksOn(LocalDate.of(1970, 1, 5)), "ein Montag lange vor jedem Plan")
    assertEquals(true, p.worksOn(LocalDate.of(2099, 1, 5)), "und ein Montag lange danach")
    assertEquals(false, p.worksOn(LocalDate.of(2026, 3, 4)), "Mittwoch steht nicht drin")
  }

  @Test
  fun `ein eintrag ohne datum laesst sich von einem datierten abloesen`() {
    val p = plan("1,2,3,4,5; 2026-03-01: 1,2,3").schedule
    assertEquals(true, p.worksOn(LocalDate.of(2026, 2, 26)), "Donnerstag davor")
    assertEquals(false, p.worksOn(LocalDate.of(2026, 3, 5)), "Donnerstag danach")
  }

  @Test
  fun `der text laesst sich wieder lesen`() {
    listOf("1,2,5,6", "2026-03-01: 1,2,5,6", "1,2,3,4,5; 2026-03-01: 1,2,3", "2026-05-01:").forEach {
      val einmal = plan(it).schedule
      val nochmal = WorkWeekSchedule.parse(einmal.toString())
      assertTrue(nochmal.errors.isEmpty(), "der eigene Text muss lesbar sein: „$einmal\" aus „$it\"")
      assertEquals(einmal.changes, nochmal.schedule.changes, "aus „$it\" wurde „$einmal\"")
    }
  }

  // ---- Errors, and that they are NOT swallowed ------------------------------------------------

  @Test
  fun `ein kaputter eintrag zerstoert die uebrigen nicht`() {
    val ergebnis = plan("2026-03-01: 1,2; kaputt; 2026-05-01: 3,4")
    assertEquals(1, ergebnis.errors.size, "gemeldet wurde: ${ergebnis.errors}")
    assertTrue(ergebnis.hasErrors)
    assertEquals(2, ergebnis.schedule.changes.size, "die beiden heilen Abschnitte bleiben stehen")
    assertEquals(true, ergebnis.schedule.worksOn(LocalDate.of(2026, 3, 2)), "Montag im ersten")
    assertEquals(true, ergebnis.schedule.worksOn(LocalDate.of(2026, 5, 6)), "Mittwoch im zweiten")
  }

  @Test
  fun `ein deutsches datum wird abgelehnt, weil es mehrdeutig ist`() {
    val ergebnis = plan("01.03.2026: 1,2")
    assertEquals(1, ergebnis.errors.size, "gemeldet wurde: ${ergebnis.errors}")
    assertTrue(ergebnis.schedule.isEmpty)
  }

  /**
   * A wrong day number does NOT merely fall out of the set -- it takes the whole section with it.
   *
   * WHY SO HARSH: „2026-03-01: 1,8" with the 8 silently dropped would leave a person who works
   * Monday ONLY. That looks like a deliberate entry and is a typo. A section one cannot read is a
   * section one must not compute with, and outside it the previous state goes on applying -- here
   * that is `null`, the project calendar.
   */
  @Test
  fun `eine falsche tagesziffer nimmt den ganzen abschnitt mit`() {
    val ergebnis = plan("2026-03-01: 1,8")
    assertEquals(1, ergebnis.errors.size, "gemeldet wurde: ${ergebnis.errors}")
    assertNull(ergebnis.schedule.worksOn(LocalDate.of(2026, 3, 2)),
      "aus „1,8\" darf keine Woche mit nur Montag werden")
  }

  @Test
  fun `die null und die acht liegen ausserhalb, die eins und die sieben nicht`() {
    assertEquals(1, plan("2026-03-01: 0").errors.size, "0 ist kein Wochentag")
    assertEquals(1, plan("2026-03-01: 8").errors.size, "8 ist kein Wochentag")
    assertEquals(1, plan("2026-03-01: -1").errors.size)
    assertEquals(1, plan("2026-03-01: Montag").errors.size)
    // Counter-check: exactly at the two limits it is valid.
    assertTrue(plan("2026-03-01: 1").errors.isEmpty())
    assertTrue(plan("2026-03-01: 7").errors.isEmpty())
  }

  /**
   * The error messages come from the fork's bundle and carry the offending text. Without this the
   * previous test would also pass against a list of empty strings.
   */
  @Test
  fun `die meldung nennt, was nicht gelesen werden konnte`() {
    assertTrue(plan("2026-03-01: Montag").errors.single().contains("Montag"),
      "die Meldung nennt den Text nicht: ${plan("2026-03-01: Montag").errors}")
    assertTrue(plan("01.03.2026: 1").errors.single().contains("01.03.2026"),
      "die Meldung nennt das Datum nicht: ${plan("01.03.2026: 1").errors}")
  }

  // ---- The empty section: MEASUREMENT, not a decision ------------------------------------------

  /**
   * NOT DECIDED, and deliberately not decided here: whether „from 1 May on no days at all" is a
   * permissible entry or an error.
   *
   * What this test pins is only the state of affairs as it stands, so that a later decision has
   * something to move away from. Measured behaviour today: the section is ACCEPTED and means
   * „works on no day". The consequences for the two walks that could run away with it are measured
   * in [WorkWeekEmptySectionTest].
   */
  @Test
  fun `ein leerer abschnitt wird heute angenommen und heisst kein tag`() {
    val ergebnis = plan("2026-03-01: 1,2,3; 2026-05-01:")
    assertTrue(ergebnis.errors.isEmpty(), "heute ist das kein Fehler: ${ergebnis.errors}")
    assertEquals(true, ergebnis.schedule.worksOn(LocalDate.of(2026, 3, 2)), "davor: Montag")
    assertEquals(false, ergebnis.schedule.worksOn(LocalDate.of(2026, 5, 4)), "danach: auch Montag nicht")
    // And it is distinguishable from „nothing entered": null would be the project calendar.
    assertNotNull(ergebnis.schedule.worksOn(LocalDate.of(2026, 5, 4)),
      "der leere Abschnitt ist eine Aussage, keine Nicht-Aussage")
  }
}
