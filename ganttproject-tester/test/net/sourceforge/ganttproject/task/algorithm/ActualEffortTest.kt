/*
Copyright 2026

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
// NEUE DATEI DIESES FORKS
package net.sourceforge.ganttproject.task.algorithm

import biz.ganttproject.core.calendar.AlwaysWorkingTimeCalendarImpl
import biz.ganttproject.core.calendar.GPCalendarCalc
import biz.ganttproject.core.option.BooleanOption
import biz.ganttproject.core.option.ColorOption
import biz.ganttproject.core.option.DefaultBooleanOption
import biz.ganttproject.core.time.TimeUnitStack
import biz.ganttproject.core.time.impl.GPTimeUnitStack
import biz.ganttproject.customproperty.CustomColumnsManager
import biz.ganttproject.customproperty.CustomPropertyClass
import junit.framework.TestCase
import net.sourceforge.ganttproject.gui.NotificationManager
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.roles.RoleManager
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.task.TaskManagerConfig
import java.awt.Color
import java.net.URL

/**
 * Tests for the recorded effort (`effort_actual_hours`).
 *
 * The most important test here is [testRecordingHoursDoesNotChangeTheDuration]: recording time
 * must never move the plan. Everything else follows the pattern of EffortDrivenModelTest.
 */
class ActualEffortTest : TestCase() {
  private lateinit var taskManager: TaskManager
  private lateinit var resourceManager: HumanResourceManager
  private lateinit var taskProperties: CustomColumnsManager
  private lateinit var resourceProperties: CustomColumnsManager

  override fun setUp() {
    super.setUp()
    taskProperties = CustomColumnsManager()
    resourceProperties = CustomColumnsManager()
    resourceManager = HumanResourceManager(
      RoleManager.Access.getInstance().defaultRole, resourceProperties)
    resourceManager.create("resource#1", 1)
    taskManager = TaskManager.Access.newInstance(null, object : TaskManagerConfig {
      override fun getDefaultColor(): Color? = null
      override fun getDefaultColorOption(): ColorOption? = null
      override fun getCalendar(): GPCalendarCalc = AlwaysWorkingTimeCalendarImpl()
      override fun getTimeUnitStack(): TimeUnitStack = GPTimeUnitStack()
      override fun getResourceManager(): HumanResourceManager? = null
      override fun getProjectDocumentURL(): URL? = null
      override fun getNotificationManager(): NotificationManager? = null
      override fun getSchedulerDisabledOption(): BooleanOption =
        DefaultBooleanOption("scheduler.disabled", false)
    })
  }

  private fun setActual(task: Task, hours: Double) {
    val def = EffortDrivenProperties.findOrCreateTaskActualEffort(taskProperties)
    task.customValues.setValue(def, hours)
  }

  private fun setPlanned(task: Task, hours: Double) {
    val def = EffortDrivenProperties.findOrCreateTaskEffort(taskProperties)
    task.customValues.setValue(def, hours)
  }

  fun testWithoutRecordedHoursTheValueIsNull() {
    assertNull(taskManager.createTask().actualEffortHours(taskProperties))
  }

  fun testRecordedHoursAreReadBack() {
    val task = taskManager.createTask()
    setActual(task, 12.5)
    assertEquals(12.5, task.actualEffortHours(taskProperties)!!, 0.001)
  }

  /** Zero counts as "nothing recorded", not as "took no time". */
  fun testZeroCountsAsNotRecorded() {
    val task = taskManager.createTask()
    setActual(task, 0.0)
    assertNull(task.actualEffortHours(taskProperties))
  }

  fun testNegativeCountsAsNotRecorded() {
    val task = taskManager.createTask()
    setActual(task, -3.0)
    assertNull(task.actualEffortHours(taskProperties))
  }

  /** Planned and recorded effort must not overwrite each other. */
  fun testPlannedAndRecordedAreIndependent() {
    val task = taskManager.createTask()
    setPlanned(task, 20.0)
    setActual(task, 31.0)
    assertEquals(20.0, task.effortHours(taskProperties)!!, 0.001)
    assertEquals(31.0, task.actualEffortHours(taskProperties)!!, 0.001)
  }

  /**
   * A column that the USER created carries the typed text as its NAME, with a generated id.
   * The lookup must find it anyway — this is the trap that made stage 1 fall back to 8 h/day.
   */
  fun testFoundByNameWhenTheUserCreatedTheColumn() {
    val def = taskProperties.createDefinition(
      CustomPropertyClass.DOUBLE, EffortDrivenProperties.TASK_EFFORT_ACTUAL_HOURS, null)
    assertFalse("precondition: the generated id must differ from the name",
      def.id == EffortDrivenProperties.TASK_EFFORT_ACTUAL_HOURS)
    val task = taskManager.createTask()
    task.customValues.setValue(def, 7.0)
    assertEquals(7.0, task.actualEffortHours(taskProperties)!!, 0.001)
  }

  /**
   * THE POINT OF THE WHOLE FEATURE BOUNDARY: recording time must not move the plan.
   *
   * Sets up a task whose duration is driven by effort, then records actual hours far above the
   * estimate. The duration must stay exactly where it was.
   */
  fun testRecordingHoursDoesNotChangeTheDuration() {
    val task = taskManager.createTask()
    val resource = resourceManager.getById(1)
    val hoursDef = EffortDrivenProperties.findOrCreateResourceHours(resourceProperties)
    resource.setValue(hoursDef, 4.0)
    setPlanned(task, 20.0)
    task.assignmentCollection.addAssignment(resource).load = 100f

    val expected = computeDurationDays(
      task.effortHours(taskProperties)!!, task.availableHoursPerDay(resourceProperties))
    assertEquals(5, expected)

    setActual(task, 200.0)   // wildly over the estimate

    assertEquals("recorded hours must not feed back into the plan", expected,
      computeDurationDays(
        task.effortHours(taskProperties)!!, task.availableHoursPerDay(resourceProperties)))
  }
}
