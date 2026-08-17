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
import biz.ganttproject.customproperty.CustomPropertyClass
import biz.ganttproject.ganttview.TaskTableModel
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
 * Tests that a change to the resources actually reaches the duration — the step that stock
 * GanttProject is missing entirely. Everything here goes through real events; nothing calls the
 * algorithm directly.
 *
 * Unlike the other test classes, this one wires the task manager to a REAL resource manager
 * (`getResourceManager()` returns it), because that is what the trigger needs in order to read the
 * daily hours.
 */
class EffortDrivenTriggerTest : TestCase() {
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
      // Must be qualified: an unqualified `resourceManager` would resolve to this object's own
      // synthetic property, i.e. to this very getter, and recurse until the stack overflows.
      override fun getResourceManager(): HumanResourceManager =
        this@EffortDrivenTriggerTest.resourceManager
      override fun getProjectDocumentURL(): URL? = null
      override fun getNotificationManager(): NotificationManager? = null
      override fun getSchedulerDisabledOption(): BooleanOption =
        DefaultBooleanOption("scheduler.disabled", false)
    })
    // This is the registration that GanttProjectImpl does for a real project.
    resourceManager.addView(EffortDrivenTrigger(taskManager))
  }

  /**
   * The daily hours as the USER creates them: through the column manager, which generates the id
   * (`tpc0`) and keeps the typed text as the NAME only. Looking the property up by id alone made
   * the feature silently fall back to 8 h/day — found by testing the running application.
   */
  private fun setHoursPerDayAsTheUserWould(resource: HumanResource, hours: Double) {
    val def = resourceProperties.definitions.firstOrNull { it.name == "hours_per_day" }
      ?: resourceProperties.createDefinition(CustomPropertyClass.DOUBLE, "hours_per_day", null)
    resource.setValue(def, hours)
  }

  fun testHoursPerDayIsFoundWhenTheColumnWasCreatedByName() {
    val task = taskManager.createTask()
    val resource = resourceManager.getById(1)
    setEffort(task, 20.0)
    task.assignmentCollection.addAssignment(resource).load = 100f

    setHoursPerDayAsTheUserWould(resource, 2.0)
    assertEquals(10, durationDays(task))

    setHoursPerDayAsTheUserWould(resource, 4.0)
    assertEquals(5, durationDays(task))
  }

  /**
   * Entering the effort must be enough on its own, without touching any resource afterwards.
   *
   * This mirrors what `GanttDialogProperties` does when OK is pressed: commit the mutator, THEN
   * run the algorithm. Doing it from a task listener does not work — the listener fires while the
   * task's own mutator is still committing, and `MutatorReentered.commit()` discards everything
   * written at that moment. Found by testing the running application: the duration only moved
   * once a resource was edited by chance.
   */
  fun testEnteringTheEffortAloneRecalculatesTheDuration() {
    val task = taskManager.createTask()
    val resource = resourceManager.getById(1)
    setHoursPerDayAsTheUserWould(resource, 2.0)
    task.assignmentCollection.addAssignment(resource).load = 100f
    val before = durationDays(task)

    val def = EffortDrivenProperties.findOrCreateTaskEffort(taskManager.customPropertyManager)
    val edited = task.customValues.copyOf().also { it.setValue(def, 20.0) }
    task.createMutator().also { it.setCustomProperties(edited) }.commit()
    taskManager.algorithmCollection.effortDrivenDurationAlgorithm.run()

    assertTrue("duration must change without touching any resource", before != durationDays(task))
    assertEquals(10, durationDays(task))
  }

  private fun setEffort(task: Task, hours: Double) {
    val def = EffortDrivenProperties.findOrCreateTaskEffort(taskManager.customPropertyManager)
    task.customValues.setValue(def, hours)
  }

  /** Editing the daily hours of a resource — this fires resourceChanged. */
  private fun setHoursPerDay(resource: HumanResource, hours: Double) {
    val def = EffortDrivenProperties.findOrCreateResourceHours(resourceProperties)
    resource.setValue(def, hours)
  }

  private fun durationDays(task: Task): Int = task.duration.length

  /**
   * The specification example, driven entirely by events: 20 h of effort, resource at 2 h/day
   * gives 10 days; setting the resource to 4 h/day must bring it down to 5 days without anyone
   * touching the duration.
   *
   * Note that this goes through resourceChanged, NOT through an assignment event: editing the
   * daily hours of a resource is not an assignment change.
   */
  fun testChangingDailyHoursChangesTheDuration() {
    val task = taskManager.createTask()
    val resource = resourceManager.getById(1)
    setEffort(task, 20.0)
    task.assignmentCollection.addAssignment(resource).load = 100f

    setHoursPerDay(resource, 2.0)
    assertEquals(10, durationDays(task))

    setHoursPerDay(resource, 4.0)
    assertEquals(5, durationDays(task))
  }

  /** A task without effort must stay untouched no matter what happens to the resources. */
  fun testTaskWithoutEffortIsNotAffected() {
    val task = taskManager.createTask()
    val resource = resourceManager.getById(1)
    task.assignmentCollection.addAssignment(resource).load = 100f
    val before = durationDays(task)

    setHoursPerDay(resource, 2.0)
    assertEquals(before, durationDays(task))
  }

  /**
   * Losing one of two assigned resources halves the availability, so the task needs twice as long.
   * This arrives as an assignment event rather than a resource change.
   */
  fun testRemovingAnAssignmentStretchesTheTask() {
    val task = taskManager.createTask()
    val r1 = resourceManager.getById(1)
    val r2 = resourceManager.getById(2)
    setEffort(task, 40.0)
    setHoursPerDay(r1, 2.0)
    setHoursPerDay(r2, 2.0)
    task.assignmentCollection.addAssignment(r1).load = 100f
    task.assignmentCollection.addAssignment(r2).load = 100f
    // 40 h at 4 h/day
    assertEquals(10, durationDays(task))

    task.assignmentCollection.getAssignment(r2).delete()
    r2.delete()
    // 40 h at 2 h/day
    assertEquals(20, durationDays(task))
  }

  /**
   * The effort typed straight into a table column instead of the dialog.
   *
   * `TaskTableModel.setValue` commits its own mutator and then runs the algorithm — the same order
   * the dialog uses, and for the same reason: while a mutator is running, `createMutator()` hands
   * out a re-entered one whose `commit()` does nothing, so a duration written then is lost.
   */
  fun testEffortTypedIntoTheTableColumnRecalculatesTheDuration() {
    val task = taskManager.createTask()
    val resource = resourceManager.getById(1)
    setHoursPerDayAsTheUserWould(resource, 2.0)
    task.assignmentCollection.addAssignment(resource).load = 100f
    val before = durationDays(task)

    val model = TaskTableModel(taskManager.customPropertyManager)
    val def = EffortDrivenProperties.findOrCreateTaskEffort(taskManager.customPropertyManager)
    model.setValue(20.0, task, def)

    assertTrue("typing the effort into the table must recalculate too",
      before != durationDays(task))
    assertEquals(10, durationDays(task))
  }
}
