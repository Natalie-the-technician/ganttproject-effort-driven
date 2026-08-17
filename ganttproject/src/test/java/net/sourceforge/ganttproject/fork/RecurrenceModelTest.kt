/*
Copyright 2026 Noctuvo

NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.

This file is part of GanttProject, an opensource project management tool.
Licensed under the GNU General Public License, version 3 or later.
*/
package net.sourceforge.ganttproject.fork

import biz.ganttproject.core.calendar.WeekendCalendarImpl
import biz.ganttproject.core.time.CalendarFactory
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.storage.ProjectDatabase
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import net.sourceforge.ganttproject.undo.GPUndoListener
import net.sourceforge.ganttproject.undo.GPUndoManager
import net.sourceforge.ganttproject.undo.UndoableEditTxnFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import net.sourceforge.ganttproject.storage.SQL_PROJECT_DATABASE_OPTIONS
import net.sourceforge.ganttproject.storage.SqlProjectDatabaseImpl
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInfo
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * Serienvorgaenge im ECHTEN Projektmodell.
 *
 * WOZU NEBEN [RecurrenceTest]: die Terminrechnung ist dort geprueft. Was hier geprueft wird, ist
 * das, was in dieser Sitzung schon zweimal schiefging und keine reine Rechnung finden kann -- das
 * Zusammenspiel mit Mutator, Planer, Kalender und Eigenschaftsverwaltung. Insbesondere die
 * Zusage "ein zweiter Aufruf legt nichts doppelt an": genau die entscheidet, ob man das
 * Hilfsmittel mehr als einmal benutzen kann.
 */
class RecurrenceModelTest {

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

  // Eine ECHTE Datenbank, keine Attrappe: `onCustomColumnChange` ist genau die Stelle, an der in
  // Sitzung 3 das Schreiben scheiterte ("Column effort_hours not found"). Eine Attrappe haette
  // das nicht gefunden. Jeder Test bekommt seine eigene, sonst ueberlebt eine Spalte aus dem
  // vorigen Test und die Pruefung ist wertlos.
  private lateinit var dataSource: JdbcDataSource
  private lateinit var db: ProjectDatabase

  @BeforeEach
  fun init(testInfo: TestInfo) {
    dataSource = JdbcDataSource().also {
      it.setURL("jdbc:h2:mem:serie${testInfo.displayName.hashCode()}$SQL_PROJECT_DATABASE_OPTIONS")
    }
    db = SqlProjectDatabaseImpl(dataSource).also { it.init() }
  }

  @AfterEach
  fun clear() {
    dataSource.connection.use { it.createStatement().execute("shutdown") }
  }

  private fun taskManager(): TaskManager =
    TestSetupHelper.newTaskManagerBuilder().withCalendar(WeekendCalendarImpl()).build()

  private fun TaskManager.task(name: String, start: LocalDate, days: Int): Task =
    this.newTaskBuilder().withName(name).withStartDate(start.toModelDate())
      .withDuration(this.createLength(days.toLong())).build()

  private fun Task.setRecurrence(manager: biz.ganttproject.customproperty.CustomPropertyManager,
                                 text: String) {
    this.customValues.setValue(findOrCreateRecurrence(manager), text)
  }

  private val montag = LocalDate.of(2026, 8, 17)

  @Test
  fun `aus einem vorgang mit wiederholung werden viele`() {
    val tm = taskManager()
    val props = tm.customPropertyManager
    val quelle = tm.task("Umsatzsteuervoranmeldung", montag, 1)
    quelle.setRecurrence(props, "monatlich; Anzahl 4")

    val plan = planRecurrences(tm, props)
    assertEquals(1, plan.seriesCount)
    assertEquals(3, plan.occurrences.size, "der erste Termin ist der Vorgang selbst")

    val angelegt = applyRecurrencesAsSingleEdit(plan, tm, props, db, RunOnlyUndoManager(), "Test")
    assertEquals(3, angelegt)
    assertEquals(4, tm.tasks.count { it.name == "Umsatzsteuervoranmeldung" })
  }

  @Test
  fun `ein zweiter aufruf legt nichts doppelt an`() {
    // DIE ENTSCHEIDENDE ZUSAGE. Ein Hilfsmittel, das beim zweiten Klick alles verdoppelt,
    // benutzt man genau einmal -- und traut sich danach nie wieder.
    val tm = taskManager()
    val props = tm.customPropertyManager
    tm.task("Bericht", montag, 1).setRecurrence(props, "woechentlich; Anzahl 5")

    applyRecurrencesAsSingleEdit(planRecurrences(tm, props), tm, props, db,
      RunOnlyUndoManager(), "Test")
    val nachDemErsten = tm.tasks.size

    val zweiterPlan = planRecurrences(tm, props)
    assertEquals(0, zweiterPlan.occurrences.size, "beim zweiten Mal ist nichts mehr zu tun")
    applyRecurrencesAsSingleEdit(zweiterPlan, tm, props, db, RunOnlyUndoManager(), "Test")
    assertEquals(nachDemErsten, tm.tasks.size)
  }

