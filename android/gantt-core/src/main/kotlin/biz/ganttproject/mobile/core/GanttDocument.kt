/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Why a file could not be read. Branch on [reason]; [message] is
 * developer-facing detail and is not meant to be shown untranslated.
 */
class GanttFormatException(
  val reason: Reason,
  message: String,
  cause: Throwable? = null
) : Exception(message, cause) {
  enum class Reason {
    /** Not well-formed XML at all. */
    NOT_XML,

    /** Well-formed XML, but the root element is not `<project>`. */
    NOT_A_PROJECT
  }
}

/**
 * An open GanttProject file (`.gan` / `.xml`).
 *
 * ## Design: edit in place, never rebuild
 *
 * The file is held as an [XmlElement] tree and every mutation touches exactly
 * the attribute or element it concerns. Saving serialises the same tree back.
 * Everything this app does not understand — view settings, column widths,
 * calendars, roles, baselines, notes, fields from newer GanttProject
 * versions — therefore survives untouched, and the diff of an edit contains
 * only the lines that were actually edited.
 *
 * The obvious alternative, generating a fresh file from the read model, would
 * be far simpler and is exactly why it is dangerous: it silently drops
 * whatever the model does not represent. GanttProject's own desktop
 * `TaskSaver` makes that mistake with unknown attributes. On a phone it would
 * be worse, because nobody inspects the file before it is written back to
 * shared storage.
 *
 * ## Threading
 *
 * Instances are not thread-safe. Load, mutate and save from a single
 * background thread.
 */
class GanttDocument private constructor(private val root: XmlElement) {

  // ------------------------------------------------------------- Loading

  companion object {
    fun parse(xml: String): GanttDocument = load(xml.toByteArray(Charsets.UTF_8))

    /**
     * Parses [bytes] as a GanttProject file.
     *
     * @throws GanttFormatException if the bytes are not well-formed XML or
     *   the root element is not `<project>`.
     */
    fun load(bytes: ByteArray): GanttDocument {
      val root = try {
        XmlParser.parse(bytes)
      } catch (e: Exception) {
        throw GanttFormatException(
          GanttFormatException.Reason.NOT_XML,
          "not well-formed XML: ${e.message}",
          e
        )
      }
      if (root.name != "project") {
        throw GanttFormatException(
          GanttFormatException.Reason.NOT_A_PROJECT,
          "expected root element <project> but found <${root.name}>"
        )
      }
      return GanttDocument(root)
    }
  }

  // ------------------------------------------------------------- Saving

  fun toXmlString(): String = XmlWriter.write(root)

  fun toXmlBytes(): ByteArray = toXmlString().toByteArray(Charsets.UTF_8)

  // -------------------------------------------------------------- Reading

  /** Builds a fresh read-only snapshot of the current tree state. */
  fun read(): ProjectModel {
    val calendar = readCalendar()
    val taskDefs = taskPropertyDefinitionsByName()
    val resourceDefs = resourcePropertyDefinitionsByName()
    return ProjectModel(
      name = root.attrOrEmpty("name"),
      company = root.attrOrEmpty("company"),
      tasks = tasksElement()?.let { readTasks(it, 0, taskDefs) } ?: emptyList(),
      resources = readResources(resourceDefs),
      allocations = readAllocations(),
      roles = readRoles(),
      calendar = calendar
    )
  }

