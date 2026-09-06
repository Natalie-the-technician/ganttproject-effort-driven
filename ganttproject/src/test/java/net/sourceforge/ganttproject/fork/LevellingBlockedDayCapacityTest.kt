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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate

/**
 * WHAT A BLOCKED DAY OCCUPIES — the release of the hours, measured without a running program.
 *
 * THE RULE THIS FILE IS ABOUT, in Natalie's words: „wenn b nicht an dem Vorgang arbeitet weil a
 * nicht da ist, kann b ja in der zeit was anderes machen". Half of it has been built since
 * 03.09.2026: a task no longer LIES on a day on which somebody it cannot proceed without is
 * missing. The other half was not: the day was still BOOKED, at full load, against every person on
 * the task, so nothing else could be laid on it either. The occupancy `used` in [levelTasks] books
 * `loadIn(pool)` over every working day of the window, and it never asked whether anything was
 * delivered on that day.
 *
 * WHAT IS MEASURED HERE, in the order the checks stand:
 *
 *  1. the release itself, through the only thing that proves it — ANOTHER task actually taking the
 *     freed day. A check that merely showed „less is booked" would be green for a version that
 *     books nothing at all;
 *  2. the day is still THERE. The task keeps its length and its start; what changes is the
 *     booking, not the plan;
 *  3. the two sides of the calculation — the window search and the booking — agree on what „this
 *     task cannot proceed today" means, so that they cannot drift apart;
 *  4. an unmarked plan is not touched, and that one is checked with the channels answering
 *     „everybody away, everybody at home, every day" so that it is not green by accident;
 *  5. the overload report, which reads the same occupancy: a day on which nobody can work is a day
 *     on which nobody is overloaded — and the tasks it NAMES are the ones actually booked on it;
 *  6. the bounds survive: a search that can never succeed still ends and still says so.
 *
 * 14 September 2026 is a Monday — the same week the measurement of 05.09.2026 used, so the dates
 * in `2026-09-05-freigabe-gemessen.md` can be read straight against these.
 */
class LevellingBlockedDayCapacityTest {

  private val montag = LocalDate.of(2026, 9, 14)
  private val dienstag = montag.plusDays(1)
  private val mittwoch = montag.plusDays(2)
  private val donnerstag = montag.plusDays(3)
  private val freitag = montag.plusDays(4)

  private val werktags: (LevelTask, LocalDate) -> Boolean = oneGridForAllTasks {
    it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY
  }

  /** Everybody is at work except [wer] on [tag]. */
  private fun wegAm(wer: String, tag: LocalDate): (String, LocalDate) -> Boolean =
    { person, d -> !(person == wer && d == tag) }

  /** Everybody is on the premises except [wer] on [tag]. */
  private fun zuHauseAm(wer: String, tag: LocalDate): (String, LocalDate) -> Boolean =
    { person, d -> !(person == wer && d == tag) }

  private val immerDa: (String, LocalDate) -> Boolean = { _, _ -> true }
  private val nieDa: (String, LocalDate) -> Boolean = { _, _ -> false }

  /**
   * T1: four working days from the Monday, on a FIXED date, `A` and `B` both at full load, `B`
   * marked as the one who has to be there.
   *
   * WHY THE DATE IS FIXED IN EVERY CHECK OF THE FIRST GROUP, and it is the load-bearing part of
   * the setup rather than decoration: for a task levelling is allowed to MOVE, the window search
   * pushes the whole task past the blocked day and the day never enters `days` in the first place —
   * measured on 05.09.2026, and checked below in `ein beweglicher vorgang bucht weiterhin jeden
   * tag seines fensters`. The loss this file is about exists exactly where the search may not
   * search.
   *
   * FOUR DAYS AND NOT THREE, because that is what the real duration calculation produces for this
   * plan: `DaysOffDuration` stretches three days of work over a blocked Wednesday into four
   * calendar working days, Monday to Thursday, with the Wednesday a hole inside them. The hole is
   * the day at issue. That the real conversion really produces the 4 is measured through the model
   * in `BlockedDayReleaseAdapterTest`; here it is stated as a number so this file can stay free of
   * GanttProject types.
   */
  private fun t1Fest(dauer: Int = 4) = LevelTask(
    id = "t1", orderInPlan = 0, priority = 2, durationDays = dauer, fixedStart = montag,
    loads = mapOf("A" to 100, "B" to 100), blocking = setOf("B"))

