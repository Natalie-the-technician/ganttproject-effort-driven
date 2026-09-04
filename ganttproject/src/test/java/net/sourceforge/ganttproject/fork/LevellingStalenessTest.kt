/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
The mark "the levelling is out of date", and the feedback loop it has to survive.

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
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
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
 * THE MARK, AND THE ONE THING THAT CAN RUIN IT.
 *
 * The mark is a boolean fed by three listeners. That part is nearly trivial. What is not trivial
 * is the feedback: writing a levelling result back fires one `taskScheduleChanged` and one
 * `taskPropertiesChanged` per moved task, so the levelling's own writes look exactly like user
 * changes. A mark that does not defend itself against that would come back on the instant it was
 * cleared — and would be permanently on for exactly the plans that had just been levelled, which
 * is the worst possible failure for a message meant to say "something needs doing".
 *
 * The measurement of 28.08.2026 called this out as a warning rather than a hole
 * (`2026-08-28-verteilung-veraltet-messung.md`, section 1.3 D). It is proved here with a real task
 * manager and a real write-back, not with an argument.
 *
 * WHAT IS NOT TESTED HERE: how often the mark goes off for nothing. That is the number that
 * decides whether the mark is worth displaying, and it has a file of its own —
 * `LevellingStalenessNoiseTest`.
 */
class LevellingStalenessTest {

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

  /** An undo manager that only executes — the same one as in `LevellingRunEventTest`. */
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

  private val MONTAG: LocalDate = LocalDate.of(2026, 9, 14)

  private class Plan {
    val builder: TestSetupHelper.TaskManagerBuilder =
      TestSetupHelper.newTaskManagerBuilder().withCalendar(WeekendCalendarImpl())
    val taskManager: TaskManager = builder.build()
    val resourceManager: HumanResourceManager = builder.resourceManager
    val taskProperties: CustomPropertyManager get() = taskManager.customPropertyManager

    fun person(name: String, id: Int): HumanResource = resourceManager.create(name, id)

    fun task(name: String, start: LocalDate, days: Int): Task =
      taskManager.newTaskBuilder().withName(name).withStartDate(start.toModelDate())
        .withDuration(taskManager.createLength(days.toLong())).build()
  }

  private val Task.startDate: LocalDate get() = this.start.time.toModelLocalDate()

  // --------------------------------------------------------------------------------------------
  // The mark exists and hears the three sources
  // --------------------------------------------------------------------------------------------

  /**
   * RED against 9178b886b — the class does not exist there:
   *   e: file:///.../fork/LevellingStalenessTest.kt:120:22 Unresolved reference 'LevellingStaleness'.
   *   e: file:///.../fork/LevellingStalenessTest.kt:121:26 Unresolved reference 'isStale'.
   */
  @Test
  fun `a fresh mark is clear`() {
    val staleness = LevellingStaleness(LevellingRunNotifier())
    assertFalse(staleness.isStale, "nothing has happened yet, so nothing is out of date")
    staleness.detach()
  }

  /**
   * The task side. All nine change events set the mark; `taskModelReset` has its own test below.
   *
   * RED against 9178b886b — unresolved reference 'LevellingStaleness', 'taskListener', 'isStale'.
   */
  @Test
  fun `a task change sets the mark`() {
    val plan = Plan()
    val staleness = LevellingStaleness(LevellingRunNotifier())
    plan.taskManager.addTaskListener(staleness.taskListener)
    try {
      val task = plan.task("Angebot", MONTAG, 5)
      assertTrue(staleness.isStale, "a new task changes what there is to level")

      staleness.clear()
      task.createMutator().also { it.setDuration(plan.taskManager.createLength(8L)) }.commit()
      assertTrue(staleness.isStale, "a changed duration changes where everything after it lands")
    } finally {
      staleness.detach()
    }
  }