  private fun readTasks(
    parent: XmlElement,
    depth: Int,
    defs: Map<String, String>
  ): List<TaskNode> {
    val effortId = defs[ForkProperties.TASK_EFFORT_HOURS]
    val actualId = defs[ForkProperties.TASK_EFFORT_ACTUAL_HOURS]
    val keysId = defs[ForkProperties.TASK_TOGGL_MATCH_KEYS]
    return parent.childElements("task").map { el ->
      TaskNode(
        id = el.attrOrEmpty("id"),
        uid = el.attr("uid")?.ifEmpty { null },
        name = el.attrOrEmpty("name"),
        start = parseDateOrToday(el.attr("start")),
        durationDays = el.attr("duration")?.toIntOrNull() ?: 0,
        completion = el.attr("complete")?.toIntOrNull()?.coerceIn(0, 100) ?: 0,
        isMilestone = el.attr("meeting") == "true",
        color = el.attr("color")?.ifEmpty { null },
        notes = el.firstChildElement("notes")?.textContent()?.trim()?.ifEmpty { null },
        depth = depth,
        children = readTasks(el, depth + 1, defs),
        effortHours = effortId?.let { readTaskPropertyValue(el, it)?.toDoubleOrNull() },
        actualEffortHours = actualId?.let { readTaskPropertyValue(el, it)?.toDoubleOrNull() },
        togglMatchKeys = ForkProperties.decodeMatchKeys(keysId?.let { readTaskPropertyValue(el, it) }),
        // Absent means expanded: that is how tasks written before the
        // attribute existed behave in the desktop too.
        isExpanded = el.attr("expand") != "false"
      )
    }
  }

  private fun readResources(defs: Map<String, String>): List<ResourceNode> {
    val hoursId = defs[ForkProperties.RESOURCE_HOURS_PER_DAY]
    return resourcesElement()?.childElements("resource")?.map { el ->
      ResourceNode(
        id = el.attrOrEmpty("id"),
        name = el.attrOrEmpty("name"),
        roleId = el.attr("function")?.ifEmpty { null },
        mail = el.attr("contacts")?.ifEmpty { null },
        phone = el.attr("phone")?.ifEmpty { null },
        standardRate = el.childElements("rate")
          .firstOrNull { it.attr("name") == "standard" }
          ?.attr("value"),
        hoursPerDay = hoursId?.let { readResourcePropertyValue(el, it)?.toDoubleOrNull() }
      )
    } ?: emptyList()
  }

  private fun readAllocations(): List<Allocation> =
    allocationsElement()?.childElements("allocation")?.map { el ->
      Allocation(
        taskId = el.attrOrEmpty("task-id"),
        resourceId = el.attrOrEmpty("resource-id"),
        function = el.attr("function")?.ifEmpty { null },
        responsible = el.attr("responsible") == "true",
        load = el.attr("load")?.toDoubleOrNull() ?: 100.0
      )
    } ?: emptyList()

  private fun readRoles(): List<Role> =
    root.childElements("roles")
      .flatMap { it.childElements("role") }
      .map { Role(it.attrOrEmpty("id"), it.attrOrEmpty("name")) }

  private fun readCalendar(): WorkingCalendar {
    val calendars = root.firstChildElement("calendars") ?: return WorkingCalendar.DEFAULT
    val week = calendars.firstChildElement("day-types")?.firstChildElement("default-week")

    // In `<default-week>` an attribute value of "1" means non-working and
    // "0" means working — day-type ids, not booleans.
    val weekend = if (week == null) {
      WorkingCalendar.DEFAULT.weekendDays
    } else {
      mapOf(
        "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY,
        "wed" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY,
        "sun" to DayOfWeek.SUNDAY
      ).filter { (attr, _) -> week.attr(attr) == "1" }.values.toSet()
    }

    // `<date year=".." month=".." date=".." type="HOLIDAY"/>`; `month` is
    // 1-based (the desktop saver writes Calendar.getMonth() + 1). A missing
    // `year` means the holiday recurs annually, so we materialise it across
    // a window around today rather than ignoring it.
    val holidays = mutableSetOf<LocalDate>()
    val thisYear = LocalDate.now().year
    for (dateEl in calendars.childElements("date")) {
      val type = dateEl.attrOrEmpty("type")
      if (type.isNotEmpty() && type != "HOLIDAY") continue
      val month = dateEl.attr("month")?.toIntOrNull() ?: continue
      val day = dateEl.attr("date")?.toIntOrNull() ?: continue
      val year = dateEl.attr("year")?.toIntOrNull()
      val years = if (year != null) listOf(year) else ((thisYear - 5)..(thisYear + 5)).toList()
      for (y in years) {
        // 29 February in a non-leap year throws; skipping it is correct.
        runCatching { holidays.add(LocalDate.of(y, month, day)) }
      }
    }
    return WorkingCalendar(weekend, holidays)
  }

