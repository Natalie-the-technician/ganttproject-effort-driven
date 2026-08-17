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
import java.io.IOException

/**
 * Tests for the real HTTP backend. No network and no real waiting: the clock, the sleeping and the
 * sending are all injected.
 *
 * The rate limit is the part worth testing hardest. It cannot be observed from the outside — a
 * wrong wait shows up as a 429 during a real import, weeks later and hard to attribute.
 */
class HttpClientBackendTest : TestCase() {

  /** A clock that only moves when the test moves it. Monotonic by construction. */
  private class FakeClock {
    var nanos: Long = 0L
    fun read(): Long = nanos
    fun advanceMillis(millis: Long) { nanos += millis * 1_000_000L }
  }

  /** A throttle that must not wait — used where waiting would mean the test asked for the wrong thing. */
  private fun noWaitExpected() =
    RequestThrottle(sleepMillis = { fail("this test must not wait") })

  private val anyUrl = "${TogglApi.BASE_URL}/me/time_entries?start_date=2026-08-01&end_date=2026-09-01"

  // --- Wartezeit zwischen Anfragen ---

  fun testFirstRequestDoesNotWait() {
    val clock = FakeClock()
    val sleeps = mutableListOf<Long>()
    RequestThrottle(nanoTime = clock::read, sleepMillis = { sleeps.add(it) }).awaitTurn()
    assertTrue("the first request has nothing to wait for", sleeps.isEmpty())
  }

  fun testSecondRequestWaitsTheFullInterval() {
    val clock = FakeClock()
    val sleeps = mutableListOf<Long>()
    val throttle = RequestThrottle(nanoTime = clock::read, sleepMillis = { sleeps.add(it) })
    throttle.awaitTurn()
    throttle.awaitTurn()
    assertEquals(listOf(1000L), sleeps)
  }

  /** Time already spent counts towards the interval — otherwise every import runs at half speed. */
  fun testOnlyTheRemainingTimeIsWaited() {
    val clock = FakeClock()
    val sleeps = mutableListOf<Long>()
    val throttle = RequestThrottle(nanoTime = clock::read, sleepMillis = { sleeps.add(it) })
    throttle.awaitTurn()
    clock.advanceMillis(400)
    throttle.awaitTurn()
    assertEquals(listOf(600L), sleeps)
  }

  fun testNoWaitOnceTheIntervalHasPassed() {
    val clock = FakeClock()
    val sleeps = mutableListOf<Long>()
    val throttle = RequestThrottle(nanoTime = clock::read, sleepMillis = { sleeps.add(it) })
    throttle.awaitTurn()
    clock.advanceMillis(1500)
    throttle.awaitTurn()
    assertTrue("the interval was over, nothing to wait for", sleeps.isEmpty())
  }

  /**
   * Half a millisecond short of the interval must still wait a whole millisecond. Rounding down
   * would send the request a hair too early — which is exactly the case Toggl answers with 429,
   * and the one an integer division gets wrong by default.
   */
  fun testARemainderBelowOneMillisecondStillWaits() {
    val clock = FakeClock()
    val sleeps = mutableListOf<Long>()
    val throttle = RequestThrottle(nanoTime = clock::read, sleepMillis = { sleeps.add(it) })
    throttle.awaitTurn()
    clock.nanos += 999_500_000L
    throttle.awaitTurn()
    assertEquals(listOf(1L), sleeps)
  }

  // --- Die Anfrage selbst ---

  fun testRequestIsAGetWithTheAuthorizationHeader() {
    val request = buildTogglRequest(anyUrl, "Basic GEHEIM")
    assertEquals("GET", request.method())
    assertEquals("Basic GEHEIM", request.headers().firstValue("Authorization").orElse(null))
    assertEquals("application/json", request.headers().firstValue("Accept").orElse(null))
    assertEquals(anyUrl, request.uri().toString())
  }

  /** Without a timeout a hanging server blocks the import until the user kills the program. */
  fun testRequestCarriesATimeout() {
    assertTrue(buildTogglRequest(anyUrl, "Basic GEHEIM").timeout().isPresent)
  }

  // --- Der Vertrag: nicht werfen bei Nicht-2xx ---