  /** T2: one working day of `A` alone, movable, nobody blocking. The task that wants the gap. */
  private fun t2Beweglich(dauer: Int = 1) = LevelTask(
    id = "t2", orderInPlan = 1, priority = 2, durationDays = dauer,
    loads = mapOf("A" to 100))

  // ===============================================================================================
  // 1. The release, proved by somebody using the freed day.
  // ===============================================================================================

  /**
   * THE RED CHECK OF THIS STAGE, and the whole point of the exercise: `A` is free on the Wednesday
   * because `B` is not there, so a second task of `A`'s is laid ON that Wednesday.
   *
   * `B` is away on the Wednesday and has to be there, so on the Wednesday T1 does not move
   * forward — the day sits inside T1 as a hole. Before this stage that hole booked 100 % of `A`'s
   * Wednesday all the same, and T2 had to wait until the Friday. It now takes the Wednesday.
   *
   * TWO DAYS EARLIER THAN TODAY and one day earlier than a plan without the marking at all. The
   * comparison case beside it is the second half of the claim: the difference is `B`'s ABSENCE and
   * not the shape of the plan.
   */
  @Test
  fun `der freigewordene tag wird von einem anderen vorgang benutzt`() {
    val ohneAusfall = levelTasks(listOf(t1Fest(dauer = 3), t2Beweglich()), montag, werktags,
      isAvailable = immerDa)
    assertEquals(donnerstag, ohneAusfall.starts["t2"],
      "Vergleichsfall: ohne Ausfall belegt T1 Mo-Mi voll und T2 kann erst am Donnerstag")

    val ergebnis = levelTasks(listOf(t1Fest(), t2Beweglich()), montag, werktags,
      isAvailable = wegAm("B", mittwoch))

    assertEquals(mittwoch, ergebnis.starts["t2"],
      "B fehlt am Mittwoch und muss dabei sein -- an dem Tag arbeitet niemand an T1, also ist " +
        "A frei und T2 gehoert auf den Mittwoch")
  }

  /**
   * THE COUNTER-CHECK THAT KEEPS THE ONE ABOVE HONEST: the same day, the same absence, the same
   * person — only the marking removed.
   *
   * Without it „T2 moves forward" would also be satisfied by a version that frees a day for EVERY
   * day off of everybody on the task, and that version would be a different program: an unmarked
   * person's day off takes their hours out of the day and leaves the task where it is
   * (`DaysOffDuration.kt`). The release has to act on the marked people AND ONLY on them.
   */
  @Test
  fun `ohne markierung gibt derselbe urlaub keinen tag frei`() {
    val unmarkiert = t1Fest().copy(blocking = emptySet())
    val ergebnis = levelTasks(listOf(unmarkiert, t2Beweglich()), montag, werktags,
      isAvailable = wegAm("B", mittwoch))

    assertEquals(freitag, ergebnis.starts["t2"],
      "B blockiert NICHT; der Urlaub darf keine Kapazitaet freigeben -- sonst wirkt die Regel " +
        "auf jede Zuordnung statt auf die markierten")
  }

  /**
   * THE OTHER COUNTER-CHECK: the marking is there, `B` is there too. Nothing is released.
   *
   * This one catches an implementation that reads the marking rather than the answer — a filter
   * that drops a day because somebody is marked at all would pass the check above and fail here.
   */
  @Test
  fun `eine markierung ohne ausfall gibt nichts frei`() {
    val ergebnis = levelTasks(listOf(t1Fest(), t2Beweglich()), montag, werktags,
      isAvailable = immerDa)

    assertEquals(freitag, ergebnis.starts["t2"],
      "niemand fehlt; die vier Tage von T1 belegen A voll und T2 kann erst danach")
  }

