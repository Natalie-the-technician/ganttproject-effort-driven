/*
Copyright 2022 BarD Software s.r.o., Anastasiia Postnikova

This file is part of GanttProject, an open-source project management tool.

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
package net.sourceforge.ganttproject.storage

import biz.ganttproject.storage.db.Tables
import biz.ganttproject.storage.db.tables.records.TaskRecord
import net.sourceforge.ganttproject.io.externalizedColor
import net.sourceforge.ganttproject.io.externalizedNotes
import net.sourceforge.ganttproject.io.externalizedWebLink
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskImpl
import org.jooq.DSLContext
import org.jooq.Insert
import org.jooq.impl.DSL
import java.math.BigDecimal

fun buildInsertTaskQuery(dsl: DSLContext, task: Task): Insert<TaskRecord> {
  var costManualValue: BigDecimal? = null
  var isCostCalculated: Boolean? = null
  if (!(task.cost.isCalculated && task.cost.manualValue == BigDecimal.ZERO)) {
    costManualValue = task.cost.manualValue
    isCostCalculated = task.cost.isCalculated
  }
  var q = dsl
    .insertInto(Tables.TASK)
    .set(Tables.TASK.UID, task.uid)
    .set(Tables.TASK.NUM, task.taskID)
    .set(Tables.TASK.NAME, task.name)
    .set(Tables.TASK.COLOR, (task as TaskImpl).externalizedColor())
    .set(Tables.TASK.SHAPE, task.shape?.array)
    .set(Tables.TASK.IS_MILESTONE, task.isLegacyMilestone)
    .set(Tables.TASK.IS_PROJECT_TASK, task.isProjectTask)
    .set(Tables.TASK.START_DATE, task.start.toLocalDate())
    .set(Tables.TASK.END_DATE, task.end.toLocalDate())
    .set(Tables.TASK.DURATION, task.duration.length)
    .set(Tables.TASK.COMPLETION, task.completionPercentage)
    .set(Tables.TASK.EARLIEST_START_DATE, task.third?.toLocalDate())
    .set(Tables.TASK.PRIORITY, task.priority.persistentValue)
    .set(Tables.TASK.WEB_LINK, task.externalizedWebLink())
    .set(Tables.TASK.COST_MANUAL_VALUE, costManualValue)
    .set(Tables.TASK.COST, task.cost.value)
    .set(Tables.TASK.IS_COST_CALCULATED, isCostCalculated)
    .set(Tables.TASK.NOTES, task.externalizedNotes())
  val customProps = mutableMapOf<Any, Any>()
  task.manager.customPropertyManager.definitions.forEach { def ->
    if (def.calculationMethod == null) {

      task.customValues.getValue(def)?.let {
       // [Fork-Aenderung] Datumswerte umsetzen. jOOQ kennt GregorianCalendar nicht und wirft
       // "Type class java.util.GregorianCalendar is not supported in dialect DEFAULT" -- damit
       // war JEDE Datums-Spalte mit einem Wert unbrauchbar, auch eine selbst angelegte. Am
       // Bildschirm gefunden, als die Spalte "Fertig bis" ihren ersten Wert bekam.
       //
       // Umgesetzt wird ueber die Kalenderfelder, NICHT ueber toInstant(): GanttProject verbiegt
       // beim Start die Standard-Zeitzone, und java.time sieht die Verbiegung nicht. Die
       // Begruendung steht in fork/LegacyDates.kt.
       customProps[DSL.field(""" "${def.id}" """, sqlType(def.type))] = sqlValue(it)
      }
    }
  }
  q.set(customProps)
  return q
}

fun buildInsertTaskDto(task: Task): OperationDto.InsertOperationDto {
  var costManualValue: BigDecimal? = null
  var isCostCalculated: Boolean? = null
  if (!(task.cost.isCalculated && task.cost.manualValue == BigDecimal.ZERO)) {
    costManualValue = task.cost.manualValue
    isCostCalculated = task.cost.isCalculated
  }
  return OperationDto.InsertOperationDto(
    Tables.TASK.name.lowercase(),
    mapOf(
      Tables.TASK.UID.name to task.uid,
      Tables.TASK.NUM.name to task.taskID.toString(),
      Tables.TASK.NAME.name to task.name,
      Tables.TASK.COLOR.name to (task as TaskImpl).externalizedColor(),
      Tables.TASK.SHAPE.name to task.shape?.array,
      Tables.TASK.IS_MILESTONE.name to task.isLegacyMilestone.toString(),
      Tables.TASK.IS_PROJECT_TASK.name to task.isProjectTask.toString(),
      Tables.TASK.START_DATE.name to task.start.toLocalDate().toString(),
      Tables.TASK.END_DATE.name to task.end.toLocalDate().toString(),
      Tables.TASK.DURATION.name to task.duration.length.toString(),
      Tables.TASK.COMPLETION.name to task.completionPercentage.toString(),
      Tables.TASK.EARLIEST_START_DATE.name to (task.third?.toLocalDate()?.toString()),
      Tables.TASK.PRIORITY.name to task.priority.persistentValue,
      Tables.TASK.WEB_LINK.name to task.externalizedWebLink(),
      Tables.TASK.COST_MANUAL_VALUE.name to (costManualValue?.toString()),
      Tables.TASK.IS_COST_CALCULATED.name to (isCostCalculated?.toString()),
      Tables.TASK.COST.name to task.cost.value?.toString(),
      Tables.TASK.NOTES.name to task.externalizedNotes(),
    )
  )
}

/**
 * [Fork-Aenderung] Der Typ, den jOOQ fuer diese Spalte versteht.
 *
 * `GregorianCalendar` versteht es nicht; `LocalDate` schon, und die H2-Spalte ist ohnehin `date`
 * (SqlCustomPropertyStorageManager:107).
 */
private fun sqlType(type: Class<*>): Class<*> =
  if (java.util.GregorianCalendar::class.java.isAssignableFrom(type) ||
      java.util.Date::class.java.isAssignableFrom(type)) java.time.LocalDate::class.java else type

/** [Fork-Aenderung] Der Wert in der Form, die zu [sqlType] passt. */
private fun sqlValue(value: Any): Any = when (value) {
  is java.util.GregorianCalendar -> java.time.LocalDate.of(
    value.get(java.util.Calendar.YEAR),
    value.get(java.util.Calendar.MONTH) + 1,
    value.get(java.util.Calendar.DAY_OF_MONTH))
  is java.util.Date -> java.util.GregorianCalendar().let { c ->
    c.time = value
    java.time.LocalDate.of(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
      c.get(java.util.Calendar.DAY_OF_MONTH))
  }
  else -> value
}
