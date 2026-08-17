/*
Copyright 2026

NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.

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
 * Was das SCHREIBEN der verteilten Termine im echten Modell anrichtet.
 *
 * WOZU DIESE DATEI EXISTIERT: die Verteilung selbst ist in `ResourceLevellingTest` geprueft, und
 * sie rechnete richtig. Trotzdem stand hinterher im Plan bei vier Vorgaengen eine
 * KUERZERE Dauer als vorher -- 11 Tage wurden 8, 26 wurden 16. Am Aufwand gemessen fehlten damit
 * 24 bzw. 80 Stunden Arbeit, die der Plan vorher kannte. Keine Rechnung findet das: der Fehler
 * entsteht erst im Zusammenspiel von Mutator, Planer und Kalender.
 *
 * Genau dieses Zusammenspiel wird hier nachgebaut -- mit dem echten TaskManager und einem echten
 * Kalender mit Feiertagen, aber ohne laufendes Programm.
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

  /** Ein Rueckgaengig-Verwalter, der nur ausfuehrt. Mehr braucht die Messung nicht. */
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

  /** Kalender mit dem Feiertagsblock aus dem Plan: 4. bis 7. November 2026. */
  private fun calendarWithHolidayBlock() = WeekendCalendarImpl().also { cal ->
    cal.publicHolidays = listOf(4, 5, 6, 7).map {
      CalendarEvent.newEvent(CalendarFactory.createGanttCalendar(2026, 10, it).time, false,
        CalendarEvent.Type.HOLIDAY, "Betriebsurlaub", null)
    }
  }

  // toModelDate, nicht java.time: die Zeitzonenfalle aus LegacyDates.kt gilt auch hier. Mit
  // java.time gelesen kam der Start dieses Tests einen Tag zu frueh heraus (2026-10-22).
  private fun LocalDate.toLegacy(): Date = this.toModelDate()

  private fun TaskManager.newTask(name: String, start: LocalDate, days: Int): Task =
    this.newTaskBuilder().withName(name).withStartDate(start.toLegacy())
      .withDuration(this.createLength(days.toLong())).build()

  private val Task.startDate: LocalDate get() = this.start.time.toModelLocalDate()

  /**
   * DER FALL AUS NATALIES PLAN, Vorgang 102: 11 Tage Dauer, verschoben auf einen Termin, von dem
   * aus die Spanne in einen Feiertagsblock hineinreicht.
   *
   * Die Dauer darf sich dabei NICHT aendern. Die Verteilung verschiebt Termine; sie darf keine
   * Arbeit wegnehmen.
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
   * Gegenprobe ohne Feiertage: derselbe Ablauf muss dort ebenfalls die Dauer erhalten. Ohne diese
   * Probe waere nicht zu erkennen, ob der obere Test die Feiertage trifft oder das Verschieben
   * ueberhaupt.
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
 * Die Umrechnung aus [LegacyDates] gegen die des Originals gestellt.
 *
 * WOZU: der Fork rechnet Termine selbst um, statt `DateParser` zu benutzen (Begruendung dort).
 * Damit muss aber bewiesen sein, dass beide Wege dasselbe Ergebnis liefern -- sonst waere
 * derselbe Tag im Fork ein anderer als im Original.
 */
class LegacyDatesTest {
  @Test
  fun `umrechnung stimmt mit der des originals ueberein`() {
    // Ausdruecklich MIT der Zeitzonen-Eigenart des laufenden Programms: erst die Sprache setzen.
    net.sourceforge.ganttproject.language.GanttLanguage.getInstance().locale = Locale.GERMANY
    // Sommerzeit, Winterzeit, Jahreswechsel, Schalttag und die Umstellungstage selbst.
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
