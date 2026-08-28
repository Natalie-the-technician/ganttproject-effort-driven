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
package net.sourceforge.ganttproject.gui.taskproperties

import biz.ganttproject.core.time.CalendarFactory
import javafx.beans.property.BooleanProperty
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.sourceforge.ganttproject.GanttProjectImpl
import net.sourceforge.ganttproject.fork.forkText
import net.sourceforge.ganttproject.fork.toModelDate
import net.sourceforge.ganttproject.task.ResourceAssignment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * The check box columns of the resources tab, and whether a tick in them reaches the assignment.
 *
 * WHY THIS TEST EXISTS. `CheckBoxTableCell.forTableColumn(column)` does NOT start an edit. It asks
 * the column for the cell value (`TableColumn.getCellObservableValue`) and, when that is a
 * `BooleanProperty`, binds the check box to it BIDIRECTIONALLY. `onEditCommit` is therefore never
 * fired, and a cell value factory that hands out a fresh `SimpleBooleanProperty` on every call
 * swallows the click: the tick lands in an object nobody reads again.
 *
 * MEASURED in the bytecode of javafx-controls-21, the version this build resolves:
 *
 *     javap -p -c javafx/scene/control/cell/CheckBoxTableCell.class | grep -c commitEdit
 *     0
 *
 * Zero occurrences in the whole class — an `onEditCommit` handler on such a column is unreachable.
 *
 * The two axis columns of P1 were built through the helper `axisProperty` for that reason. The
 * original coordinator column had the same defect and is repaired here through THE SAME helper.
 * The last test pins that all three check box columns stay on that one path.
 *
 * Setting the property is exactly what a click does, so these tests need no screen.
 */
class TaskResourcesPanelTest {

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

  /**
   * Ticking "coordinator" has to reach the assignment.
   *
   * RED before the fix, measured on e8a6e19b6:
   *
   *     org.opentest4j.AssertionFailedError: the tick did not reach the assignment ==>
   *     expected: <true> but was: <false>
   */
  @Test
  fun `ticking coordinator reaches the assignment`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val fixture = Fixture()
      assertFalse(fixture.assignment.isCoordinator, "precondition: nobody is the coordinator yet")

      fixture.checkBoxValue(COORDINATOR_COLUMN).value = true

