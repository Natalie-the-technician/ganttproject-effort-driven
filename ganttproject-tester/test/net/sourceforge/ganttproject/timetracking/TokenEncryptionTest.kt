/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.

This file is part of GanttProject, an opensource project management tool.
Licensed under the GNU General Public License, version 3 or later.
*/
package net.sourceforge.ganttproject.timetracking

import junit.framework.TestCase
import net.sourceforge.ganttproject.fork.SecretStore

/**
 * The Toggl token in the settings file: encrypted, not merely encoded.
 *
 * WHAT FOR: until 17.08.2026 it stood there in plain text -- readable by everything running under
 * the same user account. For the WebDAV password encryption was an explicit condition; the token
 * is the same secret in the same file.
 *
 * WITHOUT A SECRET STORE, FOUR OF THESE FIVE METHODS CHECK NOTHING.
 *
 * [fork change] 09.09.2026: the condition used to be Windows. `SecretStore.isAvailable` read
 * `os.name` and was true there and nowhere else, so on Linux and macOS the token went into the file
 * in the clear and these four methods returned before their first assertion. It now asks whether a
 * platform store can really be reached -- DPAPI, libsecret or the macOS Keychain -- so on a Linux
 * machine WITH a running keyring they measure for real. On a machine without one they still return.
 *
 * JUnit counts a method that returns without an error as PASSED, so such a run reports four green
 * tests here and not one of them measured anything. That is why
 * [testWithoutAStoreTheTokenIsRecognisablePlainText] was added: it is the one that runs precisely
 * when the other four do not, and it asserts what is true in that case -- that the token stands
 * there in plain sight, and NOT as something that merely looks protected.
 *
 * WHY NOT `assumeTrue` -- measured, not assumed:
 *
 * From 19 to 20 August 2026 `assumeTrue(REASON, SecretStore.isAvailable)` stood here instead of the
 * early `return`, so that the run would count the four as SKIPPED rather than passed. That does not
 * work. Measured on a Linux VM on 20 August 2026, figures from the JUnit XML:
 *
 *     tests=4  failures=4  errors=0  skipped=0
 *     org.junit.AssumptionViolatedException: DPAPI exists only on Windows; ...
 *
 * Four RED tests, not four skipped -- and with them `BUILD FAILED` on every full run outside
 * Windows. The reason lies in the inheritance: this class extends `junit.framework.TestCase` and is
 * therefore run through the Vintage engine's `JUnit38ClassRunner`. That runner passes every thrown
 * exception on as an error via `TestResult.addError`; the special handling for the
 * `AssumptionViolatedException` sits in the JUnit 4 runner, which a JUnit 3 `TestCase` never
 * reaches. On a Windows machine it never shows, because `isAvailable` is true there and the
 * assumption never fires -- which is precisely why the change went out unchecked.
 *
 * The comment that stood here until 20 August 2026 claimed the opposite: that the Vintage engine
 * reports the assumption as skipped. That was an assumption about the assumption, not a
 * measurement, and it is wrong.
 *
 * Whoever really wants the four counted as skipped has to detach the class from `TestCase` and
 * write it as a JUnit 4 or Jupiter test with `@Test`. As long as it is a JUnit 3 `TestCase`, the
 * early `return` stays: four falsely green tests are a bookkeeping error, four red ones are a
 * broken build on every non-Windows machine.
 *
 * FOR READING THE NUMBERS: a Linux baseline is exactly as large as a Windows baseline, these four
 * included. The difference is not in the number but in what was measured behind it -- on a machine
 * without a keyring, namely nothing.
 */
class TokenEncryptionTest : TestCase() {

  private val geheim = "1234567890abcdef1234567890abcdef"
  private val key = "mail=natalie@example.invalid"

  fun testTheTokenIsNotReadableInTheFile() {
    if (!SecretStore.isAvailable) return
    val text = encodeTokenMap(mapOf(key to geheim))
    assertFalse("der Token darf im gespeicherten Text nicht auftauchen: $text",
      text.contains(geheim))
    assertEquals("gelesen wird er trotzdem wieder im Klartext", geheim, tokenForKey(key, text))
  }

