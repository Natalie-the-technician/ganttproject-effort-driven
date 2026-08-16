/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.widget

import android.content.Context
import biz.ganttproject.mobile.data.AppPreferences
import java.io.File

/**
 * The last known content of a server project, kept on the device so the
 * widget can draw without asking the network.
 *
 * **Read-only, and that is the whole justification.** The app deliberately
 * has no offline cache: a second copy that can be *edited* is a second
 * version without an arbiter, which is the exact problem the sync server
 * exists to solve. This one is never edited and never written back — a tap on
 * the widget goes to the server, and the snapshot is refreshed from what the
 * server then holds. It is a picture of the file, not a copy of it.
 *
 * The alternative was a widget that fetches on every redraw. Redrawing is not
 * a user action — the system does it when it likes — and background traffic
 * to somebody's own server is a question of manners as much as battery.
 */
class WidgetSnapshot(private val context: Context) {

  private val prefs = AppPreferences(context)
  private val file: File get() = File(context.filesDir, FILE_NAME)

  /** The bytes last seen on the server, or null when there is no snapshot. */
  fun read(): ByteArray? = runCatching {
    if (file.exists()) file.readBytes() else null
  }.getOrNull()

  /**
   * Replaces the snapshot and stamps it with [takenAt].
   *
   * Written to a neighbouring file first and then moved into place. A widget
   * reads in a different process and at a moment nobody chooses; without the
   * move it could catch a half-written file and report the project as
   * unreadable, which looks exactly like the file having gone missing.
   */
  fun write(bytes: ByteArray, takenAt: Long) {
    runCatching {
      val staging = File(context.filesDir, "$FILE_NAME.neu")
      staging.writeBytes(bytes)
      staging.renameTo(file)
      prefs.setWidgetSnapshotAt(takenAt)
    }
  }

  /** Drops the snapshot, for when the widget is pointed at a local file. */
  fun clear() {
    runCatching { file.delete() }
    prefs.setWidgetSnapshotAt(0L)
  }

  private companion object {
    const val FILE_NAME = "widget-snapshot.gan"
  }
}
