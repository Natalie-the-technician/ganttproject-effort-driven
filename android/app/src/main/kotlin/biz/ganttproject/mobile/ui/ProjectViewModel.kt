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
import biz.ganttproject.mobile.core.MatchOutcome
import biz.ganttproject.mobile.core.planImport
import biz.ganttproject.mobile.data.AppPreferences
import biz.ganttproject.mobile.data.FileError
import biz.ganttproject.mobile.data.FileResult
import biz.ganttproject.mobile.data.OpenProject
import biz.ganttproject.mobile.data.ProjectStore
import biz.ganttproject.mobile.data.RecentFile
import biz.ganttproject.mobile.data.SecureStore
import biz.ganttproject.mobile.net.AndroidHttpBackend
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
  /**
   * Bumped on every edit. [ProjectModel] is a value snapshot, but the
   * enclosing [OpenProject] is mutable, so an explicit revision keeps
   * recomposition honest rather than relying on reference identity.
   */
  val revision: Int
)

data class AppState(
  val busy: Boolean = false,
  val project: ProjectUi? = null,
  val recentFiles: List<RecentFile> = emptyList(),
  val fileError: FileError? = null,
  /** One-shot confirmation, e.g. after a successful save. */
  val notice: Notice? = null
)

sealed interface Notice {
  data object Saved : Notice
  data class HoursImported(val hours: Double) : Notice
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

  private val store = ProjectStore(application)
  private val prefs = AppPreferences(application)
  private val secure = SecureStore(application)
  private val http = AndroidHttpBackend()

  private var open: OpenProject? = null
  private var revision = 0

  private val _state = MutableStateFlow(AppState(recentFiles = prefs.recentFiles()))
  val state: StateFlow<AppState> = _state.asStateFlow()

  private val _importState = MutableStateFlow(
    ImportState(token = secure.getSecret(SecureStore.KEY_TOGGL_TOKEN).orEmpty())
  )
  val importState: StateFlow<ImportState> = _importState.asStateFlow()

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

  fun save() {
    val project = open ?: return
    viewModelScope.launch {
      _state.update { it.copy(busy = true, fileError = null) }
      when (val result = store.save(project)) {
        is FileResult.Ok ->
          _state.update { it.copy(busy = false, project = snapshot(project), notice = Notice.Saved) }
        is FileResult.Err ->
          _state.update { it.copy(busy = false, fileError = result.error) }
      }
    }
  }

  fun closeProject() {
    open = null
    _state.update { it.copy(project = null, fileError = null) }
    _importState.update { it.copy(rows = emptyList(), loaded = false) }
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
    val changed = project.edit(block)
    if (changed) {
      revision++
      _state.update { it.copy(project = snapshot(project)) }
    }
    return changed
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
    ProjectUi(project.displayName, project.model, project.isDirty, revision)

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
