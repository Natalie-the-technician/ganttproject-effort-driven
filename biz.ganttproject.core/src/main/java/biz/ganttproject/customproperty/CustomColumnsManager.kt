/*
 * Copyright 2024 BarD Software s.r.o., Dmitry Barashev.
 *
 * This file is part of GanttProject, an opensource project management tool.
 *
 * GanttProject is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 * GanttProject is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with GanttProject.  If not, see <http://www.gnu.org/licenses/>.
 */
package biz.ganttproject.customproperty

import biz.ganttproject.createLogger

/**
 * This is an implementation of the custom property definition storage. It stores the definitions in a hash map in the memory.
 *
 * @author dbarashev (Dmitry Barashev)
 */
class CustomColumnsManager : CustomPropertyManager {
  private val listeners = mutableListOf<CustomPropertyListener>()
  private val mapIdCustomColum = mutableMapOf<String, CustomColumn>()
  private var nextId = 0
  private var isImporting = false

  private fun addNewCustomColumn(customColumn: CustomColumn, fireChange: Boolean) {
    if (mapIdCustomColum[customColumn.id] != null) {
      throw CustomColumnsException(
        CustomColumnsException.ALREADY_EXIST,
        "Column with ID=${customColumn.id} is already registered"
      )
    }
    mapIdCustomColum[customColumn.id] = customColumn
    if (fireChange) {
      val event = CustomPropertyEvent(CustomPropertyEvent.EVENT_ADD, customColumn)
      fireCustomColumnsChange(event)
    }
  }

  override fun addListener(listener: CustomPropertyListener) {
    listeners.add(listener)
  }

  override fun removeListener(listener: CustomPropertyListener) {
    listeners.remove(listener)
  }

  override val definitions: List<CustomPropertyDefinition> get() = mapIdCustomColum.values.toList()

  override fun createDefinition(id: String, typeAsString: String, name: String, defaultValueAsString: String?): CustomPropertyDefinition {
    val stub = PropertyTypeEncoder.decodeTypeAndDefaultValue(typeAsString, defaultValueAsString)
    val result = CustomColumn(this, name, stub.propertyClass, stub.defaultValue)
    result.id = id
    addNewCustomColumn(result, true)
    return result
  }

  override fun createDefinition(propertyClass: CustomPropertyClass, colName: String, defValue: String?): CustomPropertyDefinition {
    val stub = PropertyTypeEncoder.create(propertyClass, defValue)
    val result = CustomColumn(this, colName, stub.propertyClass, stub.defaultValue)
    result.id = createId()
    addNewCustomColumn(result, true)
    return result
  }

  override fun importData(source: CustomPropertyManager): Map<CustomPropertyDefinition, CustomPropertyDefinition> =
    try {
      isImporting = true
      val result = mutableMapOf<CustomPropertyDefinition, CustomPropertyDefinition>()
      for (thatColumn in source.definitions) {
        // [fork change] Declared-stable id first, display name second -- see findByStableId.
        var thisColumn = findByStableId(thatColumn) ?: findByName(thatColumn.name)
        if (thisColumn == null || thisColumn.propertyClass != thatColumn.propertyClass) {
          thisColumn = CustomColumn(this, thatColumn.name, thatColumn.propertyClass, thatColumn.defaultValue)
          thisColumn.id = findById(thatColumn.id)?.let { createId() } ?: thatColumn.id
          thisColumn.attributes.putAll(thatColumn.attributes)
          thisColumn.calculationMethod = thatColumn.calculationMethod
          addNewCustomColumn(thisColumn, false)
        }
        result[thatColumn] = thisColumn
      }
      result
    } finally {
      isImporting = false
      val event = CustomPropertyEvent(CustomPropertyEvent.EVENT_REBUILD, null)
      fireCustomColumnsChange(event)
    }


  override fun getCustomPropertyDefinition(id: String): CustomPropertyDefinition? {
    return mapIdCustomColum[id]
  }

  override fun deleteDefinition(def: CustomPropertyDefinition) {
    val event = CustomPropertyEvent(CustomPropertyEvent.EVENT_REMOVE, def)
    mapIdCustomColum.remove(def.id)
    fireCustomColumnsChange(event)
  }

  override fun reset() {
    mapIdCustomColum.clear()
    nextId = 0
  }

  private fun fireCustomColumnsChange(event: CustomPropertyEvent) {
    if (!isImporting) {
      listeners.forEach {
        try {
          it.customPropertyChange(event)
        } catch (ex: Exception) {
          LOG.error("Failure when processing custom columns event", exception = ex)
        }
      }
    }
  }

   internal fun fireDefinitionChanged(event: Int, def: CustomColumn, oldDef: CustomColumn) {
    if (!isImporting) {
      val e = CustomPropertyEvent(event, def, oldDef)
      fireCustomColumnsChange(e)
    }
  }

