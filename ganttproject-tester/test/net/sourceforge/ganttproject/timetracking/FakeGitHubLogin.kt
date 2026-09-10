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

/**
 * [fork change] A stand-in for the two endpoints the sign-in uses. Both live on `github.com` and
 * not on the API host, which is why this is a second fake beside [FakeGitHub] rather than two more
 * branches inside it: pointing either at the other host is a mistake worth catching.
 *
 * A small service with state, not a canned answer. The device flow is a conversation — the same
 * endpoint is asked over and over and its answer changes with what the person at the browser has
 * done. A fixed reply could show that the client copes with one answer handed to it; it could not
 * show that the client waits the right length of time between two of them, and the waiting is the
 * part that gets a program throttled.
 *
 * The same shape as the phone side's fake of the same name. Not shared code — the two programs
 * share no module — but the same script, so a difference between the two implementations shows up
 * as a difference in the test results and not as a difference in what was tested.
 *
 * WHAT IS FAITHFUL HERE, and why each part had to be:
 *
 *  * ERRORS COME BACK WITH HTTP 200 and an `error` field. That is what the device flow does, and a
 *    client that only read the status would take `access_denied` for a success.
 *  * THE BODY IS FORM-ENCODED and the fields are checked. A client sending JSON would find its
 *    parameters missing, exactly as against the real one.
 *  * THE DEVICE CODE IS CHECKED. Polling with the wrong one is an error, not a pending state.
 *  * EVERY REQUEST IS RECORDED, so "it stopped asking" can be counted instead of asserted.
 *  * TOKENS COME IN SEQUENCE, so a test can name the string it expects to find in the store rather
 *    than compare against whatever the code produced.
 *  * A USED REFRESH TOKEN IS SPENT. GitHub hands out a new one at every renewal and the old one
 *    stops working; reproducing that turns "the new pair is stored" from an assertion into a
 *    consequence.
 */
