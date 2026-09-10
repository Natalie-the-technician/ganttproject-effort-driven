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

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The sign-in against [FakeGitHubLogin]. Not one request leaves the machine,
 * and none could: the device flow needs a person to type a code into a
 * browser, so it is not runnable unattended even if that were allowed.
 *
 * ## The three tests this file exists for
 *
 * 1. `slow_down` raises the pause **and keeps it raised**. Everything else
 *    here is about a program that works; this one is about a program that
 *    does not make GitHub's day worse. It is checked by counting the pauses,
 *    because a claim about timing without a count is not a claim.
 * 2. `access_denied` stops the asking **at once**. A client that keeps polling
 *    after the person pressed Cancel hammers a service for a token it will
 *    never get.
 * 3. An expired access token is renewed **without anybody typing** and the new
 *    pair is **written to the store**. This is the one the usefulness of the
 *    whole thing hangs on: without it there is eight hours of hand work a day.
 */
class GitHubDeviceFlowTest {

  /**
   * Time that only moves when something waits.
   *
   * Not the wall clock, and not a counter that ticks on every read: waiting is
   * the only thing in this flow that consumes time, so a clock the waiter
   * advances is both the honest model and the one that lets a test say
   * "fifteen minutes went by" without taking fifteen minutes.
   */
  private class TestClock(var now: Long = 1_757_500_000L) : SecondsClock {
    override fun nowEpochSeconds(): Long = now
  }

  /** Records every pause instead of taking it, and moves [clock] by it. */
  private class CountingWaiter(private val clock: TestClock) : PollWaiter {
    val waits = mutableListOf<Long>()
    override fun waitSeconds(seconds: Long) {
      waits.add(seconds)
      clock.now += seconds
    }
  }

  /** The storage seam, in memory. */
  private class MemoryStorage(var line: String? = null) : GitHubTokenStorage {
    var writes = 0
      private set

    override fun readTokens(): String? = line

    override fun writeTokens(value: String?) {
      writes++
      line = value
    }
  }

  private val clock = TestClock()
  private val waiter = CountingWaiter(clock)
  private val storage = MemoryStorage()
  private val login = FakeGitHubLogin()
  private val logLines = mutableListOf<String>()

  private fun connection() = GitHubConnection(login, storage, clock, waiter)

  @BeforeEach
  fun captureTheLog() {
    GitHubLog.sink = { logLines.add(it) }
  }

  @AfterEach
  fun releaseTheLog() {
    GitHubLog.sink = {}
  }

  private fun promptOf(outcome: DeviceCodeOutcome): DeviceCodePrompt {
    assertTrue(outcome is DeviceCodeOutcome.Ready, "asking for a code failed: $outcome")
    return (outcome as DeviceCodeOutcome.Ready).prompt
  }

  // --- the ordinary way ----------------------------------------------------

  @Test
  fun `a code, two pending answers, then a token`() {
    login.script.addAll(listOf(FakeGitHubLogin.Answer.Pending, FakeGitHubLogin.Answer.Pending, FakeGitHubLogin.Answer.Granted))
    val connection = connection()

    val prompt = promptOf(connection.startConnecting())
    // What the person is given, and all they are given: eight characters and
    // an address. Both have to survive to the caller or there is nothing to
    // put on the screen.
    assertEquals("WDJB-MJHT", prompt.userCode)
    assertEquals("https://github.com/login/device", prompt.verificationUri)
    assertEquals(5L, prompt.intervalSeconds)
    assertEquals(900L, prompt.expiresInSeconds)

    val outcome = connection.finishConnecting(prompt)

    assertTrue(outcome is DeviceFlowOutcome.Connected, "the flow did not end in a token: $outcome")
    assertEquals("ghu_access_1", (outcome as DeviceFlowOutcome.Connected).tokens.accessToken)
    assertEquals("ghr_refresh_1", outcome.tokens.refreshToken)
    // One request for the code, three for the polling. Counted, because
    // "it asked as often as it had to" is otherwise a guess.
    assertEquals(4, login.requestCount)
    assertEquals(1, login.countOf("/login/device/code"))
    assertEquals(3, login.countOf("/login/oauth/access_token"))
  }

