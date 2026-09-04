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

import biz.ganttproject.core.calendar.GPCalendar
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.task.ResourceAssignment
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.algorithm.contributesEffort
import java.time.LocalDate

/**
 * [fork change] Where the working week of [WorkWeekSchedule] meets `isWorkingDay`.
 *
 * A1 built the model and the storage and deliberately wired it to nothing: „the place where the
 * answer meets `isWorkingDay` is a separate piece of work". This is that place.
 *
 * UNTIL NOW ONE TEST APPLIED TO EVERYBODY -- `workingDayTest(taskManager.calendar)`, built once
 * per pass and handed to every task. Whoever works Mon, Tue, Fri, Sat was planned by it as though
 * they worked Mon to Fri. From here the test is built PER TASK, out of the working weeks of the
 * people on it.
 *
 * THE RULE, in Natalie's words: „an sich nur einschraenken, ausser alle beteiligten arbeiten an zb
 * einem Samstag, dann wird der Vorgang erweitert."
 *
 * That reads like two rules and is one. Give every involved person an availability for the day,
 *
 *     available(person, day) = person.worksOn(day) ?: projectCalendar(day)
 *
 * -- their own answer where they have given one, the project calendar where they have not -- and
 * let the task use the day exactly when EVERY involved person is available. Both halves of the
 * sentence then fall out of the single rule instead of being coded twice:
 *
 *  * The project calendar says Wednesday is a working day, one person's week leaves Wednesday
 *    out: that person is unavailable, the task does not use Wednesday. RESTRICTION.
 *  * The project calendar says Saturday is free, every involved person's week names Saturday:
 *    everybody is available, the task uses Saturday. EXTENSION.
 *  * Somebody has entered nothing: their answer is `null` everywhere, they fall back to the
 *    project calendar everywhere, and they can therefore never be the one who tips the result.
 *    NOTHING ENTERED CHANGES NOTHING -- see [WorkWeekWorkingDays] for how that is guarded.
 *
 * WHY „ALL" AND NOT „SOMEBODY": the asymmetry is deliberate and it is Natalie's. A day on which
 * only half the people can work is not half a day of the task -- the fork has no notion of a task
 * running at partial strength on the day grid. The conservative reading is the one that never
 * invents availability, and „all" is that reading in both directions: it takes a day away as soon
 * as one person is missing, and it grants a free day only when nobody is.
 *
 * NOTE, and it is a real tension: [durationDaysWithDaysOff] treats an individual absence the other
 * way round -- a day off of one person removes THAT PERSON'S HOURS from the day and leaves the day
 * standing („five days of work with one day off in the middle is six days long"). A non-working
 * weekday under the rule here removes the DAY. The two are not the same question -- a holiday is
 * an exception inside a working week, a working week is the grid itself -- but the difference is
 * visible in the result and is written up in the report rather than smoothed over here.
 *
 * HOLIDAYS ARE NOT TOUCHED, and that is a boundary of this stage, not an oversight. The extension
 * half of the rule applies to days that the project calendar leaves free BECAUSE OF THE WEEKDAY.
 * A public holiday stays free even when every involved person's week names that weekday. Whether
 * somebody should be able to work through a holiday is its own switch and its own stage; see
 * [projectDayIsFreeForWeekdayReasons] for how the two are told apart.
 */

/**
 * Is [day] free in the project calendar FOR WEEKDAY REASONS -- that is, is it a candidate for the
 * extension half of the rule?
 *
 * MEASURED, not assumed. `WeekendCalendarImpl.getDayMask` builds its answer like this:
 *
 *     if (isWeekend)  { result |= WEEKEND;  if (one-off WORKING_DAY) result |= WORKING }
 *     if (isHoliday)  { result |= HOLIDAY;  return result }          // <- returns without WORKING
 *     if (!isWeekend || myOnlyShowWeekends) result |= WORKING
 *
 * so `WORKING` is absent only when the day is a weekend without a working-day exception, or when
 * it is a holiday. Therefore `no WORKING and no HOLIDAY` implies weekend, and that is the whole
 * test. The two do separate cleanly and nothing had to be forced -- the question the task asked me
 * to check before building anything.
 *
 * The one combination that is neither: a weekend day that carries a one-off WORKING_DAY event AND
 * is a holiday comes back as `WEEKEND|WORKING|HOLIDAY`. It has `WORKING`, so the project calendar
 * already says yes and the extension half is never consulted for it.
 */
