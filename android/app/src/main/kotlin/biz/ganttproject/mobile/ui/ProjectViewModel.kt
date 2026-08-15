/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import biz.ganttproject.mobile.core.EditScope
import biz.ganttproject.mobile.core.GanttDocument
import biz.ganttproject.mobile.core.ImportAssignment
import biz.ganttproject.mobile.core.ImportPlan
import biz.ganttproject.mobile.core.Matching
import biz.ganttproject.mobile.core.ProjectModel
import biz.ganttproject.mobile.core.TaskCandidate
import biz.ganttproject.mobile.core.TogglClient
import biz.ganttproject.mobile.core.TogglError
import biz.ganttproject.mobile.core.TogglResult
import biz.ganttproject.mobile.core.TogglTimeEntry
import biz.ganttproject.mobile.core.DavError
import biz.ganttproject.mobile.core.DavResult
import biz.ganttproject.mobile.core.WebDavClient
import biz.ganttproject.mobile.core.WebDavConfig
import biz.ganttproject.mobile.core.MatchOutcome
import biz.ganttproject.mobile.core.SyncGuarantee
import biz.ganttproject.mobile.core.needsUnmanagedStorageWarning
import biz.ganttproject.mobile.core.planImport
import biz.ganttproject.mobile.data.AppPreferences
import biz.ganttproject.mobile.data.FileError
import biz.ganttproject.mobile.data.FileResult
import biz.ganttproject.mobile.data.OpenProject
import biz.ganttproject.mobile.data.ProjectStore
import biz.ganttproject.mobile.data.RemoteStore
import biz.ganttproject.mobile.data.RecentFile
import biz.ganttproject.mobile.data.SecureStore
import androidx.glance.appwidget.updateAll
import biz.ganttproject.mobile.net.AndroidHttpBackend
import biz.ganttproject.mobile.widget.AgendaWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** What the UI needs to know about the currently open project. */
data class ProjectUi(
  val displayName: String,
  val model: ProjectModel,
  val isDirty: Boolean,
  /** Opened without write access — usually via a share sheet. */
  val isReadOnly: Boolean,
  /**
   * Whether this project may be changed at all: write access to the file
   * *and* the user's own edit-protection setting. The screens disable their
   * controls on this; [ProjectViewModel.edit] enforces it regardless.
   */
  val canEdit: Boolean,
  val canUndo: Boolean,
  val canRedo: Boolean,
  /** Came from the sync server rather than from device storage. */
  val isRemote: Boolean,
  /**
   * Bumped on every edit. [ProjectModel] is a value snapshot, but the
   * enclosing [OpenProject] is mutable, so an explicit revision keeps
   * recomposition honest rather than relying on reference identity.
   */
  val revision: Int
)

/** Outcome of the last connection check against the sync server. */
sealed interface DavCheck {
  /**
   * The server answered. [supportsLocking] decides whether conflicts can be
   * prevented or merely reported, so it is shown to the user rather than
   * kept as an internal detail.
   */
  data class Reachable(val supportsLocking: Boolean) : DavCheck

  /**
   * The server answered, but the address does not name a folder on it.
   *
   * Its own outcome rather than a [Failed]: nothing is wrong with the server
   * or the credentials, and telling the user to check those would send them
   * to the wrong place. The address is what needs fixing.
   */
  data object NoSuchFolder : DavCheck

  data class Failed(val error: DavError) : DavCheck
}

/** The sync-server settings as edited in the dialog. */
data class DavSettingsState(
  val baseUrl: String = "",
  val username: String = "",
  val password: String = "",
  val supportsLocking: Boolean = false,
  val checking: Boolean = false,
  val checked: DavCheck? = null
) {
  val configured: Boolean get() = baseUrl.isNotBlank()
}

/** What the widget is configured to show. */
data class WidgetSettings(val projectName: String?, val windowDays: Int)

