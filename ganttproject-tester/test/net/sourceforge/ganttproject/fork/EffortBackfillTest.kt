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

/**
 * Die Ableitung von Aufwand aus der Dauer.
 *
 * Der wichtigste Test ist [testFillingChangesNoDuration]: das Hilfsmittel darf den Plan nicht
 * verschieben. Alles andere waere eine stille Aenderung an 226 Vorgaengen auf einen Klick.
 */
class EffortBackfillTest : TestCase() {

  private fun task(
    id: String, dauer: Int, gruppe: Boolean = false, meilenstein: Boolean = false,
    aufwand: Double? = null, zuordnungen: Int = 0, warten: Boolean = false
  // BENANNTE Argumente, nicht der Reihe nach: als BackfillTask um "isWaitOnly" erweitert wurde,
  // rutschten die Werte hier stillschweigend eine Stelle weiter und der Test brach erst beim
  // Uebersetzen -- bei zwei Booleans nebeneinander haette er auch einfach falsch messen koennen.
  ) = BackfillTask(id = id, name = id, durationDays = dauer, isContainer = gruppe,
    isMilestone = meilenstein, isWaitOnly = warten, existingEffortHours = aufwand,
    assignmentCount = zuordnungen)

  /**
   * DIE SICHERHEITSEIGENSCHAFT. Aufwand aus Dauer ableiten und wieder zurueckrechnen muss
   * dieselbe Dauer ergeben -- fuer jede Dauer und jede Tagesleistung, nicht nur fuer glatte.
   */
  fun testFillingChangesNoDuration() {
    for (stunden in listOf(0.5, 1.0, 2.0, 3.0, 6.0, 7.5, 8.0, 10.0)) {
      for (dauer in 1..40) {
        val vorschlag = proposeBackfill(listOf(task("t", dauer)), stunden)
        val aufwand = vorschlag.effortHours.getValue("t")
        assertEquals(
          "Dauer $dauer bei $stunden Std./Tag muss nach dem Befuellen gleich bleiben",
          dauer, durationFromEffort(aufwand, stunden))
      }
    }
  }

  fun testEffortIsDurationTimesHoursPerDay() {
    val v = proposeBackfill(listOf(task("a", 5)), 8.0)
    assertEquals(40.0, v.effortHours.getValue("a"))
    assertEquals(listOf("a"), v.assignTo)
    assertTrue(v.skipped.isEmpty())
  }

  /** Gruppen leiten ihre Dauer aus den Kindern ab -- Aufwand dort einzutragen waere sinnlos. */
  fun testContainersAreSkipped() {
    val v = proposeBackfill(listOf(task("gruppe", 10, gruppe = true)), 8.0)
    assertTrue(v.effortHours.isEmpty())
    assertTrue("eine Gruppe bekommt auch keine Zuordnung", v.assignTo.isEmpty())
    assertEquals(BackfillSkip.CONTAINER, v.skipped["gruppe"])
  }

  /** Ein Meilenstein hat nichts zu leisten. */
  fun testMilestonesAreSkipped() {
    val v = proposeBackfill(listOf(task("m", 0, meilenstein = true)), 8.0)
    assertEquals(BackfillSkip.MILESTONE, v.skipped["m"])
    assertTrue(v.assignTo.isEmpty())
  }

  /**
   * Vorhandener Aufwand wird NIE ueberschrieben. Wer ihn gepflegt hat, hat mehr gewusst als diese
   * Ableitung -- die kennt nur die Dauer.
   */
  fun testExistingEffortIsNeverOverwritten() {
    val v = proposeBackfill(listOf(task("a", 5, aufwand = 3.0)), 8.0)
    assertTrue(v.effortHours.isEmpty())
    assertEquals(BackfillSkip.ALREADY_HAS_EFFORT, v.skipped["a"])
    assertEquals("zugeordnet wird trotzdem, sonst kennt die Verteilung den Vorgang nicht",
      listOf("a"), v.assignTo)
  }

  /** Wer schon zugeordnet ist, bekommt keine zweite Person: das wuerde die Dauer halbieren. */
  fun testAlreadyAssignedGetsNoSecondPerson() {
    val v = proposeBackfill(listOf(task("a", 5, zuordnungen = 1)), 8.0)
    assertTrue(v.assignTo.isEmpty())
    assertEquals("Aufwand fehlt trotzdem und wird ergaenzt", 40.0, v.effortHours.getValue("a"))
  }

  /** Gegenprobe dazu: ausdruecklich verlangt, wird auch der schon Zugeordnete aufgenommen. */
  fun testSecondAssignmentOnlyOnRequest() {
    val v = proposeBackfill(listOf(task("a", 5, zuordnungen = 1)), 8.0, alreadyAssignedKeepsIts = false)
    assertEquals(listOf("a"), v.assignTo)
  }

  fun testZeroDurationWithoutMilestoneIsSkipped() {
    val v = proposeBackfill(listOf(task("a", 0)), 8.0)
    assertEquals(BackfillSkip.NO_DURATION, v.skipped["a"])
  }

  /** Eine Tagesleistung von 0 waere eine Division durch null -- das faellt frueh auf, nicht spaet. */
  fun testZeroHoursPerDayIsRejected() {
    try {
      proposeBackfill(listOf(task("a", 5)), 0.0)
      fail("0 Stunden pro Tag muessen abgelehnt werden")
    } catch (expected: IllegalArgumentException) {
      // so soll es sein
    }
  }

  /** Die Anzahl der Aenderungen ist das, was in der Vorschau steht -- sie muss stimmen. */
  fun testChangeCountMatchesWhatWillHappen() {
    val v = proposeBackfill(
      listOf(task("a", 5), task("b", 3), task("gruppe", 8, gruppe = true), task("m", 0, meilenstein = true)),
      8.0)
    assertEquals("zwei Aufwaende plus zwei Zuordnungen", 4, v.changeCount)
    assertEquals(2, v.skipped.size)
  }
}
