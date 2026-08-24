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
// NEW FILE IN THIS FORK
package net.sourceforge.ganttproject.timetracking

import java.io.IOException
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The real HTTP implementation of [HttpBackend], on `java.net.http.HttpClient`.
 *
 * THE CONTRACT, and the whole reason this class is so thin: a non-2xx answer is **returned**, not
 * thrown. Which status means "wrong token" and which means "asked too often" is knowledge that
 * lives in [TogglClient] and is tested there without a network. If this class started throwing on
 * 403, that knowledge would be split across two places and the offline tests would stop covering
 * the real path.
 *
 * Only a request that produced no answer at all — network down, name not resolvable, timeout —
 * becomes a [TogglException] with [TogglFailure.UNAVAILABLE], which is exactly what that value is
 * documented to mean.
 *
 * Both collaborators are injectable so that everything in here can be tested without a network and
 * without waiting a real second:
 *
 * - [sender] performs one request. The default one is the only line of this file that genuinely
 *   needs a network, and it is kept down to two statements for that reason.
 * - [throttle] enforces Toggl's rate limit.
 *
 * THREAD SAFETY / SCOPE: the rate limit is enforced per instance. One import run must therefore
 * use ONE backend instance for all of its requests — two instances would happily fire two requests
 * in the same second and earn a 429.
 *
 * The token never reaches a log from here: no message in this file interpolates the
 * authorization header.
 */
class HttpClientBackend(
  private val sender: (HttpRequest) -> Pair<Int, String> = JdkHttpSender(),
  private val throttle: RequestThrottle = RequestThrottle(),
) : HttpBackend {

  override fun get(url: String, authorizationHeader: String): Pair<Int, String> {
    // First the wait, then the request. Waiting AFTER the answer came back would look the same
    // in a simple two-request test but is wrong: an import that stops early would have paid for
    // a wait it never needed, and a caller that gives up cannot tell the two apart.
    throttle.awaitTurn()
    val request = buildTogglRequest(url, authorizationHeader)
    return try {
      sender(request)
    } catch (e: InterruptedException) {
      // Restore the flag: swallowing it would leave a thread that no longer knows it was asked
      // to stop.
      Thread.currentThread().interrupt()
      throw TogglException(TogglFailure.UNAVAILABLE, "The Toggl request was interrupted.")
    } catch (e: IOException) {
      throw TogglException(TogglFailure.UNAVAILABLE, "Toggl could not be reached: ${e.message}")
    }
  }
}

/**
 * Builds the GET request for [url].
 *
 * Separate from the sending so a test can look at the finished request — the headers are the part
 * that is easy to get wrong and impossible to see from the outside otherwise.
 */
fun buildTogglRequest(url: String, authorizationHeader: String): HttpRequest =
  HttpRequest.newBuilder(URI.create(url))
    .GET()
    .header("Authorization", authorizationHeader)
    .header("Accept", "application/json")
    .header("User-Agent", TOGGL_USER_AGENT)
    .timeout(RESPONSE_TIMEOUT)
    .build()

/**
 * The one piece that really talks to the network. Deliberately trivial: everything that can be
 * decided rather than transmitted lives in [HttpClientBackend].
 */
class JdkHttpSender(
  private val client: HttpClient = defaultHttpClient()
) : (HttpRequest) -> Pair<Int, String> {

  override fun invoke(request: HttpRequest): Pair<Int, String> {
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    return response.statusCode() to response.body()
  }
}

/**
 * Keeps at least [TogglApi.MIN_REQUEST_INTERVAL] between two requests.
 *
 * Uses a MONOTONIC clock ([System.nanoTime]), never the wall clock: a clock correction — an NTP
 * step, or someone changing the system time — would otherwise either fire a burst of requests or
 * block the import for as long as the correction lasted.
 *
 * [nanoTime] and [sleepMillis] are injectable so the waiting can be tested in microseconds instead
 * of seconds. A test that really sleeps a second per case is a test nobody runs.
 */
class RequestThrottle(
  private val interval: Duration = TogglApi.MIN_REQUEST_INTERVAL,
  private val nanoTime: () -> Long = System::nanoTime,
  private val sleepMillis: (Long) -> Unit = { Thread.sleep(it) },
) {
  private var lastRequestNanos: Long? = null

  /** Blocks until the next request is allowed. Returns immediately for the first one. */
  @Synchronized
  fun awaitTurn() {
    lastRequestNanos?.let { last ->
      val remainingNanos = interval.toNanos() - (nanoTime() - last)
      if (remainingNanos > 0) {
        // Round UP to whole milliseconds. Rounding down would undershoot the interval by up to a
        // millisecond, which is precisely the case Toggl answers with 429.
        sleepMillis((remainingNanos + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI)
      }
    }
    lastRequestNanos = nanoTime()
  }
}

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Named so that Toggl can tell this fork apart from stock GanttProject if they ever have to look
 * at their side of a problem.
 */
private const val TOGGL_USER_AGENT = "GanttProject-Fork"

/**
 * Chosen, not measured (Claude): long enough for a slow answer, short enough that a hanging server
 * does not freeze an import for minutes. Adjust if a real import ever runs into it.
 */
private val RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(30)
private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(15)

/**
 * `ProxySelector.getDefault()` makes the JDK honour the usual proxy system properties, so an
 * import works behind a company proxy without extra settings.
 */
private fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
  .connectTimeout(CONNECT_TIMEOUT)
  .followRedirects(HttpClient.Redirect.NORMAL)
  .proxy(ProxySelector.getDefault())
  .build()
