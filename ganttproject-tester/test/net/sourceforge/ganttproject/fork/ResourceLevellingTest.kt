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

  /**
   * THE RED TEST OF THIS STAGE, since tightened: the impossible case is no longer silent, and the
   * message says WHICH Task and WHICH people.
   *
   * The same setup as the test above -- two blocking people whose available times never overlap.
   * That one pins that levelling comes back at all; this one answers the question left open
   * there. The two halves it asserts are the two halves a person needs in order to act: without
   * the Task there is nothing to look at, without the people there is nothing to change.
   *
   * IN ITS RED RUN THIS TEST ASSERTED ONLY `conflicts.isEmpty()` to be false, and it failed with
   * "der unmoegliche Fall faellt stumm zurueck: die Konfliktliste bleibt leer, obwohl es keinen
   * Tag gibt, an dem beide blockierenden Personen da sind". Recorded here because the weaker form
   * is the one that shows the state before this stage; the stronger one below could not even be
   * compiled against it.
   */
  fun testAnImpossibleIntersectionIsReported() {
    // P is there on even days only, Q on odd ones -- never both.
    val abwechselnd: (String, LocalDate) -> Boolean = { person, tag ->
      if (person == "p") tag.toEpochDay() % 2 == 0L else tag.toEpochDay() % 2 != 0L
    }
    val vorgang = task("a", 2).copy(blocking = setOf("p", "q"))

    val r = levelTasks(listOf(vorgang), montag, werktags, isAvailable = abwechselnd)

    val gemeldet = r.conflicts.filterIsInstance<LevelConflict.BlockingIntersectionEmpty>()
    assertEquals(
      "genau eine Meldung fuer den einen unmoeglichen Vorgang, gemeldet wurde: ${r.conflicts}",
      1, gemeldet.size)
    assertEquals("die Meldung muss den Vorgang nennen", "a", gemeldet[0].id)
    assertEquals("die Meldung muss die beteiligten Personen nennen",
      listOf("p", "q"), gemeldet[0].blocking)
  }

  /**
   * NACHWEIS 2: the message changes NOTHING about where the Task lies.
   *
   * The same plan twice -- once with the two impossible people, once with the same absences and
   * no marking at all, which is the calculation as it stood before axis A existed. The dates and
   * the durations have to agree; only the conflict list may differ.
   *
   * WHY IT IS COMPARED AGAINST A SECOND RUN and not against a date written out by hand: a written
   * date would have to be corrected the moment anything else about the fallback changes, and the
   * test would then stop saying what it is here to say. The test
   * `testAnImpossibleIntersectionEndsInsteadOfHanging` above holds the written date and was
   * not touched by this stage -- between the two of them the claim is covered from both sides.
   */
  fun testTheReportDoesNotMoveTheTask() {
    val abwechselnd: (String, LocalDate) -> Boolean = { person, tag ->
      if (person == "p") tag.toEpochDay() % 2 == 0L else tag.toEpochDay() % 2 != 0L
    }
    val ohneMarkierung = task("a", 2)
    val mitMarkierung = ohneMarkierung.copy(blocking = setOf("p", "q"))

    val ohne = levelTasks(listOf(ohneMarkierung), montag, werktags, isAvailable = abwechselnd)
    val mit = levelTasks(listOf(mitMarkierung), montag, werktags, isAvailable = abwechselnd)

    assertEquals("der Termin darf sich durch die Meldung nicht bewegen", ohne.starts, mit.starts)
    assertEquals("die Dauer darf sich durch die Meldung nicht aendern",
      ohne.durations, mit.durations)
    assertTrue("ohne Markierung gibt es nach wie vor nichts zu melden", ohne.conflicts.isEmpty())
  }

  /**
   * NACHWEIS 3, and without it the message would be worth nothing: a plan that CAN be laid says
   * nothing.
   *
   * The same two blocking people, the same kind of absence -- but their free times do overlap:
   * both are away on the Monday and there from the Tuesday on. The Task moves to the Tuesday, and
   * that is an ordinary result of axis A, not a conflict. A version that reported every marked
   * plan would pass the test above and fail here.
   */
  fun testASolvableIntersectionIsNotReported() {
    val abDienstag: (String, LocalDate) -> Boolean = { _, tag -> tag > montag }
    val vorgang = task("a", 2).copy(blocking = setOf("p", "q"))

    val r = levelTasks(listOf(vorgang), montag, werktags, isAvailable = abDienstag)

    assertEquals("die zwei Tage liegen hinter dem gemeinsamen freien Montag",
      montag.plusDays(1), r.starts["a"])
    assertTrue("ein loesbarer Fall darf nichts melden, gemeldet wurde: ${r.conflicts}",
      r.conflicts.isEmpty())
  }

  /**
   * One blocking person who is NEVER there is the same impossibility with one name in it, and it
   * is reported the same way.
   *
   * Worth its own test because "intersection" invites the reading that two people are needed for
   * the case. They are not: what cannot be satisfied is the SET, and a set of one that is never
   * satisfiable is exactly as unplannable -- and exactly as silent before this stage.
   */
  fun testASinglePersonWhoIsNeverThereIsReportedToo() {
    val vorgang = task("a", 2).copy(blocking = setOf("p"))

    val r = levelTasks(listOf(vorgang), montag, werktags, isAvailable = { _, _ -> false })

    val gemeldet = r.conflicts.filterIsInstance<LevelConflict.BlockingIntersectionEmpty>()
    assertEquals("auch eine einzige nie anwesende Person ist ein unloesbarer Plan",
      1, gemeldet.size)
    assertEquals(listOf("p"), gemeldet[0].blocking)
  }

  /**
   * The report stays ON the Task it belongs to. An unmarked Task beside an impossible one is not
   * mentioned, even though it lies in the same plan and shares the same pool.
   *
   * This is the counter-check against reporting per RUN rather than per Task -- a version that
   * appended one conflict for the whole levelling would look right in every test above.
   */
  fun testOnlyTheMarkedTaskIsNamed() {
    val abwechselnd: (String, LocalDate) -> Boolean = { person, tag ->
      if (person == "p") tag.toEpochDay() % 2 == 0L else tag.toEpochDay() % 2 != 0L
    }
    val plan = listOf(
      task("a", 2).copy(blocking = setOf("p", "q")),
      task("b", 2, reihe = 1))

    val r = levelTasks(plan, montag, werktags, isAvailable = abwechselnd)

    val gemeldet = r.conflicts.filterIsInstance<LevelConflict.BlockingIntersectionEmpty>()
    assertEquals("nur der markierte Vorgang ist gemeint", listOf("a"), gemeldet.map { it.id })
  }

  // ===========================================================================================
  // P6: the OTHER half of the same fallback -- the capacity dead end -- is no longer silent.
  // ===========================================================================================

  /**
   * A plan in which P is at work exactly on the days that are already booked solid, and away for
   * ever afterwards. Used by most of the P6 tests below, because it is the shape the measurement
   * found: 18 of 48 exhausted searches over a grid of 80 plans had BOTH reasons, and none had
   * capacity alone.
   */
  private val pDaBisFreitag: (String, LocalDate) -> Boolean = { _, tag -> tag < montag.plusDays(5) }

  /** The first five working days of the shared pool, booked to the brim and frozen there. */
  private fun ersteWocheVoll(pool: String = SHARED_POOL) =
    LevelTask(id = "voll", orderInPlan = 0, priority = 5, durationDays = 5,
      fixedStart = montag, frozen = true, loads = mapOf(pool to 100))

  /**
   * THE RED TEST OF THIS STAGE: the capacity dead end is no longer silent, and the message says
   * WHICH Task and WHOSE days were full.
   *
   * The search bounces off the first week because it is booked solid, and off everything after it
   * because P is gone. Neither reason on its own would end the plan -- the same plan with the
   * booking removed finds a date, which is what makes the capacity half worth saying at all.
   *
   * IN ITS RED RUN this test asserted only that ONE conflict beyond the overload and P5's message
   * existed, and it failed with
   *
   *   junit.framework.AssertionFailedError: der Kapazitaetsgrund faellt stumm zurueck: die Suche
   *   prallte an belegten Tagen ab und meldet davon nichts, gemeldet wurde
   *   [BlockingIntersectionEmpty(id=a, blocking=[p]), Overload(day=2026-08-17, percent=200,
   *   ids=[voll, a], resourceId=)] expected:<1> but was:<0>
   *
   * Recorded here because the weaker form is the one that shows the state before this stage; the
   * stronger one below could not even be compiled against it.
   */
  fun testACapacityDeadEndIsReported() {
    val plan = listOf(ersteWocheVoll(),
      task("a", 1).copy(blocking = setOf("p")))

    val r = levelTasks(plan, montag, werktags, isAvailable = pDaBisFreitag)

    val gemeldet = r.conflicts.filterIsInstance<LevelConflict.NoDayWithCapacity>()
    assertEquals("genau eine Meldung fuer den einen Vorgang, gemeldet wurde: ${r.conflicts}",
      1, gemeldet.size)
    assertEquals("die Meldung muss den Vorgang nennen", "a", gemeldet[0].id)
    assertEquals("die Meldung muss nennen, wessen Tage voll waren",
      listOf(SHARED_POOL), gemeldet[0].fullFor)
  }

  /**
   * BOTH REASONS, BOTH SAID. The same plan reports P5's message as well, and that is the reading
   * chosen on 27.08.2026 rather than an accident: an exhausted search can have had two reasons,
   * and relieving either one is enough, so naming only one of them hides a remedy.
   *
   * This is the assertion that would go red first if somebody later turned the two into an
   * `else` -- which is exactly why it is written down as an assertion and not as a comment. It has
   * been seen to do so: with an `else` put in, it failed with
   *
   *   junit.framework.AssertionFailedError: P6s Meldung muss danebenstehen expected:<1> but was:<0>
   */
  fun testBothReasonsAreReportedWhenBothApplied() {
    val plan = listOf(ersteWocheVoll(),
      task("a", 1).copy(blocking = setOf("p")))

    val r = levelTasks(plan, montag, werktags, isAvailable = pDaBisFreitag)

    assertEquals("P5s Meldung muss stehen bleiben", 1,
      r.conflicts.filterIsInstance<LevelConflict.BlockingIntersectionEmpty>().size)
    assertEquals("P6s Meldung muss danebenstehen", 1,
      r.conflicts.filterIsInstance<LevelConflict.NoDayWithCapacity>().size)
  }

  /**
   * NACHWEIS 1, and the one the whole stage stands or falls by: the message changes NOTHING about
   * where the Task lies.
   *
   * The same plan twice -- once so that the capacity dead end is reached, once with the booking
   * moved out of the way so that it is not. What is compared is not the two dates against each
   * other (they differ, and they should) but the reported date against the date the SAME plan
   * produced before the report existed. Since that state cannot be run from inside a test, the
   * date is written out: 2026-08-17, the earliest possible one, measured on the state before this
   * stage and unchanged by it.
   *
   * IT COULD NOT BE RED AGAINST THE STATE BEFORE THIS STAGE, and that is not a gap but the point:
   * what it asserts is that the state before this stage is still the state now. A check nobody has
   * ever seen fail is worth nothing all the same, so it was made to fail on purpose -- with the
   * fallback moved one working day to the right it failed with
   *
   *   junit.framework.AssertionFailedError: der Rueckfall legt den Vorgang auf den
   *   fruehestmoeglichen Termin -- dieselbe Zahl wie vor der Meldung
   *   expected:<2026-08-17> but was:<2026-08-18>
   *
   * and the same breakage took `testAnImpossibleIntersectionEndsInsteadOfHanging` and
   * `testTheReportDoesNotMoveTheTask` down with it -- P5's date guards, untouched and still
   * holding.
   */
  fun testTheCapacityReportDoesNotMoveTheTask() {
    val plan = listOf(ersteWocheVoll(),
      task("a", 1).copy(blocking = setOf("p")))

    val r = levelTasks(plan, montag, werktags, isAvailable = pDaBisFreitag)

    assertEquals("der Rueckfall legt den Vorgang auf den fruehestmoeglichen Termin -- " +
      "dieselbe Zahl wie vor der Meldung", montag, r.starts["a"])
    assertEquals("und mit derselben Dauer", 1, r.durations["a"])
    assertEquals("die Meldung darf auch die eingefrorene Arbeit nicht bewegen",
      montag, r.starts["voll"])
  }

  /**
   * NACHWEIS 2, and without it the message would be worth nothing: a plan that CAN be laid says
   * nothing about capacity.
   *
   * The same booked first week, the same person -- but P stays for good. The Task moves behind
   * the booked week, and that is an ordinary result of levelling, not a conflict. A version that
   * reported every plan with a busy day in it would pass the test above and fail here -- measured:
   * with the `exhausted` guard dropped it failed with
   *
   *   junit.framework.AssertionFailedError: ein loesbarer Fall darf keine Sackgasse melden,
   *   gemeldet wurde: [NoDayWithCapacity(id=a, fullFor=[])]
   */
  fun testAPlanWithRoomReportsNoCapacityDeadEnd() {
    val plan = listOf(ersteWocheVoll(),
      task("a", 1).copy(blocking = setOf("p")))

    val r = levelTasks(plan, montag, werktags, isAvailable = { _, _ -> true })

    assertEquals("hinter der belegten Woche ist Platz", montag.plusDays(7), r.starts["a"])
    assertTrue("ein loesbarer Fall darf keine Sackgasse melden, gemeldet wurde: ${r.conflicts}",
      r.conflicts.filterIsInstance<LevelConflict.NoDayWithCapacity>().isEmpty())
  }

  /**
   * NACHWEIS 3: only the people the search really bounced off are named, not everybody the Task
   * claims capacity from.
   *
   * The Task needs X and Y; only X is booked solid. Naming Y as well would put a name in front of
   * somebody who has nothing to change, in a message whose entire job is to say what to change.
   * This is where P6's message deliberately differs from P5's, which names the whole marked set --
   * the two make different statements, so they carry different sets.
   *
   * IN ITS RED RUN, in the weak form that could be compiled against the state before this stage,
   * it failed with
   *
   *   junit.framework.AssertionFailedError: nur x ist voll, y hat Platz -- gemeldet wird gar
   *   nichts: [BlockingIntersectionEmpty(id=a, blocking=[p]), Overload(day=2026-08-17,
   *   percent=200, ids=[voll, a], resourceId=x)] expected:<1> but was:<0>
   *
   * And it has been seen to fail in its strong form too, which is the part that matters here:
   * with `task.pools` reported instead of `search.fullFor` it failed with
   *
   *   junit.framework.AssertionFailedError: nur x war voll, y hatte Platz
   *   expected:<[x]> but was:<[x, y]>
   */
  fun testOnlyTheFullPeopleAreNamed() {
    val plan = listOf(ersteWocheVoll("x"),
      LevelTask(id = "a", orderInPlan = 1, priority = 2, durationDays = 1,
        loads = mapOf("x" to 100, "y" to 100), blocking = setOf("p")))

    val r = levelTasks(plan, montag, werktags, isAvailable = pDaBisFreitag)

    val gemeldet = r.conflicts.filterIsInstance<LevelConflict.NoDayWithCapacity>()
    assertEquals("gemeldet wurde: ${r.conflicts}", 1, gemeldet.size)
    assertEquals("nur x war voll, y hatte Platz", listOf("x"), gemeldet[0].fullFor)
  }

  /**
   * THE CASE THE STAGE IS NAMED AFTER, and it is here although it is slow and although the
   * measurement says it practically cannot happen: capacity ALONE, with not a marking in the
   * plan.
   *
   * WHY IT TAKES SO ABSURD A PLAN, measured rather than assumed. A completely free day always
   * fits, because the limit in `findEarliestWindow` is at least the Task's own load. So the search
   * only gives up when every one of 50 000 working days is booked -- some 190 years. A single
   * frozen Task cannot even do it: `workingDays` carries the same bound in CALENDAR days and tops
   * out at 35 715 working days, measured. It takes three chained blocks, booking every working day
   * into the 2260s.
   *
   * WHY IT IS WORTH THE SECONDS IT COSTS ANYWAY -- and it costs 5.5 of them, measured, against
   * 0.05 for every other test in this file: this is the only test in which P5's message cannot be
   * what fires, so it is the only one that proves P6's message stands on its own feet rather than
   * riding along beside its neighbour. If the suite ever has to be made faster, this is a
   * candidate; deleting it would leave the pure case unpinned, so it should be moved rather than
   * dropped.
   *
   * IN ITS RED RUN it failed with
   *
   *   junit.framework.AssertionFailedError: kein Tag hat Platz, und die Verteilung sagt nichts
   *   dazu expected:<1> but was:<0>
   */
  fun testAPureCapacityDeadEndIsReported() {
    // Each block claims 21 000 working days (~29 400 calendar days) and the blocks start 28 000
    // calendar days apart, so they overlap and leave no gap for the Task to slip into.
    val plan = (0 until 3).map { i ->
      LevelTask(id = "voll$i", orderInPlan = i, priority = 5, durationDays = 21_000,
        fixedStart = montag.plusDays(28_000L * i), frozen = true,
        loads = mapOf(SHARED_POOL to 100))
    } + task("a", 1, reihe = 3)

    val r = levelTasks(plan, montag, werktags)

    val gemeldet = r.conflicts.filterIsInstance<LevelConflict.NoDayWithCapacity>()
    assertEquals("ohne einen einzigen freien Tag muss die Sackgasse gemeldet werden", 1,
      gemeldet.size)
    assertEquals("a", gemeldet[0].id)
    assertTrue("hier ist keine Markierung im Spiel, P5s Meldung darf nicht feuern",
      r.conflicts.filterIsInstance<LevelConflict.BlockingIntersectionEmpty>().isEmpty())
    assertEquals("und der Vorgang liegt weiter auf dem fruehestmoeglichen Termin",
      montag, r.starts["a"])
  }

  /**
   * THE COUNTER-CHECK AGAINST REPORTING EVERY BUSY PLAN: an ordinary plan, in which Tasks queue
   * up behind one another exactly as levelling is meant to make them, reports no dead end at all.
   *
   * Two full Tasks for one person: the second waits for the first. Days were rejected for
   * capacity along the way -- that is what levelling does -- and none of it is a conflict,
   * because the search found a window. The same breakage as above took this one down too:
   *
   *   junit.framework.AssertionFailedError: eine gewoehnliche Warteschlange ist keine Sackgasse:
   *   [NoDayWithCapacity(id=b, fullFor=[])]
   */
  fun testAnOrdinaryQueueReportsNothing() {
    val r = levelTasks(listOf(task("a", 3), task("b", 2, reihe = 1)), montag, werktags)

    assertTrue("eine gewoehnliche Warteschlange ist keine Sackgasse: ${r.conflicts}",
      r.conflicts.isEmpty())
  }
}
