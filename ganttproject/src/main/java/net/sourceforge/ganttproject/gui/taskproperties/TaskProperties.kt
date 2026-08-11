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
        // [Fork-Aenderung] Aufwand und Ist-Stunden werden in DENSELBEN Halter geschrieben, den die
        // Custom-Property-Registerkarte gleich committet. Wuerden die Felder direkt auf
        // task.customValues schreiben, wuerde dieser Aufruf sie mit der beim Oeffnen gezogenen
        // Kopie stillschweigend ueberschreiben.
        resourcesPanel.applyEffort(it)
        resourcesPanel.applyActualEffort(it)
        // [Fork-Aenderung] REIHENFOLGE BEACHTEN: Dieser Abgleich muss NACH allen apply*-Aufrufen
        // stehen. Die Spiegeltabelle in der H2-Datenbank braucht eine Spalte fuer jede Property.
        // Legt einer der Aufrufe oben eine Definition neu an, entsteht diese Spalte sonst nicht
        // rechtzeitig: das UPDATE scheitert dann mit `Column "effort_hours" not found`, und weil
        // die Definition trotzdem existiert, schlaegt danach JEDER weitere Schreibvorgang fehl -
        // es lassen sich keine Vorgaenge mehr anlegen, bis das Programm neu gestartet wird.
        // Ein weiteres Feld gehoert deshalb ueber diese Zeile, nicht darunter.
        // Der Aufruf gilt fuer den ganzen Property-Manager und ist idempotent: er vergleicht
        // Definitionen und Spalten und tut nichts, wenn bereits alles passt.
        // Abgesichert durch EffortPropertyStorageTest, insbesondere
        // `effort and actual effort are stored together by one dialog commit`.
        projectDatabase.onCustomColumnChange(task.manager.customPropertyManager)
        mutator.setCustomProperties(it)
      }
      predecessorsPanel.commit()
      resourcesPanel.commit(mutator)
    }

  fun cancel() {
    customPropertiesPanel.cancel()
  }

}