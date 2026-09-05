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
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * W11 — THE RULE SURVIVES SAVING AND LOADING, and it is the CROSS of B1 and B2 that is measured
 * here rather than either half on its own.
 *
 * B1 has its round trip (`HomeOfficeStorageTest`) and B2 has its own, including the one that
 * matters most — all three states of the mark stay distinguishable (`TaskHomeWorkRoundTripTest`).
 * Neither of them ever puts a home office and a marking in ONE file, and neither asks the only
 * question B3 cares about afterwards: does the RULE still fire on the plan that came back?
 *
 * A file whose two halves each survive but which computes a different duration after a round trip
 * would pass both of the existing checks. That is the gap this file closes.
 *
 * ═══ WHY IT IS THE `<custom-property>` PATH AND NOT `<vacation>` ═══
 *
 * B1 stores the home office as a resource property, and it has to. `<vacation>` is read as a
 * HOLIDAY by every GanttProject there is, so a home-working day stored there would come back from a
 * trip through a foreign copy of the program as a free day — the mistake this package exists to
 * avoid, in the only form that outlives the session. This file therefore also asserts that nothing
 * B3 touches has put a `<vacation>` into the file.
 *
 * WHAT IS NOT MEASURED HERE: the trip through a FOREIGN GanttProject. That needs a second program
 * and a person to click in it; `AssignmentAxesOldProgramTest` is what such a measurement looks like
 * when it has been made, and no such measurement exists for these two properties. It is named in
 * the report as unmeasured rather than assumed.
 */
class HomeWorkRoundTripTest {

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

  private val montag = LocalDate.of(2026, 9, 7)
  private val mittwoch = montag.plusDays(2)

  private fun saveToXml(project: GanttProjectImpl): String =
    ByteArrayOutputStream().also { GanttXMLSaver(project).save(it) }.toString(Charsets.UTF_8)

  /**
   * A whole small plan: one person at home on the Wednesday, one task that needs her on site, and
   * one that does not. Both carry the same effort and the same assignment, so any difference
   * between them afterwards is the marking and nothing else.
   */
  private fun plan(): GanttProjectImpl {
    val project = GanttProjectImpl()
    val person = project.humanResourceManager.create("Natalie", 0)
    person.setValue(
      EffortDrivenProperties.findOrCreateResourceHours(project.resourceCustomPropertyManager), 8.0)
    person.setHomeOfficePeriods(project.resourceCustomPropertyManager,
      HomeOfficePeriods.parse(mittwoch.toString()).periods)

    listOf("Abnahme" to HomeWorkMark.ON_SITE, "Auswertung" to HomeWorkMark.NOT_DECIDED)
      .forEach { (name, mark) ->
        val task = project.taskManager.newTaskBuilder().withName(name)
          .withStartDate(montag.toModelDate())
          .withDuration(project.taskManager.createLength(1)).build()
        task.customValues.setValue(
          EffortDrivenProperties.findOrCreateTaskEffort(project.taskManager.customPropertyManager),
          40.0)
        task.assignmentCollection.addAssignment(person).apply {
          load = 100f
          isBlocking = true
        }
        findOrCreateOnSiteOnly(project.taskManager.customPropertyManager)
        applyHomeWorkMark(task.customValues, project.taskManager.customPropertyManager, mark)
      }
    return project
  }

  private fun GanttProjectImpl.dauerVon(name: String): Int? {
    val task = this.taskManager.tasks.single { it.name == name }
    return task.durationDaysWithDaysOff(
      this.taskManager.customPropertyManager, this.resourceCustomPropertyManager, montag,
      workingDayTest(this.taskManager.calendar))
  }

  /**
   * THE MEASUREMENT: the same two durations before and after the trip through the file.
   *
   * Six for the marked task and five for the unmarked one — the same pair on both sides. A file
   * that lost the home office would answer five twice; one that lost the marking would answer five
   * twice as well; one that lost both halves of B1 in different ways could answer anything. The
   * pair is what pins it down, not either number alone.
   */
  @Test
  fun `heimarbeit und markierung wirken nach dem rundweg genauso`() {
    val vorher = plan()
    assertEquals(6, vorher.dauerVon("Abnahme"),
      "Vorbedingung vor dem Speichern: der markierte Vorgang verliert den Mittwoch")
    assertEquals(5, vorher.dauerVon("Auswertung"),
      "Vorbedingung vor dem Speichern: der unmarkierte behaelt ihn")

    val xml = saveToXml(vorher)
    val nachher = XmlProjectImporter(GanttProjectImpl()).import(xml) as GanttProjectImpl

    assertEquals(6, nachher.dauerVon("Abnahme"),
      "nach Speichern und Laden muss die Regel genauso feuern wie vorher:\n$xml")
    assertEquals(5, nachher.dauerVon("Auswertung"),
      "und der unmarkierte Vorgang muss unveraendert bleiben:\n$xml")
  }

