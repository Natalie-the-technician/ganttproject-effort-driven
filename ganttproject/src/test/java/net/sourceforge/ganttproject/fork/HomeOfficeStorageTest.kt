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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * DOES THE HOME OFFICE SURVIVE THE FILE — and does it survive a change of interface language?
 *
 * A home office that exists only in memory is worth nothing. The route goes through the REAL saver
 * ([GanttXMLSaver]) and through BOTH readers the program has: [ResourceLoader] for the desktop and
 * [XmlProjectImporter] for the cloud. Two readers for one format can disagree about it, so both are
 * measured — the same reasoning as in [WorkWeekRoundTripTest].
 *
 * THE ROUTE WAS CHECKED AND NOT ASSUMED. `ResourceSaver.saveCustomProperties` writes every resource
 * property as `<custom-property definition-id=… value=…/>`, the ordinary format, which is why these
 * two properties need no reader of their own — unlike the `<allocation>` attributes of the
 * assignment axes, which had to be taught to both readers first. The test
 * `beide spalten stehen als gewoehnliche custom-property in der datei` looks at the written XML
 * rather than trusting the sentence.
 *
 * AND IT ALSO MEASURES THE CHOICE OF TWO COLUMNS instead of one. See
 * `derselbe text bedeutet in den zwei spalten zweierlei`; the second half of the argument, the
 * half-wide lock, is in [HomeOfficePanelTest].
 */
class HomeOfficeStorageTest {

