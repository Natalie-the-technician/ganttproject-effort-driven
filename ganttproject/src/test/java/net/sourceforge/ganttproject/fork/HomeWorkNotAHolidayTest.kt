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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * ═══ A HOME-WORKING DAY IS NOT A DAY OFF ═══
 *
 * THE ONE CHECK THAT AIMS STRAIGHT AT THE MOST EXPENSIVE MISTAKE THIS PACKAGE CAN MAKE, and the
 * reason it stands in a file of its own with its name on the door.
 *
 * `HumanResource.daysOffRanges()` in `DaysOffDuration.kt` is the COMMON READER of the absences. The
 * one list it returns feeds three consumers with three different meanings: `Share.daysOff` (this
 * person's hours are gone), `EffortInputs.blockingDaysOff` (the whole day of the task is dead) and,
 * through `availabilityTest`, the levelling's window search (this day is no good). Adding
 * home-office ranges there is a FOUR-CHARACTER EDIT. It compiles. It moves all three at once. And
 * every one of the guards that watch „an unmarked plan is laid exactly as before" stays GREEN under
 * it, because their plans carry no home office at all.
 *
 * So this is the check that has to be red instead, and its statement is the plainest one in the
 * package: a person who works from home is AT WORK. Given a task that does not need anybody on the
 * premises, their home-office days must change NOTHING — not the duration, not the placement.
 *
 * ═══ WHY EACH TEST CARRIES A HOLIDAY BESIDE IT ═══
 *
 * „Nothing changes" is the answer a dead mechanism gives too. Every check below therefore runs the
 * SAME plan a second time with a real HOLIDAY on the same day and demands that it DOES change. If
 * the wiring were unplugged — no home office read at all, or the availability channel not handed
 * through — the first half would still be green and the second half would say so.
 *
 * 7 September 2026 is a Monday; the 9th is the Wednesday of that week.
 */
class HomeWorkNotAHolidayTest {

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

  private class Projekt {
    val builder: TestSetupHelper.TaskManagerBuilder =
      TestSetupHelper.newTaskManagerBuilder().withCalendar(WeekendCalendarImpl())
    val taskManager: TaskManager = builder.build()
    val resourceManager: HumanResourceManager = builder.resourceManager
    val resourceProperties: CustomPropertyManager get() = resourceManager.customPropertyManager
    val taskProperties: CustomPropertyManager get() = taskManager.customPropertyManager
    private var naechsteId = 0

    fun person(name: String, stundenProTag: Double = 8.0): HumanResource =
      resourceManager.create(name, naechsteId++).also {
        it.setValue(EffortDrivenProperties.findOrCreateResourceHours(resourceProperties),
          stundenProTag)
      }

    fun vorgang(name: String, start: LocalDate, aufwand: Double, tage: Long = 1L): Task =
      taskManager.newTaskBuilder().withName(name).withStartDate(start.toModelDate())
        .withDuration(taskManager.createLength(tage)).build().also {
          it.customValues.setValue(
            EffortDrivenProperties.findOrCreateTaskEffort(taskProperties), aufwand)
        }

    fun dauer(vorgang: Task, start: LocalDate = LocalDate.of(2026, 9, 7)): Int? =
      vorgang.durationDaysWithDaysOff(taskProperties, resourceProperties, start,
        workingDayTest(taskManager.calendar))

    /** Exactly the call `LevellingAction.weiter` makes, including B3's new channel. */
    fun nivellieren(ab: LocalDate): LevelResult = levelTasks(
      collectLevelTasks(taskManager, taskProperties, resourceProperties, ab, true),
      ab,
      workingDaysPerTask(taskManager, resourceProperties),
      durationAtStart(taskManager, taskProperties, resourceProperties),
      isAvailable = availabilityTest(resourceManager),
      isAtWorkplace = presenceTest(resourceManager, resourceProperties))
  }

  private fun HumanResource.zuHauseAm(projekt: Projekt, tag: LocalDate) {
    this.setHomeOfficePeriods(projekt.resourceProperties,
      HomeOfficePeriods.parse(tag.toString()).periods)
  }

  private fun HumanResource.urlaubAm(tag: LocalDate) {
    this.addDaysOff(
      GanttDaysOff(GanttCalendar.fromLocalDate(tag), GanttCalendar.fromLocalDate(tag.plusDays(1))))
  }

  private fun Task.zuordnen(person: HumanResource, blockierend: Boolean = false) {
    this.assignmentCollection.addAssignment(person).apply {
      load = 100f
      isBlocking = blockierend
    }
  }

  private fun Task.markieren(projekt: Projekt, mark: HomeWorkMark) {
    applyHomeWorkMark(this.customValues, projekt.taskProperties, mark)
  }

  // ===============================================================================================
  // The duration channel.
  // ===============================================================================================

  /**
   * THE DURATION: same person, same day, task NOT marked — the answer is the answer of the plan
   * that has no home office at all. And the same day as a HOLIDAY does lengthen it.
   *
   * All three states of the mark that are not ON_SITE are asked, because „nobody has decided" and
   * „can be done from home" have to behave alike and both have to behave like nothing at all.
   */
  @Test
  fun `ein heimarbeitstag verlaengert einen nicht markierten vorgang nicht`() {
    val ohneAlles = Projekt().let { p ->
      val a = p.person("A")
      val v = p.vorgang("Auswertung", montag, 40.0)
      v.zuordnen(a, blockierend = true)
      p.dauer(v)
    }
    assertEquals(5, ohneAlles, "Festpunkt: 40 h bei 8 h/Tag ohne alles sind fuenf Tage")

    listOf(HomeWorkMark.NOT_DECIDED, HomeWorkMark.FROM_HOME).forEach { mark ->
      val projekt = Projekt()
      val a = projekt.person("A")
      a.zuHauseAm(projekt, mittwoch)
      val v = projekt.vorgang("Auswertung", montag, 40.0)
      v.zuordnen(a, blockierend = true)
      v.markieren(projekt, mark)
      assertEquals(ohneAlles, projekt.dauer(v),
        "der Vorgang ist im Zustand $mark; ein Heimarbeitstag ist KEIN freier Tag und darf an " +
          "seiner Dauer nichts aendern")
    }

    // THE POSITIVE CONTROL. Without it „nothing changes" would also be what a dead mechanism says.
    val mitUrlaub = Projekt().let { p ->
      val a = p.person("A")
      a.urlaubAm(mittwoch)
      val v = p.vorgang("Auswertung", montag, 40.0)
      v.zuordnen(a, blockierend = true)
      p.dauer(v)
    }
    assertEquals(6, mitUrlaub,
      "Gegenprobe: derselbe Mittwoch als URLAUB verlaengert sehr wohl -- waere auch das " +
        "unveraendert, wuerde dieser Test einen abgeklemmten Mechanismus fuer richtig erklaeren")
    assertNotEquals(ohneAlles, mitUrlaub, "Urlaub und Heimarbeit duerfen nicht dasselbe tun")
  }

  // ===============================================================================================
  // The window-search channel.
  // ===============================================================================================

  /**
   * THE PLACEMENT: the window search must not move an unmarked task off a home-office day — and
   * must move it off a holiday.
   *
   * A three-day task from Monday, its indispensable person at home on Wednesday. Marked ON_SITE the
   * search would have to jump past Wednesday; unmarked it stays on Monday, because nobody is
   * missing. The holiday beside it is the positive control: there somebody IS missing, and the task
   * jumps.
   *
   * THIS IS THE HALF THAT `availabilityTest` COULD KILL. Home-office ranges added to that map would
   * make the unmarked case jump exactly like the holiday case, and this is the check that says so.
   */
  @Test
  fun `die fenstersuche schiebt einen nicht markierten vorgang nicht vom heimarbeitstag weg`() {
    val heimarbeit = Projekt()
    val h = heimarbeit.person("A")
    h.zuHauseAm(heimarbeit, mittwoch)
    val vh = heimarbeit.vorgang("Auswertung", montag, 24.0, tage = 3L)
    vh.zuordnen(h, blockierend = true)

    val urlaub = Projekt()
    val u = urlaub.person("A")
    u.urlaubAm(mittwoch)
    val vu = urlaub.vorgang("Auswertung", montag, 24.0, tage = 3L)
    vu.zuordnen(u, blockierend = true)

    val heimStart = heimarbeit.nivellieren(montag).starts[vh.taskID.toString()]
    val urlaubStart = urlaub.nivellieren(montag).starts[vu.taskID.toString()]

    assertEquals(montag, heimStart,
      "niemand fehlt: A arbeitet am Mittwoch, nur von zu Hause, und der Vorgang verlangt keine " +
        "Anwesenheit -- er bleibt am Montag liegen")
    assertNotEquals(montag, urlaubStart,
      "Gegenprobe: derselbe Mittwoch als URLAUB muss den Vorgang sehr wohl verschieben -- sonst " +
        "misst die erste Haelfte einen abgeklemmten Kanal")
  }

  /**
   * AND WITH THE MARKING IT DOES MOVE. The same plan as above, the task marked ON_SITE: now the
   * home-office day is a day the task cannot lie on, and the search jumps it.
   *
   * Together with the check above this is the whole of condition (a): the same person, the same
   * day, two different tasks, two different answers.
   */
  @Test
  fun `mit markierung schiebt die fenstersuche den vorgang sehr wohl weg`() {
    val projekt = Projekt()
    val a = projekt.person("A")
    a.zuHauseAm(projekt, mittwoch)
    val v = projekt.vorgang("Abnahme", montag, 24.0, tage = 3L)
    v.zuordnen(a, blockierend = true)
    v.markieren(projekt, HomeWorkMark.ON_SITE)

    val start = projekt.nivellieren(montag).starts[v.taskID.toString()]
    assertTrue(start != null && start.isAfter(mittwoch),
      "der Vorgang braucht jemanden vor Ort und A ist am Mittwoch zu Hause -- kein Fenster, das " +
        "den Mittwoch enthaelt, ist brauchbar; gefunden wurde $start")
  }

  // ===============================================================================================
  // The two floors under the guarantee that the checks above cannot reach, and the trap that
  // hides the second one.
  // ===============================================================================================

  /**
   * FALLE 1, AS A PAIR OF MEASUREMENTS: a plan with a MARKING but with NO home office anywhere must
   * not move — and the reason it does not move is not the one you would guess.
   *
   * `presenceTest` has a fast path: nobody with anything entered means no lookup takes place at all,
   * and the channel hands back „everybody is at their workplace" for every question. That fast path
   * is a SECOND floor under the guarantee, independent of the marking default and of the channel
   * default, and it is also a lid: while it is there, a mistake in the lookup BELOW it cannot be
   * seen by any plan that has no home office.
   *
   * MEASURED ON 05.09.2026, and this is what makes the pair worth keeping. The elvis fallback in
   * that lookup — `?: true`, „a name I do not know is not at home" — was broken to `?: false` and
   * the whole suite of 1191 checks stayed GREEN. Broken a second time WITH the fast path removed,
   * this check goes red. Same mistake, two verdicts; the fast path was the difference.
   *
   * So whoever sharpens a guard against this channel has to take the fast path out at the same
   * time, or the guard reports green about a lookup it never reached.
   */
  @Test
  fun `ein markierter vorgang ohne jede heimarbeit im plan bewegt sich nicht`() {
    val projekt = Projekt()
    val a = projekt.person("A")
    // DELIBERATELY NO HOME OFFICE ANYWHERE. That is the whole setup: the fast path is taken.
    val v = projekt.vorgang("Abnahme", montag, 24.0, tage = 3L)
    v.zuordnen(a, blockierend = true)
    v.markieren(projekt, HomeWorkMark.ON_SITE)

    val ergebnis = projekt.nivellieren(montag)
    assertEquals(montag, ergebnis.starts[v.taskID.toString()],
      "der Vorgang ist markiert, aber niemand im Plan arbeitet von zu Hause -- er muss liegen " +
        "bleiben, wo er liegt")
    assertTrue(ergebnis.conflicts.isEmpty(),
      "und es darf nichts gemeldet werden, gemeldet wurde: ${ergebnis.conflicts}")
  }

  /**
   * A NAME THE CHANNEL DOES NOT KNOW IS AT THE WORKPLACE, not at home.
   *
   * Two such names really occur: [SHARED_POOL], the pool of the tasks nobody is assigned to, which
   * is not a person and cannot work from home at all, and a resource deleted between the conversion
   * and the calculation. Neither is a home office, and inventing one out of a gap in knowledge would
   * push every unassigned task off every day of the plan.
   *
   * ASKED WITH A REAL HOME OFFICE IN THE PLAN, on purpose: without one the fast path answers and
   * this check would measure the fast path instead of the fallback it is about.
   */
  @Test
  fun `ein unbekannter name gilt als am arbeitsplatz`() {
    val projekt = Projekt()
    val a = projekt.person("A")
    a.zuHauseAm(projekt, mittwoch)

    val test = presenceTest(projekt.resourceManager, projekt.resourceProperties)
    assertTrue(test(SHARED_POOL, mittwoch),
      "der Sammeltopf der Vorgaenge ohne Zuordnung ist keine Person und kann nicht von zu Hause " +
        "arbeiten -- er muss als anwesend gelten")
    assertTrue(test("9999", mittwoch),
      "eine Person, die es nicht (mehr) gibt, ist keine Heimarbeiterin -- aus einer Wissensluecke " +
        "eine Abwesenheit zu erfinden waere die schlechtere Antwort")
    // And the precondition, so that a fast path taken by accident cannot make the two lines above
    // pass for the wrong reason.
    assertTrue(!test(a.id.toString(), mittwoch),
      "Vorbedingung: A IST am Mittwoch zu Hause, der Schnellweg darf hier nicht gegriffen haben")
  }

  // ===============================================================================================
  // W13 — the crossing, and the cache that would quietly answer for the wrong task.
  // ===============================================================================================

  /**
   * W13: TWO TASKS, DIFFERENT MARKINGS, THE SAME PERSON, THE SAME DAY — DIFFERENT ANSWERS, IN ONE
   * RUN.
   *
   * Home working is the first quantity in this fork whose answer depends on the PERSON, the DAY and
   * the TASK all three. Every cache the fork holds today is keyed more coarsely than that, and the
   * tempting short cut — one map from day to „is somebody indispensable at home today" — holds ONE
   * answer per day and is therefore wrong for two differently marked tasks. No check that puts a
   * single marking into a run can see that; this one puts two.
   *
   * It is the same mistake `RememberedWorkingDays` documents one level down, where a map keyed by
   * the day alone handed the second asker whatever the first one asked.
   */
  @Test
  fun `zwei vorgaenge mit verschiedenen markierungen bekommen verschiedene antworten`() {
    val projekt = Projekt()
    val a = projekt.person("A")
    a.zuHauseAm(projekt, mittwoch)

    val markiert = projekt.vorgang("Abnahme", montag, 40.0)
    markiert.zuordnen(a, blockierend = true)
    markiert.markieren(projekt, HomeWorkMark.ON_SITE)

    val unmarkiert = projekt.vorgang("Auswertung", montag, 40.0)
    unmarkiert.zuordnen(a, blockierend = true)

    // BOTH THROUGH THE SAME `durationAtStart`, which is where the per-task cache lives. Asking the
    // two through two separate calls would build two caches and could not see the mistake.
    val dauerVon = durationAtStart(projekt.taskManager, projekt.taskProperties,
      projekt.resourceProperties)
    val aufgaben = collectLevelTasks(projekt.taskManager, projekt.taskProperties,
      projekt.resourceProperties, montag, true).associateBy { it.id }

    val markiertDauer = dauerVon(aufgaben.getValue(markiert.taskID.toString()), montag)
    val unmarkiertDauer = dauerVon(aufgaben.getValue(unmarkiert.taskID.toString()), montag)

    assertEquals(6, markiertDauer,
      "der markierte Vorgang verliert den Mittwoch: sechs Tage")
    assertEquals(5, unmarkiertDauer,
      "der unmarkierte Vorgang derselben Person am selben Tag behaelt ihn: fuenf Tage. Bekommt " +
        "er dieselbe Antwort wie der markierte, ist ein Zwischenspeicher zu grob geschluesselt")

    // Asked in the other order as well: a cache that answers the FIRST asker correctly and the
    // second one wrongly is not caught by a single order.
    val nochmalUnmarkiert = dauerVon(aufgaben.getValue(unmarkiert.taskID.toString()), montag)
    val nochmalMarkiert = dauerVon(aufgaben.getValue(markiert.taskID.toString()), montag)
    assertEquals(5, nochmalUnmarkiert, "die zweite Frage nach demselben Vorgang muss dasselbe sagen")
    assertEquals(6, nochmalMarkiert, "und die dritte auch")
  }
}