  /**
   * BOTH HALVES ARE REALLY IN THE FILE, and neither of them in the shape of a holiday.
   *
   * Measured against the file text and not only against the model that came back: a check that read
   * the model alone would still pass if the saver wrote nothing and the reader invented the answer.
   */
  @Test
  fun `beide eigenschaften stehen in der datei, und keine als urlaub`() {
    val xml = saveToXml(plan())

    assertTrue(xml.contains("id=\"$RESOURCE_HOME_OFFICE_PERIODS\""),
      "die Heimarbeits-Spalte der Person fehlt in der Datei:\n$xml")
    assertTrue(xml.contains("id=\"$TASK_ON_SITE_ONLY\""),
      "die Markierungs-Spalte des Vorgangs fehlt in der Datei:\n$xml")
    assertTrue(xml.contains(mittwoch.toString()),
      "der Heimarbeitstag selbst steht nicht in der Datei:\n$xml")

    // THE ONE THING THAT MUST NOT BE THERE. A home-office day written as a `<vacation>` would be
    // read as a free day by every GanttProject there is, this fork included on the next load.
    //
    // `"<vacation "` WITH THE SPACE, and the empty container asserted separately. The saver always
    // writes the `<vacations/>` element, empty or not; a check for the bare prefix matched that
    // container and was red on a correct file. Measured on 05.09.2026, and kept as a note because
    // the loose form looks more careful than it is.
    assertFalse(xml.contains("<vacation "),
      "die Heimarbeit darf NICHT als Ausfallzeit gespeichert sein -- dort gelesen waere sie " +
        "ausserhalb dieses Forks ein freier Tag:\n$xml")
    assertTrue(xml.contains("<vacations/>"),
      "und der Ausfallzeit-Behaelter muss LEER sein; steht hier etwas anderes, hat sich die " +
        "Speicherform geaendert und die Pruefung darueber ist stumpf geworden:\n$xml")
  }

  /**
   * AND THE PLAN READS BACK THROUGH THE DESKTOP'S OWN LOADER TOO.
   *
   * Two readers exist and they are different code — `XmlProjectImporter` (used above and by the
   * cloud) and the desktop's task loader. `TaskHomeWorkRoundTripTest` measures both for the mark
   * alone; here the point is only that the parse of the whole plan does not fall over on a file
   * carrying both properties at once, and that the mark still arrives.
   */
  @Test
  fun `der lader des programms liest die markierung aus derselben datei`() {
    val xml = saveToXml(plan())
    val geparst = parseXmlProject(xml)
    val markierungen = geparst.tasks.tasks.orEmpty().map { it.name }
    assertEquals(listOf("Abnahme", "Auswertung"), markierungen,
      "beide Vorgaenge muessen im geparsten Baum stehen:\n$xml")

    val geladen = XmlProjectImporter(GanttProjectImpl()).import(xml) as GanttProjectImpl
    val abnahme = geladen.taskManager.tasks.single { it.name == "Abnahme" }
    val auswertung = geladen.taskManager.tasks.single { it.name == "Auswertung" }
    assertEquals(HomeWorkMark.ON_SITE,
      abnahme.homeWorkMark(geladen.taskManager.customPropertyManager),
      "die Markierung ist auf dem Weg verlorengegangen:\n$xml")
    assertEquals(HomeWorkMark.NOT_DECIDED,
      auswertung.homeWorkMark(geladen.taskManager.customPropertyManager),
      "und der unentschiedene Vorgang darf nicht entschieden zurueckkommen:\n$xml")
    assertTrue(
      geladen.humanResourceManager.getById(0)!!
        .worksFromHome(geladen.resourceCustomPropertyManager, mittwoch),
      "die Heimarbeit der Person ist auf dem Weg verlorengegangen:\n$xml")
  }
}
