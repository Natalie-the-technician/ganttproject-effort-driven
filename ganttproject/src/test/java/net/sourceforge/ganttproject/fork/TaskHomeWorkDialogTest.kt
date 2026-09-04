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

import biz.ganttproject.core.table.ColumnList
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.customproperty.CustomPropertyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.gui.taskproperties.CustomColumnsPanel
import net.sourceforge.ganttproject.gui.taskproperties.applyEffortFieldsThenSyncColumns
import net.sourceforge.ganttproject.storage.ProjectDatabase
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.algorithm.findEffortDefinition
import net.sourceforge.ganttproject.undo.GPUndoManager
import org.easymock.EasyMock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.util.Locale

/**
 * OPENING THE TASK DIALOG AND PRESSING OK MUST NOT DECIDE ANYTHING.
 *
 * This is the guard of package B2, and it is not a formality. Measured in the code and pinned by
 * [der leere Wert wird zu false] below: `CustomColumnsPanel.save` walks EVERY definition and calls
 * `myHolder.addCustomProperty(def, it.value.get())`, and for a column with no value that string is
 * `""`. `CustomColumnsValues.addCustomProperty` hands it to `PropertyTypeEncoder`, which decodes a
 * BOOLEAN with `java.lang.Boolean.valueOf("")` -- and that is `false`, not null. So confirming the
 * dialog writes an explicit `false` into every boolean column of that task.
 *
 * Under this feature's reading `false` means "somebody decided this can be done from home". Left
 * alone, every task whose dialog was ever confirmed would silently leave the "nobody decided" set
 * -- the exact set that the mark exists to be able to report on. Nothing about it would look
 * broken; the task would simply be missing from a list nobody has written yet.
 *
 * WHY THE REAL PANEL AND NOT A REBUILT SEQUENCE. The note above `applyEffortFieldsThenSyncColumns`
 * records what happened the last time somebody tested this order: the test wrote its own sequence,
 * never ran the one the dialog runs, and stayed green while production was moved. So this test
 * builds the REAL [CustomColumnsPanel], calls its REAL `save`, and passes the fields exactly as
 * `TaskPropertiesController.save` passes them. Only the surroundings that a dialog would supply --
 * undo manager, column list, mirror database -- are stand-ins, and none of them takes part in the
 * decision being measured.
 */
class TaskHomeWorkDialogTest {

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

  private val projectDatabase: ProjectDatabase =
    EasyMock.createNiceMock<ProjectDatabase>(ProjectDatabase::class.java).also { EasyMock.replay(it) }
  private val undoManager: GPUndoManager =
    EasyMock.createNiceMock<GPUndoManager>(GPUndoManager::class.java).also { EasyMock.replay(it) }
  private val columnList: ColumnList =
    EasyMock.createNiceMock<ColumnList>(ColumnList::class.java).also { EasyMock.replay(it) }

  /** A project with the fork's columns present, exactly as an opened project has them. */
  private fun vorgang(): Pair<Task, CustomPropertyManager> {
    val taskManager = TestSetupHelper.newTaskManagerBuilder().build()
    val props = taskManager.customPropertyManager
    // The column exists in every project that was opened normally -- see
    // GanttProjectImpl.ensureCapacityColumns. Without it this test would measure nothing, because
    // CustomColumnsPanel only walks definitions that exist.
    findOrCreateOnSiteOnly(props)
    return taskManager.newTaskBuilder().withName("Vorgang").build() to props
  }

  /**
   * Runs the dialog's save path for one task: the real custom property panel, and the same field
   * list `TaskPropertiesController.save` builds, with the mark the dropdown would be showing.
   *
   * @return the state the task is in afterwards.
   */
  private fun dialogOeffnenUndMitOkSchliessen(
    task: Task, props: CustomPropertyManager, gezeigt: HomeWorkMark): HomeWorkMark {
    val holder = task.customValues.copyOf()
    val panel = runBlocking {
      withContext(Dispatchers.JavaFx) {
        CustomColumnsPanel(props, projectDatabase, CustomColumnsPanel.Type.TASK, undoManager,
                           holder, columnList).also {
          // The rows are built here. Without this the panel would have nothing to write back and
          // the test would be green for the wrong reason.
          it.getFxNode()
        }
      }
    }
    panel.save { gehalten ->
      applyEffortFieldsThenSyncColumns(
        holder = gehalten,
        definitions = props,
        projectDatabase = projectDatabase,
        fields = listOf({ h -> applyHomeWorkMark(h, props, gezeigt) }))
    }
    return homeWorkMark(holder, props)
  }

