/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import biz.ganttproject.mobile.R
import biz.ganttproject.mobile.core.ProjectModel
import biz.ganttproject.mobile.ui.theme.ChartColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Imports hours from Toggl Track.
 *
 * Two rules shape this screen. First, nothing is written without a preview:
 * the user sees the exact hours and tasks before anything touches the
 * document. Second, only a *certain* match is pre-selected — a plausible
 * guess is left blank, because a pre-filled guess gets confirmed by reflex
 * and lands hours on the wrong task.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
  project: ProjectUi,
  state: ImportState,
  viewModel: ProjectViewModel
) {
  val model = project.model
  val plan = remember(state.rows, project.revision) { viewModel.currentPlan() }

  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    item { TokenCard(state, viewModel) }
    item { RangeCard(state, viewModel) }

    state.error?.let { error ->
      item {
        Card(modifier = Modifier.fillMaxWidth()) {
          Text(
            error.text(),
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
          )
        }
      }
    }

    if (state.busy) {
      item {
        Row(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          horizontalArrangement = Arrangement.Center
        ) {
          CircularProgressIndicator()
        }
      }
    }

    if (state.loaded && state.rows.isEmpty() && !state.busy) {
      item {
        Text(
          stringResource(R.string.import_no_entries),
          style = MaterialTheme.typography.bodyMedium
        )
      }
    }

    if (state.rows.isNotEmpty()) {
      item {
        Text(
          stringResource(
            R.string.import_entries_found,
            state.rows.size,
            hours(state.rows.sumOf { it.entry.hours })
          ),
          style = MaterialTheme.typography.titleSmall
        )
      }
      items(state.rows, key = { it.entry.id }) { row ->
        EntryCard(row = row, model = model, viewModel = viewModel)
      }
      item { PreviewCard(plan = plan, state = state, viewModel = viewModel) }
    }
  }
}

