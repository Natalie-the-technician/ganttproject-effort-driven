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

import biz.ganttproject.customproperty.CustomColumnsManager
import junit.framework.TestCase
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.roles.RoleManager
import java.time.LocalDate

/**
 * Tests the read-only trial run against Toggl. No network: the backend is a stand-in that
 * answers with whatever the test prescribes.
 */
class ConnectionCheckTest : TestCase() {
  private lateinit var resourceManager: HumanResourceManager

  override fun setUp() {
    super.setUp()
    resourceManager = HumanResourceManager(
      RoleManager.Access.getInstance().defaultRole, CustomColumnsManager())
  }

  private fun person(name: String, id: Int, mail: String): HumanResource =
    resourceManager.create(name, id).also { it.mail = mail }

  /** Answers with a fixed status and body, and records what it was asked. */
  private class FakeBackend(
    private val status: Int,
    private val body: String
  ) : HttpBackend {
    var lastUrl: String? = null
    var lastAuth: String? = null
    var calls = 0
    override fun get(url: String, authorizationHeader: String): Pair<Int, String> {
      calls++
      lastUrl = url
      lastAuth = authorizationHeader
      return status to body
    }
  }

  private fun entryJson(id: Long, seconds: Long) = """
    {"id": $id, "start": "2026-08-10T09:00:00+02:00", "duration": $seconds,
     "description": "Arbeit", "tags": []}
  """.trimIndent()

  private val today = LocalDate.parse("2026-08-12")

  // --- nothing stored ---

  fun testWithoutAnyTokenNothingIsTried() {
    val backend = FakeBackend(200, "[]")
    val result = checkTogglConnection(
      TogglClient(backend), listOf(person("Nati", 1, "nati@example.org")), "", today)

    assertEquals(ConnectionCheckResult.NoToken, result)
    assertEquals("nothing may be sent when no token is stored", 0, backend.calls)
  }

  fun testTheFirstPersonWithATokenIsUsed() {
    val without = person("Ohne", 1, "ohne@example.org")
    val with = person("Mit", 2, "mit@example.org")
    val stored = withToken("", with, "geheim")

    assertEquals(with, firstResourceWithToken(listOf(without, with), stored))
  }

  // --- the service answers ---

  fun testCountsWhatCameBack() {
    val nati = person("Nati", 1, "nati@example.org")
    val stored = withToken("", nati, "geheim")
    val backend = FakeBackend(200, "[${entryJson(1L, 3600)}, ${entryJson(2L, 1800)}]")

    val result = checkTogglConnection(TogglClient(backend), listOf(nati), stored, today)

    assertTrue("expected Ok, got $result", result is ConnectionCheckResult.Ok)
    assertEquals(2, (result as ConnectionCheckResult.Ok).entryCount)
    assertEquals("Nati", result.person)
  }

  /**
   * The token goes into the USERNAME field, the literal `api_token` into the password. This is
   * the assumption most likely to be wrong on a first real run, so the check must send it that
   * way round.
   */
  fun testTokenIsSentAsTheUsername() {
    val nati = person("Nati", 1, "nati@example.org")
    val stored = withToken("", nati, "geheim")
    val backend = FakeBackend(200, "[]")

    checkTogglConnection(TogglClient(backend), listOf(nati), stored, today)

    val decoded = String(
      java.util.Base64.getDecoder().decode(backend.lastAuth!!.removePrefix("Basic ")))
    assertEquals("geheim:${TogglApi.PASSWORD_LITERAL}", decoded)
  }

  /** The trial asks for a short, recent window — not the whole history. */
  fun testAsksForTheLastSevenDays() {
    val nati = person("Nati", 1, "nati@example.org")
    val stored = withToken("", nati, "geheim")
    val backend = FakeBackend(200, "[]")

    checkTogglConnection(TogglClient(backend), listOf(nati), stored, today, days = 7)

    assertTrue("expected the window in the URL, got ${backend.lastUrl}",
      backend.lastUrl!!.contains("start_date=2026-08-05"))
    assertTrue(backend.lastUrl!!.contains("end_date=2026-08-13"))
  }

  /** A running entry has no end yet and must not be counted as importable. */
  fun testRunningEntriesAreNotCounted() {
    val nati = person("Nati", 1, "nati@example.org")
    val stored = withToken("", nati, "geheim")
    val backend = FakeBackend(200, "[${entryJson(1L, 3600)}, ${entryJson(2L, -1)}]")

    val result = checkTogglConnection(TogglClient(backend), listOf(nati), stored, today)
    assertEquals(1, (result as ConnectionCheckResult.Ok).entryCount)
  }

  // --- the service refuses ---

  fun testRefusedTokenIsReportedWithTheHint() {
    val nati = person("Nati", 1, "nati@example.org")
    val stored = withToken("", nati, "falsch")
    val backend = FakeBackend(403, "")

    val result = checkTogglConnection(TogglClient(backend), listOf(nati), stored, today)

    assertTrue("expected Failed, got $result", result is ConnectionCheckResult.Failed)
    result as ConnectionCheckResult.Failed
    assertEquals(TogglFailure.NOT_AUTHORISED, result.failure)
    assertTrue("the message must say where the token goes, it read: ${result.message}",
      result.message.contains("USERNAME"))
  }

  fun testRateLimitIsReportedAsSuch() {
    val nati = person("Nati", 1, "nati@example.org")
    val stored = withToken("", nati, "geheim")
    val result = checkTogglConnection(
      TogglClient(FakeBackend(429, "")), listOf(nati), stored, today)

    assertEquals(TogglFailure.RATE_LIMITED, (result as ConnectionCheckResult.Failed).failure)
  }

  fun testServerErrorIsReportedAsUnavailable() {
    val nati = person("Nati", 1, "nati@example.org")
    val stored = withToken("", nati, "geheim")
    val result = checkTogglConnection(
      TogglClient(FakeBackend(500, "")), listOf(nati), stored, today)

    assertEquals(TogglFailure.UNAVAILABLE, (result as ConnectionCheckResult.Failed).failure)
  }

  /**
   * An entry this code cannot read must be reported rather than dropped in silence — on a first
   * run against the real service that is the interesting part.
   */
  fun testUnreadableEntriesAreReported() {
    val nati = person("Nati", 1, "nati@example.org")
    val stored = withToken("", nati, "geheim")
    val backend = FakeBackend(200, """[${entryJson(1L, 3600)}, {"id": "keine Zahl"}]""")

    val result = checkTogglConnection(TogglClient(backend), listOf(nati), stored, today)
      as ConnectionCheckResult.Ok

    assertEquals(1, result.entryCount)
    assertFalse("the unreadable entry was swallowed", result.unreadable.isEmpty())
  }
}
