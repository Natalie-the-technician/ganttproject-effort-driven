/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * "Who is working on this with me" — the one question from the requirements
 * list the app could not answer.
 *
 * The awkward cases are the point of this file. A hand-edited `.gan` file can
 * name a resource that is not there, and GanttProject itself writes role ids
 * whose names it never puts in the file, so both have to be decided rather
 * than discovered on a phone.
 */
class TeammatesTest {

  private fun task(id: String) = TaskNode(
    id = id, uid = null, name = "Task $id", start = LocalDate.parse("2026-03-02"),
    durationDays = 3, completion = 0, isMilestone = false, color = null,
    notes = null, depth = 0, children = emptyList(),
    effortHours = null, actualEffortHours = null, togglMatchKeys = emptyList()
  )

  private fun resource(id: String, name: String) =
    ResourceNode(id, name, null, null, null, null, null)

  private fun model(
    resources: List<ResourceNode> = listOf(
      resource("0", "Anna Bauer"), resource("1", "Ben Klein"), resource("2", "Carla Ott")
    ),
    allocations: List<Allocation> = emptyList(),
    roles: List<Role> = listOf(Role("1", "Messtechnik"))
  ) = ProjectModel(
    name = "T", company = "",
    tasks = listOf(task("1"), task("2")),
    resources = resources,
    allocations = allocations,
    roles = roles,
    calendar = WorkingCalendar.DEFAULT
  )

  // --------------------------------------------------------------- the list

  @Test
  @DisplayName("a task with three assignees names all three, in the file's order")
  fun `three assignees`() {
    val m = model(allocations = listOf(
      Allocation("1", "1", null, false, 50.0),
      Allocation("1", "0", null, true, 100.0),
      Allocation("1", "2", null, false, 25.0),
      Allocation("2", "0", null, true, 25.0)
    ))
    val mates = teammatesOfTask(m, "1")
    assertEquals(listOf("Ben Klein", "Anna Bauer", "Carla Ott"), mates.map { it.name })
  }

  @Test
  @DisplayName("a task with no assignment yields nothing at all, not an empty heading")
  fun `no assignment`() {
    assertEquals(emptyList<Teammate>(), teammatesOfTask(model(), "1"))
  }

  @Test
  @DisplayName("an allocation pointing at a resource the file does not contain survives, unnamed")
  fun `broken resource id`() {
    val m = model(allocations = listOf(
      Allocation("1", "0", null, false, 100.0),
      Allocation("1", "77", null, false, 100.0)
    ))
    val mates = teammatesOfTask(m, "1")
    assertEquals(2, mates.size, "the broken row must not be dropped in silence")
    assertEquals("77", mates[1].resourceId)
    assertNull(mates[1].name, "there is no name to show, and inventing one would be worse")
  }

  @Test
  @DisplayName("an allocation with no resource-id at all is dropped: nothing to name, not even an id")
  fun `empty resource id`() {
    val m = model(allocations = listOf(
      Allocation("1", "", null, false, 100.0),
      Allocation("1", "0", null, false, 100.0)
    ))
    assertEquals(listOf("Anna Bauer"), teammatesOfTask(m, "1").map { it.name })
  }

  @Test
  @DisplayName("a resource whose name is blank counts as unnamed, like a missing one")
  fun `blank resource name`() {
    val m = model(
      resources = listOf(resource("0", "   ")),
      allocations = listOf(Allocation("1", "0", null, false, 100.0))
    )
    assertNull(teammatesOfTask(m, "1").single().name)
  }

  @Test
  @DisplayName("the same person allocated twice by hand appears once")
  fun `duplicate allocation`() {
    val m = model(allocations = listOf(
      Allocation("1", "0", null, false, 50.0),
      Allocation("1", "0", "1", false, 50.0)
    ))
    val mates = teammatesOfTask(m, "1")
    assertEquals(1, mates.size)
    assertNull(mates.single().role, "the first row wins, so the second row's role is not adopted")
  }

  // --------------------------------------------------------------- the role

