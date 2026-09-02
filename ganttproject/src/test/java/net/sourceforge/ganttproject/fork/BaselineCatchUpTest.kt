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
import net.sourceforge.ganttproject.task.Task
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * THE BUTTON OF THE SECOND STATEMENT: it creates a NEW baseline, with a timestamp in its name,
 * holding the missing tasks. It does NOT write into an existing one.
 *
 * Natalie decided that on 31.08.2026 -- "then alongside" -- so that one can go back. Everything
 * that was there before stays exactly as it was, and the mechanism underneath is the one that was
 * built and tested for it: `appendToBaseline`, which reads the OLD values out of the baseline's
 * own file rather than out of today's plan.
 */
class BaselineCatchUpTest {

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

  private val JETZT: LocalDateTime = LocalDateTime.of(2026, 9, 2, 17, 34, 5)

  /** Catches the question and answers it the way the test wants. */
  private class Frage(private val antwort: Boolean) : AskBeforeWriting {
    val texte = mutableListOf<String>()
    override fun ask(message: String, answer: (Boolean) -> Unit) {
      texte.add(message)
      answer(antwort)
    }
  }

  private fun GanttProjectImpl.addTask(name: String, start: LocalDate, days: Int): Task =
    taskManager.newTaskBuilder().withName(name).withStartDate(start.toModelDate())
      .withDuration(taskManager.createLength(days.toLong())).build()

  private fun GanttProjectImpl.takeBaseline(name: String): GanttPreviousState =
    GanttPreviousState(name, GanttPreviousState.createTasks(taskManager)).also {
      it.init()
      it.saveFile()
      baselines.add(it)
    }

  private fun baseline(name: String): GanttPreviousState =
    GanttPreviousState(name, emptyList<GanttPreviousStateTask>()).also {
      it.init()
      it.saveFile()
    }

  private fun catchUp(project: GanttProjectImpl, frage: Frage,
                      berichte: MutableList<Pair<Boolean, String>> = mutableListOf(),
                      limit: Int = MAX_AUTO_BASELINES_PER_KIND) =
    BaselineCatchUp(project.taskManager, { project.baselines }, { JETZT },
      { problem, text -> berichte.add(problem to text) }, frage, limit)

  /**
   * A plan with a baseline and one task added afterwards -- Natalie's scenario: planned in the
   * future, so the task exists but no baseline knows it.
   */
  private fun planMitLuecke(): Triple<GanttProjectImpl, GanttPreviousState, Task> {
    val project = GanttProjectImpl()
    project.addTask("Angebot", LocalDate.of(2026, 8, 3), 5)
    val basisplan = project.takeBaseline("Stand vom August")
    val spaeter = project.addTask("Spaeter geplant", LocalDate.of(2026, 10, 5), 7)
    return Triple(project, basisplan, spaeter)
  }

  /**
   * A NEW BASELINE BESIDE THE OLD ONE. The old one keeps its name, keeps its place in the list and
   * keeps its entries; the new one carries the missing task.
   *
   * RED against 67f8b58df -- no production code at all:
   *   e: .../fork/BaselineCatchUpTest.kt:120:35 Cannot infer type for type parameter 'T'. Specify it explicitly.
   */
  @Test
  fun `der nachtrag legt einen neuen basisplan an und laesst den alten unveraendert`() {
    val (project, alt, spaeter) = planMitLuecke()
    val alteEintraege = alt.load()!!.map { Triple(it.id, it.start.time, it.duration) }

    catchUp(project, Frage(true)).run()

    assertEquals(2, project.baselines.size, "der neue Basisplan tritt neben den alten")
    assertSame(alt, project.baselines[0], "der alte bleibt an seiner Stelle und ist derselbe")
    assertEquals("Stand vom August", alt.name, "und er behaelt seinen Namen")
    assertEquals(alteEintraege, alt.load()!!.map { Triple(it.id, it.start.time, it.duration) },
      "kein Eintrag des alten Basisplans darf sich geaendert haben")
    assertFalse(alt.load()!!.any { it.id == spaeter.taskID },
      "und der nachgetragene Vorgang gehoert NICHT in den alten Basisplan")
  }

