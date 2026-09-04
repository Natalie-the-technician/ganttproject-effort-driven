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
 * THE INPUT FOR THE HOME OFFICE — seven boxes and two buttons for the pattern, a list and two
 * buttons for the periods, driven the way a click drives them.
 *
 * The REAL JavaFX nodes on the REAL JavaFX thread, and the REAL buttons fired; the pattern with
 * `Dispatchers.JavaFx` is the one [WorkWeekPanelTest] and [AssignmentAxesUiTest] use, and for the
 * same reason — JavaFX nodes may only be touched from their own thread. Nothing here reaches into
 * the panel behind the controls: what a test sets, it sets on a `CheckBox`; what it triggers, it
 * triggers with `Button.fire()`.
 *
 * WHAT THEY CANNOT SAY is whether the controls FIT — whether the labels are cut off, whether the
 * list is tall enough to be worth having, whether the tab is legible next to the days-off tab.
 * That needs an eye on a screen and is reported separately.
 *
 * ═══ THE CENTRAL ONE, AND WHY IT IS SHARPER HERE THAN NEXT DOOR ═══
 *
 * `wer den reiter nur aufmacht und wieder zugeht traegt nichts ein` is the guard of the whole
 * package. Over in [WorkWeekPanelTest] the same guard could be held by looking at the stored column
 * TEXT, because the preselection there is Mon–Fri and a panel that wrote it would leave „1,2,3,4,5"
 * behind. HERE THAT WOULD NOT WORK, and the difference is a trap rather than a detail: this panel
 * has NO preselection, so a broken [HomeOfficePanelFx.save] that wrote unconditionally would write
 * an EMPTY pattern — the column value would be `""`, the parsed model would be empty, and every
 * assertion of the neighbouring shape would stay green while the guard was gone.
 *
 * What catches it is the assertion that THE COLUMN DEFINITION DOES NOT EXIST. An untouched person
 * must not even bring the two columns into a project that has none, and that is the only trace an
 * empty write leaves. It was verified by breaking it — see the report of 04.09.2026, which records
 * four separate breaks and what each of them did or did not turn red.
 */
class HomeOfficePanelTest {

