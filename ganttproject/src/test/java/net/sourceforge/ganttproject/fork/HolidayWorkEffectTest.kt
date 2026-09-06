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

import biz.ganttproject.core.calendar.CalendarEvent
import biz.ganttproject.core.calendar.GPCalendar.DayMask
import biz.ganttproject.core.calendar.WeekendCalendarImpl
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.task.ResourceAssignment
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * A4 — WHAT THE SWITCH DOES TO A PLAN, on a project with a real calendar, real people and real
 * tasks.
 *
 * `HolidayWorkTest` checks the arithmetic on bare mask bits; this file checks that the arithmetic
 * is actually reached from where the planning asks — [WorkWeekWorkingDays], which is the object
 * every one of the seven production call sites builds, and [workingDayTest] beneath it.
 *
 * THE DATES: 17.8.2026 is a Monday, so 19.8. is the Wednesday, 22.8. the Saturday and 23.8. the
 * Sunday of that week. The public holiday used throughout is the Wednesday — a weekday, so that
 * „the switch moved it" cannot be confused with „the weekend moved it" — and one test puts a
 * second one on the Saturday, which is the only day on which the two questions meet.
 *
 * ═══ THE ORDER OF THE FILE ═══
 *
 * The guard comes first and it is the longest part, because it is the check that matters most and
 * the one that is green before the change as well. A check nobody has watched fail proves nothing;
 * how these were armed — four deliberate breaks, each watched go red — is in the report of this
 * session, `2026-09-05-a4-gebaut.md`.
 */
class HolidayWorkEffectTest {

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

  private val montag = LocalDate.of(2026, 8, 17)
  private val dienstag = LocalDate.of(2026, 8, 18)
  private val mittwoch = LocalDate.of(2026, 8, 19)
  private val donnerstag = LocalDate.of(2026, 8, 20)
  private val freitag = LocalDate.of(2026, 8, 21)
  private val samstag = LocalDate.of(2026, 8, 22)
  private val sonntag = LocalDate.of(2026, 8, 23)

  /** A project with a real resource manager, a real calendar and real Tasks. */
  private class Projekt {
    val kalender = WeekendCalendarImpl()
    val builder: TestSetupHelper.TaskManagerBuilder =
      TestSetupHelper.newTaskManagerBuilder().withCalendar(kalender)
    val taskManager: TaskManager = builder.build()
    val resourceManager: HumanResourceManager = builder.resourceManager
    val resourceProperties: CustomPropertyManager get() = resourceManager.customPropertyManager
    val taskProperties: CustomPropertyManager get() = taskManager.customPropertyManager

    fun person(name: String, id: Int, woche: String? = null): HumanResource =
      resourceManager.create(name, id).also { person ->
        person.setValue(
          EffortDrivenProperties.findOrCreateResourceHours(resourceProperties), 8.0)
        if (woche != null) {
          person.setWorkWeek(resourceProperties, WorkWeekSchedule.parse(woche).schedule)
        }
      }

    fun vorgang(name: String, start: LocalDate, aufwand: Double = 8.0): Task =
      taskManager.newTaskBuilder().withName(name).withStartDate(start.toModelDate())
        .withDuration(taskManager.createLength(1L)).build().also {
          it.customValues.setValue(
            EffortDrivenProperties.findOrCreateTaskEffort(taskProperties), aufwand)
        }

    fun zuordnen(vorgang: Task, person: HumanResource, last: Float = 100f): ResourceAssignment =
      vorgang.assignmentCollection.addAssignment(person).also { it.load = last }

    /** [monatNullBasiert] as `java.util.Calendar` counts months: August is 7. */
    fun feiertage(vararg tage: Triple<Int, Int, Int>) {
      kalender.setPublicHolidays(tage.map { (jahr, monatNullBasiert, tag) ->
        CalendarEvent.newEvent(
          CalendarFactory.createGanttCalendar(jahr, monatNullBasiert, tag).time, false,
          CalendarEvent.Type.HOLIDAY, "Feiertag", null)
      })
    }

    /** The switch, as the program sets it. */
    fun feiertagsarbeit(an: Boolean) = resourceProperties.setAllowHolidayWork(an)

    /** The day grid this task actually gets — the production path, all seven call sites build it. */
    fun gitter(vorgang: Task): (LocalDate) -> Boolean =
      WorkWeekWorkingDays(taskManager.calendar, resourceProperties).forTask(vorgang)

    /** The days the task may use between [von] and [bis], both inclusive. */
    fun belegteTage(vorgang: Task, von: LocalDate, bis: LocalDate): List<LocalDate> {
      val test = gitter(vorgang)
      return generateSequence(von) { if (it.isBefore(bis)) it.plusDays(1) else null }
        .filter(test).toList()
    }

