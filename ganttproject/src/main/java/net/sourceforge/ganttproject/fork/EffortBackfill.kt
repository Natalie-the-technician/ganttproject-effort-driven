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

import kotlin.math.ceil

/**
 * Derives effort from the existing duration and proposes assignments.
 *
 * WHAT FOR: the effort-driven calculation and capacity levelling need both -- an effort on the
 * Task and an assignment to a person. The plan this fork was developed against has 226 Tasks,
 * **zero** assignments and no effort column; entering all of that by hand is a day's work. This
 * derivation takes it off one's hands.
 *
 * THE SAFETY PROPERTY, and it is the reason why the derivation is chosen exactly this way:
 *
 *     effort := duration x hours per day
 *     the back calculation gives   ceil(effort / hours per day) = duration
 *
 * After filling in, the plan therefore looks **exactly as it did before**. The filling in itself
 * moves nothing. What moves things afterwards is capacity levelling -- and that is triggered
 * separately and with a preview. A tool that changed dates secretly while filling in would be
 * exactly the kind of silent change this fork has already found in several places.
 *
 * Pure calculation, no GanttProject types: checkable without a running program that way.
 */

/** A Task as the derivation sees it. */
data class BackfillTask(
  val id: String,
  val name: String,
  /** Duration in working days. */
  val durationDays: Int,
  /** Has children: then GanttProject derives the duration, and effort does not belong here. */
  val isContainer: Boolean = false,
  /** Milestone: duration zero, there is nothing to deliver. */
  val isMilestone: Boolean = false,
  /** Pure waiting time: it lasts, but costs no work. */
  val isWaitOnly: Boolean = false,
  /** Effort already entered. Is never overwritten. */
  val existingEffortHours: Double? = null,
  /** Number of existing assignments. */
  val assignmentCount: Int = 0
)

/** Why a Task was skipped. Is shown to the person, not swallowed. */
enum class BackfillSkip {
  CONTAINER,          // Gruppe: Dauer wird abgeleitet
  MILESTONE,          // Meilenstein: nichts zu leisten
  WAIT_ONLY,          // reine Wartezeit: dauert, ist aber keine Arbeit
  ALREADY_HAS_EFFORT, // schon gepflegt, bleibt unangetastet
  NO_DURATION         // Dauer 0 oder kleiner: es gibt nichts abzuleiten
}

data class BackfillProposal(
  /** Task -> proposed effort in hours. */
  val effortHours: Map<String, Double>,
  /** Tasks that are to receive an assignment. */
  val assignTo: List<String>,
  /** Skipped Tasks with a reason. */
  val skipped: Map<String, BackfillSkip>
) {
  val changeCount: Int get() = effortHours.size + assignTo.size
}

/**
 * @param hoursPerDay daily rate of the person who is to be assigned.
 * @param alreadyAssignedKeepsIts when true, Tasks that already have an assignment do not get a
 * second one. That is the normal case: adding a second person would double the available hours
 * per day and thereby halve the duration -- a silent change to the plan.
 */
fun proposeBackfill(
  tasks: List<BackfillTask>,
  hoursPerDay: Double,
  alreadyAssignedKeepsIts: Boolean = true
): BackfillProposal {
  require(hoursPerDay > 0.0) { "Stunden pro Tag muss groesser als 0 sein, war $hoursPerDay" }

  val effort = mutableMapOf<String, Double>()
  val assign = mutableListOf<String>()
  val skipped = mutableMapOf<String, BackfillSkip>()

  for (task in tasks) {
    val reason = when {
      task.isContainer -> BackfillSkip.CONTAINER
      task.isMilestone -> BackfillSkip.MILESTONE
      task.isWaitOnly -> BackfillSkip.WAIT_ONLY
      task.existingEffortHours != null -> BackfillSkip.ALREADY_HAS_EFFORT
      task.durationDays <= 0 -> BackfillSkip.NO_DURATION
      else -> null
    }
    if (reason != null) {
      skipped[task.id] = reason
    } else {
      effort[task.id] = task.durationDays * hoursPerDay
    }
    // Assign even where the effort is already maintained: without an assignment capacity
    // levelling does not know the Task, and the effort alone achieves nothing.
    val zuordnen = !task.isContainer && !task.isMilestone && !task.isWaitOnly &&
      (task.assignmentCount == 0 || !alreadyAssignedKeepsIts)
    if (zuordnen) {
      assign.add(task.id)
    }
  }
  return BackfillProposal(effort, assign, skipped)
}

/**
 * The duration the effort-driven calculation would derive from an effort.
 *
 * Stands here so that the safety property from the class comment can be checked without pulling
 * up the actual calculation together with the project model. The formula is the same as in
 * [net.sourceforge.ganttproject.task.algorithm.EffortDrivenDurationAlgorithm].
 */
fun durationFromEffort(effortHours: Double, availableHoursPerDay: Double): Int =
  ceil(effortHours / availableHoursPerDay).toInt().coerceAtLeast(1)
