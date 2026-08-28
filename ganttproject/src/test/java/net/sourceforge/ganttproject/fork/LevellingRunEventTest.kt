/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
Hole D of the levelling-is-out-of-date measurement: there is no event "a levelling run has ended".

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

import biz.ganttproject.core.calendar.WeekendCalendarImpl
import biz.ganttproject.core.time.CalendarFactory
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.task.event.TaskListenerAdapter
import net.sourceforge.ganttproject.undo.GPUndoListener
import net.sourceforge.ganttproject.undo.GPUndoManager
import net.sourceforge.ganttproject.undo.UndoableEditTxnFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/**
 * A LEVELLING RUN HAS TO SAY THAT IT HAPPENED.
 *
 * Measured on 28.08.2026 (`2026-08-28-verteilung-veraltet-messung.md`, section 1.3, hole D):
 * `applyLevellingAsSingleEdit` writes through mutators, so the program hears a great many task
 * events — one schedule change and one property change per moved task — and not one word saying
 * "this is where a levelling run ended". Everything that wants to react to the run as a whole had
 * nothing to hook on to.
 *
 * AND THE ORDER IS THE HARD PART, which is why it has a test of its own below. The measurement put
 * it as a warning: whoever wants to clear an "out of date" mark when levelling runs has to be
 * called AFTER the write-back. Called any earlier, the write-back's own task events set the mark
 * again a moment later, and the mark comes back the instant it was cleared. `merker` in the
 * ordering test below is that mark, in the smallest form that can show the difference.
 *
 * NOT BUILT AND NOT TESTED HERE: the mark itself, a display, a status line. Those are FF3 part 2b
 * and undecided. This file is about the event and its ordering guarantee.
 */
class LevellingRunEventTest {

  init {
    object : CalendarFactory() {
      init {
        setLocaleApi(object : CalendarFactory.LocaleApi {
          override fun getLocale(): Locale = Locale.GERMANY
          override fun getShortDateFormat(): DateFormat =
            DateFormat.getDateInstance(DateFormat.SHORT, Locale.GERMANY)
        })
      }
    }
  }

  /** An undo manager that only executes — same one as in `LevellingWriteBackTest`. */
  private class RunOnlyUndoManager : GPUndoManager {
    override fun undoableEdit(localizedName: String, runnableEdit: Runnable) = runnableEdit.run()
    override fun canUndo() = false
    override fun canRedo() = false
    override fun undo() = Unit
    override fun redo() = Unit
    override val undoPresentationName = ""
    override val redoPresentationName = ""
    override fun addUndoableEditListener(listener: GPUndoListener) = Unit
    override fun removeUndoableEditListener(listener: GPUndoListener) = Unit
    override fun die() = Unit
    override fun addUndoableEditTxnFactory(factory: UndoableEditTxnFactory) = Unit
  }

  private fun LocalDate.toLegacy(): Date = this.toModelDate()

  private fun taskManager(): TaskManager =
    TestSetupHelper.newTaskManagerBuilder().withCalendar(WeekendCalendarImpl()).build()

  private fun TaskManager.newTask(name: String, start: LocalDate, days: Int): Task =
    this.newTaskBuilder().withName(name).withStartDate(start.toLegacy())
      .withDuration(this.createLength(days.toLong())).build()

  private val Task.startDate: LocalDate get() = this.start.time.toModelLocalDate()

  /** A Monday, comfortably in the future. */
  private val MONTAG: LocalDate = LocalDate.of(2026, 9, 14)

  // ------------------------------------------------------------------------------------------
  // The event exists at all
  // ------------------------------------------------------------------------------------------

  /**
   * RED against dd01fbb05 — the type does not exist there at all:
   *   e: file:///.../fork/LevellingRunEventTest.kt:115:20 Unresolved reference 'LevellingRunNotifier'.
   *   e: file:///.../fork/LevellingRunEventTest.kt:121:27 Too many arguments for 'fun
   *      applyLevellingAsSingleEdit(starts: Map<String, LocalDate>, taskManager: TaskManager,
   *      undoManager: GPUndoManager, editName: String, durations: Map<String, Int> = ...): Int'.
   */
  @Test
  fun `a levelling run reports that it has ended`() {
    val taskManager = taskManager()
    val task = taskManager.newTask("ELSTER", MONTAG, 5)
    val notifier = LevellingRunNotifier()
    val gemeldet = mutableListOf<LevellingRunFinished>()
    notifier.addListener { gemeldet.add(it) }

    applyLevellingAsSingleEdit(
      mapOf(task.taskID.toString() to MONTAG.plusDays(7)), taskManager, RunOnlyUndoManager(),
      "Test", emptyMap(), notifier)

    assertEquals(1, gemeldet.size, "a levelling run is exactly one message")
    assertEquals(1, gemeldet[0].movedTasks, "the message carries what the run actually moved")
  }

