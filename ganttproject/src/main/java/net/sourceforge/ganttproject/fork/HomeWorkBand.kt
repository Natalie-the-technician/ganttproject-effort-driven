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

import biz.ganttproject.core.chart.scene.CapacityHeatmapSceneBuilder
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.resource.HumanResource
import java.time.LocalDate
import java.util.Date

/**
 * [fork change] B4 — WHAT A HOME-WORKING DAY LOOKS LIKE, and where that is decided.
 *
 * B1 records where a person works, B2 whether a Task can be done from there, B3 what the two do to
 * the schedule. None of it is VISIBLE. Natalie's sentence has two halves and B3 only answered the
 * first: „das wäre wie ein Tag Urlaub zu handhaben, NUR IM KALENDER SOLL ES ANDERST DARGESTELLT
 * WERDEN, andere Farbe oder so."
 *
 * ═══ WHICH VIEW, AND WHY THERE IS ONLY ONE ═══
 *
 * The resource load chart — the „Ressourcen" tab. Not chosen for taste: it is the ONLY view in the
 * program that draws ONE PERSON'S absence at all. Measured rather than assumed, by asking who reads
 * the days off of a person:
 *
 *     grep -rn "getDaysOff()" ganttproject/src/main biz.ganttproject.core/src/main
 *
 * gives eight call sites, and exactly one of them is a chart: `LoadDistribution.processDaysOff`,
 * whose `-1` loads become the striped „away" band of the resource chart. The other seven are the
 * person dialog, the saver, the loader, the merger and the copy constructor. THE GANTT CHART NEVER
 * DRAWS A PERSON'S HOLIDAY — its grey columns are the PROJECT calendar, the same for everybody.
 *
 * So the question „where does Natalie see a holiday today" has one answer, and the instruction
 * „then that is where she will look for the home office" points at one view. Nothing had to be
 * chosen between.
 *
 * ═══ A BAND OF ITS OWN, NOT A SECOND COLOUR FOR THE DAY-OFF BAND ═══
 *
 * The day-off band is `load == -1f` and painter key `"dayoff"`. It would have been one line to give
 * that painter a second colour depending on the source. It is a separate load value
 * ([CapacityHeatmapSceneBuilder.HOME_WORK_LOAD]) and a separate painter key instead, so that
 * nothing downstream can accidentally treat the two as one: `LoadDistribution` never learns about
 * home working at all, and the four-character edit that B3's `HomeWorkPlanning.kt` warns about
 * stays as impossible here as it is there.
 *
 * ═══ A DAY THAT IS BOTH IS AN ABSENCE ═══
 *
 * Somebody can have a Friday in their weekly home-office pattern AND a holiday on that same Friday.
 * They are then NOT working from home, they are away, and [homeWorkBands] therefore drops the day
 * from the band. Two reasons, and the second is the one that decides it:
 *
 *  * TRUTHFULNESS. Nobody works from home while on holiday. The person is absent.
 *  * READABILITY. Both bands are translucent fills over the same rectangle. Drawn on top of each
 *    other they mix into a THIRD colour that is neither of the two and stands for nothing — the
 *    exact opposite of what this package is for.
 *
 * This is a display decision and it is taken HERE. It changes nothing about the schedule: B3 asks
 * [HomeOffice.worksFromHome] and that answer is unaffected by anything in this file.
 */

/**
 * One run of consecutive home-working days, half-open like everything else in this fork:
 * [start] is in it, [endExclusive] is not.
 */
data class HomeWorkBand(val start: LocalDate, val endExclusive: LocalDate) {
  /** How many days this band covers. Never zero — [homeWorkBands] does not produce empty bands. */
  val days: Int get() = (endExclusive.toEpochDay() - start.toEpochDay()).toInt()
}

/**
 * The home-working runs of one person inside `[from, toExclusive)`, days off taken out.
 *
 * RUNS AND NOT DAYS, and that is a requirement rather than tidiness. The style of a band is chosen
 * downstream by comparing the ACCUMULATED load against [CapacityHeatmapSceneBuilder.HOME_WORK_LOAD]
 * (see `calcLoadDistribution`), so two bands covering the same day would accumulate to -4 and fall
 * through to the ordinary load styles — a home-working day would be painted as an overload. The
 * runs this returns are disjoint and in ascending order, which is what makes that impossible.
 *
 * NOTHING ENTERED RETURNS AN EMPTY LIST WITHOUT WALKING A SINGLE DAY. That is the cheap half of the
 * guard „a plan without home working looks exactly as it did before": no band, no rectangle, no
 * change to the canvas. The expensive half is the check that says so.
 *
 * @param isDayOff the person's absences. A day it answers `true` for is left out of every band.
 */
fun homeWorkBands(
  homeOffice: HomeOffice,
  from: LocalDate,
  toExclusive: LocalDate,
  isDayOff: (LocalDate) -> Boolean = { false }
): List<HomeWorkBand> {
  if (homeOffice.isEmpty || !from.isBefore(toExclusive)) {
    return emptyList()
  }
  val bands = mutableListOf<HomeWorkBand>()
  var runStart: LocalDate? = null
  var day = from
  while (day.isBefore(toExclusive)) {
    val atHome = homeOffice.worksFromHome(day) && !isDayOff(day)
    if (atHome && runStart == null) {
      runStart = day
    } else if (!atHome && runStart != null) {
      bands.add(HomeWorkBand(runStart, day))
      runStart = null
    }
    day = day.plusDays(1)
  }
  if (runStart != null) {
    bands.add(HomeWorkBand(runStart, toExclusive))
  }
  return bands
}

/**
 * The home-working bands of this person as chart loads, ready for the capacity heatmap.
 *
 * BOUNDED BY WHAT IS ON SCREEN, and it has to be: a weekly pattern names a day every week for
 * ever, so „all home-working days of this person" is not a finite list. The visible range is what
 * the renderer knows and it is the only sensible bound — the same one the chart already uses to
 * throw away rectangles outside it.
 *
 * READS THE TWO PROPERTIES ONCE PER PERSON AND PER REPAINT, not once per day: the warning over
 * `HumanResource.worksFromHome` applies here and this is a paint path.
 */
fun HumanResource.homeWorkLoads(
  resourceProperties: CustomPropertyManager,
  from: Date,
  toExclusive: Date
): List<CapacityHeatmapSceneBuilder.Load> {
  val homeOffice = this.homeOffice(resourceProperties).homeOffice
  if (homeOffice.isEmpty) {
    return emptyList()
  }
  val daysOff = this.daysOffRanges()
  val bands = homeWorkBands(
    homeOffice, from.toModelLocalDate(), toExclusive.toModelLocalDate()
  ) { day -> daysOff.any { (start, end) -> !day.isBefore(start) && day.isBefore(end) } }
  return bands.map {
    CapacityHeatmapSceneBuilder.Load(
      it.start.toModelDate().time,
      it.endExclusive.toModelDate().time,
      CapacityHeatmapSceneBuilder.HOME_WORK_LOAD,
      null
    )
  }
}
