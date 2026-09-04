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
 * THE WORKING WEEK REACHES THE ORDINARY PLANNING RUN -- and not only the levelling menu item.
 *
 * `WorkWeekEffectTest` pins the day grid itself: that [WorkWeekWorkingDays] restricts and extends
 * the way Natalie described. It does NOT pin that anybody outside the levelling ever asks for that
 * grid. Until this package only [LevellingAdapter] did. Whoever entered a working week and never
 * opened the levelling menu noticed nothing at all.
 *
 * Three places outside the levelling built the GLOBAL test `workingDayTest(calendar)` instead:
 *
 *  * `TaskManagerImpl.deriveDurationWithDaysOff` -- the way the SCHEDULER sets durations, and
 *    therefore the one that runs on every open and every change. Pinned here by
 *    [derived duration follows the working week] and its companions.
 *  * `EffortDrivenDurationAlgorithm.recalculateLeaf` -- the duration from effort and a
 *    time-dependent daily rate. Pinned by [effort driven duration follows the working week].
 *  * `planRecurrences` -- where a recurring date lands when it falls on a non-working day. Pinned
 *    by [a recurring date follows the working week].
 *
 * WHAT THE WORKING WEEK CAN AND CANNOT MOVE HERE, measured while these checks were being built
 * and worth writing down, because it decides what a check has to look like to say anything:
 *
 * A duration is a NUMBER OF WORKING DAYS, and every working day of a constant daily rate delivers
 * the same hours. Restricting the week from Mon-Fri to Mon-Thu therefore does NOT change that
 * number -- the same five days of work simply lie further apart in the calendar. The number moves
 * only where the days stop being equal: a day off, a blocking absence, or a section of the hours
 * schedule. Every check below is built on such a day; a check without one would be green on the
 * project calendar and green on the working week and would prove nothing.
 *
 * The dates throughout: 7 September 2026 is a Monday, 12 September the Saturday of that week.
 */
class WorkWeekPlannerTest {

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

  private val freitagDavor = LocalDate.of(2026, 9, 4)
  private val montag = LocalDate.of(2026, 9, 7)
  private val samstag = LocalDate.of(2026, 9, 12)
  private val sonntag = LocalDate.of(2026, 9, 13)

  /** A project with a real calendar, a real resource manager and a running scheduler. */
  private class Projekt {
    val kalender = WeekendCalendarImpl()
    val builder: TestSetupHelper.TaskManagerBuilder =
      TestSetupHelper.newTaskManagerBuilder().withCalendar(kalender)
    val taskManager: TaskManager = builder.build()
    val resourceManager: HumanResourceManager = builder.resourceManager
    val resourceProperties: CustomPropertyManager get() = resourceManager.customPropertyManager
    val taskProperties: CustomPropertyManager get() = taskManager.customPropertyManager

    fun person(
      name: String, id: Int, woche: String? = null, stunden: Double = 8.0, plan: String? = null
    ): HumanResource = resourceManager.create(name, id).also { person ->
      person.setValue(EffortDrivenProperties.findOrCreateResourceHours(resourceProperties), stunden)
      if (woche != null) {
        person.setWorkWeek(resourceProperties, WorkWeekSchedule.parse(woche).schedule)
      }
      if (plan != null) {
        person.setValue(
          EffortDrivenProperties.findOrCreateResourceSchedule(resourceProperties), plan)
      }
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

    fun zuordnen(vorgang: Task, person: HumanResource, last: Float = 100f) {
      vorgang.assignmentCollection.addAssignment(person).load = last
    }

    fun folgt(spaeter: Task, frueher: Task) {
      taskManager.dependencyCollection.createDependency(spaeter, frueher)
    }

    /** One whole planning pass, in the order the running program uses. */
    fun planen() {
      taskManager.algorithmCollection.effortDrivenDurationAlgorithm.run()
      taskManager.algorithmCollection.scheduler.run()
    }

    fun beginn(vorgang: Task): LocalDate = vorgang.start.time.toModelLocalDate()
    fun ende(vorgang: Task): LocalDate = vorgang.end.time.toModelLocalDate()
    fun dauer(vorgang: Task): Int = vorgang.duration.length
  }

  private fun tagFrei(person: HumanResource, von: LocalDate, bisAusschliesslich: LocalDate) {
    person.addDaysOff(GanttDaysOff(von.toModelDate(), bisAusschliesslich.toModelDate()))
  }

  // ---- STELLE 1: TaskManagerImpl.deriveDurationWithDaysOff --------------------------------------

