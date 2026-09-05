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
package net.sourceforge.ganttproject.fork

import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.task.TaskManager
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * [fork change] WHAT ONE PERSON'S ABSENCE COSTS THE PLAN — three lines, and the third is a line
 * rather than a list.
 *
 * ═══ WHY THREE LINES AND NOT A LIST OF EVERYTHING THAT MOVES ═══
 *
 * This shape is not a taste. It is the result of 55 measured runs on plans built to the shape of
 * the plan this fork exists for, written up in `2026-09-05-vorschau-gemessen.md`. The finding that
 * decides it: in ALL 55 runs the MEDIAN shift equalled the LARGEST shift — 54 times seven calendar
 * days, once six. No task moved further, none moved forward. A list of 215 moved tasks would write
 * „+7 days" 215 times and would tell the reader nothing the one line does not.
 *
 * The three lines, and what each is for:
 *
 *  1. THE PROJECT END. The only one of the six measured numbers that has a value in every single
 *     run and uses the same measure across every plan size and every position of the holiday. It
 *     is „+ the length of the holiday" almost always — and precisely THAT is why the runs where
 *     it is zero are worth having: three of nineteen measured positions cost the project nothing
 *     at all.
 *  2. THE DEADLINES. The only number that names a CONSEQUENCE rather than a movement. A task that
 *     finishes a week later is not a problem; a task that misses its promised date is. It is also
 *     small — 0 to 17 of 236, usually single-digit — so the names fit.
 *  3. THE SHIFT, AS ONE LINE. See above.
 *
 * And what is deliberately NOT here, each with its measurement:
 *
 *  * NO GROUPING BY PERSON. On the reference shape 31 % of the moved tasks have no person at all
 *    (they sit in `SHARED_POOL`); on a plan with several people 81 % of them belonged to somebody
 *    other than the one taking the holiday. „Who is affected" is the wrong heading on both.
 *  * THE COUNT OF MOVED TASKS IS NOT THE HEADLINE. It runs from 1 to 219 of 236 across the
 *    nineteen measured positions and it measures WHERE IN THE PLAN the holiday falls, not what it
 *    costs. „215 of 236" reads like a catastrophe and means „the holiday is early in the year".
 *    It stands in line 3, behind the measure.
 *  * NO DISTRIBUTION, NO HISTOGRAM, NO QUANTILES. On this shape there is nothing to distribute.
 *
 * ═══ WHY IT COMPUTES TWICE ═══
 *
 * The question is not „what would a levelling run move" but „what does THIS absence move, against
 * the plan as levelling would otherwise leave it". Those are different numbers. On the reference
 * plan a single run compared against the dates in the model reports 212 of 236 moved WHETHER OR
 * NOT anybody takes a holiday — it measures the first levelling, not the absence. So both states
 * are levelled and the two results are compared against each other. That doubles the cost, and the
 * cost is affordable: about 110 ms for a plan of 236 leaves on the machine this was measured on.
 *
 * ═══ IT WRITES NOTHING, AND THAT IS THE PROPERTY TO GUARD ═══
 *
 * Neither run touches the model. The hypothetical state is handed in as a [DaysOffView] instead of
 * being written into the resource and taken out again — see there for why that choice is not a
 * matter of style. `VacationPreviewNoChangeTest` is the guard, and it is the check this whole file
 * is most likely to be broken against.
 */

/** One deadline that WAS being met and is not any more. */
data class MissedDeadline(
  val id: String,
  val name: String,
  val deadline: LocalDate,
  val actualEnd: LocalDate,
  val missingDays: Int
)

/**
 * The forecast. Numbers only — the text is [previewText], so that what is measured and what is
 * said about it can be checked apart from each other.
 */