data class AppState(
  val busy: Boolean = false,
  val widget: WidgetSettings = WidgetSettings(null, 14),
  /** How much this device is allowed to change; see [EditScope]. */
  val editScope: EditScope = EditScope.DEFAULT,
  val project: ProjectUi? = null,
  val recentFiles: List<RecentFile> = emptyList(),
  /** Project names found on the sync server, empty until asked for. */
  val remoteProjects: List<String> = emptyList(),
  val fileError: FileError? = null,
  /** One-shot confirmation, e.g. after a successful save. */
  val notice: Notice? = null
)

sealed interface Notice {
  data object Saved : Notice
  data class HoursImported(val hours: Double) : Notice

  /**
   * The project was opened while someone has it open on the desktop.
   *
   * Said at opening rather than at saving. The desktop takes a 120-minute
   * lock whenever it opens a project, so this is the ordinary state during a
   * working day at the PC — and finding out after half an hour of editing
   * would be the avoidable version of the same news.
   */
  data object OpenedWhileLocked : Notice
}

/** One time entry plus the decision the user has (or has not) made about it. */
data class ImportRow(
  val entry: TogglTimeEntry,
  val outcome: MatchOutcome,
  val selectedTaskId: String?,
  val skip: Boolean,
  val alreadyImportedHours: Double
) {
  val needsAttention: Boolean get() = !skip && selectedTaskId == null
}

data class ImportState(
  val token: String = "",
  val tokenVerified: String? = null,
  val from: LocalDate = LocalDate.now().minusDays(7),
  val to: LocalDate = LocalDate.now(),
  val busy: Boolean = false,
  val rows: List<ImportRow> = emptyList(),
  val loaded: Boolean = false,
  val error: TogglError? = null,
  val learnKeys: Boolean = true
) {
  /** Assignments the user has confirmed, ready for [planImport]. */
  val assignments: List<ImportAssignment>
    get() = rows.filter { !it.skip && it.selectedTaskId != null }
      .map { ImportAssignment(it.entry.id, it.selectedTaskId!!, it.entry.hours) }
}

class ProjectViewModel(application: Application) : AndroidViewModel(application) {

  private companion object {
    /**
     * Whether the desktop client takes WebDAV locks and writes conditionally.
     *
     * A constant rather than a setting: the app cannot observe what the
     * desktop does, and a user-facing switch would be a promise the user has
     * no way to check. Set to true in the same change that lands D1 and D3.
     */
    const val DESKTOP_HONOURS_LOCKS = false
  }

  private val store = ProjectStore(application)
  private val prefs = AppPreferences(application)
  private val secure = SecureStore(application)
  private val http = AndroidHttpBackend()

  private var open: OpenProject? = null
  private var revision = 0

  private val _state = MutableStateFlow(
    AppState(
      recentFiles = prefs.recentFiles(),
      widget = WidgetSettings(prefs.widgetProjectName(), prefs.widgetWindowDays()),
      editScope = prefs.editScope()
    )
  )
  val state: StateFlow<AppState> = _state.asStateFlow()

  private val _importState = MutableStateFlow(
    ImportState(token = secure.getSecret(SecureStore.KEY_TOGGL_TOKEN).orEmpty())
  )
  val importState: StateFlow<ImportState> = _importState.asStateFlow()

  /** Server settings as shown in the dialog, plus the last check's outcome. */
  private val _dav = MutableStateFlow(loadDavSettings())
  val dav: StateFlow<DavSettingsState> = _dav.asStateFlow()

  // ------------------------------------------------------------ File level

  fun openUri(uri: Uri) {
    viewModelScope.launch {
      _state.update { it.copy(busy = true, fileError = null) }
      store.persistPermission(uri)
      when (val result = store.open(uri)) {
        is FileResult.Ok -> {
          open = result.value
          prefs.rememberFile(uri.toString(), result.value.displayName, System.currentTimeMillis())
          revision = 0
          _state.update {
            it.copy(busy = false, project = snapshot(result.value), recentFiles = prefs.recentFiles())
          }
          // A new project means a different ledger and different candidates.
          _importState.update { it.copy(rows = emptyList(), loaded = false) }
        }
        is FileResult.Err -> {
          // A file that no longer opens should not stay in the recent list
          // offering to fail again.
          if (result.error == FileError.PermissionLost) prefs.forgetFile(uri.toString())
          _state.update {
            it.copy(busy = false, fileError = result.error, recentFiles = prefs.recentFiles())
          }
        }
      }
    }
  }

