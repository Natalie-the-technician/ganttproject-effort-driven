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
import net.sourceforge.ganttproject.io.GanttXMLSaver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.DateFormat
import java.util.Locale

/**
 * Does a project file written BEFORE the two axes existed still load unchanged?
 *
 * The file used here is `ganttproject-builder/HouseBuildingSample.gan`, the sample project that
 * ships with GanttProject. It was last written by the ORIGINAL program (commit 4ca485ce2 of
 * 28.05.2024, "version 3309, full packages") and has therefore never seen a `blocking` or a
 * `no-effort` attribute. It carries 30 assignments, which is enough for the question to have
 * substance.
 *
 * Two things are measured:
 *
 *  * the file loads, and every one of its 30 assignments comes back with BOTH axes at `false`.
 *    `false`/`false` is what the program did before P1 -- an absence takes the person's hours out
 *    of the day without stopping the task, and everyone assigned contributes work. That is the
 *    whole reason axis B is stored negated: the value a missing attribute leaves behind is the
 *    old behaviour, not a value someone has to remember to write.
 *  * nothing ELSE about the file changed. Load, save, read the result back: the 30 allocations
 *    still name the same tasks, the same people, the same loads and the same coordinators.
 */
class AssignmentAxesLegacyFileTest {

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

  /**
   * The sample file, taken from the repository rather than copied into the test resources: a copy
   * would be a file written by THIS session, and the whole point is a file that is older than the
   * change.
   *
   * Gradle runs the tests with the module directory as the working directory. Both candidates are
   * tried so that a run from the repository root finds the file too, and a miss says out loud
   * where it looked.
   */
  private fun sampleFile(): File {
    val candidates = listOf(
      File("../ganttproject-builder/HouseBuildingSample.gan"),
      File("ganttproject-builder/HouseBuildingSample.gan"))
    return candidates.firstOrNull { it.isFile }
      ?: throw AssertionError(
        "die Beispieldatei ist nicht auffindbar, gesucht in: " +
          candidates.joinToString { it.absolutePath })
  }

  private fun saveToXml(project: GanttProjectImpl): String {
    val out = ByteArrayOutputStream()
    GanttXMLSaver(project).save(out)
    return out.toString(Charsets.UTF_8)
  }

  /** (task, person, load, coordinator) -- everything an allocation carried before P1. */
  private fun allocationsOf(xml: String) =
    parseXmlProject(xml).allocations
      .map { listOf(it.taskId, it.resourceId, it.load, it.isCoordinator) }
      .toSet()

  @Test
  fun `die alte datei traegt die neuen angaben nicht`() {
    // Precondition, and it is the point of the whole test: without it the two assertions below
    // would still pass on a file this session had written itself.
    val text = sampleFile().readText(Charsets.UTF_8)
    assertFalse(text.contains("blocking="), "die Beispieldatei ist nicht mehr die alte")
    assertFalse(text.contains("no-effort="), "die Beispieldatei ist nicht mehr die alte")
    assertEquals(30, Regex("<allocation ").findAll(text).count(),
      "die Beispieldatei hat nicht mehr die 30 Zuordnungen, gegen die hier gemessen wird")
  }

  @Test
  fun `eine alte datei laedt mit den vorgabewerten des heutigen verhaltens`() {
    val geladen = XmlProjectImporter(GanttProjectImpl())
      .import(sampleFile().readText(Charsets.UTF_8)) as GanttProjectImpl

    val zuordnungen = geladen.taskManager.tasks.flatMap { it.assignments.asList() }
    assertEquals(30, zuordnungen.size,
      "beim Laden sind Zuordnungen verlorengegangen oder dazugekommen")

    zuordnungen.forEach {
      assertFalse(it.isBlocking,
        "die Abwesenheit von ${it.resource.name} blockiert \"${it.task.name}\" -- " +
          "das steht so nicht in der Datei und ist nicht das bisherige Verhalten")
      assertFalse(it.isNoEffort,
        "${it.resource.name} leistet an \"${it.task.name}\" nichts -- " +
          "das steht so nicht in der Datei und ist nicht das bisherige Verhalten")
    }
  }

  @Test
  fun `die alte datei geht unveraendert durch laden und speichern`() {
    val original = sampleFile().readText(Charsets.UTF_8)
    val geladen = XmlProjectImporter(GanttProjectImpl()).import(original) as GanttProjectImpl
    val wieder = saveToXml(geladen)

    assertEquals(allocationsOf(original), allocationsOf(wieder),
      "die Zuordnungen der alten Datei haben den Rundlauf nicht unveraendert ueberstanden")

    // Counter-check on the new attributes: they ARE written now, and both at false. Without this
    // the test above would also pass if the saver had quietly stopped writing them.
    val neu = parseXmlProject(wieder).allocations
    assertEquals(30, neu.size)
    assertTrue(neu.all { !it.isBlocking && !it.isNoEffort },
      "die neuen Angaben stehen nach dem Speichern nicht auf dem heutigen Verhalten")
  }
}
