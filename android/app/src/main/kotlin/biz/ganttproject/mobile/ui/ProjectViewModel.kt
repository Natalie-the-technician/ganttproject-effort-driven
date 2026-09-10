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
import biz.ganttproject.mobile.R
import biz.ganttproject.mobile.core.AccessTokenOutcome
import biz.ganttproject.mobile.core.DeviceCodeOutcome
import biz.ganttproject.mobile.core.DeviceFlowOutcome
import biz.ganttproject.mobile.core.EditScope
import biz.ganttproject.mobile.core.GitHubConnection
import biz.ganttproject.mobile.core.GanttDocument
import biz.ganttproject.mobile.core.monthsWithRecords
import biz.ganttproject.mobile.core.monthPeriod
import biz.ganttproject.mobile.core.RunningTimer
import biz.ganttproject.mobile.core.recordsInPeriod
import biz.ganttproject.mobile.core.TimeLogExport
import biz.ganttproject.mobile.core.TimeLogCsv
import biz.ganttproject.mobile.core.ImportAssignment
import biz.ganttproject.mobile.core.ImportPlan
import biz.ganttproject.mobile.core.Matching
import biz.ganttproject.mobile.core.ProjectModel
import biz.ganttproject.mobile.core.TaskCandidate
import biz.ganttproject.mobile.core.TimeRecord
import biz.ganttproject.mobile.core.TimeSource
import biz.ganttproject.mobile.core.TogglClient
import biz.ganttproject.mobile.core.TogglError
import biz.ganttproject.mobile.core.TogglResult
import biz.ganttproject.mobile.core.toTimeRecord
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
import biz.ganttproject.mobile.data.SecureStoreGitHubTokens
import biz.ganttproject.mobile.data.installGitHubLog
import biz.ganttproject.mobile.data.FileError
import biz.ganttproject.mobile.data.FileResult
import biz.ganttproject.mobile.data.OpenProject
import biz.ganttproject.mobile.data.ProjectStore
import biz.ganttproject.mobile.data.RemoteStore
import biz.ganttproject.mobile.widget.WidgetSnapshot
import biz.ganttproject.mobile.data.RecentFile
import biz.ganttproject.mobile.data.SecureStore
import biz.ganttproject.mobile.data.WidgetTarget
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
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

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

  /** An export was written. The count says what actually went into the file. */
  data class TimeLogExported(val records: Int) : Notice

  /** A conflict was resolved by carrying this device's records across. */
  data class RecordsCarriedOver(val records: Int) : Notice

  /** A name was changed on records already written, with a note to say so. */
  data class RecordsAmended(val records: Int) : Notice

  /**
   * The project was opened while someone has it open on the desktop.
   *
   * Said at opening rather than at saving. The desktop takes a 120-minute
   * lock whenever it opens a project, so this is the ordinary state during a
   * working day at the PC — and finding out after half an hour of editing
   * would be the avoidable version of the same news.
   */
  data object OpenedWhileLocked : Notice

  /** A project was created on the sync server. */
  data object SavedToServer : Notice
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
     * no way to check.
     *
     * True since 15 August 2026. D1 and D3 are built on the desktop side and
     * T1–T5 have run against the real server; the phone half was measured the
     * same day, see [currentSyncGuarantee]. Set back to false the moment a
     * desktop build stops taking locks or stops sending If-Match — the
     * warning it silences is the only thing standing between a user and a
     * silently overwritten afternoon.
     */
    const val DESKTOP_HONOURS_LOCKS = true
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

  // ----------------------------------------------------------- GitHub sign-in

  /**
   * [fork change] The connection that keeps the hour journal in a repository.
   *
   * Built once and kept: it owns the token store, and it is the only thing
   * that writes a renewed token down. A second one built per call would work
   * just as well, which is the point — the state that matters is on disk, not
   * in this field.
   */
  private val github = GitHubConnection(http, SecureStoreGitHubTokens(secure))

  private val _github = MutableStateFlow(GitHubConnectState(connected = github.isConnected()))
  val githubState: StateFlow<GitHubConnectState> = _github.asStateFlow()

  /**
   * Read by the polling loop before every request, written by the interface.
   *
   * `@Volatile` because the two are different threads and this is the one
   * value they share. Without it the loop could keep the old value in a
   * register and go on asking after the person pressed Cancel — which is
   * precisely the behaviour the flow is built to avoid.
   */
  @Volatile
  private var keepWaitingForGitHub = false

  init {
    installGitHubLog()
  }

  /**
   * [fork change] Asks GitHub for a code and then waits for the person.
   *
   * On [Dispatchers.IO] from end to end, and it has to be: the waiting is a
   * real `Thread.sleep` between polls and it can last a quarter of an hour.
   */
  fun connectToGitHub() {
    keepWaitingForGitHub = true
    _github.update { it.copy(busy = true, message = null, userCode = null) }
    viewModelScope.launch {
      when (val start = withContext(Dispatchers.IO) { github.startConnecting() }) {
        is DeviceCodeOutcome.Failed -> _github.update {
          it.copy(busy = false, message = message(R.string.github_failed, start.reason))
        }
        is DeviceCodeOutcome.NotEnabled -> _github.update {
          it.copy(busy = false, message = start.reason)
        }
        is DeviceCodeOutcome.Ready -> {
          _github.update {
            it.copy(
              busy = true,
              userCode = start.prompt.userCode,
              verificationUri = start.prompt.verificationUri
            )
          }
          val outcome = withContext(Dispatchers.IO) {
            github.finishConnecting(start.prompt) { keepWaitingForGitHub }
          }
          _github.update {
            it.copy(
              busy = false,
              userCode = null,
              connected = github.isConnected(),
              message = when (outcome) {
                is DeviceFlowOutcome.Connected -> message(R.string.github_ok)
                is DeviceFlowOutcome.Denied -> message(R.string.github_denied)
                is DeviceFlowOutcome.Expired -> message(R.string.github_expired)
                is DeviceFlowOutcome.Stopped -> null
                is DeviceFlowOutcome.Failed -> message(R.string.github_failed, outcome.reason)
              }
            )
          }
        }
      }
    }
  }

  /** Stops the waiting. The code stays valid at GitHub until it expires. */
  fun cancelGitHubConnect() {
    keepWaitingForGitHub = false
  }

  fun disconnectGitHub() {
    github.forget()
    _github.update { it.copy(connected = false, userCode = null, message = null, busy = false) }
  }

  /**
   * [fork change] A token to put on a request, renewing it first if it is
   * stale. Nothing calls this yet — see the report of 10.09.2026.
   */
  suspend fun githubAccessToken(): AccessTokenOutcome =
    withContext(Dispatchers.IO) { github.accessToken() }

  private fun message(id: Int, vararg args: Any): String =
    getApplication<Application>().getString(id, *args)

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

  /** Re-reads the project from wherever it came from, with a fresh version stamp. */
  private suspend fun openThrough(project: OpenProject): FileResult<OpenProject> =
    project.remoteName?.let { name ->
      remoteStore()?.open(name) ?: FileResult.Err(FileError.SyncFailed("not configured"))
    } ?: store.open(project.uri)

  /** True when this device holds records that a conflict could lose. */
  fun hasTimeRecords(): Boolean = open?.document?.timeLog()?.records?.isNotEmpty() == true

  /**
   * Resolves a conflict by taking the other version and carrying this device's
   * **records** into it.
   *
   * The other version wins for everything else — the plan, the progress, the
   * assignments. That is a deliberate asymmetry rather than a half-hearted
   * merge: a plan edit can be made again, and hours that were worked cannot.
   * Of the three ways out of a conflict this is the only one that loses no
   * measurement, which is why it is offered first and says plainly what it
   * gives up.
   *
   * Records are matched by id, so the two sides do not have to be disjoint and
   * running this twice changes nothing. Only records the other side does not
   * already have are written, so the file gains exactly what it was missing.
   */
  fun keepOtherVersionWithRecords() {
    val project = open ?: return
    val mine = project.document.timeLog().records
    viewModelScope.launch {
      _state.update { it.copy(busy = true, fileError = null) }
      val reopened = openThrough(project)
      if (reopened is FileResult.Err) {
        _state.update { it.copy(busy = false, fileError = reopened.error) }
        return@launch
      }
      val fresh = (reopened as FileResult.Ok).value
      val theirs = fresh.document.timeLog().records.map { it.id }.toSet()
      val missing = mine.filterNot { it.id in theirs }

      var carried = 0
      if (missing.isNotEmpty()) {
        fresh.edit { document ->
          missing.forEach { if (document.addTimeRecord(it)) carried++ }
          carried > 0
        }
      }

      // Nothing of ours was missing: the other version already has everything,
      // so adopting it is enough and there is nothing to write back.
      if (carried == 0) {
        open = fresh
        revision++
        _state.update {
          it.copy(busy = false, project = snapshot(fresh), notice = Notice.RecordsCarriedOver(0))
        }
        return@launch
      }

      when (val result = saveThrough(fresh, force = false)) {
        is FileResult.Ok -> {
          open = fresh
          revision++
          _state.update {
            it.copy(
              busy = false,
              project = snapshot(fresh),
              notice = Notice.RecordsCarriedOver(carried)
            )
          }
          refreshWidget()
        }
        // Somebody wrote again while we were merging. The old project stays
        // open with its records intact, so the user can simply try again.
        is FileResult.Err ->
          _state.update { it.copy(busy = false, fileError = result.error) }
      }
    }
  }

  fun save(force: Boolean = false) {
    val project = open ?: return
    if (project.isReadOnly) return
    viewModelScope.launch {
      _state.update { it.copy(busy = true, fileError = null) }
      when (val result = saveThrough(project, force)) {
        is FileResult.Ok -> {
          _state.update { it.copy(busy = false, project = snapshot(project), notice = Notice.Saved) }
          if (project.isRemote) writeWidgetSnapshot(project)
          refreshWidget()
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

  /** Whether a project could be put on the server at all right now. */
  fun canSaveToServer(): Boolean = _dav.value.baseUrl.isNotBlank()

  /**
   * Writes the open project to the server under [name].
   *
   * Always a *create*, never an overwrite: [RemoteStore.saveCopy] asks with
   * `If-None-Match: *`, so a name that is taken comes back as
   * [FileError.NameTaken] and nothing on the server is touched. That matters
   * most on the path this is reached from — someone rescuing work out of a
   * conflict must not be able to land it on top of a third version.
   *
   * @param adopt continue on the new file instead of the current one. True for
   * "save to server", which is a Save As and would otherwise leave the user
   * editing a copy that quietly diverges from the one they just created. False
   * for the rescue copy out of the conflict dialog, where the point is to keep
   * the original open.
   */
  fun saveToServer(name: String, adopt: Boolean) {
    val project = open ?: return
    val remote = remoteStore() ?: return
    val target = if (name.endsWith(".gan", ignoreCase = true)) name else "$name.gan"
    viewModelScope.launch {
      _state.update { it.copy(busy = true, fileError = null) }
      when (val result = remote.saveCopy(project, target)) {
        is FileResult.Ok ->
          if (adopt) {
            // Re-opened rather than adopted in place: it costs one GET and
            // leaves the app holding an ETag the server actually issued,
            // instead of one assembled here from what we hoped we wrote.
            openRemote(target)
            _state.update { it.copy(notice = Notice.SavedToServer) }
          } else {
            _state.update { it.copy(busy = false, notice = Notice.SavedToServer) }
          }
        is FileResult.Err -> _state.update { it.copy(busy = false, fileError = result.error) }
      }
    }
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
    // A server project is drawn from a snapshot rather than through the
    // content resolver, which cannot open an https address. The snapshot is
    // written here, so the widget has something the moment it is pointed at
    // the project instead of after the next save.
    val remote = project.remoteName
    prefs.setWidgetProject(project.uri.toString(), project.displayName, remote)
    if (remote != null) writeWidgetSnapshot(project) else WidgetSnapshot(getApplication<Application>()).clear()
    _state.update { it.copy(widget = readWidgetSettings()) }
    refreshWidget()
  }

  /**
   * Keeps the widget's copy of a server project in step.
   *
   * Called wherever the app learns what the server holds — on opening and
   * after a successful save. Never on a timer: the snapshot is a picture of
   * what we last saw, and pretending otherwise would make the timestamp on
   * the widget a lie.
   */
  private fun writeWidgetSnapshot(project: OpenProject) {
    if (prefs.widgetRemoteName() != project.remoteName) return
    runCatching {
      WidgetSnapshot(getApplication<Application>()).write(
        project.document.toXmlBytes(),
        System.currentTimeMillis()
      )
    }
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
          writeWidgetSnapshot(result.value)
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
    // Branch on where the widget's project lives, exactly as save() and
    // saveIfDirty() do. The stored URI is an https address for a server
    // project, and openUri hands it to the ContentResolver, which cannot open
    // one — the app then reports that the file could not be opened, which is
    // true of the attempt and says nothing about the file.
    when (val target = prefs.widgetTarget()) {
      is WidgetTarget.Remote -> openRemote(target.name)
      is WidgetTarget.Local -> openUri(Uri.parse(target.uri))
      WidgetTarget.None -> Unit
    }
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
    // The guarantee is a property of every client that touches the file, not
    // of the server alone, which is why this stayed UNMANAGED_FILE for as long
    // as the desktop took no lock: the configured timeout never reached
    // HttpDocument, acquireLock() was called from nowhere, and no If-Match was
    // sent. A phone edit could be silently overwritten from the PC, and that
    // is what the warning was about.
    //
    // Both halves are now measured against the real server rather than argued.
    // Desktop side: D1 and D3 built, T1, T2, T3 and T5 passed. Phone side,
    // 15 August 2026, with the file held under an exclusive write lock:
    //
    //   locked   -> PUT answered 423, "gerade am PC in Bearbeitung",
    //               the edit kept on the phone, no conflict dialog
    //   unlocked -> the same unchanged edit saved, four minutes later,
    //               same ETag, same file: 204
    //
    // The second run is what makes the first one mean anything. Without it a
    // 423 shows only that something failed, not that the lock caused it.
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

  // -------------------------------------------------------------- Time log

  /**
   * The records stored on one task, oldest first.
   *
   * Read from the document rather than from the snapshot: [ProjectModel]
   * deliberately does not carry the log, so decoding it costs nothing on the
   * projects that have none. Callers key their `remember` on
   * [ProjectUi.revision], which changes on every edit.
   */
  fun timeRecordsOf(taskUid: String): List<TimeRecord> =
    open?.document?.timeLogOfTask(taskUid)?.records.orEmpty()

  /** How many stored records on this task could not be read back. */
  fun unreadableRecordsOf(taskUid: String): Int =
    open?.document?.timeLogOfTask(taskUid)?.skippedLines ?: 0

  /**
   * Books a stretch of work that has just finished.
   *
   * Placed ending *now*, which is what "I just worked on this" means. It also
   * gives the record a real timestamp instead of a date with an invented time
   * on it — and the timestamp is what decides which day, and therefore which
   * reporting period, the hours fall in.
   *
   * The id is generated here rather than in the core, which has no clock and
   * no randomness on purpose so that its results stay reproducible.
   */
  fun bookHours(taskUid: String, hours: Double, description: String): Boolean {
    val seconds = Math.round(hours * 3600.0)
    if (seconds <= 0L) return false
    val now = OffsetDateTime.now()
    return edit {
      it.addTimeRecord(
        TimeRecord(
          id = UUID.randomUUID().toString(),
          taskUid = taskUid,
          start = now.minusSeconds(seconds),
          durationSeconds = seconds,
          description = description.trim(),
          person = prefs.person(),
          source = TimeSource.MANUAL,
          createdAt = now
        )
      )
    }
  }

  fun removeTimeRecord(recordId: String) = edit { it.removeTimeRecord(recordId) }

  /** Tasks whose stored total does not match the sum of their records. */
  fun actualHoursDrift(): List<GanttDocument.ActualHoursDrift> =
    open?.document?.actualHoursDrift().orEmpty()

  /**
   * Rewrites the recorded total of every task from its records.
   *
   * An action the user takes, not something that happens on load. The total is
   * an ordinary editable column on the desktop and the widget writes it from
   * another process, so a difference is as likely to be a correction somebody
   * made deliberately as it is to be a mistake. Overwriting it unasked would
   * throw that away with no trace.
   */
  fun alignActualHoursWithLog(): Int {
    var changed = 0
    edit { document ->
      changed = document.applyActualHoursFromLog()
      changed > 0
    }
    return changed
  }

  fun taskLabels(taskUid: String): List<String> =
    open?.document?.taskLabels(taskUid).orEmpty()

  /**
   * Free labels on a task, handed to the export untouched.
   *
   * The app attaches no meaning to them: whether a label denotes a funded
   * project, a customer or a cost centre is decided by whoever reads the
   * export.
   */
  fun setTaskLabels(taskUid: String, labels: List<String>) =
    edit { it.setTaskLabels(taskUid, labels) }

  // ----------------------------------------------------------------- Timer

  /**
   * The timer that is running, or null.
   *
   * Read from preferences on every call rather than held in a field: the
   * widget process and this one both write bookings, and a value cached here
   * would keep showing a timer somebody already stopped elsewhere.
   */
  fun runningTimer(): RunningTimer? = RunningTimer.decode(prefs.runningTimer())

  /**
   * Starts a timer on a task. Refuses when one is already running.
   *
   * Refusing rather than replacing: a second start is far more likely to be a
   * mis-tap than a deliberate switch, and silently dropping the first timer
   * would throw away however long it had been running.
   */
  fun startTimer(taskUid: String, description: String = ""): Boolean {
    if (runningTimer() != null) return false
    if (!_state.value.editScope.allowsAppEdits) return false
    prefs.setRunningTimer(
      RunningTimer(
        taskUid = taskUid,
        startedAt = OffsetDateTime.now(),
        description = description.trim(),
        person = prefs.person()
      ).encode()
    )
    revision++
    open?.let { project -> _state.update { it.copy(project = snapshot(project)) } }
    return true
  }

  /**
   * Stops the timer and writes what it measured.
   *
   * The timer is only cleared once the record is actually in the document. If
   * the task is not in the project that happens to be open, the write fails
   * and the timer keeps running — losing a measured stretch because the wrong
   * file was open would be the one mistake this must not make.
   */
  fun stopTimer(): Boolean {
    val timer = runningTimer() ?: return false
    val record = timer.stop(OffsetDateTime.now(), UUID.randomUUID().toString())
      ?: return false
    if (!edit { it.addTimeRecord(record) }) return false
    prefs.setRunningTimer(null)
    return true
  }

  /**
   * Throws the running timer away without writing anything.
   *
   * Separate from stopping and named for what it does, because it destroys a
   * measurement. The screen asks before calling it.
   */
  fun discardTimer() {
    prefs.setRunningTimer(null)
    revision++
    open?.let { project -> _state.update { it.copy(project = snapshot(project)) } }
  }

  /** Who bookings from this device are recorded as, or null when nobody said. */
  fun person(): String? = prefs.person()

  fun setPerson(name: String?) {
    prefs.setPerson(name)
    revision++
    open?.let { project -> _state.update { it.copy(project = snapshot(project)) } }
  }

  /**
   * The names that appear in the log, with how many records each carries.
   *
   * Offered so a change starts from what is actually there rather than from
   * something typed: picking a name that exists is the difference between a
   * correction and a guess.
   */
  fun personsInLog(): Map<String, Int> =
    open?.document?.timeLog()?.records
      ?.mapNotNull { it.person }
      ?.groupingBy { it }
      ?.eachCount()
      .orEmpty()

  /**
   * Changes a name on records already written, and leaves the dated note that
   * records the change.
   *
   * Deliberately not a side effect of [setPerson]: setting the name for future
   * bookings must never reach into what is already recorded. This is the other
   * operation, it is reached from somewhere else, and it insists on a reason —
   * a change nobody explained cannot be defended afterwards.
   *
   * Returns how many records were touched; zero means nothing happened.
   */
  fun changePersonInLog(from: String, to: String?, reason: String): Int {
    if (reason.isBlank()) return 0
    val project = open ?: return 0
    var touched = 0
    edit { document ->
      touched = document.changePersonInLog(
        from = from,
        to = to?.trim()?.ifBlank { null },
        reason = reason.trim(),
        at = OffsetDateTime.now(),
        amendmentId = UUID.randomUUID().toString()
      )
      touched > 0
    }
    if (touched > 0) {
      _state.update { it.copy(notice = Notice.RecordsAmended(touched)) }
    }
    return touched
  }

  // ---------------------------------------------------------------- Export

  /**
   * The months that actually have records, newest first.
   *
   * Offered instead of assuming one. "The previous month" is right for the
   * ordinary rhythm and wrong the moment a month is missed — and a month that
   * cannot be selected is a month whose hours cannot be shown to anybody.
   */
  fun exportMonths(): List<YearMonth> =
    open?.let { monthsWithRecords(it.document.timeLog().records, ZoneId.systemDefault()) }
      .orEmpty()

  /** Suggested file name, so the month is visible without opening the file. */
  fun exportJsonFileName(month: YearMonth): String = "zeitjournal-$month.json"

  fun exportCsvFileName(): String = "zeitjournal.csv"

  /**
   * Writes one month's records in the shape a reporting tool reads.
   *
   * A record counts in the month its stretch **started** in, which is the rule
   * the reporting tool uses too. A night shift therefore belongs to the day it
   * began on, and a stretch booked in August for work done on 31 July stays in
   * July — which also means a month can still grow after it has been exported.
   *
   * The device's zone decides which day a record falls on. It is the only zone
   * the app knows; the file carries a full offset per record, so a reader that
   * needs another one can still work it out.
   */
  fun exportTimeLogJson(target: android.net.Uri, month: YearMonth) {
    val project = open ?: return
    val document = project.document
    val records = recordsInPeriod(
      document.timeLog().records,
      monthPeriod(month, ZoneId.systemDefault())
    )
    val text = TimeLogExport.toTogglV2Json(records, document.labelsByTaskUid())
    writeExport(target, text, Notice.TimeLogExported(records.size))
  }

  /**
   * Writes every record as a table, for a spreadsheet.
   *
   * Not cut to a month: this one is for looking at, and cutting it would only
   * hide the rest. Both raw seconds and unrounded hours are in the file, so
   * whoever opens it can round their own way.
   */
  fun exportTimeLogCsv(target: android.net.Uri) {
    val project = open ?: return
    val document = project.document
    val records = document.timeLog().records
    val text = TimeLogCsv.write(
      records,
      ZoneId.systemDefault(),
      document.labelsByTaskUid(),
      decimalComma = java.text.DecimalFormatSymbols.getInstance().decimalSeparator == ','
    )
    writeExport(target, text, Notice.TimeLogExported(records.size))
  }

  private fun writeExport(target: android.net.Uri, text: String, notice: Notice) {
    viewModelScope.launch {
      _state.update { it.copy(busy = true, fileError = null) }
      when (val result = store.writeBytes(target, text.toByteArray(Charsets.UTF_8))) {
        is FileResult.Ok -> _state.update { it.copy(busy = false, notice = notice) }
        is FileResult.Err -> _state.update { it.copy(busy = false, fileError = result.error) }
      }
    }
  }

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
      // The same hours again, this time as records: a date, a duration and a
      // description instead of one number. The total above stays -- older
      // readers and the estimate-quality report use it -- and the two are kept
      // in step by deriving, not by writing twice.
      //
      // An entry that cannot become a record is skipped rather than placed at
      // an invented time. That is not silent: the hours did reach the total,
      // so the task sheet shows the difference between the total and the
      // records and offers to explain it.
      val entriesById = _importState.value.rows.associate { it.entry.id to it.entry }
      for ((entryId, lines) in plan.lines.filter { !it.isSkipped }.groupBy { it.entryId }) {
        val entry = entriesById[entryId] ?: continue
        val isSplit = lines.size > 1
        for (line in lines) {
          val uid = document.taskUidOfId(line.taskId) ?: continue
          val record = entry.toTimeRecord(
            taskUid = uid,
            seconds = Math.round(line.hoursToAdd * 3600.0),
            // Only a split needs its parts told apart. An entry booked whole
            // keeps the plain id, so a later import that moves it to another
            // task moves the record instead of leaving a copy behind.
            part = if (isSplit) uid else null
          ) ?: continue
          document.addTimeRecord(record)
        }
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
