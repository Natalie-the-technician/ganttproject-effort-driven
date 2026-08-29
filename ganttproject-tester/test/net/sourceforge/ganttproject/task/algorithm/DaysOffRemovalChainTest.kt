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
import biz.ganttproject.customproperty.CustomPropertyClass
import junit.framework.TestCase
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
import java.util.Date
import java.util.Locale

/**
 * THE OTHER DIRECTION: a day off that is TAKEN AWAY again has to give the task its old duration
 * back.
 *
 * Every link of that chain was pinned on its own before this class existed, but the chain as a
 * whole was never measured end to end:
 *
 *  * `EffortDrivenTriggerTest.testHolidayInsideASuccessorLengthensIt` pins the ADDING of a day off.
 *  * `HumanResourceDaysOffTest` pins that removing one fires the EVENT.
 *  * `NoEffortAxisTest` and `LevellingBlockingTest` pin what the days off do to the two axes and
 *    to the levelling.
 *
 * Nothing joined them up: no test said "delete the holiday and the duration goes back". These do,
 * and they measure the duration itself rather than an event count.
 *
 * THE ROUTE MATTERS. A test that removed the interval by some means the program never uses would
 * pin nothing about the program. The one and only place in the tree that takes a day off away is
 * `GanttDialogPerson.applyChanges()`, and [pressOkInThePersonDialog] below is a line-by-line
 * mirror of it. When that method changes, this mirror has to change with it, or the tests stop
 * saying anything.
 *
 * AND NOTHING IS RUN BY HAND. After the day off is booked or dropped, no test here calls
 * `effortDrivenDurationAlgorithm` or `scheduler` — the whole point is that the chain from the list
 * mutation through to the recomputed duration runs on its own. An explicit run would repair a
 * broken chain and the test would go green for the wrong reason.
 */
class DaysOffRemovalChainTest : TestCase() {
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

  /**
   * THE CASE THAT F25 WAS ABOUT, and it has to stand on its own.
   *
   * One single day off, and it is the one that goes. The list is EMPTY afterwards, so the whole
   * notification rests on the `clear()` — there is no following `addDaysOff` to fire a second
   * event and repair the chain by accident. A test that removed one of two days off would go green
   * even with the notification for the removal missing entirely, because the re-adding of the
   * survivor would recompute the duration anyway. That is why this case is written first and
   * separately.
   *
   * NOT RED WHEN IT WAS WRITTEN. The chain already held, so there was nothing to repair -- this
   * test is the missing evidence for a state that was already right, not the pin of a fix. To show
   * that it can fail at all, the notification it depends on was taken away for one run: the body of
   * `intervalRemoved` in `HumanResource`'s list listener was emptied, and nothing else was touched.
   * Verbatim, 29 August 2026:
   *
   *     junit.framework.AssertionFailedError: THE LAST DAY OFF: taking the only holiday away must
   *     give the task its old duration back, through the very chain the dialog walks. The whole
   *     notification hangs on the clear() here, with nothing added afterwards to cover for it.
   *     expected:<5> but was:<6>
   *         at net.sourceforge.ganttproject.task.algorithm.DaysOffRemovalChainTest
   *             .testRemovingTheOnlyDayOffGivesTheDurationBack(DaysOffRemovalChainTest.kt:118)
   *
   * In that same run the two-days-off test below stayed GREEN. That is the blind spot named above,
   * measured rather than argued: with the removal notifying nobody, the companion case notices
   * nothing.
   */
  fun testRemovingTheOnlyDayOffGivesTheDurationBack() {
    val (tasks, resources) = weekendProject()
    val person = resources.getById(1)
    val task = successorOf40Hours(tasks, person)

    val beforeTheHoliday = durationDays(task)
    assertEquals("setup: 40 h at 8 h a day are five days", 5, beforeTheHoliday)

    // Wednesday, 9 September 2026 — inside the task and a working day. The finish is EXCLUSIVE,
    // so this is the Wednesday alone.
    person.addDaysOff(GanttDaysOff(september(9), september(10)))
    assertEquals("the holiday must be on the person", 1, person.daysOff.size)
    val withTheHoliday = durationDays(task)
    assertEquals("five working days of effort plus one day off is six", 6, withTheHoliday)

    // Ok in the person dialog with the days off list emptied.
    pressOkInThePersonDialog(person, listOf())

    assertEquals("the holiday must be gone from the person", 0, person.daysOff.size)
    assertEquals(
      "THE LAST DAY OFF: taking the only holiday away must give the task its old duration back, "
        + "through the very chain the dialog walks. The whole notification hangs on the clear() "
        + "here, with nothing added afterwards to cover for it.",
      beforeTheHoliday, durationDays(task))
  }

