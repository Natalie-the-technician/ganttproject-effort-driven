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

import junit.framework.TestCase
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Capacity levelling, without a running program.
 *
 * Every case checks both directions: that levelling does what it should, AND that it refrains
 * when there is no reason to. A test that only checks "something was moved" is green even when
 * everything is always moved.
 */
class ResourceLevellingTest : TestCase() {

  /** Monday. All calculations in the test start from this week. */
  private val montag: LocalDate = LocalDate.of(2026, 8, 17)

  private val werktags: (LocalDate) -> Boolean =
    { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }

  private fun task(
    id: String, dauer: Int, last: Int = 100, prio: Int = 2, reihe: Int = 0,
    vorgaenger: List<String> = emptyList(),
    fest: LocalDate? = null, fruehestens: LocalDate? = null
    // NAMED arguments, not positional. When `loadPercent` gave way to the per-person `loads`,
    // every value behind it would otherwise have slipped one place along -- and `predecessors`
    // landing in `fixedStart` compiles just as well as the right order does. The same lesson is
    // recorded in `EffortBackfillTest`.
  ) = LevelTask(id = id, orderInPlan = reihe, priority = prio, durationDays = dauer,
    predecessors = vorgaenger, fixedStart = fest, earliestStart = fruehestens,
    loads = mapOf(SHARED_POOL to last))

  /**
   * THE CORE CASE, and the reason this file exists: two Tasks, one person, both full.
   * GanttProject lays both on the same Monday. Here they run one after the other.
   */
  fun testTwoFullTasksRunOneAfterTheOther() {
    val r = levelTasks(listOf(task("a", 3), task("b", 2, reihe = 1)), montag, werktags)
    assertEquals(montag, r.starts["a"])
    assertEquals("b muss nach den drei Tagen von a beginnen", montag.plusDays(3), r.starts["b"])
    assertTrue(r.conflicts.isEmpty())
  }

  /**
   * Counter-check to it: if both fit alongside each other, nothing is moved. Simultaneous work
   * is explicitly allowed.
   */
  fun testTwoHalfTasksRunAtTheSameTime() {
    val r = levelTasks(listOf(task("a", 3, last = 50), task("b", 3, last = 50, reihe = 1)),
      montag, werktags)
    assertEquals(montag, r.starts["a"])
    assertEquals("beide zu 50 % passen nebeneinander", montag, r.starts["b"])
    assertTrue(r.conflicts.isEmpty())
  }

  /** Three at 50 % no longer fit: the third has to wait until one becomes free. */
  fun testThirdHalfTaskHasToWait() {
    val r = levelTasks(
      listOf(task("a", 2, last = 50), task("b", 4, last = 50, reihe = 1), task("c", 1, last = 50, reihe = 2)),
      montag, werktags)
    assertEquals(montag, r.starts["a"])
    assertEquals(montag, r.starts["b"])
    assertEquals("c passt erst, wenn a fertig ist", montag.plusDays(2), r.starts["c"])
  }

  /** More important first, even when it stands further down in the plan. */
  fun testHigherPriorityGoesFirst() {
    val r = levelTasks(
      listOf(task("unwichtig", 2, prio = 1, reihe = 0), task("wichtig", 2, prio = 4, reihe = 1)),
      montag, werktags)
    assertEquals(montag, r.starts["wichtig"])
    assertEquals(montag.plusDays(2), r.starts["unwichtig"])
  }

  /** On equal priority the order in the plan decides, not chance. */
  fun testEqualPriorityFollowsPlanOrder() {
    val r = levelTasks(
      listOf(task("zweiter", 2, prio = 2, reihe = 5), task("erster", 2, prio = 2, reihe = 1)),
      montag, werktags)
    assertEquals(montag, r.starts["erster"])
    assertEquals(montag.plusDays(2), r.starts["zweiter"])
  }

  /** Dependencies still apply, even when capacity would be free. */
  fun testPredecessorIsRespectedEvenWithFreeCapacity() {
    val r = levelTasks(
      listOf(task("erst", 2, last = 10), task("dann", 1, last = 10, reihe = 1, vorgaenger = listOf("erst"))),
      montag, werktags)
    assertEquals(montag, r.starts["erst"])
    assertEquals("Kapazitaet waere frei, die Abhaengigkeit gilt trotzdem",
      montag.plusDays(2), r.starts["dann"])
  }

