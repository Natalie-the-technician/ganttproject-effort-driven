/*
Copyright 2026

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
// NEUE DATEI DIESES FORKS
package net.sourceforge.ganttproject.timetracking

import net.sourceforge.ganttproject.fork.forkText
import java.time.LocalDate

/**
 * Decides which task a recorded time entry belongs to.
 *
 * Deliberately free of GanttProject types and of any user interface: the dialog calls these
 * functions, it does not contain the logic. That is what makes the hard part testable — the
 * lesson from stage 1, where untestable wiring hid two bugs behind 300 green tests.
 *
 * NOTHING HERE DECIDES ON ITS OWN except an explicit task number. Everything else produces
 * ranked *suggestions*; picking is the user's job, and picking something entirely different
 * must always remain possible.
 */

/** The minimum a task must expose for matching. Keeps this file free of GanttProject types. */
data class MatchableTask(
  val id: Int,
  val name: String,
  /** Planned start, or null when unknown. Used for plausibility only. */
  val start: LocalDate? = null,
  /** Planned end, or null when unknown. */
  val end: LocalDate? = null,
  /** Entry texts already assigned to this task in earlier imports. */
  val learnedKeys: List<String> = emptyList()
)

/** Why a task is being suggested. Ordered from strongest to weakest evidence. */
enum class MatchReason(val weight: Int) {
  /** The entry names the task number, e.g. "#332 Firmware". No question needed. */
  EXPLICIT_TASK_NUMBER(1000),
  /** This exact entry text was assigned to this task before. */
  LEARNED(500),
  /** The Toggl project is linked to the task's group. */
  PROJECT_LINK(200),
  /** The entry text resembles the task name. */
  TEXT_SIMILARITY(100),
  /** The task was scheduled to run on the day the time was booked. */
  PLAUSIBLE_DATE(50)
}

data class MatchSuggestion(
  val task: MatchableTask,
  val reasons: List<MatchReason>,
  val score: Int
) {
  /** True when the entry names the task number: no user decision required. */
  val isCertain: Boolean get() = reasons.contains(MatchReason.EXPLICIT_TASK_NUMBER)
}

/**
 * Finds a task number written as `#123` in the entry text or in a tag.
 *
 * Only `#` followed by digits counts. A bare number is NOT accepted — "8 Stunden Doku" would
 * otherwise be read as task 8.
 */
fun explicitTaskNumber(description: String, tags: List<String> = emptyList()): Int? {
  val pattern = Regex("""#(\d{1,6})(?!\d)""")
  val candidates = (listOf(description) + tags).mapNotNull { text ->
    pattern.find(text)?.groupValues?.get(1)?.toIntOrNull()
  }
  return candidates.firstOrNull()
}

/**
 * Normalises a text for comparison: lower case, no punctuation, single spaces.
 * Kept separate so the learned-key lookup and the similarity use exactly the same form —
 * otherwise a key learned from one path would not be found by the other.
 */
fun normaliseKey(text: String): String =
  text.lowercase()
    .replace(Regex("""[^\p{L}\p{Nd}]+"""), " ")
    .trim()
    .replace(Regex("""\s+"""), " ")

/**
 * Word overlap between entry text and task name, from 0.0 to 1.0.
 *
 * Deliberately simple: a share of the entry's words that also occur in the task name. Short
 * words are dropped because "und", "der", "the" match everything and would make every task
 * look similar.
 */
fun textSimilarity(description: String, taskName: String): Double {
  val words = normaliseKey(description).split(" ").filter { it.length >= 4 }.toSet()
  if (words.isEmpty()) return 0.0
  val taskWords = normaliseKey(taskName).split(" ").filter { it.length >= 4 }.toSet()
  if (taskWords.isEmpty()) return 0.0
  return words.count { it in taskWords }.toDouble() / words.size
}

/** Similarity at or above this share counts as a suggestion. Below it, the noise dominates. */
const val SIMILARITY_THRESHOLD = 0.34

/**
 * Ranks tasks for one time entry.
 *
 * @param groupOfTogglProject maps a Toggl project id to a plan group id, when the user linked
 *   them. Null means no link is known.
 * @param groupOfTask maps a task id to its group id.
 * @return suggestions, best first. Empty when nothing matches — then the user picks freely.
 */
fun suggestTasks(
  entry: TogglTimeEntry,
  tasks: List<MatchableTask>,
  groupOfTogglProject: (Long?) -> String? = { null },
  groupOfTask: (Int) -> String? = { null },
  limit: Int = 5
): List<MatchSuggestion> {
  val explicit = explicitTaskNumber(entry.description, entry.tags)
  if (explicit != null) {
    val hit = tasks.firstOrNull { it.id == explicit }
    if (hit != null) {
      return listOf(MatchSuggestion(hit, listOf(MatchReason.EXPLICIT_TASK_NUMBER),
        MatchReason.EXPLICIT_TASK_NUMBER.weight))
    }
    // A number that names no task is a mistake worth showing, not one worth acting on:
    // fall through to the ordinary suggestions.
  }

  val key = normaliseKey(entry.description)
  val entryDate = entry.start.toLocalDate()
  val linkedGroup = groupOfTogglProject(entry.projectId)

  val scored = tasks.mapNotNull { task ->
    val reasons = mutableListOf<MatchReason>()
    if (key.isNotEmpty() && task.learnedKeys.any { normaliseKey(it) == key }) {
      reasons.add(MatchReason.LEARNED)
    }
    if (linkedGroup != null && groupOfTask(task.id) == linkedGroup) {
      reasons.add(MatchReason.PROJECT_LINK)
    }
    if (textSimilarity(entry.description, task.name) >= SIMILARITY_THRESHOLD) {
      reasons.add(MatchReason.TEXT_SIMILARITY)
    }
    if (task.start != null && task.end != null &&
      !entryDate.isBefore(task.start) && !entryDate.isAfter(task.end)) {
      reasons.add(MatchReason.PLAUSIBLE_DATE)
    }
    if (reasons.isEmpty()) null
    else MatchSuggestion(task, reasons, reasons.sumOf { it.weight })
  }
  return scored.sortedWith(compareByDescending<MatchSuggestion> { it.score }
    .thenBy { it.task.id }).take(limit)
}

