/*
Copyright 2026 Noctuvo

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

import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64

/**
 * Reads time entries from Toggl Track.
 *
 * WHY AN API AND NOT AN EXPORT: the free Toggl plan exports PDF only (CSV needs a paid plan),
 * and the PDF summary carries no date per entry — unusable as a data source. The API is
 * available on the free plan. See NOTIZ-Zeiterfassung-Import.md.
 *
 * AUTHENTICATION, the usual trap: HTTP Basic with the API token as the USERNAME and the literal
 * string `api_token` as the PASSWORD. Putting the token in the password field yields 403.
 *
 * TESTABILITY: all network access goes through [HttpBackend]. Tests supply a recorded response;
 * a test that needs the network is not a test.
 */
object TogglApi {
  const val BASE_URL = "https://api.track.toggl.com/api/v9"
  const val PASSWORD_LITERAL = "api_token"

  /** Toggl asks for at most one request per second. */
  val MIN_REQUEST_INTERVAL: Duration = Duration.ofSeconds(1)
}

/** One recorded time entry, reduced to what the import needs. */
data class TogglTimeEntry(
  val id: Long,
  val start: OffsetDateTime,
  val durationSeconds: Long,
  val description: String,
  val projectId: Long?,
  val projectName: String?,
  val tags: List<String>
) {
  val hours: Double get() = durationSeconds / 3600.0

  /** A running entry has a negative duration in Toggl and must not be imported. */
  val isRunning: Boolean get() = durationSeconds < 0
}

/** What went wrong, in terms the user can act on. */
enum class TogglFailure {
  /** 403 — wrong token, or token supplied as the password instead of the username. */
  NOT_AUTHORISED,
  /** 429 — asked too often. Wait and retry. */
  RATE_LIMITED,
  /** 402 — request quota exhausted for the billing period. */
  QUOTA_EXHAUSTED,
  /** Anything else: network down, server error, unreadable answer. */
  UNAVAILABLE
}

class TogglException(val failure: TogglFailure, message: String) : Exception(message)

/** Minimal HTTP seam so the client can be tested without a network. */
interface HttpBackend {
  /** @return status code and body. Must not throw on non-2xx. */
  fun get(url: String, authorizationHeader: String): Pair<Int, String>
}

class TogglClient(private val backend: HttpBackend) {

  /**
   * Fetches entries in [from, to). Dates are ISO-8601 (`2026-08-01`).
   *
   * Entries that are still running are dropped: they have no end and would be imported with a
   * negative duration.
   */
  fun timeEntries(token: String, from: String, to: String): List<TogglTimeEntry> {
    require(token.isNotBlank()) { "token must not be blank" }
    val url = "${TogglApi.BASE_URL}/me/time_entries?start_date=$from&end_date=$to"
    val (status, body) = backend.get(url, authorizationHeader(token))
    when (status) {
      200 -> Unit
      402 -> throw TogglException(TogglFailure.QUOTA_EXHAUSTED,
        "Toggl request quota exhausted for this billing period.")
      403 -> throw TogglException(TogglFailure.NOT_AUTHORISED,
        "Toggl refused the token. Note that the token goes into the USERNAME field and the " +
          "literal string '${TogglApi.PASSWORD_LITERAL}' into the password field.")
      429 -> throw TogglException(TogglFailure.RATE_LIMITED,
        "Toggl rate limit reached. At most one request per second.")
      else -> throw TogglException(TogglFailure.UNAVAILABLE, "Toggl answered with status $status.")
    }
    return parseTimeEntries(body).filterNot { it.isRunning }
  }

  /**
   * HTTP Basic: token as username, the literal `api_token` as password.
   * Kept public so a test can prove the order, which is the part people get wrong.
   */
  fun authorizationHeader(token: String): String =
    "Basic " + Base64.getEncoder().encodeToString(
      "$token:${TogglApi.PASSWORD_LITERAL}".toByteArray(Charsets.UTF_8))
}

/**
 * Reads the entry list from a Toggl answer.
 *
 * Deliberately walks the JSON tree instead of mapping onto a fixed class: Toggl adds fields over
 * time, and an unknown field must not break the import. Entries that cannot be read are skipped
 * rather than aborting the whole run — but see [parseTimeEntriesWithProblems] when the caller
 * wants to know about them.
 */
fun parseTimeEntries(body: String): List<TogglTimeEntry> = parseTimeEntriesWithProblems(body).first

/** @return the entries that could be read, and a description of every entry that could not. */
fun parseTimeEntriesWithProblems(body: String): Pair<List<TogglTimeEntry>, List<String>> {
  val root = try {
    kotlinx.serialization.json.Json.parseToJsonElement(body)
  } catch (e: Exception) {
    throw TogglException(TogglFailure.UNAVAILABLE, "Toggl answer is not valid JSON: ${e.message}")
  }
  val array = root as? kotlinx.serialization.json.JsonArray
    ?: throw TogglException(TogglFailure.UNAVAILABLE,
      "Toggl answer is not a list of time entries.")

  val entries = mutableListOf<TogglTimeEntry>()
  val problems = mutableListOf<String>()
  array.forEachIndexed { index, element ->
    val obj = element as? kotlinx.serialization.json.JsonObject
    if (obj == null) {
      problems.add("entry #$index is not an object")
      return@forEachIndexed
    }
    fun text(key: String): String? =
      (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { !it.isString || true }
        ?.contentOrNull
    try {
      val id = text("id")?.toLongOrNull()
        ?: throw IllegalArgumentException("missing id")
      val start = text("start")?.let { OffsetDateTime.parse(it) }
        ?: throw IllegalArgumentException("missing start")
      val duration = text("duration")?.toLongOrNull()
        ?: throw IllegalArgumentException("missing duration")
      val tags = (obj["tags"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
        ?: emptyList()
      entries.add(TogglTimeEntry(
        id = id,
        start = start,
        durationSeconds = duration,
        description = text("description").orEmpty(),
        projectId = text("project_id")?.toLongOrNull(),
        projectName = text("project_name"),
        tags = tags))
    } catch (e: Exception) {
      problems.add("entry #$index skipped: ${e.message}")
    }
  }
  return entries to problems
}

private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
  get() = if (this is kotlinx.serialization.json.JsonNull) null else this.content
