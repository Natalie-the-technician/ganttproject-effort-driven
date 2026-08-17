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

import biz.ganttproject.customproperty.CustomPropertyEvent
import biz.ganttproject.customproperty.CustomPropertyListener
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.storage.ProjectDatabase
import net.sourceforge.ganttproject.storage.SQL_PROJECT_DATABASE_OPTIONS
import net.sourceforge.ganttproject.storage.SqlProjectDatabaseImpl
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import net.sourceforge.ganttproject.task.algorithm.actualEffortHours
import net.sourceforge.ganttproject.timetracking.TogglTimeEntry
import net.sourceforge.ganttproject.timetracking.applyImportAsSingleEdit
import net.sourceforge.ganttproject.undo.GPUndoListener
import net.sourceforge.ganttproject.undo.GPUndoManager
import net.sourceforge.ganttproject.undo.UndoableEditTxnFactory
import net.sourceforge.ganttproject.timetracking.applyTaskImport
import net.sourceforge.ganttproject.timetracking.importedEntries
import net.sourceforge.ganttproject.timetracking.planTaskImport
import net.sourceforge.ganttproject.timetracking.projectImportLedger
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import java.time.OffsetDateTime
import javax.sql.DataSource

/**
 * The step that actually changes real tasks. Tested against a real task model AND a real H2
 * mirror, because two of the traps this project ran into only show up there: a custom property
 * without its mirror column, and a database error that `MutatorImpl.commit()` swallows.
 *
 * No network anywhere: the Toggl entries are built by hand.
 */
class ImportApplyTest {
  private lateinit var dataSource: DataSource
  private lateinit var projectDatabase: ProjectDatabase
  private lateinit var taskManager: TaskManager
  private lateinit var properties: CustomPropertyManager
  private lateinit var listener: CustomPropertyListener

  @BeforeEach
  fun init(testInfo: TestInfo) {
    // Own database per test: a shared in-memory H2 survives between tests and would leave columns
    // behind that make the next test pass for the wrong reason.
    dataSource = JdbcDataSource().also {
      it.setURL("jdbc:h2:mem:apply${testInfo.displayName.hashCode()}$SQL_PROJECT_DATABASE_OPTIONS")
    }
    projectDatabase = SqlProjectDatabaseImpl(dataSource)
    projectDatabase.init()
    taskManager = TestSetupHelper.newTaskManagerBuilder().also {
      it.setTaskUpdateBuilderFactory { task -> projectDatabase.createTaskUpdateBuilder(task) }
    }.build()
    properties = taskManager.customPropertyManager
    // The running application registers this in GanttProjectBase. Kept as a field so that one
    // test can remove it and check that the import creates its columns on its own.
    listener = object : CustomPropertyListener {
      override fun customPropertyChange(event: CustomPropertyEvent) {
        projectDatabase.onCustomColumnChange(properties)
      }
    }
    properties.addListener(listener)
  }

  @AfterEach
  fun clear() {
    dataSource.connection.use { conn -> conn.createStatement().execute("shutdown") }
  }

  private fun entry(id: Long, hours: Double) = TogglTimeEntry(
    id = id,
    start = OffsetDateTime.parse("2026-08-12T09:00:00+02:00"),
    durationSeconds = (hours * 3600).toLong(),
    description = "egal",
    projectId = null,
    projectName = null,
    tags = emptyList())

  private fun newTask(name: String): Task =
    taskManager.newTaskBuilder().withName(name).build().also { projectDatabase.insertTask(it) }

  private fun ledgerOfProject() = projectImportLedger(taskManager.tasks.toList(), properties)

  // --- planning changes nothing ---

  @Test
  fun `planning does not touch the tasks`() {
    val task = newTask("A")
    planTaskImport(listOf(entry(1L, 2.0) to task), emptyMap(), properties)

    assertEquals(null, task.actualEffortHours(properties),
      "planning must be a preview; nothing may be written yet")
  }

  @Test
  fun `planning reports what would be added`() {
    val task = newTask("A")
    val plan = planTaskImport(
      listOf(entry(1L, 2.0) to task, entry(2L, 1.5) to task), emptyMap(), properties)

    assertEquals(1, plan.size)
    assertEquals(3.5, plan[0].addedHours, 0.001)
    assertEquals(3.5, plan[0].newActualHours, 0.001)
  }

  // --- writing ---

  @Test
  fun `applying writes the recorded hours and the ledger`() {
    val task = newTask("A")
    val plan = planTaskImport(listOf(entry(1L, 2.0) to task), emptyMap(), properties)

    val result = applyTaskImport(plan, properties, projectDatabase)

    assertTrue(result.isComplete, "the write was reported as incomplete")
    assertEquals(2.0, task.actualEffortHours(properties)!!, 0.001)
    assertEquals(mapOf(1L to 2.0), task.importedEntries(properties))
  }