  /**
   * Writes the project through whichever store owns it.
   *
   * Every save path must come through here. Reaching for [store] directly
   * happens to work for a local file and quietly misroutes a server project:
   * its URI is an `https` address, which the `ContentResolver` cannot open for
   * writing, so the save fails — and fails citing the file rather than the
   * one thing that actually went wrong, which is that the app asked the wrong
   * store.
   */
  private suspend fun saveThrough(project: OpenProject, force: Boolean): FileResult<Unit> =
    if (project.isRemote) {
      remoteStore()?.save(project, force)
        ?: FileResult.Err(FileError.SyncFailed("not configured"))
    } else {
      store.save(project, force)
    }

  fun save(force: Boolean = false) {
    val project = open ?: return
    if (project.isReadOnly) return
    viewModelScope.launch {
      _state.update { it.copy(busy = true, fileError = null) }
      when (val result = saveThrough(project, force)) {
        is FileResult.Ok -> {
          _state.update { it.copy(busy = false, project = snapshot(project), notice = Notice.Saved) }
          // The widget reads local files, so only those go stale on save.
          if (!project.isRemote) refreshWidget()
        }
        is FileResult.Err ->
          _state.update { it.copy(busy = false, fileError = result.error) }
      }
    }
  }

  /**
   * Saves only if there is anything to save.
   *
   * Called when the app goes to the background and before closing a project,
   * so edits made on a phone are not lost to a swiped-away task or a killed
   * process. Silent by design: an unprompted dialog when the user is already
   * leaving would be worse than the save itself.
   *
   * @param onDone runs after the attempt, whether or not anything was written
   */
  fun saveIfDirty(onDone: () -> Unit = {}) {
    val project = open
    // A read-only file is not silently attempted and silently failed; the UI
    // has already said it cannot be saved.
    if (project == null || !project.isDirty || project.isReadOnly) {
      onDone()
      return
    }
    viewModelScope.launch {
      // Auto-save never forces: if the file changed elsewhere, the user is
      // asked rather than having the other version overwritten behind their
      // back — which is the whole point of the check.
      when (val result = saveThrough(project, force = false)) {
        is FileResult.Ok -> {
          _state.update { it.copy(project = snapshot(project), notice = Notice.Saved) }
          // Only local files are behind the widget; a server project is
          // refused for it, so there is nothing to refresh.
          if (!project.isRemote) refreshWidget()
        }
        is FileResult.Err ->
          // A failure must be visible: the user is about to walk away
          // believing their changes are safe.
          _state.update { it.copy(fileError = result.error) }
      }
      onDone()
    }
  }

  /** Saves first, then closes. The normal way to leave a project. */
  fun saveAndCloseProject() = saveIfDirty { closeProject() }

  /**
   * Overwrites the file even though it changed elsewhere. Only ever reached
   * through the conflict dialog, never automatically.
   */
  fun overwriteAnyway() {
    _state.update { it.copy(fileError = null) }
    save(force = true)
  }

  /** Writes the current state to a second file, leaving the original alone. */
  fun saveCopyTo(target: android.net.Uri) {
    val project = open ?: return
    viewModelScope.launch {
      _state.update { it.copy(busy = true, fileError = null) }
      when (val result = store.saveCopy(project, target)) {
        is FileResult.Ok -> _state.update { it.copy(busy = false, notice = Notice.Saved) }
        is FileResult.Err -> _state.update { it.copy(busy = false, fileError = result.error) }
      }
    }
  }

