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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * The three comparison rules for the band underneath a task bar.
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
    //
    // This is NOT the duration question. That one has had a view of its own since 25 August 2026;
    // `compareDurations` answers the very same case with NO_BAND, and the test at the bottom of
    // this file pins both answers together.
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

  // ---- Durations: length against length, with the shift left out ---------------------------

  @Test
  fun `the same duration draws nothing`() {
    assertEquals(ComparisonResult.NO_BAND, compareDurations(5, 5))
  }

  @Test
  fun `a longer duration is a delay`() {
    assertEquals(ComparisonResult.MORE, compareDurations(3, 14))
  }

  @Test
  fun `a shorter duration is a head start`() {
    assertEquals(ComparisonResult.LESS, compareDurations(14, 3))
  }

  @Test
  fun `without a baseline the durations view is neutral, not empty`() {
    // Empty would look exactly like "no deviation". The neutral band says "this view is
    // selected, the yardstick is missing" -- the same device the effort view uses.
    assertEquals(ComparisonResult.NEUTRAL, compareDurations(null, 5))
  }

  // ---- The two anchors, pinned against each other -------------------------------------------

  @Test
  fun `a merely shifted task -- the dates view reports a deviation, the durations view does not`() {
    // Planned 03 to 07 August, actually running 10 to 14 August. Same effort, same length of
    // five days, later start. Nothing about the work has changed; only the position has.
    //
    // THIS TEST EXISTS TO STOP SOMEONE "UNIFYING" THE TWO ANCHORS. The dates band hangs off the
    // PLANNED start, the durations band off TODAY'S start -- see `renderDurationBand`. Both
    // expectations sit in one test on purpose: whoever gives the two views the same anchor
    // breaks this test, and it fails loudly instead of quietly changing what the chart says.
    val plannedEnd = day("2026-08-07")
    val actualEnd = day("2026-08-14")
    val plannedDuration = 5
    val actualDuration = 5

    assertEquals(ComparisonResult.MORE, compareDates(plannedEnd, actualEnd),
      "the dates view answers \"am I on schedule\" -- a week later is a delay")
    assertEquals(ComparisonResult.NO_BAND, compareDurations(plannedDuration, actualDuration),
      "the durations view answers \"did the work grow\" -- five days are still five days")
  }

  @Test
  fun `longer but ending on the same day -- the blind spot the durations view exists for`() {
    // THIS IS THE CASE THE VIEW WAS BUILT FOR, and it is not made up. It was measured on screen
    // on 20 August 2026: a task planned to run from 10 to 14 August over five days, actually
    // running from 3 to 14 August over ten. It takes twice as long and finishes on the same day,
    // because it started a week earlier.
    //
    // The original draws NOTHING here -- both ends match, so its rule has nothing to say. That
    // blind spot is what `misc-fixes` set out to fix by comparing durations instead of ends.
    // Here the fix is a view of its own, so the dates view keeps its answer and the durations
    // view gives the other one.
    val plannedEnd = day("2026-08-14")
    val actualEnd = day("2026-08-14")
    val plannedDuration = 5
    val actualDuration = 10

    assertEquals(ComparisonResult.NO_BAND, compareDates(plannedEnd, actualEnd),
      "the dates view answers \"am I on schedule\" -- the same end date is on schedule")
    assertEquals(ComparisonResult.MORE, compareDurations(plannedDuration, actualDuration),
      "the durations view answers \"did the work grow\" -- five days became ten")
  }

  // ---- Room for the band in the row --------------------------------------------------------

  @Test
  fun `the views that draw without a baseline need room in the row`() {
    // Eight pixels of it, added in `TaskRendererImpl2.calculateRowHeight`. Whoever adds a view
    // and forgets this ends up with a band drawn into the row below -- and would notice only by
    // looking. This test notices instead.
    assertFalse(ChartComparison.DATES.needsBandRoomWithoutBaseline(),
      "the dates view draws nothing without a baseline, so it needs no room")
    assertTrue(ChartComparison.EFFORT.needsBandRoomWithoutBaseline(),
      "the effort view takes both its numbers from the task and always draws")
    assertTrue(ChartComparison.DURATIONS.needsBandRoomWithoutBaseline(),
      "the durations view draws a NEUTRAL band when the baseline is missing")
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