// ---------------------------------------------------------------------------
// Splitting one entry across several tasks
// ---------------------------------------------------------------------------

data class SplitPart(val taskId: Int, val hours: Double)

sealed interface SplitResult {
  data class Ok(val parts: List<SplitPart>) : SplitResult

  /**
   * [Fork-Aenderung] Carries a TEXT KEY, not a finished sentence.
   *
   * This reason is shown in the matching dialog, so it has to be translatable. Holding the key
   * instead of English prose also means the dialog cannot accidentally display an untranslated
   * message: there is no English string here to display. [message] resolves it.
   */
  data class Invalid(val reasonKey: String, val args: List<Any> = emptyList()) : SplitResult {
    val message: String get() = forkText(reasonKey, *args.toTypedArray())
  }
}

/** Rounding slack when checking that the parts add up, in hours. */
const val SPLIT_TOLERANCE_HOURS = 0.005

// [Fork-Aenderung] Text keys for the reasons a split can be rejected. Kept as constants so a
// test asserts on the key rather than on the wording -- a reworded message must not break a test,
// a renamed key must.
const val SPLIT_ERROR_EMPTY = "fork.split.empty"
const val SPLIT_ERROR_NOT_POSITIVE = "fork.split.notPositive"
const val SPLIT_ERROR_DUPLICATE_TASK = "fork.split.duplicateTask"
const val SPLIT_ERROR_SUM_MISMATCH = "fork.split.sumMismatch"

/**
 * Checks a manual split of one entry.
 *
 * The sum must match the entry, otherwise hours would silently appear or vanish — the kind of
 * error nobody notices until the calibration is wrong.
 */
fun validateSplit(totalHours: Double, parts: List<SplitPart>): SplitResult {
  if (parts.isEmpty()) return SplitResult.Invalid(SPLIT_ERROR_EMPTY)
  if (parts.any { it.hours <= 0.0 || !it.hours.isFinite() }) {
    return SplitResult.Invalid(SPLIT_ERROR_NOT_POSITIVE)
  }
  if (parts.map { it.taskId }.toSet().size != parts.size) {
    return SplitResult.Invalid(SPLIT_ERROR_DUPLICATE_TASK)
  }
  val sum = parts.sumOf { it.hours }
  if (kotlin.math.abs(sum - totalHours) > SPLIT_TOLERANCE_HOURS) {
    // The two figures go in as numbers, so the translation decides how they are formatted --
    // a German text writes 1,50 where an English one writes 1.50.
    return SplitResult.Invalid(SPLIT_ERROR_SUM_MISMATCH, listOf(sum, totalHours))
  }
  return SplitResult.Ok(parts)
}

// ---------------------------------------------------------------------------
// Import protection: never count an entry twice
// ---------------------------------------------------------------------------

/** What an already imported entry should do when Toggl reports a different duration. */
enum class DuplicateHandling {
  /** Entry unchanged: skip it. */
  SKIP,
  /** Duration changed in Toggl: replace the previously imported hours, never add to them. */
  UPDATE
}

data class ImportDecision(
  val entry: TogglTimeEntry,
  val handling: DuplicateHandling?,
  /** Hours previously imported for this entry, when it is known. */
  val previousHours: Double?
) {
  val isNew: Boolean get() = handling == null
}

/**
 * Decides for each entry whether it is new, unchanged or changed.
 *
 * THE MOST LIKELY SERIOUS BUG OF THIS FEATURE is double counting: without this step every run
 * would add the same hours again, silently. [alreadyImported] maps a Toggl entry id to the
 * hours imported for it.
 */
fun planImport(
  entries: List<TogglTimeEntry>,
  alreadyImported: Map<Long, Double>
): List<ImportDecision> = entries.map { entry ->
  val previous = alreadyImported[entry.id]
  when {
    previous == null -> ImportDecision(entry, null, null)
    kotlin.math.abs(previous - entry.hours) <= SPLIT_TOLERANCE_HOURS ->
      ImportDecision(entry, DuplicateHandling.SKIP, previous)
    else -> ImportDecision(entry, DuplicateHandling.UPDATE, previous)
  }
}

/** Net hours a decision adds to a task: full for new, the difference for a changed entry. */
fun ImportDecision.hoursDelta(): Double = when (handling) {
  null -> entry.hours
  DuplicateHandling.SKIP -> 0.0
  DuplicateHandling.UPDATE -> entry.hours - (previousHours ?: 0.0)
}
