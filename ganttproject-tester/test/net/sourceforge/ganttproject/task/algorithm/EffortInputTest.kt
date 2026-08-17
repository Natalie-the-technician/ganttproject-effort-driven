/*
Copyright 2026

NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.

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
 * Tests how the text of the effort field is interpreted.
 *
 * This is the part of the new UI that actually decides something, and it is deliberately kept out
 * of the JavaFX panel so that it can be tested without a screen. The panel itself (layout, the
 * read-only hours column) is not covered by any test and has to be checked by hand.
 */
class EffortInputTest : TestCase() {

  fun testPlainNumber() {
    assertEquals(EffortInput.Hours(20.0), parseEffortInput("20"))
  }

  fun testDecimalPoint() {
    assertEquals(EffortInput.Hours(20.5), parseEffortInput("20.5"))
  }

  /** A German keyboard produces a comma; reading "20,5" as 20 would silently lose half an hour. */
  fun testDecimalComma() {
    assertEquals(EffortInput.Hours(20.5), parseEffortInput("20,5"))
  }

  fun testSurroundingWhitespaceIsIgnored() {
    assertEquals(EffortInput.Hours(8.0), parseEffortInput("  8 "))
  }

  /** An empty field means "no effort": the task drops out of the feature. */
  fun testEmptyMeansClear() {
    assertEquals(EffortInput.Clear, parseEffortInput(""))
    assertEquals(EffortInput.Clear, parseEffortInput("   "))
    assertEquals(EffortInput.Clear, parseEffortInput(null))
  }

  /** Rubbish must not be turned into a number, and must not clear the stored value either. */
  fun testNonNumericIsInvalid() {
    assertEquals(EffortInput.Invalid, parseEffortInput("acht"))
    assertEquals(EffortInput.Invalid, parseEffortInput("8h"))
  }

  /** Zero and negative hours are not a duration anybody could work. */
  fun testNonPositiveIsInvalid() {
    assertEquals(EffortInput.Invalid, parseEffortInput("0"))
    assertEquals(EffortInput.Invalid, parseEffortInput("-5"))
  }

  /** "Infinity" parses as a Double but is not a usable effort. */
  fun testInfinityIsInvalid() {
    assertEquals(EffortInput.Invalid, parseEffortInput("Infinity"))
  }
}
