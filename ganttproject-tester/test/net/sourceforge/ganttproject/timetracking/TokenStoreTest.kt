/*
Copyright 2026 Noctuvo

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
// NEUE DATEI DIESES FORKS
package net.sourceforge.ganttproject.timetracking

import junit.framework.TestCase

/**
 * The token store as text: encoding, decoding and — the reason this file exists — what happens
 * when a person's KEY changes.
 *
 * Everything here runs without the GanttProject model, without a database and without JavaFX.
 * `TogglTokensTest` covers the other half, where a resource is involved.
 */
class TokenStoreTest : TestCase() {

  private val nameKey = "name=Natalie"
  private val mailKey = "mail=natalie@example.org"

  // --- Der Fund: der Schluessel aendert sich, der Token muss mit ---

  /**
   * The case that costs a token: a resource is created with a name, the token is entered, and the
   * e-mail address is added later. The key changes from `name=…` to `mail=…`.
   *
   * Without moving the entry the token would sit under the old key: the connection check reports
   * "no token" although one was entered, and the secret stays in `~/.ganttproject` under a key
   * nobody looks up.
   */
  fun testAddingAMailAddressMovesTheToken() {
    val before = encodeTokenMap(mapOf(nameKey to "GEHEIM"))

    val after = movedToken(before, previousKey = nameKey, newKey = mailKey, token = "GEHEIM")

    assertEquals("GEHEIM", tokenForKey(mailKey, after))
    assertNull("the entry under the old key must be gone, not just shadowed",
      tokenForKey(nameKey, after))
  }

  /**
   * THE point of the fix: the entry moves even when the token field itself was not touched. The
   * dialog changes the address and leaves the token alone — that is the common case.
   */
  fun testTheOldEntryGoesEvenWhenTheTokenIsUnchanged() {
    val before = encodeTokenMap(mapOf("mail=alt@example.org" to "GEHEIM"))

    val after = movedToken(before, "mail=alt@example.org", "mail=neu@example.org", "GEHEIM")

    assertEquals(mapOf("mail=neu@example.org" to "GEHEIM"), decodeTokenMap(after))
  }

  /** Somebody else's token must not be touched by a move. */
  fun testAMoveLeavesOtherPeopleAlone() {
    val before = encodeTokenMap(mapOf(nameKey to "MEINS", "mail=kollege@example.org" to "SEINS"))

    val after = movedToken(before, nameKey, mailKey, "MEINS")

    assertEquals("SEINS", tokenForKey("mail=kollege@example.org", after))
    assertEquals("MEINS", tokenForKey(mailKey, after))
    assertEquals(2, decodeTokenMap(after).size)
  }

  /** An emptied field removes the person entirely — under the old key as well as the new one. */
  fun testAnEmptyTokenRemovesBothKeys() {
    val before = encodeTokenMap(mapOf(nameKey to "ALT", mailKey to "NEU"))

    val after = movedToken(before, nameKey, mailKey, "")

    assertTrue("nothing may be left over: $after", decodeTokenMap(after).isEmpty())
  }

  /**
   * Nothing changed, so the text must not change either. The dialog compares the two and only
   * writes when they differ — otherwise opening and closing a resource would rewrite the settings
   * file every time.
   */
  fun testAnUnchangedEntryYieldsTheSameText() {
    val before = encodeTokenMap(mapOf(mailKey to "GEHEIM", "name=Kollege" to "ANDERS"))

    val after = movedToken(before, mailKey, mailKey, "GEHEIM")

    assertEquals(before, after)
  }

  fun testMovingWhenNothingWasStoredJustAddsTheToken() {
    val after = movedToken(null, nameKey, mailKey, "GEHEIM")
    assertEquals(mapOf(mailKey to "GEHEIM"), decodeTokenMap(after))
  }

  /** A token is an opaque string; the separators must survive a move as well as a plain write. */
  fun testSeparatorsSurviveAMove() {
    val awkward = "a;b:c d%e"
    val after = movedToken(encodeTokenMap(mapOf(nameKey to awkward)), nameKey, mailKey, awkward)
    assertEquals(awkward, tokenForKey(mailKey, after))
  }

  /**
   * Awkward keys too: a name is not guaranteed to be free of our separators.
   *
   * The precondition below is not decoration. Without it the test stays green when the encoding is
   * missing altogether: the store simply decodes to nothing, and the move then writes the new
   * entry anyway — both assertions hold while the store is quietly broken. Found by counter-test,
   * not by thinking about it.
   */
  fun testSeparatorsInsideAKeySurviveAMove() {
    val awkwardKey = "name=Mei;er: Anna"
    val before = encodeTokenMap(mapOf(awkwardKey to "GEHEIM"))
    assertEquals("the awkward key must survive encoding, otherwise this test proves nothing",
      "GEHEIM", tokenForKey(awkwardKey, before))

    val after = movedToken(before, awkwardKey, mailKey, "GEHEIM")

    assertEquals("GEHEIM", tokenForKey(mailKey, after))
    assertNull(tokenForKey(awkwardKey, after))
  }

  // --- Grundlagen des Speichers ---

  fun testRoundTrip() {
    val tokens = mapOf(mailKey to "GEHEIM", "name=Kollege" to "AUCHGEHEIM")
    assertEquals(tokens, decodeTokenMap(encodeTokenMap(tokens)))
  }

  fun testEmptyStoreIsEmptyText() {
    assertEquals("", encodeTokenMap(emptyMap()))
    assertTrue(decodeTokenMap("").isEmpty())
    assertTrue(decodeTokenMap(null).isEmpty())
  }

  /** The settings file must not change just because it was written again. */
  fun testOrderIsDeterministic() {
    val one = encodeTokenMap(mapOf("b" to "2", "a" to "1"))
    val other = encodeTokenMap(mapOf("a" to "1", "b" to "2"))
    assertEquals(one, other)
  }

  /** A hand-edited or truncated settings file must not cost the remaining tokens. */
  fun testGarbageIsSkippedButTheGoodEntrySurvives() {
    val text = "kaputt;" + encodeTokenMap(mapOf(mailKey to "GEHEIM"))
    assertEquals(mapOf(mailKey to "GEHEIM"), decodeTokenMap(text))
  }

  fun testAnEmptyTokenIsNeverWritten() {
    assertEquals("", encodeTokenMap(mapOf(mailKey to "")))
  }
}
