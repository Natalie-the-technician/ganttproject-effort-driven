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

import biz.ganttproject.core.calendar.AlwaysWorkingTimeCalendarImpl
import biz.ganttproject.core.option.DefaultFontOption
import biz.ganttproject.core.option.DefaultIntegerOption
import biz.ganttproject.core.option.FontSpec
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.core.time.impl.GPTimeUnitStack
import biz.ganttproject.customproperty.CustomPropertyManager
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeTableView
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.chart.ChartModelImpl
import net.sourceforge.ganttproject.gui.UIConfiguration
import net.sourceforge.ganttproject.task.Task
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Dimension
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * ═══ S8: DER ERSTE GRIFF DARF DIE ZEILE NICHT AUFREISSEN ═══
 *
 * The row built by S1–S3 rests on the group being shut. The table opens every ancestor of a
 * selection that did not come from the table, so pressing a bar on that row selected a child,
 * opened its group and gave the twelve rows back. These checks are about the rule that stops it —
 * and, just as much, about the three things that must NOT change with it.
 *
 * ═══ WAS HIER ROT WERDEN KANN ═══
 *
 * Every check in this file was run against the code before the change as well, and the four that
 * are about the new behaviour failed there; the ones about the unchanged behaviour passed there and
 * pass here, which is the whole point of having them. The wording of each failure is in the report
 * of 14.09.2026.
 *
 * ═══ WARUM DIE TABELLE HIER NICHT VORKOMMT ═══
 *
 * [ancestorsToOpen] is the whole of the change inside `TaskTable`: the line that used to build a
 * list now calls this function, and the `forEach` after it is untouched. Checking the function is
 * therefore checking the rule, without building a JavaFX `TaskTable`, a `TaskSelectionManager` and
 * a chart just to watch one boolean. What the table does with the list — and that a `TreeItem`
 * outside the open part of the tree has no row — is the last check in this file, and that one does
 * use a real `TreeTableView`.
 */
class SerieBedienungTest {

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

  private val montag = LocalDate.of(2026, 9, 7)
  private val termine = listOf(montag, montag.plusDays(7), montag.plusDays(14))

  /**
   * A plan with a real chart over it.
   *
   * IT RENDERS THROUGH THE MODEL'S OWN RENDERER, `chartModel.taskRenderer`, and not through a
   * second one built beside it. `isMergedSeriesRow` reads the switch off the model's renderer; a
   * check that set the switch on a renderer of its own would set it on an object nobody asks.
   * `SerieEineReiheTest` builds its own and gets away with it because it only ever reads rectangles.
   */
  private inner class Diagramm {
    val taskManager = TestSetupHelper.newTaskManagerBuilder()
      .withCalendar(AlwaysWorkingTimeCalendarImpl()).build()
    val props: CustomPropertyManager = taskManager.customPropertyManager
    val chartModel: ChartModelImpl

    init {
      val projectConfig = UIConfiguration(Color.BLUE, false)
      projectConfig.chartFontOption =
        DefaultFontOption("foo", FontSpec("Foo", FontSpec.Size.NORMAL), emptyList())
      projectConfig.dpiOption = DefaultIntegerOption("bar", 96)
      chartModel = ChartModelImpl(taskManager, GPTimeUnitStack(), projectConfig)
      chartModel.setBounds(Dimension(1600, 400))
      chartModel.setTopTimeUnit(GPTimeUnitStack.WEEK)
      chartModel.setBottomTimeUnit(GPTimeUnitStack.DAY)
      chartModel.setBottomUnitWidth(20)
      chartModel.setRowHeight(20)
      chartModel.startDate = montag.minusDays(7).toModelDate()
    }

    fun vorgang(name: String, start: LocalDate, tage: Int = 1, elternteil: Task? = null): Task =
      taskManager.newTaskBuilder()
        .withName(name)
        .withStartDate(start.toModelDate())
        .withDuration(taskManager.createLength(tage.toLong()))
        .let { if (elternteil == null) it else it.withParent(elternteil) }
        .build()

    /** A recurrence group with one child per date, collapsed. Same markers as `RecurrenceAdapter`. */
    fun serie(name: String, daten: List<LocalDate> = termine): Pair<Task, List<Task>> {
      val quelleId = 4711
      val gruppe = vorgang(name, daten.first())
      val kinder = daten.map { datum ->
        vorgang(name, datum, 1, gruppe).also {
          it.customValues.setValue(findOrCreateRecurrenceOf(props), recurrenceMark(quelleId, datum))
        }
      }
      gruppe.customValues.setValue(findOrCreateRecurrenceOf(props), recurrenceGroupMark(quelleId))
      taskManager.algorithmCollection.adjustTaskBoundsAlgorithm.run(gruppe)
      gruppe.expand = false
      return gruppe to kinder
    }

    /** An ordinary summary task with children, collapsed, and no recurrence marker anywhere. */
    fun sammelgruppe(name: String): Pair<Task, List<Task>> {
      val gruppe = vorgang(name, termine.first())
      val kinder = termine.map { vorgang(name, it, 1, gruppe) }
      taskManager.algorithmCollection.adjustTaskBoundsAlgorithm.run(gruppe)
      gruppe.expand = false
      return gruppe to kinder
    }

    fun schalterAn() { chartModel.taskRenderer.seriesOneRowOption.value = true }
  }

