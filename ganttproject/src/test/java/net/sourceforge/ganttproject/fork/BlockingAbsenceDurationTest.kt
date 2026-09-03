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
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import org.junit.jupiter.api.Assertions.assertEquals
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
 * AXIS A REACHES THE DURATION: whose absence stops the task, not merely whose hours it removes.
 *
 * The state this file changes, and the contradiction it removes: `ResourceLevelling` has known
 * axis A since P3 -- a day on which somebody marked `isBlocking` is away is no day for the task,
 * and levelling moves the task off it. `durationDaysWithDaysOff` did not know it. There a holiday
 * only ever set the hours of THAT person to zero; everybody else kept working on a task that
 * cannot proceed without the missing person.
 *
 * The rule, decided on 03.09.2026: on a day on which an INDISPENSABLE person is away, nobody
 * contributes to this task. The day still occupies a day of the task -- what it does not do any
 * more is eat the other people's hours.
 *
 * THE BOUNDARY THAT MUST NOT BLUR, and it is what most of this file guards: this holds
 * EXCLUSIVELY for a person marked `isBlocking`. The ordinary holiday of somebody who is not
 * indispensable stays exactly as it was -- their hours drop out, the day stays, the task grows.
 * That is the sentence in the head comment of `DaysOffDuration.kt`, "five days of work with one
 * day off in the middle is six days long, not five", and it has to stay true. If it blurred,
 * EVERY holiday would stop EVERY task, which is the worst side effect this work can have.
 *
 * WHY THE CHANGE IS SAFE FOR EXISTING PLANS: `isBlocking` defaults to `false` and no file written
 * before this fork carries the attribute, so in every plan that exists today the blocking set is
 * empty and the new rule never fires. [`ohne markierung aendert sich keine einzige dauer`] is the
 * guard for that, and it is green before the change as well -- its worth had to be established by
 * breaking the implementation on purpose, which is recorded in the report.
 */
class BlockingAbsenceDurationTest {

  init {
    // WeekendCalendarImpl and GanttCalendar both need this. Same bootstrap as VacationZeroDaysTest;
    // without it every test here dies in CalendarFactory with a NullPointerException.
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

  /** Monday, 7 September 2026. The whole file counts from here. */
  private val montag = LocalDate.of(2026, 9, 7)
  private val mittwoch = montag.plusDays(2)
  private val donnerstag = montag.plusDays(3)

  /** A project with a real resource manager, a real calendar and real Tasks, as in [WorkWeekEmptySectionTest]. */
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
     * `TaskManagerImpl.deriveDurationWithDaysOff` builds exactly this one. Not a hand-written
     * weekend lambda, so that a failure here cannot be an artefact of the test's own calendar.
     */
    fun arbeitstag(): (LocalDate) -> Boolean = workingDayTest(taskManager.calendar)

    fun dauer(vorgang: Task, start: LocalDate = LocalDate.of(2026, 9, 7)): Int? =
      vorgang.durationDaysWithDaysOff(taskProperties, resourceProperties, start, arbeitstag())
  }

  private fun HumanResource.urlaubAm(vararg tage: LocalDate) {
    tage.forEach {
      // The end of a day-off interval is EXCLUSIVE -- see the head comment of DaysOffDuration.kt.
      this.addDaysOff(
        GanttDaysOff(GanttCalendar.fromLocalDate(it), GanttCalendar.fromLocalDate(it.plusDays(1))))
    }
  }

