/*
Copyright 2026

NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.

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
import biz.ganttproject.core.option.BooleanOption
import biz.ganttproject.core.option.ColorOption
import biz.ganttproject.core.option.DefaultBooleanOption
import biz.ganttproject.core.time.TimeUnitStack
import biz.ganttproject.core.time.impl.GPTimeUnitStack
import biz.ganttproject.customproperty.CustomColumnsManager
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

/**
 * Tests the parts of the effort-driven computation that need a real task model:
 * reading the custom properties and weighting the daily hours by the assignment load.
 *
 * Setup mirrors TestResourceAssignments, which is the established pattern in this project.
 */
class EffortDrivenModelTest : TestCase() {
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
    resourceManager.create("resource#2", 2)
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

  private fun setEffort(task: Task, hours: Double) {
    val def = EffortDrivenProperties.findOrCreateTaskEffort(taskProperties)
    task.customValues.setValue(def, hours)
  }

  private fun setHoursPerDay(resource: HumanResource, hours: Double) {
    val def = EffortDrivenProperties.findOrCreateResourceHours(resourceProperties)
    resource.setValue(def, hours)
  }

  // --- effort of a task ---

  /** Without an effort value the feature stays out of the way: null, not zero. */
  fun testTaskWithoutEffortReturnsNull() {
    val task = taskManager.createTask()
    assertNull(task.effortHours(taskProperties))
  }

  fun testTaskEffortIsReadBack() {
    val task = taskManager.createTask()
    setEffort(task, 20.0)
    assertEquals(20.0, task.effortHours(taskProperties)!!, 0.001)
  }

  /** Zero or negative effort counts as "not set", so the duration is left alone. */
  fun testZeroEffortCountsAsUnset() {
    val task = taskManager.createTask()
    setEffort(task, 0.0)
    assertNull(task.effortHours(taskProperties))
  }

  // --- daily hours of a resource ---

  /** Untouched projects must keep working, so an unset resource falls back to 8 h. */
  fun testResourceWithoutValueFallsBackToDefault() {
    val resource = resourceManager.getById(1)
    assertEquals(EffortDrivenProperties.DEFAULT_HOURS_PER_DAY,
      resource.hoursPerDay(resourceProperties), 0.001)
  }

  fun testResourceHoursAreReadBack() {
    val resource = resourceManager.getById(1)
    setHoursPerDay(resource, 2.0)
    assertEquals(2.0, resource.hoursPerDay(resourceProperties), 0.001)
  }

  fun testNonPositiveResourceHoursFallBackToDefault() {
    val resource = resourceManager.getById(1)
    setHoursPerDay(resource, 0.0)
    assertEquals(EffortDrivenProperties.DEFAULT_HOURS_PER_DAY,
      resource.hoursPerDay(resourceProperties), 0.001)
  }

  // --- availability of a task through its assignments ---

  /** Nothing assigned means nothing available. computeDurationDays then refuses. */
  fun testTaskWithoutAssignmentsHasNoAvailability() {
    val task = taskManager.createTask()
    assertEquals(0.0, task.availableHoursPerDay(resourceProperties), 0.001)
  }

  fun testSingleAssignmentAtFullLoad() {
    val task = taskManager.createTask()
    val resource = resourceManager.getById(1)
    setHoursPerDay(resource, 2.0)
    task.assignmentCollection.addAssignment(resource).load = 100f
    assertEquals(2.0, task.availableHoursPerDay(resourceProperties), 0.001)
  }

  /** The load must actually be applied, not ignored: half of 8 h is 4 h. */
  fun testLoadIsApplied() {
    val task = taskManager.createTask()
    val resource = resourceManager.getById(1)
    setHoursPerDay(resource, 8.0)
    task.assignmentCollection.addAssignment(resource).load = 50f
    assertEquals(4.0, task.availableHoursPerDay(resourceProperties), 0.001)
  }

  fun testTwoAssignmentsAddUp() {
    val task = taskManager.createTask()
    val r1 = resourceManager.getById(1)
    val r2 = resourceManager.getById(2)
    setHoursPerDay(r1, 3.0)
    setHoursPerDay(r2, 5.0)
    task.assignmentCollection.addAssignment(r1).load = 100f
    task.assignmentCollection.addAssignment(r2).load = 100f
    assertEquals(8.0, task.availableHoursPerDay(resourceProperties), 0.001)
  }

  // --- the whole chain, which is what the user actually asked for ---

  /**
   * The specification example end to end: 20 h at 2 h/day is 10 days; raising the resource
   * to 4 h/day must give 5 days.
   */
  fun testSpecificationExampleThroughTheModel() {
    val task = taskManager.createTask()
    val resource = resourceManager.getById(1)
    setEffort(task, 20.0)
    setHoursPerDay(resource, 2.0)
    task.assignmentCollection.addAssignment(resource).load = 100f

    assertEquals(10, computeDurationDays(
      task.effortHours(taskProperties)!!, task.availableHoursPerDay(resourceProperties)))

    setHoursPerDay(resource, 4.0)
    assertEquals(5, computeDurationDays(
      task.effortHours(taskProperties)!!, task.availableHoursPerDay(resourceProperties)))
  }
}
