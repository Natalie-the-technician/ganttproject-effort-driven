/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
The permanently visible message that says the capacity has to be redistributed.

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

import biz.ganttproject.FXUtil
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox

/**
 * THE MESSAGE, IN THE MIDDLE OF THE STATUS BAR.
 *
 * WHY THERE. Measured on 28.08.2026 (`2026-08-28-verteilung-veraltet-messung.md`, section 3): the
 * middle of the status bar is the ONE permanently visible area of the window that is empty today.
 * It is a plain `Pane` used as a spacer, carrying nothing (`GanttProjectFxApp.kt`). The two
 * alternatives were measured and rejected in the same place: the notification bubbles disappear
 * and were already found unreadable once (`GanttProject.java`, the comment on
 * `EstimateQualityAction`), and the settings dialog is wider than a 1024x768 screen, so its Ok
 * button cannot be reached.
 *
 * WHY IT IS STILL THERE AND NOT IN A TOOLBAR. Re-measured on 02.09.2026 in the running program
 * (`2026-09-02-merker-leiste.md`), because the status bar had turned out unreadable at 1024 px:
 *
 *  * The CHART toolbar cannot carry it. At 1024 px BOTH of its halves already show the overflow
 *    chevron, and what stands behind that chevron today is `Basispläne …` and the comparison
 *    dropdown — photographed. At 1920 px the right-hand half still overflows. Anything added
 *    there lands in a popup nobody opens, and it would only ever be visible in the Gantt tab.
 *  * The MAIN toolbar has no forced minimum width, which is its one real advantage, but it has
 *    only about 158 px of slack at a 1024 px window: measured, its own items start dropping into
 *    the overflow between 861 and 870 px of window width. The shortened message needs about
 *    224 px for its first statement alone. Putting it there pushes the search box off the
 *    toolbar on exactly the screen this fork is used on — a working control of the original,
 *    traded for one of ours. Not worth it.
 *
 * So the message stays, and what was fixed is what could be fixed without touching a rule that
 * belongs to the original: the texts became SIGNALS and the sentences moved into tooltips. That
 * lifts the window width needed to read the whole message from 2400 px to about 1790, and it
 * gives the 1024 px case the first route it has ever had to the reason — see [kurzhinweis].
 *
 * WHAT IT LOOKS LIKE WHEN THERE IS NOTHING TO SAY: exactly like today. [node] is both invisible
 * and UNMANAGED when the mark is clear, so it takes no width at all and the status bar keeps the
 * layout it has now. The growing spacer stays where it is, behind the message, so the notification
 * buttons are hard right in both states.
 *
 * THE BUTTON RUNS THE MENU ITEM, it does not repeat it. [runLevelling] is meant to be a call into
 * the very same `LevellingAction` instance that hangs in the resources menu — one path, one set of
 * questions before writing, one undo step. Copying its body here would be a second levelling that
 * would drift from the first.
 *
 * THREADING. The mark is set from task and resource events, which arrive on the Swing event
 * thread; this node lives on the JavaFX thread. [FXUtil.runLater] does the hop and runs inline
 * when it is already on the right thread, so nothing is queued needlessly. The button goes the
 * other way: `LevellingAction` is a Swing action that opens Swing dialogs, so [runLevelling] has
 * to be given something that gets itself onto the Swing thread — this class does not assume it.
 *
 * THE SECOND STATEMENT, "tasks are not in any baseline", LIVES INSIDE THIS ONE. It has no occasion
 * of its own to appear: [baselineLabel] and [baselineButton] are children of [node], and [node] is
 * invisible and unmanaged while the mark is clear. In a fresh, untouched project one therefore sees
 * nothing at all -- not because a rule says so but because there is nowhere for it to show.
 *
 * AND IT IS ONLY COMPUTED ON THE EDGE. Measured on 02.09.2026 against the real plan of 276 tasks:
 * one pass over the baselines costs 2.3 ms with one baseline and 29.0 ms with fifteen, essentially
 * all of it `GanttPreviousState.load()` re-parsing temporary files, and there is no cache anywhere.
 * Asking on every task event would pay that on every keystroke. The mark is a LATCH -- it changes
 * value at most once between two levelling runs, and [LevellingStaleness] only calls its listeners
 * on a change -- so asking when the message switches itself on costs the pass once per cycle.
 *
 * WHAT THAT COSTS IN TRUTH, said plainly: a task added while the message is ALREADY on does not
 * update the second statement until the next levelling run clears the mark. The statement is then
 * too small, never too large, and it corrects itself at the next cycle. The other direction --
 * recomputing on `taskAdded` -- is rejected on an EXTRAPOLATION from the measured pass, not on a
 * measurement of its own: opening the real plan fires 276 of those events, and 276 passes at the
 * measured 2.3 / 29.0 ms is 0.6 s with one baseline and 8 s with fifteen, spent before the user
 * has done anything at all.
 */
