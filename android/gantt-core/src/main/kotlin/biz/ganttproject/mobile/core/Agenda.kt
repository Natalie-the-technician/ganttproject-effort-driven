/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import java.time.LocalDate

/**
 * One line of the agenda: a task that wants attention, with the little bit of
 * context needed to render it without consulting the project again.
 */
data class AgendaItem(
  val task: TaskNode,
  /** Last working day the task occupies, inclusive. */
  val endDate: LocalDate,
  /** Should have finished by now and has not. */
  val isOverdue: Boolean,
  /** Running right now: started, not finished, not yet past its end. */
  val isRunning: Boolean,
  /** Starts later, inside the requested window. */
  val isUpcoming: Boolean
)

/**
 * The tasks worth showing on a home screen: everything unfinished that has
 * already started or starts within [withinDays].
 *
 * ## What is left out, and why
 *
 * - **Finished tasks.** An agenda is about what is left.
 * - **Summary tasks.** Their completion is derived from their children by
 *   GanttProject, so ticking one off would achieve nothing; the children are
 *   listed instead.
 * - **Milestones** are kept: a date that has to be hit is exactly the kind of
 *   thing a home screen should nag about.
 *
 * There is deliberately **no lower bound**. A task that should have finished
 * last month and did not is the most important line on the list, not the
 * least — filtering by "recent" would hide precisely the work that slipped.
 *
 * @param today the reference day, passed in rather than read from the clock
 *   so the result is reproducible and testable
 * @param withinDays how far ahead to look; 0 means "already started only"
 */
fun agenda(
  model: ProjectModel,
  today: LocalDate,
  withinDays: Int
): List<AgendaItem> {
  val horizon = today.plusDays(withinDays.coerceAtLeast(0).toLong())
  return model.flatTasks
    .asSequence()
    .filter { it.isLeaf }
    .filter { it.completion < 100 }
    .filter { !it.start.isAfter(horizon) }
    .map { task ->
      val end = model.calendar.lastWorkingDay(task.start, task.durationDays)
      AgendaItem(
        task = task,
        endDate = end,
        isOverdue = end.isBefore(today),
        isRunning = !task.start.isAfter(today) && !end.isBefore(today),
        isUpcoming = task.start.isAfter(today)
      )
    }
    // Overdue first, then by start date: the ordering answers "what is on
    // fire" before "what is next", which is the question a glance asks.
    .sortedWith(compareByDescending<AgendaItem> { it.isOverdue }.thenBy { it.task.start }
      .thenBy { it.task.id })
    .toList()
}

/** How many days ahead the agenda looks. Offered in the widget settings. */
object AgendaWindow {
  const val DEFAULT_DAYS = 14
  val CHOICES = listOf(0, 3, 7, 14, 30, 90)
}
