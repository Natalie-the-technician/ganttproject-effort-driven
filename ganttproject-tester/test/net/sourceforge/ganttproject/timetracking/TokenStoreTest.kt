/*
Copyright 2026

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

  // --- Der Weg ueber den Ressourcendialog: der Token steht in einem Feld ---

  /**
   * The resource dialog KNOWS the token, because it is in a field on screen. That is the only
   * difference to the table; the collision rule has to be the same, or the same mistake is caught
   * on one path and destroys a token on the other. It did, until this was unified.
   */
  fun testTheDialogPathAlsoAsksOnACollision() {
    val stored = encodeTokenMap(mapOf(nameKey to "TOKEN-A", mailKey to "TOKEN-B"))

    val outcome = tokenKeyChange(stored, nameKey, mailKey, editedToken = "TOKEN-A")

    assertTrue("the dialog would have destroyed TOKEN-B silently, got $outcome",
      outcome is TokenKeyChange.Collision)
  }

  /** Emptying the field removes the entry. That is a removal, not a question. */
  fun testAnEmptiedFieldIsNoCollision() {
    val stored = encodeTokenMap(mapOf(nameKey to "TOKEN-A", mailKey to "TOKEN-B"))

    val outcome = tokenKeyChange(stored, nameKey, mailKey, editedToken = "")

    assertTrue("expected a plain move, got $outcome", outcome is TokenKeyChange.Move)
    assertNull("the entry was not removed",
      tokenForKey(nameKey, (outcome as TokenKeyChange.Move).tokens))
    assertEquals("somebody else's token was taken along", "TOKEN-B",
      tokenForKey(mailKey, outcome.tokens))
  }

  /** Editing the token without changing the key replaces one's own entry — nothing to ask. */
  fun testEditingTheTokenWithoutChangingTheKeyIsNoCollision() {
    val stored = encodeTokenMap(mapOf(mailKey to "ALT"))

    val outcome = tokenKeyChange(stored, mailKey, mailKey, editedToken = "NEU")

    assertTrue("expected a plain move, got $outcome", outcome is TokenKeyChange.Move)
    assertEquals("NEU", tokenForKey(mailKey, (outcome as TokenKeyChange.Move).tokens))
  }

  /** A dialog nobody touched must not rewrite the settings file. */
  fun testAnUntouchedDialogChangesNothing() {
    val stored = encodeTokenMap(mapOf(mailKey to "GEHEIM"))

    assertTrue(tokenKeyChange(stored, mailKey, mailKey, editedToken = "GEHEIM")
      is TokenKeyChange.Unchanged)
  }

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

  /**
   * An emptied field removes the entry — the OWN one.
   *
   * [Fork-Aenderung] Diese Erwartung wurde geaendert. Vorher stand hier, dass ein leeres Feld
   * BEIDE Schluessel raeumt. Das kostete fremde Token: wer das Feld leerte und zugleich eine
   * Adresse eintrug, unter der bereits jemand anderes gespeichert war, loeschte dessen Eintrag mit.
   *
   * Der urspruenglich gemeinte Fall — dieselbe Person unter zwei Schluesseln — entsteht durch
   * normalen Gebrauch nie, weil jeder Schreibvorgang den Eintrag verschiebt statt zu verdoppeln.
   * Der neue Fall entsteht durch normalen Gebrauch sehr wohl. Deshalb gilt jetzt: unter dem neuen
   * Schluessel Liegendes gehoert jemand anderem und bleibt.
   */
  fun testAnEmptyTokenRemovesTheOwnEntry() {
    val before = encodeTokenMap(mapOf(nameKey to "MEINS", mailKey to "FREMD"))

    val after = movedToken(before, nameKey, mailKey, "")

    assertNull("der eigene Eintrag blieb stehen: $after", tokenForKey(nameKey, after))
    assertEquals("ein fremder Token wurde mitgeloescht", "FREMD", tokenForKey(mailKey, after))
  }

  /** Ohne Schluesselwechsel ist der Eintrag unter dem Schluessel der eigene und muss weg. */
  fun testAnEmptyTokenRemovesTheEntryWhenTheKeyDidNotChange() {
    val before = encodeTokenMap(mapOf(mailKey to "MEINS"))

    val after = movedToken(before, mailKey, mailKey, "")

    assertTrue("nichts darf uebrig bleiben: $after", decodeTokenMap(after).isEmpty())
  }

  /**
   * Nothing changed, so the text must not change either. The dialog compares the two and only
   * writes when they differ — otherwise opening and closing a resource would rewrite the settings
   * file every time.
   */
  fun testAnUnchangedEntryYieldsTheSameText() {
    val before = encodeTokenMap(mapOf(mailKey to "GEHEIM", "name=Kollege" to "ANDERS"))

    val after = movedToken(before, mailKey, mailKey, "GEHEIM")

    // GEAENDERT AM 17.08.2026: verglichen wird der INHALT, nicht der Text. Seit der Token
    // verschluesselt gespeichert wird, sind zwei Texte desselben Inhalts nie mehr gleich --
    // DPAPI mischt Zufall bei, damit gleiche Geheimnisse nicht am gleichen Chiffretext zu
    // erkennen sind. Was der Test meint, ist "es geht nichts verloren und nichts kommt hinzu".
    assertEquals(decodeTokenMap(before), decodeTokenMap(after))
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
    // GEAENDERT AM 17.08.2026, als der Token verschluesselt wurde: die beiden Texte sind nicht
    // mehr Byte fuer Byte gleich, und das ist Absicht -- DPAPI mischt Zufall bei, damit zwei
    // gleiche Geheimnisse nicht am gleichen Chiffretext zu erkennen sind. Geprueft wird
    // weiterhin, was der Test eigentlich meint: die Reihenfolge haengt nicht von der Eingabe ab.
    val one = decodeTokenMap(encodeTokenMap(mapOf("b" to "2", "a" to "1")))
    val other = decodeTokenMap(encodeTokenMap(mapOf("a" to "1", "b" to "2")))
    assertEquals(one, other)
    assertEquals(listOf("a", "b"), one.keys.toList())
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
