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
package net.sourceforge.ganttproject.task.algorithm

import biz.ganttproject.core.calendar.AlwaysWorkingTimeCalendarImpl
import biz.ganttproject.core.calendar.GPCalendarCalc
import biz.ganttproject.core.calendar.GanttDaysOff
import biz.ganttproject.core.calendar.WeekendCalendarImpl
import biz.ganttproject.core.option.BooleanOption
import biz.ganttproject.core.option.ColorOption
import biz.ganttproject.core.option.DefaultBooleanOption
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.core.time.TimeUnitStack
import biz.ganttproject.core.time.impl.GPTimeUnitStack
import biz.ganttproject.customproperty.CustomColumnsManager
import junit.framework.TestCase
import net.sourceforge.ganttproject.fork.durationDaysWithDaysOff
import net.sourceforge.ganttproject.fork.toModelLocalDate
import net.sourceforge.ganttproject.gui.NotificationManager
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.roles.RoleManager
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.task.TaskManagerConfig
import java.awt.Color
import java.net.URL
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/**
 * P2 — axis B ("no-effort") ACTS on the duration.
 *
 * Axis B has been carried by every assignment since P1 (`isNoEffort`, XML attribute `no-effort`,
 * default `false`), but nobody read it. Here it becomes effective, and only here: an assignment
 * with `isNoEffort == true` contributes NO working hours, so it does not shorten the task.
 *
 * The negation is deliberate and is not touched: `false` means "contributes", and that is
 * today's behaviour. A file without the attribute therefore computes exactly as before.
 *
 * THE CONSEQUENCE FOR DAYS OFF, spelled out because it is the one thing that is not obvious:
 * a person who contributes nothing cannot have anything taken away either. Their holiday
 * therefore stops changing the duration as well. This is not a second decision, it follows from
 * the first one — [net.sourceforge.ganttproject.fork.durationDaysWithDaysOff] subtracts a day off
 * from that person's OWN hours, and those hours are zero once B is set. Dropping the whole share
 * and dropping only the hours come to the same number here; dropping the share is the one that
 * also keeps the day-off list out of the walk.
 *
 * AXIS A IS NOT TOUCHED HERE. Blocking absences and moving tasks is P3 on `axes-blocking`.
 *
 * Setup follows `EffortDrivenTriggerTest`, which is the established pattern for the days-off
 * question in this project.
 */
class NoEffortAxisTest : TestCase() {

