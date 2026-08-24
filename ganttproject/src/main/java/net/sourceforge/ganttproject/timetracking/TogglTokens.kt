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
package net.sourceforge.ganttproject.timetracking

import biz.ganttproject.core.option.DefaultIntegerOption
import biz.ganttproject.core.option.DefaultStringOption
import biz.ganttproject.core.option.GPOptionGroup
import biz.ganttproject.core.option.IntegerOption
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

  /**
   * [fork change] How far back the import looks, in days.
   *
   * Remembered instead of typed anew every time: whoever once chose 180 days usually wants that
   * again. Lives in the application settings, not in the project — the period is a habit of the
   * person, not a property of the plan.
   */
  val importDays: IntegerOption = DefaultIntegerOption("importDays", DEFAULT_IMPORT_DAYS)

  val optionGroup: GPOptionGroup = GPOptionGroup("toggl", tokens, importDays)
}

/** Default of the import period. */
const val DEFAULT_IMPORT_DAYS = 30

/** Lower bound: less than one day is not a period. */
const val MIN_IMPORT_DAYS = 1

/**
 * Upper bound: 90 days.
 *
 * NOT chosen by us but Toggl's limit. `/me/time_entries` delivers at most three months at a time
 * and answers larger periods with status 400.
 *
 * Seen against the live service: 30 days work, 99 and 300 do not. Before, 3650 stood here — a
 * limit that does not exist at all, so the field accepted values the service is certain to
 * reject.
 */
const val MAX_IMPORT_DAYS = 90

/**
 * [fork change] Parses the number of days that was typed in.
 *
 * A function of its own instead of `toIntOrNull()` at the call site, so that the edge cases can be
 * checked: empty field, letters, 0, negative numbers. Each of them would otherwise yield a period
 * nobody meant.
 *
 * @return the number of days, or null when the text does not yield a usable one.
 */
/**
 * [fork change] The value the period dialog is pre-filled with.
 *
 * The stored value can lie outside the bounds — and did exactly that: while the upper bound was
 * still (wrongly) 3650, 93 days stayed in the settings. Without clamping, a number would stand in
 * the field at the next opening that Toggl rejects with 400, and the user would have to guess
 * why.
 *
 * Clamped rather than rejected: it is not a typo but a value from an older version. Pulling it
 * quietly to something usable is right here, because it stands visibly in the field and still has
 * to be confirmed.
 */
fun usableImportDays(stored: Int?): Int =
  (stored ?: DEFAULT_IMPORT_DAYS).coerceIn(MIN_IMPORT_DAYS, MAX_IMPORT_DAYS)

fun parseImportDays(text: String?): Int? {
  val value = text?.trim()?.toIntOrNull() ?: return null
  return if (value in MIN_IMPORT_DAYS..MAX_IMPORT_DAYS) value else null
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
 * [fork change] Runs [change] and takes the stored token along if the key changed.
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
fun HumanResource.keepingTokenReachable(
  askOnCollision: TokenCollisionAsker = TokenCollisionAsker.LEAVE_EVERYTHING_ALONE,
  change: () -> Unit
) {
  val previousKey = tokenKeyFor(this)
  change()
  when (val outcome = tokenKeyChange(TogglTokenOptions.tokens.value, previousKey, tokenKeyFor(this))) {
    is TokenKeyChange.Unchanged -> Unit
    is TokenKeyChange.Move -> TogglTokenOptions.tokens.value = outcome.tokens
    is TokenKeyChange.Collision ->
      askOnCollision.ask(outcome) { chosen -> TogglTokenOptions.tokens.value = chosen }
  }
}

/**
 * [fork change] Asks which of two tokens survives when both claim the same key.
 *
 * ASYNCHRONOUS ON PURPOSE. The edit that changed the key happens on the interface thread, and a
 * dialog must not block it. [apply] may therefore be called much later — or never, which is a
 * valid outcome meaning "leave the store as it is". The token move is independent of the edit
 * itself, so nothing is inconsistent in between.
 */
fun interface TokenCollisionAsker {
  fun ask(collision: TokenKeyChange.Collision, apply: (String) -> Unit)

  companion object {
    /**
     * For callers that cannot ask — tests, and any import path with nobody at the screen.
     *
     * Does nothing at all, and that is the deliberate choice: overwriting would destroy a token
     * behind the user's back, and discarding would do the same to the other one. Leaving both
     * where they are loses nothing; the person can sort it out in the resource dialog.
     */
    @JvmField
    val LEAVE_EVERYTHING_ALONE = TokenCollisionAsker { _, _ -> }
  }
}
