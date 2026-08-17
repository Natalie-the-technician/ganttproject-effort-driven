/*
Copyright 2026

NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.

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
 * Die Kapazitaetsverteilung, ohne laufendes Programm.
 *
 * Jeder Fall prueft beide Richtungen: dass die Verteilung tut, was sie soll, UND dass sie es
 * unterlaesst, wenn kein Grund dazu besteht. Ein Test, der nur "es wurde verschoben" prueft, ist
 * auch dann gruen, wenn immer verschoben wird.
 */
class ResourceLevellingTest : TestCase() {

  /** Montag. Alle Rechnungen im Test gehen von dieser Woche aus. */
  private val montag: LocalDate = LocalDate.of(2026, 8, 17)

  private val werktags: (LocalDate) -> Boolean =
    { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }

  private fun task(
    id: String, dauer: Int, last: Int = 100, prio: Int = 2, reihe: Int = 0,
    vorgaenger: List<String> = emptyList(),
    fest: LocalDate? = null, fruehestens: LocalDate? = null
  ) = LevelTask(id, reihe, prio, dauer, last, vorgaenger, fest, fruehestens)

  /**
   * DER KERNFALL, und der Grund, warum es diese Datei gibt: zwei Vorgaenge, eine Person, beide
   * voll. GanttProject legt beide auf denselben Montag. Hier laufen sie nacheinander.
   */
  fun testTwoFullTasksRunOneAfterTheOther() {
    val r = levelTasks(listOf(task("a", 3), task("b", 2, reihe = 1)), montag, werktags)
    assertEquals(montag, r.starts["a"])
    assertEquals("b muss nach den drei Tagen von a beginnen", montag.plusDays(3), r.starts["b"])
    assertTrue(r.conflicts.isEmpty())
  }

  /**
   * Gegenprobe dazu: passen beide nebeneinander, wird NICHT verschoben. Gleichzeitige
   * Arbeit ist ausdruecklich erlaubt.
   */
  fun testTwoHalfTasksRunAtTheSameTime() {
    val r = levelTasks(listOf(task("a", 3, last = 50), task("b", 3, last = 50, reihe = 1)),
      montag, werktags)
    assertEquals(montag, r.starts["a"])
    assertEquals("beide zu 50 % passen nebeneinander", montag, r.starts["b"])
    assertTrue(r.conflicts.isEmpty())
  }

  /** Drei zu 50 % passen nicht mehr: der dritte muss warten, bis einer frei wird. */
  fun testThirdHalfTaskHasToWait() {
    val r = levelTasks(
      listOf(task("a", 2, last = 50), task("b", 4, last = 50, reihe = 1), task("c", 1, last = 50, reihe = 2)),
      montag, werktags)
    assertEquals(montag, r.starts["a"])
    assertEquals(montag, r.starts["b"])
    assertEquals("c passt erst, wenn a fertig ist", montag.plusDays(2), r.starts["c"])
  }

  /** Wichtigeres zuerst, auch wenn es im Plan weiter unten steht. */
  fun testHigherPriorityGoesFirst() {
    val r = levelTasks(
      listOf(task("unwichtig", 2, prio = 1, reihe = 0), task("wichtig", 2, prio = 4, reihe = 1)),
      montag, werktags)
    assertEquals(montag, r.starts["wichtig"])
    assertEquals(montag.plusDays(2), r.starts["unwichtig"])
  }

  /** Bei gleicher Prioritaet entscheidet die Reihenfolge im Plan, nicht der Zufall. */
  fun testEqualPriorityFollowsPlanOrder() {
    val r = levelTasks(
      listOf(task("zweiter", 2, prio = 2, reihe = 5), task("erster", 2, prio = 2, reihe = 1)),
      montag, werktags)
    assertEquals(montag, r.starts["erster"])
    assertEquals(montag.plusDays(2), r.starts["zweiter"])
  }

