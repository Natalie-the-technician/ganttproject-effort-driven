/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
Triggers the duration calculation when something changes about the resources.

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
package net.sourceforge.ganttproject.task.algorithm

import net.sourceforge.ganttproject.resource.ResourceEvent
import net.sourceforge.ganttproject.resource.ResourceView
import net.sourceforge.ganttproject.task.TaskManager

/**
 * Runs the effort-driven duration algorithm whenever something happens to the resources that can
 * change how many hours a day a task has available. Register it with
 * `humanResourceManager.addView(...)`.
 *
 * Without this, [EffortDrivenDurationAlgorithm] would sit in the [AlgorithmCollection] and never
 * run: nothing in stock GanttProject reacts to a resource change by recalculating anything.
 *
 * WHICH EVENTS, AND WHY: both of them matter, and it is easy to get this wrong.
 *  - [resourceAssignmentsChanged] — an assignment was added, removed, or its load changed.
 *  - [resourceChanged] — fired by `HumanResource.setValue(...)`, which is what happens when the
 *    daily hours of a resource are edited. This is the headline case ("set the resource to
 *    4 h/day and the task becomes 5 days"), and it is NOT an assignment event.
 *  - [resourcesRemoved] — a task assigned to two people loses one of them, so the remaining
 *    availability halves.
 *
 * NOT here: the effort itself. It lives on the TASK, and a task change arrives while the task's
 * own mutator is still committing — `MutatorReentered.commit()` does nothing, so a duration
 * written at that moment is discarded. Editing the effort therefore triggers the recalculation
 * from `GanttDialogProperties`, right after the mutator has been committed.
 *
 * ORDER: the duration algorithm runs first and the scheduler second. The algorithm sets durations,
 * the scheduler then propagates the dates through the dependency graph. The other way round the
 * propagated dates would be computed from durations that are about to change.
 */
class EffortDrivenTrigger(private val taskManager: TaskManager) : ResourceView {
  /**
   * Guards against feeding itself: the algorithm changes tasks, which fires task events, which may
   * end up back here. Same idiom as `SchedulerImpl.isRunning`.
   */
  private var isRunning = false

  override fun resourceAssignmentsChanged(e: ResourceEvent?) = recalculate()

  override fun resourceChanged(e: ResourceEvent?) = recalculate()

  override fun resourcesRemoved(event: ResourceEvent?) = recalculate()

  /** A resource that was just added carries no assignments yet, so no duration can have changed. */
  override fun resourceAdded(event: ResourceEvent?) {}

  override fun resourceStructureChanged() {}

  override fun resourceModelReset() {}

  private fun recalculate() {
    if (isRunning) {
      return
    }
    isRunning = true
    try {
      val algorithms = taskManager.algorithmCollection
      algorithms.effortDrivenDurationAlgorithm.run()
      algorithms.scheduler.run()
    } finally {
      isRunning = false
    }
  }
}
