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
 * WITHOUT WINDOWS NONE OF THESE FOUR METHODS CHECKS ANYTHING -- and they do not say so.
 *
 * `SecretStore.isAvailable` is true only on Windows; it reads `os.name`. Every method here begins
 * with `if (!SecretStore.isAvailable) return` and therefore returns on Linux and macOS before the
 * first assertion. JUnit counts a method that returns without an error as PASSED -- not as
 * skipped. The test run reports four green tests there, and nothing was measured.
 *
 * That matters when reading the numbers: a Linux baseline of 711 green tests contains four that
 * carry no statement about the encryption. Whoever takes it as demonstrated there is mistaken --
 * it is demonstrated only on a Windows run.
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
