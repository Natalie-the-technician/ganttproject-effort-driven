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

import biz.ganttproject.core.calendar.WeekendCalendarImpl
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * A MEASUREMENT, NOT A REQUIREMENT: the levelling searches its window on the PROJECT calendar
 * while it computes the duration -- and writes the end back -- on the WORKING WEEK.
 *
 * `LevellingActions.kt:284` hands `levelTasks` one global `(LocalDate) -> Boolean`, built as
 * `workingDayTest(taskManager.calendar)`. Two things inside the same call do NOT use it:
 *
 *  * `durationAtStart` builds `WorkWeekWorkingDays` of its own and walks each task's effort on
 *    the grid of the people on THAT task;
 *  * `writeLevellingBack` turns the levelled duration into an end with `endAfterWorkingDays` on
 *    that same per-task grid.
 *
 * In between, `levelTasks` lays those days out and reserves the people's capacity with the global
 * test. For anybody whose week is Monday to Friday the three agree and there is nothing to see.
 * For anybody who works Saturdays they do not, and this file measures BY HOW MUCH.
 *
 * WHY IT IS NOT FIXED HERE. Supplying `levelTasks` per task means turning its parameter from
 * `(LocalDate) -> Boolean` into `(LevelTask, LocalDate) -> Boolean`, which cuts into the core of
 * the levelling -- `nextWorkingDay`, `workingDays`, `findEarliestWindow` and the conflict
 * reporting all read it. The report `2026-09-03-arbeitswoche-wirkung.md` names that as
 * deliberately not touched, and this package keeps to it. What this file adds is the number, so
 * that the decision is taken on a measurement instead of on a feeling.
 *
 * The dates: 7 September 2026 is a Monday, 12 September the Saturday of that week.
 */
class WorkWeekLevellingWindowTest {

  init {
    object : CalendarFactory() {
      init {
        setLocaleApi(object : CalendarFactory.LocaleApi {
          override fun getLocale(): Locale = Locale.GERMANY
          override fun getShortDateFormat(): DateFormat =
            DateFormat.getDateInstance(DateFormat.SHORT, Locale.GERMANY)
        })
      }
    }
  }

  private val montag = LocalDate.of(2026, 9, 7)

  private class Projekt {
    val builder: TestSetupHelper.TaskManagerBuilder =
      TestSetupHelper.newTaskManagerBuilder().withCalendar(WeekendCalendarImpl())
    val taskManager: TaskManager = builder.build()
    val resourceManager: HumanResourceManager = builder.resourceManager
    val resourceProperties: CustomPropertyManager get() = resourceManager.customPropertyManager
    val taskProperties: CustomPropertyManager get() = taskManager.customPropertyManager

    fun person(name: String, id: Int, woche: String): HumanResource =
      resourceManager.create(name, id).also {
        it.setValue(EffortDrivenProperties.findOrCreateResourceHours(resourceProperties), 8.0)
        it.setWorkWeek(resourceProperties, WorkWeekSchedule.parse(woche).schedule)
      }

    fun vorgang(name: String, start: LocalDate, stunden: Double, person: HumanResource): Task =
      taskManager.newTaskBuilder().withName(name).withStartDate(start.toModelDate())
        .withDuration(taskManager.createLength(1L)).build().also {
          it.customValues.setValue(
            EffortDrivenProperties.findOrCreateTaskEffort(taskProperties), stunden)
          it.assignmentCollection.addAssignment(person).load = 100f
        }

    /** Exactly the call `LevellingActions.weiter` makes, with its global working-day test. */
    fun nivellieren(ab: LocalDate): LevelResult = levelTasks(
      collectLevelTasks(taskManager, taskProperties, resourceProperties, ab, true),
      ab,
      workingDayTest(taskManager.calendar),
      durationAtStart(taskManager, taskProperties, resourceProperties),
      isAvailable = availabilityTest(resourceManager))
  }

