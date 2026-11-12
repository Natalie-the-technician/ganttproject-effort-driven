/*
Copyright 2026 BarD Software s.r.o., GanttProject Cloud OU

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
package net.sourceforge.ganttproject

import biz.ganttproject.core.calendar.GanttDaysOff
import biz.ganttproject.core.model.task.levelTasks
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.core.time.CalendarFactory.LocaleApi
import biz.ganttproject.core.time.CalendarFactory.setLocaleApi
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.algorithm.applyLevelling
import net.sourceforge.ganttproject.task.algorithm.collectLevelTasks
import net.sourceforge.ganttproject.task.algorithm.durationAtStart
import net.sourceforge.ganttproject.task.algorithm.workingDayTest
import net.sourceforge.ganttproject.undo.GPUndoListener
import net.sourceforge.ganttproject.undo.GPUndoManager
import net.sourceforge.ganttproject.undo.UndoableEditTxnFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import java.math.BigDecimal
import java.text.DateFormat
import java.time.Duration
import java.time.LocalDate
import java.util.Locale

/**
 * Days off of a resource lengthen the duration of an effort driven task, and levelling counts with the lengthened
 * duration. The week starts on Monday 2026-11-02 and every test day is a weekday unless it says otherwise.
 */
class EffortDaysOffTest {
  private lateinit var project: GanttProjectImpl
  private val monday = LocalDate.of(2026, 11, 2)
  private var storedDays = 1L

  private val undoManager = object : GPUndoManager {
    override fun undoableEdit(localizedName: String, runnableEdit: Runnable) = runnableEdit.run()
    override fun canUndo() = false
    override fun canRedo() = false
    override fun undo() {}
    override fun redo() {}
    override val undoPresentationName = ""
    override val redoPresentationName = ""
    override fun addUndoableEditListener(listener: GPUndoListener) {}
    override fun removeUndoableEditListener(listener: GPUndoListener) {}
    override fun die() {}
    override fun addUndoableEditTxnFactory(factory: UndoableEditTxnFactory) {}
  }

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

  private fun resource(name: String, hoursPerDay: String = "8"): HumanResource =
    project.humanResourceManager.create(name, project.humanResourceManager.resources.size).also {
      it.hoursPerDay = BigDecimal(hoursPerDay)
    }

  private fun date(day: LocalDate) = CalendarFactory.createGanttCalendar(day.year, day.monthValue - 1, day.dayOfMonth).time

  /** The days off the way the person dialog stores them: the end is the day after the last day off. */
  private fun HumanResource.dayOff(first: LocalDate, last: LocalDate = first) {
    addDaysOff(GanttDaysOff(date(first), date(last.plusDays(1))))
  }

  private fun effortTask(effort: String, vararg resources: HumanResource): Task =
    project.taskManager.newTaskBuilder().withName("t")
      .withStartDate(date(monday))
      .withDuration(project.taskManager.createLength(storedDays)).build().also { task ->
        resources.forEach { task.assignmentCollection.addAssignment(it).load = 100f }
        task.createMutator().also {
          it.setEffort(BigDecimal(effort))
          it.setEffortDriven(true)
          it.commit()
        }
      }

  private fun Task.days() = duration.length

  private fun Task.startDay(): LocalDate = LocalDate.of(start.year, start.month + 1, start.date)

  @Test
  fun `without days off the duration is the one of the capacity alone`() {
    val anna = resource("anna")
    val task = effortTask("24", anna)
    assertEquals(3, task.days())
    // Days off of somebody who is not assigned, and an empty list of the assigned one, change nothing.
    resource("bob").dayOff(monday)
    assertEquals(3, task.days())
  }

  @Test
  fun `a day off in the middle lengthens the task by one day`() {
    val anna = resource("anna")
    anna.dayOff(monday.plusDays(2))
    // Monday 8, Tuesday 8, Wednesday off, Thursday 8: three days of work take four days.
    assertEquals(4, effortTask("24", anna).days())
  }

  @Test
  fun `the last day of a day off interval counts and the day after it does not`() {
    val anna = resource("anna")
    anna.dayOff(monday, monday.plusDays(1))
    // Monday and Tuesday off, then Wednesday, Thursday, Friday: 24 hours in five days.
    assertEquals(5, effortTask("24", anna).days())
  }

  @Test
  fun `two resources with different days off add up day by day`() {
    val anna = resource("anna")
    val bob = resource("bob")
    bob.dayOff(monday)
    // Monday 8, Tuesday 16, Wednesday 16: 32 hours need three days, two without the day off.
    assertEquals(3, effortTask("32", anna, bob).days())
  }

