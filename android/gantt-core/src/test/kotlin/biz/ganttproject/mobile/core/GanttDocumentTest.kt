/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Covers reading and — more importantly — writing back without losing data.
 *
 * The fixture is the real `HouseBuildingSample.gan` shipped with desktop
 * GanttProject, not a hand-written miniature. A self-made fixture would
 * contain exactly the fields the reader already knows about and would
 * therefore prove nothing about round-trip safety.
 */
class GanttDocumentTest {

  private fun sample(): String =
    checkNotNull(javaClass.getResourceAsStream("/HouseBuildingSample.gan")) {
      "sample project missing from the test classpath"
    }.readBytes().toString(Charsets.UTF_8)

  private fun load() = GanttDocument.parse(sample())

  // --------------------------------------------------------------- Reading

  @Test
  fun `reads the task hierarchy`() {
    val model = load().read()
    assertEquals(5, model.tasks.size, "five top-level tasks")
    val design = model.tasks.first { it.id == "0" }
    assertEquals("Architectural design", design.name)
    assertEquals(3, design.children.size)
    assertEquals(1, design.children.first().depth)
    assertFalse(design.isLeaf)
    assertTrue(design.children.first().isLeaf)
  }

  @Test
  fun `reads start date, duration and completion`() {
    val task = load().read().task("9")!!
    assertEquals(LocalDate.of(2024, 5, 27), task.start)
    assertEquals(10, task.durationDays)
    assertEquals(100, task.completion)
    assertFalse(task.isMilestone)
  }

  @Test
  fun `recognises milestones`() {
    val model = load().read()
    assertTrue(model.task("17")!!.isMilestone)
    assertFalse(model.task("9")!!.isMilestone)
  }

  @Test
  fun `reads notes`() {
    val notes = load().read().task("14")!!.notes
    assertNotNull(notes)
    assertTrue(notes!!.contains("kitchen"), "notes were: $notes")
  }

  @Test
  fun `reads resources with role and rate`() {
    val model = load().read()
    assertEquals(7, model.resources.size)
    val michelangelo = model.resource("2")!!
    assertEquals("Michelangelo", michelangelo.name)
    assertEquals("0", michelangelo.roleId)
    assertEquals("1000", michelangelo.standardRate)
  }

  @Test
  fun `resource without hours_per_day falls back to eight hours`() {
    val resource = load().read().resource("1")!!
    assertNull(resource.hoursPerDay, "the stock sample does not know the fork field")
    assertEquals(8.0, resource.effectiveHoursPerDay)
  }

  @Test
  fun `reads assignments including load`() {
    val model = load().read()
    val allocation = model.allocations.first { it.taskId == "9" && it.resourceId == "1" }
    assertEquals(50.0, allocation.load)
    assertFalse(allocation.responsible)
    assertTrue(model.allocations.first { it.taskId == "12" && it.resourceId == "1" }.responsible)
  }

  @Test
  fun `reads roles`() {
    val model = load().read()
    assertEquals("Architect", model.roles.first { it.id == "0" }.name)
    assertEquals(6, model.roles.size)
  }

  @Test
  fun `reads the working calendar from the file`() {
    val calendar = load().read().calendar
    // <default-week sun="1" ... sat="1"/> — Saturday and Sunday are off.
    assertFalse(calendar.isWorkingDay(LocalDate.of(2024, 5, 25)), "Saturday")
    assertFalse(calendar.isWorkingDay(LocalDate.of(2024, 5, 26)), "Sunday")
    assertTrue(calendar.isWorkingDay(LocalDate.of(2024, 5, 27)), "Monday")
  }

  @Test
  @DisplayName("duration counts working days — proven by the file's own dependency")
  fun `duration counts working days`() {
    val model = load().read()
    val task = model.task("9")!!
    // Task 9 starts Monday 27 May and lasts 10 working days. Its successor
    // starts on Monday 10 June according to the file. Were the duration in
    // calendar days, task 9 would already have ended on 5 June.
    assertEquals(
      LocalDate.of(2024, 6, 7),
      model.calendar.lastWorkingDay(task.start, task.durationDays)
    )
    assertEquals(LocalDate.of(2024, 6, 10), model.task("10")!!.start)
  }

