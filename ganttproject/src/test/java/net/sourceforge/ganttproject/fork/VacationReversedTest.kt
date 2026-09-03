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
import net.sourceforge.ganttproject.resource.HumanResource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * A vacation whose `end` lies BEFORE its `start`.
 *
 * Both this and `start == end` describe an interval that covers no day, and both are corrected to
 * the single day `[start, start+1)`. But they are NOT the same kind of mistake, and that is why
 * only one of them is reported:
 *
 *  * `start == end` is a recognisable shorthand. The two numbers agree, there is only one day they
 *    can mean, and it is corrected in silence. Measured in [VacationZeroDaysTest].
 *  * `end < start` is contradictory. At least one of the two numbers is wrong and the file does not
 *    say which — so the correction is one that nobody can check, and it is said out loud.
 *
 * WHAT IS MEASURED HERE, for both readers separately — the desktop reads through [ResourceLoader],
 * the cloud through [XmlProjectImporter]:
 *
 *  1. a reversed interval becomes one day from `start`;
 *  2. SEVERAL reversed intervals give ONE message, not one per interval — a broken file with 50 of
 *     them must not produce 50 boxes;
 *  3. a load WITHOUT reversed intervals gives NO message at all. This is the guard that makes (1)
 *     and (2) worth anything: a message that appears after every load is one nobody reads, and
 *     without this test a collector that always reported would pass (1) and (2) happily;
 *  4. `start == end` still gives NO message;
 *  5. the message names the person, both dates as they stand in the file, and what was made of
 *     them — otherwise nobody can repair the file.
 *
 * The message TEXT is not asserted word for word: it comes from the fork's bundle and would then
 * be a test of the translation. What is asserted is what the text must carry — the name and the
 * two dates — plus that the bundle answered at all (`forkText` falls back to the bare key, so a
 * forgotten entry would show up as "fork.vacation..." in the message).
 */
class VacationReversedTest {

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

  /** One `<vacation>` line exactly as it is to stand in the file — reversed ones included. */
  private data class Urlaub(val person: Person, val start: LocalDate, val end: LocalDate)

  private data class Person(val id: Int, val name: String)

  private val natalie = Person(0, "Natalie")
  private val olaf = Person(1, "Olaf")

  private val montag: LocalDate = LocalDate.of(2026, 9, 7)
  private val donnerstag: LocalDate = LocalDate.of(2026, 9, 10)
  private val freitag: LocalDate = LocalDate.of(2026, 9, 11)

  private fun saveToXml(project: GanttProjectImpl): String {
    val out = ByteArrayOutputStream()
    GanttXMLSaver(project).save(out)
    return out.toString(Charsets.UTF_8)
  }

  /**
   * A file with the given people and exactly the given vacation lines.
   *
   * The `<vacation>` elements are written by hand, because the point is a file the program itself
   * could never have produced — the saver always writes what the model holds, and the model cannot
   * hold a reversed interval. Everything ELSE in the file comes from the real saver, so a failure
   * cannot be about some unrelated part being malformed.
   */
  private fun dateiMit(vararg urlaube: Urlaub): String {
    val project = GanttProjectImpl()
    urlaube.map { it.person }.distinct().forEach { project.humanResourceManager.create(it.name, it.id) }
    val xml = saveToXml(project)
    val block = urlaube.joinToString("\n") {
      """        <vacation start="${it.start}" end="${it.end}" resourceid="${it.person.id}"/>"""
    }
    val mitUrlaub = xml.replace(
      Regex("""<vacations\s*/>|<vacations>\s*</vacations>"""), "<vacations>\n$block\n    </vacations>")
    assertTrue(mitUrlaub.contains("<vacation "),
      "die Testdatei traegt keinen Urlaub -- der Speicherer schreibt den leeren Block anders " +
        "als erwartet:\n$xml")
    return mitUrlaub
  }

  private fun ladeUeberResourceLoader(xml: String): ResourceLoader {
    val project = GanttProjectImpl()
    return ResourceLoader(project.humanResourceManager, project.roleManager,
      project.resourceCustomPropertyManager)
      .also { it.loadResources(parseXmlProject(xml)) }
  }

  private fun ladeUeberXmlProjectImporter(xml: String): XmlProjectImporter =
    XmlProjectImporter(GanttProjectImpl()).also { it.import(xml) }

