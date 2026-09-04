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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * THE NOTHING-CHANGES GUARD of the working-week-in-the-planner package.
 *
 * A plan in which NOBODY has entered a working week has to come out of a planning pass with
 * exactly the durations and exactly the dates it had on `main` 77abe62fe -- to the day. This is
 * the most important check of the package, because the hook it guards sits in
 * `TaskManagerImpl.deriveDurationWithDaysOff`, which is not a fork file, sits in the core, and
 * runs on EVERY open and EVERY change of every plan that exists.
 *
 * IT IS ALSO GREEN BEFORE THE CHANGE, and therefore proves nothing on its own. What it is worth
 * depends entirely on whether it has been seen to fail, and on HOW MANY DIFFERENT ways. Two
 * predecessor sessions were caught out here: at `arbeitswoche-wirkung` a fast path hid the first
 * break that was tried, and at `ausfall-nimmt-tag` the guard could not see two of four breaks by
 * construction. One break is no evidence. The breaks driven for this package, and what each of
 * them showed, are written up in `2026-09-04-arbeitswoche-planer.md`.
 *
 * ONE FINGERPRINT INSTEAD OF TWENTY ASSERTIONS, on purpose: with separate assertions the first one
 * to fail hides the rest, and a break that moves the whole plan by a day looks like a single wrong
 * number. The fingerprint shows the whole plan at once, so a failure says WHAT moved.
 *
 * THE EXPECTED TEXT WAS MEASURED ON `main`, not derived from the new code -- the run of 4 
 * September 2026 on 77abe62fe with an unchanged tree. That is the whole point: it is the state
 * before the change, written down.
 *
 * WHAT THE PLAN CONTAINS, and why each part is in it. Everything here is a shape in which a
 * mistake in the day grid would show as a moved date:
 *
 *  * a public holiday, so that the extension half of the rule has something it must NOT win back;
 *  * a day off on a WEEKDAY -- visible on every grid -- and one on a SATURDAY, which the project
 *    calendar never walks over and a Saturday week would;
 *  * a time-dependent daily rate, so that the second of the three places is reached at all;
 *  * a head task without a predecessor and a chain behind it, so that both of the scheduler's
 *    entry points are walked;
 *  * a task with nobody on it, so that the empty-involved fallback is walked;
 *  * a recurring series, so that the third place is reached.
 */
class WorkWeekPlannerGuardTest {

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
   * MEASURED ON `main` 77abe62fe, 4 September 2026, and nowhere else.
   *
   * Written as one line per task: name, duration in working days, begin, end.
   */
  private val aufMain = """
    A 7 2026-09-07 2026-09-16
    B 10 2026-09-16 2026-10-01
    C 3 2026-09-07 2026-09-10
    D 2 2026-09-16 2026-09-19
    Serie 1 2026-09-07 2026-09-08
    -- Serientermine: 2026-09-14, 2026-09-18
  """.trimIndent()

  @Test
  fun `ohne eingetragene arbeitswoche plant der lauf auf den tag genau wie auf main`() {
    assertEquals(aufMain, fingerabdruck(),
      "A PLAN IN WHICH NOBODY HAS ENTERED A WORKING WEEK MUST NOT MOVE BY A DAY. The hook this "
        + "guards runs on every open of every plan. If this line differs, the working week has "
        + "reached a plan that never asked for one.")
  }