      assertTrue(fixture.assignment.isCoordinator, "the tick did not reach the assignment")
    }
  }

  /**
   * Removing the tick has to reach the assignment as well, otherwise it could not be undone.
   *
   * RED before the fix, measured on e8a6e19b6:
   *
   *     org.opentest4j.AssertionFailedError: the removed tick did not reach the assignment ==>
   *     expected: <false> but was: <true>
   *
   * Note which assertion did NOT fail there: the one above it, that the column shows the stored
   * value. Reading was always right; only the write direction was missing.
   */
  @Test
  fun `removing the coordinator tick reaches the assignment`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val fixture = Fixture()
      fixture.assignment.isCoordinator = true

      val property = fixture.checkBoxValue(COORDINATOR_COLUMN)
      assertTrue(property.get(), "the column does not show the stored value")

      property.value = false

      assertFalse(fixture.assignment.isCoordinator, "the removed tick did not reach the assignment")
    }
  }

  /**
   * The last row of the table stands for "add a new assignment" and has no assignment behind it.
   * Ticking its check box must neither throw nor touch another row.
   *
   * COULD NOT BE RED before the fix: the old column handed out a throwaway property for the empty
   * row as well, so writing into it was a no-op there too. This test guards the NEW listener,
   * which dereferences the assignment — it is a regression guard, not a defect witness.
   */
  @Test
  fun `ticking coordinator in the empty last row does nothing`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val fixture = Fixture()

      fixture.checkBoxValue(COORDINATOR_COLUMN, row = 1).value = true

      assertFalse(fixture.assignment.isCoordinator, "the empty row must not touch another row")
    }
  }

  /**
   * THE COUNTER-CHECK OF THIS PACKAGE. All three check box columns of the tab — the original
   * coordinator column and the two axis columns of P1 — have to write through to the assignment,
   * and each one only into its own field.
   *
   * This is what makes the repair a structural one instead of a local one: there is ONE way of
   * wiring a check box column in this panel, and every such column is on it.
   *
   * RED before the fix, measured on e8a6e19b6:
   *
   *     org.opentest4j.AssertionFailedError: after ticking column 3: column 3 ==>
   *     expected: <true> but was: <false>
   *
   * It stops at the coordinator column, so this run says nothing about columns 5 and 6. That the
   * two axis columns were intact BEFORE the change is a separate measurement — `AssignmentAxesUiTest`
   * was green on the same commit — and this test keeps them measured from here on.
   */
  @Test
  fun `every check box column writes through to the assignment`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val fixture = Fixture()
      val columns = fixture.columns()

      // Pins the layout the indices below rely on. A reordered table trips here with a readable
      // message instead of failing somewhere deep inside the assertions.
      assertEquals(8, columns.size, "the table no longer has the expected number of columns")
      assertEquals(forkText("fork.assignment.blocking"), columns[BLOCKING_COLUMN].text,
        "column $BLOCKING_COLUMN is no longer the blocking axis")
      assertEquals(forkText("fork.assignment.noEffort"), columns[NO_EFFORT_COLUMN].text,
        "column $NO_EFFORT_COLUMN is no longer the no-effort axis")

      val readers = linkedMapOf<Int, (ResourceAssignment) -> Boolean>(
        COORDINATOR_COLUMN to { it.isCoordinator },
        BLOCKING_COLUMN to { it.isBlocking },
        NO_EFFORT_COLUMN to { it.isNoEffort }
      )
      val expected = readers.keys.associateWith { false }.toMutableMap()

      fun assertWholeState(step: String) {
        readers.forEach { (column, read) ->
          assertEquals(expected.getValue(column), read(fixture.assignment),
            "$step: column $column")
        }
      }

      assertWholeState("precondition")

      // One column at a time on, checking the OTHER two did not move with it.
      readers.keys.forEach { column ->
        fixture.checkBoxValue(column).value = true
        expected[column] = true
        assertWholeState("after ticking column $column")
      }

      // And one at a time off again.
      readers.keys.forEach { column ->
        fixture.checkBoxValue(column).value = false
        expected[column] = false
        assertWholeState("after unticking column $column")
      }
    }
  }
}

/** Column order of the table: id, resource, load, coordinator, role, blocking, no-effort, hours. */
private const val COORDINATOR_COLUMN = 3
private const val BLOCKING_COLUMN = 5
private const val NO_EFFORT_COLUMN = 6

/**
 * One person on one task, and the panel that shows the assignment.
 *
 * Built like the fixture of `AssignmentAxesUiTest`, which is the setup proven to carry this panel
 * on this branch: the panel's hours column reads the resource, so a project with a real resource
 * manager is needed and not a bare task manager.
 */
private class Fixture {
  private val project = GanttProjectImpl()
  val assignment: ResourceAssignment
  private val panel: TaskResourcesPanel

  init {
    project.humanResourceManager.create("Natalie", 0)
    project.taskManager.newTaskBuilder()
      .withName("Vorgang")
      .withStartDate(LocalDate.of(2026, 8, 27).toModelDate())
      .withDuration(project.taskManager.createLength(5))
      .build()
    val task = project.taskManager.tasks.single()
    assignment = task.assignmentCollection.addAssignment(project.humanResourceManager.getById(0))
    assignment.load = 100f
    panel = TaskResourcesPanel(task, project.humanResourceManager, project.roleManager)
  }

  fun columns(): List<TableColumn<*, *>> = tableOf(panel.fxComponent).columns

  /**
   * The property the check box of the given column is bound to, for the given row.
   *
   * Fails loudly when the column hands out something unwritable — a click on such a check box
   * would come out nowhere, which is exactly the defect under test.
   */
  fun checkBoxValue(column: Int, row: Int = 0): BooleanProperty {
    @Suppress("UNCHECKED_CAST")
    val value = (columns()[column] as TableColumn<Any, Any>).getCellObservableValue(row)
    return value as? BooleanProperty
      ?: throw AssertionError(
        "column $column returns ${value?.javaClass?.name} instead of a writable property")
  }

  private fun tableOf(node: Node): TableView<*> =
    node as? TableView<*>
      ?: (node as? Parent)?.childrenUnmodifiable?.firstNotNullOfOrNull {
        runCatching { tableOf(it) }.getOrNull()
      }
      ?: throw AssertionError("the resources tab contains no table")
}
