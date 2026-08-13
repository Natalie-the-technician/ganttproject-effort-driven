/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Today
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import biz.ganttproject.mobile.R
import biz.ganttproject.mobile.core.TaskNode
import biz.ganttproject.mobile.core.WorkingCalendar
import biz.ganttproject.mobile.ui.theme.ChartColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

private const val DEFAULT_NAME_COLUMN_DP = 150f
private const val MIN_NAME_COLUMN_DP = 72f
/** Beyond this the chart stops being a chart, so the drag simply stops. */
private const val MAX_NAME_COLUMN_DP = 320f
private val DIVIDER_TOUCH_WIDTH = 16.dp
private val CHEVRON_SIZE = 24.dp
private val ROW_HEIGHT = 44.dp
private val HEADER_HEIGHT = 38.dp
private const val MIN_DAY_WIDTH_DP = 2f
private const val MAX_DAY_WIDTH_DP = 48f

/**
 * Task list on the left, timeline on the right.
 *
 * ## Why the timeline is not a nested scroller
 *
 * The obvious layout — a `horizontalScroll` around each row's bar inside a
 * vertically scrolling list — puts two scrollers on top of each other, and in
 * practice the inner one swallows the vertical drag: you can scroll the list
 * by dragging the names, but not by dragging the bars. That was reported from
 * a real device.
 *
 * So there is exactly one scroller here. The [LazyColumn] owns the vertical
 * axis for the whole row, bars included, and the horizontal axis is a plain
 * pixel offset that the bars are *drawn* with. Panning feeds that offset
 * through a `scrollable` modifier on the other orientation, which cannot
 * conflict with the list, and pinch-zoom is a separate gesture that only
 * claims events once a second finger is down.
 *
 * Drawing with an offset is also cheaper: each canvas is one screen wide and
 * only iterates the days actually visible, instead of being a several-metre
 * wide surface that gets clipped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GanttScreen(project: ProjectUi, viewModel: ProjectViewModel) {
  val model = project.model
  val density = LocalDensity.current

  var dayWidthDp by rememberSaveable { mutableFloatStateOf(12f) }
  // Task names are routinely longer than any fixed column: "MEILENSTEIN:
  // Gewerbeanmeldung abgeschlossen" is not an unusual name. So the divider
  // is draggable, exactly like the one in the desktop app.
  var nameColumnDp by rememberSaveable { mutableFloatStateOf(DEFAULT_NAME_COLUMN_DP) }
  var offsetPx by rememberSaveable { mutableFloatStateOf(0f) }
  var totalWidthPx by remember { mutableFloatStateOf(0f) }
  var selectedTaskId by rememberSaveable { mutableStateOf<String?>(null) }
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

  val nameColumnWidth = nameColumnDp.dp
  val nameColumnPx = with(density) { nameColumnWidth.toPx() }
  val dayWidthPx = with(density) { dayWidthDp.dp.toPx() }
  // The chart viewport shrinks as the name column grows, so it is derived
  // rather than measured — otherwise dragging the divider would leave the
  // scroll bounds a frame behind.
  val viewportPx = (totalWidthPx - nameColumnPx).coerceAtLeast(1f)
  val contentPx = totalDays * dayWidthPx
  val maxOffsetPx = (contentPx - viewportPx).coerceAtLeast(0f)
  offsetPx = offsetPx.coerceIn(0f, maxOffsetPx)

  // Horizontal panning. Declared on the *other* orientation from the list, so
  // the two can never fight over a gesture.
  val panState = rememberScrollableState { delta ->
    val target = (offsetPx - delta).coerceIn(0f, maxOffsetPx)
    val consumed = offsetPx - target
    offsetPx = target
    consumed
  }

  fun zoomAround(factor: Float, focusPx: Float) {
    val old = dayWidthDp
    val next = (old * factor).coerceIn(MIN_DAY_WIDTH_DP, MAX_DAY_WIDTH_DP)
    if (next == old) return
    // Keep the date under the focal point where it is, otherwise zooming
    // throws the user to a different part of the project.
    val scale = next / old
    offsetPx = ((offsetPx + focusPx) * scale - focusPx).coerceAtLeast(0f)
    dayWidthDp = next
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
      IconButton(onClick = {
        // Scroll today into view, a third of the way in so there is context
        // on both sides of it.
        val todayOffset = ChronoUnit.DAYS.between(chartStart, LocalDate.now()).toFloat()
        offsetPx = (todayOffset * dayWidthPx - viewportPx / 3f).coerceIn(0f, maxOffsetPx)
      }) {
        Icon(Icons.Default.Today, contentDescription = stringResource(R.string.gantt_today))
      }
      IconButton(
        onClick = { zoomAround(1f / 1.5f, viewportPx / 2f) },
        enabled = dayWidthDp > MIN_DAY_WIDTH_DP
      ) {
        Icon(Icons.Default.ZoomOut, contentDescription = stringResource(R.string.gantt_zoom_out))
      }
      IconButton(
        onClick = { zoomAround(1.5f, viewportPx / 2f) },
        enabled = dayWidthDp < MAX_DAY_WIDTH_DP
      ) {
        Icon(Icons.Default.ZoomIn, contentDescription = stringResource(R.string.gantt_zoom_in))
      }
    }

    Box(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxSize()) {
        TimelineHeader(chartStart, dayWidthPx, offsetPx, nameColumnWidth)

        LazyColumn(
          modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { totalWidthPx = it.width.toFloat() }
            // No reverseDirection: the lambda below already turns a drag to the
            // left into a larger offset, i.e. later dates. Setting it as well
            // inverted the axis, which is how it shipped and got reported.
            .scrollable(panState, Orientation.Horizontal)
            .pointerInput(Unit) {
            // Pinch to zoom. Deliberately hand-rolled instead of
            // detectTransformGestures: that claims single-finger drags too,
            // which would take the vertical scroll away again. This only
            // consumes once a second finger is actually down.
            awaitEachGesture {
              awaitFirstDown(requireUnconsumed = false)
              do {
                val event = awaitPointerEvent()
                if (event.changes.size >= 2) {
                  val zoom = event.calculateZoom()
                  if (zoom != 1f && zoom.isFinite()) {
                    val centroid = event.calculateCentroid(useCurrent = true)
                    val focus = (centroid.x - nameColumnPx).coerceAtLeast(0f)
                    zoomAround(zoom, focus)
                    event.changes.forEach { it.consume() }
                  }
                }
              } while (event.changes.any { it.pressed })
            }
          }
        ) {
          // visibleTasks, not flatTasks: children of a folded group are left out.
          items(model.visibleTasks, key = { it.id }) { task ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .height(ROW_HEIGHT)
                .clickable { selectedTaskId = task.id }
            ) {
              TaskNameCell(task, nameColumnWidth) { viewModel.toggleExpanded(task.id) }
              TaskBar(task, model.calendar, chartStart, dayWidthPx, offsetPx)
            }
          }
        }
      }

      // The divider, laid over the seam rather than placed in every row: a
      // handle repeated per row could not be dragged across rows, and a
      // handle only in the header would be a thin target at the top edge.
      Box(
        modifier = Modifier
          .offset(x = nameColumnWidth - DIVIDER_TOUCH_WIDTH / 2)
          .width(DIVIDER_TOUCH_WIDTH)
          .fillMaxHeight()
          .draggable(
            orientation = Orientation.Horizontal,
            state = rememberDraggableState { delta ->
              nameColumnDp = (nameColumnDp + with(density) { delta.toDp().value })
                .coerceIn(MIN_NAME_COLUMN_DP, MAX_NAME_COLUMN_DP)
            }
          ),
        contentAlignment = Alignment.Center
      ) {
        // A visible seam, so the handle is discoverable at all.
        Box(
          modifier = Modifier
            .width(2.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant)
        )
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
private fun TaskNameCell(task: TaskNode, width: Dp, onToggleExpand: () -> Unit) {
  Row(
    modifier = Modifier
      .width(width)
      .fillMaxHeight()
      .padding(start = (4 + task.depth * 10).dp, end = 4.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    if (task.isLeaf) {
      // Keeps leaf names aligned with the ones that do have a chevron,
      // instead of letting every level jitter by an icon width.
      Spacer(modifier = Modifier.width(CHEVRON_SIZE))
    } else {
      Icon(
        imageVector =
          if (task.isExpanded) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = stringResource(
          if (task.isExpanded) R.string.collapse_group else R.string.expand_group
        ),
        modifier = Modifier
          .size(CHEVRON_SIZE)
          // A generous touch target: the icon is small, and hitting it must
          // not be a lottery that opens the task sheet instead.
          .clickable(onClick = onToggleExpand),
        tint = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
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

/** Month scale, weekend shading and the today marker, drawn at the same offset. */
@Composable
private fun TimelineHeader(
  chartStart: LocalDate,
  dayWidthPx: Float,
  offsetPx: Float,
  nameColumnWidth: Dp
) {
  val monthFormat = remember { DateTimeFormatter.ofPattern("MMM yyyy") }
  val outline = MaterialTheme.colorScheme.outlineVariant
  val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
  val textMeasurer = rememberTextMeasurer()
  val labelStyle = MaterialTheme.typography.labelSmall

  Row(modifier = Modifier.fillMaxWidth().height(HEADER_HEIGHT)) {
    Box(modifier = Modifier.width(nameColumnWidth))
    Canvas(modifier = Modifier.fillMaxWidth().height(HEADER_HEIGHT).clipToBounds()) {
      forEachVisibleDay(chartStart, dayWidthPx, offsetPx, size.width) { date, x ->
        if (date.dayOfWeek.value >= 6) {
          drawRect(ChartColors.weekend, Offset(x, 0f), Size(dayWidthPx, size.height))
        }
        // A tick every Monday keeps the week structure readable even when
        // zoomed out far enough that day numbers no longer fit.
        if (date.dayOfWeek.value == 1) {
          drawLine(outline, Offset(x, 18f), Offset(x, size.height), strokeWidth = 1f)
          if (date.dayOfMonth <= 7) {
            drawText(
              textMeasurer.measure(date.format(monthFormat), labelStyle),
              color = onSurface,
              topLeft = Offset(x + 2f, 0f)
            )
          }
        }
        if (date == LocalDate.now()) {
          drawLine(ChartColors.today, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
        }
      }
      drawLine(outline, Offset(0f, size.height - 1), Offset(size.width, size.height - 1), 1f)
    }
  }
}

@Composable
private fun TaskBar(
  task: TaskNode,
  calendar: WorkingCalendar,
  chartStart: LocalDate,
  dayWidthPx: Float,
  offsetPx: Float
) {
  val barColor = when {
    task.isMilestone -> ChartColors.milestone
    !task.isLeaf -> ChartColors.barSummary
    task.completion >= 100 -> ChartColors.barDone
    else -> ChartColors.bar
  }

  // clipToBounds is load-bearing, not decoration. Compose's drawBehind (which
  // Canvas is built on) does NOT clip to the node's bounds: a bar whose x has
  // gone negative because the chart is scrolled to a later date would paint
  // straight over the task names to its left. Reported from a real device as
  // "the side panel has to come to the front".
  Canvas(modifier = Modifier.fillMaxWidth().fillMaxHeight().clipToBounds()) {
    forEachVisibleDay(chartStart, dayWidthPx, offsetPx, size.width) { date, x ->
      if (date.dayOfWeek.value >= 6) {
        drawRect(ChartColors.weekend, Offset(x, 0f), Size(dayWidthPx, size.height))
      }
      if (date == LocalDate.now()) {
        drawLine(ChartColors.today, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
      }
    }

    val start = calendar.nextWorkingDay(task.start)
    val x = ChronoUnit.DAYS.between(chartStart, start) * dayWidthPx - offsetPx

    if (task.isMilestone) {
      val centre = Offset(x + dayWidthPx / 2f, size.height / 2f)
      if (centre.x < -40f || centre.x > size.width + 40f) return@Canvas
      val radius = (size.height / 4f).coerceAtMost(dayWidthPx.coerceAtLeast(8f))
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
    // it crosses weekends exactly as the desktop chart does.
    val last = calendar.lastWorkingDay(task.start, task.durationDays)
    val spanDays = (ChronoUnit.DAYS.between(start, last) + 1).toFloat().coerceAtLeast(1f)
    val width = spanDays * dayWidthPx
    if (x + width < 0f || x > size.width) return@Canvas

    val barHeight = if (task.isLeaf) size.height * 0.42f else size.height * 0.28f
    val top = (size.height - barHeight) / 2f
    val corner = androidx.compose.ui.geometry.CornerRadius(3f, 3f)

    drawRoundRect(barColor, Offset(x, top), Size(width, barHeight), corner)
    if (task.completion in 1..99 && task.isLeaf) {
      drawRoundRect(
        Color.Black.copy(alpha = 0.28f),
        Offset(x, top),
        Size(width * task.completion / 100f, barHeight),
        corner
      )
    }
  }
}

/**
 * Visits only the days that fall inside the canvas, handing each its x
 * position. Iterating the whole project instead would mean thousands of
 * no-op steps per frame on a long plan.
 */
private inline fun forEachVisibleDay(
  chartStart: LocalDate,
  dayWidthPx: Float,
  offsetPx: Float,
  widthPx: Float,
  action: (LocalDate, Float) -> Unit
) {
  if (dayWidthPx <= 0f) return
  val first = (offsetPx / dayWidthPx).toInt().coerceAtLeast(0)
  val count = (widthPx / dayWidthPx).roundToInt() + 2
  for (i in first until first + count) {
    action(chartStart.plusDays(i.toLong()), i * dayWidthPx - offsetPx)
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
