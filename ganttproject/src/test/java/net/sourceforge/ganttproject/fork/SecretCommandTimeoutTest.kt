/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
The time limit in runSecretCommand, and the two pipes it has to keep empty.

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
package net.sourceforge.ganttproject.fork

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.UUID
import java.util.stream.Collectors

/**
 * THE TIME LIMIT OF [runSecretCommand], AND THE DEADLOCK THAT SAT NEXT TO IT.
 *
 * WHAT WAS WRONG, measured on 20.09.2026 against the compiled code of `secretstore-plattformen`
 * (`f17bc7b74`): a call with a program that sleeps for 45 seconds came back after 45 seconds, with
 * a result and not with null, although [TIMEOUT_SECONDS] is 20. The order of the three lines was
 * the whole of it -- both pipes were read to their end BEFORE `waitFor` was reached, and a pipe
 * reaches its end when the process ends. By the time the limit was consulted there was nothing
 * left to interrupt. A limit that can only fire after the thing it is meant to bound is over is
 * not a limit.
 *
 * WHY THAT MATTERS AND NOT ONLY ON PAPER: the only reason `security` or `secret-tool` ever waits is
 * a locked keyring or an access decision, and that is the normal case on a real desktop, not the
 * exception. The comment above [TIMEOUT_SECONDS] says what was meant -- long enough to type a
 * passphrase, short enough not to look like a hang. Without this the program waits for ever.
 *
 * THE SECOND FAULT IS THE SAME LINE, seen from the other side, and it is tested here too:
 * stdout was read to its end before stderr was touched. A program that fills the stderr pipe blocks
 * in `write` while the parent waits on stdout, and neither side can move. That is not a second bug
 * in a second place; it is the same missing property -- both pipes have to be emptied while the
 * program runs.
 *
 * WHAT THESE TESTS MEASURE AND WHAT THEY DO NOT. They measure [runSecretCommand] and nothing else:
 * no keyring is needed, no `secret-tool`, no `security`, and the results say nothing about either
 * of those programs. That is on purpose -- [runSecretCommand] is the one piece all three platforms
 * share, so the property can be pinned on whichever machine happens to run the tests. What this
 * file does NOT show is that a locked keyring behaves the way the limit assumes; that needs a
 * locked keyring and a person, and it is named as unmeasured in the report.
 *
 * THE CHILD IS [SecretCommandChild], started as its own JVM. See its KDoc for why not `sleep`.
 */
class SecretCommandTimeoutTest {

  /**
   * A program that does not end must be given up on, and long before it ends.
   *
   * THE TWO HALVES BOTH MATTER. `null` alone would also be given by a version that answers null to
   * everything -- [testAProgramThatEndsAtOnceIsAnsweredAtOnceAndInFull] is the counterweight for
   * that. And "came back" alone would be satisfied by the broken version, which came back too,
   * only after the full sleep and with a result that claimed success.
   *
   * THE NUMBERS. The child sleeps [SLEEP_SECONDS], which is six times [TIMEOUT_SECONDS]; the test
   * allows [GIVE_UP_WITHIN_SECONDS], which is three times the limit. The gap in both directions is
   * deliberate: the test says "the limit fired", not "the limit is exactly twenty", and a loaded
   * build machine may not be punctual.
   */
  @Test
  fun testAProgramThatDoesNotEndIsGivenUpOnLongBeforeItEnds() {
    val started = System.nanoTime()
    val result = runSecretCommand(
      childCommand(marker(), SecretCommandChild.SLEEP, SLEEP_SECONDS.toString()), null)
    val seconds = secondsSince(started)
    assertNull(result,
      "a program that runs far past the time limit must be given up on, and giving up means null"
        + " -- it came back after " + seconds + "s with a result")
    assertTrue(seconds < GIVE_UP_WITHIN_SECONDS,
      "the answer has to come while the program is still running, not after it: the child sleeps "
        + SLEEP_SECONDS + "s and the answer took " + seconds + "s")
  }

