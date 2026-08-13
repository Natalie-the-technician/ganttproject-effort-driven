/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Base64

/**
 * Exercises the client without a network by supplying a canned [HttpBackend].
 * That seam is the reason error handling is testable at all: every status
 * code can be produced on demand, including the ones a real account would
 * almost never return.
 */
class TogglClientTest {

  private class FakeBackend(
    private val handler: (String, Map<String, String>) -> HttpResponse
  ) : HttpBackend {
    val requestedUrls = mutableListOf<String>()
    var lastHeaders: Map<String, String> = emptyMap()
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
      requestedUrls.add(url)
      lastHeaders = headers
      return handler(url, headers)
    }
  }

  private fun clientReturning(status: Int, body: String = "[]"): Pair<TogglClient, FakeBackend> {
    val backend = FakeBackend { _, _ -> HttpResponse(status, body) }
    return TogglClient(backend, "test-token") to backend
  }

  private fun failure(result: TogglResult<*>): TogglError {
    assertTrue(result is TogglResult.Failure, "expected a failure but got $result")
    return (result as TogglResult.Failure).error
  }

  // -------------------------------------------------------- Authentication

  @Test
  fun `uses HTTP basic auth with the api_token password`() {
    val (client, backend) = clientReturning(200)
    client.fetchTimeEntries(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 7))
    val header = backend.lastHeaders["Authorization"]!!
    val decoded = String(Base64.getDecoder().decode(header.removePrefix("Basic ")))
    assertEquals("test-token:api_token", decoded)
  }

  @Test
  fun `a blank token fails before any request is made`() {
    val backend = FakeBackend { _, _ -> error("must not be called") }
    val result = TogglClient(backend, "").fetchTimeEntries(LocalDate.now(), LocalDate.now())
    assertEquals(TogglError.InvalidToken, failure(result))
    assertTrue(backend.requestedUrls.isEmpty(), "no request may go out without a token")
  }

  // -------------------------------------------------------- Error mapping

  @Test
  fun `401 becomes InvalidToken`() {
    val (client, _) = clientReturning(401, """{"error":"nope"}""")
    assertEquals(
      TogglError.InvalidToken,
      failure(client.fetchTimeEntries(LocalDate.now(), LocalDate.now()))
    )
  }

  @Test
  fun `403 becomes Forbidden`() {
    val (client, _) = clientReturning(403)
    assertEquals(
      TogglError.Forbidden,
      failure(client.fetchTimeEntries(LocalDate.now(), LocalDate.now()))
    )
  }

  @Test
  fun `429 becomes RateLimited`() {
    val (client, _) = clientReturning(429)
    assertEquals(
      TogglError.RateLimited,
      failure(client.fetchTimeEntries(LocalDate.now(), LocalDate.now()))
    )
  }

  @Test
  fun `500 becomes ServerError carrying the status`() {
    val (client, _) = clientReturning(503)
    assertEquals(
      TogglError.ServerError(503),
      failure(client.fetchTimeEntries(LocalDate.now(), LocalDate.now()))
    )
  }

  @Test
  fun `an unexpected status keeps the code and truncates the body`() {
    val (client, _) = clientReturning(418, "x".repeat(5000))
    val error = failure(client.fetchTimeEntries(LocalDate.now(), LocalDate.now()))
    assertTrue(error is TogglError.Unexpected, "was $error")
    error as TogglError.Unexpected
    assertEquals(418, error.status)
    assertEquals(200, error.body.length, "an HTML error page must not be kept in full")
  }

  @Test
  fun `a transport exception becomes a Network error rather than escaping`() {
    val backend = FakeBackend { _, _ -> throw java.net.UnknownHostException("api.track.toggl.com") }
    val result = TogglClient(backend, "t").fetchTimeEntries(LocalDate.now(), LocalDate.now())
    val error = failure(result)
    assertTrue(error is TogglError.Network, "was $error")
  }

  @Test
  fun `an end date before the start date is refused`() {
    val (client, backend) = clientReturning(200)
    val result = client.fetchTimeEntries(LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 1))
    assertEquals(TogglError.InvalidDateRange, failure(result))
    assertTrue(backend.requestedUrls.isEmpty())
  }

  // ------------------------------------------------------------ Date range

  @Test
  fun `the inclusive end date is shifted because Toggl excludes it`() {
    val (client, backend) = clientReturning(200)
    client.fetchTimeEntries(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 7))
    val url = backend.requestedUrls.single()
    assertTrue(url.contains("start_date=2026-03-01"), url)
    assertTrue(
      url.contains("end_date=2026-03-08"),
      "without the shift, entries on the last day would be missing: $url"
    )
  }

  // --------------------------------------------------------------- Parsing

  private val client = TogglClient(FakeBackend { _, _ -> HttpResponse(200, "[]") }, "t")

  @Test
  fun `parses a normal entry`() {
    val body = """
      [{"id":1234,"description":"Furniture selection","start":"2026-03-02T09:00:00+01:00",
        "duration":5400,"project_id":77,"tags":["planning"]}]
    """.trimIndent()
    val result = client.parseTimeEntries(body, mapOf(77L to "House"))
    assertTrue(result is TogglResult.Success)
    val entry = (result as TogglResult.Success).value.single()
    assertEquals(1234L, entry.id)
    assertEquals("Furniture selection", entry.description)
    assertEquals(LocalDate.of(2026, 3, 2), entry.start)
    assertEquals(1.5, entry.hours, 1e-9)
    assertEquals("House", entry.projectName)
    assertEquals(listOf("planning"), entry.tags)
  }

  @Test
  fun `a running entry is dropped`() {
    // Toggl encodes a running entry as a negative duration. Importing one
    // would book hours that are still being counted.
    val body = """[{"id":1,"description":"running","start":"2026-03-02T09:00:00Z","duration":-1772000}]"""
    val result = client.parseTimeEntries(body, emptyMap())
    assertTrue((result as TogglResult.Success).value.isEmpty())
  }

  @Test
  fun `a zero-length entry is dropped`() {
    val body = """[{"id":1,"description":"nothing","start":"2026-03-02T09:00:00Z","duration":0}]"""
    assertTrue((client.parseTimeEntries(body, emptyMap()) as TogglResult.Success).value.isEmpty())
  }

  @Test
  fun `one broken record does not fail the whole batch`() {
    val body = """
      [{"id":1,"description":"good","start":"2026-03-02T09:00:00Z","duration":3600},
       {"description":"missing id","start":"2026-03-02T09:00:00Z","duration":3600},
       {"id":3,"description":"no start","duration":3600}]
    """.trimIndent()
    val entries = (client.parseTimeEntries(body, emptyMap()) as TogglResult.Success).value
    assertEquals(1, entries.size, "the usable record must still come through")
    assertEquals(1L, entries.single().id)
  }

  @Test
  fun `an entry with no description parses to an empty string`() {
    val body = """[{"id":1,"start":"2026-03-02T09:00:00Z","duration":3600}]"""
    val entry = (client.parseTimeEntries(body, emptyMap()) as TogglResult.Success).value.single()
    assertEquals("", entry.description)
    assertNull(entry.projectName)
  }

  @Test
  fun `a JSON object instead of an array is reported as malformed`() {
    val error = failure(client.parseTimeEntries("""{"not":"an array"}""", emptyMap()))
    assertTrue(error is TogglError.Malformed, "was $error")
  }

  @Test
  fun `unparseable JSON is reported as malformed, not thrown`() {
    val error = failure(client.parseTimeEntries("this is not json", emptyMap()))
    assertTrue(error is TogglError.Malformed, "was $error")
  }

  @Test
  fun `a bare date without a time still parses`() {
    val body = """[{"id":1,"description":"x","start":"2026-03-02","duration":3600}]"""
    val entry = (client.parseTimeEntries(body, emptyMap()) as TogglResult.Success).value.single()
    assertEquals(LocalDate.of(2026, 3, 2), entry.start)
  }
}
