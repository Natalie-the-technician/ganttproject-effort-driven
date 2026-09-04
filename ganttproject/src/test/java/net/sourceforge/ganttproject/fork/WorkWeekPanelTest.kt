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
import biz.ganttproject.core.time.CalendarFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.sourceforge.ganttproject.GanttProjectImpl
import net.sourceforge.ganttproject.io.GanttXMLSaver
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.task.algorithm.findEffortDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * THE INPUT FOR THE WORKING WEEK — seven boxes and two buttons, driven the way a click drives them.
 *
 * These tests build the REAL JavaFX nodes on the REAL JavaFX thread and fire the REAL buttons; the
 * pattern with `Dispatchers.JavaFx` is the one [AssignmentAxesUiTest] and [LevellingStalenessBarTest]
 * use, and for the same reason — JavaFX nodes may only be touched from their own thread. Nothing
 * here reaches into the panel behind the controls: what a test sets, it sets on a `CheckBox`; what
 * it triggers, it triggers with `Button.fire()`.
 *
 * WHAT THEY CANNOT SAY is whether the seven boxes and the two buttons FIT — whether the labels are
 * cut off at 360 px, whether the tab is reachable next to the days-off tab, whether anything is
 * legible. That needs an eye on the screen and is reported separately.
 *
 * THE CENTRAL ONE is `wer den reiter nur aufmacht und wieder zugeht traegt nichts ein`. The
 * distinction it guards is the whole point of this package: Monday to Friday is what the boxes
 * OFFER, it is not what an untouched person MEANS. In the model, nothing entered says „ask the
 * project calendar" and [WorkWeekSchedule.worksOn] answers `null` for it. A panel that wrote its
 * preselection on Ok would turn every person in every existing plan into an explicit Mon–Fri the
 * first time somebody looked at their properties, and nobody would see it happen.
 */
class WorkWeekPanelTest {

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

  /** A Tuesday, so that „today" is never accidentally a boundary of the week. */
  private val HEUTE: LocalDate = LocalDate.of(2026, 9, 8)
  private val SPAETER: LocalDate = LocalDate.of(2026, 12, 1)

  private fun projekt(): GanttProjectImpl = GanttProjectImpl()

  private fun GanttProjectImpl.person(spaltenwert: String? = null): HumanResource {
    val person = this.humanResourceManager.create("Natalie", 0)
    if (spaltenwert != null) {
      person.setValue(findOrCreateWorkWeek(this.resourceCustomPropertyManager), spaltenwert)
    }
    return person
  }

  private fun GanttProjectImpl.panel(person: HumanResource, gewaehlt: LocalDate = SPAETER) =
    WorkWeekPanelFx(person, this.resourceCustomPropertyManager, HEUTE, { onChosen -> onChosen(gewaehlt) })

  private fun GanttProjectImpl.spaltenwert(person: HumanResource): String? =
    this.resourceCustomPropertyManager.findEffortDefinition(RESOURCE_WORK_WEEK)
      ?.let { person.getCustomField(it)?.toString() }

  private fun WorkWeekPanelFx.ankreuzen(vararg tage: DayOfWeek) {
    dayBoxes.forEachIndexed { index, box ->
      box.isSelected = DayOfWeek.of(index + WorkWeekSchedule.FIRST_DAY) in tage.toSet()
    }
  }

  private fun WorkWeekPanelFx.angekreuzt(): Set<DayOfWeek> =
    dayBoxes.mapIndexedNotNull { index, box ->
      if (box.isSelected) DayOfWeek.of(index + WorkWeekSchedule.FIRST_DAY) else null
    }.toSet()

  /** The bundle as a FILE, so that a check of it cannot be answered by the fallback chain. */
  private fun datei(pfad: String): java.util.Properties = java.util.Properties().also { props ->
    ForkI18n::class.java.getResourceAsStream(pfad)?.use { props.load(it.reader(Charsets.UTF_8)) }
  }

  private val MO_BIS_FR = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

