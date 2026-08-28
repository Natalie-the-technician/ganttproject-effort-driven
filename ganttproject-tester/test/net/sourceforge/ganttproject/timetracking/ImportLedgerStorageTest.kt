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
package net.sourceforge.ganttproject.timetracking

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
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.task.TaskManagerConfig
import java.awt.Color
import java.net.URL

/**
 * Tests the STORAGE of the import ledger against a real task model: one custom property per task,
 * assembled into the project-wide view.
 *
 * The arithmetic itself is covered by ImportLedgerTest; here it is about reading the right value
 * off the right tasks.
 */
class ImportLedgerStorageTest : TestCase() {
  private lateinit var taskManager: TaskManager
  private lateinit var taskProperties: CustomColumnsManager

  override fun setUp() {
    super.setUp()
    taskProperties = CustomColumnsManager()
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

  private fun record(task: Task, ledger: Map<Long, Double>) {
    val def = TimeTrackingProperties.findOrCreateImportedEntries(taskProperties)
    task.customValues.setValue(def, encodeImportLedger(ledger))
  }

  fun testTaskWithoutRecordHasNothing() {
    assertTrue(taskManager.createTask().importedEntries(taskProperties).isEmpty())
  }

  fun testRecordIsReadBack() {
    val task = taskManager.createTask()
    record(task, mapOf(1234L to 1.5))
    assertEquals(mapOf(1234L to 1.5), task.importedEntries(taskProperties))
  }

  /** Nothing recorded anywhere: the guard must not invent entries. */
  fun testProjectLedgerIsEmptyWithoutRecords() {
    taskManager.createTask()
    taskManager.createTask()
    assertTrue(projectImportLedger(taskManager.tasks.toList(), taskProperties).isEmpty())
  }

  /**
   * THE reason for reading every task instead of only the one an entry is about to land on: an
   * entry may have been assigned to a different task in an earlier run.
   */
  fun testProjectLedgerSeesEntriesOnOtherTasks() {
    val first = taskManager.createTask()
    val second = taskManager.createTask()
    record(first, mapOf(1L to 2.0))
    record(second, mapOf(2L to 3.0))

    val ledger = projectImportLedger(taskManager.tasks.toList(), taskProperties)
    assertEquals(mapOf(1L to 2.0, 2L to 3.0), ledger)
  }

  /** One entry split over two tasks counts with its total, otherwise the rest gets imported again. */
  fun testSplitEntryIsCountedWithItsTotal() {
    val first = taskManager.createTask()
    val second = taskManager.createTask()
    record(first, mapOf(42L to 1.0))
    record(second, mapOf(42L to 2.0))

    assertEquals(mapOf(42L to 3.0), projectImportLedger(taskManager.tasks.toList(), taskProperties))
  }

  /**
   * The column is visible and editable in stock GanttProject. Someone typing into it must not
   * take the guard down for the other tasks.
   */
  fun testGarbageOnOneTaskDoesNotHideTheOthers() {
    val first = taskManager.createTask()
    val second = taskManager.createTask()
    val def = TimeTrackingProperties.findOrCreateImportedEntries(taskProperties)
    first.customValues.setValue(def, "von Hand hineingeschrieben")
    record(second, mapOf(7L to 4.0))

    assertEquals(mapOf(7L to 4.0), projectImportLedger(taskManager.tasks.toList(), taskProperties))
  }

  /**
   * A column the USER created carries the typed text as its NAME; its id is generated (`tpc0`).
   * Looking only at the id would silently return "nothing imported yet" and let every run count
   * the same hours again — the same trap that hit `hours_per_day` in session 3.
   */
  fun testFoundWhenTheUserCreatedTheColumnByName() {
    val def = taskProperties.createDefinition(
      CustomPropertyClass.TEXT, TimeTrackingProperties.TASK_IMPORTED_ENTRIES, null)
    assertFalse("test would prove nothing if the id happened to match the name",
      def.id == TimeTrackingProperties.TASK_IMPORTED_ENTRIES)

    val task = taskManager.createTask()
    task.customValues.setValue(def, "5:2.5")

    assertEquals(mapOf(5L to 2.5), task.importedEntries(taskProperties))
    assertEquals(mapOf(5L to 2.5), projectImportLedger(listOf(task), taskProperties))
  }
}
