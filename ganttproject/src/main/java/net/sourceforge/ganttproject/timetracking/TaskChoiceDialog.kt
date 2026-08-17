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
package net.sourceforge.ganttproject.timetracking

import biz.ganttproject.app.dialog
import javafx.beans.property.SimpleObjectProperty
import javafx.collections.FXCollections
import javafx.scene.control.ButtonType
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.util.Callback
import javafx.util.StringConverter
import net.sourceforge.ganttproject.fork.forkText
import java.util.function.Consumer

/**
 * Assigning time entries to tasks by hand — step 6.
 *
 * WHAT IT IS FOR: the import on its own only takes entries that already name their task
 * (`#332 Firmware`). Everything else needs a person, because guessing writes hours onto a task
 * nobody chose and nothing about that is visible afterwards. This is where that person decides.
 *
 * WHAT IT DELIBERATELY DOES NOT DO: split one entry across several tasks. `validateSplit` exists
 * for that, but [planTaskImport] insists on ONE assignment per entry and says why: it would need
 * an hours value per assignment (`EntryAssignment(entry, task, hours)`) instead of a pair. Until
 * that exists, offering a split here would produce exactly the double-counting the precondition
 * was added to prevent.
 *
 * Rows left on "not assign" are skipped — the same outcome as before the dialog, so closing it
 * without deciding anything costs nothing.
 */
data class ManualAssignment(val entry: TogglTimeEntry, val taskId: Int)

fun interface TaskChoiceAsker {
  /**
   * Shows the entries and calls [answer] with what the person chose — possibly an empty list, and
   * possibly never, which both mean "import only the unambiguous ones".
   */
  fun ask(
    entries: List<TogglTimeEntry>,
    tasks: List<MatchableTask>,
    answer: Consumer<List<ManualAssignment>>
  )
}

/** One row: the entry, and the task chosen for it (null = skip). */
private class ChoiceRow(val entry: TogglTimeEntry, val suggestions: List<MatchableTask>) {
  val chosen = SimpleObjectProperty<MatchableTask?>(null)
}

val ASK_FOR_THE_TASKS = TaskChoiceAsker { entries, tasks, answer ->
  // Best suggestion first, so the usual case is glancing at the preselection and pressing OK.
  // Everything else stays reachable in the same list.
  val rows = entries.map { entry ->
    val ranked = suggestTasks(entry, tasks, limit = tasks.size).map { it.task }
    val rest = tasks.filterNot { task -> ranked.any { it.id == task.id } }
    ChoiceRow(entry, ranked + rest).also { row ->
      // Preselect ONLY a strong suggestion. A weak one preselected is a guess that looks like a
      // decision -- and the person would confirm it without noticing.
      suggestTasks(entry, tasks, limit = 1).firstOrNull()
        ?.takeIf { it.reasons.contains(MatchReason.LEARNED) ||
                   it.reasons.contains(MatchReason.PROJECT_LINK) }
        ?.let { row.chosen.value = it.task }
    }
  }

  dialog(title = forkText("fork.toggl.assign.title"), id = "togglTaskChoice") { dlg ->
    val table = TableView(FXCollections.observableArrayList(rows))
    table.prefHeight = 380.0

    val entryColumn = TableColumn<ChoiceRow, String>(forkText("fork.toggl.assign.column.entry"))
    entryColumn.cellValueFactory = Callback { SimpleObjectProperty(describeEntry(it.value.entry)) }
    entryColumn.prefWidth = 420.0

    val taskColumn = TableColumn<ChoiceRow, ChoiceRow>(forkText("fork.toggl.assign.column.task"))
    taskColumn.cellValueFactory = Callback { SimpleObjectProperty(it.value) }
    taskColumn.prefWidth = 300.0
    taskColumn.cellFactory = Callback {
      object : TableCell<ChoiceRow, ChoiceRow>() {
        override fun updateItem(row: ChoiceRow?, empty: Boolean) {
          super.updateItem(row, empty)
          graphic = if (row == null || empty) null else ComboBox<MatchableTask?>().also { box ->
            // null as a real entry in the list, not a separate control: "do not assign" is a
            // choice like any other, and it must stay reachable after something was picked.
            box.items = FXCollections.observableArrayList(
              listOf<MatchableTask?>(null) + row.suggestions)
            box.value = row.chosen.value
            box.converter = object : StringConverter<MatchableTask?>() {
              override fun toString(task: MatchableTask?): String =
                task?.let { "#${it.id} ${it.name}" } ?: forkText("fork.toggl.assign.none")
              override fun fromString(s: String?): MatchableTask? = null
            }
            box.valueProperty().addListener { _, _, picked -> row.chosen.value = picked }
            box.prefWidthProperty().bind(taskColumn.widthProperty().subtract(8))
          }
        }
      }
    }

    table.columns.setAll(listOf(entryColumn, taskColumn))

    dlg.setContent(VBox(8.0).also { box ->
      box.children.add(Label(forkText("fork.toggl.assign.what", entries.size)).also {
        it.isWrapText = true
      })
      box.children.add(table)
      VBox.setVgrow(table, Priority.ALWAYS)
      box.children.add(Label(forkText("fork.toggl.assign.hint")).also { it.isWrapText = true })
    })

    dlg.setupButton(ButtonType.OK) { button ->
      button.onAction = javafx.event.EventHandler {
        answer.accept(rows.mapNotNull { row -> row.chosen.value?.let { ManualAssignment(row.entry, it.id) } })
        dlg.hide()
      }
    }
    dlg.setupButton(ButtonType.CANCEL) { button ->
      // Nothing chosen, nothing assigned -- the unambiguous entries are still imported.
      button.onAction = javafx.event.EventHandler {
        answer.accept(emptyList())
        dlg.hide()
      }
    }
  }
}