  /**
   * No work happens over the weekend: three working days from Thursday occupy Thu, Fri and
   * Monday, the successor can only start on Tuesday.
   *
   * TESTED WRONGLY ON THE FIRST ATTEMPT: without a dependency the second Task did not have to
   * wait at all -- Monday to Wednesday were free, and it started on Monday. The test now checks
   * what it is meant to check.
   */
  fun testWeekendIsSkipped() {
    val donnerstag = montag.plusDays(3)
    val r = levelTasks(
      listOf(task("a", 3, fruehestens = donnerstag),
             task("b", 1, reihe = 1, vorgaenger = listOf("a"))),
      montag, werktags)
    assertEquals(donnerstag, r.starts["a"])
    assertEquals("a belegt Do, Fr und Mo -- b kann erst Dienstag", montag.plusDays(8), r.starts["b"])
  }

  /** "Earliest begin" is a lower bound, not a fixing: later is allowed. */
  fun testEarliestStartIsALowerBoundOnly() {
    val mittwoch = montag.plusDays(2)
    val r = levelTasks(
      listOf(task("blockierer", 5), task("spaet", 1, reihe = 1, fruehestens = mittwoch)),
      montag, werktags)
    // plusDays(5) would be a Saturday -- the next working day after the full week is Monday.
    assertEquals("nicht vor Mittwoch, aber der Platz ist erst spaeter frei",
      montag.plusDays(7), r.starts["spaet"])
  }

  /**
   * A fixed date stays, even when the capacity is burst by it -- and exactly that is reported.
   * Decided that way because moving a deadline silently hides the problem.
   */
  fun testFixedDateIsHeldAndTheOverloadIsReported() {
    val r = levelTasks(
      listOf(task("laeuft", 5), task("frist", 1, reihe = 1, fest = montag)),
      montag, werktags)
    assertEquals("der feste Termin bleibt", montag, r.starts["frist"])
    val overload = r.conflicts.filterIsInstance<LevelConflict.Overload>()
    assertEquals(1, overload.size)
    assertEquals(montag, overload[0].day)
    assertEquals(200, overload[0].percent)
  }

  /** If the fixed date lies before the earliest possible one, that is reported as well. */
  fun testUnreachableFixedDateIsReported() {
    val r = levelTasks(
      listOf(task("erst", 5), task("frist", 1, reihe = 1, fest = montag, vorgaenger = listOf("erst"))),
      montag, werktags)
    val problem = r.conflicts.filterIsInstance<LevelConflict.FixedDateNotReachable>()
    assertEquals(1, problem.size)
    assertEquals("frist", problem[0].id)
    assertEquals(montag, problem[0].fixedStart)
    // "erst" occupies Mon to Fri, so the earliest possible is the Monday after.
    assertEquals(montag.plusDays(7), problem[0].earliestPossible)
  }

  /**
   * Counter-check to the two before: a fixed date that fits produces NO report. Otherwise the
   * conflict list would be full at every fixed date and thereby worthless.
   */
  fun testAFixedDateThatFitsReportsNothing() {
    val r = levelTasks(listOf(task("frist", 2, fest = montag)), montag, werktags)
    assertEquals(montag, r.starts["frist"])
    assertTrue("kein Konflikt, wenn der Termin haltbar ist", r.conflicts.isEmpty())
  }

  /** A cycle is reported instead of sending the program into an endless loop. */
  fun testACycleIsReportedInsteadOfHanging() {
    val r = levelTasks(
      listOf(task("a", 1, vorgaenger = listOf("b")), task("b", 1, vorgaenger = listOf("a"))),
      montag, werktags)
    assertTrue(r.starts.isEmpty())
    assertEquals(1, r.conflicts.filterIsInstance<LevelConflict.Cycle>().size)
  }

  /** With no Tasks nothing happens, and without an exception at that. */
  fun testEmptyPlanIsNotAnError() {
    val r = levelTasks(emptyList(), montag, werktags)
    assertTrue(r.starts.isEmpty())
    assertTrue(r.conflicts.isEmpty())
  }

