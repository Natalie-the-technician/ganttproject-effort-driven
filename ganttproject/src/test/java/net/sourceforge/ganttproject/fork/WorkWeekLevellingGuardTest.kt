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
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * [fork change] THE NOTHING-CHANGES GUARD of the per-task window search.
 *
 * A plan in which NOBODY has entered a working week must come out of `levelTasks` with exactly the
 * starting days, exactly the durations and exactly the conflicts it had on `arbeitswoche-planer`
 * fee52484a -- to the day and to the percentage point. Everything this package touches sits in the
 * core of the levelling: `nextWorkingDay`, `workingDays`, `findEarliestWindow`, the capacity
 * booking, the deadline check and the overload report all read the working-day test, and until now
 * they all read ONE of them.
 *
 * THE EXPECTED TEXT WAS MEASURED ON fee52484a WITH AN UNCHANGED TREE, and this file was written so
 * that it COMPILES THERE: it uses `collectLevelTasks`, `levelTasks` and `durationAtStart` with the
 * signatures that commit had -- `levelTasks` still taking a global `(LocalDate) -> Boolean` -- and
 * the numbers below come from running it there. Once the third parameter became per task, the one
 * call in [Projekt.verteilung] was changed with it and NOTHING ELSE in this file was touched. That
 * is the measurement: a guard whose expected values are read off the state it guards guards
 * nothing.
 *
 * IT IS GREEN BEFORE THE CHANGE AS WELL, and therefore proves nothing on its own. What it is worth
 * is exactly how many DIFFERENT ways it has been seen to fail. Three predecessor sessions were
 * caught out here -- `arbeitswoche-wirkung`, where a fast path hid the first break; `ausfall-nimmt-
 * tag`, where two of four breaks were invisible by construction; `zusammenfuehrung-2`, where a
 * cache went wrong without any existing check being able to see it. The breaks driven for this
 * package, and what each of them showed, are written up in
 * `2026-09-04-fenstersuche-arbeitswoche.md`.
 *
 * WHAT IT CANNOT SEE, said plainly and up front: in a plan without a single working week EVERY
 * task gets the SAME test object back from [WorkWeekWorkingDays], so no break that mixes the grids
 * up -- handing one task another task's grid, or keying a remembered answer by the day alone --
 * can show here. There is nothing to mix up. Those breaks belong to `WorkWeekLevellingWindowTest`,
 * which puts two DIFFERENT working weeks into one run for exactly that reason.
 *
 * ONE FINGERPRINT INSTEAD OF TWENTY ASSERTIONS, on purpose: with separate assertions the first
 * failure hides the rest, and a break that moves the whole plan by a day looks like one wrong
 * number instead of like what it is.
 *
 * WHAT THE PLAN CONTAINS, and why each part is in it. Every part is a shape in which a mistake in
 * the day grid becomes a moved date rather than a silent one:
 *
 *  * a public holiday inside the run, so the calendar says "free" for a reason that is NOT the
 *    weekday;
 *  * a day off on a WEEKDAY and one on a SATURDAY -- the Saturday is the day the project calendar
 *    never walks over and any working week would;
 *  * a time-dependent daily rate, so the sectioned branch of `durationAtStart` is travelled;
 *  * a task that has to WAIT ON CAPACITY rather than on a dependency -- that is the only way the
 *    capacity booking becomes visible from outside, and the booking is what this change moves;
 *  * a chain, so the hand-over from one task's grid to the next one's is walked;
 *  * a task with nobody assigned -- the shared pool and the empty-involved fallback;
 *  * begun work, which is frozen and booked BEFORE everything else;
 *  * a fixed date that bursts the capacity, so an `Overload` conflict is in the fingerprint;
 *  * a deadline that cannot be met, so `DeadlineMissed.missingDays` is in it -- that number is
 *    counted by a loop of its own over the working days;
 *  * a milestone, which costs no capacity but orders.
 */
class WorkWeekLevellingGuardTest {

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