  /** The whole plan as one text: name, duration, begin, end -- plus the dates of the series. */
  private fun fingerabdruck(): String {
    val projekt = Projekt()
    // A Thursday, and inside the second task: a public holiday is the one free day the extension
    // half of the rule may never win back.
    projekt.feiertag(2026, 8, 17, "Betriebsausflug")

    // NOBODY HERE HAS A WORKING WEEK. That is the whole premise of this check.
    val eine = projekt.person("P1", 1)
    val andere = projekt.person("P2", 2, plan = "2026-09-14: 4")
    // Wednesday 9 September: a working day on every grid there is.
    projekt.frei(eine, tag(9), tag(10))
    // Saturday 12 September: free in the project calendar, and a working day of anybody whose
    // week names the Saturday. Nobody's does here -- and that is exactly what is being pinned.
    projekt.frei(eine, tag(12), tag(13))

    val a = projekt.aufwand(projekt.vorgang("A", tag(7)), 48.0)
    projekt.zuordnen(a, eine)
    val b = projekt.aufwand(projekt.vorgang("B", tag(7)), 40.0)
    projekt.zuordnen(b, andere)
    projekt.folgt(b, a)
    val c = projekt.aufwand(projekt.vorgang("C", tag(7), tage = 3L), 24.0)
    val d = projekt.aufwand(projekt.vorgang("D", tag(7)), 16.0)
    projekt.zuordnen(d, eine)
    projekt.zuordnen(d, andere)
    projekt.folgt(d, a)
    val serie = projekt.vorgang("Serie", tag(7))
    projekt.zuordnen(serie, eine)
    serie.customValues.setValue(
      findOrCreateRecurrence(projekt.taskProperties), "taeglich; alle 5; Anzahl 3")

    projekt.planen()

    val termine = planRecurrences(
      projekt.taskManager, projekt.taskProperties, projekt.resourceProperties)
    return (listOf(a, b, c, d, serie).joinToString("\n") {
      "${it.name} ${projekt.dauer(it)} ${projekt.beginn(it)} ${projekt.ende(it)}"
    } + "\n-- Serientermine: "
      + termine.occurrences.joinToString(", ") { it.date.toString() })
  }

  private fun tag(tag: Int): LocalDate = LocalDate.of(2026, 9, tag)

  /** Same shape as in `WorkWeekPlannerTest`; kept separate so a change there cannot move this. */
  private class Projekt {
    val kalender = WeekendCalendarImpl()
    val builder: TestSetupHelper.TaskManagerBuilder =
      TestSetupHelper.newTaskManagerBuilder().withCalendar(kalender)
    val taskManager: TaskManager = builder.build()
    val resourceManager: HumanResourceManager = builder.resourceManager
    val resourceProperties: CustomPropertyManager get() = resourceManager.customPropertyManager
    val taskProperties: CustomPropertyManager get() = taskManager.customPropertyManager

    fun person(name: String, id: Int, plan: String? = null): HumanResource =
      resourceManager.create(name, id).also { person ->
        person.setValue(EffortDrivenProperties.findOrCreateResourceHours(resourceProperties), 8.0)
        if (plan != null) {
          person.setValue(
            EffortDrivenProperties.findOrCreateResourceSchedule(resourceProperties), plan)
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

    fun vorgang(name: String, start: LocalDate, tage: Long = 1L): Task =
      taskManager.newTaskBuilder().withName(name).withStartDate(start.toModelDate())
        .withDuration(taskManager.createLength(tage)).build()

    fun aufwand(vorgang: Task, stunden: Double): Task = vorgang.also {
      it.customValues.setValue(
        EffortDrivenProperties.findOrCreateTaskEffort(taskProperties), stunden)
    }

    fun zuordnen(vorgang: Task, person: HumanResource) {
      vorgang.assignmentCollection.addAssignment(person).load = 100f
    }

    fun folgt(spaeter: Task, frueher: Task) {
      taskManager.dependencyCollection.createDependency(spaeter, frueher)
    }

    fun planen() {
      taskManager.algorithmCollection.effortDrivenDurationAlgorithm.run()
      taskManager.algorithmCollection.scheduler.run()
    }

    fun beginn(vorgang: Task): LocalDate = vorgang.start.time.toModelLocalDate()
    fun ende(vorgang: Task): LocalDate = vorgang.end.time.toModelLocalDate()
    fun dauer(vorgang: Task): Int = vorgang.duration.length
  }
}
