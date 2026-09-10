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

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * [fork change] One HTTP exchange with a binary body.
 *
 * Separate from [HttpBackend], which reads text over GET and is all the Toggl import needs. The
 * journal store needs three things that one cannot give: a method other than GET, a request body,
 * and the response **headers** — the rate-limit counter is read from one.
 *
 * Bytes rather than a String, because the journal's check file is a SHA-256 over exactly the bytes
 * that were written. Routing content through a String on the way in or out would decode and
 * re-encode it, and a charset guess anywhere along that path would break the check without
 * changing anything a reader could see.
 *
 * The same shape exists on the phone side (`biz.ganttproject.mobile.core.HttpExchange`), where it
 * was written for WebDAV first. It is not shared code — the two programs share no module — but
 * deliberately the same seam, so the store above it can be the same on both sides.
 */
data class ExchangeRequest(
  val method: String,
  val url: String,
  val headers: Map<String, String> = emptyMap(),
  val body: ByteArray? = null,
) {
  // Generated equals/hashCode would compare the body array by identity, which is never what a
  // caller means. Nothing compares requests today; overriding keeps that from becoming a silent
  // bug the day something does.
  override fun equals(other: Any?) = this === other
  override fun hashCode() = System.identityHashCode(this)
}

/** [fork change] Response with an undecoded body and header names folded to lower case. */
data class ExchangeResponse(
  val status: Int,
  val headers: Map<String, String>,
  val body: ByteArray,
) {
  fun header(name: String): String? = headers[name.lowercase()]

  override fun equals(other: Any?) = this === other
  override fun hashCode() = System.identityHashCode(this)
}

/** [fork change] The seam that lets the journal store be tested without a network. */
interface HttpExchange {
  /**
   * Performs [request] and returns the response.
   *
   * A non-2xx status **must be returned, not thrown**. Which status means what is knowledge that
   * lives in [GitHubJournalStore] and is tested there offline; an implementation that threw on 409
   * would move half of that knowledge here and take the conflict case out of reach of those tests.
   *
   * Only a request that produced no answer at all — network down, name not resolvable, timeout —
   * may surface as an exception.
   */
  fun exchange(request: ExchangeRequest): ExchangeResponse
}

/**
 * [fork change] The real one, on the JDK's own HTTP client.
 *
 * `java.net.http` rather than OkHttp, which the program also carries: this needs nothing OkHttp
 * offers over it, and the JDK client is already what [JdkHttpSender] next door uses. No new
 * dependency either way.
 *
 * Deliberately thin. Everything that can be decided rather than transmitted lives in the store.
 */
class JdkHttpExchange(
  private val client: HttpClient = defaultExchangeClient(),
) : HttpExchange {

  override fun exchange(request: ExchangeRequest): ExchangeResponse {
    val builder = HttpRequest.newBuilder(URI.create(request.url)).timeout(RESPONSE_TIMEOUT)
    request.headers.forEach { (name, value) -> builder.header(name, value) }
    val body = request.body
      ?.let { HttpRequest.BodyPublishers.ofByteArray(it) }
      ?: HttpRequest.BodyPublishers.noBody()
    builder.method(request.method, body)

    val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    // A header can legitimately appear more than once; the first is taken, and the names are
    // folded so a caller never has to guess the server's capitalisation.
    val headers = response.headers().map().entries
      .filter { it.value.isNotEmpty() }
      .associate { it.key.lowercase() to it.value.first() }
    return ExchangeResponse(response.statusCode(), headers, response.body())
  }
}

/**
 * Chosen, not measured (Claude): long enough for a slow answer, short enough that a hanging server
 * does not freeze a save for minutes.
 */
private val RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(30)
private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(15)

/**
 * `ProxySelector.getDefault()` makes the JDK honour the usual proxy system properties, so saving
 * works behind a company proxy without extra settings.
 */
private fun defaultExchangeClient(): HttpClient = HttpClient.newBuilder()
  .connectTimeout(CONNECT_TIMEOUT)
  .followRedirects(HttpClient.Redirect.NORMAL)
  .proxy(java.net.ProxySelector.getDefault())
  .build()
