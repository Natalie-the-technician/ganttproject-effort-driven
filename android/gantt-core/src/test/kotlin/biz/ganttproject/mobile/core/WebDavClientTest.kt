/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises the WebDAV client without a network by canning the exchange.
 *
 * The status-to-meaning mapping gets the most attention here. Every one of
 * these codes ends in a different thing being said to the user — 412 is "your
 * change and someone else's collided", 423 is "just wait" — and getting two of
 * them mixed up produces a dialog that tells the user the wrong thing about
 * their own data.
 */
class WebDavClientTest {

  private class FakeExchange(
    private val handler: (HttpRequest) -> BinaryHttpResponse
  ) : HttpExchange {
    val requests = mutableListOf<HttpRequest>()
    override fun exchange(request: HttpRequest): BinaryHttpResponse {
      requests.add(request)
      return handler(request)
    }
  }

  private class ThrowingExchange(private val message: String) : HttpExchange {
    override fun exchange(request: HttpRequest): BinaryHttpResponse = throw java.io.IOException(message)
  }

  private fun response(
    status: Int,
    headers: Map<String, String> = emptyMap(),
    body: ByteArray = ByteArray(0)
  ) = BinaryHttpResponse(status, headers.mapKeys { it.key.lowercase() }, body)

  private fun clientWith(
    baseUrl: String = "https://dav.example.org/projekte",
    handler: (HttpRequest) -> BinaryHttpResponse
  ): Pair<WebDavClient, FakeExchange> {
    val backend = FakeExchange(handler)
    return WebDavClient(backend, WebDavConfig(baseUrl, "natalie", "geheim")) to backend
  }

  // --- reading -------------------------------------------------------------

  @Test
  fun `read returns bytes and etag`() {
    val bytes = "<project/>".toByteArray()
    val (client, _) = clientWith { response(200, mapOf("ETag" to "\"a1\""), bytes) }
    val result = client.read("haus.gan") as DavResult.Ok
    assertArrayEquals(bytes, result.value.bytes)
    assertEquals("\"a1\"", result.value.etag)
  }

  @Test
  fun `etag is passed through verbatim including weak prefix and quotes`() {
    val (client, _) = clientWith { response(200, mapOf("ETag" to "W/\"abc-123\"")) }
    val result = client.read("haus.gan") as DavResult.Ok
    // Stripping the quotes or the W/ would make the value fail to match what
    // the server expects back in If-Match, and every conditional write would
    // then be rejected — or worse, silently pass unconditionally.
    assertEquals("W/\"abc-123\"", result.value.etag)
  }

  @Test
  fun `missing etag on read is reported as unknown, not as empty`() {
    val (client, _) = clientWith { response(200) }
    val result = client.read("haus.gan") as DavResult.Ok
    assertNull(result.value.etag)
  }

  @Test
  fun `read preserves bytes exactly`() {
    // The bytes that a "tidying" server would change: BOM-free declaration,
    // CRLF mixed with LF, UTF-8 umlauts, no trailing newline.
    val bytes = "<?xml version=\"1.0\"?>\r\n<p n=\"Prüfung\"/>\n\tx".toByteArray(Charsets.UTF_8)
    val (client, _) = clientWith { response(200, mapOf("ETag" to "\"e\""), bytes) }
    val result = client.read("haus.gan") as DavResult.Ok
    assertArrayEquals(bytes, result.value.bytes)
  }

  // --- status mapping ------------------------------------------------------

  @Test
  fun `412 means changed elsewhere`() {
    val (client, _) = clientWith { response(412) }
    val result = client.write("haus.gan", ByteArray(0), "\"a1\"") as DavResult.Failed
    assertEquals(DavError.ChangedElsewhere, result.error)
  }

  @Test
  fun `423 means locked elsewhere`() {
    val (client, _) = clientWith { response(423) }
    val result = client.write("haus.gan", ByteArray(0), "\"a1\"") as DavResult.Failed
    assertEquals(DavError.LockedElsewhere, result.error)
  }

  @Test
  fun `412 and 423 are not the same error`() {
    val (conflicted, _) = clientWith { response(412) }
    val (locked, _) = clientWith { response(423) }
    val a = (conflicted.write("x.gan", ByteArray(0), null) as DavResult.Failed).error
    val b = (locked.write("x.gan", ByteArray(0), null) as DavResult.Failed).error
    assertFalse(a == b, "412 and 423 must stay distinguishable")
  }

