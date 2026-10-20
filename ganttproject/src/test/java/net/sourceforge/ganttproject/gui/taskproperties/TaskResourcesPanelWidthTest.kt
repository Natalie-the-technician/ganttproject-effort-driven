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
package net.sourceforge.ganttproject.gui.taskproperties

import biz.ganttproject.core.time.CalendarFactory
import javafx.geometry.Orientation
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.ScrollBar
import javafx.scene.control.TableView
import javafx.scene.layout.BorderPane
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.sourceforge.ganttproject.GanttProjectImpl
import net.sourceforge.ganttproject.fork.toModelDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * HOW WIDE THE RESOURCES TAB ASKS TO BE — the guard against the task properties dialog growing
 * past what the original can do.
 *
 * WHY THIS TEST EXISTS. The dialog is a window of its own and is sized by its content, regardless
 * of the main window. Measured in the running program on 20.10.2026 with
 * `xdotool getwindowgeometry` on the window `Properties for …`:
 *
 *     original 08564e556 :  968 x 566
 *     this fork 4b15bcdb8: 1458 x 577
 *
 * On a 1024x768 screen the fork dialog is therefore cut off on both sides. Worse, under a window
 * manager (`mutter`, which the test screen runs) the window is clipped to the screen width and
 * the button bar — which sits flush right — ends up OUTSIDE the window: `Ok` is then not merely
 * off-screen but gone, and closing the dialog discards every edit, including those to ORIGINAL
 * fields such as `Duration`. The failure is silent: no message, no asterisk in the title,
 * `Save` greyed out.
 *
 * WHERE THE 490 px CAME FROM. The four columns this fork adds to the assignments table carry
 * fixed preferred widths of 130 + 110 + 90 + 160 = 490, and 1458 − 968 = 490. The table hands its
 * preferred width straight to the dialog, so every new fork column widened the dialog by its own
 * width. That is the class of defect this test closes, not just today's instance.
 *
 * THE BUDGET, AND HOW IT WAS MEASURED. Taking the whole tab's preferred width as the yardstick
 * would make this test depend on the font and on whether the i18n bundle resolves — a label of an
 * untranslated key is wider than the translation. The yardstick is therefore the part the fork
 * actually controls, the TABLE, whose columns carry absolute pixel widths and are immune to both:
 *
 *     dialog width         968  (measured, original)
 *   − dialog chrome         52  (measured: 1458 − 1406 at the fork, 968 − 916 at the original —
 *                                the two stands agree, so this is the TabPane and DialogPane
 *                                padding and nothing else)
 *   − cost and effort pane 334  (measured in this harness with the i18n bundle loaded)
 *   = table budget         582
 *
 * And 582 is exactly what the five ORIGINAL columns need: 50 + 200 + 80 + 100 + 150 = 580 plus the
 * table's own 1 px border on each side. The budget is thus not a round number somebody picked; it
 * is "the table may ask for no more than the original table asks for".
 *
 * RED BEFORE THE FIX, measured on 4b15bcdb8 — see the message quoted at each test.
 *
 * Vault 70.03, the trap this test was written around: a wish size is not a layout result.
 * `prefWidth(-1)` is only meaningful after the node hangs in a scene and `applyCss()` plus
 * `layout()` have run, which [WidthFixture] does.
 */
class TaskResourcesPanelWidthTest {

  init {
    object : CalendarFactory() {
      init {
        setLocaleApi(object : CalendarFactory.LocaleApi {
          override fun getLocale(): Locale = Locale.US
          override fun getShortDateFormat(): DateFormat =
            DateFormat.getDateInstance(DateFormat.SHORT, Locale.US)
        })
      }
    }
  }

