/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.

This file is part of GanttProject, an opensource project management tool.

GanttProject is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

GanttProject is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with GanttProject.  If not, see <http://www.gnu.org/licenses/>.
*/
package net.sourceforge.ganttproject.timetracking

import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.storage.ProjectDatabase
import net.sourceforge.ganttproject.GPLogger
import net.sourceforge.ganttproject.action.GPAction
import net.sourceforge.ganttproject.fork.forkText
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.undo.GPUndoManager
import java.awt.event.ActionEvent
import java.time.LocalDate
import javax.swing.SwingUtilities

/**
 * Asks before anything is written.
 *
 * ASYNCHRONOUS, like the collision dialog and for the same reason: GanttProject's option dialog
 * does not block, so the answer arrives through [answer] rather than as a return value. Never
 * calling it means "no" — nothing is written.
 *
 * [answer] is a `Consumer` rather than a Kotlin lambda so that `GanttProject.java` can implement
 * this interface without wrestling with `Function1`.
 */
fun interface ImportConfirmation {
  fun ask(message: String, answer: java.util.function.Consumer<Boolean>)
}

/**
 * Menu entry "Import Toggl times".
 *
 * TWO WAYS AN ENTRY REACHES A TASK: it names the task itself (`#332 Firmware`) and is taken
 * without asking — see [selectUnambiguousImports] for why nothing else is — or the person assigns
 * it in the matching dialog (step 6, [TaskChoiceAsker]). Anything left after that is reported and
 * left alone, listed by date and text so it can be found again in Toggl.
 *
 * THREADS: the request may sit at the 30 second response timeout, so it runs on its own thread.
 * Everything that touches the project — reading tasks, planning, writing — runs on the interface
 * thread, because the model is not thread-safe and is on screen at the same time.
 *
 * The write goes through [applyImportAsSingleEdit], so the whole import is ONE undo step, and no
 * step at all when there is nothing to write.
 */
class TogglImportAction @JvmOverloads constructor(
  private val taskManager: TaskManager,
  private val resourceManager: HumanResourceManager,
  private val taskProperties: CustomPropertyManager,
  private val projectDatabase: ProjectDatabase,
  private val undoManager: GPUndoManager,
  private val showMessage: ConnectionCheckMessageSink,
  /** Confirmation before anything is written. Answering with false must change nothing. */
  private val confirm: ImportConfirmation,
  /** Asks how far back to look. Not answering means the import does not start. */
  private val askForPeriod: ImportPeriodAsker,
  /** Asks which task the entries without a task number belong to. Step 6. */
  private val askForTasks: TaskChoiceAsker,
  private val runInBackground: (() -> Unit) -> Unit = { work ->
    Thread(work, "Toggl-Import").also { it.isDaemon = true }.start()
  },
  private val runOnUiThread: (() -> Unit) -> Unit = { work -> SwingUtilities.invokeLater(work) },
  private val backendFactory: () -> HttpBackend = { HttpClientBackend() },
  private val today: () -> LocalDate = { LocalDate.now() }
) : GPAction("toggl.import") {

  override fun getLocalizedName(): String = forkText("fork.toggl.import")

  override fun actionPerformed(e: ActionEvent?) {
    // Read on the thread that owns these. The background thread gets only the token.
    val resources = resourceManager.resources.toList()
    val storedTokens = TogglTokenOptions.tokens.value
    val person = firstResourceWithToken(resources, storedTokens)
    val token = person?.let { tokenFor(it, storedTokens) }
    if (token.isNullOrBlank()) {
      showMessage.show(true, forkText("fork.toggl.check.noToken"))
      return
    }

    askForPeriod.ask(usableImportDays(TogglTokenOptions.importDays.value)) { days ->
      // Remember the choice: the next import usually wants the same period.
      TogglTokenOptions.importDays.value = days
      fetchAndPlan(token, person.name.orEmpty(), days.toLong())
    }
  }

  private fun fetchAndPlan(token: String, personName: String, days: Long) {
    showMessage.show(false, forkText("fork.toggl.import.running", days))

    runInBackground {
      val entries = try {
        TogglClient(backendFactory()).timeEntries(
          token, today().minusDays(days).toString(), today().plusDays(1).toString())
      } catch (ex: TogglException) {
        GPLogger.log("Toggl import failed: ${ex.failure} ${ex.message}")
        runOnUiThread {
          showMessage.show(true, connectionCheckMessage(
            ConnectionCheckResult.Failed(personName, ex.failure, ex.message.orEmpty())))
        }
        return@runInBackground
      } catch (ex: Exception) {
        // An import must never take the application down with it.
        GPLogger.log(ex)
        runOnUiThread {
          showMessage.show(true, connectionCheckMessage(ConnectionCheckResult.Failed(
            personName, TogglFailure.UNAVAILABLE, ex.message.orEmpty())))
        }
        return@runInBackground
      }

      runOnUiThread { planAndAsk(entries) }
    }
  }

  /** Everything from here on runs on the interface thread and touches the project. */
  private fun planAndAsk(entries: List<TogglTimeEntry>) {
    val tasks = taskManager.tasks.toList()
    val taskById = tasks.associateBy { it.taskID }
    val selection = selectUnambiguousImports(entries, taskById.keys)

    if (selection.withoutNumber.isEmpty()) {
      planAndAsk(selection, tasks, taskById, emptyList())
      return
    }
    // Entries nobody can assign automatically go to the person -- step 6. Answering with nothing
    // is a valid outcome: the unambiguous entries are imported either way.
    askForTasks.ask(selection.withoutNumber, tasks.map(::asMatchableTask)) { manual ->
      planAndAsk(selection, tasks, taskById, manual)
    }
  }

  private fun planAndAsk(
    selection: ImportSelection,
    tasks: List<Task>,
    taskById: Map<Int, Task>,
    manual: List<ManualAssignment>
  ) {
    val assignments: List<Pair<TogglTimeEntry, Task>> =
      (selection.assignments.mapNotNull { (entry, id) -> taskById[id]?.let { entry to it } } +
        manual.mapNotNull { taskById[it.taskId]?.let { task -> it.entry to task } })

    // What was assigned by hand no longer counts as skipped -- otherwise the message would keep
    // counting it as "without a task number" and would contradict its own booking.
    val assignedByHand = manual.map { it.entry.id }.toSet()
    val reported = selection.copy(
      withoutNumber = selection.withoutNumber.filterNot { it.id in assignedByHand })

    val changes = planTaskImport(
      assignments, projectImportLedger(tasks, taskProperties), taskProperties)
    val toWrite = changes.filterNot { it.isEmpty }

    if (toWrite.isEmpty()) {
      // Nothing to write is a normal outcome, not a failure: it means everything was imported
      // before. Saying so beats a silent no-op that looks like the menu item is broken.
      showMessage.show(false, nothingToWriteMessage(reported))
      return
    }

    confirm.ask(previewMessage(toWrite, reported)) { yes ->
      if (yes) {
        val result = applyImportAsSingleEdit(
          toWrite, taskProperties, projectDatabase, undoManager, forkText("fork.toggl.import.undo"))
        showMessage.show(result.failed.isNotEmpty(), resultMessage(result, reported))
      }
    }
  }
}

