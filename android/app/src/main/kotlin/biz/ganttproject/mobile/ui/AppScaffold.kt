/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import biz.ganttproject.mobile.R
import biz.ganttproject.mobile.data.FileError

private enum class Tab { GANTT, RESOURCES, IMPORT }

/**
 * Tabs survive rotation as an ordinal rather than as the enum itself.
 * rememberSaveable only accepts what a Bundle accepts, and relying on enums
 * being Serializable would fail at runtime, not at compile time - the worst
 * kind of bug to ship in a screen the user reaches on first launch.
 */
private val TabSaver = androidx.compose.runtime.saveable.Saver<Tab, Int>(
  save = { it.ordinal },
  restore = { Tab.entries[it] }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
  state: AppState,
  importState: ImportState,
  viewModel: ProjectViewModel
) {
  var tab by rememberSaveable(stateSaver = TabSaver) { mutableStateOf(Tab.GANTT) }
  var menuOpen by remember { mutableStateOf(false) }
  var aboutOpen by remember { mutableStateOf(false) }
  var confirmCloseOpen by remember { mutableStateOf(false) }
  val snackbarHost = remember { SnackbarHostState() }

  // The picker asks for any type: GanttProject files have no registered MIME
  // type, so restricting it would hide .gan files on most providers.
  val openFile = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument()
  ) { uri -> uri?.let(viewModel::openUri) }

  // Used to rescue work when the file changed elsewhere: the user picks a new
  // name and keeps both versions instead of one of them being lost.
  val saveCopy = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument("application/xml")
  ) { uri -> uri?.let(viewModel::saveCopyTo) }

  val savedText = stringResource(R.string.action_saved)
  // Resolved here rather than inside the effect: stringResource is a
  // composable and cannot be called from a coroutine.
  val importedTemplate = stringResource(R.string.import_done, "%s")
  LaunchedEffect(state.notice) {
    when (val notice = state.notice) {
      Notice.Saved -> snackbarHost.showSnackbar(savedText)
      is Notice.HoursImported ->
        snackbarHost.showSnackbar(importedTemplate.format(hours(notice.hours)))
      null -> Unit
    }
    if (state.notice != null) viewModel.dismissNotice()
  }

  Scaffold(
    snackbarHost = { SnackbarHost(snackbarHost) },
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(
              text = state.project?.displayName ?: stringResource(R.string.home_title),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            if (state.project?.isReadOnly == true) {
              Text(
                text = stringResource(R.string.read_only),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
              )
            } else if (state.project?.isDirty == true) {
              Text(
                text = stringResource(R.string.unsaved_changes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
              )
            }
          }
        },
        actions = {
          if (state.project != null) {
            IconButton(
              onClick = viewModel::save,
              enabled = state.project.isDirty && !state.project.isReadOnly && !state.busy
            ) {
              Icon(Icons.Default.Save, contentDescription = stringResource(R.string.action_save))
            }
          }
          IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = null)
          }
          DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.action_open_file)) },
              onClick = { menuOpen = false; openFile.launch(arrayOf("*/*")) }
            )
            if (state.project != null) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.action_close)) },
                onClick = { menuOpen = false; viewModel.saveAndCloseProject() }
              )
              // The escape hatch that makes auto-saving safe. Only offered
              // when there is actually something to discard.
              if (state.project.isDirty) {
                DropdownMenuItem(
                  text = { Text(stringResource(R.string.action_close_discard)) },
                  onClick = { menuOpen = false; confirmCloseOpen = true }
                )
              }
            }
            DropdownMenuItem(
              text = { Text(stringResource(R.string.action_about)) },
              onClick = { menuOpen = false; aboutOpen = true }
            )
          }
        }
      )
    },
    bottomBar = {
      if (state.project != null) {
        NavigationBar {
          NavigationBarItem(
            selected = tab == Tab.GANTT,
            onClick = { tab = Tab.GANTT },
            icon = { Icon(Icons.Default.BarChart, contentDescription = null) },
            label = { Text(stringResource(R.string.tab_gantt)) }
          )
          NavigationBarItem(
            selected = tab == Tab.RESOURCES,
            onClick = { tab = Tab.RESOURCES },
            icon = { Icon(Icons.Default.Groups, contentDescription = null) },
            label = { Text(stringResource(R.string.tab_resources)) }
          )
          NavigationBarItem(
            selected = tab == Tab.IMPORT,
            onClick = { tab = Tab.IMPORT },
            icon = { Icon(Icons.Default.Schedule, contentDescription = null) },
            label = { Text(stringResource(R.string.tab_import)) }
          )
        }
      }
    }
  ) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
      // Bound to a local so the screens take a non-null ProjectUi without
      // relying on a smart cast through two negated when-conditions.
      val project = state.project
      when {
        state.busy && project == null -> LoadingBox()
        project == null -> HomeScreen(
          state = state,
          onOpenClick = { openFile.launch(arrayOf("*/*")) },
          onRecentClick = viewModel::openUri,
          onClearRecent = viewModel::clearRecentFiles
        )
        else -> when (tab) {
          Tab.GANTT -> GanttScreen(project, viewModel)
          Tab.RESOURCES -> ResourcesScreen(project, viewModel)
          Tab.IMPORT -> ImportScreen(project, importState, viewModel)
        }
      }
    }
  }

  state.fileError?.let { error ->
    if (error == FileError.ChangedElsewhere) {
      // A conflict is not just bad news, it is a decision. The dialog offers
      // the two ways out rather than only acknowledging the problem: keep
      // both versions, or knowingly replace the other one.
      AlertDialog(
        onDismissRequest = viewModel::dismissError,
        title = { Text(stringResource(R.string.conflict_title)) },
        text = { Text(error.text()) },
        confirmButton = {
          TextButton(onClick = {
            val name = state.project?.displayName?.removeSuffix(".gan") ?: "project"
            viewModel.dismissError()
            saveCopy.launch("$name-phone.gan")
          }) {
            Text(stringResource(R.string.action_save_copy))
          }
        },
        dismissButton = {
          Row {
            TextButton(onClick = viewModel::dismissError) {
              Text(stringResource(R.string.action_cancel))
            }
            TextButton(onClick = viewModel::overwriteAnyway) {
              Text(stringResource(R.string.action_overwrite))
            }
          }
        }
      )
    } else {
      AlertDialog(
        onDismissRequest = viewModel::dismissError,
        title = { Text(stringResource(R.string.error_title)) },
        text = { Text(error.text()) },
        confirmButton = {
          TextButton(onClick = viewModel::dismissError) { Text(stringResource(R.string.action_ok)) }
        }
      )
    }
  }

  if (confirmCloseOpen) {
    AlertDialog(
      onDismissRequest = { confirmCloseOpen = false },
      title = { Text(stringResource(R.string.discard_title)) },
      text = { Text(stringResource(R.string.discard_body)) },
      confirmButton = {
        TextButton(onClick = { confirmCloseOpen = false; viewModel.closeProject() }) {
          Text(stringResource(R.string.discard_confirm))
        }
      },
      dismissButton = {
        TextButton(onClick = { confirmCloseOpen = false }) {
          Text(stringResource(R.string.action_cancel))
        }
      }
    )
  }

  if (aboutOpen) {
    AlertDialog(
      onDismissRequest = { aboutOpen = false },
      title = { Text(stringResource(R.string.app_name)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            stringResource(R.string.about_version, biz.ganttproject.mobile.BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.labelMedium
          )
          Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodyMedium)
        }
      },
      confirmButton = {
        TextButton(onClick = { aboutOpen = false }) { Text(stringResource(R.string.action_ok)) }
      }
    )
  }
}

