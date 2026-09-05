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
import biz.ganttproject.core.calendar.WeekendCalendarImpl
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.core.time.GanttCalendar
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import java.text.DateFormat
import java.time.Duration
import java.time.LocalDate
import java.util.Locale

/**
 * B3, THE DURATION SIDE: home working reaches the length of a task — and reaches nothing else.
 *
 * THE RULE HAS TWO CONDITIONS and this file is mostly about the boundary between them. A day is no
 * good for a task when the TASK is marked as needing somebody on the premises AND one of the
 * people it cannot proceed without works from home that day. Drop either condition and the result
 * is a plan in which working from home is a holiday — see `HomeWorkPlanning.kt` for why that is
 * the worst thing this package can do, and `HomeWorkNotAHolidayTest` for the check that guards it.
 *
 * DECISION E1, taken by Natalie on 04.09.2026, is the other half of what is measured here: a
 * person who is NOT indispensable and who is at home does not deliver her hours TO THIS TASK on
 * that day — she is working, just not on something that has to be done on the premises. The others
 * carry on. Both halves are needed to pin it down, and both are below: without the first this
 * would be „nothing changes", without the second it would be „the whole day dies".
 *
 * ALL DATES: 7 September 2026 is a Monday. The calendar is `WeekendCalendarImpl`, so Saturday and
 * Sunday are not working days and nothing here has to say so.
 */
class HomeWorkDurationTest {

