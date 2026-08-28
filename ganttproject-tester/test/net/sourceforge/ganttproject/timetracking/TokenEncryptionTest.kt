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
 * WITHOUT WINDOWS NONE OF THESE FOUR METHODS CHECKS ANYTHING.
 *
 * `SecretStore.isAvailable` reads `os.name` and is true only on Windows. Every method therefore
 * returns on every other system before the first assertion. JUnit counts a method that returns
 * without an error as PASSED -- so a Linux run reports four green tests here, and not one of them
 * measured anything. The encryption is still demonstrated only by a Windows run.
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
 * included. The difference is not in the number but in what was measured behind it -- on Linux,
 * namely nothing.
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
   * The counter-check to the encryption: the same token yields TWO different texts.
   *
   * That is not a mishap but the purpose -- if the ciphertext were always the same, two identical
   * secrets could be recognised by each other without knowing either. The test records it so that
   * nobody later takes the property for a bug and "fixes" it.
   */
  fun testTheSameTokenYieldsDifferentCiphertext() {
    if (!SecretStore.isAvailable) return
    val einmal = encodeTokenMap(mapOf(key to geheim))
    val nochmal = encodeTokenMap(mapOf(key to geheim))
    assertFalse("gleicher Chiffretext hiesse: gleiche Geheimnisse sind erkennbar",
      einmal == nochmal)
    assertEquals("der Inhalt ist derselbe", decodeTokenMap(einmal), decodeTokenMap(nochmal))
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
