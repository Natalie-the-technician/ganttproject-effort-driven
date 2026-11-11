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

import biz.ganttproject.core.model.task.TaskEffort
import java.math.BigDecimal

/** The effort actually spent on the task, in hours, or null if none was recorded. */
val Task.actualEffortHours: BigDecimal? get() = actualEffort?.let(TaskEffort::toHours)

/**
 * Adds [hours] to the effort actually spent on the task and commits it. The task which has none yet starts from zero.
 * The actual effort is only a record: it does not change the dates or the duration of the task.
 */
fun Task.addActualEffortHours(hours: BigDecimal) {
  require(hours.signum() > 0) { "Hours to add must be positive: $hours" }
  val spent = actualEffort ?: BigDecimal.ZERO
  createMutator().also {
    it.setActualEffort(spent.add(TaskEffort.fromHours(hours)))
    it.commit()
  }
}
