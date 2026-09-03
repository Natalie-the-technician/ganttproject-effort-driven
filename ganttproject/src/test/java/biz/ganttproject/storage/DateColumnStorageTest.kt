/*
 * Copyright 2026 BarD Software s.r.o.
 *
 * This file is part of GanttProject, an opensource project management tool.
 *
 * GanttProject is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 * GanttProject is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with GanttProject.  If not, see <http://www.gnu.org/licenses/>.
 */
package biz.ganttproject.storage

import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.core.time.GanttCalendar
import biz.ganttproject.customproperty.CustomPropertyClass
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.storage.ProjectDatabase
import net.sourceforge.ganttproject.storage.SQL_PROJECT_DATABASE_OPTIONS
import net.sourceforge.ganttproject.storage.SqlProjectDatabaseImpl
import net.sourceforge.ganttproject.storage.rebuildTaskDataTable
import net.sourceforge.ganttproject.task.TaskManager
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import javax.sql.DataSource

/**
 * Tests a custom property of class DATE which carries a value, all the way through the real H2
 * database. The unit tests in [CustomPropertyUtilsTest] cover the value mapping in isolation;
 * this test covers the same ground through an actual INSERT, because it is the jOOQ binding of
 * the column which used to reject the value.
 *
 * A mock database would make this test green and worthless.
 */
class DateColumnStorageTest {
  private lateinit var dataSource: DataSource
  private lateinit var projectDatabase: ProjectDatabase
  private lateinit var taskManager: TaskManager

  @BeforeEach
  fun init() {
    dataSource = JdbcDataSource().also {
      it.setURL("jdbc:h2:mem:datecolumn$SQL_PROJECT_DATABASE_OPTIONS")
    }
    projectDatabase = SqlProjectDatabaseImpl(dataSource).also { it.init() }
    taskManager = TestSetupHelper.newTaskManagerBuilder().also {
      it.setTaskUpdateBuilderFactory { task -> projectDatabase.createTaskUpdateBuilder(task) }
    }.build()
  }

  @AfterEach
  fun clear() {
    dataSource.connection.use { it.createStatement().execute("shutdown") }
  }

  @Test
  fun `a value of a date property reaches the task table`() {
    val def = taskManager.customPropertyManager.createDefinition(CustomPropertyClass.DATE, "deadline")
    // The column has to exist before the task is inserted, otherwise the INSERT fails with
    // "Column not found" rather than on the value binding which is what this test is about.
    rebuildTaskDataTable(dataSource, taskManager.customPropertyManager)

    val task = taskManager.newTaskBuilder()
      .withUid("uid1")
      .withId(1)
      .withName("with deadline")
      .withStartDate(TestSetupHelper.newMonday().time)
      .build()
    // The month argument of createGanttCalendar is zero-based, so this is 2026-08-31.
    task.customValues.setValue(def, CalendarFactory.createGanttCalendar(2026, 7, 31))

    projectDatabase.insertTask(task)

    val stored = dataSource.connection.use { conn ->
      conn.createStatement().executeQuery("""SELECT "${def.id}" FROM Task WHERE uid='uid1'""").let {
        if (it.next()) it.getString(1) else null
      }
    }
    // The day must survive the round trip unchanged. GanttProject replaces the default time zone
    // at startup, and java.time does not see that replacement, so a conversion which goes through
    // toInstant() shifts the date by a day. The conversion has to read the calendar fields
    // YEAR, MONTH and DAY_OF_MONTH instead.
    assertEquals("2026-08-31", stored)
  }

  @Test
  fun `a date property value survives the round trip through the model`() {
    val def = taskManager.customPropertyManager.createDefinition(CustomPropertyClass.DATE, "deadline")
    val task = taskManager.newTaskBuilder().withUid("uid2").withId(2).withName("t")
      .withStartDate(TestSetupHelper.newMonday().time)
      .build()
    task.customValues.setValue(def, CalendarFactory.createGanttCalendar(2026, 11, 31))

    assertEquals(LocalDate.of(2026, 12, 31), (task.customValues.getValue(def) as GanttCalendar).toLocalDate())
  }
}
