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

import net.sourceforge.ganttproject.GanttPreviousState
import net.sourceforge.ganttproject.GanttPreviousStateTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * TIDYING UP: at most fifteen AUTOMATICALLY CREATED baselines PER KIND, older ones go.
 *
 * PER KIND AND NOT IN TOTAL. There are two automatic kinds in this fork: the one the levelling
 * takes before every run (`fork.baseline.name`, "Before levelling {0}") and the supplement this
 * package adds (`fork.baseline.catchup.name`). A single shared budget would let the kind that
 * runs more often eat the other one's places, and the states one actually wanted to compare would
 * be the ones that disappear.
 *
 * THE PROMISE THAT MATTERS MOST, and it has a test of its own further down: ONLY a name matching
 * one of those patterns is ever deleted. A baseline someone named by hand survives any number of
 * tidy-up runs, however many of them there are.
 */
class AutoBaselinePruneTest {

  /** A baseline needs no tasks for this question -- only a name and a temporary file. */
  private fun baseline(name: String): GanttPreviousState =
    GanttPreviousState(name, emptyList<GanttPreviousStateTask>()).also {
      it.init()
      it.saveFile()
    }

  private fun levellingName(day: String) = forkText("fork.baseline.name", day)

  private fun catchUpName(stamp: String) = forkText("fork.baseline.catchup.name", stamp)

  /**
   * OVER THE LIMIT, THE OLDEST OF THE KIND GOES. "Oldest" is the position in the list: a
   * `<previous-tasks>` element carries no timestamp, so insertion order -- which is also the order
   * the file is written and read back in -- is the only ordering there is. Said out loud here so
   * that it is a decision and not an accident.
   *
   * RED against 67f8b58df -- no production code at all:
   *   e: .../fork/AutoBaselinePruneTest.kt:74:20 Unresolved reference 'pruneAutoBaselines'.
   */
  @Test
  fun `ueber der grenze faellt der aelteste automatische basisplan`() {
    val baselines = mutableListOf(
      baseline(levellingName("2026-08-01")),
      baseline(levellingName("2026-08-02")),
      baseline(levellingName("2026-08-03")))

    val entfernt = pruneAutoBaselines(baselines, limit = 2)

    assertEquals(listOf(levellingName("2026-08-01")), entfernt.map { it.name },
      "genau der aelteste der Sorte wird entfernt")
    assertEquals(listOf(levellingName("2026-08-02"), levellingName("2026-08-03")),
      baselines.map { it.name }, "die juengeren bleiben in ihrer Reihenfolge stehen")
  }

  /**
   * THE LIMIT IS PER KIND. Three of each with a limit of two has to leave two of EACH -- not two
   * in total, and not two of whichever kind happens to sit at the front of the list.
   *
   * RED against 67f8b58df:
   *   e: .../fork/AutoBaselinePruneTest.kt:99:5 Unresolved reference 'pruneAutoBaselines'.
   */
  @Test
  fun `die grenze gilt je sorte und nicht insgesamt`() {
    val baselines = mutableListOf(
      baseline(levellingName("2026-08-01")),
      baseline(catchUpName("2026-08-01 09:00:00")),
      baseline(levellingName("2026-08-02")),
      baseline(catchUpName("2026-08-02 09:00:00")),
      baseline(levellingName("2026-08-03")),
      baseline(catchUpName("2026-08-03 09:00:00")))

    pruneAutoBaselines(baselines, limit = 2)

    assertEquals(4, baselines.size, "zwei je Sorte muessen uebrigbleiben, nicht zwei insgesamt")
    assertEquals(2, baselines.count { autoBaselineKindOf(it.name) == AutoBaselineKind.BEFORE_LEVELLING },
      "von der Verteilungs-Sorte bleiben zwei")
    assertEquals(2, baselines.count { autoBaselineKindOf(it.name) == AutoBaselineKind.CATCH_UP },
      "und von der Nachtrags-Sorte ebenfalls zwei")
  }

  /**
   * THE ASSURANCE OF THIS WHOLE PACKAGE, and it gets its own test because everything else is
   * recoverable and this is not: a HAND-NAMED baseline is never deleted, no matter how many of
   * them there are and no matter how often the tidy-up runs.
   *
   * A hundred of them, twenty runs, mixed in among automatic ones that DO get thinned out.
   *
   * RED against 67f8b58df:
   *   e: .../fork/AutoBaselinePruneTest.kt:125:18 Unresolved reference 'pruneAutoBaselines'.
   */
  @Test
  fun `ein von hand benannter basisplan ueberlebt beliebig viele aufraeumlaeufe`() {
    val vonHand = (1..100).map { baseline("Meilenstein $it") }
    val baselines = mutableListOf<GanttPreviousState>()
    vonHand.forEach { baselines.add(it) }
    repeat(20) { baselines.add(baseline(levellingName("2026-08-%02d".format(it + 1)))) }

    repeat(20) { pruneAutoBaselines(baselines, limit = 2) }

    assertEquals(100, baselines.count { autoBaselineKindOf(it.name) == null },
      "kein einziger von Hand benannter Basisplan darf verschwinden")
    vonHand.forEachIndexed { i, b ->
      assertSame(b, baselines[i], "der von Hand benannte Basisplan Nr. ${i + 1} ist nicht mehr derselbe")
    }
    assertEquals(2, baselines.count { autoBaselineKindOf(it.name) != null },
      "die automatischen dagegen sind auf die Grenze zusammengeschmolzen")
  }

