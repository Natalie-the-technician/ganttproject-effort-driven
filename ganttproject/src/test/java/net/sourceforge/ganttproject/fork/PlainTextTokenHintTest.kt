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

import javafx.scene.Node
import javafx.scene.control.Labeled
import javafx.scene.layout.VBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.sourceforge.ganttproject.GanttOptions
import net.sourceforge.ganttproject.timetracking.PlainTextTokenWarning
import net.sourceforge.ganttproject.timetracking.encodeTokenMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * THE HINT THAT SAYS THE TOKEN IS BEING KEPT IN THE CLEAR.
 *
 * Where a platform key store answers, the Toggl token goes into it and the settings file holds a
 * reference. Where none answers, the token is written into `~/.ganttproject` UNENCRYPTED — a
 * decision taken on 09.09.2026 and kept, because a token, unlike a password, cannot be typed again
 * from memory. What this file guards is the other half of that decision: that somebody is told.
 *
 * ═══ THE SECOND ASSERTION IS THE IMPORTANT ONE ═══
 *
 * `the hint appears when there is no key store` is the obvious half. `and it stays away when there
 * is one` is the half that decides whether the hint is worth anything at all: a warning that comes
 * on every machine is a warning that gets read once and skipped for ever after, and it would be
 * untrue on a machine where the token really is in the keyring.
 *
 * ═══ WHY THE BOX IS BUILT AND NOT THE WHOLE PANEL ═══
 *
 * `MainPropertiesPanel.togglTokenHint()` asks two questions — `SecretStore.isAvailable` and
 * `GanttOptions.getOptionsFile()` — and hands the answers to `togglTokenHintBox`. This machine can
 * only ever answer the first one in one way; the box takes it as a parameter so that BOTH machines
 * can be measured here. It is the same function the running program builds its hint with, not a
 * copy of it: a copy would prove that the copy works.
 *
 * What is therefore NOT proven here, and is proven on screen instead (report of 09.09.2026): that
 * the panel really passes `SecretStore.isAvailable` and not `true`. Two screenshots, one machine
 * with a running gnome-keyring and one without.
 */
class PlainTextTokenHintTest {

  private val settingsFile = "/home/natalie/.ganttproject"

  /** Real JavaFX nodes on the real JavaFX thread — the pattern of [HomeOfficePanelTest]. */
  private fun hintBox(secretStoreAvailable: Boolean): VBox = runBlocking {
    withContext(Dispatchers.JavaFx) { togglTokenHintBox(secretStoreAvailable, settingsFile) }
  }

  private fun textsOf(box: VBox): List<String> = runBlocking {
    withContext(Dispatchers.JavaFx) { box.children.map(::textOf) }
  }

  private fun textOf(node: Node): String = (node as? Labeled)?.text.orEmpty()

  /**
   * The English wording, read straight out of the bundle rather than repeated here. Repeating it
   * would make this file the second place the sentence lives, and the two would drift apart.
   */
  private fun warningText(locale: Locale): String =
    requireNotNull(ForkI18n.textOrNull("fork.toggl.token.plaintext", locale, settingsFile)) {
      "fork.toggl.token.plaintext is missing from the ${locale.language} bundle"
    }

  @Test
  fun `the hint appears when there is no key store`() {
    val texts = textsOf(hintBox(secretStoreAvailable = false))

    assertTrue(texts.any { it.contains("unencrypted") || it.contains("unverschlüsselt") },
      "no warning under the token field, although nothing can be kept safe here: $texts")
    assertEquals(3, texts.size, "warning, hint, link — in that order: $texts")
  }

  /**
   * THE ONE THAT MATTERS. On a machine with a keyring the token is IN the keyring, and a line
   * saying it lies in the clear would be false as well as noisy.
   */
  @Test
  fun `and it stays away when there is one`() {
    val texts = textsOf(hintBox(secretStoreAvailable = true))

    assertEquals(2, texts.size, "with a key store there is nothing to warn about: $texts")
    assertFalse(texts.any { it.contains("unencrypted") || it.contains("unverschlüsselt") },
      "the warning appeared on a machine that has a key store: $texts")
  }

  /** The warning is the FIRST line: it may keep the field empty, so it comes before how to fill it. */
  @Test
  fun `the warning stands directly under the field, above the how-to`() {
    val texts = textsOf(hintBox(secretStoreAvailable = false))

    assertTrue(textsOf(hintBox(secretStoreAvailable = true)).first() == texts[1],
      "the second line without a store has to be the first line with one: $texts")
  }

  /** It names the file. "Unencrypted" without a where is advice nobody can act on. */
  @Test
  fun `it names the file the token goes into`() {
    val texts = textsOf(hintBox(secretStoreAvailable = false))

    assertTrue(texts.first().contains(settingsFile),
      "the warning does not say which file: ${texts.first()}")
  }

  /**
   * IT NEVER SHOWS THE TOKEN. The structural reason is that [togglTokenHintBox] is not given one;
   * this measures the consequence, so that adding one later goes red rather than unnoticed.
   */
  @Test
  fun `it never shows the token`() {
    val token = "1234567890abcdef1234567890abcdef"
    val texts = textsOf(hintBox(secretStoreAvailable = false))

    texts.forEach { text ->
      assertFalse(text.contains(token), "a token turned up in the hint: $text")
      // Not even a piece of it. A "masked" token is still eight characters of a secret.
      assertFalse(text.contains(token.take(8)), "part of a token turned up in the hint: $text")
    }
  }