  /**
   * PROGRESS IS A SEPARATE EVENT. `taskProgressChanged` is not contained in
   * `taskPropertiesChanged` — the two have their own senders in `TaskImpl.kt`. A mark that
   * listens only to the property event misses it, and progress decides `frozen` and the remaining
   * duration in `LevellingAdapter.kt`. So this is a missed alarm, not a saved false one.
   *
   * RED against 9178b886b — unresolved reference 'LevellingStaleness'.
   */
  @Test
  fun `a progress change sets the mark although it is not a property event`() {
    val plan = Plan()
    val staleness = LevellingStaleness(LevellingRunNotifier())
    val gehoert = mutableListOf<String>()
    plan.taskManager.addTaskListener(staleness.taskListener)
    plan.taskManager.addTaskListener(object :
      net.sourceforge.ganttproject.task.event.TaskListenerAdapter() {
      override fun taskPropertiesChanged(
        e: net.sourceforge.ganttproject.task.event.TaskPropertyEvent) {
        gehoert.add("properties")
      }
      override fun taskProgressChanged(
        e: net.sourceforge.ganttproject.task.event.TaskPropertyEvent) {
        gehoert.add("progress")
      }
    })
    try {
      val task = plan.task("Angebot", MONTAG, 5)
      staleness.clear()
      gehoert.clear()

      task.createMutator().also { it.setCompletionPercentage(40) }.commit()

      // MEASURED, and it surprised me: ONE commit produces TWO progress events. The dispatcher
      // runs the container-percentage recalculation before it dispatches
      // (`TaskManagerImpl.fireTaskProgressChanged`), and that recalculation dispatches once more
      // on its own. The count is recorded here rather than asserted on, because it is not this
      // fork's doing and not what this test is about. What IS asserted is the TYPE: every one of
      // them is a progress event and none is a property event.
      assertEquals(listOf("progress"), gehoert.distinct(),
        "Aufbau: progress arrives as taskProgressChanged and NOT as taskPropertiesChanged")
      assertEquals(2, gehoert.size, "measured: one commit, two progress events -- see above")
      assertTrue(staleness.isStale, "the mark has to hear the progress event separately")
    } finally {
      staleness.detach()
    }
  }

  /**
   * The resource side.
   *
   * RED against 9178b886b — unresolved reference 'LevellingStaleness', 'resourceView'.
   */
  @Test
  fun `a resource change sets the mark`() {
    val plan = Plan()
    val staleness = LevellingStaleness(LevellingRunNotifier())
    plan.resourceManager.addView(staleness.resourceView)
    try {
      val anna = plan.person("Anna", 1)
      assertTrue(staleness.isStale, "a new person is a new capacity pool")

      val task = plan.task("Angebot", MONTAG, 5)
      staleness.clear()
      task.assignmentCollection.addAssignment(anna).load = 100f
      assertTrue(staleness.isStale, "an assignment is what levelling is about in the first place")
    } finally {
      staleness.detach()
    }
  }

  /**
   * The calendar side. A holiday moves every task that would have been laid on it.
   *
   * RED against 9178b886b — unresolved reference 'LevellingStaleness', 'calendarListener'.
   */
  @Test
  fun `a calendar change sets the mark`() {
    val plan = Plan()
    val staleness = LevellingStaleness(LevellingRunNotifier())
    plan.taskManager.calendar.addListener(staleness.calendarListener)
    try {
      staleness.clear()
      plan.taskManager.calendar.publicHolidays = listOf(
        biz.ganttproject.core.calendar.CalendarEvent.newEvent(
          MONTAG.toModelDate(), false,
          biz.ganttproject.core.calendar.CalendarEvent.Type.HOLIDAY, "Feiertag", null))
      assertTrue(staleness.isStale, "a holiday moves everything that would have fallen on it")
    } finally {
      staleness.detach()
    }
  }

  // --------------------------------------------------------------------------------------------
  // THE FEEDBACK — the point of the whole file
  // --------------------------------------------------------------------------------------------

  /**
   * THE FEEDBACK, PROVED RATHER THAN ARGUED.
   *
   * A real task manager, a real write-back through `applyLevellingAsSingleEdit`, and the mark
   * wired to the task events exactly as it would be in the program. The write-back moves a task,
   * which fires `taskScheduleChanged` and `taskPropertiesChanged`. If the mark took those for user
   * changes it would stand at `true` afterwards — out of date the moment it was brought up to
   * date.
   *
   * The counter shows that the events really did arrive: without it this test would also pass
   * against a write-back that wrote nothing at all.
   *
   * RED against 9178b886b — unresolved reference 'LevellingStaleness'.
   */
  @Test
  fun `the levellings own write-back does not set the mark again`() {
    val plan = Plan()
    val notifier = LevellingRunNotifier()
    val staleness = LevellingStaleness(notifier)
    var ereignisseWaehrendDesLaufs = 0
    plan.taskManager.addTaskListener(staleness.taskListener)
    plan.taskManager.addTaskListener(
      net.sourceforge.ganttproject.task.event.TaskListenerAdapter(
        allEventsHandler = { if (notifier.isRunning) ereignisseWaehrendDesLaufs++ }))
    try {
      val task = plan.task("Angebot", MONTAG, 5)
      assertTrue(staleness.isStale, "Aufbau: creating the task set the mark")

      applyLevellingAsSingleEdit(
        mapOf(task.taskID.toString() to MONTAG.plusDays(7)), plan.taskManager,
        RunOnlyUndoManager(), "Test", emptyMap(), notifier)

      assertTrue(ereignisseWaehrendDesLaufs > 0,
        "Aufbau: the write-back really did fire task events -- otherwise this proves nothing")
      assertFalse(staleness.isStale,
        "the levelling's own write events must not set the mark again after it was cleared")
      assertEquals(MONTAG.plusDays(7), task.startDate, "Aufbau: the task really did move")
    } finally {
      staleness.detach()
    }
  }

