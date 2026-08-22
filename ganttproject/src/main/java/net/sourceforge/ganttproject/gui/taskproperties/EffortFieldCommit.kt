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

import biz.ganttproject.customproperty.CustomPropertyHolder
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.fork.findOrCreateOriginalEffort
import net.sourceforge.ganttproject.storage.ProjectDatabase
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import net.sourceforge.ganttproject.task.algorithm.findEffortDefinition

/**
 * Lets every effort field write into the property holder, and only THEN brings the mirror table
 * in line.
 *
 * The order is the whole point of this function, and it is why the function exists at all:
 *
 * - A field may CREATE its property definition when a value is entered for the first time.
 * - The H2 mirror of the project needs a column per definition. That column is created by
 *   [ProjectDatabase.onCustomColumnChange].
 * - Syncing first and writing afterwards leaves the newest definition without a column. The
 *   following UPDATE then fails with `Column "..." not found`, and because
 *   `MutatorImpl.commit()` only logs database errors, the dialog closes as if all was well.
 *   Worse, every later write fails too — in session 3 that state made it impossible to create
 *   any further task until the program was restarted.
 *
 * Kept out of the panel classes on purpose: no JavaFX type appears here, so the order can be
 * tested without a screen. An earlier test claimed to secure this order but did not — it wrote
 * its own sequence and never ran the one that `TaskPropertiesController.save()` uses. Moving the
 * sync in production left that test green. This function is the single place where the order
 * lives, so a counter-test on it is worth something.
 */
fun applyEffortFieldsThenSyncColumns(
  holder: CustomPropertyHolder,
  definitions: CustomPropertyManager,
  projectDatabase: ProjectDatabase,
  fields: List<(CustomPropertyHolder) -> Unit>
) {
  fields.forEach { field -> field(holder) }
  // [fork change] Record the first estimate typed in as the ORIGINAL one at the same time.
  //
  // WHY HERE: this is the place where a person enters an estimate. Whoever improves it later
  // otherwise compares the actual hours against the improved figure -- and the deviation
  // disappears at exactly the moment it is noticed.
  //
  // Only when nothing has been recorded yet. A second call changes nothing.
  rememberOriginalEffort(holder, definitions)
  // Must stay AFTER the loop and after the line above: both may create a definition. See above.
  projectDatabase.onCustomColumnChange(definitions)
}

private fun rememberOriginalEffort(
  holder: CustomPropertyHolder, definitions: CustomPropertyManager) {
  val effortDef = definitions.findEffortDefinition(EffortDrivenProperties.TASK_EFFORT_HOURS)
    ?: return
  val heute = holder.getValue(effortDef) ?: return
  val originalDef = findOrCreateOriginalEffort(definitions)
  if (holder.getValue(originalDef) == null) {
    holder.setValue(originalDef, heute)
  }
}
