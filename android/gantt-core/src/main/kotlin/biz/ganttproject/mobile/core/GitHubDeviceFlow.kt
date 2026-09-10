/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

/**
 * The application's own id at GitHub.
 *
 * IT IS NOT A SECRET, and it belongs in the source exactly because of that.
 * That is the whole reason the device flow is usable by a program one hands
 * out: there is no client secret to hide in an APK that anybody can unpack.
 * Putting this in [SecureStore] alongside the tokens would say the opposite of
 * what is true and would make it look as if an installed copy held something
 * worth taking.
 *
 * The app is installed on `Natalie-the-technician/noctuvo-fue-nachweis` with
 * `Contents: Read and write`, `Only on this account`. What the token may touch
 * is decided there, at install time, and not by anything sent from here — which
 * is why a GitHub App and not an OAuth application (see FF5).
 *
 * UNVERIFIED FROM HERE: whether this id is right and whether "Enable Device
 * Flow" is really ticked shows up only on the first real run. There is no
 * request against GitHub anywhere in this branch.
 */
const val GITHUB_CLIENT_ID = "Iv23liwWJJUAMEX59c3z"

/**
 * The sign-in host — `github.com`, NOT `api.github.com`.
 *
 * Both endpoints of the device flow live here; everything the journal store
 * does lives on [GITHUB_API_BASE]. Two constants because they are two hosts,
 * and pointing either at the other yields a 404 that reads like a missing
 * repository.
 */
const val GITHUB_LOGIN_BASE = "https://github.com"

/** The grant type of the device flow. The protocol wants it spelled out in full. */
private const val DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"

/**
 * What `slow_down` costs, from GitHub's documentation: five seconds, added to
 * the interval and **kept** there.
 */
private const val SLOW_DOWN_STEP_SECONDS = 5L

/**
 * Seconds since the epoch. A seam so a test can decide what "now" is.
 *
 * Needed rather than a call to the system clock because two of the things this
 * file decides are about time passing — when an access token is stale and when
 * a device code has run out — and neither can be measured in a test that has to
 * finish in a second.
 */
fun interface SecondsClock {
  fun nowEpochSeconds(): Long
}

/**
 * Waits. A seam so a test can count the pauses instead of taking them.
 *
 * The production one blocks the calling thread, which is correct **only**
 * because the flow is run on a thread of its own — on the phone's main thread
 * or on Swing's event thread it would freeze the interface for as long as the
 * person takes to type the code.
 */
fun interface PollWaiter {
  fun waitSeconds(seconds: Long)
}

/**
 * Where this file's notes go.
 *
 * A seam and not a direct call to a logger, for one reason: a test has to be
 * able to read the lines that really arrive. A test over a function that
 * *builds* a line proves nothing about the call site, and the call site is
 * exactly where somebody appends a token some day.
 *
 * The default is silence, so that `gantt-core` keeps its promise of having no
 * Android dependency at all. The app module installs the real one.
 */
object GitHubLog {
  var sink: (String) -> Unit = {}
}

/**
 * What the person is asked to do, and the receipt that proves it was us asking.
 *
 * NOT a data class, deliberately. A generated `toString` prints every property,
 * and this one carries [deviceCode] — the string that can be exchanged for a
 * token. One `GitHubLog.sink("waiting: $prompt")` would then put it in the log
 * without anybody having written it there. The `toString` below leaves it out,
 * and a test holds that in place.
 */
class DeviceCodePrompt(
  /** Eight characters for the person to read out or type. Meant to be seen. */
  val userCode: String,
  /** Where to type it. GitHub sends `https://github.com/login/device`. */
  val verificationUri: String,
  /** How long the code is good for. GitHub sends 900. */
  val expiresInSeconds: Long,
  /** The shortest pause GitHub will accept between two polls. */
  val intervalSeconds: Long,
  /** The secret half. Never shown, never logged. */
  val deviceCode: String
) {
  override fun toString(): String =
    "DeviceCodePrompt(userCode=$userCode, verificationUri=$verificationUri, " +
      "expiresInSeconds=$expiresInSeconds, intervalSeconds=$intervalSeconds)"
}

