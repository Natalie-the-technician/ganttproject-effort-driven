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

import biz.ganttproject.core.calendar.GanttDaysOff
import biz.ganttproject.core.io.XmlProjectImporter
import biz.ganttproject.core.io.parseXmlProject
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.core.time.GanttCalendar
import net.sourceforge.ganttproject.GanttProjectImpl
import net.sourceforge.ganttproject.io.GanttXMLSaver
import net.sourceforge.ganttproject.parser.ResourceLoader
import net.sourceforge.ganttproject.resource.HumanResource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * A vacation whose `start` equals its `end`.
 *
 * The end of a vacation interval is EXCLUSIVE — that is decided, and it is what the surrounding
 * code already did before this branch. Read that way, `start == end` is ZERO days of absence: the
 * plan is computed as if the vacation were not in the file, and nothing says a word about it.
 *
 * Such a file exists in the wild. The comment in `ResourceLoader` shows exactly that shape,
 * `<vacation start="2005-04-14" end="2005-04-14" resourceid="0"/>`, and a file written by hand or
 * by an older version can carry it.
 *
 * The decision measured here: a zero-day vacation means the one day it names, so it is loaded as
 * `[d, d+1)`.
 *
 * BOTH READERS ARE MEASURED SEPARATELY. The desktop reads its `.gan` files through
 * [ResourceLoader], the cloud through [XmlProjectImporter] — two readers for one file format, each
 * of which builds the [GanttDaysOff] itself. A test that only covered one of them would leave half
 * the program reading the same file differently.
 *
 * The three questions each reader is asked:
 *
 *  1. `start == end` becomes one day.
 *  2. `start < end` is NOT touched — no shift by a day. That would be the worst side effect of
 *     this change, and it is the one worth guarding hardest: it would move every vacation in every
 *     existing file.
 *  3. Load, save, load again gives the same thing.
 */
class VacationZeroDaysTest {

