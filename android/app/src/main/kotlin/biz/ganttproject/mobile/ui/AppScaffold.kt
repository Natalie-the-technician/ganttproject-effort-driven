/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.RadioButton
import biz.ganttproject.mobile.R
import androidx.compose.ui.text.input.PasswordVisualTransformation
import biz.ganttproject.mobile.core.DavError
import biz.ganttproject.mobile.core.EditScope
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

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AppScaffold(
  state: AppState,
  importState: ImportState,
  davState: DavSettingsState,
  viewModel: ProjectViewModel
) {
  var tab by rememberSaveable(stateSaver = TabSaver) { mutableStateOf(Tab.GANTT) }
  var menuOpen by remember { mutableStateOf(false) }
  var aboutOpen by remember { mutableStateOf(false) }
  var settingsOpen by remember { mutableStateOf(false) }
  var confirmCloseOpen by remember { mutableStateOf(false) }
  // The level the user just picked but has not confirmed yet. Non-null only
  // while the "are you sure" dialog is up.
  var pendingScope by remember { mutableStateOf<EditScope?>(null) }
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
            } else if (!state.editScope.allowsAppEdits) {
              // Not an error colour: this is the user's own choice, not
              // something that went wrong.
              Text(
                text = stringResource(R.string.editing_off_badge),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
            // Undo and redo sit left of save, in reading order: step back,
            // step forward, commit. Both stay visible but disabled when there
            // is nothing to step to, so their position never shifts.
            IconButton(
              onClick = { viewModel.undo() },
              enabled = state.project.canUndo && state.project.canEdit && !state.busy
            ) {
              Icon(
                Icons.AutoMirrored.Filled.Undo,
                contentDescription = stringResource(R.string.action_undo)
              )
            }
            IconButton(
              onClick = { viewModel.redo() },
              enabled = state.project.canRedo && state.project.canEdit && !state.busy
            ) {
              Icon(
                Icons.AutoMirrored.Filled.Redo,
                contentDescription = stringResource(R.string.action_redo)
              )
            }
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
              text = { Text(stringResource(R.string.action_settings)) },
              onClick = { menuOpen = false; settingsOpen = true }
            )
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
          davConfigured = davState.configured,
          onOpenClick = { openFile.launch(arrayOf("*/*")) },
          onRecentClick = viewModel::openUri,
          onClearRecent = viewModel::clearRecentFiles,
          onListRemote = viewModel::listRemoteProjects,
          onOpenRemote = viewModel::openRemote
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

  if (settingsOpen) {
    AlertDialog(
      onDismissRequest = { settingsOpen = false },
      title = { Text(stringResource(R.string.action_settings)) },
      text = {
        Column(
          modifier = Modifier.verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          // ---------------------------------------------- Edit protection
          Text(
            stringResource(R.string.editing_section),
            style = MaterialTheme.typography.labelMedium
          )
          EditScope.entries.forEach { scope ->
            EditScopeRow(
              scope = scope,
              selected = state.editScope == scope,
              onSelect = {
                // Turning protection up asks first; turning it down never
                // does. Confirming a cautious choice trains people to click
                // through the dialog that matters.
                if (viewModel.warnBeforeWidening(scope)) pendingScope = scope
                else viewModel.setEditScope(scope)
              }
            )
          }

          HorizontalDivider()

          SyncServerSection(davState, viewModel)

          HorizontalDivider()

          Text(stringResource(R.string.widget_settings), style = MaterialTheme.typography.labelMedium)
          Text(stringResource(R.string.widget_project), style = MaterialTheme.typography.labelMedium)
          Text(
            state.widget.projectName ?: stringResource(R.string.widget_no_project),
            style = MaterialTheme.typography.bodyMedium
          )
          // Only the open project can be picked: its URI permission is the
          // one the app persisted, and a widget cannot run a file picker.
          TextButton(
            onClick = { viewModel.useOpenProjectForWidget() },
            enabled = state.project != null
          ) {
            Text(stringResource(R.string.widget_use_current))
          }

          HorizontalDivider()

          Text(stringResource(R.string.widget_window), style = MaterialTheme.typography.labelMedium)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            biz.ganttproject.mobile.core.AgendaWindow.CHOICES.forEach { days ->
              FilterChip(
                selected = state.widget.windowDays == days,
                onClick = { viewModel.setWidgetWindowDays(days) },
                label = {
                  Text(
                    if (days == 0) stringResource(R.string.widget_window_started)
                    else stringResource(R.string.widget_window_days, days)
                  )
                }
              )
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { settingsOpen = false }) {
          Text(stringResource(R.string.action_ok))
        }
      }
    )
  }

  // The one thing standing between a tap in the settings and a phone that can
  // silently lose work. Deliberately spells out what is at risk and what is
  // not, because "are you sure?" gets answered yes by reflex.
  pendingScope?.let { target ->
    AlertDialog(
      onDismissRequest = { pendingScope = null },
      title = { Text(stringResource(R.string.editing_warning_title)) },
      text = {
        Text(
          stringResource(R.string.editing_warning_body),
          modifier = Modifier.verticalScroll(rememberScrollState())
        )
      },
      confirmButton = {
        TextButton(onClick = { viewModel.setEditScope(target); pendingScope = null }) {
          Text(stringResource(R.string.editing_warning_confirm))
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingScope = null }) {
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
  davConfigured: Boolean,
  onOpenClick: () -> Unit,
  onRecentClick: (android.net.Uri) -> Unit,
  onClearRecent: () -> Unit,
  onListRemote: () -> Unit,
  onOpenRemote: (String) -> Unit
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
          // Only offered once a server is configured. An always-visible
          // button that answers "not configured" teaches the user to ignore
          // it.
          if (davConfigured) {
            TextButton(onClick = onListRemote) {
              Text(stringResource(R.string.sync_open_remote))
            }
          }
        }
      }
    }

    if (state.remoteProjects.isNotEmpty()) {
      items(state.remoteProjects, key = { "remote:$it" }) { name ->
        ListItem(
          headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
          leadingContent = { Icon(Icons.Filled.CloudDownload, contentDescription = null) },
          modifier = Modifier.fillMaxWidth().clickable { onOpenRemote(name) }
        )
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

/**
 * One line of the edit-protection setting: a radio button, the level, and a
 * plain-language sentence about what it means.
 *
 * The explanation sits next to each option rather than in one paragraph above
 * them, because the choice being made here is about what can be lost, and
 * that is not obvious from three labels alone.
 */
/**
 * Server address, credentials and the connection check.
 *
 * The check is not decoration. It is the only place the user finds out
 * whether this server can lock — and therefore whether a conflict gets
 * prevented or merely reported afterwards. Both outcomes are stated in plain
 * words rather than as a green tick, because they mean genuinely different
 * things for the safety of their data.
 */
@Composable
private fun SyncServerSection(dav: DavSettingsState, viewModel: ProjectViewModel) {
  Text(stringResource(R.string.sync_settings), style = MaterialTheme.typography.labelMedium)
  Text(stringResource(R.string.sync_hint), style = MaterialTheme.typography.bodySmall)

  OutlinedTextField(
    value = dav.baseUrl,
    onValueChange = viewModel::setDavBaseUrl,
    label = { Text(stringResource(R.string.sync_url)) },
    placeholder = { Text(stringResource(R.string.sync_url_hint)) },
    singleLine = true,
    modifier = Modifier.fillMaxWidth()
  )
  OutlinedTextField(
    value = dav.username,
    onValueChange = viewModel::setDavUsername,
    label = { Text(stringResource(R.string.sync_user)) },
    singleLine = true,
    modifier = Modifier.fillMaxWidth()
  )
  OutlinedTextField(
    value = dav.password,
    onValueChange = viewModel::setDavPassword,
    label = { Text(stringResource(R.string.sync_password)) },
    singleLine = true,
    visualTransformation = PasswordVisualTransformation(),
    modifier = Modifier.fillMaxWidth()
  )

  TextButton(
    onClick = { viewModel.checkDavConnection() },
    enabled = dav.configured && !dav.checking
  ) {
    Text(stringResource(if (dav.checking) R.string.sync_checking else R.string.sync_check))
  }

  when (val checked = dav.checked) {
    null -> Unit
    is DavCheck.Reachable -> Text(
      stringResource(
        if (checked.supportsLocking) R.string.sync_ok_locking else R.string.sync_ok_no_locking
      ),
      style = MaterialTheme.typography.bodySmall
    )
    is DavCheck.Failed -> Text(
      davErrorText(checked.error),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.error
    )
  }
}

/**
 * Turns a [DavError] into something the user can act on.
 *
 * Each case names what to do next rather than what went wrong technically:
 * a wrong password and an unreachable server both read as "it did not work"
 * otherwise, and they call for completely different responses.
 */
@Composable
private fun davErrorText(error: DavError): String = when (error) {
  DavError.Insecure -> stringResource(R.string.sync_err_insecure)
  DavError.Unauthorized -> stringResource(R.string.sync_err_auth)
  DavError.Forbidden -> stringResource(R.string.sync_err_forbidden)
  DavError.NotFound -> stringResource(R.string.sync_err_notfound)
  is DavError.Network -> stringResource(R.string.sync_err_network, error.detail)
  is DavError.Server -> stringResource(R.string.sync_err_server, error.code)
  // Reached only if a check ever performs a conditional write, which it does
  // not. Shown as a server error rather than silently as nothing.
  DavError.ChangedElsewhere -> stringResource(R.string.sync_err_server, 412)
  DavError.LockedElsewhere -> stringResource(R.string.sync_err_server, 423)
}

@Composable
private fun EditScopeRow(scope: EditScope, selected: Boolean, onSelect: () -> Unit) {
  val label = when (scope) {
    EditScope.READ_ONLY -> R.string.editing_read_only
    EditScope.APP_ONLY -> R.string.editing_app_only
    EditScope.APP_AND_WIDGET -> R.string.editing_app_and_widget
  }
  val hint = when (scope) {
    EditScope.READ_ONLY -> R.string.editing_read_only_hint
    EditScope.APP_ONLY -> R.string.editing_app_only_hint
    EditScope.APP_AND_WIDGET -> R.string.editing_app_and_widget_hint
  }
  Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
    verticalAlignment = Alignment.Top
  ) {
    RadioButton(selected = selected, onClick = onSelect)
    Column(modifier = Modifier.padding(top = 12.dp)) {
      Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
      Text(
        stringResource(hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}

/**
 * Shown at the top of every editing screen while edit protection is on.
 *
 * A banner rather than hidden controls: the greyed-out slider next to it says
 * "not now", and this says why and where to change it. Hiding the controls
 * entirely would leave the user hunting for a feature they know exists.
 */
@Composable
internal fun EditingOffBanner() {
  Card(modifier = Modifier.fillMaxWidth()) {
    Text(
      text = stringResource(R.string.editing_off_banner),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(12.dp)
    )
  }
}
