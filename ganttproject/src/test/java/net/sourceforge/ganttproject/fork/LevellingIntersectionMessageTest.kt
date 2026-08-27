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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * THE OTHER HALF OF THE DECISION: the impossible intersection is not only recorded, it is SAID —
 * in words, with the Task and the people in them.
 *
 * WHY THIS FILE EXISTS BESIDE `ResourceLevellingTest`. That one measures the calculation and can
 * prove that `LevelConflict.BlockingIntersectionEmpty` carries the right id and the right set of
 * people. Ids are not a message. Between the conflict and the person reading the dialog sit two
 * translations — id to Task name, id to person name — and a text that says "12: 3, 7 all have to
 * be there" would pass every test over there and be useless in the one situation it was written
 * for.
 *
 * MEASURED ON [blockedIntersectionText] AND NOT THROUGH THE MENU ITEM. `LevellingAction` needs a
 * whole running project — an undo manager, baselines, a resource manager — and no test in this
 * tree drives it; the blocks of the preview beside this one therefore have no test at all. Rather
 * than copy that state, the one block that has to name names was pulled out into a function that
 * can be measured on its own. What is NOT covered by this is the wiring: that the action really
 * calls it, and really hands it the model's names, is read in `LevellingActions.kt` and not
 * asserted anywhere. That gap is written down in the stage report rather than papered over.
 */
class LevellingIntersectionMessageTest {

  private fun conflict(id: String, vararg people: String) =
    LevelConflict.BlockingIntersectionEmpty(id, people.toList())

  /** Ids as they arrive, names as the plan has them. */
  private val taskName: (String) -> String = { if (it == "12") "Abnahme Pruefstand" else it }
  private val personName: (String) -> String =
    { mapOf("3" to "Petra", "7" to "Quirin")[it] ?: it }

  /**
   * THE POINT OF THE WHOLE STAGE, in one assertion: the text names the Task and it names the
   * people. Both by name, neither by id.
   *
   * The assertions are on CONTAINMENT and not on the whole sentence, and that is deliberate. The
   * wording is going to be improved, translated and shortened; the four things that must survive
   * every such rewrite are these four words. A test on the exact sentence would go red on every
   * comma and would then be repaired rather than read.
   */
  @Test
  fun `the message names the task and the people`() {
    val text = blockedIntersectionText(listOf(conflict("12", "3", "7")), taskName, personName)

    assertTrue(text.contains("Abnahme Pruefstand"), "der Vorgang fehlt in der Meldung: $text")
    assertTrue(text.contains("Petra"), "die erste blockierende Person fehlt: $text")
    assertTrue(text.contains("Quirin"), "die zweite blockierende Person fehlt: $text")
    assertFalse(text.contains("fork.levelling.blocked"),
      "der Schluessel steht im Text, die Uebersetzung fehlt also: $text")
  }

  /**
   * Nothing to report means no text at all — not an empty heading, not a bullet with nothing
   * behind it. The caller appends two blank lines in front of this block, and an empty block
   * would leave them standing in the dialog.
   */
  @Test
  fun `nothing to report yields no text`() {
    assertEquals("", blockedIntersectionText(emptyList(), taskName, personName))
  }

  /**
   * An id with no name behind it still produces a usable line.
   *
   * This is not decoration: the Task may have been deleted between the calculation and the
   * dialog, and a person may have. A report that answers such a case with "null" or with a blank
   * says less than the bare id does.
   */
  @Test
  fun `an unknown id falls back to the id instead of a blank`() {
    val text = blockedIntersectionText(listOf(conflict("999", "888")), { it }, { it })

    assertTrue(text.contains("999"), "der unbekannte Vorgang muss wenigstens als Nummer stehen: $text")
    assertTrue(text.contains("888"), "die unbekannte Person muss wenigstens als Nummer stehen: $text")
  }

  /**
   * A long list is cut off after five, exactly as the neighbouring blocks of the preview are, and
   * the cut is SAID. A dialog that silently shows five of two hundred is the same kind of quiet
   * this whole stage is about.
   */
  @Test
  fun `more than five are cut off and the cut is mentioned`() {
    val viele = (1..7).map { conflict(it.toString(), "3") }

    val text = blockedIntersectionText(viele, { it }, personName)

    assertEquals(5, text.lines().count { it.trimStart().startsWith("•") },
      "es duerfen hoechstens fuenf Zeilen stehen: $text")
    assertTrue(text.contains(forkText("fork.levelling.more", 2)),
      "die zwei uebrigen muessen erwaehnt werden: $text")
  }

  /**
   * The two keys are really in both bundles, in the language they belong to.
   *
   * The bundle-wide check in `ForkI18nTest` proves that every German key has an English
   * counterpart, which would already be red if one of the two files had been forgotten. What it
   * cannot show is that the German file carries GERMAN — a line copied over from the English one
   * satisfies it perfectly. Hence one word from each language here.
   *
   * WHY THE KEYS BELONG IN THE FORK BUNDLE AND NOT IN THE MAIN ONE, measured rather than assumed:
   * `biz.ganttproject.app.localization` is a SUBMODULE of this tree, and one that points at a
   * foreign repository (`bardsoftware/biz.ganttproject.app.localization`). A key entered there is
   * a change in a different repository, would never travel in a commit of this fork, and would
   * show up as a bare key on every other machine. In a fresh worktree the submodule is not even
   * populated. The `fork.` prefix keeps the two apart -- see `ForkI18n.kt`.
   */
  @Test
  fun `both bundles carry the two keys`() {
    listOf("fork.levelling.blocked", "fork.levelling.blocked.row").forEach { key ->
      assertTrue(ForkI18n.textOrNull(key, Locale.GERMANY, 1, "x")?.isNotBlank() == true,
        "$key fehlt im deutschen Buendel")
      assertTrue(ForkI18n.textOrNull(key, Locale.US, 1, "x")?.isNotBlank() == true,
        "$key fehlt im englischen Buendel")
    }
    assertTrue(ForkI18n.textOrNull("fork.levelling.blocked", Locale.GERMANY, 1)!!
      .contains("Vorgang"), "der deutsche Text ist nicht deutsch")
    assertTrue(ForkI18n.textOrNull("fork.levelling.blocked", Locale.US, 1)!!
      .contains("task"), "der englische Text ist nicht englisch")
  }

  /**
   * Singular and plural are both formed, and they differ.
   *
   * `MessageFormat`'s `choice` is easy to write in a way that compiles, formats and yields the
   * plural for one Task as well. That reads as sloppiness in a message whose whole job is to be
   * taken seriously.
   */
  @Test
  fun `one task and several tasks read differently`() {
    val einer = ForkI18n.textOrNull("fork.levelling.blocked", Locale.GERMANY, 1)
    val mehrere = ForkI18n.textOrNull("fork.levelling.blocked", Locale.GERMANY, 3)

    assertTrue(einer!!.startsWith("Ein Vorgang"), "Einzahl: $einer")
    assertTrue(mehrere!!.startsWith("3 Vorgaenge"), "Mehrzahl: $mehrere")
  }
}