  /**
   * Drops the project without writing. The escape hatch that makes
   * auto-saving safe: a mistaken import or a fat-fingered slider can still be
   * abandoned, as long as the user says so explicitly.
   */
  fun closeProject() {
    open = null
    _state.update { it.copy(project = null, fileError = null) }
    _importState.update { it.copy(rows = emptyList(), loaded = false) }
  }

  // ------------------------------------------------------- Widget settings

  /**
   * Points the home-screen widget at the project that is open right now.
   *
   * Only the currently open project can be chosen, because that is the one
   * whose URI permission the app has already persisted — a widget cannot run
   * a file picker of its own.
   */
  fun useOpenProjectForWidget() {
    val project = open ?: return
    // A server project has an https address, and the widget reads through the
    // content resolver — it would store happily and then draw nothing, with
    // no error anywhere. Refused here rather than half-working.
    if (project.isRemote) return
    prefs.setWidgetProject(project.uri.toString(), project.displayName)
    _state.update { it.copy(widget = readWidgetSettings()) }
    refreshWidget()
  }

  fun setWidgetWindowDays(days: Int) {
    prefs.setWidgetWindowDays(days)
    _state.update { it.copy(widget = readWidgetSettings()) }
    refreshWidget()
  }

  /**
   * Redraws the home-screen widget.
   *
   * Needed because the widget is not observing anything: it reads its
   * settings and the project file when Android asks it to draw, and Android
   * only asks on its own schedule. Without this, changing the window from 90
   * to 30 days leaves the old list on the home screen until something else
   * happens to trigger a redraw.
   *
   * Failures are swallowed on purpose — there may be no widget placed at
   * all, and that is not a problem worth a message.
   */
  private fun refreshWidget() {
    viewModelScope.launch {
      // updateAll is a top-level extension function in androidx.glance.appwidget,
      // not a member of GlanceAppWidget — it needs its own import, and without
      // one the failure reads as three type-inference errors on this line.
      //
      // The explicit <Application> on getApplication is not decoration either:
      // the method is generic in its return type, and a bare call leaves
      // nothing to infer it from when the parameter is a plain Context.
      runCatching { AgendaWidget().updateAll(getApplication<Application>()) }
    }
  }

  /**
   * A store for the configured server, or null when none is configured.
   *
   * Built per call from the current settings rather than kept as a field: the
   * address, the user and the password can all change in the dialog, and a
   * cached client would go on talking to the previous server.
   */
  private fun remoteStore(): RemoteStore? {
    val settings = _dav.value
    if (settings.baseUrl.isBlank()) return null
    return RemoteStore(
      WebDavClient(http, WebDavConfig(settings.baseUrl, settings.username, settings.password))
    )
  }

  /** Loads the list of projects on the server for the picker. */
  fun listRemoteProjects() {
    val remote = remoteStore() ?: return
    viewModelScope.launch {
      _state.update { it.copy(busy = true, fileError = null) }
      when (val result = remote.list()) {
        is FileResult.Ok -> _state.update { it.copy(busy = false, remoteProjects = result.value) }
        is FileResult.Err -> _state.update { it.copy(busy = false, fileError = result.error) }
      }
    }
  }

  /** Opens a project from the server by name. */
  fun openRemote(name: String) {
    val remote = remoteStore() ?: return
    viewModelScope.launch {
      _state.update { it.copy(busy = true, fileError = null) }
      when (val result = remote.open(name)) {
        is FileResult.Ok -> {
          open = result.value
          revision = 0
          val locked = remote.isLockedElsewhere(name)
          _state.update {
            it.copy(
              busy = false,
              project = snapshot(result.value),
              notice = if (locked) Notice.OpenedWhileLocked else null
            )
          }
          // A different project means a different ledger and different
          // candidates for the import.
          _importState.update { it.copy(rows = emptyList(), loaded = false) }
        }
        is FileResult.Err ->
          _state.update { it.copy(busy = false, fileError = result.error) }
      }
    }
  }

  private fun loadDavSettings() = DavSettingsState(
    baseUrl = prefs.davBaseUrl().orEmpty(),
    username = prefs.davUsername().orEmpty(),
    password = secure.getSecret(SecureStore.KEY_DAV_PASSWORD).orEmpty(),
    supportsLocking = prefs.davSupportsLocking()
  )

