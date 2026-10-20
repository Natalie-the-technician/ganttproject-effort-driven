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

import biz.ganttproject.core.io.XmlProjectImporter
import net.sourceforge.ganttproject.io.GanttXMLSaver
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.customproperty.CustomPropertyManager
import biz.ganttproject.storage.reopenLastFileOption
import net.sourceforge.ganttproject.gui.CommandLineProjectOpenStrategy
import net.sourceforge.ganttproject.GanttProjectImpl
import net.sourceforge.ganttproject.document.DocumentManager
import net.sourceforge.ganttproject.gui.UIFacade
import net.sourceforge.ganttproject.gui.ProjectUIFacade
import org.easymock.EasyMock
import biz.ganttproject.customproperty.CustomColumnsException
import net.sourceforge.ganttproject.ensureForkColumns
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Test
import org.osgi.service.prefs.Preferences
import java.text.DateFormat
import java.util.Locale

/**
 * THE STARTUP PATH — the fork's columns have to be there without anybody taking *Project > New*.
 *
 * WHAT WAS MEASURED, 20.10.2026 on the running program: whoever starts the program and plans
 * straight away works WITHOUT all eleven fork columns. Way A (the empty startup document) gave
 * 0 of 11, way B (*Project > New*) 11 of 11, way C (opening a foreign .gan) 11 of 11. `FORK.md`
 * said so itself: "They are created in two places only: when a project file is opened, and by
 * *Project > New*."
 *
 * WHY THE CHECK HANGS ON `openStartupDocument` AND NOT ON `ensureCapacityColumns`: a check that
 * calls `ensureCapacityColumns` itself proves that the function works, never that the program
 * reaches it. That is exactly the gap this file closes, so it drives the real startup entry
 * point — `CommandLineProjectOpenStrategy.openStartupDocument`, which `AppBuilder.withDocument`
 * runs on every launch (`MainApplication.java` calls `withDocument(null)` when no file is named
 * on the command line).
 *
 * `reopenLastFileOption` is switched OFF here, and that is the case being measured: no file
 * argument, no last document — the plain start with the empty document. With the option ON and a
 * last document present, the open path runs instead, and that one resets both property managers
 * (`GanttProject.close()`) before reading, so it behaves like way C.
 */
class StartwegSpaltenTest {

  // Without this, `GanttProjectImpl()` already fails in its constructor, in
  // WeekendCalendarImpl -- "CalendarFactory.ourLocaleApi is null". The same block stands in the
  // other fork checks that build a whole project.
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

  /** The four columns of the person. Named one by one; the total is counted from the list. */
  private val resourceIds = listOf(
    "hours_per_day",        // EffortDrivenProperties.RESOURCE_HOURS_PER_DAY
    "hours_schedule",       // EffortDrivenProperties.RESOURCE_HOURS_SCHEDULE
    "utilisation_percent",  // RESOURCE_UTILISATION
    "work_week",            // RESOURCE_WORK_WEEK
  )

  /** The seven columns of the task. Named one by one; the total is counted from the list. */
  private val taskIds = listOf(
    "recurrence",             // TASK_RECURRENCE
    "recurrence_of",          // TASK_RECURRENCE_OF
    "deadline",               // TASK_DEADLINE
    "wait_only",              // TASK_WAIT_ONLY
    "on_site_only",           // TASK_ON_SITE_ONLY
    "date_fixed",             // TASK_DATE_FIXED
    "effort_original_hours",  // TASK_EFFORT_ORIGINAL
  )

  private fun idsOf(manager: CustomPropertyManager): Set<String> =
    manager.definitions.map { it.id }.toSet()

  private fun startUpWithoutDocument(): GanttProjectImpl {
    val project = GanttProjectImpl()
    val strategy = CommandLineProjectOpenStrategy(
      project,
      EasyMock.createNiceMock(DocumentManager::class.java),
      project.taskManager,
      EasyMock.createNiceMock(UIFacade::class.java),
      EasyMock.createNiceMock(ProjectUIFacade::class.java),
      EasyMock.createNiceMock(Preferences::class.java),
    )
    strategy.openStartupDocument(null)
    return project
  }

  @Test
  fun `way A - the plain start creates the fork columns of the person`() {
    val before = reopenLastFileOption.value
    reopenLastFileOption.value = false
    try {
      val vorhanden = idsOf(startUpWithoutDocument().resourceCustomPropertyManager)
      assertEquals(
        emptyList<String>(), resourceIds.filterNot { it in vorhanden },
        "Missing after a plain start: resource columns. Present: $vorhanden")
    } finally {
      reopenLastFileOption.value = before
    }
  }

  @Test
  fun `way A - the plain start creates the fork columns of the task`() {
    val before = reopenLastFileOption.value
    reopenLastFileOption.value = false
    try {
      val vorhanden = idsOf(startUpWithoutDocument().taskCustomColumnManager)
      assertEquals(
        emptyList<String>(), taskIds.filterNot { it in vorhanden },
        "Missing after a plain start: task columns. Present: $vorhanden")
    } finally {
      reopenLastFileOption.value = before
    }
  }

