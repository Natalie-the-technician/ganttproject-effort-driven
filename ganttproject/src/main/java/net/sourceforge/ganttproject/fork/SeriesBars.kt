/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
The bars a collapsed recurrence group draws in place of its own spanning bar.

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
import net.sourceforge.ganttproject.chart.ChartModel
import net.sourceforge.ganttproject.chart.gantt.ITaskSceneTaskImpl
import net.sourceforge.ganttproject.chart.gantt.TaskSceneTaskActivity
import net.sourceforge.ganttproject.task.Task

/**
 * [Fork change] TWELVE DATES IN ONE ROW — the bars of a collapsed recurrence group.
 *
 * ═══ WHAT IS WRONG WITH THE PICTURE TODAY ═══
 *
 * A series of twelve monthly dates is already ONE row when its group is collapsed; that part has
 * worked since 17.08.2026 and nothing here improves on it. What is wrong is what that one row
 * DRAWS. A summary task takes its start from the earliest child and its end from the latest
 * (`AdjustTaskBoundsAlgorithm.recalculateSupertaskSchedule`), and `TaskImpl.recalculateActivities`
 * then fills its activities across that WHOLE span. So the collapsed row of a year-long monthly
 * series is one uninterrupted bracket from January to December: twelve days of work drawn as
 * twelve months. Whoever skims the plan reads continuous load where twelve points stand.
 *
 * That single sentence is the whole case for this file, and it is the one thing collapsing cannot
 * do for itself.
 *
 * ═══ WHY THIS IS DRAWING AND NOTHING ELSE ═══
 *
 * The obvious-looking alternative — one Task with twelve gaps of its own — is a dead end, and
 * `RecurrenceAdapter.recurrenceGroupMark` already says so at the place it matters. Measured again
 * on 11.09.2026: `Task.getActivities()` is not storage but a DERIVED value.
 * `TaskImpl.recalculateActivities` asks the calendar for the activities between start and end and
 * then WRITES THE DURATION BACK from the sum of the working ones, while `calculateEnd()` derives
 * the end from start plus duration. Start, duration, end and activities are one mutually
 * determining set: feed twelve scattered dates in as activities and the duration afterwards is
 * twelve DAYS and the end lies in January. On top of that `<task start= duration=>` has no room
 * for a second stretch, so the file could not carry it either.
 *
 * So the twelve dates stay twelve Tasks in the model and in the file, exactly as they are today,
 * and only the chart draws them on one row. Nothing here writes anything. The Android app, which
 * reads `start` and `duration` per task, is untouched — the file after this change is byte for
 * byte the file before it.
 *
 * ═══ WHY THE OWNER OF EACH BAR IS THE DATE AND NOT THE GROUP ═══
 *
 * [ITaskSceneTaskImpl.activities] hands each activity the scene task it was built from as its
 * owner, and the renderer reads colour, shape, "critical" and milestone off that owner rather than
 * off the row. Hit-testing on the chart is geometric — `ChartModelImpl.findTaskBoundaryItem` takes
 * the rectangle under the pointer and reads the owner out of it, and `git grep getRowHeight()`
 * finds no division anywhere, only multiplications — so a click on the seventh bar reaches the
 * seventh date by itself, with nothing added here.
 *
 * It also matters for what is NOT built yet: the progress bar per date and the absence stripe per
 * bar (both left to the next package) hang off exactly this owner.
 *
 * ═══ WHAT THIS FILE DELIBERATELY DOES NOT DO YET ═══
 *
 * Progress is still spread across the row rather than computed per date, absence stripes are not
 * drawn on such a row at all, the comparison band still belongs to the group, and the first click
 * on a bar still expands the group again (`TaskTable.kt` expands every ancestor of a selection
 * that did not come from the table). All four are known and none of them is repaired here. That
 * is why [seriesBars] is asked only when the setting is on, and why the setting is OFF by default.
 */