  /**
   * THE contract of this class. Which status means what is decided in TogglClient and tested
   * there; if this backend threw on 403, that decision would be split in two.
   */
  fun testNonSuccessIsReturnedAndNotThrown() {
    val backend = HttpClientBackend(sender = { 403 to "forbidden" }, throttle = noWaitExpected())
    val (status, body) = backend.get(anyUrl, "Basic GEHEIM")
    assertEquals(403, status)
    assertEquals("forbidden", body)
  }

  fun testSuccessIsPassedThroughUnchanged() {
    val backend = HttpClientBackend(sender = { 200 to "[]" }, throttle = noWaitExpected())
    assertEquals(200 to "[]", backend.get(anyUrl, "Basic GEHEIM"))
  }

  /** No answer at all is a different thing from a bad answer, and gets the documented value. */
  fun testNetworkFailureBecomesUnavailable() {
    val backend = HttpClientBackend(
      sender = { throw IOException("Netz weg") }, throttle = noWaitExpected())
    try {
      backend.get(anyUrl, "Basic GEHEIM")
      fail("a dead network must be reported")
    } catch (e: TogglException) {
      assertEquals(TogglFailure.UNAVAILABLE, e.failure)
    }
  }

  /**
   * An interrupted request must report the failure AND leave the interrupt flag set — a thread
   * that was asked to stop must not forget it because we swallowed the exception.
   */
  fun testAnInterruptedRequestKeepsTheInterruptFlag() {
    val backend = HttpClientBackend(
      sender = { throw InterruptedException("abgebrochen") }, throttle = noWaitExpected())
    try {
      backend.get(anyUrl, "Basic GEHEIM")
      fail("an interrupted request must be reported")
    } catch (e: TogglException) {
      assertEquals(TogglFailure.UNAVAILABLE, e.failure)
      // Thread.interrupted() also CLEARS the flag, so it cannot leak into the next test.
      assertTrue("the interrupt flag must survive the exception", Thread.interrupted())
    }
  }

  /** The token must not turn up in a message that may end up in the log. */
  fun testTheFailureMessageDoesNotCarryTheToken() {
    val backend = HttpClientBackend(
      sender = { throw IOException("Netz weg") }, throttle = noWaitExpected())
    try {
      backend.get(anyUrl, "Basic SEHRGEHEIM")
      fail("a dead network must be reported")
    } catch (e: TogglException) {
      assertFalse("the message must not contain the authorization header",
        e.message!!.contains("SEHRGEHEIM"))
    }
  }

  // --- Zusammenspiel ---

  /**
   * The wait has to happen BEFORE the request goes out, not after it came back. Waiting afterwards
   * looks the same in a two-request test that only counts sleeps, and is wrong the moment an
   * import stops early: it would have paid for a wait it never needed.
   */
  fun testTheWaitHappensBeforeTheRequestIsSent() {
    val clock = FakeClock()
    val events = mutableListOf<String>()
    val throttle = RequestThrottle(nanoTime = clock::read, sleepMillis = { events.add("warten") })
    val backend = HttpClientBackend(
      sender = { events.add("senden"); 200 to "[]" }, throttle = throttle)

    backend.get(anyUrl, "Basic GEHEIM")
    backend.get(anyUrl, "Basic GEHEIM")

    assertEquals(listOf("senden", "warten", "senden"), events)
  }

  /** The whole point of the seam: TogglClient works unchanged on top of the real backend. */
  fun testTogglClientRunsOnThisBackend() {
    val json = """[{"id": 7, "start": "2026-08-17T09:00:00+02:00", "duration": 3600,
      "description": "x", "project_id": null, "project_name": null, "tags": []}]"""
    var seenAuthorization: String? = null
    val backend = HttpClientBackend(
      sender = { request ->
        seenAuthorization = request.headers().firstValue("Authorization").orElse(null)
        200 to json
      },
      throttle = noWaitExpected())

    val entries = TogglClient(backend).timeEntries("GEHEIM", "2026-08-01", "2026-09-01")

    assertEquals(1, entries.size)
    assertEquals(1.0, entries[0].hours, 0.0001)
    // The token belongs in the username; proving it here shows the header survives the backend.
    assertEquals(TogglClient(backend).authorizationHeader("GEHEIM"), seenAuthorization)
  }
}
