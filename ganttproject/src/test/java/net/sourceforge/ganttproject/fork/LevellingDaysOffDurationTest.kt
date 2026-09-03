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

import biz.ganttproject.core.calendar.GanttDaysOff
import biz.ganttproject.core.calendar.WeekendCalendarImpl
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import net.sourceforge.ganttproject.task.dependency.constraint.FinishStartConstraintImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * [fork change] Levelling plans a task WITH the days off of its people, not without them.
 *
 * THE DEFECT THIS FILE IS CUT AGAINST, measured on 03.09.2026 and older than everything built
 * that day: the scheduler writes six days for a task whose person is away in the middle of it
 * (`DaysOffDuration.durationDaysWithDaysOff`), and levelling lays a window of five
 * (`LevellingAdapter.durationAtStart`). Two answers to one question, and the plan afterwards
 * carries whichever of them ran last.
 *
 * IT WAS TWO PLACES AND NOT ONE. `durationAtStart` falls back to `LevelTask.durationDays` at four
 * points, and one of them -- a constant daily rate, that is: nobody has an hours schedule
 * entered -- is the everyday case. For the everyday plan `LevelTask.durationDays` alone decides,
 * and that number comes out of `durationFromEffort` (`LevellingAdapter.kt`), which knows nothing
 * about days off. Repairing only the sectioned branch would have mended the rarer half and left
 * the common one standing.
 *
 * WHAT IS CHECKED HERE IS AN AGREEMENT, not a table of numbers: the two sides have to give THE
 * SAME answer. Written-out numbers would pin today's arithmetic and would have to be adjusted
 * the moment anything else moves; the agreement keeps saying the same thing for ever. Concrete
 * numbers appear only where they carry content of their own -- six days rather than five.
 */
class LevellingDaysOffDurationTest {

  // GanttDaysOff builds its dates through CalendarFactory, which has to be woken up first --
  // the same opening as in LevellingDaysOffTest, and for the same reason.
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

  private class Project {
    val builder: TestSetupHelper.TaskManagerBuilder =
      TestSetupHelper.newTaskManagerBuilder().withCalendar(WeekendCalendarImpl())
    val taskManager: TaskManager = builder.build()
    val resourceManager: HumanResourceManager = builder.resourceManager
    val resourceProperties: CustomPropertyManager get() = resourceManager.customPropertyManager
    val taskProperties: CustomPropertyManager get() = taskManager.customPropertyManager

    fun resource(name: String, id: Int, hoursPerDay: Double? = null,
                 schedule: String? = null): HumanResource =
      resourceManager.create(name, id).also { r ->
        if (hoursPerDay != null) {
          r.setValue(
            EffortDrivenProperties.findOrCreateResourceHours(resourceProperties), hoursPerDay)
        }
        if (schedule != null) {
          r.setValue(
            EffortDrivenProperties.findOrCreateResourceSchedule(resourceProperties), schedule)
        }
      }

    fun task(name: String, start: LocalDate, effortHours: Double?, days: Long = 1L): Task =
      taskManager.newTaskBuilder().withName(name).withStartDate(start.toModelDate())
        .withDuration(taskManager.createLength(days)).build().also { t ->
          if (effortHours != null) {
            t.customValues.setValue(
              EffortDrivenProperties.findOrCreateTaskEffort(taskProperties), effortHours)
          }
        }

    fun assign(task: Task, resource: HumanResource, load: Float,
               blocking: Boolean = false, noEffort: Boolean = false) {
      task.assignmentCollection.addAssignment(resource).also {
        it.load = load
        if (blocking) it.isBlocking = true
        if (noEffort) it.isNoEffort = true
      }
    }

    /** A day off, entered the way the program enters it. THE END IS EXCLUSIVE. */
    fun dayOff(resource: HumanResource, from: LocalDate, toExclusive: LocalDate) {
      resource.addDaysOff(GanttDaysOff(from.toModelDate(), toExclusive.toModelDate()))
    }

    fun dependency(dependant: Task, dependee: Task) {
      taskManager.dependencyCollection.createDependency(dependant, dependee,
        FinishStartConstraintImpl())
    }

    val isWorkingDay: (LocalDate) -> Boolean get() = workingDayTest(taskManager.calendar)

    fun levelTaskListe(): List<LevelTask> =
      collectLevelTasks(taskManager, taskProperties, resourceProperties, HEUTE, true)

    /** What the scheduler writes. */
    fun geplanteDauer(task: Task, start: LocalDate): Int? =
      task.durationDaysWithDaysOff(taskProperties, resourceProperties, start, isWorkingDay)

