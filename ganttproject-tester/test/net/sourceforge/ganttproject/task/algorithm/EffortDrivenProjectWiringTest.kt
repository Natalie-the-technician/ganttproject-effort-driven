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

import junit.framework.TestCase
import net.sourceforge.ganttproject.GanttProjectImpl

/**
 * Checks that a real project actually HAS the effort-driven trigger installed.
 *
 * EffortDrivenTriggerTest registers the trigger itself, so it proves that the trigger works — not
 * that anybody ever switches it on. This test builds a plain GanttProjectImpl, touches nothing but
 * the project's own managers, and expects the duration to follow. If the registration in
 * GanttProjectImpl is ever dropped, the feature silently stops working in the running application
 * while every other test stays green; this is the test that catches that.
 */
class EffortDrivenProjectWiringTest : TestCase() {

  /** The specification example, driven through a whole project object. */
  fun testProjectRecalculatesDurationOnResourceChange() {
    val project = GanttProjectImpl()
    val resource = project.humanResourceManager.create("resource#1", 1)
    val task = project.taskManager.createTask()

    val effortDef = EffortDrivenProperties.findOrCreateTaskEffort(project.taskCustomColumnManager)
    task.customValues.setValue(effortDef, 20.0)
    task.assignmentCollection.addAssignment(resource).load = 100f

    val hoursDef =
      EffortDrivenProperties.findOrCreateResourceHours(project.resourceCustomPropertyManager)
    // Editing the resource is the only thing this test does. Everything else has to follow by
    // itself, through the trigger that GanttProjectImpl is supposed to have registered.
    resource.setValue(hoursDef, 2.0)
    assertEquals(10, task.duration.length)

    resource.setValue(hoursDef, 4.0)
    assertEquals(5, task.duration.length)
  }
}