/**
 * The bars to draw on the row of [group], or an empty list when that row should draw its own bar
 * as it always has.
 *
 * THREE CONDITIONS, ALL NECESSARY:
 *
 *  * [group] must be a recurrence group. Recognised by the marker `"<sourceTaskId>@Serie"` in the
 *    `recurrence_of` column — see [isRecurrenceGroup], which exists precisely because the group
 *    and its dates carry their markers in the SAME column and telling them apart by "marker
 *    present" once cost four red checks.
 *  * It must be COLLAPSED, AND THAT IS ASKED OF THE ROW LIST, NOT OF `Task.expand`. Expanded,
 *    every date is a row of its own; drawing them here as well would draw each of them twice.
 *
 *    CORRECTED ON 14.09.2026, AFTER SWITCHING THE FEATURE ON IN THE RUNNING PROGRAM AND SEEING
 *    NOTHING HAPPEN. The first version asked `group.expand`, and in the running program that field
 *    is frozen at whatever the file was loaded with: the JavaFX task table never calls `setExpand`,
 *    it keeps the truth in its own `TreeCollapseView`. Measured in the container on 14.09.2026 with
 *    a plan whose file says `expand="false"` and whose table shows the group shut:
 *
 *        seriesBars id=2 recurrenceOf=4711@Serie isGroup=true expand=TRUE kinder=12
 *
 *    -- so this method returned an empty list every single time and the whole package did nothing,
 *    whatever the setting said. Every check passed because every check sets `expand` by hand.
 *
 *    THE FORK HAD ALREADY WRITTEN THIS DOWN. `HiddenTaskGap` says it in as many words:
 *    "`Task.expand` CANNOT BE ASKED ... the model field is frozen at whatever was loaded from the
 *    file while the truth lives in the UI map `TreeCollapseView`, which the chart renderer has no
 *    way to reach." That file asks THE ROW LIST instead, and so does this one now: a group is
 *    collapsed when none of its children is a row. The row list is what the table hands the chart
 *    before every repaint, so it cannot go stale.
 *
 *    WHAT IT GUESSES, AND WHICH WAY. A filter that removes every child of an EXPANDED group looks
 *    from here exactly like a collapsed group, and this row will then draw the children's bars.
 *    `HiddenTaskGap` meets the same ambiguity and guesses the same way. Here the cost of the guess
 *    is a row that shows more than the filter asked for, not less -- and what it shows are the
 *    dates of the very group the filter left standing.
 *  * It must have children. A group without any has nothing to show and keeps its own bar.
 *
 * EVERY CHILD, NOT ONLY THE MARKED DATES. A hand-made task dragged into a recurrence group is
 * hidden while the group is collapsed exactly as the dates are, and this row is the only place it
 * could still be seen. Filtering by the occurrence marker would make it disappear from the chart
 * altogether — silently, because nothing else would change. The marker decides what a GROUP is,
 * not what belongs on its row.
 *
 * DIRECT CHILDREN ONLY. A child that is itself a summary task brings its own spanning bar along,
 * which is the same thing its own row would show; walking deeper would mix two levels of the plan
 * into one row without anything saying which is which. Recurrence groups have no such child in
 * practice — `RecurrenceAdapter` creates single dates — so this is a boundary and not a case.
 *
 * SORTED BY START, AND THE CHILDREN ARE SORTED, NOT THE ACTIVITIES. Document order is chronological
 * for a series as `RecurrenceAdapter` builds it, but nothing enforces that afterwards and the order
 * of this list is read: the label renderer writes the row's left label at the FIRST rectangle and
 * the right one at the LAST. Sorting the children keeps each child's own activities in their
 * original order, which `isFirst`/`isLast` depend on.
 */
fun seriesBars(
  group: Task,
  model: ChartModel,
  manager: CustomPropertyManager,
  isRow: (Task) -> Boolean
): List<TaskSceneTaskActivity> {
  if (!group.isRecurrenceGroup(manager)) {
    return emptyList()
  }
  val children = model.taskManager.taskHierarchy.getNestedTasks(group)
  if (children.isEmpty()) {
    return emptyList()
  }
  // THE CHEAP QUESTIONS FIRST, and not for tidiness: [isRow] walks the row list, so asking it before
  // the marker would put a scan of the whole table on every ordinary row of every repaint.
  if (children.any(isRow)) {
    return emptyList()
  }
  return children
    .sortedBy { it.start?.time }
    .flatMap { ITaskSceneTaskImpl(it, model).activities }
}
