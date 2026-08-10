/*
Copyright 2026 Noctuvo

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

import biz.ganttproject.customproperty.CustomPropertyClass
import biz.ganttproject.customproperty.CustomPropertyDefinition
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.task.Task
import kotlin.math.ceil

/**
 * Effort-driven scheduling: derives task duration from the effort and the daily availability
 * of the assigned resources.
 *
 *     duration [days] = ceil(effort [hours] / available hours per day)
 *     available hours per day = sum over assignments of (resource hours per day * load / 100)
 *
 * Example: 20 hours of effort at 2 h/day gives 10 days; changing the resource to 4 h/day
 * gives 5 days.
 *
 * COMPATIBILITY: effort and daily hours are stored as *custom properties*, not as new
 * attributes of the file format. Stock GanttProject reads and writes custom properties as a
 * regular feature, so a file can travel fork -> stock -> fork without losing the data.
 * A new XML attribute would be silently dropped by the stock saver, which writes a fixed
 * list of known attributes only.
 *
 * This algorithm does NOT do resource levelling. It computes the duration of each task on its
 * own; it does not notice that twenty tasks all claim the same person at the same time.
 */
object EffortDrivenProperties {
  /** Effort of a task, in hours. Custom property on tasks. */
  const val TASK_EFFORT_HOURS = "effort_hours"

  /** Daily availability of a resource, in hours. Custom property on resources. */
  const val RESOURCE_HOURS_PER_DAY = "hours_per_day"

  /** Used when a resource carries no explicit value. */
  const val DEFAULT_HOURS_PER_DAY = 8.0

  fun findOrCreateTaskEffort(manager: CustomPropertyManager): CustomPropertyDefinition =
    manager.getCustomPropertyDefinition(TASK_EFFORT_HOURS)
      ?: manager.createDefinition(TASK_EFFORT_HOURS, CustomPropertyClass.DOUBLE.iD, "Effort (h)", null)

  fun findOrCreateResourceHours(manager: CustomPropertyManager): CustomPropertyDefinition =
    manager.getCustomPropertyDefinition(RESOURCE_HOURS_PER_DAY)
      ?: manager.createDefinition(RESOURCE_HOURS_PER_DAY, CustomPropertyClass.DOUBLE.iD, "Hours per day", null)
}

/**
 * Reads the effort of a task, or null when the task has none. A task without effort keeps
 * whatever duration it has: this feature is opt-in, per task.
 */
fun Task.effortHours(manager: CustomPropertyManager): Double? {
  val def = manager.getCustomPropertyDefinition(EffortDrivenProperties.TASK_EFFORT_HOURS) ?: return null
  val raw = this.customValues.getValue(def) ?: return null
  val value = (raw as? Number)?.toDouble() ?: raw.toString().toDoubleOrNull() ?: return null
  return if (value > 0.0) value else null
}

/**
 * Daily hours of a resource. Falls back to [EffortDrivenProperties.DEFAULT_HOURS_PER_DAY] when
 * the resource carries no value, so that an untouched project keeps working.
 */
fun HumanResource.hoursPerDay(manager: CustomPropertyManager): Double {
  val def = manager.getCustomPropertyDefinition(EffortDrivenProperties.RESOURCE_HOURS_PER_DAY)
    ?: return EffortDrivenProperties.DEFAULT_HOURS_PER_DAY
  val raw = this.getCustomField(def) ?: return EffortDrivenProperties.DEFAULT_HOURS_PER_DAY
  val value = (raw as? Number)?.toDouble() ?: raw.toString().toDoubleOrNull()
    ?: return EffortDrivenProperties.DEFAULT_HOURS_PER_DAY
  return if (value > 0.0) value else EffortDrivenProperties.DEFAULT_HOURS_PER_DAY
}

/**
 * Hours per day available to a task through its assignments, weighted by the assignment load.
 * Returns 0.0 when nothing is assigned.
 */
fun Task.availableHoursPerDay(resourceProperties: CustomPropertyManager): Double =
  this.assignments.sumOf { assignment ->
    val resource = assignment.resource as? HumanResource ?: return@sumOf 0.0
    resource.hoursPerDay(resourceProperties) * assignment.load / 100.0
  }

/**
 * Duration in days for the given effort and availability, at least one day.
 * Kept separate from the model so it can be tested on its own.
 */
fun computeDurationDays(effortHours: Double, availableHoursPerDay: Double): Int {
  require(effortHours > 0.0) { "effort must be positive, was $effortHours" }
  require(availableHoursPerDay > 0.0) { "availability must be positive, was $availableHoursPerDay" }
  return maxOf(1, ceil(effortHours / availableHoursPerDay).toInt())
}
