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
import net.sourceforge.ganttproject.parser.AllocationTagHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * Can one assignment carry the two axes that P1 introduces, and do they survive the file?
 *
 *   A  Does this person's ABSENCE block the task?          -> attribute `blocking`
 *   B  Does this person contribute WORK that counts
 *      towards the effort?                                  -> attribute `no-effort` (negated)
 *
 * The two are independent of each other, so they are two attributes and not one enum.
 *
 * WHY THE DETOUR THROUGH THE FILE and not two setters on an object built by hand: an assignment
 * that carries the values only in memory is worth nothing. The question is whether the values
 * survive saving and loading. This test therefore goes through the real saver
 * ([GanttXMLSaver]) and through BOTH readers the program has -- the same code that runs when a
 * user saves and reopens a project.
 *
 * BEFORE P1 THE FIRST TEST FAILED, measured on 27.08.2026 with the two attributes put into the
 * file by hand:
 *
 *     AssignmentAxesRoundTripTest > die zwei angaben ueberstehen speichern und laden() FAILED
 *         org.opentest4j.AssertionFailedError: A ist auf dem Weg durch Laden und Speichern
 *         verlorengegangen:
 *         ...
 *         <allocation task-id="0" resource-id="0" function="Default:0" responsible="false"
 *                     load="100.0"/>
 *         ...
 *          ==> expected: <true> but was: <false>
 *
 * Both readers went through `<allocation>` attribute by attribute and dropped everything they did
 * not know, and the saver wrote a fixed list of five attributes. The values went into the file and
 * did not come out again.
 *
 * That an OLD file still loads unchanged is a separate question and is measured in
 * [AssignmentAxesLegacyFileTest] against a file written before this change.
 */
class AssignmentAxesRoundTripTest {

  init {
    // GanttProjectImpl builds a WeekendCalendarImpl, which needs this. Same pattern as
    // BaselineRoundTripTest; without it every test here dies with a NullPointerException in
    // CalendarFactory.
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

  private fun saveToXml(project: GanttProjectImpl): String {
    val out = ByteArrayOutputStream()
    GanttXMLSaver(project).save(out)
    return out.toString(Charsets.UTF_8)
  }

  /** One person, one task -- the smallest project that can have an `<allocation>`. */
  private fun personUndVorgang(): GanttProjectImpl {
    val project = GanttProjectImpl()
    project.humanResourceManager.create("Natalie", 0)
    project.taskManager.newTaskBuilder()
      .withName("Vorgang")
      .withStartDate(LocalDate.of(2026, 8, 27).toModelDate())
      .withDuration(project.taskManager.createLength(5))
      .build()
    return project
  }

  private fun mitZuordnung(blocking: Boolean, noEffort: Boolean): GanttProjectImpl {
    val project = personUndVorgang()
    val resource = project.humanResourceManager.getById(0)
    val assignment = project.taskManager.tasks.single().assignmentCollection.addAssignment(resource)
    assignment.load = 100f
    assignment.isBlocking = blocking
    assignment.isNoEffort = noEffort
    return project
  }

  @Test
  fun `die zwei angaben ueberstehen speichern und laden`() {
    val xml = saveToXml(mitZuordnung(blocking = true, noEffort = true))

    // First half: the values reach the file at all, and under the agreed names. A test that only
    // read the model back would still pass if the saver wrote nothing and the reader invented the
    // values.
    assertTrue(xml.contains("blocking=\"true\""), "A steht nicht in der Datei:\n$xml")
    assertTrue(xml.contains("no-effort=\"true\""), "B steht nicht in der Datei:\n$xml")

    // Second half: they come back out of the file.
    val geladen = XmlProjectImporter(GanttProjectImpl()).import(xml) as GanttProjectImpl
    val zuordnung = geladen.taskManager.tasks.single().assignments.single()
    assertTrue(zuordnung.isBlocking, "A ist beim Laden verlorengegangen")
    assertTrue(zuordnung.isNoEffort, "B ist beim Laden verlorengegangen")
    assertEquals(100f, zuordnung.load, "die Zuordnung selbst ist nicht heil angekommen")
  }

  /**
   * The desktop reads its `.gan` files through [AllocationTagHandler], the cloud through
   * [XmlProjectImporter]. Two readers for one file format -- so both are measured, otherwise the
   * two halves of the program could disagree about the same file.
   */
  @Test
  fun `auch der lader des programms bringt die zwei angaben mit`() {
    val xml = saveToXml(mitZuordnung(blocking = true, noEffort = true))

    val ziel = personUndVorgang()
    AllocationTagHandler(ziel.humanResourceManager, ziel.taskManager, ziel.roleManager)
      .process(parseXmlProject(xml))

    val zuordnung = ziel.taskManager.tasks.single().assignments.single()
    assertTrue(zuordnung.isBlocking, "A fehlt nach dem Laden ueber AllocationTagHandler")
    assertTrue(zuordnung.isNoEffort, "B fehlt nach dem Laden ueber AllocationTagHandler")
  }

  /**
   * The two are two questions, not one. If they were coupled -- one enum, or one attribute read
   * into both -- a file with only one of them set would come back with both.
   */
  @Test
  fun `die zwei angaben sind voneinander unabhaengig`() {
    val nurA = XmlProjectImporter(GanttProjectImpl())
      .import(saveToXml(mitZuordnung(blocking = true, noEffort = false))) as GanttProjectImpl
    val a = nurA.taskManager.tasks.single().assignments.single()
    assertTrue(a.isBlocking, "A wurde nicht gehalten")
    assertFalse(a.isNoEffort, "B wurde mitgesetzt, obwohl nur A gesetzt war")

    val nurB = XmlProjectImporter(GanttProjectImpl())
      .import(saveToXml(mitZuordnung(blocking = false, noEffort = true))) as GanttProjectImpl
    val b = nurB.taskManager.tasks.single().assignments.single()
    assertFalse(b.isBlocking, "A wurde mitgesetzt, obwohl nur B gesetzt war")
    assertTrue(b.isNoEffort, "B wurde nicht gehalten")
  }

  /**
   * A freshly created assignment has to behave like every assignment that existed before P1:
   * absence does not block, and the person contributes. Both are `false`, and `false` is the plain
   * Java default of the fields -- nothing has to remember to set it.
   */
  @Test
  fun `eine neue zuordnung steht auf dem heutigen verhalten`() {
    val project = personUndVorgang()
    val zuordnung = project.taskManager.tasks.single().assignmentCollection
      .addAssignment(project.humanResourceManager.getById(0))
    assertFalse(zuordnung.isBlocking, "eine neue Zuordnung blockiert, das ist nicht das bisherige Verhalten")
    assertFalse(zuordnung.isNoEffort, "eine neue Zuordnung leistet nichts, das ist nicht das bisherige Verhalten")
  }
}
