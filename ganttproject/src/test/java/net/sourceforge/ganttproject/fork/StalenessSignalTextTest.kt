/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
The message in the status bar has to read as a SIGNAL, not as a sentence.

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

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * THE TEXTS OF THE STATUS BAR MESSAGE, MEASURED IN CHARACTERS.
 *
 * WHY A LENGTH IS WORTH A TEST. Measured on the running program on 02.09.2026
 * (`2026-09-02-merker-bildschirm.md`, section 3.2): the first statement, a full sentence of 68
 * characters, is 453 px wide, and the status bar hands the message its preferred width only from
 * a window of 1890 px onwards — on a 1920 px screen the message with both statements was still
 * cut off. The status bar has 50 em of forced minimum width twice over
 * (`StatusBar.css`, the `.statusbar` class on the cloud panel and on the notification buttons),
 * so roughly 1300 px are gone before the message gets a single pixel. Every character the
 * message carries is therefore paid for at the far end of a very expensive line.
 *
 * A CHARACTER COUNT IS NOT A PIXEL COUNT, and this test does not pretend otherwise. It is a
 * ceiling that keeps a sentence from creeping back in; the pixel widths belong in the report,
 * where they were measured on the screen. [SIGNALLAENGE] is set at 30 because the longest form
 * that survives the shortening — the second statement with a two-digit count — needs 26.
 *
 * WHAT MUST NOT BE LOST is the reason. Each signal text is therefore required to have a
 * `.tooltip` sibling in both bundles that says WHY, and that sibling has to be longer than the
 * signal itself — a tooltip repeating the label would be a tooltip in name only.
 */
class StalenessSignalTextTest {

  private val german = Locale.GERMANY
  private val english = Locale.US

  /** The ceiling for anything that stands in the bar itself. See the class comment. */
  private val SIGNALLAENGE = 30

  /**
   * The keys that stand in the bar, with the arguments they are formatted with. A pattern that
   * takes a count is checked with 1 and with 99, because the plural form is the longer one and
   * a two-digit count is what a real plan produces.
   */
  private val signale: List<Pair<String, Array<Any>>> = listOf(
    "fork.staleness.message" to emptyArray(),
    "fork.staleness.button" to emptyArray(),
    "fork.baseline.missing" to arrayOf<Any>(1),
    "fork.baseline.missing" to arrayOf<Any>(99),
    "fork.baseline.missing.button" to emptyArray(),
    "fork.baseline.missing.none" to emptyArray()
  )

  /**
   * RED against 533832eb2, both languages at once:
   *
   *   org.opentest4j.AssertionFailedError: fork.staleness.message in de_DE is a sentence, not a
   *   signal: 68 characters, at most 30 allowed -- "Der Plan hat sich geändert — die Kapazität
   *   muss neu verteilt werden." ==> expected: <true> but was: <false>
   */
  @Test
  fun `every text that stands in the bar is short enough to be a signal`() {
    for (locale in listOf(german, english)) {
      for ((key, args) in signale) {
        val text = ForkI18n.textOrNull(key, locale, *args)
        assertNotNull(text, "$key is missing in $locale")
        assertTrue(text!!.length <= SIGNALLAENGE,
          "$key in $locale is a sentence, not a signal: ${text.length} characters, " +
            "at most $SIGNALLAENGE allowed -- \"$text\"")
      }
    }
  }

  /**
   * THE REASON HAS TO SURVIVE THE SHORTENING. Measured on the screen on 02.09.2026, section 3.4:
   * there is no tooltip today, on neither the label nor the button, so the truncated text cannot
   * be read back by any means at all. A short label without a tooltip would make that worse, not
   * better.
   *
   * RED against 533832eb2:
   *
   *   org.opentest4j.AssertionFailedError: fork.staleness.message.tooltip is missing in de_DE --
   *   the shortened signal has nowhere to say why ==> expected: not <null>
   */
  @Test
  fun `every signal has a tooltip that says why, in both languages`() {
    for (locale in listOf(german, english)) {
      for ((key, args) in signale.distinctBy { it.first }) {
        val hinweis = ForkI18n.textOrNull("$key.tooltip", locale, *args)
        assertNotNull(hinweis,
          "$key.tooltip is missing in $locale -- the shortened signal has nowhere to say why")
        val signal = ForkI18n.textOrNull(key, locale, *args)!!
        assertTrue(hinweis!!.length > signal.length,
          "$key.tooltip in $locale is no longer than the signal it explains: " +
            "\"$hinweis\" against \"$signal\"")
      }
    }
  }

  /**
   * The long sentence is not thrown away, it MOVES. The wording that used to stand in the bar
   * names the change and the consequence; the tooltip has to keep naming both, otherwise the
   * shortening has quietly cost information rather than moved it.
   *
   * Checked on words rather than on the whole sentence, so that the wording stays free.
   *
   * RED against 533832eb2 — it does not get as far as the words, the key is not there at all:
   *
   *   org.opentest4j.AssertionFailedError: the german tooltip of the first statement is missing
   *   ==> expected: not <null>
   */
  @Test
  fun `the tooltip of the first statement still names the change and what has to happen`() {
    val de = ForkI18n.textOrNull("fork.staleness.message.tooltip", german)
    assertNotNull(de, "the german tooltip of the first statement is missing")
    assertTrue(de!!.contains("geändert"), "the german tooltip does not mention \"geändert\"")
    assertTrue(de.contains("verteil"), "the german tooltip does not mention \"verteil\"")

    val en = ForkI18n.textOrNull("fork.staleness.message.tooltip", english)
    assertNotNull(en, "the english tooltip of the first statement is missing")
    assertTrue(en!!.contains("changed"), "the english tooltip does not mention \"changed\"")
    assertTrue(en.contains("level"), "the english tooltip does not mention \"level\"")
  }
}
