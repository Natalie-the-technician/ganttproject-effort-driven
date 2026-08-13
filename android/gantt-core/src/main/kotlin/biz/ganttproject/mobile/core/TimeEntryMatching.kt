/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import kotlin.math.abs

/*
 * Matching time entries to tasks.
 *
 * Everything here is a pure function with no UI and no network. These rules
 * decide which task somebody's hours are booked against, so they are exactly
 * what has to be verifiable without launching a phone. As a consequence the
 * import dialogs contain no matching logic at all: if something is missing,
 * it belongs in this file, with a test.
 */

/** Why a task was suggested. Shown in the UI so a suggestion can be judged. */
enum class MatchReason {
  /** The description names the task number explicitly, e.g. "#12". */
  TASK_ID_REFERENCE,

  /** A previously confirmed match key for this task fits. */
  LEARNED_KEY,

  /** Description and task name are equal after normalisation. */
  EXACT_NAME,

  /** The task name occurs in full inside the description, or vice versa. */
  NAME_CONTAINED,

  /** Only individual words overlap. */
  WORD_OVERLAP
}

data class TaskCandidate(
  val id: String,
  val name: String,
  val matchKeys: List<String> = emptyList(),
  val isLeaf: Boolean = true
)

data class TaskSuggestion(
  val taskId: String,
  /** 0.0 .. 1.0; higher is a stronger claim. */
  val score: Double,
  val reason: MatchReason
)

data class MatchOutcome(
  val entryId: Long,
  /** Best first. May be empty when nothing plausible was found. */
  val suggestions: List<TaskSuggestion>,
  /**
   * "No need to ask." True only when the match is actually evidenced — not
   * merely when some suggestion happens to sort first. Everything else goes
   * to the user for confirmation.
   */
  val isCertain: Boolean
) {
  val best: TaskSuggestion? get() = suggestions.firstOrNull()
}

object Matching {
  /** Minimum score for a match to count as certain. */
  const val CERTAIN_THRESHOLD = 0.95

  /** How far ahead of the runner-up the best match must be to count as certain. */
  const val CERTAIN_MARGIN = 0.25

  private const val MAX_SUGGESTIONS = 8

  /**
   * Detects an explicit task reference: "#12", "Nr. 12", "[12]".
   *
   * **A bare number deliberately does not count.** Descriptions such as
   * "Meeting 15" or "Invoice 2024" would otherwise be booked onto task 15 or
   * task 2024 — a silent mis-booking machine. Loosening this regex to accept
   * plain digits is the single easiest way to make this feature dangerous.
   */
  private val TASK_REFERENCE = Regex("""(?:#|\bnr\.?\s*|\[)(\d{1,9})]?""", RegexOption.IGNORE_CASE)

  /** Words that say nothing about which task is meant (German and English). */
  private val STOP_WORDS = setOf(
    "und", "oder", "der", "die", "das", "den", "dem", "des", "ein", "eine", "einer",
    "fuer", "für", "mit", "von", "im", "in", "am", "an", "auf", "zu", "zur", "zum",
    "the", "and", "for", "with", "of", "to", "a", "an"
  )

  /**
   * Lower-cases, transliterates umlauts and reduces everything else to
   * letters, digits and single spaces.
   *
   * Umlauts become the two-letter German forms (ö -> oe), not the bare
   * vowel: people who cannot type an umlaut write "Moebel", and that
   * spelling has to reach the same key as "Möbel". Folding to "mobel"
   * instead would separate the two.
   */
  fun normalize(text: String): String =
    text.lowercase()
      .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
      .replace("ß", "ss")
      .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
      .trim()
      .replace(Regex("\\s+"), " ")

  private fun words(text: String): Set<String> =
    normalize(text).split(' ').filter { it.length > 2 && it !in STOP_WORDS }.toSet()

  /**
   * The key learned on the task when a match is confirmed.
   *
   * Deliberately the normalised description, not the entry id: the id is
   * different for every new entry, so learning it would teach nothing.
   */
  fun matchKeyFor(entry: TogglTimeEntry): String = normalize(entry.description)

