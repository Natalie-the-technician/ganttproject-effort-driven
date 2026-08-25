/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Base64

/**
 * One time entry from Toggl Track.
 *
 * Toggl encodes a *running* entry as a negative duration (the negated start
 * timestamp). Such entries are dropped during parsing: a running entry has no
 * settled duration yet, and importing one would book hours that are still
 * being counted.
 */
data class TogglTimeEntry(
  val id: Long,
  val description: String,
  val start: LocalDate,
  val durationSeconds: Long,
  val projectId: Long?,
  val projectName: String?,
  val tags: List<String>,
  /**
   * The full timestamp the entry began at, offset and all.
   *
   * [start] narrows this to a date, which is all the import matching needs.
   * A record of work needs more: the same instant falls on a different day
   * depending on the zone it is read in, and a month boundary is exactly
   * where a reporting period ends. Null only for a payload that carried a
   * bare date with no time in it.
   */
  val startedAt: OffsetDateTime? = null
) {
  val hours: Double get() = durationSeconds / 3600.0
}

/**
 * Result of a fetch. Deliberately a return value rather than an exception, so
 * that every failure mode has to be handled at the call site instead of
 * escaping as a crash on a phone with flaky reception.
 */
sealed interface TogglResult<out T> {
  data class Success<T>(val value: T) : TogglResult<T>
  data class Failure(val error: TogglError) : TogglResult<Nothing>
}

/**
 * Why a fetch failed.
 *
 * These are *types*, not messages: the UI layer maps them to localised
 * strings (`values/strings.xml`, `values-de/strings.xml`). Keeping
 * user-facing wording out of this module is what lets the core stay free of
 * Android and free of any single language.
 */
sealed interface TogglError {
  /** Token missing, malformed or rejected (HTTP 401). */
  data object InvalidToken : TogglError

  /** Authenticated but not allowed (HTTP 402/403), e.g. plan restriction. */
  data object Forbidden : TogglError

  /** Too many requests (HTTP 429). See [TogglApi.MIN_REQUEST_INTERVAL]. */
  data object RateLimited : TogglError

  /** Toggl reported a server-side failure (HTTP 5xx). */
  data class ServerError(val status: Int) : TogglError

  /** No connection, DNS failure or timeout — the request never completed. */
  data class Network(val detail: String) : TogglError

  /** A response arrived but could not be read as the expected shape. */
  data class Malformed(val detail: String) : TogglError

  /** Any other non-2xx status. */
  data class Unexpected(val status: Int, val body: String) : TogglError

  /** The requested date range is not usable. */
  data object InvalidDateRange : TogglError
}

data class HttpResponse(val status: Int, val body: String)

/**
 * The HTTP seam.
 *
 * **Contract:** a non-2xx status must NOT throw — return the status and body
 * and let [TogglClient] classify it. Only genuine transport failures (no
 * network, timeout, DNS) may surface as an exception.
 *
 * This split is the whole reason the client is testable without a network:
 * tests supply a backend that returns canned responses, including error
 * statuses, and assert on the resulting [TogglError].
 */
interface HttpBackend {
  fun get(url: String, headers: Map<String, String>): HttpResponse
}

object TogglApi {
  const val BASE_URL = "https://api.track.toggl.com/api/v9"

  /**
   * Minimum spacing between requests. Toggl allows roughly one request per
   * second per token and answers with HTTP 429 beyond that.
   */
  val MIN_REQUEST_INTERVAL: Duration = Duration.ofSeconds(1)
}

/**
 * Reads time entries from Toggl Track (API v9).
 *
 * Read-only by design: nothing is ever written back to Toggl. The time
 * tracker stays the source of truth for time, the project file for planning.
 */
