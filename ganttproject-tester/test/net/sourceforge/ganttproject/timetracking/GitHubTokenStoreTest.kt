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
package net.sourceforge.ganttproject.timetracking

import biz.ganttproject.core.option.DefaultStringOption
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [fork change] Where the GitHub sign-in is written down on this side, and what is said when it
 * cannot be protected.
 *
 * BOTH MACHINES ARE MEASURED HERE, on whichever machine this runs. That is the point of
 * [SecretKeeper]: `PlainTextTokenHintTest` has to begin two of its methods with
 * `if (SecretStore.isAvailable) return`, and on a machine with a running keyring those two measure
 * nothing at all. A fake key store that can be told to answer and told to refuse costs one
 * interface and removes that hole.
 *
 * What is therefore NOT proven here, and is stated rather than hidden: that [PlatformSecretKeeper]
 * really reaches DPAPI, libsecret or the Keychain. That is `SecretStoreTest`'s subject and it is
 * unchanged by any of this.
 */
class GitHubTokenStoreTest {

  /** A key store that can be present or absent on command. */
  private class FakeKeeper(override val isAvailable: Boolean) : SecretKeeper {
    override val backendName: String get() = if (isAvailable) "fake-keyring" else "none"

    /** What was handed to it, under which alias. */
    val kept = mutableMapOf<String, String>()

    override fun protect(alias: String, plain: String): String? {
      if (!isAvailable) return null
      kept[alias] = plain
      return "$MARKER$alias"
    }

    override fun reveal(stored: String): String =
      if (stored.startsWith(MARKER)) kept[stored.removePrefix(MARKER)] ?: stored else stored

    override fun isProtected(stored: String): Boolean = stored.startsWith(MARKER)

    companion object {
      const val MARKER = "fake:"
    }
  }

  private val tokens = GitHubTokenSet("ghu_the_access_token", 1_757_528_800L, "ghr_the_refresh_token", 1_773_397_600L)
  private val said = mutableListOf<String>()
  private var previousSink: (String) -> Unit = {}
  private var previouslySaid = false

  @BeforeEach
  fun captureTheWarning() {
    previousSink = PlainTextGitHubTokenWarning.sink
    previouslySaid = PlainTextGitHubTokenWarning.alreadySaid
    PlainTextGitHubTokenWarning.sink = { said.add(it) }
    PlainTextGitHubTokenWarning.alreadySaid = false
  }

  @AfterEach
  fun releaseTheWarning() {
    PlainTextGitHubTokenWarning.sink = previousSink
    PlainTextGitHubTokenWarning.alreadySaid = previouslySaid
  }

  private fun storageOn(keeper: FakeKeeper) = OptionGitHubTokenStorage(
    option = DefaultStringOption("githubTokens", ""),
    keeper = keeper,
    optionsFilePath = { "/home/natalie/.ganttproject" }
  )

  @Test
  fun `where a key store answers, the settings file holds no token`() {
    val keeper = FakeKeeper(isAvailable = true)
    val option = DefaultStringOption("githubTokens", "")
    val storage = OptionGitHubTokenStorage(option, keeper) { "/home/natalie/.ganttproject" }

    storage.writeTokens(tokens.encoded())

    val written = option.value.orEmpty()
    assertTrue(keeper.isProtected(written), "the raw line went into the settings file: $written")
    assertFalse(written.contains("ghu_the_access_token"), written)
    assertFalse(written.contains("ghr_the_refresh_token"), written)
    // Not even Base64-shaped: the encoded line is not encryption and must not be mistaken for it.
    assertFalse(written.contains(tokens.encoded()), written)
    assertEquals(0, said.size, "a warning was given on a machine that has a key store")
  }

  @Test
  fun `and reading it back gives the pair`() {
    val keeper = FakeKeeper(isAvailable = true)
    val storage = storageOn(keeper)

    storage.writeTokens(tokens.encoded())
    val read = decodeGitHubTokens(storage.readTokens())

    assertNotNull(read)
    assertEquals(tokens, read)
  }

