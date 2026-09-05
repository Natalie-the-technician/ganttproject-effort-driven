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
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.algorithm.capacitySchedule
import net.sourceforge.ganttproject.task.algorithm.contributesEffort
import net.sourceforge.ganttproject.task.algorithm.effortHours
import java.time.LocalDate

/**
 * [fork change] Duration derived from effort, daily availability AND the days off of the assigned
 * people.
 *
 * The difference from [net.sourceforge.ganttproject.task.algorithm.computeDurationDays] is that a
 * day off still occupies a day of the task but contributes no hours. Five days of work with one
 * day off in the middle is six days long, not five.
 *
 * [fork change] AXIS A REACHES IN HERE AS WELL, since 03.09.2026: on a day on which somebody
 * marked `isBlocking` is away, NOBODY contributes to this task. The hours of everybody else go
 * back into their own pot instead of being spent on work that cannot proceed without the missing
 * person. Such a day still occupies a day of the task, exactly as any other day off does.
 *
 * THE BOUNDARY MATTERS MORE THAN THE RULE. This holds for `isBlocking` and for nothing else: the
 * ordinary holiday of a person who is not indispensable works as it always did -- their hours drop
 * out, the day stays, the task grows -- and the sentence about the six days above is unchanged by
 * it. Were the two lumped together, every holiday would stop every task.
 *
 * SAFE FOR EVERY PLAN THAT EXISTS TODAY: `isBlocking` defaults to `false` and no file written
 * before this fork carries the attribute, so the blocking set is empty and the rule never fires.
 * Measured, together with the boundary, in `BlockingAbsenceDurationTest`.
 *
 * THE END OF AN INTERVAL IS EXCLUSIVE. This is not a decision taken here, it is what the
 * surrounding code does, measured in four independent places:
 *
 *  * `DateInterval.createFromVisibleDates` stores `adjustRight(lastVisibleDay)`, and
 *    `GanttDialogPerson` hands exactly that to `GanttDaysOff` — so the dialog's "27 ... 28"
 *    becomes `GanttDaysOff(27, 29)`.
 *  * `DateInterval.createFromModelDates` reads it back with `jumpLeft(end)`, so the round trip
 *    through the dialog is stable.
 *  * `ProjectFileImporterImpl` builds a single day off as `GanttDaysOff(day, adjustRight(day))`.
 *  * `LoadDistribution.processDaysOff` turns the interval into a `Load(start, finish)`, the same
 *    half-open shape it uses for task activities.
 *
 * `GanttDaysOff.isADayOff` reads the end INCLUSIVELY and contradicts all four — but it has no
 * caller anywhere in the program, so it settles nothing. That contradiction is finding F24 and is
 * deliberately not fixed here.
 */

/** The days off of this person as half-open [start, endExclusive) ranges. */
fun HumanResource.daysOffRanges(): List<Pair<LocalDate, LocalDate>> {
  val list = this.daysOff
  return (0 until list.size).mapNotNull { i ->
    val interval = list.getElementAt(i) ?: return@mapNotNull null
    interval.start.time.toModelLocalDate() to interval.finish.time.toModelLocalDate()
  }
}

private fun List<Pair<LocalDate, LocalDate>>.covers(day: LocalDate): Boolean =
  this.any { (from, toExclusive) -> !day.isBefore(from) && day.isBefore(toExclusive) }

/**
 * [fork change] How the calculation is to SEE somebody's days off -- which need not be what the
 * model holds.
 *
 * WHY THIS EXISTS AT ALL. A preview has to answer „what would THIS holiday do", and that is a
 * question about a state the plan is not in. There are two ways to ask it: write the holiday into
 * the model, compute, write it back -- or hand the calculation a different answer to the one
 * question it asks. This is the second, and the choice is not a matter of taste. The first one is
 * one thrown exception away from leaving somebody's holidays in the plan changed, and the check
 * that would notice is exactly the check that four attempts at this preview have got wrong. A
 * calculation that cannot write cannot leave anything behind.
 *
 * IT IS THE ONE READER, AND THAT IS WHAT MAKES IT SUFFICIENT. [HumanResource.daysOffRanges] feeds
 * three consumers with three meanings -- `Share.daysOff` (the hours go), [EffortInputs.blockingDaysOff]
 * (the whole day is dead) and `LevellingAdapter.availabilityTest` (the window search) -- and all
 * three reach it through this interface once the callers pass one on. A preview that overlaid only
 * the window search would move the task and not lengthen it: right in the small and wrong in the
 * answer.
 *
 * THE DEFAULT IS [daysOffAsEntered] EVERYWHERE, so every call site written before this type keeps
 * its behaviour and its shape. That is deliberate and it has the usual price: leaving the argument
 * out compiles. The place where that would be silent is the preview itself, and
 * `VacationPreviewNoChangeTest` drives it rather than the functions below.
 */
