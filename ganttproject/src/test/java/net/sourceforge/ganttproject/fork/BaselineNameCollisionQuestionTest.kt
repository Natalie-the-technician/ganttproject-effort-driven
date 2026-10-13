/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.

Does "einmal fragen, für alle" actually hold at the level BELOW the real dialog -- the part that
does not need a window to check?

THE CENTRAL CLAIM MEASURED HERE: [resolveBaselineNameCollision] calls its `ask` function AT MOST
ONCE per import, with EVERY colliding name handed to it together, and NEVER AT ALL when nothing
collided. A version that asked once per colliding baseline, or asked even with an empty list,
would still make every other test in this file pass if they only checked the RETURNED policy --
which is why the counting assertions below exist next to the value assertions, not instead of
them.

This file is part of GanttProject, an opensource project management tool.
Licensed under the GNU General Public License, version 3 or later.
*/
package net.sourceforge.ganttproject.fork

import net.sourceforge.ganttproject.GanttPreviousState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BaselineNameCollisionQuestionTest {

  private fun baseline(name: String): GanttPreviousState = GanttPreviousState(name, emptyList())

  // ---- baselineNameCollisions() -------------------------------------------------------------

  @Test
  fun `keine kollision, wenn kein name uebereinstimmt`() {
    val quelle = listOf(baseline("Quelle A"), baseline("Quelle B"))
    val ziel = listOf(baseline("Ziel A"))
    assertEquals(emptyList<String>(), baselineNameCollisions(quelle, ziel))
  }

  @Test
  fun `eine kollision, wenn genau ein name uebereinstimmt`() {
    val quelle = listOf(baseline("Basis"))
    val ziel = listOf(baseline("Basis"))
    assertEquals(listOf("Basis"), baselineNameCollisions(quelle, ziel))
  }

  @Test
  fun `MEHRERE kollisionen werden alle zusammen gemeldet -- der Fall mit den bis zu 15 automatischen Basisplaenen`() {
    // Belegt Auflage 1: der Fork legt bis zu MAX_AUTO_BASELINES_PER_KIND automatische Basisplaene
    // je Art an, hier mit drei Arten nachgestellt. Waere das Ergebnis nur der ERSTE Treffer (ein
    // Implementierungsfehler, der "einmal fragen, fuer alle" unterlaufen wuerde, weil die
    // restlichen Namen nie zum Dialog kaemen), wuerde diese Zusicherung fehlschlagen.
    val quelle = listOf(baseline("Vor Verteilung"), baseline("Vor Planung"), baseline("Vor Export"), baseline("Unikat"))
    val ziel = listOf(baseline("Vor Verteilung"), baseline("Vor Planung"), baseline("Vor Export"))
    assertEquals(
      listOf("Vor Verteilung", "Vor Planung", "Vor Export"),
      baselineNameCollisions(quelle, ziel),
      "alle drei Kollisionen muessen in EINER Liste stehen, nicht nur die erste"
    )
  }

  @Test
  fun `ein kollidierender name kommt nur einmal vor, auch wenn zwei quell-basisplaene ihn tragen`() {
    // Zwei QUELL-Basisplaene desselben Namens sind genau der Fall, den
    // BaselineNameCollisionMeasurementTest unter "zwei basisplaene heissen gleich" beschreibt.
    val quelle = listOf(baseline("Basis"), baseline("Basis"))
    val ziel = listOf(baseline("Basis"))
    assertEquals(
      listOf("Basis"), baselineNameCollisions(quelle, ziel),
      "eine Frage pro NAMEN, nicht pro Basisplan -- sonst waere es zweimal fragen statt einmal"
    )
  }

  @Test
  fun `positivkontrolle -- unterschiedliche namen sind keine kollision`() {
    val quelle = listOf(baseline("Alpha"))
    val ziel = listOf(baseline("Beta"))
    assertTrue(
      baselineNameCollisions(quelle, ziel).isEmpty(),
      "unterschiedliche Namen duerfen nicht als Kollision gezaehlt werden -- sonst wuerde jede Messung oben nur zeigen, dass die Funktion immer alles meldet"
    )
  }

  // ---- BaselineCollisionAnswer --------------------------------------------------------------

  @Test
  fun `die drei dialog-antworten tragen die richtige richtlinie`() {
    assertEquals(BaselineNameCollision.APPEND_AS_IS, BaselineCollisionAnswer.KEEP_BOTH.policy)
    assertEquals(BaselineNameCollision.PREFIX_WITH_PROJECT_NAME, BaselineCollisionAnswer.RENAME_IMPORTED.policy)
    assertEquals(BaselineNameCollision.REPLACE_EXISTING, BaselineCollisionAnswer.OVERWRITE_EXISTING.policy)
  }

  // ---- resolveBaselineNameCollision() -------------------------------------------------------

  @Test
  fun `ohne kollision wird NICHT gefragt`() {
    var askCalls = 0
    val resolution = resolveBaselineNameCollision(emptyList()) { askCalls++; BaselineCollisionAnswer.KEEP_BOTH }
    assertEquals(BaselineNameCollisionResolution.NothingToAsk, resolution)
    assertEquals(0, askCalls, "ohne Kollision darf der Dialog gar nicht erst fallen")
  }

  @Test
  fun `bei kollision wird GENAU EINMAL gefragt, mit allen namen zusammen`() {
    var askCalls = 0
    var gefragteNamen: List<String>? = null
    val namen = listOf("Basis A", "Basis B", "Basis C")
    val resolution = resolveBaselineNameCollision(namen) { kollidierende ->
      askCalls++
      gefragteNamen = kollidierende
      BaselineCollisionAnswer.RENAME_IMPORTED
    }
    assertEquals(1, askCalls, "'einmal fragen, fuer alle' heisst EIN Aufruf, auch bei drei Kollisionen")
    assertEquals(namen, gefragteNamen, "alle drei Namen muessen im EINEN Aufruf stecken, nicht nur einer")
    assertEquals(
      BaselineNameCollisionResolution.Answered(BaselineNameCollision.PREFIX_WITH_PROJECT_NAME), resolution
    )
  }

  @Test
  fun `ohne antwort -- der dialog liefert null -- ist das ergebnis Unanswered, nicht irgendeine richtlinie`() {
    val resolution = resolveBaselineNameCollision(listOf("Basis")) { null }
    assertEquals(
      BaselineNameCollisionResolution.Unanswered, resolution,
      "null vom Dialog darf NICHT leise in eine der drei Richtlinien uebersetzt werden"
    )
  }

  @Test
  fun `Unanswered und NothingToAsk sind verschiedene Zustaende, auch wenn beide spaeter zu 'nicht einlesen' fuehren`() {
    // Der Punkt dieses Typs: ein Aufrufer, der beide Zustaende gleich behandelt, verwischt genau
    // die Unterscheidung, die Auflage 2 braucht ("ohne Antwort passiert nichts" ist nur fuer
    // Unanswered wahr -- bei NothingToAsk passiert der Import ganz normal weiter).
    val nichtsZuFragen = resolveBaselineNameCollision(emptyList()) { BaselineCollisionAnswer.KEEP_BOTH }
    val unbeantwortet = resolveBaselineNameCollision(listOf("Basis")) { null }
    assertTrue(nichtsZuFragen is BaselineNameCollisionResolution.NothingToAsk)
    assertTrue(unbeantwortet is BaselineNameCollisionResolution.Unanswered)
    assertTrue(nichtsZuFragen != unbeantwortet)
  }
}