  @Test
  fun `the code request carries the client id and the poll carries the device code`() {
    login.script.add(FakeGitHubLogin.Answer.Granted)
    val connection = connection()
    val prompt = promptOf(connection.startConnecting())
    connection.finishConnecting(prompt)

    assertEquals(GITHUB_CLIENT_ID, login.requests[0].fields["client_id"])
    assertEquals(GITHUB_CLIENT_ID, login.requests[1].fields["client_id"])
    assertEquals(
      "urn:ietf:params:oauth:grant-type:device_code",
      login.requests[1].fields["grant_type"],
      "the grant type of the device flow has to be spelled out in full"
    )
    assertEquals(login.deviceCode, login.requests[1].fields["device_code"])
  }

  @Test
  fun `the pauses are the interval GitHub asked for`() {
    login.intervalSeconds = 7
    login.script.addAll(listOf(FakeGitHubLogin.Answer.Pending, FakeGitHubLogin.Answer.Granted))
    val connection = connection()

    connection.finishConnecting(promptOf(connection.startConnecting()))

    assertEquals(listOf(7L, 7L), waiter.waits, "every poll has to be preceded by the interval")
  }

  // --- the three that carry the design -------------------------------------

  /**
   * THE PERMANENCE TEST.
   *
   * Adding five seconds once and dropping back to the old pace at the next
   * answer is the mistake that looks like obedience: GitHub asked for a
   * slower pace and got one poll's worth of it. The list below is what makes
   * the difference visible — after the first `slow_down` **both** following
   * pauses are ten, not just the one right after it.
   */
  @Test
  fun `slow_down raises the pause by five seconds and keeps it raised`() {
    login.intervalSeconds = 5
    login.script.addAll(
      listOf(
        FakeGitHubLogin.Answer.Pending,
        FakeGitHubLogin.Answer.SlowDown,
        FakeGitHubLogin.Answer.Pending,
        FakeGitHubLogin.Answer.SlowDown,
        FakeGitHubLogin.Answer.Pending,
        FakeGitHubLogin.Answer.Granted
      )
    )
    val connection = connection()

    val outcome = connection.finishConnecting(promptOf(connection.startConnecting()))

    assertTrue(outcome is DeviceFlowOutcome.Connected, "$outcome")
    assertEquals(
      listOf(5L, 5L, 10L, 10L, 15L, 15L),
      waiter.waits,
      "each slow_down adds five seconds for good; the pause never falls back"
    )
  }

  /**
   * THE STOPPING TEST.
   *
   * `pollsAfterScript` is zero only if the client asked exactly as often as
   * the script had answers. One more poll after the refusal and it is one.
   */
  @Test
  fun `access_denied stops the asking at once`() {
    login.script.addAll(listOf(FakeGitHubLogin.Answer.Pending, FakeGitHubLogin.Answer.Denied))
    val connection = connection()

    val outcome = connection.finishConnecting(promptOf(connection.startConnecting()))

    assertTrue(outcome is DeviceFlowOutcome.Denied, "a refusal is not a failure and not a pause: $outcome")
    assertEquals(0, login.pollsAfterScript, "it kept asking after the person said no")
    assertEquals(2, login.countOf("/login/oauth/access_token"))
    // Nothing was connected, so nothing may have been written.
    assertNull(storage.line)
    assertEquals(0, storage.writes)
  }

  @Test
  fun `expired_token says a new code has to be fetched`() {
    login.script.addAll(listOf(FakeGitHubLogin.Answer.Pending, FakeGitHubLogin.Answer.CodeExpired))
    val connection = connection()

    val outcome = connection.finishConnecting(promptOf(connection.startConnecting()))

    assertTrue(outcome is DeviceFlowOutcome.Expired, "$outcome")
    val reason = (outcome as DeviceFlowOutcome.Expired).reason
    assertTrue(
      reason.contains("again", ignoreCase = true) || reason.contains("new", ignoreCase = true),
      "the message has to say that one starts over, not merely that something expired: $reason"
    )
    assertEquals(0, login.pollsAfterScript, "an expired code cannot become a live one by asking again")
  }

  /**
   * The same end without GitHub saying so.
   *
   * A server that answers `authorization_pending` for ever — because the
   * person shut the browser and went home — must not be polled for ever. The
   * fifteen minutes are known from the first answer, so the client can end it
   * itself.
   */
  @Test
  fun `the code runs out here too, without asking for ever`() {
    login.expiresInSeconds = 30
    login.intervalSeconds = 5
    val connection = connection()

    val outcome = connection.finishConnecting(promptOf(connection.startConnecting()))

    assertTrue(outcome is DeviceFlowOutcome.Expired, "$outcome")
    // Six pauses of five seconds are thirty; the sixth ends it before a
    // seventh poll. Counted rather than described.
    assertEquals(listOf(5L, 5L, 5L, 5L, 5L, 5L), waiter.waits)
    assertEquals(5, login.countOf("/login/oauth/access_token"))
  }

