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

import biz.ganttproject.customproperty.isStablePropertyId
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every property this fork defines itself has to be declared stable in `CustomColumnsManager.kt`.
 *
 * WHY THIS TEST EXISTS. A property of the fork carries a fixed id and a TRANSLATED header. If its id
 * is missing from the declared list, `importData` falls back to matching the display name, and the
 * column then arrives a second time as soon as the imported file was written in another language --
 * values visible, effect none, no warning anywhere. That failure is invisible in a single-language
 * test run, so nothing else would catch it. The list of strings lives in the core module (which cannot
 * see these constants) and the constants live here; this test is the seam between the two.
 *
 * WHAT TO DO WHEN IT FAILS. Add the id to `STABLE_PROPERTY_IDS` in
 * `biz.ganttproject.core/.../CustomColumnsManager.kt`, and add the constant below.
 */
class StablePropertyIdsTest {

  @Test
  fun `jede eigene eigenschaft des forks ist als stabil erklaert`() {
    forkPropertyIds.forEach { (constant, id) ->
      assertTrue(isStablePropertyId(id),
        "$constant = \"$id\" ist nicht in STABLE_PROPERTY_IDS erklaert -- ein Import aus einer " +
          "anderssprachigen Datei haengt diese Spalte still ab")
    }
  }

  /**
   * The counter-control: without it the test above would also pass against a predicate that simply
   * says yes to everything, and that predicate is exactly the broken one -- it would merge two
   * unrelated columns that happen to share an id.
   */
  @Test
  fun `eine fremde kennung ist nicht als stabil erklaert`() {
    assertFalse(isStablePropertyId("tpc0"), "erzeugte Kennungen sind nie stabil")
    assertFalse(isStablePropertyId("col1"), "eine Kennung aus einer fremden Datei ist nicht stabil")
    assertFalse(isStablePropertyId(""), "die leere Kennung ist nicht stabil")
  }
}

/**
 * The fixed ids of this fork, by the name of the constant that holds them. Deliberately written out
 * rather than found by reflection: a reflective sweep would quietly cover nothing at all the day a
 * package gets renamed, and pass while doing so.
 */
private val forkPropertyIds: List<Pair<String, String>> = listOf(
  "EffortDrivenProperties.TASK_EFFORT_HOURS" to EffortDrivenProperties.TASK_EFFORT_HOURS,
  "EffortDrivenProperties.TASK_EFFORT_ACTUAL_HOURS" to EffortDrivenProperties.TASK_EFFORT_ACTUAL_HOURS,
  "EffortDrivenProperties.RESOURCE_HOURS_PER_DAY" to EffortDrivenProperties.RESOURCE_HOURS_PER_DAY,
  "EffortDrivenProperties.RESOURCE_HOURS_SCHEDULE" to EffortDrivenProperties.RESOURCE_HOURS_SCHEDULE,
  "TASK_EFFORT_ORIGINAL" to TASK_EFFORT_ORIGINAL,
  "RESOURCE_UTILISATION" to RESOURCE_UTILISATION,
  "RESOURCE_WORK_WEEK" to RESOURCE_WORK_WEEK,
  "RESOURCE_HOME_OFFICE_WEEK" to RESOURCE_HOME_OFFICE_WEEK,
  "RESOURCE_HOME_OFFICE_PERIODS" to RESOURCE_HOME_OFFICE_PERIODS,
  "TASK_ON_SITE_ONLY" to TASK_ON_SITE_ONLY,
  "TASK_WAIT_ONLY" to TASK_WAIT_ONLY,
  "TASK_DATE_FIXED" to TASK_DATE_FIXED,
  "TASK_DEADLINE" to TASK_DEADLINE,
  "TASK_RECURRENCE" to TASK_RECURRENCE,
  "TASK_RECURRENCE_OF" to TASK_RECURRENCE_OF,
  "PROJECT_ALLOW_HOLIDAY_WORK" to PROJECT_ALLOW_HOLIDAY_WORK,
)
