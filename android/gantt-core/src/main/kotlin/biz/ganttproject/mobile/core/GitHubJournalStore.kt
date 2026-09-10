/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import java.util.Base64

/**
 * Which file in which repository the journal is kept in.
 *
 * [branch] is null for "the repository's default branch", which is what the
 * API does when the parameter is left out. Naming it explicitly is only
 * needed where the journal lives somewhere other than the default.
 */
data class GitHubTarget(
  val owner: String,
  val repository: String,
  /** Path inside the repository, e.g. `hours/haus.timelog`. No leading slash. */
  val path: String,
  val branch: String? = null
)

/** The public API host. Overridable so a test can point somewhere else. */
const val GITHUB_API_BASE = "https://api.github.com"

/**
 * A journal kept as one file in a GitHub repository, one commit per save.
 *
 * ## Why the contents API and not the git data API
 *
 * `GET/PUT /repos/{owner}/{repo}/contents/{path}` does the whole job in one
 * request each. Building a commit through the git data API instead
 * (`/git/blobs`, `/git/trees`, `/git/commits`, `/git/refs`) takes four to five
 * requests and reimplements what the contents API already does — including
 * the one part that matters most here: the contents API takes the blob `sha`
 * of the version being replaced as a **precondition** and refuses the write
 * when it no longer matches. That is the conflict guard, handed to us.
 *
 * ## Why not `git push`
 *
 * There is no `git` program on a phone. A store that shelled out to one would
 * work on the desktop and nowhere else, and then this would be two
 * implementations rather than one. Both sides can speak HTTP; only one side
 * can run git.
 *
 * ## The baseline, and why it is the whole safety story
 *
 * The store remembers what [load] last saw: the text and its blob `sha`. A
 * [save] writes with **that** `sha`, never with a freshly fetched one.
 *
 * That distinction is the entire protection and it is easy to get backwards.
 * Fetching a fresh `sha` just before writing would satisfy GitHub every time
 * — and would silently overwrite whatever somebody else committed in the
 * meantime. Writing with the `sha` the text was derived from is what makes
 * GitHub reject the write instead. This is the same rule as `If-Match` on the
 * WebDAV side (see [WebDavClient] and DavError.ChangedElsewhere): on a
 * collision, refuse; do not overwrite.
 *
 * A refused write therefore leaves the baseline **untouched**. Calling [save]
 * again fails the same way, on purpose. Only [load] moves the baseline
 * forward, because re-reading is the deliberate act that lets a caller merge
 * and try again.
 *
 * ## The token
 *
 * Handed in, never obtained here — there is no sign-in flow in this class.
 * It is never logged, never put in a failure reason and never included in
 * [name].
 */
