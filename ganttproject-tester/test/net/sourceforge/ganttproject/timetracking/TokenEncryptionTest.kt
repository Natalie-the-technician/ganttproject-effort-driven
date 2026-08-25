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
 * OHNE WINDOWS PRUEFT KEINE DIESER VIER METHODEN ETWAS.
 *
 * `SecretStore.isAvailable` liest `os.name` und ist nur unter Windows wahr. Jede Methode kehrt
 * darum auf jedem anderen System vor der ersten Zusicherung zurueck. JUnit wertet eine Methode,
 * die ohne Fehler zurueckkommt, als BESTANDEN -- ein Linux-Lauf meldet hier also vier gruene
 * Tests, und keiner davon hat etwas gemessen. Belegt ist die Verschluesselung nach wie vor nur
 * durch einen Windows-Lauf.
 *
 * WARUM NICHT `assumeTrue` -- gemessen, nicht vermutet:
 *
 * Vom 19. bis zum 20.08.2026 stand hier `assumeTrue(GRUND, SecretStore.isAvailable)` statt des
 * fruehen `return`, damit der Lauf die vier als UEBERSPRUNGEN zaehlt statt als bestanden. Das
 * funktioniert nicht. Nachgemessen auf einer Linux-VM am 20.08.2026, Zahlen aus dem JUnit-XML:
 *
 *     tests=4  failures=4  errors=0  skipped=0
 *     org.junit.AssumptionViolatedException: DPAPI gibt es nur unter Windows; ...
 *
 * Vier ROTE Tests, nicht vier uebersprungene -- und damit `BUILD FAILED` bei jedem Volllauf
 * ausserhalb von Windows. Der Grund steckt in der Vererbung: diese Klasse erbt von
 * `junit.framework.TestCase` und wird deshalb ueber die Vintage-Engine von
 * `JUnit38ClassRunner` ausgefuehrt. Der reicht jede geworfene Ausnahme ueber
 * `TestResult.addError` als Fehler weiter; die Sonderbehandlung fuer die
 * `AssumptionViolatedException` sitzt im JUnit-4-Runner, den eine JUnit-3-`TestCase` nie
 * erreicht. Auf einem Windows-Rechner faellt das nie auf, weil `isAvailable` dort wahr ist und
 * die Annahme gar nicht erst greift -- genau deshalb ging die Umstellung ungeprueft hinaus.
 *
 * Der Kommentar, der bis zum 20.08.2026 hier stand, behauptete das Gegenteil: die Vintage-Engine
 * melde die Annahme als uebersprungen. Das war eine Annahme ueber die Annahme, keine Messung,
 * und sie ist falsch.
 *
 * Wer die vier wirklich als uebersprungen gezaehlt haben will, muss die Klasse von `TestCase`
 * loesen und sie als JUnit-4- oder Jupiter-Test mit `@Test` schreiben. Solange sie eine
 * JUnit-3-`TestCase` ist, bleibt der fruehe `return`: vier falsch-gruene Tests sind ein
 * Buchhaltungsfehler, vier rote sind ein kaputter Bau auf jeder Nicht-Windows-Maschine.
 *
 * FUERS LESEN DER ZAHLEN: eine Linux-Grundlinie ist genauso gross wie eine Windows-Grundlinie,
 * diese vier eingerechnet. Der Unterschied steckt nicht in der Zahl, sondern darin, was
 * dahinter gemessen wurde -- unter Linux naemlich nichts.
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
