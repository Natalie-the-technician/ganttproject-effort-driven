/*
Copyright 2026

NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.

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

import biz.ganttproject.customproperty.CustomPropertyClass
import biz.ganttproject.customproperty.CustomPropertyDefinition
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.fork.forkText
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.algorithm.findEffortDefinition

/**
 * Records which Toggl entry already contributed how many hours, so that a second import does not
 * count the same time twice — the most likely serious bug of this feature, see [planImport].
 *
 * This file deals ONLY with the encoding and the arithmetic. WHERE the text is stored is a
 * separate question and deliberately not decided here: GanttProject has custom properties for
 * tasks and for resources, but none for the project itself, so the ledger has to be assembled
 * from several stored strings. [mergeLedgers] is that assembly step.
 *
 * FORMAT: `id:hours` pairs separated by `;`, for example `1234:1.5;5678:2`.
 *
 * Three properties matter, and each is covered by a test:
 *
 * - **Decimal point, never a comma.** The text goes into a file that travels between machines;
 *   a locale-dependent separator would change meaning when the file is opened elsewhere.
 * - **Robust against garbage.** The value is stored in a custom property, which stock
 *   GanttProject shows as an ordinary, editable column. Someone will eventually type into it.
 *   Decoding therefore skips what it cannot read instead of throwing: losing the guard for one
 *   entry is bad, refusing to open the import dialog at all is worse.
 * - **Deterministic order.** Entries are written sorted by id, so saving an unchanged project
 *   does not produce a different file every time — that would show up as noise in the vault.
 */

/** Separates the pairs. */
private const val PAIR_SEPARATOR = ";"

/** Separates id and hours inside a pair. */
private const val FIELD_SEPARATOR = ":"

/**
 * Writes the ledger as one line. Sorted by id so that equal content always yields equal text.
 * Entries with zero or negative hours are dropped: they carry no information and would only grow
 * the line.
 */
fun encodeImportLedger(ledger: Map<Long, Double>): String =
  ledger.entries
    .filter { it.value > 0.0 }
    .sortedBy { it.key }
    .joinToString(PAIR_SEPARATOR) { "${it.key}$FIELD_SEPARATOR${formatHours(it.value)}" }

/**
 * Reads a ledger back. Unreadable pairs are skipped, see the note about hand-edited columns.
 * A duplicated id inside one text is summed, which is what a split entry looks like.
 */
fun decodeImportLedger(text: String?): Map<Long, Double> {
  val result = mutableMapOf<Long, Double>()
  text?.split(PAIR_SEPARATOR)?.forEach { pair ->
    val fields = pair.split(FIELD_SEPARATOR)
    if (fields.size == 2) {
      val id = fields[0].trim().toLongOrNull()
      val hours = fields[1].trim().toDoubleOrNull()
      if (id != null && hours != null && hours > 0.0 && hours.isFinite()) {
        result[id] = (result[id] ?: 0.0) + hours
      }
    }
  }
  return result
}

/**
 * Builds the project-wide view from the texts stored on the individual tasks.
 *
 * Summing is the point of this function, not an implementation detail: one Toggl entry can be
 * split across several tasks (see `validateSplit`). The duplicate guard asks "how many hours of
 * this entry are already recorded ANYWHERE", so the parts have to be added up. Taking the last
 * or the largest value would let a second import add the remaining parts again.
 */
fun mergeLedgers(texts: Iterable<String?>): Map<Long, Double> {
  val result = mutableMapOf<Long, Double>()
  texts.forEach { text ->
    decodeImportLedger(text).forEach { (id, hours) ->
      result[id] = (result[id] ?: 0.0) + hours
    }
  }
  return result
}

/**
 * The ledger after the given decisions have been carried out.
 *
 * - a new entry is recorded with its full hours,
 * - an unchanged one ([DuplicateHandling.SKIP]) keeps what it had,
 * - a changed one ([DuplicateHandling.UPDATE]) is recorded with its NEW total, not the difference
 *   — the ledger stores what Toggl reports, while [hoursDelta] says what to add to the task.
 */
fun ledgerAfterImport(
  previous: Map<Long, Double>,
  decisions: List<ImportDecision>
): Map<Long, Double> {
  val result = previous.toMutableMap()
  decisions.forEach { decision ->
    when (decision.handling) {
      null, DuplicateHandling.UPDATE -> result[decision.entry.id] = decision.entry.hours
      DuplicateHandling.SKIP -> Unit
    }
  }
  return result
}

/** Hours without a trailing `.0`, and always with a decimal point. */
private fun formatHours(hours: Double): String =
  if (hours == hours.toLong().toDouble()) hours.toLong().toString() else hours.toString()

/**
 * WHERE the ledger is stored, decided on 12.08.2026: one custom property per TASK,
 * assembled into the project-wide view when an import runs.
 *
 * The alternative — keeping it in the application settings — was rejected on purpose: the project
 * file lives in a shared vault, so a guard that sits on one machine would let a colleague import
 * the same hours a second time. Storing it in the file means the guard travels with the data.
 *
 * GanttProject offers no project-wide custom property; `IGanttProject` has exactly two managers,
 * for tasks and for resources. Hence "per task, merged" rather than "one entry for the project".
 *
 * The price is that the column is visible in stock GanttProject and can be edited there by hand.
 * That is why [decodeImportLedger] skips what it cannot read instead of failing.
 */
object TimeTrackingProperties {
  /** Which Toggl entries contributed hours to this task. Custom property on tasks. */
  const val TASK_IMPORTED_ENTRIES = "toggl_imported"

  fun findOrCreateImportedEntries(manager: CustomPropertyManager): CustomPropertyDefinition =
    manager.findEffortDefinition(TASK_IMPORTED_ENTRIES)
      // [Fork-Aenderung] Anzeigename aus dem Textbuendel: er erscheint als Spaltenkopf. Die id
      // bleibt unuebersetzt, weil die Suche darueber laeuft.
      ?: manager.createDefinition(TASK_IMPORTED_ENTRIES, CustomPropertyClass.TEXT.iD,
                                  forkText("fork.column.togglImported"), null)
}

/**
 * What this task has recorded. Empty when the task carries nothing, which is the normal case.
 *
 * Looks the property up by id OR name, because a column that the user created in the column
 * manager carries the typed text as its name only — the trap that made `hours_per_day` silently
 * fall back to the default in session 3.
 */
fun Task.importedEntries(manager: CustomPropertyManager): Map<Long, Double> {
  val def = manager.findEffortDefinition(TimeTrackingProperties.TASK_IMPORTED_ENTRIES)
    ?: return emptyMap()
  return decodeImportLedger(this.customValues.getValue(def)?.toString())
}

/**
 * The project-wide view: what has been imported ANYWHERE. This is what [planImport] needs.
 *
 * Reading every task is the point. Asking only the task an entry is about to land on would miss
 * the case that made per-task storage look risky in the first place: an entry that was assigned
 * to a different task last time.
 */
fun projectImportLedger(tasks: Iterable<Task>, manager: CustomPropertyManager): Map<Long, Double> {
  val def = manager.findEffortDefinition(TimeTrackingProperties.TASK_IMPORTED_ENTRIES)
    ?: return emptyMap()
  return mergeLedgers(tasks.map { task -> task.customValues.getValue(def)?.toString() })
}