  /**
   * MEASURED ON `arbeitswoche-planer` fee52484a, 4 September 2026, and nowhere else.
   *
   * One line per task -- name, duration in working days, begin -- then the conflicts in the order
   * `levelTasks` reports them.
   */
  private val aufDemGrundstand = """
    A 7 2026-09-07
    B 8 2026-09-16
    C 3 2026-09-07
    D 2 2026-09-29
    E 3 2026-09-16
    Fest 2 2026-09-07
    Frist 4 2026-09-09
    Begonnen 2 2026-09-07
    Marke 1 2026-09-09
    -- Konflikte:
    DeadlineMissed Frist 2026-09-10 -> 2026-09-14 (2)
    Overload 2026-09-07 200% [A, Fest] fuer 1
    Overload 2026-09-08 200% [A, Fest] fuer 1
  """.trimIndent()

  @Test
  fun `ohne eingetragene arbeitswoche verteilt der lauf auf den tag genau wie auf dem grundstand`() {
    assertEquals(aufDemGrundstand, fingerabdruck(),
      "A PLAN IN WHICH NOBODY HAS ENTERED A WORKING WEEK MUST NOT MOVE BY A DAY. The window "
        + "search, the capacity booking, the deadline count and the overload report all read the "
        + "working-day test. If this line differs, making that test per task has changed a plan "
        + "that never asked for one.")
  }

  /** The whole run as one text: the placement first, then the conflicts. */
  private fun fingerabdruck(): String {
    val projekt = Projekt()
    // Thursday 17 September 2026: free for a reason that is not the weekday. The extension half
    // of the working-week rule may never win it back -- and here nobody would even try.
    projekt.feiertag(2026, 8, 17, "Betriebsausflug")

    // NOBODY HERE HAS A WORKING WEEK. That is the premise of this whole check.
    val eine = projekt.person("P1", 1)
    // A time-dependent daily rate, so that the sectioned branch of `durationAtStart` runs.
    val andere = projekt.person("P2", 2, stundenplan = "2026-09-21: 4")
    // Wednesday 9 September: a working day on every grid there is.
    projekt.frei(eine, tag(9), tag(10))
    // Saturday 12 September: free in the project calendar, and a working day for anybody whose
    // week names the Saturday. Nobody's does here -- and that is exactly what is being pinned.
    projekt.frei(eine, tag(12), tag(13))

    val a = projekt.vorgang("A", tag(7), 48.0)
    projekt.zuordnen(a, eine, 100f)
    val b = projekt.vorgang("B", tag(7), 40.0)
    projekt.zuordnen(b, andere, 100f)
    projekt.folgt(b, a)
    // Nobody assigned: the shared pool, and the empty-involved fallback of the day grid.
    val c = projekt.vorgang("C", tag(7), null, tage = 3L)
    val d = projekt.vorgang("D", tag(7), 16.0)
    projekt.zuordnen(d, eine, 100f)
    projekt.zuordnen(d, andere, 100f)
    projekt.folgt(d, b)
    // NO DEPENDENCY. E has to wait for A purely because P1's days are booked -- the only way the
    // capacity booking is visible from outside this function.
    val e = projekt.vorgang("E", tag(7), 24.0)
    projekt.zuordnen(e, eine, 100f)
    // A fixed date on top of A: keeps its date, bursts P1's capacity, and is reported.
    val fest = projekt.vorgang("Fest", tag(7), 16.0)
    projekt.zuordnen(fest, eine, 100f)
    fest.customValues.setValue(findOrCreateDateFixed(projekt.taskProperties), true)
    // A deadline that cannot be met: the count of missing days has a loop of its own.
    val frist = projekt.vorgang("Frist", tag(7), 32.0)
    projekt.zuordnen(frist, andere, 100f)
    frist.setDeadline(projekt.taskProperties, tag(10))
    // Begun work: frozen, and booked before everything else.
    val begonnen = projekt.vorgang("Begonnen", tag(7), 16.0)
    projekt.zuordnen(begonnen, andere, 100f)
    begonnen.completionPercentage = 40
    // A milestone: no capacity, but it takes part in the ordering.
    val marke = projekt.vorgang("Marke", tag(7), null)
    marke.isMilestone = true
    projekt.zuordnen(marke, eine, 100f)

    val ergebnis = projekt.verteilung()
    val vorgaenge = listOf(a, b, c, d, e, fest, frist, begonnen, marke)
    val namen = vorgaenge.associate { it.taskID.toString() to (it.name ?: "?") }
    val zeilen = vorgaenge.joinToString("\n") {
      val id = it.taskID.toString()
      "${it.name} ${ergebnis.durations[id]} ${ergebnis.starts[id]}"
    }
    return zeilen + "\n-- Konflikte:\n" + ergebnis.conflicts.joinToString("\n") { konflikt ->
      when (konflikt) {
        is LevelConflict.DeadlineMissed ->
          "DeadlineMissed ${namen[konflikt.id]} ${konflikt.deadline} -> ${konflikt.actualEnd}" +
            " (${konflikt.missingDays})"
        is LevelConflict.Overload ->
          "Overload ${konflikt.day} ${konflikt.percent}% " +
            konflikt.ids.mapNotNull { namen[it] }.sorted() + " fuer ${konflikt.resourceId}"
        is LevelConflict.FixedDateNotReachable ->
          "FixedDateNotReachable ${namen[konflikt.id]} ${konflikt.fixedStart} ${konflikt.earliestPossible}"
        is LevelConflict.NoPossibleDate ->
          "NoPossibleDate ${namen[konflikt.id]} ${konflikt.blocking} ${konflikt.fullFor}"
        is LevelConflict.Cycle -> "Cycle ${konflikt.ids.map { namen[it] }}"
      }
    }
  }

