/*
GanttProject is an opensource project management tool.
Copyright (C) 2005-2021 Dmitry Barashev, GanttProject team

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
package net.sourceforge.ganttproject

import biz.ganttproject.app.SimpleBarrier
import biz.ganttproject.app.TimerBarrier
import biz.ganttproject.app.TwoPhaseBarrierImpl
import biz.ganttproject.core.calendar.GPCalendarCalc
import biz.ganttproject.core.calendar.ImportCalendarOption
import biz.ganttproject.core.calendar.WeekendCalendarImpl
import biz.ganttproject.core.option.BooleanOption
import biz.ganttproject.core.option.ColorOption
import biz.ganttproject.core.option.DefaultBooleanOption
import biz.ganttproject.core.option.DefaultColorOption
import biz.ganttproject.core.time.TimeUnitStack
import biz.ganttproject.core.time.impl.GPTimeUnitStack
import biz.ganttproject.customproperty.CustomColumnsManager
import biz.ganttproject.customproperty.CustomPropertyManager
import biz.ganttproject.ganttview.TaskFilterManager
import biz.ganttproject.ganttview.TaskViewManager
import net.sourceforge.ganttproject.document.Document
import net.sourceforge.ganttproject.document.DocumentManager
import net.sourceforge.ganttproject.gui.NotificationManager
import net.sourceforge.ganttproject.gui.UIConfiguration
import net.sourceforge.ganttproject.gui.UIFacade
import net.sourceforge.ganttproject.gui.options.model.GP1XOptionConverter
import net.sourceforge.ganttproject.importer.BufferProject
import net.sourceforge.ganttproject.importer.TaskMapping
import net.sourceforge.ganttproject.language.GanttLanguage
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.resource.HumanResourceMerger
import net.sourceforge.ganttproject.resource.OverwritingMerger
import net.sourceforge.ganttproject.roles.RoleManager
import net.sourceforge.ganttproject.storage.LazyProjectDatabaseProxy
// [fork change] New import for the trigger of the duration calculation.
import net.sourceforge.ganttproject.fork.findOrCreateDateFixed
import net.sourceforge.ganttproject.fork.findOrCreateDeadline
import net.sourceforge.ganttproject.fork.findOrCreateOriginalEffort
import net.sourceforge.ganttproject.fork.findOrCreateRecurrence
import net.sourceforge.ganttproject.fork.findOrCreateRecurrenceOf
import net.sourceforge.ganttproject.fork.findOrCreateUtilisation
import net.sourceforge.ganttproject.fork.findOrCreateWaitOnly
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenTrigger
import net.sourceforge.ganttproject.storage.ProjectDatabase
import net.sourceforge.ganttproject.task.*
import net.sourceforge.ganttproject.task.event.createTaskListenerWithTimerBarrier
import java.awt.Color
import java.io.IOException
import java.net.URL

fun interface ErrorUi {
  fun show(ex: Exception)
}

open class GanttProjectImpl(
  taskManager: TaskManagerImpl? = null,
  override val projectDatabase: ProjectDatabase = LazyProjectDatabaseProxy(
    {error("Not supposed to be called")},
    {error("Not supposed to be called")},
    {error("Not supposed to be called")},
  )) : IGanttProject {

  val listeners: MutableList<ProjectEventListener> = mutableListOf()
  override val baselines: MutableList<GanttPreviousState> = ArrayList()

  override var projectName: String = ""
  override var description: String = ""
  override var organization: String = ""
  override var webLink: String = ""

  val language: GanttLanguage get() = GanttLanguage.getInstance()
  private val myCalendar = WeekendCalendarImpl()
  final override val humanResourceManager = HumanResourceManager(
    RoleManager.Access.getInstance().defaultRole,
    CustomColumnsManager()
  )
  override val resourceCustomPropertyManager: CustomPropertyManager get() = humanResourceManager.customPropertyManager
  private val myTaskManagerConfig = TaskManagerConfigImpl(humanResourceManager, myCalendar)
  final override val taskManager: TaskManagerImpl = taskManager ?: TaskManagerImpl(null, myTaskManagerConfig)
  override val uIConfiguration = UIConfiguration(Color.BLUE, true)
  override val taskCustomColumnManager: CustomPropertyManager get() = taskManager.customPropertyManager
  override val taskFilterManager = TaskFilterManager(this.taskManager, this.projectDatabase)
  override val taskViewManager = TaskViewManager()
  override val roleManager: RoleManager
    get() = RoleManager.Access.getInstance()

  override var isModified: Boolean = false
  override val activeCalendar: GPCalendarCalc get() = myTaskManagerConfig.calendar
  override val timeUnitStack: TimeUnitStack get() = myTaskManagerConfig.timeUnitStack
  override var document: Document get() { TODO() } set(_) {
    TODO()
  }
  override val documentManager: DocumentManager
    get() = TODO("Not yet implemented")

  init {
    myCalendar.addListener { setModified() }
    // [fork change] This line is new, not present in the original. Without it the duration
    // calculation never starts — it is the only point at which the feature is switched on.
    // Effort-driven scheduling: recalculate durations when the resources change. Registered here,
    // in the UI-free project class, so that it also works headless (import, command line, tests).
    humanResourceManager.addView(EffortDrivenTrigger(this.taskManager))
  }

  /**
   * [fork change] Creates the two daily-rate columns in case they do not exist yet.
   *
   * WHY AT ALL: until now the "Hours per day" column only came into being when somebody created
   * it by hand in the column manager -- anyone who did not know that went on planning silently
   * with the default of eight hours. With the "Hours schedule" that would be worse still: a
   * property one cannot see is a property one cannot fill in either.
   *
   * WHY NOT IN THE CONSTRUCTOR, and that is measured on the machine: created there, LOADING then
   * fails for every file that contains the same column --
   * "Column with ID=hours_per_day is already registered", and as a
   * `DocumentException: Failed to parse document` for the WHOLE file. It was found by
   * `GanttChartSelectionTest` in the ganttproject-tester module, because the clipboard takes the
   * same path: save and read straight back.
   *
   * The call here happens after loading, when the columns from the file are already entered.
   * findOrCreate is then a no-op.
   */
  fun ensureCapacityColumns() {
    val ressourcen = humanResourceManager.customPropertyManager
    val vorgaenge = taskManager.customPropertyManager
    EffortDrivenProperties.findOrCreateResourceHours(ressourcen)
    EffortDrivenProperties.findOrCreateResourceSchedule(ressourcen)
    findOrCreateUtilisation(ressourcen)
    // ALL of this fork's columns, and all of them at that. MEASURED ON 17.08.2026: four of them
    // -- "Finish by", "Utilisation (%)", "Waiting", "Date fixed" -- were created NOWHERE. They
    // existed in the code but could not be filled in by anybody; levelling read them dutifully
    // and always found nothing. The same family of bug as the dead menu items from session 8:
    // built, not reachable, and from the outside indistinguishable from "does not work".
    findOrCreateRecurrence(vorgaenge)
    // [fork change] recurrence_of belongs to the same feature as recurrence and was missing here.
    // Until now it only came into being at the first recurring Task (RecurrenceAdapter). Same
    // construction, same idempotence: search first, create only when nothing is found.
    findOrCreateRecurrenceOf(vorgaenge)
    findOrCreateDeadline(vorgaenge)
    findOrCreateWaitOnly(vorgaenge)
    findOrCreateDateFixed(vorgaenge)
    findOrCreateOriginalEffort(vorgaenge)
  }

  override fun setModified() {
    isModified = true
  }

  override fun close() {
    // TODO Auto-generated method stub
  }

  override fun addProjectEventListener(listener: ProjectEventListener) {
    listeners.add(listener)
  }

  override fun removeProjectEventListener(listener: ProjectEventListener) {
    listeners.remove(listener)
  }

  fun fireProjectModified(isModified: Boolean, errorUi: ErrorUi) {
    for (modifiedStateChangeListener in listeners) {
      try {
        if (isModified) {
          modifiedStateChangeListener.projectModified()
        } else {
          modifiedStateChangeListener.projectSaved()
        }
      } catch (e: Exception) {
        errorUi.show(e)
      }
    }
  }

  open fun fireProjectCreated() {
    for (modifiedStateChangeListener in listeners) {
      modifiedStateChangeListener.projectCreated()
    }
  }

  protected open fun fireProjectClosed() {
    for (modifiedStateChangeListener in listeners) {
      modifiedStateChangeListener.projectClosed()
    }
  }

  protected open fun fireProjectOpened() {
    val barrier = TwoPhaseBarrierImpl<IGanttProject>("Project opened")
    for (l in listeners) {
      l.projectOpened(barrier, barrier)
    }
    barrier.activate(this)
  }

  @Throws(Document.DocumentException::class, IOException::class)
  override fun restore(fromDocument: Document) {
    restoreProject(fromDocument, this.listeners)
  }

  @Throws(IOException::class)
  override fun open(document: Document) {
    // TODO Auto-generated method stub
  }

  override fun importProject(
    bufferProject: BufferProject,
    mergeOption: HumanResourceMerger.MergeResourcesOption,
    importCalendarOption: ImportCalendarOption?,
    closeCurrentProject: Boolean
  ): TaskMapping {
      roleManager.importData(bufferProject.roleManager)
      if (importCalendarOption != null) {
        activeCalendar.importCalendar(bufferProject.activeCalendar, importCalendarOption)
      }
      val that2thisResourceCustomDefs =
        resourceCustomPropertyManager.importData(bufferProject.resourceCustomPropertyManager)
      val original2ImportedResource = humanResourceManager.importData(
        bufferProject.humanResourceManager, OverwritingMerger(mergeOption), that2thisResourceCustomDefs
      )
      val that2thisCustomDefs = taskCustomColumnManager.importData(bufferProject.taskCustomColumnManager)
      val origTaskManager = taskManager
      try {
        origTaskManager.setEventsEnabled(false)
        val result = origTaskManager.importData(bufferProject.taskManager, that2thisCustomDefs)
        origTaskManager.importAssignments(
          bufferProject.taskManager, humanResourceManager,
          result, original2ImportedResource
        )
        return result
      } finally {
        origTaskManager.setEventsEnabled(true)
      }
  }
}