class GitHubJournalStore(
  private val http: HttpExchange,
  private val target: GitHubTarget,
  /**
   * A token that may write to [target]. Handed in by the caller.
   *
   * A GitHub App user token expires after a few hours, so a caller that keeps
   * a store alive across a refresh will need to build a new one. That is one
   * line at the call site and keeps this class free of any notion of how a
   * token is come by.
   */
  private val token: String,
  private val apiBase: String = GITHUB_API_BASE
) : JournalStore {

  /** What the last [load] — or a create — established about the remote file. */
  private data class Baseline(
    /** Blob sha of the version seen, or null when the file was absent. */
    val blobSha: String?,
    /** The text seen, or null when the file was absent. */
    val text: String?
  )

  private var baseline: Baseline? = null

  /**
   * Why the last [load] came back null without the file being absent.
   *
   * [JournalStore.load] returns `String?` and has nowhere to put a failure, so
   * a rejected token and an empty repository both arrive at the caller as
   * null. This field is how the two can still be told apart, and it is worth
   * being blunt about the shortcoming: a caller that treats null as "no
   * journal yet" is wrong whenever this is set.
   *
   * The damage is contained regardless. A failed load does **not** set the
   * baseline, so the [save] that follows cannot mistake "could not read" for
   * "nothing there" and create a file over the top of one it never saw.
   */
  var lastLoadFailure: String? = null
    private set

  override val name: String
    get() = "GitHub ${target.owner}/${target.repository}/${target.path}"

  override fun load(): String? {
    val outcome = fetch()
    return when (outcome) {
      is Fetch.Absent -> {
        lastLoadFailure = null
        baseline = Baseline(null, null)
        null
      }
      is Fetch.Present -> {
        lastLoadFailure = null
        baseline = Baseline(outcome.blobSha, outcome.text)
        outcome.text
      }
      is Fetch.Broken -> {
        // Deliberately no baseline: see lastLoadFailure.
        lastLoadFailure = outcome.reason
        null
      }
    }
  }

  override fun save(text: String, message: String): SaveOutcome {
    val known = baseline ?: return saveWithoutHavingRead(text, message)
    if (known.text == text) {
      // Nothing to say. No request at all — not a fetch, not a commit. The
      // file store re-reads the file to decide this; here the answer is
      // already in hand and the saving is a whole round trip.
      return SaveOutcome.Unchanged
    }
    return commit(text, message, known.blobSha)
  }

  /**
   * The first save of a store that was never asked to [load].
   *
   * Creating a file that is not there is fine. Replacing one this program has
   * never read is not: its content would be gone with nothing to merge from
   * and no way to notice. So that case is refused and stays refused until
   * somebody calls [load], which is the act of reading it.
   */
  private fun saveWithoutHavingRead(text: String, message: String): SaveOutcome =
    when (val outcome = fetch()) {
      is Fetch.Absent -> {
        baseline = Baseline(null, null)
        commit(text, message, null)
      }
      is Fetch.Present ->
        if (outcome.text == text) {
          // Identical, so reading it changed nothing and there is no risk in
          // saying so. Recording the baseline is safe for the same reason.
          baseline = Baseline(outcome.blobSha, outcome.text)
          SaveOutcome.Unchanged
        } else {
          // No baseline recorded on purpose: recording it here would let the
          // very next save overwrite the file this branch exists to protect.
          SaveOutcome.Failed(
            "$name already holds a journal that this program has not read. " +
              "Read it first, so the two can be merged instead of one replacing the other."
          )
        }
      is Fetch.Broken -> SaveOutcome.Failed(outcome.reason)
    }

  // --- talking to GitHub ---------------------------------------------------

  private sealed interface Fetch {
    data object Absent : Fetch
    data class Present(val text: String, val blobSha: String) : Fetch
    data class Broken(val reason: String) : Fetch
  }

  private fun contentsUrl(withRef: Boolean): String {
    val path = target.path.split('/').joinToString("/") { encodePathSegment(it) }
    val base = "$apiBase/repos/${encodePathSegment(target.owner)}/" +
      "${encodePathSegment(target.repository)}/contents/$path"
    val ref = target.branch
    return if (withRef && ref != null) "$base?ref=${encodeQueryValue(ref)}" else base
  }

  private fun headers(): Map<String, String> = mapOf(
    "Authorization" to "Bearer $token",
    "Accept" to "application/vnd.github+json",
    // Pinning the API version is GitHub's own advice: without it a future
    // default could change a field this parser depends on, and the failure
    // would arrive without anything here having been touched.
    "X-GitHub-Api-Version" to GITHUB_API_VERSION,
    "User-Agent" to GITHUB_USER_AGENT
  )

  private fun fetch(): Fetch {
    val response = try {
      http.exchange(HttpRequest("GET", contentsUrl(withRef = true), headers()))
    } catch (e: Exception) {
      return Fetch.Broken(networkReason(e))
    }
    if (response.status == 404) return Fetch.Absent
    if (response.status !in 200..299) return Fetch.Broken(reasonFor(response, reading = true))

    val body = String(response.body, Charsets.UTF_8)
    val json = try {
      Json.parse(body)
    } catch (e: JsonParseException) {
      return Fetch.Broken("GitHub's answer for $name could not be read as JSON.")
    }
    // A directory answers with an array, not an object. Treating that as an
    // absent file would create one over a folder's name.
    val obj = json.asObject()
      ?: return Fetch.Broken("${target.path} is a directory in ${target.owner}/${target.repository}, not a journal file.")
    val sha = obj["sha"].asString()
      ?: return Fetch.Broken("GitHub's answer for $name carried no version id.")
    val encoding = obj["encoding"].asString()
    if (encoding != "base64") {
      // "none" is what a file above the contents API's size limit comes back
      // as: the metadata arrives, the content does not. Anything else is
      // unknown. Either way there is no text here, and pretending otherwise
      // would hand the caller an empty journal.
      return Fetch.Broken(
        "GitHub did not send the contents of $name (encoding \"${encoding ?: "missing"}\"). " +
          "A journal larger than a megabyte has to be fetched a different way."
      )
    }
    val encoded = obj["content"].asString()
      ?: return Fetch.Broken("GitHub's answer for $name carried no content.")
    val bytes = try {
      // MIME, not basic: GitHub wraps the base64 at 60 characters, and the
      // basic decoder throws on the line breaks rather than skipping them.
      Base64.getMimeDecoder().decode(encoded)
    } catch (e: IllegalArgumentException) {
      return Fetch.Broken("GitHub's answer for $name was not readable base64.")
    }
    return Fetch.Present(String(bytes, Charsets.UTF_8), sha)
  }

  /**
   * Writes [text], replacing the version identified by [previousBlobSha].
   *
   * A null [previousBlobSha] means "there is nothing there", and GitHub
   * enforces that: sending no sha for a file that does exist is rejected
   * rather than obeyed.
   */
  private fun commit(text: String, message: String, previousBlobSha: String?): SaveOutcome {
    val fields = mutableListOf(
      "message" to jsonString(message),
      // No line breaks in the payload: the wrapping GitHub uses when it
      // sends content back is not required of what it is given.
      "content" to jsonString(Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)))
    )
    previousBlobSha?.let { fields.add("sha" to jsonString(it)) }
    target.branch?.let { fields.add("branch" to jsonString(it)) }
    val body = fields.joinToString(",", "{", "}") { (key, value) -> "${jsonString(key)}:$value" }

    val response = try {
      http.exchange(
        HttpRequest(
          "PUT",
          contentsUrl(withRef = false),
          headers() + ("Content-Type" to "application/json"),
          body.toByteArray(Charsets.UTF_8)
        )
      )
    } catch (e: Exception) {
      return SaveOutcome.Failed(networkReason(e))
    }

    if (response.status !in 200..299) {
      return SaveOutcome.Failed(reasonFor(response, reading = false))
    }

    val answer = try {
      Json.parse(String(response.body, Charsets.UTF_8)).asObject()
    } catch (e: JsonParseException) {
      null
    }
    val newBlobSha = answer?.get("content").asObject()?.get("sha").asString()
    val commitSha = answer?.get("commit").asObject()?.get("sha").asString()

    // The baseline moves forward to what was just written. Without the new
    // blob sha the next save has no valid precondition, so the baseline is
    // dropped instead of being left describing a version that is gone; the
    // next save then re-reads rather than writing against a stale handle.
    baseline = newBlobSha?.let { Baseline(it, text) }

    // revision carries the COMMIT sha, not the blob sha. Both come back, and
    // it is the commit that a restore needs: it names a point in the history
    // that every path can be read at (`?ref=<commit>`), and it is what `git
    // show <sha>:path` and the compare view take. The blob sha names content
    // and nothing else — two identical saves in different weeks share one,
    // and it cannot say when anything happened or what else changed with it.
    // The blob sha is kept, but privately, because its job is the write
    // precondition rather than anything a caller would want.
    return SaveOutcome.Saved(commitSha)
  }

  // --- turning a failure into something a person can act on ----------------

  private fun networkReason(e: Exception): String {
    val detail = e.message ?: e::class.simpleName.orEmpty()
    return "GitHub could not be reached${if (detail.isBlank()) "" else ": $detail"}."
  }

  /**
   * A sentence, not a number.
   *
   * GitHub's own `message` field is folded in where there is one, because it
   * is often the only thing that says *which* rule was broken. It is
   * truncated: an error body can be an entire HTML page, which has no
   * business being held or shown. Nothing from the request is quoted, so
   * there is no path by which the token could reach this string.
   */
  private fun reasonFor(response: BinaryHttpResponse, reading: Boolean): String {
    val detail = githubMessage(response)
    val suffix = if (detail == null) "" else " GitHub said: $detail"
    return when (response.status) {
      401 -> "The GitHub token was refused. Connect the account again.$suffix"
      403 ->
        if (response.header("x-ratelimit-remaining") == "0") {
          "GitHub's request limit is used up for now. The journal will be saved on the next try.$suffix"
        } else {
          "This GitHub token may not ${if (reading) "read" else "write"} $name.$suffix"
        }
      404 ->
        // 404 on a write is GitHub hiding a repository the token cannot see,
        // which is the same answer it gives for one that does not exist. The
        // wording has to cover both, or it sends the reader looking for a
        // typo that is not there.
        "$name was not found. Either the repository does not exist or this token cannot see it.$suffix"
      409, 422 ->
        "$name changed on GitHub since it was read. Nothing was overwritten — " +
          "read the journal again so the two can be merged.$suffix"
      429 -> "GitHub is asking for fewer requests. The journal will be saved on the next try.$suffix"
      in 500..599 ->
        "GitHub is not answering right now (server error ${response.status}). Nothing was lost; try again later.$suffix"
      else -> "GitHub refused the request (status ${response.status}).$suffix"
    }
  }

  private fun githubMessage(response: BinaryHttpResponse): String? =
    try {
      Json.parse(String(response.body, Charsets.UTF_8)).asObject()?.get("message").asString()
        ?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }?.take(200)
    } catch (e: JsonParseException) {
      null
    }
}