  private fun Task.zuordnen(person: HumanResource, last: Float = 100f, blockierend: Boolean = false,
                           ohneAufwand: Boolean = false) {
    this.assignmentCollection.addAssignment(person).apply {
      load = last
      isBlocking = blockierend
      isNoEffort = ohneAufwand
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 1. The guard for every plan that exists today: without a single tick, nothing changes.
  // ---------------------------------------------------------------------------------------------

  /**
   * SEVEN SHAPES, each with the duration it had BEFORE axis A reached this computation, written
   * out as a number so that the check cannot pass by comparing two results of the same broken
   * rule against each other.
   *
   * Three of the seven carry a holiday, and those are the ones that bite: if the new rule fired
   * for an unmarked person -- if the blocking set were read as "everybody" or as "everybody with a
   * holiday" -- shapes 2, 4 and 7 would change and this test would go red.
   */
  @Test
  fun `ohne markierung aendert sich keine einzige dauer`() {
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
      Triple("zwei personen, eine davon mittwochs im Urlaub", 3) { p ->
        val a = p.person("A", 8.0)
        val b = p.person("B", 8.0)
        b.urlaubAm(mittwoch)
        p.vorgang("v4", montag, 40.0).also { it.zuordnen(a); it.zuordnen(b) }
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

    // COLLECTED RATHER THAN ASSERTED ONE BY ONE, deliberately: a break in the computation
    // typically hits several shapes at once, and a check that stops at the first of them tells
    // whoever reads the failure less than it knows. The probes recorded in the report were read
    // off this list.
    val abweichungen = faelle.mapNotNull { (name, erwartet, bau) ->
      val projekt = Projekt()
      val vorgang = bau(projekt)
      assertTrue(vorgang.assignments.none { it.isBlocking },
        "$name: der Fall setzt selbst eine Markierung -- dann prueft er nicht, was er zu pruefen " +
          "vorgibt")
      val gemessen = projekt.dauer(vorgang)
      if (gemessen == erwartet) null else "$name: erwartet $erwartet, war $gemessen"
    }
    assertEquals(emptyList<String>(), abweichungen,
      "in einem Plan ohne eine einzige Markierung muss diese Aenderung auf den Tag genau " +
        "dieselben Dauern liefern wie vorher")
  }

  // ---------------------------------------------------------------------------------------------
  // 2. The boundary: the ordinary holiday keeps working exactly as it did.
  // ---------------------------------------------------------------------------------------------

  /**
   * The sentence from the head comment of `DaysOffDuration.kt`, as a check: five days of work with
   * one day off in the middle are SIX days long, not five. The person is not marked -- their hours
   * drop out of Wednesday, the day itself stays.
   */
  @Test
  fun `der urlaub einer nicht zwingenden person nimmt nur ihre stunden, nicht den tag`() {
    val projekt = Projekt()
    val a = projekt.person("A", 8.0)
    val b = projekt.person("B", 8.0)
    val vorgang = projekt.vorgang("Arbeit", montag, 40.0)
    vorgang.zuordnen(a)
    vorgang.zuordnen(b, ohneAufwand = true)

    assertEquals(5, projekt.dauer(vorgang), "Vorbedingung: 40 h bei 8 h/Tag sind fuenf Arbeitstage")

    a.urlaubAm(mittwoch)
    assertEquals(6, projekt.dauer(vorgang),
      "fuenf Tage Arbeit mit einem Urlaubstag mittendrin sind sechs Tage lang, nicht fuenf")
  }

  /**
   * The same boundary where it is dangerous: the non-marked person's holiday must NOT take the
   * other person's hours with it. Two people at 8 h, 40 h of effort, B away on Wednesday --
   * Mon 16, Tue 32, Wed 8 (A alone) makes 40, so THREE days. If the day fell out whole, it would
   * be four.
   */
  @Test
  fun `der urlaub einer nicht zwingenden person laesst die anderen weiterarbeiten`() {
    val projekt = Projekt()
    val a = projekt.person("A", 8.0)
    val b = projekt.person("B", 8.0)
    b.urlaubAm(mittwoch)
    val vorgang = projekt.vorgang("Arbeit", montag, 40.0)
    vorgang.zuordnen(a)
    vorgang.zuordnen(b)

    assertEquals(3, projekt.dauer(vorgang),
      "B ist nicht zwingend noetig; A arbeitet am Mittwoch weiter, und Mo 16 + Di 16 + Mi 8 sind 40")
  }

  // ---------------------------------------------------------------------------------------------
  // 3. The change itself: a blocking absence nulls the whole day.
  // ---------------------------------------------------------------------------------------------

  /**
   * The same plan as the check just above, with ONE difference: B is marked as indispensable.
   * Now Wednesday contributes nothing at all, A's eight hours included, and the task needs a
   * fourth day. Both halves stand in one test on purpose -- the number 3 is what the number 4 has
   * to be read against.
   */
  @Test
  fun `der urlaub einer zwingend noetigen person nimmt den ganzen tag`() {
    val projekt = Projekt()
    val a = projekt.person("A", 8.0)
    val b = projekt.person("B", 8.0)
    b.urlaubAm(mittwoch)

    val ohneMarkierung = projekt.vorgang("ohne", montag, 40.0)
    ohneMarkierung.zuordnen(a)
    ohneMarkierung.zuordnen(b)

    val mitMarkierung = projekt.vorgang("mit", montag, 40.0)
    mitMarkierung.zuordnen(a)
    mitMarkierung.zuordnen(b, blockierend = true)

    assertEquals(3, projekt.dauer(ohneMarkierung),
      "Vergleichsfall: ohne Markierung arbeitet A am Mittwoch weiter")
    assertEquals(4, projekt.dauer(mitMarkierung),
      "B ist zwingend noetig und fehlt am Mittwoch -- dann traegt an dem Tag NIEMAND etwas bei, " +
        "auch A nicht, und der Aufwand braucht einen vierten Tag")
  }

  /**
   * THE CASE THAT JUSTIFIES THE WHOLE AXIS: somebody who has to be present without doing any of
   * the work -- supervision, an acceptance, a hand-over. Axis B is set (they contribute no hours),
   * axis A is set (without them nothing happens). Their holiday must take the day even though
   * their hours are zero to begin with.
   *
   * This is exactly the case the axis B filter in `durationDaysWithDaysOff` used to swallow: the
   * assignment was dropped whole, and their day-off list went with it.
   */
  @Test
  fun `eine zwingend noetige aufsicht ohne eigene stunden nimmt den tag trotzdem mit`() {
    val projekt = Projekt()
    val a = projekt.person("A", 8.0)
    val aufsicht = projekt.person("Aufsicht", 8.0)
    aufsicht.urlaubAm(mittwoch)

    val nurZuschauer = projekt.vorgang("nur Zuschauer", montag, 40.0)
    nurZuschauer.zuordnen(a)
    nurZuschauer.zuordnen(aufsicht, ohneAufwand = true)

    val zwingend = projekt.vorgang("zwingende Aufsicht", montag, 40.0)
    zwingend.zuordnen(a)
    zwingend.zuordnen(aufsicht, ohneAufwand = true, blockierend = true)

    assertEquals(5, projekt.dauer(nurZuschauer),
      "Vergleichsfall: wer nur zuschaut und nicht zwingend noetig ist, dessen Urlaub aendert nichts")
    assertEquals(6, projekt.dauer(zwingend),
      "die Aufsicht leistet selbst keine Stunden, aber ohne sie geht nichts -- ihr Urlaubstag " +
        "muss den Tag trotzdem auf null setzen")
  }

  /**
   * The mark ALONE changes nothing. Without an absence there is nothing to block, and a plan in
   * which everybody is marked but nobody is away has to compute exactly as before.
   */
  @Test
  fun `die markierung allein ohne abwesenheit aendert nichts`() {
    val projekt = Projekt()
    val a = projekt.person("A", 8.0)
    val b = projekt.person("B", 8.0)
    val vorgang = projekt.vorgang("Arbeit", montag, 40.0)
    vorgang.zuordnen(a, blockierend = true)
    vorgang.zuordnen(b, blockierend = true)

    assertEquals(3, projekt.dauer(vorgang),
      "beide sind markiert, aber niemand ist weg -- 40 h bei 16 h/Tag bleiben drei Tage")
  }

  /**
   * A blocking person's holiday OUTSIDE the task changes nothing either. Otherwise the rule would
   * read "somebody indispensable has a holiday somewhere" instead of "is away on this day".
   */
  @Test
  fun `ein urlaub der zwingenden person ausserhalb des vorgangs bleibt folgenlos`() {
    val projekt = Projekt()
    val a = projekt.person("A", 8.0)
    val b = projekt.person("B", 8.0)
    // Four weeks after the task, which is over on Wednesday.
    b.urlaubAm(montag.plusDays(28))
    val vorgang = projekt.vorgang("Arbeit", montag, 40.0)
    vorgang.zuordnen(a)
    vorgang.zuordnen(b, blockierend = true)

    assertEquals(3, projekt.dauer(vorgang),
      "der Urlaub liegt weit hinter dem Vorgang; er darf ihn nicht verlaengern")
  }

  // ---------------------------------------------------------------------------------------------
  // 4. Several blocking people: the day falls out once.
  // ---------------------------------------------------------------------------------------------

  /**
   * Two indispensable people away on the SAME day. The day is lost ONCE. A duration that grew by
   * two days would mean the computation counted absences instead of days -- and with five people
   * off on one Wednesday the task would run a week longer for a single missing day.
   *
   * The second half is the counter-check without which the first would also pass if the second
   * person were simply ignored: away on DIFFERENT days, two days are lost.
   */
  @Test
  fun `zwei zwingende personen am selben tag kosten den tag nur einmal`() {
    val projekt = Projekt()
    val a = projekt.person("A", 8.0)
    val einsMit = projekt.person("Aufsicht 1", 8.0)
    val zweiMit = projekt.person("Aufsicht 2", 8.0)
    einsMit.urlaubAm(mittwoch)
    zweiMit.urlaubAm(mittwoch)

    val gleicherTag = projekt.vorgang("gleicher Tag", montag, 40.0)
    gleicherTag.zuordnen(a)
    gleicherTag.zuordnen(einsMit, ohneAufwand = true, blockierend = true)
    gleicherTag.zuordnen(zweiMit, ohneAufwand = true, blockierend = true)

    assertEquals(6, projekt.dauer(gleicherTag),
      "beide fehlen am selben Mittwoch -- der Tag faellt einmal aus, nicht zweimal; sonst waere " +
        "die Dauer 7")

    val projekt2 = Projekt()
    val a2 = projekt2.person("A", 8.0)
    val einsGetrennt = projekt2.person("Aufsicht 1", 8.0)
    val zweiGetrennt = projekt2.person("Aufsicht 2", 8.0)
    einsGetrennt.urlaubAm(mittwoch)
    zweiGetrennt.urlaubAm(donnerstag)

    val verschiedeneTage = projekt2.vorgang("verschiedene Tage", montag, 40.0)
    verschiedeneTage.zuordnen(a2)
    verschiedeneTage.zuordnen(einsGetrennt, ohneAufwand = true, blockierend = true)
    verschiedeneTage.zuordnen(zweiGetrennt, ohneAufwand = true, blockierend = true)

    assertEquals(7, projekt2.dauer(verschiedeneTage),
      "Gegenprobe: fehlen sie an verschiedenen Tagen, gehen auch zwei Tage verloren -- sonst " +
        "wuerde die zweite Person schlicht uebersehen")
  }

  // ---------------------------------------------------------------------------------------------
  // 5. The freed hours -- what can be measured and what cannot.
  // ---------------------------------------------------------------------------------------------

  /**
   * WHAT THE RULE IS FOR, in Natalie's words on 03.09.2026: „wenn b nicht an dem Vorgang arbeiten
   * kann weil a nicht da ist, kann b ja in der zeit was anderes machen".
   *
   * WHAT IS MEASURABLE HERE: that the task does not DRAW those hours. Without the mark the task is
   * finished on Wednesday, having used A's eight Wednesday hours; with the mark it is not, and
   * exactly those eight hours are the difference. The step from three days to four IS the
   * measurement -- it exists only because the hours were not taken.
   *
   * WHAT IS NOT MEASURABLE IN TODAY'S BUILD, and this is said rather than papered over with a
   * green check: nowhere does the program keep a person's remaining hours PER DAY such that one
   * could watch them turn up on another task. `ResourceLevelling` books `task.loadIn(pool)` over
   * every day of the task's window, so its `used` map counts the blocked day as occupied for
   * everybody on the task regardless. The release is therefore real in this computation and
   * invisible in the rest of the program. That gap is named in the report, not fixed here.
   */
  @Test
  fun `die stunden der uebrigen werden an einem blockierten tag nicht verbraucht`() {
    val projekt = Projekt()
    val a = projekt.person("A", 8.0)
    val b = projekt.person("B", 8.0)
    b.urlaubAm(mittwoch)

    // 40 h at 16 h a day: Mon 16, Tue 32 -- eight hours are left for Wednesday, and A alone could
    // deliver exactly those eight. That is the whole point of these numbers.
    val vorgang = projekt.vorgang("Arbeit", montag, 40.0)
    vorgang.zuordnen(a)
    vorgang.zuordnen(b, blockierend = true)

    assertEquals(4, projekt.dauer(vorgang),
      "die acht Stunden, die A am Mittwoch haette leisten koennen, sind nicht in diesen Vorgang " +
        "geflossen -- deshalb braucht er einen vierten Tag")

    // And the sum is right: on Thursday A and B together deliver 16 h, of which 8 are needed. The
    // task never draws more than its effort.
    val ohne = projekt.vorgang("Vergleich", montag, 40.0)
    ohne.zuordnen(a)
    ohne.zuordnen(b)
    assertEquals(3, projekt.dauer(ohne),
      "Gegenprobe: ohne die Markierung fliessen A's acht Mittwochsstunden sehr wohl hinein")
  }

  // ---------------------------------------------------------------------------------------------
  // 6. The guard against the failure mode nothing is visible about.
  // ---------------------------------------------------------------------------------------------

  /**
   * An indispensable person away for a hundred years. The walk must give up and return `null` --
   * an empty answer, which the caller reads as "keep the duration you have" -- instead of running
   * for ever. Same shape as `WorkWeekEmptySectionTest`: the second guard, `MAX_DAYS * 2` loop
   * passes over CALENDAR days, is what ends it.
   */
  @Test
  fun `eine unbegrenzte abwesenheit gibt auf statt endlos zu laufen`() {
    val projekt = Projekt()
    val a = projekt.person("A", 8.0)
    val b = projekt.person("B", 8.0)
    b.addDaysOff(GanttDaysOff(
      GanttCalendar.fromLocalDate(montag), GanttCalendar.fromLocalDate(montag.plusYears(100))))
    val vorgang = projekt.vorgang("Arbeit", montag, 40.0)
    vorgang.zuordnen(a)
    vorgang.zuordnen(b, blockierend = true)

    val ergebnis = assertTimeoutPreemptively(Duration.ofSeconds(20), ThrowingSupplier {
      projekt.dauer(vorgang)
    })
    assertNull(ergebnis,
      "kein Tag der naechsten hundert Jahre traegt etwas bei; die Rechnung muss aufgeben, statt " +
        "eine Dauer zu erfinden oder stehen zu bleiben")
  }
}