  /**
   * THE CASE THIS FORK WAS BUILT FOR, carried into the release: somebody who has to be PRESENT
   * without contributing — supervision, an instruction, an acceptance — has load 0.
   *
   * Every shortcut that reads the release off the load fails here: `B`'s load is 0, so „book the
   * hours of the people who deliver" would find nothing to release, and `A`'s Wednesday would stay
   * occupied by a task nobody can work on. The two axes are independent in the model and they stay
   * independent here.
   */
  @Test
  fun `wer anwesend sein muss aber nichts beitraegt gibt den tag trotzdem frei`() {
    val abnahme = t1Fest().copy(loads = mapOf("A" to 100, "B" to 0))
    val ergebnis = levelTasks(listOf(abnahme, t2Beweglich()), montag, werktags,
      isAvailable = wegAm("B", mittwoch))

    assertEquals(mittwoch, ergebnis.starts["t2"],
      "B traegt mit Last 0 nichts bei, blockiert aber -- der Mittwoch muss A trotzdem freiwerden")
  }

  /**
   * FROZEN WORK IS BOOKED AT ITS OWN PLACE IN THE FILE, before everything else, and it needs the
   * same release. A second copy of the rule is what this pins down: begun work laid on a blocked
   * day must not hold the day either.
   *
   * WHY THIS IS A CHECK OF ITS OWN AND NOT COVERED BY THE ONE ABOVE: the two bookings are two
   * separate loops with two separate `days` lists. A change applied to one of them only is green
   * everywhere except here.
   */
  @Test
  fun `auch eingefrorene arbeit gibt den blockierten tag frei`() {
    val eingefroren = t1Fest().copy(frozen = true)
    val ergebnis = levelTasks(listOf(eingefroren, t2Beweglich()), montag, werktags,
      isAvailable = wegAm("B", mittwoch))

    assertEquals(mittwoch, ergebnis.starts["t2"],
      "die eingefrorene Arbeit wird als erstes gebucht -- auch dort darf der blockierte Tag " +
        "niemanden belegen")
    assertEquals(montag, ergebnis.starts["t1"],
      "und sie bleibt liegen, wo sie liegt: eingefrorene Arbeit ist die Vergangenheit")
  }

  // ===============================================================================================
  // 2. The day is still there — the release is about the booking, not about the plan.
  // ===============================================================================================

  /**
   * THE HOLE STAYS A HOLE. T1 keeps its start and its four days; the Wednesday is still one of
   * them.
   *
   * This is the boundary of the change written as a requirement, and it is the check that catches
   * the tempting wrong version: dropping the blocked day from `days` instead of from the booking
   * would shorten the task, move its end a day forward, and hand everything behind it a date the
   * duration calculation never computed.
   */
  @Test
  fun `der blockierte tag zaehlt weiter fuer laenge und lage des vorgangs`() {
    val ergebnis = levelTasks(listOf(t1Fest(), t2Beweglich()), montag, werktags,
      isAvailable = wegAm("B", mittwoch))

    assertEquals(montag, ergebnis.starts["t1"], "der feste Termin bleibt der feste Termin")
    assertEquals(4, ergebnis.durations["t1"],
      "der Vorgang bleibt vier Arbeitstage lang -- der Mittwoch ist ein Loch DARIN, kein " +
        "gestrichener Tag")
  }

  /**
   * A MOVABLE TASK IS NOT TOUCHED BY ANY OF THIS, and the reason is worth pinning: the window
   * search has already refused every window containing the blocked day, so the day is not in
   * `days` and there is nothing to release.
   *
   * Measured on 05.09.2026 as rows 1, 4 and 6 of the table: for a freely movable task the release
   * changes not one date. If this check ever goes red, the release has started to act where the
   * search already acted, and something is being subtracted twice.
   */
  @Test
  fun `ein beweglicher vorgang bucht weiterhin jeden tag seines fensters`() {
    val beweglich = t1Fest().copy(fixedStart = null, durationDays = 3)
    val ergebnis = levelTasks(listOf(beweglich, t2Beweglich()), montag, werktags,
      isAvailable = wegAm("B", mittwoch))

    assertEquals(donnerstag, ergebnis.starts["t1"],
      "die Fenstersuche schiebt den ganzen Vorgang hinter den Ausfalltag")
    assertEquals(montag, ergebnis.starts["t2"],
      "und T2 nimmt den ganzen freien Montag -- das tat es vor dieser Stufe auch schon")
  }