fun interface DaysOffView {
  fun rangesOf(resource: HumanResource): List<Pair<LocalDate, LocalDate>>
}

/** The days off as they stand in the plan -- what every reader did before [DaysOffView] existed. */
val daysOffAsEntered = DaysOffView { it.daysOffRanges() }

/**
 * [ranges] instead of what [resource] carries; everybody else exactly as entered.
 *
 * BY OBJECT IDENTITY AND NOT BY ID, and that is not fussiness: a person the dialog has just created
 * still carries the id -1 (`GanttDialogPerson.okButtonActionPerformed` says so in as many words),
 * so two fresh people would compare equal. The instance is the same one throughout -- the resource
 * manager hands out what it stores -- so identity is both correct and cheaper.
 */
fun daysOffReplacedFor(
  resource: HumanResource, ranges: List<Pair<LocalDate, LocalDate>>
): DaysOffView = DaysOffView { if (it === resource) ranges else it.daysOffRanges() }

/**
 * One assignment, prepared for the day-by-day walk.
 *
 * [fork change] INTERNAL RATHER THAN PRIVATE since 03.09.2026, and that is not a loosening for
 * its own sake: levelling asks for the duration of the same task once per candidate day, and
 * building this list anew every time would read every person's days off out of the model tens of
 * thousands of times per run. `LevellingAdapter.durationAtStart` therefore builds it ONCE per
 * task and per run and hands it to [daysNeededWithDaysOff]. See there for the measurement.
 */
internal class Share(
  val schedule: CapacitySchedule,
  val load: Double,
  val daysOff: List<Pair<LocalDate, LocalDate>>,
  /**
   * [fork change] B3: this person's home office, AND ONLY WHEN THIS TASK NEEDS SOMEBODY ON SITE.
   *
   * `null` in every other case, and the condition is applied WHERE THIS IS BUILT rather than where
   * it is read -- see [Task.effortInputs]. A share that carries `null` costs one reference
   * comparison per working day, which is what every plan that exists today pays.
   *
   * WHY IT IS HERE AT ALL, and this is decision E1 of 04.09.2026. Somebody who is NOT
   * indispensable and who is at home cannot work on a task that has to be done on the premises --
   * so their hours do not go into THIS task on THAT day. The others carry on: that is the half of
   * Natalie's sentence that says „kann der Rest ohne sie weiterarbeiten", and it is why this sits
   * in the share and not in [EffortInputs.blockingHomeOffice], which stops the whole day.
   *
   * IT IS NOT A DAY OFF. The person is working; their hours are simply spent elsewhere. Nothing
   * here may ever reach [HumanResource.daysOffRanges] -- that list feeds three consumers with
   * three meanings, and a home-working day put into it becomes a holiday in all three.
   */
  val homeOffice: HomeOffice? = null,
) {
  fun hoursOn(day: LocalDate): Double = when {
    daysOff.covers(day) -> 0.0
    // [fork change] E1. Deliberately the same answer as a day off -- for THIS task, on THIS day,
    // and for no other reason: the share is only given a [homeOffice] at all when the task needs
    // somebody on site.
    homeOffice != null && homeOffice.worksFromHome(day) -> 0.0
    else -> schedule.hoursOn(day) * load
  }

  /**
   * The MOST this share can deliver on any one day -- its highest daily rate, days off aside.
   *
   * [fork change] AN UPPER BOUND, not an answer, and that is what it is for: with it the walk
   * below can tell in one multiplication whether the effort is deliverable AT ALL, instead of
   * finding it out by taking ten thousand steps. See there.
   *
   * THE LOAD IS PART OF IT. An assignment at 0 % -- the person who has to be present without
   * working, supervision or an acceptance -- carries a perfectly ordinary daily rate and still
   * contributes nothing. A bound that read the daily rate alone would call that share able to
   * deliver and would have to walk after all, in what is an everyday case rather than an exotic
   * one.
   */
  val bestHoursPerDay: Double
    get() = load * maxOf(schedule.base, schedule.changes.maxOfOrNull { it.hoursPerDay } ?: 0.0)
}

