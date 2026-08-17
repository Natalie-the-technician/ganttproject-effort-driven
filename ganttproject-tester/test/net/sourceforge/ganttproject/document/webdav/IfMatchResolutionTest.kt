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

import junit.framework.TestCase

/**
 * The decision that makes conditional writing work — without a server.
 *
 * Both failure directions are covered on purpose: a condition that is too weak overwrites somebody
 * else's work silently, one that is too strict reports a conflict that never happened and teaches
 * the user to click "overwrite anyway".
 */
class IfMatchResolutionTest : TestCase() {

  /** Never asked: the extra request is for the weak case only. */
  private val neverAsked: () -> String? = { fail("the server was asked although the tag was strong"); null }

  fun testAStrongTagIsSentAsItIs() {
    assertEquals(IfMatchDecision.Send("\"abc\""), resolveIfMatch("\"abc\"", neverAsked))
  }

  /** Nothing known to condition on. The old behaviour, and the only case where it is right. */
  fun testWithoutATagTheWriteIsUnconditional() {
    assertEquals(IfMatchDecision.Unconditional, resolveIfMatch(null, neverAsked))
  }

  /**
   * The case the Apache produces within a second of writing: same value, now reported strongly.
   * Sending the strong form is exactly right — it still identifies our content.
   */
  fun testAWeakTagIsReplacedByTheStrongOneTheServerNowReports() {
    assertEquals(IfMatchDecision.Send("\"abc\""), resolveIfMatch("W/\"abc\"", currentFromServer = { "\"abc\"" }))
  }

  /**
   * Still weak on the first ask, strong on the second: Apache's sub-second mtime window. Waiting it
   * out and asking again is what keeps ordinary saving working.
   */
  fun testStillWeakThenStrongAfterWaiting() {
    val answers = mutableListOf("W/\"abc\"", "\"abc\"")
    var paused = 0
    val decision = resolveIfMatch("W/\"abc\"", { answers.removeAt(0) }, { paused++ })
    assertEquals(IfMatchDecision.Send("\"abc\""), decision)
    assertEquals("the second question must come after waiting, not immediately", 1, paused)
  }

  /**
   * THE HOLE THIS REPLACES. Until 15.08.2026 this returned Unconditional, justified with "only the
   * milliseconds in which somebody saves twice within a second".
   *
   * That assumed weakness is always Apache's mtime window. It is not: RFC 9110 *requires* a weak
   * validator whenever the representation is transformed in transit — mod_deflate, nginx with gzip,
   * any compressing proxy, any CDN. There it never becomes strong, so the branch would not be a
   * rare concession but EVERY save, and conditional writing would be switched off permanently
   * without any sign of it.
   *
   * Refusing is the honest answer. The Android session found this in the ported copy of this very
   * function.
   */
  fun testAServerThatStaysWeakGetsNoWriteAtAll() {
    assertEquals(
      IfMatchDecision.VersioningUnavailable,
      resolveIfMatch("W/\"abc\"", { "W/\"abc\"" }, {})
    )
  }

  /** Somebody else wrote while we were waiting out the window. Still a conflict, not a write. */
  fun testAChangeDuringTheWaitIsAConflict() {
    val answers = mutableListOf("W/\"abc\"", "\"xyz\"")
    assertEquals(
      IfMatchDecision.Conflict,
      resolveIfMatch("W/\"abc\"", { answers.removeAt(0) }, {})
    )
  }

  /** No waiting, and no second question, when the first answer already decides it. */
  fun testTheServerIsNotAskedTwiceWithoutNeed() {
    var asked = 0
    val decision = resolveIfMatch(
      "W/\"abc\"",
      { asked++; "\"abc\"" },
      { fail("waited although the first answer already decided it") }
    )
    assertEquals(IfMatchDecision.Send("\"abc\""), decision)
    assertEquals(1, asked)
  }

  /** THE case the whole mechanism exists for: somebody else wrote in the meantime. */
  fun testADifferentValueIsAConflict() {
    assertEquals(IfMatchDecision.Conflict, resolveIfMatch("W/\"abc\"", currentFromServer = { "\"xyz\"" }))
    assertEquals(IfMatchDecision.Conflict, resolveIfMatch("W/\"abc\"", currentFromServer = { "W/\"xyz\"" }))
  }

  /**
   * The server cannot be asked. Send the weak tag anyway and let it refuse: refusing is the safe
   * direction, writing blind is not. A wrong conflict costs a click, a lost change costs work.
   */
  fun testWhenTheServerCannotBeAskedTheWeakTagGoesOutAnyway() {
    assertEquals(IfMatchDecision.Send("W/\"abc\""), resolveIfMatch("W/\"abc\"", currentFromServer = { null }))
  }

  /**
   * `W/` is stripped ONLY to compare a value against itself across a weak/strong change. It must
   * never make two different tags look equal — that shortcut is what would let a stale write pass.
   */
  fun testTheWeaknessMarkerNeverMakesDifferentTagsEqual() {
    assertEquals("\"abc\"", opaqueEtag("W/\"abc\""))
    assertEquals("\"abc\"", opaqueEtag("\"abc\""))
    assertFalse(opaqueEtag("W/\"abc\"") == opaqueEtag("\"abcd\""))
  }

  fun testWeaknessIsRecognised() {
    assertTrue(isWeakEtag("W/\"abc\""))
    assertFalse(isWeakEtag("\"abc\""))
    // Not a weakness marker: a tag whose CONTENT happens to start with W.
    assertFalse(isWeakEtag("\"W/abc\""))
  }
}
