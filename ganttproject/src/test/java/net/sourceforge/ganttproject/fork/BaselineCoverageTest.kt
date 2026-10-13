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

import net.sourceforge.ganttproject.GanttPreviousState
import net.sourceforge.ganttproject.GanttPreviousStateTask
import net.sourceforge.ganttproject.GanttProjectImpl
import net.sourceforge.ganttproject.task.Task
import biz.ganttproject.core.time.CalendarFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * "TASKS ARE NOT IN ANY BASELINE" -- the comparison itself.
 *
 * THE STATEMENT IS DELIBERATELY "IN NO BASELINE AT ALL", not "not in the selected one". The
 * selected baseline is a fleeting thing: `getGanttChart().getBaseline()` is set in exactly one
 * place (`BaselineDialogAction.java:111/113`), is never saved and is null after a file is opened.
 * And "the newest" does not exist either -- a `<previous-tasks>` element carries no timestamp, so
 * "the last one in the list" is insertion order and nothing more. A question asked against ALL
 * baselines needs no such choice, and with a single baseline it says exactly what one expects.
 *
 * THE PRICE USED TO BE IN load(), NOT IN THE COMPARISON: measured on 02.09.2026 against the real
 * plan of 276 tasks, one pass over 15 baselines took 29.0 ms, almost all of it `load()` re-parsing
 * temporary files, against 0.058 ms for the set comparison. [Since 13.10.2026 / bardsoft #2838]
 * `getTasks()` has no parse cost left -- but [idsInBaselines] still reads each baseline exactly
 * ONCE per pass, which stays worth pinning as a plain invariant, and the display still asks only
 * when it switches itself on (see [LevellingStalenessBar]).
 */
class BaselineCoverageTest {

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

  /** Counts its own getTasks() calls -- the class is not final and neither is the method. */
  private class CountingBaseline(name: String, tasks: List<GanttPreviousStateTask>) :
    GanttPreviousState(name, tasks) {
    var loads = 0
    override fun getTasks(): List<GanttPreviousStateTask> {
      loads++
      return super.getTasks()
    }
  }

  private fun GanttProjectImpl.addTask(name: String, start: LocalDate, days: Int): Task =
    taskManager.newTaskBuilder().withName(name).withStartDate(start.toModelDate())
      .withDuration(taskManager.createLength(days.toLong())).build()

  private fun GanttProjectImpl.takeBaseline(name: String): CountingBaseline =
    CountingBaseline(name, GanttPreviousState.createTasks(taskManager)).also {
      baselines.add(it)
    }

  private fun project(): GanttProjectImpl = GanttProjectImpl()

  /**
   * A task created after the baseline was taken has no entry in it, and that is the whole point.
   *
   * RED against 67f8b58df -- no production code at all:
   *   e: .../fork/BaselineCoverageTest.kt:102:19 Unresolved reference 'tasksNotInAnyBaseline'.
   */
  @Test
  fun `ein vorgang der nach dem basisplan entstand steht in keinem`() {
    val project = project()
    project.addTask("Angebot", LocalDate.of(2026, 8, 3), 5)
    project.takeBaseline("Probe")
    val spaeter = project.addTask("Spaeter geplant", LocalDate.of(2026, 10, 5), 7)

    val fehlend = tasksNotInAnyBaseline(project.taskManager, project.baselines)

    assertEquals(listOf(spaeter.taskID), fehlend.map { it.taskID },
      "genau der nachtraeglich angelegte Vorgang steht in keinem Basisplan")
  }

  /**
   * "IN NO BASELINE" AND NOT "IN THE SELECTED ONE": a task that only the FIRST of three baselines
   * knows is covered. This is the assertion the whole wording rests on -- were it checked against
   * one chosen baseline, this task would be reported as missing.
   *
   * RED against 67f8b58df:
   *   e: .../fork/BaselineCoverageTest.kt:128:19 Unresolved reference 'tasksNotInAnyBaseline'.
   */
  @Test
  fun `ein vorgang der nur im ersten von drei basisplaenen steht fehlt nicht`() {
    val project = project()
    val frueh = project.addTask("Nur im ersten", LocalDate.of(2026, 8, 3), 5)
    project.takeBaseline("Erster")
    // The task is deleted from the plan and a new one takes its place, so the two later baselines
    // cannot know the old id.
    project.taskManager.deleteTask(frueh)
    project.addTask("Spaeter", LocalDate.of(2026, 9, 1), 5)
    project.takeBaseline("Zweiter")
    project.takeBaseline("Dritter")

    val fehlend = tasksNotInAnyBaseline(project.taskManager, project.baselines)

    assertTrue(fehlend.none { it.taskID == frueh.taskID },
      "der Vorgang steht im ersten Basisplan und darf deshalb nicht als fehlend gelten")
    assertEquals(emptyList<Int>(), fehlend.map { it.taskID },
      "alle heutigen Vorgaenge stehen in mindestens einem Basisplan")
  }

  /**
   * ZERO BASELINES IS ITS OWN ANSWER, and not "all tasks are missing". Nearly every project is in
   * that state; reporting every task as missing there would be the message that is always on.
   *
   * RED against 67f8b58df:
   *   e: .../fork/BaselineCoverageTest.kt:149:15 Unresolved reference 'baselineGap'.
   */
  @Test
  fun `ohne basisplan lautet die antwort NoBaseline und nicht eine liste fehlender vorgaenge`() {
    val project = project()
    project.addTask("Angebot", LocalDate.of(2026, 8, 3), 5)
    project.addTask("Bau", LocalDate.of(2026, 8, 10), 5)

    val gap = baselineGap(project.taskManager, project.baselines)

    val kein = assertInstanceOf(BaselineGap.NoBaseline::class.java, gap,
      "ohne Basisplan darf nicht eine Liste fehlender Vorgaenge herauskommen")
    assertEquals(2, kein.taskCount, "die Zahl der Vorgaenge steht trotzdem darin")
  }

  /**
   * AND AN EMPTY PROJECT SAYS NOTHING AT ALL. A brand new project has no baseline AND no task;
   * "no baseline yet" would be a true statement about nothing.
   *
   * RED against 67f8b58df:
   *   e: .../fork/BaselineCoverageTest.kt:167:15 Unresolved reference 'baselineGap'.
   */
  @Test
  fun `ein leeres projekt ohne basisplan hat nichts zu melden`() {
    val project = project()

    val gap = baselineGap(project.taskManager, project.baselines)

    assertInstanceOf(BaselineGap.Covered::class.java, gap,
      "ein Projekt ohne Vorgaenge hat nichts zu melden, auch ohne Basisplan")
  }

  /**
   * ALL TASKS COVERED IS ALSO ITS OWN ANSWER -- the display has to be able to say nothing.
   *
   * RED against 67f8b58df:
   *   e: .../fork/BaselineCoverageTest.kt:185:15 Unresolved reference 'baselineGap'.
   */
  @Test
  fun `sind alle vorgaenge erfasst, ist die antwort Covered`() {
    val project = project()
    project.addTask("Angebot", LocalDate.of(2026, 8, 3), 5)
    project.takeBaseline("Probe")

    val gap = baselineGap(project.taskManager, project.baselines)

    assertInstanceOf(BaselineGap.Covered::class.java, gap,
      "jeder Vorgang steht im Basisplan, also gibt es nichts zu melden")
  }

  /**
   * THE MATCHING RUNS OVER `entry.getId() == task.getRowId()`, which the task named for checking.
   * Checked here from the other side: an entry whose id belongs to no task at all does not make
   * any task look covered, and a task whose rowId equals its taskID is found.
   *
   * RED against 67f8b58df:
   *   e: .../fork/BaselineCoverageTest.kt:205:21 Unresolved reference 'idsInBaselines'.
   */
  @Test
  fun `die zuordnung laeuft ueber die kennung des eintrags gegen die zeilenkennung des vorgangs`() {
    val project = project()
    val angebot = project.addTask("Angebot", LocalDate.of(2026, 8, 3), 5)
    project.takeBaseline("Probe")

    val kennungen = idsInBaselines(project.baselines)

    assertEquals(angebot.taskID, angebot.rowId,
      "Aufbau: TaskImpl.getRowId() gibt getTaskID() zurueck -- daran haengt die Zuordnung")
    assertTrue(kennungen.contains(angebot.rowId),
      "die Zeilenkennung des Vorgangs steht unter den Kennungen des Basisplans")
  }

  /**
   * EACH BASELINE IS READ EXACTLY ONCE PER PASS. Before 13.10.2026 this was not tidiness: measured
   * on the real plan, `load()` was 29.0 of the 29.0 ms a 15-baseline pass cost, and a second read
   * per baseline would have doubled the only expensive part. [Since bardsoft #2838] `getTasks()`
   * has no such cost left, but the invariant stays worth pinning as a plain regression guard.
   */
  @Test
  fun `jeder basisplan wird je durchgang genau einmal gelesen`() {
    val project = project()
    project.addTask("Angebot", LocalDate.of(2026, 8, 3), 5)
    val a = project.takeBaseline("Erster")
    val b = project.takeBaseline("Zweiter")
    project.addTask("Spaeter", LocalDate.of(2026, 10, 5), 7)

    tasksNotInAnyBaseline(project.taskManager, project.baselines)

    assertEquals(1, a.loads, "der erste Basisplan wurde mehr als einmal gelesen")
    assertEquals(1, b.loads, "der zweite Basisplan wurde mehr als einmal gelesen")
  }

  // The test that stood here until 13.10.2026, "ein nicht lesbarer basisplan bricht den
  // durchgang nicht ab", is gone: it exercised `remove()` deleting a baseline's temporary file so
  // that a later `load()` hit an IOException and returned null. [Since bardsoft #2838] there is no
  // temporary file and no such failure mode -- `getTasks()` cannot return null, so the scenario
  // this test guarded against can no longer happen.
}
