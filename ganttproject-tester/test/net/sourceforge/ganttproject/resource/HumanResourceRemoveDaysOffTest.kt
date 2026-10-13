/*
Copyright 2026 BarD Software s.r.o

This file is part of GanttProject, an open-source project management tool.

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

import biz.ganttproject.core.calendar.GanttDaysOff
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.customproperty.CustomColumnsManager
import net.sourceforge.ganttproject.roles.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * STALE DUPLICATE -- flagged during the nachziehen-bards-1012 API adaptation (2026-10-13), not
 * deleted. bardsoft's own upstream commit fcea4c56c ("Join the three days-off test classes into
 * one, and cover the new methods") deleted this file (and HumanResourceDaysOffViewTest.kt) outright,
 * folding every assertion into [HumanResourceDaysOffTest] in this same package -- see that file's
 * class doc, which names this one by name as superseded. That deletion is one of the 48 commits this
 * merge brings in, but it did not survive the merge resolution: this file is still here, now running
 * two of its five tests against assumptions bardsoft's own change already invalidated (GanttDaysOff
 * gaining a real equals(Object); getDaysOff() becoming an unmodifiable view) -- the two fixes below
 * patch those two tests to the new semantics, kept in parallel with the (also still passing)
 * superseding assertions in HumanResourceDaysOffTest, rather than resolve the duplication.
 *
 * `git rm` and plain `rm` on this file were both denied by the Claude Code auto-mode classifier
 * (destructive-operation guard) in this session; a second, differently-worded attempt was not made,
 * per the standing rule that two denials of the same action mean the path is closed, not a cue to
 * route around it. Deleting it (restoring bardsoft's actual intent) is the right fix and is left for
 * Natalie or dirigent-gantt to do with the permission this session does not have.
 *
 * A resource can be given a day off through [HumanResource.addDaysOff], but until now it could only
 * be taken away again by reaching into the list handed out by [HumanResource.getDaysOff] and
 * mutating it. These tests pin the counterpart of addDaysOff: [HumanResource.removeDaysOff] for a
 * single interval and [HumanResource.clearDaysOff] for all of them, both notifying exactly like the
 * mutation of the handed-out list does.
 *
 * Every one of the five tests below was seen to fail against the state before this change, each with
 * its own error. The failure is a Kotlin compilation error rather than a failed assertion, because
 * the methods being pinned did not exist yet -- there is no way to name a method that is not there
 * and still compile. The verbatim output of that run is quoted at each test method; the line and
 * column numbers in it refer to this file as it stood in that run, before these very comments were
 * added, so they no longer point at the quoted line.
 */