  /** The Gantt chart, as far as this rule is concerned: it answers for its own rows. */
  private class AlsDiagramm(val model: ChartModelImpl) : MergedSeriesRows {
    override fun drawsSeriesOnOneRow(task: Task) = model.isMergedSeriesRow(task)
  }

  /** The search box, and everything else that is not the chart: it knows nothing and says nothing. */
  private class AlsSuche

  /**
   * Brings the JavaFX toolkit up for the one check below that builds a real `TreeTableView`.
   *
   * WITHOUT IT THE CHECK DIES IN `Control.<clinit>` with „Toolkit not initialized" — seen, and it
   * is not the failure the check is looking for. The tests of this module already run under an X
   * server for this reason (`gantt-env.sh`); what they do not do is start the toolkit, because
   * nothing else in the fork's own checks builds a JavaFX control.
   *
   * `IllegalStateException` is swallowed on purpose and only in the one case it can mean: all tests
   * share a JVM, so whoever ran first may have started it already.
   */
  private fun starteJavaFx() {
    try {
      val fertig = java.util.concurrent.CountDownLatch(1)
      javafx.application.Platform.startup { fertig.countDown() }
      fertig.await(30, java.util.concurrent.TimeUnit.SECONDS)
    } catch (e: IllegalStateException) {
      // already running
    }
  }

  // =============================================================================================
  // 1. Which row is a merged one -- measured through the real chart model.
  // =============================================================================================

  /**
   * The predicate the whole rule hangs on, in all five shapes it has to tell apart. One check and
   * not five, deliberately: what matters is that these five answers are given by ONE method at ONE
   * moment over ONE plan, because the bug this replaces would have been "right for the group,
   * wrong for the child".
   */
  @Test
  fun `nur die zugeklappte serie ist eine zusammengelegte zeile`() {
    val d = Diagramm()
    val (serie, kinder) = d.serie("Umsatzsteuervoranmeldung")
    val (sammel, _) = d.sammelgruppe("etwas anderes")
    val einzeln = d.vorgang("ein Vorgang fuer sich", montag)
    d.schalterAn()

    assertTrue(d.chartModel.isMergedSeriesRow(serie),
      "die zugeklappte Wiederholungsgruppe ist die zusammengelegte Zeile")
    assertFalse(d.chartModel.isMergedSeriesRow(sammel),
      "eine gewoehnliche Sammelgruppe zeichnet weiter ihren eigenen Balken")
    assertFalse(d.chartModel.isMergedSeriesRow(kinder[0]),
      "ein einzelner Termin ist keine Zeile, die etwas zusammenlegt")
    assertFalse(d.chartModel.isMergedSeriesRow(einzeln),
      "ein Vorgang ohne Kinder erst recht nicht")

    serie.expand = true
    assertFalse(d.chartModel.isMergedSeriesRow(serie),
      "aufgeklappt hat jeder Termin seine eigene Zeile, da gibt es nichts zusammenzulegen")
  }

