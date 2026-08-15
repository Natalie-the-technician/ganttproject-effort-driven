/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import java.util.Base64

/**
 * A single HTTP exchange with a binary body.
 *
 * Separate from [HttpBackend], which reads text over GET and is all the Toggl
 * import needs. WebDAV needs arbitrary methods, response headers (the ETag is
 * the whole point) and — above all — **bytes**. Routing a project file through
 * a String would decode and re-encode it, and the byte-for-byte guarantee the
 * app rests on would be gone before the file ever reached the server.
 */
data class HttpRequest(
  val method: String,
  val url: String,
  val headers: Map<String, String> = emptyMap(),
  val body: ByteArray? = null
) {
  // Generated equals/hashCode would compare the body array by identity, which
  // is never what a caller means. Nothing compares requests today; overriding
  // keeps that from becoming a silent bug the day something does.
  override fun equals(other: Any?) = this === other
  override fun hashCode() = System.identityHashCode(this)
}

/** Response with an undecoded body and header names folded to lower case. */
data class BinaryHttpResponse(
  val status: Int,
  val headers: Map<String, String>,
  val body: ByteArray
) {
  fun header(name: String): String? = headers[name.lowercase()]

  override fun equals(other: Any?) = this === other
  override fun hashCode() = System.identityHashCode(this)
}

interface HttpExchange {
  /**
   * Performs [request] and returns the response.
   *
   * A non-2xx status **must be returned, not thrown**. Every status in
   * [DavError] carries a distinct meaning for the user — 412 is a conflict,
   * 423 is "someone else has it open" — and an implementation that throws
   * collapses that into one failure the client can no longer tell apart.
   */
  fun exchange(request: HttpRequest): BinaryHttpResponse
}

/** Where the projects live and who we are. */
data class WebDavConfig(
  val baseUrl: String,
  val username: String,
  val password: String
)

/**
 * Whether someone else currently holds a WebDAV lock on a project.
 *
 * Worth asking *before* editing rather than discovering at save time. The
 * desktop takes a lock for 120 minutes whenever it opens a project, so a
 * locked file is the ordinary case during a working day at the PC — not an
 * exception. Letting someone type for half an hour and only then refusing the
 * save is the avoidable version of the same information.
 */
data class LockState(val lockedElsewhere: Boolean)

/** A project as it exists on the server, with the version that produced it. */
class RemoteProject(val bytes: ByteArray, val etag: String?)

/** What the server admitted to being able to do. */
data class DavCapabilities(
  /** `DAV: 2` in the OPTIONS response — the server implements LOCK/UNLOCK. */
  val supportsLocking: Boolean,
  val rawDavHeader: String?
)

sealed interface DavError {
  /** 401 — credentials wrong or missing. */
  data object Unauthorized : DavError

  /** 403 — authenticated but not allowed to write here. */
  data object Forbidden : DavError

  /** 404 — project gone or renamed. */
  data object NotFound : DavError

  /** 412 — changed elsewhere since we read it. The conflict case. */
  data object ChangedElsewhere : DavError

  /** 423 — someone holds the lock. Nothing is lost; it is a "wait" case. */
  data object LockedElsewhere : DavError

  /** Any other server-side failure. Retryable, never a reason to discard. */
  data class Server(val code: Int) : DavError

  /** Transport failure — offline, DNS, TLS. */
  data class Network(val detail: String) : DavError

  /** The address is not https://. Refused before anything leaves the device. */
  data object Insecure : DavError

  /**
   * The address is not an address — whitespace, control characters, or absurd
   * length. Kept apart from [NotFound] because the two send the reader to
   * different places: one to the settings, one to look for a missing file.
   */
  data object BadAddress : DavError
}

sealed interface DavResult<out T> {
  data class Ok<T>(val value: T) : DavResult<T>
  data class Failed(val error: DavError) : DavResult<Nothing>
}

/**
 * Talks WebDAV to the project store.
 *
 * Deliberately small: this is `GET` and `PUT` with two conditional headers,
 * not a WebDAV library. Anything beyond what the sync actually needs would be
 * surface to maintain for no gain.
 *
 * The contract this implements is `HANDOVER-Sync-Server.md` §3 (S1…S9); the
 * numbers in the comments below refer to it.
 */
