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
import net.sourceforge.ganttproject.action.GPAction
import net.sourceforge.ganttproject.GanttPreviousState
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.storage.ProjectDatabase
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.undo.GPUndoManager
import java.awt.event.ActionEvent
import java.time.LocalDate

/**
 * The two menu items of capacity levelling.
 *
 * BOTH ASK BEFOREHAND. That is not politeness: levelling can move 167 of 226 Tasks, filling in can
 * touch 226. Carrying out either unasked would be exactly the silent change this fork has already
 * found in several places. And both are ONE undo step.
 *
 * The preview also names what does NOT happen and why -- skipped Tasks with a reason, conflicts
 * with a date. A preview that shows only the success count keeps the essential part quiet.
 */

/**
 * What the caller is to display: text, and a callback with the answer.
 *
 * `fun interface` instead of `typealias`, because the wiring lives in `GanttProject.java`: a
 * Kotlin typealias on a function type cannot be addressed from Java.
 *
 * The callback instead of a return value is not ornament either: `showOptionDialog` does not
 * block, so the answer comes later.
 */
fun interface AskBeforeWriting {
  fun ask(message: String, answer: (Boolean) -> Unit)
}

/**
 * The block of the preview that reports [LevelConflict.NoPossibleDate] -- the Tasks for which the
 * window search found no date at all.
 *
 * A FUNCTION OF ITS OWN, while the blocks beside it are woven straight into the message builder,
 * and the reason is not tidiness. This is the one message of levelling that has to turn TWO kinds
 * of id into names -- the Task and the people -- and a report that says "12: 3, 7 all have to be
 * there" is worthless in exactly the situation it is written for. The blocks beside it have no
 * test at all, because the builder they sit in needs a whole running project; this one can be
 * measured on its own, and that is the state it does not copy.
 *
 * ONE ENTRY PER TASK, WITH A LINE PER REASON UNDER IT. Two functions stood here before 27.08.2026,
 * `blockedIntersectionText` and `capacityWindowText`, one per conflict kind -- and a Task that hit
 * both reasons got an entry in each, in two blocks, about one date. What replaced them is this
 * one, and the two-level shape is what makes the merge readable: a single line carrying both
 * reasons runs past three hundred characters, which in an option dialog is a wall rather than an
 * answer.
 *
 * THE REMEDY MOVED FROM THE HEADING INTO THE REASON LINES, and that is the correction of a real
 * defect rather than a rearrangement. The old headings each ended in a way out -- "what has to
 * change is the markings or the days off" over P5's block, "the workload or the length" over P6's.
 * With one block over Tasks that failed for different reasons, any remedy in the heading is wrong
 * for some of them; and for the frequent double case BOTH are true and either one on its own
 * suffices. So each reason line now carries its own way out, and the heading says only that
 * relieving either is enough. P6's report named that sentence as the thing a merge would have to
 * put right.
 *
 * @param taskName how an id becomes the name in the plan. Falls back to the id, because a report
 * with an ugly name still says more than no report.
 * @param personName the same for the people. [SHARED_POOL] is NOT passed through it -- that is not
 * a person but the pool of the unassigned Tasks, and it has a text of its own. Handing an empty
 * string to the name lookup would put a blank in the middle of a sentence and read as a bug.
 */