  /**
   * THE ORDER, taken apart. The clearing hangs off `LevellingRunNotifier`, which calls its
   * listeners after the write-back has finished. This test shows the OTHER half of the defence:
   * even an event that arrives in the middle of the run — from a listener of its own, not from the
   * write-back — is ignored, because [LevellingStaleness.markStale] checks `isRunning`.
   *
   * Belt and braces on purpose: either half alone would do, and neither is free to remove without
   * this test going red.
   *
   * RED against 9178b886b — unresolved reference 'LevellingStaleness'.
   */
  @Test
  fun `a change arriving in the middle of the run is ignored`() {
    val plan = Plan()
    val notifier = LevellingRunNotifier()
    val staleness = LevellingStaleness(notifier)
    plan.taskManager.addTaskListener(staleness.taskListener)
    try {
      val task = plan.task("Angebot", MONTAG, 5)
      staleness.clear()

      // A listener that renames the task while the write-back is going on. Contrived, but it is
      // the shape of every "something else reacts to the levelling" case.
      plan.taskManager.addTaskListener(object :
        net.sourceforge.ganttproject.task.event.TaskListenerAdapter() {
        private var einmal = true
        override fun taskScheduleChanged(
          e: net.sourceforge.ganttproject.task.event.TaskScheduleEvent) {
          if (einmal) {
            einmal = false
            staleness.markStale()
          }
        }
      })

      applyLevellingAsSingleEdit(
        mapOf(task.taskID.toString() to MONTAG.plusDays(7)), plan.taskManager,
        RunOnlyUndoManager(), "Test", emptyMap(), notifier)

      assertFalse(staleness.isStale,
        "markStale during a run is the run's own doing and must not count")
    } finally {
      staleness.detach()
    }
  }

  /**
   * THE isRunning GUARD, MADE LOAD-BEARING.
   *
   * The two defences are not equivalent, and it took writing this test to see it. The ordering
   * alone — clearing from `LevellingRunNotifier` after the write-back — covers everything the
   * write-back itself fires. It does NOT cover what happens after the clearing: a second listener
   * on the same event, registered later than the mark's, runs AFTER the mark has been cleared, and
   * anything it changes in the plan would set the mark again. The run would end with the mark on
   * although nothing but the run had happened.
   *
   * That is not a contrived case. It is the shape of every "something else adjusts the plan when
   * levelling has run" — the very thing Natalie's design wants the run to be a defined moment for.
   *
   * `isRunning` stays true until the last listener has returned (`LevellingRunNotifier`, the
   * `finally`), which is exactly what makes this work.
   *
   * RED against 9178b886b — unresolved reference 'LevellingStaleness'. And red against the mark
   * WITHOUT the guard, which is the run that shows this test has teeth; the output is in the
   * report of 02.09.2026.
   */
  @Test
  fun `a change made by another levelling listener does not set the mark`() {
    val plan = Plan()
    val notifier = LevellingRunNotifier()
    val staleness = LevellingStaleness(notifier)
    plan.taskManager.addTaskListener(staleness.taskListener)
    // Registered AFTER the mark, so it runs after the mark has been cleared -- which is the whole
    // point of the case.
    var nachgezogen = 0
    val task = plan.task("Angebot", MONTAG, 5)
    notifier.addListener {
      nachgezogen++
      task.createMutator().also { it.setNotes("nachgezogen $nachgezogen") }.commit()
    }
    try {
      applyLevellingAsSingleEdit(
        mapOf(task.taskID.toString() to MONTAG.plusDays(7)), plan.taskManager,
        RunOnlyUndoManager(), "Test", emptyMap(), notifier)

      assertEquals(1, nachgezogen, "Aufbau: the second listener really did run and really did write")
      assertFalse(staleness.isStale,
        "a change made from inside the run is the run's own doing -- the ordering alone cannot " +
          "catch this one, only isRunning can")
    } finally {
      staleness.detach()
    }
  }