  @Test
  fun `401 403 404 map to their own errors`() {
    listOf(
      401 to DavError.Unauthorized,
      403 to DavError.Forbidden,
      404 to DavError.NotFound
    ).forEach { (status, expected) ->
      val (client, _) = clientWith { response(status) }
      assertEquals(expected, (client.read("x.gan") as DavResult.Failed).error, "status $status")
    }
  }

  @Test
  fun `server errors keep their code`() {
    val (client, _) = clientWith { response(503) }
    val result = client.read("x.gan") as DavResult.Failed
    assertEquals(DavError.Server(503), result.error)
  }

  @Test
  fun `transport failure becomes a network error, not an exception`() {
    val client = WebDavClient(ThrowingExchange("no route to host"), WebDavConfig("https://d.example/p", "u", "p"))
    val result = client.read("x.gan") as DavResult.Failed
    assertTrue(result.error is DavError.Network)
  }

  // --- conditional writing -------------------------------------------------

  @Test
  fun `write sends If-Match when an etag is known`() {
    val (client, backend) = clientWith { response(204, mapOf("ETag" to "\"a2\"")) }
    client.write("haus.gan", "x".toByteArray(), "\"a1\"")
    assertEquals("\"a1\"", backend.requests.single().headers["If-Match"])
  }

  @Test
  fun `write without an etag sends no If-Match`() {
    val (client, backend) = clientWith { response(204, mapOf("ETag" to "\"a2\"")) }
    client.write("haus.gan", "x".toByteArray(), null)
    assertFalse(backend.requests.single().headers.containsKey("If-Match"))
  }

  @Test
  fun `write sends the bytes unchanged`() {
    val bytes = "<p n=\"Größe\"/>\r\n".toByteArray(Charsets.UTF_8)
    val (client, backend) = clientWith { response(204, mapOf("ETag" to "\"a2\"")) }
    client.write("haus.gan", bytes, null)
    assertArrayEquals(bytes, backend.requests.single().body)
  }

  @Test
  fun `createNew refuses to clobber an existing project`() {
    val (client, backend) = clientWith { response(201, mapOf("ETag" to "\"n\"")) }
    client.createNew("neu.gan", "x".toByteArray())
    assertEquals("*", backend.requests.single().headers["If-None-Match"])
  }

  // --- the etag fallback ---------------------------------------------------

  @Test
  fun `write follows up with HEAD when the server returns no etag`() {
    val (client, backend) = clientWith { request ->
      if (request.method == "PUT") response(204)
      else response(200, mapOf("ETag" to "\"fromHead\""))
    }
    val result = client.write("haus.gan", "x".toByteArray(), null) as DavResult.Ok
    assertEquals("\"fromHead\"", result.value)
    assertEquals(listOf("PUT", "HEAD"), backend.requests.map { it.method })
  }

  @Test
  fun `write reports an unknown etag rather than inventing one when HEAD fails`() {
    val (client, _) = clientWith { request ->
      if (request.method == "PUT") response(204) else response(500)
    }
    val result = client.write("haus.gan", "x".toByteArray(), "\"a1\"") as DavResult.Ok
    // Not "a1", not "": null. Reusing the old etag would make the next write
    // conditional on a version that no longer exists — it would be rejected —
    // and inventing one would make it unconditional, which silently discards
    // whatever someone else wrote in between.
    assertNull(result.value)
  }

  @Test
  fun `no extra HEAD when the PUT already carried an etag`() {
    val (client, backend) = clientWith { response(204, mapOf("ETag" to "\"a2\"")) }
    client.write("haus.gan", "x".toByteArray(), null)
    assertEquals(listOf("PUT"), backend.requests.map { it.method })
  }

  // --- transport security --------------------------------------------------

  @Test
  fun `plain http is refused before anything is sent`() {
    val (client, backend) = clientWith(baseUrl = "http://dav.example.org/projekte") { response(200) }
    val result = client.read("haus.gan") as DavResult.Failed
    assertEquals(DavError.Insecure, result.error)
    assertTrue(backend.requests.isEmpty(), "nothing may leave the device over plain http")
  }

