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
import biz.ganttproject.core.option.ObservableString
import biz.ganttproject.core.option.ValidationException
import biz.ganttproject.core.option.ValueValidator
import biz.ganttproject.core.option.validatorI18N
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskMutator
import java.math.BigDecimal

/**
 * Parses the content of a decimal field. An empty text means "not set" and gives null.
 * Throws [ValidationException] if the text is not a number or is not within the bounds.
 */
fun parseDecimal(text: String?, min: BigDecimal, isMinExclusive: Boolean = false, max: BigDecimal? = null): BigDecimal? {
  val trimmed = text?.trim()
  if (trimmed.isNullOrEmpty()) {
    return null
  }
  val value = try {
    BigDecimal(withDecimalPoint(trimmed))
  } catch (e: NumberFormatException) {
    throw ValidationException(validatorI18N("validator.decimal.error.parse", arrayOf(trimmed)))
  }
  val belowMin = if (isMinExclusive) value <= min else value < min
  if (belowMin || (max != null && value > max)) {
    throw ValidationException(validatorI18N("validator.decimal.error.range", arrayOf(trimmed)))
  }
  return value
}

/** A comma as the decimal separator is accepted, as long as there is no point which could be a separator too. */
private fun withDecimalPoint(text: String) = if (text.count { it == ',' } == 1 && '.' !in text) text.replace(',', '.') else text

fun decimalValidator(min: BigDecimal, isMinExclusive: Boolean = false, max: BigDecimal? = null) =
  ValueValidator<String> { text -> parseDecimal(text, min, isMinExclusive, max); text }

/** Effort in hours, not negative. */
val effortValidator = decimalValidator(BigDecimal.ZERO)

/** Hours per day of a resource: more than zero, at most a day. */
fun parseHoursPerDay(text: String?): BigDecimal? = parseDecimal(text, BigDecimal.ZERO, isMinExclusive = true, max = BigDecimal(24))

val hoursPerDayValidator = ValueValidator<String> { text -> parseHoursPerDay(text); text }

/**
 * The effort and the "effort driven" switch of the task properties dialog.
 * The duration which follows from them is calculated when the task mutator is committed.
 */
class TaskEffortFields(private val task: Task) {
  val effortOption = ObservableString("effort", task.effort?.toPlainString(), effortValidator)
  val effortDrivenOption = ObservableBoolean("effortDriven", task.isEffortDriven)

  fun save(mutator: TaskMutator) {
    effortOption.ifChanged {
      val effort = parseDecimal(it, BigDecimal.ZERO)
      val current = task.effort
      // An emptied field of a task without effort is not a change.
      val isSame = if (effort == null || current == null) effort == current else effort.compareTo(current) == 0
      if (!isSame) {
        mutator.setEffort(effort)
      }
    }
    effortDrivenOption.ifChanged(mutator::setEffortDriven)
  }
}