  // ------------------------------------------------------------ Mutations

  /**
   * Sets a task's completion percentage, clamped to 0..100.
   *
   * @return false if the task does not exist or is a summary task. Summary
   *   completion is recomputed by GanttProject from the children on load, so
   *   writing it would be a lie to the user rather than an edit.
   */
  fun setTaskCompletion(taskId: String, percent: Int): Boolean {
    val el = taskElement(taskId) ?: return false
    if (el.childElements("task").isNotEmpty()) return false
    el.setAttr("complete", percent.coerceIn(0, 100).toString())
    return true
  }

  /**
   * Folds or unfolds a task group, using the same `expand` attribute the
   * desktop writes — so the state carries between phone and desktop instead
   * of each keeping its own idea of the outline.
   *
   * @return false if the task is unknown or has no subtasks to fold
   */
  fun setTaskExpanded(taskId: String, expanded: Boolean): Boolean {
    val el = taskElement(taskId) ?: return false
    if (el.childElements("task").isEmpty()) return false
    el.setAttr("expand", expanded.toString())
    return true
  }

  /** @return false if the task is unknown or [name] is blank. */
  fun setTaskName(taskId: String, name: String): Boolean {
    val el = taskElement(taskId) ?: return false
    if (name.isBlank()) return false
    el.setAttr("name", name)
    return true
  }

  /** Planned effort in hours. Passing `null` removes the value again. */
  fun setTaskEffortHours(taskId: String, hours: Double?): Boolean =
    writeTaskProperty(taskId, ForkProperties.TASK_EFFORT_HOURS, "double", hours?.let { trimNumber(it) })

  /** Hours actually spent. Passing `null` removes the value again. */
  fun setTaskActualEffortHours(taskId: String, hours: Double?): Boolean =
    writeTaskProperty(
      taskId, ForkProperties.TASK_EFFORT_ACTUAL_HOURS, "double", hours?.let { trimNumber(it) }
    )

  /**
   * Adds [delta] to the actual hours and returns the new total, or `null` if
   * the task is unknown.
   *
   * The time-tracking import needs this: it must never write an absolute
   * value, only ever the difference, so that running the same import twice
   * cannot double the recorded hours.
   */
  fun addTaskActualEffortHours(taskId: String, delta: Double): Double? {
    val el = taskElement(taskId) ?: return null
    val defId = taskPropertyDefinitionsByName()[ForkProperties.TASK_EFFORT_ACTUAL_HOURS]
    val current = defId?.let { readTaskPropertyValue(el, it)?.toDoubleOrNull() } ?: 0.0
    val next = current + delta
    return if (setTaskActualEffortHours(taskId, next)) next else null
  }

  fun setTaskMatchKeys(taskId: String, keys: List<String>): Boolean =
    writeTaskProperty(
      taskId,
      ForkProperties.TASK_TOGGL_MATCH_KEYS,
      "text",
      ForkProperties.encodeMatchKeys(keys).ifEmpty { null }
    )

  fun addTaskMatchKey(taskId: String, key: String): Boolean {
    val el = taskElement(taskId) ?: return false
    val defId = taskPropertyDefinitionsByName()[ForkProperties.TASK_TOGGL_MATCH_KEYS]
    val existing = ForkProperties.decodeMatchKeys(defId?.let { readTaskPropertyValue(el, it) })
    return setTaskMatchKeys(taskId, existing + key)
  }

  // ------------------------------------------------------- Import ledger

  /**
   * Every time entry already imported into this project, with the hours
   * booked for it — the union over all tasks, summed per entry id.
   *
   * The union is what makes a per-task store behave project-wide. An entry
   * imported onto task A on the first run is found here on the second run
   * even if the user now assigns it to task B, which is exactly the case a
   * per-(entry, task) key would miss.
   *
   * Living in the file rather than on the device also means the record
   * travels with the project, and that abandoning an import by closing
   * without saving discards the record along with the hours — a device-local
   * ledger would remember an import that never reached the file, and those
   * hours could then never be imported again.
   */
  fun importedHoursByEntry(): Map<Long, Double> {
    val defId = taskPropertyDefinitionsByName()[ForkProperties.TASK_TOGGL_IMPORTED]
      ?: return emptyMap()
    val out = mutableMapOf<Long, Double>()
    fun walk(parent: XmlElement) {
      for (task in parent.childElements("task")) {
        for ((entryId, hours) in
          ForkProperties.decodeImportedHours(readTaskPropertyValue(task, defId))) {
          out[entryId] = (out[entryId] ?: 0.0) + hours
        }
        walk(task)
      }
    }
    tasksElement()?.let { walk(it) }
    return out
  }

