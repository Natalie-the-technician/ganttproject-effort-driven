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

/**
 * Estimated against needed: how good one's own estimates are.
 *
 * THE DESCRIPTION OF THE PURPOSE GIVEN ON 17.08.2026, and it determines the whole structure:
 *
 *   "One should only see it when something takes more effort than planned. If I need 15 hours
 *    instead of 9, for instance, it does not matter over what period the hours were spread."
 *
 * THREE THINGS FOLLOW FROM THAT:
 *
 * 1. **HOURS are compared, not dates.** In the future a date baseline shows one thing above all:
 *    that everything has shifted as soon as the daily rate changes. That is not a deviation but
 *    replanning. The deviation is the hours.
 * 2. **The period does not matter.** Whether the 15 hours accrued on one day or over three weeks
 *    changes nothing about the estimate. This calculation therefore knows no date.
 * 3. **The comparison is against the ORIGINAL estimate.** Anyone who corrects an estimate
 *    afterwards otherwise compares against the corrected one and learns nothing more -- the
 *    deviation disappears at the moment it is noticed. That is what the "Original effort (h)"
 *    column is for, which is set exactly once and never touched again afterwards.
 *
 * Pure calculation, no GanttProject types.
 */

/** A Task as the evaluation sees it. */
data class EstimateRow(
  val id: String,
  val name: String,
  /** The original estimate in hours. */
  val originalHours: Double,
  /** Hours actually recorded. */
  val actualHours: Double,
  /** Completion in per cent. Only finished Tasks say anything about the estimate. */
  val completionPercent: Int
) {
  val isFinished: Boolean get() = completionPercent >= 100
  /** How much more was needed. 1.0 means: hit exactly. */
  val factor: Double get() = if (originalHours > 0.0) actualHours / originalHours else 0.0
  val extraHours: Double get() = actualHours - originalHours
}

data class EstimateReport(
  /** Finished Tasks with an estimate AND recorded hours -- the basis. */
  val finished: List<EstimateRow>,
  /** Finished ones that needed more than estimated, descending by extra effort. */
  val overruns: List<EstimateRow>,
  /** Begun ones that are ALREADY over the estimate -- a warning, not a verdict. */
  val runningOver: List<EstimateRow>,
  /** Sum estimated / sum needed over the finished ones. */
  val overallFactor: Double,
  /** Effort still outstanding according to the plan, in hours. */
  val remainingPlannedHours: Double
) {
  val hasBasis: Boolean get() = finished.isNotEmpty()
  /** The outstanding effort, extrapolated with the measured factor. */
  val remainingExpectedHours: Double get() = remainingPlannedHours * overallFactor
}

/**
 * @param rows all Tasks with an original estimate.
 * @param remainingPlannedHours planned effort of all Tasks not yet finished.
 *
 * THE OVERALL FACTOR IS FORMED FROM SUMS, not as the mean of the individual factors. Otherwise a
 * Task of half an hour counts as much as one of forty -- and a single small outlier ("20 minutes
 * estimated, 2 hours needed", factor 6) distorts the whole picture. Sums weight by what the
 * matter is about: working time.
 */
fun buildEstimateReport(
  rows: List<EstimateRow>,
  remainingPlannedHours: Double
): EstimateReport {
  val fertig = rows.filter { it.isFinished && it.originalHours > 0.0 && it.actualHours > 0.0 }
  val ueber = fertig.filter { it.extraHours > 0.0 }.sortedByDescending { it.extraHours }
  val laufendUeber = rows
    .filter { !it.isFinished && it.originalHours > 0.0 && it.actualHours > it.originalHours }
    .sortedByDescending { it.extraHours }
  val summeGeschaetzt = fertig.sumOf { it.originalHours }
  val summeGebraucht = fertig.sumOf { it.actualHours }
  val faktor = if (summeGeschaetzt > 0.0) summeGebraucht / summeGeschaetzt else 1.0
  return EstimateReport(fertig, ueber, laufendUeber, faktor, remainingPlannedHours)
}