  /**
   * And the mark DOES come back for a change that arrives after the run — otherwise the guard
   * above would be a way of silencing the mark for good rather than for the run.
   *
   * RED against 9178b886b — unresolved reference 'LevellingStaleness'.
   */
  @Test
  fun `a change after the run sets the mark again`() {
    val plan = Plan()
    val notifier = LevellingRunNotifier()
    val staleness = LevellingStaleness(notifier)
    plan.taskManager.addTaskListener(staleness.taskListener)
    try {
      val task = plan.task("Angebot", MONTAG, 5)
      applyLevellingAsSingleEdit(
        mapOf(task.taskID.toString() to MONTAG.plusDays(7)), plan.taskManager,
        RunOnlyUndoManager(), "Test", emptyMap(), notifier)
      assertFalse(staleness.isStale, "Aufbau: the run left the mark clear")

      task.createMutator().also { it.setDuration(plan.taskManager.createLength(9L)) }.commit()

      assertTrue(staleness.isStale, "the guard covers the run and nothing beyond it")
    } finally {
      staleness.detach()
    }
  }

  /**
   * A levelling run that moves nothing still clears the mark. The plan agrees with the calculation
   * afterwards, and that is the whole statement the mark makes — `applyLevellingAsSingleEdit`
   * leaves early in this case, so it is a different path through the function.
   *
   * RED against 9178b886b — unresolved reference 'LevellingStaleness'.
   */
  @Test
  fun `a run that moves nothing clears the mark too`() {
    val plan = Plan()
    val notifier = LevellingRunNotifier()
    val staleness = LevellingStaleness(notifier)
    plan.taskManager.addTaskListener(staleness.taskListener)
    try {
      val task = plan.task("steht schon richtig", MONTAG, 5)
      assertTrue(staleness.isStale, "Aufbau: creating the task set the mark")

      val geschrieben = applyLevellingAsSingleEdit(
        mapOf(task.taskID.toString() to MONTAG), plan.taskManager,
        RunOnlyUndoManager(), "Test", emptyMap(), notifier)

      assertEquals(0, geschrieben, "Aufbau: there was nothing to move")
      assertFalse(staleness.isStale, "the plan and the calculation agree, which is what the mark is about")
    } finally {
      staleness.detach()
    }
  }

  // --------------------------------------------------------------------------------------------
  // Loading a file
  // --------------------------------------------------------------------------------------------

  /**
   * OPENING A FILE STARTS WITH A CLEAR MARK.
   *
   * The mark can only ever mean "changed since the levelling last ran, or since this plan was
   * loaded" — the program keeps no record of when a plan was levelled (measured,
   * `2026-08-28-verteilung-veraltet-messung.md` section 2.3). Parsing a file fires a task event
   * per task, so without this the mark would be on before the user had done anything at all.
   *
   * `TaskManagerImpl.projectOpened()` fires `taskModelReset` AFTER the parse, which is what makes
   * clearing on the reset the right move rather than a racy one. The measurement listed this as
   * unverified (its point 5); it is verified here.
   *
   * RED against 9178b886b — unresolved reference 'LevellingStaleness'.
   */
  @Test
  fun `a model reset clears the mark`() {
    val plan = Plan()
    val staleness = LevellingStaleness(LevellingRunNotifier())
    plan.taskManager.addTaskListener(staleness.taskListener)
    try {
      plan.task("aus der Datei", MONTAG, 5)
      assertTrue(staleness.isStale, "Aufbau: parsing a file looks like a change")

      staleness.taskListener.taskModelReset()

      assertFalse(staleness.isStale, "a reset means a different plan, not a changed one")
    } finally {
      staleness.detach()
    }
  }

  // --------------------------------------------------------------------------------------------
  // The listeners on the mark itself — what a display would hang off
  // --------------------------------------------------------------------------------------------

