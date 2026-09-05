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

import biz.ganttproject.core.calendar.GanttDaysOff
import biz.ganttproject.core.calendar.WeekendCalendarImpl
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import net.sourceforge.ganttproject.task.dependency.constraint.FinishStartConstraintImpl
import net.sourceforge.ganttproject.undo.GPUndoListener
import net.sourceforge.ganttproject.undo.GPUndoManager
import net.sourceforge.ganttproject.undo.UndoableEditTxnFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * THE GUARD THIS WHOLE FEATURE STANDS OR FALLS ON: THE PREVIEW MAY NOT CHANGE THE PLAN.
 *
 * It levels twice — once as things are and once as they would be — and it says what the difference
 * would cost. If a single date, duration or day-off entry is different afterwards, it has not
 * previewed anything, it has DONE it, and it has done it without asking.
 *
 * ═══ WHY THIS FILE EXISTS RATHER THAN AN ASSERTION SOMEWHERE ═══
 *
 * Four earlier attempts at this preview walked into the same trap, and the trap is not the writing
 * — nobody calls the write-back by accident. It is the arithmetic: the cheapest way to ask "what
 * would this holiday do" is to write the holiday into the person, level, and take it out again.
 * That works until anything between the two steps throws, and then the holiday stays. This code
 * takes the other road — the hypothetical state is a [DaysOffView] and never reaches the model —
 * and this file is what says so out loud.
 *
 * ═══ HOW THE GUARD WAS PROVED ABLE TO FAIL ═══
 *
 * A check nobody has ever seen fail is worth nothing. This one was broken four ways in
 * `VacationPreview.kt` and went red for each of them; the wording is in the report of 05.09.2026:
 *
 *  1. the preview writes its result back with `applyLevellingAsSingleEdit`
 *  2. the preview writes the hypothetical days off into the resource and takes them out again
 *  3. the preview writes them in and forgets to take them out
 *  4. the preview moves one single task by one single day
 *
 * And [the fingerprint sees a plan that really did move] below is the positive control, running
 * inside this same file: it performs a real write-back and demands that the fingerprint NOTICE.
 * Without it, a fingerprint that read nothing at all would make every check above pass.
 */
class VacationPreviewNoChangeTest {

  init {
    object : CalendarFactory() {
      init {
        setLocaleApi(object : CalendarFactory.LocaleApi {
          override fun getLocale(): Locale = Locale.GERMANY
          override fun getShortDateFormat(): DateFormat =
            DateFormat.getDateInstance(DateFormat.SHORT, Locale.GERMANY)
        })
      }
    }
  }

  private val start = LocalDate.of(2027, 1, 4)
  private val absenceWeek = LocalDate.of(2027, 1, 18)

  /** Runs the edit at once. Only needed by the positive control. */
  private class ImmediateUndoManager : GPUndoManager {
    override fun undoableEdit(localizedName: String, runnableEdit: Runnable) = runnableEdit.run()
    override fun canUndo() = false
    override fun canRedo() = false
    override fun undo() = Unit
    override fun redo() = Unit
    override val undoPresentationName = ""
    override val redoPresentationName = ""
    override fun addUndoableEditListener(listener: GPUndoListener) = Unit
    override fun removeUndoableEditListener(listener: GPUndoListener) = Unit
    override fun die() = Unit
    override fun addUndoableEditTxnFactory(factory: UndoableEditTxnFactory) = Unit
  }

  private class Plan {
    val builder: TestSetupHelper.TaskManagerBuilder =
      TestSetupHelper.newTaskManagerBuilder().withCalendar(WeekendCalendarImpl())
    val taskManager: TaskManager = builder.build()
    val resourceManager: HumanResourceManager = builder.resourceManager
    val resourceProperties: CustomPropertyManager get() = resourceManager.customPropertyManager
    val taskProperties: CustomPropertyManager get() = taskManager.customPropertyManager
  }

