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
package biz.ganttproject.storage

import biz.ganttproject.core.time.CalendarFactory
import net.sourceforge.ganttproject.GanttProjectImpl
import net.sourceforge.ganttproject.io.GanttXMLSaver
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Locale

/**
 * Checks what actually lands in the `.gan` file.
 *
 * This is the evidence for the design decision taken in session 2: effort and recorded hours are
 * stored as CUSTOM PROPERTIES, because stock GanttProject writes those back as a regular feature,
 * while a new XML attribute would be dropped by its saver without a word. Until now that claim
 * rested on reading `TaskSaver.kt`. Here it is exercised.
 *
 * Deliberately NOT a screen test: everything asserted below is visible in a file, so it does not
 * need a running application.
 */
class EffortFileRoundTripTest {

  init {
    // GanttProjectImpl builds a WeekendCalendarImpl, which needs this. Same pattern as
    // TestBottomUnitSceneBuilder; without it every test here dies with a NullPointerException
    // in CalendarFactory.
    object : CalendarFactory() {
      init {
        setLocaleApi(object : CalendarFactory.LocaleApi {
          override fun getLocale(): Locale = Locale.US
          override fun getShortDateFormat(): DateFormat =
            DateFormat.getDateInstance(DateFormat.SHORT, Locale.US)
        })
      }
    }
  }

  private fun saveToXml(project: GanttProjectImpl): String {
    val out = ByteArrayOutputStream()
    GanttXMLSaver(project).save(out)
    return out.toString(Charsets.UTF_8)
  }

  /**
   * Planned effort and recorded hours both reach the file, as a definition plus a value per task.
   */
  @Test
  fun `effort and recorded hours are written into the project file`() {
    val project = GanttProjectImpl()
    val definitions = project.taskCustomColumnManager
    val task = project.taskManager.newTaskBuilder().withName("Testvorgang").build()

    task.customValues.setValue(EffortDrivenProperties.findOrCreateTaskEffort(definitions), 20.0)
    task.customValues.setValue(
      EffortDrivenProperties.findOrCreateTaskActualEffort(definitions), 12.5)

    val xml = saveToXml(project)

    // The definitions travel as <taskproperty>, the values as <customproperty>.
    assertTrue(xml.contains(EffortDrivenProperties.TASK_EFFORT_HOURS),
      "the planned effort definition is missing from the file")
    assertTrue(xml.contains(EffortDrivenProperties.TASK_EFFORT_ACTUAL_HOURS),
      "the recorded hours definition is missing from the file")
    assertTrue(xml.contains("20.0"), "the planned effort value is missing from the file")
    assertTrue(xml.contains("12.5"),
      "the recorded hours value is missing, or lost its decimals on the way")
  }

  /**
   * The recorded hours must never turn into a schedule. If they ever leaked into the duration,
   * the saved file would show it — a task with 12.5 recorded hours and no planned effort must
   * keep the duration it had.
   */
  @Test
  fun `recording hours does not change what is written as duration`() {
    val project = GanttProjectImpl()
    val definitions = project.taskCustomColumnManager
    val task = project.taskManager.newTaskBuilder().withName("Testvorgang").build()

    // The task MUST have a resource, otherwise the algorithm stops at "nothing assigned" and this
    // test would pass no matter what the algorithm reads. Verified by counter-test: without the
    // assignment, letting the recorded hours drive the duration went unnoticed.
    val resource = project.humanResourceManager.create("Test", 1)
    task.assignmentCollection.addAssignment(resource).load = 100f

    val durationBefore = task.duration.length
    task.customValues.setValue(
      EffortDrivenProperties.findOrCreateTaskActualEffort(definitions), 24.0)
    project.taskManager.algorithmCollection.effortDrivenDurationAlgorithm.run()

    // 24 recorded hours at the default of 8 h/day would be 3 days — clearly different from the
    // single day the task starts with, so a leak cannot hide behind an accidentally equal value.
    assertTrue(durationBefore != 3,
      "test setup is useless: the task already has the duration a leak would produce")
    val xml = saveToXml(project)
    assertTrue(xml.contains("duration=\"$durationBefore\""),
      "recorded hours changed the planned duration; file was:\n$xml")
  }

  /**
   * A project that uses none of this must not gain the columns. Opening the task dialog is not
   * part of this test, so nothing may create a definition on its own.
   */
  @Test
  fun `an untouched project carries no effort columns`() {
    val project = GanttProjectImpl()
    project.taskManager.newTaskBuilder().withName("Testvorgang").build()

    val xml = saveToXml(project)
    assertFalse(xml.contains(EffortDrivenProperties.TASK_EFFORT_HOURS),
      "an untouched project must not gain the effort column")
    assertFalse(xml.contains(EffortDrivenProperties.TASK_EFFORT_ACTUAL_HOURS),
      "an untouched project must not gain the recorded hours column")
  }
}