  /**
   * A run that finds nothing to move is still a run: the plan agrees with the calculation
   * afterwards, which is precisely what a consumer wants to know. `applyLevellingAsSingleEdit`
   * leaves early in this case, so this is a separate path through the function and not a variation
   * of the one above.
   *
   * RED against dd01fbb05 — same unresolved reference; the type does not exist.
   */
  @Test
  fun `a run that moves nothing reports too`() {
    val taskManager = taskManager()
    val task = taskManager.newTask("steht schon richtig", MONTAG, 5)
    val notifier = LevellingRunNotifier()
    val gemeldet = mutableListOf<LevellingRunFinished>()
    notifier.addListener { gemeldet.add(it) }

    val geschrieben = applyLevellingAsSingleEdit(
      mapOf(task.taskID.toString() to MONTAG), taskManager, RunOnlyUndoManager(),
      "Test", emptyMap(), notifier)

    assertEquals(0, geschrieben, "Aufbau: es gab nichts zu verschieben")
    assertEquals(1, gemeldet.size, "a run without a move is still a run that ended")
    assertEquals(0, gemeldet[0].movedTasks, "and it moved nothing, which is a value and not a gap")
  }

  // ------------------------------------------------------------------------------------------
  // The order — the point the measurement warned about
  // ------------------------------------------------------------------------------------------

  /**
   * THE WARNING FROM THE MEASUREMENT, AS A TEST.
   *
   * `merker` here stands for the "levelling is out of date" mark that FF3 part 2b may or may not
   * build. It is set by every task event, which is what such a mark does, and cleared by the
   * levelling-finished event. If the event arrived from inside the write-back, the write-back's own
   * task events would set the mark again straight afterwards and it would end up true — the mark
   * would have cleared itself and come back within the same run.
   *
   * MEASURED RED, with the notification deliberately moved into the write-back (fired at the top of
   * `writeLevellingBack` instead of around it):
   *   org.opentest4j.AssertionFailedError: the run's own write events must not set the mark again
   *   after it was cleared -- that is why the report comes after the write-back
   *   ==> expected: <false> but was: <true>
   */
  @Test
  fun `the report comes after the write-back, not from inside it`() {
    val taskManager = taskManager()
    val task = taskManager.newTask("ELSTER", MONTAG, 5)
    var merker = false
    taskManager.addTaskListener(TaskListenerAdapter(allEventsHandler = { merker = true }))

    val notifier = LevellingRunNotifier()
    notifier.addListener { merker = false }

    merker = false
    applyLevellingAsSingleEdit(
      mapOf(task.taskID.toString() to MONTAG.plusDays(7)), taskManager, RunOnlyUndoManager(),
      "Test", emptyMap(), notifier)

    assertFalse(merker,
      "the run's own write events must not set the mark again after it was cleared -- " +
        "that is why the report comes after the write-back")
  }

  /**
   * The same order seen from the other side: when the listener is called, the new dates are
   * already in the model. A consumer that recomputes something on this event must not be handed a
   * half-written plan.
   *
   * MEASURED RED, with the notification deliberately moved into the write-back:
   *   org.opentest4j.AssertionFailedError: the listener has to see the levelled plan, not the old
   *   one ==> expected: <2026-09-21> but was: <2026-09-14>
   */
  @Test
  fun `the listener sees the written dates`() {
    val taskManager = taskManager()
    val task = taskManager.newTask("ELSTER", MONTAG, 5)
    val neuerStart = MONTAG.plusDays(7)
    val notifier = LevellingRunNotifier()
    var gesehen: LocalDate? = null
    notifier.addListener { gesehen = task.startDate }

    applyLevellingAsSingleEdit(
      mapOf(task.taskID.toString() to neuerStart), taskManager, RunOnlyUndoManager(),
      "Test", emptyMap(), notifier)

    assertEquals(neuerStart, gesehen, "the listener has to see the levelled plan, not the old one")
  }

  // ------------------------------------------------------------------------------------------
  // No endless loop
  // ------------------------------------------------------------------------------------------