  @Test
  fun `device_flow_disabled names the switch that is missing`() {
    login.deviceFlowEnabled = false
    val connection = connection()

    val outcome = connection.startConnecting()

    assertTrue(outcome is DeviceCodeOutcome.NotEnabled, "$outcome")
    val reason = (outcome as DeviceCodeOutcome.NotEnabled).reason
    assertTrue(
      reason.contains("Device Flow", ignoreCase = true),
      "whoever reads this has to know which setting to go and switch on: $reason"
    )
  }

  @Test
  fun `stopping the wait ends it and asks no more`() {
    login.script.addAll(listOf(FakeGitHubLogin.Answer.Pending, FakeGitHubLogin.Answer.Pending))
    val connection = connection()
    val prompt = promptOf(connection.startConnecting())
    var polls = 0

    val outcome = connection.finishConnecting(prompt) { polls++ < 1 }

    assertTrue(outcome is DeviceFlowOutcome.Stopped, "$outcome")
    assertEquals(1, login.countOf("/login/oauth/access_token"))
    assertEquals(0, login.pollsAfterScript)
  }

  @Test
  fun `no network becomes a reason instead of an exception`() {
    login.throwWith = IOException("Unable to resolve host github.com")
    val connection = connection()

    val outcome = connection.startConnecting()

    assertTrue(outcome is DeviceCodeOutcome.Failed, "$outcome")
    assertTrue((outcome as DeviceCodeOutcome.Failed).reason.contains("Unable to resolve host"))
  }

  // --- renewing ------------------------------------------------------------

  /**
   * THE TEST THE USEFULNESS HANGS ON.
   *
   * Eight hours is one working day. Without this the person types a code into
   * a browser every morning, and by the third morning they have turned the
   * whole thing off.
   */
  @Test
  fun `an expired access token is renewed without anybody typing`() {
    login.script.add(FakeGitHubLogin.Answer.Granted)
    val connection = connection()
    connection.finishConnecting(promptOf(connection.startConnecting()))
    val requestsSoFar = login.requestCount

    // Nine hours later: the access token is gone, the refresh token is not.
    clock.now += 9 * 3600

    val outcome = connection.accessToken()

    assertTrue(outcome is AccessTokenOutcome.Ready, "$outcome")
    assertEquals("ghu_access_2", (outcome as AccessTokenOutcome.Ready).accessToken)
    assertEquals(1, login.requestCount - requestsSoFar, "renewing is exactly one request")
    assertEquals("refresh_token", login.requests.last().fields["grant_type"])
    assertEquals("ghr_refresh_1", login.requests.last().fields["refresh_token"])
  }

  /**
   * THE NOTHING-CHANGES GUARD.
   *
   * A renewal that is not written down is worth nothing: the next start of the
   * program reads the old, dead access token, renews again, and the person
   * never notices anything except that GitHub is asked twice as often as it
   * needs to be. Worse, every renewal spends the refresh token, so a
   * forgotten renewal walks straight into a dead one.
   *
   * The reference is computed HERE, from the fake's own documented lifetimes
   * and from a clock this test owns. Nothing in it comes from the code being
   * measured, so an implementation that got the arithmetic wrong cannot move
   * both sides of the comparison at once.
   */
  @Test
  fun `and the renewed pair is written to the store`() {
    login.script.add(FakeGitHubLogin.Answer.Granted)
    val connection = connection()
    connection.finishConnecting(promptOf(connection.startConnecting()))
    clock.now += 9 * 3600
    val renewedAt = clock.now
    val writesBefore = storage.writes

    connection.accessToken()

    assertTrue(storage.writes > writesBefore, "the renewal never reached the store")
    val stored = decodeGitHubTokens(storage.line)
    assertNotNull(stored, "the store holds nothing that can be read back: ${storage.line}")
    assertEquals("ghu_access_2", stored!!.accessToken)
    assertEquals("ghr_refresh_2", stored.refreshToken, "the new refresh token has to replace the spent one")
    assertEquals(renewedAt + FakeGitHubLogin.ACCESS_TOKEN_LIFETIME_SECONDS, stored.accessExpiresAt)
    assertEquals(renewedAt + FakeGitHubLogin.REFRESH_TOKEN_LIFETIME_SECONDS, stored.refreshExpiresAt)
  }

