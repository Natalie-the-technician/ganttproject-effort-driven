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
import net.sourceforge.ganttproject.parser.ResourceLoader
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Locale

/**
 * A4 — THE SETTING SURVIVES THE FILE, through both readers this program has and in the shape a
 * foreign GanttProject was measured to carry through.
 *
 * ═══ WHAT IS MEASURED HERE AND WHAT IS NOT ═══
 *
 * MEASURED HERE: fork writes → fork reads, with the desktop reader ([ResourceLoader]) and with the
 * cloud reader ([XmlProjectImporter]) separately, because the two are genuinely different code and
 * the report of 05.09.2026 found one property on which they disagree. And: the exact bytes that
 * go into the file, because a test that only reads its own model back would pass just as well if
 * the saver wrote nothing and the reader invented the answer.
 *
 * NOT MEASURED HERE: the trip through an unmodified upstream GanttProject. That needs a second
 * program and its own JVM per file, and it is a measurement rather than a test — it is in the
 * report of this session, with md5 sums, exactly as `2026-09-05-a4-projekteinstellung.md` did it.
 * What this file pins is the thing that measurement depends on: that the fork writes the shape
 * that was measured to survive, and no other.
 */
class HolidayWorkRoundTripTest {

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

  private fun saveToXml(project: GanttProjectImpl): String =
    ByteArrayOutputStream().also { GanttXMLSaver(project).save(it) }.toString(Charsets.UTF_8)

  /** A plan with one person, so that the file has a `<resources>` section with somebody in it. */
  private fun plan(schalter: Boolean?): GanttProjectImpl {
    val project = GanttProjectImpl()
    project.humanResourceManager.create("Natalie", 0).setValue(
      EffortDrivenProperties.findOrCreateResourceHours(project.resourceCustomPropertyManager), 8.0)
    if (schalter != null) {
      project.resourceCustomPropertyManager.setAllowHolidayWork(schalter)
    }
    return project
  }

  /** The desktop reader: the one the program itself uses when a person opens a file. */
  private fun ladenWieDasProgramm(xml: String): GanttProjectImpl {
    val ziel = GanttProjectImpl()
    ResourceLoader(ziel.humanResourceManager, ziel.roleManager, ziel.resourceCustomPropertyManager)
      .loadResources(parseXmlProject(xml))
    return ziel
  }

  /** The cloud reader: a second, independent path into the same model. */
  private fun ladenWieDieWolke(xml: String): GanttProjectImpl =
    XmlProjectImporter(GanttProjectImpl()).import(xml) as GanttProjectImpl

  // ═══ DER RUNDWEG ═══

  @Test
  fun `angeschaltet steht der schalter genau in der form in der datei, die den fremden lauf uebersteht`() {
    val xml = saveToXml(plan(true))
    assertTrue(
      Regex("<custom-property-definition id=\"$PROJECT_ALLOW_HOLIDAY_WORK\" name=\"[^\"]*\" " +
        "type=\"text\" default-value=\"true\"\\s*/>").containsMatchIn(xml),
      "die Einstellung steht nicht als Merkmalsdefinition mit `default-value` in der Datei -- " +
        "genau diese Form ist die gemessene, jede andere geht in einem fremden GanttProject " +
        "wortlos verloren:\n$xml")
  }

  @Test
  fun `angeschaltet kommt der schalter durch beide leser des programms zurueck`() {
    val xml = saveToXml(plan(true))
    assertTrue(ladenWieDasProgramm(xml).resourceCustomPropertyManager.allowsHolidayWork(),
      "der Leser des Schreibtischs bringt die Einstellung nicht mit")
    assertTrue(ladenWieDieWolke(xml).resourceCustomPropertyManager.allowsHolidayWork(),
      "der Leser der Wolke bringt die Einstellung nicht mit")
  }

  /**
   * AUS MUSS GENAUSO REISEN WIE AN. A place that only carries „on" cannot carry a switch: „off"
   * would then be indistinguishable from „this file has been through a program that dropped it",
   * and nobody could tell the two apart afterwards. The measurement of 05.09. checked the same
   * thing on the format side (case `m1`); this checks it on the program side.
   */
  @Test
  fun `ausgeschaltet reist der schalter genauso, als das wort false`() {
    val xml = saveToXml(plan(false))
    assertTrue(xml.contains("default-value=\"false\""), "AUS steht nicht in der Datei:\n$xml")
    assertFalse(ladenWieDasProgramm(xml).resourceCustomPropertyManager.allowsHolidayWork())
    assertFalse(ladenWieDieWolke(xml).resourceCustomPropertyManager.allowsHolidayWork())
    assertEquals("false",
      ladenWieDieWolke(xml).resourceCustomPropertyManager
        .getCustomPropertyDefinition(PROJECT_ALLOW_HOLIDAY_WORK)?.defaultValueAsString,
      "das Wort selbst muss ankommen, nicht nur die Antwort `nein`")
  }