  /**
   * DAYS OFF ARRIVE AND CHANGE NOTHING -- the contract of this stage, stated as sharply as it can
   * be stated: the answer is "absent" for EVERY person on EVERY day, the harshest input the
   * channel accepts, and the plan comes out identical.
   *
   * This is not a placeholder. Up to here levelling did not know about days off at all, and the
   * rule that is to use them ("the absence of a blocking person moves the Task") needs something
   * to be cut against. What is pinned here is that laying the channel did not already move
   * something quietly -- the version that computes with it will be measured against exactly this
   * comparison.
   *
   * IT ALSO STAYS TRUE AFTERWARDS, and that is deliberate: the coming rule takes hold on people
   * marked as BLOCKING, and none of the Tasks here carries such a marking. Whoever builds the
   * rule may leave this test as it stands; if it goes red, the rule has taken hold on somebody it
   * was not supposed to take hold on.
   */
  fun testDaysOffArriveWithoutMovingAnything() {
    val plan = listOf(
      task("a", 3), task("b", 2, reihe = 1), task("c", 4, last = 50, reihe = 2),
      task("d", 2, reihe = 3, vorgaenger = listOf("a")))
    val ohne = levelTasks(plan, montag, werktags)
    val mit = levelTasks(plan, montag, werktags, isAvailable = { _, _ -> false })

    assertEquals("kein Termin darf sich bewegen", ohne.starts, mit.starts)
    assertEquals("keine Dauer darf sich aendern", ohne.durations, mit.durations)
    assertEquals("keine Meldung darf entstehen", ohne.conflicts, mit.conflicts)
  }

  /**
   * AXIS A, MEASURED ON THE BARE CALCULATION: the marking, and only the marking, makes a day off
   * count. The same plan, the same answer "everybody is always away", once without a marking and
   * once with -- the second one has to move, the first one must not.
   *
   * The two halves stand in ONE test on purpose. Separately they would both be satisfied by a
   * program that always moves or by one that never moves; it is the difference between them that
   * says what axis A is.
   *
   * The tests against the REAL model, with real assignments and real holidays, are in
   * `LevellingBlockingTest`. This one checks what the calculation does with the field, not
   * whether the field arrives.
   */
  fun testOnlyABlockingPersonIsMovedByADayOff() {
    // Away on the Monday and the Tuesday, at work from the Wednesday on.
    val abWennMittwoch: (String, LocalDate) -> Boolean = { _, tag -> tag >= montag.plusDays(2) }
    val ohneMarkierung = task("a", 3)
    val mitMarkierung = ohneMarkierung.copy(blocking = setOf(SHARED_POOL))

    assertEquals("ohne Markierung aendert die Abwesenheit nichts", montag,
      levelTasks(listOf(ohneMarkierung), montag, werktags,
        isAvailable = abWennMittwoch).starts["a"])
    assertEquals("mit Markierung liegen die drei Tage hinter der Abwesenheit",
      montag.plusDays(2),
      levelTasks(listOf(mitMarkierung), montag, werktags,
        isAvailable = abWennMittwoch).starts["a"])
  }

  /**
   * THE STOP CONDITION, and it is the reason this test exists rather than a nicer expectation:
   * two blocking people whose available times NEVER overlap describe a plan that can never be
   * laid. "Everybody has to be there" cannot be relaxed -- relaxing it would say the opposite of
   * what was marked -- so there is no right date to compute.
   *
   * WHAT IS PINNED HERE IS THAT LEVELLING COMES BACK. The bound in `findEarliestWindow`
   * (MAX_SEARCH_DAYS) catches the case and lays the Task at its earliest possible date. That is a
   * visibly wrong date, and a visibly wrong date is the cheapest of the possible failures: an
   * endless loop shows nothing at all -- no dialog, no message, only a menu item that seems to do
   * nothing. That failure mode has been measured on this machine twice already, and both
   * measurements are recorded in `ResourceLevelling.kt`.
   *
   * WHETHER THIS SHOULD ALSO BE REPORTED is an open question and deliberately not answered here:
   * a new kind of message is a decision, not a piece of building work.
   */
  fun testAnImpossibleIntersectionEndsInsteadOfHanging() {
    // P is there on even days only, Q on odd ones -- never both.
    val abwechselnd: (String, LocalDate) -> Boolean = { person, tag ->
      if (person == "p") tag.toEpochDay() % 2 == 0L else tag.toEpochDay() % 2 != 0L
    }
    val vorgang = task("a", 2).copy(blocking = setOf("p", "q"))

    val r = levelTasks(listOf(vorgang), montag, werktags, isAvailable = abwechselnd)

    assertNotNull("die Verteilung muss zurueckkommen, statt zu haengen", r.starts["a"])
    assertEquals("ohne moeglichen Termin bleibt der frueheste", montag, r.starts["a"])
  }
}
