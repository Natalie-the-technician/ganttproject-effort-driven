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
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.task.algorithm.findEffortDefinition
import java.time.LocalDate

/**
 * [fork change] Where the home office of [HomeOffice] is kept: TWO resource properties.
 *
 * Both are ordinary text properties of the person, so `ResourceSaver.saveCustomProperties` writes
 * each as a plain `<custom-property definition-id=… value=…/>` and every reader of the format —
 * this fork, a stock GanttProject, the cloud importer — carries them through untouched. Checked,
 * not assumed: `HomeOfficeStorageTest.der rundweg durch eine fremde fassung des programms…`
 * writes the file, reads it with the SECOND, independent reader (`XmlProjectImporter`) and gets
 * the same two texts back, and does it once with a German and once with an English interface.
 *
 * ═══ WHY TWO PROPERTIES AND NOT ONE ═══
 *
 * The pattern and the periods COULD share one text. They do not, and the reason is not tidiness —
 * it is two costs of the single column that were measured rather than guessed.
 *
 * FIRST: ONE COLUMN MEANS ONE ERROR CHANNEL, AND THEREFORE ONE LOCK. This fork's panels refuse to
 * write while what is stored cannot be read whole (the decision of `2026-09-04-arbeitswoche-
 * oberflaeche.md`: an unreadable column locks the buttons instead of quietly dropping the line it
 * could not parse). With ONE column, a mistyped date in a PERIOD would lock the seven weekday
 * boxes as well — a person could not correct their Friday because a June date has a typo in it.
 * With two, each half locks its own buttons and the other half stays usable. That is what
 * `HomeOfficePanelTest.ein unlesbarer zeitraum sperrt nur die zeitraum-knoepfe` measures.
 *
 * SECOND: ONE COLUMN CANNOT KEEP BOTH GRAMMARS. The two texts are not merely different, they
 * COLLIDE: `2026-06-01` is a valid whole entry in both, and it means different things.
 * [HomeOfficePeriods.parse] reads it as the single day 1 June; [HomeOfficeWeek.parse] reads it as a
 * list of weekdays, finds no number between 1 and 7 and reports it. A shared column would have to
 * drop one of the two readings — and the one it would have to drop is the bare-date shorthand,
 * which is the shortest and likeliest thing a person types. Measured in
 * `HomeOfficeStorageTest.derselbe text bedeutet in den zwei spalten zweierlei`.
 *
 * What the second column costs is one more entry in the custom-columns tab. That tab is a list, a
 * further line in it costs nothing, and each of the two lines then holds ONE syntax that a person
 * editing by hand can actually be told.
 *
 * ═══ WHY NOT NEXT TO THE WORKING WEEK IN LevellingAdapter.kt ═══
 *
 * `findOrCreateWorkWeek` and its neighbours sit in `LevellingAdapter.kt` for historical reasons —
 * that file grew into the place where resource properties happened to be added. It is past 1100
 * lines, and two sessions are building on neighbouring packages at the moment. A new pair of
 * properties gets its own file so that the model and its storage can be read together and so that
 * the merge does not run through a file everybody is touching.
 */

/** The weekly home-office pattern, as a property of the person. [fork change] */
const val RESOURCE_HOME_OFFICE_WEEK = "home_office_week"

/** The home-office periods, as a property of the person. [fork change] */
const val RESOURCE_HOME_OFFICE_PERIODS = "home_office_periods"

fun findOrCreateHomeOfficeWeek(manager: CustomPropertyManager): CustomPropertyDefinition =
  manager.findEffortDefinition(RESOURCE_HOME_OFFICE_WEEK)
    ?: manager.createDefinition(RESOURCE_HOME_OFFICE_WEEK, CustomPropertyClass.TEXT.iD,
                                forkText("fork.column.homeOfficeWeek"), null)

