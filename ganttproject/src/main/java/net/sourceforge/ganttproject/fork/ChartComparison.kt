/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
The comparison rules for the band underneath a task bar.

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

import java.util.Date
import kotlin.math.abs

/**
 * [Fork change] What the band underneath a task bar compares.
 *
 * WHY THERE ARE TWO, decided on 20 August 2026. In this fork a task's DURATION is not an input
 * but a computed value: `EffortDrivenDurationAlgorithm` derives it from the effort and the daily
 * availability of the assigned people. Changing that availability changes every duration in the
 * plan without anything about the work having changed.
 *
 * Measured on a real plan on 20 August 2026: availability set from 8.0 to 1.8 hours a day, and
 * 163 of 163 tasks carrying an effort grew by the factor 8/1.8 = 4.44. A baseline taken before
 * that point then coloured 194 of 276 rows red — correctly computed and yet saying nothing,
 * because it did not compare two plans but two capacity assumptions.
 *
 * Two different questions follow from that, and they used to be mixed into one display:
 *
 *  - [DATES]  "Am I on schedule?"       -> end date now against end date in the baseline.
 *  - [EFFORT] "Did this take more or less working time than I thought?"
 *             -> recorded hours against the original estimate.
 *
 * The effort comparison needs NO baseline. Both of its numbers are already on the task:
 * [TASK_EFFORT_ORIGINAL] is written exactly once and never touched again, and the recorded hours
 * come from time tracking. That is a better anchor than a baseline — it survives saving a second
 * baseline.
 */
enum class ChartComparison {
  DATES,
  EFFORT
}

/**
 * The outcome of a comparison. Deliberately not a colour: which colour this turns into is decided
 * by `StyledPainterImpl` from the three configurable values in `UIConfiguration`.
 */
enum class ComparisonResult {
  /** There is nothing to show. No band is drawn at all. */
  NO_BAND,

  /** There is a difference, but not one in either direction. The neutral colour. */
  NEUTRAL,

  /** Later, respectively more time spent. The "later" colour. */
  MORE,

  /** Earlier, respectively less time spent. The "earlier" colour. */
  LESS
}

/**
 * [ChartComparison.DATES]: today's end date against the end date in the baseline.
 *
 * This is the original GanttProject's rule, and here it is the right one again: the question is
 * "am I on schedule", and an end date answers that. The intermediate version of this fork
 * (comparing durations, introduced on 17 August 2026 in `misc-fixes`) tried to answer the effort
 * question with the same display; that does not work, see the enum comment above. The effort
 * question now has a view of its own.
 *
 * The same end date means on schedule — then the row stays empty.
 */
fun compareDates(baselineEnd: Date, currentEnd: Date): ComparisonResult = when {
  baselineEnd == currentEnd -> ComparisonResult.NO_BAND
  currentEnd.after(baselineEnd) -> ComparisonResult.MORE
  else -> ComparisonResult.LESS
}

/** Below this many hours two efforts count as equal. One minute. */
private const val HOURS_TOLERANCE = 1.0 / 60.0

/**
 * [ChartComparison.EFFORT]: the recorded hours against the original estimate.
 *
 * The comparison is against the ORIGINAL estimate, not against today's. The reason is already
 * stated at [TASK_EFFORT_ORIGINAL]: whoever revises an estimate and then compares against the
 * revised number will never see a deviation again — it disappears at the very moment one notices
 * it.
 *
 * The cases:
 *  - no original estimate -> there is no yardstick, so no band
 *  - an estimate but nothing recorded -> NEUTRAL. That is a statement of its own ("no time booked
 *    here yet") and deliberately not the same as "on target".
 *  - the same number of hours (to within a minute) -> no band
 */
fun compareEffort(originalHours: Double?, recordedHours: Double?): ComparisonResult {
  if (originalHours == null || originalHours <= 0.0) {
    return ComparisonResult.NO_BAND
  }
  if (recordedHours == null || recordedHours <= 0.0) {
    return ComparisonResult.NEUTRAL
  }
  if (abs(recordedHours - originalHours) < HOURS_TOLERANCE) {
    return ComparisonResult.NO_BAND
  }
  return if (recordedHours > originalHours) ComparisonResult.MORE else ComparisonResult.LESS
}

/**
 * The style the painter evaluates, or null for the neutral colour.
 * The names are the original's and appear verbatim in `StyledPainterImpl`.
 */
fun ComparisonResult.styleName(): String? = when (this) {
  ComparisonResult.MORE -> "later"
  ComparisonResult.LESS -> "earlier"
  else -> null
}
