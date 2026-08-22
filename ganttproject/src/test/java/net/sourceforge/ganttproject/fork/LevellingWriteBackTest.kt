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

import biz.ganttproject.core.calendar.CalendarEvent
import biz.ganttproject.core.calendar.WeekendCalendarImpl
import biz.ganttproject.core.time.CalendarFactory
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.undo.GPUndoListener
import net.sourceforge.ganttproject.undo.GPUndoManager
import net.sourceforge.ganttproject.undo.UndoableEditTxnFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/**
 * What WRITING the levelled dates does in the real model.
 *
 * WHY THIS FILE EXISTS: levelling itself is checked in `ResourceLevellingTest`, and it computed
 * correctly. Nevertheless four Tasks afterwards carried a SHORTER duration in the plan than
 * before -- 11 days became 8, 26 became 16. Measured against the effort, 24 and 80 hours of work
 * respectively were missing that the plan had known before. No calculation finds that: the bug
 * only arises in the interplay of mutator, scheduler and calendar.
 *
 * That interplay is exactly what is rebuilt here -- with the real TaskManager and a real calendar
 * with holidays, but without a running program.
 */
class LevellingWriteBackTest {

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

  /** An undo manager that only executes. The measurement needs no more than that. */
  private class RunOnlyUndoManager : GPUndoManager {
    override fun undoableEdit(localizedName: String, runnableEdit: Runnable) = runnableEdit.run()
    override fun canUndo() = false
    override fun canRedo() = false
    override fun undo() = Unit
    override fun redo() = Unit
    override val undoPresentationName = ""
    override val redoPresentationName = ""
    override fun addUndoableEditListener(listener: GPUndoListener) = Unit
    override fun removeUndoableEditListener(listener: GPUndoListener) = Unit
    override fun die() = Unit
    override fun addUndoableEditTxnFactory(factory: UndoableEditTxnFactory) = Unit
  }

  /** Calendar with the block of holidays from the plan: 4 to 7 November 2026. */
  private fun calendarWithHolidayBlock() = WeekendCalendarImpl().also { cal ->
    cal.publicHolidays = listOf(4, 5, 6, 7).map {
      CalendarEvent.newEvent(CalendarFactory.createGanttCalendar(2026, 10, it).time, false,
        CalendarEvent.Type.HOLIDAY, "Betriebsurlaub", null)
    }
  }

  // toModelDate, not java.time: the time zone trap from LegacyDates.kt applies here too. Read
  // with java.time, the start of this test came out one day too early (2026-10-22).
  private fun LocalDate.toLegacy(): Date = this.toModelDate()

  private fun TaskManager.newTask(name: String, start: LocalDate, days: Int): Task =
    this.newTaskBuilder().withName(name).withStartDate(start.toLegacy())
      .withDuration(this.createLength(days.toLong())).build()

  private val Task.startDate: LocalDate get() = this.start.time.toModelLocalDate()

  /**
   * THE CASE FROM THE PLAN THIS FORK WAS DEVELOPED AGAINST, Task 102: 11 days duration, moved to
   * a date from which the span reaches into a block of holidays.
   *
   * The duration must NOT change in the process. Levelling moves dates; it must not take work
   * away.
   */
  @Test
  fun `verschieben ueber einen feiertagsblock laesst die dauer unveraendert`() {
    val taskManager = TestSetupHelper.newTaskManagerBuilder()
      .withCalendar(calendarWithHolidayBlock()).build()
    val task = taskManager.newTask("ELSTER", LocalDate.of(2026, 11, 11), 11)
    assertEquals(11, task.duration.length, "Aufbau: die Dauer vor dem Verschieben")

    val neuerStart = LocalDate.of(2026, 10, 23)
    val geschrieben = applyLevellingAsSingleEdit(
      mapOf(task.taskID.toString() to neuerStart), taskManager, RunOnlyUndoManager(), "Test")

    assertEquals(1, geschrieben, "genau ein Vorgang war zu verschieben")
    assertEquals(neuerStart, task.startDate, "der Start soll auf dem verteilten Termin liegen")
    assertEquals(11, task.duration.length,
      "die Dauer muss 11 bleiben -- gemessen wurden im Plan 8, drei Arbeitstage weniger")
  }

  /**
   * Counter-check without holidays: the same sequence has to preserve the duration there as
   * well. Without this check there would be no way to tell whether the test above hits the
   * holidays or the moving as such.
   */
  @Test
  fun `verschieben ohne feiertage laesst die dauer unveraendert`() {
    val taskManager = TestSetupHelper.newTaskManagerBuilder()
      .withCalendar(WeekendCalendarImpl()).build()
    val task = taskManager.newTask("ohne Feiertage", LocalDate.of(2026, 11, 11), 11)
    applyLevellingAsSingleEdit(
      mapOf(task.taskID.toString() to LocalDate.of(2026, 10, 23)), taskManager,
      RunOnlyUndoManager(), "Test")
    assertEquals(LocalDate.of(2026, 10, 23), task.startDate)
    assertEquals(11, task.duration.length)
  }
}

/**
 * The conversion from [LegacyDates] set against the original's.
 *
 * WHAT FOR: the fork converts dates itself instead of using `DateParser` (reasoning there). That
 * makes it necessary to prove that both ways deliver the same result -- otherwise the same day
 * would be a different one in the fork than in the original.
 */
class LegacyDatesTest {
  @Test
  fun `umrechnung stimmt mit der des originals ueberein`() {
    // Explicitly WITH the time zone quirk of the running program: set the language first.
    net.sourceforge.ganttproject.language.GanttLanguage.getInstance().locale = Locale.GERMANY
    // Summer time, winter time, turn of the year, leap day and the changeover days themselves.
    val tage = listOf(
      LocalDate.of(2026, 8, 17), LocalDate.of(2026, 11, 4), LocalDate.of(2026, 12, 31),
      LocalDate.of(2027, 1, 1), LocalDate.of(2028, 2, 29),
      LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25))
    tage.forEach { tag ->
      assertEquals(org.w3c.util.DateParser.toJavaDate(tag), tag.toModelDate(),
        "Hinweg fuer $tag")
      assertEquals(tag, tag.toModelDate().toModelLocalDate(), "Hin und zurueck fuer $tag")
      assertEquals(org.w3c.util.DateParser.toLocalDate(tag.toModelDate()),
        tag.toModelDate().toModelLocalDate(), "Rueckweg fuer $tag")
    }
  }
}