private val DEFAULT_TASK_COLOR = Color(140, 182, 206)

class TaskManagerConfigImpl(
  private val myResourceManager: HumanResourceManager,
  calendar: GPCalendarCalc
) : TaskManagerConfig {
  private val myTimeUnitStack: GPTimeUnitStack
  private val myCalendar: GPCalendarCalc
  private val myDefaultTaskColorOption: ColorOption
  private val mySchedulerDisabledOption: BooleanOption
  override fun getDefaultColor(): Color {
    return myDefaultTaskColorOption.value!!
  }

  override fun getDefaultColorOption(): ColorOption {
    return myDefaultTaskColorOption
  }

  override fun getSchedulerDisabledOption(): BooleanOption {
    return mySchedulerDisabledOption
  }

  override fun getCalendar(): GPCalendarCalc {
    return myCalendar
  }

  override fun getTimeUnitStack(): TimeUnitStack {
    return myTimeUnitStack
  }

  override fun getResourceManager(): HumanResourceManager {
    return myResourceManager
  }

  override fun getProjectDocumentURL(): URL {
    TODO()
  }

  override fun getNotificationManager(): NotificationManager {
    TODO()
  }

  init {
    myTimeUnitStack = GPTimeUnitStack()
    myCalendar = calendar
    myDefaultTaskColorOption = DefaultTaskColorOption(DEFAULT_TASK_COLOR)
    mySchedulerDisabledOption = DefaultBooleanOption("scheduler.disabled", false)
  }
}

