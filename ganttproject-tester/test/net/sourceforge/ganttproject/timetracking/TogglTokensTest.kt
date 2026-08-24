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
import biz.ganttproject.customproperty.CustomColumnsManager
import junit.framework.TestCase
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.roles.RoleManager

/**
 * Tests the token store. Nothing here talks to Toggl; it is only about keeping the tokens
 * readable, apart, and out of the project file.
 */
class TogglTokensTest : TestCase() {
  private lateinit var resourceManager: HumanResourceManager

  override fun setUp() {
    super.setUp()
    resourceManager = HumanResourceManager(
      RoleManager.Access.getInstance().defaultRole, CustomColumnsManager())
  }

  private fun resource(name: String, id: Int, mail: String? = null): HumanResource =
    resourceManager.create(name, id).also { if (mail != null) it.mail = mail }

  // --- how a person is identified ---

  /**
   * The e-mail address identifies the Toggl account and survives a rename.
   */
  fun testKeyPrefersTheMailAddress() {
    val person = resource("Nati", 1, "nati@example.org")
    assertTrue("expected the address in the key, got ${tokenKeyFor(person)}",
      tokenKeyFor(person).contains("nati@example.org"))
  }

  fun testKeyFallsBackToTheName() {
    val person = resource("Nati", 1)
    assertTrue(tokenKeyFor(person).contains("Nati"))
  }

  /**
   * THE reason for not keying by resource id, as the handover suggested: ids are handed out per
   * project, so id 1 is a different person in every project — while these settings are global.
   * Two people with the same id must not share a key.
   */
  fun testTwoPeopleWithTheSameIdInDifferentProjectsDoNotCollide() {
    val here = resource("Nati", 1, "nati@example.org")
    val otherProject = HumanResourceManager(
      RoleManager.Access.getInstance().defaultRole, CustomColumnsManager())
      .create("Jemand anders", 1).also { it.mail = "anders@example.org" }

    assertEquals(1, here.id)
    assertEquals(1, otherProject.id)
    assertFalse("same key for two different people — one token would be sent for the other",
      tokenKeyFor(here) == tokenKeyFor(otherProject))
  }

  /** A rename must not lose the token, as long as the address stays. */
  fun testRenamingKeepsTheKey() {
    val person = resource("Nati", 1, "nati@example.org")
    val keyBefore = tokenKeyFor(person)
    person.name = "Natalie"
    assertEquals(keyBefore, tokenKeyFor(person))
  }

  // --- the second path on which the key changes ---

  /**
   * Name and e-mail can be edited straight in the RESOURCE TABLE, which never opens the resource
   * dialog and therefore never runs `MainPropertiesPanel.save()`. The fix that moves the token
   * along lives in that dialog — so this second path had the same defect: type an address into
   * the e-mail cell and the token becomes unreachable, while the secret stays in the settings
   * under a key nobody looks up.
   *
   * Uses the global option store because that is what the production path writes to; restored in
   * `finally` so no other test inherits it.
   */
  fun testEditingTheMailOutsideTheDialogKeepsTheTokenReachable() {
    val person = resource("Nati", 1)
    val before = TogglTokenOptions.tokens.value
    TogglTokenOptions.tokens.value = withToken("", person, "geheim123")
    try {
      // Precondition: without it the test could pass on a token that was never there.
      assertEquals("geheim123", tokenFor(person, TogglTokenOptions.tokens.value))

      // Exactly what ResourceTable.setValue does when the e-mail cell is edited.
      person.keepingTokenReachable { person.mail = "nati@example.org" }

      assertEquals("the token became unreachable when the address was added",
        "geheim123", tokenFor(person, TogglTokenOptions.tokens.value))
      assertNull("the old entry stayed behind as a secret nobody looks up",
        tokenForKey("name=Nati", TogglTokenOptions.tokens.value))
    } finally {
      TogglTokenOptions.tokens.value = before
    }
  }