    fun dauer(vorgang: Task, start: LocalDate): Int? =
      vorgang.durationDaysWithDaysOff(taskProperties, resourceProperties, start, gitter(vorgang))
  }

  // ---- DIE NICHTS-AENDERT-SICH-WACHE ------------------------------------------------------------

  /**
   * THE MOST IMPORTANT CHECK OF THIS PACKAGE. The default is off, so no plan that exists may move
   * by a single day — and this check is green before A4 as well, which is exactly why it had to be
   * broken on purpose before it was believed.
   *
   * 400 DAYS FROM A MONDAY IN THE PAST, and the range therefore crosses 57 weekends, both public
   * holidays set below and a year boundary. A rule that is wrong only for some shape of day cannot
   * hide in it. The same span the A2 guard uses, on purpose: the two are the same guarantee about
   * the same grid, one weekday at a time.
   */
  @Test
  fun `ausgeschaltet ist das raster tagesgleich mit dem projektkalender`() {
    val projekt = Projekt()
    projekt.feiertage(Triple(2026, 7, 19), Triple(2026, 7, 22), Triple(2026, 11, 25))
    val person = projekt.person("Natalie", 0)
    val vorgang = projekt.vorgang("Arbeit", montag)
    projekt.zuordnen(vorgang, person)

    // THE REFERENCE IS THE RAW MASK AND NOT `workingDayTest`, and that is the whole difference
    // between a guard and a decoration. Written against `workingDayTest(calendar)` this test was
    // GREEN under break 1 of the report (`HolidayRule.OFF` flipped to ON): both sides of the
    // comparison then read the same broken rule and moved together. `mask and WORKING != 0` is
    // the answer the program gave before A4 existed, spelled out here so that nothing this
    // package can change is able to change the reference too.
    val vorher: (LocalDate) -> Boolean = { tag ->
      projekt.taskManager.calendar.getDayMask(tag.toModelDate()) and DayMask.WORKING != 0
    }
    val nachher = projekt.gitter(vorgang)

    val abweichungen = generateSequence(montag) { it.plusDays(1) }.take(400)
      .filter { vorher(it) != nachher(it) }.toList()
    assertEquals(emptyList<LocalDate>(), abweichungen,
      "der Schalter steht auf AUS -- das Raster muss auf den Tag genau das des Projektkalenders " +
        "bleiben, sonst verschiebt A4 bestehende Plaene")
    assertTrue(abweichungen.isEmpty() && !nachher(mittwoch),
      "Gegenkontrolle, damit die Zeile darueber nicht leer besteht: der Mittwoch IST ein " +
        "Feiertag in diesem Plan, und er muss frei sein")
  }

  /**
   * The same guarantee once more where it is FELT rather than where it is computed: in the
   * duration. A grid that is right day by day and a duration that is not would still be a plan
   * that moved.
   */
  @Test
  fun `ausgeschaltet bleibt die dauer ueber einen feiertag hinweg dieselbe`() {
    val projekt = Projekt()
    projekt.feiertage(Triple(2026, 7, 19))
    val person = projekt.person("Natalie", 0)
    val vorgang = projekt.vorgang("Arbeit", montag, aufwand = 40.0)
    projekt.zuordnen(vorgang, person)

    assertEquals(5, projekt.dauer(vorgang, montag),
      "40 Stunden bei 8 h/Tag sind fuenf Arbeitstage; der Mittwoch ist ein Feiertag und wird " +
        "uebersprungen, nicht mitgezaehlt -- genau die Zahl, die vor A4 herauskam")
  }

  /**
   * AND THE GUARD WHERE THE DATE IS ACTUALLY WRITTEN. `endAfterWorkingDays` is what the levelling
   * write-back turns a duration into an end with, and it is the one path on which the fork's own
   * grid — not the project calendar — decides the date a person sees. If the switch leaked into it
   * while standing on AUS, levelled plans would move.
   */
  @Test
  fun `ausgeschaltet endet ein vorgang ueber den feiertag hinweg, wo er immer geendet hat`() {
    val projekt = Projekt()
    projekt.feiertage(Triple(2026, 7, 19))
    val person = projekt.person("Natalie", 0)
    val vorgang = projekt.vorgang("Arbeit", montag, aufwand = 24.0)
    projekt.zuordnen(vorgang, person)

    assertEquals(LocalDate.of(2026, 8, 21), endAfterWorkingDays(montag, 3, projekt.gitter(vorgang)),
      "drei Arbeitstage ab Montag: Montag, Dienstag, Donnerstag -- der Mittwoch ist Feiertag, " +
        "also ist der Freitag das Ende (ausschliessend)")
  }