  @Test
  fun `credentials never appear in an error value`() {
    val client = WebDavClient(ThrowingExchange("boom"), WebDavConfig("https://d.example/p", "natalie", "sehr-geheim"))
    val result = client.read("x.gan") as DavResult.Failed
    assertFalse(result.toString().contains("sehr-geheim"))
  }

  // --- capabilities --------------------------------------------------------

  @Test
  fun `DAV 1,2 is recognised as locking support`() {
    val (client, _) = clientWith { response(200, mapOf("DAV" to "1,2")) }
    val result = client.capabilities() as DavResult.Ok
    assertTrue(result.value.supportsLocking)
  }

  @Test
  fun `DAV 1 alone is not locking support`() {
    val (client, _) = clientWith { response(200, mapOf("DAV" to "1")) }
    val result = client.capabilities() as DavResult.Ok
    // This is the difference between preventing a conflict and merely
    // reporting one. Claiming locking here would silence a warning the user
    // still needs.
    assertFalse(result.value.supportsLocking)
  }

  @Test
  fun `DAV header with spaces and extra classes still parses`() {
    val (client, _) = clientWith { response(200, mapOf("DAV" to "1, 2, ordered-collections")) }
    val result = client.capabilities() as DavResult.Ok
    assertTrue(result.value.supportsLocking)
  }

  @Test
  fun `a missing DAV header is not locking support`() {
    val (client, _) = clientWith { response(200) }
    val result = client.capabilities() as DavResult.Ok
    assertFalse(result.value.supportsLocking)
  }

  @Test
  fun `12 in the DAV header is not class 2`() {
    val (client, _) = clientWith { response(200, mapOf("DAV" to "1,12")) }
    val result = client.capabilities() as DavResult.Ok
    // A substring match would read "12" as containing "2" and report locking
    // that is not there.
    assertFalse(result.value.supportsLocking)
  }

  // --- listing -------------------------------------------------------------

  @Test
  fun `list finds project files whatever the namespace prefix`() {
    val xml = """
      <?xml version="1.0"?>
      <D:multistatus xmlns:D="DAV:">
        <D:response><D:href>/projekte/</D:href></D:response>
        <D:response><D:href>/projekte/haus.gan</D:href></D:response>
        <D:response><href>/projekte/kunde%20xy.gan</href></D:response>
        <D:response><d:href>/projekte/notizen.txt</d:href></D:response>
      </D:multistatus>
    """.trimIndent().toByteArray()
    val (client, _) = clientWith { response(207, emptyMap(), xml) }
    val result = client.list() as DavResult.Ok
    assertEquals(listOf("haus.gan", "kunde xy.gan"), result.value)
  }

  // --- url building --------------------------------------------------------

  @Test
  fun `url joining tolerates a trailing slash on the base`() {
    val (withSlash, _) = clientWith(baseUrl = "https://d.example/p/") { response(200) }
    val (without, _) = clientWith(baseUrl = "https://d.example/p") { response(200) }
    assertEquals(without.urlFor("a.gan"), withSlash.urlFor("a.gan"))
  }

  // --- weak ETags -----------------------------------------------------------
  //
  // Measured against the real Apache: a PUT carries no ETag at all, and for
  // about a second afterwards HEAD reports the tag as weak. If-Match compares
  // strongly, so a weak tag matches nothing — not even itself — and sending
  // one back earns a 412 on a file nobody else touched.

  @Test
  fun `a weak stored etag is never sent in If-Match`() {
    val (client, backend) = clientWith { request ->
      when (request.method) {
        "HEAD" -> response(200, mapOf("ETag" to "\"1-abc\""))
        else -> response(204, mapOf("ETag" to "\"1-def\""))
      }
    }
    client.write("haus.gan", "x".toByteArray(), "W/\"1-abc\"")
    val put = backend.requests.single { it.method == "PUT" }
    assertEquals("\"1-abc\"", put.headers["If-Match"], "the strong form must be sent")
  }

  @Test
  fun `a weak etag that is still weak on re-read writes unconditionally`() {
    val (client, backend) = clientWith { request ->
      when (request.method) {
        "HEAD" -> response(200, mapOf("ETag" to "W/\"1-abc\""))
        else -> response(204, mapOf("ETag" to "\"1-def\""))
      }
    }
    client.write("haus.gan", "x".toByteArray(), "W/\"1-abc\"")
    val put = backend.requests.single { it.method == "PUT" }
    // The HEAD just confirmed the content is ours. Refusing to save at all
    // here would turn a remote risk into a certain annoyance.
    assertFalse(put.headers.containsKey("If-Match"))
  }