class HumanResourceRemoveDaysOffTest {
  init {
    // GanttDaysOff builds GanttCalendars, and those need a locale.
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

  private class CountingView : ResourceView {
    var changed = 0
    override fun resourceAdded(event: ResourceEvent) {}
    override fun resourcesRemoved(event: ResourceEvent) {}
    override fun resourceChanged(e: ResourceEvent) { changed++ }
    override fun resourceAssignmentsChanged(e: ResourceEvent) {}
    override fun resourceStructureChanged() {}
    override fun resourceModelReset() {}
  }

  private fun september(day: Int): Date = CalendarFactory.createGanttCalendar(2026, 8, day).time

  private fun daysOff(from: Int, to: Int) = GanttDaysOff(september(from), september(to))

  /** A manager with one resource in it, plus a view counting the resourceChanged notifications. */
  private fun newPersonWithView(): Pair<HumanResource, CountingView> {
    val manager = HumanResourceManager(null as Role?, CustomColumnsManager())
    val person = manager.newHumanResource()
    person.name = "Tester"
    manager.add(person)
    val view = CountingView()
    manager.addView(view)
    return person to view
  }

  // Red before the change:
  // e: file:///.../HumanResourceRemoveDaysOffTest.kt:88:23 Unresolved reference 'removeDaysOff'.
  @Test
  fun `removing a single day off takes it off the resource and notifies once`() {
    val (person, view) = newPersonWithView()
    val first = daysOff(9, 10)
    val second = daysOff(20, 21)
    person.addDaysOff(first)
    person.addDaysOff(second)

    view.changed = 0
    assertTrue(person.removeDaysOff(first), "removeDaysOff must report that it removed something")

    assertEquals(1, view.changed, "removing a day off must notify the listeners exactly once")
    assertEquals(1, person.daysOff.size, "the other day off must still be there")
    assertEquals(second, person.daysOff.get(0), "the WRONG day off was removed")
  }

  // Red before the change:
  // e: file:///.../HumanResourceRemoveDaysOffTest.kt:105:24 Unresolved reference 'removeDaysOff'.
  @Test
  fun `removing a day off the resource does not have changes nothing and notifies nobody`() {
    val (person, view) = newPersonWithView()
    person.addDaysOff(daysOff(9, 10))

    view.changed = 0
    // A genuinely different interval -- not one that merely looks different by identity. GanttDaysOff
    // now overrides equals(Object) (bardsoft's upstream merge gave it a real one, so that
    // List.remove() -- which calls equals(Object), not the old equals(GanttDaysOff) overload -- can
    // match by value), so a fresh object built from the SAME dates as an existing entry would in fact
    // be found and removed. Using different dates keeps this test about "not there at all".
    assertFalse(person.removeDaysOff(daysOff(13, 14)), "an interval that is not there cannot be removed")

    assertEquals(0, view.changed, "a removal that removed nothing must not notify anybody")
    assertEquals(1, person.daysOff.size, "the existing day off must be untouched")
  }

  // Red before the change:
  // e: file:///.../HumanResourceRemoveDaysOffTest.kt:119:12 Unresolved reference 'clearDaysOff'.
  @Test
  fun `clearing the days off empties the list and notifies once, whatever the count`() {
    val (person, view) = newPersonWithView()
    person.addDaysOff(daysOff(9, 10))
    person.addDaysOff(daysOff(20, 21))
    person.addDaysOff(daysOff(24, 25))

    view.changed = 0
    person.clearDaysOff()

    assertEquals(0, person.daysOff.size, "clearDaysOff must leave no day off behind")
    assertEquals(1, view.changed, "clearing three days off must notify exactly once, as clear() does")
  }

  // Red before the change:
  // e: file:///.../HumanResourceRemoveDaysOffTest.kt:130:12 Unresolved reference 'clearDaysOff'.
  @Test
  fun `clearing an empty list of days off notifies nobody`() {
    val (person, view) = newPersonWithView()

    view.changed = 0
    person.clearDaysOff()

    assertEquals(0, view.changed, "there was nothing to remove, so there is nothing to report")
  }

  /**
   * The one caller in the tree, GanttDialogPerson.applyChanges(), throws all the intervals away and
   * writes the edited ones back (nowadays through setDaysOff(), which is pinned separately; this test
   * predates that and pinned the equivalent clearDaysOff()-plus-loop sequence instead). This pins that
   * clearing and re-adding costs the very same number of notifications -- neither more (which would be
   * a regression) nor zero where there used to be one.
   *
   * There used to be a second path compared here, mutating the list handed out by getDaysOff()
   * directly instead of calling clearDaysOff(). bardsoft's upstream merge made getDaysOff() hand out
   * an unmodifiable view (see its own doc comment), so that path no longer compiles into working code
   * -- .clear() on the result now throws UnsupportedOperationException. Removed rather than adapted:
   * the whole point of the unmodifiable view is that mutating the handed-out list is no longer a
   * supported way to change a resource's days off, so a comparison against it has nothing left to
   * pin.
   *
   * Red before the change:
   * e: file:///.../HumanResourceRemoveDaysOffTest.kt:157:19 Unresolved reference 'clearDaysOff'.
   */
  @Test
  fun `the dialog's clear-all-and-rewrite costs (M greater than 0 then 1 else 0) plus N notifications`() {
    // M = intervals before the Ok, N = intervals in the dialog when Ok is pressed.
    for (m in 0..3) {
      for (n in 0..3) {
        val expected = (if (m > 0) 1 else 0) + n

        val (viaMethod, methodView) = newPersonWithView()
        repeat(m) { viaMethod.addDaysOff(daysOff(it + 1, it + 2)) }
        methodView.changed = 0
        viaMethod.clearDaysOff()
        repeat(n) { viaMethod.addDaysOff(daysOff(it + 10, it + 11)) }

        assertEquals(expected, methodView.changed, "M=$m N=$n: clearDaysOff() plus re-adding")
        assertEquals(n, viaMethod.daysOff.size, "M=$m N=$n: the rewritten intervals must be the only ones")
      }
    }
  }
}