@Composable
private fun LoadingBox() {
  Column(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    CircularProgressIndicator()
  }
}

@Composable
private fun HomeScreen(
  state: AppState,
  onOpenClick: () -> Unit,
  onRecentClick: (android.net.Uri) -> Unit,
  onClearRecent: () -> Unit
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    item {
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier = Modifier.padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text(
            stringResource(R.string.home_empty_title),
            style = MaterialTheme.typography.titleMedium
          )
          Text(
            stringResource(R.string.home_empty_body),
            style = MaterialTheme.typography.bodyMedium
          )
          TextButton(onClick = onOpenClick) {
            Text(stringResource(R.string.action_open_file))
          }
        }
      }
    }

    if (state.recentFiles.isNotEmpty()) {
      item {
        Column {
          Text(
            stringResource(R.string.recent_files),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp)
          )
        }
      }
      items(state.recentFiles, key = { it.uri }) { recent ->
        ListItem(
          headlineContent = { Text(recent.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
          leadingContent = {
            Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null)
          },
          modifier = Modifier
            .fillMaxWidth()
            .clickable { onRecentClick(android.net.Uri.parse(recent.uri)) }
        )
      }
      item {
        TextButton(onClick = onClearRecent) { Text(stringResource(R.string.recent_clear)) }
      }
    }
  }
}