  @Test
  fun `a weak etag whose value changed is a conflict, and nothing is written`() {
    val (client, backend) = clientWith { request ->
      when (request.method) {
        "HEAD" -> response(200, mapOf("ETag" to "\"1-SOMEONE-ELSE\""))
        else -> response(204)
      }
    }
    val result = client.write("haus.gan", "x".toByteArray(), "W/\"1-abc\"") as DavResult.Failed
    assertEquals(DavError.ChangedElsewhere, result.error)
    assertTrue(backend.requests.none { it.method == "PUT" }, "nothing may be written on a conflict")
  }

  @Test
  fun `a strong stored etag is sent as is, with no extra HEAD`() {
    val (client, backend) = clientWith { response(204, mapOf("ETag" to "\"1-def\"")) }
    client.write("haus.gan", "x".toByteArray(), "\"1-abc\"")
    assertEquals(listOf("PUT"), backend.requests.map { it.method })
    assertEquals("\"1-abc\"", backend.requests.single().headers["If-Match"])
  }

  @Test
  fun `an unaskable server gets the weak tag and is left to refuse`() {
    val (client, backend) = clientWith { request ->
      if (request.method == "HEAD") response(500) else response(412)
    }
    val result = client.write("haus.gan", "x".toByteArray(), "W/\"1-abc\"") as DavResult.Failed
    // Refusing is the safe direction. Writing blind because a HEAD failed
    // would discard whatever is actually on the server.
    assertEquals(DavError.ChangedElsewhere, result.error)
    assertEquals("W/\"1-abc\"", backend.requests.single { it.method == "PUT" }.headers["If-Match"])
  }

  @Test
  fun `lower-case weak marker is recognised too`() {
    val (client, backend) = clientWith { request ->
      if (request.method == "HEAD") response(200, mapOf("ETag" to "\"1-abc\"")) else response(204)
    }
    client.write("haus.gan", "x".toByteArray(), "w/\"1-abc\"")
    assertEquals("\"1-abc\"", backend.requests.single { it.method == "PUT" }.headers["If-Match"])
  }

  // --- lock discovery --------------------------------------------------------
  //
  // The desktop locks a project for 120 minutes whenever it opens one, so a
  // held lock is the ordinary case during a working day at the PC. Asking
  // before editing beats refusing the save half an hour later.

  private fun lockXml(inner: String) = """
    <?xml version="1.0"?>
    <D:multistatus xmlns:D="DAV:"><D:response><D:propstat><D:prop>
      $inner
    </D:prop></D:propstat></D:response></D:multistatus>
  """.trimIndent().toByteArray()

  @Test
  fun `an active lock is reported`() {
    val xml = lockXml("<D:lockdiscovery><D:activelock><D:locktype><D:write/></D:locktype></D:activelock></D:lockdiscovery>")
    val (client, _) = clientWith { response(207, emptyMap(), xml) }
    val result = client.lockState("haus.gan") as DavResult.Ok
    assertTrue(result.value.lockedElsewhere)
  }

  @Test
  fun `an empty lockdiscovery is not a lock`() {
    val (client, _) = clientWith { response(207, emptyMap(), lockXml("<D:lockdiscovery/>")) }
    val result = client.lockState("haus.gan") as DavResult.Ok
    // The container is always present; only an entry inside it means someone
    // holds the file. Matching on lockdiscovery would report every project as
    // locked and make the warning worthless.
    assertFalse(result.value.lockedElsewhere)
  }

  @Test
  fun `lock discovery works whatever the namespace prefix`() {
    listOf("<d:activelock/>", "<activelock/>", "<D:activelock>x</D:activelock>").forEach { inner ->
      val (client, _) = clientWith { response(207, emptyMap(), lockXml("<D:lockdiscovery>$inner</D:lockdiscovery>")) }
      val result = client.lockState("haus.gan") as DavResult.Ok
      assertTrue(result.value.lockedElsewhere, "prefix variant: $inner")
    }
  }

