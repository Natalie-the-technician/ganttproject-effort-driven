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

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * A stand-in for the two GitHub endpoints this fork uses.
 *
 * Not a canned response but a small service with state: it keeps files, gives them version ids and
 * enforces the same precondition the real one does. The conflict case is why it has to work that
 * way — a fixed answer cannot show that a stale version id is refused *because* somebody else
 * wrote in between, only that the client copes with a 409 when handed one.
 *
 * NO NEW DEPENDENCY: sha1 from `java.security`, base64 from `java.util`, JSON from the Jackson the
 * program already carries.
 *
 * Faithful in the places that bite:
 *
 * - **Version ids are real git blob shas** — `sha1("blob <len>" + NUL + content)` — so nothing can
 *   pass on a made-up id format.
 * - **Content comes back base64 wrapped at 60 characters**, the way the real API sends it. A
 *   client using the basic decoder throws on the line breaks.
 * - **A directory answers with an array**, a file with an object.
 * - **Every request is recorded**, so a claim that something did not happen can be counted rather
 *   than asserted.
 *
 * It answers [ExchangeRequest]s directly, and [handle] is also what the loopback server in
 * [GitHubJournalStoreTest] serves, so the same service backs both the offline cases and the one
 * that goes over a real socket.
 */
class FakeGitHub(
  private val owner: String = "natalie",
  private val repository: String = "stunden",
  /** The one token this instance accepts. Never printed by anything here. */
  private val acceptedToken: String = "test-token",
  /** Where the client thinks it is talking to; the prefix requests are matched against. */
  private val apiBase: String = GITHUB_API_BASE,
) : HttpExchange {

  data class Recorded(val method: String, val url: String, val body: String?)

  /** Every request that arrived, in order. */
  val requests = mutableListOf<Recorded>()

  /** Commit messages that were accepted, in order. */
  val messages = mutableListOf<String>()

  private val files = mutableMapOf<String, ByteArray>()
  private var commitCounter = 0

  /** Status and message to answer with instead of doing the work. */
  var failWith: Pair<Int, String>? = null

  /** Thrown instead of answering, for the transport-failure case. */
  var throwWith: Exception? = null

  val requestCount: Int get() = requests.size

  fun countOf(method: String): Int = requests.count { it.method == method }

  /** Content as it stands now, for asserting that nothing was overwritten. */
  fun contentOf(path: String): String? = files[path]?.toString(Charsets.UTF_8)

  fun blobShaOf(path: String): String? = files[path]?.let { gitBlobSha(it) }

  /**
   * A write by somebody else — the case this whole design exists for.
   *
   * Deliberately not routed through [exchange]: it is not our client doing it, and it must not
   * appear in [requests].
   */
  fun writtenBySomeoneElse(path: String, text: String) {
    files[path] = text.toByteArray(Charsets.UTF_8)
  }

  override fun exchange(request: ExchangeRequest): ExchangeResponse {
    val body = request.body?.toString(Charsets.UTF_8)
    requests.add(Recorded(request.method, request.url, body))
    throwWith?.let { throw it }
    val (status, payload) = handle(
      request.method,
      request.url.removePrefix(apiBase),
      request.headers["Authorization"],
      body,
    )
    return ExchangeResponse(
      status,
      mapOf("content-type" to "application/json; charset=utf-8"),
      payload.toByteArray(Charsets.UTF_8),
    )
  }

  /**
   * The service itself: a method, a path with its query, an authorization header and a body in;
   * a status and a JSON payload out. Transport-free on purpose, so the loopback server and the
   * offline seam can both drive exactly the same behaviour.
   */
  fun handle(
    method: String,
    pathAndQuery: String,
    authorization: String?,
    body: String?,
  ): Pair<Int, String> {
    failWith?.let { (status, message) -> return status to errorBody(message) }

    if (authorization != "Bearer $acceptedToken") return 401 to errorBody("Bad credentials")

    val prefix = "/repos/$owner/$repository/contents/"
    val withoutQuery = pathAndQuery.substringBefore('?')
    if (!withoutQuery.startsWith(prefix)) return 404 to errorBody("Not Found")
    val path = percentDecode(withoutQuery.removePrefix(prefix))

    return when (method) {
      "GET" -> get(path)
      "PUT" -> put(path, body)
      else -> 404 to errorBody("Not Found")
    }
  }

  private fun get(path: String): Pair<Int, String> {
    val content = files[path]
      // A directory answers with an array of its entries, not with an object.
      ?: return if (files.keys.any { it.startsWith("$path/") }) 200 to "[]"
      else 404 to errorBody("Not Found")

    val node = MAPPER.createObjectNode()
    node.put("name", path.substringAfterLast('/'))
    node.put("path", path)
    node.put("sha", gitBlobSha(content))
    node.put("size", content.size)
    node.put("content", wrapAt60(Base64.getEncoder().encodeToString(content)))
    node.put("encoding", "base64")
    return 200 to MAPPER.writeValueAsString(node)
  }

  private fun put(path: String, body: String?): Pair<Int, String> {
    val fields = try {
      body?.let { MAPPER.readTree(it) }
    } catch (e: Exception) {
      null
    } ?: return 400 to errorBody("Problems parsing JSON")

    val message = fields.path("message").takeIf { it.isTextual }?.asText()
      ?: return 422 to errorBody("Invalid request. message wasn't supplied.")
    val encoded = fields.path("content").takeIf { it.isTextual }?.asText()
      ?: return 422 to errorBody("Invalid request. content wasn't supplied.")
    val offered = fields.path("sha").takeIf { it.isTextual }?.asText()

    val existing = files[path]
    // The two shapes of "you are not replacing what you think you are". Both are what the real API
    // answers, and both mean the same thing to a caller.
    when {
      existing != null && offered == null ->
        return 422 to errorBody("Invalid request. sha wasn't supplied.")
      existing != null && offered != gitBlobSha(existing) ->
        return 409 to errorBody("$path does not match ${gitBlobSha(existing)}")
      existing == null && offered != null ->
        return 422 to errorBody("$path does not exist")
    }

    val content = Base64.getMimeDecoder().decode(encoded)
    val created = existing == null
    files[path] = content
    messages.add(message)
    commitCounter++

    val node = MAPPER.createObjectNode()
    node.putObject("content").also {
      it.put("path", path)
      it.put("sha", gitBlobSha(content))
    }
    node.putObject("commit").also {
      it.put("sha", commitShaFor(commitCounter))
      it.put("message", message)
    }
    return (if (created) 201 else 200) to MAPPER.writeValueAsString(node)
  }

  /** Deterministic, so a rerun produces the same history. */
  private fun commitShaFor(counter: Int): String =
    sha1Hex("commit $counter of $owner/$repository".toByteArray(Charsets.UTF_8))

  private fun errorBody(message: String): String =
    MAPPER.writeValueAsString(MAPPER.createObjectNode().put("message", message))

  private fun percentDecode(encoded: String): String {
    val out = ByteArrayOutputStream()
    var i = 0
    while (i < encoded.length) {
      val ch = encoded[i]
      if (ch == '%' && i + 2 < encoded.length) {
        out.write(encoded.substring(i + 1, i + 3).toInt(16))
        i += 3
      } else {
        out.write(ch.code)
        i++
      }
    }
    return out.toByteArray().toString(Charsets.UTF_8)
  }

  companion object {
    private val MAPPER = ObjectMapper()

    /** The real thing: `sha1("blob <length>" + NUL + content)`. */
    fun gitBlobSha(content: ByteArray): String {
      val header = "blob ${content.size}".toByteArray(Charsets.UTF_8) + byteArrayOf(0)
      return sha1Hex(header + content)
    }

    private fun sha1Hex(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    /** GitHub wraps the base64 it sends; the basic decoder chokes on that. */
    fun wrapAt60(text: String): String = text.chunked(60).joinToString("\n") + "\n"
  }
}
