/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
Hole A of the levelling-is-out-of-date measurement: the two assignment axes must report.

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
package net.sourceforge.ganttproject.resource

import biz.ganttproject.core.calendar.AlwaysWorkingTimeCalendarImpl
import biz.ganttproject.core.calendar.GPCalendarCalc
import biz.ganttproject.core.option.BooleanOption
import biz.ganttproject.core.option.ColorOption
import biz.ganttproject.core.option.DefaultBooleanOption
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.core.time.TimeUnitStack
import biz.ganttproject.core.time.impl.GPTimeUnitStack
import biz.ganttproject.customproperty.CustomColumnsManager
import net.sourceforge.ganttproject.gui.NotificationManager
import net.sourceforge.ganttproject.roles.RoleManager
import net.sourceforge.ganttproject.task.ResourceAssignment
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.task.TaskManagerConfig
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenTrigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Color
import java.net.URL
import java.text.DateFormat
import java.util.Locale

/**
 * THE TWO AXES OF AN ASSIGNMENT HAVE TO REPORT.
 *
 * Measured on 28.08.2026 (`2026-08-28-verteilung-veraltet-messung.md`, section 1.3, hole A):
 * `setBlocking` and `setNoEffort` changed the value and told nobody. The comment in the source
 * gave the reason of the day — "nothing depends on them yet" — and since P1 and A2 that is simply
 * no longer true:
 *
 *  - axis A (`isBlocking`) decides where the levelling may put a task at all. It goes into
 *    `blocking` in `LevellingAdapter.toLevelTask` and is asked per candidate day in
 *    `ResourceLevelling`.
 *  - axis B (`isNoEffort`) decides how many hours a day a task actually gets, through
 *    `contributesEffort` in `EffortDrivenDurationAlgorithm`.
 *
 * So a tick in the task dialog changed the result of a calculation that nothing re-ran, because
 * nothing was told. The last test in this class shows that in the strongest form there is: the
 * duration in the model was wrong afterwards, and stayed wrong.
 *
 * WHAT IS NOT CHECKED HERE: that the levelling computes correctly with axis A — that is
 * `LevellingBlockingTest`. This class is only about the message.
 *
 * Setup follows `EffortDrivenTriggerTest`, the established pattern for this question.
 */
class AssignmentAxisEventTest {

