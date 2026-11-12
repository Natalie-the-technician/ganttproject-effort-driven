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

import javafx.application.Platform
import javafx.event.Event
import javafx.scene.control.ButtonType
import javafx.scene.control.DialogPane
import javafx.scene.control.RadioButton
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.stage.Window
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives the real baseline-name collision dialog through [askBaselineNameCollisionBlocking], the
 * way the importer does: from a background thread, with the dialog on the JavaFX thread.
 * Needs an X server (xvfb), like the other tests of this module.
 */
class BaselineNameCollisionDialogTest {
  companion object {
    @BeforeAll
    @JvmStatic
    fun startJavaFx() {
      try {
        val started = CountDownLatch(1)
        Platform.startup { started.countDown() }
        started.await(30, TimeUnit.SECONDS)
      } catch (e: IllegalStateException) {
        // already running
      }
      // There is no main window here, so closing the first dialog would otherwise stop the toolkit.
      Platform.setImplicitExit(false)
    }

    // Dialogs of earlier tests, so that a test never drives a dialog it did not open.
    private val seenPanes = mutableSetOf<DialogPane>()
  }

  @Test
  fun `OK returns the chosen option`() {
    val answer = ask { pane ->
      pane.radioButton(BaselineCollisionAnswer.RENAME_IMPORTED).isSelected = true
      (pane.lookupButton(ButtonType.OK) as javafx.scene.control.Button).fire()
    }
    assertEquals(BaselineCollisionAnswer.RENAME_IMPORTED, answer)
    assertFxThreadResponds()
  }

  @Test
  fun `OK without a click returns the pre-selected option`() {
    val answer = ask { pane -> (pane.lookupButton(ButtonType.OK) as javafx.scene.control.Button).fire() }
    assertEquals(BaselineCollisionAnswer.KEEP_BOTH, answer)
    assertFxThreadResponds()
  }

  @Test
  fun `Escape returns no answer`() {
    val answer = ask { pane ->
      pane.radioButton(BaselineCollisionAnswer.OVERWRITE_EXISTING).isSelected = true
      Event.fireEvent(pane, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false))
    }
    assertNull(answer)
    assertFxThreadResponds()
  }

  @Test
  fun `OK and Escape each report exactly once`() {
    assertEquals(listOf(Optional.of(BaselineCollisionAnswer.KEEP_BOTH)), callsOf { pane ->
      (pane.lookupButton(ButtonType.OK) as javafx.scene.control.Button).fire()
    })
    assertEquals(listOf(Optional.empty<BaselineCollisionAnswer>()), callsOf { pane ->
      Event.fireEvent(pane, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false))
    })
  }

  /** Every call of the dialog's callback, collected until the dialog has been closed a while. */
  private fun callsOf(action: (DialogPane) -> Unit): List<Optional<BaselineCollisionAnswer>> {
    val calls = CopyOnWriteArrayList<Optional<BaselineCollisionAnswer>>()
    showBaselineNameCollisionDialog(listOf("A", "B")) { calls.add(Optional.ofNullable(it)) }
    val pane = waitForDialog()
    Platform.runLater { action(pane) }
    val deadline = System.currentTimeMillis() + 30_000
    while (calls.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(100)
    Thread.sleep(2_000)
    assertFxThreadResponds()
    return calls.toList()
  }

  private fun ask(action: (DialogPane) -> Unit): BaselineCollisionAnswer? {
    val result = CompletableFuture<BaselineCollisionAnswer?>()
    Thread {
      try {
        result.complete(askBaselineNameCollisionBlocking(listOf("A", "B")))
      } catch (e: Throwable) {
        result.completeExceptionally(e)
      }
    }.apply { isDaemon = true }.start()
    val pane = waitForDialog()
    Platform.runLater { action(pane) }
    return result.get(30, TimeUnit.SECONDS)
  }

  /**
   * Waits until a dialog this test has not seen yet is showing with its options in place, and
   * returns its pane. The time limits are generous because a loaded build machine can take
   * several seconds to show the first dialog.
   */
  private fun waitForDialog(): DialogPane {
    val deadline = System.currentTimeMillis() + 60_000
    while (System.currentTimeMillis() < deadline) {
      val pane = CompletableFuture<DialogPane?>()
      Platform.runLater {
        pane.complete(Window.getWindows().filter { it.isShowing }.firstNotNullOfOrNull { window ->
          val root = window.scene?.root
          ((root as? DialogPane) ?: root?.lookup(".dialog-pane") as? DialogPane)
            ?.takeIf { it !in seenPanes && it.lookupAll(".radio-button").size == 3 }
        })
      }
      pane.get(30, TimeUnit.SECONDS)?.let { seenPanes.add(it); return it }
      Thread.sleep(100)
    }
    throw AssertionError("the dialog did not show up")
  }

  private fun DialogPane.radioButton(answer: BaselineCollisionAnswer): RadioButton =
    lookupAll(".radio-button").filterIsInstance<RadioButton>().single { it.userData == answer }

  private fun assertFxThreadResponds() {
    val done = CountDownLatch(1)
    Platform.runLater { done.countDown() }
    assertTrue(done.await(15, TimeUnit.SECONDS), "the JavaFX thread hangs")
  }
}