private const val GITHUB_API_VERSION = "2022-11-28"

/** Named so GitHub can tell this fork apart if they ever look at their side. */
private const val GITHUB_USER_AGENT = "GanttProject-Fork"

/**
 * Percent-encodes one path segment.
 *
 * Only what would otherwise change the request's meaning, and `/` is not in
 * the set because the caller splits on it first. A whole-URL encoder would
 * also escape the separators and turn the path into one long segment.
 */
internal fun encodePathSegment(segment: String): String =
  segment.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
    val ch = byte.toInt().toChar()
    if (ch.isLetterOrDigit() && byte.toInt() in 0..127 || ch in "-._~") ch.toString()
    else "%%%02X".format(byte.toInt() and 0xFF)
  }

internal fun encodeQueryValue(value: String): String = encodePathSegment(value)

/**
 * A JSON string literal.
 *
 * [Json] only reads; this is the one thing that has to be written, and it is
 * small enough that a library for it would be a dependency bought for four
 * lines. Control characters go out as `\uXXXX` — a commit message arrives
 * from a change list that may carry a tab or a newline, and an unescaped one
 * would make the request body unparseable at the far end.
 */
internal fun jsonString(value: String): String {
  val out = StringBuilder(value.length + 2)
  out.append('"')
  for (ch in value) {
    when {
      ch == '"' -> out.append("\\\"")
      ch == '\\' -> out.append("\\\\")
      ch == '\n' -> out.append("\\n")
      ch == '\r' -> out.append("\\r")
      ch == '\t' -> out.append("\\t")
      ch < ' ' -> out.append("\\u%04x".format(ch.code))
      else -> out.append(ch)
    }
  }
  out.append('"')
  return out.toString()
}