  // ===============================================================================================
  // 3. The two sides of the calculation say the same thing.
  // ===============================================================================================

  /**
   * THE ANTI-DRIFT CHECK: no day a movable task is LAID on may be a day on which the task cannot
   * proceed.
   *
   * WHY IT IS NEEDED. „This task cannot proceed today" is now answered in two places — in
   * `findEarliestWindow`, which refuses such a day, and in the booking, which does not book it.
   * Two spellings of one sentence drift; the file says so itself about `blocking` and
   * `requiresPresence` („Two sets would be two truths about who has to be there; one of them
   * would drift"). This check ties the two ends together from the outside: whatever the booking
   * releases for a movable task must be a day the search would already have refused, so the set of
   * released days for such a task is EMPTY.
   *
   * Walked over a grid of absences rather than one, so that a single lucky date cannot carry it.
   */
  @Test
  fun `fenstersuche und buchung sind sich einig welcher tag ein blockierter ist`() {
    for (tag in 0..9) {
      val ausfall = montag.plusDays(tag.toLong())
      val beweglich = LevelTask(id = "b", orderInPlan = 0, priority = 2, durationDays = 3,
        loads = mapOf("A" to 100), blocking = setOf("B"), requiresPresence = true)
      val ergebnis = levelTasks(listOf(beweglich), montag, werktags,
        isAvailable = wegAm("B", ausfall), isAtWorkplace = zuHauseAm("B", ausfall.plusDays(1)))

      val start = ergebnis.starts.getValue("b")
      val tage = generateSequence(start) { it.plusDays(1) }
        .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
        .take(ergebnis.durations.getValue("b")).toList()
      assertTrue(tage.none { it == ausfall },
        "Ausfall am $ausfall: die Suche darf keinen Tag legen, den die Buchung wieder " +
          "freigeben muesste -- gelegt wurde $tage")
      assertTrue(tage.none { it == ausfall.plusDays(1) },
        "Heimarbeit am ${ausfall.plusDays(1)}: dasselbe fuer die zweite Haelfte der Regel -- " +
          "gelegt wurde $tage")
    }
  }

  // ===============================================================================================
  // 4. An unmarked plan is not touched. Checked with the channels shouting.
  // ===============================================================================================

  /**
   * A grid of plans that between them use every branch the booking passes through: frozen work,
   * a fixed date, movable tasks competing for one person, an unassigned task on the shared pool,
   * two people at partial load, a deadline, and an overload forced by two fixed dates.
   */
  private fun unmarkierteFlotte(): List<LevelTask> = listOf(
    LevelTask(id = "eingefroren", orderInPlan = 0, priority = 2, durationDays = 2,
      fixedStart = montag, frozen = true, loads = mapOf("A" to 100)),
    LevelTask(id = "fest", orderInPlan = 1, priority = 2, durationDays = 3, fixedStart = mittwoch,
      loads = mapOf("A" to 100, "B" to 50)),
    LevelTask(id = "auchFest", orderInPlan = 2, priority = 2, durationDays = 2,
      fixedStart = mittwoch, loads = mapOf("A" to 100)),
    LevelTask(id = "beweglich1", orderInPlan = 3, priority = 3, durationDays = 4,
      loads = mapOf("B" to 100)),
    LevelTask(id = "beweglich2", orderInPlan = 4, priority = 1, durationDays = 2,
      loads = mapOf("B" to 60), deadline = montag.plusDays(3)),
    LevelTask(id = "ohneZuordnung", orderInPlan = 5, priority = 2, durationDays = 3),
    LevelTask(id = "nachfolger", orderInPlan = 6, priority = 2, durationDays = 2,
      predecessors = listOf("beweglich1"), loads = mapOf("A" to 40, "B" to 40))
  )