  /**
   * EVERYTHING THE PREVIEW COULD POSSIBLY DISTURB, in one string.
   *
   * The dates and durations of every task, because that is what levelling writes. The days off of
   * every person, because that is what the hypothetical state is made of and what a write-and-undo
   * implementation would leave behind. The completion, because a frozen task is what decides
   * whether the next run may move anything at all.
   *
   * SORTED BY ID: the order of `taskManager.tasks` is not promised, and a fingerprint that changed
   * with it would go red for no reason and be switched off.
   */
  private fun fingerprint(plan: Plan): String {
    val tasks = plan.taskManager.tasks.sortedBy { it.taskID }.joinToString("\n") { task ->
      "task ${task.taskID} '${task.name}' start=${task.start.time.toModelLocalDate()} " +
        "days=${task.duration.length} done=${task.completionPercentage} " +
        "deadline=${task.deadlineDate(plan.taskProperties)}"
    }
    val people = plan.resourceManager.resources.sortedBy { it.id }.joinToString("\n") { person ->
      "person ${person.id} '${person.name}' off=${person.daysOffRanges()}"
    }
    return "$tasks\n$people"
  }

  /**
   * THE SECOND FINGERPRINT, AND IT EXISTS BECAUSE THE FIRST ONE IS BLIND TO THE CLASSIC TRAP.
   *
   * Writing the hypothetical days off into the person, levelling and writing the old ones back is
   * the obvious way to build this preview. Done without a slip it leaves the model EQUAL IN
   * CONTENT — so [fingerprint] above cannot see it, by construction, and would report green about
   * an implementation that is one thrown exception away from leaving somebody's holidays altered.
   *
   * `clearDaysOff` followed by `addDaysOff` builds NEW [biz.ganttproject.core.calendar.GanttDaysOff]
   * objects, so the identities change even when the dates do not. That is what this reads.
   */
  private fun daysOffIdentities(plan: Plan): String =
    plan.resourceManager.resources.sortedBy { it.id }.joinToString("\n") { person ->
      val list = person.daysOff
      (0 until list.size).joinToString(",") { System.identityHashCode(list.getElementAt(it)).toString() }
    }

  private fun buildPlan(): Triple<Plan, HumanResource, List<Task>> {
    val plan = Plan()
    val natalie = plan.resourceManager.create("Natalie", 0).also {
      it.setValue(EffortDrivenProperties.findOrCreateResourceHours(plan.resourceProperties), 8.0)
    }
    val tasks = (0 until 5).map { i ->
      plan.taskManager.newTaskBuilder().withName("T${i + 1}")
        .withStartDate(start.plusDays(7L * i).toModelDate())
        .withDuration(plan.taskManager.createLength(5L)).build().also { task ->
          task.customValues.setValue(
            EffortDrivenProperties.findOrCreateTaskEffort(plan.taskProperties), 40.0)
          task.assignmentCollection.addAssignment(natalie).apply {
            load = 100f
            isBlocking = true
          }
        }
    }
    tasks.zipWithNext().forEach { (before, after) ->
      plan.taskManager.dependencyCollection.createDependency(after, before,
        FinishStartConstraintImpl())
    }
    tasks[4].setDeadline(plan.taskProperties, LocalDate.of(2027, 2, 5))
    return Triple(plan, natalie, tasks)
  }

  /** As in VacationPreviewTest: JUnit's assertNotNull hands back nothing and cannot be chained. */
  private fun VacationPreview?.required(): VacationPreview =
    this ?: throw AssertionError("a forecast has to be possible on this plan")

  private fun previewOf(plan: Plan, person: HumanResource): VacationPreview? = vacationPreview(
    plan.taskManager, plan.resourceManager, plan.taskProperties, plan.resourceProperties,
    before = daysOffReplacedFor(person, emptyList()),
    after = daysOffAsEntered,
    today = start)

  /**
   * THE GUARD. A preview that finds plenty to report leaves the plan byte for byte as it was.
   *
   * The absence is a real one and the forecast is a loud one — end one week later, three tasks
   * moved, a deadline broken. If a preview is ever going to write something, this is the run in
   * which it does.
   */
  @Test
  fun `a preview with plenty to report changes nothing in the plan`() {
    val (plan, natalie, _) = buildPlan()
    natalie.addDaysOff(GanttDaysOff(absenceWeek.toModelDate(),
      absenceWeek.plusDays(7).toModelDate()))
    val before = fingerprint(plan)

    val preview = previewOf(plan, natalie).required()

    // The preview really did find something. Without this the check below would also pass for a
    // preview that did nothing at all.
    assertEquals(7L, preview.projectEndShift,
      "precondition: this run has to be one that actually forecasts something")
    assertEquals(3, preview.movedCount)
    assertEquals(1, preview.newlyMissed.size)

    assertEquals(before, fingerprint(plan),
      "the preview computes twice and writes NOTHING. Anything different here means it did not " +
        "forecast the holiday, it carried it out")
  }

