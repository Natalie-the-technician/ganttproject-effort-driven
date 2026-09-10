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

import java.util.Base64

/**
 * [fork change] Where the token pair is kept, as far as the sign-in is concerned.
 *
 * Two methods and one opaque string, on purpose. The phone half puts this on its `SecureStore`
 * (AES-GCM, key in the Android keystore, excluded from cloud backup); here it goes through
 * [OptionGitHubTokenStorage] onto whatever key store this machine has — and says so out loud when
 * it has none. Neither half has to know about the other's.
 *
 * Writing null erases. That is the whole of "disconnect".
 */
interface GitHubTokenStorage {
  fun readTokens(): String?
  fun writeTokens(value: String?)
}

/**
 * The version marker of the stored line.
 *
 * It earns its keep the day the shape changes: a `gh2:` line read by an older build is refused
 * rather than misread, and being refused means "connect again", which works.
 */
private const val TOKEN_LINE_VERSION = "gh1"

/**
 * [fork change] The stored form of a token pair: `gh1:<access>:<expiry>:<refresh>:<expiry>`, both
 * tokens Base64url-encoded and both expiry times as plain seconds.
 *
 * WHY BASE64 AND NOT THE TOKENS AS THEY STAND. A token is an opaque string from a service. Nothing
 * promises it holds no colon, and one colon would cut this line into six fields; the connection
 * would then be silently gone with no error anywhere to explain it. Base64url has no colon in its
 * alphabet, so the separator cannot occur inside a field.
 *
 * IT IS NOT ENCRYPTION and must not be read as any. The protection is whatever
 * [GitHubTokenStorage] does with the string — see [OptionGitHubTokenStorage], which hands it to
 * the platform key store and warns where there is none.
 */
fun GitHubTokenSet.encoded(): String = listOf(
  TOKEN_LINE_VERSION,
  base64(accessToken),
  accessExpiresAt.toString(),
  base64(refreshToken),
  refreshExpiresAt.toString(),
).joinToString(":")

/**
 * @return the pair, or null for anything that cannot be read as one.
 *
 * Null and not an exception: an unreadable line is the same situation as an empty one — there is no
 * usable connection — and it has exactly one sensible answer, which is to sign in again. Throwing
 * would take the program down over a settings file somebody edited by hand.
 */
fun decodeGitHubTokens(text: String?): GitHubTokenSet? {
  if (text.isNullOrEmpty()) return null
  val parts = text.split(':')
  if (parts.size != 5 || parts[0] != TOKEN_LINE_VERSION) return null
  return try {
    GitHubTokenSet(
      accessToken = unbase64(parts[1]),
      accessExpiresAt = parts[2].toLong(),
      refreshToken = unbase64(parts[3]),
      refreshExpiresAt = parts[4].toLong(),
    ).takeIf { it.accessToken.isNotEmpty() && it.refreshToken.isNotEmpty() }
  } catch (e: IllegalArgumentException) {
    null
  }
}

private fun base64(value: String): String =
  Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

private fun unbase64(value: String): String =
  String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)

/** [fork change] What came of asking for a token to put on a request. */
sealed interface AccessTokenOutcome {
  data class Ready(val accessToken: String) : AccessTokenOutcome

  /** Nothing is stored. Nobody has ever connected, or somebody disconnected. */
  data class NotConnected(val reason: String) : AccessTokenOutcome

  /**
   * There was a connection and it is over. The way on is the device flow, and saying so is the
   * difference between a window that offers a "connect" button and one that shows an error nobody
   * can act on.
   */
  data class ConnectAgain(val reason: String) : AccessTokenOutcome

  /** Something went wrong that may well work in a minute. Do not disconnect. */
  data class Failed(val reason: String) : AccessTokenOutcome
}

/**
 * How much of an access token's life is treated as already spent.
 *
 * CHOSEN, NOT MEASURED (Claude). A save is two requests against the API and each may sit at the
 * thirty-second response timeout of [JdkHttpExchange], so a token handed out with twenty seconds
 * left would fail in the middle of one — after the read, before the write. Five minutes is
 * comfortably more than any single save can take, and against an eight-hour life it costs no extra
 * renewal worth counting.
 */
private const val REFRESH_SKEW_SECONDS = 300L

/**
 * [fork change] The one thing the interface talks to: connect, stay connected, disconnect.
 *
 * It owns the store, so nothing above it has to remember to write a renewed token down — which is
 * the failure that would cost a person eight hours of patience with nobody seeing it, because
 * everything keeps working, just with a browser visit every morning.
 *
 * ## The rule about renewing
 *
 * ```
 * access token still good                 -> use it, no request
 * access token stale, refresh token good  -> renew, WRITE IT DOWN, use it
 * refresh token gone by our clock         -> ConnectAgain, and NO request
 * refresh token refused by GitHub         -> ConnectAgain, forget it, no second try
 * ```
 *
 * The last two lines are the same rule twice: ONCE IT CANNOT WORK, STOP TRYING. A store that keeps
 * offering a dead refresh token turns every save into a failed request for ever, and the person
 * sees a program that is slow for no reason they can name.
 */