  @Test
  fun `eine erweiterte serie legt nur die fehlenden termine nach`() {
    val tm = taskManager()
    val props = tm.customPropertyManager
    val quelle = tm.task("Bericht", montag, 1)
    quelle.setRecurrence(props, "woechentlich; Anzahl 3")
    applyRecurrencesAsSingleEdit(planRecurrences(tm, props), tm, props, db,
      RunOnlyUndoManager(), "Test")
    assertEquals(3, tm.tasks.count { it.name == "Bericht" })

    // Aus 3 werden 6: es duerfen genau die drei fehlenden dazukommen.
    quelle.setRecurrence(props, "woechentlich; Anzahl 6")
    val plan = planRecurrences(tm, props)
    assertEquals(3, plan.occurrences.size)
    applyRecurrencesAsSingleEdit(plan, tm, props, db, RunOnlyUndoManager(), "Test")
    assertEquals(6, tm.tasks.count { it.name == "Bericht" })
  }

  @Test
  fun `wiederholungen erben dauer, aufwand und zuordnung`() {
    val tm = taskManager()
    val props = tm.customPropertyManager
    val quelle = tm.task("Abschluss", montag, 3)
    val effortDef = EffortDrivenProperties.findOrCreateTaskEffort(props)
    quelle.customValues.setValue(effortDef, 24.0)
    quelle.setRecurrence(props, "monatlich; Anzahl 2")

    applyRecurrencesAsSingleEdit(planRecurrences(tm, props), tm, props, db,
      RunOnlyUndoManager(), "Test")

    val kopie = tm.tasks.first { it.recurrenceOf(props) != null }
    assertEquals(3, kopie.duration.length, "die Dauer des Ausgangsvorgangs")
    assertEquals(24.0, kopie.customValues.getValue(effortDef), "und sein Aufwand")
    assertNotNull(kopie.recurrenceOf(props))
  }

  @Test
  fun `die wiederholung traegt selbst keine regel`() {
    // Sonst erzeugte der naechste Lauf Wiederholungen von Wiederholungen -- und der uebernaechste
    // Wiederholungen davon.
    val tm = taskManager()
    val props = tm.customPropertyManager
    tm.task("Bericht", montag, 1).setRecurrence(props, "woechentlich; Anzahl 3")
    applyRecurrencesAsSingleEdit(planRecurrences(tm, props), tm, props, db,
      RunOnlyUndoManager(), "Test")

    val kopien = tm.tasks.filter { it.recurrenceOf(props) != null }
    assertEquals(2, kopien.size)
    kopien.forEach { assertEquals(null, it.recurrenceText(props)) }
  }

  @Test
  fun `der ausgangsvorgang wird nicht zur gruppe`() {
    // Wuerden die Wiederholungen UNTER den Ausgangsvorgang gehaengt, leitete er seine Termine aus
    // ihnen ab und verlore seine eigene Dauer.
    val tm = taskManager()
    val props = tm.customPropertyManager
    val quelle = tm.task("Bericht", montag, 2)
    quelle.setRecurrence(props, "woechentlich; Anzahl 3")
    applyRecurrencesAsSingleEdit(planRecurrences(tm, props), tm, props, db,
      RunOnlyUndoManager(), "Test")

    assertTrue(tm.taskHierarchy.getNestedTasks(quelle).isEmpty(), "keine Kinder")
    assertEquals(2, quelle.duration.length, "und die eigene Dauer bleibt")
    assertEquals(montag, quelle.start.time.toModelLocalDate(), "der Termin auch")
  }

  @Test
  fun `eine unlesbare regel legt gar nichts an`() {
    val tm = taskManager()
    val props = tm.customPropertyManager
    tm.task("Gut", montag, 1).setRecurrence(props, "woechentlich; Anzahl 3")
    tm.task("Kaputt", montag, 1).setRecurrence(props, "monatlich")  // ohne Begrenzung

    val plan = planRecurrences(tm, props)
    assertTrue(plan.hasErrors)
    assertEquals(listOf("Kaputt"), plan.errors.keys.toList())
    // Die Aktion legt bei Fehlern nichts an; hier wird nachgewiesen, dass der Fehler ueberhaupt
    // bis zum Aufrufer kommt, statt still uebersprungen zu werden.
    assertTrue(plan.occurrences.any { it.sourceName == "Gut" },
      "die lesbare Serie ist geplant -- die Aktion fuehrt sie wegen des Fehlers trotzdem nicht aus")
  }

  @Test
  fun `ohne wiederholung passiert nichts`() {
    val tm = taskManager()
    val props = tm.customPropertyManager
    tm.task("Einmalig", montag, 1)
    val plan = planRecurrences(tm, props)
    assertEquals(0, plan.occurrences.size)
    assertTrue(plan.errors.isEmpty())
  }
}