internal class DefaultTaskColorOption internal constructor(defaultColor: Color) :
  DefaultColorOption("taskDefaultColor", defaultColor), GP1XOptionConverter {
  constructor() : this(DEFAULT_TASK_COLOR)

  override fun getTagName(): String {
    return "colors"
  }

  override fun getAttributeName(): String {
    return "tasks"
  }

  override fun loadValue(legacyValue: String) {
    loadPersistentValue(legacyValue)
    commit()
  }
}

internal fun (IGanttProject).restoreProject(fromDocument: Document, listeners: List<ProjectEventListener>) {
  restoreProject(listeners) {
    fromDocument.read()
  }
}

internal fun <T> (IGanttProject).restoreProject(listeners: List<ProjectEventListener>, closeCurrentProject: Boolean = true, restoreCode: ()->T): T {
  val completionPromise = SimpleBarrier<Document>()
  listeners.forEach { it.projectRestoring(completionPromise) }
  val projectDocument = document
  if (closeCurrentProject) {
    close()
  }
  val algs = taskManager.algorithmCollection
  return try {
    algs.scheduler.isEnabled = false
    algs.recalculateTaskScheduleAlgorithm.isEnabled = false
    algs.adjustTaskBoundsAlgorithm.isEnabled = false
    restoreCode()
  } finally {
    algs.recalculateTaskScheduleAlgorithm.isEnabled = true
    algs.adjustTaskBoundsAlgorithm.isEnabled = true
    algs.scheduler.isEnabled = true
    completionPromise.resolve(projectDocument)
    document = projectDocument
  }
}


internal fun createProjectModificationListener(project: IGanttProject, uiFacade: UIFacade): ProjectOpenStateMachineBuilder {
  val timerBarrier = TimerBarrier(1000).apply {
    await {
      project.setModified()
    }
  }
  val taskListener = createTaskListenerWithTimerBarrier(timerBarrier).also {
    it.taskAddedHandler = {
      project.setModified()
      uiFacade.viewManager.getView(UIFacade.GANTT_INDEX.toString()).isActive = true
      uiFacade.refresh()
    }
  }
  project.taskManager.addTaskListener(taskListener)
  return { stateMachine ->
    stateMachine.stateStarted.await {
      timerBarrier.isPaused = true
    }
    stateMachine.stateCompleted.await {
      timerBarrier.isPaused = false
      project.isModified = false
    }
    stateMachine.stateCancelled.await {
      timerBarrier.isPaused = false
    }
    stateMachine.stateFailed.await {
      timerBarrier.isPaused = false
    }
  }
}