  // --------------------------------------------------- Lossless round trip

  @Test
  fun `saving without changes preserves everything the model does not know`() {
    val written = load().toXmlString()

    assertTrue(written.contains("""<view id="resource-table">"""), "view lost")
    assertTrue(written.contains("""gantt-divider-location="693""""), "divider position lost")
    assertTrue(written.contains("""<field id="tpd3" name="Name" width="257""""), "column width lost")
    assertTrue(written.contains("""<vacation start="2009-02-02""""), "vacation lost")
    assertTrue(written.contains("""<role id="5" name="Roofer"/>"""), "role lost")
    assertTrue(
      written.contains("""<date year="2006" month="2" date="14" type="HOLIDAY"/>"""),
      "holiday lost"
    )
    assertTrue(written.contains("""<rate name="standard" value="1000"/>"""), "pay rate lost")
    assertTrue(written.contains("""thirdDate="2022-02-24""""), "baseline date lost")
    assertTrue(written.contains("""hardness="Rubber""""), "dependency hardness lost")
    assertTrue(written.contains("<simple-select"), "calculated column lost")
    assertTrue(written.contains("CDATA"), "CDATA sections lost")
    assertTrue(written.contains("Embedded devices"), "note text lost")
  }

  @Test
  fun `model is unchanged after save and reload`() {
    val before = load().read()
    val after = GanttDocument.parse(load().toXmlString()).read()
    assertEquals(before.flatTasks.map { it.id }, after.flatTasks.map { it.id })
    assertEquals(before.flatTasks.map { it.name }, after.flatTasks.map { it.name })
    assertEquals(before.flatTasks.map { it.start }, after.flatTasks.map { it.start })
    assertEquals(before.flatTasks.map { it.durationDays }, after.flatTasks.map { it.durationDays })
    assertEquals(before.flatTasks.map { it.completion }, after.flatTasks.map { it.completion })
    assertEquals(before.flatTasks.map { it.notes }, after.flatTasks.map { it.notes })
    assertEquals(before.resources, after.resources)
    assertEquals(before.allocations, after.allocations)
    assertEquals(before.roles, after.roles)
  }

  @Test
  fun `saving twice produces identical output`() {
    val once = load().toXmlString()
    val twice = GanttDocument.parse(once).toXmlString()
    assertEquals(once, twice, "output must stabilise, otherwise the file grows on every save")
  }

  // -------------------------------------------------------------- Mutation

  @Test
  fun `completion is written and reads back`() {
    val document = load()
    assertTrue(document.setTaskCompletion("13", 42))
    assertEquals(42, GanttDocument.parse(document.toXmlString()).read().task("13")!!.completion)
  }

  @Test
  fun `completion of a summary task is refused`() {
    val document = load()
    // Task 0 has children. GanttProject recomputes its completion from them
    // on load, so writing it would show the user a change that then vanishes.
    assertFalse(document.setTaskCompletion("0", 10))
    assertEquals(85, GanttDocument.parse(document.toXmlString()).read().task("0")!!.completion)
  }

  @Test
  fun `completion is clamped to 0 to 100`() {
    val document = load()
    document.setTaskCompletion("13", 500)
    assertEquals(100, GanttDocument.parse(document.toXmlString()).read().task("13")!!.completion)
    document.setTaskCompletion("13", -7)
    assertEquals(0, GanttDocument.parse(document.toXmlString()).read().task("13")!!.completion)
  }

  @Test
  fun `effort hours create the definition and survive a save`() {
    val document = load()
    assertTrue(document.setTaskEffortHours("13", 20.0))
    val written = document.toXmlString()
    assertTrue(
      written.contains("""name="effort_hours""""),
      "the definition must be written too, otherwise the value cannot be read back"
    )
    assertTrue(written.contains("""valuetype="double""""))
    assertEquals(20.0, GanttDocument.parse(written).read().task("13")!!.effortHours)
  }

  @Test
  fun `a new definition gets a free id`() {
    val document = load()
    // The sample already uses tpc0 for its "Is done" column.
    document.setTaskEffortHours("13", 5.0)
    val written = document.toXmlString()
    assertTrue(written.contains("""<taskproperty id="tpc0" name="Is done""""), "existing def kept")
    assertTrue(written.contains("""id="tpc1" name="effort_hours""""), "new def gets tpc1")
  }

  @Test
  fun `an existing custom property on the same task is not clobbered`() {
    val document = load()
    document.setTaskEffortHours("9", 12.0)
    // Task 9 already carried tpc0 = true; that value must stay.
    assertTrue(
      document.toXmlString().contains("""<customproperty taskproperty-id="tpc0" value="true"/>"""),
      "the pre-existing value was overwritten"
    )
  }

  @Test
  fun `actual hours can be set and removed again`() {
    val document = load()
    document.setTaskActualEffortHours("13", 7.5)
    assertEquals(7.5, GanttDocument.parse(document.toXmlString()).read().task("13")!!.actualEffortHours)
    document.setTaskActualEffortHours("13", null)
    assertNull(GanttDocument.parse(document.toXmlString()).read().task("13")!!.actualEffortHours)
  }

  @Test
  fun `actual hours accumulate instead of overwriting`() {
    val document = load()
    assertEquals(3.0, document.addTaskActualEffortHours("13", 3.0))
    assertEquals(5.5, document.addTaskActualEffortHours("13", 2.5))
    assertEquals(5.5, GanttDocument.parse(document.toXmlString()).read().task("13")!!.actualEffortHours)
  }

  @Test
  fun `hours per day on a resource survives a save`() {
    val document = load()
    assertTrue(document.setResourceHoursPerDay("1", 4.0))
    val written = document.toXmlString()
    assertTrue(written.contains("""<custom-property-definition id="tpc0" name="hours_per_day""""))
    assertTrue(written.contains("""<custom-property definition-id="tpc0" value="4"/>"""))
    val reloaded = GanttDocument.parse(written).read()
    assertEquals(4.0, reloaded.resource("1")!!.hoursPerDay)
    assertEquals(4.0, reloaded.resource("1")!!.effectiveHoursPerDay)
  }

  @Test
  fun `resource property definitions precede the resources`() {
    val document = load()
    document.setResourceHoursPerDay("1", 6.0)
    val written = document.toXmlString()
    // The desktop writer puts definitions before the <resource> elements.
    // Matching that keeps our output indistinguishable from a desktop save.
    assertTrue(
      written.indexOf("custom-property-definition") < written.indexOf("""<resource id="""),
      "definition must come before the first resource"
    )
  }

  @Test
  fun `assignment load can be changed`() {
    val document = load()
    assertTrue(document.setAllocationLoad("9", "1", 75.0))
    val reloaded = GanttDocument.parse(document.toXmlString()).read()
    assertEquals(75.0, reloaded.allocations.first { it.taskId == "9" && it.resourceId == "1" }.load)
  }

  @Test
  fun `non-positive assignment load is refused`() {
    val document = load()
    assertFalse(document.setAllocationLoad("9", "1", 0.0))
    assertFalse(document.setAllocationLoad("9", "1", -20.0))
    val reloaded = GanttDocument.parse(document.toXmlString()).read()
    assertEquals(50.0, reloaded.allocations.first { it.taskId == "9" && it.resourceId == "1" }.load)
  }

  @Test
  fun `assigning a resource inherits its role`() {
    val document = load()
    assertTrue(document.assignResource("13", "6", load = 60.0, responsible = true))
    val allocation = GanttDocument.parse(document.toXmlString()).read()
      .allocations.first { it.taskId == "13" && it.resourceId == "6" }
    assertEquals(60.0, allocation.load)
    assertTrue(allocation.responsible)
    assertEquals("2", allocation.function, "role taken from the resource")
  }

  @Test
  fun `assigning twice does not create a duplicate`() {
    val document = load()
    document.assignResource("9", "1", load = 30.0)
    val matches = GanttDocument.parse(document.toXmlString()).read()
      .allocations.filter { it.taskId == "9" && it.resourceId == "1" }
    assertEquals(1, matches.size, "there must be exactly one assignment per pair")
    assertEquals(30.0, matches.single().load)
  }

  @Test
  fun `assigning an unknown task or resource is refused`() {
    val document = load()
    assertFalse(document.assignResource("no-such-task", "1"))
    assertFalse(document.assignResource("13", "no-such-resource"))
  }

  @Test
  fun `unassigning removes only the one assignment`() {
    val document = load()
    assertTrue(document.unassignResource("9", "1"))
    val reloaded = GanttDocument.parse(document.toXmlString()).read()
    assertTrue(reloaded.allocations.none { it.taskId == "9" && it.resourceId == "1" })
    assertTrue(
      reloaded.allocations.any { it.taskId == "9" && it.resourceId == "2" },
      "other assignments of the same task must survive"
    )
  }

  @Test
  fun `match keys are stored and read back`() {
    val document = load()
    document.addTaskMatchKey("13", "pick furniture")
    document.addTaskMatchKey("13", "furniture selection call")
    assertEquals(
      listOf("pick furniture", "furniture selection call"),
      GanttDocument.parse(document.toXmlString()).read().task("13")!!.togglMatchKeys
    )
  }

  @Test
  fun `a match key containing commas stays one key`() {
    val document = load()
    // Time-entry descriptions contain commas constantly. Were the comma the
    // separator, this single key would decay into three useless fragments.
    document.addTaskMatchKey("13", "furniture, lamps, rugs")
    assertEquals(
      listOf("furniture, lamps, rugs"),
      GanttDocument.parse(document.toXmlString()).read().task("13")!!.togglMatchKeys
    )
  }

  // ---------------------------------------------------------------- Errors

  @Test
  fun `a file whose root is not project is refused`() {
    val error = runCatching { GanttDocument.parse("<html><body>no</body></html>") }.exceptionOrNull()
    assertTrue(error is GanttFormatException, "expected GanttFormatException, got $error")
    assertEquals(GanttFormatException.Reason.NOT_A_PROJECT, (error as GanttFormatException).reason)
  }

  @Test
  fun `malformed XML is refused`() {
    val error = runCatching { GanttDocument.parse("<project><tasks>") }.exceptionOrNull()
    assertTrue(error is GanttFormatException, "expected GanttFormatException, got $error")
    assertEquals(GanttFormatException.Reason.NOT_XML, (error as GanttFormatException).reason)
  }

  @Test
  fun `external entities are not resolved`() {
    // A project file can arrive from a shared folder or a chat message. If
    // the parser resolved external entities, opening one could exfiltrate
    // local files into the project the user then saves and shares back.
    val hostile = """<?xml version="1.0"?>
      <!DOCTYPE project [ <!ENTITY secret SYSTEM "file:///etc/passwd"> ]>
      <project name="&secret;"><tasks/></project>"""
    val result = runCatching { GanttDocument.parse(hostile).read().name }
    val name = result.getOrNull()
    assertTrue(
      result.isFailure || name.isNullOrEmpty() || !name.contains("root:"),
      "external entity was resolved, name was: $name"
    )
  }

  @Test
  fun `a minimal project without resources can still be extended`() {
    val document = GanttDocument.parse(
      """<?xml version="1.0" encoding="UTF-8"?>
         <project name="Small">
           <tasks><task id="1" name="Something" start="2026-01-05" duration="3" complete="0"/></tasks>
         </project>"""
    )
    assertTrue(document.setTaskEffortHours("1", 9.0))
    val reloaded = GanttDocument.parse(document.toXmlString()).read()
    assertEquals(9.0, reloaded.task("1")!!.effortHours)
    assertEquals("Small", reloaded.name)
  }
}