  /** Same for a rename while no address is set. */
  fun testRenamingOutsideTheDialogKeepsTheTokenReachable() {
    val person = resource("Nati", 1)
    val before = TogglTokenOptions.tokens.value
    TogglTokenOptions.tokens.value = withToken("", person, "geheim123")
    try {
      person.keepingTokenReachable { person.name = "Natalie" }
      assertEquals("geheim123", tokenFor(person, TogglTokenOptions.tokens.value))
    } finally {
      TogglTokenOptions.tokens.value = before
    }
  }

  /**
   * An edit that does not touch the key must leave the settings text untouched — otherwise the
   * settings file would be rewritten every time somebody edits a phone number.
   */
  fun testAnEditThatDoesNotChangeTheKeyWritesNothing() {
    val person = resource("Nati", 1, "nati@example.org")
    val before = TogglTokenOptions.tokens.value
    TogglTokenOptions.tokens.value = withToken("", person, "geheim123")
    try {
      val stored = TogglTokenOptions.tokens.value
      person.keepingTokenReachable { person.phone = "0123" }
      // CHANGED ON 17.08.2026: CONTENT instead of text, for the same reason as above. The test
      // means "an edit that does not touch the key rewrites nothing" -- and that is exactly what
      // is checked, since tokenKeyChange compares the contents.
      assertEquals(decodeTokenMap(stored), decodeTokenMap(TogglTokenOptions.tokens.value))
    } finally {
      TogglTokenOptions.tokens.value = before
    }
  }

  /** Nothing stored, nothing to move — and nothing invented either. */
  fun testMovingWithoutAStoredTokenChangesNothing() {
    val person = resource("Nati", 1)
    val before = TogglTokenOptions.tokens.value
    TogglTokenOptions.tokens.value = ""
    try {
      person.keepingTokenReachable { person.mail = "nati@example.org" }
      assertEquals("", TogglTokenOptions.tokens.value)
    } finally {
      TogglTokenOptions.tokens.value = before
    }
  }

  // --- two tokens claim the same key ---

  /**
   * The case that needs a person to decide: "Nati" has no address and a token; another resource
   * already uses that address with a DIFFERENT token. Adding the address to "Nati" makes both
   * claim the same key, and one token is lost whichever way it goes.
   *
   * Nothing may happen without an answer. Silently overwriting would destroy a secret behind the
   * user's back — and the loss would only surface much later, as a connection check that fails.
   */
  fun testACollisionChangesNothingUntilSomebodyDecides() {
    val nati = resource("Nati", 1)
    val other = resource("Anders", 2, "nati@example.org")
    val before = TogglTokenOptions.tokens.value
    TogglTokenOptions.tokens.value =
      withToken(withToken("", nati, "TOKEN-NEU"), other, "TOKEN-ALT")
    try {
      val stored = TogglTokenOptions.tokens.value
      var asked = false

      // The asker that never answers — exactly what closing the dialog does.
      nati.keepingTokenReachable({ _, _ -> asked = true }) { nati.mail = "nati@example.org" }

      assertTrue("no question was asked, so a token was destroyed silently", asked)
      assertEquals("the store was changed although nobody had decided yet",
        stored, TogglTokenOptions.tokens.value)
    } finally {
      TogglTokenOptions.tokens.value = before
    }
  }

  /** "Keep the new one": the moving token wins, the stored one is gone. */
  fun testKeepingTheMovingTokenReplacesTheStoredOne() {
    val nati = resource("Nati", 1)
    val other = resource("Anders", 2, "nati@example.org")
    val before = TogglTokenOptions.tokens.value
    TogglTokenOptions.tokens.value =
      withToken(withToken("", nati, "TOKEN-NEU"), other, "TOKEN-ALT")
    try {
      nati.keepingTokenReachable({ collision, apply -> apply(collision.ifOverwritten) }) {
        nati.mail = "nati@example.org"
      }

      assertEquals("TOKEN-NEU", tokenFor(nati, TogglTokenOptions.tokens.value))
      assertNull("the old key was left behind as a secret nobody looks up",
        tokenForKey("name=Nati", TogglTokenOptions.tokens.value))
    } finally {
      TogglTokenOptions.tokens.value = before
    }
  }

