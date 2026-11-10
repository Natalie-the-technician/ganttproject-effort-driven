/*
Copyright 2026 BarD Software s.r.o., GanttProject Team

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
package net.sourceforge.ganttproject.gui.taskproperties

import biz.ganttproject.core.option.ObservableBoolean
import biz.ganttproject.core.option.ValidationException
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.core.time.CalendarFactory.LocaleApi
import biz.ganttproject.core.time.CalendarFactory.setLocaleApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import net.sourceforge.ganttproject.GanttProjectImpl
import net.sourceforge.ganttproject.gui.resourceproperties.MainPropertiesPanel
import net.sourceforge.ganttproject.task.Task
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.text.DateFormat
import java.util.Locale

class EffortFieldsTest {
  private lateinit var project: GanttProjectImpl

  @BeforeEach
  fun setUp() {
    object : CalendarFactory() {
      init {
        setLocaleApi(object : LocaleApi {
          override fun getLocale(): Locale = Locale.US
          override fun getShortDateFormat(): DateFormat = DateFormat.getDateInstance(DateFormat.SHORT, Locale.US)
        })
      }
    }
    project = GanttProjectImpl()
  }

  private fun newTask(): Task = project.taskManager.newTaskBuilder().withName("t").build()

  @Test
  fun `an empty field is not set, a number is taken as it is`() {
    assertNull(parseDecimal("", BigDecimal.ZERO))
    assertNull(parseDecimal("  ", BigDecimal.ZERO))
    assertNull(parseDecimal(null, BigDecimal.ZERO))
    assertEquals(BigDecimal("12.5"), parseDecimal(" 12.5 ", BigDecimal.ZERO))
    assertEquals(BigDecimal.ZERO, parseDecimal("0", BigDecimal.ZERO))
  }

  @Test
  fun `a negative effort and a text are rejected`() {
    assertThrows(ValidationException::class.java) { effortValidator.parse("-1") }
    assertThrows(ValidationException::class.java) { effortValidator.parse("eight") }
    effortValidator.parse("8")
    effortValidator.parse("")
  }

  @Test
  fun `hours per day must be more than zero and at most a day`() {
    assertThrows(ValidationException::class.java) { hoursPerDayValidator.parse("0") }
    assertThrows(ValidationException::class.java) { hoursPerDayValidator.parse("-2") }
    assertThrows(ValidationException::class.java) { hoursPerDayValidator.parse("24.5") }
    assertThrows(ValidationException::class.java) { hoursPerDayValidator.parse("x") }
    assertEquals(BigDecimal("24"), parseHoursPerDay("24"))
    assertEquals(BigDecimal("0.5"), parseHoursPerDay("0.5"))
    assertNull(parseHoursPerDay(""))
  }

  @Test
  fun `effort and the switch are written to the task on commit`() {
    val task = newTask()
    val fields = TaskEffortFields(task)
    assertNull(task.effort)
    assertFalse(task.isEffortDriven)

    fields.effortOption.value = "16"
    fields.effortDrivenOption.value = true
    task.createMutator().also { fields.save(it); it.commit() }

    assertEquals(BigDecimal("16"), task.effort)
    assertTrue(task.isEffortDriven)
  }

  @Test
  fun `the dialog is filled from the task and an emptied field clears the effort`() {
    val task = newTask()
    task.createMutator().also { it.setEffort(BigDecimal("40")); it.setEffortDriven(true); it.commit() }

    val fields = TaskEffortFields(task)
    assertEquals("40", fields.effortOption.value)
    assertTrue(fields.effortDrivenOption.value)

    fields.effortOption.value = ""
    task.createMutator().also { fields.save(it); it.commit() }
    assertNull(task.effort)
  }

  @Test
  fun `a task which was not touched in the dialog keeps its values`() {
    val task = newTask()
    task.createMutator().also { it.setEffort(BigDecimal("8.50")); it.commit() }
    val fields = TaskEffortFields(task)
    task.createMutator().also { fields.save(it); it.commit() }
    assertEquals(BigDecimal("8.50"), task.effort)
  }

  @Test
  fun `the duration which follows from the effort is there after the commit`() {
    val resource = project.humanResourceManager.create("a", 0).also { it.hoursPerDay = BigDecimal("4") }
    val task = newTask()
    task.assignmentCollection.addAssignment(resource).load = 100f
    val fields = TaskEffortFields(task)
    fields.effortOption.value = "20"
    fields.effortDrivenOption.value = true
    task.createMutator().also { fields.save(it); it.commit() }
    assertEquals(5, task.duration.length)
  }

  @Test
  fun `only a task which is not a milestone or a summary task has its duration derived`() {
    assertTrue(isDurationDerived(isEffortDriven = true, isMilestone = false, isSummary = false))
    assertFalse(isDurationDerived(isEffortDriven = false, isMilestone = false, isSummary = false))
    assertFalse(isDurationDerived(isEffortDriven = true, isMilestone = true, isSummary = false))
    assertFalse(isDurationDerived(isEffortDriven = true, isMilestone = false, isSummary = true))
  }

  @Test
  fun `a derived duration locks the duration and the end, the start stays free`() {
    for (part in CalculatedPart.entries) {
      val w = datesWritability(isMilestone = false, isDurationDerived = true, calculatedPart = part)
      assertEquals(DatesWritability(start = true, end = false, duration = false, scheduling = false), w, "$part")
    }
  }

  @Test
  fun `without the switch the dates are editable as before`() {
    assertEquals(DatesWritability(false, true, true, true), datesWritability(false, false, CalculatedPart.START))
    assertEquals(DatesWritability(true, false, true, true), datesWritability(false, false, CalculatedPart.END))
    assertEquals(DatesWritability(true, true, false, true), datesWritability(false, false, CalculatedPart.DURATION))
    assertEquals(DatesWritability(true, false, false, false), datesWritability(true, false, CalculatedPart.END))
  }

  @Test
  fun `the switch in the dialog locks and unlocks the fields of the controller`() {
    val task = newTask()
    val milestone = ObservableBoolean("milestone", false)
    val effortDriven = ObservableBoolean("effortDriven", false)
    val controller = TaskDatesController(task, milestone, effortDriven, CoroutineScope(Dispatchers.Unconfined))
    assertTrue(controller.durationOption.isWritable.value)
    assertTrue(controller.startDateOption.isWritable.value)

    effortDriven.value = true
    assertFalse(controller.durationOption.isWritable.value)
    assertFalse(controller.displayEndDateOption.isWritable.value)
    assertTrue(controller.startDateOption.isWritable.value)

    effortDriven.value = false
    assertTrue(controller.durationOption.isWritable.value)
    assertFalse(controller.displayEndDateOption.isWritable.value)
  }

  @Test
  fun `hours per day of a resource are written when the dialog is saved`() {
    val resource = project.humanResourceManager.create("a", 0)
    val panel = MainPropertiesPanel(resource)
    val option = panel.hoursPerDayOption
    assertNull(resource.hoursPerDay)

    option.value = "6.5"
    panel.save()
    assertEquals(BigDecimal("6.5"), resource.hoursPerDay)

    option.value = ""
    panel.save()
    assertNull(resource.hoursPerDay)
  }
}