/**
 * [fork change] Everything the day-by-day walk needs out of the model, BOTH AXES TOGETHER.
 *
 * They travel together because they are read from the SAME assignment list and the ORDER of the
 * two readings is the point -- see [Task.effortInputs]. A caller who could take the shares alone
 * would silently lose axis A, and every check on either side would stay green; the type is what
 * stops that.
 */
internal class EffortInputs(
  /** AXIS B: who contributes how many hours, and when are they away. */
  val shares: List<Share>,
  /** AXIS A: whose ABSENCE stops the whole day, flattened into one list of ranges. */
  val blockingDaysOff: List<Pair<LocalDate, LocalDate>>,
  /**
   * [fork change] B3, AXIS A's SECOND HALF: whose HOME WORKING stops the whole day.
   *
   * A SEPARATE LIST AND NOT MORE RANGES IN [blockingDaysOff], and the reason is not tidiness --
   * it is that the two cannot be the same list. A day off is a bounded interval; a home-office
   * arrangement is „every Friday", which has no end and cannot be written down as ranges at all.
   * A [HomeOffice] answers the question instead of listing the days.
   *
   * That the shapes differ is a piece of luck rather than a nuisance: it makes the deadly mistake
   * -- feeding home working into the day-off channel -- impossible to make by accident.
   *
   * EMPTY UNLESS THE TASK NEEDS SOMEBODY ON SITE, and empty in every plan that exists today. The
   * condition is read in [Task.effortInputs] BEFORE anything is collected, so an unmarked task
   * pays one boolean per run and not one model read per person.
   */
  val blockingHomeOffice: List<HomeOffice> = emptyList(),
) {
  /**
   * [fork change] THE ONE PLACE the two halves of axis A are folded together.
   *
   * Named, and deliberately not written out at the call site in the walk: axis A is the rule that
   * makes a day of the task pass without progress, and whoever adds a third reason for that should
   * have one line to change. It is also what keeps the walk's hottest line unchanged in shape --
   * the rule comes in through the DATA, not through a second branch in the loop.
   *
   * ORDER: days off first. It is the question every plan asks and the cheaper of the two, and the
   * second list is empty whenever the task is not marked.
   */
  fun blocksWholeDay(day: LocalDate): Boolean =
    blockingDaysOff.covers(day) || blockingHomeOffice.any { it.worksFromHome(day) }
}

/**
 * [fork change] The inputs of a task: who contributes how many hours, when are they away, and
 * whose absence stops the task outright.
 *
 * READ ONCE, NOT PER QUESTION -- the same rule, and for the same reason, as
 * `LevellingAdapter.availabilityTest`. Everything in here comes out of the model and none of it
 * depends on the day the task starts, so a caller who asks for the duration many times over may
 * and should keep the result.
 *
 * THE TWO AXES ARE READ IN THIS ORDER, AND THE ORDER IS THE POINT. See the two comments in the
 * body: axis A is taken off the UNFILTERED assignment list, before axis B has dropped anybody.
 */
