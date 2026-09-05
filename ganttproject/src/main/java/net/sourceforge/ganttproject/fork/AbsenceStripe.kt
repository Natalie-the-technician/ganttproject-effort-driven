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

import biz.ganttproject.core.chart.grid.Offset
import biz.ganttproject.core.chart.grid.OffsetLookup
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.task.ResourceAssignment
import net.sourceforge.ganttproject.task.Task
import java.time.LocalDate
import java.util.Date

/**
 * [fork change] THE HOLIDAY STRIPE ON A TASK BAR — one day at a time.
 *
 * Natalie: „Ich hatte gedacht den vorgangsbalken mit einem Urlaubsstreifen. Er muss ja nur zeigen
 * das an dem Tag jemand nicht da ist. Also nicht der ganze Vorgang soll den streifen bekommen,
 * sondern nur der teil, wenn jemand an Tag 3 von 5 fehlt soll nur Tag 3 den streifen haben."
 *
 * PER DAY AND NOT PER TASK. That sentence is the whole specification and it is also the whole
 * difficulty: a task bar is drawn as one rectangle per ACTIVITY, and an activity is many days long.
 * The stripe therefore cannot be a property of the bar; it has to be its own set of rectangles laid
 * over it, and their edges have to fall exactly on day boundaries. [absenceStripeSpans] is that
 * arithmetic, and it goes through the very same [OffsetLookup] the bar itself is measured with, so
 * the stripe cannot drift a pixel away from the day column it names.
 *
 * WHAT THE CHART SHOWED BEFORE: NOTHING. The grey columns of the Gantt chart are the PROJECT
 * calendar, the same for everybody; the holiday of a PERSON was drawn in exactly one place in the
 * whole program, the „absent" band of the resource chart (`LoadDistribution.processDaysOff`).
 * Measured in package B4 on 05.09.2026 by asking who reads `getDaysOff()` at all — eight callers,
 * one of them a chart. So nothing here replaces anything; it is a new sentence on the screen.
 *
 * HOME WORKING IS NOT AN ABSENCE AND MUST NEVER REACH THIS FILE. B3 and B4 spent a package each on
 * that boundary: somebody working from home IS working, they merely cannot do the tasks that need
 * them on the premises. This file reads [HumanResource.daysOffRanges] and NOTHING else, so the home
 * office — which lives in two custom columns of the person, not in `daysOff` — cannot get in by
 * accident. `HomeOffice.kt` warns about the same thing from the other side.
 */

/** The style name of an absence stripe. Read by `StyledPainterImpl` to pick its painter. */
const val STYLE_ABSENCE = "task.absence"

/**
 * One run of consecutive days on which somebody assigned to the task is away.
 *
 * HALF-OPEN, like every interval in this fork: [start] is in it, [endExclusive] is not. That is not
 * a choice made here — `DaysOffDuration.kt` lists the four independent places in the program that
 * already read a vacation that way, and `GanttDaysOff.isADayOff`, the one that disagrees, has no
 * caller anywhere (finding F24).
 */
data class AbsenceRun(val start: LocalDate, val endExclusive: LocalDate) {
  /** How many days this run covers. Never zero — [mergeAbsenceRuns] drops empty runs. */
  val days: Int get() = (endExclusive.toEpochDay() - start.toEpochDay()).toInt()
}

/** A stripe as the chart needs it: a half-open pixel span inside one bar rectangle. */
data class StripeSpan(val leftX: Int, val rightX: Int) {
  val width: Int get() = rightX - leftX
}

