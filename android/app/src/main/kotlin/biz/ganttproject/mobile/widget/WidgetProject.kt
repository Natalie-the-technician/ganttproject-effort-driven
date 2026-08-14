/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.widget

import android.content.Context
import android.net.Uri
import biz.ganttproject.mobile.core.AgendaItem
import biz.ganttproject.mobile.core.AgendaWindow
import biz.ganttproject.mobile.core.GanttDocument
import biz.ganttproject.mobile.core.agenda
import biz.ganttproject.mobile.core.compareFingerprint
import biz.ganttproject.mobile.core.contentFingerprint
import biz.ganttproject.mobile.core.FileChangeState
import biz.ganttproject.mobile.data.AppPreferences
import java.time.LocalDate

/** What the widget managed to load, and what went wrong if it did not. */
sealed interface WidgetState {
  /** No project has been chosen for the widget yet. */
  data object NoProject : WidgetState

  /** The file is gone, or the app lost permission to it. */
  data object Unreadable : WidgetState

  data class Loaded(
    val projectName: String,
    val items: List<AgendaItem>,
    val windowDays: Int
  ) : WidgetState
}

/** Outcome of a change made from the widget, so it can be shown right there. */
enum class WidgetEditResult {
  /** Written to the file. */
  SAVED,

  /**
   * Refused: the file changed since the widget last read it. The widget must
   * not resolve this on its own — there is no room to explain the choice or
   * to offer keeping both versions, so it points at the app instead.
   */
  CONFLICT,

  /** The file could not be read or written at all. */
  FAILED
}

/**
 * Loads and edits the project on behalf of the widget.
 *
 * Deliberately separate from the app's `ProjectStore`: a widget has no
 * ViewModel, no lifecycle to hang state on, and no way to hold a document
 * open between taps. Every operation here therefore reads the file, does one
 * thing, and writes it back.
 *
 * That read-modify-write is only safe because it is guarded: the fingerprint
 * check from the app applies here too, so a tap on the home screen can never
 * overwrite an edit that arrived from the desktop in the meantime.
 */
class WidgetProject(private val context: Context) {

  private val prefs = AppPreferences(context)

  fun load(): WidgetState {
    val uri = prefs.widgetProjectUri()?.let(Uri::parse) ?: return WidgetState.NoProject
    val bytes = readBytes(uri) ?: return WidgetState.Unreadable
    val document = runCatching { GanttDocument.load(bytes) }.getOrNull()
      ?: return WidgetState.Unreadable
    val model = document.read()
    val days = prefs.widgetWindowDays()
    return WidgetState.Loaded(
      projectName = model.name.ifBlank { prefs.widgetProjectName().orEmpty() },
      items = agenda(model, LocalDate.now(), days),
      windowDays = days
    )
  }

  /** Marks a task finished. */
  fun complete(taskId: String): WidgetEditResult =
    edit { it.setTaskCompletion(taskId, 100) }

  /** Adds to a task's recorded hours; the widget's "I just worked on this". */
  fun addHours(taskId: String, hours: Double): WidgetEditResult =
    edit { it.addTaskActualEffortHours(taskId, hours) != null }

  /** Sets progress to a fixed step, for the quick 25/50/75 taps. */
  fun setProgress(taskId: String, percent: Int): WidgetEditResult =
    edit { it.setTaskCompletion(taskId, percent) }

  /**
   * Reads, applies [change], and writes back — refusing if the file moved
   * underneath us in between.
   */
  private fun edit(change: (GanttDocument) -> Boolean): WidgetEditResult {
    val uri = prefs.widgetProjectUri()?.let(Uri::parse) ?: return WidgetEditResult.FAILED
    val before = readBytes(uri) ?: return WidgetEditResult.FAILED
    val document = runCatching { GanttDocument.load(before) }.getOrNull()
      ?: return WidgetEditResult.FAILED

    if (!change(document)) return WidgetEditResult.FAILED

    // Re-read immediately before writing. The window is small but real: a
    // sync client can land a new copy between the two reads, and this is the
    // last moment where noticing costs nothing.
    val current = readBytes(uri)?.let(::contentFingerprint)
    if (compareFingerprint(contentFingerprint(before), current) ==
      FileChangeState.CHANGED_ELSEWHERE
    ) {
      return WidgetEditResult.CONFLICT
    }

    return runCatching {
      context.contentResolver.openOutputStream(uri, "wt")?.use {
        it.write(document.toXmlBytes())
      } ?: return WidgetEditResult.FAILED
      WidgetEditResult.SAVED
    }.getOrDefault(WidgetEditResult.FAILED)
  }

  private fun readBytes(uri: Uri): ByteArray? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
  }.getOrNull()

  companion object {
    val WINDOW_CHOICES = AgendaWindow.CHOICES
  }
}