  /**
   * Scored suggestions for one time entry, best first.
   *
   * Summary tasks are never suggested. Hours belong on the leaf; booked on a
   * parent they would be counted twice as soon as anything sums the tree.
   */
  fun suggestTasks(entry: TogglTimeEntry, candidates: List<TaskCandidate>): MatchOutcome {
    val description = entry.description
    val normalizedDescription = normalize(description)
    val descriptionWords = words(description)
    val leaves = candidates.filter { it.isLeaf }

    val scored = mutableListOf<TaskSuggestion>()

    // 1. Explicit task number — the only genuinely hard evidence.
    val referencedIds = TASK_REFERENCE.findAll(description).map { it.groupValues[1] }.toSet()
    for (candidate in leaves) {
      if (candidate.id in referencedIds) {
        scored.add(TaskSuggestion(candidate.id, 1.0, MatchReason.TASK_ID_REFERENCE))
      }
    }

    for (candidate in leaves) {
      if (scored.any { it.taskId == candidate.id }) continue
      val normalizedName = normalize(candidate.name)

      // 2. A key the user confirmed on an earlier import.
      val learned = candidate.matchKeys.any { normalize(it) == normalizedDescription }
      if (learned && normalizedDescription.isNotEmpty()) {
        scored.add(TaskSuggestion(candidate.id, 0.98, MatchReason.LEARNED_KEY))
        continue
      }

      if (normalizedName.isEmpty() || normalizedDescription.isEmpty()) continue

      // 3. Same name.
      if (normalizedName == normalizedDescription) {
        scored.add(TaskSuggestion(candidate.id, 0.96, MatchReason.EXACT_NAME))
        continue
      }

      // 4. Name contained in the description, or the reverse. Weighted by
      //    name length: a short name like "Build" is inside half the corpus
      //    and is weak evidence, a 30-character name is strong.
      if (normalizedDescription.contains(normalizedName) ||
        normalizedName.contains(normalizedDescription)
      ) {
        val lengthFactor = normalizedName.length.coerceAtMost(30) / 30.0
        scored.add(
          TaskSuggestion(candidate.id, 0.55 + 0.3 * lengthFactor, MatchReason.NAME_CONTAINED)
        )
        continue
      }

      // 5. Word overlap — always below the certainty threshold, on purpose.
      val nameWords = words(candidate.name)
      if (nameWords.isEmpty() || descriptionWords.isEmpty()) continue
      val shared = nameWords.intersect(descriptionWords)
      if (shared.isEmpty()) continue
      val overlap = shared.size.toDouble() / minOf(nameWords.size, descriptionWords.size)
      val projectBoost =
        if (entry.projectName != null &&
          normalize(entry.projectName).isNotEmpty() &&
          normalizedName.contains(normalize(entry.projectName))
        ) 0.05 else 0.0
      scored.add(
        TaskSuggestion(
          candidate.id,
          (0.20 + 0.35 * overlap + projectBoost).coerceAtMost(0.70),
          MatchReason.WORD_OVERLAP
        )
      )
    }

    // Ties broken by task id so the order is stable across runs; an unstable
    // order would make "the top suggestion" mean something different each
    // time the same import is previewed.
    val sorted = scored
      .sortedWith(compareByDescending<TaskSuggestion> { it.score }.thenBy { it.taskId })
      .take(MAX_SUGGESTIONS)

    val best = sorted.firstOrNull()
    val runnerUp = sorted.getOrNull(1)
    val certain = best != null &&
      best.score >= CERTAIN_THRESHOLD &&
      (runnerUp == null || best.score - runnerUp.score >= CERTAIN_MARGIN)

    return MatchOutcome(entry.id, sorted, certain)
  }
}

// ---------------------------------------------------------------- Splitting

/** One share of a time entry booked onto a single task. */
data class SplitPart(val taskId: String, val hours: Double)

/**
 * Outcome of validating a split. Failure cases are types, not messages, so
 * the UI can localise them.
 */
sealed interface SplitValidation {
  data object Valid : SplitValidation

  /** No task selected at all. */
  data object NoParts : SplitValidation

  /** A share is zero or negative. */
  data class NonPositivePart(val taskId: String) : SplitValidation

  /** The same task appears twice, which would hide a wrong total. */
  data class DuplicateTask(val taskId: String) : SplitValidation

