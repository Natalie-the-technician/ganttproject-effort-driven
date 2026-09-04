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

import biz.ganttproject.customproperty.CustomPropertyClass
import biz.ganttproject.customproperty.CustomPropertyDefinition
import biz.ganttproject.customproperty.CustomPropertyHolder
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.GPLogger
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.algorithm.findEffortDefinition

/**
 * Can a task be done from home? [fork change]
 *
 * THREE STATES, NOT TWO. That is the whole point of this file and the reason it is not a plain
 * boolean like [TASK_WAIT_ONLY] next door:
 *
 *   [HomeWorkMark.NOT_DECIDED]  nobody has said anything about this task. Every task in every
 *                               existing plan is in this state, and it is the state a new task
 *                               starts in.
 *   [HomeWorkMark.FROM_HOME]    somebody looked at this task and said: this can be done from home.
 *   [HomeWorkMark.ON_SITE]      somebody looked at this task and said: this cannot.
 *
 * NOT_DECIDED AND FROM_HOME ARE TREATED ALIKE AND ARE STILL NOT THE SAME THING. For the only
 * question the schedule ever asks -- may this task run on somebody's home working day? -- the two
 * answer identically; that fold is [HomeWorkMark.allowsHomeWorkingDay] and it lives in exactly one
 * place. But they must stay distinguishable in storage, because the plan is meant to be able to
 * say later "nobody has ever decided about these tasks". That report is not built here; this file
 * only keeps it possible.
 *
 * SO DO NOT COLLAPSE THE TWO. They behave the same today, which makes merging them look like a
 * simplification. It is not one: it throws away the only thing that distinguishes "we thought
 * about it and it is fine" from "nobody looked", and no test outside this feature would go red.
 *
 * ----------------------------------------------------------------------------------------------
 * WHY THE STORED BOOLEAN MEANS "on site only" AND NOT "works from home"
 * ----------------------------------------------------------------------------------------------
 *
 * The three states ride on one nullable boolean: absent/null is NOT_DECIDED, `true` is ON_SITE,
 * `false` is FROM_HOME. Which way round `true` points was decided by measurement, not by taste,
 * and it still matters even though `null` already carries the harmless default:
 *
 *  - `CustomColumnsPanel.save` walks EVERY definition and calls
 *    `myHolder.addCustomProperty(def, it.value.get())` -- and for a property with no value that
 *    string is `""`. `CustomColumnsValues.addCustomProperty` runs it through
 *    `PropertyTypeEncoder`, where `BOOLEAN` decodes `""` with `java.lang.Boolean.valueOf("")`,
 *    which is `false` and NOT null. So confirming the task dialog writes an explicit `false` into
 *    every boolean column of every task, whether anybody touched it or not. That is measured in
 *    `TaskHomeWorkDialogTest`.
 *
 *  - Therefore `false` is the value that turns up by accident, and `true` is the value that only
 *    appears when somebody means it. The dangerous state -- the one that will keep a task off a
 *    home working day -- has to be the one accidents cannot produce. Hence `true` = ON_SITE.
 *
 * The stray `false` still costs something: it moves a task from NOT_DECIDED to FROM_HOME, so the
 * "nobody decided" report would lose it. [applyHomeWorkMark] is what prevents that -- it runs
 * after the panel and clears the value again for a task still marked NOT_DECIDED. That order is
 * not luck; `TaskPropertiesController.save` passes the fields into the block that
 * `CustomColumnsPanel.save` runs last, and `TaskHomeWorkDialogTest` measures it end to end.
 */
enum class HomeWorkMark {
  /** Nobody has said. Behaves like [FROM_HOME] and must not be confused with it -- see above. */
  NOT_DECIDED,

  /** Said out loud: this task can be done from home. */
  FROM_HOME,

  /** Said out loud: this task needs somebody on site. The only state that will restrict anything. */
  ON_SITE
}

/**
 * THE ONE PLACE where "nobody said" is folded onto "may be done from home".
 *
 * Named, and deliberately not inlined as `!= ON_SITE` at the call sites: whoever later has to
 * change how an undecided task is treated should have exactly one line to change and should see
 * this comment while doing it.
 */
val HomeWorkMark.allowsHomeWorkingDay: Boolean get() = this != HomeWorkMark.ON_SITE

/** The column. Stored as "on site only" -- read the note above before turning it around. */
const val TASK_ON_SITE_ONLY = "on_site_only"

/**
 * The definition of the column, created if the project has none yet.
 *
 * `null` as the default value is not an oversight. `CustomColumnsValues.getValue` returns
 * `mapCustomColumnValue[def.id] ?: def.defaultValue`, so a definition carrying a default would
 * answer for EVERY task that has no value of its own -- and there would be no NOT_DECIDED left in
 * the whole plan.
 */
fun findOrCreateOnSiteOnly(manager: CustomPropertyManager): CustomPropertyDefinition =
  manager.findEffortDefinition(TASK_ON_SITE_ONLY)
    ?: manager.createDefinition(TASK_ON_SITE_ONLY, CustomPropertyClass.BOOLEAN.iD,
                                forkText("fork.column.onSiteOnly"), null)

