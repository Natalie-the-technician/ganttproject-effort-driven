/*
Copyright 2026 Noctuvo

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

import net.sourceforge.ganttproject.resource.HumanResource
import java.time.LocalDate

/**
 * A read-only trial run against Toggl: fetch a few days of entries and report what came back.
 *
 * It exists because everything below it has only ever spoken to recorded answers.
 * [HttpClientBackend] has never talked to the real service, and two assumptions are still
 * unproven: that the token belongs in the USERNAME field (with the literal `api_token` as the
 * password), and that the timeouts are generous enough. Finding that out while writing into
 * Natalie's tasks would be the wrong moment.
 *
 * **Nothing is written.** No task, no property, no ledger. The result is a message.
 */
sealed interface ConnectionCheckResult {
  /** The service answered. [entryCount] is what would be offered for import. */
  data class Ok(val person: String, val entryCount: Int, val unreadable: List<String>) :
    ConnectionCheckResult

  /** No token stored for anybody, so there is nothing to try. */
  object NoToken : ConnectionCheckResult

  /** The service refused or could not be reached. */
  data class Failed(val person: String, val failure: TogglFailure, val message: String) :
    ConnectionCheckResult
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