  /**
   * NOTHING CHANGES WITHOUT A MARKING, AND THE CHANNELS ARE SHOUTING WHILE IT DOES NOT.
   *
   * The two runs differ in nothing but the two availability channels: one says „everybody is at
   * work and on the premises, always", the other says „NOBODY is at work and NOBODY is on the
   * premises, on any day". Every task in the fleet is in the state every task of every plan
   * written before this fork is in — [LevelTask.blocking] empty and [LevelTask.requiresPresence]
   * false — and in that state the two runs have to be identical to the day, including the
   * conflicts.
   *
   * WHY IT IS COMPARED AND NOT WRITTEN OUT. Dates written out would pin these plans; what has to
   * be pinned is the DIFFERENCE, which is none. A comparison also survives the day somebody
   * changes the ordering rule for an unrelated reason.
   *
   * WHY THE FLEET AND NOT ONE TASK. The booking happens in two loops and is read by two consumers
   * (the window search and the overload report). A single movable task walks through one of the
   * four. The fleet has frozen work, fixed dates, a shared pool, a deadline and a forced overload
   * in it, so a release wired to the wrong set of people cannot hide in a branch nobody entered.
   *
   * WHAT IT CATCHES, AND — MORE IMPORTANTLY — WHAT IT DOES NOT. This was established by breaking
   * the implementation on purpose six ways and running the file each time, not by reading it.
   *
   * It catches a release keyed on [LevelTask.loads] instead of [LevelTask.blocking]: „nobody
   * available" then releases every day of every task and the whole plan collapses onto the Monday.
   *
   * IT IS BLIND TO TWO OF THE FIVE, and the reason is a short circuit rather than the checking:
   * [LevelTask.contributingDays] returns before it asks anything at all when the blocking set is
   * empty, which is the state every task in this fleet is in. An inverted condition and a
   * home-office half that forgot [LevelTask.requiresPresence] are therefore both invisible HERE —
   * the code that carries the mistake is never reached. That is not a hole in the corpus; it is
   * why the two checks below this one exist, each with a NON-EMPTY blocking set and one of the
   * channels shouting. Whoever changes this file should know which of the three sees what, because
   * a green run of this one alone proves less than it looks like it does.
   */
  @Test
  fun `ohne markierung aendern die kanaele nichts obwohl sie alles verneinen`() {
    val ruhig = levelTasks(unmarkierteFlotte(), montag, werktags,
      isAvailable = immerDa, isAtWorkplace = immerDa)
    val schreiend = levelTasks(unmarkierteFlotte(), montag, werktags,
      isAvailable = nieDa, isAtWorkplace = nieDa)

    assertEquals(ruhig.starts, schreiend.starts,
      "kein Vorgang traegt eine Markierung -- kein Termin darf sich um einen Tag bewegen")
    assertEquals(ruhig.durations, schreiend.durations,
      "und keine Dauer darf sich aendern")
    assertEquals(ruhig.conflicts, schreiend.conflicts,
      "und keine Meldung darf dazukommen oder wegfallen")
  }

  /**
   * THE SECOND NEUTRALITY CHECK, AND IT IS THE ONE THE FIRST CANNOT MAKE: the marking is SET on
   * every task, and nobody is ever missing. That has to be the same plan as one with no marking at
   * all.
   *
   * The first check runs with an empty [LevelTask.blocking], where the released set is empty by
   * construction — a wrongly INVERTED test („release the days on which everybody IS there") is
   * invisible to it, because there is nobody to be there. Here the set is not empty and the answer
   * is always „present", so an inverted test releases every single day and every task falls onto
   * the Monday.
   */
  @Test
  fun `eine markierung ohne einen einzigen ausfalltag ist dasselbe wie keine markierung`() {
    val ohne = levelTasks(unmarkierteFlotte(), montag, werktags, isAvailable = immerDa)
    val mit = levelTasks(
      unmarkierteFlotte().map { it.copy(blocking = setOf("A", "B"), requiresPresence = true) },
      montag, werktags, isAvailable = immerDa, isAtWorkplace = immerDa)

    assertEquals(ohne.starts, mit.starts,
      "alle Markierten sind an allen Tagen da -- die Markierung darf dann nichts bewirken")
    assertEquals(ohne.durations, mit.durations, "und die Dauern bleiben")
    assertEquals(ohne.conflicts, mit.conflicts, "und die Meldungen bleiben")
  }

