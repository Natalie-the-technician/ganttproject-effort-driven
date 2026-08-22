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

import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenTrigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.util.Locale

/**
 * Does the duration change when the daily rate changes?
 *
 * WHAT FOR: that is the real question put to the rebuild. The number of hours per day rises as
 * soon as the main job is reduced -- and then the plan should recompute itself without 162
 * durations being touched by hand. The trigger ([EffortDrivenTrigger]) had been built and wired
 * up, but was not backed by any test. "Wired up" and "takes effect" are not the same thing --
 * this session has already found three counter-examples for that (dead menu items, a settings
 * page without saving, a lock without If-Match).
 */
class HoursPerDayChangeTest {

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

  private fun setHoursPerDay(
    manager: CustomPropertyManager, resource: net.sourceforge.ganttproject.resource.HumanResource,
    hours: Double) {
    val definition = EffortDrivenProperties.findOrCreateResourceHours(manager)
    resource.setValue(definition, hours)
  }

  @Test
  fun `mehr stunden pro tag verkuerzen die dauer`() {
    // The resource manager MUST be the TaskManager's own: the algorithm fetches the properties
    // through `config.getResourceManager()`. With a manager of its own the calculation fell back
    // to the default of 8 h/day -- the test was then green without checking anything. That is
    // exactly what happened on the first run (5 instead of 10 days in the setup).
    val builder = TestSetupHelper.newTaskManagerBuilder()
    val taskManager = builder.build()
    val resourceManager = builder.resourceManager
    // Exactly the wiring from GanttProjectImpl:109.
    resourceManager.addView(EffortDrivenTrigger(taskManager))

    val resource = resourceManager.create("Natalie", 0)
    setHoursPerDay(resourceManager.customPropertyManager, resource, 4.0)

    val task = taskManager.newTaskBuilder().withName("Firmware").build()
    task.assignmentCollection.addAssignment(resource).load = 100f
    // 40 hours of effort at 4 h/day: ten days.
    val effortDefinition = EffortDrivenProperties.findOrCreateTaskEffort(taskManager.customPropertyManager)
    task.customValues.setValue(effortDefinition, 40.0)
    taskManager.algorithmCollection.effortDrivenDurationAlgorithm.run()
    assertEquals(10, task.duration.length, "Aufbau: 40 Std. bei 4 Std./Tag")

    // Main job reduced: eight hours a day. NOBODY touches the duration.
    setHoursPerDay(resourceManager.customPropertyManager, resource, 8.0)

    assertEquals(5, task.duration.length,
      "40 Std. bei 8 Std./Tag sind fuenf Tage -- die Aenderung der Tagesleistung muss die Dauer " +
        "von selbst nachziehen")
    assertEquals(40.0, task.customValues.getValue(effortDefinition),
      "der Aufwand selbst darf sich dabei NICHT aendern: die Arbeit bleibt dieselbe")
  }
}