  @Test
  @DisplayName("a role the file names is resolved through its id")
  fun `named role`() {
    val m = model(allocations = listOf(Allocation("1", "0", "1", false, 100.0)))
    assertEquals(TeammateRole.Named("Messtechnik"), teammatesOfTask(m, "1").single().role)
  }

  @Test
  @DisplayName("Default:1 is GanttProject's built-in project manager, which the file never names")
  fun `built-in project manager`() {
    val m = model(allocations = listOf(Allocation("1", "0", "Default:1", true, 100.0)))
    assertEquals(TeammateRole.ProjectManager, teammatesOfTask(m, "1").single().role)
  }

  @Test
  @DisplayName("Default:0 means undefined, so no role is shown rather than the word 'undefined'")
  fun `default role zero`() {
    val m = model(allocations = listOf(Allocation("1", "0", "Default:0", false, 100.0)))
    assertNull(teammatesOfTask(m, "1").single().role)
  }

  @Test
  @DisplayName("a roleset the app cannot name shows no role, never a raw id")
  fun `unknown roleset`() {
    val m = model(allocations = listOf(Allocation("1", "0", "SoftwareDevelopment:4", false, 100.0)))
    assertNull(teammatesOfTask(m, "1").single().role)
  }

  @Test
  @DisplayName("a missing or blank function is simply no role")
  fun `no function`() {
    val m = model(allocations = listOf(
      Allocation("1", "0", null, false, 100.0),
      Allocation("1", "1", "  ", false, 100.0)
    ))
    assertTrue(teammatesOfTask(m, "1").all { it.role == null })
  }

  @Test
  @DisplayName("a role named in the file wins over the built-in table")
  fun `file role beats built-in`() {
    val m = model(
      allocations = listOf(Allocation("1", "0", "Default:1", false, 100.0)),
      roles = listOf(Role("Default:1", "Bauleitung"))
    )
    assertEquals(TeammateRole.Named("Bauleitung"), teammatesOfTask(m, "1").single().role)
  }

  // ----------------------------------------------------------------- and me

  @Test
  @DisplayName("I am marked, the others are not")
  fun `me is marked`() {
    val m = model(allocations = listOf(
      Allocation("1", "0", null, false, 100.0),
      Allocation("1", "1", null, false, 100.0)
    ))
    val mates = teammatesOfTask(m, "1", meResourceId = "1")
    assertEquals(listOf(false, true), mates.map { it.isMe })
    assertEquals(listOf("Anna Bauer", "Ben Klein"), mates.map { it.name },
      "marking must not reorder: the list stays in the file's order")
  }

  @Test
  @DisplayName("with nobody identified, nobody is marked and the list still shows")
  fun `me unknown`() {
    val m = model(allocations = listOf(Allocation("1", "0", null, false, 100.0)))
    val mates = teammatesOfTask(m, "1", meResourceId = null)
    assertEquals(1, mates.size)
    assertTrue(mates.none { it.isMe })
  }

  @Test
  @DisplayName("alone on a task, I am still shown — 'nobody else' is worth knowing")
  fun `alone on the task`() {
    val m = model(allocations = listOf(Allocation("1", "0", null, false, 100.0)))
    val mates = teammatesOfTask(m, "1", meResourceId = "0")
    assertEquals(1, mates.size)
    assertTrue(mates.single().isMe)
  }

  @Test
  @DisplayName("the name typed for time entries finds the matching resource, ignoring case and spaces")
  fun `resolve me`() {
    assertEquals("1", resolveMe(model(), "  ben klein "))
  }

  @Test
  @DisplayName("a name no resource carries resolves to nobody rather than to the nearest one")
  fun `resolve me no match`() {
    assertNull(resolveMe(model(), "B. Klein"))
  }

  @Test
  @DisplayName("two resources with the same name resolve to nobody: a coin toss would mark the wrong person")
  fun `resolve me ambiguous`() {
    val m = model(resources = listOf(resource("0", "Ben Klein"), resource("5", "ben klein")))
    assertNull(resolveMe(m, "Ben Klein"))
  }

  @Test
  @DisplayName("no name set, no identification")
  fun `resolve me blank`() {
    assertNull(resolveMe(model(), null))
    assertNull(resolveMe(model(), "   "))
  }
}