    /** What levelling lays the window with. */
    fun verteilteDauer(task: Task, start: LocalDate): Int {
      val levelTask = levelTaskListe().single { it.id == task.taskID.toString() }
      return durationAtStart(taskManager, taskProperties, resourceProperties)(levelTask, start)
    }

    fun verteilung(abWann: LocalDate = MONTAG): LevelResult =
      levelTasks(levelTaskListe(), abWann, isWorkingDay,
        durationAtStart(taskManager, taskProperties, resourceProperties),
        isAvailable = availabilityTest(resourceManager))
  }

  companion object {
    /** Monday, 07.09.2026 -- the day the measurement of 03.09.2026 was made on. */
    private val MONTAG: LocalDate = LocalDate.of(2026, 9, 7)
    private val MITTWOCH: LocalDate = LocalDate.of(2026, 9, 9)
    private val HEUTE: LocalDate = LocalDate.of(2026, 9, 1)
  }

  /**
   * ROW 1 of the table of 03.09.2026, and the one that carries the whole thing: an ORDINARY
   * holiday, no hours schedule, one person. The scheduler says six days, levelling said five.
   *
   * This is the everyday plan. Whoever repairs only the sectioned branch leaves this row standing.
   */
  @Test
  fun `eine person mit gewoehnlichem urlaub - beide seiten sagen sechs`() {
    val p = Project()
    val r = p.resource("A", 1)
    val t = p.task("T", MONTAG, 40.0)
    p.assign(t, r, 100f)
    p.dayOff(r, MITTWOCH, MITTWOCH.plusDays(1))

    assertEquals(6, p.geplanteDauer(t, MONTAG),
      "fuenf Arbeitstage mit einem Urlaubstag mittendrin sind sechs Tage lang")
    assertEquals(6, p.verteilteDauer(t, MONTAG),
      "die Nivellierung muss dasselbe Fenster legen, das der Planer schreibt -- sonst plant sie " +
        "fuenf Tage fuer einen Vorgang, den das Programm sechs Tage lang macht")
  }

  /**
   * ROW 2: the same plan, only with an hours schedule entered, so that `durationAtStart` takes
   * its sectioned branch. Written out separately because the two rows fail for DIFFERENT reasons
   * and a repair can mend one without the other.
   */
  @Test
  fun `eine person mit urlaub und stundenplan - beide seiten sagen sechs`() {
    val p = Project()
    // The section starts long after the task; it changes no hour of it. What it does change is
    // WHICH branch of durationAtStart runs -- that is the whole point of this row.
    val r = p.resource("A", 1, schedule = "2027-04-01: 6")
    val t = p.task("T", MONTAG, 40.0)
    p.assign(t, r, 100f)
    p.dayOff(r, MITTWOCH, MITTWOCH.plusDays(1))

    assertEquals(6, p.geplanteDauer(t, MONTAG), "der Stundenplan aendert an diesen Tagen nichts")
    assertEquals(6, p.verteilteDauer(t, MONTAG),
      "auch der Zweig mit Stundenabschnitten muss die Ausfallzeiten kennen")
  }

  /** ROW 3: two people, one of them on an ordinary holiday -- the row that already agreed. */
  @Test
  fun `zwei personen mit urlaub bei einer - beide seiten sagen dasselbe`() {
    val p = Project()
    val a = p.resource("A", 1)
    val b = p.resource("B", 2)
    val t = p.task("T", MONTAG, 48.0)
    p.assign(t, a, 100f)
    p.assign(t, b, 100f)
    p.dayOff(a, MITTWOCH, MITTWOCH.plusDays(1))

    assertEquals(p.geplanteDauer(t, MONTAG), p.verteilteDauer(t, MONTAG),
      "Planer und Nivellierung muessen dieselbe Dauer nennen")
  }

  /**
   * ROW 4: the person is BLOCKING and on holiday.
   *
   * The number this row produces depends on whether axis A has reached the duration calculation
   * -- on `main` of 03.09.2026 it has not, on the branch `ausfall-nimmt-tag` it has. This check
   * deliberately does NOT write a number out, and that is what makes it survive the merge: it
   * demands that both sides say the same thing, and after this change levelling derives its
   * answer from the very function the scheduler uses. Whatever that function learns, both learn.
   */
  @Test
  fun `zwei personen mit zwingender und abwesender person - beide seiten sagen dasselbe`() {
    val p = Project()
    val a = p.resource("A", 1)
    val b = p.resource("B", 2)
    val t = p.task("T", MONTAG, 48.0)
    p.assign(t, a, 100f, blocking = true)
    p.assign(t, b, 100f)
    p.dayOff(a, MITTWOCH, MITTWOCH.plusDays(1))

    assertEquals(p.geplanteDauer(t, MONTAG), p.verteilteDauer(t, MONTAG),
      "Planer und Nivellierung muessen dieselbe Dauer nennen, auch fuer eine zwingende Person")
  }

