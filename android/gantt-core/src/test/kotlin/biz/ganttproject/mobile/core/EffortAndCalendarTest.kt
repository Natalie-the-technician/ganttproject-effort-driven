/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate

class WorkingCalendarTest {

  private val calendar = WorkingCalendar.DEFAULT

  @Test
  fun `weekends are not working days`() {
    assertTrue(calendar.isWorkingDay(LocalDate.of(2026, 3, 6)), "Friday")
    assertFalse(calendar.isWorkingDay(LocalDate.of(2026, 3, 7)), "Saturday")
    assertFalse(calendar.isWorkingDay(LocalDate.of(2026, 3, 8)), "Sunday")
  }

  @Test
  fun `a ten day task starting Monday ends the Friday of the next week`() {
    // The single most consequential rule in the whole chart: get this wrong
    // and every bar and every date label is off.
    assertEquals(
      LocalDate.of(2026, 3, 13),
      calendar.lastWorkingDay(LocalDate.of(2026, 3, 2), 10)
    )
  }

  @Test
  fun `a one day task ends on its start day`() {
    assertEquals(LocalDate.of(2026, 3, 2), calendar.lastWorkingDay(LocalDate.of(2026, 3, 2), 1))
  }

  @Test
  fun `a milestone occupies its start day only`() {
    assertEquals(LocalDate.of(2026, 3, 2), calendar.lastWorkingDay(LocalDate.of(2026, 3, 2), 0))
    assertEquals(1, calendar.workingDaysOf(LocalDate.of(2026, 3, 2), 0).size)
  }

  @Test
  fun `a task starting on a Saturday effectively starts on the Monday`() {
    assertEquals(LocalDate.of(2026, 3, 2), calendar.nextWorkingDay(LocalDate.of(2026, 2, 28)))
  }

  @Test
  fun `holidays are skipped`() {
    val withHoliday = WorkingCalendar(
      weekendDays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
      holidays = setOf(LocalDate.of(2026, 3, 4))
    )
    // Mon 2, Tue 3, [Wed 4 holiday], Thu 5 -> three working days end on the 5th.
    assertEquals(LocalDate.of(2026, 3, 5), withHoliday.lastWorkingDay(LocalDate.of(2026, 3, 2), 3))
  }

  @Test
  fun `working days of a task are listed in order and exclude weekends`() {
    val days = calendar.workingDaysOf(LocalDate.of(2026, 3, 6), 3)
    assertEquals(
      listOf(LocalDate.of(2026, 3, 6), LocalDate.of(2026, 3, 9), LocalDate.of(2026, 3, 10)),
      days
    )
  }

  @Test
  fun `counting working days is inclusive at both ends`() {
    assertEquals(5, calendar.countWorkingDays(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 6)))
    assertEquals(0, calendar.countWorkingDays(LocalDate.of(2026, 3, 6), LocalDate.of(2026, 3, 2)))
  }

  @Test
  fun `a calendar with no working days at all does not hang`() {
    // A malformed file could mark every weekday as off. The guard turns that
    // into a wrong-looking chart instead of a frozen UI thread.
    val nothing = WorkingCalendar(DayOfWeek.entries.toSet(), emptySet())
    assertEquals(LocalDate.of(2026, 3, 2), nothing.nextWorkingDay(LocalDate.of(2026, 3, 2)))
  }

  @Test
  fun `a calendar without weekends counts calendar days`() {
    assertEquals(
      LocalDate.of(2026, 3, 11),
      WorkingCalendar.ALL_DAYS.lastWorkingDay(LocalDate.of(2026, 3, 2), 10)
    )
  }
}

class EffortMathTest {

  @Test
  fun `the worked example from the project brief`() {
    // 20 hours at 2 h/day is 10 days; switch to 4 h/day and it is 5 days.
    assertEquals(10, computeDurationDays(20.0, 2.0))
    assertEquals(5, computeDurationDays(20.0, 4.0))
  }