  /**
   * THE COUNTERWEIGHT, and the guard on the normal case: a program that is finished at once is
   * answered at once, in full, and with its exit code.
   *
   * Everything a caller of [runSecretCommand] uses is checked here in one go -- the exit code,
   * every byte of stdout, and stderr -- because stdout is where the secret comes back and a single
   * byte lost at either end is a wrong password. The child writes a tab, a line break at the end
   * and characters outside ASCII for exactly that reason.
   *
   * AND IT HAS TO BE FAST. A version that waits out the time limit before answering would pass
   * every check above; the duration is what separates it from a working one.
   */
  @Test
  fun testAProgramThatEndsAtOnceIsAnsweredAtOnceAndInFull() {
    val started = System.nanoTime()
    val result = runSecretCommand(childCommand(marker(), SecretCommandChild.TALK), null)
    val seconds = secondsSince(started)
    assertNotNull(result, "a program that ends at once has an answer")
    assertEquals(SecretCommandChild.TALK_EXIT, result!!.exitCode, "the exit code is passed through")
    assertArrayEquals(SecretCommandChild.TALK_STDOUT.toByteArray(StandardCharsets.UTF_8),
      result.stdout, "stdout is where the secret comes back: byte for byte or not at all")
    assertEquals(SecretCommandChild.TALK_STDERR, result.stderr, "stderr is passed through as well")
    assertTrue(seconds < ANSWER_AT_ONCE_WITHIN_SECONDS,
      "a program that is done must not be waited out: it took " + seconds + "s")
  }

  /**
   * BOTH PIPES ARE EMPTIED AT THE SAME TIME, and this is the test that says so.
   *
   * The child fills stderr with [FLOOD_BYTES] -- far more than a pipe holds, measured at 64 KB on
   * Linux -- and says nothing on stdout until it is done. A parent that reads stdout to its end
   * first never gets there: the child is blocked in `write` on a full stderr pipe, so it never
   * ends, so stdout never reaches its end either.
   *
   * WHY THIS TEST FAILS INSTEAD OF HANGING: the child kills itself after
   * [SecretCommandChild.WATCHDOG_SECONDS] with exit code [SecretCommandChild.WATCHDOG_EXIT]. A
   * broken parent therefore gets a late answer with the wrong exit code and too little stderr, and
   * every one of the four checks below says so. A build that hangs teaches nobody anything.
   */
  @Test
  fun testStderrIsEmptiedWhileTheProgramIsStillWriting() {
    val started = System.nanoTime()
    val result = runSecretCommand(
      childCommand(marker(), SecretCommandChild.FLOOD, FLOOD_BYTES.toString()), null)
    val seconds = secondsSince(started)
    assertNotNull(result, "the child ends by itself, so there is an answer")
    assertEquals(0, result!!.exitCode,
      "exit code " + SecretCommandChild.WATCHDOG_EXIT + " means the child had to kill itself"
        + " because nobody emptied stderr")
    assertEquals(FLOOD_BYTES, result.stderr.length,
      "every byte the child wrote to stderr has to arrive")
    assertEquals("fertig", String(result.stdout, StandardCharsets.UTF_8),
      "and stdout, which the child only writes after stderr, has to arrive as well")
    assertTrue(seconds < ANSWER_AT_ONCE_WITHIN_SECONDS,
      "nothing here has to wait for anything: it took " + seconds + "s")
  }

  /**
   * THE PROGRAM THAT RAN OUT OF TIME IS REALLY DEAD.
   *
   * Giving up is worth nothing if the process stays behind. On a desktop that would be a
   * `security` or `secret-tool` left sitting on the keyring for the rest of the session, one per
   * attempt, and at worst one of them still holding a dialogue in front of the user.
   *
   * THE POSITIVE CONTROL COMES FIRST and is the reason this test means anything. Looking for a
   * process by its command line can come up empty because the process is gone or because the
   * lookup does not work on this machine -- `ProcessHandle.info().commandLine()` is documented to
   * be allowed to return nothing. So the test first starts a child of its own with the same kind
   * of marker, insists that it FINDS it, kills it, and insists that it is then gone. Only after
   * that does the real question get asked. If the lookup is blind on this machine, this test fails
   * at the control and does not quietly report success.
   */
  @Test
  fun testTheProgramThatRanOutOfTimeIsReallyDead() {
    val controlMarker = marker()
    val control = ProcessBuilder(
      childCommand(controlMarker, SecretCommandChild.SLEEP, SLEEP_SECONDS.toString())).start()
    try {
      assertTrue(waitUntilFound(controlMarker),
        "the control: a child that is definitely running has to be findable by its command line,"
          + " otherwise this test cannot tell a dead process from a blind lookup")
      control.destroyForcibly()
      assertTrue(control.waitFor(KILL_WITHIN_SECONDS, java.util.concurrent.TimeUnit.SECONDS),
        "the control: a killed child has to be reaped")
      assertTrue(processesCarrying(controlMarker).isEmpty(),
        "the control: and it must be gone from the process table")
    } finally {
      control.destroyForcibly()
    }

    val timedOutMarker = marker()
    val result = runSecretCommand(
      childCommand(timedOutMarker, SecretCommandChild.SLEEP, SLEEP_SECONDS.toString()), null)
    assertNull(result, "this call is meant to run out of time")
    val leftOver = processesCarrying(timedOutMarker)
    assertTrue(leftOver.isEmpty(),
      "a program that was given up on must not stay behind: " + leftOver.size + " left over")
  }

