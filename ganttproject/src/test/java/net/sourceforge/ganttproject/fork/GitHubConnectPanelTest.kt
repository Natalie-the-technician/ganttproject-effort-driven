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
import javafx.scene.control.TextInputControl
import javafx.scene.layout.VBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.sourceforge.ganttproject.timetracking.OptionGitHubTokenStorage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * [fork change] THE WINDOW THAT SHOWS THE CODE.
 *
 * Everything the sign-in does is measured in `GitHubDeviceFlowTest`; this is about the one thing
 * that only shows on a screen — whether a person can actually get eight characters from here into
 * a browser that may be on another machine.
 *
 * Three claims are worth holding in place, and each of them is easy to lose in a refactor that
 * looks harmless:
 *
 *  1. THE CODE IS SELECTABLE. A JavaFX `Label` cannot be selected, so a change from `TextField` to
 *     `Label` would look identical on screen and quietly remove copying and the screen reader.
 *  2. THE CODE IS BIG AND MONOSPACE. The alphabet GitHub uses contains characters a proportional
 *     face renders nearly alike; a wrong transcription earns `incorrect_device_code` and no hint.
 *  3. THE WARNING ABOUT THE SETTINGS FILE APPEARS ONLY WHERE IT IS TRUE. A warning on every
 *     machine is one nobody reads, and it would be false where the sign-in really is in a keyring.
 *
 * The box is built and not the whole window, for the same reason as [PlainTextTokenHintTest]:
 * `gitHubConnectBox(state)` asks `SecretStore.isAvailable` and `GanttOptions.getOptionsFile()`, and
 * this machine can only ever answer the first one one way. The three-argument form takes both as
 * parameters so BOTH machines can be measured here — and it is the same function the running
 * program builds its box with, not a copy of it.
 */
class GitHubConnectPanelTest {

  private val settingsFile = "/home/natalie/.ganttproject"
  private val code = "WDJB-MJHT"
  private val address = "https://github.com/login/device"

  /** Real JavaFX nodes on the real JavaFX thread — the pattern of [PlainTextTokenHintTest]. */
  private fun box(state: GitHubConnectUi, secretStoreAvailable: Boolean = true): VBox = runBlocking {
    withContext(Dispatchers.JavaFx) { gitHubConnectBox(state, secretStoreAvailable, settingsFile) }
  }

  private fun nodes(box: VBox): List<Node> = runBlocking {
    withContext(Dispatchers.JavaFx) { box.children.toList() }
  }

  private fun textOf(node: Node): String = when (node) {
    is Labeled -> node.text.orEmpty()
    is TextInputControl -> node.text.orEmpty()
    else -> ""
  }

  private fun texts(box: VBox): List<String> = nodes(box).map(::textOf)

  private val waiting = GitHubConnectUi(userCode = code, verificationUri = address)

  // --- the code ------------------------------------------------------------

  @Test
  fun `the code is on the screen, character for character`() {
    val texts = texts(box(waiting))

    assertTrue(texts.any { it == code }, "the code is not shown as itself: $texts")
    assertTrue(texts.any { it == address }, "the address is not shown: $texts")
  }

  /**
   * THE ONE A REFACTOR LOSES SILENTLY. A `Label` looks the same and cannot be selected at all.
   */
  @Test
  fun `and it can be selected and copied, but not typed into`() {
    val field = nodes(box(waiting)).filterIsInstance<TextInputControl>().firstOrNull { it.text == code }

    assertNotNull(field, "the code is not in a control whose text can be selected")
    assertFalse(field!!.isEditable, "the code can be typed over")
    // Not disabled: a disabled field cannot be selected either, which would undo the point above.
    assertFalse(field.isDisable, "a disabled field cannot be selected, so it cannot be copied")
  }

  @Test
  fun `and it is monospace and clearly larger than ordinary text`() {
    val field = nodes(box(waiting)).filterIsInstance<TextInputControl>().first { it.text == code }
    val style = field.style

    assertTrue(style.contains("monospace"), "0 and O are told apart by the face, not by hope: $style")
    val size = Regex("-fx-font-size:\\s*(\\d+)").find(style)?.groupValues?.get(1)?.toInt()
    assertNotNull(size, "no font size is set on the code: $style")
    // GanttProject's ordinary text is about 12-13 px. Twenty is the smallest that is unmistakably
    // "read this from over there" rather than "slightly emphasised".
    assertTrue(size!! >= 20, "the code is not big enough to read across a desk: $size px")
  }