  /**
   * THE GUARD THE ONE ABOVE CANNOT SEE, and it is here because of what the order warned about:
   * a watch that misses two of four breaks by construction.
   *
   * `ausgeschaltet ist das raster tagesgleich mit dem projektkalender` uses a person who has
   * entered NO working week, and such a task takes the fast path in [WorkWeekWorkingDays] — it
   * gets the project calendar test itself, so the A2 arithmetic is never executed at all and a
   * mistake in it cannot show up. Break [HolidayRule.isFreeForWeekdayReasons] so that a holiday
   * counts as „free for weekday reasons" with the switch OFF, and that test stays green while
   * every holiday in a plan quietly becomes workable for anybody whose week names the weekday.
   * Measured: break 3 of the report.
   *
   * So this one puts a working week in that names EVERY weekday — the strongest possible pull on
   * the extension half of A2 — and holds the holidays free anyway. The Saturdays are asserted as
   * WORKING in the same breath, so the test cannot pass by the grid being empty.
   */
  @Test
  fun `ausgeschaltet bleibt ein feiertag frei, auch wenn jeder beteiligte an dem wochentag arbeitet`() {
    val projekt = Projekt()
    projekt.feiertage(Triple(2026, 7, 19), Triple(2026, 7, 22), Triple(2026, 11, 25))
    val vorgang = projekt.vorgang("Arbeit", montag)
    projekt.zuordnen(vorgang, projekt.person("Natalie", 0, woche = "1,2,3,4,5,6,7"))
    projekt.zuordnen(vorgang, projekt.person("Olaf", 1, woche = "1,2,3,4,5,6,7"))

    val raster = projekt.gitter(vorgang)
    listOf(mittwoch, samstag, LocalDate.of(2026, 12, 25)).forEach { feiertag ->
      assertFalse(raster(feiertag),
        "$feiertag ist ein Feiertag und der Schalter steht auf AUS -- er darf nicht dadurch " +
          "fallen, dass jeder Beteiligte an dem Wochentag arbeitet. Das ist die Grenze zu A2.")
    }
    assertTrue(raster(sonntag),
      "Gegenkontrolle: der Sonntag ist kein Feiertag, und beide arbeiten sonntags -- er MUSS " +
        "fallen, sonst ist die Zeile oben leer bestanden")
    assertTrue(raster(LocalDate.of(2026, 8, 29)),
      "Gegenkontrolle: der Samstag darauf ist kein Feiertag und faellt ueber die Arbeitswoche")
  }

  // ---- ANGESCHALTET: DER FEIERTAG BEWEGT SICH ---------------------------------------------------

  @Test
  fun `angeschaltet ist der feiertag ein arbeitstag`() {
    val projekt = Projekt()
    projekt.feiertage(Triple(2026, 7, 19))
    val person = projekt.person("Natalie", 0)
    val vorgang = projekt.vorgang("Arbeit", montag)
    projekt.zuordnen(vorgang, person)

    assertFalse(projekt.gitter(vorgang)(mittwoch), "Vorbedingung: ausgeschaltet ist er frei")
    projekt.feiertagsarbeit(true)
    assertTrue(projekt.gitter(vorgang)(mittwoch),
      "angeschaltet wird der Feiertag nicht mehr als frei gefuehrt")
  }

  /**
   * THE SENTENCE FROM THE ORDER, in the form a person notices it: the task is done a day earlier
   * because the holiday is worked through.
   */
  @Test
  fun `angeschaltet wird der vorgang um genau den feiertag kuerzer`() {
    val projekt = Projekt()
    projekt.feiertage(Triple(2026, 7, 19))
    val person = projekt.person("Natalie", 0)
    val vorgang = projekt.vorgang("Arbeit", montag, aufwand = 40.0)
    projekt.zuordnen(vorgang, person)

    assertEquals(5, projekt.dauer(vorgang, montag), "Vorbedingung: fuenf Arbeitstage")
    assertEquals(LocalDate.of(2026, 8, 25),
      endAfterWorkingDays(montag, 5, projekt.gitter(vorgang)),
      "Vorbedingung: ohne den Mittwoch liegen die fuenf Tage auf Mo Di Do Fr Mo, also ist der " +
        "Dienstag darauf das Ende (ausschliessend)")

    projekt.feiertagsarbeit(true)
    assertEquals(5, projekt.dauer(vorgang, montag),
      "die Stunden aendern sich nicht -- fuenf Arbeitstage bleiben fuenf Arbeitstage")
    // MEASURED, and the measurement corrected the expectation this test was written with: 22.8.
    // is the Saturday, and `endAfterWorkingDays` moves an exclusive end ONTO a working day. So
    // the five days lie on Mo Di Mi Do Fr and the end is the Monday after the weekend.
    assertEquals(LocalDate.of(2026, 8, 24),
      endAfterWorkingDays(montag, 5, projekt.gitter(vorgang)),
      "die fuenf Tage liegen jetzt auf Mo Di Mi Do Fr -- ein Tag frueher fertig, und genau ein " +
        "Tag, naemlich der durchgearbeitete Feiertag")
  }

