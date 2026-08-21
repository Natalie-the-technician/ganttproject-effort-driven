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
*/
package net.sourceforge.ganttproject.fork

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * The two comparison rules for the band underneath a task bar.
 *
 * The numbers in the effort part are NOT made up: they come from the plan on which the finding of
 * 20 August 2026 was measured — 1.8 hours a day against the default of 8, a factor of 4.44.
 * Exactly that case must NOT turn red in the effort comparison, because no effort changed by so
 * much as an hour.
 */
class ChartComparisonTest {

  private fun day(iso: String): Date =
    Date.from(LocalDate.parse(iso).atStartOfDay(ZoneId.systemDefault()).toInstant())

  // ---- Dates: end against end -------------------------------------------------------------

  @Test
  fun `the same end date means on schedule and draws nothing`() {
    assertEquals(ComparisonResult.NO_BAND, compareDates(day("2026-08-15"), day("2026-08-15")))
  }

  @Test
  fun `a later end date is a delay`() {
    assertEquals(ComparisonResult.MORE, compareDates(day("2026-08-15"), day("2026-08-22")))
  }

  @Test
  fun `an earlier end date is a head start`() {
    assertEquals(ComparisonResult.LESS, compareDates(day("2026-08-15"), day("2026-08-08")))
  }

  @Test
  fun `merely shifted counts as a delay, because the end date is a different one`() {
    // The rule of 17 August 2026 would have said "same duration, so neutral" here. That was the
    // attempt to answer two questions with one display. For "am I on schedule", an end date a
    // week later is a delay, no matter how long the task is.
    assertEquals(ComparisonResult.MORE, compareDates(day("2026-08-07"), day("2026-08-14")))
  }

  // ---- Effort: recorded hours against the original estimate --------------------------------

  @Test
  fun `without an original estimate there is no yardstick`() {
    assertEquals(ComparisonResult.NO_BAND, compareEffort(null, 12.0))
    assertEquals(ComparisonResult.NO_BAND, compareEffort(0.0, 12.0))
  }

  @Test
  fun `an estimate with nothing recorded is neutral, not on schedule`() {
    assertEquals(ComparisonResult.NEUTRAL, compareEffort(8.0, null))
    assertEquals(ComparisonResult.NEUTRAL, compareEffort(8.0, 0.0))
  }

  @Test
  fun `more hours spent`() {
    assertEquals(ComparisonResult.MORE, compareEffort(9.0, 15.0))
  }

  @Test
  fun `fewer hours spent`() {
    assertEquals(ComparisonResult.LESS, compareEffort(24.0, 0.368))
  }

  @Test
  fun `the same number of hours draws nothing`() {
    assertEquals(ComparisonResult.NO_BAND, compareEffort(16.0, 16.0))
  }

  @Test
  fun `half a minute of difference is no difference`() {
    assertEquals(ComparisonResult.NO_BAND, compareEffort(16.0, 16.0 + 0.5 / 60.0))
  }

  @Test
  fun `two minutes of difference are one`() {
    assertEquals(ComparisonResult.MORE, compareEffort(16.0, 16.0 + 2.0 / 60.0))
  }

  @Test
  fun `capacity levelling does not colour the effort comparison`() {
    // The measured case: effort 24 hours, duration going from 3 to 14 days because the daily
    // availability was set from 8 to 1.8 hours. 24 hours are recorded.
    // The duration quadrupled, the effort did not — so no band.
    assertEquals(ComparisonResult.NO_BAND, compareEffort(24.0, 24.0))
  }

  // ---- Style names ------------------------------------------------------------------------

  @Test
  fun `the style names are the original's`() {
    assertEquals("later", ComparisonResult.MORE.styleName())
    assertEquals("earlier", ComparisonResult.LESS.styleName())
    assertNull(ComparisonResult.NEUTRAL.styleName())
    assertNull(ComparisonResult.NO_BAND.styleName())
  }
}
