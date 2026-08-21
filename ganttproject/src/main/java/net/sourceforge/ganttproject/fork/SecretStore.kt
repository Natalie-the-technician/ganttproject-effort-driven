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

import net.sourceforge.ganttproject.GPLogger
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Encrypts stored secrets with the Windows login account (DPAPI).
 *
 * WHAT FOR: `GPCloudStorageOptions` stored the WebDAV password in PLAIN TEXT in `~/.ganttproject`
 * as soon as "save password" was set. Every program running under the same user could read it, and
 * it went into every backup of that file. The checkbox was therefore left unset and the password
 * typed anew on every start.
 *
 * DPAPI ties the ciphertext to the Windows account: a copied file is worthless elsewhere. This is
 * no vault -- whoever can run programs as this user can decrypt as well. It removes exactly the
 * class of mistakes at issue here: passwords that are visible in backups, in data stores, and over
 * the shoulder.
 *
 * MEASURED, not assumed: `jna-platform` only comes in indirectly via `appdirs`, the core `jna`
 * sits beside it in a different version (5.16 against 5.13). Whether DPAPI runs at all in this
 * mixture was open and was checked with `tools/dpapiprobe` against the shipped classpath: round
 * trip in order, 246 bytes of ciphertext, no plain text in the result.
 *
 * WHAT HAPPENS ON OTHER SYSTEMS: nothing. [protect] returns null there, and the caller then does
 * NOT store. Better to keep asking on every start than to write plain text in secret -- a fallback
 * to "insecure but convenient" would be exactly the quiet mistake this fork has already found in
 * several places. For a contribution to the original this would additionally need libsecret
 * (Linux) and Keychain (macOS).
 */
object SecretStore {

  /**
   * Marker in front of the ciphertext. Base64 contains neither a tab nor a line break, so the
   * storage format of the server list (separated by tabs, one line per server) remains
   * untouched.
   */
  private const val MARKER = "dpapi:"

  val isAvailable: Boolean = System.getProperty("os.name", "").startsWith("Windows")

  /**
   * @return the marked ciphertext, or null if encryption is not possible. Null explicitly means
   * "do not store" and not "store in plain text".
   */
  /** Whether this stored value is already encrypted. */
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
      // Error too: a missing library must not abort the saving of the settings.
      GPLogger.log(e)
      null
    }
  }

  /**
   * @return the secret in plain text.
   *
   * A value WITHOUT a marker is returned unchanged: that way entries stay readable which were
   * written in plain text before this change. At the next save they get encrypted.
   *
   * If decryption fails, the value is likewise returned unchanged instead of throwing an
   * exception. The rare case of an old plain-text password that happens to start with "dpapi:"
   * thereby falls back to the right behaviour -- and a value encrypted on a different machine
   * leads to a rejected login, not to a crash.
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
