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

import net.sourceforge.ganttproject.GPLogger
import net.sourceforge.ganttproject.action.GPAction
import net.sourceforge.ganttproject.fork.forkText
import net.sourceforge.ganttproject.resource.HumanResourceManager
import java.awt.event.ActionEvent
import java.time.LocalDate
import javax.swing.SwingUtilities

/**
 * Menu entry "Check Toggl connection".
 *
 * WHY THIS EXISTS: everything below it has only ever spoken to recorded answers. Two assumptions
 * are still unproven — that the token goes into the USERNAME field, and that the timeouts are
 * generous enough. This finds that out while nothing is at stake: **the check writes nothing**.
 * No task, no property, no ledger.
 *
 * WHY A SEPARATE THREAD: the request may sit at the response timeout of 30 seconds (see
 * `HttpClientBackend`). Run on the event dispatch thread, the whole window would be frozen for
 * that long — no repaint, no menu, and it looks exactly like a crash. So the request runs on its
 * own thread and only the answer is handed back to the interface.
 *
 * The heavy lifting is deliberately NOT here: [checkTogglConnection] does the work and
 * [connectionCheckMessage] builds the text, both plain functions with tests. What is left in this
 * class is the part a test cannot reach anyway — the thread and the dialog.
 */
fun interface ConnectionCheckMessageSink {
  /**
   * @param isProblem picks how the message is presented. Passed as a plain flag so this file needs
   * no knowledge of the notification machinery, and the caller stays free to show it differently.
   */
  fun show(isProblem: Boolean, message: String)
}

class TogglConnectionAction @JvmOverloads constructor(
  // @JvmOverloads: the defaults below are invisible to Java without it, and this class is
  // constructed from GanttProject.java.
  private val resourceManager: HumanResourceManager,
  private val showMessage: ConnectionCheckMessageSink,
  /** Injected so a test could run it inline; production passes a real thread. */
  private val runInBackground: (() -> Unit) -> Unit = { work ->
    Thread(work, "Toggl-Verbindungstest").also { it.isDaemon = true }.start()
  },
  /**
   * Back to the interface thread. Swing components may only be touched from the event dispatch
   * thread; calling [showMessage] straight from the background thread would be a rule violation
   * that usually appears to work and occasionally paints garbage or deadlocks.
   */
  private val runOnUiThread: (() -> Unit) -> Unit = { work -> SwingUtilities.invokeLater(work) },
  private val backendFactory: () -> HttpBackend = { HttpClientBackend() },
  private val today: () -> LocalDate = { LocalDate.now() }
) : GPAction("toggl.checkConnection") {

  companion object {
    /**
     * Heading of the notification box, for whoever presents the message.
     *
     * A getter, not a `const`: the language can change while the program runs, and a constant
     * would keep whatever language was active when the class was first touched. `@JvmStatic` so
     * `GanttProject.java` can reach it without going through the file facade class.
     */
    @JvmStatic
    val notificationTitle: String get() = forkText("fork.toggl.check.title")
  }

  /**
   * The label comes from the fork bundle, not from GanttProject's translations — the key does not
   * exist there and the menu would show the bare id.
   *
   * CAREFUL when changing this: `GPAction`'s constructor calls `updateName()` (GPAction.java:112),
   * which calls this method while the base class is still being built — before any property of
   * this class exists. It works only because nothing here reads a field. Use one, and the label is
   * null at construction time with nothing to indicate why.
   */
  override fun getLocalizedName(): String = forkText("fork.toggl.checkConnection")

  override fun actionPerformed(e: ActionEvent?) {
    // Read the resources HERE, on the interface thread that owns them, and hand the background
    // thread only what it needs. Touching the resource manager from the other thread would be a
    // race for a value that is on screen at the same time.
    val resources = resourceManager.resources.toList()
    val storedTokens = TogglTokenOptions.tokens.value

    // Non-blocking on purpose: the answer can take up to 30 seconds, and a message the user has
    // to click away first would be worse than no message at all.
    showMessage.show(false, forkText("fork.toggl.check.running"))

    runInBackground {
      val result = try {
        checkTogglConnection(TogglClient(backendFactory()), resources, storedTokens, today())
      } catch (ex: Exception) {
        // A check must never take the application down with it. Anything unforeseen becomes an
        // ordinary "not reachable" for the user and a full entry in the log.
        GPLogger.log(ex)
        ConnectionCheckResult.Failed("", TogglFailure.UNAVAILABLE, ex.message ?: "")
      }
      // The English developer text goes to the log; the user reads the translated message.
      if (result is ConnectionCheckResult.Failed) {
        GPLogger.log("Toggl connection check failed: ${result.failure} ${result.message}")
      }
      val message = connectionCheckMessage(result)
      // "No token stored" counts as a problem too: the user asked for a check and got none.
      val isProblem = result !is ConnectionCheckResult.Ok
      runOnUiThread { showMessage.show(isProblem, message) }
    }
  }
}