  fun setDavBaseUrl(value: String) = _dav.update { it.copy(baseUrl = value, checked = null) }

  fun setDavUsername(value: String) = _dav.update { it.copy(username = value, checked = null) }

  fun setDavPassword(value: String) = _dav.update { it.copy(password = value, checked = null) }

  /**
   * Stores the server settings.
   *
   * The observed locking capability is cleared on every change: it describes
   * one particular server, and keeping it across an address change would let
   * the app call a completely different server managed on the strength of an
   * answer the old one gave.
   */
  fun saveDavSettings() {
    val current = _dav.value
    prefs.setDavServer(current.baseUrl, current.username)
    secure.putSecret(SecureStore.KEY_DAV_PASSWORD, current.password.ifEmpty { null })
    if (current.checked == null) prefs.setDavSupportsLocking(false)
  }

  /**
   * Asks the server what it can do, and says so plainly.
   *
   * This is the only place the user learns whether they are protected or
   * merely warned: a server without locking cannot stop the desktop from
   * overwriting a phone edit, it can only reject the phone afterwards.
   */
  fun checkDavConnection() {
    val current = _dav.value
    if (current.baseUrl.isBlank()) return
    _dav.update { it.copy(checking = true, checked = null) }
    viewModelScope.launch {
      val client = WebDavClient(
        http,
        WebDavConfig(current.baseUrl, current.username, current.password)
      )
      val outcome = withContext(Dispatchers.IO) { client.capabilities() }
      if (outcome is DavResult.Failed) {
        _dav.update {
          it.copy(checking = false, checked = DavCheck.Failed(outcome.error), supportsLocking = false)
        }
        return@launch
      }
      // OPTIONS answers for the server, not for the address. On the built
      // Apache it returns 200 and DAV: 1,2 for a path that does not exist, so
      // on its own it reported "connected, the server can lock" for a pasted
      // paragraph of prose — and then persisted it, because a successful check
      // is what writes the settings down. Asking about the collection itself
      // is the question the person typing an address is really asking.
      val exists = withContext(Dispatchers.IO) { client.collectionExists() }
      if (exists is DavResult.Failed) {
        _dav.update {
          it.copy(checking = false, checked = DavCheck.Failed(exists.error), supportsLocking = false)
        }
        return@launch
      }
      if (!(exists as DavResult.Ok).value) {
        // Deliberately not persisted. Storing an address that has just been
        // shown not to exist is how the wrong one survived two attempts to
        // correct it.
        _dav.update { it.copy(checking = false, checked = DavCheck.NoSuchFolder, supportsLocking = false) }
        return@launch
      }
      val capabilities = (outcome as DavResult.Ok).value
      prefs.setDavServer(current.baseUrl, current.username)
      secure.putSecret(SecureStore.KEY_DAV_PASSWORD, current.password.ifEmpty { null })
      prefs.setDavSupportsLocking(capabilities.supportsLocking)
      _dav.update {
        it.copy(
          checking = false,
          checked = DavCheck.Reachable(capabilities.supportsLocking),
          supportsLocking = capabilities.supportsLocking
        )
      }
    }
  }

  private fun readWidgetSettings() =
    WidgetSettings(prefs.widgetProjectName(), prefs.widgetWindowDays())

  /** Opens whatever project the widget is pointed at. */
  fun openWidgetProject() {
    prefs.widgetProjectUri()?.let { openUri(Uri.parse(it)) }
  }

  // ------------------------------------------------------- Edit protection

