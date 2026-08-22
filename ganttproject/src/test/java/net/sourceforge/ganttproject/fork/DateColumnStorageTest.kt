/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.

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
 * A column of type DATE with a value -- and the mirror database.
 *
 * FOUND ON SCREEN, 17.08.2026: as soon as the "Finish by" column got its first value, the program
 * reported "Type class java.util.GregorianCalendar is not supported in dialect DEFAULT". jOOQ
 * does not know GregorianCalendar -- and with that EVERY date column holding a value was
 * unusable, including one created by hand. The bug is in the original but only came to notice
 * once this fork started creating a date column.
 *
 * The test goes through the REAL H2 database. With a mock it would be green and worthless.
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
    // Create the column FIRST, THEN reconcile the database -- in that order. The first attempt
    // created a different column and reconciled before setDeadline produced the right one:
    // "Column deadline not found". The same trap as in session 3.
    findOrCreateDeadline(props)
    db.onCustomColumnChange(props)

    val task = taskManager.newTaskBuilder().withName("mit Frist").build()
    task.setDeadline(props, LocalDate.of(2026, 8, 31))
    // This is the path on which the bug occurred: the Task travels into the mirror table.
    db.insertTask(task)

    // Reading goes through the same column -- if the value comes back out, the write worked.
    val gelesen = dataSource.connection.use { conn ->
      conn.createStatement().executeQuery("""SELECT "deadline" FROM Task WHERE name='mit Frist'""")
        .let { rs -> if (rs.next()) rs.getString(1) else null }
    }
    assertEquals("2026-08-31", gelesen?.take(10),
      "der Datumswert muss in der Spiegeltabelle stehen")
  }

  @Test
  fun `das modell liest die frist unveraendert zurueck`() {
    // Counter-check on the model side: the detour through the database must not shift the value
    // -- the time zone trap from LegacyDates.kt lurks exactly here.
    val taskManager = TestSetupHelper.newTaskManagerBuilder().build()
    val props = taskManager.customPropertyManager
    val task = taskManager.newTaskBuilder().withName("t").build()
    task.setDeadline(props, LocalDate.of(2026, 12, 31))
    assertEquals(LocalDate.of(2026, 12, 31), task.deadlineDate(props))
  }
}
