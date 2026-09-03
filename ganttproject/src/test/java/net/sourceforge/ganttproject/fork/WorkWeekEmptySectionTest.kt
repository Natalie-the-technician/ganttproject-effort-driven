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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.util.Locale

/**
 * WHAT AN EMPTY SECTION WOULD MEAN -- a MEASUREMENT, not a decision.
 *
 * The open question of this stage: is „from 1 May on no days at all" a permissible entry or an
 * error? It is not settled here. What is settled here is the FACT the decision has to be taken
 * against: what the two walks that would have to live with it actually do.
 *
 * Both walks carry a `MAX_DAYS` guard, and the guard is the reason the question is dangerous at
 * all: without it a week with no working day would be an endless loop, and an endless loop is the
 * failure mode nothing is visible about (recorded in [AuslastungsgradTest]).
 *
 * This file does NOT wire the working week into anything. The working week reaches the walks here
 * exactly the way a later stage would have to hand it to them -- as the `isWorkingDay` function
 * they already take -- and nothing in the program is changed for it.
 */
class WorkWeekEmptySectionTest {

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

  /** Monday to Friday -- what the project calendar says when the person says nothing. */
  private val projektkalender: (LocalDate) -> Boolean = {
    it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY
  }

  /**
   * The junction a later stage would have to build: the person's answer wins, and `null` -- no
   * statement -- falls through to the project calendar.
   *
   * It stands HERE and not in the program on purpose. This stage builds the model and the storage;
   * whether and where the program asks this question is stage 2 and belongs to somebody else.
   */
  private fun workingDayTest(week: WorkWeekSchedule): (LocalDate) -> Boolean =
    { day -> week.worksOn(day) ?: projektkalender(day) }

  private val montag = LocalDate.of(2026, 8, 17)

  // ---- daysNeeded ------------------------------------------------------------------------------

  @Test
  fun `daysNeeded gibt bei einem leeren abschnitt auf, statt endlos zu laufen`() {
    val woche = WorkWeekSchedule.parse("2026-08-19:").schedule
    // ThrowingSupplier explicitly: with a bare lambda Kotlin picks the Executable overload, the
    // result falls on the floor and the assertion below compares against Unit. Measured in the red
    // stage -- "expected: <null> but was: <kotlin.Unit>".
    val ergebnis = assertTimeoutPreemptively(Duration.ofSeconds(20), ThrowingSupplier {
      daysNeeded(100.0, montag, CapacitySchedule(8.0), isWorkingDay = workingDayTest(woche))
    })
    assertNull(ergebnis,
      "ab dem 19.8. ist kein Tag mehr ein Arbeitstag; die Rechnung muss aufgeben und den " +
        "Aufrufer melden lassen, statt eine Zahl zu erfinden")
  }

  @Test
  fun `was vor dem leeren abschnitt noch hineinpasst, wird sehr wohl gerechnet`() {
    // Counter-check to the previous one: the walk does not simply capitulate at the sight of an
    // empty section. Mon 17.8. and Tue 18.8. at 8 h are 16 h -- and 16 h fit.
    val woche = WorkWeekSchedule.parse("2026-08-19:").schedule
    assertEquals(2, daysNeeded(16.0, montag, CapacitySchedule(8.0),
      isWorkingDay = workingDayTest(woche)))
  }

  /**
   * How long the giving up takes, in working days walked -- the figure the proposal is argued
   * from. `MAX_DAYS` counts DAYS ACTUALLY WORKED, so with no working day left it is never reached;
   * what ends the walk is the second guard, `MAX_DAYS * 2` loop passes, and those are calendar
   * days. The walk therefore runs 20 000 calendar days, about 54 years, before it returns null.
   */
  @Test
  fun `der leere abschnitt laeuft in die zweite sicherung, nicht in die erste`() {
    var besuchteTage = 0
    val woche = WorkWeekSchedule.parse("2026-08-19:").schedule
    val test = workingDayTest(woche)
    assertNull(daysNeeded(100.0, montag, CapacitySchedule(8.0), isWorkingDay = { tag ->
      besuchteTage++
      test(tag)
    }))
    assertEquals(CapacitySchedule.MAX_DAYS * 2, besuchteTage,
      "die Rechnung laeuft bis an die Kalendertag-Sicherung, nicht an die Arbeitstag-Sicherung")
  }

  // ---- durationDaysWithDaysOff -----------------------------------------------------------------

  /** A project with a real resource manager and a real Task -- as in [LevellingDaysOffTest]. */
  private class Projekt {
    val builder: TestSetupHelper.TaskManagerBuilder =
      TestSetupHelper.newTaskManagerBuilder().withCalendar(WeekendCalendarImpl())
    val taskManager: TaskManager = builder.build()
    val resourceManager: HumanResourceManager = builder.resourceManager
    val resourceProperties: CustomPropertyManager get() = resourceManager.customPropertyManager
    val taskProperties: CustomPropertyManager get() = taskManager.customPropertyManager

    fun person(name: String, id: Int, stundenProTag: Double): HumanResource =
      resourceManager.create(name, id).also {
        it.setValue(EffortDrivenProperties.findOrCreateResourceHours(resourceProperties),
          stundenProTag)
      }

    fun vorgang(name: String, start: LocalDate, aufwand: Double): Task =
      taskManager.newTaskBuilder().withName(name).withStartDate(start.toModelDate())
        .withDuration(taskManager.createLength(1L)).build().also {
          it.customValues.setValue(
            EffortDrivenProperties.findOrCreateTaskEffort(taskProperties), aufwand)
        }
  }

  @Test
  fun `durationDaysWithDaysOff gibt bei einem leeren abschnitt ebenfalls auf`() {
    val projekt = Projekt()
    val person = projekt.person("Natalie", 0, 8.0)
    val vorgang = projekt.vorgang("viel Arbeit", montag, 100.0)
    vorgang.assignmentCollection.addAssignment(person).load = 100f

    val woche = WorkWeekSchedule.parse("2026-08-19:").schedule
    val ergebnis = assertTimeoutPreemptively(Duration.ofSeconds(20), ThrowingSupplier {
      vorgang.durationDaysWithDaysOff(projekt.taskProperties, projekt.resourceProperties,
        montag, workingDayTest(woche))
    })
    assertNull(ergebnis,
      "auch dieser Weg muss aufgeben statt eine Dauer zu erfinden -- 100 Stunden lassen sich " +
        "ohne Arbeitstag nicht abarbeiten")
  }

  @Test
  fun `ohne leeren abschnitt rechnet derselbe weg wie bisher`() {
    // Counter-check: the setup itself is sound, and a week that leaves days over delivers a
    // duration. Mon-Wed at 8 h are 24 h; 20 h fit in three days.
    val projekt = Projekt()
    val person = projekt.person("Natalie", 0, 8.0)
    val vorgang = projekt.vorgang("Arbeit", montag, 20.0)
    vorgang.assignmentCollection.addAssignment(person).load = 100f

    val woche = WorkWeekSchedule.parse("2026-08-17: 1,2,3").schedule
    assertEquals(3, vorgang.durationDaysWithDaysOff(projekt.taskProperties,
      projekt.resourceProperties, montag, workingDayTest(woche)))
  }
}