  init {
    // GanttProjectImpl builds a WeekendCalendarImpl, which needs this. Same opening as in
    // WorkWeekRoundTripTest; without it every test here dies in CalendarFactory.
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

  /** Every Friday at home, and from 1 June only the Monday. */
  private val MUSTER = "5; 2026-06-01: 1"

  /** 1 to 3 June at home — the end is EXCLUSIVE, so the 4th is not in it. */
  private val ZEITRAEUME = "2026-06-01..2026-06-04"

  private fun saveToXml(project: GanttProjectImpl): String {
    val out = ByteArrayOutputStream()
    GanttXMLSaver(project).save(out)
    return out.toString(Charsets.UTF_8)
  }

  private fun personMitHeimarbeit(
    muster: String? = MUSTER, zeitraeume: String? = ZEITRAEUME
  ): GanttProjectImpl {
    val project = GanttProjectImpl()
    val person = project.humanResourceManager.create("Natalie", 0)
    if (muster != null) {
      person.setHomeOfficeWeek(project.resourceCustomPropertyManager, HomeOfficeWeek.parse(muster).week)
    }
    if (zeitraeume != null) {
      person.setHomeOfficePeriods(project.resourceCustomPropertyManager,
        HomeOfficePeriods.parse(zeitraeume).periods)
    }
    return project
  }

  private fun GanttProjectImpl.heimarbeitVon(id: Int): HomeOffice =
    this.humanResourceManager.getById(id)!!.homeOffice(this.resourceCustomPropertyManager).homeOffice

  private fun GanttProjectImpl.person(): HumanResource = this.humanResourceManager.getById(0)!!

  // ═══ DER RUNDWEG ═══

  /**
   * EINTRAGEN, SPEICHERN, LADEN — DASSELBE ZURUECK, and both halves at once.
   *
   * Two ends are measured, not one. First that the values reach the FILE under the agreed ids: a
   * test that only read the model back would still pass if the saver wrote nothing and the reader
   * invented the answer. Then that the days come back with the boundary intact.
   */
  @Test
  fun `heimarbeit uebersteht speichern und laden`() {
    val xml = saveToXml(personMitHeimarbeit())

    assertTrue(xml.contains("definition-id=\"$RESOURCE_HOME_OFFICE_WEEK\" value=\"$MUSTER\""),
      "das Wochenmuster steht nicht in der Datei:\n$xml")
    assertTrue(xml.contains("definition-id=\"$RESOURCE_HOME_OFFICE_PERIODS\" value=\"$ZEITRAEUME\""),
      "die Zeitraeume stehen nicht in der Datei:\n$xml")

    val geladen = XmlProjectImporter(GanttProjectImpl()).import(xml) as GanttProjectImpl
    val heimarbeit = geladen.heimarbeitVon(0)

    assertTrue(heimarbeit.worksFromHome(LocalDate.of(2026, 5, 29)), "Freitag vor dem 1.6., aus dem Muster")
    assertTrue(heimarbeit.worksFromHome(LocalDate.of(2026, 6, 8)), "Montag nach dem 1.6., aus dem Muster")
    assertFalse(heimarbeit.worksFromHome(LocalDate.of(2026, 6, 12)),
      "Freitag nach dem 1.6. - der zweite Abschnitt hat den Freitag abgeloest")

    assertTrue(heimarbeit.worksFromHome(LocalDate.of(2026, 6, 3)), "Mi 3.6., letzter Tag des Zeitraums")
    assertFalse(heimarbeit.worksFromHome(LocalDate.of(2026, 6, 4)),
      "Do 4.6. - das Ende ist exklusiv und muss es auch nach dem Rundweg sein")
  }

  /** The desktop reads through [ResourceLoader], the cloud through [XmlProjectImporter]. Both. */
  @Test
  fun `auch der lader des programms bringt die heimarbeit mit`() {
    val xml = saveToXml(personMitHeimarbeit())

    val ziel = GanttProjectImpl()
    ResourceLoader(ziel.humanResourceManager, ziel.roleManager, ziel.resourceCustomPropertyManager)
      .loadResources(parseXmlProject(xml))

    val heimarbeit = ziel.heimarbeitVon(0)
    assertTrue(heimarbeit.worksFromHome(LocalDate.of(2026, 6, 3)), "Mi 3.6. aus dem Zeitraum")
    assertFalse(heimarbeit.worksFromHome(LocalDate.of(2026, 6, 4)), "Do 4.6. - exklusives Ende")
    assertTrue(heimarbeit.worksFromHome(LocalDate.of(2026, 6, 8)), "Mo 8.6. aus dem Muster")
  }

  /**
   * BEIDE SPALTEN SIND GEWOEHNLICHE `custom-property` — the sentence from the brief, checked in
   * the written XML instead of taken from `ResourceSaver.java:78`.
   *
   * This is what makes the round trip through a FOREIGN GanttProject work: a stock version knows
   * nothing about home offices, but it knows this element, keeps it and writes it out again.
   */
  @Test
  fun `beide spalten stehen als gewoehnliche custom-property in der datei`() {
    val xml = saveToXml(personMitHeimarbeit())
    listOf(RESOURCE_HOME_OFFICE_WEEK, RESOURCE_HOME_OFFICE_PERIODS).forEach { id ->
      assertTrue(
        Regex("<custom-property definition-id=\"$id\" value=\"[^\"]*\"\\s*/>").containsMatchIn(xml),
        "„$id\" steht nicht im Standardformat in der Datei - eine fremde Fassung wuerde es " +
          "verlieren:\n$xml")
    }
  }

  /**
   * WHAT IS STORED CARRIES NO LANGUAGE. Digits, ISO dates and separators, nothing a translation
   * could touch — the same guard [WorkWeekRoundTripTest] holds over the working week.
   */
  @Test
  fun `in den gespeicherten werten steht nichts, was eine uebersetzung anfassen koennte`() {
    val projekt = personMitHeimarbeit()
    val manager = projekt.resourceCustomPropertyManager
    listOf(
      projekt.person().getCustomField(findOrCreateHomeOfficeWeek(manager))!!.toString(),
      projekt.person().getCustomField(findOrCreateHomeOfficePeriods(manager))!!.toString()
    ).forEach { wert ->
      assertTrue(wert.matches(Regex("[0-9;:,. -]+")),
        "im Wert „$wert\" steht etwas, das eine Uebersetzung anfassen koennte")
    }
  }

  /**
   * DER RUNDWEG UEBER EINEN SPRACHWECHSEL DER OBERFLAECHE: written in a German session, read in an
   * English one.
   *
   * The column NAMES travel along in German — unavoidable and harmless, because the columns are
   * found again by their ID. Were the pattern stored as weekday names, this test would come back
   * with an empty pattern and no error at all.
   */
  @Test
  fun `der rundweg ueberlebt einen sprachwechsel der oberflaeche`() {
    val vorher = Locale.getDefault()
    try {
      Locale.setDefault(Locale.GERMANY)
      val xml = saveToXml(personMitHeimarbeit())
      assertTrue(xml.contains("name=\"Heimarbeit (Wochenmuster)\""),
        "die Spalte wurde nicht unter dem deutschen Namen geschrieben:\n$xml")

      Locale.setDefault(Locale.US)
      val geladen = XmlProjectImporter(GanttProjectImpl()).import(xml) as GanttProjectImpl
      val heimarbeit = geladen.heimarbeitVon(0)
      assertTrue(heimarbeit.worksFromHome(LocalDate.of(2026, 6, 3)),
        "Mi 3.6., gelesen in der englischen Sitzung")
      assertFalse(heimarbeit.worksFromHome(LocalDate.of(2026, 6, 4)),
        "Do 4.6. - das exklusive Ende muss den Sprachwechsel ueberstehen")
      assertTrue(heimarbeit.worksFromHome(LocalDate.of(2026, 6, 8)),
        "Mo 8.6. aus dem Muster, gelesen in der englischen Sitzung")

      // And the English session does not create SECOND columns beside the German ones -- that is
      // the „Column with ID=… is already registered" trap from the other direction.
      val vorSuche = geladen.resourceCustomPropertyManager.definitions.size
      val muster = findOrCreateHomeOfficeWeek(geladen.resourceCustomPropertyManager)
      val zeitraeume = findOrCreateHomeOfficePeriods(geladen.resourceCustomPropertyManager)
      assertEquals(vorSuche, geladen.resourceCustomPropertyManager.definitions.size,
        "die englische Sitzung hat zusaetzliche Spalten angelegt")
      assertEquals(RESOURCE_HOME_OFFICE_WEEK, muster.id)
      assertEquals(RESOURCE_HOME_OFFICE_PERIODS, zeitraeume.id)
      assertEquals("Heimarbeit (Wochenmuster)", muster.name,
        "die Spalte wurde nicht ueber ihre Kennung wiedergefunden")
    } finally {
      Locale.setDefault(vorher)
    }
  }

  /**
   * OHNE EINTRAG BLEIBT ES OHNE EINTRAG, auch durch die Datei hindurch.
   *
   * An old plan opened and saved again must not silently acquire a home office for everybody — and
   * the two column definitions must not even come into a project that has none.
   */
  @Test
  fun `ohne eintrag kommt auch nach dem rundweg keine heimarbeit zurueck`() {
    val projekt = GanttProjectImpl()
    projekt.humanResourceManager.create("Natalie", 0)
    val xml = saveToXml(projekt)

    assertFalse(xml.contains(RESOURCE_HOME_OFFICE_WEEK),
      "die Spalte fuer das Muster ist in eine Datei geraten, die sie nicht braucht:\n$xml")
    assertFalse(xml.contains(RESOURCE_HOME_OFFICE_PERIODS),
      "die Spalte fuer die Zeitraeume ist in eine Datei geraten, die sie nicht braucht:\n$xml")

    val geladen = XmlProjectImporter(GanttProjectImpl()).import(xml) as GanttProjectImpl
    val heimarbeit = geladen.heimarbeitVon(0)
    assertTrue(heimarbeit.isEmpty, "nach dem Rundweg ist aus nichts etwas geworden")
    (0L..13L).forEach { versatz ->
      val tag = LocalDate.of(2026, 6, 1).plusDays(versatz)
      assertFalse(heimarbeit.worksFromHome(tag),
        "nach dem Rundweg ist $tag (${tag.dayOfWeek}) Heimarbeit geworden")
    }
  }

  /** Eine Spalte fehlt, die andere ist da: die vorhandene gilt, die fehlende ist schlicht leer. */
  @Test
  fun `eine der beiden spalten allein reicht`() {
    val nurMuster = personMitHeimarbeit(zeitraeume = null).heimarbeitVon(0)
    assertTrue(nurMuster.worksFromHome(LocalDate.of(2026, 5, 29)), "Freitag aus dem Muster")
    assertTrue(nurMuster.periods.isEmpty, "ohne die zweite Spalte darf kein Zeitraum entstehen")

    val nurZeitraum = personMitHeimarbeit(muster = null).heimarbeitVon(0)
    assertTrue(nurZeitraum.worksFromHome(LocalDate.of(2026, 6, 3)), "Mi aus dem Zeitraum")
    assertFalse(nurZeitraum.worksFromHome(LocalDate.of(2026, 5, 29)),
      "ohne die erste Spalte darf kein Wochenmuster entstehen")
    assertTrue(nurZeitraum.week.isEmpty, "das Muster muss leer bleiben")
  }

  /** Die Abfrage an der Person selbst — dasselbe wie am Modell, nur ueber die Ablage. */
  @Test
  fun `die abfrage an der person liefert ein schlichtes ja oder nein`() {
    val projekt = personMitHeimarbeit()
    val manager = projekt.resourceCustomPropertyManager
    assertTrue(projekt.person().worksFromHome(manager, LocalDate.of(2026, 6, 3)), "Mi 3.6.")
    assertFalse(projekt.person().worksFromHome(manager, LocalDate.of(2026, 6, 4)), "Do 4.6.")

    val ohne = GanttProjectImpl().also { it.humanResourceManager.create("Ohne", 0) }
    assertFalse(ohne.person().worksFromHome(ohne.resourceCustomPropertyManager, LocalDate.of(2026, 6, 3)),
      "eine Person ohne Eintrag muss schlicht false sagen")
  }

  // ═══ DIE MESSUNG ZUR WAHL: EINE SPALTE ODER ZWEI ═══

  /**
   * WARUM ZWEI SPALTEN, ERSTE HAELFTE DER MESSUNG: DIE ZWEI GRAMMATIKEN KOLLIDIEREN.
   *
   * `2026-06-01` is a complete, valid entry in BOTH texts and means something different in each:
   * a single day at home in the period column, a broken list of weekdays in the pattern column.
   * A shared column would have to give up one of the two readings — and the one it would have to
   * give up is the bare-date shorthand, the shortest and likeliest thing a person types.
   *
   * This is not a thought experiment: both readings are computed here and shown to differ.
   */
  @Test
  fun `derselbe text bedeutet in den zwei spalten zweierlei`() {
    val text = "2026-06-01"

    val alsZeitraum = HomeOfficePeriods.parse(text)
    assertFalse(alsZeitraum.hasErrors, "als Zeitraum gelesen ist „$text\" tadellos")
    assertEquals(1, alsZeitraum.periods.periods.single().days, "und bedeutet genau einen Tag")

    val alsMuster = HomeOfficeWeek.parse(text)
    assertTrue(alsMuster.hasErrors,
      "als Wochenmuster gelesen muesste „$text\" eine Beschwerde ergeben, ergab aber: ${alsMuster.week}")
    assertTrue(alsMuster.week.changes.isEmpty(),
      "und darf keinen brauchbaren Abschnitt hinterlassen")

    // Named outright: the two readings are not merely different in shape, they are incompatible.
    assertTrue(HomeOffice(periods = alsZeitraum.periods).worksFromHome(LocalDate.of(2026, 6, 1)))
    assertFalse(HomeOffice(week = alsMuster.week).worksFromHome(LocalDate.of(2026, 6, 1)))
  }

  /**
   * WARUM ZWEI SPALTEN, ZWEITE HAELFTE: die Fehler bleiben getrennt.
   *
   * A broken date among the periods leaves the pattern's error list EMPTY. With one column both
   * would arrive in one list, and the panel — which locks on any error — would lock everything.
   * The lock itself is measured in [HomeOfficePanelTest]; what is measured here is that the
   * storage keeps the two apart at all.
   */
  @Test
  fun `ein fehler in der einen spalte laesst die andere unberuehrt`() {
    // The RAW text, set directly. Going through personMitHeimarbeit would parse it first, and the
    // broken line would never reach the column at all -- this test then measured nothing and was
    // green for it. Caught in the break run of 04.09.2026.
    val projekt = personMitHeimarbeit(zeitraeume = null)
    projekt.person().setValue(
      findOrCreateHomeOfficePeriods(projekt.resourceCustomPropertyManager), "01.06.2026..04.06.2026")
    val gelesen = projekt.person().homeOffice(projekt.resourceCustomPropertyManager)

    assertTrue(gelesen.periodErrors.isNotEmpty(), "der kaputte Zeitraum muss gemeldet werden")
    assertTrue(gelesen.weekErrors.isEmpty(),
      "das Wochenmuster ist tadellos und darf keinen Fehler mitbekommen: ${gelesen.weekErrors}")
    assertTrue(gelesen.homeOffice.worksFromHome(LocalDate.of(2026, 5, 29)),
      "das lesbare Muster muss weiter gelten, obwohl die andere Spalte kaputt ist")
    assertTrue(gelesen.homeOffice.periods.isEmpty, "aus der kaputten Zeile darf kein Zeitraum werden")
  }

  /** Ein leerer Wert ist kein Fehler, sondern schlicht nichts eingetragen. */
  @Test
  fun `ein leerer spaltenwert ist kein fehler`() {
    val projekt = personMitHeimarbeit(muster = "", zeitraeume = "")
    val gelesen = projekt.person().homeOffice(projekt.resourceCustomPropertyManager)
    assertFalse(gelesen.hasErrors, "ein leerer Wert darf nichts melden: ${gelesen.errors}")
    assertTrue(gelesen.homeOffice.isEmpty, "und nichts bedeuten")
    assertNull(projekt.person().getCustomField(
      findOrCreateHomeOfficeWeek(projekt.resourceCustomPropertyManager))?.toString()?.ifEmpty { null },
      "der Wert selbst ist leer")
  }
}