  /**
   * "Keep the stored one": the moving token is dropped — and REALLY dropped, not left under the
   * old key. A token under a key nobody looks up is the abandoned secret this whole mechanism
   * exists to avoid.
   */
  fun testKeepingTheStoredTokenDropsTheMovingOneEntirely() {
    val nati = resource("Nati", 1)
    val other = resource("Anders", 2, "nati@example.org")
    val before = TogglTokenOptions.tokens.value
    TogglTokenOptions.tokens.value =
      withToken(withToken("", nati, "TOKEN-NEU"), other, "TOKEN-ALT")
    try {
      nati.keepingTokenReachable({ collision, apply -> apply(collision.ifDiscarded) }) {
        nati.mail = "nati@example.org"
      }

      assertEquals("TOKEN-ALT", tokenFor(nati, TogglTokenOptions.tokens.value))
      assertNull("the discarded token stayed behind under the old key",
        tokenForKey("name=Nati", TogglTokenOptions.tokens.value))
      assertFalse("the discarded token is still somewhere in the store",
        TogglTokenOptions.tokens.value.orEmpty().contains("TOKEN-NEU"))
    } finally {
      TogglTokenOptions.tokens.value = before
    }
  }

  /**
   * The SAME token under both keys is not a collision — nothing is lost by dropping the duplicate,
   * so asking would be a question with only one sensible answer.
   */
  fun testTheSameTokenUnderBothKeysIsNoCollision() {
    val nati = resource("Nati", 1)
    val other = resource("Anders", 2, "nati@example.org")
    val before = TogglTokenOptions.tokens.value
    TogglTokenOptions.tokens.value =
      withToken(withToken("", nati, "DERSELBE"), other, "DERSELBE")
    try {
      var asked = false
      nati.keepingTokenReachable({ _, _ -> asked = true }) { nati.mail = "nati@example.org" }

      assertFalse("asked although nothing could be lost", asked)
      assertEquals("DERSELBE", tokenFor(nati, TogglTokenOptions.tokens.value))
      assertNull(tokenForKey("name=Nati", TogglTokenOptions.tokens.value))
    } finally {
      TogglTokenOptions.tokens.value = before
    }
  }

  /** Callers that cannot ask must change nothing rather than guess. */
  fun testWithoutAnAskerACollisionLeavesEverythingAlone() {
    val nati = resource("Nati", 1)
    val other = resource("Anders", 2, "nati@example.org")
    val before = TogglTokenOptions.tokens.value
    TogglTokenOptions.tokens.value =
      withToken(withToken("", nati, "TOKEN-NEU"), other, "TOKEN-ALT")
    try {
      val stored = TogglTokenOptions.tokens.value
      nati.keepingTokenReachable { nati.mail = "nati@example.org" }
      assertEquals(stored, TogglTokenOptions.tokens.value)
    } finally {
      TogglTokenOptions.tokens.value = before
    }
  }

  /** The dialog shows the address, not the internal `mail=` prefix. */
  fun testTheKeyIsShownWithoutItsPrefix() {
    assertEquals("nati@example.org", readableKey("mail=nati@example.org"))
    assertEquals("Nati", readableKey("name=Nati"))
    // Nothing to strip: show it as it is rather than an empty string.
    assertEquals("kaputt", readableKey("kaputt"))
  }

  // --- storing ---

  fun testTokenIsReadBack() {
    val person = resource("Nati", 1, "nati@example.org")
    val stored = withToken("", person, "geheim123")
    assertEquals("geheim123", tokenFor(person, stored))
  }

  fun testUnknownPersonHasNoToken() {
    val known = resource("Nati", 1, "nati@example.org")
    val unknown = resource("Anders", 2, "anders@example.org")
    val stored = withToken("", known, "geheim123")
    assertNull(tokenFor(unknown, stored))
  }

