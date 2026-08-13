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
 *
 * This file holds the half that knows about resources and settings. The text handling itself —
 * encoding, decoding, changing an entry — sits in `TokenStore.kt`, which needs neither the model
 * nor JavaFX and can therefore be run in any session.
 */
object TogglTokenOptions {
  /** All tokens in one option, encoded by [encodeTokenMap]. */
  val tokens: StringOption = DefaultStringOption("resourceTokens", "")

  val optionGroup: GPOptionGroup = GPOptionGroup("toggl", tokens)
}

/**
 * How a person is identified in the token store.
 *
 * The e-mail address first: it identifies a Toggl account and survives a rename. The name is the
 * fallback, prefixed so that a name can never be mistaken for an address.
 *
 * The key therefore CHANGES when the address is added or edited. Whoever saves a token has to deal
 * with that — see [movedToken].
 */
fun tokenKeyFor(resource: HumanResource): String {
  val mail = resource.mail?.trim().orEmpty()
  return if (mail.isNotEmpty()) "mail=$mail" else "name=${resource.name?.trim().orEmpty()}"
}

/** The token for this person, or null when none is stored. */
fun tokenFor(resource: HumanResource, storedTokens: String?): String? =
  tokenForKey(tokenKeyFor(resource), storedTokens)

/** The store with this person's token set. An empty token removes the entry. */
fun withToken(storedTokens: String?, resource: HumanResource, token: String): String =
  tokenKeyFor(resource).let { key -> movedToken(storedTokens, key, key, token) }

/**
 * [Fork-Aenderung] Runs [change] and takes the stored token along if the key changed.
 *
 * WHY THIS EXISTS BESIDE THE DIALOG: `MainPropertiesPanel.save()` already handles the case where
 * name or e-mail are edited in the resource dialog. But both can ALSO be edited straight in the
 * resource table (`ResourceTable.setValue`), which never goes through that dialog. Without this,
 * typing an address into the e-mail cell has exactly the effect the dialog was fixed for: the
 * connection check reports "no token" although one was entered, and the secret stays behind in
 * `~/.ganttproject` under a key nobody looks up any more.
 *
 * Deliberately no token argument: nobody is editing the token here, it only has to follow. The
 * settings are written only when something actually moved — see [tokensAfterKeyChange].
 */
fun HumanResource.keepingTokenReachable(change: () -> Unit) {
  val previousKey = tokenKeyFor(this)
  change()
  tokensAfterKeyChange(TogglTokenOptions.tokens.value, previousKey, tokenKeyFor(this))
    ?.let { TogglTokenOptions.tokens.value = it }
}