class WebDavClient(
  private val http: HttpExchange,
  private val config: WebDavConfig
) {

  /**
   * The configured address with surrounding whitespace removed.
   *
   * A field on a phone collects a trailing space from a paste or an
   * autocorrect, and nobody can see it. Untrimmed it became part of the
   * request path — the server was asked for `/intern/%20`, answered 404, and
   * the app said the project was gone. The stored copy was trimmed on its way
   * into preferences, so it looked right everywhere a person could look, while
   * the value actually used still carried the space.
   *
   * Trimmed here rather than at the field, so it holds for every caller
   * instead of for the ones that remembered.
   */
  private val collection: String get() = config.baseUrl.trim()

  /**
   * Rejects an address that must not be used, before anything leaves the
   * device.
   *
   * Not https (S7) is checked on every request rather than once at
   * configuration time: a URL can also arrive from a restored preference or a
   * future settings import, and Basic auth puts the credentials in *every*
   * request, so there is no such thing as one harmless plaintext call.
   *
   * The whitespace check is not pedantry. A settings field accepts whatever is
   * pasted into it, and what gets pasted is not always an address: a paragraph
   * of prose lands there, is percent-encoded into a valid-looking path, and
   * the server answers 404 — which the app then reports as "this project is no
   * longer on the server". Every step correct, and the reader is sent hunting
   * for a file that was never missing. Refusing here makes the address itself
   * the subject of the complaint.
   *
   * Interior whitespace only: the edges are already gone, see [collection].
   */
  private fun requireHttps(url: String): DavError? = when {
    !url.startsWith("https://", ignoreCase = true) -> DavError.Insecure
    url.any { it.isWhitespace() || it.isISOControl() } -> DavError.BadAddress
    url.length > MAX_URL_LENGTH -> DavError.BadAddress
    else -> null
  }

  private fun authHeaders(): Map<String, String> {
    val credentials = Base64.getEncoder()
      .encodeToString("${config.username}:${config.password}".toByteArray(Charsets.UTF_8))
    return mapOf("Authorization" to "Basic $credentials")
  }

  /**
   * Joins the base URL and a project name.
   *
   * Only the name is escaped, and only the characters that would otherwise
   * change the request's meaning. A full URL encoder would also escape the
   * slashes in the base and turn one path into one long segment.
   *
   * Public because callers need the address of a project for display and for
   * the recent list, not only for sending requests.
   */
  fun urlFor(name: String): String {
    val base = collection.trimEnd('/')
    val escaped = name.flatMap { ch ->
      when (ch) {
        ' ' -> "%20".toList()
        '#' -> "%23".toList()
        '?' -> "%3F".toList()
        '%' -> "%25".toList()
        else -> listOf(ch)
      }
    }.joinToString("")
    return "$base/$escaped"
  }

  private fun send(request: HttpRequest): DavResult<BinaryHttpResponse> {
    requireHttps(request.url)?.let { return DavResult.Failed(it) }
    return try {
      DavResult.Ok(http.exchange(request))
    } catch (e: Exception) {
      // Transport failure only. A status code never arrives here — see the
      // HttpExchange contract.
      DavResult.Failed(DavError.Network(e.message ?: e::class.simpleName.orEmpty()))
    }
  }

  /** Maps a status to its meaning for the user (S9). */
  private fun errorFor(status: Int): DavError = when (status) {
    401 -> DavError.Unauthorized
    403 -> DavError.Forbidden
    404 -> DavError.NotFound
    412 -> DavError.ChangedElsewhere
    423 -> DavError.LockedElsewhere
    else -> DavError.Server(status)
  }

  /**
   * Asks the server what it can do (S1, S5).
   *
   * The `DAV` header decides whether the desktop will ever take a lock, and
   * therefore whether the app may call this storage managed. Reported honestly
   * rather than assumed: a server that cannot lock is, for conflicts, no
   * better than a file in a synced folder.
   */
  fun capabilities(): DavResult<DavCapabilities> {
    val result = send(HttpRequest("OPTIONS", collection.trimEnd('/') + "/", authHeaders()))
    val response = when (result) {
      is DavResult.Failed -> return result
      is DavResult.Ok -> result.value
    }
    if (response.status !in 200..299) return DavResult.Failed(errorFor(response.status))
    val dav = response.header("dav")
    // "DAV: 1,2" — class 2 is locking. Parsed by token so that "1, 2" and
    // "1,2,3" and "1, 2, ordered-collections" all read the same.
    val classes = dav.orEmpty().split(',').map { it.trim() }
    return DavResult.Ok(DavCapabilities(supportsLocking = classes.contains("2"), rawDavHeader = dav))
  }

  /** Reads a project and the ETag that identifies this exact content (S2). */
  fun read(name: String): DavResult<RemoteProject> {
    val result = send(HttpRequest("GET", urlFor(name), authHeaders()))
    val response = when (result) {
      is DavResult.Failed -> return result
      is DavResult.Ok -> result.value
    }
    if (response.status !in 200..299) return DavResult.Failed(errorFor(response.status))
    // The ETag is passed on exactly as received, quotes, W/ prefix and all.
    // It is an opaque token; the only valid operation is comparing it for
    // equality with one the same server sent earlier.
    return DavResult.Ok(RemoteProject(response.body, response.header("etag")))
  }

  /**
   * Writes a project back, refusing if it changed meanwhile (S3).
   *
   * [ifMatch] is the ETag from the [read] that produced this content. Passing
   * null overwrites unconditionally — the "overwrite anyway" the user picks in
   * the conflict dialog, never the normal path.
   *
   * Returns the new ETag, or null when the server gave none and could not be
   * asked for one. Null means "unknown", and the caller must treat it as such:
   * the next write then has nothing to be conditional on.
   */
  /** True for `W/"..."` — an ETag that can never satisfy `If-Match`. */
  private fun isWeak(etag: String) = etag.startsWith("W/", ignoreCase = true)

  /** The value without its weakness marker, for comparing two tags by identity. */
  private fun opaque(etag: String) = etag.removePrefix("W/").removePrefix("w/")

  /**
   * Turns a stored ETag into one that can actually be used in `If-Match`.
   *
   * RFC 7232 compares `If-Match` with the **strong** function, and a weak tag
   * fails that comparison against everything — including against itself. A
   * server may legitimately answer a fresh write with a weak tag (Apache does,
   * for about a second, because a file written twice within its timestamp
   * resolution could differ without the tag differing). Sending that tag back
   * would earn a 412 on a file nobody else has touched, and the app would put
   * a conflict dialog in front of the user over a conflict that never existed.
   *
   * So a weak tag is re-fetched rather than sent:
   * - the resource now reports a different value → someone really did write,
   *   and the caller is told so without anything being overwritten
   * - it reports the same value, now strong → use it, which is the ordinary
   *   case once a second has passed
   * - it reports the same value, still weak → see [Resolved.Unconditional]
   */
  private sealed interface Resolved {
    data class Strong(val etag: String) : Resolved
    /**
     * The server still will not issue a usable tag, but a HEAD taken
     * milliseconds ago showed the content is exactly what we last wrote.
     *
     * The write then goes out unconditionally. That is a deliberate, narrow
     * concession: it reopens — for those milliseconds — the check-then-write
     * gap the server exists to close. The alternative is refusing to save at
     * all whenever the user saves twice inside one second, which trades a
     * remote risk for a certain annoyance. Only reachable in that window.
     */
    data object Unconditional : Resolved
    data class Conflict(val error: DavError) : Resolved
  }

  private fun resolveIfMatch(name: String, ifMatch: String?): Resolved {
    if (ifMatch == null) return Resolved.Unconditional
    if (!isWeak(ifMatch)) return Resolved.Strong(ifMatch)

    val head = (send(HttpRequest("HEAD", urlFor(name), authHeaders())) as? DavResult.Ok)?.value
    // Cannot ask: send the weak tag and let the server refuse. Refusing is
    // the safe direction; writing blind is not.
    val current = head?.header("etag") ?: return Resolved.Strong(ifMatch)
    if (head.status !in 200..299) return Resolved.Strong(ifMatch)
    if (opaque(current) != opaque(ifMatch)) return Resolved.Conflict(DavError.ChangedElsewhere)
    return if (isWeak(current)) Resolved.Unconditional else Resolved.Strong(current)
  }

  fun write(name: String, bytes: ByteArray, ifMatch: String?): DavResult<String?> {
    val effective = when (val resolved = resolveIfMatch(name, ifMatch)) {
      is Resolved.Conflict -> return DavResult.Failed(resolved.error)
      is Resolved.Strong -> resolved.etag
      Resolved.Unconditional -> null
    }
    return writeWith(name, bytes, effective)
  }

  private fun writeWith(name: String, bytes: ByteArray, ifMatch: String?): DavResult<String?> {
    val headers = buildMap {
      putAll(authHeaders())
      put("Content-Type", "application/xml")
      if (ifMatch != null) put("If-Match", ifMatch)
    }
    val result = send(HttpRequest("PUT", urlFor(name), headers, bytes))
    val response = when (result) {
      is DavResult.Failed -> return result
      is DavResult.Ok -> result.value
    }
    if (response.status !in 200..299) return DavResult.Failed(errorFor(response.status))

    response.header("etag")?.let { return DavResult.Ok(it) }

    // S4 is only a SHOULD, so the ETag can legitimately be missing. Fetch it
    // rather than guess.
    val headResult = send(HttpRequest("HEAD", urlFor(name), authHeaders()))
    val head = (headResult as? DavResult.Ok)?.value
    // If that fails too, report the write as succeeded — it did — with an
    // unknown version. Inventing or reusing an ETag here would make the next
    // write silently unconditional, which is the one failure this whole
    // mechanism exists to prevent.
    return DavResult.Ok(if (head != null && head.status in 200..299) head.header("etag") else null)
  }

  /** Creates a project, failing if one of that name already exists (S3). */
  fun createNew(name: String, bytes: ByteArray): DavResult<String?> {
    val headers = buildMap {
      putAll(authHeaders())
      put("Content-Type", "application/xml")
      put("If-None-Match", "*")
    }
    val result = send(HttpRequest("PUT", urlFor(name), headers, bytes))
    val response = when (result) {
      is DavResult.Failed -> return result
      is DavResult.Ok -> result.value
    }
    if (response.status !in 200..299) return DavResult.Failed(errorFor(response.status))
    return DavResult.Ok(response.header("etag"))
  }

  /**
   * Lists the project files in the collection.
   *
   * The XML is scanned for href elements rather than parsed as a namespaced
   * document: the only thing wanted is the file names, the namespace prefix
   * varies between servers (`D:href`, `d:href`, `href`), and a full parse
   * would be more code failing in more ways for the same answer.
   */
  fun list(): DavResult<List<String>> {
    val headers = buildMap {
      putAll(authHeaders())
      put("Depth", "1")
      put("Content-Type", "application/xml")
    }
    val result = send(HttpRequest("PROPFIND", collection.trimEnd('/') + "/", headers))
    val response = when (result) {
      is DavResult.Failed -> return result
      is DavResult.Ok -> result.value
    }
    if (response.status !in 200..299) return DavResult.Failed(errorFor(response.status))
    val xml = String(response.body, Charsets.UTF_8)
    val names = HREF.findAll(xml)
      .map { it.groupValues[1].trimEnd('/').substringAfterLast('/') }
      .filter { it.endsWith(".gan", ignoreCase = true) }
      .map { decodePercent(it) }
      .distinct()
      .toList()
    return DavResult.Ok(names)
  }

  /**
   * Asks whether a lock is held on a project (RFC 4918 `lockdiscovery`).
   *
   * Scanned for `<activelock>` rather than parsed as a namespaced document:
   * the prefix varies between servers (`D:activelock`, `d:activelock`,
   * `activelock`), and the only question is whether one is present. An empty
   * `<lockdiscovery/>` means no lock, which is why the marker searched for is
   * the entry, not the container.
   */
  fun lockState(name: String): DavResult<LockState> {
    val headers = buildMap {
      putAll(authHeaders())
      put("Depth", "0")
      put("Content-Type", "application/xml")
    }
    val body = """<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:"><D:prop><D:lockdiscovery/></D:prop></D:propfind>"""
      .toByteArray(Charsets.UTF_8)
    val result = send(HttpRequest("PROPFIND", urlFor(name), headers, body))
    val response = when (result) {
      is DavResult.Failed -> return result
      is DavResult.Ok -> result.value
    }
    if (response.status !in 200..299) return DavResult.Failed(errorFor(response.status))
    val xml = String(response.body, Charsets.UTF_8)
    return DavResult.Ok(LockState(lockedElsewhere = ACTIVE_LOCK.containsMatchIn(xml)))
  }

  private fun decodePercent(value: String): String {
    if (!value.contains('%')) return value
    val out = StringBuilder()
    var i = 0
    while (i < value.length) {
      val ch = value[i]
      val hex = if (ch == '%' && i + 2 < value.length) value.substring(i + 1, i + 3) else null
      val code = hex?.toIntOrNull(16)
      if (code != null) {
        out.append(code.toChar())
        i += 3
      } else {
        out.append(ch)
        i++
      }
    }
    return out.toString()
  }

  private companion object {
    /**
     * Well past any real project URL and well short of a pasted paragraph.
     * A limit is needed at all because the check below reports the address,
     * and an error message quoting several hundred characters of prose helps
     * nobody.
     */
    const val MAX_URL_LENGTH = 2048

    val HREF = Regex("<[^>]*href[^>]*>([^<]*)</[^>]*href[^>]*>", RegexOption.IGNORE_CASE)

    /**
     * Matches `<activelock>` with or without a namespace prefix.
     *
     * The trailing class must include `/`, or the self-closing `<activelock/>`
     * — which is what a server sends for a lock with no further detail — is
     * missed and the file reads as unlocked.
     */
    val ACTIVE_LOCK = Regex("<[a-z0-9]*:?activelock[\\s/>]", RegexOption.IGNORE_CASE)
  }
}
