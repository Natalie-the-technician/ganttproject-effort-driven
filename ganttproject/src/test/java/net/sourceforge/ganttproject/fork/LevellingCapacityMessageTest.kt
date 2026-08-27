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
 * THE OTHER HALF OF THE DECISION FOR P6: the capacity dead end is not only recorded, it is SAID —
 * in words, with the Task and the people in them.
 *
 * A FILE OF ITS OWN BESIDE `LevellingIntersectionMessageTest`, which does the same job for P5's
 * message. The two are deliberately not merged: P5's message was to stay exactly as it is, and a
 * shared test file is the first step towards a shared implementation.
 *
 * MEASURED ON [capacityWindowText] AND NOT THROUGH THE MENU ITEM, for the reason written up next
 * door: `LevellingAction` needs a whole running project and no test in this tree drives it. What
 * is NOT covered by this is the wiring — that the action really calls this function and really
 * hands it the model's names is read in `LevellingActions.kt` and asserted nowhere. That gap is
 * written down in the stage report rather than papered over.
 */
class LevellingCapacityMessageTest {

  private fun conflict(id: String, vararg people: String) =
    LevelConflict.NoDayWithCapacity(id, people.toList())

  private val taskName: (String) -> String = { if (it == "12") "Abnahme Pruefstand" else it }

  /**
   * Marks every lookup, so a test can tell whether an id went through the PERSON lookup at all.
   * That matters for [SHARED_POOL], which must not.
   */
  private val personName: (String) -> String =
    { mapOf("3" to "Petra", "7" to "Quirin")[it] ?: "PERSON:$it" }

  /**
   * THE POINT OF THE WHOLE STAGE, in one assertion: the text names the Task and it names the
   * people whose days were full. Both by name, neither by id.
   *
   * The assertions are on CONTAINMENT and not on the whole sentence, for the same reason as next
   * door: the wording will be improved, translated and shortened, and a test on the exact sentence
   * would go red on every comma and then be repaired rather than read.
   */
  @Test
  fun `the message names the task and the full people`() {
    val text = capacityWindowText(listOf(conflict("12", "3", "7")), taskName, personName)

    assertTrue(text.contains("Abnahme Pruefstand"), "der Vorgang fehlt in der Meldung: $text")
    assertTrue(text.contains("Petra"), "die erste volle Person fehlt: $text")
    assertTrue(text.contains("Quirin"), "die zweite volle Person fehlt: $text")
    assertFalse(text.contains("fork.levelling.nocapacity"),
      "der Schluessel steht im Text, die Uebersetzung fehlt also: $text")
  }

  /**
   * Nothing to report means no text at all. The caller appends two blank lines in front of this
   * block, and an empty block would leave them standing in the dialog.
   */
  @Test
  fun `nothing to report yields no text`() {
    assertEquals("", capacityWindowText(emptyList(), taskName, personName))
  }

  /**
   * THE POOL OF THE UNASSIGNED TASKS IS NAMED, not left as a blank.
   *
   * [SHARED_POOL] is the empty string, and it is a perfectly ordinary entry in
   * [LevelConflict.NoDayWithCapacity.fullFor] — Tasks nobody is assigned to occupy time in it just
   * like everybody else. Sent through the person lookup it would come back as an empty string or
   * as a resource id of -1, and the row would read "…: the working days of  are already taken".
   * That is a sentence with a hole in it, and a hole reads as a bug rather than as an answer.
   *
   * IT COULD NOT BE RED AGAINST THE STATE BEFORE THIS STAGE — before it, there was no message to
   * put a hole in. It has been seen to fail all the same: with the special case taken back out of
   * `capacityWindowText` it failed with
   *
   *   der gemeinsame Topf darf nicht durch die Personensuche laufen: … • Abnahme Pruefstand: die
   *   Arbeitstage von PERSON: sind an jedem in Frage kommenden Termin schon belegt
   *   ==> expected: <false> but was: <true>
   */
  @Test
  fun `the shared pool is named rather than left blank`() {
    val text = capacityWindowText(listOf(conflict("12", SHARED_POOL)), taskName, personName)

    assertFalse(text.contains("PERSON:"),
      "der gemeinsame Topf darf nicht durch die Personensuche laufen: $text")
    assertTrue(text.contains(forkText("fork.levelling.nocapacity.shared")),
      "der gemeinsame Topf braucht einen eigenen Namen: $text")
  }

