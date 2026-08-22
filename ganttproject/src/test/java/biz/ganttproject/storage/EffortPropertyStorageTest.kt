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
package biz.ganttproject.storage

import biz.ganttproject.customproperty.CustomPropertyClass
import biz.ganttproject.customproperty.CustomPropertyEvent
import biz.ganttproject.customproperty.CustomPropertyListener
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.gui.taskproperties.applyEffortFieldsThenSyncColumns
import net.sourceforge.ganttproject.storage.LazyProjectDatabaseProxy
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

  /**
   * The failure as it happens in the running application, with the SAME wiring: the task manager
   * talks to a LazyProjectDatabaseProxy, and the custom property listener is the one that
   * GanttProjectBase registers (`createTaskCustomPropertyListener`).
   *
   * Every earlier test in this file bypassed the proxy and therefore passed while the application
   * kept failing. This one is the honest reproduction.
   */
  @Test
  fun `effort reaches the database through the real proxy wiring`() {
    val proxy = LazyProjectDatabaseProxy(
      databaseFactory = { SqlProjectDatabaseImpl(dataSource) },
      taskManager = { proxyTaskManager },
      filterUpdater = {})
    proxyTaskManager = TestSetupHelper.newTaskManagerBuilder().also {
      it.setTaskUpdateBuilderFactory { task -> proxy.createTaskUpdateBuilder(task) }
    }.build()
    val cpm = proxyTaskManager.customPropertyManager
    cpm.addListener(proxy.createTaskCustomPropertyListener())

    val task = proxyTaskManager.newTaskBuilder().withName("t").build()
    proxy.insertTask(task)

    // Exactly what the task properties dialog does: create the definition, then write a value.
    val def = EffortDrivenProperties.findOrCreateTaskEffort(cpm)
    proxy.onCustomColumnChange(cpm)
    val edited = task.customValues.copyOf().also { it.setValue(def, 20.0) }
    task.createMutator().also { it.setCustomProperties(edited) }.commit()

    assertEquals(20.0, readEffortColumn())
  }

  /**
   * THE bug that manual testing found, reproduced: after "Projekt -> Neu" every custom column
   * change was silently dropped.
   *
   * `ProjectUIFacadeImpl.createProject` calls `project.close()` (which sets `isProjectOpen`
   * to false) and then `fireProjectCreated()`. Nothing handled `projectCreated`, so the flag
   * stayed false and `LazyProjectDatabaseProxy.onCustomColumnChange` swallowed everything from
   * then on — no exception, no log, no column.
   *
   * This is not specific to the effort feature: ANY custom column created after a new project
   * was affected. The effort field just hits it every single time.
   */
  @Test
  fun `custom columns still reach the database after a new project was created`() {
    val proxy = LazyProjectDatabaseProxy(
      databaseFactory = { SqlProjectDatabaseImpl(dataSource) },
      taskManager = { proxyTaskManager },
      filterUpdater = {})
    proxyTaskManager = TestSetupHelper.newTaskManagerBuilder().also {
      it.setTaskUpdateBuilderFactory { task -> proxy.createTaskUpdateBuilder(task) }
    }.build()
    val cpm = proxyTaskManager.customPropertyManager
    cpm.addListener(proxy.createTaskCustomPropertyListener())
    val projectListener = proxy.createProjectEventListener()

    // This is what happens when the user picks "Projekt -> Neu".
    projectListener.projectClosed()
    projectListener.projectCreated()

    val task = proxyTaskManager.newTaskBuilder().withName("t").build()
    proxy.insertTask(task)
    val def = EffortDrivenProperties.findOrCreateTaskEffort(cpm)
    val edited = task.customValues.copyOf().also { it.setValue(def, 20.0) }
    task.createMutator().also { it.setCustomProperties(edited) }.commit()

    assertEquals(20.0, readEffortColumn())
  }

  /**
   * The same defect, shown WITHOUT any of this fork's code: a plain custom text column, created
   * the way the column manager creates one. Nothing here mentions effort.
   *
   * This is what makes the report to the upstream project honest — the bug is in stock
   * GanttProject, our feature merely runs into it every time.
   */
  @Test
  fun `a plain custom column also fails after a new project was created`() {
    val proxy = LazyProjectDatabaseProxy(
      databaseFactory = { SqlProjectDatabaseImpl(dataSource) },
      taskManager = { proxyTaskManager },
      filterUpdater = {})
    proxyTaskManager = TestSetupHelper.newTaskManagerBuilder().also {
      it.setTaskUpdateBuilderFactory { task -> proxy.createTaskUpdateBuilder(task) }
    }.build()
    val cpm = proxyTaskManager.customPropertyManager
    cpm.addListener(proxy.createTaskCustomPropertyListener())
    val projectListener = proxy.createProjectEventListener()

    projectListener.projectClosed()
    projectListener.projectCreated()

    val task = proxyTaskManager.newTaskBuilder().withName("t").build()
    proxy.insertTask(task)
    val def = cpm.createDefinition(CustomPropertyClass.TEXT, "Bemerkung", null)
    val edited = task.customValues.copyOf().also { it.setValue(def, "hallo") }
    task.createMutator().also { it.setCustomProperties(edited) }.commit()

    val stored = dataSource.connection.use { conn ->
      conn.createStatement().use { stmt ->
        stmt.executeQuery("SELECT ${def.id} FROM Task").use { rs ->
          if (rs.next()) rs.getObject(1)?.toString() else null
        }
      }
    }
    assertEquals("hallo", stored)
  }

  private lateinit var proxyTaskManager: TaskManager

  /** Reads the effort straight out of the mirror table. Throws when the column does not exist. */
  private fun readEffortColumn(): Double? = readDoubleColumn(EffortDrivenProperties.TASK_EFFORT_HOURS)

  /**
   * Reads the recorded actual effort straight out of the mirror table. Throws when the column does
   * not exist — which is the whole point: a missing column is exactly the failure being tested.
   */
  private fun readActualEffortColumn(): Double? =
    readDoubleColumn(EffortDrivenProperties.TASK_EFFORT_ACTUAL_HOURS)

  private fun readDoubleColumn(column: String): Double? =
    dataSource.connection.use { conn ->
      conn.createStatement().use { stmt ->
        stmt.executeQuery("SELECT $column FROM Task").use { rs ->
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

  // [fork change] ---- start: actual hours (step 1 of the time-tracking handover) ----

  /**
   * The recorded actual effort walks into exactly the same trap as the planned effort: its
   * definition is created while the task properties dialog is being committed, so the mirror
   * column has to appear at that very moment.
   *
   * Same shape as `effort can be stored right after its definition was created`, for the second
   * property. It is a separate test on purpose — one property having a column says nothing about
   * the other one.
   */
  @Test
  fun `actual effort can be stored right after its definition was created`() {
    val task = taskManager.newTaskBuilder().withName("t").build()
    projectDatabase.insertTask(task)

    val def = EffortDrivenProperties.findOrCreateTaskActualEffort(customPropertyManager)
    val edited = task.customValues.copyOf().also { it.setValue(def, 12.5) }

    val mutator = task.createMutator()
    mutator.setCustomProperties(edited)
    mutator.commit()
    // Read back: the commit swallows database errors, so only the stored value proves anything.
    assertEquals(12.5, readActualEffortColumn())
  }

  /**
   * The broken state for the actual effort, and the repair: definition present, mirror column
   * missing, value lost — until the explicit sync runs.
   *
   * The precondition is asserted, not assumed: without the `assertTrue` below the test would also
   * pass if the column had been there all along, and would prove nothing.
   */
  @Test
  fun `an explicit column sync repairs an actual effort definition without a column`() {
    val task = taskManager.newTaskBuilder().withName("t").build()
    projectDatabase.insertTask(task)

    // Simulate the broken state: definition present, mirror column missing.
    customPropertyManager.removeListener(listener)
    val def = EffortDrivenProperties.findOrCreateTaskActualEffort(customPropertyManager)

    val edited = task.customValues.copyOf().also { it.setValue(def, 12.5) }
    task.createMutator().also { it.setCustomProperties(edited) }.commit()
    assertTrue(runCatching { readActualEffortColumn() }.isFailure,
      "the mirror column must be missing here, otherwise this test proves nothing")

    // This is what TaskPropertiesController.save() does before committing.
    projectDatabase.onCustomColumnChange(customPropertyManager)

    val edited2 = task.customValues.copyOf().also { it.setValue(def, 31.0) }
    task.createMutator().also { it.setCustomProperties(edited2) }.commit()
    assertEquals(31.0, readActualEffortColumn())
  }

  /**
   * The order that the task properties dialog performs: BOTH effort fields write into one property
   * holder, the column sync runs ONCE for the whole manager, and a single mutator commit stores
   * everything. See `TaskPropertiesController.save()`.
   *
   * The listener is removed on purpose, so that the explicit sync is the ONLY thing that can
   * create the two mirror columns. Otherwise the listener would create them anyway and this test
   * would stay green even with the sync deleted — it would guard nothing.
   *
   * Removing the sync, or moving it in front of the two `apply` calls (the easy mistake when a
   * third field is added later), makes the reads below fail on a missing column.
   *
   * Both values are checked: a sync that produced only the first column would otherwise pass.
   */
  @Test
  fun `effort and actual effort are stored together by one dialog commit`() {
    val task = taskManager.newTaskBuilder().withName("t").build()
    projectDatabase.insertTask(task)
    customPropertyManager.removeListener(listener)

    val holder = task.customValues.copyOf()
    holder.setValue(EffortDrivenProperties.findOrCreateTaskEffort(customPropertyManager), 20.0)
    holder.setValue(
      EffortDrivenProperties.findOrCreateTaskActualEffort(customPropertyManager), 12.5)
    projectDatabase.onCustomColumnChange(customPropertyManager)
    task.createMutator().also { it.setCustomProperties(holder) }.commit()

    assertEquals(20.0, readEffortColumn())
    assertEquals(12.5, readActualEffortColumn())
  }

  /**
   * The order that the dialog really uses, driven through the SAME function the dialog calls:
   * [applyEffortFieldsThenSyncColumns].
   *
   * The test above writes its own sequence and therefore secures nothing — verified by
   * counter-test: moving the sync in `TaskPropertiesController.save()` in front of the two
   * `apply` calls left it green. This one goes through the production code path, so a
   * counter-test inside that function does break it.
   *
   * The listener is removed on purpose, so the explicit sync is the only thing that can create
   * the columns.
   */
  @Test
  fun `the dialog order creates the columns before the values are written`() {
    val task = taskManager.newTaskBuilder().withName("t").build()
    projectDatabase.insertTask(task)
    customPropertyManager.removeListener(listener)

    val holder = task.customValues.copyOf()
    applyEffortFieldsThenSyncColumns(
      holder = holder,
      definitions = customPropertyManager,
      projectDatabase = projectDatabase,
      // Stand-ins for the two panel fields: each creates its definition on first use, exactly
      // like applyEffort/applyActualEffort do.
      fields = listOf(
        { h -> h.setValue(EffortDrivenProperties.findOrCreateTaskEffort(customPropertyManager), 20.0) },
        { h -> h.setValue(EffortDrivenProperties.findOrCreateTaskActualEffort(customPropertyManager), 12.5) }))
    task.createMutator().also { it.setCustomProperties(holder) }.commit()

    assertEquals(20.0, readEffortColumn())
    assertEquals(12.5, readActualEffortColumn())
  }

  // [fork change] ---- end of the new block ----

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
