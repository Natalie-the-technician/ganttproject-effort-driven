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
package net.sourceforge.ganttproject.fork

import junit.framework.TestCase

/**
 * The store for secrets.
 *
 * The round trip only runs under Windows, because that is where DPAPI lives. The cases that do NOT
 * depend on the operating system -- legacy plain text, unusable ciphertext -- run everywhere and
 * are the more important ones: on them hangs whether an existing password is still readable after
 * the change.
 */
class SecretStoreTest : TestCase() {

  /**
   * A value without a marker is legacy data and has to come back unchanged. If that were lost,
   * all stored passwords would be unusable after the change -- and quietly so, because a wrong
   * password looks like a server problem.
   */
  fun testPlainTextFromBeforeStaysReadable() {
    assertEquals("geheim123", SecretStore.reveal("geheim123"))
    assertEquals("", SecretStore.reveal(""))
    // Also something that happens to look like Base64 but carries no marker.
    assertEquals("AAECAwQ=", SecretStore.reveal("AAECAwQ="))
  }

  /**
   * A marked but unusable value must not throw. It can come from a different machine; the
   * consequence is then a rejected login, not a crash at startup.
   */
  fun testUndecryptableValueDoesNotThrow() {
    val broken = "dpapi:###keinbase64###"
    assertEquals(broken, SecretStore.reveal(broken))
  }

  /** An empty secret is never stored -- there would be nothing to protect. */
  fun testEmptySecretIsNeverStored() {
    assertNull(SecretStore.protect(""))
  }

  /**
   * The actual purpose: what gets stored no longer contains the password, and it comes back
   * unchanged. Both halves together -- "comes back" alone would also be passed by a store that
   * encrypts nothing at all.
   */
  fun testRoundTripOnWindows() {
    if (!SecretStore.isAvailable) {
      return
    }
    val secret = "Passwort-mit-Umlauten-äöü-und-Zeichen-!\"§\$%&/()"
    val stored = SecretStore.protect(secret)
    assertNotNull("Unter Windows muss verschluesselt werden koennen", stored)
    assertFalse("Der gespeicherte Wert darf das Passwort nicht enthalten", stored!!.contains(secret))
    assertFalse("Kein Tabulator: er wuerde das Speicherformat der Serverliste zerlegen",
      stored.contains("\t"))
    assertFalse("Kein Zeilenumbruch: er wuerde eine zweite Serverzeile vortaeuschen",
      stored.contains("\n"))
    assertEquals(secret, SecretStore.reveal(stored))
  }
}