  init {
    // GanttCalendars need a locale. Same bootstrap as EffortDrivenTriggerTest.
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

  private lateinit var taskManager: TaskManager
  private lateinit var resourceManager: HumanResourceManager
  private lateinit var resourceProperties: CustomColumnsManager

  @BeforeEach
  fun setUp() {
    resourceProperties = CustomColumnsManager()
    resourceManager = HumanResourceManager(
      RoleManager.Access.getInstance().defaultRole, resourceProperties)
    resourceManager.create("Anna", 1)
    resourceManager.create("Bert", 2)
    taskManager = TaskManager.Access.newInstance(null, object : TaskManagerConfig {
      override fun getDefaultColor(): Color? = null
      override fun getDefaultColorOption(): ColorOption? = null
      override fun getCalendar(): GPCalendarCalc = AlwaysWorkingTimeCalendarImpl()
      override fun getTimeUnitStack(): TimeUnitStack = GPTimeUnitStack()
      // Must be qualified — an unqualified name would resolve to this very getter and recurse.
      override fun getResourceManager(): HumanResourceManager =
        this@AssignmentAxisEventTest.resourceManager
      override fun getProjectDocumentURL(): URL? = null
      override fun getNotificationManager(): NotificationManager? = null
      override fun getSchedulerDisabledOption(): BooleanOption =
        DefaultBooleanOption("scheduler.disabled", false)
    })
  }

  /** Counts the assignment messages, which is the event the two axes belong to. */
  private class CountingView : ResourceView {
    var assignmentsChanged = 0
    var resourceChanged = 0
    override fun resourceAdded(event: ResourceEvent) {}
    override fun resourcesRemoved(event: ResourceEvent) {}
    override fun resourceChanged(e: ResourceEvent) { resourceChanged++ }
    override fun resourceAssignmentsChanged(e: ResourceEvent) { assignmentsChanged++ }
    override fun resourceStructureChanged() {}
    override fun resourceModelReset() {}
  }

  /** A task with one assignment, and a counter that starts at zero AFTER the setup. */
  private fun setUpAssignment(): Pair<ResourceAssignment, CountingView> {
    val task = taskManager.createTask()
    val assignment = task.assignmentCollection.addAssignment(resourceManager.getById(1))
    assignment.load = 100f
    val view = CountingView()
    resourceManager.addView(view)
    return assignment to view
  }

  private fun setHoursPerDay(resource: HumanResource, hours: Double) {
    resource.setValue(EffortDrivenProperties.findOrCreateResourceHours(resourceProperties), hours)
  }

  private fun setEffort(task: Task, hours: Double) {
    task.customValues.setValue(
      EffortDrivenProperties.findOrCreateTaskEffort(taskManager.customPropertyManager), hours)
  }

  // ------------------------------------------------------------------------------------------
  // The message itself
  // ------------------------------------------------------------------------------------------

  /**
   * RED against dd01fbb05:
   *   org.opentest4j.AssertionFailedError: ticking "absence blocks" must reach the listeners --
   *   the value steers the levelling ==> expected: <1> but was: <0>
   */
  @Test
  fun `ticking axis A reports`() {
    val (assignment, view) = setUpAssignment()
    assignment.isBlocking = true
    assertEquals(1, view.assignmentsChanged,
      "ticking \"absence blocks\" must reach the listeners -- the value steers the levelling")
  }

  /**
   * RED against dd01fbb05:
   *   org.opentest4j.AssertionFailedError: ticking "no effort" must reach the listeners --
   *   the value steers the duration ==> expected: <1> but was: <0>
   */
  @Test
  fun `ticking axis B reports`() {
    val (assignment, view) = setUpAssignment()
    assignment.isNoEffort = true
    assertEquals(1, view.assignmentsChanged,
      "ticking \"no effort\" must reach the listeners -- the value steers the duration")
  }

  /**
   * Clearing a tick again matters exactly as much as setting it: the calculation goes back to the
   * other result.
   *
   * RED against dd01fbb05:
   *   org.opentest4j.AssertionFailedError: clearing the tick must report just as setting it does
   *   ==> expected: <1> but was: <0>
   */
  @Test
  fun `clearing axis A reports as well`() {
    val (assignment, view) = setUpAssignment()
    assignment.isBlocking = true
    view.assignmentsChanged = 0
    assignment.isBlocking = false
    assertEquals(1, view.assignmentsChanged,
      "clearing the tick must report just as setting it does")
  }

  /**
   * The two axes are independent, and so are their messages: setting one must not look like a
   * change of the other. Checked here on the count, because that is what a listener sees.
   *
   * COULD NOT BE RED against dd01fbb05 in the "one message" half -- against the old code the
   * count was zero, which also satisfies "not two". It is red in the first assertion, and it is
   * listed here so that the independence is pinned and not merely assumed.
   *
   * RED against dd01fbb05:
   *   org.opentest4j.AssertionFailedError: axis A alone is one message, not two
   *   ==> expected: <1> but was: <0>
   */
  @Test
  fun `one axis is one message`() {
    val (assignment, view) = setUpAssignment()
    assignment.isBlocking = true
    assertEquals(1, view.assignmentsChanged, "axis A alone is one message, not two")
    assignment.isNoEffort = true
    assertEquals(2, view.assignmentsChanged, "axis B is a message of its own")
  }

  /**
   * NO EVENT STORM. Writing the value that is already there is not a change, and every copying
   * path in the program does exactly that: the two copying constructors of
   * `ResourceAssignmentCollectionImpl`, its `commit()`, `importData`, `ClipboardTaskProcessor`,
   * `TaskManagerImpl.importData` and the file parser all write both axes unconditionally. Without
   * the guard each of them would produce two messages per assignment for nothing.
   *
   * COULD NOT BE RED against dd01fbb05: the old code fired nothing at all, so "no further
   * message" was true there for the wrong reason. It is listed under the tests that could not
   * fail beforehand. It guards the new code against the opposite mistake.
   */
  @Test
  fun `writing the same value again reports nothing`() {
    val (assignment, view) = setUpAssignment()
    assignment.isBlocking = true
    view.assignmentsChanged = 0
    assignment.isBlocking = true
    assignment.isBlocking = true
    assertEquals(0, view.assignmentsChanged,
      "writing an unchanged value must stay silent -- every copying path does it")
  }

  /**
   * The second way in, and the one the task dialog uses for an assignment created in that same
   * dialog: the value is entered on the mutator's stub, and `commit()` copies it onto the real
   * assignment. The stub itself is silent by design; the copy must not be.
   *
   * RED against dd01fbb05:
   *   org.opentest4j.AssertionFailedError: the axis copied by commit() must report -- it is the
   *   dialog's path for a newly added assignment ==> expected: <true> but was: <false>
   */
  @Test
  fun `the axis copied by the mutator commit reports`() {
    val task = taskManager.createTask()
    val view = CountingView()
    resourceManager.addView(view)

    val mutator = task.assignmentCollection.createMutator()
    mutator.addAssignment(resourceManager.getById(1)).also {
      it.load = 100f
      it.isBlocking = true
    }
    val beforeCommit = view.assignmentsChanged
    mutator.commit()

    // The commit adds the assignment and sets the load, which report on their own; what is
    // measured is whether the axis adds its message on top of those.
    val axisReported = view.assignmentsChanged > beforeCommit + 2
    assertEquals(true, axisReported,
      "the axis copied by commit() must report -- it is the dialog's path for a newly added assignment")
  }

  // ------------------------------------------------------------------------------------------
  // The price: no additional resetLoads
  // ------------------------------------------------------------------------------------------

  /**
   * AN AXIS MUST NOT THROW AWAY THE CACHED LOAD DISTRIBUTION.
   *
   * `LoadDistribution` is built from the load, the task activities and the days off — read it in
   * `LoadDistribution.java`, the two axes appear nowhere in it. So `resetLoads()` on an axis
   * change would discard a cache that is still perfectly valid, on every single tick.
   *
   * Measured on the object identity: `getLoadDistribution()` caches in `myLoadDistribution` and
   * rebuilds only after `resetLoads()`. Same object means the cache survived.
   *
   * COULD NOT BE RED against dd01fbb05: the old setter did nothing at all, so it did not reset
   * either. This test does not pin an old defect, it pins the new code against the cheap and
   * tempting alternative of calling `fireAssignmentChanged()`, which would reset.
   */
  @Test
  fun `an axis change keeps the cached load distribution`() {
    val (assignment, _) = setUpAssignment()
    val resource = assignment.resource
    val before = resource.loadDistribution
    assignment.isBlocking = true
    assignment.isNoEffort = true
    assertSame(before, resource.loadDistribution,
      "no axis takes part in the load distribution, so no axis may throw it away")
  }

  /**
   * The counter-check to the one above, so that the identity comparison is not measuring nothing:
   * a change that DOES belong to the load distribution has to discard it. Otherwise the test
   * above would pass even if `getLoadDistribution` never cached at all.
   *
   * COULD NOT BE RED against dd01fbb05: it describes behaviour that was already there before this
   * work and is untouched by it.
   */
  @Test
  fun `a load change does throw the cached load distribution away`() {
    val (assignment, _) = setUpAssignment()
    val resource = assignment.resource
    val before = resource.loadDistribution
    assignment.load = 50f
    assertNotEquals(System.identityHashCode(before),
      System.identityHashCode(resource.loadDistribution),
      "the load is part of the distribution, so changing it must discard the cache")
  }

  // ------------------------------------------------------------------------------------------
  // No endless loop
  // ------------------------------------------------------------------------------------------

  /**
   * NO CIRCLE. A listener that reacts to the message by writing the axis value it was just told
   * about is the shape a feedback loop takes here — and the copying paths do exactly that, which
   * is why it is not a theoretical case. Without the "only on a real change" guard in the setter
   * this test would not fail, it would never return.
   *
   * RED against dd01fbb05 — for the plainer reason that the message never arrived at all:
   *   org.opentest4j.AssertionFailedError: one tick is one message, even with a listener that
   *   writes back ==> expected: <1> but was: <0>
   * The old code could not loop because it never spoke. The value of this test lies in the other
   * direction: it holds the NEW code, which does speak, to terminating.
   */
  @Test
  fun `a listener writing the axis back does not loop`() {
    val (assignment, _) = setUpAssignment()
    var seen = 0
    resourceManager.addView(object : ResourceView {
      override fun resourceAdded(event: ResourceEvent) {}
      override fun resourcesRemoved(event: ResourceEvent) {}
      override fun resourceChanged(e: ResourceEvent) {}
      override fun resourceAssignmentsChanged(e: ResourceEvent) {
        seen++
        // Write back what we were just told. With the guard this is a no-op; without it, the
        // setter fires again and this handler is entered again, without end.
        assignment.isBlocking = assignment.isBlocking
      }
      override fun resourceStructureChanged() {}
      override fun resourceModelReset() {}
    })

    assignment.isBlocking = true
    assertEquals(1, seen, "one tick is one message, even with a listener that writes back")
  }

  /**
   * The same guarantee against the REAL consumer rather than a hand-built one:
   * `EffortDrivenTrigger` reacts to an assignment message by recalculating durations, which writes
   * to tasks. That is the loop the measurement warned about. It has to run once and stop.
   *
   * RED against dd01fbb05 — again because nothing was said at all:
   *   org.opentest4j.AssertionFailedError: the recalculation must not send the message that
   *   started it a second time ==> expected: <1> but was: <0>
   * "Exactly one" is the assertion that matters here: it is violated by silence in the old code
   * and would be violated by a cascade in the new one.
   */
  @Test
  fun `the effort trigger runs once and stops`() {
    resourceManager.addView(EffortDrivenTrigger(taskManager))
    val (assignment, view) = setUpAssignment()
    setHoursPerDay(assignment.resource, 4.0)
    setEffort(assignment.task, 40.0)
    view.assignmentsChanged = 0

    assignment.isNoEffort = true

    assertEquals(1, view.assignmentsChanged,
      "the recalculation must not send the message that started it a second time")
  }

  // ------------------------------------------------------------------------------------------
  // Why it matters: the message reaches a calculation that is already there
  // ------------------------------------------------------------------------------------------

  /**
   * THE POINT OF THE WHOLE HOLE, in one number.
   *
   * Two people, four hours a day each, 40 hours of work: 5 days. Mark Bert as contributing no
   * effort and only Anna's four hours remain, so the task needs 10 days. Nobody calls the
   * algorithm here — the tick has to be enough, exactly as it is when a resource's daily hours are
   * edited (`EffortDrivenTriggerTest.testChangingDailyHoursChangesTheDuration`).
   *
   * Until 28.08.2026 the duration stayed at 5. Not "was recomputed later" — it stayed wrong in the
   * model until something else happened to touch a resource.
   *
   * RED against dd01fbb05:
   *   org.opentest4j.AssertionFailedError: the tick alone has to reach the duration: 40 h at
   *   Anna's 4 h/day is 10 days ==> expected: <10> but was: <5>
   */
  @Test
  fun `axis B alone changes the duration in the model`() {
    resourceManager.addView(EffortDrivenTrigger(taskManager))
    val anna = resourceManager.getById(1)
    val bert = resourceManager.getById(2)
    setHoursPerDay(anna, 4.0)
    setHoursPerDay(bert, 4.0)

    val task = taskManager.createTask()
    setEffort(task, 40.0)
    task.assignmentCollection.addAssignment(anna).load = 100f
    val bertsAssignment = task.assignmentCollection.addAssignment(bert)
    bertsAssignment.load = 100f
    assertEquals(5, task.duration.length, "setup: 40 h at 8 h/day between the two of them")

    bertsAssignment.isNoEffort = true

    assertEquals(10, task.duration.length,
      "the tick alone has to reach the duration: 40 h at Anna's 4 h/day is 10 days")
  }
}
