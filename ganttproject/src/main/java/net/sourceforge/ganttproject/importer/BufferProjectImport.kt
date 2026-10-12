/*
Copyright 2021 BarD Software s.r.o

This file is part of GanttProject, an open-source project management tool.

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

import biz.ganttproject.core.calendar.ImportCalendarOption
import biz.ganttproject.core.table.ColumnList
import biz.ganttproject.customproperty.CustomPropertyDefinition
import net.sourceforge.ganttproject.IGanttProject
import net.sourceforge.ganttproject.fork.BaselineNameCollision
import net.sourceforge.ganttproject.gui.UIFacade
import net.sourceforge.ganttproject.resource.HumanResourceMerger.MergeResourcesOption
import net.sourceforge.ganttproject.task.Task

data class ImportBufferProjectApi(
  val taskColumnList: () -> ColumnList,
  val resourceColumnList: () -> ColumnList,
  val refresh: () -> Unit
)

typealias TaskMapping = Map<Task, Task>

/**
 * [fork change] What [IGanttProject.importProject] actually produces: the task mapping it always
 * returned, plus the source-to-target mapping of the TASK custom column definitions that
 * [importProject] already computes internally (`that2thisCustomDefs`) in order to carry the custom
 * property VALUES over correctly, but used to discard once that was done. Column ids are not stable
 * across projects -- `CustomColumnsManager.createId()` starts a fresh counter at "tpc0" in every
 * project -- so anything that wants to talk about "the same custom column" across the source and the
 * target, such as which columns should stay visible after an import, needs this mapping too.
 */
data class ProjectImportResult(
  val taskMapping: TaskMapping,
  val customColumnMapping: Map<CustomPropertyDefinition, CustomPropertyDefinition>
)

/**
 * @author dbarashev@bardsoftware.com
 */
// @JvmOverloads ADDED IN THIS FORK, together with the trailing [baselineNameCollision] parameter.
// Without it, the three existing Java call sites (ImporterFromCsvFile, PasteAction,
// ImporterFromMsProjectFile) -- none of which import baselines and all of which already pass
// every parameter up to closeCurrentProject explicitly -- would stop compiling: Java cannot see a
// Kotlin default value on a plain function unless this annotation generates the shorter overload
// for it. This is a plain top-level function, unlike IGanttProject.importProject, which is an
// interface method and therefore cannot carry @JvmOverloads at all; see the comment there.
@JvmOverloads
fun importBufferProject(
  targetProject: IGanttProject,
  bufferProject: BufferProject,
  importApi: ImportBufferProjectApi,
  mergeOption: MergeResourcesOption,
  importCalendarOption: ImportCalendarOption?,
  closeCurrentProject: Boolean = false,
  baselineNameCollision: BaselineNameCollision? = null
): TaskMapping {
  val result = targetProject.importProject(bufferProject, mergeOption, importCalendarOption, closeCurrentProject, baselineNameCollision)
  importApi.refresh()
  // [fork change] The source's visible-fields list is written in the SOURCE project's column ids.
  // Whenever the source's own custom column collided with one the target already had,
  // CustomColumnsManager.importData (above, inside importProject) gave it a NEW id in the target --
  // translate the list to match, or the merge below ends up comparing the source's custom column
  // against whatever the target happens to have under that same, now-unrelated id.
  val idMapping = result.customColumnMapping.entries.associate { (source, target) -> source.id to target.id }
  importApi.taskColumnList().importData(bufferProject.visibleFields.translateCustomColumnIds(idMapping), true)
  // The resource table's own visible-fields/custom-column collision is a separate, unmeasured gap --
  // see 2026-10-08-import-spalten-sichtbarkeit.md ("Was ich NICHT geprüft habe"). Left as it was.
  importApi.resourceColumnList().importData(bufferProject.myResourceVisibleFields, true)
  return result.taskMapping
}

private fun ColumnList.translateCustomColumnIds(idMapping: Map<String, String>): ColumnList {
  if (idMapping.isEmpty()) return this
  val translated = (0 until size).map { idx ->
    val column = getField(idx)
    idMapping[column.id]?.let { newId ->
      ColumnList.ColumnStub(newId, column.name, column.isVisible, column.order, column.width)
    } ?: column
  }
  return ColumnList.Immutable.fromList(translated)
}

fun UIFacade.asImportBufferProjectApi() = ImportBufferProjectApi(
  { taskColumnList },
  { resourceColumnList },
  { refresh() }
)