internal fun Task.effortInputs(
  taskProperties: CustomPropertyManager,
  resourceProperties: CustomPropertyManager,
  /**
   * [fork change] Where the days off are read from. The default is the plan itself; a preview
   * hands in a different answer for one person. See [DaysOffView] for why this is threaded through
   * rather than written into the model and taken out again.
   */
  daysOff: DaysOffView = daysOffAsEntered
): EffortInputs {
  // [fork change] B3, CONDITION (a), AND IT IS READ FIRST. Whether this task needs somebody on the
  // premises is a property OF THE TASK, and it decides whether any home office is looked at at
  // all.
  //
  // BEFORE THE COLLECTING AND NOT AS A FILTER AFTERWARDS. Both give the same answer; what differs
  // is the price. `false` is the state of every task in every plan written before this fork, and
  // in that state nothing is read out of the model at all -- one boolean per task and per run
  // instead of one home-office parse per person.
  //
  // THE FOLD IS ASKED, NOT REBUILT: `mayRunOnHomeWorkingDay` is where „nobody has decided" is
  // treated like „can be done from home", and it lives in exactly one place. Comparing the enum
  // by hand here would be a second copy of that decision.
  val requiresPresence = !this.mayRunOnHomeWorkingDay(taskProperties)
  // [fork change] AXIS A: whose ABSENCE takes the whole task with it, as opposed to whose hours
  // it removes. Flattened into ONE list of ranges, because the only question asked of it is
  // "is any of them away on this day" -- two indispensable people away on the same day therefore
  // cost that day once, not twice.
  //
  // "any absent" is the same statement as "all present must be present", and it is the same
  // sentence `ResourceLevelling.findEarliestWindow` already makes about the same set. Until
  // 03.09.2026 the two disagreed: levelling knew axis A and this computation did not.
  //
  // READ BEFORE THE AXIS B FILTER BELOW, AND THAT ORDER IS THE POINT. The person who has to be
  // present without doing any of the work -- supervision, an acceptance, a hand-over -- carries
  // axis B as well, and the filter below drops their assignment whole. Asking axis A afterwards
  // would drop exactly the case that justifies the axis. The two are independent, which is what
  // `ResourceAssignment` says of them.
  //
  // EMPTY IN EVERY PLAN THAT HAS NEVER TICKED THE BOX, and then it costs one empty `any` per
  // working day.
  val blockingDaysOff: List<Pair<LocalDate, LocalDate>> = this.assignments
    .filter { it.isBlocking }
    .mapNotNull { it.resource as? HumanResource }
    .flatMap { daysOff.rangesOf(it) }
  // [fork change] B3, AXIS A's SECOND HALF: the home office of the same people, off the same
  // UNFILTERED assignment list and for the same reason as the line above it.
  //
  // THE ORDER MATTERS MORE HERE THAN IT DOES FOR THE HOLIDAY, and that is worth saying plainly.
  // Consider which tasks ever get marked „needs somebody on site": the acceptance, the hand-over,
  // the appointment at the machine. Their indispensable person is very often the one who
  // contributes NOTHING but their presence -- load 0, `no-effort` set -- and the axis B filter
  // below drops that assignment whole. Collected after the filter, this rule would be dead in its
  // own main case, and a plan without markings would not notice.
  //
  // A PERSON WITH NOTHING ENTERED IS DROPPED HERE rather than answering `false` all day long: the
  // list is walked once per working day, and an empty [HomeOffice] would answer the same question
  // at a cost for no reason.
  val blockingHomeOffice: List<HomeOffice> = if (!requiresPresence) emptyList() else
    this.assignments
      .filter { it.isBlocking }
      .mapNotNull { it.resource as? HumanResource }
      .map { it.homeOffice(resourceProperties).homeOffice }
      .filter { !it.isEmpty }
  // [fork change] AXIS B. An assignment marked `no-effort` is dropped whole, not merely set to
  // zero hours -- and dropping it whole is what also takes that person's days off out of the
  // walk. That is the same answer either way, and it is the RIGHT one FOR THE HOURS: somebody who
  // contributes no hours cannot have hours taken away from them by a holiday, so their day off
  // does not lengthen the task on that account.
  //
  // Whether that person's absence should STOP the task is a different question entirely -- that
  // is axis A -- and it IS asked, a few lines above, on the assignment list before this filter
  // has touched it.
  val shares = this.assignments.filter { it.contributesEffort }.mapNotNull { assignment ->
    (assignment.resource as? HumanResource)?.let { resource ->
      Share(
        resource.capacitySchedule(resourceProperties).schedule,
        assignment.load / 100.0,
        daysOff.rangesOf(resource),
        // [fork change] E1, and the condition is the whole of it: a person at home delivers no
        // hours TO A TASK THAT NEEDS SOMEBODY ON SITE. Handing the home office in unconditionally
        // would make every home-office day a holiday for every task -- the mistake this package
        // exists to avoid, in its quietest form.
        homeOffice = if (requiresPresence) {
          resource.homeOffice(resourceProperties).homeOffice.takeIf { !it.isEmpty }
        } else {
          null
        })
    }
  }
  return EffortInputs(shares, blockingDaysOff, blockingHomeOffice)
}

/**
 * [fork change] The day-by-day walk itself, separated from the model on 03.09.2026.
 *
 * Kept apart from [durationDaysWithDaysOff] for one reason only: levelling has to ask this
 * question once per candidate starting day, and it must not pay for [effortInputs] each time. The
 * arithmetic is not touched by the separation -- the loop below is the loop that stood in
 * [durationDaysWithDaysOff] before, line for line.
 *
 * @return the number of working days, at least 1, or `null` when nothing can be derived: nobody
 * who could contribute, or an effort that cannot be worked off within [CapacitySchedule.MAX_DAYS]
 * working days. An empty answer is better than an invented number.
 */