/** What came of asking for a code. */
sealed interface DeviceCodeOutcome {
  data class Ready(val prompt: DeviceCodePrompt) : DeviceCodeOutcome

  /**
   * A case of its own rather than one more [Failed], because it is the only
   * one nobody at the keyboard can do anything about: somebody has to open the
   * app's settings page at GitHub and tick "Enable Device Flow". A message
   * that named a generic error would send the reader looking at their network.
   */
  data class NotEnabled(val reason: String) : DeviceCodeOutcome

  data class Failed(val reason: String) : DeviceCodeOutcome
}

/** What came of waiting for the person to confirm. */
sealed interface DeviceFlowOutcome {
  data class Connected(val tokens: GitHubTokenSet) : DeviceFlowOutcome

  /**
   * The person pressed Cancel on GitHub's page.
   *
   * Not a failure and not something to retry: it is an answer, and the right
   * reply to it is to stop. Kept apart from [Failed] so the interface can say
   * "cancelled" rather than "error", which is a different thing to read.
   */
  data class Denied(val reason: String) : DeviceFlowOutcome

  /** The fifteen minutes are up. The way on is a new code, not another poll. */
  data class Expired(val reason: String) : DeviceFlowOutcome

  /** The caller stopped waiting — the dialogue was closed. */
  data class Stopped(val reason: String) : DeviceFlowOutcome

  data class Failed(val reason: String) : DeviceFlowOutcome
}

/**
 * The sign-in at GitHub: ask for a code, show it, wait for the person.
 *
 * ## Why the device flow and not the way GP Cloud signs in
 *
 * GP Cloud opens a browser and catches the answer on a tiny web server bound to
 * `localhost` (`GPCloudHttpImpl.kt:447`). That cannot be built on a phone, and
 * it needs a browser **on the same machine**. The device flow needs neither: it
 * shows eight characters and the person types them wherever they happen to have
 * a browser — including another device entirely. So one flow serves both sides
 * of this fork instead of two.
 *
 * ## The two rules that keep it civil
 *
 * 1. **The pause is GitHub's to set.** It sends `interval` with the code and
 *    every poll is preceded by that pause. Asking faster earns `slow_down`,
 *    which makes the wait longer, not shorter.
 * 2. **`slow_down` raises the pause for good.** Adding five seconds for one
 *    poll and dropping back is not obedience; it is the same rate with a stutter
 *    in it, and it earns another `slow_down`.
 *
 * ## Where scope went
 *
 * Nowhere. A GitHub App's permissions are fixed when it is installed on a
 * repository, so a `scope` parameter would be ignored — the token can write the
 * contents of `noctuvo-fue-nachweis` and nothing else because that is what the
 * installation says. Sending an empty one anyway would suggest this program has
 * a say in the matter, and it does not.
 *
 * Nothing here reaches a store. The caller decides what to do with what comes
 * back; [GitHubConnection] is the one that writes it down.
 */