class FakeGitHubLogin(
  private val clientId: String = GITHUB_CLIENT_ID,
  private val loginBase: String = GITHUB_LOGIN_BASE,
) : HttpExchange {

  /** One scripted answer of the token endpoint, consumed in order. */
  sealed interface Answer {
    /** Nobody has typed the code yet. */
    data object Pending : Answer

    /** Asked too often. The client must add five seconds, permanently. */
    data object SlowDown : Answer

    /** The person pressed "Cancel" on GitHub's page. */
    data object Denied : Answer

    /** The fifteen minutes are up. */
    data object CodeExpired : Answer

    /** The person typed the code and confirmed. */
    data object Granted : Answer
  }

  data class Recorded(val method: String, val url: String, val fields: Map<String, String>)

  val requests = mutableListOf<Recorded>()

  val requestCount: Int get() = requests.size

  fun countOf(path: String): Int = requests.count { it.url.endsWith(path) }

  /** What the token endpoint answers, in order. Empty means "still pending". */
  val script = ArrayDeque<Answer>()

  /**
   * Polls that arrived after the script ran out.
   *
   * The whole point of the `access_denied` case is that the client stops. A counter that only ever
   * rises when it should not is a cheaper way to say that than making the fake blow up mid-request.
   */
  var pollsAfterScript = 0
    private set

  var deviceFlowEnabled = true
  var userCode = "WDJB-MJHT"
  var deviceCode = "device-code-that-must-never-be-shown"
  var verificationUri = "https://github.com/login/device"
  var expiresInSeconds = 900L
  var intervalSeconds = 5L

  /** Refresh tokens GitHub has decided are no good, whatever our clock says. */
  val deadRefreshTokens = mutableSetOf<String>()

  /** Thrown instead of answering, for the no-network case. */
  var throwWith: Exception? = null

  private var issued = 0

  /** Every access token this fake ever handed out, oldest first. */
  val issuedAccessTokens = mutableListOf<String>()

  /** Every refresh token this fake ever handed out, oldest first. */
  val issuedRefreshTokens = mutableListOf<String>()

  override fun exchange(request: ExchangeRequest): ExchangeResponse {
    val fields = formFields(request.body?.toString(Charsets.UTF_8))
    requests.add(Recorded(request.method, request.url, fields))
    throwWith?.let { throw it }

    if (request.method != "POST") {
      return json(404, """{"message":"Not Found"}""")
    }
    return when (request.url) {
      "$loginBase/login/device/code" -> deviceCode(fields)
      "$loginBase/login/oauth/access_token" -> token(fields)
      else -> json(404, """{"message":"Not Found"}""")
    }
  }

  private fun deviceCode(fields: Map<String, String>): ExchangeResponse {
    if (fields["client_id"] != clientId) {
      return json(200, """{"error":"incorrect_client_credentials"}""")
    }
    if (!deviceFlowEnabled) {
      return json(
        200,
        """{"error":"device_flow_disabled","error_description":"Device Flow has not been enabled on this app. Enable it in the app settings."}"""
      )
    }
    return json(
      200,
      "{\"device_code\":" + quoted(deviceCode) +
        ",\"user_code\":" + quoted(userCode) +
        ",\"verification_uri\":" + quoted(verificationUri) +
        ",\"expires_in\":" + expiresInSeconds +
        ",\"interval\":" + intervalSeconds + "}"
    )
  }

  private fun token(fields: Map<String, String>): ExchangeResponse {
    if (fields["client_id"] != clientId) {
      return json(200, """{"error":"incorrect_client_credentials"}""")
    }
    return when (fields["grant_type"]) {
      // Spelled out here rather than read from a constant of the code under test: if the client
      // sent a different grant type, a shared constant would move with it and this check would
      // pass anyway.
      "urn:ietf:params:oauth:grant-type:device_code" -> deviceCodeGrant(fields)
      "refresh_token" -> refreshGrant(fields)
      else -> json(200, """{"error":"unsupported_grant_type"}""")
    }
  }

  private fun deviceCodeGrant(fields: Map<String, String>): ExchangeResponse {
    if (fields["device_code"] != deviceCode) {
      return json(200, """{"error":"incorrect_device_code"}""")
    }
    val answer = script.removeFirstOrNull()
    if (answer == null) {
      pollsAfterScript++
      return json(200, """{"error":"authorization_pending"}""")
    }
    return when (answer) {
      Answer.Pending -> json(200, """{"error":"authorization_pending"}""")
      Answer.SlowDown -> json(200, """{"error":"slow_down"}""")
      Answer.Denied -> json(
        200, """{"error":"access_denied","error_description":"The authorization request was denied."}"""
      )
      Answer.CodeExpired -> json(
        200, """{"error":"expired_token","error_description":"The device code has expired."}"""
      )
      Answer.Granted -> grant()
    }
  }

  private fun refreshGrant(fields: Map<String, String>): ExchangeResponse {
    val offered = fields["refresh_token"]
    if (offered == null || offered !in issuedRefreshTokens) {
      return json(200, """{"error":"bad_refresh_token"}""")
    }
    if (offered in deadRefreshTokens) {
      return json(
        200,
        """{"error":"bad_refresh_token","error_description":"The refresh token passed is incorrect or expired."}"""
      )
    }
    deadRefreshTokens.add(offered)
    return grant()
  }

  private fun grant(): ExchangeResponse {
    issued++
    val access = "ghu_access_$issued"
    val refresh = "ghr_refresh_$issued"
    issuedAccessTokens.add(access)
    issuedRefreshTokens.add(refresh)
    return json(
      200,
      "{\"access_token\":" + quoted(access) +
        ",\"expires_in\":" + ACCESS_TOKEN_LIFETIME_SECONDS +
        ",\"refresh_token\":" + quoted(refresh) +
        ",\"refresh_token_expires_in\":" + REFRESH_TOKEN_LIFETIME_SECONDS +
        ",\"token_type\":\"bearer\",\"scope\":\"\"}"
    )
  }

  private fun json(status: Int, body: String) = ExchangeResponse(
    status,
    mapOf("content-type" to "application/json; charset=utf-8"),
    body.toByteArray(Charsets.UTF_8)
  )

  private fun quoted(value: String): String = MAPPER.writeValueAsString(value)

  private fun formFields(body: String?): Map<String, String> {
    if (body.isNullOrEmpty()) return emptyMap()
    return body.split('&').mapNotNull { pair ->
      val name = pair.substringBefore('=', "")
      if (name.isEmpty()) null else formDecode(name) to formDecode(pair.substringAfter('=', ""))
    }.toMap()
  }

  private fun formDecode(text: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < text.length) {
      val ch = text[i]
      when {
        ch == '+' -> { out.append(' '); i++ }
        ch == '%' && i + 3 <= text.length -> {
          out.append(text.substring(i + 1, i + 3).toInt(16).toChar()); i += 3
        }
        else -> { out.append(ch); i++ }
      }
    }
    return out.toString()
  }

  companion object {
    private val MAPPER = ObjectMapper()

    /**
     * The lifetimes the fake hands out, written here as literals ON PURPOSE.
     *
     * A test that wants to say "the access token is good for eight hours from now" needs a number
     * that did not come out of the code under test. If this read a constant from the main source,
     * an implementation that multiplied it by two would move both sides of the comparison and the
     * check would stay green. These are GitHub's documented values, read on 10.09.2026, and they
     * are the reference the tests measure against.
     */
    const val ACCESS_TOKEN_LIFETIME_SECONDS = 28800L
    const val REFRESH_TOKEN_LIFETIME_SECONDS = 15897600L
  }
}
