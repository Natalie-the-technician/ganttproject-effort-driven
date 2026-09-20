/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
A child program for SecretCommandTimeoutTest, and nothing else.

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

import java.nio.charset.StandardCharsets

/**
 * [fork change] The program that [SecretCommandTimeoutTest] runs. It is never part of GanttProject.
 *
 * WHY A JAVA PROGRAM AND NOT `/bin/sleep`. What is being measured is [runSecretCommand], which
 * starts a process and has to survive whatever that process does. The obvious child is `sleep`, but
 * `sleep` does not exist on Windows, and the DPAPI half of this fork is measured on Windows. The
 * JVM that runs the test is on every platform by definition, so the child is started with
 * `java -cp <the test classpath> <this class>`. It costs a JVM start -- measured at well under a
 * second -- and buys a test that does not have to ask what operating system it is on.
 *
 * WRITING TO STDOUT AND STDERR IS THE POINT HERE. The rule that GanttProject says things through
 * the logger applies to GanttProject; this is a separate process whose entire job is to fill those
 * two pipes in a way the parent has to cope with.
 *
 * THE WATCHDOG IN [FLOOD] IS WHAT KEEPS A BROKEN PARENT FROM HANGING A BUILD. A parent that does
 * not empty stderr leaves this program blocked in `write` for ever. It therefore kills itself after
 * [WATCHDOG_SECONDS], so a failing test FAILS instead of hanging -- and it fails loudly, because
 * the exit code is then [WATCHDOG_EXIT] and not 0.
 */
object SecretCommandChild {

  /** What [TALK] writes to stdout. Deliberately carries a line break and characters outside ASCII. */
  const val TALK_STDOUT = "ein-ausgedachter-wert-äöü\tmit-Tabulator\nund Zeilenumbruch am Ende\n"

  /** What [TALK] writes to stderr. */
  const val TALK_STDERR = "eine Meldung auf stderr\n"

  /** The exit code [TALK] ends with. Not 0, so that a swallowed exit code is visible. */
  const val TALK_EXIT = 3

  /** The exit code the watchdog uses. */
  const val WATCHDOG_EXIT = 70

  /** How long [FLOOD] lets itself be blocked before it gives up on the parent. */
  const val WATCHDOG_SECONDS = 60L

  /** How long [SLOW_TALK] thinks before it says anything. Longer than the parent's drain grace. */
  const val SLOW_TALK_SECONDS = 9L

  const val SLEEP = "sleep"
  const val TALK = "talk"
  const val FLOOD = "flood"
  const val SLOW_TALK = "slowtalk"

  @JvmStatic
  fun main(args: Array<String>) {
    when (args.getOrNull(0)) {
      // sleep <seconds>. The test finds this process again by a system property the parent puts
      // on the command line, so there is nothing to read here.
      SLEEP -> {
        Thread.sleep(args[1].toLong() * 1000L)
        System.out.flush()
      }
      // Ends at once, with something on both pipes and a non-zero exit code.
      TALK -> {
        System.err.write(TALK_STDERR.toByteArray(StandardCharsets.UTF_8))
        System.err.flush()
        System.out.write(TALK_STDOUT.toByteArray(StandardCharsets.UTF_8))
        System.out.flush()
        Runtime.getRuntime().halt(TALK_EXIT)
      }
      // flood <bytes>: fills stderr first and only then says anything on stdout. A parent that
      // reads stdout to its end before it looks at stderr deadlocks here.
      FLOOD -> {
        startWatchdog()
        val howMuch = args[1].toInt()
        val chunk = ByteArray(1024) { 'x'.code.toByte() }
        var written = 0
        while (written < howMuch) {
          val n = minOf(chunk.size, howMuch - written)
          System.err.write(chunk, 0, n)
          written += n
        }
        System.err.flush()
        System.out.write("fertig".toByteArray(StandardCharsets.UTF_8))
        System.out.flush()
        Runtime.getRuntime().halt(0)
      }
      // slowtalk: says nothing for SLOW_TALK_SECONDS and only then behaves like TALK. This is the
      // keyring dialogue: the program is alive and working, it is simply waiting for a person.
      SLOW_TALK -> {
        Thread.sleep(SLOW_TALK_SECONDS * 1000L)
        System.err.write(TALK_STDERR.toByteArray(StandardCharsets.UTF_8))
        System.err.flush()
        System.out.write(TALK_STDOUT.toByteArray(StandardCharsets.UTF_8))
        System.out.flush()
        Runtime.getRuntime().halt(TALK_EXIT)
      }
      else -> Runtime.getRuntime().halt(64)
    }
  }

  /**
   * Kills this program if it is still around after [WATCHDOG_SECONDS].
   *
   * A daemon thread on purpose: it must not be the reason the program keeps running. `halt` and not
   * `exit`, because the thread that is blocked in `write` would keep a shutdown hook waiting.
   */
  private fun startWatchdog() {
    val watchdog = Thread({
      Thread.sleep(WATCHDOG_SECONDS * 1000L)
      Runtime.getRuntime().halt(WATCHDOG_EXIT)
    }, "secret-command-child-watchdog")
    watchdog.isDaemon = true
    watchdog.start()
  }
}