  /**
   * THE THREADS THAT EMPTY THE PIPES MUST NOT KEEP THE JVM ALIVE, AND MUST NOT OUTLIVE THE CALL.
   *
   * The fix moved the reading of both pipes into threads of their own. That buys the time limit,
   * and it buys a new way to get the same fault back: a non-daemon thread sitting on a pipe that
   * never closes keeps the JVM from ending. GanttProject would then not shut down -- the same hang,
   * only after the window is gone, and green tests all the way. So the property is pinned here.
   *
   * SAMPLED WHILE THE CALL RUNS, not after it. Afterwards the threads are gone and every check
   * would pass by default; the question is what they are WHILE they exist. A sampler thread
   * therefore looks at the thread table every few milliseconds during a call that runs out of time.
   *
   * THE POSITIVE CONTROL IS [seen]. If the sampler never finds a single helper thread, the test
   * has measured nothing at all, and it says so instead of reporting success. That is the same
   * reason [testTheProgramThatRanOutOfTimeIsReallyDead] starts a control child of its own.
   *
   * AND THEY HAVE TO END. After the call comes back the pipes are broken -- the program was killed
   * -- so the threads run out of work and stop. A thread still alive [THREAD_END_WITHIN_SECONDS]
   * later is one that would hang a shutdown, daemon or not.
   */
  @Test
  fun testThePipeThreadsAreDaemonsAndDoNotOutliveTheCall() {
    val seen = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
    val sampler = Thread({
      while (sampling.get()) {
        helperThreads().forEach { seen[it.name] = it.isDaemon }
        Thread.sleep(20L)
      }
    }, "secret-command-thread-sampler")
    sampler.isDaemon = true
    sampler.start()

    val result = try {
      runSecretCommand(childCommand(marker(), SecretCommandChild.SLEEP, SLEEP_SECONDS.toString()), null)
    } finally {
      sampling.set(false)
    }
    sampler.join(KILL_WITHIN_SECONDS * 1000L)
    assertNull(result, "this call is meant to run out of time")

    assertTrue(seen.isNotEmpty(),
      "the control: the call has to have used at least one helper thread named " + HELPER_PREFIX
        + "* -- without one, this test measures nothing")
    val notDaemons = seen.filterValues { !it }.keys.sorted()
    assertTrue(notDaemons.isEmpty(),
      "every thread that empties a pipe has to be a daemon, or the JVM cannot end while one of"
        + " them waits on a pipe that never closes. These were not: " + notDaemons)

    val deadline = System.nanoTime() + THREAD_END_WITHIN_SECONDS * 1_000_000_000L
    while (System.nanoTime() < deadline && helperThreads().isNotEmpty()) {
      Thread.sleep(100L)
    }
    val stillAlive = helperThreads().map { it.name }.sorted()
    assertTrue(stillAlive.isEmpty(),
      "the helper threads have to end when the program does: still alive after "
        + THREAD_END_WITHIN_SECONDS + "s: " + stillAlive)
  }

  /** The name every helper thread of [runSecretCommand] starts with. */
  private val HELPER_PREFIX = "gp-secret-"

  private val THREAD_END_WITHIN_SECONDS = 10L

