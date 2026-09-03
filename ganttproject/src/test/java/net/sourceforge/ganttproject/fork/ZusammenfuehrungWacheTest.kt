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
import net.sourceforge.ganttproject.task.dependency.constraint.FinishStartConstraintImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * [fork change] THE NOTHING-CHANGES GUARD OF THE MERGE OF 03.09.2026.
 *
 * A plan with NO working week, NO `isBlocking` and NO days off has to be levelled exactly as it
 * was before the three branches existed -- the same starting days, the same durations, to the day.
 *
 * WRITTEN-OUT DATES AND NUMBERS, deliberately, and they were not read off this branch. THIS FILE
 * USES ONLY WHAT `main` 702b1ea49 ALREADY OFFERED -- `collectLevelTasks`, `levelTasks`,
 * `durationAtStart`, `workingDayTest`, all four with the signatures they had there -- so that it
 * compiles and runs against that commit UNCHANGED. The numbers below come from exactly that run;
 * how it was made is in the report of 03.09.2026. The portability of this file is the measurement:
 * a guard whose expected values were read off the state it is guarding would guard nothing.
 *
 * THE PLAN TRAVELS EVERY FALLBACK of `durationAtStart` and every shape the three branches touch --
 * the shared pool, two people on one task, a half load, an onlooker (axis B) beside a contributor,
 * a chain, work without effort, begun work, finished work, a readable hours schedule and an
 * unreadable one. What it contains nowhere is a mark of any of the three branches.
 */
class ZusammenfuehrungWacheTest {

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

  private class Projekt {
    val builder: TestSetupHelper.TaskManagerBuilder =
      TestSetupHelper.newTaskManagerBuilder().withCalendar(WeekendCalendarImpl())
    val taskManager: TaskManager = builder.build()
    val resourceManager: HumanResourceManager = builder.resourceManager
    val resourceProperties: CustomPropertyManager get() = resourceManager.customPropertyManager
    val taskProperties: CustomPropertyManager get() = taskManager.customPropertyManager

    fun person(name: String, id: Int, stundenplan: String? = null): HumanResource =
      resourceManager.create(name, id).also { person ->
        person.setValue(
          EffortDrivenProperties.findOrCreateResourceHours(resourceProperties), 8.0)
        if (stundenplan != null) {
          person.setValue(
            EffortDrivenProperties.findOrCreateResourceSchedule(resourceProperties), stundenplan)
        }
      }

    fun vorgang(name: String, aufwand: Double?, tage: Long = 1L): Task =
      taskManager.newTaskBuilder().withName(name).withStartDate(MONTAG.toModelDate())
        .withDuration(taskManager.createLength(tage)).build().also {
          if (aufwand != null) {
            it.customValues.setValue(
              EffortDrivenProperties.findOrCreateTaskEffort(taskProperties), aufwand)
          }
        }

    fun zuordnen(vorgang: Task, person: HumanResource, last: Float = 100f,
                 ohneAufwand: Boolean = false) {
      vorgang.assignmentCollection.addAssignment(person).also {
        it.load = last
        if (ohneAufwand) it.isNoEffort = true
      }
    }

    fun abhaengig(nachfolger: Task, vorgaenger: Task) {
      taskManager.dependencyCollection.createDependency(nachfolger, vorgaenger,
        FinishStartConstraintImpl())
    }

    fun verteilung(): LevelResult =
      levelTasks(
        collectLevelTasks(taskManager, taskProperties, resourceProperties, HEUTE, true),
        MONTAG,
        workingDayTest(taskManager.calendar),
        durationAtStart(taskManager, taskProperties, resourceProperties),
        isAvailable = availabilityTest(resourceManager))
  }

  companion object {
    private val MONTAG: LocalDate = LocalDate.of(2026, 9, 7)
    private val HEUTE: LocalDate = LocalDate.of(2026, 9, 1)
  }

