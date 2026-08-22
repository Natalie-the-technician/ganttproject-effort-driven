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

import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.action.GPAction
import net.sourceforge.ganttproject.storage.ProjectDatabase
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.undo.GPUndoManager
import java.awt.event.ActionEvent

/**
 * The "create recurring Tasks …" menu item.
 *
 * Like the fork's two other tools: it ASKS beforehand, the preview also names what does NOT
 * happen, and everything is ONE undo step.
 *
 * IF ONE RULE CONTAINS AN ERROR, NOTHING AT ALL IS CREATED, not even for the series that can be
 * read. That is deliberate: had the run created half of them, it would no longer be possible after
 * fixing the typo to tell what already exists -- and a second run would have to guess.
 */
class RecurrenceAction(
  private val taskManager: TaskManager,
  private val taskProperties: CustomPropertyManager,
  private val projectDatabase: ProjectDatabase,
  private val undoManager: GPUndoManager,
  private val report: (Boolean, String) -> Unit,
  private val ask: AskBeforeWriting
) : GPAction("recurrence.run") {

  override fun getLocalizedName(): String = forkText("fork.recurrence.run")

  override fun actionPerformed(event: ActionEvent?) {
    // Create the column if it does not exist yet: a property one cannot see is a property one
    // cannot fill in either. Here and not at program startup -- a column that comes into being in
    // the constructor makes every file unloadable that contains the same column.
    findOrCreateRecurrence(taskProperties)
    projectDatabase.onCustomColumnChange(taskProperties)

    val plan = planRecurrences(taskManager, taskProperties)

    if (plan.hasErrors) {
      val text = StringBuilder()
      plan.errors.forEach { (name, fehler) ->
        text.append(forkText("fork.recurrence.error.title", name)).appendLine()
        fehler.forEach { text.append("  • ").append(it).appendLine() }
      }
      text.appendLine().append(forkText("fork.recurrence.error.consequence"))
      report(true, text.toString())
      return
    }
    if (plan.seriesCount == 0) {
      // Two different cases, two different messages: "there is nothing in it" and "there is
      // something in it, but it has already been created" feel the same on screen and have
      // completely different next steps.
      val gibtEsRegeln = taskManager.tasks.any { it.recurrenceText(taskProperties) != null }
      report(false, forkText(
        if (gibtEsRegeln) "fork.recurrence.nothingNew" else "fork.recurrence.none"))
      return
    }

    val message = StringBuilder(
      forkText("fork.recurrence.preview", plan.seriesCount, plan.occurrences.size))
    if (plan.truncated.isNotEmpty()) {
      message.appendLine().appendLine().append(forkText(
        "fork.recurrence.truncated", plan.truncated.size, RecurrenceRule.MAX_OCCURRENCES))
      plan.truncated.take(5).forEach { message.appendLine().append("  • ").append(it) }
    }
    ask.ask(message.toString()) { confirmed ->
      if (!confirmed) {
        return@ask
      }
      val angelegt = applyRecurrencesAsSingleEdit(plan, taskManager, taskProperties,
        projectDatabase, undoManager, forkText("fork.recurrence.undo"))
      report(false, forkText("fork.recurrence.done", angelegt))
    }
  }
}
