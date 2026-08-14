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
    assertEquals(IfMatchDecision.Send("\"abc\""), resolveIfMatch("W/\"abc\"") { "\"abc\"" })
  }

  /**
   * Still weak, same value: the server just told us the file is what we last wrote. Writing
   * unconditionally is the narrow concession — sending the weak tag would fail against everything,
   * including itself.
   */
  fun testAStillWeakButUnchangedTagWritesUnconditionally() {
    assertEquals(IfMatchDecision.Unconditional, resolveIfMatch("W/\"abc\"") { "W/\"abc\"" })
  }

  /** THE case the whole mechanism exists for: somebody else wrote in the meantime. */
  fun testADifferentValueIsAConflict() {
    assertEquals(IfMatchDecision.Conflict, resolveIfMatch("W/\"abc\"") { "\"xyz\"" })
    assertEquals(IfMatchDecision.Conflict, resolveIfMatch("W/\"abc\"") { "W/\"xyz\"" })
  }

  /**
   * The server cannot be asked. Send the weak tag anyway and let it refuse: refusing is the safe
   * direction, writing blind is not. A wrong conflict costs a click, a lost change costs work.
   */
  fun testWhenTheServerCannotBeAskedTheWeakTagGoesOutAnyway() {
    assertEquals(IfMatchDecision.Send("W/\"abc\""), resolveIfMatch("W/\"abc\"") { null })
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
