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
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/** 7 September 2026, a Monday. At file level because the nested [Projekt] needs it too. */
private val MONTAG = LocalDate.of(2026, 9, 7)

/**
 * W10 — THE PLANNER AND THE LEVELLING HAVE TO GIVE THE SAME ANSWER.
 *
 * There are two ways into the duration computation and they belong to different parts of the
 * program:
 *
 *   * `Task.durationDaysWithDaysOff` — what `TaskManagerImpl.deriveDurationWithDaysOff` calls, and
 *     therefore what runs on EVERY open of a file and EVERY change to a task. It is the more
 *     important of the two by a wide margin, because nobody has to ask for it.
 *   * `durationAtStart` — what the levelling asks once per candidate day.
 *
 * IF ONLY ONE OF THEM LEARNED THE HOME-WORK RULE, the fork would hold two answers to one question
 * and the plan would keep whichever ran last. That is not a hypothesis: it is the defect A2 fixed
 * on 03.09.2026, when levelling knew axis A and the duration computation did not, and a task's
 * length depended on whether somebody had opened the levelling menu.
 *
 * Both paths go through `daysNeededWithDaysOff`, so the joint is ONE joint — B3 only has to avoid
 * losing it. What could lose it is real and small: the signature of `effortInputs` grew a
 * `taskProperties` argument, and a caller that passed the wrong manager would be quietly right on
 * one path and quietly wrong on the other.
 *
 * 7 September 2026 is a Monday.
 */
class HomeWorkAgreementTest {

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

  private val montag = MONTAG
  private val mittwoch = MONTAG.plusDays(2)

  private class Projekt {
    val builder: TestSetupHelper.TaskManagerBuilder =
      TestSetupHelper.newTaskManagerBuilder().withCalendar(WeekendCalendarImpl())
    val taskManager: TaskManager = builder.build()
    val resourceManager: HumanResourceManager = builder.resourceManager
    val resourceProperties: CustomPropertyManager get() = resourceManager.customPropertyManager
    val taskProperties: CustomPropertyManager get() = taskManager.customPropertyManager
    private var naechsteId = 0

    fun person(name: String): HumanResource =
      resourceManager.create(name, naechsteId++).also {
        it.setValue(EffortDrivenProperties.findOrCreateResourceHours(resourceProperties), 8.0)
      }

    fun vorgang(name: String, aufwand: Double): Task =
      taskManager.newTaskBuilder().withName(name).withStartDate(MONTAG.toModelDate())
        .withDuration(taskManager.createLength(1L)).build().also {
          it.customValues.setValue(
            EffortDrivenProperties.findOrCreateTaskEffort(taskProperties), aufwand)
        }

    /** The planner's way in — what runs on every open and every change. */
    fun planerDauer(vorgang: Task): Int? =
      vorgang.durationDaysWithDaysOff(taskProperties, resourceProperties, MONTAG,
        WorkWeekWorkingDays(taskManager.calendar, resourceProperties).forTask(vorgang))

    /** The levelling's way in — asked once per candidate day. */
    fun nivellierungsDauer(vorgang: Task): Int {
      val aufgaben = collectLevelTasks(taskManager, taskProperties, resourceProperties, MONTAG, true)
        .associateBy { it.id }
      return durationAtStart(taskManager, taskProperties, resourceProperties)(
        aufgaben.getValue(vorgang.taskID.toString()), MONTAG)
    }
  }

  private fun HumanResource.zuHauseAm(projekt: Projekt, tag: LocalDate) {
    this.setHomeOfficePeriods(projekt.resourceProperties,
      HomeOfficePeriods.parse(tag.toString()).periods)
  }

  private fun Task.zuordnen(person: HumanResource, blockierend: Boolean = false) {
    this.assignmentCollection.addAssignment(person).apply {
      load = 100f
      isBlocking = blockierend
    }
  }

  /**
   * FOUR SHAPES, EACH THROUGH BOTH DOORS, and the number written out beside them so that the two
   * cannot agree by being equally wrong.
   *
   * The fourth is the one that matters most: the same plan without the marking. If the two paths
   * agreed on the marked task and disagreed on the unmarked one, the fork would have moved its
   * defect rather than removed it.
   */
  @Test
  fun `planer und nivellierung sind sich ueber die heimarbeit einig`() {
    val faelle = listOf<Triple<String, Int, (Projekt) -> Task>>(
      Triple("markiert, die zwingende Person mittwochs zu Hause", 6) { p ->
        val a = p.person("A")
        a.zuHauseAm(p, mittwoch)
        p.vorgang("Abnahme", 40.0).also {
          it.zuordnen(a, blockierend = true)
          applyHomeWorkMark(it.customValues, p.taskProperties, HomeWorkMark.ON_SITE)
        }
      },
      Triple("markiert, eine NICHT zwingende Person mittwochs zu Hause", 4) { p ->
        val a = p.person("A")
        val b = p.person("B")
        b.zuHauseAm(p, mittwoch)
        p.vorgang("Abnahme", 48.0).also {
          it.zuordnen(a)
          it.zuordnen(b)
          applyHomeWorkMark(it.customValues, p.taskProperties, HomeWorkMark.ON_SITE)
        }
      },
      Triple("markiert, aber niemand hat Heimarbeit eingetragen", 5) { p ->
        val a = p.person("A")
        p.vorgang("Abnahme", 40.0).also {
          it.zuordnen(a, blockierend = true)
          applyHomeWorkMark(it.customValues, p.taskProperties, HomeWorkMark.ON_SITE)
        }
      },
      Triple("Heimarbeit eingetragen, aber NICHT markiert", 5) { p ->
        val a = p.person("A")
        a.zuHauseAm(p, mittwoch)
        p.vorgang("Auswertung", 40.0).also { it.zuordnen(a, blockierend = true) }
      })

    val abweichungen = faelle.mapNotNull { (name, erwartet, bau) ->
      val projekt = Projekt()
      val vorgang = bau(projekt)
      val planer = projekt.planerDauer(vorgang)
      val nivellierung = projekt.nivellierungsDauer(vorgang)
      when {
        planer != erwartet -> "$name: der Planerpfad sagt $planer, erwartet war $erwartet"
        nivellierung != erwartet ->
          "$name: der Nivellierungspfad sagt $nivellierung, erwartet war $erwartet"
        else -> null
      }
    }
    assertEquals(emptyList<String>(), abweichungen,
      "beide Wege in die Dauerrechnung muessen dieselbe Zahl liefern -- sonst behaelt der Plan, " +
        "welcher zuletzt gelaufen ist, und genau dieser Defekt ist am 03.09.2026 behoben worden")
  }
}
