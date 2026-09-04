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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * [fork change] ONE DAY GRID PER TASK, FOR ALL THREE OF THE THINGS THAT HAVE TO AGREE.
 *
 * WHAT THIS FILE USED TO BE. Until 4 September 2026 it was a MEASUREMENT and not a requirement: it
 * wrote down that the levelling searched its window on the PROJECT calendar while it computed the
 * duration -- and wrote the end back -- on the WORKING WEEK, and it held the two different numbers
 * that came out. `LevellingActions` handed `levelTasks` one global `(LocalDate) -> Boolean`, built
 * as `workingDayTest(taskManager.calendar)`, while `durationAtStart` and `writeLevellingBack` both
 * asked [WorkWeekWorkingDays] per task. For anybody working Monday to Friday the three agreed and
 * there was nothing to see; for anybody working Saturdays the levelling booked their capacity on a
 * Monday they did not work on it and left the Saturday free that they did.
 *
 * WHAT IT IS NOW: the requirement that the two numbers be ONE number. The tests below no longer
 * write down two ends; they compute the end on the task's OWN grid and demand that the placement
 * `levelTasks` produces agrees with it. A written-out date stands beside it so that a grid which is
 * wrong on BOTH sides cannot make them agree by being equally wrong.
 *
 * THE SECOND TEST IS THE ONE ABOUT THE CACHE. Two people with DIFFERENT working weeks, on
 * different tasks, in ONE run: each one's capacity has to be booked on their own grid. Making the
 * test per task means remembering answers per task, and a remembered answer keyed by the day alone
 * -- or a grid handed to the wrong task -- is a mistake that no plan with a single working week can
 * show. `WorkWeekLevellingGuardTest` says the same thing from its side: what it cannot see is
 * exactly this, and this is where it is checked.
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

    /** Exactly the call `LevellingActions.weiter` makes, with its working-day test. */
    fun nivellieren(ab: LocalDate): LevelResult = levelTasks(
      collectLevelTasks(taskManager, taskProperties, resourceProperties, ab, true),
      ab,
      workingDaysPerTask(taskManager, resourceProperties),
      durationAtStart(taskManager, taskProperties, resourceProperties),
      isAvailable = availabilityTest(resourceManager))

    /** The grid the write-back uses for this task, and the one the duration was computed on. */
    fun rasterVon(vorgang: Task): (LocalDate) -> Boolean =
      WorkWeekWorkingDays(taskManager.calendar, resourceProperties).forTask(vorgang)
  }

  /**
   * THE CONTRADICTION, RESOLVED, and measured THROUGH `levelTasks` rather than beside it.
   *
   * Natalie works Monday to Saturday. 48 hours at 8 a day is six of HER working days, which is
   * what `durationAtStart` answers and what the write-back lays out: Monday to Saturday, so the
   * task is over after the Saturday and the next of her days is the following Monday.
   *
   * A second task of hers, with no dependency at all, can therefore begin only where her capacity
   * is free again -- and WHERE THAT IS is the whole question. It is the only way the booking is
   * visible from outside: `LevelResult` reports starts, durations and conflicts, not the occupancy
   * map. So the second task's start IS the end of the first one's booking, read out of the
   * levelling itself.
   *
   * ONE NUMBER, DEMANDED TWICE. The end is computed once, on the task's own grid, with the very
   * function the write-back uses. Then the levelling has to agree with it. Before this package the
   * two differed by a day -- the search laid Natalie's six days on the project calendar, where the
   * Saturday does not exist, and reached into the following Monday.
   *
   * THE WRITTEN-OUT DATE BESIDE IT IS NOT DECORATION. A grid that answered "every day is a working
   * day" would make both sides agree on a wrong date; the literal is what stops the check from
   * being satisfied by two equal mistakes.
   */
  @Test
  fun `die fenstersuche bucht auf demselben raster auf dem die dauer gerechnet wird`() {
    val projekt = Projekt()
    val natalie = projekt.person("Natalie", 1, woche = "1,2,3,4,5,6")
    val erster = projekt.vorgang("Block", montag, 48.0, natalie)
    val zweiter = projekt.vorgang("Danach", montag, 8.0, natalie)

    val ergebnis = projekt.nivellieren(montag)
    val id = erster.taskID.toString()
    assertEquals(6, ergebnis.durations[id],
      "setup: 48 hours at 8 a day are six of Natalie's working days, Monday to Saturday")
    assertEquals(montag, ergebnis.starts[id], "setup: the first task keeps the Monday")

    val endeAufIhremRaster =
      endAfterWorkingDays(montag, 6, projekt.rasterVon(erster))
    assertEquals(LocalDate.of(2026, 9, 14), endeAufIhremRaster,
      "setup: six of Natalie's days from Monday are Monday to Saturday, and the next day she "
        + "works is the following Monday. On the project calendar the same six days would reach "
        + "to Tuesday the 15th -- that was the contradiction.")

    assertEquals(endeAufIhremRaster, ergebnis.starts[zweiter.taskID.toString()],
      "THE ONE NUMBER: the levelling has to free Natalie's capacity on the day the write-back "
        + "writes as the end of the first task. If these differ, the window search books her on a "
        + "day she does not work on the task and leaves free the day she does.")
  }

  /**
   * TWO DIFFERENT WORKING WEEKS IN ONE RUN, and each person's capacity booked on their own grid.
   *
   * This is the check against a remembered answer with the wrong key. Making the working-day test
   * per task means remembering per task -- and the two mistakes that costs nothing to make are
   * remembering by the day ALONE, so that whoever asks second gets the first one's answer, and
   * handing a task somebody else's grid. Neither can show in a plan where all the grids are the
   * same object, which is every plan that has no working week in it at all.
   *
   * THE TWO PEOPLE DISAGREE ABOUT TWO SPECIFIC DAYS, and both of them are load-bearing:
   *
   *  * SATURDAY 12 September: Natalie works it, Otto does not, and neither does the project
   *    calendar. It is what makes Natalie's six days end after the Saturday.
   *  * WEDNESDAY 9 September: Otto does NOT work it, Natalie does, and so does the project
   *    calendar. It is what pushes Otto's third day out to the Thursday.
   *
   * NATALIE'S TASKS COME FIRST IN THE PLAN on purpose, so that hers are the answers a shared
   * memory would already hold when Otto asks. With one map for both, Otto is told the Wednesday is
   * his working day, his 24 hours end on the Wednesday, and his second task starts on the Thursday
   * -- one day early, and silently.
   */
  @Test
  fun `zwei verschiedene arbeitswochen im selben lauf werden nicht vertauscht`() {
    val projekt = Projekt()
    val natalie = projekt.person("Natalie", 1, woche = "1,2,3,4,5,6")
    // Monday, Tuesday, Thursday, Friday -- no Wednesday.
    val otto = projekt.person("Otto", 2, woche = "1,2,4,5")
    val natalieErst = projekt.vorgang("N1", montag, 48.0, natalie)
    val natalieDann = projekt.vorgang("N2", montag, 8.0, natalie)
    val ottoErst = projekt.vorgang("O1", montag, 24.0, otto)
    val ottoDann = projekt.vorgang("O2", montag, 8.0, otto)

    val ergebnis = projekt.nivellieren(montag)

    assertEquals(6, ergebnis.durations[natalieErst.taskID.toString()],
      "setup: 48 hours at 8 a day are six of Natalie's days")
    assertEquals(3, ergebnis.durations[ottoErst.taskID.toString()],
      "setup: 24 hours at 8 a day are three of Otto's days")

    assertEquals(LocalDate.of(2026, 9, 14),
      ergebnis.starts[natalieDann.taskID.toString()],
      "NATALIE'S GRID: her six days are Monday to Saturday, so her next task begins on the "
        + "following Monday. On the project calendar it would be Tuesday the 15th.")
    assertEquals(LocalDate.of(2026, 9, 11),
      ergebnis.starts[ottoDann.taskID.toString()],
      "OTTO'S GRID, and it is a DIFFERENT one: he does not work Wednesdays, so his three days are "
        + "Monday, Tuesday and Thursday and his next task begins on the Friday. On the project "
        + "calendar -- or on Natalie's answers -- it would be Thursday the 10th.")

    assertEquals(
      endAfterWorkingDays(montag, 6, projekt.rasterVon(natalieErst)),
      ergebnis.starts[natalieDann.taskID.toString()],
      "and each of the two agrees with the end the write-back would write for it (Natalie)")
    assertEquals(
      endAfterWorkingDays(montag, 3, projekt.rasterVon(ottoErst)),
      ergebnis.starts[ottoDann.taskID.toString()],
      "and each of the two agrees with the end the write-back would write for it (Otto)")
  }

  /**
   * THE BOUND STAYS INTACT WITH A GRID PER TASK.
   *
   * `findEarliestWindow` gives up after `MAX_SEARCH_DAYS` and lays the task at its earliest
   * possible date rather than running for ever; the caller turns that into a
   * `LevelConflict.NoPossibleDate`. That safeguard reads the working-day test on every one of
   * those days, so it is worth checking once THROUGH the real conversion that a per-task grid has
   * not turned it into a hang.
   *
   * The task here has a blocking person who is away for two centuries: no day satisfies the set,
   * the search runs into the bound, and what comes out is a report and a date -- not a hanging
   * program. The person also has a working week, so the grid the bound walks is a per-task one and
   * not the shared project-calendar object.
   */
  @Test
  fun `die schranke greift auch mit einem raster je vorgang`() {
    val projekt = Projekt()
    val natalie = projekt.person("Natalie", 1, woche = "1,2,3,4,5,6")
    val vorgang = projekt.vorgang("Nie", montag, 8.0, natalie)
    vorgang.assignmentCollection.assignments.first().isBlocking = true
    natalie.addDaysOff(biz.ganttproject.core.calendar.GanttDaysOff(
      montag.toModelDate(), montag.plusYears(200).toModelDate()))

    val ergebnis = projekt.nivellieren(montag)

    assertNotNull(ergebnis.starts[vorgang.taskID.toString()],
      "the levelling has to come back with a date instead of hanging")
    assertEquals(montag, ergebnis.starts[vorgang.taskID.toString()],
      "the fallback is the earliest possible date, exactly as it was before the grid became "
        + "per task")
    val ohneTermin = ergebnis.conflicts.filterIsInstance<LevelConflict.NoPossibleDate>()
    assertEquals(1, ohneTermin.size, "and the exhausted search is reported, not swallowed")
    assertTrue(ohneTermin.single().blocking.contains(natalie.id.toString()),
      "the report names the person whose absence the search kept bouncing off")
  }
}