  /**
   * THE OTHER MACHINE. Store anyway — and say so. That is the rule taken on 09.09.2026 for the
   * Toggl token, applied here rather than re-argued.
   */
  @Test
  fun `where none answers, it is stored anyway and it is said`() {
    val keeper = FakeKeeper(isAvailable = false)
    val option = DefaultStringOption("githubTokens", "")
    val storage = OptionGitHubTokenStorage(option, keeper) { "/home/natalie/.ganttproject" }

    storage.writeTokens(tokens.encoded())

    assertEquals(tokens.encoded(), option.value, "the connection was silently dropped instead of stored")
    assertEquals(decodeGitHubTokens(option.value), decodeGitHubTokens(storage.readTokens()))
    assertEquals(1, said.size, "nothing was said although the sign-in went in in the clear")
  }

  /**
   * And the warning is worth reading: the file, the setting, and not one character of a token.
   *
   * The setting has to be the one that really exists. `toggl.tokens` stood in the Toggl warning
   * until 09.09.2026 and the setting is `toggl.resourceTokens`; advice that names the wrong setting
   * sends whoever follows it looking for something that is not there.
   */
  @Test
  fun `the warning names the file and the setting and carries no token`() {
    val storage = storageOn(FakeKeeper(isAvailable = false))

    storage.writeTokens(tokens.encoded())

    assertEquals(1, said.size)
    val line = said.first()
    assertTrue(line.contains("/home/natalie/.ganttproject"), line)
    assertTrue(line.contains("github.githubTokens"), "the log names a setting that does not exist: $line")
    assertFalse(line.contains("ghu_the_access_token"), line)
    assertFalse(line.contains("ghr_the_refresh_token"), line)
    assertFalse(line.contains(tokens.encoded()), line)
    // Not shortened either. Eight characters of a token are eight an attacker no longer has to
    // guess, and a truncated secret in a log reads as if somebody had thought about it.
    assertFalse(line.contains("ghu_the_"), line)
  }

  @Test
  fun `it is said once per run and not once per write`() {
    val storage = storageOn(FakeKeeper(isAvailable = false))

    storage.writeTokens(tokens.encoded())
    storage.writeTokens(tokens.encoded())
    storage.writeTokens(tokens.encoded())

    assertEquals(1, said.size, "the settings file is rewritten all day; a warning per write is one nobody reads")
  }

  @Test
  fun `disconnecting empties the setting on either machine`() {
    for (available in listOf(true, false)) {
      val option = DefaultStringOption("githubTokens", "")
      val storage = OptionGitHubTokenStorage(option, FakeKeeper(available)) { "/tmp/settings" }
      storage.writeTokens(tokens.encoded())

      storage.writeTokens(null)

      assertEquals("", option.value, "a key store presence of $available left the pair behind")
      assertNull(storage.readTokens())
    }
  }

  @Test
  fun `an empty setting is nothing stored`() {
    val storage = storageOn(FakeKeeper(isAvailable = true))

    assertNull(storage.readTokens())
    assertNull(decodeGitHubTokens(storage.readTokens()))
  }

  /**
   * A settings file that travelled from a machine with a key store to one without.
   *
   * `SecretStore.reveal` gives back what it cannot resolve, unchanged. What must NOT happen is that
   * the unresolvable reference is read as a token pair — the connection is gone either way, and
   * "connect again" is the only honest answer.
   */
  @Test
  fun `a reference this machine cannot resolve is not mistaken for a pair`() {
    val option = DefaultStringOption("githubTokens", "")
    option.value = "fake:github:someone-elses-machine"
    val storage = OptionGitHubTokenStorage(option, FakeKeeper(isAvailable = false)) { "/tmp/settings" }

    assertNull(decodeGitHubTokens(storage.readTokens()), "an unresolvable reference was read as a sign-in")
  }
}
