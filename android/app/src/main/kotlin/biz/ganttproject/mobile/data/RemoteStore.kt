/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.data

import android.net.Uri
import biz.ganttproject.mobile.core.DavError
import biz.ganttproject.mobile.core.DavResult
import biz.ganttproject.mobile.core.GanttDocument
import biz.ganttproject.mobile.core.GanttFormatException
import biz.ganttproject.mobile.core.WebDavClient
import biz.ganttproject.mobile.core.contentFingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads and writes projects on the sync server.
 *
 * The counterpart to [ProjectStore], and deliberately the same shape so the
 * view model can treat both origins alike. The difference that matters is
 * who decides whether a save is safe: locally the app compares a fingerprint
 * it took itself and can be overtaken between checking and writing; here the
 * server decides, at the moment of writing, and says no by returning 412.
 */
class RemoteStore(private val client: WebDavClient) {

  /** Lists the projects on the server. */
  suspend fun list(): FileResult<List<String>> = withContext(Dispatchers.IO) {
    when (val result = client.list()) {
      is DavResult.Ok -> FileResult.Ok(result.value)
      is DavResult.Failed -> FileResult.Err(translate(result.error))
    }
  }

  /** Opens a project by file name, remembering the version it came from. */
  suspend fun open(name: String): FileResult<OpenProject> = withContext(Dispatchers.IO) {
    val remote = when (val result = client.read(name)) {
      is DavResult.Ok -> result.value
      is DavResult.Failed -> return@withContext FileResult.Err(translate(result.error))
    }
    val document = try {
      GanttDocument.load(remote.bytes)
    } catch (e: GanttFormatException) {
      return@withContext FileResult.Err(
        when (e.reason) {
          GanttFormatException.Reason.NOT_XML -> FileError.NotXml
          GanttFormatException.Reason.NOT_A_PROJECT -> FileError.NotAProject
        }
      )
    } catch (e: Exception) {
      return@withContext FileResult.Err(FileError.OpenFailed(e.message ?: "unknown"))
    }
    // The URI is carried for the recent-files list and for display only;
    // nothing reads the file through it. Saving goes back through this store.
    val project = OpenProject(
      uri = Uri.parse(client.urlFor(name)),
      displayName = name,
      document = document,
      isReadOnly = false,
      remoteName = name
    )
    project.markSavedRemote(contentFingerprint(remote.bytes), remote.etag)
    FileResult.Ok(project)
  }

  /**
   * Writes the project back, conditional on the version it was read at.
   *
   * [force] drops the condition — the "overwrite anyway" from the conflict
   * dialog, and the only way an unconditional write ever happens.
   *
   * Without [force] and without a known version the save is **refused**
   * rather than attempted. That combination means the app cannot tell what
   * is on the server, and writing blind is the precise failure this whole
   * arrangement exists to prevent; better to ask the user than to guess on
   * their behalf.
   */
  suspend fun save(project: OpenProject, force: Boolean = false): FileResult<Unit> =
    withContext(Dispatchers.IO) {
      val name = project.remoteName
        ?: return@withContext FileResult.Err(FileError.SaveFailed("not a server project"))
      if (!force && project.lastKnownETag == null) {
        return@withContext FileResult.Err(FileError.UnknownVersion)
      }
      val bytes = project.document.toXmlBytes()
      when (val result = client.write(name, bytes, if (force) null else project.lastKnownETag)) {
        is DavResult.Ok -> {
          project.markSavedRemote(contentFingerprint(bytes), result.value)
          FileResult.Ok(Unit)
        }
        is DavResult.Failed -> FileResult.Err(translate(result.error))
      }
    }

  /**
   * Writes the project to a second name on the server, leaving the original
   * untouched.
   *
   * The way out of a conflict that keeps both versions. Uses a conditional
   * create so that a copy can never land on top of something else — the user
   * asked to keep work, not to overwrite different work.
   */
  suspend fun saveCopy(project: OpenProject, name: String): FileResult<Unit> =
    withContext(Dispatchers.IO) {
      when (val result = client.createNew(name, project.document.toXmlBytes())) {
        is DavResult.Ok -> FileResult.Ok(Unit)
        is DavResult.Failed -> FileResult.Err(translate(result.error))
      }
    }

  /**
   * Maps a transport-level error to what the app tells the user.
   *
   * The three that must stay apart are [FileError.ChangedElsewhere] (someone
   * saved over your base version — a real conflict), [FileError.LockedElsewhere]
   * (open on the desktop — wait) and everything else (nothing to decide, try
   * again). Collapsing them would put the wrong question in front of the user
   * at the moment their data is at stake.
   */
  private fun translate(error: DavError): FileError = when (error) {
    DavError.ChangedElsewhere -> FileError.ChangedElsewhere
    DavError.LockedElsewhere -> FileError.LockedElsewhere
    DavError.NotFound -> FileError.PermissionLost
    DavError.Unauthorized -> FileError.SyncFailed("401")
    DavError.Forbidden -> FileError.SyncFailed("403")
    DavError.Insecure -> FileError.SyncFailed("https")
    is DavError.Server -> FileError.SyncFailed(error.code.toString())
    is DavError.Network -> FileError.SyncFailed(error.detail)
  }
}
