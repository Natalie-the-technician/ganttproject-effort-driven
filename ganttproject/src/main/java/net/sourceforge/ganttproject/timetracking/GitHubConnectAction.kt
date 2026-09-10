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
package net.sourceforge.ganttproject.timetracking

import javafx.application.Platform
import javafx.scene.control.ButtonType
import net.sourceforge.ganttproject.action.GPAction
import net.sourceforge.ganttproject.fork.GitHubConnectUi
import net.sourceforge.ganttproject.fork.forkText
import net.sourceforge.ganttproject.fork.gitHubConnectBox
import biz.ganttproject.app.DialogController
import biz.ganttproject.app.dialog
import java.awt.event.ActionEvent

/**
 * [fork change] Menu entry "Connect the hour journal to GitHub…".
 *
 * DELIBERATELY THIN, the same division as [TogglConnectionAction]: the thread and the window are
 * here, and nothing else. What the sign-in does lives in [GitHubConnection] and what it looks like
 * lives in `GitHubConnectPanel`, and both of those have tests. What is left in this class is what a
 * test cannot reach anyway.
 *
 * WHY A THREAD OF ITS OWN, and why it is not optional: the waiting between polls is a real
 * `Thread.sleep` and it can go on for a quarter of an hour. On the JavaFX thread the whole window
 * would be frozen for that long — no repaint, no menu — which looks exactly like a crash.
 *
 * WHY THE WINDOW STOPS THE POLLING WHEN IT CLOSES: [keepWaiting] is read before every poll. Without
 * it, closing the window would leave a thread asking GitHub every five seconds for a confirmation
 * nobody is going to give, until the code expires.
 */
class GitHubConnectAction @JvmOverloads constructor(
  private val connectionFactory: () -> GitHubConnection = {
    GitHubConnection(JdkHttpExchange(), OptionGitHubTokenStorage())
  },
  /** Injected so a test could run it inline; production passes a real thread. */
  private val runInBackground: (() -> Unit) -> Unit = { work ->
    Thread(work, "GitHub-Anmeldung").also { it.isDaemon = true }.start()
  },
) : GPAction("fork.github.menu") {

  /**
   * Read by the polling thread before every request, written by the JavaFX thread when the window
   * closes.
   *
   * `@Volatile` because the two are different threads and this is the one value they share. Without
   * it the loop could keep the old value in a register and go on asking after the window is gone —
   * exactly the behaviour the flow is built to avoid.
   */
  @Volatile
  private var keepWaiting = false

  /**
   * The label comes from this fork's bundle, not from GanttProject's translations — the key does not
   * exist there and the menu would show the bare id.
   *
   * CAREFUL when changing this: `GPAction`'s constructor calls `updateName()` (GPAction.java:112),
   * which calls this method while the base class is still being built, before any property of this
   * class exists. It works only because nothing here reads a field.
   */
  override fun getLocalizedName(): String = forkText("fork.github.menu")

  override fun actionPerformed(e: ActionEvent?) {
    val connection = connectionFactory()
    keepWaiting = true

    dialog(title = forkText("fork.github.title"), id = "fork.github.connect") { controller ->
      var ui = GitHubConnectUi(connected = connection.isConnected())
      controller.setContent(gitHubConnectBox(ui))
      controller.setupButton(ButtonType.CLOSE) { button ->
        button.text = forkText("fork.github.disconnect")
        button.setOnAction {
          connection.forget()
          keepWaiting = false
          controller.hide()
        }
      }
      // The window is the only thing that can end the waiting from outside.
      controller.onClosed = { keepWaiting = false }

      fun show(next: GitHubConnectUi) {
        ui = next
        // Back to the JavaFX thread. Touching a scene graph from the polling thread is a rule
        // violation that usually appears to work and occasionally paints rubbish.
        Platform.runLater { controller.setContent(gitHubConnectBox(ui)) }
      }

      if (!ui.connected) {
        show(ui.copy(message = forkText("fork.github.waiting")))
        runInBackground { connect(connection, ::show) }
      }
    }
  }

  /**
   * The whole sign-in, on the background thread.
   *
   * Every branch ends in a sentence on the screen. A sign-in that simply stops, leaving a code
   * nobody will ever confirm, is the one outcome worth ruling out — the person would sit there
   * waiting for a window that has given up.
   */
  private fun connect(connection: GitHubConnection, show: (GitHubConnectUi) -> Unit) {
    when (val start = connection.startConnecting()) {
      is DeviceCodeOutcome.Failed ->
        show(GitHubConnectUi(message = forkText("fork.github.failed", start.reason)))

      // Not wrapped in "the connection failed": nobody at this keyboard can do anything about it,
      // and the sentence already says who can.
      is DeviceCodeOutcome.NotEnabled -> show(GitHubConnectUi(message = start.reason))

      is DeviceCodeOutcome.Ready -> {
        show(
          GitHubConnectUi(
            userCode = start.prompt.userCode,
            verificationUri = start.prompt.verificationUri,
            message = forkText("fork.github.waiting")
          )
        )
        val outcome = connection.finishConnecting(start.prompt) { keepWaiting }
        show(
          GitHubConnectUi(
            connected = connection.isConnected(),
            message = when (outcome) {
              is DeviceFlowOutcome.Connected -> forkText("fork.github.ok")
              is DeviceFlowOutcome.Denied -> forkText("fork.github.denied")
              is DeviceFlowOutcome.Expired -> forkText("fork.github.expired")
              // The window is gone; there is nobody to tell.
              is DeviceFlowOutcome.Stopped -> null
              is DeviceFlowOutcome.Failed -> forkText("fork.github.failed", outcome.reason)
            }
          )
        )
      }
    }
  }
}