fun findOrCreateHomeOfficePeriods(manager: CustomPropertyManager): CustomPropertyDefinition =
  manager.findEffortDefinition(RESOURCE_HOME_OFFICE_PERIODS)
    ?: manager.createDefinition(RESOURCE_HOME_OFFICE_PERIODS, CustomPropertyClass.TEXT.iD,
                                forkText("fork.column.homeOfficePeriods"), null)

/**
 * The weekly pattern of this person, together with whatever could not be read.
 *
 * A PERSON WITH NO PROPERTY AT ALL gets an empty pattern and no errors — not an exception and not
 * a null. Nothing entered is no home office; see the comment of [HomeOffice].
 */
fun HumanResource.homeOfficeWeek(manager: CustomPropertyManager): HomeOfficeWeekParseResult {
  val definition = manager.findEffortDefinition(RESOURCE_HOME_OFFICE_WEEK)
    ?: return HomeOfficeWeekParseResult(HomeOfficeWeek(), emptyList())
  return HomeOfficeWeek.parse(this.getCustomField(definition)?.toString())
}

/** The home-office periods of this person, together with whatever could not be read. */
fun HumanResource.homeOfficePeriods(manager: CustomPropertyManager): HomeOfficePeriodsParseResult {
  val definition = manager.findEffortDefinition(RESOURCE_HOME_OFFICE_PERIODS)
    ?: return HomeOfficePeriodsParseResult(HomeOfficePeriods(), emptyList())
  return HomeOfficePeriods.parse(this.getCustomField(definition)?.toString())
}

/**
 * Both halves at once, and the errors of each KEPT APART.
 *
 * Apart, because that is the whole benefit of the two columns: a caller that wants to lock
 * something has to be able to lock the right half. A caller that only wants to complain can join
 * them with [errors].
 */
data class HomeOfficeParseResult(
  val homeOffice: HomeOffice,
  val weekErrors: List<String>,
  val periodErrors: List<String>
) {
  val hasErrors: Boolean get() = weekErrors.isNotEmpty() || periodErrors.isNotEmpty()
  val errors: List<String> get() = weekErrors + periodErrors
}

/** The home office of this person — pattern and periods — with the errors of both halves. */
fun HumanResource.homeOffice(manager: CustomPropertyManager): HomeOfficeParseResult {
  val week = this.homeOfficeWeek(manager)
  val periods = this.homeOfficePeriods(manager)
  return HomeOfficeParseResult(HomeOffice(week.week, periods.periods), week.errors, periods.errors)
}

/**
 * Does this person work from home on [day]?
 *
 * A PLAIN `Boolean`, never `null` — nothing entered means NO home office, not „unknown" and not
 * „ask the project calendar". The contrast with [HumanResource.worksOn] is deliberate and is
 * argued in the comment of [HomeOffice].
 *
 * PARSES THE TEXT ON EVERY CALL, exactly as [HumanResource.workWeek] does. Fine for a dialog, and
 * NOT fine per day inside a walk: whoever asks this in a loop reads it once with
 * [HumanResource.homeOffice] and keeps the [HomeOffice]. The same warning stands over
 * `WorkWeekEffect.kt` and for the same reason.
 */
fun HumanResource.worksFromHome(manager: CustomPropertyManager, day: LocalDate): Boolean =
  this.homeOffice(manager).homeOffice.worksFromHome(day)

/** Writes the weekly pattern back, in the form [HomeOfficeWeek.toString] produces. */
fun HumanResource.setHomeOfficeWeek(manager: CustomPropertyManager, week: HomeOfficeWeek) {
  this.setValue(findOrCreateHomeOfficeWeek(manager), week.toString())
}

/** Writes the periods back, in the form [HomeOfficePeriods.toString] produces. */
fun HumanResource.setHomeOfficePeriods(manager: CustomPropertyManager, periods: HomeOfficePeriods) {
  this.setValue(findOrCreateHomeOfficePeriods(manager), periods.toString())
}
