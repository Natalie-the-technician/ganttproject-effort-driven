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

import net.sourceforge.ganttproject.GPLogger
import net.sourceforge.ganttproject.fork.SecretStore

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
    .joinToString(PAIR_SEPARATOR) {
      "${it.key.urlEncoded()}$FIELD_SEPARATOR${protectedToken(it.key, it.value).urlEncoded()}"
    }

/**
 * [fork change] The token as it is written into the settings file: encrypted.
 *
 * Before, it lay there in plain text -- readable by everything running under the same user
 * account. For the WebDAV password that has been solved since 17.08.2026; here the token follows.
 *
 * TWO PECULIARITIES, both deliberate:
 *
 * 1. THE PROMISE "unchanged content always yields the same line" NO LONGER HOLDS AS STATED, and
 *    that is intentional: DPAPI mixes in randomness so that two identical secrets cannot be
 *    recognised by an identical ciphertext. The stored text therefore looks different after every
 *    write, its CONTENT stays the same. What the promise was meant to protect -- no change without
 *    a reason -- concerns a settings file that gets rewritten on every exit anyway; that the map
 *    carries plain text everywhere is the more important value.
 * 2. If the secret cannot be kept (no store on this machine), the previous behaviour stands rather
 *    than losing the token. For the password "do not store" was the right answer, because it can be
 *    typed again; a lost token, by contrast, only shows up at the next import, and then the cause
 *    is missing. It has to be FETCHED AGAIN FROM THE TOGGL WEBSITE, which is a different kind of
 *    cost from typing a password one knows.
 *
 * [fork change] 09.09.2026: THE FALLBACK NO LONGER HAPPENS IN SILENCE. Writing a secret in the
 * clear is a decision, and until now nobody was told it had been taken -- the token simply stood in
 * `~/.ganttproject` and looked no different from a setting. The log line says which file and which
 * setting. It NEVER says the token; not even shortened.
 *
 * [fork change] 09.09.2026: the alias. The map key -- `mail=…` or `name=…` -- is what identifies
 * whose token this is, and it is the same string at every save, which is what the keyring backends
 * need. It is not a secret: it already stands unencrypted in the same file, right next to the
 * token, as the first half of the pair.
 */
private fun protectedToken(key: String, token: String): String {
  if (SecretStore.isProtected(token)) {
    return token
  }
  val protectedValue = SecretStore.protect("toggl:$key", token)
  if (protectedValue != null) {
    return protectedValue
  }
  warnAboutPlainTextTokenOnce()
  return token
}

/**
 * [fork change] Says once per run that a token is being written in the clear.
 *
 * ONCE, not once per write: `~/.ganttproject` is rewritten whenever the settings change, and a
 * warning that appears fifty times is one nobody reads. Once per run is enough for the fact to be
 * in the log of the session in which it happened.
 *
 * This is only half an answer, and it is meant to be seen as one. The log is not where a person
 * looks. What belongs here is a note in the dialogue that holds the token field, at the moment the
 * token is entered -- see the report of 09.09.2026; that is a decision about the user interface and
 * not one to be taken in passing in a storage function.
 */
private var plainTextTokenWarned = false

private fun warnAboutPlainTextTokenOnce() {
  if (plainTextTokenWarned) {
    return
  }
  plainTextTokenWarned = true
  GPLogger.log("[fork] No secret store on this machine (backend: ${SecretStore.backendName}). "
    + "The Toggl API token is written to ~/.ganttproject IN PLAIN TEXT, under the setting "
    + "'toggl.tokens'. Anything running as this user can read it, and it is in every backup of "
    + "that file. Revoke the token in Toggl if that file leaves this machine.")
}

/** Reads the store back. Unreadable pairs are skipped rather than failing the whole settings file. */
fun decodeTokenMap(text: String?): Map<String, String> {
  val result = mutableMapOf<String, String>()
  text?.split(PAIR_SEPARATOR)?.forEach { pair ->
    val fields = pair.split(FIELD_SEPARATOR)
    if (fields.size == 2) {
      val key = fields[0].urlDecodedOrNull()
      val token = fields[1].urlDecodedOrNull()
      if (!key.isNullOrEmpty() && !token.isNullOrEmpty()) {
        // [fork change] Decrypted here, not first at the point of use: the map carries plain
        // text. Everything else in the program -- comparisons, moving, the collision check --
        // works with tokens, not with ciphertexts. A ciphertext in the map did damage in exactly
        // that place on a first attempt: two ciphertexts of the SAME token are different, and the
        // collision question would have been asked where there is none.
        result[key] = SecretStore.reveal(token)
      }
    }
  }
  return result
}

/** The token stored under [key], or null. */
fun tokenForKey(key: String, storedTokens: String?): String? = decodeTokenMap(storedTokens)[key]