  /**
   * Changes how much this device may edit.
   *
   * The caller is expected to have shown the warning from
   * [warnBeforeWidening] first when it applies; this method does not ask,
   * because it is also the way *back down*, which must never be obstructed.
   */
  fun setEditScope(scope: EditScope) {
    // Edits already made stay saveable. Switching protection on stops the
    // next change, it does not retroactively discard the last one — silently
    // dropping work the user was allowed to do would be the very failure
    // this setting exists to prevent.
    prefs.setEditScope(scope)
    _state.update { it.copy(editScope = scope) }
    // The widget shows or hides its buttons according to this setting.
    refreshWidget()
    // canEdit is part of the project snapshot, so the screens only notice the
    // new setting once the snapshot is rebuilt.
    open?.let { project -> _state.update { it.copy(project = snapshot(project)) } }
  }

  /**
   * Whether raising the protection level to [target] should be confirmed.
   *
   * The guarantee comes from [currentSyncGuarantee] rather than a constant, because that
   * is what the app has: a file in a folder, with nothing arbitrating two
   * writers. If a versioned backend is ever added, this is the one line that
   * changes and the warning stops appearing by itself.
   */
  fun warnBeforeWidening(target: EditScope): Boolean =
    needsUnmanagedStorageWarning(_state.value.editScope, target, currentSyncGuarantee())

  /**
   * How much the storage behind the open project actually guarantees.
   *
   * [SyncGuarantee.MANAGED_SERVER] requires both that the project came from
   * the server **and** that the server was observed to support locking. A
   * server that cannot lock is, for conflicts, no better than a file in a
   * synced folder — claiming otherwise would silence a warning the user still
   * needs.
   */
  private fun currentSyncGuarantee(): SyncGuarantee =
    // Deliberately still UNMANAGED_FILE even against a locking server.
    //
    // The guarantee is a property of every client that touches the file, not
    // of the server alone. The desktop currently takes no lock at all — the
    // configured timeout never reaches HttpDocument (both call sites hard-wire
    // -1) and acquireLock() is called from nowhere — and it sends no If-Match.
    // So a phone edit can still be silently overwritten from the PC, which is
    // exactly what this warning is about.
    //
    // Flip to MANAGED_SERVER once D1 and D3 in HANDOVER-Desktop-Sperren.md are
    // built and T1/T3 pass. Until then, silencing the warning would tell the
    // user they are safe when they are not.
    if (open?.isRemote == true && prefs.davSupportsLocking() && DESKTOP_HONOURS_LOCKS) {
      SyncGuarantee.MANAGED_SERVER
    } else {
      SyncGuarantee.UNMANAGED_FILE
    }

  fun clearRecentFiles() {
    prefs.clearRecentFiles()
    _state.update { it.copy(recentFiles = emptyList()) }
  }

  fun dismissError() = _state.update { it.copy(fileError = null) }

  fun dismissNotice() = _state.update { it.copy(notice = null) }

  // ----------------------------------------------------------------- Edits

  /**
   * Runs an edit against the open document and republishes the snapshot.
   * Returns false when the document refused the change, so the caller can
   * leave the input field showing the old value.
   */
  private fun edit(block: (GanttDocument) -> Boolean): Boolean {
    val project = open ?: return false
    // The single choke point for edit protection. The screens also disable
    // their controls, but that is cosmetics — this is the guarantee, and it
    // holds for any caller added later that forgets to ask.
    if (!_state.value.editScope.allowsAppEdits) return false
    val changed = project.edit(block)
    if (changed) {
      revision++
      _state.update { it.copy(project = snapshot(project)) }
    }
    return changed
  }

  /** Steps back one edit. */
  fun undo() = stepHistory { it.undo() }

  /** Steps forward again after an undo. */
  fun redo() = stepHistory { it.redo() }

  /**
   * Shared body of [undo] and [redo].
   *
   * Gated by the edit scope like any other change: stepping through history
   * rewrites the document, and with protection on nothing may.
   */
  private fun stepHistory(step: (OpenProject) -> Boolean): Boolean {
    val project = open ?: return false
    if (!_state.value.editScope.allowsAppEdits) return false
    if (!step(project)) return false
    revision++
    _state.update { it.copy(project = snapshot(project)) }
    return true
  }