  init {
    // WeekendCalendarImpl and GanttCalendar both need this. Same bootstrap as
    // BlockingAbsenceDurationTest; without it every test here dies in CalendarFactory.
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
  private val dienstag = montag.plusDays(1)
  private val mittwoch = montag.plusDays(2)

  /** A project with a real resource manager, a real calendar and real tasks. */
  private class Projekt {
    val builder: TestSetupHelper.TaskManagerBuilder =
      TestSetupHelper.newTaskManagerBuilder().withCalendar(WeekendCalendarImpl())
    val taskManager: TaskManager = builder.build()
    val resourceManager: HumanResourceManager = builder.resourceManager
    val resourceProperties: CustomPropertyManager get() = resourceManager.customPropertyManager
    val taskProperties: CustomPropertyManager get() = taskManager.customPropertyManager
    private var naechsteId = 0

    fun person(name: String, stundenProTag: Double): HumanResource =
      resourceManager.create(name, naechsteId++).also {
        it.setValue(EffortDrivenProperties.findOrCreateResourceHours(resourceProperties),
          stundenProTag)
      }

    fun vorgang(name: String, start: LocalDate, aufwand: Double): Task =
      taskManager.newTaskBuilder().withName(name).withStartDate(start.toModelDate())
        .withDuration(taskManager.createLength(1L)).build().also {
          it.customValues.setValue(
            EffortDrivenProperties.findOrCreateTaskEffort(taskProperties), aufwand)
        }

    /**
     * The working-day test the program itself hands to the duration computation --
     * `TaskManagerImpl.deriveDurationWithDaysOff` builds exactly this one, so a failure here
     * cannot be an artefact of a hand-written weekend lambda.
     */
    fun arbeitstag(): (LocalDate) -> Boolean = workingDayTest(taskManager.calendar)

    fun dauer(vorgang: Task, start: LocalDate = LocalDate.of(2026, 9, 7)): Int? =
      vorgang.durationDaysWithDaysOff(taskProperties, resourceProperties, start, arbeitstag())
  }

  /** Home office as PERIODS -- the days named, each as its own one-day period. */
  private fun HumanResource.zuHauseAm(projekt: Projekt, vararg tage: LocalDate) {
    this.setHomeOfficePeriods(projekt.resourceProperties,
      HomeOfficePeriods.parse(tage.joinToString("; ") { it.toString() }).periods)
  }

  /** Home office as a WEEKLY PATTERN -- ISO weekday numbers, 1 is Monday. */
  private fun HumanResource.zuHauseJeden(projekt: Projekt, muster: String) {
    this.setHomeOfficeWeek(projekt.resourceProperties, HomeOfficeWeek.parse(muster).week)
  }

  private fun HumanResource.urlaubAm(vararg tage: LocalDate) {
    tage.forEach {
      this.addDaysOff(
        GanttDaysOff(GanttCalendar.fromLocalDate(it), GanttCalendar.fromLocalDate(it.plusDays(1))))
    }
  }

  private fun Task.markieren(projekt: Projekt, mark: HomeWorkMark) {
    applyHomeWorkMark(this.customValues, projekt.taskProperties, mark)
  }

  private fun Task.zuordnen(person: HumanResource, last: Float = 100f, blockierend: Boolean = false,
                            ohneAufwand: Boolean = false) {
    this.assignmentCollection.addAssignment(person).apply {
      load = last
      isBlocking = blockierend
      isNoEffort = ohneAufwand
    }
  }

  // ===============================================================================================
  // W1 — the guarantee for every plan that exists today.
  // ===============================================================================================

  /**
   * W1: WITHOUT A SINGLE HOME-OFFICE ENTRY AND WITHOUT A SINGLE MARKING, NOT ONE DURATION MOVES.
   *
   * Seven shapes, each against a number WRITTEN OUT rather than computed a second time — a check
   * that compared two runs of the same rule would agree with itself no matter how wrong it was.
   * The numbers are the ones `BlockingAbsenceDurationTest` pinned down before B3 existed and that
   * a full run on `b-integriert` confirmed on 05.09.2026.
   *
   * WHAT THIS GUARD CANNOT SEE, and saying so is the point of writing it down: its plans carry no
   * home office and no marking BY CONSTRUCTION, so every mistake that acts only on a marked plan
   * is invisible to it. That is not a weakness, it is its statement. The mistakes it cannot see are
   * the ones `HomeWorkNotAHolidayTest` and the checks further down are for; anybody who „sharpens"
   * this one by adding a home office to its plans destroys the only thing it says.
   */
  @Test
  fun `ohne heimarbeit und ohne markierung aendert sich keine einzige dauer`() {
    val faelle = listOf<Triple<String, Int, (Projekt) -> Task>>(
      Triple("eine person, 40 h bei 8 h/Tag", 5) { p ->
        val a = p.person("A", 8.0)
        p.vorgang("v1", montag, 40.0).also { it.zuordnen(a) }
      },
      Triple("eine person mit einem Urlaubstag mittendrin", 6) { p ->
        val a = p.person("A", 8.0)
        a.urlaubAm(mittwoch)
        p.vorgang("v2", montag, 40.0).also { it.zuordnen(a) }
      },
      Triple("zwei personen, 40 h bei 16 h/Tag", 3) { p ->
        val a = p.person("A", 8.0)
        val b = p.person("B", 8.0)
        p.vorgang("v3", montag, 40.0).also { it.zuordnen(a); it.zuordnen(b) }
      },
      Triple("eine zwingende person, mittwochs im Urlaub", 6) { p ->
        val a = p.person("A", 8.0)
        a.urlaubAm(mittwoch)
        p.vorgang("v4", montag, 40.0).also { it.zuordnen(a, blockierend = true) }
      },
      Triple("eine person zu 50 Prozent, 20 h bei 4 h/Tag", 5) { p ->
        val a = p.person("A", 8.0)
        p.vorgang("v5", montag, 20.0).also { it.zuordnen(a, last = 50f) }
      },
      Triple("ein Zuschauer (Achse B) verkuerzt nichts", 5) { p ->
        val a = p.person("A", 8.0)
        val z = p.person("Z", 8.0)
        p.vorgang("v6", montag, 40.0).also { it.zuordnen(a); it.zuordnen(z, ohneAufwand = true) }
      },
      Triple("der Urlaub eines Zuschauers (Achse B) verlaengert nichts", 5) { p ->
        val a = p.person("A", 8.0)
        val z = p.person("Z", 8.0)
        z.urlaubAm(mittwoch)
        p.vorgang("v7", montag, 40.0).also { it.zuordnen(a); it.zuordnen(z, ohneAufwand = true) }
      })

    // COLLECTED RATHER THAN ASSERTED ONE BY ONE: a break in the computation typically hits several
    // shapes at once, and a check that stops at the first tells the reader less than it knows.
    val abweichungen = faelle.mapNotNull { (name, erwartet, bau) ->
      val projekt = Projekt()
      val vorgang = bau(projekt)
      assertEquals(HomeWorkMark.NOT_DECIDED, vorgang.homeWorkMark(projekt.taskProperties),
        "$name: der Fall markiert selbst -- dann prueft er nicht, was er zu pruefen vorgibt")
      assertTrue(projekt.resourceManager.resources.all {
        it.homeOffice(projekt.resourceProperties).homeOffice.isEmpty
      }, "$name: der Fall traegt selbst Heimarbeit ein -- dann ist er nicht mehr diese Wache")
      val gemessen = projekt.dauer(vorgang)
      if (gemessen == erwartet) null else "$name: erwartet $erwartet, war $gemessen"
    }
    assertEquals(emptyList<String>(), abweichungen,
      "in einem Plan ohne Heimarbeit und ohne Markierung muss B3 auf den Tag genau dieselben " +
        "Dauern liefern wie vorher")
  }

  // ===============================================================================================
  // W4 / W6 — the rule itself, and that the day is OCCUPIED rather than skipped.
  // ===============================================================================================

  /**
   * W4: THE HOME-WORKING DAY OF AN INDISPENSABLE PERSON TAKES THE WHOLE DAY — and the comparison
   * case beside it is half the check.
   *
   * A alone, 8 h a day, 40 h of work, at home on Wednesday. Marked ON_SITE the task runs Mon, Tue,
   * (Wed dead), Thu, Fri, Mon — SIX days. The same plan without the marking is FIVE, and that is
   * what proves the length hangs on the MARKING and not merely on the day being a Wednesday.
   */
  @Test
  fun `der heimarbeitstag einer zwingenden person nimmt den ganzen tag`() {
    val mitMarkierung = Projekt()
    val a1 = mitMarkierung.person("A", 8.0)
    a1.zuHauseAm(mitMarkierung, mittwoch)
    val v1 = mitMarkierung.vorgang("Abnahme", montag, 40.0)
    v1.zuordnen(a1, blockierend = true)
    v1.markieren(mitMarkierung, HomeWorkMark.ON_SITE)

    val ohneMarkierung = Projekt()
    val a2 = ohneMarkierung.person("A", 8.0)
    a2.zuHauseAm(ohneMarkierung, mittwoch)
    val v2 = ohneMarkierung.vorgang("Abnahme", montag, 40.0)
    v2.zuordnen(a2, blockierend = true)

    assertEquals(5, ohneMarkierung.dauer(v2),
      "Vergleichsfall: ohne Markierung darf die Heimarbeit gar nichts tun -- 40 h bei 8 h/Tag " +
        "sind fuenf Arbeitstage")
    assertEquals(6, mitMarkierung.dauer(v1),
      "der Vorgang braucht jemanden vor Ort, A ist zwingend noetig und am Mittwoch zu Hause: " +
        "Mo, Di, (Mi tot), Do, Fr, Mo sind sechs Tage")
  }

  /**
   * W6: A HOME-WORKING DAY OCCUPIES A DAY OF THE TASK. The task gets LONGER; it does not skip.
   *
   * Two rebuilds look obvious and both are wrong, and both would show here: pulling `days++` into
   * the axis-A branch of the walk, and putting home working into the working-day GRID as though it
   * were a non-working weekday. Either makes Wednesday disappear from the task instead of dying in
   * it, and the answer drops from six to five — which is exactly the answer of the plan that has no
   * home office at all, so the difference to the guard above is the whole measurement.
   */
  @Test
  fun `ein heimarbeitstag belegt einen tag des vorgangs`() {
    val projekt = Projekt()
    val a = projekt.person("A", 8.0)
    val vorgang = projekt.vorgang("Abnahme", montag, 40.0)
    vorgang.zuordnen(a, blockierend = true)
    vorgang.markieren(projekt, HomeWorkMark.ON_SITE)

    val ohneHeimarbeit = projekt.dauer(vorgang)
    assertEquals(5, ohneHeimarbeit, "Vorbedingung: ohne Heimarbeit fuenf Tage")

    a.zuHauseAm(projekt, mittwoch)
    val mitHeimarbeit = projekt.dauer(vorgang)
    assertEquals(6, mitHeimarbeit,
      "der Heimarbeitstag muss einen Tag des Vorgangs BELEGEN, nicht uebersprungen werden -- " +
        "sonst waere die Antwort dieselbe fuenf wie ganz ohne Heimarbeit")
    assertEquals(1, mitHeimarbeit!! - ohneHeimarbeit!!,
      "genau ein Tag laenger, nicht null und nicht zwei")
  }

  // ===============================================================================================
  // W3 — decision E1, in both of its halves.
  // ===============================================================================================

  /**
   * W3, FIRST HALF: THE HOME-WORKING DAY OF A NON-INDISPENSABLE PERSON LETS THE OTHERS CARRY ON.
   *
   * Natalie: „wenn sie es nicht ist, kann der Rest ohne sie weiterarbeiten." A and B at 8 h each,
   * 40 h of work, B at home on Wednesday and NOT marked as indispensable. Mon 16, Tue 32, Wed A
   * alone 8 — that is 40, so THREE days.
   *
   * The break that makes this red is asking the home office of everybody instead of only the
   * blocking people: then Wednesday dies whole, A's eight hours are lost with it, and the answer
   * is four.
   */
  @Test
  fun `der heimarbeitstag einer nicht zwingenden person laesst die anderen weiterarbeiten`() {
    val projekt = Projekt()
    val a = projekt.person("A", 8.0)
    val b = projekt.person("B", 8.0)
    b.zuHauseAm(projekt, mittwoch)
    val vorgang = projekt.vorgang("Abnahme", montag, 40.0)
    vorgang.zuordnen(a)
    vorgang.zuordnen(b)
    vorgang.markieren(projekt, HomeWorkMark.ON_SITE)

    assertEquals(3, projekt.dauer(vorgang),
      "B ist nicht zwingend noetig; A arbeitet am Mittwoch weiter, und Mo 16 + Di 16 + Mi 8 " +
        "sind 40")
  }

  /**
   * W3, SECOND HALF: HER OWN HOURS DO FALL AWAY FOR THIS TASK — that is what makes E1 a decision
   * and not „nothing happens".
   *
   * The same plan with 48 h instead of 40. Without E1 it is Mon 16, Tue 32, Wed 48 — three days.
   * With E1, B contributes nothing on Wednesday: Mon 16, Tue 32, Wed 40, Thu 56 — FOUR.
   *
   * The two halves together are what pins E1 down exactly. The first alone would also pass under
   * reading 2 („nothing changes"), the second alone would also pass if the whole day died.
   */
  @Test
  fun `die stunden einer nicht zwingenden person fallen fuer diesen vorgang weg`() {
    val projekt = Projekt()
    val a = projekt.person("A", 8.0)
    val b = projekt.person("B", 8.0)
    val vorgang = projekt.vorgang("Abnahme", montag, 48.0)
    vorgang.zuordnen(a)
    vorgang.zuordnen(b)
    vorgang.markieren(projekt, HomeWorkMark.ON_SITE)

    assertEquals(3, projekt.dauer(vorgang), "Vorbedingung: 48 h bei 16 h/Tag sind drei Tage")

    b.zuHauseAm(projekt, mittwoch)
    assertEquals(4, projekt.dauer(vorgang),
      "B kann am Mittwoch nicht an einem Vorgang arbeiten, der Anwesenheit verlangt -- ihre acht " +
        "Stunden fallen fuer DIESEN Vorgang an DIESEM Tag weg, und aus drei Tagen werden vier")
  }

  /**
   * And the boundary of E1: on a task that does NOT need anybody on the premises, the same person
   * on the same day delivers her hours as she always did. E1 is a rule with a condition; without
   * the condition it is the deadly mistake.
   */
  @Test
  fun `ohne markierung liefert die person im homeoffice ihre stunden wie immer`() {
    val projekt = Projekt()
    val a = projekt.person("A", 8.0)
    val b = projekt.person("B", 8.0)
    b.zuHauseAm(projekt, mittwoch)
    val vorgang = projekt.vorgang("Auswertung", montag, 48.0)
    vorgang.zuordnen(a)
    vorgang.zuordnen(b)

    assertEquals(3, projekt.dauer(vorgang),
      "der Vorgang ist nicht als 'braucht jemanden vor Ort' markiert -- B arbeitet von zu Hause " +
        "an ihm mit, und 48 h bei 16 h/Tag sind drei Tage")

    vorgang.markieren(projekt, HomeWorkMark.FROM_HOME)
    assertEquals(3, projekt.dauer(vorgang),
      "'geht von zu Hause' ausdruecklich gesagt muss dasselbe bedeuten wie nichts gesagt")
  }

  // ===============================================================================================
  // W8 — the case the whole feature is for: the supervisor who contributes nothing but presence.
  // ===============================================================================================

  /**
   * W8: AN INDISPENSABLE ONLOOKER WITH NO HOURS OF HIS OWN TAKES THE DAY WITH HIM.
   *
   * This is the ordinary case of a task marked „needs somebody on site" — the acceptance, the
   * hand-over, the appointment at the machine. The person who has to be there frequently
   * contributes nothing but their presence: load 0 and `no-effort` set. The axis B filter in
   * `effortInputs` drops such an assignment WHOLE, so home working collected after that filter
   * would be dead in the main case of its own rule.
   *
   * AND THE GUARD ABOVE WOULD STAY GREEN UNDER THAT BREAK, because its plans carry no marking.
   * That is why this check exists separately and why the report has to name it.
   */
  @Test
  fun `eine zwingende aufsicht ohne eigene stunden nimmt den tag trotzdem mit`() {
    val projekt = Projekt()
    val a = projekt.person("A", 8.0)
    val z = projekt.person("Z", 8.0)
    val vorgang = projekt.vorgang("Abnahme", montag, 40.0)
    vorgang.zuordnen(a)
    vorgang.zuordnen(z, last = 0f, blockierend = true, ohneAufwand = true)
    vorgang.markieren(projekt, HomeWorkMark.ON_SITE)

    assertEquals(5, projekt.dauer(vorgang),
      "Vorbedingung: der Zuschauer verkuerzt und verlaengert nichts, solange er da ist")

    z.zuHauseAm(projekt, mittwoch)
    assertEquals(6, projekt.dauer(vorgang),
      "Z traegt keine Stunde bei und ist trotzdem zwingend noetig -- ist er am Mittwoch zu " +
        "Hause, kommt der Vorgang an diesem Tag nicht voran, und aus fuenf Tagen werden sechs")
  }

  // ===============================================================================================
  // The union of the two halves of B1, and the boundary against the holiday.
  // ===============================================================================================

  /**
   * BOTH HALVES OF THE HOME OFFICE REACH THE PLAN, and a day named by both costs ONE day.
   *
   * B1 stores the weekly pattern and the periods separately and unions them in
   * [HomeOffice.worksFromHome]. A reader that took only one of the two would be green on every
   * check that used the other, so both are asked here, and then both at once on the same day.
   */
  @Test
  fun `muster und zeitraum wirken beide, und ein tag mit beidem kostet einmal`() {
    // Only the pattern: every Tuesday.
    val nurMuster = Projekt()
    val a1 = nurMuster.person("A", 8.0)
    a1.zuHauseJeden(nurMuster, "2")
    val v1 = nurMuster.vorgang("Abnahme", montag, 40.0)
    v1.zuordnen(a1, blockierend = true)
    v1.markieren(nurMuster, HomeWorkMark.ON_SITE)
    assertEquals(6, nurMuster.dauer(v1), "das Wochenmuster allein muss wirken")

    // Only the period: Tuesday.
    val nurZeitraum = Projekt()
    val a2 = nurZeitraum.person("A", 8.0)
    a2.zuHauseAm(nurZeitraum, dienstag)
    val v2 = nurZeitraum.vorgang("Abnahme", montag, 40.0)
    v2.zuordnen(a2, blockierend = true)
    v2.markieren(nurZeitraum, HomeWorkMark.ON_SITE)
    assertEquals(6, nurZeitraum.dauer(v2), "der Zeitraum allein muss wirken")

    // Both, on the SAME day -- one home-office day, not two.
    val beides = Projekt()
    val a3 = beides.person("A", 8.0)
    a3.zuHauseJeden(beides, "2")
    a3.zuHauseAm(beides, dienstag)
    val v3 = beides.vorgang("Abnahme", montag, 40.0)
    v3.zuordnen(a3, blockierend = true)
    v3.markieren(beides, HomeWorkMark.ON_SITE)
    assertEquals(6, beides.dauer(v3),
      "derselbe Dienstag aus Muster UND Zeitraum ist EIN Heimarbeitstag und kostet einen Tag, " +
        "nicht zwei")
  }

  /**
   * A DAY THAT IS BOTH A HOLIDAY AND A HOME-OFFICE DAY COSTS ONE DAY, not two.
   *
   * Not a case anybody enters on purpose; it is the check that the two lists are asked with an
   * `or` and not added up. The walk counts DAYS, and a day is dead once.
   */
  @Test
  fun `urlaub und heimarbeit am selben tag kosten den vorgang einen tag`() {
    val projekt = Projekt()
    val a = projekt.person("A", 8.0)
    a.urlaubAm(mittwoch)
    a.zuHauseAm(projekt, mittwoch)
    val vorgang = projekt.vorgang("Abnahme", montag, 40.0)
    vorgang.zuordnen(a, blockierend = true)
    vorgang.markieren(projekt, HomeWorkMark.ON_SITE)

    assertEquals(6, projekt.dauer(vorgang),
      "derselbe Mittwoch als Urlaub UND als Heimarbeitstag kostet einen Tag, nicht zwei")
  }

  // ===============================================================================================
  // Falle 4 and the second half of W12: the upper bound stays generous.
  // ===============================================================================================

  /**
   * A TASK WITH A GREAT DEAL OF HOME WORKING STILL GETS A NUMBER, and one that can never proceed
   * gives up instead of running for ever.
   *
   * `Share.bestHoursPerDay` is an upper bound whose only job is to answer „not possible at all"
   * without walking, and axis A is deliberately not in it: a bound made stricter can turn a
   * computable duration into `null`, and it would do so precisely for the plans with a lot of home
   * working. So the first half here demands a NUMBER where a tightened bound would answer `null`.
   *
   * The second half is the honest `null`: somebody indispensable who is at home EVERY working day
   * makes the task impossible, and the walk has to say so rather than spin. The bound in the walk
   * is what catches it — the same one that catches an unbounded absence.
   */
  @Test
  fun `viel heimarbeit gibt eine dauer, unbegrenzte gibt auf statt endlos zu laufen`() {
    val vielProjekt = Projekt()
    val a = vielProjekt.person("A", 8.0)
    // Every day of the week except Monday. Four working days out of five are spent at home.
    a.zuHauseJeden(vielProjekt, "2,3,4,5")
    val viel = vielProjekt.vorgang("Abnahme", montag, 24.0)
    viel.zuordnen(a, blockierend = true)
    viel.markieren(vielProjekt, HomeWorkMark.ON_SITE)

    val gemessen = assertTimeoutPreemptively(Duration.ofSeconds(20), ThrowingSupplier {
      vielProjekt.dauer(viel)
    })
    assertNotNull(gemessen,
      "24 h bei 8 h/Tag an je einem Montag sind zu schaffen -- die obere Schranke darf daraus " +
        "kein 'keine Dauer ableitbar' machen")
    assertEquals(11, gemessen,
      "nur die Montage bringen den Vorgang voran, acht Stunden je Montag: der dritte Montag " +
        "schliesst die 24 h ab, und in Arbeitstagen gezaehlt ist das der elfte")

    val immerProjekt = Projekt()
    val b = immerProjekt.person("B", 8.0)
    b.zuHauseJeden(immerProjekt, "1,2,3,4,5")
    val nie = immerProjekt.vorgang("Abnahme", montag, 24.0)
    nie.zuordnen(b, blockierend = true)
    nie.markieren(immerProjekt, HomeWorkMark.ON_SITE)

    val unmoeglich = assertTimeoutPreemptively(Duration.ofSeconds(20), ThrowingSupplier {
      immerProjekt.dauer(nie)
    })
    assertNull(unmoeglich,
      "eine zwingende Person, die an jedem Arbeitstag zu Hause ist, macht den Vorgang " +
        "unmoeglich -- die Rechnung muss aufgeben und nicht endlos laufen")
  }
}