internal fun projectDayIsFreeForWeekdayReasons(mask: Int): Boolean =
  mask and GPCalendar.DayMask.WORKING == 0 && mask and GPCalendar.DayMask.HOLIDAY == 0

/**
 * The rule itself, on the working weeks of the people involved.
 *
 * Pure calculation -- no GanttProject types, checkable without a running program, in the manner of
 * [WorkWeekSchedule] and [CapacitySchedule].
 *
 * @param projectWorking what the project calendar says about [day].
 * @param projectFreeForWeekdayReasons whether a `false` in [projectWorking] comes from the weekday
 * and not from a holiday. Only then may the day be won back.
 * @param involved the working weeks of the people the task depends on for this day; see
 * [isInvolvedInWorkWeek]. An EMPTY list means the project calendar decides alone -- that case is
 * settled by the caller and is written down in [WorkWeekWorkingDays].
 */
fun taskWorksOn(
  day: LocalDate,
  projectWorking: Boolean,
  projectFreeForWeekdayReasons: Boolean,
  involved: List<WorkWeekSchedule>
): Boolean {
  // A TASK WITHOUT ANY INVOLVED PERSON FALLS BACK TO THE PROJECT CALENDAR -- decided, not
  // derived. `all` over an empty list is TRUE, so without this line an unassigned task would be
  // "everybody works" on every day and would come to lie on a Sunday.
  if (involved.isEmpty()) {
    return projectWorking
  }
  // available(person, day) = their own answer where they gave one, the project calendar where
  // they did not -- and the task uses the day exactly when EVERY involved person is available.
  // Both halves of Natalie's sentence come out of this one line; see the file comment.
  //
  // Note what `?: projectWorking` does on a day the project calendar calls FREE: it becomes
  // `?: false`, so somebody who has entered nothing does not silently consent to the Saturday.
  // Nothing entered is not agreement, and it is not refusal either -- it is the project calendar,
  // which for that day says free.
  val everybodyAvailable = involved.all { it.worksOn(day) ?: projectWorking }
  return if (projectWorking) {
    everybodyAvailable
  } else {
    // Winning a free day back is the EXTENSION half, and it applies to weekdays only. A public
    // holiday stays free however the weeks read -- that is package A4 and its own switch.
    everybodyAvailable && projectFreeForWeekdayReasons
  }
}

/**
 * Is this assignment one of the „involved" whose working week may decide the day?
 *
 * NOT SIMPLY EVERY ASSIGNMENT. The fork carries two independent axes on an assignment, and they
 * were built for exactly this kind of question:
 *
 *  * AXIS B, [contributesEffort] (`!isNoEffort`): does this person's time count towards the work?
 *    [durationDaysWithDaysOff] and [EffortDrivenDurationAlgorithm] filter by it -- somebody marked
 *    „no effort" contributes no hours and their holiday changes no duration.
 *  * AXIS A, [ResourceAssignment.isBlocking]: does this person's ABSENCE stop the task?
 *    `collectLevelTasks` filters by it to build `LevelTask.blocking` -- the person who has to be
 *    present without working, the acceptance, the supervision.
 *
 * THE UNION OF THE TWO is the right set here, and each half earns its place:
 *
 *  * An effort contributor who does not work on Saturday means the task gets nothing out of that
 *    Saturday. Axis A alone would miss them -- and would miss nearly everybody, because
 *    `isBlocking` defaults to `false`, so in every plan that exists today the blocking set is
 *    EMPTY. A rule built on axis A alone would be dead until somebody ticked a box.
 *  * A blocking non-contributor -- the supervisor who must be there and books no hours -- stops
 *    the task by being away. Axis B alone would miss them, and the day would be planned as if
 *    their presence were not required after all.
 *
 * WHAT THE UNION LEAVES OUT is a person who is on the task with axis B set and axis A clear: they
 * contribute no hours and their absence does not stop anything. They are on the task to be named
 * in a report. Letting them veto a Saturday everybody else works would be the „still kippt" case
 * in the other direction -- a bystander silently shortening the plan.
 */
