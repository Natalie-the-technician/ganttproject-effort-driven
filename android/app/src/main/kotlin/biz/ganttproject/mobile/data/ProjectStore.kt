/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import biz.ganttproject.mobile.core.GanttDocument
import biz.ganttproject.mobile.core.GanttFormatException
import biz.ganttproject.mobile.core.ProjectModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What went wrong opening or saving; the UI maps these to localised text. */
sealed interface FileError {
  data object NotXml : FileError
  data object NotAProject : FileError
  data object PermissionLost : FileError
  data class OpenFailed(val detail: String) : FileError
  data class SaveFailed(val detail: String) : FileError
}

sealed interface FileResult<out T> {
  data class Ok<T>(val value: T) : FileResult<T>
  data class Err(val error: FileError) : FileResult<Nothing>
}

/** A project the app currently has open. */
class OpenProject(
  val uri: Uri,
  val displayName: String,
  val document: GanttDocument,
  /**
   * True when the app holds read access but not write access.
   *
   * Happens for files handed over by a share sheet, which usually grant
   * read only. It has to be known up front: with auto-saving on, the
   * alternative is a save that fails at the moment the user walks away,
   * which is the one failure mode this app must not have.
   */
  val isReadOnly: Boolean = false
) {
  /**
   * Snapshot for the UI. Rebuilt after every edit rather than mutated,
   * so Compose can compare states and nothing can hold a stale sub-object.
   */
  var model: ProjectModel = document.read()
    private set

  var isDirty: Boolean = false
    private set

  /**
   * Applies an edit and refreshes the snapshot.
   *
   * @param edit returns false when the document refused the change (unknown
   *   id, summary task, invalid value); the project is then not marked dirty,
   *   so the save button does not light up for a change that did not happen.
   */
  fun edit(edit: (GanttDocument) -> Boolean): Boolean {
    val changed = edit(document)
    if (changed) {
      model = document.read()
      isDirty = true
    }
    return changed
  }

  /**
   * Applies a change that is view state, not content — folding a task group,
   * for instance — and refreshes the snapshot **without** marking the project
   * dirty.
   *
   * Merely looking at a project must not rewrite the file. With auto-saving
   * on, treating a fold as an edit would mean every browse session writes to
   * a synced folder and churns its history. The change still sits in the
   * document, so it rides along the next time something real is saved.
   */
  fun editViewState(edit: (GanttDocument) -> Boolean): Boolean {
    val changed = edit(document)
    if (changed) model = document.read()
    return changed
  }

  internal fun markSaved() {
    isDirty = false
  }
}

/**
 * Reads and writes project files through the Storage Access Framework.
 *
 * SAF rather than direct file paths on purpose: it covers local storage and
 * every cloud provider installed on the device (Drive, Nextcloud, OneDrive,
 * Dropbox) with the same code, and it needs no storage permission — the app
 * can only ever touch files the user handed it explicitly.
 */
class ProjectStore(private val context: Context) {

  private val resolver: ContentResolver get() = context.contentResolver

  /**
   * Keeps access to [uri] across app restarts.
   *
   * A provider may refuse; that is not fatal — the file still opens for this
   * session, it just will not appear as a working entry in the recent list.
   */
  fun persistPermission(uri: Uri): Boolean = runCatching {
    resolver.takePersistableUriPermission(
      uri,
      Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    )
    true
  }.getOrDefault(false)

  suspend fun open(uri: Uri): FileResult<OpenProject> = withContext(Dispatchers.IO) {
    val bytes = try {
      resolver.openInputStream(uri)?.use { it.readBytes() }
        ?: return@withContext FileResult.Err(FileError.OpenFailed("no stream"))
    } catch (e: SecurityException) {
      // The persisted grant expired, or the file was opened from a
      // notification whose one-shot permission is gone.
      return@withContext FileResult.Err(FileError.PermissionLost)
    } catch (e: Exception) {
      return@withContext FileResult.Err(FileError.OpenFailed(e.message ?: "unknown"))
    }

    val document = try {
      GanttDocument.load(bytes)
    } catch (e: GanttFormatException) {
      return@withContext FileResult.Err(
        when (e.reason) {
          GanttFormatException.Reason.NOT_XML -> FileError.NotXml
          GanttFormatException.Reason.NOT_A_PROJECT -> FileError.NotAProject
        }
      )
    }
    FileResult.Ok(OpenProject(uri, displayName(uri), document, isReadOnly = !canWrite(uri)))
  }

  /**
   * Writes the project back to the file it came from.
   *
   * Mode "wt" truncates first. Without it a file that shrinks — say after
   * removing an assignment — would keep the tail of the previous version and
   * become unparseable.
   */
  suspend fun save(project: OpenProject): FileResult<Unit> = withContext(Dispatchers.IO) {
    val bytes = project.document.toXmlBytes()
    try {
      resolver.openOutputStream(project.uri, "wt")?.use { it.write(bytes) }
        ?: return@withContext FileResult.Err(FileError.SaveFailed("no stream"))
    } catch (e: SecurityException) {
      return@withContext FileResult.Err(FileError.PermissionLost)
    } catch (e: Exception) {
      return@withContext FileResult.Err(FileError.SaveFailed(e.message ?: "unknown"))
    }
    project.markSaved()
    FileResult.Ok(Unit)
  }

  /**
   * Whether this URI may be written back.
   *
   * Asked rather than assumed: a document picked through the picker is
   * writable, one arriving from a share sheet usually is not, and the
   * difference decides whether auto-saving may run at all.
   */
  private fun canWrite(uri: Uri): Boolean = runCatching {
    context.checkUriPermission(
      uri,
      android.os.Process.myPid(),
      android.os.Process.myUid(),
      Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
  }.getOrDefault(false)

  /** Human-readable file name, falling back to the last path segment. */
  fun displayName(uri: Uri): String {
    runCatching {
      resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
          val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (index >= 0) return cursor.getString(index)
        }
      }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
  }
}