  /**
   * The point of the whole ledger. A second run with the same data must add nothing — and must
   * not write either, so an unchanged import produces no undo step full of no-ops.
   */
  @Test
  fun `a second import of the same entries adds nothing`() {
    val task = newTask("A")
    val entries = listOf(entry(1L, 2.0), entry(2L, 1.5))

    applyTaskImport(
      planTaskImport(entries.map { it to task }, ledgerOfProject(), properties),
      properties, projectDatabase)
    val afterFirst = task.actualEffortHours(properties)!!

    val secondPlan = planTaskImport(entries.map { it to task }, ledgerOfProject(), properties)
    assertTrue(secondPlan.all { it.isEmpty }, "the second run still wanted to write something")
    applyTaskImport(secondPlan, properties, projectDatabase)

    assertEquals(afterFirst, task.actualEffortHours(properties)!!, 0.001)
    assertEquals(3.5, afterFirst, 0.001)
  }

  /**
   * An entry whose duration grew in Toggl adds only the difference — not its whole length again.
   */
  @Test
  fun `a changed entry adds only the difference`() {
    val task = newTask("A")
    applyTaskImport(
      planTaskImport(listOf(entry(1L, 2.0) to task), ledgerOfProject(), properties),
      properties, projectDatabase)

    applyTaskImport(
      planTaskImport(listOf(entry(1L, 3.5) to task), ledgerOfProject(), properties),
      properties, projectDatabase)

    assertEquals(3.5, task.actualEffortHours(properties)!!, 0.001)
    assertEquals(mapOf(1L to 3.5), task.importedEntries(properties))
  }

  /**
   * The entry moved to another task in this run. The guard is project-wide, so the hours must not
   * be counted a second time on the new task.
   */
  @Test
  fun `an entry reassigned to another task is not counted twice`() {
    val first = newTask("A")
    val second = newTask("B")
    applyTaskImport(
      planTaskImport(listOf(entry(1L, 2.0) to first), ledgerOfProject(), properties),
      properties, projectDatabase)

    val plan = planTaskImport(listOf(entry(1L, 2.0) to second), ledgerOfProject(), properties)
    applyTaskImport(plan, properties, projectDatabase)

    assertEquals(null, second.actualEffortHours(properties),
      "the same hours were recorded again on the other task")
  }

  /**
   * [Fork-Aenderung] Derselbe Eintrag auf zwei Vorgängen — die Aufteilung, die es noch nicht gibt.
   *
   * Ohne Vorbedingung bekäme JEDER der beiden Vorgänge die vollen Stunden: `hoursDelta()` liefert
   * für einen neuen Eintrag `entry.hours`, und `ledgerAfterImport` schreibt ebenfalls die vollen
   * Stunden in die Buchführung beider Vorgänge. Aus 4 Stunden würden 8 — in den Vorgängen und in
   * der Buchführung, ohne jede Meldung.
   *
   * Bis Schritt 6 die Aufteilung mit Anteilen baut, muss das laut scheitern.
   */
  @Test
  fun `the same entry on two tasks is refused instead of counted twice`() {
    val first = newTask("A")
    val second = newTask("B")
    val shared = entry(1L, 4.0)

    val failure = assertThrows(IllegalArgumentException::class.java) {
      planTaskImport(listOf(shared to first, shared to second), emptyMap(), properties)
    }
    assertTrue(failure.message!!.contains("1"),
      "the message must name the entry, otherwise nobody can find it: ${failure.message}")

    // Und nichts darf dabei geschrieben worden sein.
    assertEquals(null, first.actualEffortHours(properties))
    assertEquals(null, second.actualEffortHours(properties))
  }

  /** Zweimal derselbe Eintrag auf DEMSELBEN Vorgang zählt genauso doppelt. */
  @Test
  fun `the same entry twice on one task is refused`() {
    val task = newTask("A")
    val shared = entry(7L, 3.0)

    assertThrows(IllegalArgumentException::class.java) {
      planTaskImport(listOf(shared to task, shared to task), emptyMap(), properties)
    }
  }

  /**
   * The import runs from its own menu item, so it cannot rely on anyone else creating the mirror
   * columns. Here the listener is removed, which leaves the explicit sync inside [applyTaskImport]
   * as the only thing that can create them.
   *
   * Without that sync the write fails with `Column "..." not found`, and the failure is invisible
   * because `MutatorImpl.commit()` only logs it.
   *
   * This test therefore queries H2 DIRECTLY. Verified by counter-test: asserting through
   * `task.actualEffortHours(...)` passes even with the sync deleted, because that reads the task
   * in memory — which the mutator updated regardless of what the database did.
   */
  @Test
  fun `the write creates its own mirror columns`() {
    val task = newTask("A")
    properties.removeListener(listener)

    val plan = planTaskImport(listOf(entry(1L, 2.0) to task), emptyMap(), properties)
    applyTaskImport(plan, properties, projectDatabase)

    assertEquals(2.0, readActualHoursFromDatabase(),
      "the value never reached the mirror table")
  }