/**
 * Whose absence a task bar shows.
 *
 * EVERY ASSIGNMENT, and this is deliberately WIDER than the set the fork uses when it CALCULATES.
 * `WorkWeekEffect.isInvolvedInWorkWeek` takes `contributesEffort || isBlocking` and gives a careful
 * reason for leaving out the third kind of assignment — the person who books no hours and whose
 * absence stops nothing, who is on the task „to be named in a report". Letting that person shorten
 * a plan would be a bystander silently changing the schedule.
 *
 * THE SAME PERSON MUST NOT BE HIDDEN HERE, and the reason is that this is not a calculation. This
 * stripe changes no date, no duration and no cost; it draws a mark that says „somebody assigned to
 * this is away today". Natalie's sentence is „Er muss ja nur zeigen das an dem Tag jemand nicht da
 * ist" — jemand, anybody. And the second half of her sentence settles it: „Wer da fehlt kann man
 * dann in der detailansicht schauen wenn man den Vorgang öffnet." The detail view lists EVERY
 * assignment. A stripe built on a narrower set would mark a day the detail view cannot explain, or
 * worse, leave a day unmarked whose absence the detail view does list — and the reader would have
 * to know which of the two axes was ticked to make sense of the picture.
 *
 * SO THE ANSWER HERE IS NOT THE ANSWER PACKAGE A2 GAVE, and that is the point rather than an
 * oversight: for a SUM the narrow set is right, for a SIGN the wide one is. The measurement behind
 * it is in the report of 05.09.2026 — the set was narrowed twice on purpose and the red checks
 * counted, exactly as A2 did it.
 *
 * IT IS A PROPERTY AND NOT AN INLINE `true` so that narrowing it stays a one-line change and so
 * that the next person can find, by its name, where the decision was taken.
 */
@Suppress("unused")
val ResourceAssignment.isShownOnTheTaskBar: Boolean get() = true

/**
 * Sorts, merges and drops empties: turns any set of day ranges into disjoint ascending runs.
 *
 * RUNS AND NOT DAYS, for two independent reasons:
 *
 *  * TWO PEOPLE AWAY ON THE SAME DAY ARE ONE STRIPE. The stripe says „somebody is missing", a
 *    sentence that is not truer twice. Two rectangles on the same pixels would also paint their
 *    translucent fill twice and produce a colour that stands for nothing — the same trap B4 avoided
 *    in `homeWorkBands`.
 *  * NO SEAMS. Neighbouring days merged into one rectangle have no border drawn between them, so a
 *    week of holiday reads as one block rather than as five boxes.
 *
 * TOUCHING RANGES ARE MERGED, not only overlapping ones: `[Mon, Wed)` and `[Wed, Fri)` are Monday
 * to Thursday with nothing in between, and drawing a border at Wednesday would invent a gap.
 */
fun mergeAbsenceRuns(ranges: List<Pair<LocalDate, LocalDate>>): List<AbsenceRun> {
  val sorted = ranges.filter { it.first.isBefore(it.second) }.sortedBy { it.first }
  if (sorted.isEmpty()) {
    return emptyList()
  }
  val merged = mutableListOf<AbsenceRun>()
  var start = sorted[0].first
  var end = sorted[0].second
  sorted.drop(1).forEach { (nextStart, nextEnd) ->
    if (nextStart.isAfter(end)) {
      merged.add(AbsenceRun(start, end))
      start = nextStart
      end = nextEnd
    } else if (nextEnd.isAfter(end)) {
      end = nextEnd
    }
  }
  merged.add(AbsenceRun(start, end))
  return merged
}

/**
 * The days on which somebody assigned to this task is away, as disjoint ascending runs.
 *
 * NOT CLIPPED TO THE TASK. Clipping happens where the drawing happens ([absenceStripeSpans]),
 * against the ACTIVITY rather than against the task, because a bar is split into activities and
 * only the activity knows which of its days carry work at all. Doing it twice would be two places
 * to get the exclusive end wrong.
 */
fun Task.absenceRuns(): List<AbsenceRun> =
  mergeAbsenceRuns(
    this.assignments
      .filter { it.isShownOnTheTaskBar }
      .flatMap { it.resource?.daysOffRanges() ?: emptyList() }
  )