  /**
   * THE NEW ONE HOLDS THE OLD ENTRIES UNCHANGED PLUS THE MISSING TASK. Unchanged means from the
   * BASELINE's file, not from today's plan -- that is the whole reason `appendToBaseline` reads
   * through `load()`.
   *
   * RED against 67f8b58df:
   *   e: .../fork/BaselineCatchUpTest.kt:144:35 Cannot infer type for type parameter 'T'. Specify it explicitly.
   */
  @Test
  fun `der neue basisplan traegt die alten eintraege wertgleich und den fehlenden vorgang dazu`() {
    val (project, alt, spaeter) = planMitLuecke()
    val alteEintraege = alt.load()!!.associate { it.id to Triple(it.start.time, it.duration, it.isMilestone) }

    catchUp(project, Frage(true)).run()

    val neu = project.baselines[1].load()!!
    assertEquals(alteEintraege.size + 1, neu.size, "genau ein Eintrag kommt dazu")
    neu.filter { alteEintraege.containsKey(it.id) }.forEach {
      assertEquals(alteEintraege[it.id], Triple(it.start.time, it.duration, it.isMilestone),
        "der Eintrag ${it.id} hat sich beim Nachtragen veraendert")
    }
    val nachgetragen = neu.first { it.id == spaeter.taskID }
    assertEquals(7, nachgetragen.duration,
      "der nachgetragene Vorgang steht mit seiner geplanten Dauer darin")
  }

  /**
   * THE NAME CARRIES A TIMESTAMP, and it is recognisable as the automatic kind -- otherwise the
   * tidying up could never find it again.
   *
   * RED against 67f8b58df:
   *   e: .../fork/BaselineCatchUpTest.kt:173:18 Unresolved reference 'AutoBaselineKind'.
   */
  @Test
  fun `der name des nachtrags traegt einen zeitstempel und ist als automatisch erkennbar`() {
    val (project, _, _) = planMitLuecke()

    catchUp(project, Frage(true)).run()

    val name = project.baselines[1].name
    assertTrue(name.contains("2026-09-02") && name.contains("17:34"),
      "der Zeitstempel des Anlegens steht im Namen, gefunden wurde: \"$name\"")
    assertEquals(AutoBaselineKind.CATCH_UP, autoBaselineKindOf(name),
      "und der Name muss als die neue automatische Sorte erkannt werden")
  }

  /**
   * WITHOUT CONSENT NOTHING IS WRITTEN. The fork asks before every write, and this is a write.
   *
   * RED against 67f8b58df:
   *   e: .../fork/BaselineCatchUpTest.kt:188:29 Cannot infer type for type parameter 'T'. Specify it explicitly.
   */
  @Test
  fun `ohne zustimmung wird nichts geschrieben`() {
    val (project, alt, _) = planMitLuecke()
    val frage = Frage(false)

    catchUp(project, frage).run()

    assertEquals(1, frage.texte.size, "gefragt wird trotzdem")
    assertEquals(listOf(alt), project.baselines, "und bei einem Nein bleibt die Liste, wie sie war")
  }

  /**
   * AFTERWARDS NOTHING IS OUTSIDE THE BASELINES ANY MORE -- the button has to actually settle the
   * statement it sits next to, otherwise the message stays on after it was pressed.
   *
   * RED against 67f8b58df:
   *   e: .../fork/BaselineCatchUpTest.kt:205:21 Unresolved reference 'tasksNotInAnyBaseline'.
   */
  @Test
  fun `nach dem nachtrag steht kein vorgang mehr ausserhalb der basisplaene`() {
    val (project, _, _) = planMitLuecke()
    project.addTask("Noch einer", LocalDate.of(2026, 11, 2), 3)
    assertEquals(2, tasksNotInAnyBaseline(project.taskManager, project.baselines).size,
      "Aufbau: zwei Vorgaenge stehen in keinem Basisplan")

    catchUp(project, Frage(true)).run()

    assertEquals(emptyList<String>(),
      tasksNotInAnyBaseline(project.taskManager, project.baselines).map { it.name },
      "nach dem Nachtragen darf kein Vorgang mehr fehlen")
  }

  /**
   * THE DELETION IS ANNOUNCED. Point 5 of the task: a sentence in the preview, naming the baseline
   * that goes. Deleting silently is not on.
   *
   * RED against 67f8b58df:
   *   e: .../fork/BaselineCatchUpTest.kt:230:40 Cannot infer type for type parameter 'T'. Specify it explicitly.
   */
  @Test
  fun `die vorschau sagt an welcher basisplan dabei entfernt wird`() {
    val (project, _, _) = planMitLuecke()
    val opfer = forkText("fork.baseline.catchup.name", "2026-08-01 08:00:00")
    project.baselines.add(0, baseline(opfer))
    project.baselines.add(1, baseline(forkText("fork.baseline.catchup.name", "2026-08-02 08:00:00")))
    val frage = Frage(true)

    catchUp(project, frage, limit = 2).run()

    assertEquals(1, frage.texte.size, "genau eine Frage vor dem Schreiben")
    assertTrue(frage.texte[0].contains(opfer),
      "die Vorschau muss den Basisplan nennen, der dabei entfernt wird -- sie lautete:\n" +
        frage.texte[0])
    assertFalse(project.baselines.any { it.name == opfer },
      "und er ist danach wirklich weg")
  }