class GitHubDeviceFlow(
  private val http: HttpExchange,
  private val clock: SecondsClock,
  private val waiter: PollWaiter,
  private val clientId: String = GITHUB_CLIENT_ID,
  private val loginBase: String = GITHUB_LOGIN_BASE
) {

  /**
   * Step one: ask GitHub for a code to show.
   *
   * One request. Nothing is stored and nothing is authorised yet — until the
   * person confirms in a browser, the code in hand is worth nothing.
   */
  fun requestCode(): DeviceCodeOutcome {
    val answer = try {
      postForm("$loginBase/login/device/code", mapOf("client_id" to clientId))
    } catch (e: Exception) {
      return DeviceCodeOutcome.Failed(networkReason(e))
    }
    val fields = jsonFields(answer)
      ?: return DeviceCodeOutcome.Failed(unreadableReason(answer))

    fields["error"].asString()?.let { error ->
      return when (error) {
        "device_flow_disabled" -> DeviceCodeOutcome.NotEnabled(
          "This build cannot sign in to GitHub: the app's \"Enable Device Flow\" setting is " +
            "switched off. Somebody with access to the GitHub App's settings page has to tick it."
        )
        "incorrect_client_credentials" -> DeviceCodeOutcome.Failed(
          "GitHub does not know this application's id. The client id in this build is wrong or " +
            "the app has been deleted."
        )
        else -> DeviceCodeOutcome.Failed(githubErrorSentence(error, fields))
      }
    }

    val deviceCode = fields["device_code"].asString()
    val userCode = fields["user_code"].asString()
    val uri = fields["verification_uri"].asString()
    if (deviceCode.isNullOrEmpty() || userCode.isNullOrEmpty() || uri.isNullOrEmpty()) {
      return DeviceCodeOutcome.Failed("GitHub's answer carried no code to show.")
    }
    val prompt = DeviceCodePrompt(
      userCode = userCode,
      verificationUri = uri,
      expiresInSeconds = fields["expires_in"].asLong() ?: DEFAULT_EXPIRES_IN_SECONDS,
      // At least one second. A missing or zero interval would otherwise be a
      // request as fast as the machine can send them, which is the one
      // behaviour this whole class exists to avoid.
      intervalSeconds = (fields["interval"].asLong() ?: DEFAULT_INTERVAL_SECONDS).coerceAtLeast(1),
      deviceCode = deviceCode
    )
    // The user code is on the screen anyway, so it may be in the log too, and
    // knowing which code a run was showing is worth having. The device code is
    // not here and must not be added.
    GitHubLog.sink(
      "[fork] GitHub sign-in started: code $userCode at ${prompt.verificationUri}, " +
        "good for ${prompt.expiresInSeconds} s, asking every ${prompt.intervalSeconds} s"
    )
    return DeviceCodeOutcome.Ready(prompt)
  }

  /**
   * Step two: ask over and over until the person has confirmed.
   *
   * Blocks for as long as that takes — up to the fifteen minutes the code is
   * good for. Run it on a thread of its own.
   *
   * @param keepWaiting asked before every poll. Returning false ends it with
   * [DeviceFlowOutcome.Stopped]; that is how a closed dialogue stops the
   * polling instead of leaving a thread asking into the void.
   */
  fun awaitToken(
    prompt: DeviceCodePrompt,
    keepWaiting: () -> Boolean = { true }
  ): DeviceFlowOutcome {
    var interval = prompt.intervalSeconds
    val deadline = clock.nowEpochSeconds() + prompt.expiresInSeconds

    while (true) {
      if (!keepWaiting()) {
        GitHubLog.sink("[fork] GitHub sign-in given up before the code was entered")
        return DeviceFlowOutcome.Stopped("The connection was cancelled here, not at GitHub.")
      }
      // Wait FIRST. The person has not even read the code yet at this point,
      // so an immediate poll is a request that cannot succeed — and it counts
      // towards the rate GitHub measures.
      waiter.waitSeconds(interval)

      // Our own deadline, checked before the request rather than after it.
      // GitHub answers `expired_token` too, and that answer is authoritative;
      // this one exists for the case where nobody answers at all — the browser
      // was shut, the person went home, and a poll every five seconds for ever
      // would be the result.
      if (clock.nowEpochSeconds() >= deadline) {
        GitHubLog.sink("[fork] GitHub sign-in: the code ran out before it was entered")
        return DeviceFlowOutcome.Expired(expiredSentence())
      }

      val answer = try {
        postForm(
          "$loginBase/login/oauth/access_token",
          mapOf(
            "client_id" to clientId,
            "device_code" to prompt.deviceCode,
            "grant_type" to DEVICE_CODE_GRANT
          )
        )
      } catch (e: Exception) {
        return DeviceFlowOutcome.Failed(networkReason(e))
      }
      val fields = jsonFields(answer)
        ?: return DeviceFlowOutcome.Failed(unreadableReason(answer))

      // The device flow reports its errors with HTTP 200 and an `error` field.
      // A client that only looked at the status would read `access_denied` as
      // a success and then wonder why there is no token in the answer.
      when (val error = fields["error"].asString()) {
        null -> return granted(fields)

        "authorization_pending" -> Unit

        "slow_down" -> {
          interval += SLOW_DOWN_STEP_SECONDS
          GitHubLog.sink("[fork] GitHub asked for a slower pace; asking every $interval s from now on")
        }

        "expired_token" -> {
          GitHubLog.sink("[fork] GitHub sign-in: the code had expired")
          return DeviceFlowOutcome.Expired(expiredSentence())
        }

        "access_denied" -> {
          // STOP. Not a pause, not a retry. The answer will never change, and
          // a client that keeps asking is asking a service for something a
          // person has just said no to.
          GitHubLog.sink("[fork] GitHub sign-in refused by the user")
          return DeviceFlowOutcome.Denied(
            "The connection was refused at GitHub. Nothing was changed; start again if that was a mistake."
          )
        }

        "device_flow_disabled" -> return DeviceFlowOutcome.Failed(
          "This build cannot sign in to GitHub: the app's \"Enable Device Flow\" setting is " +
            "switched off. Somebody with access to the GitHub App's settings page has to tick it."
        )

        "incorrect_device_code" -> return DeviceFlowOutcome.Failed(
          "GitHub did not recognise this sign-in. Start again to get a fresh code."
        )

        else -> return DeviceFlowOutcome.Failed(githubErrorSentence(error, fields))
      }
    }
  }

  private fun granted(fields: Map<String, JsonValue>): DeviceFlowOutcome {
    val tokens = tokensFrom(fields, clock.nowEpochSeconds())
      ?: return DeviceFlowOutcome.Failed("GitHub confirmed the sign-in but sent no usable token.")
    GitHubLog.sink(
      "[fork] connected to GitHub; the access token is good for " +
        "${tokens.accessExpiresAt - clock.nowEpochSeconds()} s"
    )
    return DeviceFlowOutcome.Connected(tokens)
  }

  private fun expiredSentence(): String =
    "The code has run out — it is only good for a quarter of an hour. Ask for a new one and type " +
      "that instead."

  private fun postForm(url: String, fields: Map<String, String>): BinaryHttpResponse =
    http.exchange(
      HttpRequest(
        "POST",
        url,
        mapOf(
          // Without this GitHub answers form-encoded, which is the documented
          // default and would need a second parser for no reason.
          "Accept" to "application/json",
          "Content-Type" to "application/x-www-form-urlencoded",
          "User-Agent" to GITHUB_SIGN_IN_USER_AGENT
        ),
        formBody(fields).toByteArray(Charsets.UTF_8)
      )
    )
}

