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

/**
 * Folding task groups, stored in the same `expand` attribute the desktop
 * writes so the outline state survives a trip in either direction.
 */
class CollapseTest {

  private fun sample(): String =
    checkNotNull(javaClass.getResourceAsStream("/HouseBuildingSample.gan")) {
      "sample project missing from the test classpath"
    }.readBytes().toString(Charsets.UTF_8)

  private fun load() = GanttDocument.parse(sample())

  private fun reload(document: GanttDocument) = GanttDocument.parse(document.toXmlString())

  @Test
  fun `the sample is fully expanded, and everything is visible`() {
    val model = load().read()
    assertTrue(model.flatTasks.all { it.isExpanded })
    assertEquals(model.flatTasks.size, model.visibleTasks.size)
  }

  @Test
  fun `a missing expand attribute counts as expanded`() {
    // Tasks written before the attribute existed must not vanish.
    val document = GanttDocument.parse(
      """<project name="X"><tasks>
           <task id="1" name="Parent" start="2026-03-02" duration="5">
             <task id="2" name="Child" start="2026-03-02" duration="5"/>
           </task>
         </tasks></project>"""
    )
    val model = document.read()
    assertTrue(model.task("1")!!.isExpanded)
    assertEquals(2, model.visibleTasks.size)
  }

  @Test
  @DisplayName("folding a group hides its subtasks but keeps them in the project")
  fun `folding hides children from the outline only`() {
    val document = load()
    assertTrue(document.setTaskExpanded("0", false))
    val model = document.read()

    assertFalse(model.task("0")!!.isExpanded)
    // Task 0 has three subtasks: 9, 10 and 17.
    assertTrue(model.visibleTasks.none { it.id in setOf("9", "10", "17") })
    assertTrue(model.visibleTasks.any { it.id == "0" }, "the group itself stays visible")

    // Everything that reasons about the project must still see them all;
    // otherwise a folded group would quietly drop out of resource load,
    // totals and the list of import targets.
    assertTrue(model.flatTasks.any { it.id == "9" })
    assertEquals(model.flatTasks.size - 3, model.visibleTasks.size)
  }

  @Test
  @DisplayName("a folded task is still found and still counted everywhere else")
  fun `folding is a view state, not a filter on the project`() {
    // Folding must change what is drawn and nothing else. A counter-test that
    // pointed task() at the visible list went unnoticed by the whole suite,
    // which is what these assertions are for: with that change, a folded
    // group would silently drop out of resource load, of the task sheet and
    // of the list of import targets.
    val document = load()
    document.setTaskExpanded("0", false) // hides 9, 10, 17
    val model = document.read()

    assertTrue(model.visibleTasks.none { it.id == "9" }, "precondition: 9 is hidden")

    assertEquals("Create draft of architecture", model.task("9")?.name, "lookup must still find it")
    assertTrue(
      model.allocationsOfTask("9").isNotEmpty(),
      "assignments of a folded task must still be reachable"
    )

    // Resource 1 works on task 9 at 50%; folding must not change its load.
    val foldedLoad = computeResourceLoad(model, "1")
    val openLoad = computeResourceLoad(load().read(), "1")
    assertEquals(
      openLoad.totalCommittedHours,
      foldedLoad.totalCommittedHours,
      1e-9,
      "folding a group changed the resource load"
    )
  }

  @Test
  fun `folding a nested group hides only its own branch`() {
    val document = load()
    document.setTaskExpanded("11", false) // "Interior design", holds 12, 13, 14
    val visible = document.read().visibleTasks.map { it.id }
    assertTrue(visible.none { it in setOf("12", "13", "14") })
    assertTrue(visible.contains("9"), "a sibling branch must stay open")
  }

  @Test
  fun `the folded state survives a save and reload`() {
    val document = load()
    document.setTaskExpanded("0", false)
    val written = document.toXmlString()
    assertTrue(written.contains("""expand="false""""), "attribute not written")
    assertFalse(reload(document).read().task("0")!!.isExpanded)
  }

  @Test
  fun `unfolding restores the subtasks`() {
    val document = load()
    document.setTaskExpanded("0", false)
    document.setTaskExpanded("0", true)
    assertEquals(load().read().flatTasks.size, document.read().visibleTasks.size)
  }

  @Test
  fun `a leaf task cannot be folded`() {
    // Nothing to hide, and writing expand="false" on a leaf would be a lie
    // that the desktop then renders as a collapsed group with no content.
    assertFalse(load().setTaskExpanded("13", false))
  }

  @Test
  fun `folding an unknown task is refused`() {
    assertFalse(load().setTaskExpanded("no-such-task", false))
  }

  @Test
  fun `folding changes nothing else in the file`() {
    val document = load()
    document.setTaskExpanded("0", false)
    val written = document.toXmlString()
    assertTrue(written.contains("""<view id="resource-table">"""), "view lost")
    assertTrue(written.contains("""<role id="5" name="Roofer"/>"""), "role lost")
    assertTrue(written.contains("Embedded devices"), "note lost")
  }
}