internal fun daysNeededWithDaysOff(
  effortHours: Double,
  inputs: EffortInputs,
  start: LocalDate,
  isWorkingDay: (LocalDate) -> Boolean,
): Int? {
  // WHAT CANNOT BE DELIVERED AT ALL IS ANSWERED WITHOUT WALKING. Everybody at their highest
  // daily rate, every day a working day, nobody ever away: if even that best of all cases does
  // not work the effort off within [CapacitySchedule.MAX_DAYS], no walk can do better.
  //
  // THE ANSWER IS UNCHANGED, only its price. The loop below lowers `remaining` by at most
  // `best` per working day -- days off, a blocked day and lower sections only lower it by less --
  // so after MAX_DAYS days something would be left over and the loop would return the same
  // `null`. The comparison is written against the same tolerance the loop uses, so that the two
  // cannot disagree about a case that lands exactly on the boundary.
  //
  // AXIS A IS NOT IN THE BOUND, on purpose: a blocked day takes hours away, so ignoring it here
  // can only make the bound MORE generous, and a generous upper bound never turns a walkable
  // effort into a `null`. A day on which somebody indispensable is away is found out by walking,
  // which is what keeps `eine unbegrenzte abwesenheit gibt auf statt endlos zu laufen` answering
  // `null` rather than a number.
  //
  // TWO CASES IT COVERS, and both are everyday ones rather than exotic: nobody who could deliver
  // anything -- an assignment at 0 %, a person with no hours entered, nobody assigned at all --
  // and an effort too large for the plan. Before 03.09.2026 the first was found out by ten
  // thousand steps and the second was never asked here at all, because levelling did not walk.
  val best = inputs.shares.sumOf { it.bestHoursPerDay }
  if (best <= 0.0 || effortHours - best * CapacitySchedule.MAX_DAYS > 1e-9) {
    return null
  }
  var remaining = effortHours
  var day = start
  var days = 0
  var guard = 0
  while (guard++ < CapacitySchedule.MAX_DAYS * 2) {
    if (isWorkingDay(day)) {
      days++
      // [fork change] AXIS A, and it takes the WHOLE day: a task that is missing somebody it
      // cannot proceed without makes no progress on that day, not through the other people
      // either. The day is counted all the same -- it occupies a day of the task, as every day
      // off has always done. What it no longer does is eat the others' hours.
      //
      // [fork change] B3: „missing" now has TWO readings -- away, or at home on a task that needs
      // somebody here. Both are folded in [EffortInputs.blocksWholeDay], and `days++` above stays
      // where it is: a home-working day OCCUPIES a day of the task exactly as a holiday does. The
      // task gets LONGER; it does not skip the day. Pulling `days++` inside this `if` is the
      // obvious-looking rebuild and it is wrong, measured in `BlockingAbsenceDurationTest` for the
      // holiday and in `HomeWorkDurationTest` for the home office.
      if (!inputs.blocksWholeDay(day)) {
        remaining -= inputs.shares.sumOf { it.hoursOn(day) }
      }
      if (remaining <= 1e-9) {
        return days
      }
      if (days >= CapacitySchedule.MAX_DAYS) {
        return null
      }
    }
    day = day.plusDays(1)
  }
  return null
}

/**
 * The duration this task needs when the days off of its people are taken into account, or `null`
 * when nothing can be derived: no effort recorded, nobody assigned, or no availability at all.
 *
 * Returns `null` as well when the effort cannot be worked off within [CapacitySchedule.MAX_DAYS]
 * working days — an empty plan is a better answer than an arbitrary number.
 */
// [fork change] @JvmOverloads because TaskManagerImpl.java calls this and Java does not see Kotlin
// default arguments -- without it, adding the optional parameter below would break that call site.
@JvmOverloads
fun Task.durationDaysWithDaysOff(
  taskProperties: CustomPropertyManager,
  resourceProperties: CustomPropertyManager,
  start: LocalDate,
  isWorkingDay: (LocalDate) -> Boolean,
  /** [fork change] See [DaysOffView]. Last and optional, so every existing call site is untouched. */
  daysOff: DaysOffView = daysOffAsEntered,
): Int? {
  val effort = this.effortHours(taskProperties) ?: return null
  if (effort <= 0.0) {
    return 1
  }
  return daysNeededWithDaysOff(
    effort, this.effortInputs(taskProperties, resourceProperties, daysOff), start, isWorkingDay)
}
