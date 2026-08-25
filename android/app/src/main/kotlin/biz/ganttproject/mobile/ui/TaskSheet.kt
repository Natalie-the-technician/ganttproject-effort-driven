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
import biz.ganttproject.mobile.core.TimeRecord
import biz.ganttproject.mobile.core.TimeSource
import biz.ganttproject.mobile.core.availableHoursPerDay
import biz.ganttproject.mobile.core.computeDurationDays
import biz.ganttproject.mobile.core.parseEffortInput
import java.time.ZoneId
import kotlin.math.abs

/**
 * Everything about one task that can be changed from a phone.
 *
 * Dates and duration are shown but not editable: changing them without
 * running GanttProject's scheduler would leave dependent tasks where they
 * were, producing a file that looks fine and is quietly inconsistent. The
 * note in the UI says so rather than leaving the user to wonder.
 */
@Composable
fun TaskSheet(
  task: TaskNode,
  model: ProjectModel,
  viewModel: ProjectViewModel,
  canEdit: Boolean,
  /**
   * Changes on every edit. The time log is read straight from the document
   * rather than from the model snapshot, so this is what tells the section
   * to look again.
   */
  revision: Int
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 20.dp)
      .padding(bottom = 32.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    if (!canEdit) EditingOffBanner()

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
    ProgressSection(task, viewModel, canEdit)

    HorizontalDivider()

    // ----------------------------------------------------------- Hours
    HoursSection(task, model, viewModel, canEdit)

    HorizontalDivider()

    TimeLogSection(task, viewModel, canEdit, revision)

    HorizontalDivider()

    // ----------------------------------------------------- Assignments
    AssignmentsSection(task, model, viewModel, canEdit)

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
private fun ProgressSection(task: TaskNode, viewModel: ProjectViewModel, canEdit: Boolean) {
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
        enabled = canEdit,
        valueRange = 0f..100f,
        steps = 19 // 5% increments, which is how people actually report progress
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0, 25, 50, 75, 100).forEach { value ->
          AssistChip(
            onClick = { pending = value.toFloat(); viewModel.setCompletion(task.id, value) },
            enabled = canEdit,
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
private fun HoursSection(
  task: TaskNode,
  model: ProjectModel,
  viewModel: ProjectViewModel,
  canEdit: Boolean
) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    HoursField(
      label = stringResource(R.string.task_effort_hours),
      value = task.effortHours,
      key = "${task.id}-effort",
      enabled = canEdit,
      onCommit = { viewModel.setEffortHours(task.id, it) }
    )
    HoursField(
      label = stringResource(R.string.task_actual_hours),
      value = task.actualEffortHours,
      key = "${task.id}-actual",
      enabled = canEdit,
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
  enabled: Boolean,
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
      enabled = enabled,
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
      TextButton(enabled = enabled, onClick = {
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
private fun AssignmentsSection(
  task: TaskNode,
  model: ProjectModel,
  viewModel: ProjectViewModel,
  canEdit: Boolean
) {
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
          IconButton(
            onClick = { viewModel.unassignResource(task.id, resource.id) },
            enabled = canEdit
          ) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.assignment_remove))
          }
        }
        Slider(
          value = pendingLoad,
          onValueChange = { pendingLoad = it },
          onValueChangeFinished = {
            viewModel.setAllocationLoad(task.id, resource.id, pendingLoad.toDouble())
          },
          enabled = canEdit,
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
            },
            enabled = canEdit
          )
          Text(
            stringResource(R.string.assignment_responsible),
            style = MaterialTheme.typography.bodySmall
          )
        }
      }
    }

    Row {
      TextButton(
        onClick = { addMenuOpen = true },
        enabled = canEdit && unassigned.isNotEmpty()
      ) {
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

// ------------------------------------------------------------------ Time log

/**
 * Individual records of work on this task.
 *
 * Separate from the "actual hours" field above, and deliberately so: that
 * field is one number, this is what the number is made of. Only the log can
 * answer "when, how long, and on what" — which is what any report, invoice or
 * funding claim actually asks for.
 *
 * Nothing here rounds. What is booked is what is stored, down to the second;
 * whoever reads the export decides how to round it, once.
 */
@Composable
private fun TimeLogSection(
  task: TaskNode,
  viewModel: ProjectViewModel,
  canEdit: Boolean,
  revision: Int
) {
  val uid = task.uid

  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text(stringResource(R.string.task_timelog), style = MaterialTheme.typography.titleMedium)

    if (uid == null) {
      // A record has to point at something that survives the task being moved
      // or re-indented, and only the uid does. Files written by older
      // GanttProject versions may not have one, and saying so beats a button
      // that quietly does nothing.
      Text(
        stringResource(R.string.task_timelog_no_uid),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    } else {
      val records = remember(uid, revision) { viewModel.timeRecordsOf(uid) }
      val unreadable = remember(uid, revision) { viewModel.unreadableRecordsOf(uid) }
      val loggedHours = records.sumOf { it.durationSeconds } / 3600.0

      if (records.isEmpty()) {
        Text(
          stringResource(R.string.task_timelog_empty),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      } else {
        Text(
          stringResource(R.string.task_timelog_total, hours(loggedHours), records.size),
          style = MaterialTheme.typography.bodyMedium
        )
      }

      if (unreadable > 0) {
        // Never silent. A record that cannot be read is work somebody did, and
        // hours that were worked cannot be reconstructed later.
        Text(
          stringResource(R.string.task_timelog_unreadable, unreadable),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error
        )
      }

      // The stored total and the log can disagree: the field is an ordinary
      // editable column on the desktop, and the home-screen widget writes it
      // from another process. Reported rather than corrected — the difference
      // may well be a correction somebody made on purpose.
      val stored = task.actualEffortHours
      val differs = if (stored == null) loggedHours > 0.01 else abs(stored - loggedHours) > 0.01
      if (differs) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            stringResource(R.string.task_timelog_drift, hours(stored ?: 0.0), hours(loggedHours)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          if (canEdit) {
            TextButton(onClick = { viewModel.alignActualHoursWithLog() }) {
              Text(stringResource(R.string.task_timelog_align))
            }
          }
        }
      }

      records.forEach { record ->
        TimeRecordRow(record, viewModel, canEdit)
      }

      if (canEdit) BookHoursRow(uid, viewModel)
    }
  }
}

@Composable
private fun TimeRecordRow(record: TimeRecord, viewModel: ProjectViewModel, canEdit: Boolean) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // The device's zone, because this is a screen. A report takes its zone
        // from the reporting period instead — the same instant falls on a
        // different day depending on where the question is asked.
        Text(
          formatDate(record.dateIn(ZoneId.systemDefault())),
          style = MaterialTheme.typography.bodyMedium
        )
        Text(
          stringResource(R.string.task_timelog_hours, hours(record.hours)),
          style = MaterialTheme.typography.bodyMedium
        )
        if (record.source != TimeSource.MANUAL) {
          Text(
            record.source.text(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
      if (record.description.isNotBlank()) {
        Text(
          record.description,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
    if (canEdit) {
      IconButton(onClick = { viewModel.removeTimeRecord(record.id) }) {
        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.task_timelog_delete))
      }
    }
  }
}

/**
 * Books a stretch that has just finished.
 *
 * Hours and a description, no date field: the record is placed ending now,
 * which is what "I just worked on this" means and gives it a real timestamp.
 * A date picker would let somebody enter a day without a time, and the time is
 * exactly what decides which reporting period the hours land in.
 */
@Composable
private fun BookHoursRow(taskUid: String, viewModel: ProjectViewModel) {
  var hoursText by remember(taskUid) { mutableStateOf("") }
  var what by remember(taskUid) { mutableStateOf("") }
  var invalid by remember(taskUid) { mutableStateOf(false) }

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedTextField(
      value = hoursText,
      onValueChange = { hoursText = it; invalid = false },
      label = { Text(stringResource(R.string.task_timelog_book_hours)) },
      singleLine = true,
      isError = invalid,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
      modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
      value = what,
      onValueChange = { what = it },
      label = { Text(stringResource(R.string.task_timelog_book_what)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth()
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = if (invalid) stringResource(R.string.task_timelog_book_invalid)
        else stringResource(R.string.task_timelog_book_hint),
        style = MaterialTheme.typography.bodySmall,
        color = if (invalid) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f)
      )
      TextButton(
        onClick = {
          val parsed = parseEffortInput(hoursText)
          if (parsed == null || parsed <= 0.0 || !viewModel.bookHours(taskUid, parsed, what)) {
            invalid = true
          } else {
            hoursText = ""
            what = ""
            invalid = false
          }
        }
      ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Text(stringResource(R.string.task_timelog_book))
      }
    }
  }
}