data class VacationPreview(
  /** All leaf tasks the levelling looked at. The denominator of line 3. */
  val taskCount: Int,
  /** The ids of the tasks that lie on a different day with the absence than without it. */
  val movedIds: List<String>,
  /**
   * The shifts of the moved tasks in CALENDAR days, signed, ascending. Positive means later.
   *
   * CALENDAR DAYS AND NOT WORKING DAYS, because that is what was measured and what the reader
   * counts on a calendar: „one week" is seven. A shift of five working days over a weekend is
   * seven days late in every sense a plan cares about.
   */
  val shifts: List<Long>,
  val projectEndBefore: LocalDate?,
  val projectEndAfter: LocalDate?,
  /** How many tasks in the plan carry a „finish by" date at all. Decides whether line 2 appears. */
  val deadlinesInPlan: Int,
  /** Deadlines that were ALREADY being missed before this absence. Not a consequence of it. */
  val alreadyMissedBefore: Int,
  /** Deadlines that held before and are missed after. THE line-2 number. */
  val newlyMissed: List<MissedDeadline>,
  /**
   * [fork change] Tasks laid with a DIFFERENT DURATION because of the absence.
   *
   * ═══ WHY THIS IS NOT AN EXTRA AND WHY IT IS NOT IN [movedIds] ═══
   *
   * An absence acts on a task in TWO ways, and which one it takes is decided by one tick box. If
   * the person is marked as indispensable, the window search will not lay the task across their
   * days off and MOVES it. If they are not — which is what every assignment in every plan written
   * before this fork is — their hours simply drop out of the day, the day stays, and the task GETS
   * LONGER where it is. `DaysOffDuration.kt` says so in as many words, and the second case is by
   * far the commoner one.
   *
   * A task that got longer did not MOVE, so putting it in [movedIds] would make line 3 say "moves
   * by 7 days" about a task that starts on exactly the same day. Leaving it out of the preview
   * ALTOGETHER is worse: on a plan whose stretched task has no successor and is not the last one,
   * every other number stays at zero and the preview would say "this absence moves nothing" about
   * a task that doubled in length. That sentence would be false, and it is the one sentence this
   * preview must never get wrong.
   *
   * So it is counted separately, it keeps [changesNothing] honest, and it earns one sentence of
   * its own — only when there is one to write. This goes beyond the three lines the measurement of
   * 05.09.2026 recommended; the measurement built its plans with indispensable people throughout
   * and could not see this case.
   */
  val stretchedIds: List<String> = emptyList()
) {
  val movedCount: Int get() = movedIds.size

  /** How much later the project ends, in calendar days. Negative would mean earlier. */
  val projectEndShift: Long
    get() = if (projectEndBefore == null || projectEndAfter == null) 0L
    else ChronoUnit.DAYS.between(projectEndBefore, projectEndAfter)

  /**
   * Nothing at all follows from this absence.
   *
   * ALL THREE CONDITIONS, not just the count of moved tasks. A run in which no task moves but a
   * deadline is newly missed cannot happen today — a deadline is missed because an end moved — but
   * saying so here would make the sentence „this changes nothing" depend on an argument instead of
   * on what was measured.
   */
  val changesNothing: Boolean
    get() = movedIds.isEmpty() && projectEndShift == 0L && newlyMissed.isEmpty() &&
      stretchedIds.isEmpty()

  /**
   * Every moved task moves by the SAME amount, so line 3 can name one figure.
   *
   * ═══ THIS IS A TIGHTER TEST THAN THE REPORT PROPOSED, ON PURPOSE ═══
   *
   * `2026-09-05-vorschau-gemessen.md` §6.2 proposes „median == largest" as the rule. That rule is
   * satisfied by 100 tasks at +7 and one at -3: the median is 7 and the largest is 7, and the
   * sentence „all of them move by exactly one week" would then be false about the one that moved
   * forward. Forward-moving tasks were measured — up to 23 in a single run on a plan with several
   * people — so the case is real rather than hypothetical.
   *
   * `smallest == largest` says exactly what the sentence claims and nothing else. It is not a
   * weaker guarantee on the measured data: in all 55 runs where median equalled largest, every
   * moved task moved by that same amount (the column „exactly 7" equalled the column „moved", and
   * „backwards" was 0), so smallest equalled largest there too. The rule that was measured is kept;
   * the rule that is checked is the one the sentence needs.
   */
  val uniformShift: Boolean get() = shifts.isNotEmpty() && shifts.first() == shifts.last()

  /**
   * The middle shift. The UPPER middle for an even count — deliberately an integer, because
   * „half a day later" is not a statement anybody can act on. Only ever shown when the shifts
   * differ; where they do not, [uniformShift] carries the answer.
   */
  val medianShift: Long get() = if (shifts.isEmpty()) 0L else shifts[shifts.size / 2]
}

