/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
Which groups the task table may open when a selection arrives from somewhere else.

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

import biz.ganttproject.task.ancestors
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskContainmentHierarchyFacade

/**
 * [Fork change] S8. THE CLICK THAT UNDID THE ROW IT WAS AIMED AT.
 *
 * ═══ THE LOOP ═══
 *
 * The table opens every ancestor of a selection that did not come from the table itself
 * (`TaskTable.initSelectionListeners`). That rule is right and old: if something else says "this
 * task", the table has to be able to show it, and a task inside a collapsed group has no row.
 *
 * It closes a loop, though, as soon as a collapsed group draws the bars of its children on its own
 * row. The bar a user presses on such a row belongs to a CHILD -- that is the whole point of
 * [seriesBars], and it is what makes dragging and progress find the right date. So pressing it
 * selects the child, the child's ancestor is the collapsed group, the group opens, and the one row
 * is twelve rows again. Not only a deliberate click does this: `MouseListenerImpl.mousePressed`
 * sets the selection BEFORE it decides what the gesture is, so the first millimetre of a drag
 * undoes the row as well.
 *
 * ═══ WHY THE SOURCE DECIDES AND NOT THE GROUP ALONE ═══
 *
 * "Never open a collapsed recurrence group" would be shorter and it would be wrong. Measured on
 * 14.09.2026, exactly three kinds of source reach that rule (`git grep` for `setSelectedTasks` and
 * `fireSelectionChanged`, main sources only):
 *
 *   * the table itself             -- the rule already skips it, `source != this@TaskTable`
 *   * the Gantt chart              -- `MouseListenerImpl`, three call sites, source is the
 *                                     [net.sourceforge.ganttproject.chart.gantt.GanttChartController]
 *   * the search box               -- `TaskSearchService.select`, source is the service
 *   * `null`                       -- `clear()`, `TaskLinkAction`, `TaskUnlinkAction`,
 *                                     `TaskTable` on `taskMoved`; the selection is unchanged or
 *                                     empty, so the rule re-opens only what is already open
 *
 * THE SEARCH BOX MUST KEEP OPENING THE GROUP. Someone who types the name of a single date and hits
 * the result is asking to be taken there; answering with a row that stays shut answers a different
 * question. The chart is the one source that is asking for nothing of the kind -- it is reporting
 * where the pointer went, on a row the user has already said they want shut.
 *
 * So the source is asked, and it is asked about a task rather than about a mode: only the object
 * that drew the row knows whether that row is a merged one. When the setting of S10 is off, the
 * chart answers `false` to everything and this file changes nothing at all.
 */
interface MergedSeriesRows {
  /**
   * True when [task] is a group whose row currently draws the bars of its children instead of its
   * own -- that is, when opening it would undo exactly the picture the user is looking at.
   */
  fun drawsSeriesOnOneRow(task: Task): Boolean
}

/**
 * The ancestors of [selection] that the table may open, outermost first.
 *
 * OUTERMOST FIRST is not cosmetic and it is kept from the original: `ancestors` walks upwards, and
 * opening an inner group before its container would set `isExpanded` on a `TreeItem` that is not
 * in the tree yet.
 *
 * [source] is the object that caused the selection. A source that knows nothing about merged rows
 * -- the search box, `null`, anything added later -- leaves the list untouched, which is the
 * behaviour every caller had before this fork.
 */
fun ancestorsToOpen(
  selection: List<Task>,
  hierarchy: TaskContainmentHierarchyFacade,
  source: Any?
): List<Task> {
  val outermostFirst = ancestors(selection, hierarchy).reversed()
  val chart = source as? MergedSeriesRows ?: return outermostFirst
  return outermostFirst.filterNot { chart.drawsSeriesOnOneRow(it) }
}