  /** The same demand for the quiet case: a holiday that costs nothing may not cost anything either. */
  @Test
  fun `a preview that forecasts nothing changes nothing either`() {
    val (plan, natalie, _) = buildPlan()
    natalie.addDaysOff(GanttDaysOff(LocalDate.of(2027, 6, 7).toModelDate(),
      LocalDate.of(2027, 6, 14).toModelDate()))
    val before = fingerprint(plan)

    assertEquals(true, previewOf(plan, natalie).required().changesNothing)

    assertEquals(before, fingerprint(plan))
  }

  /**
   * Asked twice, the preview answers the same thing.
   *
   * This is the other shape the trap takes: an implementation that writes the hypothetical state in
   * and takes it out again can leave the model subtly different — an interval merged, a duration
   * recomputed — and the SECOND run then measures against a plan the first one moved. The
   * fingerprint above would catch most of that; this catches the rest, because it compares the
   * ANSWERS rather than the model.
   */
  @Test
  fun `the same question asked twice gives the same answer`() {
    val (plan, natalie, _) = buildPlan()
    natalie.addDaysOff(GanttDaysOff(absenceWeek.toModelDate(),
      absenceWeek.plusDays(7).toModelDate()))

    val first = previewOf(plan, natalie).required()
    val second = previewOf(plan, natalie).required()

    assertEquals(previewText(first), previewText(second))
    assertEquals(first, second)
  }

  /**
   * THE PREVIEW DOES NOT SO MUCH AS REPLACE A DAY-OFF OBJECT.
   *
   * A tighter demand than "nothing is different afterwards", and deliberately so: see
   * [daysOffIdentities]. An implementation that writes the hypothetical state in and takes it out
   * again passes every other check in this file and fails this one.
   */
  @Test
  fun `the preview does not even replace a day-off entry with an equal one`() {
    val (plan, natalie, _) = buildPlan()
    natalie.addDaysOff(GanttDaysOff(absenceWeek.toModelDate(),
      absenceWeek.plusDays(7).toModelDate()))
    val before = daysOffIdentities(plan)

    assertEquals(7L, previewOf(plan, natalie).required().projectEndShift,
      "precondition: this run has to forecast something")

    assertEquals(before, daysOffIdentities(plan),
      "the days off were not merely restored, they were never touched. An implementation that " +
        "writes the hypothetical state into the model and takes it out again leaves the dates " +
        "equal and the objects new -- and is one thrown exception away from leaving the holiday " +
        "in the plan")
  }

  /**
   * THE POSITIVE CONTROL, and it belongs in this file rather than beside it.
   *
   * Every check above says "the fingerprint did not change". A fingerprint that read nothing would
   * satisfy all of them. So one run here writes the levelled dates back for real and demands that
   * the fingerprint NOTICE — the same plan, the same absence, through the same door the preview
   * deliberately does not use.
   */
  @Test
  fun `the fingerprint sees a plan that really did move`() {
    val (plan, natalie, _) = buildPlan()
    natalie.addDaysOff(GanttDaysOff(absenceWeek.toModelDate(),
      absenceWeek.plusDays(7).toModelDate()))
    val before = fingerprint(plan)

    val tasks = collectLevelTasks(plan.taskManager, plan.taskProperties, plan.resourceProperties,
      start, moveUnstartedPast = true)
    val result = levelTasks(tasks, start, workingDaysPerTask(plan.taskManager, plan.resourceProperties),
      durationAtStart(plan.taskManager, plan.taskProperties, plan.resourceProperties),
      isAvailable = availabilityTest(plan.resourceManager),
      isAtWorkplace = presenceTest(plan.resourceManager, plan.resourceProperties))
    val written = applyLevellingAsSingleEdit(result.starts, plan.taskManager,
      ImmediateUndoManager(), "levelled", result.durations,
      notifier = LevellingRunNotifier(), resourceProperties = plan.resourceProperties)

    assertNotEquals(0, written, "precondition: this write-back has to move something")
    assertNotEquals(before, fingerprint(plan),
      "if the fingerprint cannot see a plan that really moved, it cannot see one the preview " +
        "moved either, and every other check in this file is worthless")
  }
}