  /**
   * Folds or unfolds a task group. Not an edit: see
   * [OpenProject.editViewState] for why this must not dirty the file.
   *
   * Deliberately not gated by the edit scope. It never dirties the document,
   * so it can never cause a write — and a project you are not allowed to
   * change is exactly the one you most want to be able to fold up and read.
   */
  fun toggleExpanded(taskId: String) {
    val project = open ?: return
    val task = project.model.task(taskId) ?: return
    if (project.editViewState { it.setTaskExpanded(taskId, !task.isExpanded) }) {
      revision++
      _state.update { it.copy(project = snapshot(project)) }
    }
  }

  fun setCompletion(taskId: String, percent: Int) = edit { it.setTaskCompletion(taskId, percent) }

  fun setEffortHours(taskId: String, hours: Double?) = edit { it.setTaskEffortHours(taskId, hours) }

  fun setActualHours(taskId: String, hours: Double?) =
    edit { it.setTaskActualEffortHours(taskId, hours) }

  fun setResourceHoursPerDay(resourceId: String, hours: Double?) =
    edit { it.setResourceHoursPerDay(resourceId, hours) }

  fun setAllocationLoad(taskId: String, resourceId: String, load: Double) =
    edit { it.setAllocationLoad(taskId, resourceId, load) }

  fun setAllocationResponsible(taskId: String, resourceId: String, responsible: Boolean) =
    edit { it.setAllocationResponsible(taskId, resourceId, responsible) }

  fun assignResource(taskId: String, resourceId: String, load: Double = 100.0) =
    edit { it.assignResource(taskId, resourceId, load) }

  fun unassignResource(taskId: String, resourceId: String) =
    edit { it.unassignResource(taskId, resourceId) }

  private fun snapshot(project: OpenProject) =
    ProjectUi(
      displayName = project.displayName,
      model = project.model,
      isDirty = project.isDirty,
      isReadOnly = project.isReadOnly,
      canEdit = !project.isReadOnly && _state.value.editScope.allowsAppEdits,
      canUndo = project.canUndo,
      canRedo = project.canRedo,
      isRemote = project.isRemote,
      revision = revision
    )

  // ---------------------------------------------------------------- Import

  fun setToken(token: String) = _importState.update { it.copy(token = token, tokenVerified = null) }

  fun saveToken() {
    val token = _importState.value.token.trim()
    secure.putSecret(SecureStore.KEY_TOGGL_TOKEN, token.ifEmpty { null })
  }

  fun setRange(from: LocalDate, to: LocalDate) = _importState.update { it.copy(from = from, to = to) }

  fun setLearnKeys(enabled: Boolean) = _importState.update { it.copy(learnKeys = enabled) }

  fun verifyToken() {
    val token = _importState.value.token.trim()
    if (token.isEmpty()) {
      _importState.update { it.copy(error = TogglError.InvalidToken) }
      return
    }
    viewModelScope.launch {
      _importState.update { it.copy(busy = true, error = null) }
      val result = withContext(Dispatchers.IO) { TogglClient(http, token).verifyToken() }
      _importState.update {
        when (result) {
          is TogglResult.Success -> it.copy(busy = false, tokenVerified = result.value)
          is TogglResult.Failure -> it.copy(busy = false, error = result.error)
        }
      }
    }
  }