  /**
   * SEVEN BOXES, ONE PER DAY, AND SUNDAY IS ONE OF THEM. Natalie's words: „sieben abhakkästen für
   * die tage, also pro Tag einer" — and Sunday is a box like every other, not a special case of
   * the weekend.
   */
  @Test
  fun `es sind sieben kaestchen, eines je tag, und sonntag ist dabei`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val panel = projekt.panel(projekt.person())
      assertEquals(7, panel.dayBoxes.size, "es muss je Tag ein Kaestchen geben")
      assertEquals(forkText("fork.workweek.ui.day.7"), panel.dayBoxes[6].text,
        "das siebte Kaestchen ist der Sonntag")
    }
  }

  /** Monday to Friday is TICKED when the tab opens. Preselection, and only that. */
  @Test
  fun `die vorauswahl ist montag bis freitag`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val panel = projekt.panel(projekt.person())
      assertEquals(MO_BIS_FR, panel.angekreuzt(),
        "beim Oeffnen sollen Mo bis Fr angekreuzt sein, Sa und So nicht")
    }
  }

  /**
   * THE MOST IMPORTANT TEST OF THIS PACKAGE.
   *
   * Open the tab, touch nothing, close with Ok. The preselection is Mon–Fr and it must NOT become
   * an entry: the person goes on falling back to the project calendar, and [WorkWeekSchedule.worksOn]
   * goes on answering `null`. Checked in three ways that fail independently — the stored column
   * text, the parsed schedule, and the query itself — because each one alone can be true for the
   * wrong reason: an empty string parses to an empty schedule, and an empty schedule answers null.
   */
  @Test
  fun `wer den reiter nur aufmacht und wieder zugeht traegt nichts ein`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person()
      val panel = projekt.panel(person)

      panel.save()   // this is what the Ok button of the dialog does

      val wert = projekt.spaltenwert(person)
      assertTrue(wert == null || wert.isEmpty(),
        "aus der blossen Vorauswahl ist ein Spalteneintrag geworden: \"$wert\"")
      val woche = person.workWeek(projekt.resourceCustomPropertyManager).schedule
      assertTrue(woche.isEmpty, "die Person hat einen Fahrplan bekommen, ohne dass jemand einen Knopf gedrueckt hat: $woche")
      assertNull(woche.worksOn(LocalDate.of(2026, 9, 12)),
        "Samstag muss unbeantwortet bleiben — ohne Eintrag gilt der Projektkalender, nicht Mo bis Fr")
      assertNull(woche.worksOn(LocalDate.of(2026, 9, 7)),
        "auch der Montag muss unbeantwortet bleiben, sonst ist aus der Vorauswahl eine Aussage geworden")
    }
  }

  /**
   * The same guard one step earlier: even a pressed button changes nothing until the dialog is
   * closed with Ok. Cancel has to cancel.
   */
  @Test
  fun `ohne Ok schreibt auch ein knopfdruck nichts`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person()
      val panel = projekt.panel(person)
      panel.ankreuzen(DayOfWeek.MONDAY, DayOfWeek.SATURDAY)

      panel.applyFromTodayButton.fire()   // pressed, but the dialog is cancelled: no save()

      assertTrue(person.workWeek(projekt.resourceCustomPropertyManager).schedule.isEmpty,
        "der Knopfdruck allein hat schon geschrieben — Abbrechen bricht dann nichts mehr ab")
    }
  }

  /** „ab jetzt übernehmen" — exactly one section, and it begins today. */
  @Test
  fun `ab jetzt uebernehmen schreibt genau einen abschnitt ab heute`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person()
      val panel = projekt.panel(person)
      panel.ankreuzen(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)

      panel.applyFromTodayButton.fire()
      panel.save()

      val woche = person.workWeek(projekt.resourceCustomPropertyManager).schedule
      assertEquals(1, woche.changes.size, "es soll genau ein Abschnitt entstehen: $woche")
      assertEquals(HEUTE, woche.changes[0].from,
        "der Abschnitt muss HEUTE beginnen — ein undatierter Abschnitt gaelte rueckwirkend vom Anfang des Plans an")
      assertNull(woche.worksOn(HEUTE.minusDays(1)),
        "gestern liegt vor dem Abschnitt und muss unbeantwortet bleiben")
      assertEquals(true, woche.worksOn(LocalDate.of(2026, 9, 12)), "Samstag nach dem Anfang")
    }
  }

  /**
   * „ab zeitpunkt in der Zukunft" — the picker names the day, and an EARLIER section survives.
   * That is the whole point of the second button: somebody drops to three days from 1 December and
   * what they worked before that has to stay written.
   */
  @Test
  fun `ab zeitpunkt schreibt ab dem gewaehlten tag und laesst den frueheren abschnitt stehen`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person("2026-01-01: 1,2,5,6")
      val panel = projekt.panel(person, SPAETER)
      panel.ankreuzen(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY)

      panel.applyFromDateButton.fire()
      panel.save()

      val woche = person.workWeek(projekt.resourceCustomPropertyManager).schedule
      assertEquals(2, woche.changes.size, "der frueherer Abschnitt ist verschwunden: $woche")
      assertEquals(LocalDate.of(2026, 1, 1), woche.changes[0].from, "der alte Abschnitt zuerst")
      assertEquals(SPAETER, woche.changes[1].from, "der neue Abschnitt ab dem gewaehlten Tag")
      assertEquals(true, woche.worksOn(LocalDate.of(2026, 6, 6)),
        "ein Samstag VOR dem 1.12. gehoert noch zur alten Woche")
      assertEquals(false, woche.worksOn(LocalDate.of(2026, 12, 5)),
        "ein Samstag NACH dem 1.12. gehoert zur neuen Woche")
    }
  }

  /** The example from the brief: Mon, Tue, Fri, Sat — entered, and unchanged when reopened. */
  @Test
  fun `montag dienstag freitag samstag kommt beim erneuten oeffnen unveraendert zurueck`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person()
      val ersteSitzung = projekt.panel(person)
      ersteSitzung.ankreuzen(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
      ersteSitzung.applyFromTodayButton.fire()
      ersteSitzung.save()

      val zweiteSitzung = projekt.panel(person)
      assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
        zweiteSitzung.angekreuzt(),
        "beim erneuten Oeffnen muessen genau die eingetragenen Tage angekreuzt sein, nicht die Vorauswahl")
    }
  }

  /** Sunday is a box like every other — ticked, stored as 7, and ticked again when reopened. */
  @Test
  fun `sonntag laesst sich ankreuzen und wird nicht verschluckt`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person()
      val panel = projekt.panel(person)
      panel.ankreuzen(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
      panel.applyFromTodayButton.fire()
      panel.save()

      assertTrue(projekt.spaltenwert(person)!!.contains("7"),
        "der Sonntag steht nicht als 7 in der Spalte: ${projekt.spaltenwert(person)}")
      val woche = person.workWeek(projekt.resourceCustomPropertyManager).schedule
      assertEquals(true, woche.worksOn(LocalDate.of(2026, 9, 13)), "Sonntag, der 13. September")
      assertTrue(DayOfWeek.SUNDAY in projekt.panel(person).angekreuzt(),
        "beim erneuten Oeffnen fehlt der Sonntag im Kaestchen")
    }
  }

  /**
   * THE EMPTY SECTION IS REFUSED AT THE INPUT, while the model goes on accepting it — the split
   * proposed in the report of 03.09.2026. What stands in a file has to stay readable; what nobody
   * has typed yet does not have to become creatable.
   *
   * The reason is not taste: a section without a day means this person never works again from that
   * date on, and the two day-by-day walks then pace out about 54 years before their guard stops
   * them. Whoever means „from here on, no more" means an absence, and an absence has an end.
   */
  @Test
  fun `ein abschnitt ohne angekreuzten tag wird abgelehnt und sagt warum`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person()
      val panel = projekt.panel(person)
      panel.ankreuzen()   // not a single day

      panel.applyFromTodayButton.fire()
      panel.save()

      assertTrue(person.workWeek(projekt.resourceCustomPropertyManager).schedule.isEmpty,
        "eine leere Woche ist angenommen worden: ${projekt.spaltenwert(person)}")
      assertEquals(forkText("fork.workweek.ui.error.noDay"), panel.messageLabel.text,
        "die Ablehnung muss sagen, warum — eine stumme Verweigerung sieht aus wie ein kaputter Knopf")
      assertTrue(panel.messageLabel.text.isNotEmpty(), "die Meldung darf nicht leer sein")
    }
  }

  /**
   * AN ENTRY THAT IS ALREADY THERE IS SHOWN, and a text with several sections is shown as the
   * section in force TODAY. Not as the first one, and not as the preselection.
   */
  @Test
  fun `ein bestehender eintrag aus der textspalte wird in den kaestchen angezeigt`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      // Mon, Tue, Fri, Sat from the beginning; Mon, Tue, Wed from 1 March. Today (8.9.2026) the
      // second section is in force.
      val person = projekt.person("1,2,5,6; 2026-03-01: 1,2,3")
      val panel = projekt.panel(person)

      assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY), panel.angekreuzt(),
        "gezeigt werden muss der Abschnitt, der HEUTE gilt")
      assertTrue(panel.summaryLabel.text.contains("2026-03-01"),
        "die Uebersicht muss auch den spaeteren Abschnitt nennen, sonst ist er unsichtbar: ${panel.summaryLabel.text}")
    }
  }

  /** The undated section is shown too — it is the plain case, „works Mon, Tue, Fri, Sat, always has". */
  @Test
  fun `auch ein undatierter eintrag wird in den kaestchen angezeigt`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person("1,2,5,6")
      assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
        projekt.panel(person).angekreuzt(), "der undatierte Abschnitt gilt heute")
    }
  }

  /**
   * A COLUMN TEXT THAT CANNOT BE READ LOCKS THE BUTTONS instead of quietly throwing it away.
   *
   * [WorkWeekSchedule.parse] DROPS a section it cannot read whole and reports it. If the panel
   * built its new schedule on that remainder, one press of a button would silently delete a line
   * somebody typed by hand — the panel would look like it merely added a section and would in fact
   * have removed one. Refusing, and naming the reason, is the only honest answer.
   */
  @Test
  fun `eine unlesbare textspalte sperrt die knoepfe statt sie stillschweigend zu verwerfen`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person("1,2,5,6; 01.03.2026: 1,2,3")
      val panel = projekt.panel(person)
      panel.ankreuzen(DayOfWeek.MONDAY)

      assertTrue(panel.applyFromTodayButton.isDisable, "der Knopf muss gesperrt sein")
      assertTrue(panel.applyFromDateButton.isDisable, "der zweite Knopf auch")
      panel.applyFrom(HEUTE)
      panel.save()

      assertEquals("1,2,5,6; 01.03.2026: 1,2,3", projekt.spaltenwert(person),
        "der von Hand getippte Text ist ueberschrieben und der kaputte Abschnitt dabei verschwunden")
      assertTrue(panel.messageLabel.text.isNotEmpty(), "die Sperre muss sich erklaeren")
    }
  }

  /**
   * THE ROUND TRIP: entered through the boxes, saved to a .gan, loaded again, opened again — the
   * same week. The model's own round trip is measured in [WorkWeekRoundTripTest]; what is added
   * here is the two ends, the boxes on both sides of the file.
   */
  @Test
  fun `der rundweg durch die datei bringt dieselbe woche in die kaestchen zurueck`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person()
      val panel = projekt.panel(person)
      panel.ankreuzen(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
      panel.applyFromTodayButton.fire()
      panel.save()

      val out = ByteArrayOutputStream()
      GanttXMLSaver(projekt).save(out)
      val xml = out.toString(Charsets.UTF_8)
      assertTrue(xml.contains("definition-id=\"$RESOURCE_WORK_WEEK\""),
        "die Arbeitswoche steht nicht in der Datei:\n$xml")

      val geladen = XmlProjectImporter(GanttProjectImpl()).import(xml) as GanttProjectImpl
      val geladenePerson = geladen.humanResourceManager.getById(0)!!
      assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
        geladen.panel(geladenePerson).angekreuzt(),
        "nach Speichern und Laden zeigen die Kaestchen etwas anderes an")
    }
  }

  /**
   * And the counterpart of the round trip: a person nobody touched writes NOTHING into the file,
   * so an old plan opened and saved again does not silently acquire a working week for everybody.
   */
  @Test
  fun `eine unberuehrte person traegt auch in der datei nichts ein`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person()
      projekt.panel(person).save()

      val out = ByteArrayOutputStream()
      GanttXMLSaver(projekt).save(out)
      val xml = out.toString(Charsets.UTF_8)
      assertFalse(xml.contains("definition-id=\"$RESOURCE_WORK_WEEK\" value=\"1,2,3,4,5\""),
        "die Vorauswahl ist in die Datei gewandert:\n$xml")

      val geladen = XmlProjectImporter(GanttProjectImpl()).import(xml) as GanttProjectImpl
      assertNull(geladen.humanResourceManager.getById(0)!!
        .workWeek(geladen.resourceCustomPropertyManager).schedule.worksOn(LocalDate.of(2026, 9, 12)),
        "nach dem Rundweg hat die unberuehrte Person eine Aussage — der Projektkalender gilt nicht mehr")
    }
  }

  /**
   * EVERY LABEL OF THIS PANEL COMES OUT OF A BUNDLE, in both languages.
   *
   * Two failures are being guarded, and they look different in the running program. A key that is
   * in NEITHER file comes back as itself — `forkText` is built that way on purpose, a visible
   * „fork.workweek.ui.day.7" gets noticed where an empty label would not. A key that is only in the
   * English file falls through silently and looks like an ordinary untranslated word; nobody
   * reports that, and the German session carries an English button forever.
   *
   * The weekday names deserve the check most: they are the one place where this fork writes day
   * names at all. What is STORED is the ISO number and carries no language — the labels are the
   * only part a translation may touch, and [WorkWeekRoundTripTest] holds the other end of that.
   */
  @Test
  fun `die beschriftungen kommen aus beiden sprachdateien und nicht als blanker schluessel`() {
    val deutscheDatei = datei("/language/fork/i18n_de.properties")
    val englischeDatei = datei("/language/fork/i18n.properties")
    // Guard: an unreadable file would leave both empty and make every check below vacuous.
    assertTrue(deutscheDatei.size > 0 && englischeDatei.size > 0,
      "eine der beiden Sprachdateien wurde nicht gelesen — dieser Test bewiese dann nichts")

    val schluessel = (WorkWeekSchedule.FIRST_DAY..WorkWeekSchedule.LAST_DAY)
      .map { "fork.workweek.ui.day.$it" } + listOf(
      "fork.workweek.ui.intro", "fork.workweek.ui.applyNow", "fork.workweek.ui.applyFrom",
      "fork.workweek.ui.none", "fork.workweek.ui.current", "fork.workweek.ui.applied",
      "fork.workweek.ui.error.noDay", "fork.workweek.ui.error.unreadable")

    schluessel.forEach { key ->
      assertNotEquals(key, forkText(key), "\"$key\" steht in keiner der beiden Dateien und erscheint als blanker Schluessel")
      // READ OUT OF THE FILE, not asked of the localizer. Measured while writing this test:
      // `ForkI18n.textOrNull(key, Locale.GERMANY)` walks its locale chain down to the ENGLISH file,
      // so it answers for a key the German file does not have at all — the check would have been
      // green for exactly the failure it is meant to catch, and it was: with
      // `fork.workweek.ui.day.7` commented out of the German file, only the named assertion below
      // went red. The chain is right for the program and useless as a measure of the file.
      assertTrue(deutscheDatei.containsKey(key),
        "\"$key\" fehlt in der deutschen Datei und faellt still auf Englisch durch")
      assertTrue(englischeDatei.containsKey(key), "\"$key\" fehlt in der englischen Datei")
    }

    // Named outright, so that a file which merely HAS the key but with the wrong content is caught
    // too — Sunday is the day this package has to get right.
    assertEquals("Sonntag", ForkI18n.textOrNull("fork.workweek.ui.day.7", Locale.GERMANY))
    assertEquals("Sunday", ForkI18n.textOrNull("fork.workweek.ui.day.7", Locale.US))
  }
}