  /** The ledger stored on one task alone. Mostly useful for inspection. */
  fun importedHoursOfTask(taskId: String): Map<Long, Double> {
    val el = taskElement(taskId) ?: return emptyMap()
    val defId = taskPropertyDefinitionsByName()[ForkProperties.TASK_TOGGL_IMPORTED]
      ?: return emptyMap()
    return ForkProperties.decodeImportedHours(readTaskPropertyValue(el, defId))
  }

  /**
   * Records that [hours] of time entry [entryId] were booked onto [taskId].
   *
   * Additive, like the hours themselves: importing the growth of an entry
   * twice must not make the ledger claim more than was actually written.
   */
  fun recordImportedHours(taskId: String, entryId: Long, hours: Double): Boolean {
    if (hours <= 0.0) return false
    val el = taskElement(taskId) ?: return false
    val defId = taskPropertyDefinitionsByName()[ForkProperties.TASK_TOGGL_IMPORTED]
    val existing = ForkProperties.decodeImportedHours(defId?.let { readTaskPropertyValue(el, it) })
    val merged = existing.toMutableMap()
    merged[entryId] = (merged[entryId] ?: 0.0) + hours
    return writeTaskProperty(
      taskId,
      ForkProperties.TASK_TOGGL_IMPORTED,
      "text",
      ForkProperties.encodeImportedHours(merged).ifEmpty { null }
    )
  }

  /** Working hours per day for a resource. Passing `null` removes the value. */
  fun setResourceHoursPerDay(resourceId: String, hours: Double?): Boolean =
    writeResourceProperty(
      resourceId, ForkProperties.RESOURCE_HOURS_PER_DAY, "double", hours?.let { trimNumber(it) }
    )

  fun setResourceName(resourceId: String, name: String): Boolean {
    val el = resourceElement(resourceId) ?: return false
    if (name.isBlank()) return false
    el.setAttr("name", name)
    return true
  }

  /**
   * Changes the load of an existing assignment.
   *
   * @return false if there is no such assignment, or if [load] is not
   *   positive — a zero-load assignment means "assigned but contributing
   *   nothing", which is a mistake in every case we could construct.
   */
  fun setAllocationLoad(taskId: String, resourceId: String, load: Double): Boolean {
    val el = allocationElement(taskId, resourceId) ?: return false
    if (load <= 0.0) return false
    el.setAttr("load", trimNumber(load))
    return true
  }

  fun setAllocationResponsible(taskId: String, resourceId: String, responsible: Boolean): Boolean {
    val el = allocationElement(taskId, resourceId) ?: return false
    el.setAttr("responsible", responsible.toString())
    return true
  }

  /**
   * Assigns a resource to a task. If the assignment already exists its load
   * and responsibility are updated rather than a duplicate being appended —
   * GanttProject treats (task, resource) as unique and a second entry
   * corrupts the resource load chart.
   *
   * @return false if either the task or the resource is unknown.
   */
  fun assignResource(
    taskId: String,
    resourceId: String,
    load: Double = 100.0,
    responsible: Boolean = false
  ): Boolean {
    if (taskElement(taskId) == null) return false
    val resource = resourceElement(resourceId) ?: return false
    allocationElement(taskId, resourceId)?.let { existing ->
      existing.setAttr("load", trimNumber(load))
      existing.setAttr("responsible", responsible.toString())
      return true
    }
    val allocations = allocationsElement() ?: createAllocationsElement()
    allocations.children.add(
      XmlElement("allocation").apply {
        setAttr("task-id", taskId)
        setAttr("resource-id", resourceId)
        // Inherit the resource's role, which is what the desktop does when a
        // new assignment is created; an allocation whose role id is unknown
        // is rejected by the desktop loader.
        setAttr("function", resource.attr("function")?.ifEmpty { null } ?: "Default:0")
        setAttr("responsible", responsible.toString())
        setAttr("load", trimNumber(load))
      }
    )
    return true
  }

