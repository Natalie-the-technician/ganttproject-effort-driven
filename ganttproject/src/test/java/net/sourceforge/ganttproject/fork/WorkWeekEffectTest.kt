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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * THE WORKING WEEK TAKES EFFECT -- package A2.
 *
 * A1 built [WorkWeekSchedule] and wired it to nothing. This file checks the wiring: that
 * [WorkWeekWorkingDays] hands each task a day grid built from the people on it, that the grid
 * restricts and extends the way Natalie described, and above all that a plan in which nobody has
 * entered anything is not moved by so much as a day.
 *
 * The dates throughout: 17.8.2026 is a Monday, so 22.8. is the Saturday and 23.8. the Sunday of
 * that week.
 */
class WorkWeekEffectTest {

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

    fun feiertag(jahr: Int, monatNullBasiert: Int, tag: Int, name: String) {
      kalender.setPublicHolidays(listOf(CalendarEvent.newEvent(
        CalendarFactory.createGanttCalendar(jahr, monatNullBasiert, tag).time, false,
        CalendarEvent.Type.HOLIDAY, name, null)))
    }

    /** The day grid this task actually gets. */
    fun gitter(vorgang: Task): (LocalDate) -> Boolean =
      WorkWeekWorkingDays(taskManager.calendar, resourceProperties).forTask(vorgang)

