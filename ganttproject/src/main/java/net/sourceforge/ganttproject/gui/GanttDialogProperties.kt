/*
GanttProject is an opensource project management tool.
Copyright (C) 2011 GanttProject Team

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 3
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package net.sourceforge.ganttproject.gui

import javafx.scene.control.Tab
import net.sourceforge.ganttproject.GPLogger
import net.sourceforge.ganttproject.GanttTask
import net.sourceforge.ganttproject.IGanttProject
import net.sourceforge.ganttproject.action.CancelAction
import net.sourceforge.ganttproject.action.OkAction
import net.sourceforge.ganttproject.gui.taskproperties.TaskPropertiesController
import net.sourceforge.ganttproject.language.GanttLanguage
import net.sourceforge.ganttproject.task.dependency.TaskDependencyException
import java.text.MessageFormat

class GanttDialogProperties(private val tasks: Array<GanttTask?>) {
    fun show(project: IGanttProject, uiFacade: UIFacade) {
        val language = GanttLanguage.getInstance()
        val taskPropertiesController = TaskPropertiesController(tasks[0]!!, project.roleManager, project.humanResourceManager, project.projectDatabase, uiFacade)

        val okAction = OkAction.create("ok") {
            uiFacade.getUndoManager().undoableEdit(language.getText("properties.changed"), Runnable {
                val mutator = taskPropertiesController.save()
                mutator.commit()
                // [fork change] The effort is a property of the TASK. While its mutator is
                // committing, task.createMutator() returns a re-entered mutator whose commit()
                // does nothing - a duration set there would be lost. Hence only here, after the
                // commit, and BEFORE the scheduling algorithm below: the algorithm sets the
                // duration, then the scheduler propagates the dates.
                project.taskManager.algorithmCollection.effortDrivenDurationAlgorithm.run()
                try {
                    project.taskManager.getAlgorithmCollection().recalculateTaskScheduleAlgorithm.run()
                } catch (e: TaskDependencyException) {
                    if (!GPLogger.log(e)) {
                        e.printStackTrace()
                    }
                }
                uiFacade.refresh()
                uiFacade.getActiveChart().focus()
            })
        }

        val cancelAction = CancelAction.create("cancel") {
            taskPropertiesController.cancel()
            uiFacade.getActiveChart().focus()
        }

        val taskNames = StringBuffer()
        for (i in tasks.indices) {
            if (i > 0) {
                taskNames.append(language.getText(if (i + 1 == tasks.size) "list.separator.last" else "list.separator"))
            }
            taskNames.append(tasks[i]!!.name)
        }

        val title = MessageFormat.format(language.getText("properties.task.title"), taskNames)
        val tabProviders = listOf(
            PropertiesDialogTabProvider(
              { tabPane -> tabPane.tabs.add(Tab(
                taskPropertiesController.mainPropertiesPanel.title,
                taskPropertiesController.mainPropertiesPanel.fxComponent
              ))},
              { taskPropertiesController.mainPropertiesPanel.requestFocus() }
            ),
            PropertiesDialogTabProvider(
              { tabPane -> tabPane.tabs.add(Tab(
                taskPropertiesController.predecessorsPanel.title,
                taskPropertiesController.predecessorsPanel.fxComponent
              ))},
              { taskPropertiesController.predecessorsPanel.requestFocus() }
            ),
          PropertiesDialogTabProvider(
            { tabPane -> tabPane.tabs.add(Tab(
              taskPropertiesController.resourcesPanel.title,
              taskPropertiesController.resourcesPanel.fxComponent
            ))},
            { taskPropertiesController.resourcesPanel.requestFocus() }
          ),
            //swingTab(language.getText("predecessors")) { taskPropertiesBean.predecessorsPanel },
            //swingTab(language.getText("human")) { taskPropertiesBean.resourcesPanel },
            PropertiesDialogTabProvider(
                { tabPane ->
                    tabPane.tabs.add(
                        Tab(
                            taskPropertiesController.customPropertiesPanel.title,
                            taskPropertiesController.customPropertiesPanel.getFxNode()
                        )
                    )
                },
                { }
            )
        )

        val actions = listOf(okAction, cancelAction)
        propertiesDialog(title, "taskProperties", actions, taskPropertiesController.validationErrors, tabProviders)
    }
}