  /**
   * `isRunning` has to be true while the run's own events are travelling, so that a task listener
   * can tell "this is the levelling writing" from "the user changed something". Same idiom as
   * `EffortDrivenTrigger.isRunning` and `SchedulerImpl.isRunning`.
   *
   * COULD NOT BE RED against dd01fbb05: there was no notifier and hence no flag to look at. It
   * pins the new code.
   */
  @Test
  fun `isRunning is set while the write-back happens and cleared afterwards`() {
    val taskManager = taskManager()
    val task = taskManager.newTask("ELSTER", MONTAG, 5)
    val notifier = LevellingRunNotifier()
    var waehrendDesSchreibens: Boolean? = null
    var waehrendDerMeldung: Boolean? = null
    taskManager.addTaskListener(TaskListenerAdapter(allEventsHandler = {
      waehrendDesSchreibens = notifier.isRunning
    }))
    notifier.addListener { waehrendDerMeldung = notifier.isRunning }

    assertFalse(notifier.isRunning, "Aufbau: vor dem Lauf laeuft nichts")
    applyLevellingAsSingleEdit(
      mapOf(task.taskID.toString() to MONTAG.plusDays(7)), taskManager, RunOnlyUndoManager(),
      "Test", emptyMap(), notifier)

    assertEquals(true, waehrendDesSchreibens, "a task event of the run itself must be recognisable")
    assertEquals(true, waehrendDerMeldung, "the flag also covers the notification itself")
    assertFalse(notifier.isRunning, "after the run nothing runs any more")
  }

  /**
   * NO CIRCLE. A listener that starts another levelling run from inside its handler is the shape
   * the feedback takes here. The nested run has to write and return without reporting again;
   * otherwise the two would call each other without end and this test would not fail, it would
   * never return.
   *
   * RED against dd01fbb05 only as a compile error -- there was no event, so no listener could start
   * anything. It did however go red in the mis-wired variant used for the ordering measurement,
   * which shows the assertion has teeth:
   *   org.opentest4j.AssertionFailedError: the nested run did write back
   *   ==> expected: <true> but was: <false>
   */
  @Test
  fun `a listener that levels again does not loop`() {
    val taskManager = taskManager()
    val task = taskManager.newTask("ELSTER", MONTAG, 5)
    val notifier = LevellingRunNotifier()
    var gemeldet = 0
    var geschachtelt = 0
    notifier.addListener {
      gemeldet++
      if (geschachtelt < 5) {
        geschachtelt++
        applyLevellingAsSingleEdit(
          mapOf(task.taskID.toString() to MONTAG.plusDays(14)), taskManager,
          RunOnlyUndoManager(), "verschachtelt", emptyMap(), notifier)
      }
    }

    applyLevellingAsSingleEdit(
      mapOf(task.taskID.toString() to MONTAG.plusDays(7)), taskManager, RunOnlyUndoManager(),
      "Test", emptyMap(), notifier)

    assertEquals(1, gemeldet, "one run is one message, however often it is entered")
    assertEquals(1, geschachtelt, "the nested run wrote, and it did not report")
    assertTrue(task.startDate == MONTAG.plusDays(14), "the nested run did write back")
  }

  /**
   * A listener that throws must not take the run down with it, nor keep the other listeners from
   * being called: the write-back has already happened when they are notified, and an exception on
   * the side channel cannot undo it.
   *
   * COULD NOT BE RED against dd01fbb05: no event, no listener, no exception.
   */
  @Test
  fun `a throwing listener does not take the run down`() {
    val taskManager = taskManager()
    val task = taskManager.newTask("ELSTER", MONTAG, 5)
    val notifier = LevellingRunNotifier()
    var zweiterGerufen = false
    notifier.addListener { throw IllegalStateException("absichtlich") }
    notifier.addListener { zweiterGerufen = true }

    val geschrieben = applyLevellingAsSingleEdit(
      mapOf(task.taskID.toString() to MONTAG.plusDays(7)), taskManager, RunOnlyUndoManager(),
      "Test", emptyMap(), notifier)

    assertEquals(1, geschrieben, "the run's result must survive a listener that throws")
    assertTrue(zweiterGerufen, "the second listener must still be called")
    assertFalse(notifier.isRunning, "and the flag must be cleared even then")
  }

  /**
   * A removed listener is not called again. Without this, a consumer that comes and goes — a
   * window, a view — would leak into every later run.
   *
   * COULD NOT BE RED against dd01fbb05: no event, no listener to remove.
   */
  @Test
  fun `a removed listener is silent`() {
    val taskManager = taskManager()
    val task = taskManager.newTask("ELSTER", MONTAG, 5)
    val notifier = LevellingRunNotifier()
    var gerufen = 0
    val listener: (LevellingRunFinished) -> Unit = { gerufen++ }
    notifier.addListener(listener)
    notifier.removeListener(listener)

    applyLevellingAsSingleEdit(
      mapOf(task.taskID.toString() to MONTAG.plusDays(7)), taskManager, RunOnlyUndoManager(),
      "Test", emptyMap(), notifier)

    assertEquals(0, gerufen, "a removed listener hears nothing")
  }
}
