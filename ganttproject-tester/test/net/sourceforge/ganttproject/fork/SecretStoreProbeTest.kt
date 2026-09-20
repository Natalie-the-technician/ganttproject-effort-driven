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
package net.sourceforge.ganttproject.fork

import junit.framework.TestCase
import java.io.File

/**
 * [fork change] MEASURING INSTRUMENT, NOT A FEATURE TEST. It exists because of a bookkeeping hole
 * that every other test in this area shares.
 *
 * THE HOLE: `SecretStoreTest` and `TokenEncryptionTest` guard their real work with
 * `if (!SecretStore.isAvailable) return`. That is deliberate and argued at length in both files --
 * but the consequence is that a machine with NO secret store at all produces the very same result
 * XML as a machine with one: same test count, same zero failures, and `skipped` stays at 0 because
 * there is no `assumeTrue` anywhere. A green run therefore does not say whether anything was
 * measured. It cannot be read; it has to be told.
 *
 * THIS CLASS TELLS. It writes the two facts that decide how the other numbers are to be read --
 * whether a store was found, and which one -- to stdout and to `build/secretstore-probe.txt`, and
 * then it ASSERTS that a store was found where the platform is supposed to have one. That makes it
 * a check that can go red, which is what the other two cannot do.
 *
 * WHERE IT ASSERTS AND WHERE IT ONLY REPORTS:
 *
 *  * Windows and macOS: a store is DEMANDED. Both ship one with the operating system -- DPAPI is
 *    part of Windows, the Keychain part of macOS -- so "not available" there means the code is
 *    broken or the machine is set up wrongly, and either way the run must not pass quietly.
 *  * Linux and the BSDs: only REPORTED. A build machine without a session bus and without
 *    gnome-keyring is an ordinary Linux machine, not a broken one. Demanding a store there would
 *    turn a green build red for a reason that has nothing to do with this code.
 *
 * NO SECRET IS USED, READ OR WRITTEN HERE. [SecretStore.backendName] is documented never to be one,
 * and nothing else is touched.
 */
class SecretStoreProbeTest : TestCase() {

  fun testSayWhetherAnythingWasActuallyMeasured() {
    val osName = System.getProperty("os.name", "")
    val available = SecretStore.isAvailable
    val line = "SECRETSTORE-PROBE os.name=" + osName +
      " isAvailable=" + available +
      " backend=" + SecretStore.backendName +
      " chosenForThisOs=" + secretBackendsFor(osName).joinToString(",") { it.name }
    println(line)
    writeItWhereTheWorkflowCanFindIt(line)

    val needsOne = osName.startsWith("Windows") || osName.startsWith("Mac") || osName.startsWith("Darwin")
    if (needsOne) {
      assertTrue(
        "on " + osName + " the operating system ships a secret store, so isAvailable must be true." +
          " It is not, which means every other test in this area returned early and measured" +
          " NOTHING -- while still counting as passed. " + line,
        available)
      assertFalse("and then it has to be able to name it", "none" == SecretStore.backendName)
    }
  }

  /**
   * Best effort on purpose: the file is a convenience for the workflow that reads it, stdout is the
   * record. A test must not fail because a directory was not where it was expected.
   */
  private fun writeItWhereTheWorkflowCanFindIt(line: String) {
    try {
      val dir = File("build")
      if (dir.isDirectory || dir.mkdirs()) {
        File(dir, "secretstore-probe.txt").writeText(line + "\n")
      }
    } catch (e: Exception) {
      println("SECRETSTORE-PROBE could not be written to a file: " + e)
    }
  }
}
