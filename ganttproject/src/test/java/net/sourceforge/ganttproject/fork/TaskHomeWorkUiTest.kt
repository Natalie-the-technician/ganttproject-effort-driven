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

import biz.ganttproject.app.PropertySheetBuilder
import biz.ganttproject.app.RootLocalizer
import biz.ganttproject.core.option.ObservableBoolean
import biz.ganttproject.core.option.ObservableEnum
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.customproperty.CustomPropertyManager
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.task.Task
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.util.Locale

/**
 * DOES THE CONTROL ACTUALLY APPEAR IN THE TASK DIALOG, and does it show the right state?
 *
 * A mark that the model stores perfectly and the dialog never shows is worth nothing, and no test
 * of the model can tell the difference. This one runs the REAL row builder -- [homeWorkRow], the
 * same function `MainPropertiesPanel` calls and the only place the control is described -- lets it
 * build its real JavaFX nodes, and looks at the control that comes out.
 *
 * WHAT IT THEREFORE DOES NOT COVER, stated rather than glossed over: that the row is actually
 * present in `MainPropertiesPanel`. The whole panel cannot be built in the test JVM -- its name
 * field asks for autocompletion, `AutoCompletionBinding` reaches into `com.sun.javafx.event`, and
 * the `test` task in the root `build.gradle` does not pass `javaExportOptions` to the test JVM the
 * way the application gets them:
 *
 *     java.lang.IllegalAccessError: class biz.ganttproject.lib.fx.AutoCompletionBinding
 *     cannot access class com.sun.javafx.event.EventHandlerManager (in module javafx.base)
 *     because module javafx.base does not export com.sun.javafx.event to unnamed module
 *
 * Measured on 04.09.2026, on a first version of this test that did build the panel. Adding
 * `jvmArgs javaExportOptions` to that `test` block would close the gap, but it changes the test
 * JVM of every module in the tree and is not this package's to decide.
 */
class TaskHomeWorkUiTest {

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

  private fun vorgang(mark: HomeWorkMark): Pair<Task, CustomPropertyManager> {
    val taskManager = TestSetupHelper.newTaskManagerBuilder().build()
    val props = taskManager.customPropertyManager
    val task = taskManager.newTaskBuilder().withName("Vorgang").build()
    applyHomeWorkMark(task.customValues, props, mark)
    return task to props
  }

  private fun alleKnoten(node: Node): Sequence<Node> =
    sequenceOf(node) + (node as? Parent)?.childrenUnmodifiable.orEmpty()
      .asSequence().flatMap { alleKnoten(it) }

  /** Runs the real row builder and hands back the dropdown it produced, plus its option. */
  @Suppress("UNCHECKED_CAST")
  private fun auswahlfeldImDialog(
    task: Task, props: CustomPropertyManager): Pair<ObservableEnum<HomeWorkMark>, ComboBox<Pair<HomeWorkMark, String>>> =
    runBlocking {
      withContext(Dispatchers.JavaFx) {
        val option = homeWorkOption(task, props)
        val sheet = PropertySheetBuilder(RootLocalizer).pane { homeWorkRow(option) }
        val feld = alleKnoten(sheet.node).filterIsInstance<ComboBox<*>>()
          .firstOrNull { combo -> combo.items.any { (it as? Pair<*, *>)?.first is HomeWorkMark } }
        assertNotNull(feld, "die Zeile hat kein Auswahlfeld fuer die Heimarbeit gebaut")
        option to (feld as ComboBox<Pair<HomeWorkMark, String>>)
      }
    }

  // ------------------------------------------------------------------------------------------

  /** All three states are offered, each under the fork's own text. */
  @Test
  fun `das auswahlfeld bietet alle drei zustaende an`() {
    val (task, props) = vorgang(HomeWorkMark.NOT_DECIDED)
    val (_, feld) = auswahlfeldImDialog(task, props)

    assertEquals(HomeWorkMark.entries, feld.items.map { it.first },
      "es muessen genau die drei Zustaende zur Wahl stehen, in dieser Reihenfolge")
    feld.items.forEach { (mark, text) ->
      assertTrue(text.isNotBlank() && !text.startsWith("fork."),
        "der Zustand $mark hat keine Beschriftung, sondern zeigt den Schluessel \"$text\" -- " +
          "dann fehlt er in einer der beiden i18n-Dateien")
    }
  }

  /**
   * THE STATE IS SHOWN, not just stored. Measured for all three, because a control that always
   * displays the first entry would look right for exactly one of them.
   */
  @Test
  fun `das auswahlfeld zeigt den zustand des vorgangs`() {
    HomeWorkMark.entries.forEach { mark ->
      val (task, props) = vorgang(mark)
      val (_, feld) = auswahlfeldImDialog(task, props)
      assertEquals(mark, feld.value?.first,
        "der Dialog zeigt fuer einen Vorgang im Zustand $mark etwas anderes an")
    }
  }

  /** And what the control shows is what gets written when the dialog is confirmed. */
  @Test
  fun `was im auswahlfeld steht wird geschrieben`() {
    HomeWorkMark.entries.forEach { gewaehlt ->
      val (task, props) = vorgang(HomeWorkMark.NOT_DECIDED)
      val (option, feld) = auswahlfeldImDialog(task, props)
      runBlocking { withContext(Dispatchers.JavaFx) {
        feld.selectionModel.select(feld.items.first { it.first == gewaehlt })
        // A selection made programmatically does not fire onAction, which is what carries the
        // value into the option -- so the click is completed by hand, the way the panel's own
        // handler would.
        feld.onAction?.handle(javafx.event.ActionEvent())
      } }

      val holder = task.customValues.copyOf()
      applyHomeWorkMark(holder, props, option.value)
      assertEquals(gewaehlt, homeWorkMark(holder, props),
        "im Feld stand $gewaehlt, geschrieben wurde etwas anderes")
    }
  }

  /**
   * WHY THIS FEATURE USES A DROPDOWN AND NOT A CHECKBOX, pinned so the reasoning is not just an
   * assertion in a comment.
   *
   * `PropertySheet.createBooleanOptionEditor` builds a `CheckBox`, hangs a watcher on the option
   * and returns -- and `ObservableImpl.addWatcher` only stores the watcher, it does not fire it.
   * So the box starts unticked whatever the option says. Harmless for `milestone`, where the
   * option is usually false anyway; not harmless for a mark whose whole point is to be visible.
   *
   * IF THIS TEST EVER GOES RED, that quirk was repaired upstream. That is good news: delete this
   * test and shorten the note in `MainPropertiesPanel`. It is not a reason to change this feature,
   * which needs three states and would need the dropdown regardless.
   */
  @Test
  fun `ein kaestchen wuerde seinen anfangswert nicht zeigen`() {
    val kaestchen = runBlocking {
      withContext(Dispatchers.JavaFx) {
        val option = ObservableBoolean("angekreuzt", true)
        val sheet = PropertySheetBuilder(RootLocalizer).pane { checkbox(option) }
        alleKnoten(sheet.node).filterIsInstance<CheckBox>().first()
      }
    }
    assertFalse(kaestchen.isSelected,
      "das Kaestchen zeigt seinen Anfangswert jetzt doch an -- siehe den Hinweis an diesem Test")
  }
}