  @Test
  fun `a word merely containing activelock is not a lock`() {
    val (client, _) = clientWith { response(207, emptyMap(), lockXml("<D:inactivelocking/>")) }
    val result = client.lockState("haus.gan") as DavResult.Ok
    assertFalse(result.value.lockedElsewhere)
  }

  @Test
  fun `lock discovery sends PROPFIND with depth zero`() {
    val (client, backend) = clientWith { response(207, emptyMap(), lockXml("<D:lockdiscovery/>")) }
    client.lockState("haus.gan")
    val request = backend.requests.single()
    assertEquals("PROPFIND", request.method)
    assertEquals("0", request.headers["Depth"])
  }

  @Test
  fun `a failed lock check is an error, not a quiet no`() {
    val (client, _) = clientWith { response(500) }
    val result = client.lockState("haus.gan") as DavResult.Failed
    // Reporting "not locked" when the question could not be asked would be a
    // reassurance nobody checked.
    assertEquals(DavError.Server(500), result.error)
  }

  // --- an address that is not an address -----------------------------------
  //
  // A settings field takes whatever is pasted into it. On 15 August 2026 a
  // paragraph of prose was pasted into the server address, the client
  // percent-encoded it into a valid-looking path, and the server answered 404
  // — which the app reported as "this project is no longer on the server".
  // Every step was correct and the result sent the reader hunting for a file
  // that had never gone missing.

  @Test
  fun `an address with spaces in it never reaches the network`() {
    val prose = "https://dav.example.org/intern/alle drei Fixes von heute. Warum es"
    val (client, backend) = clientWith(baseUrl = prose) { response(200) }
    val result = client.list() as DavResult.Failed
    assertEquals(DavError.BadAddress, result.error)
    assertTrue(backend.requests.isEmpty(), "nothing may be sent for a bad address")
  }

  @Test
  fun `a line break in the address is refused too`() {
    val (client, backend) = clientWith(baseUrl = "https://dav.example.org/a\nb/") { response(200) }
    val result = client.list() as DavResult.Failed
    assertEquals(DavError.BadAddress, result.error)
    assertTrue(backend.requests.isEmpty())
  }

  @Test
  fun `a trailing space in the address is trimmed, not sent`() {
    // What actually happened on the phone: the field held a trailing space
    // from a paste, the stored copy was trimmed on the way into preferences,
    // and the value in use was not. The server saw /intern/%20 and said 404,
    // so the app told her the project was gone.
    val (client, backend) = clientWith(baseUrl = "https://dav.example.org/intern/ ") {
      response(207, emptyMap(), "<D:multistatus xmlns:D=\"DAV:\"/>".toByteArray())
    }
    client.list()
    assertEquals("https://dav.example.org/intern/", backend.requests.single().url)
  }

  @Test
  fun `a trailing space does not stop a project from being addressed`() {
    val (client, backend) = clientWith(baseUrl = " https://dav.example.org/intern/ ") {
      response(200, mapOf("ETag" to "\"a1\""))
    }
    client.read("t4-probe.gan")
    assertEquals("https://dav.example.org/intern/t4-probe.gan", backend.requests.single().url)
  }

  @Test
  fun `a bad address is not reported as a missing project`() {
    // The distinction the whole check exists for: one sends the reader to the
    // settings, the other to look for a deleted file.
    val (bad, _) = clientWith(baseUrl = "https://dav.example.org/x y/") { response(404) }
    val (missing, _) = clientWith(baseUrl = "https://dav.example.org/x/") { response(404) }
    assertEquals(DavError.BadAddress, (bad.list() as DavResult.Failed).error)
    assertEquals(DavError.NotFound, (missing.list() as DavResult.Failed).error)
  }

  @Test
  fun `the check does not refuse an ordinary address`() {
    // A guard that is too eager costs more than the bug it prevents.
    val xml = """
      <D:multistatus xmlns:D="DAV:">
        <D:response><D:href>/intern/t4-probe.gan</D:href></D:response>
      </D:multistatus>
    """.trimIndent().toByteArray()
    val (client, _) = clientWith(baseUrl = "https://dav.example.org/intern/") {
      response(207, emptyMap(), xml)
    }
    assertEquals(listOf("t4-probe.gan"), (client.list() as DavResult.Ok).value)
  }