  /**
   * THE SWITCH OF S10 DECIDES, AND IT IS OFF BY DEFAULT. Without this the rule would change what
   * the table does for every user of this fork, switch or no switch — and the one promise this
   * whole package rests on is that it changes nothing until somebody turns it on.
   */
  @Test
  fun `bei ausgeschaltetem schalter gibt es keine zusammengelegte zeile`() {
    val d = Diagramm()
    val (serie, _) = d.serie("Umsatzsteuervoranmeldung")

    assertFalse(d.chartModel.taskRenderer.seriesOneRowOption.value,
      "der Schalter muss per Vorgabe AUS sein")
    assertFalse(d.chartModel.isMergedSeriesRow(serie),
      "ausgeschaltet zeichnet die Gruppe ihren eigenen Balken, und die Tabelle darf sie oeffnen")

    d.schalterAn()
    assertTrue(d.chartModel.isMergedSeriesRow(serie),
      "und eingeschaltet nicht -- sonst pruefte die Zeile darueber gar nichts")
  }

  // =============================================================================================
  // 2. The rule itself.
  // =============================================================================================

  /**
   * THE CHECK THE CHANGE EXISTS FOR. A press on a bar selects the date; the date's group is the
   * collapsed series; the list the table opens must not contain it.
   *
   * THE GRANDPARENT IS IN THE PLAN ON PURPOSE. Dropping every ancestor would pass a check that only
   * looked for the group's absence, and would leave the series unreachable inside a shut folder.
   * Only the merged row is kept shut; everything above it is opened as before.
   */
  @Test
  fun `ein griff im diagramm oeffnet die zusammengelegte zeile nicht`() {
    val d = Diagramm()
    val ordner = d.vorgang("Steuern", montag, 30)
    val (serie, kinder) = d.serie("Umsatzsteuervoranmeldung")
    serie.move(ordner)
    d.taskManager.algorithmCollection.adjustTaskBoundsAlgorithm.run(ordner)
    d.schalterAn()

    val zuOeffnen = ancestorsToOpen(
      listOf(kinder[1]), d.taskManager.taskHierarchy, AlsDiagramm(d.chartModel))

    assertEquals(listOf(ordner), zuOeffnen,
      "nur der Ordner darueber wird geoeffnet, die Serie bleibt zu -- bekommen: " +
        zuOeffnen.map { it.name to it.taskID })
  }

  /**
   * THE SEARCH BOX KEEPS ITS ANSWER. Typing the name of one date and hitting the result has to take
   * the user there, and there is only a row to take them to if the group opens. The source decides,
   * and the search box is not the chart.
   */
  @Test
  fun `die suche oeffnet die serie weiterhin`() {
    val d = Diagramm()
    val (serie, kinder) = d.serie("Umsatzsteuervoranmeldung")
    d.schalterAn()

    val zuOeffnen = ancestorsToOpen(listOf(kinder[1]), d.taskManager.taskHierarchy, AlsSuche())

    assertEquals(listOf(serie), zuOeffnen,
      "eine Auswahl, die nicht aus dem Diagramm kommt, oeffnet die Serie wie vorher")
  }

  /**
   * AND A SOURCE OF `null` TOO. `clear()`, `TaskLinkAction`, `TaskUnlinkAction` and the table's own
   * `taskMoved` all fire with no source at all; `as?` on null must fall through to the old list and
   * not to an empty one.
   */
  @Test
  fun `ohne quelle bleibt alles wie vorher`() {
    val d = Diagramm()
    val (serie, kinder) = d.serie("Umsatzsteuervoranmeldung")
    d.schalterAn()

    assertEquals(listOf(serie),
      ancestorsToOpen(listOf(kinder[0]), d.taskManager.taskHierarchy, null),
      "eine Meldung ohne Quelle darf sich nicht wie das Diagramm verhalten")
  }

