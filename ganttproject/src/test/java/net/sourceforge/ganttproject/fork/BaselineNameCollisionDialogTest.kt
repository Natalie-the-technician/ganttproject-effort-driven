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
import java.util.concurrent.CompletableFuture
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
    }
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
    return result.get(20, TimeUnit.SECONDS)
  }

  /** Waits until the dialog is showing with its options in place, and returns its pane. */
  private fun waitForDialog(): DialogPane {
    val deadline = System.currentTimeMillis() + 20_000
    while (System.currentTimeMillis() < deadline) {
      val pane = CompletableFuture<DialogPane?>()
      Platform.runLater {
        pane.complete(Window.getWindows().filter { it.isShowing }.firstNotNullOfOrNull { window ->
          val root = window.scene?.root
          ((root as? DialogPane) ?: root?.lookup(".dialog-pane") as? DialogPane)
            ?.takeIf { it.lookupAll(".radio-button").size == 3 }
        })
      }
      pane.get(5, TimeUnit.SECONDS)?.let { return it }
      Thread.sleep(100)
    }
    throw AssertionError("the dialog did not show up")
  }

  private fun DialogPane.radioButton(answer: BaselineCollisionAnswer): RadioButton =
    lookupAll(".radio-button").filterIsInstance<RadioButton>().single { it.userData == answer }

  private fun assertFxThreadResponds() {
    val done = CountDownLatch(1)
    Platform.runLater { done.countDown() }
    assertTrue(done.await(5, TimeUnit.SECONDS), "the JavaFX thread hangs")
  }
}