internal fun noPossibleDateText(
  conflicts: List<LevelConflict.NoPossibleDate>,
  taskName: (String) -> String,
  personName: (String) -> String
): String {
  if (conflicts.isEmpty()) {
    return ""
  }
  val text = StringBuilder(forkText("fork.levelling.nodate", conflicts.size))
  // The same five as everywhere else in this preview, and the same "and n more" behind it: a
  // dialog that lists two hundred rows is a dialog nobody reads to the end. Counted in TASKS and
  // not in lines, so a Task with two reasons still costs one of the five: the cut is there to
  // bound how many Tasks are named, and a Task named half is worse than a Task not named.
  conflicts.take(5).forEach { conflict ->
    text.append("\n  • ").append(forkText("fork.levelling.nodate.task", taskName(conflict.id)))
    // Both `if`s, and never an `else` between them: where both reasons applied, both are said.
    // That is the whole point of the merge, and an `else` here would rebuild the silence it was
    // built to end. The order is fixed -- absence first -- so that the same plan always renders
    // the same message.
    if (conflict.blocking.isNotEmpty()) {
      text.append("\n      - ").append(forkText("fork.levelling.nodate.blocked",
        conflict.blocking.joinToString(", ") { personName(it) }))
    }
    if (conflict.fullFor.isNotEmpty()) {
      text.append("\n      - ").append(forkText("fork.levelling.nodate.full",
        conflict.fullFor.joinToString(", ") {
          if (it == SHARED_POOL) forkText("fork.levelling.nodate.shared") else personName(it)
        }))
    }
  }
  if (conflicts.size > 5) {
    text.append("\n  … ").append(forkText("fork.levelling.more", conflicts.size - 5))
  }
  return text.toString()
}

/** Derive the effort from the duration and assign everything to one person. */
class BackfillAction(
  private val taskManager: TaskManager,
  private val resourceManager: HumanResourceManager,
  private val taskProperties: CustomPropertyManager,
  private val resourceProperties: CustomPropertyManager,
  private val projectDatabase: ProjectDatabase,
  private val undoManager: GPUndoManager,
  private val report: (Boolean, String) -> Unit,
  private val ask: AskBeforeWriting
) : GPAction("levelling.backfill") {

  // The label comes from this fork's bundle: GPAction knows only the original's keys and would
  // otherwise display the bare key.
  override fun getLocalizedName(): String = forkText("fork.levelling.backfill")

  override fun actionPerformed(event: ActionEvent?) {
    val resources = resourceManager.resources
    if (resources.isEmpty()) {
      report(true, forkText("fork.levelling.noResource"))
      return
    }
    // With exactly one person the assignment is unambiguous. With several, nothing is guessed.
    if (resources.size > 1) {
      report(true, forkText("fork.levelling.manyResources", resources.size))
      return
    }
    // A faulty hours schedule would fall back silently to the fixed number of hours. Better not
    // to compute at all than to compute plausibly wrong.
    val probleme = capacityProblems(taskManager, taskProperties, resourceManager, resourceProperties)
    if (probleme.hasErrors) {
      val text = StringBuilder()
      probleme.errors.forEach { (person, fehler) ->
        text.append(forkText("fork.capacity.error.title", person)).appendLine()
        fehler.forEach { text.append("  • ").append(it).appendLine() }
      }
      text.appendLine().append(forkText("fork.capacity.error.consequence"))
      report(true, text.toString())
      return
    }
    val resource = resources[0]
    val hoursPerDay = resource.dailyHours(resourceProperties)
    val proposal = proposeBackfill(collectBackfillTasks(taskManager, taskProperties), hoursPerDay)
    val ohneUrsprung = tasksMissingOriginalEffort(taskManager, taskProperties).size

    if (proposal.changeCount == 0 && ohneUrsprung == 0) {
      report(false, forkText("fork.levelling.backfill.nothing"))
      return
    }
    val skipped = proposal.skipped.values.groupingBy { it }.eachCount()
    val message = forkText("fork.levelling.backfill.preview",
      proposal.effortHours.size, proposal.assignTo.size, resource.name, hoursPerDay,
      skipped[BackfillSkip.CONTAINER] ?: 0,
      skipped[BackfillSkip.MILESTONE] ?: 0,
      skipped[BackfillSkip.ALREADY_HAS_EFFORT] ?: 0,
      skipped[BackfillSkip.WAIT_ONLY] ?: 0)
    val text = StringBuilder(message)
    if (ohneUrsprung > 0) {
      text.appendLine().appendLine().append(forkText("fork.levelling.backfill.original", ohneUrsprung))
    }
    ask.ask(text.toString()) { confirmed ->
      if (!confirmed) {
        return@ask
      }
      val touched = applyBackfillAsSingleEdit(proposal, resource, taskManager, taskProperties,
        projectDatabase, undoManager, forkText("fork.levelling.backfill.undo"))
      report(false, forkText("fork.levelling.backfill.done", touched))
    }
  }
}

