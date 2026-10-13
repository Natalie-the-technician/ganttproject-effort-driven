/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
Carrying the baselines of an imported project across the import boundary.

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

WHY THIS EXISTS. Measured on 08.10.2026 at the running program: a project imported through
*Project ▸ Import…* loses its baselines completely and without a word. The baseline dialog of the
target project shows an empty list afterwards, while the same file opened on its own shows the
baseline — so the file was fine and the import dropped it. In the code,
`GanttProjectImpl.importProject` transfers roles, calendar, resource properties, resources, task
properties, tasks and assignments, and nothing else. The only reader of `<previous>` outside the
file loader, `XmlProjectImporter.importPreviousStateTasks`, belongs to the CLOUD import path.

WHY THE CLOUD ONE CANNOT SIMPLY BE REUSED, which is the whole reason this file is not three lines:

  1. IDS. A baseline does not record tasks, it records TASK IDS. The cloud path keeps ids 1:1, the
     desktop import does NOT: `taskManager.importData` hands out NEW ids and returns the mapping.
     An untranslated baseline entry therefore does not merely point nowhere -- it points at a
     DIFFERENT, EXISTING task of the target project, because low ids are taken there too. That is
     worse than losing the baseline: it would draw the imported project's history onto the target
     project's own tasks, and nothing in the interface would say so.
  2. [NO LONGER A REASON, since 13.10.2026 / bardsoft #2838 -- was reason 2 until the merge of
     nachziehen-bards-1012.] A `GanttPreviousState` used to hold its entries in a temporary file,
     written by `init()` + `saveFile()`, which the cloud path's `importPreviousStateTasks` never
     called. That is moot now: a baseline is in-memory from construction on, and the cloud path
     already builds one correctly (`GanttPreviousState(baseline.name, tasks)`, no further call
     needed). Reason 1, the id translation, is the one this file still exists for.

WHAT THIS FILE DOES NOT DECIDE, ON PURPOSE: what happens when the target project already has a
baseline of the same name. [BaselineNameCollision] lists the possibilities, this file implements
all of them, and the choice belongs to Natalie -- it is not made here and it is not made by
whoever wires this up. See the report of 08.10.2026 for the four options and their consequences,
and `BaselineNameCollisionMeasurementTest` for what the program does today (nothing: there is no
duplicate check anywhere, and a lookup by name hits the first of two).

NOT WIRED UP YET, and that is deliberate. The call belongs in `GanttProjectImpl.importProject`,
directly after `importAssignments`, passing the `TaskMapping` that `importData` returns:

    importBaselines(bufferProject.baselines, baselines, result, <the decided collision policy>)

One line, one decision missing.
*/
package net.sourceforge.ganttproject.fork

import net.sourceforge.ganttproject.GanttPreviousState
import net.sourceforge.ganttproject.GanttPreviousStateTask
import net.sourceforge.ganttproject.task.Task

/**
 * What to do with an imported baseline whose name the target project already uses.
 *
 * THE VALUES ARE THE OPTIONS PUT TO NATALIE on 08.10.2026, so that the decision and the code use
 * the same words. None of them is a default: the caller has to name one, which is why this is a
 * parameter without a default value.
 */
enum class BaselineNameCollision {
  /**
   * Append under the unchanged name, so the list shows the name twice.
   *
   * Matches what the file loader does today, and inherits its problem: the baseline dialog looks
   * a baseline up BY NAME and stops at the first hit, so deleting one of two identically named
   * ones removes the other.
   */
  APPEND_AS_IS,

  /**
   * Append under a name prefixed with the imported project's name, e.g. "QUELLPROJEKT: Basis".
   *
   * Loses nothing and costs no extra dialog. If the prefixed name collides as well -- the same
   * file imported twice -- a counter is appended, because the alternative would be to re-create
   * the ambiguity this option exists to avoid.
   */
  PREFIX_WITH_PROJECT_NAME,

  /** Replace the target project's baseline of that name. Loses the target's own recorded state. */
  REPLACE_EXISTING,

  /**
   * Import nothing under a colliding name and report it, leaving the target's baseline alone.
   *
   * The conservative one: it never loses what the target has, but it does lose what the import
   * brought, so the result has to be reported rather than swallowed -- which is what
   * [BaselineImportResult.skippedForNameCollision] is for.
   */
  SKIP_COLLIDING
}

/**
 * What an import did to the baselines, to be reported rather than swallowed.
 *
 * EVERY NUMBER HERE IS A LOSS OR A CHANGE THE USER CANNOT SEE OTHERWISE. A baseline that arrived
 * under a different name, an entry that was dropped because its task is gone, a baseline skipped
 * because of its name: none of that is visible in the baseline dialog, which shows a list of
 * names and nothing about where they came from.
 */