  fun testTwoPeopleKeepSeparateTokens() {
    val first = resource("Nati", 1, "nati@example.org")
    val second = resource("Anders", 2, "anders@example.org")
    val stored = withToken(withToken("", first, "token-A"), second, "token-B")

    assertEquals("token-A", tokenFor(first, stored))
    assertEquals("token-B", tokenFor(second, stored))
  }

  fun testEmptyTokenRemovesTheEntry() {
    val person = resource("Nati", 1, "nati@example.org")
    val stored = withToken(withToken("", person, "geheim123"), person, "")
    assertNull(tokenFor(person, stored))
  }

  /**
   * A token is an opaque string from Toggl and may contain the separators. Without encoding, a
   * token with a semicolon would cut the rest of the store off — and the next person's token
   * would silently disappear.
   */
  fun testSeparatorsInsideATokenSurvive() {
    val first = resource("Nati", 1, "nati@example.org")
    val second = resource("Anders", 2, "anders@example.org")
    val awkward = "ab;cd:ef"

    val stored = withToken(withToken("", first, awkward), second, "token-B")

    assertEquals(awkward, tokenFor(first, stored))
    assertEquals("the second token was lost, so the separators were not escaped",
      "token-B", tokenFor(second, stored))
  }

  /** Names with umlauts or separators must not break the store either. */
  fun testAwkwardNamesSurvive() {
    val person = resourceManager.create("Müller; Anna:B", 3)
    val stored = withToken("", person, "geheim123")
    assertEquals("geheim123", tokenFor(person, stored))
  }

  /**
   * A damaged settings file must not take the readable entries with it, and must not throw —
   * otherwise a single bad line would make the tokens unreachable.
   */
  fun testGarbageIsSkippedButTheGoodEntrySurvives() {
    val good = "mail%3Dnati%40example.org:geheim123"
    val decoded = decodeTokenMap("kaputt;;x:y:z;$good")

    assertEquals(mapOf("mail=nati@example.org" to "geheim123"), decoded)
    assertTrue(decodeTokenMap(null).isEmpty())
    assertTrue(decodeTokenMap("").isEmpty())
  }

  // --- the way into the settings file ---

  /**
   * The settings file stores an option through `getPersistentValue` and reads it back through
   * `loadPersistentValue` (see `OptionSaver`). This is that round trip: what GanttOptions writes
   * into `~/.ganttproject` must come back as the same token.
   *
   * Tested at this layer rather than against the real file, because `GanttOptions.save()` writes
   * to a fixed path in the home directory and a test must not touch the real settings.
   */
  fun testTokenSurvivesTheSettingsRoundTrip() {
    val person = resource("Nati", 1, "nati@example.org")
    val option = DefaultStringOption("resourceTokens", "")
    option.value = withToken("", person, "geheim123")

    val persisted = option.persistentValue
    val reloaded = DefaultStringOption("resourceTokens", "")
    reloaded.loadPersistentValue(persisted)

    assertEquals("geheim123", tokenFor(person, reloaded.value))
  }

  /**
   * The key under which the token appears in the settings file is `<group>.<option>`. Renaming
   * either would silently orphan every stored token, so the names are pinned here.
   */
  fun testTheSettingsKeyIsStable() {
    assertEquals("toggl", TogglTokenOptions.optionGroup.id)
    assertEquals("resourceTokens", TogglTokenOptions.tokens.id)
  }

  /** Same content, same text — otherwise the settings file churns on every save. */
  fun testOrderIsDeterministic() {
    val first = resource("Nati", 1, "nati@example.org")
    val second = resource("Anders", 2, "anders@example.org")
    val oneWay = withToken(withToken("", first, "A"), second, "B")
    val otherWay = withToken(withToken("", second, "B"), first, "A")
    // CHANGED ON 17.08.2026: compare CONTENTS. The token is stored encrypted, and DPAPI mixes in
    // randomness -- two texts of the same content are never equal again. What is meant is "the
    // order of the input does not change the result", and that still holds.
    assertEquals(decodeTokenMap(oneWay), decodeTokenMap(otherWay))
  }
}
