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
package net.sourceforge.ganttproject.parser

import biz.ganttproject.core.io.XmlProject
import biz.ganttproject.core.io.XmlResources
import biz.ganttproject.core.io.XmlResources.XmlResource
import biz.ganttproject.core.model.task.parseCapacityChanges
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("ForkFreeText")

/** Custom property in which the effort driven fork kept the hours per day of a resource. */
const val FORK_HOURS_PER_DAY = "hours_per_day"
/** Custom property in which the effort driven fork kept the utilization of a resource in percent. */
const val FORK_UTILISATION = "utilisation_percent"
/** Custom property in which the effort driven fork kept the changes of the working hours of a resource. */
const val FORK_HOURS_SCHEDULE = "hours_schedule"

/**
 * Turns the custom properties in which the effort driven fork kept the working hours of a resource into the attributes
 * which hold them now, before the resources are read. This is shared by the desktop and the cloud readers, so that
 * they cannot disagree about the same file.
 *
 * A value is taken the way the fork computed with it: a utilization outside 1..100, zero included, was 100 in the fork,
 * so it becomes no utilization, and not zero, which means no hours now. Hours per day of zero or less were the default
 * in the fork, so they become no hours per day. A value which cannot be read is not guessed:
 * it stays where it is, as text, and is logged and handed to [onUnreadable]. So does a value next to an attribute which
 * is already in the file, because the attribute wins. A definition which no resource uses any more is dropped, so that
 * saving writes only the attributes.
 */
fun XmlProject.migrateForkFreeText(onUnreadable: (resourceName: String, property: String, value: String) -> Unit = { _, _, _ -> }) {
  val definitions = resources.customProperties
  val migrations = listOf(
    FORK_HOURS_PER_DAY to XmlResource::migrateHoursPerDay,
    FORK_UTILISATION to XmlResource::migrateUtilisation,
    FORK_HOURS_SCHEDULE to XmlResource::migrateHoursSchedule,
  ).mapNotNull { (key, migrate) -> definitions.findForkDefinition(key)?.let { Triple(key, it, migrate) } }
  if (migrations.isEmpty()) {
    return
  }
  val kept = mutableSetOf<String>()
  resources.resources.forEach { resource ->
    resource.props = resource.props.filter { prop ->
      val (key, _, migrate) = migrations.firstOrNull { it.second.id == prop.definitionId } ?: return@filter true
      val taken = migrate(resource, prop.value.trim())
      if (!taken) {
        LOGGER.warn("Resource {} keeps {}=\"{}\" of the effort driven fork as text: it cannot be read, or the file has the attribute already",
          resource.name, key, prop.value)
        onUnreadable(resource.name, key, prop.value)
        kept.add(prop.definitionId)
      }
      !taken
    }
  }
  resources.customProperties = definitions.mapNotNull { definition ->
    when {
      migrations.none { it.second === definition } -> definition
      // What is left is text which cannot be read as a number, and the reader would throw it away in a numeric property.
      definition.id in kept -> definition.also { it.type = "text" }
      else -> null
    }
  }
}

/** The definition the fork looked up: by its id first, then by its name. */
private fun List<XmlResources.XmlCustomPropertyDefinition>.findForkDefinition(key: String) =
  firstOrNull { it.id == key } ?: firstOrNull { it.name == key }

/** @return whether the value is taken over, or has nothing in it. */
private fun XmlResource.migrateHoursPerDay(value: String): Boolean {
  if (value.isEmpty()) {
    return true
  }
  if (hoursPerDay != null) {
    return false
  }
  val hours = value.toBigDecimalOrNull() ?: return false
  if (hours.signum() <= 0) {
    LOGGER.warn("Resource {} has {}={} of the effort driven fork, which the fork read as the default", name, FORK_HOURS_PER_DAY, hours)
    return true
  }
  hoursPerDay = hours
  return true
}

/** @return whether the value is taken over, or has nothing in it. */
private fun XmlResource.migrateUtilisation(value: String): Boolean {
  if (value.isEmpty()) {
    return true
  }
  if (utilization != null) {
    return false
  }
  val percent = value.toIntOrNull() ?: return false
  if (percent !in 1..100) {
    LOGGER.warn("Resource {} has {}={} of the effort driven fork, which the fork read as 100", name, FORK_UTILISATION, percent)
  }
  utilization = percent.takeIf { it in 1..99 }
  return true
}

/** @return whether the value is taken over, or has nothing in it. A schedule with one faulty entry is not taken at all. */
private fun XmlResource.migrateHoursSchedule(value: String): Boolean {
  if (value.isEmpty()) {
    return true
  }
  if (hoursChanges.isNotEmpty()) {
    return false
  }
  val changes = parseCapacityChanges(value) ?: return false
  hoursChanges = changes.map { XmlResource.XmlHoursChange(it.from.toString(), it.hoursPerDay) }
  return true
}
