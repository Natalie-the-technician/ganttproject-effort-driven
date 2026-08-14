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
import biz.ganttproject.mobile.core.FileChangeState
import biz.ganttproject.mobile.core.ProjectModel
import biz.ganttproject.mobile.core.UndoHistory
import biz.ganttproject.mobile.core.compareFingerprint
import biz.ganttproject.mobile.core.contentFingerprint
import biz.ganttproject.mobile.core.expandedStates
import biz.ganttproject.mobile.core.restoreWithViewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What went wrong opening or saving; the UI maps these to localised text. */
sealed interface FileError {
  data object NotXml : FileError
  data object NotAProject : FileError
  data object PermissionLost : FileError
  data class OpenFailed(val detail: String) : FileError
  data class SaveFailed(val detail: String) : FileError

  /**
   * The file changed since it was opened — the desktop saved it, or the sync
   * client brought down a newer copy. Saving now would erase that work, so
   * the user gets asked instead.
   */
  data object ChangedElsewhere : FileError

  /**
   * Someone holds the WebDAV lock — the project is open on the desktop.
   *
   * Deliberately not [ChangedElsewhere]: nothing has been lost and nothing
   * has to be merged, the user simply has to wait. Folding the two together
   * would offer a conflict dialog for a situation that has no conflict.
   */
  data object LockedElsewhere : FileError

  /** The server said no, or could not be reached. */
  data class SyncFailed(val detail: String) : FileError

  /**
   * Signed in, but this folder is not shared with this account.
   *
   * A normal outcome, not a fault: the server grants per folder, and the
   * collection root deliberately allows browsing while refusing writes.
   * Reporting it as "server unreachable" would send the user looking for a
   * network problem that does not exist.
   */
  data object NoAccessToFolder : FileError

  /** The project is no longer on the server — deleted or renamed. */
  data object GoneFromServer : FileError

  /**
   * The version of the open project is unknown, so a safe save is not
   * possible.
   *
   * Reached when the server returned no ETag and could not be asked for one.
   * Writing anyway would be an unconditional overwrite — exactly what the
   * server exists to prevent — so the user is asked instead.
   */
  data object UnknownVersion : FileError
}

sealed interface FileResult<out T> {
  data class Ok<T>(val value: T) : FileResult<T>
  data class Err(val error: FileError) : FileResult<Nothing>
}

