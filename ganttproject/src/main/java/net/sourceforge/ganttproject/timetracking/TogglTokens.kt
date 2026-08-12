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

import biz.ganttproject.core.option.DefaultStringOption
import biz.ganttproject.core.option.GPOptionGroup
import biz.ganttproject.core.option.StringOption
import net.sourceforge.ganttproject.resource.HumanResource
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Where the Toggl access tokens live: in the APPLICATION settings (`~/.ganttproject`), never in
 * the project file.
 *
 * The project file is shared and kept in a vault. A token in it would be handed to everyone who
 * receives the file, and it grants full access to that person's Toggl account.
 *
 * WHAT IS NOT PROTECTED HERE, stated plainly rather than implied: `~/.ganttproject` is a plain
 * text file. The token is encoded so that it survives the format, NOT encrypted. Anything running
 * under the same user account can read it. Keeping it out of the shared file is the point;
 * protecting it from the local machine is not, and this code should not be read as if it were.
 *
 * KEYED BY PERSON, NOT BY RESOURCE ID — a deliberate deviation from the handover, which said
 * "resource id → token". Resource ids are handed out per project (1, 2, 3 …), so id 1 means a
 * different person in every project, while these settings are global. Two projects would
 * overwrite each other's tokens, and one person's token would be sent for another person's
 * entries. The key is therefore the e-mail address, and the name when no address is set.
 */
object TogglTokenOptions {
  /** All tokens in one option, encoded by [encodeTokenMap]. */
  val tokens: StringOption = DefaultStringOption("resourceTokens", "")

  val optionGroup: GPOptionGroup = GPOptionGroup("toggl", tokens)
}

private const val PAIR_SEPARATOR = ";"
private const val FIELD_SEPARATOR = ":"

/**
 * How a person is identified in the token store.
 *
 * The e-mail address first: it identifies a Toggl account and survives a rename. The name is the
 * fallback, prefixed so that a name can never be mistaken for an address.
 */
fun tokenKeyFor(resource: HumanResource): String {
  val mail = resource.mail?.trim().orEmpty()
  return if (mail.isNotEmpty()) "mail=$mail" else "name=${resource.name?.trim().orEmpty()}"
}

/**
 * Encodes the whole store into one line.
 *
 * Key and token are URL-encoded, because both may contain the separators — a token is an opaque
 * string from Toggl, and an e-mail address or a name may contain almost anything. Without the
 * encoding a token containing a semicolon would silently truncate the store.
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

/** The token for this person, or null when none is stored. */
fun tokenFor(resource: HumanResource, storedTokens: String?): String? =
  decodeTokenMap(storedTokens)[tokenKeyFor(resource)]

/** The store with this person's token set. An empty token removes the entry. */
fun withToken(storedTokens: String?, resource: HumanResource, token: String): String {
  val tokens = decodeTokenMap(storedTokens).toMutableMap()
  val key = tokenKeyFor(resource)
  if (token.isEmpty()) tokens.remove(key) else tokens[key] = token
  return encodeTokenMap(tokens)
}

private fun String.urlEncoded(): String = URLEncoder.encode(this, Charsets.UTF_8)

private fun String.urlDecodedOrNull(): String? =
  try {
    URLDecoder.decode(this, Charsets.UTF_8)
  } catch (e: IllegalArgumentException) {
    null
  }
