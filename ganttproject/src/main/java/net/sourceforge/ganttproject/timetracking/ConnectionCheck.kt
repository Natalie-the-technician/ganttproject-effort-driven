/*
Copyright 2026

NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.

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

import net.sourceforge.ganttproject.fork.forkText
import net.sourceforge.ganttproject.resource.HumanResource
import java.time.LocalDate

/**
 * A read-only trial run against Toggl: fetch a few days of entries and report what came back.
 *
 * It exists because everything below it has only ever spoken to recorded answers.
 * [HttpClientBackend] has never talked to the real service, and two assumptions are still
 * unproven: that the token belongs in the USERNAME field (with the literal `api_token` as the
 * password), and that the timeouts are generous enough. Finding that out while writing into
 * real tasks would be the wrong moment.
 *
 * **Nothing is written.** No task, no property, no ledger. The result is a message.
 */
sealed interface ConnectionCheckResult {
  /** The service answered. [entryCount] is what would be offered for import. */
  data class Ok(val person: String, val entryCount: Int, val unreadable: List<String>) :
    ConnectionCheckResult

  /** No token stored for anybody, so there is nothing to try. */
  object NoToken : ConnectionCheckResult

  /**
   * The service refused or could not be reached.
   *
   * [message] is the ENGLISH developer text from [TogglException]. It belongs in the log, not on
   * screen — see [connectionCheckMessage], which builds what the user reads from [failure].
   */
  data class Failed(val person: String, val failure: TogglFailure, val message: String) :
    ConnectionCheckResult
}

/**
 * [Fork-Aenderung] What the user gets to read.
 *
 * Built from [TogglFailure], deliberately NOT from `Failed.message`: that one is English prose
 * meant for the log, and showing it would put "Toggl refused the token…" into a German dialog.
 * Keeping this a plain function — no dialog, no thread — is what makes the promise testable.
 */
fun connectionCheckMessage(result: ConnectionCheckResult): String = when (result) {
  is ConnectionCheckResult.NoToken -> forkText("fork.toggl.check.noToken")

  is ConnectionCheckResult.Ok ->
    forkText("fork.toggl.check.ok", result.person, result.entryCount) +
      // Only mention unreadable entries when there are any. On a first run against the real
      // service this is the interesting part, and staying silent about it would hide exactly the
      // surprise the check exists to find.
      if (result.unreadable.isEmpty()) ""
      else "\n\n" + forkText("fork.toggl.check.unreadable", result.unreadable.size) +
        result.unreadable.joinToString("") { "\n• $it" }

  is ConnectionCheckResult.Failed -> forkText(
    when (result.failure) {
      TogglFailure.BAD_REQUEST -> "fork.toggl.check.badRequest"
      TogglFailure.NOT_AUTHORISED -> "fork.toggl.check.notAuthorised"
      TogglFailure.RATE_LIMITED -> "fork.toggl.check.rateLimited"
      TogglFailure.QUOTA_EXHAUSTED -> "fork.toggl.check.quotaExhausted"
      TogglFailure.UNAVAILABLE -> "fork.toggl.check.unavailable"
    },
    result.person)
}

/**
 * The first person carrying a token, or null.
 *
 * The check is deliberately about ONE person: it answers "does the connection work at all",
 * and asking every resource would multiply the requests without adding an answer — Toggl allows
 * one request per second (see [TogglApi.MIN_REQUEST_INTERVAL]).
 */
fun firstResourceWithToken(
  resources: List<HumanResource>,
  storedTokens: String?
): HumanResource? = resources.firstOrNull { !tokenFor(it, storedTokens).isNullOrBlank() }

/**
 * Runs the trial. [today] is passed in rather than read from the clock so the test can pin the
 * dates it expects.
 */
fun checkTogglConnection(
  client: TogglClient,
  resources: List<HumanResource>,
  storedTokens: String?,
  today: LocalDate,
  days: Long = 7
): ConnectionCheckResult {
  val person = firstResourceWithToken(resources, storedTokens) ?: return ConnectionCheckResult.NoToken
  val token = tokenFor(person, storedTokens) ?: return ConnectionCheckResult.NoToken
  val name = person.name ?: ""

  return try {
    val body = client.timeEntriesWithProblems(
      token, today.minusDays(days).toString(), today.plusDays(1).toString())
    ConnectionCheckResult.Ok(name, body.first.size, body.second)
  } catch (e: TogglException) {
    // The message carries the hint about username vs password for a refused token, which is the
    // mistake to expect on a first run.
    ConnectionCheckResult.Failed(name, e.failure, e.message ?: "")
  }
}