/**
 * The pixel spans an absence covers inside ONE bar rectangle.
 *
 * @param runs the task's absence runs, from [absenceRuns].
 * @param segmentStart, @param segmentEndExclusive the days this bar rectangle stands for.
 * @param barLeftX, @param barRightX the rectangle as it was actually drawn. The result is clamped
 *        to it, so a rounding difference can never let a stripe stick out over the end of the bar.
 * @param offsets the day columns of the chart — THE SAME LIST the bar was measured against.
 *
 * THE END IS EXCLUSIVE ON BOTH SIDES OF THE COMPARISON. `!from.before(segmentEnd)` rather than
 * `from.after(segmentEnd)`: a holiday that begins exactly where this segment ends belongs to the
 * next one, and a holiday that ends exactly where this segment begins touches nothing here. Those
 * two are the boundary cases the checks in `UrlaubsstreifenTest` are built around, and getting
 * either of them wrong shifts every stripe by a day without ever crashing.
 */
fun absenceStripeSpans(
  runs: List<AbsenceRun>,
  segmentStart: Date,
  segmentEndExclusive: Date,
  barLeftX: Int,
  barRightX: Int,
  offsets: List<Offset>
): List<StripeSpan> {
  if (runs.isEmpty() || offsets.isEmpty() || barRightX <= barLeftX) {
    return emptyList()
  }
  val lookup = OffsetLookup()
  val spans = mutableListOf<StripeSpan>()
  runs.forEach { run ->
    val from = run.start.toModelDate()
    val toExclusive = run.endExclusive.toModelDate()
    if (from.before(segmentEndExclusive) && toExclusive.after(segmentStart)) {
      val clippedFrom = if (from.before(segmentStart)) segmentStart else from
      val clippedTo = if (toExclusive.after(segmentEndExclusive)) segmentEndExclusive else toExclusive
      val bounds = lookup.getBounds(clippedFrom, clippedTo, offsets)
      val leftX = maxOf(bounds[0], barLeftX)
      val rightX = minOf(bounds[1], barRightX)
      if (rightX > leftX) {
        spans.add(StripeSpan(leftX, rightX))
      }
    }
  }
  return spans
}

/**
 * [fork change] THE OTHER HALF OF NATALIE'S SENTENCE: „Wer da fehlt kann man dann in der
 * detailansicht schauen wenn man den Vorgang öffnet."
 *
 * The days of THIS task on which THIS person is away, written out for the task dialog. Empty text
 * when they are there throughout — an empty cell rather than a „–", so that a column of mostly
 * present people stays quiet and the eye finds the few that are not.
 *
 * CLIPPED TO THE TASK, unlike [absenceRuns]. The dialog is opened for one task and the question it
 * answers is about that task; a three-week holiday of which one day falls into a two-day task
 * would otherwise be reported in full and read as if the task were dead for three weeks.
 *
 * THE DATE IS SHOWN INCLUSIVELY EVEN THOUGH IT IS STORED EXCLUSIVELY. `[9., 12.)` is written
 * „9. – 11.", because that is what a person reads as three days off. This is the ONE place in the
 * fork where the exclusive end is converted, and it is converted here rather than anywhere upstream
 * on purpose: everything that computes keeps the half-open form (see the head comment of
 * `DaysOffDuration.kt`), and only the last step before a human eye turns it round. A single day is
 * written once and not as a range from itself to itself.
 *
 * @param formatDay how to write one day. Handed in rather than chosen here so that this function
 *        can be checked without a language being loaded, and so that the dialog can use the very
 *        date format the rest of the program shows.
 */
fun absenceSummaryForTask(
  task: Task,
  resource: HumanResource,
  formatDay: (LocalDate) -> String
): String {
  val taskStart = task.start?.time?.toModelLocalDate() ?: return ""
  val taskEndExclusive = task.end?.time?.toModelLocalDate() ?: return ""
  if (!taskStart.isBefore(taskEndExclusive)) {
    return ""
  }
  return mergeAbsenceRuns(resource.daysOffRanges())
    .mapNotNull { run ->
      val from = maxOf(run.start, taskStart)
      val toExclusive = minOf(run.endExclusive, taskEndExclusive)
      if (from.isBefore(toExclusive)) AbsenceRun(from, toExclusive) else null
    }
    .joinToString(", ") { run ->
      val last = run.endExclusive.minusDays(1)
      if (run.start == last) formatDay(run.start)
      else "${formatDay(run.start)} - ${formatDay(last)}"
    }
}
