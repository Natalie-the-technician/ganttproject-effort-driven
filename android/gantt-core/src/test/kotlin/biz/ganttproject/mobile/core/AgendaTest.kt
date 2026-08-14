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
 * The agenda decides what a home-screen widget shows. Getting it wrong means
 * either a wall of irrelevant rows or, worse, quietly hiding work that is
 * already late.
 */
class AgendaTest {

  private val today = LocalDate.of(2026, 3, 11) // a Wednesday

  private fun task(
    id: String,
    start: String,
    duration: Int = 5,
    completion: Int = 0,
    milestone: Boolean = false,
    children: List<TaskNode> = emptyList()
  ) = TaskNode(
    id = id, uid = null, name = "Task $id", start = LocalDate.parse(start),
    durationDays = duration, completion = completion, isMilestone = milestone,
    color = null, notes = null, depth = 0, children = children,
    effortHours = null, actualEffortHours = null, togglMatchKeys = emptyList()
  )

  private fun model(vararg tasks: TaskNode) = ProjectModel(
    name = "T", company = "", tasks = tasks.toList(), resources = emptyList(),
    allocations = emptyList(), roles = emptyList(), calendar = WorkingCalendar.DEFAULT
  )

  @Test
  fun `a running task is listed`() {
    val m = model(task("1", "2026-03-09", duration = 5))
    val item = agenda(m, today, 14).single()
    assertTrue(item.isRunning)
    assertFalse(item.isOverdue)
    assertFalse(item.isUpcoming)
  }

  @Test
  fun `a task starting inside the window is listed as upcoming`() {
    val m = model(task("1", "2026-03-20"))
    val item = agenda(m, today, 14).single()
    assertTrue(item.isUpcoming)
    assertFalse(item.isRunning)
  }

  @Test
  fun `a task starting beyond the window is left out`() {
    val m = model(task("1", "2026-04-30"))
    assertTrue(agenda(m, today, 14).isEmpty())
  }

  @Test
  fun `the window is inclusive on its last day`() {
    val m = model(task("1", "2026-03-25")) // exactly today + 14
    assertEquals(1, agenda(m, today, 14).size)
  }

  @Test
  fun `a window of zero shows only what has already started`() {
    val m = model(task("1", "2026-03-09"), task("2", "2026-03-12"))
    assertEquals(listOf("1"), agenda(m, today, 0).map { it.task.id })
  }

  @Test
  @DisplayName("an overdue task is shown, and shown first")
  fun `overdue work is never hidden`() {
    // The whole point: a task that should have finished in January and did
    // not is the most important line on the list. A filter that only kept
    // "recent" tasks would drop exactly the work that slipped.
    val m = model(
      task("late", "2026-01-05", duration = 5),
      task("soon", "2026-03-20")
    )
    val items = agenda(m, today, 14)
    assertEquals(listOf("late", "soon"), items.map { it.task.id })
    assertTrue(items.first().isOverdue)
  }

  @Test
  fun `finished tasks are left out`() {
    val m = model(task("1", "2026-03-09", completion = 100), task("2", "2026-03-09"))
    assertEquals(listOf("2"), agenda(m, today, 14).map { it.task.id })
  }

  @Test
  fun `a partly finished task is still listed`() {
    val m = model(task("1", "2026-03-09", completion = 60))
    assertEquals(1, agenda(m, today, 14).size)
  }

  @Test
  fun `summary tasks are left out but their children are not`() {
    // Ticking a summary task off would achieve nothing: GanttProject derives
    // its completion from the children.
    val child = task("child", "2026-03-09")
    val parent = task("parent", "2026-03-09", children = listOf(child))
    val items = agenda(model(parent), today, 14)
    assertEquals(listOf("child"), items.map { it.task.id })
  }

  @Test
  fun `milestones are kept`() {
    val m = model(task("m", "2026-03-13", duration = 0, milestone = true))
    assertEquals(1, agenda(m, today, 14).size)
  }

  @Test
  fun `the end date accounts for weekends`() {
    // Started Monday 9 March for 5 working days -> ends Friday 13 March.
    val m = model(task("1", "2026-03-09", duration = 5))
    assertEquals(LocalDate.of(2026, 3, 13), agenda(m, today, 14).single().endDate)
  }

  @Test
  fun `a task ending today is not yet overdue`() {
    val m = model(task("1", "2026-03-09", duration = 3)) // Mon..Wed = today
    val item = agenda(m, today, 14).single()
    assertFalse(item.isOverdue, "a task still has the whole of its last day")
    assertTrue(item.isRunning)
  }

  @Test
  fun `ordering is stable for tasks sharing a start date`() {
    val m = model(task("b", "2026-03-12"), task("a", "2026-03-12"))
    repeat(5) { assertEquals(listOf("a", "b"), agenda(m, today, 14).map { it.task.id }) }
  }

  @Test
  fun `a negative window is treated as zero rather than hiding everything`() {
    val m = model(task("1", "2026-03-09"))
    assertEquals(1, agenda(m, today, -5).size)
  }
}