/**
 * Reads the raw value out of [holder] and reports which of the three states the task is in.
 *
 * FOUR WAYS A PLAN CAN BE SILENT, and all four are [HomeWorkMark.NOT_DECIDED], not
 * [HomeWorkMark.FROM_HOME]:
 *
 *  1. the project has no such column at all -- a file written before this change,
 *  2. the column exists but this task has no value in it,
 *  3. the value is `null`,
 *  4. the value is the EMPTY STRING. This one is not hypothetical: `""` is what a text-shaped
 *     path hands over for "nothing here", and `java.lang.Boolean.valueOf("")` would turn it into
 *     `false`, which under this reading means somebody decided it can be done from home. It has to
 *     be caught here, before any boolean conversion.
 *
 * THE `toString()` PATH, AND WHAT IT IS ACTUALLY FOR. [Task.isWaitOnly] next door carries the same
 * construction with the note that the value "comes back from the file as a string too". MEASURED
 * ON 04.09.2026, that is not what happens for a BOOLEAN column: both readers convert the text
 * themselves -- `TaskLoader.loadCustomProperties` with `java.lang.Boolean.valueOf(valueStr)` and
 * `XmlProjectImporter.setTaskCustomValue` with `it.toBoolean()` -- and `CustomColumnsValues.setValue`
 * would refuse a `String` for a boolean column anyway ("value class=..., column class=...").
 *
 * It is still needed, for a case that is real but different: [findEffortDefinition] finds a column
 * by its id OR its name, so the definition this read gets hold of need not be the boolean one this
 * fork creates. A file that declares the same id with `valuetype="text"` -- an older version, a
 * hand-edited file, a column somebody made themselves -- hands the value over as a `String`, and
 * then `true` has to keep meaning `true` instead of turning into "nobody decided". That is
 * measured in `TaskHomeWorkTest` and, through the real loader, in `TaskHomeWorkRoundTripTest`.
 *
 * Anything that is neither `true` nor `false` -- somebody typed into the column by hand -- is
 * NOT_DECIDED and is logged. Guessing would be worse: the two things a guess could produce are a
 * silent restriction or a silent permission, and the honest answer is that nobody has said.
 */
fun homeWorkMark(holder: CustomPropertyHolder, manager: CustomPropertyManager): HomeWorkMark {
  val def = manager.findEffortDefinition(TASK_ON_SITE_ONLY) ?: return HomeWorkMark.NOT_DECIDED
  val raw = holder.getValue(def) ?: return HomeWorkMark.NOT_DECIDED
  if (raw is Boolean) {
    return if (raw) HomeWorkMark.ON_SITE else HomeWorkMark.FROM_HOME
  }
  val text = raw.toString().trim()
  return when {
    text.isEmpty() -> HomeWorkMark.NOT_DECIDED
    text.equals("true", ignoreCase = true) -> HomeWorkMark.ON_SITE
    text.equals("false", ignoreCase = true) -> HomeWorkMark.FROM_HOME
    else -> {
      LOG.debug("The \"{}\" column of a task holds {}, which is neither true nor false. " +
                "Read as \"nobody has decided\".", TASK_ON_SITE_ONLY, text)
      HomeWorkMark.NOT_DECIDED
    }
  }
}

/** Which of the three states is this task in? */
fun Task.homeWorkMark(manager: CustomPropertyManager): HomeWorkMark =
  homeWorkMark(this.customValues, manager)

/**
 * May this task run on a home working day of the people on it?
 *
 * This is the question the schedule will ask, in the wording it was asked for, and it is the only
 * caller of the fold. Whoever needs it should ask here rather than comparing the enum by hand.
 */
fun Task.mayRunOnHomeWorkingDay(manager: CustomPropertyManager): Boolean =
  this.homeWorkMark(manager).allowsHomeWorkingDay

/**
 * Writes the state the dialog shows into the holder the dialog is about to commit.
 *
 * No JavaFX type appears here on purpose -- the same reason `applyEffortFieldsThenSyncColumns`
 * gives for sitting outside the panels: this rule can then be measured without a screen, and it is
 * a rule worth measuring.
 *
 * NOT_DECIDED CLEARS. It does not write `false`, and it does not simply do nothing either. Doing
 * nothing would be wrong for the measured reason in the note at the top of this file: by the time
 * this runs, `CustomColumnsPanel.save` has already put an explicit `false` into the holder for
 * every boolean column, and a task nobody decided about would silently become FROM_HOME. Clearing
 * takes that back, leaves the task without a value of its own, and so writes nothing into the
 * `.gan` file for it.
 *
 * And no definition is created for NOT_DECIDED. A project in which nobody decided anything does
 * not gain the column by being looked at. (`GanttProjectImpl.ensureCapacityColumns` creates it for
 * a project that is opened normally, so that it can be seen and filled in at all -- that is a
 * different question from this one.)
 */
fun applyHomeWorkMark(holder: CustomPropertyHolder, definitions: CustomPropertyManager,
                      mark: HomeWorkMark) {
  when (mark) {
    HomeWorkMark.NOT_DECIDED ->
      definitions.findEffortDefinition(TASK_ON_SITE_ONLY)?.let { holder.setValue(it, null) }
    HomeWorkMark.FROM_HOME -> holder.setValue(findOrCreateOnSiteOnly(definitions), false)
    HomeWorkMark.ON_SITE -> holder.setValue(findOrCreateOnSiteOnly(definitions), true)
  }
}

private val LOG = GPLogger.create("Fork.HomeWork")