/**
 * [fork change] The store after a person's KEY may have changed.
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
  if (token.isEmpty()) {
    // [fork change] Remove ONLY one's own entry. Formerly an unconditional `tokens.remove(newKey)`
    // stood here -- anyone who emptied the token field in the dialog AND at the same time entered
    // an address under which somebody else was already stored thereby deleted that person's
    // token. If the key is unchanged, the entry under [newKey] is one's own and has to go; if it
    // changed, whatever lies there belongs to somebody else.
    if (newKey == previousKey) tokens.remove(newKey)
  } else {
    tokens[newKey] = token
  }
  return encodeTokenMap(tokens)
}

/**
 * [fork change] What has to happen to the store after a person's key changed.
 *
 * Deliberately a result type rather than a plain string: the interesting case is the one where
 * TWO tokens claim the same key, and that one cannot be decided here — it costs a secret either
 * way, so the person has to say which.
 */
sealed interface TokenKeyChange {
  /** Key unchanged, or nothing stored to move. The settings file stays untouched. */
  object Unchanged : TokenKeyChange

  /** Unambiguous: write [tokens]. */
  data class Move(val tokens: String) : TokenKeyChange

  /**
   * Two DIFFERENT tokens claim [key], and one of them will be gone whatever happens.
   *
   * Carries the two finished store texts rather than the tokens themselves, so whoever asks the
   * question never has to handle a secret — it picks one of these and writes it.
   *
   * @property ifOverwritten the moving token wins; the one stored under [key] until now is lost.
   * @property ifDiscarded the stored token stays; the moving one is dropped — deliberately dropped
   * and not left under the old key, because a token under a key nobody looks up is exactly the
   * abandoned secret this whole mechanism exists to avoid.
   */
  data class Collision(
    val key: String,
    val ifOverwritten: String,
    val ifDiscarded: String
  ) : TokenKeyChange
}

/**
 * [fork change] Works out what a changed key means for the store.
 *
 * Counterpart to [movedToken] for the second way a key can change. [movedToken] is used where the
 * token field is on screen and its value is known. Here the token is not being edited at all —
 * somebody renamed a resource or filled in its e-mail address, and the token simply has to follow.
 *
 * WHY A COLLISION IS POSSIBLE AT ALL: the key is the e-mail address, which identifies the Toggl
 * ACCOUNT. Two resources with the same address are therefore the same account — and a collision
 * means two different tokens were entered for one account. One of them is stale, but which one
 * cannot be told from here.
 *
 * Same token under both keys is NOT a collision: nothing is lost by dropping the duplicate.
 */
fun tokenKeyChange(
  storedTokens: String?,
  previousKey: String,
  newKey: String,
  editedToken: String? = null
): TokenKeyChange {
  // Two callers, one rule. The resource dialog KNOWS the token, because it is in a field on
  // screen; the resource table does not and has to read it out of the store. Everything after
  // this line is identical for both — otherwise the same collision would be caught on one path
  // and silently destroy a token on the other, which is exactly what happened before.
  val moving = editedToken ?: tokenForKey(previousKey, storedTokens) ?: return TokenKeyChange.Unchanged

  val target = movedToken(storedTokens, previousKey, newKey, moving)
  // Compare against the NORMALISED text, not the raw one: a store that was written by an older
  // version may differ in order or encoding without differing in content, and rewriting it for
  // that reason alone would churn the settings file.
  // [fork change] Compare CONTENTS, not texts. Since the token is stored encrypted, two texts of
  // the same content are never equal again -- DPAPI mixes in randomness. A text comparison would
  // never have yielded "Unchanged" here again, and every edit of a resource would have rewritten
  // the settings file. Measured on the machine, found by
  // testAnUntouchedDialogChangesNothing.
  if (decodeTokenMap(target) == decodeTokenMap(storedTokens)) return TokenKeyChange.Unchanged

  // A collision needs the key to actually move, a token to move there, and a DIFFERENT one
  // already sitting in the way. An emptied field is a removal, not a collision.
  if (newKey != previousKey && moving.isNotEmpty()) {
    val occupying = tokenForKey(newKey, storedTokens)
    if (occupying != null && occupying != moving) {
      return TokenKeyChange.Collision(
        key = newKey,
        ifOverwritten = target,
        ifDiscarded = encodeTokenMap(decodeTokenMap(storedTokens).toMutableMap().also {
          it.remove(previousKey)
        }))
    }
  }

  return TokenKeyChange.Move(target)
}

/**
 * [fork change] The key without its technical prefix, for showing to a person.
 *
 * `mail=nati@example.org` is not something to put in front of somebody who never asked how the
 * store is built. A key without a prefix is shown unchanged rather than as an empty string — a
 * blank in a question about losing a secret would be worse than an odd-looking one.
 */
fun readableKey(key: String): String = key.substringAfter('=', key)

private fun String.urlEncoded(): String = URLEncoder.encode(this, Charsets.UTF_8)

private fun String.urlDecodedOrNull(): String? =
  try {
    URLDecoder.decode(this, Charsets.UTF_8)
  } catch (e: IllegalArgumentException) {
    null
  }
