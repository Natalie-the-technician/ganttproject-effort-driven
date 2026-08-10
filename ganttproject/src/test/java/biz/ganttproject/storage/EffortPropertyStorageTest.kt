/*
Copyright 2026 Noctuvo

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
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import javax.sql.DataSource

/**
 * Reproduces the failure that manual testing found: saving the effort blew up with
 * `Column "effort_hours" not found`, because the H2 mirror table of the project had no column for
 * the freshly created property definition.
 *
 * The point of this test is the ORDER: the definition is created late, while the task properties
 * dialog is being committed, and the update follows immediately. Anything that creates the column
 * only at project open would pass a naive test and still fail in the running application.
 */
class EffortPropertyStorageTest {
  private lateinit var dataSource: DataSource
  private lateinit var projectDatabase: ProjectDatabase
  private lateinit var taskManager: TaskManager
  private lateinit var customPropertyManager: CustomPropertyManager
  private lateinit var listener: CustomPropertyListener

  /**
   * Every test gets its OWN in-memory database. With a shared name the H2 database survives
   * between the tests of this class, so a column created by one test is still there in the next
   * one — which made the repair test below pass while proving nothing.
   */
  @BeforeEach
  fun init(testInfo: TestInfo) {
    val dbName = "effort${testInfo.displayName.hashCode()}"
    dataSource = JdbcDataSource().also {
      it.setURL("jdbc:h2:mem:$dbName$SQL_PROJECT_DATABASE_OPTIONS")
    }
    projectDatabase = SqlProjectDatabaseImpl(dataSource)
    projectDatabase.init()
    taskManager = TestSetupHelper.newTaskManagerBuilder().also {
      it.setTaskUpdateBuilderFactory { task -> projectDatabase.createTaskUpdateBuilder(task) }
    }.build()
    customPropertyManager = taskManager.customPropertyManager
    // The running application registers this listener in GanttProjectBase; without it nothing
    // ever adds a column to the mirror table.
    listener = object : CustomPropertyListener {
      override fun customPropertyChange(event: CustomPropertyEvent) {
        projectDatabase.onCustomColumnChange(customPropertyManager)
      }
    }
    customPropertyManager.addListener(listener)
  }

  @AfterEach
  fun clear() {
    dataSource.connection.use { conn -> conn.createStatement().execute("shutdown") }
  }

  /** Reads the effort straight out of the mirror table. Throws when the column does not exist. */
  private fun readEffortColumn(): Double? =
    dataSource.connection.use { conn ->
      conn.createStatement().use { stmt ->
        stmt.executeQuery("SELECT ${EffortDrivenProperties.TASK_EFFORT_HOURS} FROM Task").use { rs ->
          if (rs.next()) (rs.getObject(1) as? Number)?.toDouble() else null
        }
      }
    }

  /**
   * Creates the effort definition the way the task properties dialog does, then writes a value to
   * a task. This is what failed by hand.
   */
  @Test
  fun `effort can be stored right after its definition was created`() {
    val task = taskManager.newTaskBuilder().withName("t").build()
    projectDatabase.insertTask(task)

    val def = EffortDrivenProperties.findOrCreateTaskEffort(customPropertyManager)
    val edited = task.customValues.copyOf().also { it.setValue(def, 20.0) }

    val mutator = task.createMutator()
    mutator.setCustomProperties(edited)
    mutator.commit()
    // Read back: the commit swallows database errors, so only the stored value proves anything.
    assertEquals(20.0, readEffortColumn())
  }

  /**
   * The failure that manual testing hit, reproduced by creating the definition WITHOUT the
   * listener that adds the mirror column — and the rescue that the task properties dialog now
   * performs explicitly.
   *
   * Why this matters beyond the error message: once the definition exists without its column,
   * every later write of custom properties fails too, so no further task can be created until the
   * program is restarted. The explicit sync must repair that.
   */
  @Test
  fun `an explicit column sync repairs a definition without a column`() {
    val task = taskManager.newTaskBuilder().withName("t").build()
    projectDatabase.insertTask(task)

    // Simulate the broken state: definition present, mirror column missing.
    customPropertyManager.removeListener(listener)
    val def = EffortDrivenProperties.findOrCreateTaskEffort(customPropertyManager)

    // A COPY, exactly like the task properties dialog uses. Modifying task.customValues in place
    // and handing in the same object produces no change for the mutator to write, and the test
    // would prove nothing.
    val edited = task.customValues.copyOf().also { it.setValue(def, 20.0) }

    // The write must be checked by looking into the database, NOT by catching an exception:
    // MutatorImpl.commit() swallows ProjectDatabaseException and only logs it (TaskImpl.kt:283).
    // That is why the running application carried on after the failure - and why an earlier
    // version of this test passed while proving nothing.
    task.createMutator().also { it.setCustomProperties(edited) }.commit()
    assertTrue(runCatching { readEffortColumn() }.isFailure,
      "the mirror column must be missing here, otherwise this test proves nothing")

    // This is what TaskPropertiesController.save() now does before committing.
    projectDatabase.onCustomColumnChange(customPropertyManager)

    val edited2 = task.customValues.copyOf().also { it.setValue(def, 30.0) }
    task.createMutator().also { it.setCustomProperties(edited2) }.commit()
    assertEquals(30.0, readEffortColumn())
  }

  /**
   * The same thing, but wrapped in a project database transaction — this is what the running
   * application does, because the task properties dialog commits inside an undoable edit
   * (UndoableEditTxnImpl starts a transaction before the edit runs).
   */
  @Test
  fun `effort can be stored inside an open transaction`() {
    val task = taskManager.newTaskBuilder().withName("t").build()
    projectDatabase.insertTask(task)

    val txn = projectDatabase.startTransaction("edit properties")
    val def = EffortDrivenProperties.findOrCreateTaskEffort(customPropertyManager)
    val edited = task.customValues.copyOf().also { it.setValue(def, 20.0) }

    val mutator = task.createMutator()
    mutator.setCustomProperties(edited)
    mutator.commit()
    // Read back: the commit swallows database errors, so only the stored value proves anything.
    assertEquals(20.0, readEffortColumn())
    txn.commit()
  }
}