  @Test
  fun `ohne arbeitswoche ohne zwingende person und ohne ausfallzeit bleibt die verteilung dieselbe`() {
    val p = dreimalNichts()
    val ergebnis = p.verteilung()

    val anfaenge = ergebnis.starts.mapKeys { p.taskManager.getTask(it.key.toInt()).name }
    val dauern = ergebnis.durations.mapKeys { p.taskManager.getTask(it.key.toInt()).name }

    assertEquals(mapOf(
      "angefangen" to LocalDate.of(2026, 9, 7),
      "fertig" to LocalDate.of(2026, 9, 7),
      "sammel" to LocalDate.of(2026, 9, 7),
      "zwei-personen" to LocalDate.of(2026, 9, 21),
      "halbe-last" to LocalDate.of(2026, 9, 7),
      "zuschauer" to LocalDate.of(2026, 9, 14),
      "kette-a" to LocalDate.of(2026, 9, 24),
      "kette-b" to LocalDate.of(2026, 9, 29),
      "ohne-aufwand" to LocalDate.of(2026, 9, 24),
      "mit-stundenplan" to LocalDate.of(2026, 9, 7),
      "kaputter-stundenplan" to LocalDate.of(2026, 9, 7),
    ), anfaenge,
      "kein Anfangstag darf sich bewegen: in diesem Plan hat niemand eine Arbeitswoche, niemand " +
        "ist zwingend noetig und niemand hat Ausfallzeiten -- alle drei Aenderungen vom " +
        "03.09.2026 muessen an ihm wirkungslos sein")

    assertEquals(mapOf(
      "angefangen" to 2,
      "fertig" to 10,
      "sammel" to 2,
      "zwei-personen" to 3,
      "halbe-last" to 5,
      "zuschauer" to 3,
      "kette-a" to 3,
      "kette-b" to 2,
      "ohne-aufwand" to 3,
      "mit-stundenplan" to 5,
      "kaputter-stundenplan" to 4,
    ), dauern, "und keine Dauer darf sich aendern")
  }

  /**
   * Eleven leaves, five people, one chain -- and not one mark of any of the three branches.
   */
  private fun dreimalNichts(): Projekt {
    val p = Projekt()
    val a = p.person("A", 1)
    val b = p.person("B", 2)
    val c = p.person("C", 3)
    val mitPlan = p.person("MitPlan", 4, stundenplan = "2026-09-01: 4")
    val kaputt = p.person("Kaputt", 5, stundenplan = "das ist kein datum: 6")

    // 1. nobody assigned -- the shared pool.
    p.vorgang("sammel", 16.0, tage = 2L)
    // 2. two people at full load.
    val zwei = p.vorgang("zwei-personen", 48.0)
    p.zuordnen(zwei, a)
    p.zuordnen(zwei, b)
    // 3. half a load: 20 hours at four hours a day.
    val halbe = p.vorgang("halbe-last", 20.0)
    p.zuordnen(halbe, a, last = 50f)
    // 4. an onlooker (axis B) beside a contributor -- the onlooker delivers nothing.
    val zuschauer = p.vorgang("zuschauer", 24.0)
    p.zuordnen(zuschauer, a)
    p.zuordnen(zuschauer, c, ohneAufwand = true)
    // 5. and 6. a chain on one person, so that the window search has to work.
    val ketteA = p.vorgang("kette-a", 24.0)
    val ketteB = p.vorgang("kette-b", 16.0)
    p.zuordnen(ketteA, a)
    p.zuordnen(ketteB, a)
    p.abhaengig(ketteB, ketteA)
    // 7. no effort at all -- keeps the duration entered.
    val ohneAufwand = p.vorgang("ohne-aufwand", null, tage = 3L)
    p.zuordnen(ohneAufwand, b)
    // 8. begun work: the remainder at today's rate.
    val angefangen = p.vorgang("angefangen", 32.0, tage = 4L)
    p.zuordnen(angefangen, b)
    angefangen.completionPercentage = 50
    // 9. finished work: measured past, untouchable.
    val fertig = p.vorgang("fertig", 80.0, tage = 7L)
    p.zuordnen(fertig, b)
    fertig.completionPercentage = 100
    // 10. a readable hours schedule -- the sectioned branch of durationAtStart.
    val mitStundenplan = p.vorgang("mit-stundenplan", 20.0)
    p.zuordnen(mitStundenplan, mitPlan)
    // 11. an unreadable one -- durationAtStart refuses and falls back.
    val kaputterPlan = p.vorgang("kaputter-stundenplan", 32.0, tage = 4L)
    p.zuordnen(kaputterPlan, kaputt)
    return p
  }
}