  fun testAnOldPlaintextEntryStillWorksAndIsEncryptedOnTheNextWrite() {
    if (!SecretStore.isAvailable) return
    // This is how the file looked before this change: URL-encoded only.
    val alt = "mail%3Dnatalie%40example.invalid:$geheim"
    assertEquals("wer schon einen Token hatte, darf ihn nicht verlieren",
      geheim, tokenForKey(key, alt))
    val neu = encodeTokenMap(decodeTokenMap(alt))
    assertFalse("beim naechsten Speichern ist er verschluesselt", neu.contains(geheim))
    assertEquals(geheim, tokenForKey(key, neu))
  }

  /**
   * The counter-check: TWO PEOPLE WITH THE SAME TOKEN MUST NOT LOOK ALIKE IN THE FILE.
   *
   * Whoever can read `~/.ganttproject` must not be able to tell that two entries hold the same
   * secret, without knowing either. That is worth something on its own: it is how a shared token,
   * or a token reused from somewhere else, would show itself to a reader who has no other way in.
   *
   * [fork change] 09.09.2026 THIS TEST USED TO ASSERT THE WRONG THING, and the change of backends
   * is what brought it out. It encoded the SAME key twice and demanded two different texts, which
   * held for DPAPI -- it mixes in randomness, so every write differs -- and made the property look
   * like a property of the ciphertext. It is not. With a keyring the stored value is a REFERENCE
   * derived from the key, so encoding the same entry twice gives the same text on purpose:
   * a reference that changed at every save would leave one orphaned keyring entry behind per save.
   *
   * What was really being protected survives both ways, and it is what is asserted now: the stored
   * value depends on WHOSE token it is, never on WHAT the token is. DPAPI gets there through
   * randomness, the keyrings through the key. The old assertion would have gone red on Linux for a
   * change that is correct -- a test measuring the mechanism instead of the purpose.
   */
  fun testTwoPeopleWithTheSameTokenDoNotLookAlike() {
    if (!SecretStore.isAvailable) return
    val zweiterSchluessel = "name=Natalie"
    val text = encodeTokenMap(mapOf(key to geheim, zweiterSchluessel to geheim))
    val gespeicherteHaelften = text.split(";").map { it.split(":")[1] }
    assertEquals("zwei Eintraege erwartet", 2, gespeicherteHaelften.size)
    assertFalse("gleiche gespeicherte Werte hiessen: gleiche Geheimnisse sind erkennbar",
      gespeicherteHaelften[0] == gespeicherteHaelften[1])
    assertFalse("und der Token steht nirgends: " + text, text.contains(geheim))
    assertEquals("beide sind trotzdem lesbar", geheim, tokenForKey(key, text))
    assertEquals(geheim, tokenForKey(zweiterSchluessel, text))
  }

  /**
   * [fork change] THE ONE THAT RUNS WHERE THE OTHERS DO NOT: no store, no appearance of one.
   *
   * On a machine without a keyring the token IS written in the clear -- deliberately, because
   * unlike a password it cannot be typed again from memory; it has to be fetched from the Toggl
   * website. What must not happen is that it looks like anything else. A value that carried a
   * marker, or that had been scrambled with a key lying next to it in the same file, would tell the
   * reader of that file a comforting untruth.
   *
   * So this asserts the uncomfortable thing: the token is there, verbatim, findable by anyone who
   * opens `~/.ganttproject`. `TokenStore` says so in the log at the same moment.
   */
  fun testWithoutAStoreTheTokenIsRecognisablePlainText() {
    if (SecretStore.isAvailable) {
      // Then the other four measure it, and the opposite holds.
      return
    }
    val text = encodeTokenMap(mapOf(key to geheim))
    assertTrue("without a store the token has to stand there in plain sight, not disguised: " + text,
      text.contains(geheim))
    assertFalse("and nothing may claim it is protected", SecretStore.isProtected(geheim))
    assertEquals("it is still readable back, of course", geheim, tokenForKey(key, text))
  }

  /** Two identical tokens must not trigger a collision question. */
  fun testTheSameTokenIsNoCollision() {
    if (!SecretStore.isAvailable) return
    val gespeichert = encodeTokenMap(mapOf(key to geheim, "name=Natalie" to geheim))
    val ergebnis = tokenKeyChange(gespeichert, "name=Natalie", key)
    assertFalse("derselbe Token unter beiden Schluesseln ist keine Kollision, war aber $ergebnis",
      ergebnis is TokenKeyChange.Collision)
  }
}
