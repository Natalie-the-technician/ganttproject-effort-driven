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
package net.sourceforge.ganttproject.timetracking

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

  /** Same content, same text — otherwise the settings file churns on every save. */
  fun testOrderIsDeterministic() {
    val first = resource("Nati", 1, "nati@example.org")
    val second = resource("Anders", 2, "anders@example.org")
    val oneWay = withToken(withToken("", first, "A"), second, "B")
    val otherWay = withToken(withToken("", second, "B"), first, "A")
    assertEquals(oneWay, otherWay)
  }
}
