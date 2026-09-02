/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
The button of the second statement: take the missing tasks into a NEW baseline, beside the old one.

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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * BESIDE THE OLD ONE, NOT INTO IT. Natalie's decision of 31.08.2026 — "then alongside" — so that
 * one can go back: the tasks that are in no baseline go into a NEW baseline with a timestamp in
 * its name, and every existing baseline stays exactly as it was, name, place and entries.
 *
 * THE MECHANISM UNDERNEATH IS [appendToBaseline], which was built and tested for this and had no
 * caller until now. What matters about it is that it reads the old values out of the BASELINE's
 * own file rather than out of today's plan — so the tasks that were already recorded keep the
 * dates they were recorded with, and only the new ones arrive with today's.
 *
 * WHICH BASELINE IS SUPPLEMENTED — a decision this class makes and says out loud. The question
 * "are there tasks in NO baseline" needs no choice (see [tasksNotInAnyBaseline]); building the
 * supplement does, because [appendToBaseline] starts from one. The LAST of the list is taken,
 * which is insertion order and nothing better — but here that is a different kind of choice than
 * it would be for the question: nothing existing is changed, the name of the source is written
 * into the question before anything is written, and the user can say no.
 *
 * ONE NEW BASELINE, NOT ONE PER TASK. `appendToBaseline` builds a whole new baseline per call, so
 * supplementing three tasks produces two throwaway ones on the way. They are removed again —
 * otherwise the tidy-up limit would be counting files nobody can reach.
 */
class BaselineCatchUp(
  private val taskManager: TaskManager,
  /**
   * A SUPPLIER AND NOT A LIST, for the reason `LevellingAction` documents: `GanttProject` REPLACES
   * its baseline field with a new ArrayList when a project is closed (`GanttProject.java:819`), so
   * a list captured once would be orphaned after the first file is opened.
   */
  private val baselines: () -> MutableList<GanttPreviousState>,
  private val now: () -> LocalDateTime = { LocalDateTime.now() },
  private val report: (Boolean, String) -> Unit,
  private val ask: AskBeforeWriting,
  private val limit: Int = MAX_AUTO_BASELINES_PER_KIND
) {

  /** What the display should say — see [BaselineGap]. */
  fun gap(): BaselineGap = baselineGap(taskManager, baselines())

  /**
   * Asks, and on a yes writes the supplement. [onDone] is called in EVERY case, including a no and
   * including "there was nothing to do": the display recomputes on that signal, and a signal that
   * only arrived on success would leave the message standing after the user declined.
   */
  fun run(onDone: () -> Unit = {}) {
    val vorhandene = baselines()
    // No baseline at all: there is no button in that state, the display shows a bare statement
    // instead. Called anyway, this must not quietly invent the project's first baseline.
    if (vorhandene.isEmpty()) {
      onDone()
      return
    }
    val fehlend = tasksNotInAnyBaseline(taskManager, vorhandene)
    // A question without an occasion is a question that gets clicked away.
    if (fehlend.isEmpty()) {
      onDone()
      return
    }
    val quelle = vorhandene.last()
    val name = forkText("fork.baseline.catchup.name", stamp())
    val opfer = autoBaselinesToPrune(vorhandene, AutoBaselineKind.CATCH_UP, limit)

    ask.ask(vorschau(quelle, fehlend, name, opfer)) { ja ->
      if (ja) {
        schreiben(quelle, fehlend, name)
      }
      onDone()
    }
  }

  /** ROOT locale: the stamp is digits only, and a name must not read differently per language. */
  private fun stamp(): String =
    now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withLocale(Locale.ROOT))

  /**
   * THE DELETION IS ANNOUNCED, point 5 of the task. The fork asks before writing here anyway, so
   * the sentence costs nothing extra — and deleting a baseline without saying so is not on, even
   * an automatic one.
   */
  private fun vorschau(quelle: GanttPreviousState, fehlend: List<Task>, name: String,
                       opfer: List<GanttPreviousState>): String {
    val text = StringBuilder(forkText("fork.baseline.catchup.ask", fehlend.size))
      .appendLine().appendLine()
      .append(forkText("fork.baseline.catchup.what", name, quelle.name, fehlend.size))
      .appendLine().appendLine()
      .append(forkText("fork.baseline.catchup.hint"))
    fehlend.take(5).forEach { text.appendLine().append("  - ").append(it.name) }
    if (fehlend.size > 5) {
      text.appendLine().append("  ").append(forkText("fork.baseline.catchup.more", fehlend.size - 5))
    }
    if (opfer.isNotEmpty()) {
      text.appendLine().appendLine().append(forkText("fork.baseline.catchup.prune", opfer.size))
      opfer.forEach { text.appendLine().append("  - ").append(it.name) }
    }
    return text.toString()
  }

  private fun schreiben(quelle: GanttPreviousState, fehlend: List<Task>, name: String) {
    var current = quelle
    val zwischenschritte = mutableListOf<GanttPreviousState>()
    fehlend.forEach { task ->
      when (val ergebnis = appendToBaseline(current, task, taskManager, name)) {
        is BaselineAppendResult.Appended -> {
          if (current !== quelle) {
            zwischenschritte.add(current)
          }
          current = ergebnis.baseline
        }
        // Cannot happen -- these tasks were selected BECAUSE no baseline holds them -- but a
        // silently skipped task must not abort the rest either.
        is BaselineAppendResult.AlreadyPresent -> Unit
        is BaselineAppendResult.Failed -> {
          // Half a supplement would be worse than none: nothing goes into the list, and the
          // throwaway files of the steps so far are cleaned up. The source is NEVER removed.
          zwischenschritte.forEach { it.remove() }
          if (current !== quelle) {
            current.remove()
          }
          report(true, forkText("fork.baseline.catchup.failed"))
          return
        }
      }
    }
    if (current === quelle) {
      return
    }
    zwischenschritte.forEach { it.remove() }
    val liste = baselines()
    liste.add(current)
    // AFTER adding, so the new one is counted -- and it is the youngest, so it is never the one
    // that goes.
    val entfernt = pruneAutoBaselines(liste, limit)
    val meldung = StringBuilder(forkText("fork.baseline.catchup.done", name, fehlend.size))
    if (entfernt.isNotEmpty()) {
      meldung.appendLine().append(forkText("fork.baseline.catchup.pruned", entfernt.size))
      entfernt.forEach { meldung.appendLine().append("  - ").append(it.name) }
    }
    report(false, meldung.toString())
  }
}