@Composable
private fun TokenCard(state: ImportState, viewModel: ProjectViewModel) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Text(stringResource(R.string.import_title), style = MaterialTheme.typography.titleMedium)
      OutlinedTextField(
        value = state.token,
        onValueChange = viewModel::setToken,
        label = { Text(stringResource(R.string.import_token)) },
        singleLine = true,
        // The token is a credential; it should not be readable over a
        // shoulder or captured in a screenshot of the screen.
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
      )
      Text(
        stringResource(R.string.import_token_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = viewModel::saveToken) {
          Text(stringResource(R.string.import_token_save))
        }
        TextButton(onClick = viewModel::verifyToken, enabled = !state.busy) {
          Text(stringResource(R.string.import_token_verify))
        }
      }
      state.tokenVerified?.let { name ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.CheckCircle, contentDescription = null, tint = ChartColors.barDone)
          Text(
            stringResource(R.string.import_token_ok, if (name.isBlank()) "" else " — $name"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 6.dp)
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeCard(state: ImportState, viewModel: ProjectViewModel) {
  var picking by remember { mutableStateOf<Boolean?>(null) }

  Card(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(
          onClick = { viewModel.setRange(LocalDate.now().minusDays(7), LocalDate.now()) },
          label = { Text(stringResource(R.string.import_range_week)) }
        )
        AssistChip(
          onClick = { viewModel.setRange(LocalDate.now().minusDays(30), LocalDate.now()) },
          label = { Text(stringResource(R.string.import_range_month)) }
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { picking = true }) {
          Text("${stringResource(R.string.import_from)}: ${formatDate(state.from)}")
        }
        TextButton(onClick = { picking = false }) {
          Text("${stringResource(R.string.import_to)}: ${formatDate(state.to)}")
        }
      }
      Button(
        onClick = viewModel::fetchEntries,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(
          if (state.busy) stringResource(R.string.import_loading)
          else stringResource(R.string.import_fetch)
        )
      }
      Text(
        stringResource(R.string.import_ledger_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }

  picking?.let { isFrom ->
    val initial = if (isFrom) state.from else state.to
    val pickerState = rememberDatePickerState(
      initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    )
    DatePickerDialog(
      onDismissRequest = { picking = null },
      confirmButton = {
        TextButton(onClick = {
          pickerState.selectedDateMillis?.let { millis ->
            val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
            if (isFrom) viewModel.setRange(picked, state.to)
            else viewModel.setRange(state.from, picked)
          }
          picking = null
        }) { Text(stringResource(R.string.action_ok)) }
      },
      dismissButton = {
        TextButton(onClick = { picking = null }) { Text(stringResource(R.string.action_cancel)) }
      }
    ) {
      DatePicker(state = pickerState)
    }
  }
}

@Composable
private fun EntryCard(row: ImportRow, model: ProjectModel, viewModel: ProjectViewModel) {
  var menuOpen by remember { mutableStateOf(false) }
  val selectedTask = row.selectedTaskId?.let { model.task(it) }
  // Only leaves can receive hours; booking on a parent double-counts them.
  val candidates = remember(model) { model.flatTasks.filter { it.isLeaf && !it.isMilestone } }

  Card(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          row.entry.description.ifBlank { "—" },
          style = MaterialTheme.typography.titleSmall,
          modifier = Modifier.weight(1f),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
        Text(
          stringResource(R.string.import_entry_hours, hours(row.entry.hours)),
          style = MaterialTheme.typography.titleSmall
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          formatDate(row.entry.start),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        row.entry.projectName?.let {
          Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }

      if (row.alreadyImportedHours > 0) {
        Text(
          "${stringResource(R.string.import_already_imported)}: ${hours(row.alreadyImportedHours)} h",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary
        )
      }

      // Why this task is being suggested, so the user can judge it rather
      // than trust it.
      row.outcome.best?.let { best ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            if (row.outcome.isCertain) Icons.Default.CheckCircle else Icons.Default.HelpOutline,
            contentDescription = null,
            tint = if (row.outcome.isCertain) ChartColors.barDone else MaterialTheme.colorScheme.outline
          )
          Text(
            "${if (row.outcome.isCertain) stringResource(R.string.import_certain)
            else stringResource(R.string.import_uncertain)} · ${best.reason.text()}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 6.dp)
          )
        }
      }

      Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { menuOpen = true }, enabled = !row.skip) {
          Text(selectedTask?.name ?: stringResource(R.string.import_choose_task), maxLines = 1)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
          // Suggestions first, then every task: free choice must always be
          // reachable, never only what the matcher proposed.
          row.outcome.suggestions.forEach { suggestion ->
            val task = model.task(suggestion.taskId) ?: return@forEach
            DropdownMenuItem(
              text = { Text("★ ${task.name}") },
              onClick = { menuOpen = false; viewModel.selectTaskForEntry(row.entry.id, task.id) }
            )
          }
          if (row.outcome.suggestions.isNotEmpty()) HorizontalDivider()
          candidates.forEach { task ->
            DropdownMenuItem(
              text = { Text(task.name) },
              onClick = { menuOpen = false; viewModel.selectTaskForEntry(row.entry.id, task.id) }
            )
          }
        }
      }

      FilterChip(
        selected = row.skip,
        onClick = { viewModel.setEntrySkipped(row.entry.id, !row.skip) },
        label = { Text(stringResource(R.string.import_skip_entry)) }
      )
    }
  }
}

@Composable
private fun PreviewCard(
  plan: biz.ganttproject.mobile.core.ImportPlan,
  state: ImportState,
  viewModel: ProjectViewModel
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Text(stringResource(R.string.import_preview_title), style = MaterialTheme.typography.titleMedium)

      if (plan.isEmpty) {
        Text(
          stringResource(R.string.import_preview_nothing),
          style = MaterialTheme.typography.bodyMedium
        )
      } else {
        Text(
          stringResource(
            R.string.import_preview_total,
            hours(plan.totalHours),
            plan.hoursPerTask.size
          ),
          style = MaterialTheme.typography.bodyLarge
        )
      }

      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = state.learnKeys, onCheckedChange = viewModel::setLearnKeys)
        Text(
          stringResource(R.string.import_learn_keys),
          style = MaterialTheme.typography.bodySmall
        )
      }

      Button(
        onClick = viewModel::applyImport,
        enabled = !plan.isEmpty && !state.busy,
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(stringResource(R.string.import_apply))
      }
    }
  }
}