/**
 * What one absence costs, computed twice and compared.
 *
 * @param before how the days off are to be READ for the first run — the state the plan is in
 * without the absence being previewed.
 * @param after the same for the second run. In the running program this is [daysOffAsEntered]: the
 * absence has been saved, and the hypothetical state is the PAST one. That direction is chosen and
 * it matters — everything else the dialog writes (the working week, the home office) is then in
 * BOTH runs, so the only difference between them is the absence.
 *
 * @return null when no forecast can be made at all: nothing to level, a cycle in the dependencies,
 * or an unreadable hours schedule. The levelling menu item reports all three properly, with the
 * remedy; a resource dialog is the wrong place to teach about dependency cycles, and a half
 * forecast would be worse than none.
 */
fun vacationPreview(
  taskManager: TaskManager,
  resourceManager: HumanResourceManager,
  taskProperties: CustomPropertyManager,
  resourceProperties: CustomPropertyManager,
  before: DaysOffView,
  after: DaysOffView = daysOffAsEntered,
  today: LocalDate = LocalDate.now()
): VacationPreview? {
  if (capacityProblems(taskManager, taskProperties, resourceManager, resourceProperties).hasErrors) {
    return null
  }
  // THE SAME SETTING FOR BOTH RUNS, and `true` is the one the levelling menu uses when it has
  // nothing to ask about. Work left lying in the past is brought forward in both states, so the
  // COMPARISON — which is all this function reports — is like for like. The absolute dates in
  // line 1 do depend on it, and a person who answers the levelling's own question with „no" will
  // see two other dates there. That is a known limit, not a hidden one.
  val tasks = collectLevelTasks(taskManager, taskProperties, resourceProperties, today,
    moveUnstartedPast = true)
  if (tasks.isEmpty()) {
    return null
  }
  // ONE GRID OBJECT FOR BOTH RUNS. The day grid comes from the working weeks and the project
  // calendar; days off are not in it, so it cannot carry anything from one run into the other. It
  // is also what makes the second run cheap — the remembered calendar answers are already there.
  val grid = workingDaysPerTask(taskManager, resourceProperties)
  val projectStart = taskManager.projectStart?.toModelLocalDate() ?: today
  val from = maxOf(projectStart, today)
  // The home office is read once and shared: this preview is about days off, and handing the two
  // runs two different presence channels would let a second difference into the comparison.
  val atWorkplace = presenceTest(resourceManager, resourceProperties)

  fun run(view: DaysOffView): LevelledPlan? {
    val result = levelTasks(tasks, from, grid,
      durationAtStart(taskManager, taskProperties, resourceProperties, view),
      isAvailable = availabilityTest(resourceManager, view),
      isAtWorkplace = atWorkplace)
    if (result.conflicts.any { it is LevelConflict.Cycle }) {
      return null
    }
    val byId = tasks.associateBy { it.id }
    val ends = result.starts.mapValues { (id, start) ->
      val task = byId.getValue(id)
      lastWorkingDay(start, result.durations[id] ?: task.durationDays) { day -> grid(task, day) }
    }
    val durations = result.starts.mapValues { (id, _) ->
      result.durations[id] ?: byId.getValue(id).durationDays
    }
    return LevelledPlan(result.starts, durations, ends,
      result.conflicts.filterIsInstance<LevelConflict.DeadlineMissed>().associateBy { it.id })
  }

  val plannedBefore = run(before) ?: return null
  val plannedAfter = run(after) ?: return null

  val moved = mutableListOf<String>()
  val shifts = mutableListOf<Long>()
  plannedAfter.starts.forEach { (id, startAfter) ->
    val startBefore = plannedBefore.starts[id] ?: return@forEach
    val days = ChronoUnit.DAYS.between(startBefore, startAfter)
    if (days != 0L) {
      moved.add(id)
      shifts.add(days)
    }
  }

  // The other half of what an absence does to a task -- see VacationPreview.stretchedIds.
  val stretched = plannedAfter.durations.filter { (id, daysAfter) ->
    plannedBefore.durations[id]?.let { it != daysAfter } ?: false
  }.keys.toList()

  // ONLY THE DEADLINES THAT HELD BEFORE AND ARE MISSED AFTER, AND THIS IS THE LINE THAT IS EASIEST
  // TO BUILD WRONG. Reporting `plannedAfter.missed.size` would put every deadline the plan was
  // already missing on this absence's account; reporting the DIFFERENCE OF THE COUNTS would report
  // 0 for a plan in which one deadline starts holding and another starts breaking. What is asked is
  // a change of STATE per task, so the two sets are compared per id and not by size.
  //
  // A deadline that was already missed and is now missed by MORE is deliberately NOT reported. It
  // did not change state, and the reader can act on the ones that did; `alreadyMissedBefore`
  // carries the count so the decision can be revisited without measuring again.
  val newlyMissed = plannedAfter.missed
    .filterKeys { it !in plannedBefore.missed }
    .map { (id, conflict) ->
      MissedDeadline(id, taskManager.getTask(id.toIntOrNull() ?: 0)?.name ?: id,
        conflict.deadline, conflict.actualEnd, conflict.missingDays)
    }
    .sortedBy { it.deadline }

  return VacationPreview(
    taskCount = tasks.size,
    movedIds = moved,
    shifts = shifts.sorted(),
    projectEndBefore = plannedBefore.ends.values.maxOrNull(),
    projectEndAfter = plannedAfter.ends.values.maxOrNull(),
    deadlinesInPlan = tasks.count { it.deadline != null },
    alreadyMissedBefore = plannedBefore.missed.size,
    newlyMissed = newlyMissed,
    stretchedIds = stretched)
}

