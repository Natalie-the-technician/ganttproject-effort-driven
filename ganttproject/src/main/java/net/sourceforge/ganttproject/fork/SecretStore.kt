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

import net.sourceforge.ganttproject.GPLogger
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Verschluesselt gespeicherte Geheimnisse mit dem Windows-Anmeldekonto (DPAPI).
 *
 * WOZU: `GPCloudStorageOptions` legte das WebDAV-Passwort im KLARTEXT in `~/.ganttproject` ab,
 * sobald "Passwort speichern" gesetzt war. Jedes Programm, das unter demselben Benutzer laeuft,
 * konnte es lesen, und es wanderte in jede Sicherung dieser Datei. Der Haken wurde deshalb nicht
 * gesetzt und das Passwort dafuer bei jedem Start neu getippt.
 *
 * DPAPI bindet den Chiffretext an das Windows-Konto: eine kopierte Datei ist anderswo wertlos.
 * Das ist kein Tresor -- wer als dieser Benutzer Programme ausfuehren kann, kann auch
 * entschluesseln. Es beseitigt genau die Klasse von Fehlern, um die es hier geht: Passwoerter, die
 * in Sicherungen, Datenspeichern und ueber die Schulter geschaut sichtbar sind.
 *
 * GEMESSEN, nicht angenommen: `jna-platform` kommt nur mittelbar ueber `appdirs` herein, der Kern
 * `jna` liegt in einer anderen Fassung daneben (5.16 gegen 5.13). Ob DPAPI in dieser Mischung
 * ueberhaupt laeuft, war offen und wurde mit `tools/dpapiprobe` am ausgelieferten Klassenpfad
 * geprueft: Rundlauf in Ordnung, 246 Byte Chiffre, kein Klartext im Ergebnis.
 *
 * WAS AUF ANDEREN SYSTEMEN PASSIERT: nichts. [protect] liefert dort null, und der Aufrufer
 * speichert dann NICHT. Lieber weiter bei jedem Start fragen als heimlich Klartext schreiben --
 * ein Rueckfall auf "unsicher, aber bequem" waere genau der stille Fehler, den dieser Fork an
 * mehreren Stellen bereits gefunden hat. Fuer einen Beitrag an das Original braeuchte es hier
 * zusaetzlich libsecret (Linux) und Keychain (macOS).
 */
object SecretStore {

  /**
   * Kennzeichen vor dem Chiffretext. Base64 enthaelt weder Tabulator noch Zeilenumbruch, das
   * Speicherformat der Serverliste (durch Tabulatoren getrennt, eine Zeile je Server) bleibt also
   * unberuehrt.
   */
  private const val MARKER = "dpapi:"

  val isAvailable: Boolean = System.getProperty("os.name", "").startsWith("Windows")

  /**
   * @return den gekennzeichneten Chiffretext, oder null wenn nicht verschluesselt werden kann.
   * Null heisst ausdruecklich "nicht speichern" und nicht "im Klartext speichern".
   */
  /** Ob dieser gespeicherte Wert bereits verschluesselt ist. */
  fun isProtected(stored: String): Boolean = stored.startsWith(MARKER)

  fun protect(plain: String): String? {
    if (!isAvailable || plain.isEmpty()) {
      return null
    }
    return try {
      val cipher = com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(
        plain.toByteArray(StandardCharsets.UTF_8))
      MARKER + Base64.getEncoder().encodeToString(cipher)
    } catch (e: Throwable) {
      // Auch Error: eine fehlende Bibliothek darf das Speichern der Einstellungen nicht abbrechen.
      GPLogger.log(e)
      null
    }
  }

  /**
   * @return das Geheimnis im Klartext.
   *
   * Ein Wert OHNE Kennzeichen wird unveraendert zurueckgegeben: so bleiben Eintraege lesbar, die
   * vor dieser Aenderung im Klartext geschrieben wurden. Beim naechsten Speichern werden sie
   * verschluesselt.
   *
   * Schlaegt das Entschluesseln fehl, wird der Wert ebenfalls unveraendert zurueckgegeben statt
   * eine Ausnahme zu werfen. Der seltene Fall, dass ein altes Klartextpasswort zufaellig mit
   * "dpapi:" beginnt, faellt damit auf das richtige Verhalten zurueck -- und ein auf einem anderen
   * Rechner verschluesselter Wert fuehrt zu einer abgelehnten Anmeldung, nicht zu einem Absturz.
   */
  fun reveal(stored: String): String {
    if (!stored.startsWith(MARKER)) {
      return stored
    }
    return try {
      val cipher = Base64.getDecoder().decode(stored.removePrefix(MARKER))
      String(com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(cipher), StandardCharsets.UTF_8)
    } catch (e: Throwable) {
      GPLogger.log(e)
      stored
    }
  }
}
