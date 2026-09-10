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

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * The one place that decides what happens to a secret before it is written to disk.
 *
 * WHAT FOR: `GPCloudStorageOptions` stored the WebDAV password in PLAIN TEXT in `~/.ganttproject`
 * as soon as "save password" was set, and `TokenStore` did the same with the Toggl API token. Every
 * program running under the same user could read them, and they went into every backup of that
 * file.
 *
 * WHAT CHANGED ON 09.09.2026: until then this was DPAPI and nothing else, so the fix only ever
 * reached Windows. Outside Windows the password was simply not stored (the "save password" box
 * looked as if it worked and did nothing) and the Toggl token WAS still written in the clear. There
 * are now three ways -- see [SecretBackend] -- and which one applies is decided by
 * [secretBackendsFor] and by a probe, not by the name of the operating system alone.
 *
 * THIS IS NO VAULT. Whoever can run programs as this user can get at the secrets as well, on all
 * three platforms. What it removes is the class of mistakes actually at issue: secrets readable in
 * backups, in synchronised data directories, and over the shoulder.
 *
 * WHAT HAPPENS WHERE THERE IS NO STORE AT ALL -- a server, a container, a stripped-down desktop:
 * [protect] returns null. Null means "do not store" and NEVER "store in the clear". The callers
 * differ in what they make of that, and deliberately so:
 *
 *  * The WebDAV password is not stored. It can be typed again.
 *  * The Toggl token IS still written in the clear, because it cannot be typed again -- it has to
 *    be fetched from the Toggl website. `TokenStore` says so in the log when it does.
 *
 * NO THIRD WAY WAS BUILT, and that is a decision and not an omission: encrypting with a key derived
 * on the machine itself. A key that lies next to the secret is not encryption, it is the look of
 * it, and the look is worse than plain text because somebody will rely on it.
 */
object SecretStore {

  /**
   * Every marker any version of this code has ever written, whatever platform wrote it.
   *
   * All of them, not just this platform's: a settings file travels between machines, and something
   * written on Windows has to be RECOGNISED on Linux even though it cannot be read there. Were
   * `dpapi:` missing from this list on Linux, [isProtected] would call a ciphertext a plain-text
   * token and hand it to the keyring as one.
   */
  private val MARKERS = listOf(DpapiBackend.marker, LibsecretBackend.marker, MacKeychainBackend.marker)

  /**
   * The way in use, or null if there is none here.
   *
   * Lazy on purpose. Deciding this means probing, probing means running a program, and on a locked
   * keyring that means a dialogue. None of that belongs in the start-up of a program that may never
   * touch a secret at all.
   */
  private val backend: SecretBackend? by lazy {
    secretBackendsFor(System.getProperty("os.name", "")).firstOrNull { it.isAvailable() }
  }

  /** Whether a secret can really be kept on this machine right now. */
  val isAvailable: Boolean get() = backend != null

  /** For the log and for the report. Never a secret. */
  val backendName: String get() = backend?.name ?: "none"

  /** Whether this stored value is a reference or a ciphertext rather than a bare secret. */
  fun isProtected(stored: String): Boolean = MARKERS.any { stored.startsWith(it) }

  /**
   * @param alias what this secret IS -- which server, whose token. Not a secret itself, and it must
   * be the same string every time the same secret is saved. The keyring backends store the secret
   * under it, and a changing alias would leave an orphaned keyring entry behind at every save.
   * DPAPI ignores it.
   * @return the marked value for the settings file, or null if nothing may be written. Null
   * explicitly means "do not store" and not "store in plain text".
   */
  fun protect(alias: String, plain: String): String? {
    if (alias.isEmpty() || plain.isEmpty()) {
      return null
    }
    return backend?.protect(handleFor(alias), plain)
  }

  /**
   * @return the secret in plain text.
   *
   * A value WITHOUT a marker is returned unchanged: that way entries stay readable which were
   * written in plain text before this change. At the next save they get protected.
   *
   * A value with a marker that is NOT this platform's is likewise returned unchanged -- a Windows
   * ciphertext read on Linux, a keyring reference read on Windows. So is a value this platform's
   * store cannot resolve: keyring locked, entry deleted, ciphertext from another machine. The
   * consequence is then a rejected login, not a crash at startup, and that is the deliberate
   * choice: the rare old plain-text password that happens to begin with "dpapi:" falls back to the
   * right behaviour by the same rule.
   */
  fun reveal(stored: String): String {
    val backend = this.backend ?: return stored
    if (!stored.startsWith(backend.marker)) {
      return stored
    }
    return backend.reveal(stored.removePrefix(backend.marker)) ?: stored
  }

  /**
   * The alias as it may appear in a command line and in a settings file.
   *
   * An alias is built from things a person typed -- a server URL, a user name, an e-mail address --
   * and those may contain anything at all. Two of the things they may contain would do damage:
   *
   *  * A TAB OR A LINE BREAK would take the WebDAV server list apart. It writes one line per server
   *    with tab-separated fields, so a tab inside a field invents one, and a line break invents a
   *    whole server.
   *  * A LEADING "-" would be read as an option by the keyring programs.
   *
   * URL-safe Base64 has neither, and the leading "a" settles the second point. Nothing has to
   * decode it again: what goes into the file and what goes to the keyring as a name are the same
   * string.
   */
  private fun handleFor(alias: String): String =
    "a" + Base64.getUrlEncoder().withoutPadding()
      .encodeToString(alias.toByteArray(StandardCharsets.UTF_8))
}