  /**
   * The table must not ask for more width than the original table asks for.
   *
   * RED before the fix, measured on 4b15bcdb8:
   *
   *     org.opentest4j.AssertionFailedError: the assignments table asks for 1072.0 px, which is
   *     490.0 px more than the budget of 582.0 px; the dialog would be 1072.0 + 334.0 + 52.0 =
   *     1458.0 px wide instead of at most 968.0 ==> expected: <true> but was: <false>
   */
  @Test
  fun `the table stays inside the budget of the original`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val fixture = WidthFixture()
      val asked = fixture.table.prefWidth(-1.0)
      assertTrue(asked <= TABLE_BUDGET,
        "the assignments table asks for $asked px, which is ${asked - TABLE_BUDGET} px more than " +
          "the budget of $TABLE_BUDGET px; that much over the budget makes the dialog " +
          "${ORIGINAL_DIALOG_WIDTH + (asked - TABLE_BUDGET)} px wide instead of at most " +
          "$ORIGINAL_DIALOG_WIDTH, the width of the original")
    }
  }

  /**
   * The same budget stated as the rule it comes from: the four fork columns must add NOTHING to
   * what the tab asks for. This is the font-independent half — both numbers are measured in the
   * same run, so neither the font nor the i18n bundle can move them apart.
   *
   * RED before the fix, measured on 4b15bcdb8:
   *
   *     org.opentest4j.AssertionFailedError: the four fork columns widen the resources tab by
   *     490.0 px (1406.0 with them, 916.0 without) ==> expected: <916.0> but was: <1406.0>
   */
  @Test
  fun `the four fork columns do not widen the tab`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val fixture = WidthFixture()
      val withForkColumns = fixture.panelNode.prefWidth(-1.0)

      val forkColumns = fixture.table.columns.drop(ORIGINAL_COLUMN_COUNT)
      assertEquals(4, forkColumns.size, "the fork columns are not where this test expects them")
      fixture.table.columns.removeAll(forkColumns)
      fixture.relayout()
      val withoutForkColumns = fixture.panelNode.prefWidth(-1.0)

      assertEquals(withoutForkColumns, withForkColumns,
        "the four fork columns widen the resources tab by " +
          "${withForkColumns - withoutForkColumns} px ($withForkColumns with them, " +
          "$withoutForkColumns without)")
    }
  }

  /**
   * Narrow is not the same as mutilated. At the budget width every column — the four fork ones
   * included — must keep its FULL width, and the overflow must be reachable by scrolling
   * sideways. A fix that squeezed `Absence blocks` into `Abs…` would pass the two tests above and
   * is what this one forbids.
   *
   * The table's resize policy is `unconstrained-resize` (measured), which is what makes this
   * possible: the columns keep their widths and the table scrolls. Were somebody to switch the
   * policy to `constrained-resize`, the columns would shrink instead and this test goes red.
   */
  @Test
  fun `every column keeps its full width and the overflow can be scrolled to`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val fixture = WidthFixture()
      fixture.layoutAt(ORIGINAL_DIALOG_WIDTH - DIALOG_CHROME)

      fixture.table.columns.forEachIndexed { index, column ->
        if (index >= ORIGINAL_COLUMN_COUNT) {
          assertEquals(column.prefWidth, column.width,
            "fork column '${column.text}' was squeezed from ${column.prefWidth} to ${column.width}")
        }
      }

      val scrollBar = fixture.horizontalScrollBar()
        ?: throw AssertionError("the table has no horizontal scroll bar, so the fork columns " +
          "cannot be reached at all")
      assertTrue(scrollBar.isVisible, "the horizontal scroll bar is hidden")
      val overflow = fixture.table.columns.sumOf { it.width } - fixture.table.width
      assertTrue(scrollBar.max >= overflow - 1.0,
        "scrolling reaches only ${scrollBar.max} px of the $overflow px that are out of sight")
    }
  }

  /**
   * The cost section and the effort section must not become the victims of the narrowing. Both sit
   * in the right hand pane of this tab; at the budget width it has to get its full preferred
   * width, not a squeezed one.
   */
  @Test
  fun `the cost and effort pane keeps its full width`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val fixture = WidthFixture()
      val wanted = fixture.costPane.prefWidth(-1.0)
      assertTrue(wanted > 0.0, "the cost and effort pane asks for no width at all")

      fixture.layoutAt(ORIGINAL_DIALOG_WIDTH - DIALOG_CHROME)
      assertEquals(wanted, fixture.costPane.width,
        "the cost and effort pane was squeezed from $wanted to ${fixture.costPane.width}")
    }
  }

  /**
   * Three cases at the limit, not one — a raised or lowered limit has to be caught on both sides.
   * The verdict is taken from the real layout: the table's preferred width is set to each value,
   * the tab is laid out again, and what the table then asks for is what is judged.
   *
   * The limit is stated on the TABLE on purpose; through the budget above it is the 968 px limit
   * of the dialog:
   *
   *   580 px table -> 580 + 334 + 52 = 966 px dialog -> fits (clearly below)
   *   582 px table -> 582 + 334 + 52 = 968 px dialog -> fits (EXACTLY the original's width)
   *   584 px table -> 584 + 334 + 52 = 970 px dialog -> does not fit (above)
   */
  @Test
  fun `the limit holds below it, on it and above it`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val fixture = WidthFixture()

      fun askedFor(tableWidth: Double): Double {
        (fixture.table as Region).prefWidth = tableWidth
        fixture.relayout()
        return fixture.table.prefWidth(-1.0)
      }

      assertEquals(580.0, askedFor(580.0), "the set preferred width did not take effect")
      assertTrue(askedFor(580.0) <= TABLE_BUDGET, "580 px below the limit has to fit")

      assertEquals(582.0, askedFor(582.0), "the set preferred width did not take effect")
      assertTrue(askedFor(582.0) <= TABLE_BUDGET, "582 px exactly on the limit has to fit")

      assertEquals(584.0, askedFor(584.0), "the set preferred width did not take effect")
      assertFalse(askedFor(584.0) <= TABLE_BUDGET, "584 px above the limit must NOT fit")
    }
  }
}