  /** The days a person is actually absent, out of the half-open ranges the rest of the fork reads. */
  private fun abwesendeTage(resource: HumanResource): List<LocalDate> =
    resource.daysOffRanges().flatMap { (von, bisExklusiv) ->
      generateSequence(von) { it.plusDays(1) }.takeWhile { it.isBefore(bisExklusiv) }.toList()
    }

  // --- 1. a reversed interval becomes one day from start -------------------------------------

  @Test
  fun `ein verdrehtes intervall wird ein tag ab start - desktop-lader`() {
    val xml = dateiMit(Urlaub(natalie, start = donnerstag, end = montag))
    val project = GanttProjectImpl()
    ResourceLoader(project.humanResourceManager, project.roleManager,
      project.resourceCustomPropertyManager).loadResources(parseXmlProject(xml))
    val person = project.humanResourceManager.getById(natalie.id)
    assertEquals(listOf(donnerstag), abwesendeTage(person),
      "ResourceLoader: aus end < start ist nicht der eine Tag ab start geworden")
  }

  @Test
  fun `ein verdrehtes intervall wird ein tag ab start - cloud-lader`() {
    val xml = dateiMit(Urlaub(natalie, start = donnerstag, end = montag))
    val project = XmlProjectImporter(GanttProjectImpl()).import(xml) as GanttProjectImpl
    val person = project.humanResourceManager.getById(natalie.id)
    assertEquals(listOf(donnerstag), abwesendeTage(person),
      "XmlProjectImporter: aus end < start ist nicht der eine Tag ab start geworden")
  }

  // --- 2. several reversed intervals give ONE message -----------------------------------------

  @Test
  fun `mehrere verdrehte intervalle ergeben eine meldung - desktop-lader`() {
    val xml = dateiMit(
      Urlaub(natalie, start = donnerstag, end = montag),
      Urlaub(olaf, start = freitag, end = montag))
    val problems = ladeUeberResourceLoader(xml).vacationProblems

    assertEquals(2, problems.problems.size, "ResourceLoader: nicht beide verdrehten Urlaube gesammelt")
    val meldung = problems.message()
    assertNotNull(meldung, "ResourceLoader: zwei verdrehte Urlaube und keine Meldung")
    // EINE Meldung, die BEIDE nennt -- nicht zwei Meldungen.
    assertTrue(meldung!!.contains(natalie.name) && meldung.contains(olaf.name),
      "ResourceLoader: die eine Meldung nennt nicht beide Personen:\n$meldung")
  }

  @Test
  fun `mehrere verdrehte intervalle ergeben eine meldung - cloud-lader`() {
    val xml = dateiMit(
      Urlaub(natalie, start = donnerstag, end = montag),
      Urlaub(olaf, start = freitag, end = montag))
    val problems = ladeUeberXmlProjectImporter(xml).vacationProblems

    assertEquals(2, problems.problems.size, "XmlProjectImporter: nicht beide verdrehten Urlaube gesammelt")
    val meldung = problems.message()
    assertNotNull(meldung, "XmlProjectImporter: zwei verdrehte Urlaube und keine Meldung")
    assertTrue(meldung!!.contains(natalie.name) && meldung.contains(olaf.name),
      "XmlProjectImporter: die eine Meldung nennt nicht beide Personen:\n$meldung")
  }

  // --- 3. no reversed intervals, no message ---------------------------------------------------
  //
  // The guard. Without it a collector that reported after every load would pass everything above.

  @Test
  fun `ohne verdrehte intervalle gibt es keine meldung - desktop-lader`() {
    val xml = dateiMit(Urlaub(natalie, start = montag, end = donnerstag))
    val problems = ladeUeberResourceLoader(xml).vacationProblems
    assertTrue(problems.isEmpty, "ResourceLoader: ein gewoehnlicher Urlaub wurde als Problem gesammelt")
    assertNull(problems.message(),
      "ResourceLoader: eine Meldung ohne Anlass -- eine, die immer kommt, liest niemand")
  }

  @Test
  fun `ohne verdrehte intervalle gibt es keine meldung - cloud-lader`() {
    val xml = dateiMit(Urlaub(natalie, start = montag, end = donnerstag))
    val problems = ladeUeberXmlProjectImporter(xml).vacationProblems
    assertTrue(problems.isEmpty, "XmlProjectImporter: ein gewoehnlicher Urlaub wurde als Problem gesammelt")
    assertNull(problems.message(),
      "XmlProjectImporter: eine Meldung ohne Anlass -- eine, die immer kommt, liest niemand")
  }

