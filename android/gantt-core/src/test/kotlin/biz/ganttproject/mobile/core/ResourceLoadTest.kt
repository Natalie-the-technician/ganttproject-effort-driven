/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Overload detection is the thing stock GanttProject cannot do — its load
 * percentage has no effect on scheduling at all — so it needs its own tests
 * rather than riding along on the document tests.
 */
class ResourceLoadTest {

  private fun task(id: String, start: String, duration: Int, children: List<TaskNode> = emptyList()) =
    TaskNode(
      id = id, uid = null, name = "Task $id", start = LocalDate.parse(start),
      durationDays = duration, completion = 0, isMilestone = false, color = null,
      notes = null, depth = 0, children = children,
      effortHours = null, actualEffortHours = null, togglMatchKeys = emptyList()
    )

  private fun model(
    tasks: List<TaskNode>,
    hoursPerDay: Double = 8.0,
    allocations: List<Allocation>
  ) = ProjectModel(
    name = "T", company = "",
    tasks = tasks,
    resources = listOf(ResourceNode("r1", "Alice", null, null, null, null, hoursPerDay)),
    allocations = allocations,
    roles = emptyList(),
    calendar = WorkingCalendar.DEFAULT
  )

  @Test
  fun `a single full assignment is exactly full, not overloaded`() {
    val m = model(
      listOf(task("1", "2026-03-02", 3)),
      allocations = listOf(Allocation("1", "r1", null, false, 100.0))
    )
    val report = computeResourceLoad(m, "r1")
    assertEquals(3, report.days.size)
    assertEquals(8.0, report.days.first().committedHours, 1e-9)
    assertFalse(report.hasOverload, "100% on one task is not an overload")
    assertEquals(100.0, report.peakUtilisationPercent, 1e-9)
  }

  @Test
  @DisplayName("two overlapping full assignments are flagged as overload")
  fun `overlapping assignments overload the resource`() {
    // This is precisely the situation stock GanttProject shows no warning for.
    val m = model(
      listOf(task("1", "2026-03-02", 3), task("2", "2026-03-03", 3)),
      allocations = listOf(
        Allocation("1", "r1", null, false, 100.0),
        Allocation("2", "r1", null, false, 100.0)
      )
    )
    val report = computeResourceLoad(m, "r1")
    assertTrue(report.hasOverload)
    // 2 and 5 March carry one task each, 3 and 4 March carry both.
    assertEquals(2, report.overloadedDays.size)
    assertEquals(16.0, report.overloadedDays.first().committedHours, 1e-9)
    assertEquals(200.0, report.peakUtilisationPercent, 1e-9)
  }

  @Test
  fun `two half assignments fit exactly`() {
    val m = model(
      listOf(task("1", "2026-03-02", 2), task("2", "2026-03-02", 2)),
      allocations = listOf(
        Allocation("1", "r1", null, false, 50.0),
        Allocation("2", "r1", null, false, 50.0)
      )
    )
    val report = computeResourceLoad(m, "r1")
    assertFalse(report.hasOverload)
    assertEquals(8.0, report.days.first().committedHours, 1e-9)
  }

  @Test
  fun `a lower hours per day makes the same plan an overload`() {
    // The whole point of the hours_per_day field: a part-time resource is
    // overbooked by a plan that is fine for a full-time one.
    val allocations = listOf(
      Allocation("1", "r1", null, false, 100.0),
      Allocation("2", "r1", null, false, 50.0)
    )
    val tasks = listOf(task("1", "2026-03-02", 2), task("2", "2026-03-02", 2))
    assertTrue(computeResourceLoad(model(tasks, 8.0, allocations), "r1").hasOverload)
    // Capacity scales with the field, so the ratio is unchanged — the point
    // is that both numbers move together and stay comparable.
    assertEquals(
      150.0,
      computeResourceLoad(model(tasks, 4.0, allocations), "r1").peakUtilisationPercent,
      1e-9
    )
  }

  @Test
  fun `weekends carry no load`() {
    val m = model(
      listOf(task("1", "2026-03-06", 2)), // Friday plus the following Monday
      allocations = listOf(Allocation("1", "r1", null, false, 100.0))
    )
    val dates = computeResourceLoad(m, "r1").days.map { it.date }
    assertEquals(listOf(LocalDate.of(2026, 3, 6), LocalDate.of(2026, 3, 9)), dates)
  }

  @Test
  fun `milestones demand nothing`() {
    val m = model(
      listOf(task("1", "2026-03-02", 0)),
      allocations = listOf(Allocation("1", "r1", null, false, 100.0))
    )
    assertTrue(computeResourceLoad(m, "r1").days.isEmpty())
  }

  @Test
  fun `a summary task does not double count its children`() {
    val child = task("2", "2026-03-02", 2)
    val parent = task("1", "2026-03-02", 2, children = listOf(child))
    val m = model(
      listOf(parent),
      allocations = listOf(
        Allocation("1", "r1", null, false, 100.0),
        Allocation("2", "r1", null, false, 100.0)
      )
    )
    val report = computeResourceLoad(m, "r1")
    assertFalse(report.hasOverload, "the parent's assignment must not be counted as well")
    assertEquals(8.0, report.days.first().committedHours, 1e-9)
  }

  @Test
  fun `an unknown resource yields an empty report rather than an error`() {
    val m = model(listOf(task("1", "2026-03-02", 1)), allocations = emptyList())
    assertTrue(computeResourceLoad(m, "ghost").days.isEmpty())
  }

  @Test
  fun `the window restricts the reported days`() {
    val m = model(
      listOf(task("1", "2026-03-02", 10)),
      allocations = listOf(Allocation("1", "r1", null, false, 100.0))
    )
    val window = LocalDate.of(2026, 3, 4)..LocalDate.of(2026, 3, 6)
    assertEquals(3, computeResourceLoad(m, "r1", window).days.size)
  }
}