class LevellingStalenessBar(
  private val staleness: LevellingStaleness,
  private val runLevelling: () -> Unit,
  /**
   * The expensive question, asked only on the edge. Defaults to "nothing to say", so a caller that
   * does not want the second statement gets exactly the display that was there before it existed.
   */
  private val baselineGapOf: () -> BaselineGap = { BaselineGap.Covered },
  /**
   * Runs the supplement and calls back when it is over -- on a yes, on a no and on nothing-to-do
   * alike. The callback is what makes the message disappear after the button has settled it; one
   * that only arrived on success would leave it standing after the user declined.
   */
  private val runCatchUp: (onDone: () -> Unit) -> Unit = { it() }
) {
  val label: Label = Label(forkText("fork.staleness.message")).also {
    it.tooltip = kurzhinweis(forkText("fork.staleness.message.tooltip"))
  }

  val button: Button = Button(forkText("fork.staleness.button")).also {
    it.setOnAction { runLevelling() }
    // The button gets its OWN hint, not a copy of the label's: what it has to explain is what
    // pressing it would do, which is a question a button raises and a label does not.
    it.tooltip = kurzhinweis(forkText("fork.staleness.button.tooltip"))
  }

  /**
   * The second statement. Its text depends on the state: with baselines present it says how many
   * tasks are in none of them; with NO baseline at all it says just that, and [baselineButton]
   * stays away.
   */
  val baselineLabel: Label = Label(forkText("fork.baseline.missing.none")).also {
    // A little air, so the two statements do not read as one sentence.
    it.padding = Insets(0.0, 0.0, 0.0, 8.0)
    // The hint has to FOLLOW the text: this label says two different things -- "n tasks with no
    // baseline" while baselines exist, "no baseline" while none does -- and they need different
    // explanations. One set here once would explain the wrong state half the time, so
    // [zeigeLuecke] rewrites it along with the text.
    it.tooltip = kurzhinweis(forkText("fork.baseline.missing.none.tooltip"))
  }

  /**
   * NO BUTTON WHEN THERE IS NO BASELINE, and that is Natalie's decision of 02.09.2026, point 2. In
   * that state the message is a statement: it demands nothing, so it does not become wallpaper.
   * A button would promise to settle something that only saving a baseline can settle.
   */
  val baselineButton: Button = Button(forkText("fork.baseline.missing.button")).also {
    it.setOnAction { runCatchUp { FXUtil.runLater { refreshBaselineGap() } } }
    it.tooltip = kurzhinweis(forkText("fork.baseline.missing.button.tooltip"))
  }

  /**
   * Put this in front of the spacer `Pane`, and leave the spacer's `HBox.hgrow` alone.
   *
   * DELIBERATELY NOT GROWING ITSELF. Two growing children would share the free width between them,
   * so the message would sit in a box wider than its text with the notification buttons still hard
   * right -- readable, but the gap would move with the window. Letting only the spacer grow keeps
   * the message next to the cloud lock, where it is in the same place whatever the window size.
   */
  val node: HBox = HBox(8.0, label, button, baselineLabel, baselineButton).also {
    it.alignment = Pos.CENTER_LEFT
    it.padding = Insets(0.0, 8.0, 0.0, 8.0)
  }

  private val onChange: (Boolean) -> Unit = { FXUtil.runLater { zeige(it) } }

  init {
    zeige(staleness.isStale)
    staleness.addListener(onChange)
  }

  fun detach() {
    staleness.removeListener(onChange)
  }

  private fun zeige(sichtbar: Boolean) {
    node.isVisible = sichtbar
    // AND managed, not only visible: an invisible but managed node still occupies its width, and
    // the status bar would silently get a gap in it that nobody could explain.
    node.isManaged = sichtbar
    // The expensive question ONLY on the way in. Switching off costs nothing: there is nothing to
    // ask about a message nobody can see.
    if (sichtbar) refreshBaselineGap() else zeigeLuecke(null)
  }

  /**
   * Asks the baseline question again and rebuilds the second statement from the answer.
   *
   * Public because the supplement's callback comes back through it: after the button has taken the
   * missing tasks in, the statement has to go away by itself.
   */
  fun refreshBaselineGap() {
    zeigeLuecke(if (node.isVisible) baselineGapOf() else null)
  }

  /** null means "do not even ask" -- the message as a whole is off. */
  private fun zeigeLuecke(gap: BaselineGap?) {
    val text: String? = when (gap) {
      null, is BaselineGap.Covered -> null
      is BaselineGap.NoBaseline -> forkText("fork.baseline.missing.none")
      // The bundle carries the singular in a MessageFormat choice, the way the levelling messages
      // beside it do -- one key, both numbers.
      is BaselineGap.Missing -> forkText("fork.baseline.missing", gap.count)
    }
    val hinweis: String? = when (gap) {
      null, is BaselineGap.Covered -> null
      is BaselineGap.NoBaseline -> forkText("fork.baseline.missing.none.tooltip")
      is BaselineGap.Missing -> forkText("fork.baseline.missing.tooltip", gap.count)
    }
    if (text != null) {
      baselineLabel.text = text
    }
    // Rewritten in step with the text, never left over from the previous state. The last hint
    // stays put while the statement is off, for the same reason the last text does: nobody can
    // read either of them then.
    if (hinweis != null) {
      baselineLabel.tooltip.text = hinweis
    }
    baselineLabel.isVisible = text != null
    baselineLabel.isManaged = text != null
    // Same reasoning as for the whole box: invisible is not enough, an unmanaged node is what
    // keeps the width out of the status bar.
    val mitKnopf = gap is BaselineGap.Missing
    baselineButton.isVisible = mitKnopf
    baselineButton.isManaged = mitKnopf
  }

  /**
   * A hint that WRAPS. The explanations are whole sentences, and an unwrapped tooltip is laid out
   * as a single line: on the 1024 px screen this fork is built for, the sentence that was cut off
   * in the status bar would be cut off again in the very thing meant to make it readable.
   *
   * [HINWEIS_BREITE] is deliberately narrower than that screen rather than equal to it, so that a
   * hint opening near the right edge still has room to fall inwards.
   */
  private fun kurzhinweis(text: String): Tooltip = Tooltip(text).also {
    it.isWrapText = true
    it.maxWidth = HINWEIS_BREITE
    it.prefWidth = HINWEIS_BREITE
  }
}

/** See [LevellingStalenessBar.kurzhinweis]. */
private const val HINWEIS_BREITE = 360.0
