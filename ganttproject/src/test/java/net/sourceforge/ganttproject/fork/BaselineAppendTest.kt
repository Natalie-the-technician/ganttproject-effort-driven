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

import biz.ganttproject.core.time.CalendarFactory
import net.sourceforge.ganttproject.GanttPreviousState
import net.sourceforge.ganttproject.GanttPreviousStateTask
import net.sourceforge.ganttproject.GanttProjectImpl
import net.sourceforge.ganttproject.io.GanttXMLSaver
import net.sourceforge.ganttproject.task.Task
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * Adding a single task to an EXISTING baseline.
 *
 * THE PROBLEM: a task that did not exist when the baseline was taken has no entry in it.
 * `GanttChartSceneBuilder.renderBaseline` (line 228) walks the baseline entries and draws a band
 * only for an id it finds there; a task with no entry stays invisible in the comparison. The
 * scenario: plan a task in the FUTURE and take it into the existing baseline afterwards.
 *
 * WHAT IS PINNED HERE is the mechanism alone -- no window, no question, no decision about when
 * the user is asked. In particular the central promise: the entries that were already in the
 * baseline come out value for value unchanged.
 */
class BaselineAppendTest {

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

  /** id, start, duration, milestone, summary -- all five fields a baseline entry carries. */
  private data class Snapshot(
    val id: Int, val start: LocalDate, val duration: Int,
    val isMilestone: Boolean, val hasNested: Boolean)

  private fun GanttPreviousStateTask.snapshot() =
    Snapshot(id, start.time.toModelLocalDate(), duration, isMilestone, hasNested())

  private fun saveToXml(project: GanttProjectImpl): String =
    ByteArrayOutputStream().also { GanttXMLSaver(project).save(it) }.toString(Charsets.UTF_8)

  private fun newBaseline(project: GanttProjectImpl, name: String) =
    GanttPreviousState(name, GanttPreviousState.createTasks(project.taskManager)).also {
      // init() AND saveFile() are mandatory: a baseline holds its entries in a temporary file,
      // not in memory (GanttPreviousState.java:55/75). Without them load() runs into a null file.
      it.init()
      it.saveFile()
    }

  /**
   * A plan with three kinds of entry, so that the "unchanged" check covers all five fields and
   * not only the easy ones: a summary task (super="true"), a normal task, a milestone
   * (meeting="true", duration 0).
   */
  private fun projectWithBaseline(): Triple<GanttProjectImpl, GanttPreviousState, List<Task>> {
    val project = GanttProjectImpl()
    val tm = project.taskManager
    val summary = tm.newTaskBuilder().withName("Sammelvorgang")
      .withStartDate(LocalDate.of(2026, 8, 3).toModelDate())
      .withDuration(tm.createLength(5)).build()
    val child = tm.newTaskBuilder().withName("Teilvorgang").withParent(summary)
      .withStartDate(LocalDate.of(2026, 8, 3).toModelDate())
      .withDuration(tm.createLength(5)).build()
    val milestone = tm.newTaskBuilder().withName("Meilenstein").withLegacyMilestone()
      .withStartDate(LocalDate.of(2026, 8, 10).toModelDate())
      .withDuration(tm.createLength(1)).build()
    val baseline = newBaseline(project, "Probe-Basisplan")
    project.baselines.add(baseline)
    return Triple(project, baseline, listOf(summary, child, milestone))
  }

  /** A task created AFTER the baseline -- Natalie's scenario, planned in the future. */
  private fun futureTask(project: GanttProjectImpl): Task =
    project.taskManager.newTaskBuilder().withName("Spaeter geplant")
      .withStartDate(LocalDate.of(2026, 10, 5).toModelDate())
      .withDuration(project.taskManager.createLength(7)).build()

