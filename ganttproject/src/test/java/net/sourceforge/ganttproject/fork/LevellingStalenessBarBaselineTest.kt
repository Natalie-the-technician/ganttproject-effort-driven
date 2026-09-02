/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
The second statement of the message, tested without a screen.

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

import biz.ganttproject.core.calendar.WeekendCalendarImpl
import biz.ganttproject.core.time.CalendarFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.task.TaskManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * THE SECOND STATEMENT: "tasks are not in any baseline" -- and above all the CONDITION under which
 * it is allowed to appear.
 *
 * THE CONDITION IS THE POINT OF THE WHOLE STAGE. The second statement lives INSIDE the message
 * that is already on screen because the mark is set. It has no occasion of its own to appear. In
 * a fresh, untouched project one sees nothing at all -- and that is not a rule somebody has to
 * remember but a consequence of where the nodes hang: they are children of the very box that is
 * invisible and unmanaged while the mark is clear.
 *
 * AND IT IS NOT EVEN COMPUTED THEN. Measured on 02.09.2026 against the real plan of 276 tasks:
 * one pass over the baselines costs 2.3 ms with one baseline and 29.0 ms with fifteen, of which
 * essentially all is `GanttPreviousState.load()` re-parsing the temporary files. A question asked
 * on every keystroke would be paid for at that rate, so it is asked only on the EDGE -- when the
 * message switches itself on. The counting supplier below pins that.
 */
class LevellingStalenessBarBaselineTest {

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

  private val MONTAG: LocalDate = LocalDate.of(2026, 9, 14)

  private fun taskManager(): TaskManager =
    TestSetupHelper.newTaskManagerBuilder().withCalendar(WeekendCalendarImpl()).build()

  /** Counts how often the display asks the expensive question. */
  private class Lieferant(var antwort: BaselineGap) : () -> BaselineGap {
    var aufrufe = 0
    override fun invoke(): BaselineGap {
      aufrufe++
      return antwort
    }
  }

  private fun luecke(anzahl: Int) = BaselineGap.Missing(anzahl)

  /**
   * THE TRAP THIS WHOLE STAGE EXISTS TO AVOID: in a fresh project, with no baseline anywhere, the
   * second statement must not be on screen -- and it must not even be asked for.
   *
   * The supplier here answers "no baseline at all", which is the state of nearly every project. If
   * the second statement had an occasion of its own, this is where it would show.
   *
   * RED against 67f8b58df -- the parameter does not exist there:
   *   e: .../fork/LevellingStalenessBarBaselineTest.kt:102:35 Unresolved reference 'BaselineGap'.
   *      'constructor(staleness: LevellingStaleness, runLevelling: () -> Unit): LevellingStalenessBar'.
   */
  @Test
  fun `im frischen projekt ist die zweite aussage unsichtbar und wird nicht einmal berechnet`() =
    runBlocking {
      withContext(Dispatchers.JavaFx) {
        val staleness = LevellingStaleness(LevellingRunNotifier())
        val lieferant = Lieferant(BaselineGap.NoBaseline(12))
        val bar = LevellingStalenessBar(staleness, { }, lieferant, { it() })
        try {
          assertFalse(staleness.isStale, "Aufbau: nichts ist geschehen")
          assertFalse(bar.node.isVisible, "die ganze Meldung ist aus")
          assertFalse(bar.node.isManaged, "und nimmt keine Breite ein")
          assertEquals(0, lieferant.aufrufe,
            "bei geloeschtem Merker darf die teure Frage gar nicht gestellt werden")
        } finally {
          bar.detach()
          staleness.detach()
        }
      }
    }

