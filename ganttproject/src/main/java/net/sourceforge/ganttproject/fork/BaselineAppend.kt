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

import net.sourceforge.ganttproject.GPLogger
import net.sourceforge.ganttproject.GanttPreviousState
import net.sourceforge.ganttproject.GanttPreviousStateTask
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import java.io.IOException

/**
 * Taking a single task into an EXISTING baseline.
 *
 * WHAT FOR: a task that did not exist when the baseline was taken has no entry in it.
 * `GanttChartSceneBuilder.renderBaseline` (line 228-231) looks for an entry whose id matches the
 * row and draws a band only if it finds one -- a task without an entry stays invisible in the
 * comparison. The case this is built for: plan a task in the FUTURE, let the scheduler place it,
 * and take it into the existing baseline afterwards.
 *
 * WHY IT HAS TO GO THIS WAY AROUND. A baseline is written AS A WHOLE. `GanttPreviousState` has
 * exactly one setter, `setName`; the task list arrives in the constructor, is `private final` and
 * has no way in from outside. There is no `addTask`. So appending is a rewrite -- but a
 * LOSS-FREE one, and that is the point: the old values do not come from the current plan but
 * from the baseline's own file. `HistorySaver.saveBaseline` (line 45) calls `nextState.load()`;
 * the current plan becomes the source only when a baseline is CREATED
 * (`GanttPreviousState.createTasks`, called from `BaselineDialogAction` and `LevellingActions`).
 * The path here is therefore:
 *
 *     source.load()  ->  the old entries + one new one  ->  a new GanttPreviousState,
 *     init(), saveFile()
 *
 * READ VIA load(), NOT VIA THE FIELD. `load()` re-parses the temporary file and hands back a
 * fresh, mutable `ArrayList` (`PreviousStateTasksTagHandler.getTasks()`). The list a baseline was
 * CONSTRUCTED with is a different matter: `BaselineSerializer.kt:37` builds it with Kotlin's
 * `.toList()`, which is read-only to its static type. `myTasks` is private and final anyway, so
 * `load()` is the only way in -- and it is the same way the saver and the chart take, which is
 * why what is appended here is exactly what ends up in the file.
 *
 * WHAT THIS DOES NOT DECIDE, ON PURPOSE. Appending does not touch `IGanttProject.getBaselines()`.
 * It hands back a finished, saved baseline and leaves the caller one line of choice:
 *
 *     replace:    baselines[baselines.indexOf(source)] = result.baseline; source.remove()
 *     alongside:  baselines.add(result.baseline)          // with a name of the caller's choosing
 *
 * The [name] parameter exists for exactly that second flavour. Whether a supplemented baseline
 * takes the old one's place or steps beside it with a timestamp in its name is an open question
 * -- and this file must not answer it.
 *
 * NOT IN HERE: no window, no question, no decision about when the user is asked, no listener.
 */
sealed class BaselineAppendResult {
  /**
   * A new baseline, already `init()`ed and `saveFile()`d, holding the old entries unchanged plus
   * one. It is NOT in any baseline list yet -- see the note on the cut above.
   */
  data class Appended(val baseline: GanttPreviousState,
                      val entries: List<GanttPreviousStateTask>) : BaselineAppendResult()

  /**
   * The task already has an entry, and nothing was done -- see the reasoning at
   * [appendToBaseline]. The caller can tell the user; the source baseline is untouched.
   */
  data class AlreadyPresent(val taskId: Int) : BaselineAppendResult()

  /**
   * The temporary file could not be read or written. Nothing was changed. Half a baseline would
   * be worse than none: an entry silently missing looks exactly like a task that is on plan.
   */
  data class Failed(val reason: String, val cause: IOException?) : BaselineAppendResult()
}

/**
 * The entry a baseline holds for [task] -- built exactly the way `GanttPreviousState.createTasks`
 * builds it, so that an entry appended later cannot be told apart from one that was there from
 * the start. (Nor could it be: a `<previous-task>` carries no timestamp and no origin, only the
 * five fields below.)
 *
 * THE DURATION IS WORKING DAYS, an int, not hours and not calendar days. `Task.getDuration()`
 * returns a `TimeDuration` in the project's default time unit, and that is DAY
 * (`GPTimeUnitStack.getDefaultTimeUnit`); `getLength()` gives the count in that unit. Three days
 * from a Friday end on the Wednesday -- five calendar days, entry value 3.
 */
fun baselineEntryFor(task: Task, taskManager: TaskManager): GanttPreviousStateTask =
  GanttPreviousStateTask(
    task.taskID,
    task.start,
    task.duration.length,
    task.isMilestone,
    taskManager.taskHierarchy.hasNestedTasks(task))

/**
 * Builds a NEW baseline from [source] plus [task] and hands it back. [source] is not touched, and
 * neither is any baseline list -- the caller decides whether the result replaces [source] or
 * steps beside it.
 *
 * @param name the new baseline's name; by default the source's, which is the "replace" flavour.
 *   Pass something else (a timestamp, say) for the "alongside" flavour.
 *
 * A TASK ALREADY IN THE BASELINE IS SKIPPED, not overwritten ([BaselineAppendResult.AlreadyPresent]).
 * A baseline records what was PLANNED at the time it was taken; writing today's values over an
 * existing entry would destroy precisely the thing it exists to hold, and nothing in the file
 * would show it had happened. Overwriting is a different operation -- "update the baseline" --
 * and it should be asked for by that name, not fall out of appending. Skipping is also the
 * reversible choice: nothing is lost, and the caller can still offer the other thing.
 */
fun appendToBaseline(source: GanttPreviousState, task: Task, taskManager: TaskManager,
                     name: String = source.name): BaselineAppendResult {
  // load() and not the field: it re-parses the baseline's temporary file, which is the same
  // source the saver reads from. A read failure comes back as null -- load() swallows the
  // exception and returns null (GanttPreviousState.java:99-118).
  val existing = source.load()
    ?: return BaselineAppendResult.Failed(
      "the baseline '${source.name}' could not be read back", null)

  existing.firstOrNull { it.id == task.taskID }?.let {
    return BaselineAppendResult.AlreadyPresent(task.taskID)
  }

  // Our own copy. load() already hands out a fresh list, so this changes nothing today -- but it
  // states that the source's entries are read, never written.
  val entries = ArrayList(existing)
  entries.add(baselineEntryFor(task, taskManager))

  return try {
    // init() AND saveFile() are both mandatory and the constructor does not show it: a baseline
    // holds its entries in a temporary file, not in memory. Without the two calls myFile is null
    // and the thing would be visible in the list, invisible in the chart, and an exception when
    // saving the project.
    val appended = GanttPreviousState(name, entries).also {
      it.init()
      it.saveFile()
    }
    BaselineAppendResult.Appended(appended, entries)
  } catch (e: IOException) {
    // Over the logger, not System.err -- GanttProject redirects the latter.
    GPLogger.log(e)
    BaselineAppendResult.Failed("the supplemented baseline could not be written", e)
  }
}
