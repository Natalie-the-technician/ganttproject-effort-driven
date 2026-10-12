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
  importApi.taskColumnList().importData(bufferProject.visibleFields, true)
  importApi.resourceColumnList().importData(bufferProject.myResourceVisibleFields, true)
  return result
}

fun UIFacade.asImportBufferProjectApi() = ImportBufferProjectApi(
  { taskColumnList },
  { resourceColumnList },
  { refresh() }
)