class GitHubConnection(
  private val http: HttpExchange,
  private val storage: GitHubTokenStorage,
  private val clock: SecondsClock = SecondsClock { System.currentTimeMillis() / 1000 },
  private val waiter: PollWaiter = PollWaiter { Thread.sleep(it * 1000L) },
  private val clientId: String = GITHUB_CLIENT_ID,
  private val loginBase: String = GITHUB_LOGIN_BASE,
) {

  private val flow = GitHubDeviceFlow(http, clock, waiter, clientId, loginBase)

  /** Whether a pair is on file at all. Says nothing about whether it still works. */
  fun isConnected(): Boolean = decodeGitHubTokens(storage.readTokens()) != null

  /** Step one of connecting: the code to show. */
  fun startConnecting(): DeviceCodeOutcome = flow.requestCode()

  /**
   * Step two: wait for the person, and WRITE DOWN what comes back.
   *
   * The writing is here rather than at the call site because a call site that forgot it would leave
   * a program that connects perfectly and has forgotten by the next start. That is a bug nobody
   * reports; they just stop using it.
   */
  fun finishConnecting(
    prompt: DeviceCodePrompt,
    keepWaiting: () -> Boolean = { true },
  ): DeviceFlowOutcome {
    val outcome = flow.awaitToken(prompt, keepWaiting)
    if (outcome is DeviceFlowOutcome.Connected) {
      storage.writeTokens(outcome.tokens.encoded())
    }
    return outcome
  }

  /** Forgets the pair. The tokens stay valid at GitHub until they expire. */
  fun forget() {
    storage.writeTokens(null)
  }

  /**
   * A token that may be put on a request right now, renewing first if need be.
   *
   * Costs nothing while the current one holds — no request, not even a check with GitHub, because
   * the expiry is already known.
   */
  fun accessToken(): AccessTokenOutcome {
    val stored = decodeGitHubTokens(storage.readTokens())
      ?: return AccessTokenOutcome.NotConnected("This program is not connected to GitHub yet.")

    val now = clock.nowEpochSeconds()
    if (now + REFRESH_SKEW_SECONDS < stored.accessExpiresAt) {
      return AccessTokenOutcome.Ready(stored.accessToken)
    }

    if (now >= stored.refreshExpiresAt) {
      // NOT ONE REQUEST. We know it is dead; offering it would be a request per save that can never
      // succeed, for the rest of the installation's life. The pair is left on file so the message
      // can stay this specific.
      GitHubLog.sink("[fork] the GitHub connection is older than its refresh token; a new sign-in is needed")
      return AccessTokenOutcome.ConnectAgain(
        "The connection to GitHub has run out — a sign-in lasts six months. Connect again."
      )
    }

    return renew(stored, now)
  }

  private fun renew(stored: GitHubTokenSet, now: Long): AccessTokenOutcome {
    val answer = try {
      http.exchange(
        ExchangeRequest(
          "POST",
          "$loginBase/login/oauth/access_token",
          mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/x-www-form-urlencoded",
            "User-Agent" to "GanttProject-Fork",
          ),
          formBody(
            mapOf(
              "client_id" to clientId,
              "grant_type" to "refresh_token",
              "refresh_token" to stored.refreshToken,
            )
          ).toByteArray(Charsets.UTF_8),
        )
      )
    } catch (e: Exception) {
      // The network is down, not the connection. Keeping the pair is the whole point: a laptop on a
      // train must not be signed out.
      return AccessTokenOutcome.Failed(networkReason(e))
    }

    val fields = jsonFields(answer) ?: return AccessTokenOutcome.Failed(unreadableReason(answer))

    textOrNull(fields, "error")?.let { error ->
      if (error == "bad_refresh_token" || error == "bad_verification_code") {
        // GitHub has the last word, and it has said this token is no good — revoked, already spent,
        // or from an app that was reinstalled. Keeping it would mean offering it again at the next
        // save and at every save after that. Forgetting it is what makes the next call cost
        // nothing.
        forget()
        GitHubLog.sink("[fork] GitHub refused the stored refresh token; it has been discarded")
        return AccessTokenOutcome.ConnectAgain("GitHub no longer accepts this connection. Connect again.")
      }
      return AccessTokenOutcome.Failed(githubErrorSentence(error, fields))
    }

    val renewed = tokensFrom(fields, now)
      ?: return AccessTokenOutcome.Failed("GitHub's answer to the renewal carried no usable token.")

    // WRITE IT DOWN. GitHub spends the old refresh token on every renewal and hands out a new one; a
    // renewal that is not stored means the next start offers a token that has already been used.
    storage.writeTokens(renewed.encoded())
    GitHubLog.sink(
      "[fork] the GitHub access token was renewed without anybody typing; " +
        "good for another ${renewed.accessExpiresAt - now} s"
    )
    return AccessTokenOutcome.Ready(renewed.accessToken)
  }
}