  @Test
  fun `a bad address also stops a save before the bytes leave`() {
    val (client, backend) = clientWith(baseUrl = "https://dav.example.org/x y/") { response(204) }
    val result = client.write("haus.gan", "geheim".toByteArray(), "\"a1\"") as DavResult.Failed
    assertEquals(DavError.BadAddress, result.error)
    assertTrue(backend.requests.isEmpty(), "a bad address must not put project bytes on the wire")
  }

  // --- lock and conflict together ---------------------------------------------
  //
  // Since the desktop takes a lock on open, a save can now fail for two
  // different reasons at once: the file is held AND its content moved on.
  // Which one the user is told decides whether they wait or resolve, so the
  // answer is pinned here rather than left to emerge.
  //
  // Which of the two the server reports is the server's decision, not ours.
  // Measured against the built Apache on 15 August 2026 by the desktop
  // session, with a control:
  //
  //   held, no If-Match         -> 423
  //   held, If-Match current    -> 423
  //   held, If-Match stale      -> 412   <- the precondition is evaluated first
  //
  // So a stale ETag reads as a conflict whether or not anyone holds the file,
  // and 423 is reachable only from an up-to-date phone. An earlier comment
  // here claimed the opposite ordering; it was derived, not measured, and it
  // was wrong. The client maps whatever arrives and does not re-rank it.

  @Test
  fun `423 means waiting, whatever the request carried`() {
    val (client, _) = clientWith { response(423) }
    val result = client.write("haus.gan", "x".toByteArray(), "\"current\"") as DavResult.Failed
    assertEquals(DavError.LockedElsewhere, result.error)
  }

  @Test
  fun `a stale etag reports a conflict, held or not`() {
    // Both of these are 412 on the real server: the held case because the
    // precondition is checked before the lock, the unheld case for the
    // obvious reason. One mapping covers both.
    val (client, _) = clientWith { response(412) }
    val result = client.write("haus.gan", "x".toByteArray(), "\"stale\"") as DavResult.Failed
    assertEquals(DavError.ChangedElsewhere, result.error)
  }

  @Test
  fun `a weak etag whose content moved on is decided here, before the server sees a PUT`() {
    val (client, backend) = clientWith { request ->
      when (request.method) {
        "HEAD" -> response(200, mapOf("ETag" to "\"someone-else\""))
        else -> response(423)   // the server would have said "locked"
      }
    }
    val result = client.write("haus.gan", "x".toByteArray(), "W/\"mine\"") as DavResult.Failed
    // The client answers ChangedElsewhere without ever sending the PUT, so the
    // server never gets to say 423. Reachable only in the one-second window
    // where the stored tag is still weak — after a normal open it is strong
    // and the server decides. Named here because it is the one case where the
    // app, not the server, picks the message.
    assertEquals(DavError.ChangedElsewhere, result.error)
    assertTrue(backend.requests.none { it.method == "PUT" })
  }

  @Test
  fun `the client never locks, whatever it is asked to do`() {
    val (client, backend) = clientWith { request ->
      when (request.method) {
        "GET" -> response(200, mapOf("ETag" to "\"a1\""), "<project/>".toByteArray())
        "PROPFIND" -> response(207, emptyMap(), lockXml("<D:lockdiscovery/>"))
        else -> response(204, mapOf("ETag" to "\"a2\""))
      }
    }
    client.capabilities(); client.list(); client.read("h.gan")
    client.lockState("h.gan"); client.write("h.gan", "x".toByteArray(), "\"a1\"")
    client.createNew("n.gan", "x".toByteArray())

    val methods = backend.requests.map { it.method }.toSet()
    // Deliberate, and now pinned rather than merely intended. A phone that
    // loses signal mid-edit would leave a lock nobody releases, and the PC
    // would sit in front of it until the timeout. If-Match protects us
    // without that side effect.
    //
    // The desktop session found the cost of the other choice the hard way:
    // after taking its own lock, Milton left the lock owner unset, so its own
    // writability check said "not writable" and the PC locked itself out. A
    // client that never locks cannot have that class of bug at all.
    assertFalse(methods.contains("LOCK"), "the app must never take a lock")
    assertFalse(methods.contains("UNLOCK"), "the app must never release one either")
  }

  @Test
  fun `spaces and hashes in a name are escaped`() {
    val (client, _) = clientWith { response(200) }
    assertEquals("https://dav.example.org/projekte/kunde%20xy%232.gan", client.urlFor("kunde xy#2.gan"))
  }
}
