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
import net.sourceforge.ganttproject.task.TaskContainmentHierarchyFacade
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.task.TaskManagerConfig
import java.awt.Color
import java.net.URL

/**
 * Tests the [EffortDrivenDurationAlgorithm] against a real task model: it must actually rewrite
 * the duration of a leaf task, and it must leave everything else alone (tasks without effort,
 * tasks without assignments, and container tasks).
 *
 * Setup mirrors [EffortDrivenModelTest], which is the established pattern in this project.
 */
class EffortDrivenAlgorithmTest : TestCase() {
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

  /** The algorithm under test, wired to this test's hierarchy and property managers. */
  private fun newAlgorithm(
    resourceProps: CustomColumnsManager? = resourceProperties): EffortDrivenDurationAlgorithm =
    object : EffortDrivenDurationAlgorithm(taskManager, taskProperties, { resourceProps }) {
      override fun createContainmentFacade(): TaskContainmentHierarchyFacade =
        taskManager.taskHierarchy
    }

  private fun setEffort(task: Task, hours: Double) {
    val def = EffortDrivenProperties.findOrCreateTaskEffort(taskProperties)
    task.customValues.setValue(def, hours)
  }

  private fun setHoursPerDay(resource: HumanResource, hours: Double) {
    val def = EffortDrivenProperties.findOrCreateResourceHours(resourceProperties)
    resource.setValue(def, hours)
  }

  private fun setDurationDays(task: Task, days: Int) {
    val mutator = task.createMutator()
    mutator.setDuration(taskManager.createLength(days.toLong()))
    mutator.commit()
  }

  private fun durationDays(task: Task): Int = task.duration.length

  // --- the core behaviour: a leaf duration is derived from effort ---

  /**
   * The specification example, driven through the algorithm and written back into the model:
   * 20 h at 2 h/day is 10 days; raising the resource to 4 h/day and re-running gives 5 days.
   */
  fun testLeafDurationIsDerivedFromEffort() {
    val task = taskManager.createTask()
    val resource = resourceManager.getById(1)
    setEffort(task, 20.0)
    setHoursPerDay(resource, 2.0)
    task.assignmentCollection.addAssignment(resource).load = 100f

    newAlgorithm().run()
    assertEquals(10, durationDays(task))

    setHoursPerDay(resource, 4.0)
    newAlgorithm().run()
    assertEquals(5, durationDays(task))
  }

  /** A task with no effort keeps whatever duration it has: the feature is opt-in per task. */
  fun testTaskWithoutEffortIsUntouched() {
    val task = taskManager.createTask()
    val resource = resourceManager.getById(1)
    setHoursPerDay(resource, 2.0)
    task.assignmentCollection.addAssignment(resource).load = 100f
    setDurationDays(task, 7)

    newAlgorithm().run()
    assertEquals(7, durationDays(task))
  }

  /** Effort but nothing assigned means no availability to spread it over: leave the duration. */
  fun testTaskWithoutAssignmentIsUntouched() {
    val task = taskManager.createTask()
    setEffort(task, 20.0)
    setDurationDays(task, 7)

    newAlgorithm().run()
    assertEquals(7, durationDays(task))
  }

  /**
   * Container durations are derived by GanttProject from the children, never from the container's
   * own effort, so the algorithm must not even try to write one.
   *
   * This is asserted on the *diagnostic*, not on the duration. Verified by counter-test: when the
   * leaf check is removed, the model still discards the duration written onto the container, so an
   * assertion on the duration passes either way and proves nothing. The list of touched tasks does
   * change, and that is what this test looks at.
   */
  fun testContainerIsNotTouched() {
    val parent = taskManager.createTask()
    val child = taskManager.createTask()
    taskManager.taskHierarchy.move(child, parent)

    val resource = resourceManager.getById(1)
    setEffort(parent, 80.0)
    setHoursPerDay(resource, 2.0)
    parent.assignmentCollection.addAssignment(resource).load = 100f

    val touched = runRecording()
    assertFalse("the container must not be touched", touched.contains(parent))
  }

  /** The counterpart: a leaf with effort *is* reported as touched, so the recording works. */
  fun testLeafIsReportedAsTouched() {
    val task = taskManager.createTask()
    val resource = resourceManager.getById(1)
    setEffort(task, 20.0)
    setHoursPerDay(resource, 2.0)
    task.assignmentCollection.addAssignment(resource).load = 100f

    val touched = runRecording()
    assertTrue("the leaf must be touched", touched.contains(task))
  }

  /**
   * A project can have no resource manager at all — TaskManagerConfig.getResourceManager() returns
   * null in tests and in headless imports. There are then no daily hours to read, so the algorithm
   * must do nothing rather than fail.
   */
  fun testWithoutResourcePropertiesNothingHappens() {
    val task = taskManager.createTask()
    val resource = resourceManager.getById(1)
    setEffort(task, 20.0)
    setHoursPerDay(resource, 2.0)
    task.assignmentCollection.addAssignment(resource).load = 100f
    setDurationDays(task, 7)

    newAlgorithm(resourceProps = null).run()
    assertEquals(7, durationDays(task))
  }

  /** Runs the algorithm and returns the tasks it reported as modified. */
  private fun runRecording(): List<Task> {
    val touched = mutableListOf<Task>()
    val algorithm = newAlgorithm()
    algorithm.setDiagnostic(object : AlgorithmBase.Diagnostic {
      override fun addModifiedTask(t: Task, newStart: java.util.Date?, newEnd: java.util.Date?) {
        touched.add(t)
      }
      override fun logError(ex: Exception) = throw ex
    })
    algorithm.run()
    return touched
  }
}
