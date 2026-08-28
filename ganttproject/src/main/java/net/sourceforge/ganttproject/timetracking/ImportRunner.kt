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

import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.storage.ProjectDatabase
import net.sourceforge.ganttproject.undo.GPUndoManager

/**
 * Puts one import into ONE undo step.
 *
 * Why this is its own function rather than a line in the menu action: the property "an import is
 * undone in one go" is worth a test, and a menu action cannot be tested without a screen. Here
 * the undo manager is a parameter, so a fake can count how many edits were opened.
 *
 * Two rules, both covered by tests:
 *
 * - **One edit for the whole import**, however many tasks it touches. Otherwise the user would
 *   have to press undo once per task to get back to where they started.
 * - **No edit at all when there is nothing to write.** Re-importing unchanged data must not leave
 *   an empty step in the undo history that looks like something happened.
 *
 * The call must NOT come from inside a mutator commit: a re-entered mutator's `commit()` does
 * nothing, so the values would be dropped. That is why the import hangs off a menu item.
 */
fun applyImportAsSingleEdit(
  changes: List<TaskImportChange>,
  taskProperties: CustomPropertyManager,
  projectDatabase: ProjectDatabase,
  undoManager: GPUndoManager,
  editName: String
): ImportWriteResult {
  val toWrite = changes.filterNot { it.isEmpty }
  if (toWrite.isEmpty()) {
    return ImportWriteResult(emptyList(), emptyList())
  }
  var result = ImportWriteResult(emptyList(), emptyList())
  undoManager.undoableEdit(editName) {
    result = applyTaskImport(toWrite, taskProperties, projectDatabase)
  }
  return result
}
