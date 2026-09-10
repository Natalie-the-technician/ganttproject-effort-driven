/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

/**
 * "Who is working on this with me" — the participant's side of an assignment.
 *
 * ## Why this is not the assignment list the app already has
 *
 * The task sheet's assignment section is the *planner's* view: load per cent,
 * capacity, a slider, a responsible flag. Those are the numbers the person who
 * makes the plan works with, and they are exactly the clutter the app is
 * supposed to spare everyone else. What the participant needs from the same
 * data is much smaller — a name, a role if the file gives one, and which of
 * the names is theirs.
 *
 * Nothing here writes. Everything is derived from what is already in the file.
 */

/**
 * A person's role on one task, as far as the file allows it to be known.
 *
 * Typed rather than pre-rendered text so the core stays free of any one
 * language; `Localise.kt` turns these into words.
 */
sealed interface TeammateRole {
  /**
   * A role the project names itself, through `<role id=… name=…/>`. Any
   * roleset a planner has defined arrives this way.
   */
  data class Named(val name: String) : TeammateRole

  /**
   * `Default:1`. GanttProject builds two roles into every project —
   * "undefined" (`Default:0`) and the project manager — and writes neither
   * name into the file, only the marker `<roles roleset-name="Default"/>`.
   * So the one role most files actually carry cannot be looked up; it has to
   * be recognised. Its counterpart `Default:0` means "no role stated" and is
   * reported as no role at all, because showing the word "undefined" next to
   * a colleague's name tells nobody anything.
   */
  data object ProjectManager : TeammateRole
}

/**
 * One person on a task.
 *
 * @param name `null` when the file has an assignment but no resource behind
 *   it — a hand-edited file does this. The row is kept: a person who cannot be
 *   named is still someone the participant is sharing the task with, and
 *   dropping the row would quietly shrink the crew.
 */
data class Teammate(
  val resourceId: String,
  val name: String?,
  val role: TeammateRole?,
  val isMe: Boolean
)

/** The id GanttProject gives its built-in project manager in every project. */
private const val ROLE_ID_PROJECT_MANAGER = "Default:1"

/**
 * Everyone assigned to [taskId], in the order the file lists them.
 *
 * The order is the file's on purpose. Sorting — me first, or alphabetically —
 * would make the same task look different on the phone than on the desktop,
 * and there is no reading of "who is on this" that needs a ranking.
 *
 * @param meResourceId the resource the reader is, from [resolveMe]. `null`
 *   simply means nobody is marked; the list is the same either way.
 */
fun teammatesOfTask(
  model: ProjectModel,
  taskId: String,
  meResourceId: String? = null
): List<Teammate> {
  val seen = mutableSetOf<String>()
  return model.allocationsOfTask(taskId)
    // An assignment with no resource-id at all names nobody, not even by id.
    .filter { it.resourceId.isNotEmpty() }
    // A file edited by hand can allocate the same person twice; on screen that
    // is one person, not two.
    .filter { seen.add(it.resourceId) }
    .map { allocation ->
      Teammate(
        resourceId = allocation.resourceId,
        name = model.resource(allocation.resourceId)?.name?.trim()?.ifEmpty { null },
        role = roleOf(model, allocation.function),
        isMe = meResourceId != null && allocation.resourceId == meResourceId
      )
    }
}

/**
 * Which resource the reader is, or `null` when that cannot be said.
 *
 * The app already asks for a name — the one it stamps on time entries — and
 * that name is the only thing on the device that claims to be an identity. So
 * it is matched against the project's resources, ignoring case and surrounding
 * space, and **only** when exactly one resource carries it.
 *
 * Two resources with the same name, or none, produce `null` rather than a
 * best guess. Marking the wrong person as "you" is worse than marking nobody:
 * the reader would not know they were being told something false.
 */
fun resolveMe(model: ProjectModel, personName: String?): String? {
  val wanted = personName?.trim()?.lowercase() ?: return null
  if (wanted.isEmpty()) return null
  return model.resources
    .filter { it.name.trim().lowercase() == wanted }
    .singleOrNull()
    ?.id
}

/**
 * The role behind an allocation's `function` attribute.
 *
 * The file is asked first, so a project that defines its own roleset — even
 * one that reuses an id from the built-in set — is taken at its word. Only
 * then comes the one id GanttProject hardcodes. Anything else, including the
 * built-in software-development roleset whose names live in the desktop's
 * translation files and nowhere in the project, yields no role: a raw
 * `SoftwareDevelopment:4` on screen is noise, not information.
 */
private fun roleOf(model: ProjectModel, function: String?): TeammateRole? {
  val id = function?.trim()?.ifEmpty { null } ?: return null
  model.roles.firstOrNull { it.id == id }?.let { role ->
    return role.name.trim().ifEmpty { null }?.let(TeammateRole::Named)
  }
  return if (id == ROLE_ID_PROJECT_MANAGER) TeammateRole.ProjectManager else null
}
