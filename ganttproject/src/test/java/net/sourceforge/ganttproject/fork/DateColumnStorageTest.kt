/*
Copyright 2026

NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.

This file is part of GanttProject, an opensource project management tool.
Licensed under the GNU General Public License, version 3 or later.
*/
package net.sourceforge.ganttproject.fork

import biz.ganttproject.core.time.CalendarFactory
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.storage.ProjectDatabase
import net.sourceforge.ganttproject.storage.SQL_PROJECT_DATABASE_OPTIONS
import net.sourceforge.ganttproject.storage.SqlProjectDatabaseImpl
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * Eine Spalte vom Typ DATUM mit einem Wert -- und die Spiegel-Datenbank.
 *
 * AM BILDSCHIRM GEFUNDEN, 17.08.2026: sobald die Spalte "Fertig bis" ihren ersten Wert bekam,
 * meldete das Programm "Type class java.util.GregorianCalendar is not supported in dialect
 * DEFAULT". jOOQ kennt GregorianCalendar nicht -- und damit war JEDE Datums-Spalte mit einem Wert
 * unbrauchbar, auch eine von Hand angelegte. Der Fehler steckt im Original, ist aber erst
 * aufgefallen, seit dieser Fork eine Datums-Spalte anlegt.
 *
 * Der Test geht ueber die ECHTE H2-Datenbank. Mit einer Attrappe waere er gruen und wertlos.
 */
class DateColumnStorageTest {

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

  private lateinit var dataSource: JdbcDataSource
  private lateinit var db: ProjectDatabase

  @BeforeEach
  fun init(testInfo: TestInfo) {
    dataSource = JdbcDataSource().also {
      it.setURL("jdbc:h2:mem:datum${testInfo.displayName.hashCode()}$SQL_PROJECT_DATABASE_OPTIONS")
    }
    db = SqlProjectDatabaseImpl(dataSource).also { it.init() }
  }

  @AfterEach
  fun clear() {
    dataSource.connection.use { it.createStatement().execute("shutdown") }
  }

  @Test
  fun `ein datumswert erreicht die datenbank`() {
    val taskManager = TestSetupHelper.newTaskManagerBuilder().also {
      it.setTaskUpdateBuilderFactory { task -> db.createTaskUpdateBuilder(task) }
    }.build()
    val props = taskManager.customPropertyManager
    // ERST die Spalte anlegen, DANN die Datenbank abgleichen -- in dieser Reihenfolge. Mein
    // erster Anlauf legte eine andere Spalte an und glich ab, bevor setDeadline die richtige
    // erzeugte: "Column deadline not found". Dieselbe Falle wie in Sitzung 3.
    findOrCreateDeadline(props)
    db.onCustomColumnChange(props)

    val task = taskManager.newTaskBuilder().withName("mit Frist").build()
    task.setDeadline(props, LocalDate.of(2026, 8, 31))
    // Das ist der Weg, auf dem der Fehler auftrat: der Vorgang wandert in die Spiegeltabelle.
    db.insertTask(task)

    // Gelesen wird ueber dieselbe Spalte -- kommt der Wert wieder heraus, hat das Schreiben
    // funktioniert.
    val gelesen = dataSource.connection.use { conn ->
      conn.createStatement().executeQuery("""SELECT "deadline" FROM Task WHERE name='mit Frist'""")
        .let { rs -> if (rs.next()) rs.getString(1) else null }
    }
    assertEquals("2026-08-31", gelesen?.take(10),
      "der Datumswert muss in der Spiegeltabelle stehen")
  }

  @Test
  fun `das modell liest die frist unveraendert zurueck`() {
    // Gegenprobe auf der Modellseite: der Umweg ueber die Datenbank darf den Wert nicht
    // verschieben -- die Zeitzonenfalle aus LegacyDates.kt lauert genau hier.
    val taskManager = TestSetupHelper.newTaskManagerBuilder().build()
    val props = taskManager.customPropertyManager
    val task = taskManager.newTaskBuilder().withName("t").build()
    task.setDeadline(props, LocalDate.of(2026, 12, 31))
    assertEquals(LocalDate.of(2026, 12, 31), task.deadlineDate(props))
  }
}
