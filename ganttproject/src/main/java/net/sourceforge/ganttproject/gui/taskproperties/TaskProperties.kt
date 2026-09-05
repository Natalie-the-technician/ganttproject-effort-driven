/*
Copyright 2025 Dmitry Barashev, BarD Software s.r.o

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
package net.sourceforge.ganttproject.gui.taskproperties

import javafx.collections.FXCollections
import net.sourceforge.ganttproject.gui.UIFacade
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.roles.RoleManager
import net.sourceforge.ganttproject.storage.ProjectDatabase
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskMutator

class TaskPropertiesController(private val task: Task, roleManager: RoleManager, resourceManager: HumanResourceManager,
                               private val projectDatabase: ProjectDatabase, private val uiFacade: UIFacade) {

  val mainPropertiesPanel by lazy {
    MainPropertiesPanel(task, uiFacade.getCurrentTaskView()).also {
      it.defaultColor = uiFacade.ganttChart.taskDefaultColorOption
      it.validationErrors.subscribe {
        validationErrors.clear()
        validationErrors.addAll(it.validationErrors)
      }
    }
  }

  val predecessorsPanel by lazy {
    TaskDependenciesPanelFx(task)
  }

  val resourcesPanel by lazy {
    TaskResourcesPanel(task,  resourceManager, roleManager)
  }

  val customPropertiesPanel by lazy {
    CustomColumnsPanel(task.manager.customPropertyManager, projectDatabase, CustomColumnsPanel.Type.TASK,
      uiFacade.undoManager, task.customValues.copyOf(), uiFacade.taskColumnList)
  }


  val validationErrors = FXCollections.observableArrayList<String>()

  fun save(): TaskMutator =
    task.createMutator().also { mutator ->
      mainPropertiesPanel.save(mutator)
      customPropertiesPanel.save {
        // [fork change] Effort and actual hours are written into the SAME holder that the custom
        // property tab commits in a moment. If the fields wrote to task.customValues directly,
        // this call would silently overwrite them with the copy taken when the dialog was
        // opened.
        // [fork change] The order (write the fields, THEN reconcile the columns) sits in
        // applyEffortFieldsThenSyncColumns - there it is checkable without JavaFX and secured by
        // a counter-test. A further field is added to this list, nothing else changes here.
        applyEffortFieldsThenSyncColumns(
          holder = it,
          definitions = task.manager.customPropertyManager,
          projectDatabase = projectDatabase,
          // [fork change] The home working mark of package B2 joins this list and nothing else
          // changes here -- which is exactly what the note above promised a further field would
          // cost.
          //
          // IT HAS TO RUN HERE AND NOT IN mainPropertiesPanel.save(mutator) ABOVE. By this point
          // `CustomColumnsPanel.save` has already walked every definition and written an explicit
          // `false` into every boolean column of this task, because the value it hands over for an
          // empty column is `""` and `java.lang.Boolean.valueOf("")` is `false`. A task nobody
          // decided about would silently become "can be done from home". The field below runs
          // after that and clears the value again. Measured in `TaskHomeWorkDialogTest`.
          fields = listOf(resourcesPanel::applyEffort, resourcesPanel::applyActualEffort,
                          mainPropertiesPanel::applyHomeWorkMark))
        mutator.setCustomProperties(it)
      }
      predecessorsPanel.commit()
      resourcesPanel.commit(mutator)
    }

  fun cancel() {
    customPropertiesPanel.cancel()
  }

}