  /**
   * WITH NOTHING MISSING THE BUTTON WRITES NOTHING AND ASKS NOTHING. A question without an
   * occasion is a question that gets clicked away.
   *
   * RED against 67f8b58df:
   *   e: .../fork/BaselineCatchUpTest.kt:254:29 Cannot infer type for type parameter 'T'. Specify it explicitly.
   */
  @Test
  fun `ist nichts nachzutragen wird nicht gefragt und nichts angelegt`() {
    val project = GanttProjectImpl()
    project.addTask("Angebot", LocalDate.of(2026, 8, 3), 5)
    project.takeBaseline("Stand vom August")
    val frage = Frage(true)

    catchUp(project, frage).run()

    assertEquals(emptyList<String>(), frage.texte, "es gibt nichts zu fragen")
    assertEquals(1, project.baselines.size, "und nichts anzulegen")
  }

  /**
   * WITH NO BASELINE AT ALL THE BUTTON DOES NOTHING EITHER. There is no button in that state --
   * the display shows a bare statement instead (point 2 of the task) -- and the class must not
   * quietly do something else if it is called anyway.
   *
   * RED against 67f8b58df:
   *   e: .../fork/BaselineCatchUpTest.kt:274:29 Cannot infer type for type parameter 'T'. Specify it explicitly.
   */
  @Test
  fun `ohne jeden basisplan legt der nachtrag nichts an`() {
    val project = GanttProjectImpl()
    project.addTask("Angebot", LocalDate.of(2026, 8, 3), 5)
    val frage = Frage(true)

    catchUp(project, frage).run()

    assertEquals(emptyList<String>(), frage.texte, "ohne Basisplan gibt es nichts zu fragen")
    assertEquals(emptyList<GanttPreviousState>(), project.baselines,
      "und der Nachtrag darf nicht heimlich den ersten Basisplan erfinden")
  }

  /**
   * THE CALLER IS TOLD WHEN IT IS OVER, in both answers -- the display recomputes on that signal,
   * and a signal that only arrives on "yes" would leave the message standing after a "no".
   *
   * RED against 67f8b58df:
   *   e: .../fork/BaselineCatchUpTest.kt:293:36 Cannot infer type for type parameter 'T'. Specify it explicitly.
   */
  @Test
  fun `der aufrufer erfaehrt das ende auch bei einem nein`() {
    val (project, _, _) = planMitLuecke()
    var fertig = 0

    catchUp(project, Frage(false)).run { fertig++ }
    catchUp(project, Frage(true)).run { fertig++ }

    assertEquals(2, fertig, "beide Male muss der Aufrufer erfahren, dass es vorbei ist")
  }

  /**
   * THE HAND-NAMED BASELINE SURVIVES THE BUTTON TOO. The tidy-up runs as part of this action, so
   * the promise has to hold through it -- checked here on the whole path, not only on the tidy-up
   * function.
   *
   * RED against 67f8b58df:
   *   e: .../fork/BaselineCatchUpTest.kt:321:47 Unresolved reference 'autoBaselineKindOf'.
   */
  @Test
  fun `der knopf laesst von hand benannte basisplaene stehen`() {
    val (project, alt, _) = planMitLuecke()
    val vonHand = (1..5).map { baseline("Meilenstein $it") }
    vonHand.forEach { project.baselines.add(it) }

    repeat(5) { catchUp(project, Frage(true), limit = 1).run() }

    vonHand.forEach {
      assertTrue(project.baselines.contains(it),
        "der von Hand benannte Basisplan \"${it.name}\" ist verschwunden")
    }
    assertTrue(project.baselines.contains(alt),
      "und der ebenfalls von Hand benannte Ausgangs-Basisplan auch")
    assertEquals(1, project.baselines.count { autoBaselineKindOf(it.name) == AutoBaselineKind.CATCH_UP },
      "von der automatischen Sorte bleibt die Grenze uebrig")
  }

  /**
   * NO LEFTOVER TEMPORARY FILES FROM THE INTERMEDIATE STEPS. `appendToBaseline` builds a whole new
   * baseline per task, so supplementing three tasks produces two throwaway ones on the way. They
   * have to go, or the tidy-up limit would be measuring the wrong thing.
   *
   * RED against 67f8b58df:
   *   e: .../fork/BaselineCatchUpTest.kt:339:35 Cannot infer type for type parameter 'T'. Specify it explicitly.
   */
  @Test
  fun `die zwischenschritte hinterlassen keine basisplaene in der liste`() {
    val (project, _, _) = planMitLuecke()
    project.addTask("Noch einer", LocalDate.of(2026, 11, 2), 3)
    project.addTask("Und noch einer", LocalDate.of(2026, 11, 9), 3)

    catchUp(project, Frage(true)).run()

    assertEquals(2, project.baselines.size,
      "drei nachgetragene Vorgaenge duerfen genau EINEN neuen Basisplan ergeben")
    assertNotNull(project.baselines[1].load(),
      "und der eine muss lesbar sein")
  }
}