  /**
   * NAMES THAT ONLY LOOK LIKE THE PATTERN ARE NOT TOUCHED. The pattern's fixed part has to match
   * from beginning to end -- "Before levelling the office move" is a hand-written name that starts
   * like the automatic one, and deleting it would be exactly the failure this guards against.
   *
   * RED against 67f8b58df:
   *   e: .../fork/AutoBaselinePruneTest.kt:146:16 Unresolved reference 'autoBaselineKindOf'.
   */
  @Test
  fun `ein selbstvergebener name der nur so anfaengt gilt nicht als automatisch`() {
    assertNull(autoBaselineKindOf("Stand vor dem Umzug"),
      "ein gewoehnlicher Name ist keine automatische Sorte")
    assertNull(autoBaselineKindOf(""),
      "ein leerer Name auch nicht")
    assertEquals(AutoBaselineKind.BEFORE_LEVELLING, autoBaselineKindOf(levellingName("2026-08-01")),
      "der echte Name der Verteilungs-Sorte wird dagegen erkannt")
  }

  /**
   * A NAME WRITTEN IN THE OTHER LANGUAGE OF THE BUNDLE IS RECOGNISED TOO. The name is built from
   * a translated pattern, so a baseline taken in a German session and tidied up in an English one
   * would otherwise pile up for ever. Both bundles are asked.
   *
   * RED against 67f8b58df:
   *   e: .../fork/AutoBaselinePruneTest.kt:169:18 Unresolved reference 'AutoBaselineKind'.
   */
  @Test
  fun `ein name aus der anderen sprache des buendels wird ebenfalls erkannt`() {
    val deutsch = ForkI18n.textOrNull("fork.baseline.name", Locale.GERMAN, "2026-08-01")
    val englisch = ForkI18n.textOrNull("fork.baseline.name", Locale.ENGLISH, "2026-08-01")
    assertTrue(deutsch != null && englisch != null && deutsch != englisch,
      "Aufbau: die beiden Buendel muessen fuer diesen Schluessel verschiedene Muster tragen")

    assertEquals(AutoBaselineKind.BEFORE_LEVELLING, autoBaselineKindOf(deutsch!!),
      "der deutsche Name wird erkannt")
    assertEquals(AutoBaselineKind.BEFORE_LEVELLING, autoBaselineKindOf(englisch!!),
      "der englische ebenso")
  }

  /**
   * THE PREVIEW NAMES EXACTLY WHAT A RUN WOULD REMOVE, including the one baseline that does not
   * exist yet: the caller asks BEFORE writing, and at that moment the supplement is still to come.
   * With the limit already reached, adding one more has to cost the oldest one its place -- and
   * the question has to say so.
   *
   * RED against 67f8b58df:
   *   e: .../fork/AutoBaselinePruneTest.kt:190:21 Unresolved reference 'autoBaselinesToPrune'.
   */
  @Test
  fun `die vorschau zaehlt den noch nicht angelegten basisplan mit`() {
    val baselines = mutableListOf(
      baseline(catchUpName("2026-08-01 09:00:00")),
      baseline(catchUpName("2026-08-02 09:00:00")))

    val ohneNeuen = autoBaselinesToPrune(baselines, incoming = null, limit = 2)
    val mitNeuem = autoBaselinesToPrune(baselines, incoming = AutoBaselineKind.CATCH_UP, limit = 2)

    assertEquals(emptyList<String>(), ohneNeuen.map { it.name },
      "ohne einen weiteren Basisplan ist die Grenze eingehalten")
    assertEquals(listOf(catchUpName("2026-08-01 09:00:00")), mitNeuem.map { it.name },
      "mit dem noch anzulegenden faellt der aelteste, und die Vorschau muss ihn nennen")
    assertEquals(2, baselines.size, "die Vorschau selbst aendert nichts")
  }

  /**
   * A REMOVED BASELINE'S TEMPORARY FILE IS GONE. `GanttPreviousState.remove()` (line 95-97)
   * deletes it; taking the entry out of the list alone would leave the file behind until the JVM
   * exits, and the point of the limit is that they do not accumulate.
   *
   * RED against 67f8b58df:
   *   e: .../fork/AutoBaselinePruneTest.kt:214:5 Unresolved reference 'pruneAutoBaselines'.
   */
  @Test
  fun `der entfernte basisplan laesst keine temporaere datei zurueck`() {
    val alt = baseline(levellingName("2026-08-01"))
    val baselines = mutableListOf(alt, baseline(levellingName("2026-08-02")))
    assertTrue(alt.load() != null, "Aufbau: die temporaere Datei ist lesbar")

    pruneAutoBaselines(baselines, limit = 1)

    assertNull(alt.load(),
      "nach dem Entfernen darf die temporaere Datei nicht mehr lesbar sein")
  }

  /**
   * THE DEFAULT LIMIT IS FIFTEEN, per kind. Pinned so that a change to the number is a change to
   * this test and not a silent one.
   *
   * RED against 67f8b58df:
   *   e: .../fork/AutoBaselinePruneTest.kt:229:22 Unresolved reference 'MAX_AUTO_BASELINES_PER_KIND'.
   */
  @Test
  fun `die vorgabegrenze ist fuenfzehn je sorte`() {
    assertEquals(15, MAX_AUTO_BASELINES_PER_KIND)

    val baselines = (1..16).mapTo(mutableListOf()) { baseline(levellingName("2026-08-%02d".format(it))) }
    val entfernt = pruneAutoBaselines(baselines)

    assertEquals(1, entfernt.size, "der sechzehnte kostet genau einen Platz")
    assertEquals(15, baselines.size, "und fuenfzehn bleiben stehen")
  }
}
