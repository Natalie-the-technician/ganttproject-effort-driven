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

import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.task.Task

/**
 * [fork change] B3 — WHERE HOME WORKING MEETS THE SCHEDULE, and where it must not.
 *
 * B1 records WHERE a person works ([HomeOffice]). B2 records whether a Task can be done from there
 * ([HomeWorkMark]). This is the package that lets the two act on the plan, and it is small on
 * purpose: almost all of it is one condition read in two places and one channel handed through.
 *
 * ═══ THE RULE, WITH BOTH OF ITS CONDITIONS ═══
 *
 * A day D is no good for Task V when
 *
 *   (a) V is marked ON_SITE — somebody said it cannot be done from home — AND
 *   (b) one of the people in V's BLOCKING set works from home on D.
 *
 * Both, or it is not the rule. Condition (a) is what keeps the ordinary Task — the one nobody has
 * marked, which is every Task in every plan that exists today — untouched by anybody's home
 * office. Condition (b) is what keeps it to the people who have to be there: Natalie's sentence is
 * „kommt drauf an ob die Person als zwingend notwendig eingetragen ist … wenn sie es nicht ist,
 * kann der Rest ohne sie weiterarbeiten".
 *
 * ═══ THE SENTENCE THIS WHOLE PACKAGE IS BUILT AROUND ═══
 *
 * A HOME-WORKING DAY IS NOT A DAY OFF. The person is at work. Their hours exist, their day counts,
 * and the plan may spend them — just not on a Task that needs somebody on the premises.
 *
 * There is exactly one place in this fork where the two could be confused, and it is worth naming
 * so that nobody has to find it twice: [net.sourceforge.ganttproject.resource.HumanResource]'s
 * `daysOffRanges()` in `DaysOffDuration.kt`. It is the COMMON READER. The same list it returns
 * feeds three consumers with three different meanings —
 *
 *   * `Share.daysOff` — this person's hours are gone from the day,
 *   * `EffortInputs.blockingDaysOff` — the whole day of the Task is dead,
 *   * `availabilityTest` — the day is no good for the window search.
 *
 * — so putting home-office ranges into it is a four-character edit that compiles, moves all three
 * at once, and turns a home-working day into a holiday. B3 therefore never touches it. It builds
 * SIBLINGS: [EffortInputs.blockingHomeOffice] beside `blockingDaysOff`, and `presenceTest` beside
 * `availabilityTest`, each with the condition (a) that the day-off channel cannot express.
 *
 * `HomeWorkNotAHolidayTest` is the check that stands on this, and it is the one that would go red
 * for the four-character edit.
 *
 * ═══ WHAT B3 DELIBERATELY DOES NOT TOUCH ═══
 *
 *  * `WorkWeekWorkingDays` — the working week is the GRID; home working is an exception inside it,
 *    like a public holiday. Put into the grid, the day would vanish from the Task and the Task
 *    would not get longer. It has to get longer.
 *  * `Share.bestHoursPerDay` — an upper bound whose only job is to answer „this cannot be done at
 *    all" without walking. A more accurate bound can only turn a computable duration into `null`;
 *    a generous one costs time and nothing else. The same reasoning axis A already carries there.
 *  * `LoadDistribution.processDaysOff` — the `-1` load that paints the „unavailable" stripes in
 *    the resource chart. Home working shown there would be optically a holiday, which is the one
 *    thing Natalie asked NOT to happen („nur im kalender soll es anderst dargestellt werden").
 *    That is package B4's, and it needs a load of its own rather than this one.
 *  * The `<vacation>` element. Home working rides on `<custom-property>` (B1), and it has to: a
 *    foreign GanttProject reads `<vacation>` as a holiday, so a home-working day stored there
 *    would be a free day outside this fork — the deadly mistake in its most durable form.
 */

/**
 * Does this Task need somebody ON THE PREMISES? — condition (a) of the rule above.
 *
 * THE NEGATION OF THE FOLD AND NOT A SECOND READING OF THE COLUMN. [HomeWorkMark] has three states
 * and two of them — „nobody has decided" and „can be done from home" — answer this question the
 * same way. Where that is decided is [HomeWorkMark.allowsHomeWorkingDay], in one line, and this is
 * the one place the schedule asks it from. Comparing the enum against `ON_SITE` here would be a
 * second copy of a decision that exists so as not to be copied.
 *
 * WHY IT IS PHRASED THE OTHER WAY ROUND from `mayRunOnHomeWorkingDay`. Both call sites — the
 * duration in `DaysOffDuration.kt` and the conversion in `LevellingAdapter.kt` — want the
 * restricting case, and `if (!mayRun…)` at each of them reads as a double negative in code whose
 * whole difficulty is keeping straight which way each condition points.
 *
 * FALSE FOR EVERY TASK IN EVERY PLAN WRITTEN BEFORE THIS FORK, because the column is not in those
 * files and a missing column is NOT_DECIDED. That is what makes the two conditions of the rule a
 * short circuit rather than a cost.
 */
fun Task.requiresPresence(taskProperties: CustomPropertyManager): Boolean =
  !this.mayRunOnHomeWorkingDay(taskProperties)
