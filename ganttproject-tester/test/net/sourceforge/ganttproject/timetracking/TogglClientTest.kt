/*
Copyright 2026

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
// NEUE DATEI DIESES FORKS
package net.sourceforge.ganttproject.timetracking

import junit.framework.TestCase
import java.util.Base64

/**
 * Tests for the Toggl client. No network is used: the HTTP seam is replaced by recorded
 * answers, so these tests run anywhere and always mean the same thing.
 */
class TogglClientTest : TestCase() {

  private class FakeBackend(
    private val status: Int,
    private val body: String
  ) : HttpBackend {
    var lastUrl: String? = null
    var lastAuth: String? = null
    override fun get(url: String, authorizationHeader: String): Pair<Int, String> {
      lastUrl = url
      lastAuth = authorizationHeader
      return status to body
    }
  }

  private val zweiEintraege = """
    [
      {"id": 111, "start": "2026-08-17T09:00:00+02:00", "duration": 7200,
       "description": "#332 Firmware Sensorik", "project_id": 42,
       "project_name": "Prototype board", "tags": ["entwicklung"]},
      {"id": 112, "start": "2026-08-18T10:00:00+02:00", "duration": 1800,
       "description": "Office paperwork", "project_id": null,
       "project_name": null, "tags": []}
    ]
  """.trimIndent()

  // --- Authentifizierung: der Fehler, den alle machen ---

  /** Token gehoert in den Benutzernamen, das Wort api_token ins Passwort. */
  fun testTokenGoesIntoTheUsername() {
    val header = TogglClient(FakeBackend(200, "[]")).authorizationHeader("GEHEIM")
    val decoded = String(Base64.getDecoder().decode(header.removePrefix("Basic ")))
    assertEquals("GEHEIM:api_token", decoded)
  }

  fun testBlankTokenIsRejected() {
    try {
      TogglClient(FakeBackend(200, "[]")).timeEntries("  ", "2026-08-01", "2026-09-01")
      fail("a blank token must be rejected before a request is made")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.contains("token"))
    }
  }

  // --- Fehlerarten, jede mit eigener Aussage ---

  private fun expectFailure(status: Int, expected: TogglFailure) {
    try {
      TogglClient(FakeBackend(status, "")).timeEntries("t", "2026-08-01", "2026-09-01")
      fail("status $status must be reported as a failure")
    } catch (e: TogglException) {
      assertEquals(expected, e.failure)
    }
  }

  fun testForbiddenIsReportedAsAuthProblem() = expectFailure(403, TogglFailure.NOT_AUTHORISED)
  fun testTooManyRequestsIsReportedAsRateLimit() = expectFailure(429, TogglFailure.RATE_LIMITED)
  fun testPaymentRequiredIsReportedAsQuota() = expectFailure(402, TogglFailure.QUOTA_EXHAUSTED)
  fun testServerErrorIsReportedAsUnavailable() = expectFailure(500, TogglFailure.UNAVAILABLE)

  /** The 403 message must name the cause, otherwise the user has no way to fix it. */
  fun testAuthMessageExplainsTheUsernamePasswordOrder() {
    try {
      TogglClient(FakeBackend(403, "")).timeEntries("t", "2026-08-01", "2026-09-01")
      fail("expected a failure")
    } catch (e: TogglException) {
      assertTrue("the message must mention the password literal",
        e.message!!.contains(TogglApi.PASSWORD_LITERAL))
    }
  }

  // --- Anfrage ---

  fun testRequestCarriesTheDateRange() {
    val backend = FakeBackend(200, "[]")
    TogglClient(backend).timeEntries("t", "2026-08-01", "2026-09-01")
    assertTrue(backend.lastUrl!!.contains("start_date=2026-08-01"))
    assertTrue(backend.lastUrl!!.contains("end_date=2026-09-01"))
    assertTrue(backend.lastUrl!!.startsWith(TogglApi.BASE_URL))
  }

  // --- Lesen ---

  fun testEntriesAreRead() {
    val entries = TogglClient(FakeBackend(200, zweiEintraege))
      .timeEntries("t", "2026-08-01", "2026-09-01")
    assertEquals(2, entries.size)
    assertEquals(111L, entries[0].id)
    assertEquals("#332 Firmware Sensorik", entries[0].description)
    assertEquals("Prototype board", entries[0].projectName)
    assertEquals(listOf("entwicklung"), entries[0].tags)
  }

  fun testSecondsBecomeHours() {
    val entries = TogglClient(FakeBackend(200, zweiEintraege))
      .timeEntries("t", "2026-08-01", "2026-09-01")
    assertEquals(2.0, entries[0].hours, 0.0001)
    assertEquals(0.5, entries[1].hours, 0.0001)
  }

  fun testNullFieldsDoNotBreakTheImport() {
    val entries = TogglClient(FakeBackend(200, zweiEintraege))
      .timeEntries("t", "2026-08-01", "2026-09-01")
    assertNull(entries[1].projectId)
    assertNull(entries[1].projectName)
    assertTrue(entries[1].tags.isEmpty())
  }

  /** A running entry has a negative duration and must never reach the plan. */
  fun testRunningEntriesAreDropped() {
    val laufend = """[{"id": 200, "start": "2026-08-17T09:00:00+02:00", "duration": -1755424800,
      "description": "laeuft gerade", "project_id": null, "project_name": null, "tags": []}]"""
    val entries = TogglClient(FakeBackend(200, laufend))
      .timeEntries("t", "2026-08-01", "2026-09-01")
    assertTrue("a running entry must not be imported", entries.isEmpty())
  }

  /** An unknown field must not break anything — Toggl adds fields over time. */
  fun testUnknownFieldsAreIgnored() {
    val mitNeuem = """[{"id": 300, "start": "2026-08-17T09:00:00+02:00", "duration": 3600,
      "description": "x", "project_id": null, "project_name": null, "tags": [],
      "irgendwas_neues": {"a": 1}}]"""
    val entries = TogglClient(FakeBackend(200, mitNeuem))
      .timeEntries("t", "2026-08-01", "2026-09-01")
    assertEquals(1, entries.size)
  }

  /** One broken entry must not lose the good ones — but it must be reported. */
  fun testBrokenEntryIsSkippedAndReported() {
    val gemischt = """[{"id": 400, "start": "2026-08-17T09:00:00+02:00", "duration": 3600,
      "description": "gut", "tags": []},
      {"start": "2026-08-18T09:00:00+02:00", "duration": 3600, "description": "ohne id"}]"""
    val (entries, problems) = parseTimeEntriesWithProblems(gemischt)
    assertEquals(1, entries.size)
    assertEquals(1, problems.size)
    assertTrue(problems[0].contains("id"))
  }

  fun testInvalidJsonIsReported() {
    try {
      parseTimeEntries("kein json")
      fail("invalid JSON must be reported")
    } catch (e: TogglException) {
      assertEquals(TogglFailure.UNAVAILABLE, e.failure)
    }
  }
}