  /**
   * THE MOST IMPORTANT OF THE THREE. This is the way the SCHEDULER sets durations, so it runs on
   * every open and on every change; a working week that does not reach it reaches nothing that a
   * person does not explicitly ask for.
   *
   * THE SHAPE, and why it is this one. Natalie works Monday to Saturday and has the Saturday of
   * that week off. 48 hours of effort at 8 hours a day:
   *
   *  * on the PROJECT calendar the Saturday is not a working day at all, so it is never walked
   *    over: Mon-Fri give 40 hours in five days, Monday the 14th gives the last 8 -- SIX days.
   *  * on HER week the Saturday IS a working day, and her day off makes it a day of the task that
   *    delivers nothing -- the same rule any holiday follows. Mon-Fri, then the empty Saturday,
   *    then Monday: SEVEN days.
   *
   * SIX IS ALSO WHAT `computeDurationDays` WOULD SAY -- ceil(48 / 8) -- and that is the point of
   * choosing 48 rather than 40. The effort-driven algorithm runs in the same pass and writes six.
   * A check expecting six would therefore be green even with this whole hook deleted. Seven can be
   * produced by nothing but the days-off walk on Natalie's own grid.
   */
  @Test
  fun `die hergeleitete dauer folgt der arbeitswoche`() {
    val projekt = Projekt()
    val natalie = projekt.person("Natalie", 1, woche = "1,2,3,4,5,6")
    tagFrei(natalie, samstag, sonntag)

    val vorher = projekt.vorgang("A", freitagDavor)
    val vorgang = projekt.aufwand(projekt.vorgang("B", montag), 48.0)
    projekt.zuordnen(vorgang, natalie)
    projekt.folgt(vorgang, vorher)
    projekt.planen()

    assertEquals(montag, projekt.beginn(vorgang),
      "setup: the successor has to begin on the Monday, otherwise the days below are other days")
    assertEquals(7, projekt.dauer(vorgang),
      "THE SATURDAY IS HERS: on the project calendar it is not a working day and her day off on "
        + "it costs nothing (six days). On her working week it is a day of the task that delivers "
        + "no hours, exactly as any holiday does -- seven days. Six is also what ceil(48/8) gives, "
        + "so only the per-task grid can produce seven.")
  }

  /**
   * THE HEAD CASE GETS THE SAME TREATMENT, and it needs its own check because it enters through a
   * DIFFERENT DOOR: a task with neither a predecessor nor an earliest begin is never handed to
   * `modifyTaskStart`, so `SchedulerImpl` derives its duration in the `else` branch of
   * `schedule(node)` instead.
   *
   * Both doors call `SchedulerImpl.deriveDuration`, which calls the one derivation the task
   * manager handed the scheduler in its constructor. That is why converting the derivation once
   * reaches both -- but "they happen to share a method today" is exactly the kind of fact that
   * stops being true without anybody noticing, and then the working week would hold for tasks
   * with a predecessor and not for the heads of the plan. Same numbers as above, deliberately.
   */
  @Test
  fun `ein kopfvorgang bekommt dieselbe arbeitswochen behandlung`() {
    val projekt = Projekt()
    val natalie = projekt.person("Natalie", 1, woche = "1,2,3,4,5,6")
    tagFrei(natalie, samstag, sonntag)

    val vorgang = projekt.aufwand(projekt.vorgang("Kopf", montag), 48.0)
    projekt.zuordnen(vorgang, natalie)
    projekt.planen()

    assertEquals(montag, projekt.beginn(vorgang), "setup: the head task is not moved")
    assertEquals(7, projekt.dauer(vorgang),
      "a task without a predecessor has to be planned on the same grid as one with a predecessor")
  }

  /**
   * NOBODY HAS ENTERED ANYTHING: the project calendar decides, and the very same Saturday day off
   * costs nothing.
   *
   * The companion of the two above, with ONE difference -- the working week is missing. Otto has
   * the same hours, the same effort and the same day off on the same Saturday. Six days, and it is
   * the project calendar that says so.
   *
   * IN THE SAME PROJECT AS SOMEBODY WHO HAS ONE. That is deliberate: [WorkWeekWorkingDays] caches
   * per task, and a cache that handed the second task the first task's grid would show up here and
   * nowhere else in this file.
   */
  @Test
  fun `ohne eingetragene arbeitswoche entscheidet der projektkalender`() {
    val projekt = Projekt()
    val natalie = projekt.person("Natalie", 1, woche = "1,2,3,4,5,6")
    val otto = projekt.person("Otto", 2)
    tagFrei(natalie, samstag, sonntag)
    tagFrei(otto, samstag, sonntag)

    val ihrer = projekt.aufwand(projekt.vorgang("ihrer", montag), 48.0)
    projekt.zuordnen(ihrer, natalie)
    val seiner = projekt.aufwand(projekt.vorgang("seiner", montag), 48.0)
    projekt.zuordnen(seiner, otto)
    projekt.planen()

    assertEquals(6, projekt.dauer(seiner),
      "Otto has entered no working week: his Saturday off falls on a day the project calendar "
        + "does not use anyway, and costs nothing")
    assertEquals(7, projekt.dauer(ihrer),
      "and the task of the person who DID enter one keeps its own answer in the same run")
  }

