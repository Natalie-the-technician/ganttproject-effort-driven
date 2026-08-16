/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import biz.ganttproject.mobile.MainActivity
import biz.ganttproject.mobile.R
import biz.ganttproject.mobile.data.AppPreferences
import biz.ganttproject.mobile.core.AgendaItem
import biz.ganttproject.mobile.core.formatHours
import java.time.format.DateTimeFormatter

/**
 * A home-screen list of what needs attention: everything unfinished that has
 * started or starts within the configured window.
 *
 * ## What can be done from here
 *
 * Ticking a task off, adding half an hour to its recorded time, and tapping
 * through to the app for anything finer. Those three cover the reason to look
 * at a plan on a home screen at all; percentages and assignments need a
 * keyboard and a bigger surface, and get one in the app.
 *
 * ## Why every tap re-reads the file
 *
 * A widget has no lifecycle to hold a document open across taps, and holding
 * one would be worse anyway: the file lives in a synced folder and can change
 * between two taps. So each action reads, changes one thing, and writes back
 * — guarded by the same fingerprint check the app uses, so a stale widget can
 * never overwrite an edit that arrived from the desktop.
 */
class AgendaWidget : GlanceAppWidget() {

  override suspend fun provideGlance(context: Context, id: GlanceId) {
    val state = WidgetProject(context).load()
    provideContent {
      GlanceTheme {
        WidgetBody(context, state)
      }
    }
  }
}

@Composable
private fun WidgetBody(context: Context, state: WidgetState) {
  Column(
    modifier = GlanceModifier
      .fillMaxSize()
      .background(GlanceTheme.colors.widgetBackground)
      .padding(12.dp)
  ) {
    when (state) {
      WidgetState.NoProject -> WidgetHint(
        context.getString(R.string.widget_no_project),
        openApp = true
      )

      WidgetState.Unreadable -> WidgetHint(
        context.getString(R.string.widget_unreadable),
        openApp = true
      )

      is WidgetState.Loaded -> {
        Row(
          modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = state.projectName.ifBlank { context.getString(R.string.app_name) },
            style = TextStyle(
              fontWeight = FontWeight.Bold,
              color = GlanceTheme.colors.onSurface
            ),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight()
              .clickable(actionStartActivity<MainActivity>())
          )
          Text(
            text = context.getString(R.string.widget_window_label, state.windowDays),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
            maxLines = 1
          )
        }

        // Only for a server project. A local file is read straight from disk
        // on every redraw, so it is never out of date and there is nothing to
        // fetch — a refresh button there would promise an act it does not
        // perform.
        if (state.snapshotAt > 0L) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = context.getString(R.string.widget_snapshot_at, snapshotLabel(context, state.snapshotAt)),
              style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
              maxLines = 1,
              modifier = GlanceModifier.defaultWeight()
            )
            Text(
              text = context.getString(R.string.widget_refresh),
              style = TextStyle(color = GlanceTheme.colors.primary),
              maxLines = 1,
              modifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>())
            )
          }
        }

        state.notice?.let { key ->
          Text(
            text = context.getString(noticeText(key)),
            style = TextStyle(color = GlanceTheme.colors.error),
            maxLines = 2
          )
        }

        if (state.items.isEmpty()) {
          WidgetHint(context.getString(R.string.widget_nothing_due), openApp = false)
        } else {
          LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
            items(state.items, itemId = { it.task.id.hashCode().toLong() }) { item ->
              AgendaRow(context, item, canEdit = state.canEdit)
            }
          }
        }
      }
    }
  }
}

/**
 * "14:32" for a snapshot from today, otherwise a date.
 *
 * A bare time on a three-day-old snapshot would read as "just now", which is
 * the thing this label exists to prevent.
 */
private fun noticeText(key: String): Int = when (key) {
  "locked" -> R.string.widget_locked
  "offline" -> R.string.widget_offline
  else -> R.string.widget_failed
}

private fun snapshotLabel(context: Context, millis: Long): String {
  val taken = java.time.Instant.ofEpochMilli(millis)
    .atZone(java.time.ZoneId.systemDefault())
  return if (taken.toLocalDate() == java.time.LocalDate.now()) {
    java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(taken)
  } else {
    java.time.format.DateTimeFormatter.ofPattern("d.M. HH:mm").format(taken)
  }
}

@Composable
private fun WidgetHint(text: String, openApp: Boolean) {
  val modifier = GlanceModifier.fillMaxSize().padding(8.dp)
  Text(
    text = text,
    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
    modifier = if (openApp) modifier.clickable(actionStartActivity<MainActivity>()) else modifier
  )
}

