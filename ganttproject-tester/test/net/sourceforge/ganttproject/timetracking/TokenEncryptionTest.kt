/*
Copyright 2026

NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.

This file is part of GanttProject, an opensource project management tool.
Licensed under the GNU General Public License, version 3 or later.
*/
package net.sourceforge.ganttproject.timetracking

import junit.framework.TestCase
import net.sourceforge.ganttproject.fork.SecretStore
import org.junit.Assume.assumeTrue

/**
 * Der Toggl-Token in der Einstellungsdatei: verschluesselt, nicht nur kodiert.
 *
 * WOZU: bis zum 17.08.2026 stand er dort im Klartext -- lesbar fuer alles, was unter demselben
 * Benutzerkonto laeuft. Beim WebDAV-Passwort war Verschluesselung ausdrueckliche Bedingung; der
 * Token ist dasselbe Geheimnis in derselben Datei.
 *
 * OHNE WINDOWS PRUEFT KEINE DIESER VIER METHODEN ETWAS -- und seit dem 20.08.2026 sagen sie es.
 *
 * `SecretStore.isAvailable` ist nur unter Windows wahr; es liest `os.name`. Vorher begann jede
 * Methode mit `if (!SecretStore.isAvailable) return` und kehrte unter Linux und macOS vor der
 * ersten Zusicherung zurueck. JUnit wertet eine Methode, die ohne Fehler zurueckkommt, als
 * BESTANDEN -- nicht als uebersprungen; der Lauf meldete dort vier gruene Tests, gemessen wurde
 * nichts. Jetzt steht `assumeTrue` davor, und der Lauf zaehlt sie als UEBERSPRUNGEN.
 *
 * Das bleibt beim Lesen der Zahlen wichtig, nur andersherum: eine Linux-Grundlinie enthaelt vier
 * Tests weniger als eine Windows-Grundlinie, und der Unterschied ist genau dieser. Belegt ist die
 * Verschluesselung nach wie vor nur auf einem Windows-Lauf.
 *
 * NICHT NACHGEMESSEN: dass die vier unter Linux tatsaechlich als uebersprungen gezaehlt werden.
 * Auf einem Windows-Rechner ist `isAvailable` wahr, die Annahme greift also nie -- die Umstellung
 * laesst sich hier nicht pruefen. Das gehoert auf einen Linux-Lauf.
 */
/**
 * [Fork-Aenderung] Der Grund fuers Ueberspringen, damit er im Bericht steht und nicht nur hier.
 *
 * `assumeTrue` statt `return`: eine Methode, die ohne Zusicherung zurueckkommt, zaehlt JUnit als
 * BESTANDEN. Vier gruene Tests, die nichts gemessen haben, stehen in derselben Zahl wie die
 * echten -- genau das, wovor der Kommentar oben warnt. Uebersprungen zaehlt getrennt.
 *
 * `org.junit.Assume` und nicht `org.junit.jupiter.api.Assumptions`: diese Klasse erbt von
 * `junit.framework.TestCase`, laeuft also ueber die Vintage-Engine. Deren Runner kennt die
 * AssumptionViolatedException und meldet sie als uebersprungen; die Jupiter-Fassung wuerde hier
 * nicht greifen.
 */
private const val GRUND = "DPAPI gibt es nur unter Windows; hier wird nichts geprueft."

class TokenEncryptionTest : TestCase() {

  private val geheim = "1234567890abcdef1234567890abcdef"
  private val key = "mail=natalie@example.invalid"

  fun testTheTokenIsNotReadableInTheFile() {
    assumeTrue(GRUND, SecretStore.isAvailable)
    val text = encodeTokenMap(mapOf(key to geheim))
    assertFalse("der Token darf im gespeicherten Text nicht auftauchen: $text",
      text.contains(geheim))
    assertEquals("gelesen wird er trotzdem wieder im Klartext", geheim, tokenForKey(key, text))
  }

  fun testAnOldPlaintextEntryStillWorksAndIsEncryptedOnTheNextWrite() {
    assumeTrue(GRUND, SecretStore.isAvailable)
    // So sah die Datei vor dieser Aenderung aus: nur URL-kodiert.
    val alt = "mail%3Dnatalie%40example.invalid:$geheim"
    assertEquals("wer schon einen Token hatte, darf ihn nicht verlieren",
      geheim, tokenForKey(key, alt))
    val neu = encodeTokenMap(decodeTokenMap(alt))
    assertFalse("beim naechsten Speichern ist er verschluesselt", neu.contains(geheim))
    assertEquals(geheim, tokenForKey(key, neu))
  }

  /**
   * Die Gegenprobe zur Verschluesselung: derselbe Token ergibt ZWEI verschiedene Texte.
   *
   * Das ist keine Panne, sondern der Zweck -- waere der Chiffretext immer derselbe, koennte man
   * zwei gleiche Geheimnisse aneinander erkennen, ohne eines davon zu kennen. Der Test haelt es
   * fest, damit niemand die Eigenschaft spaeter fuer einen Fehler haelt und "repariert".
   */
  fun testTheSameTokenYieldsDifferentCiphertext() {
    assumeTrue(GRUND, SecretStore.isAvailable)
    val einmal = encodeTokenMap(mapOf(key to geheim))
    val nochmal = encodeTokenMap(mapOf(key to geheim))
    assertFalse("gleicher Chiffretext hiesse: gleiche Geheimnisse sind erkennbar",
      einmal == nochmal)
    assertEquals("der Inhalt ist derselbe", decodeTokenMap(einmal), decodeTokenMap(nochmal))
  }

  /** Zwei gleiche Token duerfen keine Kollisionsfrage ausloesen. */
  fun testTheSameTokenIsNoCollision() {
    assumeTrue(GRUND, SecretStore.isAvailable)
    val gespeichert = encodeTokenMap(mapOf(key to geheim, "name=Natalie" to geheim))
    val ergebnis = tokenKeyChange(gespeichert, "name=Natalie", key)
    assertFalse("derselbe Token unter beiden Schluesseln ist keine Kollision, war aber $ergebnis",
      ergebnis is TokenKeyChange.Collision)
  }
}