  @Test
  fun `a day off before the start of the task changes nothing`() {
    val anna = resource("anna")
    anna.dayOff(monday.minusDays(7), monday.minusDays(3))
    assertEquals(3, effortTask("24", anna).days())
  }

  @Test
  fun `a day off over the whole task moves its work behind the day off`() {
    val anna = resource("anna")
    anna.dayOff(monday, monday.plusDays(4))
    // A whole week without hours still belongs to the task: five days, then two days of work.
    assertEquals(7, effortTask("16", anna).days())
  }

  @Test
  fun `a day off on a non working day changes nothing`() {
    val anna = resource("anna")
    anna.dayOff(monday.plusDays(5))
    // Saturday is no day of the task, so it is neither counted nor does it cost a day: Monday to Friday, then Monday.
    assertEquals(6, effortTask("48", anna).days())
  }

  @Test
  fun `days off without hours per day keep the stored duration and end`() {
    val anna = resource("anna", "0")
    anna.dayOff(monday.plusDays(1))
    storedDays = 5
    val task = assertTimeoutPreemptively(Duration.ofSeconds(10), ThrowingSupplier { effortTask("24", anna) })
    assertEquals(5, task.days())
  }

  @Test
  fun `adding and removing days off follows`() {
    val anna = resource("anna")
    val task = effortTask("24", anna)
    assertEquals(3, task.days())

    anna.dayOff(monday.plusDays(1))
    assertEquals(4, task.days())

    anna.clearDaysOff()
    assertEquals(3, task.days())
  }

  @Test
  fun `moving the start of the task counts the days off from the new start`() {
    val anna = resource("anna")
    anna.dayOff(monday.plusDays(7))
    val task = effortTask("24", anna)
    assertEquals(3, task.days())
    task.createMutator().also {
      it.setStart(CalendarFactory.createGanttCalendar(2026, 10, 9))
      it.commit()
    }
    // From Monday 9 on: Monday off, then Tuesday, Wednesday, Thursday.
    assertEquals(4, task.days())
  }

  @Test
  fun `levelling asks for the duration at the start it tries, with the days off from there on`() {
    val anna = resource("anna")
    anna.dayOff(monday.plusDays(1))
    val task = effortTask("16", anna)
    val levelTask = collectLevelTasks(project.taskManager, monday).single()
    val durationAt = durationAtStart(project.taskManager)
    // From Monday: Monday 8, Tuesday off, Wednesday 8. From Wednesday the day off is behind the task.
    assertEquals(3, durationAt(levelTask, monday))
    assertEquals(2, durationAt(levelTask, monday.plusDays(2)))
    assertEquals(3, task.days())
  }

  @Test
  fun `levelling without days off puts the second task right behind the first`() {
    val anna = resource("anna")
    val first = effortTask("16", anna)
    val second = project.taskManager.newTaskBuilder().withName("second")
      .withStartDate(date(monday)).withDuration(project.taskManager.createLength(1)).build()
    second.assignmentCollection.addAssignment(anna).load = 100f

    val tasks = collectLevelTasks(project.taskManager, monday)
    val result = levelTasks(tasks, monday, workingDayTest(project.taskManager.calendar), durationAtStart(project.taskManager))
    applyLevelling(result.starts, result.durations, project.taskManager, undoManager, "level")

    assertEquals(monday, first.startDay())
    assertEquals(monday.plusDays(2), second.startDay())
  }

  @Test
  fun `levelling waits for a first task which a day off has lengthened`() {
    // The one first resource, anna, gets the day off inside the first task: it lasts Monday to Wednesday.
    val anna = resource("anna")
    anna.dayOff(monday.plusDays(1))
    val first = effortTask("16", anna)
    assertEquals(3, first.days())
    val second = project.taskManager.newTaskBuilder().withName("second")
      .withStartDate(date(monday)).withDuration(project.taskManager.createLength(1)).build()
    second.assignmentCollection.addAssignment(anna).load = 100f

    val tasks = collectLevelTasks(project.taskManager, monday)
    val result = levelTasks(tasks, monday, workingDayTest(project.taskManager.calendar), durationAtStart(project.taskManager))
    applyLevelling(result.starts, result.durations, project.taskManager, undoManager, "level")

    assertEquals(monday, first.startDay())
    assertEquals(3, first.days())
    assertEquals(monday.plusDays(3), second.startDay())
  }
}