  /**
   * The sentence that says the browser may be elsewhere. It is the reason for all of the above, and
   * without it a person sitting at a desktop with no browser has no idea what to do next.
   */
  @Test
  fun `and the screen says the browser may be on another device`() {
    val texts = texts(box(waiting))
    val expected = requireNotNull(ForkI18n.textOrNull("fork.github.otherDevice", Locale.ENGLISH))

    assertTrue(texts.any { it == expected }, "nothing says the code can be typed elsewhere: $texts")
  }

  // --- the warning about the settings file ---------------------------------

  @Test
  fun `the plain-text warning appears where there is no key store`() {
    val texts = texts(box(waiting, secretStoreAvailable = false))

    assertTrue(
      texts.any { it.contains("unencrypted") || it.contains("unverschlüsselt") },
      "no warning although nothing can be kept safe here: $texts"
    )
    assertTrue(texts.any { it.contains(settingsFile) }, "the warning does not name the file: $texts")
    assertTrue(
      texts.any { it.contains(OptionGitHubTokenStorage.GITHUB_TOKENS_SETTING) },
      "the warning does not name the setting: $texts"
    )
  }

  /**
   * THE ONE THAT MATTERS. On a machine with a keyring the sign-in IS in the keyring, and a line
   * saying it lies in the clear would be false as well as noisy.
   */
  @Test
  fun `and it stays away where there is one`() {
    val texts = texts(box(waiting, secretStoreAvailable = true))

    assertFalse(
      texts.any { it.contains("unencrypted") || it.contains("unverschlüsselt") },
      "the warning appeared on a machine that has a key store: $texts"
    )
  }

  // --- the states without a code -------------------------------------------

  @Test
  fun `before connecting it says what connecting is for`() {
    val texts = texts(box(GitHubConnectUi()))
    val expected = requireNotNull(ForkI18n.textOrNull("fork.github.intro", Locale.ENGLISH))

    assertTrue(texts.any { it == expected }, "$texts")
    assertTrue(texts.none { it == code }, "a code is shown although none was asked for: $texts")
  }

  @Test
  fun `once connected it says so instead`() {
    val texts = texts(box(GitHubConnectUi(connected = true)))
    val expected = requireNotNull(ForkI18n.textOrNull("fork.github.connected", Locale.ENGLISH))

    assertTrue(texts.any { it == expected }, "$texts")
  }

  @Test
  fun `a message is shown as it stands`() {
    val texts = texts(box(GitHubConnectUi(message = "GitHub could not be reached: no route to host.")))

    assertTrue(texts.any { it == "GitHub could not be reached: no route to host." }, "$texts")
  }

  /**
   * Both bundles carry every key this window uses. A missing German key shows the bare id on
   * screen, which is not a crash and therefore not noticed until somebody sees `fork.github.step`
   * where a sentence should be.
   */
  @Test
  fun `every label of this window exists in both languages`() {
    val keys = listOf(
      "fork.github.menu", "fork.github.title", "fork.github.intro", "fork.github.step",
      "fork.github.otherDevice", "fork.github.connect", "fork.github.disconnect",
      "fork.github.connected", "fork.github.waiting", "fork.github.ok", "fork.github.denied",
      "fork.github.expired", "fork.github.failed", "fork.github.plaintext"
    )
    for (key in keys) {
      for (locale in listOf(Locale.ENGLISH, Locale.GERMAN)) {
        assertNotNull(
          ForkI18n.textOrNull(key, locale, "arg0", "arg1"),
          "$key is missing from the ${locale.language} bundle"
        )
      }
    }
    // And German is really German, not the English text falling through. ForkI18n answers with the
    // English one for a key the German file does not carry, so a missing key looks like a present
    // one unless the two are compared.
    for (key in keys) {
      assertNotEqualsUnlessDeliberate(key)
    }
  }

  private fun assertNotEqualsUnlessDeliberate(key: String) {
    val english = ForkI18n.textOrNull(key, Locale.ENGLISH, "arg0", "arg1")
    val german = ForkI18n.textOrNull(key, Locale.GERMAN, "arg0", "arg1")
    assertFalse(english == german, "$key is not translated: the German bundle answers with the English text")
  }
}
