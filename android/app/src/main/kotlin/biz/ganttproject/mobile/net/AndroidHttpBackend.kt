/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.net

import biz.ganttproject.mobile.core.BinaryHttpResponse
import biz.ganttproject.mobile.core.HttpBackend
import biz.ganttproject.mobile.core.HttpExchange
import biz.ganttproject.mobile.core.HttpRequest
import biz.ganttproject.mobile.core.HttpResponse
import biz.ganttproject.mobile.core.TogglApi
import java.net.HttpURLConnection
import java.net.URL

/**
 * [HttpBackend] on top of `HttpURLConnection`.
 *
 * `HttpURLConnection` rather than a client library because this is the only
 * network call the app makes; adding OkHttp for one GET would be a
 * dependency, a size increase and a licence line for no benefit.
 *
 * Honouring the backend contract matters more than it looks: a non-2xx
 * response must be **returned**, not thrown, so that `TogglClient` alone
 * decides what each status means. That is why the error stream is read
 * explicitly instead of letting `getInputStream()` throw.
 */
class AndroidHttpBackend(
  private val connectTimeoutMs: Int = 15_000,
  private val readTimeoutMs: Int = 30_000
) : HttpBackend, HttpExchange {

  /**
   * Toggl allows roughly one request per second and answers 429 beyond that.
   * Spacing requests here keeps the client from tripping a limit it would
   * then have to report to the user as a failure.
   */
  private var lastRequestAt = 0L

  @Synchronized
  private fun throttle() {
    val minGap = TogglApi.MIN_REQUEST_INTERVAL.toMillis()
    val waitFor = lastRequestAt + minGap - System.currentTimeMillis()
    if (waitFor > 0) {
      runCatching { Thread.sleep(waitFor) }
    }
    lastRequestAt = System.currentTimeMillis()
  }

  /**
   * Performs an arbitrary exchange for the WebDAV client.
   *
   * Not throttled: unlike Toggl, this server is the user's own and every
   * request here is the direct result of something they just did — opening a
   * project, saving one. Spacing those out would only make the app feel slow.
   *
   * `HttpURLConnection` refuses to send PROPFIND, LOCK and UNLOCK because it
   * validates the method against a fixed list. The field holding that method
   * is set directly when the normal setter rejects it; without this the app
   * could read and write but never list a collection, and the failure would
   * look like a server misconfiguration rather than a client limitation.
   */
  override fun exchange(request: HttpRequest): BinaryHttpResponse {
    // Held locally because HttpRequest lives in gantt-core, and Kotlin will
    // not smart-cast a property across a module boundary — the value could in
    // principle change between the null check and the use.
    val payload = request.body
    val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
      connectTimeout = connectTimeoutMs
      readTimeout = readTimeoutMs
      instanceFollowRedirects = false
      runCatching { requestMethod = request.method }.onFailure { forceMethod(this, request.method) }
      // Checked, not assumed. The setter throws for PROPFIND and the
      // reflection that follows can fail without saying so, and the failure
      // mode is a silent downgrade to GET — a request that succeeds, returns
      // something plausible, and answers a different question than the one
      // asked. Better to fail here, loudly, than to act on that.
      if (!requestMethod.equals(request.method, ignoreCase = true)) {
        throw java.net.ProtocolException(
          "${request.method} konnte nicht gesendet werden (wurde ${requestMethod})"
        )
      }
      request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
      if (payload != null) {
        doOutput = true
        setFixedLengthStreamingMode(payload.size)
      }
    }
    return try {
      payload?.let { connection.outputStream.use { out -> out.write(it) } }
      val status = connection.responseCode
      val stream = if (status in 200..299) connection.inputStream else connection.errorStream
      val body = stream?.use { it.readBytes() } ?: ByteArray(0)
      val headers = connection.headerFields
        .filterKeys { it != null }
        .map { (name, values) -> name.lowercase() to values.joinToString(", ") }
        .toMap()
      BinaryHttpResponse(status, headers, body)
    } finally {
      connection.disconnect()
    }
  }

  /**
   * Sets the request method past `HttpURLConnection`'s allow-list.
   *
   * Reflection on a platform internal, which is exactly as fragile as it
   * looks. The caller verifies the result rather than trusting this to have
   * worked.
   *
   * Two things have to be right, and the first version got both wrong in a way
   * that could not be seen from the outside:
   *
   * Over `https` — the only scheme this app allows — Android does not hand
   * back the object that performs the request. It hands back a wrapper holding
   * the real connection in a `delegate` field and forwarding to it. Writing
   * the method onto the wrapper changes a field nobody reads; the delegate
   * keeps its own, still `GET`, and that is what goes on the wire. So the
   * delegate chain is walked to the end first.
   *
   * And the field is declared on `java.net.HttpURLConnection`, not on the
   * concrete class, so `getDeclaredField` has to climb the hierarchy rather
   * than ask the runtime class once.
   */
  private fun forceMethod(connection: HttpURLConnection, method: String) {
    // Every object in the chain, not just the last: which one performs the
    // request and which one answers getRequestMethod() are both platform
    // details, and they need not be the same object. Setting all of them costs
    // nothing and removes the guess.
    var target: Any? = connection
    var hops = 0
    while (target != null && hops++ < 8) {
      setMethodField(target, method)
      val next = runCatching {
        target!!.javaClass.getDeclaredField("delegate")
          .apply { isAccessible = true }
          .get(target)
      }.getOrNull()
      target = if (next === target) null else next
    }
  }

  /** Writes the `method` field declared anywhere in [target]'s hierarchy. */
  private fun setMethodField(target: Any, method: String) {
    var type: Class<*>? = target.javaClass
    while (type != null) {
      val field = runCatching { type.getDeclaredField("method") }.getOrNull()
      if (field != null && field.type == String::class.java) {
        runCatching {
          field.isAccessible = true
          field.set(target, method)
        }
        return
      }
      type = type.superclass
    }
  }

  override fun get(url: String, headers: Map<String, String>): HttpResponse {
    throttle()
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = connectTimeoutMs
      readTimeout = readTimeoutMs
      // Redirects are not followed: a redirect off the API host would carry
      // the Authorization header somewhere it does not belong.
      instanceFollowRedirects = false
      headers.forEach { (name, value) -> setRequestProperty(name, value) }
    }
    return try {
      val status = connection.responseCode
      val stream = if (status in 200..299) connection.inputStream else connection.errorStream
      val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
      HttpResponse(status, body)
    } finally {
      connection.disconnect()
    }
  }
}