  /**
   * THE THIRD NEUTRALITY CHECK, and the one the other two cannot make: the marking is set, NOBODY
   * is on the premises on any day, and no task needs anybody on the premises. That has to be the
   * same plan as one with no marking at all.
   *
   * The first check cannot see it because its blocking set is empty and the released set is never
   * computed; the second cannot see it because its `isAtWorkplace` says „everybody is here", so
   * the home-office half has nothing to fire on. Here both are arranged against it: the set is
   * full, the channel says „everybody at home, every day", and the ONLY thing standing between
   * that and a plan collapsing onto the Monday is the [LevelTask.requiresPresence] in front of the
   * home-office half.
   *
   * Measured: drop that `requiresPresence &&` and this check goes red while both others stay
   * green. That is what it is for.
   */
  @Test
  fun `eine markierung ohne praesenzpflicht ueberlebt dauerhafte heimarbeit`() {
    val ohne = levelTasks(unmarkierteFlotte(), montag, werktags, isAvailable = immerDa)
    val mit = levelTasks(
      unmarkierteFlotte().map { it.copy(blocking = setOf("A", "B")) },
      montag, werktags, isAvailable = immerDa, isAtWorkplace = nieDa)

    assertEquals(ohne.starts, mit.starts,
      "kein Vorgang braucht jemanden vor Ort -- dauerhafte Heimarbeit darf keinen Tag freigeben")
    assertEquals(ohne.durations, mit.durations, "und die Dauern bleiben")
    assertEquals(ohne.conflicts, mit.conflicts, "und die Meldungen bleiben")
  }

  // ===============================================================================================
  // 5. The overload report reads the same occupancy.
  // ===============================================================================================

  /** Two tasks on the same fixed Monday, both claiming all of `A`. That is how overload arises. */
  private fun ueberlastFlotte() = listOf(
    t1Fest(),
    LevelTask(id = "gleichzeitig", orderInPlan = 1, priority = 2, durationDays = 4,
      fixedStart = montag, loads = mapOf("A" to 100))
  )

  private fun ueberlastTage(r: LevelResult): List<LocalDate> =
    r.conflicts.filterIsInstance<LevelConflict.Overload>()
      .filter { it.resourceId == "A" }.map { it.day }.sorted()

  /**
   * A DAY ON WHICH NOBODY CAN WORK IS A DAY ON WHICH NOBODY IS OVERLOADED.
   *
   * Two tasks, both nailed to the Monday, both claiming all of `A`: four working days at 200 %,
   * four messages. `B` has to be there for T1 and is away on the Wednesday, so on the Wednesday
   * T1 takes nothing from `A` and `A` stands at 100 % — which is not an overload.
   *
   * THE MEASUREMENT OF 05.09.2026 CALLED THIS THE ONE FALSE MESSAGE OF THE FOUR, and the point is
   * worth keeping: the message that disappears is not a warning being suppressed, it is a sentence
   * that was not true. The three days on which `A` really is claimed twice keep theirs — that is
   * the other half of this check and the reason the days are written out rather than counted.
   */
  @Test
  fun `am blockierten tag meldet niemand mehr eine ueberlastung`() {
    val heute = levelTasks(ueberlastFlotte(), montag, werktags, isAvailable = immerDa)
    assertEquals(listOf(montag, dienstag, mittwoch, donnerstag), ueberlastTage(heute),
      "Vergleichsfall ohne Ausfall: vier Tage zu 200 %, vier Meldungen")

    val ergebnis = levelTasks(ueberlastFlotte(), montag, werktags,
      isAvailable = wegAm("B", mittwoch))

    assertEquals(listOf(montag, dienstag, donnerstag), ueberlastTage(ergebnis),
      "am Mittwoch traegt T1 nichts bei, A steht bei 100 % -- die Meldung fuer den Mittwoch war " +
        "falsch und muss weg, die drei richtigen muessen bleiben")
    assertTrue(ergebnis.conflicts.filterIsInstance<LevelConflict.Overload>()
      .none { it.day == mittwoch },
      "und zwar fuer JEDEN Kapazitaetstopf, nicht nur fuer A")
  }