  /**
   * THE CHART DOES NOT GET A FREE PASS EITHER. With the switch off the very same press must open
   * the group again, because then the row really does draw only the group's own bar and a date
   * inside it really has nowhere to be seen.
   */
  @Test
  fun `bei ausgeschaltetem schalter oeffnet auch das diagramm wieder`() {
    val d = Diagramm()
    val (serie, kinder) = d.serie("Umsatzsteuervoranmeldung")

    assertEquals(listOf(serie),
      ancestorsToOpen(listOf(kinder[0]), d.taskManager.taskHierarchy, AlsDiagramm(d.chartModel)),
      "ausgeschaltet ist der alte Weg der richtige")
  }

  /**
   * A GROUP THAT IS NOT A SERIES IS OPENED BY THE CHART AS WELL. Pressing the bar of a child inside
   * an ordinary collapsed summary task cannot happen through a merged row — the child is not drawn
   * at all — but it can happen through multiple selection, and the answer there is the old one.
   */
  @Test
  fun `eine gewoehnliche sammelgruppe oeffnet das diagramm weiterhin`() {
    val d = Diagramm()
    val (sammel, kinder) = d.sammelgruppe("etwas anderes")
    d.schalterAn()

    assertEquals(listOf(sammel),
      ancestorsToOpen(listOf(kinder[0]), d.taskManager.taskHierarchy, AlsDiagramm(d.chartModel)),
      "nur die Wiederholungsgruppe ist ausgenommen, nicht jede Gruppe")
  }

  /**
   * MEHRERE TERMINE AUF EINMAL. Ctrl-click adds to the selection, so the list handed in can hold
   * several dates of the same series — the answer must still be one entry per ancestor and the
   * series still absent. `ancestors` de-duplicates; this pins that the change did not undo it.
   */
  @Test
  fun `mehrere termine derselben serie ergeben keine doppelten eintraege`() {
    val d = Diagramm()
    val ordner = d.vorgang("Steuern", montag, 30)
    val (serie, kinder) = d.serie("Umsatzsteuervoranmeldung")
    serie.move(ordner)
    d.taskManager.algorithmCollection.adjustTaskBoundsAlgorithm.run(ordner)
    d.schalterAn()

    val zuOeffnen = ancestorsToOpen(kinder, d.taskManager.taskHierarchy, AlsDiagramm(d.chartModel))
    assertEquals(listOf(ordner), zuOeffnen, "einmal der Ordner, und die Serie gar nicht")
  }

  // =============================================================================================
  // 3. What the table does with a row that stays shut.
  // =============================================================================================

  /**
   * THE PLATFORM FACT THE SCROLL GUARD RESTS ON, measured rather than recalled: a `TreeItem` under
   * a collapsed parent has NO row, and JavaFX says so with -1.
   *
   * That -1 used to travel on into `GPTreeTableViewSkin.scrollTo(row + 1)`, which is row 0 — asking
   * to be taken to something invisible took the table to the top of the plan. The guard in
   * `GPTreeTableView.scrollTo` returns instead. Both halves are asserted here: the visible sibling
   * still HAS a row, so a guard written as „never scroll" would fail this.
   */
  @Test
  fun `ein eintrag unter einem zugeklappten knoten hat keine zeile`() {
    starteJavaFx()
    val wurzel = TreeItem("Plan")
    wurzel.isExpanded = true
    val serie = TreeItem("Umsatzsteuervoranmeldung")
    val nachbar = TreeItem("etwas anderes")
    val termin = TreeItem("Maerz")
    serie.children.add(termin)
    wurzel.children.addAll(listOf(serie, nachbar))

    val tabelle = TreeTableView(wurzel)

    serie.isExpanded = true
    assertTrue(tabelle.getRow(termin) > 0,
      "aufgeklappt hat der Termin eine Zeile -- bekommen: ${tabelle.getRow(termin)}")

    serie.isExpanded = false
    assertEquals(-1, tabelle.getRow(termin),
      "zugeklappt hat er keine, und genau darauf stuetzt sich die Wache in scrollTo")
    assertTrue(tabelle.getRow(nachbar) > 0,
      "der sichtbare Nachbar hat weiterhin eine -- sonst pruefte die Zeile darueber nichts")
  }
}