  init {
    // GanttProjectImpl builds a WeekendCalendarImpl, which needs this. Same opening as in
    // WorkWeekPanelTest; without it every test here dies in CalendarFactory.
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

  /** Mon 1 June 2026 to Thu 4 June 2026 — three days, the fourth being the exclusive end. */
  private val ZEITRAUM_ANFANG: LocalDate = LocalDate.of(2026, 6, 1)
  private val ZEITRAUM_ENDE_EXKLUSIV: LocalDate = LocalDate.of(2026, 6, 4)

  private fun projekt(): GanttProjectImpl = GanttProjectImpl()

  private fun GanttProjectImpl.person(muster: String? = null, zeitraeume: String? = null): HumanResource {
    val person = this.humanResourceManager.create("Natalie", 0)
    if (muster != null) {
      person.setValue(findOrCreateHomeOfficeWeek(this.resourceCustomPropertyManager), muster)
    }
    if (zeitraeume != null) {
      person.setValue(findOrCreateHomeOfficePeriods(this.resourceCustomPropertyManager), zeitraeume)
    }
    return person
  }

  private fun GanttProjectImpl.panel(
    person: HumanResource,
    gewaehlterTag: LocalDate = SPAETER,
    gewaehlterZeitraum: Pair<LocalDate, LocalDate> = ZEITRAUM_ANFANG to ZEITRAUM_ENDE_EXKLUSIV
  ) = HomeOfficePanelFx(
    person, this.resourceCustomPropertyManager, HEUTE,
    { onChosen -> onChosen(gewaehlterTag) },
    { onChosen -> onChosen(gewaehlterZeitraum.first, gewaehlterZeitraum.second) })

  /** The stored text of a column, or null when the column does not exist at all. */
  private fun GanttProjectImpl.spaltenwert(person: HumanResource, id: String): String? =
    this.resourceCustomPropertyManager.findEffortDefinition(id)
      ?.let { person.getCustomField(it)?.toString() }

  /** Whether the column DEFINITION exists. The only trace an empty write leaves — see the class comment. */
  private fun GanttProjectImpl.spalteBesteht(id: String): Boolean =
    this.resourceCustomPropertyManager.findEffortDefinition(id) != null

  private fun HomeOfficePanelFx.ankreuzen(vararg tage: DayOfWeek) {
    dayBoxes.forEachIndexed { index, box ->
      box.isSelected = DayOfWeek.of(index + HomeOfficeWeek.FIRST_DAY) in tage.toSet()
    }
  }

  private fun HomeOfficePanelFx.angekreuzt(): Set<DayOfWeek> =
    dayBoxes.mapIndexedNotNull { index, box ->
      if (box.isSelected) DayOfWeek.of(index + HomeOfficeWeek.FIRST_DAY) else null
    }.toSet()

  private fun GanttProjectImpl.heimarbeitVon(person: HumanResource): HomeOffice =
    person.homeOffice(this.resourceCustomPropertyManager).homeOffice

  /** The bundle as a FILE, so that a check of it cannot be answered by the fallback chain. */
  private fun datei(pfad: String): java.util.Properties = java.util.Properties().also { props ->
    HomeOfficePanelFx::class.java.getResourceAsStream(pfad)?.use { props.load(it.reader(Charsets.UTF_8)) }
  }

  // ═══ 1. DIE WACHE: OHNE KNOPFDRUCK WIRD NICHTS GESCHRIEBEN ═══

  /**
   * THE MOST IMPORTANT TEST OF THIS PACKAGE.
   *
   * Open the tab, touch nothing, close with Ok. Nothing may be written — and „nothing" here means
   * FOUR separate things, each of which fails on its own, because each alone can be true for the
   * wrong reason:
   *
   *  1. THE COLUMN DEFINITIONS DO NOT EXIST. The sharp one. An empty write would leave the value
   *     `""` and the model empty, and only this notices.
   *  2. The stored texts are absent or empty.
   *  3. The parsed model is empty.
   *  4. The query answers `false` on days of every shape — and `false` because nothing is entered,
   *     not because something empty was entered.
   */
  @Test
  fun `wer den reiter nur aufmacht und wieder zugeht traegt nichts ein`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person()
      val panel = projekt.panel(person)
      // Touch every control, exactly as looking at the tab does -- the lazy construction must not
      // be what keeps the person clean.
      panel.node
      panel.dayBoxes
      panel.periodList

      panel.save()   // this is what the Ok button of the dialog does

      assertFalse(projekt.spalteBesteht(RESOURCE_HOME_OFFICE_WEEK),
        "die Spalte fuer das Wochenmuster ist angelegt worden, ohne dass jemand einen Knopf " +
          "gedrueckt hat - ein Projekt ohne Heimarbeit traegt sie nun im Kopf")
      assertFalse(projekt.spalteBesteht(RESOURCE_HOME_OFFICE_PERIODS),
        "die Spalte fuer die Zeitraeume ist angelegt worden, ohne dass jemand einen Knopf gedrueckt hat")

      val muster = projekt.spaltenwert(person, RESOURCE_HOME_OFFICE_WEEK)
      assertTrue(muster == null || muster.isEmpty(),
        "aus dem blossen Aufmachen ist ein Wochenmuster geworden: \"$muster\"")
      val zeitraeume = projekt.spaltenwert(person, RESOURCE_HOME_OFFICE_PERIODS)
      assertTrue(zeitraeume == null || zeitraeume.isEmpty(),
        "aus dem blossen Aufmachen ist ein Zeitraum geworden: \"$zeitraeume\"")

      val heimarbeit = projekt.heimarbeitVon(person)
      assertTrue(heimarbeit.isEmpty,
        "die Person hat Heimarbeit bekommen, ohne dass jemand einen Knopf gedrueckt hat: " +
          "${heimarbeit.week} / ${heimarbeit.periods}")
      (0L..13L).forEach { versatz ->
        val tag = HEUTE.plusDays(versatz)
        assertFalse(heimarbeit.worksFromHome(tag),
          "$tag (${tag.dayOfWeek}) ist Heimarbeit geworden, ohne dass jemand etwas eingetragen hat")
      }
    }
  }

  /**
   * KEINE VORAUSWAHL — and that is the difference from [WorkWeekPanelTest], stated as a test so
   * that nobody „makes the two panels consistent" by copying the Mon–Fri preselection over.
   *
   * Nothing entered means NO home office. A preselection here would not merely be a suggestion, it
   * would show the opposite of what the untouched person means.
   */
  @Test
  fun `beim oeffnen ist kein tag angekreuzt`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val panel = projekt.panel(projekt.person())
      assertEquals(7, panel.dayBoxes.size, "es muss je Tag ein Kaestchen geben")
      assertEquals(emptySet<DayOfWeek>(), panel.angekreuzt(),
        "beim Oeffnen darf kein Tag angekreuzt sein - nichts eingetragen heisst keine Heimarbeit")
      assertEquals(forkText("fork.homeoffice.ui.day.7"), panel.dayBoxes[6].text,
        "das siebte Kaestchen ist der Sonntag")
    }
  }

  /** Auch ein Knopfdruck schreibt nichts, solange der Dialog nicht mit Ok geschlossen wird. */
  @Test
  fun `ohne Ok schreibt auch ein knopfdruck nichts`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person()
      val panel = projekt.panel(person)
      panel.ankreuzen(DayOfWeek.FRIDAY)

      panel.applyFromTodayButton.fire()   // pressed, but the dialog is cancelled: no save()
      panel.addPeriodButton.fire()

      assertFalse(projekt.spalteBesteht(RESOURCE_HOME_OFFICE_WEEK),
        "der Knopfdruck allein hat schon geschrieben - Abbrechen bricht dann nichts mehr ab")
      assertFalse(projekt.spalteBesteht(RESOURCE_HOME_OFFICE_PERIODS),
        "der Zeitraum-Knopf allein hat schon geschrieben")
      assertTrue(projekt.heimarbeitVon(person).isEmpty,
        "die Person hat Heimarbeit, obwohl der Dialog abgebrochen wurde")
    }
  }

  /**
   * DIE ZWEI HAELFTEN SCHREIBEN GETRENNT. Wer nur einen Zeitraum eintraegt, bekommt kein leeres
   * Wochenmuster ueber eine von Hand getippte Spalte geschrieben - und umgekehrt.
   */
  @Test
  fun `wer nur einen zeitraum eintraegt schreibt kein wochenmuster`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person()
      val panel = projekt.panel(person)

      panel.addPeriodButton.fire()
      panel.save()

      assertTrue(projekt.spalteBesteht(RESOURCE_HOME_OFFICE_PERIODS), "der Zeitraum muss geschrieben sein")
      assertFalse(projekt.spalteBesteht(RESOURCE_HOME_OFFICE_WEEK),
        "es ist ein leeres Wochenmuster mitgeschrieben worden, obwohl niemand einen Muster-Knopf drueckte")
    }
  }

  // ═══ 2. DAS WOCHENMUSTER ═══

  /** „ab jetzt übernehmen" — genau ein Abschnitt, und er beginnt heute. */
  @Test
  fun `ab jetzt uebernehmen schreibt genau einen abschnitt ab heute`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person()
      val panel = projekt.panel(person)
      panel.ankreuzen(DayOfWeek.FRIDAY)

      panel.applyFromTodayButton.fire()
      panel.save()

      val heimarbeit = projekt.heimarbeitVon(person)
      assertEquals(1, heimarbeit.week.changes.size, "es soll genau ein Abschnitt entstehen: ${heimarbeit.week}")
      assertEquals(HEUTE, heimarbeit.week.changes[0].from,
        "der Abschnitt muss HEUTE beginnen - ein undatierter gaelte rueckwirkend vom Anfang des Plans an")
      assertFalse(heimarbeit.worksFromHome(LocalDate.of(2026, 9, 4)),
        "der Freitag VOR heute liegt vor dem Abschnitt und ist keine Heimarbeit")
      assertTrue(heimarbeit.worksFromHome(LocalDate.of(2026, 9, 11)), "der erste Freitag nach heute")
      assertFalse(heimarbeit.worksFromHome(LocalDate.of(2026, 9, 10)), "der Donnerstag davor nicht")
    }
  }

  /**
   * „ab Zeitpunkt" — der Waehler nennt den Tag, und ein FRUEHERER Abschnitt bleibt stehen. Das ist
   * der ganze Zweck des zweiten Knopfes.
   */
  @Test
  fun `ab zeitpunkt schreibt ab dem gewaehlten tag und laesst den frueheren abschnitt stehen`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person(muster = "2026-01-01: 5")
      val panel = projekt.panel(person, gewaehlterTag = SPAETER)
      panel.ankreuzen(DayOfWeek.MONDAY)

      panel.applyFromDateButton.fire()
      panel.save()

      val heimarbeit = projekt.heimarbeitVon(person)
      assertEquals(2, heimarbeit.week.changes.size, "der frueherer Abschnitt ist verschwunden: ${heimarbeit.week}")
      assertEquals(LocalDate.of(2026, 1, 1), heimarbeit.week.changes[0].from, "der alte Abschnitt zuerst")
      assertEquals(SPAETER, heimarbeit.week.changes[1].from, "der neue Abschnitt ab dem gewaehlten Tag")
      assertTrue(heimarbeit.worksFromHome(LocalDate.of(2026, 6, 5)),
        "ein Freitag VOR dem 1.12. gehoert noch zum alten Abschnitt")
      assertFalse(heimarbeit.worksFromHome(LocalDate.of(2026, 12, 4)),
        "ein Freitag NACH dem 1.12. gehoert zum neuen Abschnitt, der nur den Montag nennt")
      assertTrue(heimarbeit.worksFromHome(LocalDate.of(2026, 12, 7)), "der Montag danach schon")
    }
  }

  /** Ein bestehender Eintrag wird angezeigt, und zwar der Abschnitt, der HEUTE gilt. */
  @Test
  fun `ein bestehender eintrag aus der textspalte wird in den kaestchen angezeigt`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      // Fridays from the beginning; Mon and Tue from 1 March. Today (8.9.2026) the second applies.
      val person = projekt.person(muster = "5; 2026-03-01: 1,2")
      val panel = projekt.panel(person)

      assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), panel.angekreuzt(),
        "gezeigt werden muss der Abschnitt, der HEUTE gilt")
      assertTrue(panel.weekSummaryLabel.text.contains("2026-03-01"),
        "die Uebersicht muss auch den spaeteren Abschnitt nennen: ${panel.weekSummaryLabel.text}")
    }
  }

  /**
   * EIN ABSCHNITT OHNE ANGEKREUZTEN TAG BEENDET DIE HEIMARBEIT — und das ist das Gegenteil dessen,
   * was der Arbeitswochen-Reiter mit demselben Griff tut, wo er ihn ablehnt.
   *
   * Dort hiesse „kein Tag angekreuzt", die Person arbeitet nie wieder. Hier heisst es, sie ist nicht
   * mehr zu Hause - und das ist die einzige Art, eine Absprache zu beenden, ohne ihre Geschichte zu
   * loeschen.
   */
  @Test
  fun `kein tag angekreuzt beendet ein bestehendes muster`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person(muster = "5")
      val panel = projekt.panel(person, gewaehlterTag = SPAETER)
      panel.ankreuzen()   // not a single day

      panel.applyFromDateButton.fire()
      panel.save()

      val heimarbeit = projekt.heimarbeitVon(person)
      assertTrue(heimarbeit.worksFromHome(LocalDate.of(2026, 6, 5)),
        "ein Freitag vor dem Ende bleibt Heimarbeit - die Geschichte darf nicht geloescht werden")
      assertFalse(heimarbeit.worksFromHome(LocalDate.of(2026, 12, 4)),
        "ein Freitag nach dem Ende ist keine Heimarbeit mehr")
      assertFalse(heimarbeit.isEmpty, "der beendende Abschnitt ist ein Eintrag")
      assertEquals(forkText("fork.homeoffice.ui.ended", SPAETER.toString()), panel.messageLabel.text,
        "die Meldung muss sagen, dass beendet und nicht uebernommen wurde")
    }
  }

  /** Ohne bestehendes Muster gibt es aber nichts zu beenden, und das wird gesagt statt geschrieben. */
  @Test
  fun `ohne bestehendes muster wird ein leerer abschnitt abgelehnt und sagt warum`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person()
      val panel = projekt.panel(person)
      panel.ankreuzen()

      panel.applyFromTodayButton.fire()
      panel.save()

      assertFalse(projekt.spalteBesteht(RESOURCE_HOME_OFFICE_WEEK),
        "es ist eine Zeile geschrieben worden, die genau das sagt, was ihr Fehlen schon sagte")
      assertEquals(forkText("fork.homeoffice.ui.error.nothingToEnd"), panel.messageLabel.text,
        "die Ablehnung muss sagen, warum - eine stumme Verweigerung sieht aus wie ein kaputter Knopf")
    }
  }

  /** Sonntag ist ein Kaestchen wie jedes andere und wird als 7 abgelegt. */
  @Test
  fun `sonntag laesst sich ankreuzen und wird nicht verschluckt`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person()
      val panel = projekt.panel(person)
      panel.ankreuzen(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
      panel.applyFromTodayButton.fire()
      panel.save()

      assertTrue(projekt.spaltenwert(person, RESOURCE_HOME_OFFICE_WEEK)!!.contains("7"),
        "der Sonntag steht nicht als 7 in der Spalte: ${projekt.spaltenwert(person, RESOURCE_HOME_OFFICE_WEEK)}")
      assertTrue(projekt.heimarbeitVon(person).worksFromHome(LocalDate.of(2026, 9, 13)),
        "Sonntag, der 13. September")
      assertTrue(DayOfWeek.SUNDAY in projekt.panel(person).angekreuzt(),
        "beim erneuten Oeffnen fehlt der Sonntag im Kaestchen")
    }
  }

  // ═══ 3. DIE ZEITRAEUME ═══

  /**
   * EIN ZEITRAUM WIRD MIT DEM EXKLUSIVEN ENDE ABGELEGT UND MIT DEM LETZTEN TAG ANGEZEIGT.
   *
   * Der Waehler liefert bereits das exklusive Ende (`DateInterval.createFromVisibleDates` legt
   * `adjustRight(letzterTag)` ab), es wird also nichts verschoben. Was in der Liste steht, nennt
   * dagegen den 3.6. - denn wer „bis 4.6." liest, zaehlt vier Tage.
   */
  @Test
  fun `ein zeitraum wird exklusiv abgelegt und mit seinem letzten tag angezeigt`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person()
      val panel = projekt.panel(person)

      panel.addPeriodButton.fire()
      panel.save()

      assertEquals("2026-06-01..2026-06-04", projekt.spaltenwert(person, RESOURCE_HOME_OFFICE_PERIODS),
        "abgelegt wird das exklusive Ende, wie beim <vacation>-Element auch")

      val heimarbeit = projekt.heimarbeitVon(person)
      assertTrue(heimarbeit.worksFromHome(LocalDate.of(2026, 6, 1)), "Mo 1.6., erster Tag")
      assertTrue(heimarbeit.worksFromHome(LocalDate.of(2026, 6, 3)), "Mi 3.6., letzter Tag")
      assertFalse(heimarbeit.worksFromHome(LocalDate.of(2026, 6, 4)),
        "Do 4.6. steht im Text, das Ende ist aber exklusiv")
      assertEquals(3, heimarbeit.daysFromHome(LocalDate.of(2026, 5, 25), LocalDate.of(2026, 6, 15)),
        "es muessen genau drei Tage sein")

      assertEquals(1, panel.periodList.items.size, "der Zeitraum muss in der Liste stehen")
      val zeile = panel.periodList.items[0]
      assertEquals(LocalDate.of(2026, 6, 3), zeile.lastDay,
        "angezeigt wird der letzte Tag, und der ist der 3.6. - nicht der 4.")
      assertTrue(panel.messageLabel.text.contains("2026-06-03"),
        "auch die Meldung muss den 3.6. nennen: ${panel.messageLabel.text}")
      assertFalse(panel.messageLabel.text.contains("2026-06-04"),
        "die Meldung darf den 4.6. NICHT nennen: ${panel.messageLabel.text}")
    }
  }

  /** Ein bestehender Zeitraum aus der Textspalte steht beim Oeffnen in der Liste. */
  @Test
  fun `bestehende zeitraeume stehen beim oeffnen in der liste`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person(zeitraeume = "2026-06-01..2026-06-04; 2026-08-03..2026-08-08")
      val panel = projekt.panel(person)

      assertEquals(2, panel.periodList.items.size, "beide Zeitraeume muessen in der Liste stehen")
      assertEquals(LocalDate.of(2026, 6, 1), panel.periodList.items[0].start, "der frueheste zuerst")
      assertEquals(LocalDate.of(2026, 8, 7), panel.periodList.items[1].lastDay,
        "der letzte Tag des zweiten Zeitraums ist der 7.8.")
    }
  }

  /** Entfernen nimmt genau den ausgewaehlten Zeitraum heraus und laesst die anderen stehen. */
  @Test
  fun `entfernen nimmt genau den ausgewaehlten zeitraum heraus`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person(zeitraeume = "2026-06-01..2026-06-04; 2026-08-03..2026-08-08")
      val panel = projekt.panel(person)

      assertTrue(panel.removePeriodButton.isDisable,
        "solange nichts ausgewaehlt ist, muss der Knopf gesperrt sein")
      panel.periodList.selectionModel.select(0)
      assertFalse(panel.removePeriodButton.isDisable, "mit Auswahl muss er benutzbar sein")

      panel.removePeriodButton.fire()
      panel.save()

      assertEquals("2026-08-03..2026-08-08", projekt.spaltenwert(person, RESOURCE_HOME_OFFICE_PERIODS),
        "es muss genau der zweite Zeitraum uebrig bleiben")
      val heimarbeit = projekt.heimarbeitVon(person)
      assertFalse(heimarbeit.worksFromHome(LocalDate.of(2026, 6, 2)), "der entfernte Zeitraum ist weg")
      assertTrue(heimarbeit.worksFromHome(LocalDate.of(2026, 8, 4)), "der andere steht noch")
    }
  }

  /** Ein Zeitraum, der keinen Tag umfasst, wird abgelehnt und sagt warum. */
  @Test
  fun `ein zeitraum ohne einen einzigen tag wird abgelehnt`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person()
      val panel = projekt.panel(person,
        gewaehlterZeitraum = ZEITRAUM_ANFANG to ZEITRAUM_ANFANG)

      panel.addPeriodButton.fire()
      panel.save()

      assertFalse(projekt.spalteBesteht(RESOURCE_HOME_OFFICE_PERIODS),
        "ein Zeitraum ohne Tag ist angenommen worden")
      assertEquals(forkText("fork.homeoffice.ui.error.emptyPeriod"), panel.messageLabel.text,
        "die Ablehnung muss sagen, warum")
    }
  }

  // ═══ 4. MUSTER UND ZEITRAUM ZUGLEICH, UND DIE HALBBREITE SPERRE ═══

  /** Beides in einer Sitzung eingetragen: beides steht danach da, und die Vereinigung gilt. */
  @Test
  fun `muster und zeitraum lassen sich in einer sitzung eintragen`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person()
      // The chosen period is Mon 1 to Wed 3 June; the pattern is every Monday from 1 January.
      val panel = projekt.panel(person, gewaehlterTag = LocalDate.of(2026, 1, 1))
      panel.ankreuzen(DayOfWeek.MONDAY)
      panel.applyFromDateButton.fire()
      panel.addPeriodButton.fire()
      panel.save()

      val heimarbeit = projekt.heimarbeitVon(person)
      assertTrue(heimarbeit.worksFromHome(LocalDate.of(2026, 6, 1)),
        "Mo 1.6. liegt in BEIDEM - Muster und Zeitraum")
      assertTrue(heimarbeit.worksFromHome(LocalDate.of(2026, 6, 2)), "Di 2.6. nur aus dem Zeitraum")
      assertTrue(heimarbeit.worksFromHome(LocalDate.of(2026, 6, 8)), "Mo 8.6. nur aus dem Muster")
      assertFalse(heimarbeit.worksFromHome(LocalDate.of(2026, 6, 4)), "Do 4.6. aus keinem von beiden")
      // Mon 1, Tue 2, Wed 3 from the period, Mon 8 from the pattern -- FOUR days, the Monday of the
      // first week counted once although it lies in both.
      assertEquals(4, heimarbeit.daysFromHome(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 15)),
        "der Montag liegt in beidem und muss einmal zaehlen")
    }
  }

  /**
   * EINE UNLESBARE TEXTSPALTE SPERRT DIE KNOEPFE — die Entscheidung aus dem Arbeitswochen-Reiter,
   * hier uebernommen: [HomeOfficeWeek.parse] verwirft einen Abschnitt, den es nicht ganz lesen kann,
   * und ein Knopfdruck auf dem Rest wuerde eine von Hand getippte Zeile still loeschen.
   */
  @Test
  fun `eine unlesbare musterspalte sperrt die musterknoepfe statt sie zu verwerfen`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person(muster = "5; 01.03.2026: 1,2")
      val panel = projekt.panel(person)
      panel.ankreuzen(DayOfWeek.MONDAY)

      assertTrue(panel.applyFromTodayButton.isDisable, "der Knopf muss gesperrt sein")
      assertTrue(panel.applyFromDateButton.isDisable, "der zweite Knopf auch")
      panel.applyWeekFrom(HEUTE)
      panel.save()

      assertEquals("5; 01.03.2026: 1,2", projekt.spaltenwert(person, RESOURCE_HOME_OFFICE_WEEK),
        "der von Hand getippte Text ist ueberschrieben und der kaputte Abschnitt dabei verschwunden")
      assertTrue(panel.messageLabel.text.isNotEmpty(), "die Sperre muss sich erklaeren")
    }
  }

  /**
   * DIE SPERRE IST HALBBREIT — und DAS ist die gemessene Begruendung fuer zwei Spalten statt einer.
   *
   * Ein vertipptes Datum bei den Zeitraeumen sperrt die zwei Zeitraum-Knoepfe und laesst die sieben
   * Wochentag-Kaestchen benutzbar. Laegen beide in EINER Spalte, gaebe es eine Fehlerliste und
   * damit eine Sperre: die Person koennte ihren Freitag nicht eintragen, weil ein Juni-Datum einen
   * Tippfehler hat.
   */
  @Test
  fun `ein unlesbarer zeitraum sperrt nur die zeitraum-knoepfe`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person(zeitraeume = "01.06.2026..04.06.2026")
      val panel = projekt.panel(person)

      assertTrue(panel.addPeriodButton.isDisable, "der Zeitraum-Knopf muss gesperrt sein")
      assertTrue(panel.removePeriodButton.isDisable, "der Entfernen-Knopf auch")
      assertFalse(panel.applyFromTodayButton.isDisable,
        "die Muster-Knoepfe muessen benutzbar bleiben - genau dafuer gibt es zwei Spalten")
      assertFalse(panel.applyFromDateButton.isDisable, "auch der zweite Muster-Knopf")

      // And the pattern can really be entered while the other column is broken.
      panel.ankreuzen(DayOfWeek.FRIDAY)
      panel.applyFromTodayButton.fire()
      panel.save()

      assertTrue(projekt.heimarbeitVon(person).worksFromHome(LocalDate.of(2026, 9, 11)),
        "das Muster liess sich nicht eintragen, obwohl nur die andere Spalte kaputt ist")
      assertEquals("01.06.2026..04.06.2026", projekt.spaltenwert(person, RESOURCE_HOME_OFFICE_PERIODS),
        "die kaputte Zeile ist ueberschrieben worden")
    }
  }

  // ═══ 5. DER RUNDWEG DURCH DIE DATEI ═══

  /**
   * EINTRAGEN, .gan SPEICHERN, LADEN, WIEDER AUFMACHEN — dasselbe zurueck, beide Haelften, und
   * ueber einen SPRACHWECHSEL der Oberflaeche hinweg.
   *
   * Der Rundweg des Modells wird in [HomeOfficeStorageTest] gemessen; was hier dazukommt, sind die
   * zwei Enden - die Kaestchen und die Liste auf beiden Seiten der Datei.
   */
  @Test
  fun `der rundweg durch die datei bringt dasselbe in kaestchen und liste zurueck`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val vorher = Locale.getDefault()
      try {
        Locale.setDefault(Locale.GERMANY)
        val projekt = projekt()
        val person = projekt.person()
        val panel = projekt.panel(person)
        panel.ankreuzen(DayOfWeek.FRIDAY, DayOfWeek.SUNDAY)
        panel.applyFromTodayButton.fire()
        panel.addPeriodButton.fire()
        panel.save()

        val out = ByteArrayOutputStream()
        GanttXMLSaver(projekt).save(out)
        val xml = out.toString(Charsets.UTF_8)
        assertTrue(xml.contains("definition-id=\"$RESOURCE_HOME_OFFICE_WEEK\""),
          "das Wochenmuster steht nicht in der Datei:\n$xml")
        assertTrue(xml.contains("definition-id=\"$RESOURCE_HOME_OFFICE_PERIODS\""),
          "die Zeitraeume stehen nicht in der Datei:\n$xml")

        // The English session opens the file the German one wrote.
        Locale.setDefault(Locale.US)
        val geladen = XmlProjectImporter(GanttProjectImpl()).import(xml) as GanttProjectImpl
        val geladenePerson = geladen.humanResourceManager.getById(0)!!
        val zweiteSitzung = geladen.panel(geladenePerson)

        assertEquals(setOf(DayOfWeek.FRIDAY, DayOfWeek.SUNDAY), zweiteSitzung.angekreuzt(),
          "nach Speichern, Laden und Sprachwechsel zeigen die Kaestchen etwas anderes an")
        assertEquals(1, zweiteSitzung.periodList.items.size, "der Zeitraum fehlt in der Liste")
        assertEquals(LocalDate.of(2026, 6, 1), zweiteSitzung.periodList.items[0].start,
          "der Anfang des Zeitraums hat sich verschoben")
        assertEquals(LocalDate.of(2026, 6, 3), zweiteSitzung.periodList.items[0].lastDay,
          "der letzte Tag hat sich verschoben - das exklusive Ende hat den Rundweg nicht ueberlebt")
      } finally {
        Locale.setDefault(vorher)
      }
    }
  }

  /** Die Gegenprobe des Rundwegs: eine unberuehrte Person traegt auch in der Datei nichts ein. */
  @Test
  fun `eine unberuehrte person traegt auch in der datei nichts ein`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val person = projekt.person()
      projekt.panel(person).save()

      val out = ByteArrayOutputStream()
      GanttXMLSaver(projekt).save(out)
      val xml = out.toString(Charsets.UTF_8)
      assertFalse(xml.contains(RESOURCE_HOME_OFFICE_WEEK),
        "die Spalte fuer das Muster ist in die Datei gewandert:\n$xml")
      assertFalse(xml.contains(RESOURCE_HOME_OFFICE_PERIODS),
        "die Spalte fuer die Zeitraeume ist in die Datei gewandert:\n$xml")

      val geladen = XmlProjectImporter(GanttProjectImpl()).import(xml) as GanttProjectImpl
      assertTrue(geladen.humanResourceManager.getById(0)!!
        .homeOffice(geladen.resourceCustomPropertyManager).homeOffice.isEmpty,
        "nach dem Rundweg hat die unberuehrte Person Heimarbeit")
    }
  }

  // ═══ 6. DIE BESCHRIFTUNGEN ═══

  /**
   * JEDE BESCHRIFTUNG DIESES REITERS KOMMT AUS BEIDEN SPRACHDATEIEN.
   *
   * Zwei Fehler werden bewacht, und sie sehen im laufenden Programm verschieden aus. Ein Schluessel,
   * der in KEINER Datei steht, kommt als er selbst zurueck - das faellt auf. Einer, der nur in der
   * englischen steht, faellt still durch und sieht aus wie ein gewoehnliches unuebersetztes Wort;
   * die deutsche Sitzung traegt den englischen Knopf dann fuer immer.
   *
   * DESHALB WIRD AUS DER DATEI GELESEN und nicht den Localizer gefragt: `ForkI18n.textOrNull` geht
   * seine Locale-Kette bis zur englischen Datei hinunter und antwortet auch fuer einen Schluessel,
   * den die deutsche gar nicht hat. Dieselbe Messung steht in [WorkWeekPanelTest].
   */
  @Test
  fun `die beschriftungen kommen aus beiden sprachdateien und nicht als blanker schluessel`() {
    val deutscheDatei = datei("/language/fork/i18n_de.properties")
    val englischeDatei = datei("/language/fork/i18n.properties")
    assertTrue(deutscheDatei.size > 0 && englischeDatei.size > 0,
      "eine der beiden Sprachdateien wurde nicht gelesen - dieser Test bewiese dann nichts")

    val schluessel = (HomeOfficeWeek.FIRST_DAY..HomeOfficeWeek.LAST_DAY)
      .map { "fork.homeoffice.ui.day.$it" } + listOf(
      "fork.column.homeOfficeWeek", "fork.column.homeOfficePeriods",
      "fork.homeoffice.error.date", "fork.homeoffice.error.day", "fork.homeoffice.error.reversed",
      "fork.homeoffice.ui.tab", "fork.homeoffice.ui.intro", "fork.homeoffice.ui.weekSection",
      "fork.homeoffice.ui.applyNow", "fork.homeoffice.ui.applyFrom", "fork.homeoffice.ui.applied",
      "fork.homeoffice.ui.ended", "fork.homeoffice.ui.weekNone", "fork.homeoffice.ui.weekCurrent",
      "fork.homeoffice.ui.periodSection", "fork.homeoffice.ui.addPeriod",
      "fork.homeoffice.ui.removePeriod", "fork.homeoffice.ui.periodItem",
      "fork.homeoffice.ui.periodItemOneDay", "fork.homeoffice.ui.periodAdded",
      "fork.homeoffice.ui.periodRemoved", "fork.homeoffice.ui.error.emptyPeriod",
      "fork.homeoffice.ui.error.nothingToEnd", "fork.homeoffice.ui.error.weekUnreadable",
      "fork.homeoffice.ui.error.periodsUnreadable")

    schluessel.forEach { key ->
      assertNotEquals(key, forkText(key),
        "\"$key\" steht in keiner der beiden Dateien und erscheint als blanker Schluessel")
      assertTrue(deutscheDatei.containsKey(key),
        "\"$key\" fehlt in der deutschen Datei und faellt still auf Englisch durch")
      assertTrue(englischeDatei.containsKey(key), "\"$key\" fehlt in der englischen Datei")
    }

    // Named outright, so that a file which merely HAS the key but with the wrong content is caught.
    assertEquals("Sonntag", ForkI18n.textOrNull("fork.homeoffice.ui.day.7", Locale.GERMANY))
    assertEquals("Sunday", ForkI18n.textOrNull("fork.homeoffice.ui.day.7", Locale.US))
    assertEquals("Heimarbeit", ForkI18n.textOrNull("fork.homeoffice.ui.tab", Locale.GERMANY))
  }

  /** Und keine Zeichenkette im Code: was der Reiter zeigt, ist aus dem Buendel geholt. */
  @Test
  fun `die knoepfe tragen die beschriftung aus dem buendel`() = runBlocking {
    withContext(Dispatchers.JavaFx) {
      val projekt = projekt()
      val panel = projekt.panel(projekt.person())
      assertEquals(forkText("fork.homeoffice.ui.applyNow"), panel.applyFromTodayButton.text)
      assertEquals(forkText("fork.homeoffice.ui.applyFrom"), panel.applyFromDateButton.text)
      assertEquals(forkText("fork.homeoffice.ui.addPeriod"), panel.addPeriodButton.text)
      assertEquals(forkText("fork.homeoffice.ui.removePeriod"), panel.removePeriodButton.text)
      assertEquals(forkText("fork.homeoffice.ui.weekNone"), panel.weekSummaryLabel.text,
        "ohne Eintrag muss die Uebersicht sagen, dass nichts eingetragen ist")
      assertNull(panel.messageLabel.text?.ifEmpty { null },
        "ohne Fehler muss die Meldezeile leer sein")
    }
  }
}