val ResourceAssignment.isInvolvedInWorkWeek: Boolean
  get() = this.contributesEffort || this.isBlocking

/**
 * Builds the per-task `isWorkingDay` and remembers what it has read.
 *
 * READ ONCE, NOT PER QUESTION -- the same reasoning `availabilityTest` records a few hundred lines
 * up in [LevellingAdapter]. The day-by-day walks run over up to 50 000 days per task, and
 * [HumanResource.workWeek] PARSES TEXT on every call. Reading it per day would turn levelling into
 * a waiting game. The working weeks are therefore read here, once per person, and the returned
 * function only looks things up. The price is that it does not see later changes; it is built anew
 * for each pass, which is exactly its lifetime.
 *
 * THE NOTHING-ENTERED FAST PATH is not only an optimisation, it is the guarantee written down. A
 * task whose involved people have all entered nothing gets back the very same function object the
 * program used before this file existed, [workingDayTest] over the project calendar. Not an
 * equivalent one -- the same one. There is then no code path on which a working week could change
 * such a plan, and „nothing entered changes nothing" is a property of the wiring rather than a
 * hope about the arithmetic.
 */
class WorkWeekWorkingDays(
  private val calendar: GPCalendar,
  private val resourceProperties: CustomPropertyManager
) {
  /** The project calendar alone -- what every task used before this file existed. */
  private val projectOnly: (LocalDate) -> Boolean = workingDayTest(calendar)

  /**
   * [fork change] The same object [forTask] hands back to a task with nothing entered.
   *
   * READABLE FROM OUTSIDE, and only for its IDENTITY. A caller that has no `Task` at all -- an id
   * whose task has gone away between the conversion and the calculation, see
   * `LevellingAdapter.workingDaysPerTask` -- needs the project calendar as its answer. Building a
   * second `workingDayTest(calendar)` for that would answer the same and be a DIFFERENT object,
   * and every memory in this fork that keys day answers by the test would open a map of its own
   * for it. The guarantee this class is built on is one of identity, and this is how a caller
   * outside it can keep it.
   */
  val projectCalendarOnly: (LocalDate) -> Boolean get() = projectOnly

  /** Resource id -> working week. Parsed once. */
  private val perResource = mutableMapOf<Int, WorkWeekSchedule>()

  /** Task id -> its test. Built once. */
  private val perTask = mutableMapOf<Int, (LocalDate) -> Boolean>()

  fun forTask(task: Task): (LocalDate) -> Boolean = perTask.getOrPut(task.taskID) {
    // NOT FILTERED BY `isEmpty` here, and that matters. Somebody who has entered nothing has to
    // stay IN the list: on a Saturday their `null` falls back to „the project calendar says free",
    // and the task therefore does not get the Saturday. Dropping them would let one person's
    // Saturday week speak for a colleague who never said anything.
    val involved: List<WorkWeekSchedule> = task.assignments
      .filter { it.isInvolvedInWorkWeek }
      .mapNotNull { it.resource as? HumanResource }
      .map { resource ->
        perResource.getOrPut(resource.id) { resource.workWeek(resourceProperties).schedule }
      }
    // A TASK WITHOUT ANY INVOLVED PERSON FALLS BACK TO THE PROJECT CALENDAR. Decided, not derived:
    // „every involved person works" is vacuously TRUE over an empty set, and the task would lie on
    // a Sunday. An unassigned task is not thereby entitled to the weekend.
    if (involved.isEmpty() || involved.all { it.isEmpty }) {
      projectOnly
    } else {
      { day ->
        val mask = calendar.getDayMask(day.toModelDate())
        taskWorksOn(
          day,
          projectWorking = mask and GPCalendar.DayMask.WORKING != 0,
          projectFreeForWeekdayReasons = projectDayIsFreeForWeekdayReasons(mask),
          involved = involved)
      }
    }
  }
}
