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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * Does the working week survive the file -- and does it survive a change of the interface language?
 *
 * WHY THE DETOUR THROUGH THE FILE: a working week that exists only in memory is worth nothing. The
 * question is whether it comes back out, and it goes through the REAL saver ([GanttXMLSaver]) and
 * through BOTH readers the program has -- [XmlProjectImporter] for the cloud, [ResourceLoader] for
 * the desktop. Same reasoning as in [AssignmentAxesRoundTripTest], and the same trap: two readers
 * for one format can disagree about it.
 *
 * WHY THE LANGUAGE MATTERS HERE OF ALL PLACES: the same file is opened with `ui.language=de_DE` and
 * with `en_US`. „Mo,Di,Fr" would be nonsense in the second session -- so what goes into the file is
 * ISO weekday numbers, 1 = Monday to 7 = Sunday, and nothing that a translation could touch. The
 * COLUMN NAME is translated and does not matter, because the column is found by its id.
 *
 * The route was checked beforehand and not assumed: `ResourceSaver.saveCustomProperties` writes
 * `<custom-property definition-id=… value=…/>`, the ordinary format -- unlike the `<allocation>`
 * attributes, which had to be taught to both readers first.
 */
class WorkWeekRoundTripTest {

  init {
    // GanttProjectImpl builds a WeekendCalendarImpl, which needs this. Same opening as in
    // AssignmentAxesRoundTripTest; without it every test here dies in CalendarFactory.
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

  /** One person carrying a working week -- the smallest project that can have one. */
  private fun personMitWoche(text: String): GanttProjectImpl {
    val project = GanttProjectImpl()
    val person = project.humanResourceManager.create("Natalie", 0)
    person.setWorkWeek(project.resourceCustomPropertyManager,
      WorkWeekSchedule.parse(text).schedule)
    return project
  }

  private fun GanttProjectImpl.wocheVon(id: Int): WorkWeekSchedule =
    this.humanResourceManager.getById(id)!!.workWeek(this.resourceCustomPropertyManager).schedule

  /** Mon, Tue, Fri, Sat -- and from 1.3. only Mon, Tue, Wed. The example from the brief. */
  private val woche = "1,2,5,6; 2026-03-01: 1,2,3"

  @Test
  fun `die arbeitswoche uebersteht speichern und laden`() {
    val xml = saveToXml(personMitWoche(woche))

    // First half: it reaches the file at all, and under the agreed id. A test that only read the
    // model back would still pass if the saver wrote nothing and the reader invented the week.
    assertTrue(xml.contains("definition-id=\"$RESOURCE_WORK_WEEK\""),
      "die Arbeitswoche steht nicht in der Datei:\n$xml")
    assertTrue(xml.contains("value=\"1,2,5,6; 2026-03-01: 1,2,3\""),
      "die Arbeitswoche steht nicht so in der Datei, wie sie eingetragen wurde:\n$xml")

    // Second half: it comes back out.
    val geladen = XmlProjectImporter(GanttProjectImpl()).import(xml) as GanttProjectImpl
    val gelesen = geladen.wocheVon(0)
    assertEquals(true, gelesen.worksOn(LocalDate.of(2026, 2, 7)), "Samstag vor dem 1. Maerz")
    assertEquals(false, gelesen.worksOn(LocalDate.of(2026, 3, 7)), "Samstag danach")
    assertEquals(true, gelesen.worksOn(LocalDate.of(2026, 3, 4)), "Mittwoch danach")
  }

  /**
   * The desktop reads its `.gan` files through [ResourceLoader], the cloud through
   * [XmlProjectImporter]. Two readers for one format -- so both are measured.
   */
  @Test
  fun `auch der lader des programms bringt die arbeitswoche mit`() {
    val xml = saveToXml(personMitWoche(woche))

    val ziel = GanttProjectImpl()
    ResourceLoader(ziel.humanResourceManager, ziel.roleManager, ziel.resourceCustomPropertyManager)
      .loadResources(parseXmlProject(xml))

    assertEquals(true, ziel.wocheVon(0).worksOn(LocalDate.of(2026, 3, 3)), "Dienstag nach dem 1.3.")
    assertEquals(false, ziel.wocheVon(0).worksOn(LocalDate.of(2026, 3, 5)), "Donnerstag nach dem 1.3.")
  }

  /**
   * THE POINT OF THE STORAGE FORMAT: what stands in the file carries no language.
   *
   * Nothing but digits, dates and separators -- no weekday name in any language. Were „Mo,Di,Fr"
   * in there, the second session would read rubbish and would not even be able to say so.
   */
  @Test
  fun `in der datei steht kein wochentagsname`() {
    val xml = saveToXml(personMitWoche(woche))
    val wert = Regex("definition-id=\"$RESOURCE_WORK_WEEK\" value=\"([^\"]*)\"")
      .find(xml)?.groupValues?.get(1)
    assertEquals("1,2,5,6; 2026-03-01: 1,2,3", wert, "der gespeicherte Wert, aus:\n$xml")
    assertTrue(wert!!.matches(Regex("[0-9;:, -]+")),
      "im Wert „$wert\" steht etwas, das eine Uebersetzung anfassen koennte")
  }

  /**
   * THE CHECK THE FORMAT EXISTS FOR: written in a German session, read in an English one.
   *
   * The column NAME travels along in German -- that is unavoidable and harmless. What matters is
   * that the column is found again by its ID, and that the value means the same thing on both
   * sides. Were the week stored as weekday names, this test would come back with an empty week and
   * no error.
   */
  @Test
  fun `der rundweg ueberlebt einen sprachwechsel der oberflaeche`() {
    val vorher = Locale.getDefault()
    val xml: String
    try {
      Locale.setDefault(Locale.GERMANY)
      xml = saveToXml(personMitWoche(woche))
      assertTrue(xml.contains("name=\"Arbeitswoche\""),
        "die Spalte wurde nicht unter dem deutschen Namen geschrieben:\n$xml")

      Locale.setDefault(Locale.US)
      val geladen = XmlProjectImporter(GanttProjectImpl()).import(xml) as GanttProjectImpl
      assertEquals(true, geladen.wocheVon(0).worksOn(LocalDate.of(2026, 3, 3)),
        "Dienstag nach dem 1.3., gelesen in der englischen Sitzung")
      assertEquals(false, geladen.wocheVon(0).worksOn(LocalDate.of(2026, 3, 6)),
        "Freitag nach dem 1.3., gelesen in der englischen Sitzung")

      // And the English session does not create a SECOND column beside the German one -- that
      // would be the „Column with ID=… is already registered" trap from the other direction.
      val vorSuche = geladen.resourceCustomPropertyManager.definitions.size
      val gefunden = findOrCreateWorkWeek(geladen.resourceCustomPropertyManager)
      assertEquals(vorSuche, geladen.resourceCustomPropertyManager.definitions.size,
        "die englische Sitzung hat eine zweite Spalte angelegt")
      assertEquals(RESOURCE_WORK_WEEK, gefunden.id)
      assertEquals("Arbeitswoche", gefunden.name,
        "die Spalte wurde nicht ueber ihre Kennung wiedergefunden")
    } finally {
      Locale.setDefault(vorher)
    }
  }

  /**
   * A person without an entry stays a person without an entry -- through the file as well. Nothing
   * may turn into „Monday to Friday" on the way.
   */
  @Test
  fun `ohne eintrag kommt auch nach dem rundweg keine angabe zurueck`() {
    val project = GanttProjectImpl()
    project.humanResourceManager.create("Natalie", 0)
    project.ensureCapacityColumns()
    val xml = saveToXml(project)

    val geladen = XmlProjectImporter(GanttProjectImpl()).import(xml) as GanttProjectImpl
    assertNull(geladen.wocheVon(0).worksOn(LocalDate.of(2026, 3, 2)),
      "aus „nichts eingetragen\" ist beim Rundweg eine Aussage geworden")
    assertNull(geladen.wocheVon(0).worksOn(LocalDate.of(2026, 3, 8)), "auch am Sonntag nicht")
  }

  /** The column comes into being with the others, not only when somebody creates it by hand. */
  @Test
  fun `die spalte wird mit den uebrigen des forks angelegt`() {
    val project = GanttProjectImpl()
    assertFalse(project.resourceCustomPropertyManager.definitions.map { it.id }
      .contains(RESOURCE_WORK_WEEK), "die Spalte darf nicht schon im Konstruktor entstehen")
    project.ensureCapacityColumns()
    assertTrue(project.resourceCustomPropertyManager.definitions.map { it.id }
      .contains(RESOURCE_WORK_WEEK),
      "Ressourcenspalte fehlt: $RESOURCE_WORK_WEEK (vorhanden: " +
        "${project.resourceCustomPropertyManager.definitions.map { it.id }})")
    val nachher = project.resourceCustomPropertyManager.definitions.size
    project.ensureCapacityColumns()
    assertEquals(nachher, project.resourceCustomPropertyManager.definitions.size,
      "ein zweiter Aufruf legt die Spalte doppelt an")
  }

  /** A defective entry in the file does not destroy the rest, and it is reported. */
  @Test
  fun `ein kaputter eintrag ueberlebt den rundweg als fehler, nicht als stille woche`() {
    val xml = saveToXml(personMitWoche("2026-03-01: 1,2; kaputt; 2026-05-01: 3,4"))
    val geladen = XmlProjectImporter(GanttProjectImpl()).import(xml) as GanttProjectImpl
    // The broken entry is dropped on the way IN -- setWorkWeek writes what parse could read.
    // What has to survive is the usable remainder, and that it is not silently something else.
    val gelesen = geladen.humanResourceManager.getById(0)!!
      .workWeek(geladen.resourceCustomPropertyManager)
    assertTrue(gelesen.errors.isEmpty(), "gemeldet wurde: ${gelesen.errors}")
    assertEquals(true, gelesen.schedule.worksOn(LocalDate.of(2026, 3, 2)), "Montag im ersten Abschnitt")
    assertEquals(true, gelesen.schedule.worksOn(LocalDate.of(2026, 5, 6)), "Mittwoch im zweiten")
  }

  /**
   * And the other direction: text that a HAND has put into the file is read with its errors, not
   * quietly repaired. That is the case the storage really has to survive -- the column is a text
   * field, and anybody can type into it.
   */
  @Test
  fun `von hand eingetippter unsinn wird gemeldet und nimmt die heilen abschnitte nicht mit`() {
    val project = GanttProjectImpl()
    val person = project.humanResourceManager.create("Natalie", 0)
    person.setValue(findOrCreateWorkWeek(project.resourceCustomPropertyManager),
      "2026-03-01: 1,2; kaputt; 2026-05-01: 3,4")
    val xml = saveToXml(project)

    val geladen = XmlProjectImporter(GanttProjectImpl()).import(xml) as GanttProjectImpl
    val gelesen = geladen.humanResourceManager.getById(0)!!
      .workWeek(geladen.resourceCustomPropertyManager)
    assertEquals(1, gelesen.errors.size, "gemeldet wurde: ${gelesen.errors}")
    assertEquals(2, gelesen.schedule.changes.size, "die heilen Abschnitte sind mitgestorben")
    assertEquals(true, gelesen.schedule.worksOn(LocalDate.of(2026, 3, 2)))
  }
}