  /**
   * The companion case: two days off, one of them survives the Ok. Weaker than the one above — the
   * survivor being written back fires an event of its own — but it pins the arithmetic rather than
   * just the notification: the duration has to fall by exactly the one day that went, not back to
   * the value without any holiday at all.
   */
  fun testRemovingOneOfTwoDaysOffGivesBackExactlyThatOneDay() {
    val (tasks, resources) = weekendProject()
    val person = resources.getById(1)
    val task = successorOf40Hours(tasks, person)
    assertEquals("setup: 40 h at 8 h a day are five days", 5, durationDays(task))

    // Wednesday and Thursday of the task's first week, booked as two separate holidays.
    val wednesday = GanttDaysOff(september(9), september(10))
    val thursday = GanttDaysOff(september(10), september(11))
    person.addDaysOff(wednesday)
    person.addDaysOff(thursday)
    assertEquals("both holidays must be on the person", 2, person.daysOff.size)
    assertEquals("five days of effort plus two days off is seven", 7, durationDays(task))

    // Ok with the Wednesday deleted in the dialog and the Thursday left standing.
    pressOkInThePersonDialog(person, listOf(thursday))

    assertEquals("one holiday must be left", 1, person.daysOff.size)
    assertEquals("dropping one of the two days off must give exactly that day back", 6,
      durationDays(task))
  }

  /**
   * A LINE-BY-LINE MIRROR of `GanttDialogPerson.applyChanges()`: everything is thrown away and the
   * intervals still standing in the dialog's list are written back as fresh `GanttDaysOff`
   * objects. Deleting a holiday in the dialog does not remove anything from the resource — it
   * removes a row from the dialog's own model, and the resource only ever sees a clear followed by
   * the rewrite.
   *
   * The round trip through `DateInterval.createFromModelDates`/`getEnd` that the dialog does on
   * the way in and out is stable — `createFromModelDates` keeps the model end untouched in `end`,
   * and that is the field `applyChanges` reads — so passing the original intervals straight
   * through is what the dialog would hand over.
   *
   * THE CLEAR IS THE ONE THE DIALOG CALLS. It used to be `getDaysOff().clear()`, reaching into the
   * handed-out list; since the removal methods were added it is [HumanResource.clearDaysOff]. Both
   * routes were measured here and both give the same three durations -- the method calls the very
   * same `DefaultListModel.clear()` -- but the mirror follows the dialog rather than picking the
   * one that suits it, because a mirror that has drifted pins nothing.
   */
  private fun pressOkInThePersonDialog(person: HumanResource, remaining: List<GanttDaysOff>) {
    person.clearDaysOff()
    for (interval in remaining) {
      person.addDaysOff(GanttDaysOff(interval.start.time, interval.finish.time))
    }
  }

  /**
   * A task of 40 h of effort with the person on it at 100 %, standing behind a predecessor. The
   * predecessor is what makes the scheduler call `modifyTaskStart`, which is where the days-off
   * derivation hangs; the shape is the one from
   * `EffortDrivenTriggerTest.testHolidayInsideASuccessorLengthensIt`.
   */
  private fun successorOf40Hours(tasks: TaskManager, person: HumanResource): Task {
    // Monday, 7 September 2026.
    val predecessor = tasks.newTaskBuilder().withName("A").withStartDate(september(7)).build()
    val task = tasks.newTaskBuilder().withName("B").withStartDate(september(7)).build()
    tasks.dependencyCollection.createDependency(task, predecessor)
    val def = EffortDrivenProperties.findOrCreateTaskEffort(tasks.customPropertyManager)
    task.customValues.setValue(def, 40.0)
    task.assignmentCollection.addAssignment(person).load = 100f
    tasks.algorithmCollection.effortDrivenDurationAlgorithm.run()
    tasks.algorithmCollection.scheduler.run()
    return task
  }

  private fun durationDays(task: Task): Int = task.duration.length

  private fun september(day: Int): Date = CalendarFactory.createGanttCalendar(2026, 8, day).time

  /**
   * A project with a REAL calendar rather than the always-working one: a day off has to fall on a
   * day that is a working day to begin with. Same shape as
   * `EffortDrivenTriggerTest.weekendProject`.
   */
  private fun weekendProject(): Pair<TaskManager, HumanResourceManager> {
    val properties = CustomColumnsManager()
    val resources = HumanResourceManager(
      RoleManager.Access.getInstance().defaultRole, properties)
    resources.create("Person", 1)
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
    resources.addView(EffortDrivenTrigger(tasks))
    // The hours per day live on the resource manager's property set, not on the task manager's.
    val hours = properties.definitions.firstOrNull { it.name == "hours_per_day" }
      ?: properties.createDefinition(CustomPropertyClass.DOUBLE, "hours_per_day", null)
    resources.getById(1).setValue(hours, 8.0)
    return tasks to resources
  }
}
