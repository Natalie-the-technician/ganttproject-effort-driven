/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile

import android.content.Intent
import android.net.Uri
import android.os.Build
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

/** Extra the widget puts on its launch intent; see AgendaWidget. */
private const val WIDGET_TASK_EXTRA = "taskId"

class MainActivity : ComponentActivity() {

  private val viewModel: ProjectViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    // Opening straight from a file manager or a cloud app.
    val uri = intentUri(intent)
    if (uri != null) {
      viewModel.openUri(uri)
    } else if (intent.hasExtra(WIDGET_TASK_EXTRA)) {
      // Arrived from the home-screen widget: open the project the widget is
      // pointed at, so the user lands where they tapped rather than on an
      // empty start screen.
      viewModel.openWidgetProject()
    }

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
    val uri = intentUri(intent)
    if (uri != null) viewModel.openUri(uri)
    else if (intent.hasExtra(WIDGET_TASK_EXTRA)) viewModel.openWidgetProject()
  }

  /**
   * The URI a launch intent points at, whether the file was tapped
   * (ACTION_VIEW) or sent to us from a share sheet (ACTION_SEND).
   *
   * Share is the fallback that works when no filter matched: a cloud
   * provider may hand over a content:// URI with no file name in it and a
   * MIME type of its own choosing, and then "Share -> GanttProject Mobile"
   * is the only route that reaches us.
   */
  private fun intentUri(intent: Intent?): Uri? = when (intent?.action) {
    Intent.ACTION_VIEW -> intent.data
    Intent.ACTION_SEND ->
      @Suppress("DEPRECATION")
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
      } else {
        intent.getParcelableExtra(Intent.EXTRA_STREAM)
      }
    else -> null
  }
}