  /**
   * Fetches entries and pre-matches them against the project's leaf tasks.
   *
   * A suggestion is only pre-selected when the match is certain; anything
   * weaker is left blank so the user has to look at it. Pre-filling a guess
   * would get it confirmed by reflex.
   */
  fun fetchEntries() {
    val project = open ?: return
    val token = _importState.value.token.trim()
    if (token.isEmpty()) {
      _importState.update { it.copy(error = TogglError.InvalidToken) }
      return
    }
    val range = _importState.value
    viewModelScope.launch {
      _importState.update { it.copy(busy = true, error = null) }
      val result = withContext(Dispatchers.IO) {
        val client = TogglClient(http, token)
        // Project names make an otherwise ambiguous description decidable;
        // failing to get them is not a reason to abandon the import.
        val names = (client.fetchProjectNames() as? TogglResult.Success)?.value ?: emptyMap()
        client.fetchTimeEntries(range.from, range.to, names)
      }
      when (result) {
        is TogglResult.Failure ->
          _importState.update { it.copy(busy = false, error = result.error) }
        is TogglResult.Success -> {
          val candidates = project.model.flatTasks.map {
            TaskCandidate(it.id, it.name, it.togglMatchKeys, it.isLeaf && !it.isMilestone)
          }
          // The ledger comes from the project file, not from this device,
          // so it is right even when the last import happened elsewhere.
          val ledger = project.document.importedHoursByEntry()
          val rows = result.value.map { entry ->
            val outcome = Matching.suggestTasks(entry, candidates)
            ImportRow(
              entry = entry,
              outcome = outcome,
              selectedTaskId = if (outcome.isCertain) outcome.best?.taskId else null,
              skip = false,
              alreadyImportedHours = ledger[entry.id] ?: 0.0
            )
          }
          _importState.update { it.copy(busy = false, rows = rows, loaded = true) }
        }
      }
    }
  }

  fun selectTaskForEntry(entryId: Long, taskId: String?) = _importState.update { state ->
    state.copy(
      rows = state.rows.map { if (it.entry.id == entryId) it.copy(selectedTaskId = taskId) else it }
    )
  }

  fun setEntrySkipped(entryId: Long, skip: Boolean) = _importState.update { state ->
    state.copy(rows = state.rows.map { if (it.entry.id == entryId) it.copy(skip = skip) else it })
  }

  fun dismissImportError() = _importState.update { it.copy(error = null) }

  /** The plan that the preview shows and [applyImport] then writes. */
  fun currentPlan(): ImportPlan {
    val ledger = open?.document?.importedHoursByEntry() ?: emptyMap()
    return planImport(_importState.value.assignments, ledger)
  }

  /**
   * Writes the planned hours into the document.
   *
   * Hours are **added**, never set: combined with the ledger this is what
   * makes a second run of the same import a no-op instead of a doubling.
   *
   * The ledger is written into the document in the same edit as the hours,
   * so the two can never disagree. The file itself is not written here — the
   * user still has to save, which means abandoning a mistaken import by
   * closing without saving discards the record along with the hours.
   */
  fun applyImport() {
    val project = open ?: return
    // Checked here as well as in [edit]: the import writes through
    // OpenProject.edit directly, because the hours, the ledger and the
    // learned keys have to land in one single edit.
    if (!_state.value.editScope.allowsAppEdits) return
    val plan = currentPlan()
    if (plan.isEmpty) return
    val learn = _importState.value.learnKeys

    project.edit { document ->
      for ((taskId, hours) in plan.hoursPerTask) {
        document.addTaskActualEffortHours(taskId, hours)
      }
      // Record what was written, on the same task, in the same edit.
      for (line in plan.lines) {
        if (line.isSkipped) continue
        document.recordImportedHours(line.taskId, line.entryId, line.hoursToAdd)
      }
      if (learn) {
        // Remember the confirmed pairing so the next import recognises it
        // outright instead of asking again.
        for (row in _importState.value.rows) {
          val taskId = row.selectedTaskId ?: continue
          if (row.skip) continue
          val key = Matching.matchKeyFor(row.entry)
          if (key.isNotEmpty()) document.addTaskMatchKey(taskId, key)
        }
      }
      true
    }
    revision++

    val addedPerEntry = plan.lines
      .filter { !it.isSkipped }
      .groupBy { it.entryId }
      .mapValues { (_, lines) -> lines.sumOf { it.hoursToAdd } }

    _state.update {
      it.copy(project = snapshot(project), notice = Notice.HoursImported(plan.totalHours))
    }
    // Refresh the rows so the newly imported entries show as such.
    _importState.update { state ->
      state.copy(
        rows = state.rows.map {
          it.copy(alreadyImportedHours = it.alreadyImportedHours + (addedPerEntry[it.entry.id] ?: 0.0))
        }
      )
    }
  }

}
