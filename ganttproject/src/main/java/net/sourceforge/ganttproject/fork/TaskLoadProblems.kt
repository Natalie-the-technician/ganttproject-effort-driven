/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.

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
package net.sourceforge.ganttproject.fork

/**
 * One `<depend>` element whose `id` names a task this file does not have.
 *
 * The missing id is kept exactly as it stood in the file — that is what a person has to search
 * for to repair it. [ownerTaskName] is `null` when even the task carrying the `<depend>` element
 * could not be found; [ownerLabel] falls back to the id in that case.
 */
data class DanglingDependencyProblem(
  val ownerTaskName: String?, val ownerTaskID: Int, val missingTaskID: Int
) {
  val ownerLabel: String get() = ownerTaskName?.takeIf { it.isNotBlank() } ?: "#$ownerTaskID"
}

/**
 * One custom field value that is not a valid literal of the type its column declares.
 *
 * [rawValue] is kept exactly as written in the file, for the same reason as above.
 */
data class CustomPropertyProblem(
  val taskName: String?, val taskID: Int, val columnName: String, val rawValue: String
) {
  val taskLabel: String get() = taskName?.takeIf { it.isNotBlank() } ?: "#$taskID"
}

/**
 * [fork change] The dependency- and custom-property problems of ONE load, collected so that ONE
 * message can be shown for the whole file.
 *
 * Same split as [VacationProblems]: `TaskLoader` and `loadDependencyGraph` only collect, and stay
 * free of any user interface. Whoever has a `UIFacade` — [net.sourceforge.ganttproject.document.ProxyDocument]
 * — decides whether there is anyone to tell.
 *
 * WHY BOTH KINDS IN ONE CLASS: a single `.gan` file that triggers one is not unlikely to trigger
 * the other too (both come from a file edited by hand or by another tool), and a reader should
 * not get two separate notification boxes for what is, from their side, one broken file.
 */
class TaskLoadProblems {
  private val danglingDependencies = mutableListOf<DanglingDependencyProblem>()
  private val customProperties = mutableListOf<CustomPropertyProblem>()

  val isEmpty: Boolean get() = danglingDependencies.isEmpty() && customProperties.isEmpty()

  /** For tests: everything collected so far, dependencies first. */
  val problems: List<Any> get() = danglingDependencies + customProperties

  fun recordDanglingDependency(problem: DanglingDependencyProblem) {
    danglingDependencies.add(problem)
  }

  fun recordCustomProperty(problem: CustomPropertyProblem) {
    customProperties.add(problem)
  }

  /**
   * ONE text for the whole load, or `null` when there is nothing to say.
   *
   * `null` and not an empty string, so that a caller cannot accidentally show an empty box: see
   * [VacationProblems.message] for why that matters.
   */
  fun message(): String? {
    if (isEmpty) {
      return null
    }
    val text = StringBuilder()
    if (danglingDependencies.isNotEmpty()) {
      text.append(
        if (danglingDependencies.size == 1) forkText("fork.taskLoad.dependency.one")
        else forkText("fork.taskLoad.dependency.many", danglingDependencies.size))
      text.appendLine()
      danglingDependencies.forEach {
        text.append("  • ")
          .append(forkText("fork.taskLoad.dependency.item", it.ownerLabel, it.missingTaskID))
          .appendLine()
      }
      text.appendLine().append(forkText("fork.taskLoad.dependency.consequence"))
    }
    if (customProperties.isNotEmpty()) {
      if (danglingDependencies.isNotEmpty()) {
        text.appendLine()
      }
      text.append(
        if (customProperties.size == 1) forkText("fork.taskLoad.customProperty.one")
        else forkText("fork.taskLoad.customProperty.many", customProperties.size))
      text.appendLine()
      customProperties.forEach {
        text.append("  • ")
          .append(forkText("fork.taskLoad.customProperty.item", it.taskLabel, it.columnName, it.rawValue))
          .appendLine()
      }
      text.appendLine().append(forkText("fork.taskLoad.customProperty.consequence"))
    }
    return text.toString()
  }
}
