/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
The mark that says "the plan has changed since the last levelling run".

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

import biz.ganttproject.core.calendar.GPCalendarListener
import net.sourceforge.ganttproject.GPLogger
import net.sourceforge.ganttproject.resource.ResourceEvent
import net.sourceforge.ganttproject.resource.ResourceView
import net.sourceforge.ganttproject.task.event.TaskDependencyEvent
import net.sourceforge.ganttproject.task.event.TaskHierarchyEvent
import net.sourceforge.ganttproject.task.event.TaskListener
import net.sourceforge.ganttproject.task.event.TaskPropertyEvent
import net.sourceforge.ganttproject.task.event.TaskScheduleEvent
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ONE BOOLEAN: has anything happened since the last levelling run that could have made its result
 * out of date?
 *
 * WHAT IT DOES NOT DO, said first because it is the thing that is easiest to misread. It does not
 * compute. It does not ask whether the levelling would in fact move anything — that question has
 * an exact answer in the code (`LevellingActions.kt`, `moved == 0 && conflicts.isEmpty()`), and
 * answering it costs a full levelling run. Measured on 28.08.2026
 * (`2026-08-28-verteilung-probelauf-zeit.md`): about 1.5 seconds on the real plan, thirty times
 * over the threshold that was named as acceptable. So this observes events; it never calculates,
 * and it never writes.
 *
 * WHAT THE MARK HONESTLY MEANS: "something changed since the levelling last ran, OR since this
 * plan was loaded". Not "the levelling is wrong". The program has no record of when a plan was
 * last levelled — measured, `2026-08-28-verteilung-veraltet-messung.md` section 2.3: no
 * timestamp, no counter, nothing in the baseline either. A freshly opened file therefore starts
 * with the mark CLEARED (see [taskListener]'s `taskModelReset`), because "changed since load" is
 * the only claim the mark can actually support. It is not a claim that the file was levelled.
 *
 * WHY EVERY TASK PROPERTY EVENT COUNTS, false alarms and all. `taskPropertiesChanged` carries no
 * indication of WHICH property moved — checked: `TaskPropertyEvent` has no column field. Four
 * custom columns the levelling really reads (deadline, fixed date, waiting time, effort) arrive
 * through no other event. Dropping the property event to silence the renaming false alarm would
 * silently drop those four. The price is measured rather than argued: see
 * `LevellingStalenessNoiseTest`.
 *
 * AND `taskProgressChanged` SEPARATELY, because it is a separate event and is NOT contained in
 * `taskPropertiesChanged` (`TaskImpl.kt`, `ProgressEventSender`). Progress decides `frozen` and
 * the remaining duration in `LevellingAdapter.kt` — leaving it out would be a missed alarm, not
 * a saved false one.
 *
 * THE FEEDBACK IS REAL, and it is the reason for [runNotifier]. Writing a levelling result back
 * fires one `taskScheduleChanged` and one `taskPropertiesChanged` per moved task. Without a guard
 * the levelling's own writes would set the mark again the instant it was cleared, and the mark
 * would be permanently on for exactly the plans that have just been levelled — the worst possible
 * failure for a message meant to say "something needs doing".
 *
 * TWO THINGS STAND AGAINST IT, AND THEY ARE NOT EQUAL. Measured on 02.09.2026 by mis-wiring each
 * in turn and running the tests:
 *  1. [markStale] returns early while `runNotifier.isRunning`. Same idiom as
 *     `EffortDrivenTrigger.isRunning` and `SchedulerImpl.isRunning`. THIS ONE IS LOAD-BEARING.
 *     Removing it turns three tests red, among them a case the ordering cannot reach at all: a
 *     SECOND listener on the run event, registered after this one, runs after the mark has been
 *     cleared, and anything it changes in the plan sets the mark again.
 *  2. The clearing hangs off `LevellingRunNotifier`, which calls its listeners after the write-back
 *     has finished. FOR THIS MARK THAT IS REDUNDANT — measured: with guard 1 in place and the
 *     notifier deliberately re-wired to report BEFORE the write-back, all 26 tests stayed green.
 *     It is kept because it costs nothing here, because it is the guarantee the notifier gives
 *     every other consumer, and because it is the half that still catches the common case if
 *     somebody later removes the guard by accident.
 * The mis-wired output is in the report of 02.09.2026.
 */
class LevellingStaleness(private val runNotifier: LevellingRunNotifier = levellingRunNotifier) {
  private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

  /**
   * True when something has changed since the last levelling run or since the plan was loaded.
   *
   * THREADING: task and resource events arrive on the Swing event thread. A JavaFX display reads
   * this from the FX thread. Volatile keeps the reader from seeing a stale value; it is not a
   * lock and is not meant to be one — nothing here does read-modify-write across threads.
   */
  @Volatile
  var isStale: Boolean = false
    private set

  /** Cleared whenever a levelling run ends — that is the only thing that makes the plan current. */
  private val onLevellingRun: (LevellingRunFinished) -> Unit = { clear() }

  init {
    runNotifier.addListener(onLevellingRun)
  }

  /** For a test, and for a project that is being torn down. */
  fun detach() {
    runNotifier.removeListener(onLevellingRun)
    listeners.clear()
  }

  /** Called when [isStale] changes value. Never called when it merely gets set to what it was. */
  fun addListener(listener: (Boolean) -> Unit) {
    listeners.add(listener)
  }

  fun removeListener(listener: (Boolean) -> Unit) {
    listeners.remove(listener)
  }

  /**
   * The plan changed. Ignored while a levelling run is in progress: those events are the run's
   * own write-back, and treating them as user changes is the feedback loop described above.
   */
  fun markStale() {
    if (runNotifier.isRunning) {
      return
    }
    set(true)
  }

  /** The plan and the calculation agree again. */
  fun clear() = set(false)

  private fun set(value: Boolean) {
    if (isStale == value) {
      return
    }
    isStale = value
    // A listener that throws must not keep the others from hearing, and must not propagate back
    // into whatever model change happened to fire the event. Same reasoning as in
    // LevellingRunNotifier.runAndReport.
    listeners.forEach { listener ->
      try {
        listener(value)
      } catch (e: Exception) {
        GPLogger.log(e)
      }
    }
  }

  /**
   * Register with `taskManager.addTaskListener(...)` (`TaskManager.java`, public).
   *
   * ALL TEN EVENTS SET THE MARK, except `taskModelReset`, which CLEARS it. Reset means a different
   * plan — either a file was opened or the project was closed. The task events fired while the
   * file is being parsed arrive BEFORE it: `TaskManagerImpl.projectOpened()` fires the reset after
   * the parse, so a freshly opened file does not start life with the mark already on. Tested.
   */
  val taskListener: TaskListener = object : TaskListener {
    override fun taskScheduleChanged(e: TaskScheduleEvent) = markStale()
    override fun dependencyAdded(e: TaskDependencyEvent) = markStale()
    override fun dependencyRemoved(e: TaskDependencyEvent) = markStale()
    override fun dependencyChanged(e: TaskDependencyEvent) = markStale()
    override fun taskAdded(e: TaskHierarchyEvent) = markStale()
    override fun taskRemoved(e: TaskHierarchyEvent) = markStale()
    override fun taskMoved(e: TaskHierarchyEvent) = markStale()
    override fun taskPropertiesChanged(e: TaskPropertyEvent) = markStale()
    override fun taskProgressChanged(e: TaskPropertyEvent) = markStale()
    override fun taskModelReset() = clear()
  }

  /**
   * Register with `humanResourceManager.addView(...)` (`HumanResourceManager.java`, public).
   *
   * `resourceModelReset` clears for the same reason `taskModelReset` does. `resourceAdded` sets
   * the mark although a brand new person carries no assignment yet and therefore cannot change
   * the result — it is kept because the capacity pools are built from
   * `resourceManager.resources` and a person with a broken hours schedule turns the whole run
   * into an error message (`capacityProblems`). One event more, and it is not one a user
   * produces while typing.
   */
  val resourceView: ResourceView = object : ResourceView {
    override fun resourceAdded(event: ResourceEvent) = markStale()
    override fun resourcesRemoved(event: ResourceEvent) = markStale()
    override fun resourceChanged(e: ResourceEvent) = markStale()
    override fun resourceAssignmentsChanged(e: ResourceEvent) = markStale()
    override fun resourceStructureChanged() = markStale()
    override fun resourceModelReset() = clear()
  }

  /** Register with `calendar.addListener(...)` (`GPCalendar.java`, public). */
  val calendarListener: GPCalendarListener = GPCalendarListener { markStale() }
}
