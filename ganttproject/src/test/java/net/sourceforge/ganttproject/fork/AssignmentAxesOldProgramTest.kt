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

import biz.ganttproject.core.io.XmlProjectImporter
import biz.ganttproject.core.io.parseXmlProject
import biz.ganttproject.core.time.CalendarFactory
import net.sourceforge.ganttproject.GanttProjectImpl
import net.sourceforge.ganttproject.parser.AllocationTagHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.util.Locale

/**
 * P4: what happens when a file with the two axes meets a GanttProject that does not know them?
 *
 * MEASURED ON 27.08.2026, not derived from the source. An original GanttProject was built from
 * commit 08564e556 (`/opt/gantt-stock`, distribution `/mnt/data/cu-shots/stock-dist`, zero classes
 * under `net/sourceforge/ganttproject/fork/` in its jar) and the four files this fork writes --
 * `blocking`/`no-effort` in all four combinations -- were opened in it, looked at and saved again.
 * The result, for all four:
 *
 *  * it starts, opens the file and says nothing. No error, no warning, no dialog.
 *  * task, person, load and coordinator arrive unchanged.
 *  * ON SAVING BOTH ATTRIBUTES ARE GONE. The original reads `<allocation>` into a record that has
 *    no field for them, and writes its own fixed list of five attributes back out. Nobody is told.
 *
 * The last point is a REAL LOSS OF DATA, and this test is where it is written down: a project that
 * travels through an old program comes back with both axes on `false`. It is quiet, and it is
 * survivable -- `false`/`false` is the behaviour of the program before P1, so what comes back is a
 * correct project, just one that has forgotten two answers.
 *
 * WHAT THIS TEST CAN AND CANNOT DO. It cannot run the old program: those classes are not on this
 * fork's classpath. So it works on the two ends of the measurement instead, and both are pinned
 * against the FILES THAT THE MEASUREMENT ACTUALLY PRODUCED, checked in beside it:
 *
 *   p4-vom-fork-geschrieben.gan   what this fork wrote (`blocking="true" no-effort="true"`)
 *   p4-vom-original-zurueck.gan   the same project after the original had saved it
 *
 * And it pins the mechanism the tolerance rests on -- an attribute the reader does not know is
 * skipped, not a reason to fail -- by feeding this fork's own two readers an `<allocation>` with a
 * made-up attribute. That is the same code path the original walks when it meets `blocking`:
 * `FAIL_ON_UNKNOWN_PROPERTIES` is off in [biz.ganttproject.core.io.parseXmlProject] and has been
 * since long before this fork. It is a stand-in, not the old program itself, and the reason it is
 * one is written here so nobody mistakes it for the measurement.
 */
class AssignmentAxesOldProgramTest {

  init {
    object : CalendarFactory() {
      init {
        setLocaleApi(object : CalendarFactory.LocaleApi {
          override fun getLocale(): Locale = Locale.GERMANY
          override fun getShortDateFormat(): DateFormat =
            DateFormat.getDateInstance(DateFormat.SHORT, Locale.GERMANY)
        })
      }
    }
  }

  private fun fixture(name: String): String =
    AssignmentAxesOldProgramTest::class.java.getResourceAsStream("/$name")
      ?.readBytes()?.toString(Charsets.UTF_8)
      ?: throw AssertionError("die gemessene Datei $name liegt nicht im Testpfad")

  /** (task, person, load, coordinator) -- everything an allocation carried before P1. */
  private fun allocationsOf(xml: String) =
    parseXmlProject(xml).allocations
      .map { listOf(it.taskId, it.resourceId, it.load, it.isCoordinator) }
      .toSet()

  /**
   * The two files are what they claim to be. Without this the two tests below would still pass on
   * a pair of files that had quietly become identical.
   */
  @Test
  fun `die gemessenen dateien sind die gemessenen dateien`() {
    val vomFork = fixture("p4-vom-fork-geschrieben.gan")
    assertTrue(vomFork.contains("blocking=\"true\""),
      "die Fork-Datei traegt A nicht mehr, damit misst der Rest hier nichts")
    assertTrue(vomFork.contains("no-effort=\"true\""),
      "die Fork-Datei traegt B nicht mehr, damit misst der Rest hier nichts")
    assertEquals(3, Regex("<allocation ").findAll(vomFork).count(),
      "die Fork-Datei hat nicht mehr die drei Zuordnungen, gegen die hier gemessen wird")
  }