  @Test
  @DisplayName("a partial day is rounded up, because a started day is an occupied day")
  fun `partial days round up`() {
    assertEquals(3, computeDurationDays(17.0, 8.0))
    assertEquals(1, computeDurationDays(0.5, 8.0))
  }

  @Test
  fun `non-positive input is rejected rather than silently wrong`() {
    assertTrue(runCatching { computeDurationDays(0.0, 8.0) }.isFailure)
    assertTrue(runCatching { computeDurationDays(-5.0, 8.0) }.isFailure)
    assertTrue(runCatching { computeDurationDays(8.0, 0.0) }.isFailure)
    assertTrue(runCatching { computeDurationDays(8.0, -2.0) }.isFailure)
  }

  private fun resource(id: String, hoursPerDay: Double?) =
    ResourceNode(id, "R$id", null, null, null, null, hoursPerDay)

  @Test
  fun `availability sums assignments weighted by load`() {
    val resources = mapOf("1" to resource("1", 8.0), "2" to resource("2", 6.0))
    val allocations = listOf(
      Allocation("t", "1", null, false, 50.0),
      Allocation("t", "2", null, false, 100.0)
    )
    assertEquals(10.0, availableHoursPerDay(allocations) { resources[it] }, 1e-9)
  }

  @Test
  fun `load is actually applied`() {
    val resources = mapOf("1" to resource("1", 8.0))
    val full = availableHoursPerDay(listOf(Allocation("t", "1", null, false, 100.0))) { resources[it] }
    val half = availableHoursPerDay(listOf(Allocation("t", "1", null, false, 50.0))) { resources[it] }
    assertEquals(8.0, full, 1e-9)
    assertEquals(4.0, half, 1e-9)
  }

  @Test
  fun `no assignments means no availability`() {
    assertEquals(0.0, availableHoursPerDay(emptyList()) { null })
  }

  @Test
  fun `an assignment to a deleted resource contributes nothing instead of crashing`() {
    val allocations = listOf(Allocation("t", "ghost", null, false, 100.0))
    assertEquals(0.0, availableHoursPerDay(allocations) { null })
  }

  @Test
  fun `a resource without hours per day falls back to eight`() {
    assertEquals(8.0, resource("1", null).effectiveHoursPerDay)
    assertEquals(8.0, resource("1", 0.0).effectiveHoursPerDay, "a zero value is not a capacity")
    assertEquals(8.0, resource("1", -3.0).effectiveHoursPerDay)
  }
}

class EffortInputTest {

  @Test
  fun `accepts both decimal separators`() {
    assertEquals(7.5, parseEffortInput("7,5"))
    assertEquals(7.5, parseEffortInput("7.5"))
    assertEquals(8.0, parseEffortInput(" 8 "))
  }

  @Test
  fun `rejects input that would otherwise overwrite a good value with nonsense`() {
    assertNull(parseEffortInput(null))
    assertNull(parseEffortInput(""))
    assertNull(parseEffortInput("   "))
    assertNull(parseEffortInput("acht"))
    assertNull(parseEffortInput("-3"))
    assertNull(parseEffortInput("8h"))
  }

  @Test
  fun `formats without trailing zeros`() {
    assertEquals("8", formatHours(8.0))
    assertEquals("7.5", formatHours(7.5))
    assertEquals("7,5", formatHours(7.5, decimalSeparator = ','))
    assertEquals("—", formatHours(null))
  }

  @Test
  fun `match keys survive encode and decode`() {
    val keys = listOf("first key", "second, with comma")
    assertEquals(keys, ForkProperties.decodeMatchKeys(ForkProperties.encodeMatchKeys(keys)))
  }

  @Test
  fun `duplicate and blank match keys are dropped`() {
    val encoded = ForkProperties.encodeMatchKeys(listOf("a", "a", "  ", "b"))
    assertEquals(listOf("a", "b"), ForkProperties.decodeMatchKeys(encoded))
  }

  @Test
  fun `a key containing the separator does not create a phantom key`() {
    val encoded = ForkProperties.encodeMatchKeys(listOf("a|b"))
    assertEquals(listOf("a b"), ForkProperties.decodeMatchKeys(encoded))
  }
}
