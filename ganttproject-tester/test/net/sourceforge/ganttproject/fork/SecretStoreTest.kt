/*
Copyright 2026

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
package net.sourceforge.ganttproject.fork

import junit.framework.TestCase

/**
 * Die Ablage fuer Geheimnisse.
 *
 * Der Rundlauf laeuft nur unter Windows, weil DPAPI dort liegt. Die Faelle, die NICHT vom
 * Betriebssystem abhaengen -- Altbestand im Klartext, unbrauchbarer Chiffretext -- laufen ueberall
 * und sind die wichtigeren: an ihnen haengt, ob ein vorhandenes Passwort nach der Umstellung noch
 * lesbar ist.
 */
class SecretStoreTest : TestCase() {

  /**
   * Ein Wert ohne Kennzeichen ist Altbestand und muss unveraendert zurueckkommen. Ginge das
   * verloren, waeren nach der Umstellung alle gespeicherten Passwoerter unbrauchbar -- und zwar
   * still, denn ein falsches Passwort sieht aus wie ein Serverproblem.
   */
  fun testPlainTextFromBeforeStaysReadable() {
    assertEquals("geheim123", SecretStore.reveal("geheim123"))
    assertEquals("", SecretStore.reveal(""))
    // Auch etwas, das zufaellig nach Base64 aussieht, aber kein Kennzeichen traegt.
    assertEquals("AAECAwQ=", SecretStore.reveal("AAECAwQ="))
  }

  /**
   * Ein gekennzeichneter, aber unbrauchbarer Wert darf nicht werfen. Er kann von einem anderen
   * Rechner stammen; dann ist die Folge eine abgelehnte Anmeldung, kein Absturz beim Start.
   */
  fun testUndecryptableValueDoesNotThrow() {
    val broken = "dpapi:###keinbase64###"
    assertEquals(broken, SecretStore.reveal(broken))
  }

  /** Leeres Geheimnis wird nie gespeichert -- es gaebe nichts zu schuetzen. */
  fun testEmptySecretIsNeverStored() {
    assertNull(SecretStore.protect(""))
  }

  /**
   * Der eigentliche Zweck: was gespeichert wird, enthaelt das Passwort nicht mehr, und es kommt
   * unveraendert zurueck. Beide Haelften zusammen -- nur "kommt zurueck" wuerde auch eine Ablage
   * bestehen, die gar nichts verschluesselt.
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