  /**
   * Both wordings exist, and the German one is really German rather than the English text falling
   * through. `fork.toggl.token.plaintext` has to name the file in both.
   */
  @Test
  fun `both languages say it, and both name the file`() {
    val english = warningText(Locale.ENGLISH)
    val german = warningText(Locale.GERMANY)

    assertNotNull(english)
    assertTrue(english.contains(settingsFile), english)
    assertTrue(german.contains(settingsFile), german)
    assertTrue(german.contains("unverschlüsselt"), "the german text fell through to english: $german")
    assertTrue(english.contains("unencrypted"), english)
  }

  /**
   * ═══ THE WIRING: THE HINT ASKS THE REAL KEY STORE, NOT A CONSTANT ═══
   *
   * Everything above builds the box from a boolean handed in. This one builds it the way the
   * running program does — [togglTokenHint], which asks `SecretStore.isAvailable` itself — and
   * checks that the answer and the hint agree. Put `true` or `false` in place of the probe and this
   * goes red.
   *
   * WHAT IT COVERS ON WHICH MACHINE, and it takes both to cover both mistakes: on a machine
   * WITHOUT a keyring (the host of 09.09.2026) a hard-wired `true` shows up here; on one WITH a
   * keyring (the `computer-use` container) a hard-wired `false` does. Neither run alone is enough,
   * which is why this file is run in both.
   */
  @Test
  fun `the hint asks the real key store and not a constant`() {
    val texts = runBlocking {
      withContext(Dispatchers.JavaFx) { togglTokenHint().children.map(::textOf) }
    }
    val warned = texts.any { it.contains("unencrypted") || it.contains("unverschlüsselt") }

    assertEquals(!SecretStore.isAvailable, warned,
      "SecretStore.isAvailable is ${SecretStore.isAvailable} (backend ${SecretStore.backendName}), "
        + "so the warning should ${if (SecretStore.isAvailable) "not " else ""}be there: $texts")
  }

  /**
   * And the file it names is the file that is really written, asked of `GanttOptions` rather than
   * spelled out. A hard-wired "~/.ganttproject" would be right on most machines and wrong on the
   * ones where it matters — another user account, a different `user.home`, a packaged build.
   */
  @Test
  fun `the file it names is the one GanttOptions writes`() {
    if (SecretStore.isAvailable) {
      return
    }
    val texts = runBlocking {
      withContext(Dispatchers.JavaFx) { togglTokenHint().children.map(::textOf) }
    }

    assertTrue(texts.first().contains(GanttOptions.getOptionsFile().path),
      "the warning names something other than the settings file "
        + "${GanttOptions.getOptionsFile().path}: ${texts.first()}")
  }

  /**
   * ═══ THE LOG LINE, WHICH IS THE OTHER PLACE A TOKEN COULD ESCAPE ═══
   *
   * `TokenStore` says once per run that it is writing a token in the clear. What reaches the logger
   * is captured here, not a function that builds the sentence: somebody appending the token at the
   * call site would leave a test of the builder green.
   *
   * RUNS ONLY WHERE THERE IS NO KEY STORE, because that is the only place the line is written at
   * all. On the host of 09.09.2026 that is the case; in the `computer-use` container with a running
   * gnome-keyring it is not, and there this method measures nothing. Stated rather than hidden.
   */
  @Test
  fun `the log line about the plain text token does not carry the token`() {
    if (SecretStore.isAvailable) {
      return
    }
    val token = "1234567890abcdef1234567890abcdef"
    val said = mutableListOf<String>()
    val previousSink = PlainTextTokenWarning.sink
    val previouslySaid = PlainTextTokenWarning.alreadySaid
    try {
      PlainTextTokenWarning.sink = { said.add(it) }
      PlainTextTokenWarning.alreadySaid = false

      val stored = encodeTokenMap(mapOf("mail=natalie@example.invalid" to token))

      // Precondition: without it this would also pass on a run in which nothing was said at all.
      assertEquals(1, said.size, "nothing was written to the log although the token went in in the clear")
      assertTrue(stored.contains(token), "precondition: without a store the token really is in the clear")
      said.forEach { line ->
        assertFalse(line.contains(token), "the token stands in the log: $line")
        assertFalse(line.contains(token.take(8)), "part of the token stands in the log: $line")
      }
    } finally {
      PlainTextTokenWarning.sink = previousSink
      PlainTextTokenWarning.alreadySaid = previouslySaid
    }
  }

  /**
   * And it names the setting it is talking about correctly. Until 09.09.2026 it said
   * `toggl.tokens`; the setting is `toggl.resourceTokens` — group id plus option id, as
   * `GanttOptions.java:889` composes them. Advice that names the wrong setting sends whoever
   * follows it looking for something that is not there.
   */
  @Test
  fun `the log line names the setting that really exists`() {
    if (SecretStore.isAvailable) {
      return
    }
    val said = mutableListOf<String>()
    val previousSink = PlainTextTokenWarning.sink
    val previouslySaid = PlainTextTokenWarning.alreadySaid
    try {
      PlainTextTokenWarning.sink = { said.add(it) }
      PlainTextTokenWarning.alreadySaid = false

      encodeTokenMap(mapOf("mail=natalie@example.invalid" to "1234567890abcdef1234567890abcdef"))

      assertEquals(1, said.size)
      assertTrue(said.first().contains("toggl.resourceTokens"),
        "the log names a setting that does not exist: ${said.first()}")
    } finally {
      PlainTextTokenWarning.sink = previousSink
      PlainTextTokenWarning.alreadySaid = previouslySaid
    }
  }
}