  /**
   * AND THE MESSAGE NAMES THE RIGHT TASKS. An overload report carries the list of tasks that made
   * the day too full; a task released from the day did not.
   *
   * Three tasks on the Monday, all of `A`'s: T1 with `B` blocking and away on the Wednesday, and
   * two ordinary ones. The Wednesday is still overloaded — the two ordinary ones make 200 % of
   * their own — but T1 has nothing to do with it and must not be in the list. Naming it would
   * point the reader at the one task on the day that is NOT the reason.
   *
   * WHY THIS NEEDS ITS OWN CHECK: the report rebuilds each task's days from `starts` and
   * `durations`, which is a THIRD place the days of a task are computed. It does not read the
   * occupancy it is reporting on. A release that changed the booking and not this walk would leave
   * the numbers right and the names wrong, and nothing else in this file would see it.
   */
  @Test
  fun `die ueberlastmeldung nennt nur die vorgaenge die den tag wirklich belegen`() {
    val flotte = ueberlastFlotte() + LevelTask(id = "dritter", orderInPlan = 2, priority = 2,
      durationDays = 4, fixedStart = montag, loads = mapOf("A" to 100))
    val ergebnis = levelTasks(flotte, montag, werktags, isAvailable = wegAm("B", mittwoch))

    val amMittwoch = ergebnis.conflicts.filterIsInstance<LevelConflict.Overload>()
      .single { it.day == mittwoch && it.resourceId == "A" }
    assertEquals(200, amMittwoch.percent,
      "zwei Vorgaenge zu je 100 % belegen A am Mittwoch -- T1 ist nicht dabei")
    assertEquals(listOf("dritter", "gleichzeitig"), amMittwoch.ids.sorted(),
      "T1 gibt den Mittwoch frei und darf in der Liste der Schuldigen nicht auftauchen")
  }

  // ===============================================================================================
  // 6. The home-work half of the rule — the same day, a different reason.
  // ===============================================================================================

  /**
   * B3 CARRIED INTO THE BOOKING: a task that needs somebody ON THE PREMISES cannot proceed on a day
   * that person works from home, so that day must not book anybody either.
   *
   * THE SAME SENTENCE AS ABOVE WITH A DIFFERENT REASON, and that is exactly why it has to be here:
   * the window search learned the home-work rule on 04.09.2026 and refuses such a day; a booking
   * that knows only about days off would keep holding a day the search itself calls unusable. The
   * two would then say different things about the same Wednesday — the drift this file's third
   * group is about.
   *
   * The two counter-checks beside it are the two halves of the rule's own condition: without
   * [LevelTask.requiresPresence] and with the person outside [LevelTask.blocking] nothing is
   * released, because in neither case does the task stop.
   */
  @Test
  fun `ein heimarbeitstag der zwingenden person gibt den tag ebenfalls frei`() {
    val vorOrt = t1Fest().copy(requiresPresence = true)
    val ergebnis = levelTasks(listOf(vorOrt, t2Beweglich()), montag, werktags,
      isAvailable = immerDa, isAtWorkplace = zuHauseAm("B", mittwoch))

    assertEquals(mittwoch, ergebnis.starts["t2"],
      "B arbeitet am Mittwoch zu Hause, T1 braucht B vor Ort -- an dem Tag laeuft T1 nicht, " +
        "also ist A frei")
  }

  @Test
  fun `ohne praesenzpflicht gibt der heimarbeitstag nichts frei`() {
    val ergebnis = levelTasks(listOf(t1Fest(), t2Beweglich()), montag, werktags,
      isAvailable = immerDa, isAtWorkplace = zuHauseAm("B", mittwoch))

    assertEquals(freitag, ergebnis.starts["t2"],
      "der Vorgang braucht niemanden vor Ort -- B arbeitet von zu Hause an ihm mit, der Tag " +
        "bleibt belegt")
  }