  // ---- ANGESCHALTET: DAS WOCHENENDE BEWEGT SICH NICHT -------------------------------------------

  /**
   * The sharpest form of the boundary the order insists on: the switch is ON, everything it can
   * touch is touched, and the weekend is still there.
   */
  @Test
  fun `angeschaltet bleiben samstag und sonntag frei, wenn niemand am wochenende arbeitet`() {
    val projekt = Projekt()
    projekt.feiertage(Triple(2026, 7, 19))
    projekt.feiertagsarbeit(true)
    val person = projekt.person("Natalie", 0)
    val vorgang = projekt.vorgang("Arbeit", montag)
    projekt.zuordnen(vorgang, person)

    assertEquals(listOf(montag, dienstag, mittwoch, donnerstag, freitag),
      projekt.belegteTage(vorgang, montag, sonntag),
      "der Feiertag am Mittwoch ist dazugekommen, Samstag und Sonntag nicht")
  }

  /**
   * The two ways a free day can be won back must not be able to stand in for each other. Here the
   * SWITCH is on and nobody works Saturdays: the Saturday stays free, so the switch is demonstrably
   * not a back door into the weekend.
   */
  @Test
  fun `der schalter ist kein weg zum samstag`() {
    val projekt = Projekt()
    projekt.feiertagsarbeit(true)
    val person = projekt.person("Natalie", 0)
    val vorgang = projekt.vorgang("Arbeit", montag)
    projekt.zuordnen(vorgang, person)
    assertFalse(projekt.gitter(vorgang)(samstag))
    assertFalse(projekt.gitter(vorgang)(sonntag))
  }

  /**
   * And the other direction: the WORKING WEEK is the way to the Saturday, and it works with the
   * switch off just as it did before A4. A2's rule is untouched.
   */
  @Test
  fun `die arbeitswoche ist der weg zum samstag, und sie braucht den schalter nicht`() {
    val projekt = Projekt()
    val person = projekt.person("Natalie", 0, woche = "1,2,3,4,5,6")
    val vorgang = projekt.vorgang("Arbeit", montag)
    projekt.zuordnen(vorgang, person)

    assertTrue(projekt.gitter(vorgang)(samstag),
      "der Schalter steht auf AUS und der Samstag faellt trotzdem -- ueber die Arbeitswoche")
    assertFalse(projekt.gitter(vorgang)(sonntag))
  }

  /**
   * THE TWO WAYS SIDE BY SIDE, which is the case the order asks for by name: the switch is on AND
   * somebody works Saturdays. Each day has to fall through its own door — the Wednesday through
   * the switch, the Saturday through the working week — and the test shows that by taking each
   * door away in turn.
   */
  @Test
  fun `feiertag ueber den schalter, samstag ueber die arbeitswoche, und keiner ueber den anderen`() {
    val mitBeidem = Projekt()
    mitBeidem.feiertage(Triple(2026, 7, 19))
    mitBeidem.feiertagsarbeit(true)
    val vorgangBeides = mitBeidem.vorgang("Arbeit", montag)
    mitBeidem.zuordnen(vorgangBeides, mitBeidem.person("Natalie", 0, woche = "1,2,3,4,5,6"))
    assertEquals(listOf(montag, dienstag, mittwoch, donnerstag, freitag, samstag),
      mitBeidem.belegteTage(vorgangBeides, montag, sonntag))

    val nurSchalter = Projekt()
    nurSchalter.feiertage(Triple(2026, 7, 19))
    nurSchalter.feiertagsarbeit(true)
    val vorgangSchalter = nurSchalter.vorgang("Arbeit", montag)
    nurSchalter.zuordnen(vorgangSchalter, nurSchalter.person("Natalie", 0))
    assertEquals(listOf(montag, dienstag, mittwoch, donnerstag, freitag),
      nurSchalter.belegteTage(vorgangSchalter, montag, sonntag),
      "ohne Arbeitswoche faellt der Samstag nicht, obwohl der Schalter an ist")

    val nurWoche = Projekt()
    nurWoche.feiertage(Triple(2026, 7, 19))
    val vorgangWoche = nurWoche.vorgang("Arbeit", montag)
    nurWoche.zuordnen(vorgangWoche, nurWoche.person("Natalie", 0, woche = "1,2,3,4,5,6"))
    assertEquals(listOf(montag, dienstag, donnerstag, freitag, samstag),
      nurWoche.belegteTage(vorgangWoche, montag, sonntag),
      "ohne Schalter faellt der Mittwoch nicht, obwohl jeder Beteiligte mittwochs arbeitet -- " +
        "das ist die Grenze aus A2, und sie muss scharf bleiben")
  }