  fun unassignResource(taskId: String, resourceId: String): Boolean {
    val allocations = allocationsElement() ?: return false
    val el = allocationElement(taskId, resourceId) ?: return false
    allocations.removeChild(el)
    return true
  }

  // ----------------------------------------------------- Custom properties

  /**
   * Writes a custom property on a task, creating the definition if needed.
   * A value of `null` removes the property from that task.
   */
  private fun writeTaskProperty(
    taskId: String,
    name: String,
    valueType: String,
    value: String?
  ): Boolean {
    val task = taskElement(taskId) ?: return false
    if (value == null) {
      val defId = taskPropertyDefinitionsByName()[name] ?: return true
      task.childElements("customproperty")
        .filter { it.attr("taskproperty-id") == defId }
        .forEach { task.removeChild(it) }
      return true
    }
    val defId = findOrCreateTaskPropertyDefinition(name, valueType)
    val existing = task.childElements("customproperty")
      .firstOrNull { it.attr("taskproperty-id") == defId }
    if (existing != null) {
      existing.setAttr("value", value)
      return true
    }
    val el = XmlElement("customproperty").apply {
      setAttr("taskproperty-id", defId)
      setAttr("value", value)
    }
    // Insert before the first nested <task> so child order matches what the
    // desktop writes (notes, depend, customproperty, then children).
    task.insertBefore(el, task.childElements("task").firstOrNull())
    return true
  }

  private fun writeResourceProperty(
    resourceId: String,
    name: String,
    valueType: String,
    value: String?
  ): Boolean {
    val resource = resourceElement(resourceId) ?: return false
    if (value == null) {
      val defId = resourcePropertyDefinitionsByName()[name] ?: return true
      resource.childElements("custom-property")
        .filter { it.attr("definition-id") == defId }
        .forEach { resource.removeChild(it) }
      return true
    }
    val defId = findOrCreateResourcePropertyDefinition(name, valueType)
    val existing = resource.childElements("custom-property")
      .firstOrNull { it.attr("definition-id") == defId }
    if (existing != null) {
      existing.setAttr("value", value)
      return true
    }
    resource.children.add(
      XmlElement("custom-property").apply {
        setAttr("definition-id", defId)
        setAttr("value", value)
      }
    )
    return true
  }

  private fun taskPropertiesElement(): XmlElement {
    val tasks = tasksElement() ?: createTasksElement()
    tasks.firstChildElement("taskproperties")?.let { return it }
    val el = XmlElement("taskproperties")
    tasks.insertBefore(el, tasks.children.firstOrNull())
    return el
  }

  /** Custom task property definitions by name. Built-ins (`tpd*`) are excluded. */
  private fun taskPropertyDefinitionsByName(): Map<String, String> =
    tasksElement()?.firstChildElement("taskproperties")
      ?.childElements("taskproperty")
      ?.filter { it.attr("type") == "custom" }
      ?.associate { it.attrOrEmpty("name") to it.attrOrEmpty("id") }
      ?: emptyMap()

  private fun resourcePropertyDefinitionsByName(): Map<String, String> =
    resourcesElement()?.childElements("custom-property-definition")
      ?.associate { it.attrOrEmpty("name") to it.attrOrEmpty("id") }
      ?: emptyMap()

  private fun findOrCreateTaskPropertyDefinition(name: String, valueType: String): String {
    taskPropertyDefinitionsByName()[name]?.let { return it }
    val container = taskPropertiesElement()
    val id = nextCustomId(container.childElements("taskproperty").map { it.attrOrEmpty("id") })
    container.children.add(
      XmlElement("taskproperty").apply {
        setAttr("id", id)
        setAttr("name", name)
        setAttr("type", "custom")
        setAttr("valuetype", valueType)
      }
    )
    return id
  }

