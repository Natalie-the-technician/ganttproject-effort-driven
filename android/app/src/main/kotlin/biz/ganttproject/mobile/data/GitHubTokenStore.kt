/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.data

import android.util.Log
import biz.ganttproject.mobile.core.GitHubLog
import biz.ganttproject.mobile.core.GitHubTokenStorage

/**
 * The GitHub token pair on [SecureStore]: AES-GCM, key generated inside the
 * Android keystore and not extractable, file excluded from cloud backup.
 *
 * NOTHING WAS BUILT FOR THIS. The mechanism already carries the Toggl token
 * and the WebDAV password; this is the third caller. That is the whole
 * difference from the desktop half, where the same question needs a probe, a
 * choice of three back ends and a warning for the machines that have none.
 *
 * ## The one thing that had to be added: reading it back
 *
 * `SecureStore.putSecret` swallows an encryption failure — `runCatching { … }
 * .getOrNull() ?: return` — and there is a reason for that (a keystore entry
 * can vanish after a device restore, and crashing on every launch is worse).
 * The consequence here is particular, though: a connection that looks made and
 * is not stored sends the person back to the browser at the next start with
 * nothing said. So the value is read back once, and if it did not stick, that
 * fact goes in the log rather than being discovered by somebody wondering why
 * they keep typing codes.
 */
class SecureStoreGitHubTokens(private val secure: SecureStore) : GitHubTokenStorage {

  override fun readTokens(): String? = secure.getSecret(SecureStore.KEY_GITHUB_TOKENS)

  override fun writeTokens(value: String?) {
    secure.putSecret(SecureStore.KEY_GITHUB_TOKENS, value)
    if (value != null && secure.getSecret(SecureStore.KEY_GITHUB_TOKENS) == null) {
      // NEVER the value itself, not even shortened. What is worth saying is
      // that it did not stick and what follows from that.
      GitHubLog.sink(
        "[fork] the GitHub sign-in could not be kept on this device — the keystore refused it. " +
          "The connection will have to be made again at the next start."
      )
    }
  }
}

/**
 * Sends this fork's GitHub notes to the Android log.
 *
 * `gantt-core` keeps a sink that does nothing, because the module has no
 * Android dependency on purpose. This is the one line that gives it one, and
 * it belongs here rather than in the core for exactly that reason.
 *
 * Call it once, at start-up. Everything that reaches this sink is written by
 * `GitHubDeviceFlow` and `GitHubConnection`, and both are tested against a
 * check that no token ever gets there.
 */
fun installGitHubLog() {
  GitHubLog.sink = { line -> Log.i("GanttFork", line) }
}
