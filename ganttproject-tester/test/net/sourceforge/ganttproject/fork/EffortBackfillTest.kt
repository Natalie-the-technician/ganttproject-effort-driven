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

import junit.framework.TestCase

/**
 * The derivation of effort from the duration.
 *
 * The most important test is [testFillingChangesNoDuration]: the tool must not move the plan.
 * Anything else would be a silent change to 226 Tasks on one click.
 */
class EffortBackfillTest : TestCase() {

  private fun task(
    id: String, dauer: Int, gruppe: Boolean = false, meilenstein: Boolean = false,
    aufwand: Double? = null, zuordnungen: Int = 0, warten: Boolean = false
  // NAMED arguments, not positional: when BackfillTask was extended by "isWaitOnly", the values
  // here silently slipped one position along and the test only broke at compile time -- with two
  // booleans next to each other it could just as well have measured wrongly.
  ) = BackfillTask(id = id, name = id, durationDays = dauer, isContainer = gruppe,
    isMilestone = meilenstein, isWaitOnly = warten, existingEffortHours = aufwand,
    assignmentCount = zuordnungen)

  /**
   * THE SAFETY PROPERTY. Deriving effort from duration and computing back has to give the same
   * duration -- for every duration and every daily rate, not only for round ones.
   */
  fun testFillingChangesNoDuration() {
    for (stunden in listOf(0.5, 1.0, 2.0, 3.0, 6.0, 7.5, 8.0, 10.0)) {
      for (dauer in 1..40) {
        val vorschlag = proposeBackfill(listOf(task("t", dauer)), stunden)
        val aufwand = vorschlag.effortHours.getValue("t")
        assertEquals(
          "Dauer $dauer bei $stunden Std./Tag muss nach dem Befuellen gleich bleiben",
          dauer, durationFromEffort(aufwand, stunden))
      }
    }
  }

  fun testEffortIsDurationTimesHoursPerDay() {
    val v = proposeBackfill(listOf(task("a", 5)), 8.0)
    assertEquals(40.0, v.effortHours.getValue("a"))
    assertEquals(listOf("a"), v.assignTo)
    assertTrue(v.skipped.isEmpty())
  }

  /** Groups derive their duration from their children -- entering an effort there would be pointless. */
  fun testContainersAreSkipped() {
    val v = proposeBackfill(listOf(task("gruppe", 10, gruppe = true)), 8.0)
    assertTrue(v.effortHours.isEmpty())
    assertTrue("eine Gruppe bekommt auch keine Zuordnung", v.assignTo.isEmpty())
    assertEquals(BackfillSkip.CONTAINER, v.skipped["gruppe"])
  }

  /** A milestone has nothing to deliver. */
  fun testMilestonesAreSkipped() {
    val v = proposeBackfill(listOf(task("m", 0, meilenstein = true)), 8.0)
    assertEquals(BackfillSkip.MILESTONE, v.skipped["m"])
    assertTrue(v.assignTo.isEmpty())
  }

  /**
   * An existing effort is NEVER overwritten. Whoever maintained it knew more than this
   * derivation -- which knows only the duration.
   */
  fun testExistingEffortIsNeverOverwritten() {
    val v = proposeBackfill(listOf(task("a", 5, aufwand = 3.0)), 8.0)
    assertTrue(v.effortHours.isEmpty())
    assertEquals(BackfillSkip.ALREADY_HAS_EFFORT, v.skipped["a"])
    assertEquals("zugeordnet wird trotzdem, sonst kennt die Verteilung den Vorgang nicht",
      listOf("a"), v.assignTo)
  }

  /** Whoever already has an assignment gets no second person: that would halve the duration. */
  fun testAlreadyAssignedGetsNoSecondPerson() {
    val v = proposeBackfill(listOf(task("a", 5, zuordnungen = 1)), 8.0)
    assertTrue(v.assignTo.isEmpty())
    assertEquals("Aufwand fehlt trotzdem und wird ergaenzt", 40.0, v.effortHours.getValue("a"))
  }

  /** Counter-check to it: when explicitly asked for, the already assigned one is included too. */
  fun testSecondAssignmentOnlyOnRequest() {
    val v = proposeBackfill(listOf(task("a", 5, zuordnungen = 1)), 8.0, alreadyAssignedKeepsIts = false)
    assertEquals(listOf("a"), v.assignTo)
  }

  fun testZeroDurationWithoutMilestoneIsSkipped() {
    val v = proposeBackfill(listOf(task("a", 0)), 8.0)
    assertEquals(BackfillSkip.NO_DURATION, v.skipped["a"])
  }

  /** A daily rate of 0 would be a division by zero -- that shows up early, not late. */
  fun testZeroHoursPerDayIsRejected() {
    try {
      proposeBackfill(listOf(task("a", 5)), 0.0)
      fail("0 Stunden pro Tag muessen abgelehnt werden")
    } catch (expected: IllegalArgumentException) {
      // this is how it should be
    }
  }

  /** The number of changes is what the preview shows -- it has to be right. */
  fun testChangeCountMatchesWhatWillHappen() {
    val v = proposeBackfill(
      listOf(task("a", 5), task("b", 3), task("gruppe", 8, gruppe = true), task("m", 0, meilenstein = true)),
      8.0)
    assertEquals("zwei Aufwaende plus zwei Zuordnungen", 4, v.changeCount)
    assertEquals(2, v.skipped.size)
  }
}