  /**
   * THE STATEMENT COMES WITH THE MESSAGE, NOT BEFORE IT. The mark goes on -- and only then is the
   * question asked, exactly once.
   *
   * RED against 67f8b58df:
   *   e: .../fork/LevellingStalenessBarBaselineTest.kt:131:55 Too many arguments for 'constructor(staleness: LevellingStaleness, runLevelling: () -> Unit): LevellingStalenessBar'.
   */
  @Test
  fun `erst mit der eingeschalteten anzeige erscheint die zweite aussage`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val taskManager = taskManager()
      val staleness = LevellingStaleness(LevellingRunNotifier())
      taskManager.addTaskListener(staleness.taskListener)
      val lieferant = Lieferant(luecke(3))
      val bar = LevellingStalenessBar(staleness, { }, lieferant, { it() })
      try {
        assertEquals(0, lieferant.aufrufe, "Aufbau: noch nicht gefragt")

        taskManager.newTaskBuilder().withName("Angebot").withStartDate(MONTAG.toModelDate())
          .withDuration(taskManager.createLength(5L)).build()

        assertTrue(bar.node.isVisible, "die Meldung ist an")
        assertEquals(1, lieferant.aufrufe, "und genau einmal gefragt worden")
        assertTrue(bar.baselineLabel.isVisible, "die zweite Aussage steht da")
        assertTrue(bar.baselineLabel.isManaged, "und nimmt ihre Breite ein")
        assertTrue(bar.baselineButton.isVisible, "mit einem Knopf, denn es gibt einen Basisplan")
      } finally {
        bar.detach()
        staleness.detach()
      }
    }
  }

  /**
   * EVERY FURTHER CHANGE COSTS NOTHING. The mark is a latch: once it is set it stays set, and the
   * listener only fires on a CHANGE of value. Ten more task events therefore do not cause ten more
   * passes over the baselines -- that is the measurement turned into a property.
   *
   * RED against 67f8b58df:
   *   e: .../fork/LevellingStalenessBarBaselineTest.kt:165:55 Too many arguments for 'constructor(staleness: LevellingStaleness, runLevelling: () -> Unit): LevellingStalenessBar'.
   */
  @Test
  fun `weitere aenderungen kosten keinen zweiten durchgang ueber die basisplaene`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val taskManager = taskManager()
      val staleness = LevellingStaleness(LevellingRunNotifier())
      taskManager.addTaskListener(staleness.taskListener)
      val lieferant = Lieferant(luecke(1))
      val bar = LevellingStalenessBar(staleness, { }, lieferant, { it() })
      try {
        repeat(10) { i ->
          taskManager.newTaskBuilder().withName("Vorgang $i").withStartDate(MONTAG.toModelDate())
            .withDuration(taskManager.createLength(5L)).build()
        }

        assertEquals(1, lieferant.aufrufe,
          "zehn Aenderungen duerfen nur eine Flanke und damit einen Durchgang ausloesen")
      } finally {
        bar.detach()
        staleness.detach()
      }
    }
  }

  /**
   * WITH NO BASELINE AT ALL: A BARE STATEMENT, NO BUTTON. Natalie's decision, point 2 -- it asks
   * for nothing and therefore does not turn into wallpaper. A button there would be a demand the
   * message cannot make good on.
   *
   * RED against 67f8b58df:
   *   e: .../fork/LevellingStalenessBarBaselineTest.kt:195:55 Too many arguments for 'constructor(staleness: LevellingStaleness, runLevelling: () -> Unit): LevellingStalenessBar'.
   */
  @Test
  fun `ohne basisplan steht dort eine feststellung ohne knopf`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val taskManager = taskManager()
      val staleness = LevellingStaleness(LevellingRunNotifier())
      taskManager.addTaskListener(staleness.taskListener)
      val bar = LevellingStalenessBar(staleness, { }, Lieferant(BaselineGap.NoBaseline(4)), { it() })
      try {
        taskManager.newTaskBuilder().withName("Angebot").withStartDate(MONTAG.toModelDate())
          .withDuration(taskManager.createLength(5L)).build()

        assertTrue(bar.baselineLabel.isVisible, "die Feststellung steht da")
        assertFalse(bar.baselineButton.isVisible, "aber ohne Knopf")
        assertFalse(bar.baselineButton.isManaged, "und der Knopf nimmt auch keine Breite ein")
        assertEquals(forkText("fork.baseline.missing.none"), bar.baselineLabel.text,
          "und der Text ist die kurze Feststellung, nicht die Aufforderung")
      } finally {
        bar.detach()
        staleness.detach()
      }
    }
  }

  /**
   * NOTHING MISSING, NOTHING SAID. The first statement stands on its own; the second is silent and
   * takes no width, so the message does not grow a blank in the middle.
   *
   * RED against 67f8b58df:
   *   e: .../fork/LevellingStalenessBarBaselineTest.kt:225:55 Too many arguments for 'constructor(staleness: LevellingStaleness, runLevelling: () -> Unit): LevellingStalenessBar'.
   */
  @Test
  fun `sind alle vorgaenge erfasst schweigt die zweite aussage`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val taskManager = taskManager()
      val staleness = LevellingStaleness(LevellingRunNotifier())
      taskManager.addTaskListener(staleness.taskListener)
      val bar = LevellingStalenessBar(staleness, { }, Lieferant(BaselineGap.Covered), { it() })
      try {
        taskManager.newTaskBuilder().withName("Angebot").withStartDate(MONTAG.toModelDate())
          .withDuration(taskManager.createLength(5L)).build()

        assertTrue(bar.node.isVisible, "die erste Aussage steht fuer sich")
        assertFalse(bar.baselineLabel.isVisible, "die zweite sagt nichts")
        assertFalse(bar.baselineLabel.isManaged, "und nimmt keine Breite ein")
        assertFalse(bar.baselineButton.isManaged, "der Knopf ebenso wenig")
      } finally {
        bar.detach()
        staleness.detach()
      }
    }
  }

  /**
   * THE BUTTON RUNS THE SUPPLEMENT AND THE DISPLAY RECOMPUTES AFTERWARDS. Without the second half
   * the message would keep demanding something that has just been done.
   *
   * RED against 67f8b58df:
   *   e: .../fork/LevellingStalenessBarBaselineTest.kt:256:55 Too many arguments for 'constructor(staleness: LevellingStaleness, runLevelling: () -> Unit): LevellingStalenessBar'.
   */
  @Test
  fun `der knopf traegt nach und die anzeige rechnet danach neu`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val taskManager = taskManager()
      val staleness = LevellingStaleness(LevellingRunNotifier())
      taskManager.addTaskListener(staleness.taskListener)
      val lieferant = Lieferant(luecke(2))
      var laeufe = 0
      val bar = LevellingStalenessBar(staleness, { }, lieferant, { fertig ->
        laeufe++
        lieferant.antwort = BaselineGap.Covered
        fertig()
      })
      try {
        taskManager.newTaskBuilder().withName("Angebot").withStartDate(MONTAG.toModelDate())
          .withDuration(taskManager.createLength(5L)).build()
        assertTrue(bar.baselineLabel.isVisible, "Aufbau: die zweite Aussage steht da")

        bar.baselineButton.fire()

        assertEquals(1, laeufe, "der Knopf loest den Nachtrag aus")
        assertEquals(2, lieferant.aufrufe, "und danach wird genau einmal neu gerechnet")
        assertFalse(bar.baselineLabel.isVisible, "die Aussage ist erledigt und verschwindet")
      } finally {
        bar.detach()
        staleness.detach()
      }
    }
  }

  /**
   * SWITCHING OFF COSTS NOTHING EITHER. A levelling run clears the mark; the message goes away, and
   * there is nothing to ask about a message nobody can see.
   *
   * RED against 67f8b58df:
   *   e: .../fork/LevellingStalenessBarBaselineTest.kt:293:55 Too many arguments for 'constructor(staleness: LevellingStaleness, runLevelling: () -> Unit): LevellingStalenessBar'.
   */
  @Test
  fun `beim ausschalten wird nicht noch einmal gerechnet`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val taskManager = taskManager()
      val notifier = LevellingRunNotifier()
      val staleness = LevellingStaleness(notifier)
      taskManager.addTaskListener(staleness.taskListener)
      val lieferant = Lieferant(luecke(1))
      val bar = LevellingStalenessBar(staleness, { }, lieferant, { it() })
      try {
        taskManager.newTaskBuilder().withName("Angebot").withStartDate(MONTAG.toModelDate())
          .withDuration(taskManager.createLength(5L)).build()
        assertEquals(1, lieferant.aufrufe, "Aufbau: einmal beim Einschalten")

        staleness.clear()

        assertFalse(bar.node.isVisible, "die Meldung ist wieder aus")
        assertEquals(1, lieferant.aufrufe, "und das Ausschalten hat nichts gekostet")
      } finally {
        bar.detach()
        staleness.detach()
      }
    }
  }

  /**
   * THE MESSAGE OF THE FIRST STATEMENT IS UNTOUCHED. The mark and its button work exactly as they
   * did before this half was added -- checked here rather than argued, because the task says the
   * first half must not be rebuilt.
   *
   * RED against 67f8b58df:
   *   e: .../fork/LevellingStalenessBarBaselineTest.kt:326:66 Too many arguments for 'constructor(staleness: LevellingStaleness, runLevelling: () -> Unit): LevellingStalenessBar'.
   *      'constructor(staleness: LevellingStaleness, runLevelling: () -> Unit): LevellingStalenessBar'.
   */
  @Test
  fun `die erste aussage bleibt was sie war`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val taskManager = taskManager()
      val staleness = LevellingStaleness(LevellingRunNotifier())
      taskManager.addTaskListener(staleness.taskListener)
      var verteilt = 0
      val bar = LevellingStalenessBar(staleness, { verteilt++ }, Lieferant(BaselineGap.Covered), { it() })
      try {
        taskManager.newTaskBuilder().withName("Angebot").withStartDate(MONTAG.toModelDate())
          .withDuration(taskManager.createLength(5L)).build()

        assertEquals(forkText("fork.staleness.message"), bar.label.text,
          "der Text der ersten Aussage ist unveraendert")
        bar.button.fire()
        assertEquals(1, verteilt, "und ihr Knopf ruft weiterhin die Verteilung")
      } finally {
        bar.detach()
        staleness.detach()
      }
    }
  }
}