  /**
   * EINE DATEI OHNE DEN SCHALTER LAEDT ALS AUS, NICHT ALS FEHLER — and it is every plan written
   * before today, so this is the ordinary case and not the exotic one.
   */
  @Test
  fun `eine datei ohne den schalter laedt als aus und beschwert sich nicht`() {
    val xml = saveToXml(plan(null))
    assertFalse(xml.contains(PROJECT_ALLOW_HOLIDAY_WORK),
      "wer die Einstellung nie angefasst hat, soll sie auch nicht in seiner Datei finden:\n$xml")
    assertFalse(ladenWieDasProgramm(xml).resourceCustomPropertyManager.allowsHolidayWork())
    assertFalse(ladenWieDieWolke(xml).resourceCustomPropertyManager.allowsHolidayWork())
  }

  /**
   * ZWEIMAL DURCH DIE DATEI, and the second trip is what shows there is no ratchet in the reading:
   * a reader that turned the definition into something slightly different each time would still
   * pass a single round trip.
   */
  @Test
  fun `zweimal speichern und laden aendert nichts mehr`() {
    val einmal = saveToXml(plan(true))
    val zweimal = saveToXml(ladenWieDieWolke(einmal))
    assertEquals(
      Regex("<custom-property-definition id=\"$PROJECT_ALLOW_HOLIDAY_WORK\"[^>]*/>")
        .find(einmal)?.value,
      Regex("<custom-property-definition id=\"$PROJECT_ALLOW_HOLIDAY_WORK\"[^>]*/>")
        .find(zweimal)?.value,
      "die Zeile muss beim zweiten Gang Zeichen fuer Zeichen dieselbe sein")
    assertTrue(ladenWieDieWolke(zweimal).resourceCustomPropertyManager.allowsHolidayWork())
  }

  /**
   * DER WERT TRAEGT KEINE SPRACHE. `true` and `false` are the two words, in every interface
   * language — the same guard the working week and the home office carry, and for the same reason:
   * one file, two machines, two languages.
   */
  @Test
  fun `der gespeicherte wert haengt nicht an der sprache der oberflaeche`() {
    listOf(Locale.GERMANY, Locale.US).forEach { locale ->
      val vorher = Locale.getDefault()
      try {
        Locale.setDefault(locale)
        assertTrue(saveToXml(plan(true)).contains("default-value=\"true\""),
          "unter $locale wird nicht `true` geschrieben")
      } finally {
        Locale.setDefault(vorher)
      }
    }
  }

  /**
   * KEINE PERSON TRAEGT DEN WERT. The setting is the DEFAULT of the definition and nothing else;
   * if it ever became a value on a person, a plan with two people could carry two answers and a
   * plan with none could carry none. Case `m6` of the measurement — the definition survives a
   * project with no people in it at all — is what makes this possible.
   */
  @Test
  fun `der schalter haengt an der definition und nicht an einer person`() {
    val xml = saveToXml(plan(true))
    assertFalse(xml.contains("<custom-property definition-id=\"$PROJECT_ALLOW_HOLIDAY_WORK\""),
      "die Einstellung darf an keiner Person haengen:\n$xml")

    val ohnePersonen = GanttProjectImpl()
    ohnePersonen.resourceCustomPropertyManager.setAllowHolidayWork(true)
    val leer = saveToXml(ohnePersonen)
    assertTrue(leer.contains("id=\"$PROJECT_ALLOW_HOLIDAY_WORK\""),
      "auch ein Projekt ganz ohne Personen muss die Einstellung tragen koennen:\n$leer")
    assertTrue(ladenWieDieWolke(leer).resourceCustomPropertyManager.allowsHolidayWork())
  }

  /**
   * NEBEN DEN MERKMALEN AUS A1 UND B1, weil sie in demselben Behaelter stehen. Case `m7` of the
   * measurement checked that the format carries both; this checks that the program does.
   */
  @Test
  fun `der schalter stoert die personenmerkmale nicht und sie ihn nicht`() {
    val project = GanttProjectImpl()
    val person = project.humanResourceManager.create("Natalie", 0)
    person.setValue(
      EffortDrivenProperties.findOrCreateResourceHours(project.resourceCustomPropertyManager), 8.0)
    person.setWorkWeek(project.resourceCustomPropertyManager,
      WorkWeekSchedule.parse("1,2,3,4,5,6").schedule)
    project.resourceCustomPropertyManager.setAllowHolidayWork(true)

    val geladen = ladenWieDieWolke(saveToXml(project))
    assertTrue(geladen.resourceCustomPropertyManager.allowsHolidayWork())
    assertEquals("1,2,3,4,5,6",
      geladen.humanResourceManager.getById(0)!!
        .workWeek(geladen.resourceCustomPropertyManager).schedule.toString(),
      "die Arbeitswoche der Person muss neben der Projekteinstellung unveraendert ankommen")
  }
}