  /**
   * The other half of the same guard: a program that starts again finds it.
   *
   * A store written but never read would pass the test above and fail here,
   * and that is the failure that costs a person eight hours' patience.
   */
  @Test
  fun `a second connection over the same store needs no request at all`() {
    login.script.add(FakeGitHubLogin.Answer.Granted)
    val first = connection()
    first.finishConnecting(promptOf(first.startConnecting()))
    val requestsSoFar = login.requestCount

    val second = GitHubConnection(login, storage, clock, waiter)
    val outcome = second.accessToken()

    assertTrue(outcome is AccessTokenOutcome.Ready, "$outcome")
    assertEquals("ghu_access_1", (outcome as AccessTokenOutcome.Ready).accessToken)
    assertEquals(requestsSoFar, login.requestCount, "a token still good for hours does not need renewing")
    assertTrue(second.isConnected())
  }

  /**
   * THE ANTI-LOOP TEST.
   *
   * Six months on, the refresh token is as dead as the access token. The one
   * thing that must not happen is a program that keeps offering it: that is a
   * request per save, for ever, that can never succeed. The count of zero is
   * the whole assertion.
   */
  @Test
  fun `an expired refresh token leads back to the device flow, not into a loop`() {
    login.script.add(FakeGitHubLogin.Answer.Granted)
    val connection = connection()
    connection.finishConnecting(promptOf(connection.startConnecting()))
    val requestsSoFar = login.requestCount

    // Seven months.
    clock.now += 7L * 30 * 24 * 3600

    val first = connection.accessToken()
    val second = connection.accessToken()

    assertTrue(first is AccessTokenOutcome.ConnectAgain, "$first")
    assertTrue(second is AccessTokenOutcome.ConnectAgain, "$second")
    assertEquals(
      requestsSoFar, login.requestCount,
      "a refresh token our own clock says is dead must not be offered to GitHub even once"
    )
  }

  /**
   * The same end by the other route: our clock says the refresh token lives,
   * GitHub says it does not. It has the last word, and what it says has to be
   * believed once and not asked again.
   */
  @Test
  fun `a refresh token GitHub refuses is thrown away, so the next call does not ask again`() {
    login.script.add(FakeGitHubLogin.Answer.Granted)
    val connection = connection()
    connection.finishConnecting(promptOf(connection.startConnecting()))
    login.deadRefreshTokens.add("ghr_refresh_1")
    clock.now += 9 * 3600
    val requestsSoFar = login.requestCount

    val first = connection.accessToken()
    val afterFirst = login.requestCount
    val second = connection.accessToken()

    assertTrue(first is AccessTokenOutcome.ConnectAgain, "$first")
    assertEquals(1, afterFirst - requestsSoFar, "the refusal costs exactly one request")
    assertTrue(second is AccessTokenOutcome.NotConnected, "$second")
    assertEquals(afterFirst, login.requestCount, "a token GitHub has refused must not be offered a second time")
    assertFalse(connection.isConnected())
  }

  @Test
  fun `nothing stored is not connected, and not a failure`() {
    val connection = connection()

    val outcome = connection.accessToken()

    assertTrue(outcome is AccessTokenOutcome.NotConnected, "$outcome")
    assertEquals(0, login.requestCount)
    assertFalse(connection.isConnected())
  }

  @Test
  fun `disconnecting empties the store`() {
    login.script.add(FakeGitHubLogin.Answer.Granted)
    val connection = connection()
    connection.finishConnecting(promptOf(connection.startConnecting()))
    assertTrue(connection.isConnected())

    connection.forget()

    assertNull(storage.line)
    assertFalse(connection.isConnected())
  }

  // --- the store itself ----------------------------------------------------

  @Test
  fun `the store carries both tokens and both expiry times, and survives odd characters`() {
    // Tokens are opaque strings from a service. Nothing says they cannot
    // contain the separator this encoding uses, and a store cut in half by
    // one would lose a connection with no error anywhere.
    val tokens = GitHubTokenSet("ghu_a:b=c;d", 1_757_500_000L, "ghr_x:y=z&w", 1_773_400_000L)

    val read = decodeGitHubTokens(tokens.encoded())

    assertNotNull(read)
    assertEquals(tokens.accessToken, read!!.accessToken)
    assertEquals(tokens.refreshToken, read.refreshToken)
    assertEquals(tokens.accessExpiresAt, read.accessExpiresAt)
    assertEquals(tokens.refreshExpiresAt, read.refreshExpiresAt)
  }

