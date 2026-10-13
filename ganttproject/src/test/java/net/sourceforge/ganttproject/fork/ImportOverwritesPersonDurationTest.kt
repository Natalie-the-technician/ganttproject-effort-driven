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

Does the silent overwrite of a target-project resource really move the target
project's own plan? Measured here, not believed: 40 hours of effort at 8 h/day
are five days; after the import the same task is seven days long, because the
record of `Zielperson` now carries the 6 h/day of `Zukauf-Person`.

The overwrite itself is upstream's; the duration shift needs this fork, because
`hours_per_day` is a fork property and stock GanttProject derives no duration
from a resource at all.
*/
package net.sourceforge.ganttproject.fork

import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.customproperty.CustomColumnsManager
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.resource.HumanResourceMerger
import net.sourceforge.ganttproject.resource.OverwritingMerger
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenTrigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.util.Locale

class ImportOverwritesPersonDurationTest {

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

  private fun setHoursPerDay(manager: CustomPropertyManager, resource: HumanResource, hours: Double) {
    resource.setValue(EffortDrivenProperties.findOrCreateResourceHours(manager), hours)
  }

  @Test
  fun `der import verschiebt den eigenen plan, weil er die person ueberschreibt`() {
    // The target project: Zielperson, 8 h/day, ZIEL Teil A with 40 hours of effort.
    val builder = TestSetupHelper.newTaskManagerBuilder()
    val taskManager = builder.build()
    val targetResources = builder.resourceManager
    targetResources.addView(EffortDrivenTrigger(taskManager))

    val zielperson = targetResources.create("Zielperson", 0)
    setHoursPerDay(targetResources.customPropertyManager, zielperson, 8.0)

    val teilA = taskManager.newTaskBuilder().withName("ZIEL Teil A").build()
    teilA.assignmentCollection.addAssignment(zielperson).load = 100f
    val effort = EffortDrivenProperties.findOrCreateTaskEffort(taskManager.customPropertyManager)
    teilA.customValues.setValue(effort, 40.0)
    taskManager.algorithmCollection.effortDrivenDurationAlgorithm.run()
    assertEquals(5, teilA.duration.length, "Aufbau: 40 Std. bei 8 Std./Tag sind fuenf Tage")

    // The imported file: Zukauf-Person, 6 h/day, resource #0 as well -- as in every file
    // GanttProject writes.
    val importedResources = HumanResourceManager(null, CustomColumnsManager())
    val zukaufPerson = importedResources.create("Zukauf-Person", 0)
    setHoursPerDay(importedResources.customPropertyManager, zukaufPerson, 6.0)

    // Exactly the call chain of GanttProjectImpl.importProject.
    val that2this = targetResources.customPropertyManager.importData(importedResources.customPropertyManager)
    targetResources.importData(
      importedResources, OverwritingMerger(HumanResourceMerger.MergeResourcesOption()), that2this)
    taskManager.algorithmCollection.effortDrivenDurationAlgorithm.run()

    assertEquals(5, teilA.duration.length,
      "der Import hat den eigenen Plan verschoben: 40 Std. mit 6 statt 8 Std./Tag sind sieben Tage")
    assertEquals("Zielperson", teilA.assignments[0].resource.name,
      "ZIEL Teil A haengt nach dem Import an einer anderen Person")
  }
}
