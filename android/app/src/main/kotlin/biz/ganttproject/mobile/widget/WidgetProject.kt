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
import biz.ganttproject.mobile.core.TimeRecord
import biz.ganttproject.mobile.core.TimeSource
import biz.ganttproject.mobile.core.agenda
import biz.ganttproject.mobile.core.compareFingerprint
import biz.ganttproject.mobile.core.contentFingerprint
import biz.ganttproject.mobile.core.FileChangeState
import biz.ganttproject.mobile.core.DavResult
import biz.ganttproject.mobile.core.WebDavClient
import biz.ganttproject.mobile.core.WebDavConfig
import biz.ganttproject.mobile.data.AppPreferences
import biz.ganttproject.mobile.data.SecureStore
import biz.ganttproject.mobile.data.WidgetTarget
import biz.ganttproject.mobile.net.AndroidHttpBackend
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** What the widget managed to load, and what went wrong if it did not. */
sealed interface WidgetState {
  /** No project has been chosen for the widget yet. */
  data object NoProject : WidgetState

  /** The file is gone, or the app lost permission to it. */
  data object Unreadable : WidgetState

  data class Loaded(
    val projectName: String,
    val items: List<AgendaItem>,
    val windowDays: Int,
    /**
     * Whether the tick and the +½ h button are offered at all. When editing
     * from the widget is switched off, the list stays — a plan is worth
     * looking at even when you must not change it from here.
     */
    val canEdit: Boolean,
    /**
     * When the drawn content was last fetched, or 0 for a local file, which
     * is read directly and is therefore never out of date.
     */
    val snapshotAt: Long = 0L,
    /** Why the last tap did nothing, for the half minute after it. */
    val notice: String? = null
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
  FAILED,

  /**
   * Refused because editing from the widget is switched off.
   *
   * Reachable even though the buttons are hidden: a widget on the home screen
   * can be a redraw behind the setting, and the tap that is already in flight
   * has to land somewhere.
   */
  DISABLED,

  /**
   * Refused because the project is open on the desktop.
   *
   * Its own outcome, not a [CONFLICT]: nothing collided and nothing is lost,
   * the file is simply held. Telling somebody to resolve a conflict that does
   * not exist would send them into the app to compare two versions that are
   * the same.
   */
  LOCKED,

  /** The server could not be reached. Nothing was written. */
  OFFLINE
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

  private val snapshot = WidgetSnapshot(context)

  /** The project name on the server, or null when the widget shows a file. */
  private val remoteName: String? get() = (prefs.widgetTarget() as? WidgetTarget.Remote)?.name

  fun load(): WidgetState {
    val target = prefs.widgetTarget()
    val bytes = when (target) {
      // Never the network here. A redraw is the system's decision, not the
      // user's, and a widget that fetches whenever Android feels like it is
      // background traffic by another name.
      is WidgetTarget.Remote -> snapshot.read() ?: return WidgetState.Unreadable
      is WidgetTarget.Local -> readBytes(Uri.parse(target.uri)) ?: return WidgetState.Unreadable
      WidgetTarget.None -> return WidgetState.NoProject
    }
    val remote = (target as? WidgetTarget.Remote)?.name
    val document = runCatching { GanttDocument.load(bytes) }.getOrNull()
      ?: return WidgetState.Unreadable
    val model = document.read()
    val days = prefs.widgetWindowDays()
    return WidgetState.Loaded(
      projectName = model.name.ifBlank { prefs.widgetProjectName().orEmpty() },
      items = agenda(model, LocalDate.now(), days),
      windowDays = days,
      canEdit = prefs.editScope().allowsWidgetEdits,
      snapshotAt = if (remote != null) prefs.widgetSnapshotAt() else 0L,
      notice = prefs.widgetNotice()
    )
  }

  /**
   * Fetches the project from the server and replaces the snapshot.
   *
   * The refresh button. Everything else about the widget avoids the network;
   * this is the one place the user asks for it, so it is the one place that
   * goes.
   */
  fun refresh(): WidgetEditResult {
    val name = remoteName ?: return WidgetEditResult.SAVED
    val client = client() ?: return WidgetEditResult.FAILED
    return when (val result = client.read(name)) {
      is DavResult.Ok -> {
        snapshot.write(result.value.bytes, System.currentTimeMillis())
        WidgetEditResult.SAVED
      }
      is DavResult.Failed -> failureFor(result.error)
    }
  }

  private fun client(): WebDavClient? {
    val base = prefs.davBaseUrl() ?: return null
    val user = prefs.davUsername() ?: return null
    val password = SecureStore(context).getSecret(SecureStore.KEY_DAV_PASSWORD) ?: return null
    return WebDavClient(AndroidHttpBackend(), WebDavConfig(base, user, password))
  }

  private fun failureFor(error: biz.ganttproject.mobile.core.DavError): WidgetEditResult =
    when (error) {
      biz.ganttproject.mobile.core.DavError.LockedElsewhere -> WidgetEditResult.LOCKED
      biz.ganttproject.mobile.core.DavError.ChangedElsewhere -> WidgetEditResult.CONFLICT
      is biz.ganttproject.mobile.core.DavError.Network -> WidgetEditResult.OFFLINE
      else -> WidgetEditResult.FAILED
    }

  /**
   * Applies [change] to the project on the server.
   *
   * Read, change, write back conditionally — the same shape as the local
   * path, with the server doing the checking instead of a fingerprint. The
   * ETag is fetched here rather than remembered: the widget holds nothing
   * between taps, and a stored one would let a tap write against a version
   * the user never saw.
   */
  private fun editRemote(name: String, change: (GanttDocument) -> Boolean): WidgetEditResult {
    if (!prefs.editScope().allowsWidgetEdits) return WidgetEditResult.DISABLED
    val client = client() ?: return WidgetEditResult.FAILED
    val current = when (val result = client.read(name)) {
      is DavResult.Ok -> result.value
      is DavResult.Failed -> return failureFor(result.error)
    }
    val etag = current.etag ?: return WidgetEditResult.FAILED
    val document = runCatching { GanttDocument.load(current.bytes) }.getOrNull()
      ?: return WidgetEditResult.FAILED
    if (!change(document)) return WidgetEditResult.FAILED
    val bytes = document.toXmlBytes()
    return when (val result = client.write(name, bytes, etag)) {
      is DavResult.Ok -> {
        snapshot.write(bytes, System.currentTimeMillis())
        WidgetEditResult.SAVED
      }
      is DavResult.Failed -> failureFor(result.error)
    }
  }

  /** Marks a task finished. */
  fun complete(taskId: String): WidgetEditResult =
    edit { it.setTaskCompletion(taskId, 100) }

  /**
   * Adds to a task's recorded hours; the widget's "I just worked on this".
   *
   * Writes a record as well as the total. Only the total would leave the log
   * disagreeing with it after every tap from the home screen, and the widget
   * runs in its own process, so nothing would be there to notice.
   *
   * The stretch is placed ending now, like every other booking: a tap means
   * the work just happened, and a real timestamp is what decides which day the
   * hours count towards.
   *
   * A task with no uid still gets its total. The record cannot be attached,
   * and the task sheet will show that the two disagree rather than either of
   * them being quietly wrong.
   */
  fun addHours(taskId: String, hours: Double): WidgetEditResult =
    edit { document ->
      val seconds = Math.round(hours * 3600.0)
      if (seconds <= 0L) return@edit false
      document.taskUidOfId(taskId)?.let { uid ->
        val now = OffsetDateTime.now()
        document.addTimeRecord(
          TimeRecord(
            id = UUID.randomUUID().toString(),
            taskUid = uid,
            start = now.minusSeconds(seconds),
            durationSeconds = seconds,
            description = "",
            person = prefs.person(),
            source = TimeSource.QUICK,
            createdAt = now
          )
        )
      }
      document.addTaskActualEffortHours(taskId, hours) != null
    }

  /** Sets progress to a fixed step, for the quick 25/50/75 taps. */
  fun setProgress(taskId: String, percent: Int): WidgetEditResult =
    edit { it.setTaskCompletion(taskId, percent) }

  /**
   * Reads, applies [change], and writes back — refusing if the file moved
   * underneath us in between.
   */
  private fun edit(change: (GanttDocument) -> Boolean): WidgetEditResult {
    // Read fresh from disk on every tap, never cached: the widget lives in a
    // different process from the app, so a value cached here would keep
    // writing after the user switched editing off.
    remoteName?.let { return editRemote(it, change) }
    if (!prefs.editScope().allowsWidgetEdits) return WidgetEditResult.DISABLED
    val target = prefs.widgetTarget() as? WidgetTarget.Local ?: return WidgetEditResult.FAILED
    val uri = Uri.parse(target.uri)
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