  /**
   * The one day on which the two questions genuinely meet: a public holiday that falls on a
   * Saturday. With the switch on it becomes an ORDINARY Saturday — free for a team that does not
   * work Saturdays, available to a team that does. Neither answer comes from the switch alone.
   */
  @Test
  fun `ein feiertag am samstag wird mit dem schalter zu einem gewoehnlichen samstag`() {
    val ohneSamstagswoche = Projekt()
    ohneSamstagswoche.feiertage(Triple(2026, 7, 22))
    ohneSamstagswoche.feiertagsarbeit(true)
    val v1 = ohneSamstagswoche.vorgang("Arbeit", montag)
    ohneSamstagswoche.zuordnen(v1, ohneSamstagswoche.person("Natalie", 0))
    assertFalse(ohneSamstagswoche.gitter(v1)(samstag),
      "niemand arbeitet samstags, also bleibt der Tag frei -- der Schalter allein holt ihn nicht")

    val mitSamstagswoche = Projekt()
    mitSamstagswoche.feiertage(Triple(2026, 7, 22))
    mitSamstagswoche.feiertagsarbeit(true)
    val v2 = mitSamstagswoche.vorgang("Arbeit", montag)
    mitSamstagswoche.zuordnen(v2, mitSamstagswoche.person("Natalie", 0, woche = "1,2,3,4,5,6"))
    assertTrue(mitSamstagswoche.gitter(v2)(samstag),
      "mit dem Schalter ist der Feiertag weg, und dann entscheidet die Arbeitswoche -- die " +
        "sagt Samstag")

    val ohneSchalter = Projekt()
    ohneSchalter.feiertage(Triple(2026, 7, 22))
    val v3 = ohneSchalter.vorgang("Arbeit", montag)
    ohneSchalter.zuordnen(v3, ohneSchalter.person("Natalie", 0, woche = "1,2,3,4,5,6"))
    assertFalse(ohneSchalter.gitter(v3)(samstag),
      "ohne Schalter bleibt der Feiertag ein Feiertag, auch am Samstag, auch wenn die " +
        "Arbeitswoche den Samstag nennt")
  }

  // ---- DER SCHALTER GILT FUER DEN GANZEN PLAN ---------------------------------------------------

  /**
   * A task nobody is assigned to takes the project calendar alone — the fast path in
   * [WorkWeekWorkingDays] that hands back the very object [workingDayTest] built. The switch has
   * to reach that path too, or it would apply to some tasks of a plan and not to others.
   */
  @Test
  fun `auch ein vorgang ohne zuordnung folgt dem schalter`() {
    val projekt = Projekt()
    projekt.feiertage(Triple(2026, 7, 19))
    projekt.feiertagsarbeit(true)
    val vorgang = projekt.vorgang("Niemandes Arbeit", montag)
    assertTrue(projekt.gitter(vorgang)(mittwoch))
    assertFalse(projekt.gitter(vorgang)(samstag))
  }

  /**
   * The switch is read when the grid is BUILT, and a grid is built per pass. So turning it on
   * during a session takes effect on the next pass and not on a grid already in hand — the same
   * lifetime every other thing this class reads has, and worth pinning rather than assuming.
   */
  @Test
  fun `ein bereits gebautes raster sieht die aenderung nicht, ein neues schon`() {
    val projekt = Projekt()
    projekt.feiertage(Triple(2026, 7, 19))
    val person = projekt.person("Natalie", 0)
    val vorgang = projekt.vorgang("Arbeit", montag)
    projekt.zuordnen(vorgang, person)

    val altesRaster = projekt.gitter(vorgang)
    projekt.feiertagsarbeit(true)
    assertFalse(altesRaster(mittwoch), "das alte Raster antwortet mit dem Stand, den es gelesen hat")
    assertTrue(projekt.gitter(vorgang)(mittwoch), "ein neues Raster liest den neuen Stand")
    assertNotEquals(altesRaster(mittwoch), projekt.gitter(vorgang)(mittwoch))
  }
}