  /**
   * THE HOOK STILL TAKES NO INFORMATION AWAY. A task for which nothing can be derived -- effort
   * recorded, nobody assigned who could deliver it -- keeps the duration it has.
   *
   * That property is what makes the hook safe to run on every planning pass, and it is stated in
   * the comment above `deriveDurationWithDaysOff`. It has nothing to do with working weeks and
   * everything to do with not breaking it while wiring one in.
   */
  @Test
  fun `ohne zuordnung behaelt der vorgang seine dauer`() {
    val projekt = Projekt()
    projekt.person("Natalie", 1, woche = "1,2,3,4,5,6")
    val vorgang = projekt.aufwand(projekt.vorgang("ohne jemanden", montag, tage = 3L), 48.0)
    projekt.planen()

    assertEquals(3, projekt.dauer(vorgang),
      "nothing is derivable without anybody on the task, and then the entered duration stands")
  }

  // ---- STELLE 2: EffortDrivenDurationAlgorithm.recalculateLeaf ----------------------------------

  /**
   * THE SECOND PLACE, and it is reached only when the daily rate is TIME-DEPENDENT: with a
   * constant rate `recalculateLeaf` takes the `computeDurationDays` branch, which never asks a
   * calendar at all. So the check needs an hours schedule, and the numbers have to be chosen so
   * that the schedule's boundary falls between the two grids.
   *
   * Natalie works Monday to Thursday, 8 hours, and from Monday 14 September only 4. 40 hours of
   * effort from Monday 7 September:
   *
   *  * PROJECT CALENDAR: Mon-Fri at 8 hours is 40 hours in FIVE days. The boundary is never
   *    reached.
   *  * HER WEEK: Mon-Thu give 32 hours in four days, the Friday is not hers, and the next two days
   *    she works fall past the boundary at 4 hours each -- 36, then 40. SIX days.
   *
   * THE SCHEDULER IS SWITCHED OFF for this check, and that is what makes it belong to this place
   * and to no other: `TaskManagerImpl.deriveDurationWithDaysOff` computes the same arithmetic and
   * would answer six as well. With the scheduler off it never runs, and the six can only have been
   * written by the effort-driven algorithm.
   */
  @Test
  fun `die aufwandsgetriebene dauer folgt der arbeitswoche`() {
    val projekt = Projekt()
    projekt.taskManager.algorithmCollection.scheduler.isEnabled = false
    val natalie = projekt.person("Natalie", 1, woche = "1,2,3,4", plan = "2026-09-14: 4")

    val vorgang = projekt.aufwand(projekt.vorgang("B", montag), 40.0)
    projekt.zuordnen(vorgang, natalie)
    projekt.taskManager.algorithmCollection.effortDrivenDurationAlgorithm.run()

    assertEquals(6, projekt.dauer(vorgang),
      "on the project calendar the five days Mon-Fri lie entirely before the boundary and give "
        + "40 hours. On her week the Friday drops out, so two of the days fall past the boundary "
        + "at four hours -- six days. Five is also ceil(40/8), so only the per-task grid gives six.")
  }

  // ---- STELLE 3: planRecurrences ---------------------------------------------------------------

  /**
   * THE THIRD PLACE: a recurring date that falls on a non-working day moves FORWARD to the next
   * working day -- and which day that is depends on the week of the people on the series.
   *
   * Every fifth day from Monday 7 September gives the raw dates 7, 12, 17 September. The 12th is a
   * Saturday:
   *
   *  * PROJECT CALENDAR: it moves on to Monday the 14th.
   *  * NATALIE'S WEEK, which names Saturday: it stays on the 12th.
   *
   * The first date is the source task itself and is never created again, so what is planned is the
   * second and the third.
   */
  @Test
  fun `ein serientermin folgt der arbeitswoche`() {
    val projekt = Projekt()
    val natalie = projekt.person("Natalie", 1, woche = "1,2,3,4,5,6")
    val quelle = projekt.vorgang("Serie", montag)
    projekt.zuordnen(quelle, natalie)
    quelle.customValues.setValue(
      findOrCreateRecurrence(projekt.taskProperties), "taeglich; alle 5; Anzahl 3")

    val plan = planRecurrences(projekt.taskManager, projekt.taskProperties,
      projekt.resourceProperties)

    assertEquals(listOf(samstag, LocalDate.of(2026, 9, 17)), plan.occurrences.map { it.date },
      "the Saturday is a working day of hers, so the date stays on it instead of being pushed to "
        + "Monday the 14th")
  }
}
