/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Extra fields this app understands, on top of stock GanttProject.
 *
 * They are stored as GanttProject **custom properties**, never as new XML
 * attributes. This is not a style preference — it is required for round-trip
 * safety. GanttProject's `TaskSaver` writes a hard-coded list of attributes,
 * so an unknown attribute is silently dropped the next time the desktop app
 * saves the file: data gone, no error. Custom properties, by contrast, are a
 * first-class desktop feature and are written back verbatim.
 *
 * The practical consequence: a file can travel app -> desktop -> app without
 * losing these values, and stock GanttProject shows them as ordinary
 * (if inert) columns.
 */
object ForkProperties {
  /** Planned effort in hours, per task. */
  const val TASK_EFFORT_HOURS = "effort_hours"

  /** Hours actually spent, per task. */
  const val TASK_EFFORT_ACTUAL_HOURS = "effort_actual_hours"

  /** Learned match keys for the time-tracking import, per task. */
  const val TASK_TOGGL_MATCH_KEYS = "toggl_match_keys"

  /** Working hours per day, per resource. */
  const val RESOURCE_HOURS_PER_DAY = "hours_per_day"

  /**
   * Fallback when a resource carries no `hours_per_day`, so that projects
   * created before this field existed keep calculating sensibly.
   */
  const val DEFAULT_HOURS_PER_DAY = 8.0

  /**
   * Separator inside `toggl_match_keys`. Deliberately not a comma: time-entry
   * descriptions contain commas all the time, and a single key must not be
   * torn into three by one.
   */
  const val MATCH_KEY_SEPARATOR = "|"

  fun encodeMatchKeys(keys: List<String>): String =
    keys.map { it.replace(MATCH_KEY_SEPARATOR, " ") }
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .distinct()
      .joinToString(MATCH_KEY_SEPARATOR)

  fun decodeMatchKeys(raw: String?): List<String> =
    raw?.split(MATCH_KEY_SEPARATOR)
      ?.map { it.trim() }
      ?.filter { it.isNotEmpty() }
      ?.distinct()
      ?: emptyList()
}

/**
 * Reads an hours value from user input.
 *
 * Accepts both the decimal comma ("7,5", common in German locales) and the
 * decimal point. Returns `null` for anything unusable so the caller can keep
 * the previous value instead of overwriting it with garbage.
 */
fun parseEffortInput(text: String?): Double? {
  val trimmed = text?.trim()?.replace(',', '.') ?: return null
  if (trimmed.isEmpty()) return null
  val value = trimmed.toDoubleOrNull() ?: return null
  if (value.isNaN() || value.isInfinite()) return null
  if (value < 0.0) return null
  return value
}

/**
 * Formats an hours value for display, trimming pointless decimals:
 * 8.0 -> "8", 7.5 -> "7.5", null -> an em dash.
 *
 * @param decimalSeparator pass ',' for locales that use the decimal comma
 */
fun formatHours(value: Double?, decimalSeparator: Char = '.'): String {
  if (value == null) return "—"
  val rounded = Math.round(value * 100.0) / 100.0
  val text = if (abs(rounded - Math.rint(rounded)) < 1e-9) {
    rounded.toLong().toString()
  } else {
    String.format(Locale.ROOT, "%.2f", rounded).trimEnd('0').trimEnd('.')
  }
  return if (decimalSeparator == '.') text else text.replace('.', decimalSeparator)
}

/**
 * The arithmetic core of effort-driven scheduling, kept free of any project
 * or UI types so it can be tested on its own.
 *
 * Rounds up because a partially used day is still an occupied day, and never
 * returns less than one day because a task spanning zero days is not a thing.
 *
 * @throws IllegalArgumentException if either argument is not positive; both
 *   would otherwise produce a silently nonsensical duration.
 */
fun computeDurationDays(effortHours: Double, availableHoursPerDay: Double): Int {
  require(effortHours > 0.0) { "effort must be > 0 but was $effortHours" }
  require(availableHoursPerDay > 0.0) {
    "availability must be > 0 but was $availableHoursPerDay"
  }
  return maxOf(1, ceil(effortHours / availableHoursPerDay).toInt())
}

/**
 * Hours per day available to a task: the sum over all assigned resources,
 * each weighted by its assignment load in percent.
 *
 * @param resourceById resolves a resource id; unknown ids contribute nothing
 *   rather than throwing, because a file may reference a deleted resource.
 */
fun availableHoursPerDay(
  allocations: List<Allocation>,
  resourceById: (String) -> ResourceNode?
): Double =
  allocations.sumOf { allocation ->
    val resource = resourceById(allocation.resourceId) ?: return@sumOf 0.0
    resource.effectiveHoursPerDay * allocation.load / 100.0
  }
