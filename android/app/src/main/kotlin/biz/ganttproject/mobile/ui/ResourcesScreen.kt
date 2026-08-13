/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import biz.ganttproject.mobile.R
import biz.ganttproject.mobile.core.ProjectModel
import biz.ganttproject.mobile.core.ResourceLoadReport
import biz.ganttproject.mobile.core.ResourceNode
import biz.ganttproject.mobile.core.computeResourceLoad
import biz.ganttproject.mobile.core.parseEffortInput
import biz.ganttproject.mobile.ui.theme.ChartColors

/**
 * Resource list with the utilisation figure stock GanttProject never shows.
 *
 * The desktop app ignores an assignment's load percentage when scheduling
 * (upstream issue #83), so twenty tasks can each claim half of the same
 * person on the same day with no warning anywhere. This screen is the answer
 * to that, which is why it leads with peak utilisation rather than with
 * names and phone numbers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourcesScreen(project: ProjectUi, viewModel: ProjectViewModel) {
  val model = project.model
  var selectedResourceId by rememberSaveable { mutableStateOf<String?>(null) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  if (model.resources.isEmpty()) {
    EmptyMessage(stringResource(R.string.resources_none))
    return
  }

  // Recomputed whenever an edit lands, keyed on the revision so a changed
  // hours-per-day is reflected immediately.
  val reports = remember(project.revision, model) {
    model.resources.associate { it.id to computeResourceLoad(model, it.id) }
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    items(model.resources, key = { it.id }) { resource ->
      ResourceCard(
        resource = resource,
        report = reports[resource.id],
        taskCount = model.allocationsOfResource(resource.id).size,
        onClick = { selectedResourceId = resource.id }
      )
    }
  }

  selectedResourceId?.let { id ->
    val resource = model.resource(id)
    if (resource != null) {
      ModalBottomSheet(
        onDismissRequest = { selectedResourceId = null },
        sheetState = sheetState
      ) {
        ResourceSheet(
          resource = resource,
          report = reports[id],
          model = model,
          viewModel = viewModel
        )
      }
    }
  }
}

@Composable
private fun ResourceCard(
  resource: ResourceNode,
  report: ResourceLoadReport?,
  taskCount: Int,
  onClick: () -> Unit
) {
  val overloaded = report?.hasOverload == true
  Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          resource.name,
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        if (overloaded) {
          Icon(Icons.Default.Warning, contentDescription = null, tint = ChartColors.overload)
        }
      }

      Text(
        stringResource(R.string.assignment_hours_per_day, hours(resource.effectiveHoursPerDay)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )

      if (report == null || report.days.isEmpty()) {
        Text(
          stringResource(R.string.resource_not_assigned),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      } else {
        // Capped at 100% so the bar stays readable; the number next to it
        // carries the real figure, which may well be 300%.
        LinearProgressIndicator(
          progress = { (report.peakUtilisationPercent / 100.0).coerceIn(0.0, 1.0).toFloat() },
          modifier = Modifier.fillMaxWidth(),
          color = if (overloaded) ChartColors.overload else MaterialTheme.colorScheme.primary
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Text(
            stringResource(R.string.resource_peak, hours(report.peakUtilisationPercent)),
            style = MaterialTheme.typography.bodySmall
          )
          Text(
            if (overloaded) {
              stringResource(R.string.resource_overload_days, report.overloadedDays.size)
            } else {
              stringResource(R.string.resource_no_overload)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (overloaded) ChartColors.overload else MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        Text(
          stringResource(R.string.resource_task_count, taskCount),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}

@Composable
private fun ResourceSheet(
  resource: ResourceNode,
  report: ResourceLoadReport?,
  model: ProjectModel,
  viewModel: ProjectViewModel
) {
  var text by remember(resource.id, resource.hoursPerDay) {
    mutableStateOf(resource.hoursPerDay?.let { hours(it) } ?: "")
  }
  var invalid by remember(resource.id) { mutableStateOf(false) }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 20.dp)
      .padding(bottom = 32.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    Text(resource.name, style = MaterialTheme.typography.titleLarge)

    Column {
      OutlinedTextField(
        value = text,
        onValueChange = { text = it; invalid = false },
        label = { Text(stringResource(R.string.resource_hours_per_day)) },
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
          if (invalid) stringResource(R.string.task_effort_invalid)
          else stringResource(R.string.resource_hours_per_day_hint),
          style = MaterialTheme.typography.bodySmall,
          color = if (invalid) MaterialTheme.colorScheme.error
          else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f)
        )
        TextButton(onClick = {
          if (text.isBlank()) {
            viewModel.setResourceHoursPerDay(resource.id, null)
            invalid = false
          } else {
            val parsed = parseEffortInput(text)
            // Zero hours a day is not a capacity, it is a typo.
            if (parsed == null || parsed <= 0.0) invalid = true
            else { viewModel.setResourceHoursPerDay(resource.id, parsed); invalid = false }
          }
        }) {
          Text(stringResource(R.string.action_apply))
        }
      }
    }

    HorizontalDivider()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(stringResource(R.string.resource_utilisation), style = MaterialTheme.typography.labelMedium)
      if (report == null || report.days.isEmpty()) {
        Text(
          stringResource(R.string.resource_not_assigned),
          style = MaterialTheme.typography.bodyMedium
        )
      } else {
        Text(
          stringResource(R.string.resource_peak, hours(report.peakUtilisationPercent)),
          style = MaterialTheme.typography.bodyLarge
        )
        if (report.hasOverload) {
          Text(
            stringResource(R.string.resource_overload_days, report.overloadedDays.size),
            style = MaterialTheme.typography.bodyMedium,
            color = ChartColors.overload
          )
          // Naming the days makes the number actionable instead of alarming.
          report.overloadedDays.take(5).forEach { day ->
            Text(
              "${formatDate(day.date)} — ${hours(day.committedHours)} / ${hours(day.capacityHours)} h",
              style = MaterialTheme.typography.bodySmall
            )
          }
        }
        Text(
          stringResource(R.string.resource_overload_explained),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }

    HorizontalDivider()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(stringResource(R.string.assignments), style = MaterialTheme.typography.labelMedium)
      val allocations = model.allocationsOfResource(resource.id)
      if (allocations.isEmpty()) {
        Text(
          stringResource(R.string.resource_not_assigned),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
      allocations.forEach { allocation ->
        val task = model.task(allocation.taskId)
        Row(modifier = Modifier.fillMaxWidth()) {
          Text(
            task?.name ?: allocation.taskId,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium
          )
          Text(
            stringResource(R.string.assignment_load_percent, hours(allocation.load)),
            style = MaterialTheme.typography.bodyMedium
          )
        }
      }
    }
  }
}