    /** The days the task may use between [von] and [bis], both inclusive. */
    fun belegteTage(vorgang: Task, von: LocalDate, bis: LocalDate): List<LocalDate> {
      val test = gitter(vorgang)
      return generateSequence(von) { if (it.isBefore(bis)) it.plusDays(1) else null }
        .filter(test).toList()
    }
  }

  // ---- DIE NICHTS-AENDERT-SICH-WACHE ------------------------------------------------------------

  /**
   * THE MOST IMPORTANT CHECK OF THIS PACKAGE, and the one that needs the most care, because it is
   * ALSO GREEN BEFORE the change -- a check nobody has seen fail proves nothing.
   *
   * How it was armed is written up in the report: the fallback in [taskWorksOn] was deliberately
   * broken (`?: projectWorking` turned into `?: false`, i.e. „nothing entered means works never")
   * and this test was watched go red over 400 days. It bites.
   *
   * 400 days rather than a week, and starting on a Monday in the past: the range crosses 57
   * weekends, a public holiday and a year boundary, so a fallback that is wrong only for some
   * shape of day cannot hide in it.
   */
  @Test
  fun `ohne eingetragene arbeitswoche ist das raster tagesgleich mit dem projektkalender`() {
    val projekt = Projekt()
    projekt.feiertag(2026, 11, 25, "Weihnachten")
    val person = projekt.person("Natalie", 0)
    val zweite = projekt.person("Olaf", 1)
    val vorgang = projekt.vorgang("Arbeit", montag)
    projekt.zuordnen(vorgang, person)
    projekt.zuordnen(vorgang, zweite)

    val vorher = workingDayTest(projekt.taskManager.calendar)
    val nachher = projekt.gitter(vorgang)

    val abweichungen = generateSequence(montag) { it.plusDays(1) }.take(400)
      .filter { vorher(it) != nachher(it) }.toList()
    assertEquals(emptyList<LocalDate>(), abweichungen,
      "niemand hat eine Arbeitswoche eingetragen; das Raster muss auf den Tag genau das des " +
        "Projektkalenders bleiben, sonst verschiebt diese Aenderung bestehende Plaene")
  }

  /** The same guarantee once more where it is actually felt: in the computed duration. */
  @Test
  fun `ohne eingetragene arbeitswoche bleibt die dauer dieselbe`() {
    val projekt = Projekt()
    val person = projekt.person("Natalie", 0)
    val vorgang = projekt.vorgang("Arbeit", montag, aufwand = 40.0)
    projekt.zuordnen(vorgang, person)

    val alt = vorgang.durationDaysWithDaysOff(projekt.taskProperties, projekt.resourceProperties,
      montag, workingDayTest(projekt.taskManager.calendar))
    val neu = vorgang.durationDaysWithDaysOff(projekt.taskProperties, projekt.resourceProperties,
      montag, projekt.gitter(vorgang))
    assertEquals(alt, neu,
      "40 Stunden bei 8 h/Tag sind fuenf Arbeitstage -- vor wie nach dieser Aenderung")
    assertEquals(5, neu)
  }

  // ---- EINSCHRAENKEN UND ERWEITERN --------------------------------------------------------------

  @Test
  fun `wer montags dienstags freitags samstags arbeitet, belegt genau diese tage`() {
    val projekt = Projekt()
    val person = projekt.person("Natalie", 0, woche = "1,2,5,6")
    val vorgang = projekt.vorgang("Arbeit", montag)
    projekt.zuordnen(vorgang, person)

    assertEquals(listOf(montag, dienstag, freitag, samstag),
      projekt.belegteTage(vorgang, montag, sonntag),
      "Mi und Do fallen weg (einschraenken), Sa kommt hinzu (erweitern) -- beide Haelften der " +
        "Regel an einem Beispiel")
  }

  @Test
  fun `der sonntag wird nicht als wochenende verschluckt`() {
    val projekt = Projekt()
    // ISO 7 is Sunday. Whoever enters it works on it.
    val person = projekt.person("Natalie", 0, woche = "1,7")
    val vorgang = projekt.vorgang("Arbeit", montag)
    projekt.zuordnen(vorgang, person)

    assertTrue(projekt.gitter(vorgang)(sonntag),
      "der Sonntag ist im Projektkalender frei, die Person arbeitet an ihm -- er muss belegt " +
        "werden koennen und darf nicht als Wochenende durchfallen")
    assertEquals(listOf(montag, sonntag), projekt.belegteTage(vorgang, montag, sonntag))
  }

  @Test
  fun `ein vorgang ohne zuordnung liegt nicht am wochenende`() {
    val projekt = Projekt()
    val vorgang = projekt.vorgang("herrenlos", montag)

    assertFalse(projekt.gitter(vorgang)(samstag),
      "„alle Beteiligten arbeiten\" waere ueber der leeren Menge wahr; ein Vorgang ohne " +
        "Zuordnung muss auf den Projektkalender zurueckfallen statt am Wochenende zu liegen")
    assertFalse(projekt.gitter(vorgang)(sonntag))
    assertEquals(listOf(montag, dienstag, mittwoch, donnerstag, freitag),
      projekt.belegteTage(vorgang, montag, sonntag))
  }

  // ---- DER GRENZTAG EINES ABSCHNITTS ------------------------------------------------------------

  /**
   * A section takes effect ON its own day, not one day early and not one day late. Both
   * neighbouring days are checked with it -- a boundary tested only from one side is half tested.
   *
   * The section starts on Wednesday 19.8. and drops Wednesday from the week. Before it the person
   * has no statement at all, so the project calendar carries Monday and Tuesday.
   */
  @Test
  fun `ein abschnitt greift am grenztag selbst, nicht einen tag frueher oder spaeter`() {
    val projekt = Projekt()
    val person = projekt.person("Natalie", 0, woche = "2026-08-19: 1,2,4,5")
    val vorgang = projekt.vorgang("Arbeit", montag)
    projekt.zuordnen(vorgang, person)
    val raster = projekt.gitter(vorgang)

    assertTrue(raster(dienstag),
      "der Tag VOR dem Grenztag liegt noch vor jedem Abschnitt -- keine Angabe, also " +
        "Projektkalender, und der sagt Dienstag ist Arbeitstag")
    assertFalse(raster(mittwoch),
      "AM Grenztag selbst gilt der Abschnitt, und er nennt den Mittwoch nicht")
    assertTrue(raster(donnerstag),
      "der Tag NACH dem Grenztag gehoert weiter zum Abschnitt, und der nennt den Donnerstag")
  }

  /** The counter-direction: a section that ADDS the Saturday starts on its day, not before. */
  @Test
  fun `ein erweiternder abschnitt beginnt ebenfalls genau an seinem tag`() {
    val projekt = Projekt()
    // From Saturday 22.8. on the person works six days. The Saturday BEFORE that section --
    // 15.8. -- is not covered by it and stays free.
    val person = projekt.person("Natalie", 0, woche = "2026-08-22: 1,2,3,4,5,6")
    val vorgang = projekt.vorgang("Arbeit", montag)
    projekt.zuordnen(vorgang, person)
    val raster = projekt.gitter(vorgang)

    assertFalse(raster(LocalDate.of(2026, 8, 15)),
      "der Samstag eine Woche VOR dem Abschnitt ist von keiner Angabe gedeckt und bleibt frei")
    assertTrue(raster(samstag), "AM Grenztag selbst greift der Abschnitt")
    assertTrue(raster(LocalDate.of(2026, 8, 29)), "und danach ebenfalls")
  }

  // ---- ZWEI PERSONEN AN EINEM VORGANG -----------------------------------------------------------

  @Test
  fun `arbeitet nur eine von zwei personen samstags, wird der samstag nicht genommen`() {
    val projekt = Projekt()
    val samstagsarbeiterin = projekt.person("Natalie", 0, woche = "1,2,3,4,5,6")
    val ohneSamstag = projekt.person("Olaf", 1, woche = "1,2,3,4,5")
    val vorgang = projekt.vorgang("Arbeit", montag)
    projekt.zuordnen(vorgang, samstagsarbeiterin)
    projekt.zuordnen(vorgang, ohneSamstag)

    assertFalse(projekt.gitter(vorgang)(samstag),
      "erweitert wird nur, wenn ALLE Beteiligten an dem Tag arbeiten -- Olaf tut es nicht")
  }

  @Test
  fun `arbeiten beide personen samstags, wird der samstag genommen`() {
    val projekt = Projekt()
    val eine = projekt.person("Natalie", 0, woche = "1,2,3,4,5,6")
    val andere = projekt.person("Olaf", 1, woche = "1,2,3,4,5,6")
    val vorgang = projekt.vorgang("Arbeit", montag)
    projekt.zuordnen(vorgang, eine)
    projekt.zuordnen(vorgang, andere)

    assertTrue(projekt.gitter(vorgang)(samstag),
      "alle Beteiligten arbeiten samstags, also wird der Vorgang erweitert")
  }

  /**
   * The half of the „all" rule that is easy to get wrong the other way: one person has entered a
   * week, the other has entered NOTHING. Nothing is not consent -- the colleague has not said they
   * work Saturdays, so the Saturday is not taken.
   */
  @Test
  fun `wer nichts eingetragen hat, stimmt dem samstag nicht stillschweigend zu`() {
    val projekt = Projekt()
    val samstagsarbeiterin = projekt.person("Natalie", 0, woche = "1,2,3,4,5,6")
    val schweigt = projekt.person("Olaf", 1)
    val vorgang = projekt.vorgang("Arbeit", montag)
    projekt.zuordnen(vorgang, samstagsarbeiterin)
    projekt.zuordnen(vorgang, schweigt)

    assertFalse(projekt.gitter(vorgang)(samstag),
      "Olaf hat keine Angabe gemacht; fuer ihn gilt der Projektkalender, und der sagt frei")
    assertTrue(projekt.gitter(vorgang)(mittwoch),
      "eingeschraenkt wird deshalb aber nichts -- der Mittwoch bleibt fuer beide ein Arbeitstag")
  }

  // ---- FEIERTAGE BLEIBEN AUSSEN VOR -------------------------------------------------------------

  /**
   * The extension rule is about WEEKDAYS. A public holiday is a different switch -- package A4 --
   * and until it exists a holiday stays free no matter what anybody's week says.
   */
  @Test
  fun `ein feiertag bleibt frei, auch wenn alle beteiligten an dem wochentag arbeiten`() {
    val projekt = Projekt()
    // Wednesday 19.8.2026 is made a public holiday. Month is 0-based in CalendarFactory.
    projekt.feiertag(2026, 7, 19, "Betriebsfeiertag")
    val person = projekt.person("Natalie", 0, woche = "1,2,3,4,5,6")
    val zweite = projekt.person("Olaf", 1, woche = "1,2,3,4,5,6")
    val vorgang = projekt.vorgang("Arbeit", montag)
    projekt.zuordnen(vorgang, person)
    projekt.zuordnen(vorgang, zweite)
    val raster = projekt.gitter(vorgang)

    assertFalse(raster(mittwoch),
      "beide arbeiten mittwochs, aber dieser Mittwoch ist ein Feiertag -- Feiertage haengen an " +
        "einem eigenen Schalter (Paket A4) und werden hier nicht erweitert")
    assertTrue(raster(samstag),
      "die Gegenprobe im selben Aufbau: der Samstag ist kein Feiertag und wird sehr wohl " +
        "erweitert -- der Feiertag oben faellt also nicht deshalb weg, weil die Regel gar " +
        "nicht greift")
  }

  /** And the reason it can be told apart at all, checked on the mask directly. */
  @Test
  fun `wochenende und feiertag lassen sich an der tagesmaske trennen`() {
    val projekt = Projekt()
    projekt.feiertag(2026, 7, 19, "Betriebsfeiertag")
    val kalender = projekt.taskManager.calendar

    assertTrue(projectDayIsFreeForWeekdayReasons(kalender.getDayMask(samstag.toModelDate())),
      "der Samstag ist nur wegen des Wochentags frei")
    assertFalse(projectDayIsFreeForWeekdayReasons(kalender.getDayMask(mittwoch.toModelDate())),
      "der Feiertag ist nicht wegen des Wochentags frei")
    assertFalse(projectDayIsFreeForWeekdayReasons(kalender.getDayMask(dienstag.toModelDate())),
      "ein gewoehnlicher Arbeitstag ist ueberhaupt nicht frei")
  }

  // ---- WER IST „BETEILIGT" ----------------------------------------------------------------------

  /**
   * Axis B alone would miss the person who must be present and books no hours; axis A alone would
   * miss nearly everybody, because `isBlocking` is `false` by default. The union is what
   * [isInvolvedInWorkWeek] takes, and these three checks are the evidence for it.
   */
  @Test
  fun `eine blockierende person ohne aufwand entscheidet ueber den samstag mit`() {
    val projekt = Projekt()
    val arbeitende = projekt.person("Natalie", 0, woche = "1,2,3,4,5,6")
    val aufsicht = projekt.person("Olaf", 1, woche = "1,2,3,4,5")
    val vorgang = projekt.vorgang("Abnahme", montag)
    projekt.zuordnen(vorgang, arbeitende)
    projekt.zuordnen(vorgang, aufsicht, last = 0f).also {
      it.isNoEffort = true   // axis B: contributes no hours
      it.isBlocking = true   // axis A: but must be there
    }

    assertFalse(projekt.gitter(vorgang)(samstag),
      "Olaf leistet keine Stunden, aber ohne ihn laeuft der Vorgang nicht -- und er arbeitet " +
        "samstags nicht. Achse B allein wuerde ihn uebersehen")
  }

  @Test
  fun `eine person ohne aufwand und ohne blockade entscheidet nicht mit`() {
    val projekt = Projekt()
    val arbeitende = projekt.person("Natalie", 0, woche = "1,2,3,4,5,6")
    val zuschauer = projekt.person("Olaf", 1, woche = "1,2,3,4,5")
    val vorgang = projekt.vorgang("Arbeit", montag)
    projekt.zuordnen(vorgang, arbeitende)
    projekt.zuordnen(vorgang, zuschauer, last = 0f).also {
      it.isNoEffort = true    // contributes nothing
      it.isBlocking = false   // and does not stop anything either
    }

    assertTrue(projekt.gitter(vorgang)(samstag),
      "Olaf leistet nichts und haelt nichts auf -- er steht zur Nennung im Bericht am Vorgang. " +
        "Ihn den Samstag verhindern zu lassen, waere das stille Kippen in die andere Richtung")
  }

  @Test
  fun `eine gewoehnliche zuordnung ist beteiligt, obwohl sie nicht blockiert`() {
    val projekt = Projekt()
    val person = projekt.person("Natalie", 0, woche = "1,2,3,4,5")
    val vorgang = projekt.vorgang("Arbeit", montag)
    val zuordnung = projekt.zuordnen(vorgang, person)

    assertFalse(zuordnung.isBlocking,
      "die Vorgabe von Achse A ist false -- eine Regel allein auf isBlocking waere in jedem " +
        "heute bestehenden Plan wirkungslos")
    assertTrue(zuordnung.isInvolvedInWorkWeek,
      "trotzdem ist die Person beteiligt: sie leistet die Stunden")
  }

  // ---- DIE REINE REGEL --------------------------------------------------------------------------

  /** [taskWorksOn] without any GanttProject type, so the rule can be read off directly. */
  @Test
  fun `die reine regel schraenkt ein und erweitert nach derselben vorschrift`() {
    val montagsWoche = WorkWeekSchedule.parse("1,2,5,6").schedule
    val ohneAngabe = WorkWeekSchedule.parse(null).schedule

    // Wednesday: project says working, the week does not name it -> restricted away.
    assertFalse(taskWorksOn(mittwoch, projectWorking = true,
      projectFreeForWeekdayReasons = false, involved = listOf(montagsWoche)))
    // Saturday: project says free for weekday reasons, the week names it -> extended.
    assertTrue(taskWorksOn(samstag, projectWorking = false,
      projectFreeForWeekdayReasons = true, involved = listOf(montagsWoche)))
    // Saturday, but a holiday: not extended.
    assertFalse(taskWorksOn(samstag, projectWorking = false,
      projectFreeForWeekdayReasons = false, involved = listOf(montagsWoche)))
    // Nothing entered: the project calendar decides, in both directions.
    assertTrue(taskWorksOn(mittwoch, projectWorking = true,
      projectFreeForWeekdayReasons = false, involved = listOf(ohneAngabe)))
    assertFalse(taskWorksOn(samstag, projectWorking = false,
      projectFreeForWeekdayReasons = true, involved = listOf(ohneAngabe)))
  }
}