  /**
   * THE NUMBER ITSELF: the same levelled duration gives two different ends, and the gap is the
   * measure of the contradiction.
   *
   * Natalie works Monday to Saturday. 48 hours at 8 a day is six of HER working days, which is
   * what `durationAtStart` answers and what the write-back lays out: Monday to Saturday, so the
   * task is over after the Saturday. The window search takes the same six days and lays them on
   * the project calendar, where the Saturday does not exist: Monday to Friday and then the next
   * Monday.
   *
   * The consequence in the plan is not an inaccuracy but a contradiction: for one and the same
   * task the levelling reserves Natalie's capacity on a Monday she does not work on it, and
   * leaves the Saturday she does work on it free for something else.
   */
  @Test
  fun `die fenstersuche rechnet auf dem projektkalender und die dauer auf der arbeitswoche`() {
    val projekt = Projekt()
    val natalie = projekt.person("Natalie", 1, woche = "1,2,3,4,5,6")
    val vorgang = projekt.vorgang("Block", montag, 48.0, natalie)

    val ergebnis = projekt.nivellieren(montag)
    val id = vorgang.taskID.toString()
    val dauer = ergebnis.durations[id] ?: -1
    assertEquals(6, dauer,
      "setup: 48 hours at 8 a day are six of Natalie's working days, Monday to Saturday")

    val gitterDerPerson =
      WorkWeekWorkingDays(projekt.taskManager.calendar, projekt.resourceProperties)
        .forTask(vorgang)
    val projektGitter = workingDayTest(projekt.taskManager.calendar)
    val start = ergebnis.starts[id] ?: montag

    assertEquals(LocalDate.of(2026, 9, 14), endAfterWorkingDays(start, dauer, gitterDerPerson),
      "THE WRITE-BACK grid, the one `writeLevellingBack` uses: six of her days are Monday to "
        + "Saturday, and the end is the next day she works")
    assertEquals(LocalDate.of(2026, 9, 15), endAfterWorkingDays(start, dauer, projektGitter),
      "THE WINDOW-SEARCH grid, the one `levelTasks` uses: the same six days on the project "
        + "calendar reach into the following week")
  }

  /**
   * THE CONTRADICTION AS THE NEXT TASK SEES IT, and this one goes THROUGH `levelTasks` rather
   * than mirroring its arithmetic -- the check above computes the two ends itself and could
   * therefore agree with a `levelTasks` that had long stopped doing this.
   *
   * A second task for the same person has to wait for the first. Where it lands says which grid
   * the occupancy was booked on: on Natalie's own grid the first task is over after Saturday the
   * 12th and the second may start on Monday the 14th; on the project calendar the first task
   * still holds Monday the 14th and the second is pushed to Tuesday the 15th.
   *
   * The measured answer is the second one, and the day it costs is the number this file exists
   * for.
   */
  @Test
  fun `der naechste vorgang wird um den unterschied verschoben`() {
    val projekt = Projekt()
    val natalie = projekt.person("Natalie", 1, woche = "1,2,3,4,5,6")
    val erster = projekt.vorgang("Block", montag, 48.0, natalie)
    val zweiter = projekt.vorgang("Danach", montag, 8.0, natalie)

    val ergebnis = projekt.nivellieren(montag)

    assertEquals(montag, ergebnis.starts[erster.taskID.toString()],
      "setup: the first task keeps the Monday")
    assertEquals(LocalDate.of(2026, 9, 15), ergebnis.starts[zweiter.taskID.toString()],
      "MEASURED, not wanted: the window search booked Natalie's capacity for the first task on "
        + "the project calendar, which reaches to Monday the 14th, so the second task is pushed "
        + "to Tuesday the 15th. On the grid the duration was computed with, the first task is "
        + "over after Saturday the 12th and the second could begin on Monday the 14th. The "
        + "contradiction costs one day here; how much it costs in a real plan depends on how many "
        + "Saturdays a task runs over.")
  }
}