/** One levelled state, reduced to what the comparison needs. */
private class LevelledPlan(
  val starts: Map<String, LocalDate>,
  /** The duration each task was actually LAID with -- not the one it carries in the plan. */
  val durations: Map<String, Int>,
  val ends: Map<String, LocalDate>,
  val missed: Map<String, LevelConflict.DeadlineMissed>
)

/** How many names of missed deadlines are written out before the count takes over. */
private const val NAMES_SHOWN = 5

/**
 * The three lines, as text.
 *
 * SEPARATE FROM THE MEASURING ABOVE so that both halves can be checked on their own: the numbers
 * against a plan with a known answer, the wording against numbers written out by hand.
 */
fun previewText(preview: VacationPreview): String {
  if (preview.changesNothing) {
    // ONE SENTENCE, NO FIGURES. This is the most frequent case — on one measured shape 17 of 19
    // positions of the holiday, on the reference shape 3 of 19 — and it is the case in which a
    // preview becomes a nuisance. Whoever reads „nothing moves" has their answer; a table of
    // zeroes underneath it would only have to be read to find that out again.
    return forkText("fork.vacation.preview.nothing")
  }
  val message = StringBuilder(forkText("fork.vacation.preview.head"))

  // LINE 1 — THE PROJECT END.
  message.append("\n\n")
  val endBefore = preview.projectEndBefore
  val endAfter = preview.projectEndAfter
  if (endBefore == null || endAfter == null) {
    message.append(forkText("fork.vacation.preview.end.unknown"))
  } else if (preview.projectEndShift == 0L) {
    message.append(forkText("fork.vacation.preview.end.same", endBefore))
  } else {
    message.append(forkText("fork.vacation.preview.end.changed", endBefore, endAfter,
      shiftPhrase(preview.projectEndShift)))
  }

  // LINE 2 — THE DEADLINES. Absent entirely when the plan carries none.
  //
  // NOT „0 deadlines are missed" ON A PLAN WITHOUT DEADLINES, and that is a decision rather than an
  // omission. On such a plan the zero is not a result, it is the absence of a question; read as a
  // result it says „your dates are safe" about dates nobody entered. Where deadlines DO exist the
  // zero is a genuine finding and is said out loud.
  if (preview.deadlinesInPlan > 0) {
    message.append("\n\n")
    if (preview.newlyMissed.isEmpty()) {
      message.append(forkText("fork.vacation.preview.deadline.none", preview.deadlinesInPlan))
    } else {
      message.append(forkText("fork.vacation.preview.deadline.broken", preview.newlyMissed.size))
      preview.newlyMissed.take(NAMES_SHOWN).forEach {
        // The same row text the levelling preview uses. One wording for one fact.
        message.append("\n  • ").append(forkText("fork.levelling.deadline.row",
          it.name, it.deadline, it.actualEnd, it.missingDays))
      }
      if (preview.newlyMissed.size > NAMES_SHOWN) {
        message.append("\n  … ")
          .append(forkText("fork.levelling.more", preview.newlyMissed.size - NAMES_SHOWN))
      }
    }
    if (preview.alreadyMissedBefore > 0) {
      message.append("\n").append(forkText("fork.vacation.preview.deadline.already",
        preview.alreadyMissedBefore))
    }
  }

  // LINE 3 — THE SHIFT. One line when every moved task moves by the same amount, which is what 55
  // of 55 measured runs on this fork's reference shape did; a range otherwise. The rule is a
  // comparison of two numbers, NOT knowledge about how the plan is built — a plan with several
  // indispensable people really does spread, and there the range is the honest answer.
  if (preview.movedCount > 0) {
    message.append("\n\n")
    if (preview.uniformShift) {
      message.append(forkText("fork.vacation.preview.shift.uniform", preview.movedCount,
        preview.taskCount, shiftPhrase(preview.shifts.first())))
    } else {
      message.append(forkText("fork.vacation.preview.shift.spread", preview.movedCount, preview.taskCount,
        shiftPhrase(preview.shifts.first()), shiftPhrase(preview.shifts.last()),
        shiftPhrase(preview.medianShift)))
    }
  }

  // THE TASKS THAT GOT LONGER RATHER THAN MOVING. Only written when there are any, and after
  // line 3, because it is the same kind of statement one step to the side: line 3 is about tasks
  // that start elsewhere, this is about tasks that start where they did and end later.
  if (preview.stretchedIds.isNotEmpty()) {
    message.append("\n\n").append(forkText("fork.vacation.preview.longer",
      preview.stretchedIds.size))
  }

  // AND WHAT HAS NOT HAPPENED. The two dates in line 1 are two COMPUTED plans, and neither of them
  // is what stands in the plan right now — the reference plan has never been levelled, so its
  // stored end is a third date again. Leaving that unsaid would be the quiet mistake this preview
  // exists to avoid: somebody would go looking for 08.02.2046 in their chart and not find it.
  message.append("\n\n").append(forkText("fork.vacation.preview.hint"))
  return message.toString()
}

/** „one week later", „three calendar days earlier" — composed, so a translation can reorder it. */
private fun shiftPhrase(days: Long): String {
  val amount = forkText("fork.vacation.preview.amount", abs(days))
  return forkText(if (days < 0) "fork.vacation.preview.earlier" else "fork.vacation.preview.later", amount)
}
