/*
Copyright 2026 BarD Software s.r.o

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
package net.sourceforge.ganttproject.importer

import net.sourceforge.ganttproject.GanttPreviousState
import net.sourceforge.ganttproject.GanttPreviousStateTask

/**
 * Adds the baselines of an imported project to the baselines of the target project.
 *
 * A baseline records task ids, and importing tasks gives them new ids, so the ids of the entries
 * are translated with [taskMapping]. An entry whose task is not in the mapping is dropped, because
 * its id may belong to a different task of the target project.
 *
 * If the target project already has a baseline with the same name, the imported one gets a number
 * appended, e.g. "Baseline (2)". The baselines of the target project are never changed.
 *
 * @return the baselines which were added to [targetBaselines]
 */
fun importBaselines(
  sourceBaselines: List<GanttPreviousState>,
  targetBaselines: MutableList<GanttPreviousState>,
  taskMapping: TaskMapping
): List<GanttPreviousState> {
  val id2id = taskMapping.entries.associate { (original, imported) -> original.taskID to imported.taskID }
  return sourceBaselines.map { source ->
    val tasks = source.tasks.mapNotNull { entry ->
      id2id[entry.id]?.let { newId ->
        GanttPreviousStateTask(newId, entry.start, entry.duration, entry.isMilestone, entry.hasNested())
      }
    }
    GanttPreviousState(freeName(source.name, targetBaselines), tasks).also { targetBaselines.add(it) }
  }
}

private fun freeName(name: String, baselines: List<GanttPreviousState>): String {
  val taken = baselines.map { it.name }.toSet()
  if (name !in taken) {
    return name
  }
  var n = 2
  while ("$name ($n)" in taken) {
    n++
  }
  return "$name ($n)"
}