  // ------------------------------------------------------------------------------------------

  /**
   * The trap itself, pinned on its own so that whoever changes it later sees this test go red
   * rather than the feature go quietly wrong.
   */
  @Test
  fun `der leere Wert wird zu false`() {
    val (task, props) = vorgang()
    val def = props.findEffortDefinition(TASK_ON_SITE_ONLY)!!
    task.customValues.addCustomProperty(def, "")
    assertEquals(java.lang.Boolean.FALSE, task.customValues.getValue(def),
      "Vorbedingung dieses Pakets: die leere Zeichenkette wird zu false und nicht zu null. " +
        "Faellt das weg, ist applyHomeWorkMark's Loeschen unnoetig -- aber dann muss man es " +
        "mit Grund entfernen und nicht aus Versehen.")
  }

  /**
   * THE GUARD. A task nobody decided about goes through the whole dialog save and comes out
   * undecided -- not "can be done from home".
   */
  @Test
  fun `dialog aufmachen und mit Ok schliessen entscheidet nichts`() {
    val (task, props) = vorgang()
    assertEquals(HomeWorkMark.NOT_DECIDED, task.homeWorkMark(props), "Ausgangslage")

    val danach = dialogOeffnenUndMitOkSchliessen(task, props, HomeWorkMark.NOT_DECIDED)

    assertEquals(HomeWorkMark.NOT_DECIDED, danach,
      "der Vorgang ist durch blosses Oeffnen und Bestaetigen des Dialogs entschieden worden")
  }

  /** And a decision that WAS made survives the same path -- in both directions. */
  @Test
  fun `eine getroffene entscheidung ueberlebt den dialog`() {
    listOf(HomeWorkMark.ON_SITE, HomeWorkMark.FROM_HOME).forEach { gewaehlt ->
      val (task, props) = vorgang()
      assertEquals(gewaehlt, dialogOeffnenUndMitOkSchliessen(task, props, gewaehlt),
        "die Wahl $gewaehlt ist im Dialog verlorengegangen")
    }
  }

  /**
   * A decision can also be taken back through the dialog: from decided to undecided. That is the
   * way out of a misclick, and it has to survive the same `""`-to-`false` path.
   */
  @Test
  fun `eine entscheidung laesst sich im dialog zuruecknehmen`() {
    val (task, props) = vorgang()
    applyHomeWorkMark(task.customValues, props, HomeWorkMark.ON_SITE)
    assertEquals(HomeWorkMark.ON_SITE, task.homeWorkMark(props), "Ausgangslage")

    assertEquals(HomeWorkMark.NOT_DECIDED,
      dialogOeffnenUndMitOkSchliessen(task, props, HomeWorkMark.NOT_DECIDED),
      "der Weg zurueck nach \"niemand hat entschieden\" fuehrt nicht zurueck")
  }

  /** The undecided task keeps no value of its own, so nothing about it reaches the file. */
  @Test
  fun `nach dem dialog hat der unentschiedene vorgang keinen eigenen wert`() {
    val (task, props) = vorgang()
    val holder = task.customValues.copyOf()
    val def = props.findEffortDefinition(TASK_ON_SITE_ONLY)!!
    holder.addCustomProperty(def, "")
    assertTrue(holder.hasOwnValue(def), "Vorbedingung: der leere Wert ist erst einmal da")

    applyHomeWorkMark(holder, props, HomeWorkMark.NOT_DECIDED)

    assertTrue(!holder.hasOwnValue(def),
      "nach dem Loeschen darf kein eigener Wert mehr da sein -- sonst steht er in der .gan-Datei")
  }
}
