/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import biz.ganttproject.mobile.R

/**
 * What the connect card shows, and nothing more.
 *
 * A plain value rather than a handle on the flow: the flow blocks for up to a
 * quarter of an hour, and nothing that blocks may be reachable from a
 * composable. [ProjectViewModel] runs it on a thread of its own and pushes
 * these into the screen.
 */
data class GitHubConnectState(
  /** True while a code is being fetched or the confirmation is being waited for. */
  val busy: Boolean = false,
  /** The eight characters to type, once GitHub has sent them. */
  val userCode: String? = null,
  /** Where to type them — `https://github.com/login/device`. */
  val verificationUri: String? = null,
  /** Whether a token pair is on file. */
  val connected: Boolean = false,
  /** What went wrong, or what happened. Already a sentence. */
  val message: String? = null
)

/**
 * Connecting the hour journal to GitHub: the code, the address, and what came
 * of it.
 *
 * ## Why the code is a selectable monospace line and not a label
 *
 * GitHub's own documentation makes the point that the browser may be **on a
 * different device**, and on a phone that is the likely case rather than the
 * exception. A code that can only be tapped is no use to somebody typing it on
 * a laptop; it has to be readable across a desk and copyable if the browser is
 * on this device after all. Hence: large, monospace (so `0` and `O`, `1` and
 * `l` are told apart), inside a [SelectionContainer], and with a copy button
 * beside it.
 *
 * ## Why "open the page" is a button and not automatic
 *
 * Opening a browser by itself, from a screen the person did not ask to leave,
 * is the behaviour that makes people distrust an app. And it would be the
 * wrong thing exactly in the case above — where the browser is elsewhere.
 */
@Composable
fun GitHubConnectCard(
  state: GitHubConnectState,
  onConnect: () -> Unit,
  onCancel: () -> Unit,
  onDisconnect: () -> Unit
) {
  val context = LocalContext.current
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Text(stringResource(R.string.github_title), style = MaterialTheme.typography.titleMedium)

      if (state.userCode != null) {
        Text(
          stringResource(R.string.github_step_1),
          style = MaterialTheme.typography.bodyMedium
        )
        // The code itself. SelectionContainer is what makes it copyable by
        // long press as well as by the button.
        SelectionContainer {
          Text(
            state.userCode,
            fontFamily = FontFamily.Monospace,
            fontSize = 32.sp,
            letterSpacing = 4.sp,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
          )
        }
        SelectionContainer {
          Text(
            state.verificationUri.orEmpty(),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium
          )
        }
        Text(
          stringResource(R.string.github_other_device),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(onClick = { copyToClipboard(context, state.userCode) }) {
            Text(stringResource(R.string.github_copy_code))
          }
          state.verificationUri?.let { uri ->
            TextButton(onClick = { openPage(context, uri) }) {
              Text(stringResource(R.string.github_open_page))
            }
          }
          TextButton(onClick = onCancel) { Text(stringResource(R.string.github_cancel)) }
        }
      }

      if (state.busy) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(4.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          CircularProgressIndicator()
          Text(
            stringResource(R.string.github_waiting),
            style = MaterialTheme.typography.bodySmall
          )
        }
      }

      state.message?.let { message ->
        Text(
          message,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }

      if (!state.busy) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (state.connected) {
            Text(
              stringResource(R.string.github_connected),
              style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = onDisconnect) {
              Text(stringResource(R.string.github_disconnect))
            }
          } else {
            Button(onClick = onConnect) { Text(stringResource(R.string.github_connect)) }
          }
        }
      }
    }
  }
}

/**
 * The user code on the clipboard.
 *
 * The USER code, never a token. It is meant to be read out and typed; there is
 * nothing in it worth protecting, and refusing to copy it would only make
 * somebody transcribe eight characters by hand.
 */
private fun copyToClipboard(context: Context, code: String) {
  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
  clipboard.setPrimaryClip(ClipData.newPlainText("GitHub code", code))
}

/**
 * Opens GitHub's page in whatever browser this device has.
 *
 * A missing browser is a real state on a stripped-down device, and it is not a
 * failure of the sign-in: the address is on the screen and the code can be
 * typed anywhere. So the exception is swallowed rather than turned into an
 * error that would suggest the connection had gone wrong.
 */
private fun openPage(context: Context, uri: String) {
  try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
  } catch (e: Exception) {
    // Nothing to do: the address is legible on the screen either way.
  }
}