  init {
    // GanttProjectImpl builds a WeekendCalendarImpl, which needs this. Same pattern as
    // AssignmentAxesRoundTripTest; without it every test here dies with a NullPointerException in
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

  private val montag: LocalDate = LocalDate.of(2026, 9, 7)
  private val donnerstag: LocalDate = LocalDate.of(2026, 9, 10)

  private fun saveToXml(project: net.sourceforge.ganttproject.GanttProjectImpl): String {
    val out = ByteArrayOutputStream()
    GanttXMLSaver(project).save(out)
    return out.toString(Charsets.UTF_8)
  }

  /**
   * A file with one person and one vacation, written by the real saver and then edited in the one
   * attribute this test is about. Going through the saver rather than pasting a whole `.gan`
   * literal keeps the file valid in every other respect, so a failure can only be about the
   * vacation.
   */
  private fun dateiMitUrlaub(von: LocalDate, bisExklusiv: LocalDate): String {
    val project = GanttProjectImpl()
    val person = project.humanResourceManager.create("Natalie", 0)
    person.addDaysOff(
      GanttDaysOff(GanttCalendar.fromLocalDate(von), GanttCalendar.fromLocalDate(bisExklusiv)))
    val xml = saveToXml(project)
    assertTrue(xml.contains("<vacation "), "der Speicherer hat gar keinen Urlaub geschrieben:\n$xml")
    return xml
  }

  /** The file this whole test is about: `start` and `end` naming the same day. */
  private fun dateiMitNulltage(tag: LocalDate): String {
    // Written with a real one-day interval and then shortened to zero, so that everything else in
    // the file is exactly what the program itself writes.
    val xml = dateiMitUrlaub(tag, tag.plusDays(1))
    val nulltage = xml.replace("end=\"${tag.plusDays(1)}\"", "end=\"$tag\"")
    assertTrue(nulltage.contains("start=\"$tag\" end=\"$tag\""),
      "die Testdatei sagt nicht start == end, gegen die ganze Pruefung nichts wert waere:\n$nulltage")
    return nulltage
  }

  /** The desktop reader -- the one `ResourceTagHandler` uses when a `.gan` file is opened. */
  private fun ladeUeberResourceLoader(xml: String): GanttProjectImpl {
    val project = GanttProjectImpl()
    ResourceLoader(project.humanResourceManager, project.roleManager,
      project.resourceCustomPropertyManager)
      .loadResources(parseXmlProject(xml))
    return project
  }

  /** The cloud reader. */
  private fun ladeUeberXmlProjectImporter(xml: String): GanttProjectImpl =
    XmlProjectImporter(GanttProjectImpl()).import(xml) as GanttProjectImpl

  private fun person(project: GanttProjectImpl): HumanResource =
    project.humanResourceManager.getById(0)
      ?: throw AssertionError("die Person ist beim Laden gar nicht angekommen")

  /**
   * The days this person is actually absent, walked out of the half-open ranges the rest of the
   * fork reads (`daysOffRanges`, the same function the duration computation uses). This is the
   * number that matters: an interval that covers no day changes no plan.
   */
  private fun abwesendeTage(resource: HumanResource): List<LocalDate> =
    resource.daysOffRanges().flatMap { (von, bisExklusiv) ->
      generateSequence(von) { it.plusDays(1) }.takeWhile { it.isBefore(bisExklusiv) }.toList()
    }

  // --- 1. start == end becomes one day ------------------------------------------------------

  @Test
  fun `ein urlaub ohne laenge wird beim laden ein tag - desktop-lader`() {
    val person = person(ladeUeberResourceLoader(dateiMitNulltage(donnerstag)))
    assertEquals(listOf(donnerstag), abwesendeTage(person),
      "ResourceLoader: aus start == end ist kein Tag Abwesenheit geworden")
  }

  @Test
  fun `ein urlaub ohne laenge wird beim laden ein tag - cloud-lader`() {
    val person = person(ladeUeberXmlProjectImporter(dateiMitNulltage(donnerstag)))
    assertEquals(listOf(donnerstag), abwesendeTage(person),
      "XmlProjectImporter: aus start == end ist kein Tag Abwesenheit geworden")
  }

  // --- 2. a real vacation is not moved ------------------------------------------------------

  @Test
  fun `ein echter urlaub wird beim laden nicht verschoben - desktop-lader`() {
    val person = person(ladeUeberResourceLoader(dateiMitUrlaub(montag, donnerstag)))
    assertEquals(listOf(montag, montag.plusDays(1), montag.plusDays(2)), abwesendeTage(person),
      "ResourceLoader: der Urlaub Mo-Do (exklusiv, drei Tage) ist beim Laden verrutscht")
    assertEquals(montag to donnerstag, person.daysOffRanges().single(),
      "ResourceLoader: die Grenzen des Urlaubs sind nicht mehr die der Datei")
  }

  @Test
  fun `ein echter urlaub wird beim laden nicht verschoben - cloud-lader`() {
    val person = person(ladeUeberXmlProjectImporter(dateiMitUrlaub(montag, donnerstag)))
    assertEquals(listOf(montag, montag.plusDays(1), montag.plusDays(2)), abwesendeTage(person),
      "XmlProjectImporter: der Urlaub Mo-Do (exklusiv, drei Tage) ist beim Laden verrutscht")
    assertEquals(montag to donnerstag, person.daysOffRanges().single(),
      "XmlProjectImporter: die Grenzen des Urlaubs sind nicht mehr die der Datei")
  }

  // --- 3. the round trip is stable ----------------------------------------------------------

  @Test
  fun `der rundweg laden speichern laden ist stabil - desktop-lader`() {
    val einmal = person(ladeUeberResourceLoader(dateiMitNulltage(donnerstag)))
    val zwischendatei = saveToXml(ladeUeberResourceLoader(dateiMitNulltage(donnerstag)))
    val zweimal = person(ladeUeberResourceLoader(zwischendatei))

    assertEquals(listOf(donnerstag), abwesendeTage(einmal), "ResourceLoader: schon der erste Lauf stimmt nicht")
    assertEquals(abwesendeTage(einmal), abwesendeTage(zweimal),
      "ResourceLoader: der zweite Lauf durch dieselbe Datei ergibt etwas anderes als der erste")
    assertTrue(zwischendatei.contains("start=\"$donnerstag\" end=\"${donnerstag.plusDays(1)}\""),
      "ResourceLoader: die gespeicherte Datei traegt den Urlaub nicht als [d, d+1):\n$zwischendatei")
  }

  @Test
  fun `der rundweg laden speichern laden ist stabil - cloud-lader`() {
    val einmal = person(ladeUeberXmlProjectImporter(dateiMitNulltage(donnerstag)))
    val zwischendatei = saveToXml(ladeUeberXmlProjectImporter(dateiMitNulltage(donnerstag)))
    val zweimal = person(ladeUeberXmlProjectImporter(zwischendatei))

    assertEquals(listOf(donnerstag), abwesendeTage(einmal), "XmlProjectImporter: schon der erste Lauf stimmt nicht")
    assertEquals(abwesendeTage(einmal), abwesendeTage(zweimal),
      "XmlProjectImporter: der zweite Lauf durch dieselbe Datei ergibt etwas anderes als der erste")
    assertTrue(zwischendatei.contains("start=\"$donnerstag\" end=\"${donnerstag.plusDays(1)}\""),
      "XmlProjectImporter: die gespeicherte Datei traegt den Urlaub nicht als [d, d+1):\n$zwischendatei")
  }

  /**
   * The counter-check that makes the two "not moved" tests worth something: an ordinary vacation
   * goes through the file unchanged, byte for byte in the two attributes this change touches.
   */
  @Test
  fun `ein echter urlaub steht nach dem rundweg unveraendert in der datei`() {
    val original = dateiMitUrlaub(montag, donnerstag)
    val wieder = saveToXml(ladeUeberXmlProjectImporter(original))
    assertTrue(wieder.contains("start=\"$montag\" end=\"$donnerstag\""),
      "der gewoehnliche Urlaub hat den Rundweg nicht unveraendert ueberstanden:\n$wieder")
  }
}
