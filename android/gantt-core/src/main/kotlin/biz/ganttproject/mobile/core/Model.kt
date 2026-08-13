/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version. See LICENSE for details.
 */
package biz.ganttproject.mobile.core

import java.time.LocalDate

/**
 * A read-only snapshot of a GanttProject project.
 *
 * This model is deliberately *not* the thing you edit. Edits go through
 * [GanttDocument], which works directly on the XML tree. The reason is data
 * safety: anything the app does not model — views, column widths, calendars,
 * roles, baselines, notes, fields added by future GanttProject versions —
 * must survive a save untouched. Rebuilding a file from a model can only
 * ever write back what the model knows about.
 */
data class ProjectModel(
  val name: String,
  val company: String,
  val tasks: List<TaskNode>,
  val resources: List<ResourceNode>,
  val allocations: List<Allocation>,
  val roles: List<Role>,
  val calendar: WorkingCalendar
) {
  /** All tasks flattened in display order (depth-first). */
  val flatTasks: List<TaskNode> by lazy {
    val out = mutableListOf<TaskNode>()
    fun walk(list: List<TaskNode>) {
      list.forEach { out.add(it); walk(it.children) }
    }
    walk(tasks)
    out
  }

  /**
   * The tasks a collapsible outline actually shows: children of a folded
   * group are left out.
   *
   * Kept separate from [flatTasks] on purpose. Anything that reasons about
   * the project — matching time entries, resource load, totals — must see
   * every task regardless of what happens to be folded on screen.
   */
  val visibleTasks: List<TaskNode> by lazy {
    val out = mutableListOf<TaskNode>()
    fun walk(list: List<TaskNode>) {
      for (task in list) {
        out.add(task)
        if (task.isExpanded) walk(task.children)
      }
    }
    walk(tasks)
    out
  }

  fun task(id: String): TaskNode? = flatTasks.firstOrNull { it.id == id }

  fun resource(id: String): ResourceNode? = resources.firstOrNull { it.id == id }

  fun allocationsOfTask(taskId: String): List<Allocation> = allocations.filter { it.taskId == taskId }

  fun allocationsOfResource(resourceId: String): List<Allocation> =
    allocations.filter { it.resourceId == resourceId }

  /** Earliest start across all tasks, or today when the project is empty. */
  fun projectStart(): LocalDate = flatTasks.minOfOrNull { it.start } ?: LocalDate.now()

  /** Latest occupied working day across all tasks, inclusive. */
  fun projectEnd(): LocalDate =
    flatTasks.maxOfOrNull { calendar.lastWorkingDay(it.start, it.durationDays) } ?: projectStart()
}

data class TaskNode(
  val id: String,
  val uid: String?,
  val name: String,
  val start: LocalDate,
  /** Duration in WORKING days, exactly as GanttProject stores it. */
  val durationDays: Int,
  val completion: Int,
  val isMilestone: Boolean,
  val color: String?,
  val notes: String?,
  val depth: Int,
  val children: List<TaskNode>,
  /** Fork field `effort_hours` — planned effort in hours, or null if unset. */
  val effortHours: Double?,
  /** Fork field `effort_actual_hours` — hours actually spent, or null if unset. */
  val actualEffortHours: Double?,
  /** Fork field `toggl_match_keys` — confirmed time-entry match keys. */
  val togglMatchKeys: List<String>,
  /**
   * The `expand` attribute: whether this task's subtasks are shown.
   *
   * Stock GanttProject stores its own collapse state here, so a group folded
   * on the desktop arrives folded on the phone and vice versa. Missing means
   * expanded, which is how a task written by an older version behaves.
   */
  val isExpanded: Boolean = true
) {
  val isLeaf: Boolean get() = children.isEmpty()

  /**
   * GanttProject recomputes a summary task's completion from its children on
   * load, so writing it here would silently vanish. The UI disables the field
   * for such tasks rather than pretending the edit took effect.
   */
  val isCompletionEditable: Boolean get() = isLeaf && !isMilestone
}

data class ResourceNode(
  val id: String,
  val name: String,
  /** Persistent role id, e.g. "Default:1" or "3". */
  val roleId: String?,
  val mail: String?,
  val phone: String?,
  val standardRate: String?,
  /**
   * Fork field `hours_per_day`. `null` means "not set"; calculations then fall
   * back to [ForkProperties.DEFAULT_HOURS_PER_DAY] so that projects created
   * before this field existed keep working unchanged.
   */
  val hoursPerDay: Double?
) {
  val effectiveHoursPerDay: Double
    get() = hoursPerDay?.takeIf { it > 0.0 } ?: ForkProperties.DEFAULT_HOURS_PER_DAY
}

data class Allocation(
  val taskId: String,
  val resourceId: String,
  val function: String?,
  val responsible: Boolean,
  /** Assignment load in percent, as stored in the file format. */
  val load: Double
)

data class Role(val id: String, val name: String)
