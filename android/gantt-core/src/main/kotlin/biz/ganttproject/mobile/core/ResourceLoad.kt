/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import java.time.LocalDate

/**
 * Committed hours for one resource on one day.
 *
 * @param committedHours what all assignments together demand that day
 * @param capacityHours what the resource actually has that day
 */
data class DayLoad(
  val date: LocalDate,
  val committedHours: Double,
  val capacityHours: Double
) {
  val isOverloaded: Boolean get() = committedHours > capacityHours + HOURS_EPSILON

  /** Utilisation in percent; 100 means exactly full. */
  val utilisationPercent: Double
    get() = if (capacityHours <= 0.0) 0.0 else committedHours / capacityHours * 100.0
}

data class ResourceLoadReport(
  val resourceId: String,
  val days: List<DayLoad>
) {
  val overloadedDays: List<DayLoad> get() = days.filter { it.isOverloaded }
  val hasOverload: Boolean get() = overloadedDays.isNotEmpty()
  val peakUtilisationPercent: Double get() = days.maxOfOrNull { it.utilisationPercent } ?: 0.0
  val totalCommittedHours: Double get() = days.sumOf { it.committedHours }
}

/**
 * Computes day-by-day load for one resource.
 *
 * This is the thing stock GanttProject cannot tell you and the reason this
 * fork exists: in the desktop app an assignment's load percentage has no
 * effect on scheduling at all (upstream issue #83, open since 2013), so
 * twenty tasks can each claim 50% of the same person on the same day and
 * nothing anywhere says so.
 *
 * A task's demand is spread evenly across its working days, which is what
 * the file format expresses — it stores a load percentage, not a per-day
 * profile. Milestones (duration 0) demand nothing.
 *
 * @param window optional range to report on; defaults to the project span.
 *   Passing an unbounded range on a multi-year project builds one entry per
 *   working day, so the caller should narrow it for interactive use.
 */
fun computeResourceLoad(
  model: ProjectModel,
  resourceId: String,
  window: ClosedRange<LocalDate>? = null
): ResourceLoadReport {
  val resource = model.resource(resourceId)
    ?: return ResourceLoadReport(resourceId, emptyList())
  val capacity = resource.effectiveHoursPerDay

  // date -> hours demanded that day, accumulated across every assignment.
  val demandByDay = mutableMapOf<LocalDate, Double>()

  for (allocation in model.allocationsOfResource(resourceId)) {
    val task = model.task(allocation.taskId) ?: continue
    // Summary tasks carry their children's assignments in some files; counting
    // both would double the demand.
    if (!task.isLeaf) continue
    if (task.durationDays <= 0) continue

    val hoursPerDay = capacity * allocation.load / 100.0
    if (hoursPerDay <= 0.0) continue

    for (day in model.calendar.workingDaysOf(task.start, task.durationDays)) {
      if (window != null && day !in window) continue
      demandByDay[day] = (demandByDay[day] ?: 0.0) + hoursPerDay
    }
  }

  val days = demandByDay.entries
    .sortedBy { it.key }
    .map { (date, hours) -> DayLoad(date, hours, capacity) }

  return ResourceLoadReport(resourceId, days)
}

/** Load reports for every resource, cheapest way to find who is overbooked. */
fun computeAllResourceLoads(
  model: ProjectModel,
  window: ClosedRange<LocalDate>? = null
): List<ResourceLoadReport> =
  model.resources.map { computeResourceLoad(model, it.id, window) }