  @Test
  fun `die heimarbeit einer nicht blockierenden person gibt nichts frei`() {
    val vorOrt = t1Fest().copy(requiresPresence = true)
    val ergebnis = levelTasks(listOf(vorOrt, t2Beweglich()), montag, werktags,
      isAvailable = immerDa, isAtWorkplace = zuHauseAm("A", mittwoch))

    assertEquals(freitag, ergebnis.starts["t2"],
      "A ist nicht als zwingend markiert; A's Heimarbeit haelt den Vorgang nicht an und gibt " +
        "darum auch nichts frei")
  }

  // ===============================================================================================
  // 7. The bounds survive.
  // ===============================================================================================

  /**
   * THE SEARCH STILL GIVES UP INSTEAD OF HANGING, and it still says why.
   *
   * Two people who have to be there and are never there on the same day leave no window at all.
   * `MAX_SEARCH_DAYS` is what ends that search; the task is laid on its earliest possible date and
   * a [LevelConflict.NoPossibleDate] says so. The release runs on the days of that fallback like on
   * any others, so this is the check that it cannot turn the fallback into an endless walk.
   *
   * WITH A FIXED-DATE NEIGHBOUR IN THE PLAN so that the release is genuinely in play during the
   * run — a check whose feature is switched off proves nothing about the bound.
   */
  @Test
  fun `eine unmoegliche schnittmenge endet weiterhin mit einer meldung statt zu haengen`() {
    val unmoeglich = LevelTask(id = "unmoeglich", orderInPlan = 1, priority = 2, durationDays = 2,
      loads = mapOf("A" to 100), blocking = setOf("P", "Q"))
    // P is away on even days, Q on odd ones: never a day both are there.
    val nieGemeinsam: (String, LocalDate) -> Boolean = { person, tag ->
      if (person == "P") tag.toEpochDay() % 2 == 0L else tag.toEpochDay() % 2 != 0L
    }

    val ergebnis = assertTimeoutPreemptively(Duration.ofSeconds(30), ThrowingSupplier {
      levelTasks(listOf(t1Fest(), unmoeglich), montag, werktags, isAvailable = nieGemeinsam)
    })

    val meldung = ergebnis.conflicts.filterIsInstance<LevelConflict.NoPossibleDate>()
      .single { it.id == "unmoeglich" }
    assertEquals(listOf("P", "Q"), meldung.blocking,
      "die Meldung nennt die ganze markierte Menge, nicht nur die gesehenen Abwesenden")
    assertTrue(meldung.blockingReasons.contains(AbsenceKind.AWAY),
      "und den Grund: weg, nicht zu Hause")
    assertEquals(montag, ergebnis.starts["unmoeglich"],
      "der Rueckfall ist unveraendert der fruehestmoegliche Termin")
  }

  /**
   * The bound is not reached in an ordinary plan, and this says so from the other side: a task
   * whose blocking person is away for a single day still finds its window at once. Without this,
   * the check above would be green for a version in which EVERY search runs into the bound.
   */
  @Test
  fun `ein gewoehnlicher ausfall laeuft nicht in die schranke`() {
    val beweglich = LevelTask(id = "b", orderInPlan = 0, priority = 2, durationDays = 2,
      loads = mapOf("A" to 100), blocking = setOf("B"))
    val ergebnis = levelTasks(listOf(beweglich), montag, werktags,
      isAvailable = wegAm("B", montag))

    assertTrue(ergebnis.conflicts.isEmpty(),
      "ein einzelner Ausfalltag ist keine Sackgasse, gemeldet wurde: ${ergebnis.conflicts}")
    assertEquals(dienstag, ergebnis.starts["b"], "der Vorgang beginnt am Tag nach dem Ausfall")
    assertNotEquals(montag, ergebnis.starts["b"], "und ganz sicher nicht auf dem Ausfalltag")
  }
}
