/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import biz.ganttproject.mobile.R
import biz.ganttproject.mobile.core.ProjectModel
import biz.ganttproject.mobile.core.TaskNode
import biz.ganttproject.mobile.core.availableHoursPerDay
import biz.ganttproject.mobile.core.computeDurationDays
import biz.ganttproject.mobile.core.parseEffortInput

/**
 * Everything about one task that can be changed from a phone.
 *
 * Dates and duration are shown but not editable: changing them without
 * running GanttProject's scheduler would leave dependent tasks where they
 * were, producing a file that looks fine and is quietly inconsistent. The
 * note in the UI says so rather than leaving the user to wonder.
 */
@Composable
fun TaskSheet(task: TaskNode, model: ProjectModel, viewModel: ProjectViewModel) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 20.dp)
      .padding(bottom = 32.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    Column {
      Text(task.name, style = MaterialTheme.typography.titleLarge)
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (task.isMilestone) {
          Text(stringResource(R.string.milestone), style = MaterialTheme.typography.labelMedium)
        } else if (!task.isLeaf) {
          Text(stringResource(R.string.summary_task), style = MaterialTheme.typography.labelMedium)
        }
      }
    }

    // ------------------------------------------------------------ Dates
    Column {
      Text(stringResource(R.string.task_dates), style = MaterialTheme.typography.labelMedium)
      val last = model.calendar.lastWorkingDay(task.start, task.durationDays)
      Text(formatDateRange(task.start, last), style = MaterialTheme.typography.bodyLarge)
      if (!task.isMilestone) {
        Text(
          stringResource(R.string.task_duration_days, task.durationDays),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
      Text(
        stringResource(R.string.task_duration_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }

    HorizontalDivider()

    // --------------------------------------------------------- Progress
    ProgressSection(task, viewModel)

    HorizontalDivider()

    // ----------------------------------------------------------- Hours
    HoursSection(task, model, viewModel)

    HorizontalDivider()

    // ----------------------------------------------------- Assignments
    AssignmentsSection(task, model, viewModel)

    task.notes?.let { notes ->
      HorizontalDivider()
      Column {
        Text(stringResource(R.string.task_notes), style = MaterialTheme.typography.labelMedium)
        Text(notes, style = MaterialTheme.typography.bodyMedium)
      }
    }
  }
}

@Composable
private fun ProgressSection(task: TaskNode, viewModel: ProjectViewModel) {
  Column {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(stringResource(R.string.task_progress), style = MaterialTheme.typography.labelMedium)
      Text(
        stringResource(R.string.task_progress_percent, task.completion),
        style = MaterialTheme.typography.titleMedium
      )
    }

    if (task.isCompletionEditable) {
      // Local state so dragging is smooth; the document is only touched when
      // the finger lifts, which keeps one edit per gesture in the undo story
      // and avoids re-reading the whole model on every pixel.
      var pending by remember(task.id, task.completion) { mutableStateOf(task.completion.toFloat()) }
      Slider(
        value = pending,
        onValueChange = { pending = it },
        onValueChangeFinished = { viewModel.setCompletion(task.id, pending.toInt()) },
        valueRange = 0f..100f,
        steps = 19 // 5% increments, which is how people actually report progress
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0, 25, 50, 75, 100).forEach { value ->
          AssistChip(
            onClick = { pending = value.toFloat(); viewModel.setCompletion(task.id, value) },
            label = { Text("$value%") }
          )
        }
      }
    } else {
      Text(
        stringResource(
          if (task.isMilestone) R.string.task_progress_milestone_locked
          else R.string.task_progress_summary_locked
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}

@Composable
private fun HoursSection(task: TaskNode, model: ProjectModel, viewModel: ProjectViewModel) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    HoursField(
      label = stringResource(R.string.task_effort_hours),
      value = task.effortHours,
      key = "${task.id}-effort",
      onCommit = { viewModel.setEffortHours(task.id, it) }
    )
    HoursField(
      label = stringResource(R.string.task_actual_hours),
      value = task.actualEffortHours,
      key = "${task.id}-actual",
      onCommit = { viewModel.setActualHours(task.id, it) }
    )

    val planned = task.effortHours
    val actual = task.actualEffortHours
    if (planned != null && actual != null) {
      Text(
        stringResource(R.string.task_effort_vs_actual, hours(actual), hours(planned)),
        style = MaterialTheme.typography.bodyMedium
      )
      if (actual > planned) {
        // A note, never an automatic correction: the plan is the user's to
        // change, and silently rewriting it would destroy the comparison.
        Text(
          stringResource(R.string.task_effort_over, hours(actual - planned)),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error
        )
      }
    }

    // Effort-driven duration, recomputed on every edit: the availability
    // comes from the assignments and each resource's hours per day, so
    // changing either of those updates this line immediately.
    //
    // Shown, never written. Writing a new duration would leave every
    // dependent task where it was, because GanttProject's scheduler does not
    // run here — a file that looks right and is wrong. Flagging the
    // disagreement lets the user fix it on the desktop, where the scheduler
    // will move the rest of the plan with it.
    if (planned != null && planned > 0 && !task.isMilestone) {
      val availability =
        availableHoursPerDay(model.allocationsOfTask(task.id)) { model.resource(it) }
      if (availability <= 0.0) {
        Text(
          stringResource(R.string.task_no_assignment_for_effort),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      } else {
        val derived = computeDurationDays(planned, availability)
        Text(
          stringResource(R.string.task_derived_duration, derived),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (task.isLeaf && derived != task.durationDays) {
          Text(
            stringResource(
              R.string.task_effort_duration_mismatch, task.durationDays, derived
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
          )
        }
      }
    }
  }
}

/**
 * An hours field that keeps what the user typed until it is committed.
 *
 * Invalid input leaves the stored value alone and shows an error instead of
 * silently writing 0 — the difference between "I mistyped" and "this task
 * takes no time" matters.
 */
@Composable
private fun HoursField(
  label: String,
  value: Double?,
  key: String,
  onCommit: (Double?) -> Unit
) {
  var text by remember(key, value) { mutableStateOf(value?.let { hours(it) } ?: "") }
  var invalid by remember(key) { mutableStateOf(false) }

  Column {
    OutlinedTextField(
      value = text,
      onValueChange = { text = it; invalid = false },
      label = { Text(label) },
      singleLine = true,
      isError = invalid,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
      modifier = Modifier.fillMaxWidth()
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = if (invalid) stringResource(R.string.task_effort_invalid)
        else stringResource(R.string.task_effort_hint),
        style = MaterialTheme.typography.bodySmall,
        color = if (invalid) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant
      )
      TextButton(onClick = {
        if (text.isBlank()) {
          onCommit(null)
          invalid = false
        } else {
          val parsed = parseEffortInput(text)
          if (parsed == null) invalid = true else { onCommit(parsed); invalid = false }
        }
      }) {
        Text(stringResource(R.string.action_apply))
      }
    }
  }
}

@Composable
private fun AssignmentsSection(task: TaskNode, model: ProjectModel, viewModel: ProjectViewModel) {
  val allocations = model.allocationsOfTask(task.id)
  var addMenuOpen by remember { mutableStateOf(false) }
  val unassigned = model.resources.filter { resource ->
    allocations.none { it.resourceId == resource.id }
  }

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(stringResource(R.string.assignments), style = MaterialTheme.typography.labelMedium)

    if (allocations.isEmpty()) {
      Text(
        stringResource(R.string.assignments_none),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }

    allocations.forEach { allocation ->
      val resource = model.resource(allocation.resourceId) ?: return@forEach
      var pendingLoad by remember(task.id, allocation.resourceId, allocation.load) {
        mutableStateOf(allocation.load.toFloat())
      }
      Column {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(resource.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
              stringResource(
                R.string.assignment_hours_per_day,
                hours(resource.effectiveHoursPerDay)
              ),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
          Text(stringResource(R.string.assignment_load_percent, hours(pendingLoad.toDouble())))
          IconButton(onClick = { viewModel.unassignResource(task.id, resource.id) }) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.assignment_remove))
          }
        }
        Slider(
          value = pendingLoad,
          onValueChange = { pendingLoad = it },
          onValueChangeFinished = {
            viewModel.setAllocationLoad(task.id, resource.id, pendingLoad.toDouble())
          },
          // From 5%, never 0: a zero-load assignment is refused by the
          // document layer because it means nothing.
          valueRange = 5f..200f,
          steps = 38
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          Checkbox(
            checked = allocation.responsible,
            onCheckedChange = {
              viewModel.setAllocationResponsible(task.id, resource.id, it)
            }
          )
          Text(
            stringResource(R.string.assignment_responsible),
            style = MaterialTheme.typography.bodySmall
          )
        }
      }
    }

    Row {
      TextButton(onClick = { addMenuOpen = true }, enabled = unassigned.isNotEmpty()) {
        Icon(Icons.Default.Add, contentDescription = null)
        Text(stringResource(R.string.assignment_add), modifier = Modifier.padding(start = 4.dp))
      }
      DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
        unassigned.forEach { resource ->
          DropdownMenuItem(
            text = { Text(resource.name) },
            onClick = {
              addMenuOpen = false
              viewModel.assignResource(task.id, resource.id)
            }
          )
        }
      }
    }
    if (unassigned.isEmpty() && model.resources.isNotEmpty()) {
      Text(
        stringResource(R.string.assignment_all_assigned),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}
