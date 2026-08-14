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
package net.sourceforge.ganttproject.document.webdav

/**
 * Which ETag — if any — a write should carry in `If-Match`.
 *
 * WHY THIS IS ITS OWN FUNCTION: the whole point of conditional writing is that a write is refused
 * when somebody else changed the file in the meantime. Getting the condition wrong fails in one of
 * two ways, and both are bad in a way nobody notices at the time: too weak a condition overwrites
 * a colleague's work silently, too strict a one reports a conflict that does not exist and trains
 * the user to click "overwrite anyway". Deciding it in one place makes it testable without a
 * server.
 *
 * THE TRAP, and it is not hypothetical — the Android app walked into it first:
 *
 * Apache with `mod_dav_fs` returns **no** ETag on `PUT`, only on `HEAD`/`GET`/`PROPFIND`. And for
 * about one second after a write it reports the ETag as **weak** (`W/"…"`), afterwards the very
 * same value as strong. `If-Match` is compared **strongly** (RFC 7232 §3.1), so a weak tag matches
 * nothing at all — not even itself. Sending a freshly fetched weak tag back therefore yields `412`
 * on **every** save, and shows the user a conflict that never happened.
 */
sealed interface IfMatchDecision {
  /** Send this ETag in `If-Match`. */
  data class Send(val etag: String) : IfMatchDecision

  /**
   * Write without a condition.
   *
   * Only reached when the server has just confirmed that the file still carries the very content
   * we last wrote, and merely reports it weakly. A deliberate, narrow concession: for those
   * milliseconds it reopens the check-then-write gap the server exists to close. The alternative
   * is refusing to save whenever somebody saves twice within a second — a certain annoyance
   * traded against a remote risk.
   */
  data object Unconditional : IfMatchDecision

  /** Somebody else changed the file. Do not write. */
  data object Conflict : IfMatchDecision
}

/** `W/"abc"` is weak, `"abc"` is strong. */
fun isWeakEtag(etag: String): Boolean = etag.trimStart().startsWith("W/")

/**
 * The tag without its weakness marker, for comparing "same content" across a weak/strong change.
 *
 * NOT for comparing two ETags in general: dropping `W/` and treating the rest as equal is exactly
 * the shortcut that would let a genuinely stale write through. It is used here only after the
 * server told us the current value, to tell "the same file, just freshly written" from "somebody
 * else's file".
 */
fun opaqueEtag(etag: String): String = etag.trim().removePrefix("W/").trim()

/**
 * @param remembered the ETag the pending changes are based on, or null when none is known.
 * @param currentFromServer asks the server for the ETag the file carries right now. Called ONLY
 * when [remembered] is weak — one extra request in a rare case, none in the normal one. Returns
 * null when the question cannot be answered.
 */
fun resolveIfMatch(remembered: String?, currentFromServer: () -> String?): IfMatchDecision {
  if (remembered == null) return IfMatchDecision.Unconditional
  if (!isWeakEtag(remembered)) return IfMatchDecision.Send(remembered)

  // Cannot ask: send the weak tag and let the server refuse. Refusing is the safe direction,
  // writing blind is not.
  val current = currentFromServer() ?: return IfMatchDecision.Send(remembered)

  if (opaqueEtag(current) != opaqueEtag(remembered)) return IfMatchDecision.Conflict
  return if (isWeakEtag(current)) IfMatchDecision.Unconditional else IfMatchDecision.Send(current)
}