  /**
   * The forward direction, and the finding: the old program throws both attributes away.
   *
   * The file on the right-hand side was written BY THE ORIGINAL, in the running program, from the
   * file on the left. Everything an allocation carried before P1 is identical between the two --
   * that is the good half. The two attributes are simply not there any more -- that is the loss.
   */
  @Test
  fun `das original wirft die zwei angaben beim speichern weg`() {
    val vomFork = fixture("p4-vom-fork-geschrieben.gan")
    val zurueck = fixture("p4-vom-original-zurueck.gan")

    assertFalse(zurueck.contains("blocking="),
      "das Original hat A doch behalten -- dann ist der Befund hier ueberholt")
    assertFalse(zurueck.contains("no-effort="),
      "das Original hat B doch behalten -- dann ist der Befund hier ueberholt")

    assertEquals(allocationsOf(vomFork), allocationsOf(zurueck),
      "das Original hat mehr verloren als die zwei Angaben")
  }

  /**
   * And the fork can read what comes back. The values are gone, so the file says what a file
   * before P1 said, and it has to load exactly like one: absence does not block, everyone
   * contributes.
   */
  @Test
  fun `was vom original zurueckkommt laedt mit den vorgabewerten`() {
    val geladen = XmlProjectImporter(GanttProjectImpl())
      .import(fixture("p4-vom-original-zurueck.gan")) as GanttProjectImpl

    val zuordnungen = geladen.taskManager.tasks.flatMap { it.assignments.asList() }
    assertEquals(3, zuordnungen.size, "beim Laden sind Zuordnungen verlorengegangen")
    zuordnungen.forEach {
      assertFalse(it.isBlocking, "A steht nicht auf dem bisherigen Verhalten")
      assertFalse(it.isNoEffort, "B steht nicht auf dem bisherigen Verhalten")
    }
  }

  /**
   * The mechanism, measured on a stand-in: an attribute at `<allocation>` that the reader has no
   * field for is skipped. `zukunfts-achse` is invented for this test and no program knows it --
   * exactly the position `blocking` is in when the original meets it.
   *
   * Both readers, because the program has two: the desktop reads through [AllocationTagHandler],
   * the cloud through [XmlProjectImporter].
   */
  @Test
  fun `ein unbekanntes merkmal an der zuordnung wirft keinen der zwei leser`() {
    val original = fixture("p4-vom-fork-geschrieben.gan")
    val mitFremdem = original.replace(
      "<allocation task-id=", "<allocation zukunfts-achse=\"true\" task-id=")
    assertEquals(3, Regex("zukunfts-achse").findAll(mitFremdem).count(),
      "das fremde Merkmal ist nicht in der Datei gelandet")

    // Cloud reader.
    val ueberImporter = XmlProjectImporter(GanttProjectImpl())
      .import(mitFremdem) as GanttProjectImpl
    assertEquals(3, ueberImporter.taskManager.tasks.flatMap { it.assignments.asList() }.size,
      "der Importer hat am fremden Merkmal Zuordnungen verloren")

    // Desktop reader. Tasks and people first, otherwise the allocations have nothing to attach to.
    val ziel = XmlProjectImporter(GanttProjectImpl()).import(original) as GanttProjectImpl
    AllocationTagHandler(ziel.humanResourceManager, ziel.taskManager, ziel.roleManager)
      .process(parseXmlProject(mitFremdem))

    // The known attributes still arrive, the fork's own two included -- the reader skipped the one
    // attribute it did not know and nothing else.
    assertEquals(allocationsOf(original), allocationsOf(mitFremdem),
      "das fremde Merkmal hat die uebrigen Werte verschoben")
    val gesetzte = parseXmlProject(mitFremdem).allocations.filter { it.isBlocking && it.isNoEffort }
    assertEquals(1, gesetzte.size,
      "die zwei Achsen sind neben dem fremden Merkmal nicht mehr lesbar")
  }
}
