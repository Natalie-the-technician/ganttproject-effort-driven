/*
Copyright 2026 Noctuvo

NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.

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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Tests the fork's own text bundle.
 *
 * The point of these is not that "Aufwand" is spelled correctly — that is a sense question no test
 * can answer. The point is that the bundle is found, decoded and formatted correctly.
 *
 * WHAT THESE TESTS DO NOT PROVE, found by counter-test and worth stating plainly: they cannot tell
 * a production-correct resource path from a wrong one. `ganttproject/build.gradle` declares BOTH
 * `src/main/resources/resources` (as a classpath entry, line 44) and `src/main/resources` (as a
 * resource root, line 114), so under test the bundle answers to `/language/fork/…` AND to
 * `/resources/language/fork/…`. In the packaged plugin only the first works, because `plugin.xml`
 * makes `resources/` the library root. Setting the path constant to the second value therefore
 * leaves every test here green and breaks the built application.
 *
 * That claim needs the built artifact, so it is checked there instead — see the packaging note in
 * CLAUDE-NOTES.md.
 */
class ForkI18nTest {

  private val german = Locale.GERMANY
  private val english = Locale.US

  /**
   * The bundle is on the classpath. Deliberately the first assertion: if this fails, every other
   * test in this file fails too, and the reason is the file, not the translation.
   *
   * Note the limit stated above — this says the file is reachable under test, not that it is
   * packaged where the running application looks for it.
   */
  @Test
  fun `the fork bundle is found on the classpath`() {
    assertNotNull(ForkI18n::class.java.getResourceAsStream("/language/fork/i18n.properties"),
      "the fork bundle is not on the classpath — check the resources root in build.gradle")
  }

  @Test
  fun `german texts come from the german file`() {
    assertEquals("Ist-Stunden", ForkI18n.textOrNull("fork.effort.actualHours", german))
    assertEquals("Zeiterfassung", ForkI18n.textOrNull("fork.toggl.section", german))
  }

  @Test
  fun `english texts come from the default file`() {
    assertEquals("Actual hours", ForkI18n.textOrNull("fork.effort.actualHours", english))
  }

  /**
   * A language this fork has not been translated into must fall back to English rather than show
   * the key. There is no French file, and there is not meant to be one.
   */
  @Test
  fun `an untranslated language falls back to english`() {
    assertEquals("Actual hours", ForkI18n.textOrNull("fork.effort.actualHours", Locale.FRANCE))
  }

  /**
   * The stock loader only ever looks for `lang_COUNTRY`. A plain `de` — which is what a German
   * system without a region set reports — would find nothing there. Here it must work.
   */
  @Test
  fun `a language without a country still finds the translation`() {
    assertEquals("Ist-Stunden", ForkI18n.textOrNull("fork.effort.actualHours", Locale("de")))
  }

  /**
   * `Properties.load(InputStream)` assumes ISO-8859-1. Read that way, the German file yields
   * "prÃ¼fen" — text that is wrong but looks like text, so it would go unnoticed until it reached
   * a screenshot. Hence an explicit assertion on a word with an umlaut.
   */
  @Test
  fun `umlauts survive being read from the file`() {
    assertEquals("Toggl-Verbindung prüfen", ForkI18n.textOrNull("fork.toggl.checkConnection", german))
  }

  /** An undefined key is a normal state — the caller decides what to do, so: null, not an error. */
  @Test
  fun `an unknown key yields null`() {
    assertNull(ForkI18n.textOrNull("fork.gibt.es.nicht", german))
  }

  /**
   * `forkText` must always return something printable. The key itself is deliberate: an empty
   * label looks like an ordinary blank and hides the mistake, a visible "fork.gibt.es.nicht" does
   * not.
   */
  @Test
  fun `an unknown key shows up as itself rather than as a blank`() {
    assertEquals("fork.gibt.es.nicht", forkText("fork.gibt.es.nicht"))
  }

  /**
   * Every key this fork's interface asks for must exist in the English file. Without this, adding
   * a German label and forgetting the English one produces a bare key on any non-German system —
   * invisible to anyone developing in German.
   */
  @Test
  fun `every german key has an english counterpart`() {
    val germanKeys = keysOf("/language/fork/i18n_de.properties")
    val englishKeys = keysOf("/language/fork/i18n.properties")
    val missing = germanKeys - englishKeys

    // Guard: if the files could not be read, both sets are empty and the check above is vacuous.
    assertNotNull(germanKeys.firstOrNull(), "the german file was not read, so this test proves nothing")
    assertEquals(emptySet<String>(), missing,
      "these keys exist in german only and would show as bare keys elsewhere")
  }

  /**
   * The display name of a custom property is its COLUMN HEADER, so it is translated. The id is
   * not, and everything looks the property up by that id.
   *
   * The danger this guards against: translating the id along with the name. A project saved in
   * German would then carry a column the English build cannot find, the effort would read as
   * absent, and every task would silently fall back to the default duration. Nothing would throw.
   */
  @Test
  fun `a translated column name does not stop the column being found`() {
    val manager = biz.ganttproject.customproperty.CustomColumnsManager()
    val created = net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
      .findOrCreateTaskEffort(manager)

    // The id is the technical key and must stay as it is, in every language.
    assertEquals("effort_hours", created.id)

    // Asked again, the SAME definition must come back -- not a second column beside the first.
    val foundAgain = net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
      .findOrCreateTaskEffort(manager)
    assertEquals(created.id, foundAgain.id)
    assertEquals(1, manager.definitions.count { it.id == "effort_hours" },
      "the column was created a second time instead of being found")
  }