/** A project the app currently has open. */
class OpenProject(
  val uri: Uri,
  val displayName: String,
  document: GanttDocument,
  /**
   * True when the app holds read access but not write access.
   *
   * Happens for files handed over by a share sheet, which usually grant
   * read only. It has to be known up front: with auto-saving on, the
   * alternative is a save that fails at the moment the user walks away,
   * which is the one failure mode this app must not have.
   */
  val isReadOnly: Boolean = false,
  /**
   * File name on the sync server, or null for a project opened from the
   * device.
   *
   * The origin decides which store handles the save, and the two are not
   * interchangeable: a local save compares a fingerprint it took itself, a
   * remote save hands the decision to the server. Mixing them up would mean
   * writing with no version check at all.
   */
  val remoteName: String? = null
) {
  val isRemote: Boolean get() = remoteName != null
  /**
   * The document itself. Replaced wholesale by [undo] and [redo], which is
   * why it is not a `val`: stepping back means loading the bytes that were
   * there, not trying to reverse each edit.
   */
  var document: GanttDocument = document
    private set

  private val history = UndoHistory()

  val canUndo: Boolean get() = history.canUndo
  val canRedo: Boolean get() = history.canRedo

  /**
   * Snapshot for the UI. Rebuilt after every edit rather than mutated,
   * so Compose can compare states and nothing can hold a stale sub-object.
   */
  var model: ProjectModel = document.read()
    private set

  var isDirty: Boolean = false
    private set

  /**
   * Fingerprint of the bytes we last read from or wrote to this file.
   * Compared against the file just before every save; see [ProjectStore.save].
   */
  internal var lastKnownFingerprint: String? = null

  /**
   * ETag of the bytes last read from or written to the server, for remote
   * projects only.
   *
   * Null means "unknown", not "any" — see [RemoteStore.save], which then
   * refuses to write silently rather than writing unconditionally.
   */
  internal var lastKnownETag: String? = null

  /**
   * Applies an edit and refreshes the snapshot.
   *
   * @param edit returns false when the document refused the change (unknown
   *   id, summary task, invalid value); the project is then not marked dirty,
   *   so the save button does not light up for a change that did not happen.
   */
  fun edit(edit: (GanttDocument) -> Boolean): Boolean {
    // Taken before the edit, kept only if the edit actually happened. A
    // refused change must not leave an undo step that does nothing.
    val before = document.toXmlBytes()
    val changed = edit(document)
    if (changed) {
      history.record(before)
      model = document.read()
      isDirty = true
    }
    return changed
  }

  /** Steps back one edit. @return false if there is nothing to undo. */
  fun undo(): Boolean = step { history.undo(document.toXmlBytes()) }

  /** Steps forward again. @return false if there is nothing to redo. */
  fun redo(): Boolean = step { history.redo(document.toXmlBytes()) }

  /**
   * Replaces the document with a remembered state.
   *
   * The fold state of the moment is carried across rather than restored from
   * the snapshot: folding is not an edit, so undoing a progress change must
   * not also reopen a group the user just closed.
   */
  private fun step(take: () -> ByteArray?): Boolean {
    val snapshot = take() ?: return false
    document = restoreWithViewState(snapshot, model.expandedStates())
    model = document.read()
    // Undoing back to what is on disk leaves nothing to save. Compared by
    // content rather than by counting steps, which would be wrong the moment
    // a save happened somewhere in the middle of the history.
    isDirty = contentFingerprint(document.toXmlBytes()) != lastKnownFingerprint
    return true
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

  internal fun markSaved(fingerprint: String?) {
    isDirty = false
    lastKnownFingerprint = fingerprint
  }

  internal fun markSavedRemote(fingerprint: String?, etag: String?) {
    isDirty = false
    lastKnownFingerprint = fingerprint
    lastKnownETag = etag
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
    FileResult.Ok(
      OpenProject(uri, displayName(uri), document, isReadOnly = !canWrite(uri)).apply {
        lastKnownFingerprint = contentFingerprint(bytes)
      }
    )
  }

  /**
   * Writes the project back to the file it came from.
   *
   * **Refuses to overwrite a file that changed underneath us.** A project in
   * a synced folder has several writers — this phone, the desktop, and the
   * sync client — and without the check, saving means writing over whatever
   * arrived in the meantime, silently. The user is asked instead
   * ([FileError.ChangedElsewhere]) and can still insist via [force].
   *
   * Mode "wt" truncates first. Without it a file that shrinks — say after
   * removing an assignment — would keep the tail of the previous version and
   * become unparseable.
   *
   * @param force skip the change check and overwrite regardless. Only ever
   *   set from an explicit user decision.
   */
  suspend fun save(project: OpenProject, force: Boolean = false): FileResult<Unit> =
    withContext(Dispatchers.IO) {
      if (!force && readFingerprint(project.uri).let { current ->
          compareFingerprint(project.lastKnownFingerprint, current) ==
            FileChangeState.CHANGED_ELSEWHERE
        }
      ) {
        return@withContext FileResult.Err(FileError.ChangedElsewhere)
      }

      val bytes = project.document.toXmlBytes()
      try {
        resolver.openOutputStream(project.uri, "wt")?.use { it.write(bytes) }
          ?: return@withContext FileResult.Err(FileError.SaveFailed("no stream"))
      } catch (e: SecurityException) {
        return@withContext FileResult.Err(FileError.PermissionLost)
      } catch (e: Exception) {
        return@withContext FileResult.Err(FileError.SaveFailed(e.message ?: "unknown"))
      }
      project.markSaved(contentFingerprint(bytes))
      FileResult.Ok(Unit)
    }

  /** Writes the project to a different file, leaving the original alone. */
  suspend fun saveCopy(project: OpenProject, target: Uri): FileResult<Unit> =
    withContext(Dispatchers.IO) {
      val bytes = project.document.toXmlBytes()
      try {
        resolver.openOutputStream(target, "wt")?.use { it.write(bytes) }
          ?: return@withContext FileResult.Err(FileError.SaveFailed("no stream"))
      } catch (e: Exception) {
        return@withContext FileResult.Err(FileError.SaveFailed(e.message ?: "unknown"))
      }
      FileResult.Ok(Unit)
    }

  /** Fingerprint of the file as it is right now, or null if unreadable. */
  private fun readFingerprint(uri: Uri): String? = runCatching {
    resolver.openInputStream(uri)?.use { contentFingerprint(it.readBytes()) }
  }.getOrNull()

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