  /**
   * The number alone would prove nothing: what has to grow is the WINDOW.
   *
   * Checked at the successor, because that is where a window that is too short does its damage --
   * it hands the following work a starting day the plan cannot keep.
   */
  @Test
  fun `der vorgang bekommt wirklich das laengere fenster und nicht nur die laengere zahl`() {
    val p = Project()
    val r = p.resource("A", 1)
    val erst = p.task("erst", MONTAG, 40.0)
    val danach = p.task("danach", MONTAG, 8.0)
    p.assign(erst, r, 100f)
    p.assign(danach, r, 100f)
    p.dependency(danach, erst)
    p.dayOff(r, MITTWOCH, MITTWOCH.plusDays(1))

    val ergebnis = p.verteilung()
    assertEquals(6, ergebnis.durations[erst.taskID.toString()],
      "die Nivellierung muss dem Vorgang sechs Tage geben")
    // Monday 07.09. + six working days ends on Monday 14.09.; the successor starts on Tuesday.
    assertEquals(LocalDate.of(2026, 9, 15), ergebnis.starts[danach.taskID.toString()],
      "der Nachfolger muss hinter dem SECHSTEN Tag beginnen, nicht hinter dem fuenften")
  }

  /**
   * THE MOST IMPORTANT CHECK OF THIS PACKAGE: a plan without a single day off levels EXACTLY as
   * before -- the same starting days, the same durations, to the day.
   *
   * WRITTEN-OUT DATES HERE, and deliberately so, against the style of the rest of the file: the
   * comparison has to be against the state BEFORE the change, and that state exists nowhere any
   * more except in these numbers. They were read off a run against `main` (702b1ea49) before a
   * line was touched.
   *
   * THE PLAN IS BUILT SO THAT EVERY FALLBACK OF `durationAtStart` IS TRAVELLED: a task without
   * effort, a task without anybody on it, a task whose only person contributes nothing (axis B),
   * a task with an unreadable hours schedule, a begun task, a finished one, a task with a
   * readable hours schedule, and two ordinary ones sharing a person.
   */
  @Test
  fun `ohne jede ausfallzeit aendert sich an der verteilung nichts`() {
    val p = ohneAusfallzeiten()
    val ergebnis = p.verteilung()

    val starts = ergebnis.starts.mapKeys { p.taskManager.getTask(it.key.toInt()).name }
    val dauern = ergebnis.durations.mapKeys { p.taskManager.getTask(it.key.toInt()).name }

    assertEquals(mapOf(
      "ohne-aufwand" to LocalDate.of(2026, 9, 9),
      "ohne-person" to LocalDate.of(2026, 9, 7),
      "nur-zuschauer" to LocalDate.of(2026, 9, 21),
      "kaputter-stundenplan" to LocalDate.of(2026, 9, 7),
      "angefangen" to LocalDate.of(2026, 9, 7),
      "fertig" to LocalDate.of(2026, 9, 7),
      "mit-stundenplan" to LocalDate.of(2026, 9, 7),
      "gewoehnlich-a" to LocalDate.of(2026, 9, 14),
      "gewoehnlich-b" to LocalDate.of(2026, 9, 17),
    ), starts, "kein Anfangstag darf sich bewegen, solange niemand Ausfallzeiten hat")

    assertEquals(mapOf(
      "ohne-aufwand" to 3,
      "ohne-person" to 2,
      "nur-zuschauer" to 1,
      "kaputter-stundenplan" to 4,
      "angefangen" to 2,
      "fertig" to 10,
      "mit-stundenplan" to 5,
      "gewoehnlich-a" to 3,
      "gewoehnlich-b" to 2,
    ), dauern, "und keine Dauer darf sich aendern")
  }

  /**
   * The same plan, and the same statement, WITHOUT written-out numbers: the answer must not
   * depend on which of the two calculations levelling asks.
   *
   * This one stands beside the check above rather than instead of it, because the two fail in
   * different ways: the numbers above catch a change of the RESULT, this one catches a
   * DISAGREEMENT between the two sides even when both change together.
   */
  @Test
  fun `ohne ausfallzeiten sagen planer und nivellierung fuer jeden vorgang dasselbe`() {
    val p = ohneAusfallzeiten()
    p.taskManager.tasks.filter { it.name != "angefangen" && it.name != "fertig" }.forEach { t ->
      val geplant = p.geplanteDauer(t, MONTAG) ?: return@forEach
      assertEquals(geplant, p.verteilteDauer(t, MONTAG),
        "Vorgang '${t.name}': der Planer sagt $geplant Tage, die Nivellierung etwas anderes")
    }
  }

