/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import biz.ganttproject.mobile.R
import biz.ganttproject.mobile.core.ProjectModel
import biz.ganttproject.mobile.core.TaskNode
import biz.ganttproject.mobile.core.WorkingCalendar
import biz.ganttproject.mobile.ui.theme.ChartColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val NAME_COLUMN_WIDTH = 150.dp
private val ROW_HEIGHT = 44.dp
private const val MIN_DAY_WIDTH = 4f
private const val MAX_DAY_WIDTH = 36f

/**
 * Task list on the left, timeline on the right, sharing one horizontal
 * scroll state so the bars stay under their dates while the names stay put.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GanttScreen(project: ProjectUi, viewModel: ProjectViewModel) {
  val model = project.model
  var dayWidth by rememberSaveable { mutableStateOf(12f) }
  var selectedTaskId by rememberSaveable { mutableStateOf<String?>(null) }
  val horizontalScroll = rememberScrollState()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  if (model.flatTasks.isEmpty()) {
    EmptyMessage(stringResource(R.string.gantt_no_tasks))
    return
  }

  // The chart starts on the Monday of the project's first week so the weekend
  // shading lines up with the week grid.
  val chartStart = remember(model) {
    val start = model.projectStart()
    start.minusDays((start.dayOfWeek.value - 1).toLong())
  }
  val totalDays = remember(model, chartStart) {
    (ChronoUnit.DAYS.between(chartStart, model.projectEnd()).toInt() + 8).coerceAtLeast(14)
  }

  Column(modifier = Modifier.fillMaxSize()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = model.name.ifBlank { project.displayName },
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      IconButton(
        onClick = { dayWidth = (dayWidth / 1.5f).coerceAtLeast(MIN_DAY_WIDTH) },
        enabled = dayWidth > MIN_DAY_WIDTH
      ) {
        Icon(Icons.Default.ZoomOut, contentDescription = stringResource(R.string.gantt_zoom_out))
      }
      IconButton(
        onClick = { dayWidth = (dayWidth * 1.5f).coerceAtMost(MAX_DAY_WIDTH) },
        enabled = dayWidth < MAX_DAY_WIDTH
      ) {
        Icon(Icons.Default.ZoomIn, contentDescription = stringResource(R.string.gantt_zoom_in))
      }
    }

    TimelineHeader(chartStart, totalDays, dayWidth, horizontalScroll)

    LazyColumn(modifier = Modifier.fillMaxSize()) {
      items(model.flatTasks, key = { it.id }) { task ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clickable { selectedTaskId = task.id }
        ) {
          TaskNameCell(task)
          Box(modifier = Modifier.horizontalScroll(horizontalScroll)) {
            TaskBar(task, model.calendar, chartStart, totalDays, dayWidth)
          }
        }
      }
    }
  }

  selectedTaskId?.let { taskId ->
    val task = model.task(taskId)
    if (task != null) {
      ModalBottomSheet(
        onDismissRequest = { selectedTaskId = null },
        sheetState = sheetState
      ) {
        TaskSheet(task = task, model = model, viewModel = viewModel)
      }
    }
  }
}

@Composable
private fun TaskNameCell(task: TaskNode) {
  Row(
    modifier = Modifier
      .width(NAME_COLUMN_WIDTH)
      .fillMaxSize()
      .padding(start = (8 + task.depth * 12).dp, end = 4.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column {
      Text(
        text = task.name,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (task.isLeaf) FontWeight.Normal else FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      if (task.completion > 0 && !task.isMilestone) {
        Text(
          text = stringResource(R.string.task_progress_percent, task.completion),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}

/** Month and day-of-month scale, plus weekend shading and the today marker. */
@Composable
private fun TimelineHeader(
  chartStart: LocalDate,
  totalDays: Int,
  dayWidth: Float,
  scrollState: androidx.compose.foundation.ScrollState
) {
  val density = LocalDensity.current
  val monthFormat = remember { DateTimeFormatter.ofPattern("MMM yyyy") }
  val outline = MaterialTheme.colorScheme.outlineVariant
  val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
  val textMeasurer = rememberTextMeasurer()
  val labelStyle = MaterialTheme.typography.labelSmall

  Row(modifier = Modifier.fillMaxWidth().height(38.dp)) {
    Box(modifier = Modifier.width(NAME_COLUMN_WIDTH))
    Box(modifier = Modifier.horizontalScroll(scrollState)) {
      Canvas(
        modifier = Modifier
          .width(with(density) { (totalDays * dayWidth).toDp() })
          .height(38.dp)
      ) {
        val today = LocalDate.now()
        for (index in 0 until totalDays) {
          val date = chartStart.plusDays(index.toLong())
          val x = index * dayWidth

          if (date.dayOfWeek.value >= 6) {
            drawRect(ChartColors.weekend, Offset(x, 0f), Size(dayWidth, size.height))
          }
          // A tick on Mondays keeps the week structure readable even when
          // zoomed out far enough that day numbers no longer fit.
          if (date.dayOfWeek.value == 1) {
            drawLine(outline, Offset(x, 18f), Offset(x, size.height), strokeWidth = 1f)
            if (date.dayOfMonth <= 7) {
              val label = textMeasurer.measure(date.format(monthFormat), labelStyle)
              drawText(label, color = onSurface, topLeft = Offset(x + 2f, 0f))
            }
          }
          if (date == today) {
            drawLine(ChartColors.today, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
          }
        }
        drawLine(outline, Offset(0f, size.height - 1), Offset(size.width, size.height - 1), 1f)
      }
    }
  }
}

@Composable
private fun TaskBar(
  task: TaskNode,
  calendar: WorkingCalendar,
  chartStart: LocalDate,
  totalDays: Int,
  dayWidth: Float
) {
  val density = LocalDensity.current
  val barColor = when {
    task.isMilestone -> ChartColors.milestone
    !task.isLeaf -> ChartColors.barSummary
    task.completion >= 100 -> ChartColors.barDone
    else -> ChartColors.bar
  }

  Canvas(
    modifier = Modifier
      .width(with(density) { (totalDays * dayWidth).toDp() })
      .height(ROW_HEIGHT)
  ) {
    val today = LocalDate.now()
    // Background grid first so bars sit on top of it.
    for (index in 0 until totalDays) {
      val date = chartStart.plusDays(index.toLong())
      val x = index * dayWidth
      if (date.dayOfWeek.value >= 6) {
        drawRect(ChartColors.weekend, Offset(x, 0f), Size(dayWidth, size.height))
      }
      if (date == today) {
        drawLine(ChartColors.today, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
      }
    }

    val start = calendar.nextWorkingDay(task.start)
    val startOffset = ChronoUnit.DAYS.between(chartStart, start).toFloat()
    if (startOffset < 0) return@Canvas
    val x = startOffset * dayWidth

    if (task.isMilestone) {
      // A diamond, the way GanttProject draws milestones.
      val centre = Offset(x + dayWidth / 2f, size.height / 2f)
      val radius = (ROW_HEIGHT.toPx() / 4f).coerceAtMost(dayWidth.coerceAtLeast(8f))
      drawPath(
        Path().apply {
          moveTo(centre.x, centre.y - radius)
          lineTo(centre.x + radius, centre.y)
          lineTo(centre.x, centre.y + radius)
          lineTo(centre.x - radius, centre.y)
          close()
        },
        barColor
      )
      return@Canvas
    }

    // The bar spans calendar days from the first to the last working day, so
    // it visually crosses weekends exactly as the desktop chart does.
    val last = calendar.lastWorkingDay(task.start, task.durationDays)
    val spanDays = (ChronoUnit.DAYS.between(start, last) + 1).toFloat().coerceAtLeast(1f)
    val width = spanDays * dayWidth
    val barHeight = if (task.isLeaf) size.height * 0.42f else size.height * 0.28f
    val top = (size.height - barHeight) / 2f

    drawRoundRect(
      color = barColor,
      topLeft = Offset(x, top),
      size = Size(width, barHeight),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
    )
    if (task.completion in 1..99 && task.isLeaf) {
      drawRoundRect(
        color = Color.Black.copy(alpha = 0.28f),
        topLeft = Offset(x, top),
        size = Size(width * task.completion / 100f, barHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
      )
    }
  }
}

@Composable
fun EmptyMessage(text: String) {
  Box(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    contentAlignment = Alignment.Center
  ) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
  }
}
