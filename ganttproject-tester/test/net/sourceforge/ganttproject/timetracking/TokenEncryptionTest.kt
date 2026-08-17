/*
Copyright 2026

NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.

This file is part of GanttProject, an opensource project management tool.
Licensed under the GNU General Public License, version 3 or later.
*/
package net.sourceforge.ganttproject.timetracking

import junit.framework.TestCase
import net.sourceforge.ganttproject.fork.SecretStore

/**
 * Der Toggl-Token in der Einstellungsdatei: verschluesselt, nicht nur kodiert.
 *
 * WOZU: bis zum 17.08.2026 stand er dort im Klartext -- lesbar fuer alles, was unter demselben
 * Benutzerkonto laeuft. Beim WebDAV-Passwort war Verschluesselung ausdrueckliche Bedingung; der
 * Token ist dasselbe Geheimnis in derselben Datei.
 *
 * Ohne Windows gibt es kein DPAPI und nichts zu pruefen. Die Tests melden das, statt gruen zu
 * sein und nichts gemessen zu haben.
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
    if (!SecretStore.isAvailable) return
    val einmal = encodeTokenMap(mapOf(key to geheim))
    val nochmal = encodeTokenMap(mapOf(key to geheim))
    assertFalse("gleicher Chiffretext hiesse: gleiche Geheimnisse sind erkennbar",
      einmal == nochmal)
    assertEquals("der Inhalt ist derselbe", decodeTokenMap(einmal), decodeTokenMap(nochmal))
  }

  /** Zwei gleiche Token duerfen keine Kollisionsfrage ausloesen. */
  fun testTheSameTokenIsNoCollision() {
    if (!SecretStore.isAvailable) return
    val gespeichert = encodeTokenMap(mapOf(key to geheim, "name=Natalie" to geheim))
    val ergebnis = tokenKeyChange(gespeichert, "name=Natalie", key)
    assertFalse("derselbe Token unter beiden Schluesseln ist keine Kollision, war aber $ergebnis",
      ergebnis is TokenKeyChange.Collision)
  }
}
