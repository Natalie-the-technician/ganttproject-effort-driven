/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
The one event that says "a levelling run has just ended".

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

import java.util.concurrent.CopyOnWriteArrayList

/**
 * What a finished levelling run reports.
 *
 * [movedTasks] is what `applyLevellingAsSingleEdit` returns: the number of tasks whose start or
 * duration was actually written. ZERO IS A VALID AND MEANINGFUL VALUE -- it says the plan already
 * matched the calculation. Whoever wants to know "is the levelling still current" has to treat a
 * run that moved nothing exactly like one that moved a hundred tasks; both end with a plan that
 * agrees with the calculation.
 */
data class LevellingRunFinished(val movedTasks: Int)

/**
 * The missing event. Writing the levelled dates back produces a great many task events -- one
 * `taskScheduleChanged` and one `taskPropertiesChanged` per moved task -- but not one single
 * message saying "this is where a levelling run ended". Anything that wants to react to the run as
 * a whole rather than to its individual writes had nothing to hook on to.
 *
 * WHY THE ORDER IN [runAndReport] IS THE WHOLE POINT. The listeners are called AFTER the write-back
 * has completely finished, not from inside it. Somebody who later wants to clear a "levelling is
 * out of date" mark on this event MUST be called at that moment: called earlier, the write-back's
 * own task events would set the mark again immediately afterwards, and the mark would come back on
 * the instant it was cleared. The order is fixed here, in one place, so that no caller can get it
 * wrong.
 *
 * WHY [isRunning] EXISTS ON TOP OF THAT. Same idiom as `EffortDrivenTrigger.isRunning` and
 * `SchedulerImpl.isRunning`, and for the same reason -- guarding against feeding itself. It covers
 * two things at once:
 *  - a listener that starts another levelling run from inside its handler gets the write-back but
 *    no second event, so the chain ends instead of running in a circle;
 *  - it stays true while the listeners are being called, so a task listener that reacts to the
 *    write-back's own events can tell "this change is the levelling's own doing" from "the user
 *    changed something".
 *
 * NO SUBSCRIBER IN THE PROGRAM YET, and that is deliberate. The mark, the display and the status
 * line are FF3 part 2b and are not decided. What is built here is the event and its ordering
 * guarantee -- the part that is a defect on its own, because without it every possible consumer
 * would have to reconstruct the ordering for itself and would get it wrong in the way described
 * above.
 *
 * THREADING: levelling runs on the Swing event thread, so this is single-threaded in practice. The
 * volatile field and the copy-on-write list cost nothing and keep a reader on another thread from
 * seeing a torn value.
 */
class LevellingRunNotifier {
  private val listeners = CopyOnWriteArrayList<(LevellingRunFinished) -> Unit>()

  /** True from the beginning of a write-back until the last listener has returned. */
  @Volatile
  var isRunning: Boolean = false
    private set

  fun addListener(listener: (LevellingRunFinished) -> Unit) {
    listeners.add(listener)
  }

  fun removeListener(listener: (LevellingRunFinished) -> Unit) {
    listeners.remove(listener)
  }

  /**
   * Runs [writeBack] and reports afterwards. Returns whatever [writeBack] returned, so that this
   * can wrap an existing function without changing what its callers see.
   *
   * A nested call -- one that arrives while a run is still in progress -- writes back and returns
   * WITHOUT reporting. One run is one event, however many times it is entered.
   */
  fun runAndReport(writeBack: () -> Int): Int {
    if (isRunning) {
      return writeBack()
    }
    isRunning = true
    try {
      val moved = writeBack()
      // A listener that throws must not swallow the run's result nor keep the others from being
      // called: the notification is a side channel, the write-back has already happened.
      listeners.forEach { listener ->
        try {
          listener(LevellingRunFinished(moved))
        } catch (e: Exception) {
          net.sourceforge.ganttproject.GPLogger.log(e)
        }
      }
      return moved
    } finally {
      isRunning = false
    }
  }
}

/**
 * The instance the program uses. A shared object and not a field on some manager: the event has no
 * owner in the model yet, and threading it through `TaskManager` or `GanttProject` would touch
 * original files for a value that nothing reads there.
 *
 * `applyLevellingAsSingleEdit` takes the notifier as a parameter defaulting to this one, so a test
 * can measure against its own instance without the shared one carrying state from one test into
 * the next.
 */
val levellingRunNotifier = LevellingRunNotifier()
