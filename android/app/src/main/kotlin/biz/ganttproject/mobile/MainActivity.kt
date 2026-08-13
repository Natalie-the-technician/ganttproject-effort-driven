/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import biz.ganttproject.mobile.ui.AppScaffold
import biz.ganttproject.mobile.ui.ProjectViewModel
import biz.ganttproject.mobile.ui.theme.GanttMobileTheme

class MainActivity : ComponentActivity() {

  private val viewModel: ProjectViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    // Opening straight from a file manager or a cloud app.
    intentUri(intent)?.let(viewModel::openUri)

    // Auto-save whenever the app leaves the foreground.
    //
    // ON_STOP rather than ON_PAUSE: pause fires for a dialog or the
    // notification shade, which would write the file constantly. Stop is the
    // point at which Android may kill the process without warning, so it is
    // the last moment where saving still helps.
    lifecycle.addObserver(object : DefaultLifecycleObserver {
      override fun onStop(owner: LifecycleOwner) {
        viewModel.saveIfDirty()
      }
    })

    setContent {
      GanttMobileTheme {
        val state by viewModel.state.collectAsStateWithLifecycle()
        val importState by viewModel.importState.collectAsStateWithLifecycle()
        AppScaffold(state = state, importState = importState, viewModel = viewModel)
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    intentUri(intent)?.let(viewModel::openUri)
  }

  private fun intentUri(intent: Intent?): Uri? =
    if (intent?.action == Intent.ACTION_VIEW) intent.data else null
}