  /**
   * The total, counted from the two lists above and NOT written down a second time — the
   * expectation may not exist in two forms that can drift apart.
   */
  @Test
  fun `way A - the plain start creates all fork columns and that is eleven`() {
    val before = reopenLastFileOption.value
    reopenLastFileOption.value = false
    try {
      val project = startUpWithoutDocument()
      val vorhanden = idsOf(project.resourceCustomPropertyManager) + idsOf(project.taskCustomColumnManager)
      val erwartet = resourceIds + taskIds
      assertEquals(11, erwartet.size, "The two lists in this file no longer add up to eleven")
      assertEquals(erwartet.size, erwartet.count { it in vorhanden },
        "Not all fork columns exist after a plain start. Missing: ${erwartet.filterNot { it in vorhanden }}")
    } finally {
      reopenLastFileOption.value = before
    }
  }

  /**
   * THE TRAP OF THIS CHANGE, AND IT IS THE ONE THAT BREAKS WAYS B AND C RATHER THAN WAY A.
   *
   * `CustomColumnsManager.addNewCustomColumn` throws `CustomColumnsException` —
   * "Column with ID=hours_per_day is already registered" — when a definition of that id is
   * already there. Creating the columns before something is READ into the same project, with no
   * reset in between, therefore does not spoil the startup: it spoils the loading of every file
   * that brings the same column along, and in the program that arrives as
   * `DocumentException: Failed to parse document` for the WHOLE file.
   *
   * MEASURED HERE, and that is the point of this check — the constraint is not an assumption
   * taken over from a comment. Whoever moves the seeding in `openStartupDocument` out of the
   * `else` branch and above the `if` sees this go red, and so does whoever puts it into the
   * `GanttProject` constructor.
   *
   * WHAT IT DOES NOT COVER: the SAX shell of the desktop read path (`GanttXMLOpen` with its tag
   * handlers). What it does drive is the chokepoint both paths share,
   * `CustomPropertyManager.createDefinition`, which is the only place that throws.
   */
  @Test
  fun `seeding before a read without a reset makes the file unreadable`() {
    val ziel = GanttProjectImpl()
    ensureForkColumns(ziel.resourceCustomPropertyManager, ziel.taskCustomColumnManager)
    val fehler = assertThrows(CustomColumnsException::class.java) {
      XmlProjectImporter(ziel).import(fileBringingAllColumns())
    }
    assertEquals("Column with ID=hours_per_day is already registered", fehler.message)
  }

  /**
   * AND THE WAY THE PROGRAM ACTUALLY GOES IS SAFE, for one reason only: it resets first.
   * `ProjectUIFacadeImpl.onDocumentReady` calls `project.close()` before reading, and
   * `GanttProject.close()` calls `reset()` on BOTH property managers. This check does exactly
   * that reset and then reads the same file that the check above choked on.
   *
   * So the reset is load-bearing, not incidental. Take the two `reset()` calls out of
   * `GanttProject.close()` and way C breaks — which is worth knowing independently of this
   * change.
   */
  @Test
  fun `after the reset the program does, the same file loads and brings its eleven columns`() {
    val xml = fileBringingAllColumns()
    val ziel = GanttProjectImpl()
    ensureForkColumns(ziel.resourceCustomPropertyManager, ziel.taskCustomColumnManager)

    // What GanttProject.close() does to the two managers, and nothing else.
    ziel.resourceCustomPropertyManager.reset()
    ziel.taskCustomColumnManager.reset()
    assertEquals(0, ziel.resourceCustomPropertyManager.definitions.size +
      ziel.taskCustomColumnManager.definitions.size, "the reset did not empty the managers")

    XmlProjectImporter(ziel).import(xml)
    // GanttProject.open() runs this right after document.read().
    ziel.ensureCapacityColumns()

    val vorhanden = idsOf(ziel.resourceCustomPropertyManager) + idsOf(ziel.taskCustomColumnManager)
    val erwartet = resourceIds + taskIds
    assertEquals(erwartet.size, erwartet.count { it in vorhanden },
      "way C lost columns. Missing: ${erwartet.filterNot { it in vorhanden }}")
    assertEquals(erwartet.size, vorhanden.size,
      "way C has columns twice over, or extra ones: $vorhanden")
  }

  /** A project that holds all eleven columns, saved — the file way C opens. */
  private fun fileBringingAllColumns(): String {
    val quelle = GanttProjectImpl()
    quelle.ensureCapacityColumns()
    quelle.humanResourceManager.create("Natalie", 0)
    val xml = ByteArrayOutputStream().also { GanttXMLSaver(quelle).save(it) }.toString(Charsets.UTF_8)
    assertTrue(xml.contains("hours_per_day"), "the test file does not carry the columns:\n$xml")
    assertTrue(xml.contains("work_week"), "the test file is missing work_week:\n$xml")
    return xml
  }

  /**
   * The seeding is idempotent — it has to be, because the startup runs it and
   * `GanttProject.open()` runs it again right afterwards on every way C.
   */
  @Test
  fun `seeding twice creates nothing twice`() {
    val project = GanttProjectImpl()
    ensureForkColumns(project.resourceCustomPropertyManager, project.taskCustomColumnManager)
    val einmal = project.resourceCustomPropertyManager.definitions.map { it.id }.toSet() +
      project.taskCustomColumnManager.definitions.map { it.id }.toSet()
    val groesse = project.resourceCustomPropertyManager.definitions.size +
      project.taskCustomColumnManager.definitions.size
    ensureForkColumns(project.resourceCustomPropertyManager, project.taskCustomColumnManager)
    project.ensureCapacityColumns()
    assertEquals(groesse, project.resourceCustomPropertyManager.definitions.size +
      project.taskCustomColumnManager.definitions.size,
      "a second and third call duplicated columns")
    assertEquals(11, einmal.size, "the seeding does not create exactly eleven distinct ids: $einmal")
  }
}