  /**
   * The reason a split was rejected must arrive as a real sentence with the numbers filled in,
   * not as a bare key. This is the one message of the fork that carries arguments, so it is also
   * the one where a broken pattern would show up as literal "{0}" on screen.
   */
  @Test
  fun `a rejected split explains itself with its numbers`() {
    val rejected = net.sourceforge.ganttproject.timetracking.validateSplit(
      4.0,
      listOf(
        net.sourceforge.ganttproject.timetracking.SplitPart(1, 2.0),
        net.sourceforge.ganttproject.timetracking.SplitPart(2, 1.0)))
        as net.sourceforge.ganttproject.timetracking.SplitResult.Invalid

    val message = rejected.message
    assertNotEquals(rejected.reasonKey, message, "the message is still the bare key")
    // Both figures have to appear, otherwise the sentence does not say what is wrong. The decimal
    // separator depends on the language, so only the digits are asserted.
    assertTrue(Regex("3[.,]00").containsMatchIn(message), "the sum is missing from: $message")
    assertTrue(Regex("4[.,]00").containsMatchIn(message), "the total is missing from: $message")
  }

  /**
   * The result of the connection check is built from the failure KIND, never from the exception
   * message. That message is English prose written for the log; showing it would put
   * "Toggl refused the token…" into a German dialog.
   *
   * Every failure kind is walked, so a new one added to the enum without a text shows up here as
   * a bare key rather than in front of the user.
   */
  @Test
  fun `no english exception text reaches the connection check message`() {
    val developerText = "Toggl refused the token. Note that the token goes into the USERNAME field"

    net.sourceforge.ganttproject.timetracking.TogglFailure.entries.forEach { failure ->
      val message = net.sourceforge.ganttproject.timetracking.connectionCheckMessage(
        net.sourceforge.ganttproject.timetracking.ConnectionCheckResult.Failed(
          "Nati", failure, developerText))

      assertFalse(message.contains("USERNAME"),
        "the english developer text reached the user for $failure: $message")
      assertFalse(message.startsWith("fork."), "no text is defined for $failure: $message")
    }
  }

  /**
   * The successful case has to name the person and the count -- a bare "it works" would not tell
   * the user whether the right account was reached.
   */
  @Test
  fun `the successful check names the person and the count`() {
    val message = net.sourceforge.ganttproject.timetracking.connectionCheckMessage(
      net.sourceforge.ganttproject.timetracking.ConnectionCheckResult.Ok("Nati", 7, listOf()))

    assertTrue(message.contains("Nati"), "the person is missing from: $message")
    assertTrue(message.contains("7"), "the count is missing from: $message")
  }

  /**
   * Entries the code could not read must be mentioned. On a first run against the real service
   * that is the interesting part, and swallowing it would hide the surprise the check exists for.
   */
  @Test
  fun `unreadable entries are mentioned in the message`() {
    val quiet = net.sourceforge.ganttproject.timetracking.connectionCheckMessage(
      net.sourceforge.ganttproject.timetracking.ConnectionCheckResult.Ok("Nati", 7, listOf()))
    val noisy = net.sourceforge.ganttproject.timetracking.connectionCheckMessage(
      net.sourceforge.ganttproject.timetracking.ConnectionCheckResult.Ok(
        "Nati", 7, listOf("entry #3 skipped: missing start")))

    assertNotEquals(quiet, noisy, "the unreadable entry left no trace in the message")
    assertTrue(noisy.contains("entry #3"), "the entry is not named in: $noisy")
  }

  /**
   * Button labels have to stay short.
   *
   * Found on screen, not by reasoning: "Neuen behalten (bisherigen verwerfen)" was rendered as
   * "Neuen behalten (bisherigen verwerf…" — the buttons have a fixed width and truncate, even with
   * the window maximised. The one thing that must not be cut off is what the button costs you.
   *
   * The consequence now lives in the dialog text; the labels only name the action. 24 characters
   * is well under the ~36 at which truncation was observed, and covers translations being longer
   * than the German original.
   */
  @Test
  fun `button labels are short enough not to be cut off`() {
    val labels = listOf("fork.toggl.collision.overwrite", "fork.toggl.collision.discard")

    listOf(Locale.GERMANY, Locale.US).forEach { locale ->
      labels.forEach { key ->
        val label = ForkI18n.textOrNull(key, locale)
        assertNotNull(label, "no text for $key in $locale")
        assertTrue(label!!.length <= 24,
          "the label would be truncated on the button and the consequence unreadable: " +
            "$key in $locale is ${label.length} characters: $label")
      }
    }
  }

  /**
   * Whatever the buttons no longer say has to be said in the dialog text instead, otherwise
   * shortening them quietly removed the very information the dialog exists for.
   */
  @Test
  fun `the dialog text still names what each choice costs`() {
    val text = ForkI18n.textOrNull("fork.toggl.collision.choice", Locale.GERMANY)

    assertNotNull(text, "the explanation of the two choices is missing")
    assertTrue(text!!.contains("Neuen behalten"), "the first choice is not explained: $text")
    assertTrue(text.contains("Bisherigen behalten"), "the second choice is not explained: $text")
    assertTrue(text.contains("verwirft"), "the text does not say that something is discarded: $text")
  }

  private fun keysOf(path: String): Set<String> =
    java.util.Properties().also { properties ->
      ForkI18n::class.java.getResourceAsStream(path)?.use {
        it.reader(Charsets.UTF_8).use(properties::load)
      }
    }.stringPropertyNames()
}
