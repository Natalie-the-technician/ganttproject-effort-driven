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
package net.sourceforge.ganttproject.task.algorithm

import junit.framework.TestCase

/**
 * Tests for the effort-to-duration computation.
 *
 * Every rule here has a matching negative case: a test is worthless until it has been seen
 * to fail. The "rejects" tests exercise exactly that.
 */
class EffortDrivenDurationTest : TestCase() {

  /** The case from the specification: 20 h at 2 h/day is 10 days. */
  fun testTheSpecificationExample() {
    assertEquals(10, computeDurationDays(effortHours = 20.0, availableHoursPerDay = 2.0))
  }

  /** Same effort at twice the daily hours must halve the duration. */
  fun testDoublingAvailabilityHalvesDuration() {
    assertEquals(10, computeDurationDays(20.0, 2.0))
    assertEquals(5, computeDurationDays(20.0, 4.0))
  }

  /** A partial day must be rounded up: 21 h at 4 h/day is 6 days, not 5. */
  fun testPartialDayIsRoundedUp() {
    assertEquals(6, computeDurationDays(21.0, 4.0))
    assertEquals(2, computeDurationDays(8.5, 8.0))
  }

  /** An exact fit must not be rounded up: 16 h at 8 h/day is exactly 2 days. */
  fun testExactFitIsNotRoundedUp() {
    assertEquals(2, computeDurationDays(16.0, 8.0))
    assertEquals(1, computeDurationDays(8.0, 8.0))
  }

  /** Tiny effort still occupies a whole day; a zero-day task would be a milestone. */
  fun testDurationIsAtLeastOneDay() {
    assertEquals(1, computeDurationDays(0.25, 8.0))
  }

  /** Two resources at 4 h/day are equivalent to one at 8 h/day. */
  fun testTwoResourcesAddUp() {
    assertEquals(computeDurationDays(40.0, 8.0), computeDurationDays(40.0, 4.0 + 4.0))
  }

  /** Half a person at 8 h/day is 4 h/day, so the task takes twice as long. */
  fun testHalfLoadDoublesDuration() {
    val fullLoad = 8.0 * 100 / 100.0
    val halfLoad = 8.0 * 50 / 100.0
    assertEquals(5, computeDurationDays(40.0, fullLoad))
    assertEquals(10, computeDurationDays(40.0, halfLoad))
  }

  // --- Negative cases: these must throw, otherwise the guards are decoration ---

  /** Nothing assigned means no availability; silently returning a duration would be wrong. */
  fun testRejectsZeroAvailability() {
    try {
      computeDurationDays(20.0, 0.0)
      fail("zero availability must be rejected, otherwise the result would be infinite")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.contains("availability"))
    }
  }

  fun testRejectsNegativeAvailability() {
    try {
      computeDurationDays(20.0, -4.0)
      fail("negative availability must be rejected")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.contains("availability"))
    }
  }

  /** Zero effort is not a task with a duration, it is a milestone. */
  fun testRejectsZeroEffort() {
    try {
      computeDurationDays(0.0, 8.0)
      fail("zero effort must be rejected, a task with no work is a milestone")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.contains("effort"))
    }
  }

  fun testRejectsNegativeEffort() {
    try {
      computeDurationDays(-5.0, 8.0)
      fail("negative effort must be rejected")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.contains("effort"))
    }
  }
}