/** Width of the task properties dialog of the unmodified original, measured in the running program. */
private const val ORIGINAL_DIALOG_WIDTH = 968.0

/**
 * What the dialog adds around the tab content: TabPane and DialogPane padding. Measured at both
 * stands independently — 1458 − 1406 at this fork and 968 − 916 at the original — which is why it
 * is a constant here and not a guess.
 */
private const val DIALOG_CHROME = 52.0

/**
 * What the assignments table may ask for: the dialog width of the original, less the dialog
 * chrome, less the cost and effort pane (334 px measured). Equals the five original columns
 * (50 + 200 + 80 + 100 + 150 = 580) plus the table's 1 px border on each side.
 */
private const val TABLE_BUDGET = 582.0

/** id, resource name, load, coordinator, role — everything after these is a fork column. */
private const val ORIGINAL_COLUMN_COUNT = 5

/**
 * One person on one task, and the resources tab that shows the assignment, hanging in a scene so
 * that preferred widths mean something (Vault 70.03).
 *
 * Built like the fixture of [TaskResourcesPanelTest]: the panel's hours column reads the resource,
 * so a project with a real resource manager is needed and not a bare task manager.
 */
private class WidthFixture {
  private val project = GanttProjectImpl()
  val panelNode: Region
  private var scene: Scene

  init {
    project.humanResourceManager.create("Natalie", 0)
    project.taskManager.newTaskBuilder()
      .withName("Vorgang")
      .withStartDate(LocalDate.of(2026, 8, 27).toModelDate())
      .withDuration(project.taskManager.createLength(5))
      .build()
    val task = project.taskManager.tasks.single()
    val assignment = task.assignmentCollection.addAssignment(project.humanResourceManager.getById(0))
    assignment.load = 100f
    panelNode = TaskResourcesPanel(task, project.humanResourceManager, project.roleManager)
      .fxComponent as Region
    // The same stylesheet the real dialog puts on its pane (Dialog.kt).
    scene = Scene(Group(panelNode)).also { it.stylesheets.add(DIALOG_STYLESHEET_PATH) }
    relayout()
  }

  val table: TableView<*> get() = tableOf(panelNode)

  /** The right hand pane of the tab: cost section on top, effort section underneath. */
  val costPane: Region get() = (panelNode as BorderPane).right as Region

  fun relayout() {
    scene.root.applyCss()
    scene.root.layout()
  }

  /** Lays the tab out at a given width, the way the dialog would. */
  fun layoutAt(width: Double) {
    scene = Scene(StackPane(panelNode), width, 600.0)
      .also { it.stylesheets.add(DIALOG_STYLESHEET_PATH) }
    relayout()
  }

  fun horizontalScrollBar(): ScrollBar? = findScrollBar(table)

  private fun findScrollBar(node: Node): ScrollBar? {
    if (node is ScrollBar && node.orientation == Orientation.HORIZONTAL) return node
    return (node as? Parent)?.childrenUnmodifiable?.firstNotNullOfOrNull { findScrollBar(it) }
  }

  private fun tableOf(node: Node): TableView<*> =
    node as? TableView<*>
      ?: (node as? Parent)?.childrenUnmodifiable?.firstNotNullOfOrNull {
        runCatching { tableOf(it) }.getOrNull()
      }
      ?: throw AssertionError("the resources tab contains no table")
}

private const val DIALOG_STYLESHEET_PATH = "/biz/ganttproject/app/Dialog.css"
