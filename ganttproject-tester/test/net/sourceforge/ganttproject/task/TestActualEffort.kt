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
package net.sourceforge.ganttproject.task

import biz.ganttproject.core.io.XmlTasks
import biz.ganttproject.lib.fx.SimpleTreeCollapseView
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.parser.TaskLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class TestActualEffort {
  private val taskManager = TestSetupHelper.newTaskManagerBuilder().build()

  private fun newTask() = taskManager.newTaskBuilder().withName("t").build()

  @Test
  fun `a new task has no actual effort`() {
    assertNull(newTask().actualEffort)
    assertNull(newTask().actualEffortHours)
  }

  @Test
  fun `actual effort is set and cleared through the mutator`() {
    val task = newTask()
    task.createMutator().also {
      it.setActualEffort(BigDecimal("7.5"))
      it.commit()
    }
    assertEquals(BigDecimal("7.5"), task.actualEffort)
    assertEquals(BigDecimal("7.5"), task.actualEffortHours)

    task.createMutator().also {
      it.setActualEffort(null)
      it.commit()
    }
    assertNull(task.actualEffort, "actual effort has to be clearable")
  }

  @Test
  fun `actual effort does not touch the planned effort, the dates or the duration`() {
    val task = newTask()
    task.createMutator().also {
      it.setEffort(BigDecimal("40"))
      it.setEffortDriven(true)
      it.commit()
    }
    val start = task.start.clone()
    val end = task.end.clone()
    val duration = task.duration.length

    task.createMutator().also {
      it.setActualEffort(BigDecimal("100"))
      it.commit()
    }
    assertEquals(BigDecimal("40"), task.effort)
    assertEquals(start, task.start)
    assertEquals(end, task.end)
    assertEquals(duration, task.duration.length)
  }

  @Test
  fun `the unplugged clone keeps actual effort and a task created from a prototype starts without it`() {
    val prototype = newTask()
    prototype.createMutator().also {
      it.setActualEffort(BigDecimal("3"))
      it.commit()
    }
    // Effort spent is not a property of a template.
    assertNull(taskManager.newTaskBuilder().withPrototype(prototype).build().actualEffort)
    assertEquals(BigDecimal("3"), prototype.unpluggedClone().actualEffort)
  }

  @Test
  fun `task loader reads actual effort`() {
    val xmlTask = XmlTasks.XmlTask(id = 1, uid = "", name = "T", startDate = "2026-11-09").also {
      it.actualEffort = BigDecimal("16.25")
    }
    assertEquals(BigDecimal("16.25"), TaskLoader(taskManager, SimpleTreeCollapseView()).loadTask(null, xmlTask).actualEffort)
  }

  @Test
  fun `hours are added to the actual effort of a task which has none and to one which has some`() {
    val task = newTask()
    task.addActualEffortHours(BigDecimal("1.5"))
    assertEquals(BigDecimal("1.5"), task.actualEffortHours)
    task.addActualEffortHours(BigDecimal("2"))
    assertEquals(0, BigDecimal("3.5").compareTo(task.actualEffortHours))
  }

  @Test
  fun `only positive hours can be added`() {
    val task = newTask()
    assertThrows(IllegalArgumentException::class.java) { task.addActualEffortHours(BigDecimal.ZERO) }
    assertThrows(IllegalArgumentException::class.java) { task.addActualEffortHours(BigDecimal("-1")) }
    assertNull(task.actualEffort)
  }
}