  /** The shares do not add up to the entry's duration. */
  data class SumMismatch(val expectedHours: Double, val actualHours: Double) : SplitValidation
}

/** Tolerance when comparing hour sums: 0.01 h is 36 seconds. */
const val HOURS_EPSILON = 0.01

/**
 * Validates splitting one time entry across several tasks.
 *
 * The sum check is the entire point. Without it an entry can be booked onto
 * two tasks in full, doubling the recorded hours with nothing to show for it
 * in the UI.
 */
fun validateSplit(entryHours: Double, parts: List<SplitPart>): SplitValidation {
  if (parts.isEmpty()) return SplitValidation.NoParts
  parts.firstOrNull { it.hours <= 0.0 }?.let {
    return SplitValidation.NonPositivePart(it.taskId)
  }
  parts.groupBy { it.taskId }.entries.firstOrNull { it.value.size > 1 }?.let {
    return SplitValidation.DuplicateTask(it.key)
  }
  val sum = parts.sumOf { it.hours }
  if (abs(sum - entryHours) > HOURS_EPSILON) {
    return SplitValidation.SumMismatch(entryHours, sum)
  }
  return SplitValidation.Valid
}

// ------------------------------------------------------------------ Import

/** A confirmed decision: book [hours] of entry [entryId] onto [taskId]. */
data class ImportAssignment(
  val entryId: Long,
  val taskId: String,
  val hours: Double
)

data class ImportLine(
  val entryId: Long,
  val taskId: String,
  /** What will actually be added. May be 0 when the entry was imported before. */
  val hoursToAdd: Double,
  /** What earlier runs already booked for this entry. */
  val hoursAlreadyImported: Double
) {
  val isSkipped: Boolean get() = hoursToAdd <= HOURS_EPSILON
}

data class ImportPlan(
  val lines: List<ImportLine>,
  /** Total per task — exactly what gets written. */
  val hoursPerTask: Map<String, Double>,
  val totalHours: Double,
  val skippedEntryIds: Set<Long>
) {
  val isEmpty: Boolean get() = totalHours <= HOURS_EPSILON
}

/**
 * Turns confirmed assignments into the plan of what will be written.
 *
 * [alreadyImported] maps **time-entry id -> hours already booked**. The key is
 * only the entry id, never the (entry, task) pair: otherwise the protection
 * stops working the moment the same entry is assigned to a different task on
 * a second run, and those hours land in the file twice.
 *
 * Nothing is ever set absolutely — only the difference is added. Running the
 * same import twice therefore leaves the total unchanged, which is the
 * property the double-import test pins down.
 */
fun planImport(
  assignments: List<ImportAssignment>,
  alreadyImported: Map<Long, Double>
): ImportPlan {
  val lines = mutableListOf<ImportLine>()
  val skipped = mutableSetOf<Long>()

  for ((entryId, group) in assignments.groupBy { it.entryId }) {
    val requested = group.sumOf { it.hours }
    val already = alreadyImported[entryId] ?: 0.0
    val remaining = (requested - already).coerceAtLeast(0.0)

    if (remaining <= HOURS_EPSILON || requested <= 0.0) {
      skipped.add(entryId)
      group.forEach { lines.add(ImportLine(entryId, it.taskId, 0.0, already)) }
      continue
    }

    // Scale shares down proportionally when part of the entry was booked
    // before (its duration grew in the tracker since the last import). The
    // last share absorbs the rounding residual so the parts add up exactly
    // to `remaining` instead of leaving fractions of a minute behind.
    val factor = remaining / requested
    var distributed = 0.0
    group.forEachIndexed { index, assignment ->
      val share = if (index == group.lastIndex) {
        remaining - distributed
      } else {
        assignment.hours * factor
      }
      distributed += share
      lines.add(ImportLine(entryId, assignment.taskId, share.coerceAtLeast(0.0), already))
    }
  }

  val perTask = lines
    .filter { !it.isSkipped }
    .groupBy { it.taskId }
    .mapValues { (_, group) -> group.sumOf { it.hoursToAdd } }

  return ImportPlan(
    lines = lines,
    hoursPerTask = perTask,
    totalHours = perTask.values.sum(),
    skippedEntryIds = skipped
  )
}