/**
 * The token pair, absolute rather than relative.
 *
 * GitHub sends `expires_in` — a duration — and a duration is useless the moment
 * it is written down: read back tomorrow it says the token is good for another
 * eight hours when it died overnight. Both are turned into a point in time here,
 * at the moment the answer arrived, and that is what is stored.
 *
 * NOT a data class, for the same reason as [DeviceCodePrompt]: a generated
 * `toString` would print both tokens, and something will interpolate one of
 * these into a message sooner or later.
 */
class GitHubTokenSet(
  val accessToken: String,
  /** When the access token dies. GitHub gives it eight hours. */
  val accessExpiresAt: Long,
  val refreshToken: String,
  /** When the refresh token dies. GitHub gives it six months. */
  val refreshExpiresAt: Long
) {
  override fun toString(): String =
    "GitHubTokenSet(accessExpiresAt=$accessExpiresAt, refreshExpiresAt=$refreshExpiresAt)"

  override fun equals(other: Any?): Boolean =
    other is GitHubTokenSet &&
      accessToken == other.accessToken &&
      accessExpiresAt == other.accessExpiresAt &&
      refreshToken == other.refreshToken &&
      refreshExpiresAt == other.refreshExpiresAt

  override fun hashCode(): Int =
    (((accessToken.hashCode() * 31 + accessExpiresAt.hashCode()) * 31) +
      refreshToken.hashCode()) * 31 + refreshExpiresAt.hashCode()
}