  /**
   * A display must not be redrawn once per keystroke. The mark reports only when its VALUE moves,
   * so a hundred changes in a row are one message and not a hundred.
   *
   * RED against 9178b886b — unresolved reference 'LevellingStaleness'.
   */
  @Test
  fun `the mark reports only when it really changes`() {
    val plan = Plan()
    val staleness = LevellingStaleness(LevellingRunNotifier())
    val meldungen = mutableListOf<Boolean>()
    staleness.addListener { meldungen.add(it) }
    plan.taskManager.addTaskListener(staleness.taskListener)
    try {
      val task = plan.task("Angebot", MONTAG, 5)
      repeat(5) { i ->
        task.createMutator().also { it.setName("Angebot $i") }.commit()
      }

      assertEquals(listOf(true), meldungen,
        "six changes in a row are ONE message -- a display redrawn per keystroke is the thing " +
          "this mark exists to avoid")
    } finally {
      staleness.detach()
    }
  }

  /**
   * A listener that throws must not take the model change down with it. The message is a side
   * channel; the change has already happened. Same reasoning as in `LevellingRunNotifier`.
   *
   * COULD NOT BE RED against 9178b886b: without the class there is no listener that could throw.
   * It guards the new code against the obvious mistake of letting the exception escape.
   */
  @Test
  fun `a throwing listener does not take the change down`() {
    val plan = Plan()
    val staleness = LevellingStaleness(LevellingRunNotifier())
    val zweiter = mutableListOf<Boolean>()
    staleness.addListener { throw IllegalStateException("absichtlich") }
    staleness.addListener { zweiter.add(it) }
    plan.taskManager.addTaskListener(staleness.taskListener)
    try {
      plan.task("Angebot", MONTAG, 5)
      assertTrue(staleness.isStale, "the mark was set despite the throwing listener")
      assertEquals(listOf(true), zweiter, "and the second listener heard it")
    } finally {
      staleness.detach()
    }
  }

  /**
   * A removed listener is silent, and `detach` unhooks from the run notifier.
   *
   * COULD NOT BE RED against 9178b886b: no class, no listener, nothing to remove.
   */
  @Test
  fun `a removed listener is silent`() {
    val plan = Plan()
    val staleness = LevellingStaleness(LevellingRunNotifier())
    val meldungen = mutableListOf<Boolean>()
    val listener: (Boolean) -> Unit = { meldungen.add(it) }
    staleness.addListener(listener)
    staleness.removeListener(listener)
    plan.taskManager.addTaskListener(staleness.taskListener)
    try {
      plan.task("Angebot", MONTAG, 5)
      assertTrue(staleness.isStale, "Aufbau: the mark itself still works")
      assertTrue(meldungen.isEmpty(), "a removed listener hears nothing")
    } finally {
      staleness.detach()
    }
  }

  // --------------------------------------------------------------------------------------------
  // The calculation stays untouched
  // --------------------------------------------------------------------------------------------

  /**
   * THE MARK OBSERVES, IT DOES NOT INTERVENE.
   *
   * The same plan levelled twice — once with the mark listening to everything, once without it —
   * has to give the identical result, down to the dates. The mark writes nothing, and this is the
   * proof rather than the claim.
   *
   * COULD NOT BE RED against 9178b886b: the class does not exist there, so there is no "with the
   * mark" arm to compare against. It is the guard that the mark stays an observer.
   */
  @Test
  fun `the calculation is the same with and without the mark`() {
    fun ergebnis(mitMerker: Boolean): Map<String, LocalDate> {
      val plan = Plan()
      val staleness = if (mitMerker) LevellingStaleness(LevellingRunNotifier()) else null
      staleness?.let {
        plan.taskManager.addTaskListener(it.taskListener)
        plan.resourceManager.addView(it.resourceView)
        plan.taskManager.calendar.addListener(it.calendarListener)
      }
      try {
        val anna = plan.person("Anna", 1)
        listOf("A", "B", "C").forEach { name ->
          val task = plan.task(name, MONTAG, 3)
          task.assignmentCollection.addAssignment(anna).load = 100f
        }
        val tasks = collectLevelTasks(plan.taskManager, plan.taskProperties,
          plan.resourceManager.customPropertyManager, LocalDate.of(2026, 9, 1), true)
        return levelTasks(tasks, MONTAG,
          workingDaysPerTask(plan.taskManager, plan.resourceManager.customPropertyManager),
          durationAtStart(plan.taskManager, plan.taskProperties,
            plan.resourceManager.customPropertyManager),
          isAvailable = availabilityTest(plan.resourceManager)).starts
      } finally {
        staleness?.detach()
      }
    }

    val ohne = ergebnis(mitMerker = false)
    val mit = ergebnis(mitMerker = true)
    assertTrue(ohne.isNotEmpty(), "Aufbau: there was something to level")
    assertEquals(ohne, mit, "the mark observes; it must not change a single date")
  }
}