  /**
   * An id with no name behind it still produces a usable line: the Task may have been deleted
   * between the calculation and the dialog, and a person may have. A report that answers such a
   * case with "null" or with a blank says less than the bare id does.
   */
  @Test
  fun `an unknown id falls back to the id instead of a blank`() {
    val text = capacityWindowText(listOf(conflict("999", "888")), { it }, { it })

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

    val text = capacityWindowText(viele, { it }, personName)

    assertEquals(5, text.lines().count { it.trimStart().startsWith("•") },
      "es duerfen hoechstens fuenf Zeilen stehen: $text")
    assertTrue(text.contains(forkText("fork.levelling.more", 2)),
      "die zwei uebrigen muessen erwaehnt werden: $text")
  }

  /**
   * The three keys are really in both bundles, in the language they belong to.
   *
   * The bundle-wide check in `ForkI18nTest` proves that every German key has an English
   * counterpart, which would already be red if one of the two files had been forgotten. What it
   * cannot show is that the German file carries GERMAN — a line copied over from the English one
   * satisfies it perfectly. Hence one word from each language here.
   *
   * WHY THE KEYS BELONG IN THE FORK BUNDLE AND NOT IN THE MAIN ONE: the main bundle lives in the
   * submodule `biz.ganttproject.app.localization`, which points at a foreign repository. A key
   * entered there would never travel in a commit of this fork and would show up as a bare key on
   * every other machine. See `ForkI18n.kt`.
   *
   * IN ITS RED RUN this test failed with
   *
   *   org.opentest4j.AssertionFailedError: fork.levelling.nocapacity fehlt im deutschen Buendel
   *   ==> expected: not <null>
   */
  @Test
  fun `both bundles carry the three keys`() {
    listOf("fork.levelling.nocapacity", "fork.levelling.nocapacity.row",
      "fork.levelling.nocapacity.shared").forEach { key ->
      assertTrue(ForkI18n.textOrNull(key, Locale.GERMANY, 1, "x")?.isNotBlank() == true,
        "$key fehlt im deutschen Buendel")
      assertTrue(ForkI18n.textOrNull(key, Locale.US, 1, "x")?.isNotBlank() == true,
        "$key fehlt im englischen Buendel")
    }
    assertTrue(ForkI18n.textOrNull("fork.levelling.nocapacity", Locale.GERMANY, 1)!!
      .contains("Vorgang"), "der deutsche Text ist nicht deutsch")
    assertTrue(ForkI18n.textOrNull("fork.levelling.nocapacity", Locale.US, 1)!!
      .contains("task"), "der englische Text ist nicht englisch")
  }

  /**
   * Singular and plural are both formed, and they differ. `MessageFormat`'s `choice` is easy to
   * write in a way that compiles, formats and yields the plural for one Task as well — which
   * reads as sloppiness in a message whose whole job is to be taken seriously.
   */
  @Test
  fun `one task and several tasks read differently`() {
    val einer = ForkI18n.textOrNull("fork.levelling.nocapacity", Locale.GERMANY, 1)
    val mehrere = ForkI18n.textOrNull("fork.levelling.nocapacity", Locale.GERMANY, 3)

    assertTrue(einer!!.startsWith("Ein Vorgang"), "Einzahl: $einer")
    assertTrue(mehrere!!.startsWith("3 Vorgaenge"), "Mehrzahl: $mehrere")
  }

  /**
   * P5's message and P6's are two different texts, and neither has been replaced by the other.
   *
   * Cheap, and it guards the one thing the stage was told not to do: P5's message stays as it is.
   * If somebody ever folds the two blocks into one shared builder, this is where it shows.
   */
  @Test
  fun `the two messages are not the same text`() {
    val p5 = blockedIntersectionText(
      listOf(LevelConflict.BlockingIntersectionEmpty("12", listOf("3"))), taskName, personName)
    val p6 = capacityWindowText(listOf(conflict("12", "3")), taskName, personName)

    assertTrue(p5.isNotBlank() && p6.isNotBlank(), "beide Meldungen muessen etwas sagen")
    assertFalse(p5 == p6, "die zwei Meldungen duerfen nicht derselbe Text sein")
  }
}
