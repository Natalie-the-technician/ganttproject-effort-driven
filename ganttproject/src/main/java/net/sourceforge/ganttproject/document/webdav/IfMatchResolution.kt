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
   * Write without a condition. Reached only when nothing is remembered — a first write, or a read
   * whose version the server would not tell us. There is no version to be conditional on.
   */
  data object Unconditional : IfMatchDecision

  /** Somebody else changed the file. Do not write. */
  data object Conflict : IfMatchDecision

  /**
   * The server never answers with a strong ETag, so no write can be made conditional. Do not write.
   *
   * WHY THIS IS NOT "just write it then": a weak tag is not always Apache's mtime window. RFC 9110
   * *requires* a weak validator whenever the representation is transformed in transit —
   * `mod_deflate`, nginx with `gzip`, any compressing proxy, any CDN. Then it never becomes strong,
   * and a branch that falls back to unconditional writing turns from a millisecond-wide concession
   * into **every single save**: D3 would be switched off, permanently and without a sign.
   *
   * Refusing is the honest answer. Silently writing blind is the failure this whole mechanism
   * exists to prevent, and the user would have no way of knowing it had come back.
   */
  data object VersioningUnavailable : IfMatchDecision
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

/** How long to wait out Apache's sub-second mtime window before asking a second time. */
const val WEAK_ETAG_RETRY_MILLIS = 1100L

/**
 * @param remembered the ETag the pending changes are based on, or null when none is known.
 * @param currentFromServer asks the server for the ETag the file carries right now. Returns null
 * when the question cannot be answered. Called only when [remembered] is weak — but note that this
 * is NOT the rare case it looks like: [MiltonResourceImpl] re-reads the ETag straight after every
 * `PUT`, which is exactly the second in which Apache answers weakly. From the second save of a
 * session onwards, this is the normal path.
 * @param pause waits between the two questions. A parameter so that tests need not really sleep.
 */
// @JvmOverloads: MiltonResourceImpl ist Java und sieht Kotlins Vorgabewerte sonst nicht.
@JvmOverloads
fun resolveIfMatch(
  remembered: String?,
  currentFromServer: () -> String?,
  pause: () -> Unit = { Thread.sleep(WEAK_ETAG_RETRY_MILLIS) }
): IfMatchDecision {
  if (remembered == null) return IfMatchDecision.Unconditional
  if (!isWeakEtag(remembered)) return IfMatchDecision.Send(remembered)

  when (val first = judge(remembered, currentFromServer())) {
    is Judged.Decided -> return first.decision
    Judged.StillWeak -> {}
  }

  // Still weak. Two very different things look alike here, and only time tells them apart:
  // Apache's sub-second mtime window, which passes, and a transformed representation
  // (compression, proxy, CDN), which never does. So wait out the first and ask again.
  pause()
  return when (val second = judge(remembered, currentFromServer())) {
    is Judged.Decided -> second.decision
    Judged.StillWeak -> IfMatchDecision.VersioningUnavailable
  }
}

private sealed interface Judged {
  data class Decided(val decision: IfMatchDecision) : Judged
  data object StillWeak : Judged
}

private fun judge(remembered: String, current: String?): Judged {
  // Cannot ask: send the weak tag and let the server refuse. Refusing is the safe direction,
  // writing blind is not.
  if (current == null) return Judged.Decided(IfMatchDecision.Send(remembered))
  if (opaqueEtag(current) != opaqueEtag(remembered)) return Judged.Decided(IfMatchDecision.Conflict)
  return if (isWeakEtag(current)) Judged.StillWeak else Judged.Decided(IfMatchDecision.Send(current))
}