  private fun findOrCreateResourcePropertyDefinition(name: String, valueType: String): String {
    resourcePropertyDefinitionsByName()[name]?.let { return it }
    val container = resourcesElement() ?: createResourcesElement()
    val id = nextCustomId(
      container.childElements("custom-property-definition").map { it.attrOrEmpty("id") }
    )
    val el = XmlElement("custom-property-definition").apply {
      setAttr("id", id)
      setAttr("name", name)
      setAttr("type", valueType)
    }
    // Definitions precede the <resource> elements, matching the desktop writer.
    container.insertBefore(el, container.childElements("resource").firstOrNull())
    return id
  }

  /**
   * Next free `tpcN` id.
   *
   * Derived from the highest id already in use, not from the count: after a
   * definition has been deleted the count would hand out an id that is still
   * taken, and two definitions sharing an id corrupt every value under it.
   */
  private fun nextCustomId(existing: List<String>): String {
    val max = existing.mapNotNull { it.removePrefix("tpc").toIntOrNull() }.maxOrNull() ?: -1
    return "tpc${max + 1}"
  }

  private fun readTaskPropertyValue(task: XmlElement, defId: String): String? =
    task.childElements("customproperty")
      .firstOrNull { it.attr("taskproperty-id") == defId }
      ?.attr("value")
      ?.ifEmpty { null }

  private fun readResourcePropertyValue(resource: XmlElement, defId: String): String? =
    resource.childElements("custom-property")
      .firstOrNull { it.attr("definition-id") == defId }
      ?.attr("value")
      ?.ifEmpty { null }

  // -------------------------------------------------------- Element access

  private fun tasksElement(): XmlElement? = root.firstChildElement("tasks")

  private fun createTasksElement(): XmlElement =
    XmlElement("tasks").also { root.children.add(it) }

  private fun resourcesElement(): XmlElement? = root.firstChildElement("resources")

  /** Created directly after `<tasks>`, which is where the desktop puts it. */
  private fun createResourcesElement(): XmlElement =
    XmlElement("resources").also { insertAfter(it, tasksElement()) }

  private fun allocationsElement(): XmlElement? = root.firstChildElement("allocations")

  private fun createAllocationsElement(): XmlElement =
    XmlElement("allocations").also { insertAfter(it, resourcesElement()) }

  private fun insertAfter(element: XmlElement, reference: XmlElement?) {
    val index = reference?.let { root.children.indexOf(it) } ?: -1
    if (index < 0) root.children.add(element) else root.children.add(index + 1, element)
  }

  /** Depth-first: task ids are unique across the whole hierarchy. */
  private fun taskElement(taskId: String): XmlElement? {
    fun search(parent: XmlElement): XmlElement? {
      for (child in parent.childElements("task")) {
        if (child.attr("id") == taskId) return child
        search(child)?.let { return it }
      }
      return null
    }
    return tasksElement()?.let { search(it) }
  }

  private fun resourceElement(resourceId: String): XmlElement? =
    resourcesElement()?.childElements("resource")?.firstOrNull { it.attr("id") == resourceId }

  private fun allocationElement(taskId: String, resourceId: String): XmlElement? =
    allocationsElement()?.childElements("allocation")
      ?.firstOrNull { it.attr("task-id") == taskId && it.attr("resource-id") == resourceId }
}

// ------------------------------------------------------------------ Helpers

/**
 * A missing or unparseable date falls back to today rather than throwing: one
 * bad attribute should not make an otherwise readable project unopenable.
 */
private fun parseDateOrToday(text: String?): LocalDate =
  if (text.isNullOrBlank()) LocalDate.now()
  else try {
    LocalDate.parse(text)
  } catch (e: DateTimeParseException) {
    LocalDate.now()
  }

/** Shortest lossless notation: 8.0 -> "8", 7.5 -> "7.5". */
internal fun trimNumber(value: Double): String {
  val rounded = Math.round(value * 1_000_000.0) / 1_000_000.0
  return if (rounded == Math.rint(rounded) && !rounded.isInfinite()) {
    rounded.toLong().toString()
  } else {
    rounded.toString()
  }
}