  /** Abhaengigkeiten gelten weiterhin, auch wenn Kapazitaet frei waere. */
  fun testPredecessorIsRespectedEvenWithFreeCapacity() {
    val r = levelTasks(
      listOf(task("erst", 2, last = 10), task("dann", 1, last = 10, reihe = 1, vorgaenger = listOf("erst"))),
      montag, werktags)
    assertEquals(montag, r.starts["erst"])
    assertEquals("Kapazitaet waere frei, die Abhaengigkeit gilt trotzdem",
      montag.plusDays(2), r.starts["dann"])
  }

  /**
   * Ueber das Wochenende wird nicht gearbeitet: drei Arbeitstage ab Donnerstag belegen Do, Fr und
   * Montag, der Nachfolger kann erst am Dienstag.
   *
   * BEIM ERSTEN ANLAUF FALSCH GETESTET: ohne Abhaengigkeit musste der zweite Vorgang gar nicht
   * warten -- Montag bis Mittwoch waren frei, und er startete am Montag. Der Test prueft jetzt,
   * was er pruefen soll.
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

  /** „Fruehester Beginn" ist eine untere Schranke, keine Fixierung: spaeter darf es werden. */
  fun testEarliestStartIsALowerBoundOnly() {
    val mittwoch = montag.plusDays(2)
    val r = levelTasks(
      listOf(task("blockierer", 5), task("spaet", 1, reihe = 1, fruehestens = mittwoch)),
      montag, werktags)
    // plusDays(5) waere ein Samstag -- der naechste Arbeitstag nach der vollen Woche ist Montag.
    assertEquals("nicht vor Mittwoch, aber der Platz ist erst spaeter frei",
      montag.plusDays(7), r.starts["spaet"])
  }

  /**
   * Ein fester Termin bleibt stehen, auch wenn die Kapazitaet dadurch gesprengt wird -- und genau
   * das wird gemeldet. So entschieden, weil eine Frist stillschweigend zu verschieben
   * das Problem versteckt.
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

  /** Liegt der feste Termin vor dem fruehestmoeglichen, wird auch das gemeldet. */
  fun testUnreachableFixedDateIsReported() {
    val r = levelTasks(
      listOf(task("erst", 5), task("frist", 1, reihe = 1, fest = montag, vorgaenger = listOf("erst"))),
      montag, werktags)
    val problem = r.conflicts.filterIsInstance<LevelConflict.FixedDateNotReachable>()
    assertEquals(1, problem.size)
    assertEquals("frist", problem[0].id)
    assertEquals(montag, problem[0].fixedStart)
    // "erst" belegt Mo bis Fr, fruehestens moeglich ist damit der Montag darauf.
    assertEquals(montag.plusDays(7), problem[0].earliestPossible)
  }

  /**
   * Gegenprobe zu den beiden davor: ein fester Termin, der passt, erzeugt KEINE Meldung. Sonst
   * waere die Konfliktliste bei jedem festen Termin voll und damit wertlos.
   */
  fun testAFixedDateThatFitsReportsNothing() {
    val r = levelTasks(listOf(task("frist", 2, fest = montag)), montag, werktags)
    assertEquals(montag, r.starts["frist"])
    assertTrue("kein Konflikt, wenn der Termin haltbar ist", r.conflicts.isEmpty())
  }

  /** Ein Kreis wird gemeldet, statt das Programm in eine Endlosschleife zu schicken. */
  fun testACycleIsReportedInsteadOfHanging() {
    val r = levelTasks(
      listOf(task("a", 1, vorgaenger = listOf("b")), task("b", 1, vorgaenger = listOf("a"))),
      montag, werktags)
    assertTrue(r.starts.isEmpty())
    assertEquals(1, r.conflicts.filterIsInstance<LevelConflict.Cycle>().size)
  }

  /** Ohne Vorgaenge passiert nichts, und zwar ohne Ausnahme. */
  fun testEmptyPlanIsNotAnError() {
    val r = levelTasks(emptyList(), montag, werktags)
    assertTrue(r.starts.isEmpty())
    assertTrue(r.conflicts.isEmpty())
  }
}
