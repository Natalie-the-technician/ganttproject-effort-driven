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
import net.sourceforge.ganttproject.gui.taskproperties.TaskResourcesPanel
import net.sourceforge.ganttproject.task.ResourceAssignment
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * Does a tick in the assignment tab actually reach the assignment?
 *
 * WHY THIS TEST EXISTS. The two checkboxes were built the way the ORIGINAL coordinator column is
 * built: a cell value factory that hands out a fresh `SimpleBooleanProperty`, plus a
 * `setOnEditCommit` handler. In the running program that combination swallows the click.
 * `CheckBoxTableCell.forTableColumn(column)` does not start an edit -- it binds the checkbox
 * BIDIRECTIONALLY to whatever the cell value factory returned, so `onEditCommit` is never fired
 * and the tick lands in a throwaway object.
 *
 * MEASURED ON 27.08.2026 in the running program, before the fix: both ticks set in the dialog,
 * Ok, Ctrl+S -- the saved file said
 *
 *     <allocation task-id="0" resource-id="0" ... blocking="false" no-effort="false" .../>
 *
 * and reopening the dialog showed both boxes empty again. Nothing in the test suite noticed,
 * because no test went through the panel.
 *
 * This test goes through the REAL column of the REAL panel: it asks the column for the property
 * that the checkbox is bound to and writes into it, which is exactly what a click does.
 *
 * The same defect sits in the original coordinator column. It is NOT repaired here -- that would
 * be behaviour outside P1 -- and it is reported separately.
 */
class AssignmentAxesUiTest {

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

  /** One person on one task, and the panel that shows the assignment. */
  private class Aufbau {
    val project = GanttProjectImpl()
    val assignment: ResourceAssignment
    val panel: TaskResourcesPanel

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
  }

  private fun tabelle(node: Node): TableView<*> =
    node as? TableView<*>
      ?: (node as? Parent)?.childrenUnmodifiable?.firstNotNullOfOrNull { runCatching { tabelle(it) }.getOrNull() }
      ?: throw AssertionError("im Zuordnungsreiter steckt keine Tabelle")

  /** The property the checkbox of [ueberschrift] in the first row is bound to. */
  private fun haekchen(panel: TaskResourcesPanel, ueberschrift: String): BooleanProperty {
    val spalten = tabelle(panel.fxComponent).columns
    val spalte = spalten.firstOrNull { it.text == ueberschrift }
      ?: throw AssertionError(
        "die Spalte \"$ueberschrift\" fehlt, vorhanden sind: ${spalten.map { it.text }}")
    @Suppress("UNCHECKED_CAST")
    val wert = (spalte as TableColumn<Any, Any>).getCellObservableValue(0)
    return wert as? BooleanProperty
      ?: throw AssertionError(
        "die Spalte \"$ueberschrift\" liefert ${wert?.javaClass?.name} statt einer beschreibbaren " +
          "Eigenschaft -- ein Klick auf das Haekchen kaeme nirgends an")
  }

  @Test
  fun `ein haekchen im zuordnungsreiter erreicht die zuordnung`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val aufbau = Aufbau()

      assertFalse(aufbau.assignment.isBlocking, "die Zuordnung steht nicht auf dem heutigen Verhalten")
      assertFalse(aufbau.assignment.isNoEffort, "die Zuordnung steht nicht auf dem heutigen Verhalten")

      // Setting the property IS what a click on the checkbox does: CheckBoxTableCell binds the
      // box bidirectionally to exactly this object.
      haekchen(aufbau.panel, forkText("fork.assignment.blocking")).set(true)
      assertTrue(aufbau.assignment.isBlocking,
        "das Haekchen \"Abwesenheit blockiert\" ist in der Oberflaeche haengengeblieben")
      assertFalse(aufbau.assignment.isNoEffort, "B wurde mitgesetzt, obwohl nur A angehakt war")

      haekchen(aufbau.panel, forkText("fork.assignment.noEffort")).set(true)
      assertTrue(aufbau.assignment.isNoEffort,
        "das Haekchen \"Ohne Leistung\" ist in der Oberflaeche haengengeblieben")
    }
  }

  /** Taking the tick back has to arrive as well, otherwise it could not be undone. */
  @Test
  fun `ein zurueckgenommenes haekchen erreicht die zuordnung auch`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val aufbau = Aufbau()
      aufbau.assignment.isBlocking = true

      val eigenschaft = haekchen(aufbau.panel, forkText("fork.assignment.blocking"))
      assertTrue(eigenschaft.get(), "die Spalte zeigt den gespeicherten Wert nicht an")

      eigenschaft.set(false)
      assertFalse(aufbau.assignment.isBlocking,
        "das zurueckgenommene Haekchen ist in der Oberflaeche haengengeblieben")
    }
  }
}
