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
import net.sourceforge.ganttproject.task.dependency.TaskDependency
import net.sourceforge.ganttproject.task.dependency.constraint.FinishStartConstraintImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * THE THREE LINES, ON A PLAN WHOSE ANSWER IS KNOWN BEFORE THE PROGRAM IS ASKED.
 *
 * The plan: five tasks of five working days each, chained one after another, all of them the work
 * of one person who is indispensable on every one of them. January 2027 has no public holidays in
 * the weekend calendar these checks use, so the dates can be written out by hand:
 *
 *   T1  Mon 04.01. - Fri 08.01.
 *   T2  Mon 11.01. - Fri 15.01.
 *   T3  Mon 18.01. - Fri 22.01.
 *   T4  Mon 25.01. - Fri 29.01.
 *   T5  Mon 01.02. - Fri 05.02.      <- the project end
 *
 * The absence: that one person is away for the whole of the week of 18.01. T3 can then no longer
 * be laid there, and everything from T3 on moves one week:
 *
 *   T3  Mon 25.01. - Fri 29.01.
 *   T4  Mon 01.02. - Fri 05.02.
 *   T5  Mon 08.02. - Fri 12.02.      <- the project end, seven calendar days later
 *
 * So the answer, written out before anything ran: end 05.02.2027 -> 12.02.2027, three of five
 * tasks moved, every one of them by exactly seven calendar days.
 */
class VacationPreviewTest {