data class BaselineImportResult(
  /** Baselines that are now in the target project. */
  val imported: List<GanttPreviousState> = emptyList(),
  /** Imported name -> the name it ended up under, only for the ones that changed. */
  val renamed: Map<String, String> = emptyMap(),
  /** Baselines not imported because their name collided and the policy said to skip. */
  val skippedForNameCollision: List<String> = emptyList(),
  /** Baselines of the target project that an imported one replaced. */
  val replaced: List<String> = emptyList(),
  /**
   * Entries dropped per baseline name because their task id is not in the mapping.
   *
   * A BASELINE MAY KNOW TASKS THAT NO LONGER EXIST -- it is a record of a past state, and a task
   * recorded then can have been deleted since. Such an entry has no counterpart in the target
   * project, and keeping its id untranslated would point it at a stranger (see the file comment),
   * so it is dropped and counted.
   */
  val droppedEntries: Map<String, Int> = emptyMap()
)

/**
 * Carries [sourceBaselines] into [targetBaselines], translating task ids through [original2imported].
 *
 * @param original2imported the mapping `taskManager.importData` returns: original task -> imported
 *   task. Translated to ids here rather than passed as ids, because that is the shape the caller
 *   already has and converting it in the caller would invite someone to pass the wrong direction.
 * @param onNameCollision what to do about a name the target already uses. No default on purpose:
 *   the decision is Natalie's, see [BaselineNameCollision].
 * @param importedProjectName used by [BaselineNameCollision.PREFIX_WITH_PROJECT_NAME]. Blank names
 *   fall back to a generic prefix, because an untitled project is normal and a prefix of ": " is
 *   not a name.
 */
fun importBaselines(
  sourceBaselines: List<GanttPreviousState>,
  targetBaselines: MutableList<GanttPreviousState>,
  original2imported: Map<Task, Task>,
  onNameCollision: BaselineNameCollision,
  importedProjectName: String = ""
): BaselineImportResult {
  if (sourceBaselines.isEmpty()) {
    return BaselineImportResult()
  }
  // ID TRANSLATION TABLE, built once. getTaskID() and not the uid: a baseline records the int id.
  val id2id = original2imported.entries.associate { (original, imported) ->
    original.taskID to imported.taskID
  }

  val imported = mutableListOf<GanttPreviousState>()
  val renamed = mutableMapOf<String, String>()
  val skipped = mutableListOf<String>()
  val replaced = mutableListOf<String>()
  val dropped = mutableMapOf<String, Int>()

  sourceBaselines.forEach { source ->
    val originalEntries = source.getTasks()
    val translated = originalEntries.mapNotNull { entry ->
      id2id[entry.id]?.let { newId ->
        GanttPreviousStateTask(newId, entry.start, entry.duration, entry.isMilestone, entry.hasNested())
      }
    }
    val lost = originalEntries.size - translated.size
    if (lost > 0) {
      dropped[source.name] = lost
    }

    // THE NAME IS DECIDED AGAINST THE TARGET LIST AS IT IS RIGHT NOW, not against the list as it
    // was when the import started: two imported baselines can collide with each other, not only
    // with the target's own.
    val collides = targetBaselines.any { it.name == source.name }
    val name = when {
      !collides -> source.name
      onNameCollision == BaselineNameCollision.PREFIX_WITH_PROJECT_NAME ->
        freeName(prefixed(source.name, importedProjectName), targetBaselines)
      else -> source.name
    }
    if (collides && onNameCollision == BaselineNameCollision.SKIP_COLLIDING) {
      skipped.add(source.name)
      return@forEach
    }
    if (name != source.name) {
      renamed[source.name] = name
    }

    val carried = GanttPreviousState(name, translated)
    if (collides && onNameCollision == BaselineNameCollision.REPLACE_EXISTING) {
      val victim = targetBaselines.first { it.name == name }
      targetBaselines[targetBaselines.indexOf(victim)] = carried
      replaced.add(name)
    } else {
      targetBaselines.add(carried)
    }
    imported.add(carried)
  }

  return BaselineImportResult(imported, renamed, skipped, replaced, dropped)
}

/** "QUELLPROJEKT: Basis", or "Imported: Basis" when the imported project has no name. */
private fun prefixed(name: String, projectName: String): String =
  if (projectName.isBlank()) "Imported: $name" else "$projectName: $name"

/** [candidate], or [candidate] with the lowest free counter appended. */
private fun freeName(candidate: String, existing: List<GanttPreviousState>): String {
  val taken = existing.map { it.name }.toSet()
  if (candidate !in taken) {
    return candidate
  }
  var n = 2
  while ("$candidate ($n)" in taken) {
    n++
  }
  return "$candidate ($n)"
}