  private fun tag(tag: Int): LocalDate = LocalDate.of(2026, 9, tag)

  private class Projekt {
    val kalender = WeekendCalendarImpl()
    val builder: TestSetupHelper.TaskManagerBuilder =
      TestSetupHelper.newTaskManagerBuilder().withCalendar(kalender)
    val taskManager: TaskManager = builder.build()
    val resourceManager: HumanResourceManager = builder.resourceManager
    val resourceProperties: CustomPropertyManager get() = resourceManager.customPropertyManager
    val taskProperties: CustomPropertyManager get() = taskManager.customPropertyManager

    fun person(name: String, id: Int, stundenplan: String? = null): HumanResource =
      resourceManager.create(name, id).also { person ->
        person.setValue(EffortDrivenProperties.findOrCreateResourceHours(resourceProperties), 8.0)
        if (stundenplan != null) {
          person.setValue(
            EffortDrivenProperties.findOrCreateResourceSchedule(resourceProperties), stundenplan)
        }
      }

    fun frei(person: HumanResource, von: LocalDate, bisAusschliesslich: LocalDate) {
      person.addDaysOff(GanttDaysOff(von.toModelDate(), bisAusschliesslich.toModelDate()))
    }

    fun feiertag(jahr: Int, monatNullBasiert: Int, tag: Int, name: String) {
      kalender.setPublicHolidays(listOf(CalendarEvent.newEvent(
        CalendarFactory.createGanttCalendar(jahr, monatNullBasiert, tag).time, false,
        CalendarEvent.Type.HOLIDAY, name, null)))
    }

    fun vorgang(name: String, start: LocalDate, stunden: Double?, tage: Long = 1L): Task =
      taskManager.newTaskBuilder().withName(name).withStartDate(start.toModelDate())
        .withDuration(taskManager.createLength(tage)).build().also {
          if (stunden != null) {
            it.customValues.setValue(
              EffortDrivenProperties.findOrCreateTaskEffort(taskProperties), stunden)
          }
        }

    fun zuordnen(vorgang: Task, person: HumanResource, last: Float) {
      vorgang.assignmentCollection.addAssignment(person).load = last
    }

    fun folgt(spaeter: Task, frueher: Task) {
      taskManager.dependencyCollection.createDependency(spaeter, frueher, FinishStartConstraintImpl())
    }

    /**
     * Exactly the call `LevellingActions.weiter` makes.
     *
     * THE ONE LINE THIS FILE CHANGED when the third parameter became per task. Everything else --
     * the plan and the expected text above -- is untouched from the run on fee52484a.
     */
    fun verteilung(): LevelResult = levelTasks(
      collectLevelTasks(taskManager, taskProperties, resourceProperties, HEUTE, true),
      MONTAG,
      workingDaysPerTask(taskManager, resourceProperties),
      durationAtStart(taskManager, taskProperties, resourceProperties),
      isAvailable = availabilityTest(resourceManager))
  }

  companion object {
    private val MONTAG: LocalDate = LocalDate.of(2026, 9, 7)
    private val HEUTE: LocalDate = LocalDate.of(2026, 9, 1)
  }
}
