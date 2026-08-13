/*
Copyright 2026 Noctuvo

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
package net.sourceforge.ganttproject.timetracking

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The token store as pure text handling: encode, decode, change an entry. Knows nothing about
 * resources, projects or the settings file — see [TogglTokens.kt] for that half.
 *
 * WHY THIS FILE IS SEPARATE: everything here can be compiled and run without the GanttProject
 * model, without a database and without JavaFX. That matters for this particular code, because it
 * handles a secret and because a session that cannot run the full Gradle build (see
 * `CLAUDE-NOTES.md`, section 13) can still verify it. Splitting it out was not tidiness for its
 * own sake.
 *
 * FORMAT: `key:token` pairs separated by `;`, both halves URL-encoded so that a separator inside a
 * token or a name cannot cut the store in half.
 */

private const val PAIR_SEPARATOR = ";"
private const val FIELD_SEPARATOR = ":"

/**
 * Encodes the whole store into one line.
 *
 * Key and token are URL-encoded, because both may contain the separators — a token is an opaque
 * string from Toggl, and an e-mail address or a name may contain almost anything. Without the
 * encoding a token containing a semicolon would silently truncate the store.
 *
 * Sorted by key so that unchanged content always yields the same line: the settings file should
 * not change just because it was written again.
 */
fun encodeTokenMap(tokens: Map<String, String>): String =
  tokens.entries
    .filter { it.value.isNotEmpty() }
    .sortedBy { it.key }
    .joinToString(PAIR_SEPARATOR) { "${it.key.urlEncoded()}$FIELD_SEPARATOR${it.value.urlEncoded()}" }

/** Reads the store back. Unreadable pairs are skipped rather than failing the whole settings file. */
fun decodeTokenMap(text: String?): Map<String, String> {
  val result = mutableMapOf<String, String>()
  text?.split(PAIR_SEPARATOR)?.forEach { pair ->
    val fields = pair.split(FIELD_SEPARATOR)
    if (fields.size == 2) {
      val key = fields[0].urlDecodedOrNull()
      val token = fields[1].urlDecodedOrNull()
      if (!key.isNullOrEmpty() && !token.isNullOrEmpty()) {
        result[key] = token
      }
    }
  }
  return result
}

/** The token stored under [key], or null. */
fun tokenForKey(key: String, storedTokens: String?): String? = decodeTokenMap(storedTokens)[key]

/**
 * [Fork-Aenderung] The store after a person's KEY may have changed.
 *
 * The key is derived from the e-mail address, or from the name when there is none — and both can
 * be edited in the very dialog that also edits the token. Without this step the token stays under
 * the old key: invisible to the person it belongs to (the connection check reports "no token"
 * although one was entered) and left behind in `~/.ganttproject` as a secret nobody looks up.
 *
 * The case is not exotic. The most likely one is not even a changed address: create a resource
 * with a name, enter the token, add the e-mail address later — at that moment the key changes from
 * `name=…` to `mail=…`.
 *
 * [previousKey] is always cleared, whether or not the token itself was edited. An empty [token]
 * removes the entry entirely.
 */
fun movedToken(storedTokens: String?, previousKey: String, newKey: String, token: String): String {
  val tokens = decodeTokenMap(storedTokens).toMutableMap()
  tokens.remove(previousKey)
  // EHRLICH VERMERKT: der Zweig fuer den leeren Token ist doppelt abgesichert -- [encodeTokenMap]
  // laesst leere Werte ohnehin weg. Ein Gegentest kann diese Zeile deshalb nicht isolieren
  // (nachgewiesen: entfernt man sie, bleiben alle Tests gruen). Sie bleibt trotzdem, weil sie den
  // Vertrag schon auf der Abbildung wahr macht und nicht erst im Text.
  if (token.isEmpty()) tokens.remove(newKey) else tokens[newKey] = token
  return encodeTokenMap(tokens)
}

private fun String.urlEncoded(): String = URLEncoder.encode(this, Charsets.UTF_8)

private fun String.urlDecodedOrNull(): String? =
  try {
    URLDecoder.decode(this, Charsets.UTF_8)
  } catch (e: IllegalArgumentException) {
    null
  }