  init {
    // GanttDaysOff builds GanttCalendars, and those need a locale. Same bootstrap as
    // EffortDrivenTriggerTest.
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

  private lateinit var taskManager: TaskManager
  private lateinit var resourceManager: HumanResourceManager
  private lateinit var resourceProperties: CustomColumnsManager

  override fun setUp() {
    super.setUp()
    resourceProperties = CustomColumnsManager()
    resourceManager = HumanResourceManager(
      RoleManager.Access.getInstance().defaultRole, resourceProperties)
    resourceManager.create("resource#1", 1)
    resourceManager.create("resource#2", 2)
    taskManager = TaskManager.Access.newInstance(null, object : TaskManagerConfig {
      override fun getDefaultColor(): Color? = null
      override fun getDefaultColorOption(): ColorOption? = null
      override fun getCalendar(): GPCalendarCalc = AlwaysWorkingTimeCalendarImpl()
      override fun getTimeUnitStack(): TimeUnitStack = GPTimeUnitStack()
      override fun getResourceManager(): HumanResourceManager =
        this@NoEffortAxisTest.resourceManager
      override fun getProjectDocumentURL(): URL? = null
      override fun getNotificationManager(): NotificationManager? = null
      override fun getSchedulerDisabledOption(): BooleanOption =
        DefaultBooleanOption("scheduler.disabled", false)
    })
  }

  private fun setHoursPerDay(resource: HumanResource, hours: Double) {
    val def = EffortDrivenProperties.findOrCreateResourceHours(resourceProperties)
    resource.setValue(def, hours)
  }

  private fun setSchedule(resource: HumanResource, text: String) {
    val def = EffortDrivenProperties.findOrCreateResourceSchedule(resourceProperties)
    resource.setValue(def, text)
  }

  private fun setEffort(task: Task, hours: Double) {
    val def = EffortDrivenProperties.findOrCreateTaskEffort(taskManager.customPropertyManager)
    task.customValues.setValue(def, hours)
  }

  private fun september(day: Int): Date = CalendarFactory.createGanttCalendar(2026, 8, day).time

  /**
   * Weekends are excluded by hand rather than through the calendar, so that this class does not
   * have to reach into levelling for `workingDayTest` — those files belong to another line of
   * work and are not touched here.
   */
  private val withoutWeekends: (LocalDate) -> Boolean = { day ->
    day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY
  }

  private fun daysOffDuration(task: Task, start: Date): Int? =
    task.durationDaysWithDaysOff(
      taskManager.customPropertyManager, resourceProperties,
      start.toModelLocalDate(), withoutWeekends)

  // ------------------------------------------------------------------------------------------
  // The available hours — the place where axis B has to bite first.
  // ------------------------------------------------------------------------------------------

  /**
   * NACHWEIS 1 at its smallest: the person who sits in on a task but does no work on it must not
   * shorten it. Three hours plus five hours are three hours when the five are marked.
   */
  fun testANoEffortAssignmentContributesNoHours() {
    val task = taskManager.createTask()
    val worker = resourceManager.getById(1)
    val bystander = resourceManager.getById(2)
    setHoursPerDay(worker, 3.0)
    setHoursPerDay(bystander, 5.0)
    task.assignmentCollection.addAssignment(worker).load = 100f
    task.assignmentCollection.addAssignment(bystander).apply {
      load = 100f
      isNoEffort = true
    }
    assertEquals("the marked person still contributes hours",
      3.0, task.availableHoursPerDay(resourceProperties), 0.001)
  }

  /** NACHWEIS 3: without the tick, nothing changes at all. Both people add up as before. */
  fun testWithoutTheTickBothPeopleStillAddUp() {
    val task = taskManager.createTask()
    setHoursPerDay(resourceManager.getById(1), 3.0)
    setHoursPerDay(resourceManager.getById(2), 5.0)
    task.assignmentCollection.addAssignment(resourceManager.getById(1)).load = 100f
    task.assignmentCollection.addAssignment(resourceManager.getById(2)).load = 100f
    assertEquals(8.0, task.availableHoursPerDay(resourceProperties), 0.001)
  }

  /** Everyone marked means nothing available — the same answer as nobody assigned. */
  fun testEveryAssignmentMarkedLeavesNoAvailability() {
    val task = taskManager.createTask()
    setHoursPerDay(resourceManager.getById(1), 3.0)
    task.assignmentCollection.addAssignment(resourceManager.getById(1)).apply {
      load = 100f
      isNoEffort = true
    }
    assertEquals(0.0, task.availableHoursPerDay(resourceProperties), 0.001)
  }

  // ------------------------------------------------------------------------------------------
  // The hours schedule — the second reader of the assignments, and it must agree with the first.
  // ------------------------------------------------------------------------------------------

  /**
   * The time-dependent rate must drop the marked person as well. Otherwise the two readers would
   * disagree and the duration would depend on whether anyone happens to have filled in an hours
   * schedule.
   */
  fun testTheHoursScheduleAlsoDropsAMarkedAssignment() {
    val task = taskManager.createTask()
    val worker = resourceManager.getById(1)
    val bystander = resourceManager.getById(2)
    setHoursPerDay(worker, 3.0)
    setHoursPerDay(bystander, 5.0)
    setSchedule(bystander, "2026-09-01: 9")
    task.assignmentCollection.addAssignment(worker).load = 100f
    task.assignmentCollection.addAssignment(bystander).apply {
      load = 100f
      isNoEffort = true
    }
    val schedule = task.capacitySchedule(resourceProperties).schedule
    assertEquals("the marked person's base rate is still in the schedule",
      3.0, schedule.base, 0.001)
    assertEquals("the marked person's section is still in the schedule",
      3.0, schedule.hoursOn(LocalDate.of(2026, 9, 15)), 0.001)
  }

  // ------------------------------------------------------------------------------------------
  // NACHWEIS 1 through the duration, which is what the user actually sees.
  // ------------------------------------------------------------------------------------------

  /**
   * Two people at one task, one of them marked: the duration is the SAME as with the one
   * contributing person alone. Measured against that person alone, not against a number typed in
   * here, so the check cannot pass because both sides happened to be wrong.
   */
  fun testAMarkedPersonDoesNotShortenTheTask() {
    val worker = resourceManager.getById(1)
    val bystander = resourceManager.getById(2)
    setHoursPerDay(worker, 8.0)
    setHoursPerDay(bystander, 8.0)

    val alone = taskManager.createTask()
    setEffort(alone, 40.0)
    alone.assignmentCollection.addAssignment(worker).load = 100f

    val withBystander = taskManager.createTask()
    setEffort(withBystander, 40.0)
    withBystander.assignmentCollection.addAssignment(worker).load = 100f
    withBystander.assignmentCollection.addAssignment(bystander).apply {
      load = 100f
      isNoEffort = true
    }

    taskManager.algorithmCollection.effortDrivenDurationAlgorithm.run()

    assertEquals("setup: 40 h at 8 h a day are five days", 5, alone.duration.length)
    assertEquals("the marked person shortened the task", 5, withBystander.duration.length)
  }

  /**
   * The counterpart, so the check above cannot pass for the wrong reason: WITHOUT the tick the
   * second person does shorten the task. 40 h over 16 h a day is three days.
   */
  fun testAnUnmarkedSecondPersonStillShortensTheTask() {
    val worker = resourceManager.getById(1)
    val helper = resourceManager.getById(2)
    setHoursPerDay(worker, 8.0)
    setHoursPerDay(helper, 8.0)
    val task = taskManager.createTask()
    setEffort(task, 40.0)
    task.assignmentCollection.addAssignment(worker).load = 100f
    task.assignmentCollection.addAssignment(helper).load = 100f
    taskManager.algorithmCollection.effortDrivenDurationAlgorithm.run()
    assertEquals(3, task.duration.length)
  }

  // ------------------------------------------------------------------------------------------
  // NACHWEIS 2 — the days off.
  // ------------------------------------------------------------------------------------------

  /**
   * The holiday of a MARKED person does not change the duration. Five working days of effort
   * stay five working days, whatever that person does with their week.
   *
   * Wednesday alone is the day off: `GanttDaysOff`'s finish is EXCLUSIVE. That is not decided
   * here, it is what `DateInterval`, `GanttDialogPerson`, `ProjectFileImporterImpl` and
   * `LoadDistribution` all do; `GanttDaysOff.isADayOff` contradicts them and has no caller
   * (finding F24, not touched).
   */
  fun testTheHolidayOfAMarkedPersonDoesNotChangeTheDuration() {
    val worker = resourceManager.getById(1)
    val bystander = resourceManager.getById(2)
    setHoursPerDay(worker, 8.0)
    setHoursPerDay(bystander, 8.0)
    val task = taskManager.createTask()
    setEffort(task, 40.0)
    task.assignmentCollection.addAssignment(worker).load = 100f
    task.assignmentCollection.addAssignment(bystander).apply {
      load = 100f
      isNoEffort = true
    }

    // Monday, 7 September 2026.
    assertEquals("setup: only the contributing person counts, so five days",
      5, daysOffDuration(task, september(7)))

    // Wednesday of that same week — inside the task and a working day.
    bystander.addDaysOff(GanttDaysOff(september(9), september(10)))
    assertEquals("the holiday must be on the person", 1, bystander.daysOff.size)

    assertEquals("the holiday of a person who contributes nothing changed the duration",
      5, daysOffDuration(task, september(7)))
  }

  /**
   * NACHWEIS 2, the other half: the holiday of a CONTRIBUTING person still lengthens the task.
   * Without this the check above would also pass if days off had stopped working altogether.
   */
  fun testTheHolidayOfAContributingPersonStillChangesTheDuration() {
    val worker = resourceManager.getById(1)
    val bystander = resourceManager.getById(2)
    setHoursPerDay(worker, 8.0)
    setHoursPerDay(bystander, 8.0)
    val task = taskManager.createTask()
    setEffort(task, 40.0)
    task.assignmentCollection.addAssignment(worker).load = 100f
    task.assignmentCollection.addAssignment(bystander).apply {
      load = 100f
      isNoEffort = true
    }

    worker.addDaysOff(GanttDaysOff(september(9), september(10)))
    assertEquals("the holiday must be on the person", 1, worker.daysOff.size)

    assertEquals("five working days of effort plus one day off is six",
      6, daysOffDuration(task, september(7)))
  }

  /**
   * NACHWEIS 3 for the days-off path: with nobody marked, two people and one holiday give the
   * number they gave before. 40 h over 16 h a day is 2.5 days, and the lost Wednesday pushes the
   * remainder onto a fourth day — Mon 16, Tue 32, Wed 40 minus the absent person is 40 … which
   * lands exactly on the effort. Written out so the number is checkable rather than believed.
   */
  fun testWithoutTheTickTheHolidayCountsAsBefore() {
    val one = resourceManager.getById(1)
    val two = resourceManager.getById(2)
    setHoursPerDay(one, 8.0)
    setHoursPerDay(two, 8.0)
    val task = taskManager.createTask()
    setEffort(task, 40.0)
    task.assignmentCollection.addAssignment(one).load = 100f
    task.assignmentCollection.addAssignment(two).load = 100f

    assertEquals("setup: 40 h over 16 h a day are three days",
      3, daysOffDuration(task, september(7)))

    two.addDaysOff(GanttDaysOff(september(9), september(10)))
    assertEquals(3, daysOffDuration(task, september(7)))
  }

  /**
   * The whole chain, through the SCHEDULER rather than by calling the day-off arithmetic
   * directly: a task with a predecessor, because that is what makes the scheduler call
   * `modifyTaskStart`, which is where the days-off derivation hangs (A2). Template:
   * `EffortDrivenTriggerTest.testHolidayInsideASuccessorLengthensIt`.
   */
  fun testThroughTheSchedulerAMarkedPersonsHolidayChangesNothing() {
    val (tasks, resources) = weekendProject()
    val worker = resources.getById(1)
    val bystander = resources.getById(2)
    // Monday, 7 September 2026.
    val predecessor = tasks.newTaskBuilder().withName("A").withStartDate(september(7)).build()
    val task = tasks.newTaskBuilder().withName("B").withStartDate(september(7)).build()
    tasks.dependencyCollection.createDependency(task, predecessor)

    val def = EffortDrivenProperties.findOrCreateTaskEffort(tasks.customPropertyManager)
    task.customValues.setValue(def, 40.0)
    task.assignmentCollection.addAssignment(worker).load = 100f
    task.assignmentCollection.addAssignment(bystander).apply {
      load = 100f
      isNoEffort = true
    }
    tasks.algorithmCollection.effortDrivenDurationAlgorithm.run()
    tasks.algorithmCollection.scheduler.run()
    assertEquals("setup: only the contributing person counts, so five days",
      5, task.duration.length)

    bystander.addDaysOff(GanttDaysOff(september(9), september(10)))
    assertEquals("the holiday must be on the person", 1, bystander.daysOff.size)
    tasks.algorithmCollection.scheduler.run()

    assertEquals("the scheduler let a marked person's holiday move the duration",
      5, task.duration.length)
  }

  /**
   * A second project of its own, with a REAL calendar instead of the always-working one: a day
   * off has to fall on a day that is a working day to begin with. Template:
   * `EffortDrivenTriggerTest.weekendProject`.
   */
  private fun weekendProject(): Pair<TaskManager, HumanResourceManager> {
    val properties = CustomColumnsManager()
    val resources = HumanResourceManager(
      RoleManager.Access.getInstance().defaultRole, properties)
    resources.create("Person", 1)
    resources.create("Gast", 2)
    val tasks = TaskManager.Access.newInstance(null, object : TaskManagerConfig {
      override fun getDefaultColor(): Color? = null
      override fun getDefaultColorOption(): ColorOption? = null
      override fun getCalendar(): GPCalendarCalc = WeekendCalendarImpl()
      override fun getTimeUnitStack(): TimeUnitStack = GPTimeUnitStack()
      override fun getResourceManager(): HumanResourceManager = resources
      override fun getProjectDocumentURL(): URL? = null
      override fun getNotificationManager(): NotificationManager? = null
      override fun getSchedulerDisabledOption(): BooleanOption =
        DefaultBooleanOption("scheduler.disabled", false)
    })
    val hours = EffortDrivenProperties.findOrCreateResourceHours(properties)
    resources.getById(1).setValue(hours, 8.0)
    resources.getById(2).setValue(hours, 8.0)
    return tasks to resources
  }
}
