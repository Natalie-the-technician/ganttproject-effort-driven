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

import biz.ganttproject.app.PropertyPaneBuilderImpl
import biz.ganttproject.core.option.ObservableEnum
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.task.Task

/**
 * The one row the home working mark occupies in the task dialog. [fork change]
 *
 * WHY IT IS NOT SIMPLY WRITTEN INSIDE `MainPropertiesPanel`: the panel as a whole cannot be built
 * in the test JVM. Its name field asks for autocompletion, `AutoCompletionBinding` reaches into
 * `com.sun.javafx.event`, and the `test` task in the root `build.gradle` does not pass
 * `javaExportOptions` to the test JVM the way the application gets them -- so building the panel
 * dies with `IllegalAccessError: ... module javafx.base does not export com.sun.javafx.event`.
 * Measured on 04.09.2026; it has nothing to do with this feature.
 *
 * The alternative would have been a test that rebuilds the row itself, and the note above
 * `applyEffortFieldsThenSyncColumns` records what that is worth: the last test that rebuilt a
 * sequence instead of running it stayed green while production moved out from under it. So the row
 * lives here, the panel calls it, and `TaskHomeWorkUiTest` calls the same function. Whatever the
 * dialog shows is what the test measures.
 */
fun PropertyPaneBuilderImpl.homeWorkRow(option: ObservableEnum<HomeWorkMark>) {
  // Label and entries are given explicitly because every text here comes out of this fork's own
  // bundle. Left alone the builder would look up `homeWork.label` and `homeWork.value.on_site` in
  // GanttProject's translations, which do not have them, and the dialog would show the keys.
  dropdown(option) {
    labelText = forkText("fork.task.homeWork")
    value2string = { mark -> forkText(homeWorkLabelKey(mark)) }
  }
}

/** The bundle key for one state. Kept next to the row so a new state cannot be forgotten here. */
fun homeWorkLabelKey(mark: HomeWorkMark): String = when (mark) {
  HomeWorkMark.NOT_DECIDED -> "fork.task.homeWork.notDecided"
  HomeWorkMark.FROM_HOME -> "fork.task.homeWork.fromHome"
  HomeWorkMark.ON_SITE -> "fork.task.homeWork.onSite"
}

/** The option behind the row, standing on whatever the task says today. */
fun homeWorkOption(task: Task, manager: CustomPropertyManager): ObservableEnum<HomeWorkMark> =
  ObservableEnum("homeWork", task.homeWorkMark(manager), HomeWorkMark.entries.toTypedArray())