class TogglClient(
  private val backend: HttpBackend,
  private val apiToken: String
) {
  private fun authHeaders(): Map<String, String> {
    // Toggl uses HTTP Basic with the literal string "api_token" as password.
    val credentials = Base64.getEncoder()
      .encodeToString("$apiToken:api_token".toByteArray(Charsets.UTF_8))
    return mapOf(
      "Authorization" to "Basic $credentials",
      "Accept" to "application/json",
      "User-Agent" to "GanttProject-Mobile"
    )
  }

  /**
   * Fetches the authenticated user's time entries between [from] and [to],
   * both inclusive.
   *
   * @param projectNames optional id -> name map from [fetchProjectNames], used
   *   to enrich entries whose description alone is ambiguous.
   */
  fun fetchTimeEntries(
    from: LocalDate,
    to: LocalDate,
    projectNames: Map<Long, String> = emptyMap()
  ): TogglResult<List<TogglTimeEntry>> {
    if (apiToken.isBlank()) return TogglResult.Failure(TogglError.InvalidToken)
    if (to.isBefore(from)) return TogglResult.Failure(TogglError.InvalidDateRange)
    // Toggl treats end_date as exclusive, so shift by a day to make the
    // caller's inclusive range come out right.
    val url = "${TogglApi.BASE_URL}/me/time_entries" +
      "?start_date=$from&end_date=${to.plusDays(1)}"
    return request(url).flatMap { body -> parseTimeEntries(body, projectNames) }
  }

  /** Project id -> name, so an entry is not judged on its description alone. */
  fun fetchProjectNames(): TogglResult<Map<Long, String>> {
    if (apiToken.isBlank()) return TogglResult.Failure(TogglError.InvalidToken)
    return request("${TogglApi.BASE_URL}/me/projects").flatMap { body ->
      try {
        val items = Json.parse(body).asArray()
          ?: return@flatMap TogglResult.Failure(TogglError.Malformed("expected a JSON array"))
        val map = items.mapNotNull { item ->
          val obj = item.asObject() ?: return@mapNotNull null
          val id = obj["id"].asLong() ?: return@mapNotNull null
          val name = obj["name"].asString() ?: return@mapNotNull null
          id to name
        }.toMap()
        TogglResult.Success(map)
      } catch (e: JsonParseException) {
        TogglResult.Failure(TogglError.Malformed(e.message ?: "unparseable JSON"))
      }
    }
  }

  /** Checks only whether the token is accepted; returns the account name. */
  fun verifyToken(): TogglResult<String> =
    request("${TogglApi.BASE_URL}/me").flatMap { body ->
      try {
        val obj = Json.parse(body).asObject()
        TogglResult.Success(
          obj?.get("fullname").asString() ?: obj?.get("email").asString() ?: ""
        )
      } catch (e: JsonParseException) {
        TogglResult.Failure(TogglError.Malformed(e.message ?: "unparseable JSON"))
      }
    }

  private fun request(url: String): TogglResult<String> {
    val response = try {
      backend.get(url, authHeaders())
    } catch (e: Exception) {
      return TogglResult.Failure(TogglError.Network(e.message ?: e.javaClass.simpleName))
    }
    return when {
      response.status in 200..299 -> TogglResult.Success(response.body)
      response.status == 401 -> TogglResult.Failure(TogglError.InvalidToken)
      response.status == 402 || response.status == 403 ->
        TogglResult.Failure(TogglError.Forbidden)
      response.status == 429 -> TogglResult.Failure(TogglError.RateLimited)
      response.status in 500..599 -> TogglResult.Failure(TogglError.ServerError(response.status))
      // Truncated: the body of an unexpected response can be an entire HTML
      // error page, which has no business being held in memory or logged.
      else -> TogglResult.Failure(TogglError.Unexpected(response.status, response.body.take(200)))
    }
  }

  /** Visible for testing: parses a time-entry payload without any network. */
  internal fun parseTimeEntries(
    body: String,
    projectNames: Map<Long, String>
  ): TogglResult<List<TogglTimeEntry>> {
    val parsed = try {
      Json.parse(body)
    } catch (e: JsonParseException) {
      return TogglResult.Failure(TogglError.Malformed(e.message ?: "unparseable JSON"))
    }
    val items = parsed.asArray()
      ?: return TogglResult.Failure(TogglError.Malformed("expected a JSON array"))
    val entries = items.mapNotNull { item ->
      // Skip entries that are unusable rather than failing the whole batch:
      // one odd record should not block an otherwise good import.
      val obj = item.asObject() ?: return@mapNotNull null
      val id = obj["id"].asLong() ?: return@mapNotNull null
      val duration = obj["duration"].asLong() ?: return@mapNotNull null
      if (duration <= 0L) return@mapNotNull null // still running
      val startText = obj["start"].asString() ?: return@mapNotNull null
      val start = parseTogglDate(startText) ?: return@mapNotNull null
      val projectId = obj["project_id"].asLong()
      TogglTimeEntry(
        id = id,
        description = obj["description"].asString().orEmpty().trim(),
        start = start,
        durationSeconds = duration,
        projectId = projectId,
        projectName = projectId?.let { projectNames[it] },
        tags = obj["tags"].asArray()?.mapNotNull { it.asString() } ?: emptyList(),
        startedAt = parseTogglInstant(startText)
      )
    }
    return TogglResult.Success(entries)
  }
}

/**
 * The full timestamp, or null when the text carried only a date.
 *
 * Deliberately no fallback: a date without a time is not a timestamp, and
 * inventing midnight here would hide that from every caller. [parseTogglDate]
 * is the one that may fall back, because a date is all it promises.
 */
internal fun parseTogglInstant(text: String): OffsetDateTime? =
  try {
    OffsetDateTime.parse(text)
  } catch (e: DateTimeParseException) {
    null
  }

/** Accepts a full ISO timestamp, falling back to a bare date. */
internal fun parseTogglDate(text: String): LocalDate? =
  try {
    OffsetDateTime.parse(text).toLocalDate()
  } catch (e: DateTimeParseException) {
    try {
      LocalDate.parse(text.take(10))
    } catch (e2: DateTimeParseException) {
      null
    }
  }

private inline fun <T, R> TogglResult<T>.flatMap(
  transform: (T) -> TogglResult<R>
): TogglResult<R> = when (this) {
  is TogglResult.Success -> transform(value)
  is TogglResult.Failure -> this
}