  private fun helperThreads(): List<Thread> =
    Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith(HELPER_PREFIX) }


  /**
   * A PROGRAM THAT IS SLOW BUT INSIDE THE LIMIT IS WAITED FOR, AND ANSWERED IN FULL.
   *
   * THIS IS THE CASE THE LIMIT EXISTS FOR, not an edge one. The comment above [TIMEOUT_SECONDS]
   * says it: a locked keyring puts a dialogue in front of the user and the program then waits for
   * a person to type a passphrase. Twenty seconds is that person's time. Nine seconds of silence
   * followed by a full answer is exactly what that looks like from here.
   *
   * IT IS ALSO THE GUARD ON A SECOND, SMALLER LIMIT THAT HAS NOTHING TO DO WITH THE FIRST.
   * [DRAIN_GRACE_SECONDS] bounds how long this code waits for the PIPES after the program has
   * ended; it is five seconds, and it must never turn into a bound on the program itself. Measured
   * on 20.09.2026: with the awaits moved back in front of `waitFor` -- the shape this fix replaced
   * -- every other test in this file still passed, and their durations merely dropped from twenty
   * seconds to five. The limit had silently become five seconds and nothing said so. The child
   * sleeps [SecretCommandChild.SLOW_TALK_SECONDS], which sits deliberately between the two numbers:
   * past the drain grace, well short of the limit.
   */
  @Test
  fun testAProgramThatIsSlowButInsideTheLimitIsWaitedForAndAnsweredInFull() {
    val started = System.nanoTime()
    val result = runSecretCommand(childCommand(marker(), SecretCommandChild.SLOW_TALK), null)
    val seconds = secondsSince(started)
    assertNotNull(result,
      "a program that answers after " + SecretCommandChild.SLOW_TALK_SECONDS + "s is well inside"
        + " the time limit and must be waited for, not given up on after " + seconds + "s")
    assertEquals(SecretCommandChild.TALK_EXIT, result!!.exitCode, "the exit code is passed through")
    assertArrayEquals(SecretCommandChild.TALK_STDOUT.toByteArray(StandardCharsets.UTF_8),
      result.stdout, "and every byte of the secret arrives, late answer or not")
    assertEquals(SecretCommandChild.TALK_STDERR, result.stderr, "stderr too")
    assertTrue(seconds >= SecretCommandChild.SLOW_TALK_SECONDS,
      "the control: if this came back faster than the child can possibly have answered ("
        + seconds + "s), the child is not doing what this test assumes")
  }

  /** Six times the time limit, so "it ran past the limit" needs no interpretation. */
  private val SLEEP_SECONDS = 120L

  /** Three times the time limit. Room for a slow machine, and far short of [SLEEP_SECONDS]. */
  private val GIVE_UP_WITHIN_SECONDS = 60.0

  /** A JVM start plus the work. Far below the time limit, which is what this number is for. */
  private val ANSWER_AT_ONCE_WITHIN_SECONDS = 15.0

  private val KILL_WITHIN_SECONDS = 10L

  /** More than a pipe holds several times over; a pipe was measured at 64 KB on Linux. */
  private val FLOOD_BYTES = 512 * 1024

  /**
   * `java -D<the marker> -cp <the classpath of this test> SecretCommandChild <args>`.
   *
   * `java.home` and `java.class.path` rather than anything built by hand: the child then runs on
   * the same JVM and the same classpath as the test, whatever the build put there.
   *
   * THE MARKER GOES IN FRONT OF `-cp`, AND THAT IS NOT A MATTER OF TASTE. Measured on 20.09.2026
   * on this machine: `ProcessHandle.info().commandLine()` gives back at most 4096 characters. The
   * classpath of a Gradle test JVM is longer than that on its own, so a marker placed after it is
   * cut off and the process becomes invisible -- which is exactly how the first run of
   * [testTheProgramThatRanOutOfTimeIsReallyDead] failed, at its control and not at its question.
   * In front of `-cp` the marker is inside the first hundred characters.
   */
  private fun childCommand(marker: String, vararg args: String): List<String> {
    val exe = if (System.getProperty("os.name", "").startsWith("Windows")) "java.exe" else "java"
    val java = Paths.get(System.getProperty("java.home"), "bin", exe).toString()
    return listOf(java, "-D" + MARKER_PROPERTY + "=" + marker,
      "-cp", System.getProperty("java.class.path"), SecretCommandChild::class.java.name) + args
  }

  /** The system property the marker is carried in. Read by nobody; it only has to be visible. */
  private val MARKER_PROPERTY = "gp.secret.command.probe"

  /** A string that appears in the command line of one child and nowhere else on the machine. */
  private fun marker() = "gp-secret-timeout-probe-" + UUID.randomUUID()

  private fun processesCarrying(marker: String): List<ProcessHandle> =
    ProcessHandle.allProcesses()
      .filter { it.info().commandLine().orElse("").contains(marker) }
      .collect(Collectors.toList())

  /** A started process takes a moment to appear in the process table. */
  private fun waitUntilFound(marker: String): Boolean {
    val deadline = System.nanoTime() + KILL_WITHIN_SECONDS * 1_000_000_000L
    while (System.nanoTime() < deadline) {
      if (processesCarrying(marker).isNotEmpty()) {
        return true
      }
      Thread.sleep(100L)
    }
    return false
  }

  private fun secondsSince(startedNanos: Long) =
    (System.nanoTime() - startedNanos) / 1_000_000_000.0
}