@Composable
private fun AgendaRow(context: Context, item: AgendaItem, canEdit: Boolean) {
  val dateFormat = DateTimeFormatter.ofPattern("d. MMM")
  Row(
    modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    // The tick. A plain text glyph rather than a CheckBox: Glance's checkbox
    // reports a boolean and expects to be told the new state back, which for
    // a one-way "this is finished now" is more machinery than meaning.
    //
    // With editing switched off it stays as a bullet with no click target,
    // rather than a greyed-out control: a button that cannot do anything is
    // an invitation to keep tapping it.
    Text(
      text = if (canEdit) "○" else "·",
      style = TextStyle(
        color = if (canEdit) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant
      ),
      modifier = if (canEdit) {
        GlanceModifier
          .size(32.dp)
          .clickable(
            actionRunCallback<CompleteTaskAction>(
              actionParametersOf(TaskIdKey to item.task.id)
            )
          )
      } else {
        GlanceModifier.size(32.dp)
      }
    )

    Column(modifier = GlanceModifier.defaultWeight().padding(horizontal = 4.dp)) {
      Text(
        text = item.task.name,
        style = TextStyle(color = GlanceTheme.colors.onSurface),
        maxLines = 2,
        modifier = GlanceModifier.clickable(
          // Opens the app and jumps to this task, for anything the widget
          // cannot express.
          actionStartActivity<MainActivity>(
            actionParametersOf(TaskIdKey to item.task.id)
          )
        )
      )
      Text(
        text = buildString {
          if (item.isOverdue) append(context.getString(R.string.widget_overdue)).append(" · ")
          append(item.endDate.format(dateFormat))
          if (item.task.completion > 0) append(" · ${item.task.completion}%")
          item.task.actualEffortHours?.let { append(" · ${formatHours(it)} h") }
        },
        style = TextStyle(
          color = if (item.isOverdue) GlanceTheme.colors.error
          else GlanceTheme.colors.onSurfaceVariant
        ),
        maxLines = 1
      )
    }

    // Half an hour is the smallest unit worth recording by thumb; anything
    // finer belongs in the app or comes from the time tracker.
    if (canEdit) {
      Text(
        text = context.getString(R.string.widget_add_half_hour),
        style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Medium),
        modifier = GlanceModifier
          .width(48.dp)
          .clickable(
            actionRunCallback<AddHoursAction>(
              actionParametersOf(TaskIdKey to item.task.id)
            )
          )
      )
    }
  }
}

val TaskIdKey = ActionParameters.Key<String>("taskId")

/** Marks a task finished from the home screen. */
class CompleteTaskAction : ActionCallback {
  override suspend fun onAction(
    context: Context,
    glanceId: GlanceId,
    parameters: ActionParameters
  ) {
    val taskId = parameters[TaskIdKey] ?: return
    handleResult(context, glanceId, WidgetProject(context).complete(taskId))
  }
}

/** Adds half an hour to a task's recorded time. */
class AddHoursAction : ActionCallback {
  override suspend fun onAction(
    context: Context,
    glanceId: GlanceId,
    parameters: ActionParameters
  ) {
    val taskId = parameters[TaskIdKey] ?: return
    handleResult(context, glanceId, WidgetProject(context).addHours(taskId, 0.5))
  }
}

/**
 * Fetches the current version from the server on request.
 *
 * The counterpart to drawing from a snapshot: nothing is fetched on its own,
 * and this is how the user asks. Deliberately a button rather than a schedule
 * — a widget that polls somebody's own server all day is impolite in a way
 * that never shows up in testing.
 */
class RefreshAction : ActionCallback {
  override suspend fun onAction(
    context: Context,
    glanceId: GlanceId,
    parameters: ActionParameters
  ) {
    handleResult(context, glanceId, WidgetProject(context).refresh())
  }
}

/**
 * Redraws after a change, and sends the user to the app when the widget must
 * not decide on its own.
 *
 * A conflict is not something a home-screen tap can resolve: keeping both
 * versions or replacing the other one is a choice that needs explaining and a
 * file picker. So the widget hands over rather than guessing.
 */
private suspend fun handleResult(context: Context, glanceId: GlanceId, result: WidgetEditResult) {
  // DISABLED needs no reaction beyond the redraw below, which removes the
  // buttons that should not have been there. Sending the user to the app
  // would punish them for a tap the widget itself offered.
  AppPreferences(context).setWidgetNotice(
    when (result) {
      WidgetEditResult.LOCKED -> "locked"
      WidgetEditResult.OFFLINE -> "offline"
      WidgetEditResult.FAILED -> "failed"
      else -> null
    }
  )
  if (result == WidgetEditResult.CONFLICT) {
    context.startActivity(
      Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
  }
  AgendaWidget().update(context, glanceId)
}

/** Registers the widget with the system. */
class AgendaWidgetReceiver : GlanceAppWidgetReceiver() {
  override val glanceAppWidget: GlanceAppWidget = AgendaWidget()
}
