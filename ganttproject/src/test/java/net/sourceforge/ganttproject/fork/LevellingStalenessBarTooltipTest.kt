/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
The short hint that carries the reason the shortened message no longer spells out.

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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * THE TOOLTIP, WHICH IS WHERE THE LONG SENTENCE WENT.
 *
 * WHY IT HAS TO EXIST. Measured on the running program on 02.09.2026
 * (`2026-09-02-merker-bildschirm.md`, section 3.4): five seconds with the pointer on the label and
 * five on the button produced NO tooltip, and none was set in the source either. At 1024 px both
 * labels and both buttons stand at their minimum width and show nothing but an ellipsis, so the
 * text was, in that report's words, obtainable "by no route at all". Shortening the labels does
 * not change that at 1024 — the minimum width is what the message gets there, whatever its text.
 * The tooltip is the route.
 *
 * WHAT THIS TEST CAN AND CANNOT SAY. It asserts that the nodes carry a tooltip and that the
 * tooltip holds the bundle's explanation rather than a repeat of the label. Whether the tooltip
 * actually pops up under the pointer is a question for the screen, and is answered with a
 * photograph in the report of 02.09.2026 (`2026-09-02-merker-leiste.md`).
 *
 * The pattern with `Dispatchers.JavaFx` is the one [LevellingStalenessBarTest] uses, for the same
 * reason: JavaFX nodes may only be touched from their own thread.
 */
class LevellingStalenessBarTooltipTest {

  private class Lieferant(var antwort: BaselineGap) : () -> BaselineGap {
    override fun invoke(): BaselineGap = antwort
  }

  /**
   * THE FIRST STATEMENT AND ITS BUTTON both carry the reason.
   *
   * The button gets its own text, not a copy of the label's: what it explains is what pressing it
   * would DO, which is the question a button raises and a label does not.
   *
   * RED against 533832eb2:
   *   org.opentest4j.AssertionFailedError: the label of the first statement has no tooltip, so
   *   the truncated text cannot be read anywhere ==> expected: not <null>
   */
  @Test
  fun `the first statement and its button both carry a tooltip`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val staleness = LevellingStaleness(LevellingRunNotifier())
      val bar = LevellingStalenessBar(staleness, { })
      try {
        assertNotNull(bar.label.tooltip,
          "the label of the first statement has no tooltip, so the truncated text cannot be " +
            "read anywhere")
        assertEquals(forkText("fork.staleness.message.tooltip"), bar.label.tooltip.text,
          "the label's tooltip has to be the bundle's explanation")

        assertNotNull(bar.button.tooltip, "the button of the first statement has no tooltip")
        assertEquals(forkText("fork.staleness.button.tooltip"), bar.button.tooltip.text,
          "the button's tooltip has to say what pressing it does")
      } finally {
        bar.detach()
        staleness.detach()
      }
    }
  }

  /**
   * THE SECOND STATEMENT'S TOOLTIP FOLLOWS ITS TEXT. The label says two different things depending
   * on the state — "n tasks with no baseline" when baselines exist, "no baseline" when none does —
   * and the two need different explanations. A tooltip set once at construction would explain the
   * wrong state half the time.
   *
   * Checked in the state where baselines exist and some tasks are outside them.
   *
   * RED against 533832eb2:
   *   org.opentest4j.AssertionFailedError: the second statement has no tooltip ==> expected: not
   *   <null>
   */
  @Test
  fun `the second statement explains the gap it is naming`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val staleness = LevellingStaleness(LevellingRunNotifier())
      val bar = LevellingStalenessBar(staleness, { }, Lieferant(BaselineGap.Missing(3)), { it() })
      try {
        staleness.markStale()
        assertNotNull(bar.baselineLabel.tooltip, "the second statement has no tooltip")
        assertEquals(forkText("fork.baseline.missing.tooltip", 3), bar.baselineLabel.tooltip.text,
          "the tooltip has to carry the same count as the statement it explains")

        assertNotNull(bar.baselineButton.tooltip, "the catch-up button has no tooltip")
        assertEquals(forkText("fork.baseline.missing.button.tooltip"),
          bar.baselineButton.tooltip.text,
          "the catch-up button's tooltip has to say what it creates")
      } finally {
        bar.detach()
        staleness.detach()
      }
    }
  }

  /**
   * WITH NO BASELINE AT ALL the statement demands nothing and carries no button — that is the
   * decision of 02.09.2026 and it stays. The tooltip is then the ONLY place left that can say
   * what a baseline is good for, so it has to be the one for that state and not the one about
   * tasks standing outside baselines.
   *
   * RED against 533832eb2:
   *   org.opentest4j.AssertionFailedError: with no baseline the statement has no tooltip, and
   *   there is no button either -- nothing on screen can explain it ==> expected: not <null>
   */
  @Test
  fun `with no baseline the tooltip explains what a baseline is for`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val staleness = LevellingStaleness(LevellingRunNotifier())
      val bar = LevellingStalenessBar(staleness, { }, Lieferant(BaselineGap.NoBaseline(4)), { it() })
      try {
        staleness.markStale()
        assertNotNull(bar.baselineLabel.tooltip,
          "with no baseline the statement has no tooltip, and there is no button either -- " +
            "nothing on screen can explain it")
        assertEquals(forkText("fork.baseline.missing.none.tooltip"), bar.baselineLabel.tooltip.text,
          "the state without any baseline needs its own explanation")
      } finally {
        bar.detach()
        staleness.detach()
      }
    }
  }
}