  init {
    // GanttDaysOff builds its dates through CalendarFactory, which has to be woken up first.
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

  private class Plan {
    val builder: TestSetupHelper.TaskManagerBuilder =
      TestSetupHelper.newTaskManagerBuilder().withCalendar(WeekendCalendarImpl())
    val taskManager: TaskManager = builder.build()
    val resourceManager: HumanResourceManager = builder.resourceManager
    val resourceProperties: CustomPropertyManager get() = resourceManager.customPropertyManager
    val taskProperties: CustomPropertyManager get() = taskManager.customPropertyManager

    fun person(name: String): HumanResource =
      resourceManager.create(name, 0).also {
        it.setValue(EffortDrivenProperties.findOrCreateResourceHours(resourceProperties), 8.0)
      }

    fun task(name: String, on: LocalDate, hours: Double, days: Long): Task =
      taskManager.newTaskBuilder().withName(name).withStartDate(on.toModelDate())
        .withDuration(taskManager.createLength(days)).build().also {
          it.customValues.setValue(
            EffortDrivenProperties.findOrCreateTaskEffort(taskProperties), hours)
        }

    fun after(predecessor: Task, successor: Task): TaskDependency =
      taskManager.dependencyCollection.createDependency(successor, predecessor,
        FinishStartConstraintImpl())
  }

  private fun Task.doneBy(person: HumanResource) {
    this.assignmentCollection.addAssignment(person).apply {
      load = 100f
      isBlocking = true
    }
  }

  private fun HumanResource.awayFor(from: LocalDate, workingDays: Long) {
    // The end of an interval is EXCLUSIVE -- see DaysOffDuration.kt. Monday plus seven calendar
    // days is the Monday after, so this is exactly the working week from `from` on.
    this.addDaysOff(GanttDaysOff(from.toModelDate(), from.plusDays(workingDays + 2).toModelDate()))
  }

  /** The plan above, with the person already carrying the absence. */
  private fun buildPlan(): Triple<Plan, HumanResource, List<Task>> {
    val plan = Plan()
    val natalie = plan.person("Natalie")
    val tasks = (0 until 5).map { i ->
      plan.task("T${i + 1}", start.plusDays(7L * i), hours = 40.0, days = 5L)
        .also { it.doneBy(natalie) }
    }
    tasks.zipWithNext().forEach { (before, after) -> plan.after(before, after) }
    return Triple(plan, natalie, tasks)
  }

  /**
   * The preview of "Natalie is away the week of 18.01." against the plan without it.
   *
   * `before` is the state WITHOUT the absence and `after` is the model as it stands -- which is the
   * direction the running program uses: the days off are saved first, and the hypothetical state is
   * the one that has been left behind.
   */
  /**
   * A forecast, or a failed check. JUnit's own assertNotNull hands back nothing, so it cannot be
   * used as an expression here.
   */
  private fun VacationPreview?.required(): VacationPreview =
    this ?: throw AssertionError("a forecast has to be possible on this plan")

  private fun previewOf(plan: Plan, person: HumanResource): VacationPreview? = vacationPreview(
    plan.taskManager, plan.resourceManager, plan.taskProperties, plan.resourceProperties,
    before = daysOffReplacedFor(person, emptyList()),
    after = daysOffAsEntered,
    today = start)

  @Test
  fun `line one names the project end before and after, and it is one week later`() {
    val (plan, natalie, _) = buildPlan()
    natalie.awayFor(absenceWeek, workingDays = 5)

    val preview = previewOf(plan, natalie).required()

    assertEquals(LocalDate.of(2027, 2, 5), preview.projectEndBefore,
      "five chained tasks of five working days from Mon 04.01. end on Fri 05.02.")
    assertEquals(LocalDate.of(2027, 2, 12), preview.projectEndAfter,
      "with the week of 18.01. gone, everything from T3 on moves one week")
    assertEquals(7L, preview.projectEndShift)
  }

  @Test
  fun `line three is one sentence because every moved task moves by the same amount`() {
    val (plan, natalie, _) = buildPlan()
    natalie.awayFor(absenceWeek, workingDays = 5)

    val preview = previewOf(plan, natalie).required()

    assertEquals(5, preview.taskCount)
    assertEquals(3, preview.movedCount, "T3, T4 and T5 move; T1 and T2 lie before the absence")
    assertEquals(listOf(7L, 7L, 7L), preview.shifts)
    assertTrue(preview.uniformShift,
      "smallest equals largest, so the sentence may name one figure")
    assertFalse(preview.changesNothing)
  }

  /**
   * THE CHECK THAT IS EASIEST TO BUILD WRONG: only deadlines that HELD BEFORE and are missed
   * AFTER belong to this absence.
   *
   * Three deadlines are entered:
   *  * on T1, for 01.01.2027 -- already missed before the absence and missed after it. It is a
   *    statement about the plan, not about the holiday, and must NOT be counted.
   *  * on T2, for 15.01.2027 -- its own end, held before and after. T2 lies before the absence.
   *  * on T5, for 05.02.2027 -- its end without the absence. Held before, missed after. THE one.
   *
   * An implementation that reports "the deadlines missed afterwards" answers 2 here. One that
   * reports the difference of the two counts answers 1 for the wrong reason and would answer 0 on a
   * plan where one deadline starts holding as another starts breaking.
   */
  @Test
  fun `line two counts only the deadlines this absence breaks`() {
    val (plan, natalie, tasks) = buildPlan()
    tasks[0].setDeadline(plan.taskProperties, LocalDate.of(2027, 1, 1))
    tasks[1].setDeadline(plan.taskProperties, LocalDate.of(2027, 1, 15))
    tasks[4].setDeadline(plan.taskProperties, LocalDate.of(2027, 2, 5))
    natalie.awayFor(absenceWeek, workingDays = 5)

    val preview = previewOf(plan, natalie).required()

    assertEquals(3, preview.deadlinesInPlan)
    assertEquals(listOf("T5"), preview.newlyMissed.map { it.name },
      "T1 was already late before the absence and T2 is not touched by it")
    assertEquals(1, preview.alreadyMissedBefore,
      "T1's deadline is missed in both runs and is kept apart rather than dropped")
    assertEquals(LocalDate.of(2027, 2, 5), preview.newlyMissed.single().deadline)
    assertEquals(LocalDate.of(2027, 2, 12), preview.newlyMissed.single().actualEnd)
    assertEquals(5, preview.newlyMissed.single().missingDays,
      "one working week too late")
  }

  @Test
  fun `the text names all three lines`() {
    val (plan, natalie, tasks) = buildPlan()
    tasks[4].setDeadline(plan.taskProperties, LocalDate.of(2027, 2, 5))
    natalie.awayFor(absenceWeek, workingDays = 5)

    val text = previewText(previewOf(plan, natalie).required())

    assertTrue(text.contains("2027-02-05") && text.contains("2027-02-12"),
      "line 1 has to name both project ends, found:\n$text")
    assertTrue(text.contains("T5"), "line 2 has to name the task that misses its deadline:\n$text")
    assertTrue(text.contains("3") && text.contains("5"),
      "line 3 has to name three of five:\n$text")
    assertFalse(text.contains("T3") || text.contains("T4"),
      "the moved tasks are NOT listed -- that is the whole point of line 3 being a line:\n$text")
  }

  /**
   * THE MOST FREQUENT CASE, AND THE ONE A PREVIEW GOES WRONG ON: an absence that costs nothing
   * says so in one sentence and shows no figures at all.
   *
   * The holiday is laid after the last task is finished. Nothing can move.
   */
  @Test
  fun `an absence that moves nothing says so without a single figure`() {
    val (plan, natalie, _) = buildPlan()
    natalie.awayFor(LocalDate.of(2027, 6, 7), workingDays = 5)

    val preview = previewOf(plan, natalie).required()

    assertTrue(preview.changesNothing, "the holiday lies after the whole plan")
    assertEquals(0, preview.movedCount)
    assertEquals(0L, preview.projectEndShift)

    val text = previewText(preview)
    assertEquals(forkText("fork.vacation.preview.nothing"), text,
      "one sentence and nothing else -- found:\n$text")
    assertFalse(text.any { it.isDigit() }, "not a single figure in it, found:\n$text")
  }

  /**
   * A PLAN WITHOUT DEADLINES DOES NOT SHOW LINE 2 AT ALL, and that is a decision rather than an
   * omission. "0 deadlines are missed" on a plan that has none reads as "your dates are safe" about
   * dates nobody ever entered. Where deadlines exist, the zero IS a finding and is said out loud --
   * the second half of this check.
   */
  @Test
  fun `line two is absent without deadlines and present with them`() {
    val (bare, bareNatalie, _) = buildPlan()
    bareNatalie.awayFor(absenceWeek, workingDays = 5)
    val bareText = previewText(previewOf(bare, bareNatalie).required())
    assertEquals(0, previewOf(bare, bareNatalie).required().deadlinesInPlan)
    assertFalse(bareText.contains(forkText("fork.vacation.preview.deadline.none", 1).take(20)),
      "no deadline line on a plan without deadlines, found:\n$bareText")

    val (withOne, itsNatalie, tasks) = buildPlan()
    // On T2, which lies before the absence: it carries a deadline and keeps it.
    tasks[1].setDeadline(withOne.taskProperties, LocalDate.of(2027, 1, 15))
    itsNatalie.awayFor(absenceWeek, workingDays = 5)
    val withText = previewText(previewOf(withOne, itsNatalie).required())
    assertTrue(withText.contains(forkText("fork.vacation.preview.deadline.none", 1)),
      "a plan WITH deadlines and none broken says so, found:\n$withText")
  }

  /**
   * THE OTHER HALF OF WHAT AN ABSENCE DOES, AND IT IS THE COMMONER HALF.
   *
   * Every check above marks its assignment as indispensable, and then a holiday MOVES the task. An
   * assignment that carries no such marking — which is every assignment in every plan written
   * before this fork — behaves differently: the person's hours drop out of the day, the day stays,
   * and the task GETS LONGER where it is. `DaysOffDuration.kt` says so; this is the check that the
   * preview sees it.
   *
   * The plan is built so that every other number stays at zero: one task of Natalie's that the
   * holiday lengthens, and one long task nobody is assigned to that ends later than either version
   * of the first and therefore fixes the project end. Without [VacationPreview.stretchedIds] the
   * forecast would be "this absence moves nothing" about a task that doubled in length.
   */
  @Test
  fun `an absence on an unmarked assignment lengthens the task instead of moving it`() {
    val plan = Plan()
    val natalie = plan.person("Natalie")
    // NOT `doneBy`: no isBlocking. That is the state of every assignment written before this fork.
    val stretched = plan.task("Stretched", start, hours = 40.0, days = 5L)
    stretched.assignmentCollection.addAssignment(natalie).apply { load = 100f }
    // Nobody is assigned to this one, so it sits in the shared pool and no holiday touches it. It
    // is long enough to be the project end both before and after.
    plan.task("Long", start, hours = 0.0, days = 60L)
    natalie.awayFor(start, workingDays = 5)

    val preview = previewOf(plan, natalie).required()

    assertEquals(emptyList<String>(), preview.movedIds,
      "no task starts on a different day -- the holiday covers the first task's own week")
    assertEquals(0L, preview.projectEndShift, "the long unassigned task fixes the project end")
    assertEquals(1, preview.stretchedIds.size,
      "the first task needs five more days for the same 40 hours")
    assertFalse(preview.changesNothing,
      "a task that doubled in length is not nothing. Without stretchedIds this forecast would " +
        "say \"this absence moves nothing\"")
    val text = previewText(preview)
    assertTrue(text.contains(forkText("fork.vacation.preview.longer", 1)),
      "the sentence about tasks getting longer has to be in the text, found:\n$text")
  }

  /**
   * THE CHOICE FORMAT OF THE AMOUNT, PINNED IN BOTH LANGUAGES.
   *
   * `fork.vacation.preview.amount` has four ascending limits — 1, just above 1, 7, just above 7 — so that
   * exactly seven calendar days read as "one week", which is how anybody entering a week off reads
   * it back. Put the limits in the wrong order and MessageFormat does not throw: it silently
   * answers the wrong branch, and a holiday of three days would be reported as a week. That is
   * unreadable in the properties file and invisible on screen until somebody counts.
   *
   * Asked through [ForkI18n.textOrNull] with an explicit locale rather than through [forkText],
   * because the default locale of the machine running the checks is not this file's business.
   */
  @Test
  fun `the amount phrase says one week for exactly seven, in both languages`() {
    val german = Locale.GERMANY
    val english = Locale.US
    assertEquals("einen Kalendertag", ForkI18n.textOrNull("fork.vacation.preview.amount", german, 1))
    assertEquals("3 Kalendertage", ForkI18n.textOrNull("fork.vacation.preview.amount", german, 3))
    assertEquals("eine Woche", ForkI18n.textOrNull("fork.vacation.preview.amount", german, 7))
    assertEquals("14 Kalendertage", ForkI18n.textOrNull("fork.vacation.preview.amount", german, 14))
    assertEquals("one calendar day", ForkI18n.textOrNull("fork.vacation.preview.amount", english, 1))
    assertEquals("3 calendar days", ForkI18n.textOrNull("fork.vacation.preview.amount", english, 3))
    assertEquals("one week", ForkI18n.textOrNull("fork.vacation.preview.amount", english, 7))
    assertEquals("14 calendar days", ForkI18n.textOrNull("fork.vacation.preview.amount", english, 14))
  }

  /**
   * THE GERMAN WORDING OF ALL THREE LINES, PINNED.
   *
   * The three lines are ASSEMBLED — an amount phrase inside a direction phrase inside a sentence —
   * so that a translation can reorder them. Assembly is also how a sentence comes out grammatically
   * wrong while every piece of it is right, and nothing on the way to the screen would notice. The
   * German text is the one that reaches Natalie, so it is the one written out here.
   */
  @Test
  fun `the german wording of the three lines is what it is meant to be`() {
    val german = Locale.GERMANY
    val week = ForkI18n.textOrNull("fork.vacation.preview.amount", german, 7)
    val back = ForkI18n.textOrNull("fork.vacation.preview.later", german, week!!)
    assertEquals("eine Woche nach hinten", back)
    assertEquals("Projektende: 2027-02-05 -> 2027-02-12 (eine Woche nach hinten).",
      ForkI18n.textOrNull("fork.vacation.preview.end.changed", german,
        LocalDate.of(2027, 2, 5), LocalDate.of(2027, 2, 12), back!!))
    assertEquals("3 von 5 Vorgaengen ruecken eine Woche nach hinten - jeder einzelne um genau " +
      "diesen Betrag.", ForkI18n.textOrNull("fork.vacation.preview.shift.uniform", german, 3, 5, back))
    assertEquals("Eine Frist reisst dadurch, und vorher war sie zu halten:",
      ForkI18n.textOrNull("fork.vacation.preview.deadline.broken", german, 1))
    assertEquals("Dadurch reisst keine Frist (2 Vorgaenge tragen eine).",
      ForkI18n.textOrNull("fork.vacation.preview.deadline.none", german, 2))
    assertEquals("Diese Ausfallzeit verschiebt nichts: kein Vorgang rueckt, und das Projektende " +
      "bleibt stehen.", ForkI18n.textOrNull("fork.vacation.preview.nothing", german))
  }

  /** Nothing to level means no forecast, and no forecast is said with a null rather than a zero. */
  @Test
  fun `an empty plan yields no forecast at all`() {
    val plan = Plan()
    val nobody = plan.person("Natalie")
    assertNull(vacationPreview(plan.taskManager, plan.resourceManager, plan.taskProperties,
      plan.resourceProperties, before = daysOffReplacedFor(nobody, emptyList()),
      after = daysOffAsEntered, today = start))
  }
}