  /** Reads the recorded hours out of H2. Throws when the column does not exist. */
  private fun readActualHoursFromDatabase(): Double? =
    dataSource.connection.use { conn ->
      conn.createStatement().use { stmt ->
        stmt.executeQuery(
          "SELECT ${EffortDrivenProperties.TASK_EFFORT_ACTUAL_HOURS} FROM Task").use { rs ->
          if (rs.next()) (rs.getObject(1) as? Number)?.toDouble() else null
        }
      }
    }

  // --- one import, one undo step ---

  /** Counts how many undoable edits were opened, and runs them. */
  private class CountingUndoManager : GPUndoManager {
    var edits = 0
    val names = mutableListOf<String>()
    override fun undoableEdit(localizedName: String, runnableEdit: Runnable) {
      edits++
      names.add(localizedName)
      runnableEdit.run()
    }
    override fun canUndo() = false
    override fun canRedo() = false
    override fun undo() {}
    override fun redo() {}
    override val undoPresentationName = ""
    override val redoPresentationName = ""
    override fun addUndoableEditListener(listener: GPUndoListener) {}
    override fun removeUndoableEditListener(listener: GPUndoListener) {}
    override fun die() {}
    override fun addUndoableEditTxnFactory(factory: UndoableEditTxnFactory) {}
  }

  /**
   * However many tasks an import touches, undoing it must take one press. Otherwise the user has
   * to undo task by task and can stop halfway, leaving the hours half imported.
   */
  @Test
  fun `an import over several tasks is a single undo step`() {
    val first = newTask("A")
    val second = newTask("B")
    val undo = CountingUndoManager()

    val plan = planTaskImport(
      listOf(entry(1L, 2.0) to first, entry(2L, 3.0) to second), emptyMap(), properties)
    applyImportAsSingleEdit(plan, properties, projectDatabase, undo, "Import")

    assertEquals(1, undo.edits, "an import must open exactly one undoable edit")
    assertEquals(2.0, first.actualEffortHours(properties)!!, 0.001)
    assertEquals(3.0, second.actualEffortHours(properties)!!, 0.001)
  }

  /** Re-importing unchanged data must not leave an empty step in the undo history. */
  @Test
  fun `an import with nothing to write opens no undo step`() {
    val task = newTask("A")
    val undo = CountingUndoManager()
    applyImportAsSingleEdit(
      planTaskImport(listOf(entry(1L, 2.0) to task), emptyMap(), properties),
      properties, projectDatabase, undo, "Import")

    val secondPlan = planTaskImport(
      listOf(entry(1L, 2.0) to task), ledgerOfProject(), properties)
    applyImportAsSingleEdit(secondPlan, properties, projectDatabase, undo, "Import")

    assertEquals(1, undo.edits, "the unchanged second run added an undo step")
  }

  // --- what must stay untouched ---

  @Test
  fun `the planned effort and the duration are left alone`() {
    val task = newTask("A")
    task.customValues.setValue(EffortDrivenProperties.findOrCreateTaskEffort(properties), 10.0)
    val plannedBefore = task.customValues.getValue(
      EffortDrivenProperties.findOrCreateTaskEffort(properties))
    val durationBefore = task.duration.length
    val completionBefore = task.completionPercentage

    applyTaskImport(
      planTaskImport(listOf(entry(1L, 40.0) to task), emptyMap(), properties),
      properties, projectDatabase)

    assertEquals(plannedBefore, task.customValues.getValue(
      EffortDrivenProperties.findOrCreateTaskEffort(properties)), "the planned effort changed")
    assertEquals(durationBefore, task.duration.length, "the duration changed")
    assertEquals(completionBefore, task.completionPercentage, "the completion changed")
  }

  /** An overrun is reported, not corrected. */
  @Test
  fun `an overrun is reported and nothing is corrected`() {
    val task = newTask("A")
    task.customValues.setValue(EffortDrivenProperties.findOrCreateTaskEffort(properties), 10.0)

    val plan = planTaskImport(listOf(entry(1L, 40.0) to task), emptyMap(), properties)

    assertTrue(plan[0].exceedsPlanned, "40 recorded against 10 planned must be reported")
    assertEquals(10.0, plan[0].plannedHours!!, 0.001, "the plan itself must stay as it was")
  }

  @Test
  fun `staying within the plan is not reported as an overrun`() {
    val task = newTask("A")
    task.customValues.setValue(EffortDrivenProperties.findOrCreateTaskEffort(properties), 10.0)

    val plan = planTaskImport(listOf(entry(1L, 4.0) to task), emptyMap(), properties)
    assertFalse(plan[0].exceedsPlanned)
  }
}