/**
 * What the confirmation says. Names the totals AND what is being left out — an import that
 * silently ignores half the entries would look complete.
 */
internal fun previewMessage(changes: List<TaskImportChange>, selection: ImportSelection): String {
  val hours = changes.sumOf { it.addedHours }
  val overruns = changes.count { it.exceedsPlanned }
  return buildString {
    append(forkText("fork.toggl.import.preview", changes.size, hours))
    if (overruns > 0) append("\n\n").append(forkText("fork.toggl.import.overrun", overruns))
    append(skippedNote(selection))
  }
}

internal fun nothingToWriteMessage(selection: ImportSelection): String =
  forkText("fork.toggl.import.nothingNew") + skippedNote(selection)

internal fun resultMessage(result: ImportWriteResult, selection: ImportSelection): String =
  buildString {
    append(forkText("fork.toggl.import.done", result.written.size))
    if (result.failed.isNotEmpty()) {
      append("\n\n").append(forkText("fork.toggl.import.failed", result.failed.size))
    }
    append(skippedNote(selection))
  }

/**
 * What was left out, and why. Separated by cause: a wrong task number is a typo the user can fix,
 * a missing one needs the matching dialog. Silence about either would read as "everything was
 * imported".
 */
private fun skippedNote(selection: ImportSelection): String = buildString {
  // The entries are LISTED, not merely counted. "7 entries skipped" says that something is
  // missing, but not WHICH ones -- and without that nobody can do anything. With a date and a
  // text they can be found again in Toggl.
  if (selection.unknownNumber.isNotEmpty()) {
    append("\n\n").append(forkText("fork.toggl.import.unknownNumber", selection.unknownNumber.size))
    append("\n").append(describeEntries(selection.unknownNumber))
  }
  if (selection.withoutNumber.isNotEmpty()) {
    append("\n\n").append(forkText("fork.toggl.import.withoutNumber", selection.withoutNumber.size))
    append("\n").append(describeEntries(selection.withoutNumber))
  }
}

/**
 * [Fork-Aenderung] A project task as the matching sees it.
 *
 * Planned dates are passed on because they feed [MatchReason.PLAUSIBLE_DATE] — an entry booked on
 * a day the task was running is a better suggestion than one booked months away. `learnedKeys`
 * stays empty: the ledger records entry IDS and hours, not their texts, so there is nothing to
 * learn from yet. Claiming otherwise would produce suggestions with a reason that is not real.
 */
internal fun asMatchableTask(task: Task): MatchableTask = MatchableTask(
  id = task.taskID,
  name = task.name.orEmpty(),
  start = runCatching { task.start?.toLocalDate() }.getOrNull(),
  end = runCatching { task.end?.toLocalDate() }.getOrNull())