  // RED before the build (no production class at all):
  //   e: BaselineAppendTest.kt:124:18 Unresolved reference 'appendToBaseline'.
  // RED against the stub that hands the source straight back:
  //   org.opentest4j.AssertionFailedError: der neue Vorgang fehlt im ergaenzten Basisplan
  //   ==> expected: <true> but was: <false>
  @Test
  fun `der ergaenzte vorgang steht mit seiner geplanten lage im basisplan`() {
    val (project, baseline, _) = projectWithBaseline()
    val neu = futureTask(project)
    assertTrue(baseline.load().none { it.id == neu.taskID },
      "Vorbedingung: der neue Vorgang darf noch nicht im Basisplan stehen")

    val result = appendToBaseline(baseline, neu, project.taskManager)
    val appended = assertInstanceOf(BaselineAppendResult.Appended::class.java, result)

    // load() and not the field: this reads the temporary file back, i.e. the round trip through
    // HistorySaver -> XML -> PreviousStateTasksTagHandler that the saver and the chart also take.
    val eintrag = appended.baseline.load().firstOrNull { it.id == neu.taskID }
    assertTrue(eintrag != null, "der neue Vorgang fehlt im ergaenzten Basisplan")
    assertEquals(LocalDate.of(2026, 10, 5), eintrag!!.start.time.toModelLocalDate())
    assertEquals(7, eintrag.duration)
    assertFalse(eintrag.isMilestone)
    assertFalse(eintrag.hasNested())
  }

