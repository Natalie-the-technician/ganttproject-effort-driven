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

import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.core.time.GanttCalendar
import net.sourceforge.ganttproject.GanttProjectImpl
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.dependency.constraint.FinishStartConstraintImpl
import net.sourceforge.ganttproject.task.event.TaskListenerAdapter
import net.sourceforge.ganttproject.task.event.TaskScheduleEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * A MEASUREMENT, not a promise of the fork: how often does `taskScheduleChanged` come when a
 * task is edited the way the properties dialog edits it?
 *
 * WHAT FOR: the later question "when do we ask the user whether the new task should go into the
 * baseline" hangs on this. `taskAdded` knows nothing about the position (the new task gets the
 * left edge of the visible window and duration 1); `taskScheduleChanged` is the only moment at
 * which the scheduler has already run and the task has the position it was planned into. If that
 * event came twice per Ok, a question hanging on it would come twice.
 *
 * This could not be read off the source with certainty: the dialog sets start and duration
 * separately (MainPropertiesPanel.kt:227/233), and whether that is one commit or two is decided
 * at run time. It is one -- TaskPropertiesController.save() (TaskProperties.kt:58) creates ONE
 * mutator, GanttDialogProperties.kt:40 commits it once, and MutatorImpl.commit()
 * (TaskImpl.kt:296-300) fires at most one event, guarded by a single `hasActualDatesChange` flag.
 * These tests pin that number so a later change is noticed.
 *
 * NOT MEASURED HERE: the real dialog on the screen. The path below is the code path of
 * GanttDialogProperties.OkAction, rebuilt without JavaFX.
 */
class TaskScheduleEventCountTest {

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

  private class Counter {
    val events = mutableListOf<TaskScheduleEvent>()
    fun idsOf(task: Task) = events.count { it.task.taskID == task.taskID }
  }

  private fun GanttProjectImpl.count(): Counter {
    val counter = Counter()
    taskManager.addTaskListener(TaskListenerAdapter().also {
      it.taskScheduleChangedHandler = { e -> counter.events.add(e) }
    })
    return counter
  }

  /**
   * The code path of GanttDialogProperties.OkAction: one mutator, start and duration set on it,
   * one commit, then the two algorithms the Ok button runs afterwards.
   */
  private fun editLikeTheDialog(task: Task, start: LocalDate, durationDays: Int) {
    val tm = task.manager
    val mutator = task.createMutator()
    mutator.setStart(GanttCalendar.fromLocalDate(start))
    mutator.setDuration(tm.createLength(durationDays.toLong()))
    mutator.commit()
    tm.algorithmCollection.effortDrivenDurationAlgorithm.run()
    tm.algorithmCollection.recalculateTaskScheduleAlgorithm.run()
  }

  @Test
  fun `ein einzelner vorgang meldet beim ueblichen bedienen genau ein taskScheduleChanged`() {
    val project = GanttProjectImpl()
    val tm = project.taskManager
    val task = tm.newTaskBuilder().withName("Vorgang")
      .withStartDate(LocalDate.of(2026, 8, 3).toModelDate())
      .withDuration(tm.createLength(5)).build()
    val counter = project.count()

    editLikeTheDialog(task, LocalDate.of(2026, 10, 5), 7)

    assertEquals(1, counter.events.size,
      "gezaehlte Ereignisse: " + counter.events.map { it.task.name })
    assertEquals(1, counter.idsOf(task))
  }

  /**
   * The counter-check that gives the number above its meaning: is the event bound to the commit
   * or to the field? Only the start is changed here -- if the event were bound to the field, the
   * first test would have to give two and this one one.
   */
  @Test
  fun `nur den start zu setzen meldet ebenfalls genau ein taskScheduleChanged`() {
    val project = GanttProjectImpl()
    val tm = project.taskManager
    val task = tm.newTaskBuilder().withName("Vorgang")
      .withStartDate(LocalDate.of(2026, 8, 3).toModelDate())
      .withDuration(tm.createLength(5)).build()
    val counter = project.count()

    val mutator = task.createMutator()
    mutator.setStart(GanttCalendar.fromLocalDate(LocalDate.of(2026, 10, 5)))
    mutator.commit()
    tm.algorithmCollection.effortDrivenDurationAlgorithm.run()
    tm.algorithmCollection.recalculateTaskScheduleAlgorithm.run()

    assertEquals(1, counter.events.size,
      "gezaehlte Ereignisse: " + counter.events.map { it.task.name })
  }

  /**
   * And the case that limits the number: a successor. `fireTaskScheduleChanged` runs the
   * scheduler BEFORE the event loop (TaskManagerImpl.java:722); the successor is moved along by
   * a mutator of its own, and that commit fires an event of its own.
   *
   * MEASURED: TWO events per Ok, and the successor's arrives FIRST -- the order is
   * [Zweiter, Erster]. It is nested, not sequential: the edited task's event is the outermost,
   * and everything the scheduler dragged along has already reported by the time it arrives.
   *
   * So the number from the first test holds only for the edited task itself. A question hanging
   * on this event without a filter would also come for tasks the user never touched.
   */
  @Test
  fun `ein nachfolger wird mitverschoben und meldet sich selbst`() {
    val project = GanttProjectImpl()
    val tm = project.taskManager
    val erst = tm.newTaskBuilder().withName("Erster")
      .withStartDate(LocalDate.of(2026, 8, 3).toModelDate())
      .withDuration(tm.createLength(5)).build()
    val zweit = tm.newTaskBuilder().withName("Zweiter")
      .withStartDate(LocalDate.of(2026, 8, 10).toModelDate())
      .withDuration(tm.createLength(5)).build()
    tm.dependencyCollection.createDependency(zweit, erst, FinishStartConstraintImpl())
    val counter = project.count()

    editLikeTheDialog(erst, LocalDate.of(2026, 10, 5), 7)

    assertEquals(LocalDate.of(2026, 10, 14), zweit.start.time.toModelLocalDate(),
      "Vorbedingung: der Nachfolger ist tatsaechlich mitgewandert")
    // The measured numbers. If they change, so does the assumption behind any later question
    // bound to this event.
    assertEquals(2, counter.events.size,
      "gezaehlte Ereignisse: " + counter.events.map { it.task.name })
    assertEquals(listOf("Zweiter", "Erster"), counter.events.map { it.task.name },
      "der mitverschobene Nachfolger meldet sich VOR dem bearbeiteten Vorgang")
    assertEquals(1, counter.idsOf(erst), "der bearbeitete Vorgang genau einmal")
    assertEquals(1, counter.idsOf(zweit), "der mitverschobene Nachfolger genau einmal")
  }
}
