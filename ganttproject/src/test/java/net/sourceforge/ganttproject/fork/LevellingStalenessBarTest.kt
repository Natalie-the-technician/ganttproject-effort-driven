/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
The permanently visible message, tested without a screen.

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.task.TaskManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * THE MESSAGE, AS FAR AS IT CAN BE CHECKED WITHOUT A SCREEN.
 *
 * These tests build the real JavaFX nodes on the real JavaFX thread and drive them the way the
 * program does: the mark changes, and the node has to follow; the button is fired, and the
 * levelling has to run. What they CANNOT say is whether the result is legible in the running
 * window — whether the text fits at 1024x768, whether it collides with the cloud lock on the left
 * or the notification buttons on the right. That needs an eye on the screen and is listed as such
 * in the report of 02.09.2026.
 *
 * The pattern with `Dispatchers.JavaFx` is the one `AssignmentAxesUiTest` uses, for the same
 * reason: JavaFX nodes may only be touched from their own thread.
 */
class LevellingStalenessBarTest {

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

  private val MONTAG: LocalDate = LocalDate.of(2026, 9, 14)

  private fun taskManager(): TaskManager =
    TestSetupHelper.newTaskManagerBuilder().withCalendar(WeekendCalendarImpl()).build()

  /**
   * NOTHING TO SAY, NOTHING SHOWN — and, just as important, nothing TAKING UP SPACE.
   *
   * Invisible alone is not enough: an invisible but managed node still occupies its width, and the
   * status bar would carry a gap that nobody could account for. Both flags are checked.
   *
   * RED against 9178b886b — the class does not exist there:
   *   e: file:///.../fork/LevellingStalenessBarTest.kt:88:19 Unresolved reference 'LevellingStalenessBar'.
   */
  @Test
  fun `with a clear mark the message is invisible and takes no space`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val staleness = LevellingStaleness(LevellingRunNotifier())
      val bar = LevellingStalenessBar(staleness, { })
      try {
        assertFalse(staleness.isStale, "Aufbau: nothing has happened")
        assertFalse(bar.node.isVisible, "with nothing to say the message is not shown")
        assertFalse(bar.node.isManaged, "and it must not take up width either")
      } finally {
        bar.detach()
        staleness.detach()
      }
    }
  }

  /**
   * A CHANGE IN THE PLAN REACHES THE MESSAGE. The whole chain, from a task event through the mark
   * to the node: nothing here pokes the bar directly.
   *
   * RED against 9178b886b — unresolved reference 'LevellingStalenessBar'.
   */
  @Test
  fun `a change in the plan makes the message appear`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val taskManager = taskManager()
      val staleness = LevellingStaleness(LevellingRunNotifier())
      taskManager.addTaskListener(staleness.taskListener)
      val bar = LevellingStalenessBar(staleness, { })
      try {
        taskManager.newTaskBuilder().withName("Angebot").withStartDate(MONTAG.toModelDate())
          .withDuration(taskManager.createLength(5L)).build()

        assertTrue(staleness.isStale, "Aufbau: the task event reached the mark")
        assertTrue(bar.node.isVisible, "and the mark reached the message")
        assertTrue(bar.node.isManaged, "which also means it now occupies its width")
      } finally {
        bar.detach()
        staleness.detach()
      }
    }
  }

  /**
   * AND IT GOES AWAY AGAIN when the levelling has run. A message that only ever appears is a
   * message that is always on, which is the thing this whole stage is trying not to build.
   *
   * RED against 9178b886b — unresolved reference 'LevellingStalenessBar'.
   */
  @Test
  fun `the message disappears when the mark is cleared`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val staleness = LevellingStaleness(LevellingRunNotifier())
      val bar = LevellingStalenessBar(staleness, { })
      try {
        staleness.markStale()
        assertTrue(bar.node.isVisible, "Aufbau: the message is up")

        staleness.clear()

        assertFalse(bar.node.isVisible, "and it goes down again")
        assertFalse(bar.node.isManaged, "and gives its width back")
      } finally {
        bar.detach()
        staleness.detach()
      }
    }
  }

  /**
   * THE BUTTON RUNS THE LEVELLING — once per press, and through the callback rather than through a
   * copy of the action's body. `button.fire()` is what a click does.
   *
   * RED against 9178b886b — unresolved reference 'LevellingStalenessBar'.
   */
  @Test
  fun `the button runs the levelling exactly once`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val staleness = LevellingStaleness(LevellingRunNotifier())
      var laeufe = 0
      val bar = LevellingStalenessBar(staleness, { laeufe++ })
      try {
        staleness.markStale()
        bar.button.fire()

        assertEquals(1, laeufe, "one press is one levelling run")
      } finally {
        bar.detach()
        staleness.detach()
      }
    }
  }

  /**
   * THE TEXTS COME FROM THE FORK BUNDLE, and from both halves of it. A key that is missing falls
   * through to the raw key name, which would show up in the status bar as `fork.staleness.message`
   * — visible nonsense rather than a failure.
   *
   * RED against 9178b886b — unresolved reference 'LevellingStalenessBar'; and the two keys are not
   * in either bundle there.
   */
  @Test
  fun `the message and the button carry real texts`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val staleness = LevellingStaleness(LevellingRunNotifier())
      val bar = LevellingStalenessBar(staleness, { })
      try {
        assertEquals(forkText("fork.staleness.message"), bar.label.text,
          "the message text comes out of the fork bundle")
        assertEquals(forkText("fork.staleness.button"), bar.button.text,
          "and so does the button")
        assertFalse(bar.label.text.startsWith("fork."),
          "a missing key falls through to the key name -- that would stand in the status bar")
        assertFalse(bar.button.text.startsWith("fork."),
          "same for the button")
      } finally {
        bar.detach()
        staleness.detach()
      }
    }
  }

  /**
   * A DETACHED BAR STOPS FOLLOWING. The status bar is built once per window, but a bar that keeps
   * a dead node updated is a leak waiting to be found later rather than now.
   *
   * COULD NOT BE RED against 9178b886b: no class, no listener, nothing to detach.
   */
  @Test
  fun `a detached bar no longer follows the mark`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val staleness = LevellingStaleness(LevellingRunNotifier())
      val bar = LevellingStalenessBar(staleness, { })
      try {
        bar.detach()
        staleness.markStale()
        assertFalse(bar.node.isVisible, "a detached bar hears nothing more")
      } finally {
        staleness.detach()
      }
    }
  }
}
