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
 * WHY THERE IS MORE THAN ONE, decided on 20 August 2026. In this fork a task's DURATION is not an
 * input but a computed value: `EffortDrivenDurationAlgorithm` derives it from the effort and the
 * daily availability of the assigned people. Changing that availability changes every duration in
 * the plan without anything about the work having changed.
 *
 * Measured on a real plan on 20 August 2026: availability set from 8.0 to 1.8 hours a day, and
 * 163 of 163 tasks carrying an effort grew by the factor 8/1.8 = 4.44. A baseline taken before
 * that point then coloured 194 of 276 rows red — correctly computed and yet saying nothing,
 * because it did not compare two plans but two capacity assumptions.
 *
 * Three different questions follow from that, and they used to be mixed into one display:
 *
 *  - [DATES]     "Am I on schedule?"    -> end date now against end date in the baseline.
 *  - [EFFORT]    "Did this take more or less working time than I thought?"
 *                -> recorded hours against the original estimate.
 *  - [DURATIONS] "Does the work still take as long as it was planned to?"
 *                -> length now against length in the baseline, with the shift left out.
 *
 * The effort comparison needs NO baseline. Both of its numbers are already on the task:
 * [TASK_EFFORT_ORIGINAL] is written exactly once and never touched again, and the recorded hours
 * come from time tracking. That is a better anchor than a baseline — it survives saving a second
 * baseline.
 *
 * WHY [DURATIONS] WAS ADDED ON 25 August 2026, and why it is NOT a return to the withdrawn rule
 * of 17 August 2026: back then the duration comparison REPLACED the date one and was therefore
 * asked to answer a question it does not answer. Here it stands NEXT to it. Whoever wants to know
 * whether a task is late picks [DATES]; whoever wants to know whether it grew picks [DURATIONS];
 * the two are one entry apart in the same dropdown. That a merely shifted task shows no deviation
 * under [DURATIONS] is now a property of the view, not a defect in it.
 */
enum class ChartComparison {
  DATES,
  EFFORT,
  DURATIONS
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

/**
 * [ChartComparison.DURATIONS]: today's length against the length in the baseline.
 *
 * THE SHIFT IS DELIBERATELY LEFT OUT. A task that starts a week later but still takes five days
 * has not grown, and this view says so — it reports no deviation. [compareDates] reports one for
 * exactly the same task, and that is not a contradiction: the two answer different questions and
 * sit one entry apart in the same dropdown.
 *
 * WITHOUT A BASELINE THERE IS NO YARDSTICK, and unlike [compareDates] this does not stay silent
 * about it: it returns [ComparisonResult.NEUTRAL] so that a neutral band is drawn. Staying empty
 * would look exactly like "no deviation", and the two must not be confused. The effort view uses
 * the same device for "estimated but nothing recorded".
 *
 * The lengths are whole days, so no tolerance is needed here — unlike [compareEffort], which
 * compares fractional hours.
 *
 * THE SAME RULE EXISTS A SECOND TIME, AND THAT IS DELIBERATE. The `misc-fixes` branch, which
 * collects what is meant to go back to the original project, carries it as four inline lines
 * inside `renderBaseline` — there it REPLACES the date rule instead of standing beside it,
 * because the original has no `ChartComparison` to hang a third view off, and giving it one
 * would be a far larger proposal than the fix it needs.
 *
 * So: one rule, two shapes, two recipients. The two branches collide on those four lines, and
 * the collision is expected. WHOEVER MERGES THEM MUST NOT LET ONE SIDE WIN: the correct
 * resolution is that `misc-fixes`' rule becomes this function, and `renderBaseline` keeps the end
 * date. Reading the collision as a contradiction and picking a side would silently drop one of
 * the two views.
 */
fun compareDurations(baselineDays: Int?, currentDays: Int): ComparisonResult = when {
  baselineDays == null -> ComparisonResult.NEUTRAL
  baselineDays == currentDays -> ComparisonResult.NO_BAND
  currentDays > baselineDays -> ComparisonResult.MORE
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
 * Does this view need room for its band in the row even when NO baseline is selected?
 *
 * The row is eight pixels taller when a band can appear underneath the bar. The original only
 * ever drew one with a baseline, so a baseline was the whole condition. Two of this fork's views
 * draw without one — [ChartComparison.EFFORT] takes both its numbers from the task itself, and
 * [ChartComparison.DURATIONS] draws a neutral band to say that the yardstick is missing. Without
 * the extra room they draw into the row below.
 *
 * THE RULE LIVES HERE AND NOT IN `TaskRendererImpl2` so that it can be checked at all: the row
 * height itself is only obtainable from a fully built chart. Whoever adds a fourth view has to
 * answer this question in one place, and the test says which answer each view gives.
 */
fun ChartComparison.needsBandRoomWithoutBaseline(): Boolean = when (this) {
  ChartComparison.DATES -> false
  ChartComparison.EFFORT -> true
  ChartComparison.DURATIONS -> true
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
