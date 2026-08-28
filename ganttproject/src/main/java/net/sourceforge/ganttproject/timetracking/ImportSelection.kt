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

/**
 * Which time entries may be imported WITHOUT asking anybody.
 *
 * WHY THIS IS DELIBERATELY NARROW: the matching dialog (step 6) does not exist yet. Until it does,
 * the only assignment this program is entitled to make on its own is the one the person already
 * made in Toggl — writing the task number into the entry, e.g. "#332 Firmware". Everything else
 * is a guess, and a guess that silently writes hours onto the wrong task is worse than an import
 * that does less.
 *
 * [MatchReason.EXPLICIT_TASK_NUMBER] carries the weight 1000 and the note "No question needed";
 * this is that rule, applied.
 *
 * The other reasons — learned text, project link, similarity, plausible date — are exactly the
 * ones that need a person to confirm them. They stay for the dialog.
 */
data class ImportSelection(
  /** Entry and the task number it names. Safe to import. */
  val assignments: List<Pair<TogglTimeEntry, Int>>,

  /**
   * Entries naming a task number that exists nowhere in this project.
   *
   * Kept apart from [withoutNumber] on purpose: this is most likely a typo in Toggl, and telling
   * the user "3 entries name a task that does not exist" is actionable, while lumping them in with
   * "needs the dialog" hides a mistake they can fix in a second.
   */
  val unknownNumber: List<TogglTimeEntry>,

  /** Entries naming no task number. These need the matching dialog. */
  val withoutNumber: List<TogglTimeEntry>
) {
  val hasAnythingToImport: Boolean get() = assignments.isNotEmpty()
}

/**
 * [Fork-Aenderung] One entry, short enough for a list and complete enough to find it again in
 * Toggl: date, text, hours.
 *
 * WHY THIS EXISTS: reporting only "7 entries were skipped" tells the user that something is
 * missing but not WHICH — and without that they cannot act at all. Naming them turns a dead end
 * into a to-do list.
 *
 * An entry with no description shows a placeholder rather than a blank: an empty line in a list
 * of skipped items looks like a display fault.
 */
fun describeEntry(entry: TogglTimeEntry): String {
  val text = entry.description.trim().ifEmpty { "(ohne Text)" }
  return "%s  %s  (%.2f h)".format(entry.start.toLocalDate(), text, entry.hours)
}

/**
 * [Fork-Aenderung] The entries as a list, at most [limit] of them.
 *
 * The cap is not silent: when more were left out, the last line says how many. A list that stops
 * without saying so reads as if it were complete — and the count in the message above it would
 * then contradict it.
 */
fun describeEntries(entries: List<TogglTimeEntry>, limit: Int = 12): String {
  if (entries.isEmpty()) return ""
  val shown = entries.take(limit).joinToString("\n") { "• ${describeEntry(it)}" }
  val rest = entries.size - limit
  return if (rest > 0) "$shown\n… und $rest weitere" else shown
}

/**
 * Sorts entries into the three buckets above.
 *
 * @param knownTaskIds every task id in the project. An entry naming an id outside this set is
 * reported rather than assigned — the number is real but points nowhere.
 */
fun selectUnambiguousImports(
  entries: List<TogglTimeEntry>,
  knownTaskIds: Set<Int>
): ImportSelection {
  val assignments = mutableListOf<Pair<TogglTimeEntry, Int>>()
  val unknownNumber = mutableListOf<TogglTimeEntry>()
  val withoutNumber = mutableListOf<TogglTimeEntry>()

  entries.forEach { entry ->
    // Running entries have a negative duration and no end. TogglClient already drops them, but
    // this function is also reachable from a test or a later caller, and importing a negative
    // number of hours would be silently wrong rather than loudly wrong.
    if (entry.isRunning) {
      withoutNumber.add(entry)
      return@forEach
    }
    when (val number = explicitTaskNumber(entry.description, entry.tags)) {
      null -> withoutNumber.add(entry)
      in knownTaskIds -> assignments.add(entry to number)
      else -> unknownNumber.add(entry)
    }
  }

  return ImportSelection(assignments, unknownNumber, withoutNumber)
}