  /**
   * [fork change] Matches the column of an incoming project by its ID rather than by its display
   * name -- for the handful of ids that are DECLARED stable, see [STABLE_PROPERTY_IDS]. Everything
   * else keeps the name as its identity and behaves exactly as before.
   *
   * WHY THE ID HAS TO BE TRIED AT ALL. Matching on the display name is sound as long as every column
   * name is text the user typed: that string is the same in every language. It stops being sound for
   * a column with a FIXED id and a TRANSLATED name, and this fork has sixteen of them -- the effort
   * column for one, id `effort_hours`, header out of the fork's own text bundle. Importing a file
   * written by an English installation into a session running in German looked for "Effort (h)", did
   * not find "Aufwand (Std.)" and created a SECOND column; since `effort_hours` was taken the new one
   * received a generated id. The imported figures then sat plainly visible in a column of their own
   * and had no effect whatsoever, because the scheduling resolves its column by the id and kept
   * finding the old, empty one.
   *
   * WHY A DECLARED LIST AND NOT THE PLAIN RULE "id before name". Because the plain rule is wrong, and
   * GanttProject says so in two tests. An id is NOT an identity in general: for a column the user
   * creates it is a counter (`tpc0`, `tpc1`, ...) that every project starts at zero, and for an id
   * stored in a file nothing guarantees that two projects mean the same thing by it.
   * `CustomPropertyImportTest.import preserves property id` and
   * `ImportTasksTestCase.testImportCustomColumns` both set up two columns that share an id and differ
   * in name, and both require them to stay apart -- the second one checks that neither column loses
   * its values. Measured 2026-10-01: matching every non-generated id by id turns the first test's 4
   * definitions into 3 and makes the second one lose a column outright. An id is an identity only
   * where somebody vouches for it, which is what the list below does.
   *
   * The class is compared as well, so a same-id column of a different type falls through to the name
   * lookup instead of silently swallowing values of a type it cannot hold.
   */
  private fun findByStableId(thatColumn: CustomPropertyDefinition): CustomColumn? =
    if (!isStablePropertyId(thatColumn.id)) null
    else mapIdCustomColum[thatColumn.id]?.takeIf { it.propertyClass == thatColumn.propertyClass }

  private fun findByName(name: String) = mapIdCustomColum.values.find { it.name == name }
  private fun findById(id: String) = mapIdCustomColum.values.find { it.id == id }
  private fun createId(): String {
    while (true) {
      val id = "$ID_PREFIX${nextId++}"
      if (!mapIdCustomColum.containsKey(id)) {
        return id
      }
    }
  }

}

private val LOG = createLogger("CustomColumns")
private const val ID_PREFIX = "tpc"

/**
 * [fork change] The ids whose identity is the ID and not the display name.
 *
 * These are the properties the PROGRAM defines, with a fixed id and a header text that comes out of a
 * translation bundle -- as opposed to a column the user creates, whose typed name is its identity and
 * whose id is a mere counter. For the program's own properties it is the other way round, and an
 * import has to follow the id, otherwise the same column arrives twice as soon as the file was
 * written in another language. See [CustomColumnsManager.findByStableId] for the whole argument.
 *
 * WHY A CONSTANT AND NOT A REGISTRY THAT THE FORK FILLS AT STARTUP. `importData` has to give the same
 * answer regardless of which part of the program ran first. A registry would make the result depend
 * on whether anybody had touched the effort machinery yet -- and the case that matters most is a
 * project read from a file, where the column comes from the parser and no fork code has necessarily
 * run at all.
 *
 * The strings are literals here and constants in the fork's own files; `StablePropertyIdsTest` ties
 * the two together, so a column added without a declaration here fails a test rather than silently
 * detaching itself on the next import.
 */
private val STABLE_PROPERTY_IDS = setOf(
  "effort_hours",            // EffortDrivenProperties.TASK_EFFORT_HOURS
  "effort_actual_hours",     // EffortDrivenProperties.TASK_EFFORT_ACTUAL_HOURS
  "effort_original_hours",   // TASK_EFFORT_ORIGINAL
  "hours_per_day",           // EffortDrivenProperties.RESOURCE_HOURS_PER_DAY
  "hours_schedule",          // EffortDrivenProperties.RESOURCE_HOURS_SCHEDULE
  "utilisation_percent",     // RESOURCE_UTILISATION
  "work_week",               // RESOURCE_WORK_WEEK
  "home_office_week",        // RESOURCE_HOME_OFFICE_WEEK
  "home_office_periods",     // RESOURCE_HOME_OFFICE_PERIODS
  "on_site_only",            // TASK_ON_SITE_ONLY
  "wait_only",               // TASK_WAIT_ONLY
  "date_fixed",              // TASK_DATE_FIXED
  "deadline",                // TASK_DEADLINE
  "recurrence",              // TASK_RECURRENCE
  "recurrence_of",           // TASK_RECURRENCE_OF
  "fork.allow_holiday_work", // PROJECT_ALLOW_HOLIDAY_WORK
)

/**
 * [fork change] Whether [id] is one of the ids declared stable in [STABLE_PROPERTY_IDS].
 *
 * Public so the fork's own modules can assert that every property they define is declared here; the
 * import path itself uses it through [CustomColumnsManager.findByStableId].
 */
fun isStablePropertyId(id: String): Boolean = id in STABLE_PROPERTY_IDS