  // RED before the build: Unresolved reference 'appendToBaseline'.
  // RED against the stub:
  //   org.opentest4j.AssertionFailedError: der neue Vorgang fehlt in der Projektdatei:
  @Test
  fun `der rundlauf ueber die projektdatei traegt den neuen eintrag`() {
    val (project, baseline, _) = projectWithBaseline()
    val neu = futureTask(project)
    val appended = appendToBaseline(baseline, neu, project.taskManager)
      as BaselineAppendResult.Appended

    // The caller decides here -- this test takes the "replace" flavour. See the neutrality test.
    project.baselines[project.baselines.indexOf(baseline)] = appended.baseline

    val xml = saveToXml(project)
    assertTrue(xml.contains("""<previous-task id="${neu.taskID}" start="2026-10-05" duration="7""""),
      "der neue Vorgang fehlt in der Projektdatei:\n$xml")
    assertEquals(1, xml.split("<previous-tasks ").size - 1,
      "es darf genau ein Basisplan in der Datei stehen")
    // Counter-check: the date does not appear anywhere else by accident -- without the appending
    // the file must not contain that previous-task line.
    val ohne = saveToXml(GanttProjectImpl())
    assertFalse(ohne.contains("2026-10-05"))
  }

  // RED before the build: Unresolved reference 'appendToBaseline'.
  // RED against the stub:
  //   org.opentest4j.AssertionFailedError: expected: <4> but was: <3>
  @Test
  fun `die alten eintraege kommen wert fuer wert unveraendert wieder heraus`() {
    val (project, baseline, _) = projectWithBaseline()
    val vorher = baseline.load().map { it.snapshot() }
    assertEquals(3, vorher.size, "Vorbedingung: drei Eintraege im Ausgangs-Basisplan")
    assertTrue(vorher.any { it.hasNested }, "Vorbedingung: ein Sammelvorgang ist dabei")
    assertTrue(vorher.any { it.isMilestone }, "Vorbedingung: ein Meilenstein ist dabei")

    val neu = futureTask(project)
    val appended = appendToBaseline(baseline, neu, project.taskManager)
      as BaselineAppendResult.Appended

    val nachher = appended.baseline.load().map { it.snapshot() }
    assertEquals(vorher.size + 1, nachher.size)
    // Value for value AND in the same order: the old entries are the head of the new list.
    assertEquals(vorher, nachher.take(vorher.size), "ein alter Eintrag hat sich veraendert")
    // And the source itself is untouched -- it still has its three entries.
    assertEquals(vorher, baseline.load().map { it.snapshot() },
      "der Quell-Basisplan selbst wurde veraendert")
  }

  // RED before the build: Unresolved reference 'appendToBaseline'.
  // RED against the stub: org.opentest4j.AssertionFailedError: Unexpected type, expected:
  //   <...BaselineAppendResult.AlreadyPresent> but was: <...BaselineAppendResult.Appended>
  @Test
  fun `ein schon eingetragener vorgang wird nicht doppelt aufgenommen`() {
    val (project, baseline, tasks) = projectWithBaseline()
    val schonDrin = tasks.first()
    val vorher = baseline.load().map { it.snapshot() }

    val result = appendToBaseline(baseline, schonDrin, project.taskManager)

    val present = assertInstanceOf(BaselineAppendResult.AlreadyPresent::class.java, result)
    assertEquals(schonDrin.taskID, present.taskId)
    // The decision is SKIP, not replace: the baseline holds what was PLANNED then. Overwriting
    // the entry with today's values would destroy exactly what the baseline exists for, and
    // nothing in the file would show it -- a previous-task carries no timestamp and no origin.
    assertEquals(vorher, baseline.load().map { it.snapshot() },
      "ein vorhandener Eintrag darf beim Uebergehen nicht angefasst werden")
  }

  // RED before the build: Unresolved reference 'appendToBaseline'.
  // RED against the stub:
  //   java.util.NoSuchElementException: Collection contains no element matching the predicate.
  @Test
  fun `die dauer im basisplan zaehlt arbeitstage, nicht kalendertage und nicht stunden`() {
    val (project, baseline, _) = projectWithBaseline()
    val tm = project.taskManager
    // Friday 21.08.2026, three working days -> Fri, Mon, Tue; the task ends on Wednesday
    // 26.08. That is five CALENDAR days. The baseline entry has to say 3.
    val ueberWochenende = tm.newTaskBuilder().withName("Ueber das Wochenende")
      .withStartDate(LocalDate.of(2026, 8, 21).toModelDate())
      .withDuration(tm.createLength(3)).build()
    assertEquals(LocalDate.of(2026, 8, 26), ueberWochenende.end.time.toModelLocalDate(),
      "Vorbedingung: drei Arbeitstage ab Freitag enden am Mittwoch")

    val appended = appendToBaseline(baseline, ueberWochenende, project.taskManager)
      as BaselineAppendResult.Appended
    val eintrag = appended.baseline.load().first { it.id == ueberWochenende.taskID }

    assertEquals(3, eintrag.duration, "die Dauer sind Arbeitstage")
    assertFalse(eintrag.duration == 5, "keine Kalendertage")
    assertFalse(eintrag.duration == 24, "keine Stunden")
  }

  // RED before the build: Unresolved reference 'appendToBaseline'.
  // RED against the stub:
  //   org.opentest4j.AssertionFailedError:
  //   expected: <Probe-Basisplan + Spaeter geplant> but was: <Probe-Basisplan>
  @Test
  fun `das ergaenzen entscheidet nicht ueber ersetzen oder danebenstellen`() {
    val (project, baseline, _) = projectWithBaseline()
    val neu = futureTask(project)

    val appended = appendToBaseline(baseline, neu, project.taskManager)
      as BaselineAppendResult.Appended

    // THE CUT: appending itself does not touch the baseline LIST. Whether the result replaces
    // the old baseline or steps beside it is decided by the caller, one line further on.
    assertEquals(1, project.baselines.size, "das Ergaenzen darf die Liste nicht anfassen")
    assertSame(baseline, project.baselines[0], "der alte Basisplan steht unveraendert in der Liste")

    // Flavour A -- alongside, under a name of the caller's choosing.
    val daneben = appendToBaseline(baseline, neu, project.taskManager,
      name = "${baseline.name} + Spaeter geplant") as BaselineAppendResult.Appended
    project.baselines.add(daneben.baseline)
    assertEquals(2, project.baselines.size)
    assertEquals("Probe-Basisplan + Spaeter geplant", daneben.baseline.name)
    assertEquals("Probe-Basisplan", baseline.name, "der alte Name bleibt")

    // Flavour B -- replace, same name.
    project.baselines.remove(daneben.baseline)
    project.baselines[project.baselines.indexOf(baseline)] = appended.baseline
    assertEquals(1, project.baselines.size)
    assertEquals("Probe-Basisplan", appended.baseline.name, "ohne Namensangabe bleibt der Name")

    val xml = saveToXml(project)
    assertEquals(1, xml.split("<previous-tasks ").size - 1)
  }
}