  /**
   * The fallbacks stay whole. Every one of them has to lead to a usable number -- levelling has
   * no way of handling a `null`, and a zero would lay a task of no length.
   */
  @Test
  fun `jeder rueckfall liefert weiter eine brauchbare zahl`() {
    val p = ohneAusfallzeiten()
    p.taskManager.tasks.forEach { t ->
      val dauer = p.verteilteDauer(t, MONTAG)
      assertTrue(dauer >= 1, "Vorgang '${t.name}' bekommt die Dauer $dauer -- das ist kein Fenster")
    }
  }

  /**
   * A person entered at 0 % carries an ordinary daily rate and still contributes nothing. Before
   * 03.09.2026 the walk found that out by running its full 10 000 days; now it is answered
   * without a step. The ANSWER has to be the old one all the same: fall back, do not invent.
   */
  @Test
  fun `eine zuordnung zu null prozent faellt zurueck statt eine dauer zu erfinden`() {
    val p = Project()
    val r = p.resource("A", 1)
    val t = p.task("T", MONTAG, 40.0, days = 3L)
    p.assign(t, r, 0f)

    assertEquals(null, p.geplanteDauer(t, MONTAG),
      "aus 0 % laesst sich keine Dauer ableiten")
    assertEquals(3, p.verteilteDauer(t, MONTAG),
      "die Nivellierung behaelt die eingetragene Dauer, statt zu raten")
  }

  /**
   * A holiday OUTSIDE the task leaves it alone. The counter-check to all of the above: what
   * lengthens the task is the absence ON its days, not the existence of an absence.
   */
  @Test
  fun `ein urlaub ausserhalb des vorgangs bleibt folgenlos`() {
    val p = Project()
    val r = p.resource("A", 1)
    val t = p.task("T", MONTAG, 40.0)
    p.assign(t, r, 100f)
    p.dayOff(r, MONTAG.plusMonths(3), MONTAG.plusMonths(3).plusDays(7))

    assertEquals(5, p.geplanteDauer(t, MONTAG))
    assertEquals(5, p.verteilteDauer(t, MONTAG),
      "ein Urlaub, der den Vorgang nicht beruehrt, darf ihn nicht verlaengern")
  }

  /**
   * A holiday that falls ON A WEEKEND costs nothing -- it is not a working day to begin with.
   *
   * WHY THIS CHECK EXISTS, and it is not a corner case dragged in for completeness: levelling
   * remembers the calendar's answers since 03.09.2026, so that the walk does not ask the same day
   * twice. A memory that answers wrongly -- "every day is a working day" -- is invisible to every
   * other check in this file, because without days off the walk counts the same number of days
   * whatever the calendar says. Only where an ABSENCE meets a NON-WORKING day do the two come
   * apart: the correct calendar skips Saturday entirely, the broken one spends the holiday on it.
   *
   * THE TASK HAS TO REACH ACROSS THE WEEKEND, and the first cut of this check did not: five days
   * of work beginning on Monday are finished on Friday and never see the Saturday, so the holiday
   * on it was never asked about and the falsified calendar stayed invisible. Seven days do reach
   * across. Measured on 03.09.2026: with the calendar memory falsified to always answer "working
   * day", this check turns red at 9 days against 7, and it is the ONLY one in this file that does.
   */
  @Test
  fun `ein urlaub, der nur auf ein wochenende faellt, verlaengert nichts`() {
    val p = Project()
    val r = p.resource("A", 1)
    // 56 h at 8 h a day: seven working days, Monday to the Tuesday of the following week.
    val t = p.task("T", MONTAG, 56.0)
    p.assign(t, r, 100f)
    // Saturday and Sunday in the middle of it. Neither is a working day.
    p.dayOff(r, MONTAG.plusDays(5), MONTAG.plusDays(7))

    assertEquals(7, p.geplanteDauer(t, MONTAG), "ein Wochenende ist kein Arbeitstag")
    assertEquals(7, p.verteilteDauer(t, MONTAG),
      "ein Urlaub am Wochenende darf den Vorgang nicht verlaengern -- die Tage waren ohnehin frei")
  }