  // --- 4. start == end stays silent -----------------------------------------------------------

  @Test
  fun `start gleich end erzeugt keine meldung - desktop-lader`() {
    val xml = dateiMit(Urlaub(natalie, start = donnerstag, end = donnerstag))
    val loader = ladeUeberResourceLoader(xml)
    assertNull(loader.vacationProblems.message(),
      "ResourceLoader: die Kurzschreibweise start == end wird gemeldet, obwohl sie eindeutig ist")
  }

  @Test
  fun `start gleich end erzeugt keine meldung - cloud-lader`() {
    val xml = dateiMit(Urlaub(natalie, start = donnerstag, end = donnerstag))
    val importer = ladeUeberXmlProjectImporter(xml)
    assertNull(importer.vacationProblems.message(),
      "XmlProjectImporter: die Kurzschreibweise start == end wird gemeldet, obwohl sie eindeutig ist")
  }

  // --- 5. the message says what happened, not just that something happened ---------------------

  @Test
  fun `die meldung nennt person, beide daten und den ergebnistag`() {
    val xml = dateiMit(Urlaub(natalie, start = donnerstag, end = montag))
    val meldung = ladeUeberResourceLoader(xml).vacationProblems.message()
    assertNotNull(meldung, "keine Meldung fuer ein verdrehtes Intervall")

    assertTrue(meldung!!.contains(natalie.name),
      "die Meldung nennt die Person nicht -- niemand weiss, wo zu suchen ist:\n$meldung")
    assertTrue(meldung.contains(donnerstag.toString()),
      "die Meldung nennt den Anfang aus der Datei nicht:\n$meldung")
    assertTrue(meldung.contains(montag.toString()),
      "die Meldung nennt das Ende aus der Datei nicht:\n$meldung")
    // forkText faellt auf den blanken Schluessel zurueck, wenn das Buendel ihn nicht kennt.
    assertFalse(meldung.contains("fork.vacation"),
      "im Buendel fehlt ein Text, die Meldung zeigt den Schluessel:\n$meldung")
  }

  /**
   * The dates are written in the form they have IN THE FILE, not in the display format of the
   * current language. Whoever wants to repair the file searches for that string in it.
   */
  @Test
  fun `die meldung schreibt die daten so wie sie in der datei stehen`() {
    val xml = dateiMit(Urlaub(natalie, start = donnerstag, end = montag))
    assertTrue(xml.contains("start=\"$donnerstag\""), "Vorbedingung: die Datei sagt nicht was erwartet:\n$xml")
    // Ausdruecklich geprueft statt mit !! erzwungen: ein blanker NullPointerException im roten
    // Lauf sagt niemandem, was fehlt.
    val meldung = ladeUeberResourceLoader(xml).vacationProblems.message()
    assertNotNull(meldung, "keine Meldung fuer ein verdrehtes Intervall")
    assertTrue(meldung!!.contains("2026-09-10"),
      "die Meldung schreibt das Datum nicht in der Form der Datei:\n$meldung")
  }

  // --- 6. the correction is durable -----------------------------------------------------------

  /**
   * After saving, the file no longer contains the contradiction — so opening it again is quiet.
   * A message that came back on every open of the same repaired file would be a nuisance, and it
   * would also mean the correction had not actually been written.
   */
  @Test
  fun `nach speichern und erneutem laden gibt es keine meldung mehr`() {
    val xml = dateiMit(Urlaub(natalie, start = donnerstag, end = montag))

    val ersteLadung = XmlProjectImporter(GanttProjectImpl())
    val project = ersteLadung.import(xml) as GanttProjectImpl
    assertFalse(ersteLadung.vacationProblems.isEmpty, "Vorbedingung: der erste Lauf meldet nichts")

    val gespeichert = saveToXml(project)
    assertTrue(gespeichert.contains("start=\"$donnerstag\" end=\"${donnerstag.plusDays(1)}\""),
      "der berichtigte Urlaub steht nicht als [d, d+1) in der gespeicherten Datei:\n$gespeichert")

    val zweiteLadung = ladeUeberXmlProjectImporter(gespeichert)
    assertNull(zweiteLadung.vacationProblems.message(),
      "die berichtigte Datei meldet beim zweiten Laden immer noch etwas")
  }
}