  @Test
  fun `rubbish in the store is nothing stored, not a crash`() {
    assertNull(decodeGitHubTokens(null))
    assertNull(decodeGitHubTokens(""))
    assertNull(decodeGitHubTokens("gh1:only:two"))
    assertNull(decodeGitHubTokens("what is this"))
    // A version marker from a later release: unreadable here, and it must not
    // be guessed at.
    assertNull(decodeGitHubTokens("gh9:AAAA:1:BBBB:2"))
  }

  // --- the token never reaches the log -------------------------------------

  /**
   * NOT A RESOLUTION BUT A CHECK.
   *
   * A log with a token in it is a token in every backup, in every bug report
   * and on every screen it was ever read from. Intending not to write one
   * there is worth nothing; this reads what actually arrived at the sink.
   *
   * The `isNotEmpty` assertion is not decoration. Without it the test passes
   * for a program that logs nothing at all, and it would then go on passing
   * on the day somebody adds the one line that spoils it.
   */
  @Test
  fun `no secret ever reaches the log`() {
    login.intervalSeconds = 5
    login.script.addAll(
      listOf(FakeGitHubLogin.Answer.Pending, FakeGitHubLogin.Answer.SlowDown, FakeGitHubLogin.Answer.Granted)
    )
    val connection = connection()
    val prompt = promptOf(connection.startConnecting())
    connection.finishConnecting(prompt)
    clock.now += 9 * 3600
    connection.accessToken()

    assertTrue(logLines.isNotEmpty(), "nothing was logged at all, so this proves nothing")
    val secrets = buildList {
      add(prompt.deviceCode)
      addAll(login.issuedAccessTokens)
      addAll(login.issuedRefreshTokens)
    }
    for (line in logLines) {
      for (secret in secrets) {
        assertFalse(line.contains(secret), "a secret is in the log: $line")
        // Not shortened either. Eight characters of a token are eight
        // characters an attacker no longer has to guess, and a truncated
        // secret in a log reads as if somebody had thought about it.
        assertFalse(line.contains(secret.take(8)), "part of a secret is in the log: $line")
      }
    }
  }

  @Test
  fun `a failure reason never carries a secret either`() {
    login.script.add(FakeGitHubLogin.Answer.Granted)
    val connection = connection()
    connection.finishConnecting(promptOf(connection.startConnecting()))
    login.deadRefreshTokens.add("ghr_refresh_1")
    clock.now += 9 * 3600

    val outcome = connection.accessToken()

    val reason = when (outcome) {
      is AccessTokenOutcome.ConnectAgain -> outcome.reason
      is AccessTokenOutcome.Failed -> outcome.reason
      else -> ""
    }
    assertTrue(reason.isNotEmpty(), "there is no sentence to check: $outcome")
    assertFalse(reason.contains("ghr_refresh_1"), "the refresh token is in the message shown to the user: $reason")
    assertFalse(reason.contains("ghu_access_1"), "the access token is in the message shown to the user: $reason")
  }

  /**
   * The trap a data class walks into.
   *
   * `data class` generates a `toString` that prints every property, so
   * `GPLogger.log("could not save: $tokens")` would put both tokens in the log
   * without anybody writing them there. That is why neither of these two is a
   * data class, and this is the check that keeps it that way.
   */
  @Test
  fun `printing a token set or a prompt shows no secret`() {
    val tokens = GitHubTokenSet("ghu_secret_access", 1L, "ghr_secret_refresh", 2L)
    val prompt = DeviceCodePrompt("WDJB-MJHT", "https://github.com/login/device", 900, 5, "the-device-code")

    assertFalse(tokens.toString().contains("ghu_secret_access"), tokens.toString())
    assertFalse(tokens.toString().contains("ghr_secret_refresh"), tokens.toString())
    assertFalse(prompt.toString().contains("the-device-code"), prompt.toString())
    // The user code is meant for the screen, so it may be printed — and being
    // able to see which code a run was showing is worth having in a log.
    assertTrue(prompt.toString().contains("WDJB-MJHT"), prompt.toString())
  }
}