/** Spread the Tasks so that nobody has to deliver more than 100 % at once. */
class LevellingAction(
  private val taskManager: TaskManager,
  private val resourceManager: HumanResourceManager,
  private val taskProperties: CustomPropertyManager,
  private val resourceProperties: CustomPropertyManager,
  private val undoManager: GPUndoManager,
  /**
   * The baselines of the project. Before levelling, saving the current state is offered --
   * without that the existing dates are gone after saving, and undo only helps as long as the
   * program is running.
   *
   * A SUPPLIER AND NOT A LIST, and that is measured on the machine: `GanttProject` REPLACES its
   * field `myPreviousStates` with a new ArrayList when a project is closed
   * (`GanttProject.java:819`). The menu items come into being at startup; capturing the list
   * there once means writing, after the first file is opened, into a list nobody reads any more.
   * Measured: the baseline was confirmed, reported -- and was not in the file.
   */
  private val baselines: () -> MutableList<GanttPreviousState>,
  private val today: () -> LocalDate = { LocalDate.now() },
  private val report: (Boolean, String) -> Unit,
  private val ask: AskBeforeWriting
) : GPAction("levelling.run") {

  override fun getLocalizedName(): String = forkText("fork.levelling.run")

  override fun actionPerformed(event: ActionEvent?) {
    // A faulty hours schedule would fall back silently to the fixed number of hours. Better not
    // to compute at all than to compute plausibly wrong.
    val probleme = capacityProblems(taskManager, taskProperties, resourceManager, resourceProperties)
    if (probleme.hasErrors) {
      val text = StringBuilder()
      probleme.errors.forEach { (person, fehler) ->
        text.append(forkText("fork.capacity.error.title", person)).appendLine()
        fehler.forEach { text.append("  • ").append(it).appendLine() }
      }
      text.appendLine().append(forkText("fork.capacity.error.consequence"))
      report(true, text.toString())
      return
    }
    // THE QUESTION ABOUT THE PAST, and it comes BEFORE everything else -- the answer changes the
    // calculation, not only the writing. It is asked only when such Tasks exist: a question
    // without an occasion is a question that gets clicked away.
    val liegengeblieben = unstartedInThePast(taskManager, today())
    if (liegengeblieben.isEmpty()) {
      weiter(moveUnstartedPast = true, verschobeneAusDerVergangenheit = 0)
      return
    }
    val frage = StringBuilder(forkText("fork.levelling.past.ask", liegengeblieben.size))
      .appendLine().appendLine().append(forkText("fork.levelling.past.what"))
      .appendLine().appendLine().append(forkText("fork.levelling.past.hint"))
    liegengeblieben.take(5).forEach {
      frage.appendLine().append("  - ").append(it.name)
    }
    ask.ask(frage.toString()) { verschieben ->
      weiter(verschieben, if (verschieben) liegengeblieben.size else 0)
    }
  }

  private fun weiter(moveUnstartedPast: Boolean, verschobeneAusDerVergangenheit: Int) {
    val tasks = collectLevelTasks(taskManager, taskProperties, resourceProperties, today(),
      moveUnstartedPast)
    if (tasks.isEmpty()) {
      report(false, forkText("fork.levelling.noTasks"))
      return
    }
    // toModelLocalDate, not java.time: see LegacyDates.kt.
    val projectStart = taskManager.projectStart?.toModelLocalDate() ?: today()
    // FROM TODAY, not from the project start: laying unfinished work into the past yields no
    // plan. What has already been begun stays where it is regardless -- `frozen` handles that.
    val abWann = maxOf(projectStart, today())
    val auslastung = resourceManager.resources.associate {
      it.id.toString() to it.utilisationPercent(resourceProperties)
    }
    // The packing limit stays at 100 %: the utilisation acts on the available HOURS (see
    // availableHoursPerDay) and is therefore already contained in the durations. Applying it a
    // second time here would mean subtracting the same buffer twice.
    //
    // The days off of the people come in here, and as of this stage they take effect -- but only
    // for the people an assignment marks as BLOCKING (axis A). A Task is then laid only where
    // every one of them is at work; several of them give the intersection of their time. For
    // everybody else nothing changes: their day off takes their hours out of the day and leaves
    // the Task where it is, exactly as before.
    //
    // NO SECOND SWITCH FOR IT. Whether absence moves a Task is decided at the assignment, where
    // the person is, and not once more in the menu -- a global flag would silently overrule the
    // markings entered in the plan.
    //
    // [fork change] THE DAY GRID IS PER TASK AND NOT PER PROJECT, since 04.09.2026. This line used
    // to read `workingDayTest(taskManager.calendar)` -- one calendar for the whole plan -- while
    // the two neighbours of this call, `durationAtStart` here and `writeLevellingBack` in the
    // write-back, both asked [WorkWeekWorkingDays] per task. Inside ONE run the duration of a task
    // was therefore computed on the grid of the people on it and the window for it was searched on
    // the project's. Somebody working Monday to Saturday had their capacity booked on the Monday
    // they do not work on the task and the Saturday they do left free -- one calendar day of error
    // per Saturday a task runs over. `WorkWeekLevellingWindowTest` measured that and now demands
    // the two agree.
    val result = levelTasks(tasks, abWann, workingDaysPerTask(taskManager, resourceProperties),
      durationAtStart(taskManager, taskProperties, resourceProperties),
      isAvailable = availabilityTest(resourceManager))

    val cycles = result.conflicts.filterIsInstance<LevelConflict.Cycle>()
    if (cycles.isNotEmpty()) {
      report(true, forkText("fork.levelling.cycle"))
      return
    }

    val moved = result.starts.count { (id, start) ->
      val task = taskManager.getTask(id.toIntOrNull() ?: return@count false) ?: return@count false
      task.start.time.toModelLocalDate() != start
    }
    if (moved == 0 && result.conflicts.isEmpty()) {
      report(false, forkText("fork.levelling.nothing"))
      return
    }

    val fristen = result.conflicts.filterIsInstance<LevelConflict.DeadlineMissed>()
    val eingefroren = tasks.count { it.frozen }
    val unreachable = result.conflicts.filterIsInstance<LevelConflict.FixedDateNotReachable>()
    val overloads = result.conflicts.filterIsInstance<LevelConflict.Overload>()
    val message = StringBuilder(forkText("fork.levelling.preview", moved, tasks.size))
    if (unreachable.isNotEmpty()) {
      message.append("\n\n").append(forkText("fork.levelling.unreachable", unreachable.size))
      unreachable.take(5).forEach {
        val name = taskManager.getTask(it.id.toIntOrNull() ?: 0)?.name ?: it.id
        message.append("\n  • ").append(
          forkText("fork.levelling.unreachable.row", name, it.fixedStart, it.earliestPossible))
      }
      if (unreachable.size > 5) {
        message.append("\n  … ").append(forkText("fork.levelling.more", unreachable.size - 5))
      }
    }
    // THE TASKS WITH NO POSSIBLE DATE, and this block stands here rather than further down for a
    // reason: it is the only entry in this list that reports a date the calculation itself calls
    // wrong. An overload is a plan that is tight, a missed deadline is a plan that is late -- this
    // one is a plan that cannot be laid at all, and burying it under the others would be the same
    // silence in a longer form.
    //
    // ONE BLOCK, AND EVERY AFFECTED TASK IN IT EXACTLY ONCE. Two blocks stood here until
    // 27.08.2026, one per reason, and a Task whose search failed for both reasons appeared in
    // both -- twice in the preview, two sentences about one date, and the reader left to work out
    // that it was one fallback and not two problems. Both reasons now stand under one entry, and
    // where both apply, relieving either one is enough; the reason lines say which.
    val ohneTermin = result.conflicts.filterIsInstance<LevelConflict.NoPossibleDate>()
    if (ohneTermin.isNotEmpty()) {
      message.append("\n\n").append(noPossibleDateText(ohneTermin,
        { id -> taskManager.getTask(id.toIntOrNull() ?: 0)?.name ?: id },
        // The blocking people are named by resource id -- the same string the capacity pools use,
        // see `LevellingAdapter.toLevelTask`. Turning it back into a name is this side's job.
        { id -> resourceManager.getById(id.toIntOrNull() ?: -1)?.name ?: id }))
    }
    if (overloads.isNotEmpty()) {
      message.append("\n\n").append(forkText("fork.levelling.overload", overloads.size))
    }
    // DEADLINES, FROZEN WORK AND UTILISATION BELONG IN THE PREVIEW. They had stood here once and
    // were lost in a later rebuild -- noticed on screen: the calculation knew about a missed
    // deadline, the dialog said nothing about it. A preview that keeps the most important finding
    // quiet is worse than none.
    if (fristen.isNotEmpty()) {
      message.append("\n\n").append(forkText("fork.levelling.deadline", fristen.size))
      fristen.take(5).forEach {
        val name = taskManager.getTask(it.id.toIntOrNull() ?: 0)?.name ?: it.id
        message.append("\n  • ").append(forkText("fork.levelling.deadline.row",
          name, it.deadline, it.actualEnd, it.missingDays))
      }
      if (fristen.size > 5) {
        message.append("\n  … ").append(forkText("fork.levelling.more", fristen.size - 5))
      }
    }
    if (eingefroren > 0) {
      message.append("\n\n").append(forkText("fork.levelling.frozen", eingefroren))
    }
    val gradWerte = auslastung.values.distinct()
    if (gradWerte.size == 1 && gradWerte[0] != 100) {
      message.append("\n\n").append(forkText("fork.levelling.utilisation", gradWerte[0]))
    }
    ask.ask(message.toString()) { confirmed ->
      if (!confirmed) {
        return@ask
      }
      // The baseline FIRST, THEN the levelling. The other way round it records the dates that
      // have already been moved and is worthless.
      val baselineText = StringBuilder(forkText("fork.baseline.ask"))
        .appendLine().appendLine().append(forkText("fork.baseline.what"))
        .appendLine().appendLine().append(forkText("fork.baseline.hint"))
      ask.ask(baselineText.toString()) { sichern ->
        val meldung = StringBuilder()
        if (sichern) {
          val name = forkText("fork.baseline.name", today().toString())
          val basisplan = GanttPreviousState(name, GanttPreviousState.createTasks(taskManager))
          // init() AND saveFile() ARE MANDATORY, and the constructor does not show it: a
          // baseline does NOT hold its Tasks in memory but in a temporary file. `load()` -- what
          // the saver and the chart call -- reads exactly that file. Without the two calls
          // `myFile` is null: the baseline would be visible in the list, invisible in the chart
          // and an exception when saving. Looked up in the code
          // (GanttPreviousState.java:55/75/99), after the first attempt had left them out.
          try {
            basisplan.init()
            basisplan.saveFile()
            baselines().add(basisplan)
            meldung.append(forkText("fork.baseline.done", name)).appendLine()
          } catch (e: java.io.IOException) {
            // No baseline is bad; half a one would be worse. Better to report and carry on --
            // levelling itself is unaffected by it.
            net.sourceforge.ganttproject.GPLogger.log(e)
            meldung.append(forkText("fork.baseline.failed")).appendLine()
          }
        }
        val written = applyLevellingAsSingleEdit(result.starts, taskManager, undoManager,
          forkText("fork.levelling.undo"), result.durations,
          resourceProperties = resourceProperties)
        report(false, meldung.append(forkText("fork.levelling.done", written)).toString())
      }
    }
  }
}