  /**
   * An effort that cannot be worked off within the bound falls back -- and does so WITHOUT
   * walking ten thousand days for every candidate starting day.
   *
   * The number here is the one that just misses: 10 001 days at 8 hours. Levelling asks this
   * question once per candidate day, so an answer that costs a walk would be paid for tens of
   * thousands of times over. What is checked is the answer; that it is cheap is measured, not
   * asserted -- a check that watches a clock says something different every time the machine is
   * busy.
   */
  @Test
  fun `ein aufwand, der nicht zu schaffen ist, faellt zurueck`() {
    val p = Project()
    val r = p.resource("A", 1)
    val t = p.task("T", MONTAG, 8.0 * (CapacitySchedule.MAX_DAYS + 1), days = 4L)
    p.assign(t, r, 100f)

    assertEquals(null, p.geplanteDauer(t, MONTAG),
      "ein leerer Plan ist die bessere Antwort als eine erfundene Zahl")
    // The fallback is `LevelTask.durationDays`, and that number comes from `durationFromEffort`,
    // which knows no bound: 80 008 h at 8 h a day is 10 001 days and it says so. That is what
    // levelling laid before this change and what it lays after it -- the fallback was not
    // touched. The effort-driven algorithm writes the same number into the plan, so the two
    // sides do agree; they simply agree on a number the day-by-day walk refuses to name.
    assertEquals(CapacitySchedule.MAX_DAYS + 1, p.verteilteDauer(t, MONTAG),
      "die Nivellierung faellt auf die aus dem Aufwand abgeleitete Dauer zurueck")
  }

  /** The counter-check to it: one day less, and the answer is a duration after all. */
  @Test
  fun `ein aufwand knapp innerhalb der grenze wird noch gerechnet`() {
    val p = Project()
    val r = p.resource("A", 1)
    val t = p.task("T", MONTAG, 8.0 * CapacitySchedule.MAX_DAYS, days = 4L)
    p.assign(t, r, 100f)

    assertEquals(CapacitySchedule.MAX_DAYS, p.geplanteDauer(t, MONTAG),
      "genau an der Grenze wird noch gerechnet, nicht aufgegeben")
    assertEquals(CapacitySchedule.MAX_DAYS, p.verteilteDauer(t, MONTAG),
      "und die Nivellierung sagt dasselbe")
  }

  /**
   * A plan in which every fallback of `durationAtStart` is travelled and NOBODY has a day off.
   * Nine leaves, three people, one chain, one fixed date.
   */
  private fun ohneAusfallzeiten(): Project {
    val p = Project()
    val a = p.resource("A", 1)
    val b = p.resource("B", 2)
    val kaputt = p.resource("Kaputt", 3, schedule = "das ist kein datum: 6")
    val mitPlan = p.resource("MitPlan", 4, schedule = "2026-09-01: 4")

    // 1. no effort at all -- keeps the duration entered.
    val ohneAufwand = p.task("ohne-aufwand", MONTAG, null, days = 3L)
    p.assign(ohneAufwand, a, 100f)
    // 2. nobody assigned -- the shared pool.
    p.task("ohne-person", MONTAG, 16.0, days = 2L)
    // 3. the only person is an onlooker (axis B) -- contributes no hours.
    val zuschauer = p.task("nur-zuschauer", MONTAG, 16.0, days = 1L)
    p.assign(zuschauer, b, 100f, noEffort = true)
    // 4. an unreadable hours schedule -- durationAtStart refuses and falls back.
    val kaputterPlan = p.task("kaputter-stundenplan", MONTAG, 32.0, days = 4L)
    p.assign(kaputterPlan, kaputt, 100f)
    // 5. begun work: the start stands, the remainder is computed at today's rate.
    val angefangen = p.task("angefangen", MONTAG, 32.0, days = 4L)
    p.assign(angefangen, a, 100f)
    angefangen.completionPercentage = 50
    // 6. finished work: measured past, untouchable.
    val fertig = p.task("fertig", MONTAG, 80.0, days = 7L)
    p.assign(fertig, b, 100f)
    fertig.completionPercentage = 100
    // 7. a readable hours schedule -- the sectioned branch of durationAtStart.
    val mitStundenplan = p.task("mit-stundenplan", MONTAG, 20.0, days = 1L)
    p.assign(mitStundenplan, mitPlan, 100f)
    // 8. and 9. two ordinary ones on the same person, so that the window search has to work.
    val g1 = p.task("gewoehnlich-a", MONTAG, 24.0, days = 1L)
    val g2 = p.task("gewoehnlich-b", MONTAG, 16.0, days = 1L)
    p.assign(g1, a, 100f)
    p.assign(g2, a, 100f)
    p.dependency(g2, g1)
    return p
  }
}
