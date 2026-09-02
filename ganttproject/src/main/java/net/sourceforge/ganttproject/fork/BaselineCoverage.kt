/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
"Tasks are not in any baseline" — the comparison behind the second statement of the message.

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

import net.sourceforge.ganttproject.GanttPreviousState
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager

/**
 * What the display has to say about the baselines. Three states, and each of them is a different
 * sentence — which is the whole reason this is a type and not a number.
 */
sealed class BaselineGap {
  /** Nothing to say: every task has an entry somewhere, or the plan has no tasks at all. */
  object Covered : BaselineGap()

  /**
   * There is no baseline at all — the state of nearly every project. NOT the same thing as "every
   * task is missing", although it is technically true: the display answers it with a bare
   * statement and no button (Natalie's decision of 02.09.2026, point 2), because a demand that
   * cannot be met by pressing anything is the message that turns into wallpaper.
   *
   * [taskCount] is carried along so the statement can stay a statement without asking again.
   */
  data class NoBaseline(val taskCount: Int) : BaselineGap()

  /** [count] tasks have an entry in no baseline, and there is at least one baseline. */
  data class Missing(val count: Int) : BaselineGap()
}

/**
 * The ids every one of [baselines] knows, read ONCE per baseline.
 *
 * THIS IS WHERE THE WHOLE PRICE OF THE QUESTION SITS. `GanttPreviousState.load()`
 * (`GanttPreviousState.java:99-118`) builds a fresh `SAXParserFactory` and a fresh `SAXParser` on
 * every call and re-reads the temporary file from disk; there is no cache anywhere. Measured on
 * 02.09.2026 against the real plan of 276 tasks: one pass costs 2.3 ms over one baseline and
 * 29.0 ms over fifteen, of which the set comparison below is 0.058 ms. So the loop reads each
 * baseline exactly once, and the caller — [LevellingStalenessBar] — asks only when it switches
 * the message on, not on every event.
 *
 * A BASELINE THAT CANNOT BE READ CONTRIBUTES NOTHING AND BREAKS NOTHING. `load()` swallows its
 * exception and hands back null; the ids it would have carried are simply missing, which can make
 * a task look uncovered. Reporting one task too many is the harmless direction — throwing inside
 * a status bar is not.
 */
fun idsInBaselines(baselines: List<GanttPreviousState>): Set<Int> {
  val ids = HashSet<Int>()
  baselines.forEach { baseline ->
    baseline.load()?.forEach { ids.add(it.id) }
  }
  return ids
}

/**
 * The tasks of [taskManager] that have an entry in NO baseline of [baselines].
 *
 * "IN NO BASELINE" AND NOT "IN THE SELECTED ONE", and that is a decision, not a shortcut. The
 * selected baseline is a fleeting thing: `GanttChart.getBaseline()` is set in exactly one place
 * (`BaselineDialogAction.java:111/113`), is never saved, and is null after a file has been opened.
 * "The newest" does not exist either — a `<previous-tasks>` element carries no timestamp, so the
 * last entry of the list is insertion order and nothing more. The wording "in no baseline" needs
 * no choice at all, and with a single baseline it means exactly what one expects it to mean.
 *
 * THE MATCHING IS `entry.id == task.rowId`. `TaskImpl.getRowId()` returns `getTaskID()`
 * (`TaskImpl.java:203-205`), and `GanttPreviousState.createTasks` fills the entry's id from
 * `t.getTaskID()` as well (`:120-128`) — so the two sides are the same number by construction.
 * Checked once more on the real plan on 02.09.2026: 276 of 276 tasks matched.
 */
fun tasksNotInAnyBaseline(taskManager: TaskManager, baselines: List<GanttPreviousState>): List<Task> {
  if (baselines.isEmpty()) {
    return emptyList()
  }
  val known = idsInBaselines(baselines)
  return taskManager.tasks.filter { !known.contains(it.rowId) }
}

/**
 * The answer the display needs, in one call.
 *
 * ZERO BASELINES IS ITS OWN ANSWER — but only when there is something it could be about. A brand
 * new project has no baseline AND no task, and "no baseline yet" would then be a true statement
 * about nothing at all. It stays [BaselineGap.Covered] in that case, so a project nobody has typed
 * anything into shows nothing whatsoever.
 */
fun baselineGap(taskManager: TaskManager, baselines: List<GanttPreviousState>): BaselineGap {
  // `tasks` and not `taskCount`: getTaskCount() is the raw map size while getTasks() filters
  // deleted tasks out (TaskManagerImpl.java:124-130/454-456). Counting one way and filtering the
  // other would let a deleted task make the message appear.
  val taskCount = taskManager.tasks.size
  if (taskCount == 0) {
    return BaselineGap.Covered
  }
  if (baselines.isEmpty()) {
    return BaselineGap.NoBaseline(taskCount)
  }
  val missing = tasksNotInAnyBaseline(taskManager, baselines)
  return if (missing.isEmpty()) BaselineGap.Covered else BaselineGap.Missing(missing.size)
}