/**
 * Reads a token answer — the same shape for a first sign-in and for a renewal.
 *
 * @param now the moment the answer arrived, which is what the durations are
 * counted from.
 */
internal fun tokensFrom(fields: Map<String, JsonValue>, now: Long): GitHubTokenSet? {
  val access = fields["access_token"].asString()
  val refresh = fields["refresh_token"].asString()
  if (access.isNullOrEmpty() || refresh.isNullOrEmpty()) {
    // A pair with no refresh token would work for eight hours and then send
    // the person back to the browser with nothing said. Refusing it here means
    // the failure lands where it can still be explained.
    return null
  }
  return GitHubTokenSet(
    accessToken = access,
    accessExpiresAt = now + (fields["expires_in"].asLong() ?: DEFAULT_ACCESS_LIFETIME_SECONDS),
    refreshToken = refresh,
    refreshExpiresAt = now + (fields["refresh_token_expires_in"].asLong() ?: DEFAULT_REFRESH_LIFETIME_SECONDS)
  )
}

internal fun jsonFields(response: BinaryHttpResponse): Map<String, JsonValue>? = try {
  Json.parse(String(response.body, Charsets.UTF_8)).asObject()
} catch (e: JsonParseException) {
  null
}

internal fun unreadableReason(response: BinaryHttpResponse): String =
  "GitHub's answer could not be read (status ${response.status})."

internal fun networkReason(e: Exception): String {
  val detail = e.message ?: e::class.simpleName.orEmpty()
  return "GitHub could not be reached${if (detail.isBlank()) "" else ": $detail"}."
}

/**
 * An error code turned into a sentence.
 *
 * GitHub's `error_description` is folded in where there is one — it is often the
 * only text that says which rule was broken — and truncated, because an error
 * body can be an entire page. NOTHING FROM THE REQUEST IS QUOTED, so there is
 * no path by which a token could reach this string.
 */
internal fun githubErrorSentence(error: String, fields: Map<String, JsonValue>): String {
  val description = fields["error_description"].asString()
    ?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }?.take(200)
  return "GitHub refused the sign-in ($error)." + if (description == null) "" else " GitHub said: $description"
}

/** `application/x-www-form-urlencoded`, which is what both endpoints take. */
internal fun formBody(fields: Map<String, String>): String =
  fields.entries.joinToString("&") { "${formEncode(it.key)}=${formEncode(it.value)}" }

/**
 * Percent-encoding for a form field.
 *
 * The grant type is a URN full of colons and slashes; unencoded they would end
 * the value early and GitHub would answer `unsupported_grant_type` with nothing
 * to indicate why.
 */
internal fun formEncode(value: String): String =
  value.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
    val ch = byte.toInt().toChar()
    if (byte.toInt() in 0..127 && (ch.isLetterOrDigit() || ch in "-._~")) ch.toString()
    else "%%%02X".format(byte.toInt() and 0xFF)
  }

/** Named so GitHub can tell this fork apart if they ever look at their side. */
private const val GITHUB_SIGN_IN_USER_AGENT = "GanttProject-Fork"

/**
 * Fallbacks for fields GitHub always sends.
 *
 * They are the documented values (read 10.09.2026), used only if an answer
 * arrives without them. Guessing eight hours when the answer said nothing is
 * better than treating the token as immortal — the worst that follows a guess
 * that is too short is one renewal too many.
 */
private const val DEFAULT_EXPIRES_IN_SECONDS = 900L
private const val DEFAULT_INTERVAL_SECONDS = 5L
private const val DEFAULT_ACCESS_LIFETIME_SECONDS = 28800L
private const val DEFAULT_REFRESH_LIFETIME_SECONDS = 15897600L
