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
 * THE OTHER HALF OF THE DECISION: a Task for which levelling found no date at all is not only
 * recorded, it is SAID — in words, with the Task and the people in them, and with every reason
 * the search had.
 *
 * WHY THIS FILE EXISTS BESIDE `ResourceLevellingTest`. That one measures the calculation and can
 * prove that `LevelConflict.NoPossibleDate` carries the right id and the right two sets of people.
 * Ids are not a message. Between the conflict and the person reading the dialog sit two
 * translations — id to Task name, id to person name — and a text that says "12: 3, 7 all have to
 * be there" would pass every test over there and be useless in the one situation it was written
 * for.
 *
 * ONE FILE WHERE TWO STOOD. `LevellingIntersectionMessageTest` (P5) and
 * `LevellingCapacityMessageTest` (P6) each measured one of two text builders, and they were kept
 * apart on purpose while P5's message was not to be touched — a shared test file being the first
 * step towards a shared implementation. On 27.08.2026 the shared implementation is the point: one
 * builder, one block, one entry per Task. Every assertion of both files is below; where the two
 * asserted the same thing about their own builder, it is asserted once about the one that
 * replaced them, and the stage report of P7 lists which those were.
 *
 * MEASURED ON [noPossibleDateText] AND NOT THROUGH THE MENU ITEM. `LevellingAction` needs a whole
 * running project — an undo manager, baselines, a resource manager — and no test in this tree
 * drives it; the blocks of the preview beside this one therefore have no test at all. Rather than
 * copy that state, the one block that has to name names was pulled out into a function that can be
 * measured on its own. What is NOT covered by this is the wiring: that the action really calls it,
 * and really hands it the model's names, is read in `LevellingActions.kt` and not asserted
 * anywhere. That gap is written down in the stage report rather than papered over.
 */
class LevellingNoDateMessageTest {

  /** A Task that failed on absence alone — what P5's kind used to stand for. */
  private fun blockiert(id: String, vararg people: String) =
    LevelConflict.NoPossibleDate(id, people.toList(), emptyList())

  /** A Task that failed on capacity alone — what P6's kind used to stand for. */
  private fun ohnePlatz(id: String, vararg people: String) =
    LevelConflict.NoPossibleDate(id, emptyList(), people.toList())

  /** A Task that failed on both — the case that had no report of its own before P7. */
  private fun beides(id: String, blocking: List<String>, full: List<String>) =
    LevelConflict.NoPossibleDate(id, blocking, full)

  /** Ids as they arrive, names as the plan has them. */
  private val taskName: (String) -> String = { if (it == "12") "Abnahme Pruefstand" else it }

  /**
   * Marks every lookup, so a test can tell whether an id went through the PERSON lookup at all.
   * That matters for [SHARED_POOL], which must not.
   */
  private val personName: (String) -> String =
    { mapOf("3" to "Petra", "7" to "Quirin")[it] ?: "PERSON:$it" }

  /**
   * THE POINT OF P5, kept: the text names the Task and it names the blocking people. Both by name,
   * neither by id.
   *
   * The assertions are on CONTAINMENT and not on the whole sentence, and that is deliberate. The
   * wording is going to be improved, translated and shortened; the four things that must survive
   * every such rewrite are these four words. A test on the exact sentence would go red on every
   * comma and would then be repaired rather than read.
   */
  @Test
  fun `the message names the task and the blocking people`() {
    val text = noPossibleDateText(listOf(blockiert("12", "3", "7")), taskName, personName)

    assertTrue(text.contains("Abnahme Pruefstand"), "der Vorgang fehlt in der Meldung: $text")
    assertTrue(text.contains("Petra"), "die erste blockierende Person fehlt: $text")
    assertTrue(text.contains("Quirin"), "die zweite blockierende Person fehlt: $text")
    assertFalse(text.contains("fork.levelling.nodate"),
      "der Schluessel steht im Text, die Uebersetzung fehlt also: $text")
  }

  /** THE POINT OF P6, kept: the same for the people whose days were already booked. */
  @Test
  fun `the message names the task and the full people`() {
    val text = noPossibleDateText(listOf(ohnePlatz("12", "3", "7")), taskName, personName)

    assertTrue(text.contains("Abnahme Pruefstand"), "der Vorgang fehlt in der Meldung: $text")
    assertTrue(text.contains("Petra"), "die erste volle Person fehlt: $text")
    assertTrue(text.contains("Quirin"), "die zweite volle Person fehlt: $text")
    assertFalse(text.contains("fork.levelling.nodate"),
      "der Schluessel steht im Text, die Uebersetzung fehlt also: $text")
  }

  /**
   * THE POINT OF P7, in one assertion: a Task that failed for both reasons gets ONE entry, and
   * that entry names both.
   *
   * Before this stage the same Task got an entry in P5's block AND an entry in P6's block — two
   * bullets, two headings, one date, and the reader left to work out that it was one fallback and
   * not two problems. Counted in bullets rather than in lines, because the reasons under an entry
   * are lines too and it is the ENTRIES that must not double.
   *
   * IN ITS RED RUN AGAINST 08a3244b8 — written against exactly what `LevellingActions.kt` put
   * together then, P5's block plus two blank lines plus P6's — it failed with
   *
   *   org.opentest4j.AssertionFailedError: der Vorgang steht in zwei Eintraegen der Vorschau statt
   *   in einem:
   *   One task finds no date on which EVERY person marked as blocking is at work. […]
   *     • Abnahme Pruefstand: Petra all have to be there at the same time; their available times
   *       never overlap for long enough
   *
   *   One task finds no date with room: every date that came into question was already fully
   *   booked for somebody. […]
   *     • Abnahme Pruefstand: the working days of Petra are already taken on every date in
   *       question ==> expected: <1> but was: <2>
   */
  @Test
  fun `a task with both reasons gets one entry carrying both`() {
    val text = noPossibleDateText(
      listOf(beides("12", listOf("3"), listOf("7"))), taskName, personName)

    assertEquals(1, text.lines().count { it.trimStart().startsWith("•") },
      "der Vorgang darf nur EINMAL in der Vorschau stehen:\n$text")
    assertTrue(text.contains("Petra"), "der Abwesenheitsgrund fehlt: $text")
    assertTrue(text.contains("Quirin"), "der Kapazitaetsgrund fehlt: $text")
  }

  /**
   * AND THE ENTRY NAMES BOTH WAYS OUT, which is the thing the two-block form could not do.
   *
   * P5's remedy sentence — "what has to change is the markings or the days off" — sat in a
   * HEADING. Over a merged block it would stand above Tasks whose only way out is capacity, and
   * for the double case it names one of two true remedies. P6's report called that sentence
   * incomplete and left it alone under its own remit; here it is put right by moving both remedies
   * down into the reason lines, where each belongs to the reason it answers.
   *
   * ASSERTED IN TWO STEPS, AND NEITHER OF THEM ON THE DEFAULT LOCALE. `forkText` renders in the
   * JVM's default locale, and this suite's is not fixed -- measured: the same test saw English
   * when run alone and German when run inside the whole suite, so an assertion on English wording
   * against the rendered text is an assertion on test order. What is checked instead is that the
   * rendered entry really carries both reason lines, and, separately and with the locale pinned,
   * that each of those lines names its own way out -- in BOTH languages, which is more than the
   * default-locale form could have said.
   *
   * IN ITS RED RUN AGAINST 08a3244b8, in the default-locale form it was first written in, it
   * failed with
   *
   *   org.opentest4j.AssertionFailedError: der Eintrag nennt den Markierungs-Ausweg nicht:
   *   • Abnahme Pruefstand: Petra all have to be there at the same time; their available times
   *   never overlap for long enough ==> expected: <true> but was: <false>
   */
  @Test
  fun `the entry for a task with both reasons names both ways out`() {
    val text = noPossibleDateText(
      listOf(beides("12", listOf("3"), listOf("7"))), taskName, personName)

    assertTrue(text.contains(forkText("fork.levelling.nodate.blocked", "Petra")),
      "die Abwesenheitszeile steht nicht im Eintrag:\n$text")
    assertTrue(text.contains(forkText("fork.levelling.nodate.full", "Quirin")),
      "die Kapazitaetszeile steht nicht im Eintrag:\n$text")

    val blockedEn = ForkI18n.textOrNull("fork.levelling.nodate.blocked", Locale.US, "Petra")!!
    val fullEn = ForkI18n.textOrNull("fork.levelling.nodate.full", Locale.US, "Quirin")!!
    assertTrue(blockedEn.contains("markings") && blockedEn.contains("days off"),
      "die englische Abwesenheitszeile nennt ihren Ausweg nicht: $blockedEn")
    assertTrue(fullEn.contains("workload") && fullEn.contains("length of the task"),
      "die englische Kapazitaetszeile nennt ihren Ausweg nicht: $fullEn")

    val blockedDe = ForkI18n.textOrNull("fork.levelling.nodate.blocked", Locale.GERMANY, "Petra")!!
    val fullDe = ForkI18n.textOrNull("fork.levelling.nodate.full", Locale.GERMANY, "Quirin")!!
    assertTrue(blockedDe.contains("Markierungen") && blockedDe.contains("Ausfallzeiten"),
      "die deutsche Abwesenheitszeile nennt ihren Ausweg nicht: $blockedDe")
    assertTrue(fullDe.contains("Auslastung") && fullDe.contains("Dauer"),
      "die deutsche Kapazitaetszeile nennt ihren Ausweg nicht: $fullDe")
  }

  /**
   * THE HEADING NAMES NO REMEDY OF ITS OWN, and this is the assertion that pins the correction of
   * P5's sentence rather than only its replacement.
   *
   * P5's heading ended in "what has to change is the markings or the days off", P6's in "the
   * workload of the people named, or the length of the task". One block over Tasks that failed for
   * different reasons cannot carry either: it would tell a reader whose Task failed on capacity to
   * go and change markings. Checked as an ABSENCE in the heading, because presence in the reason
   * lines — which the test above checks — would stay green if the old sentence were also left
   * standing up top.
   *
   * IT COULD NOT BE RED AGAINST 08a3244b8: there the remedy in the heading was correct, because
   * each heading stood over one reason. It was made to fail on purpose; the output is in the stage
   * report.
   */
  @Test
  fun `the heading carries no remedy of its own`() {
    val heading = ForkI18n.textOrNull("fork.levelling.nodate", Locale.US, 2)!!

    assertFalse(heading.contains("markings"),
      "die Ueberschrift steht auch ueber Vorgaengen ohne Markierung: $heading")
    assertFalse(heading.contains("days off"),
      "die Ueberschrift steht auch ueber Vorgaengen ohne Ausfallzeit: $heading")
    assertFalse(heading.contains("workload"),
      "die Ueberschrift steht auch ueber Vorgaengen ohne vollen Tag: $heading")
    val deutsch = ForkI18n.textOrNull("fork.levelling.nodate", Locale.GERMANY, 2)!!
    assertFalse(deutsch.contains("Markierungen") || deutsch.contains("Auslastung"),
      "dasselbe gilt fuer den deutschen Text: $deutsch")
  }

  /**
   * THE TWO REASONS ALWAYS COME IN THE SAME ORDER: absence first, capacity under it.
   *
   * The lists inside the conflict are sorted so that the same plan always yields the same message;
   * that is worth nothing if the two LINES can swap. Two reports of the same plan that read
   * differently invite the reading that something changed between them.
   *
   * IT COULD NOT BE RED AGAINST 08a3244b8 — with a block per reason the order was the order of the
   * blocks. It was made to fail on purpose; the output is in the stage report.
   */
  @Test
  fun `the reasons always come in the same order`() {
    val text = noPossibleDateText(
      listOf(beides("12", listOf("3"), listOf("7"))), taskName, personName)
    val zeilen = text.lines().filter { it.trimStart().startsWith("-") }

    assertEquals(2, zeilen.size, "zwei Gruende, zwei Zeilen:\n$text")
    assertTrue(zeilen[0].contains("Petra"), "die Abwesenheit gehoert nach oben: $zeilen")
    assertTrue(zeilen[1].contains("Quirin"), "die Kapazitaet gehoert darunter: $zeilen")
  }

  /**
   * A Task with ONE reason is given ONE reason line, and not an empty second one.
   *
   * The counter-check to the test above: an implementation that appended both lines unconditionally
   * would pass everything above this and produce "the working days of  are already taken" under
   * every intersection.
   *
   * IT COULD NOT BE RED AGAINST 08a3244b8 — with a kind per reason there was no way to say the
   * wrong reason. It was made to fail on purpose instead; the output is in the stage report.
   */
  @Test
  fun `a task with one reason gets one reason line`() {
    val nurBlockiert = noPossibleDateText(listOf(blockiert("12", "3")), taskName, personName)
    val nurVoll = noPossibleDateText(listOf(ohnePlatz("12", "3")), taskName, personName)

    assertEquals(1, nurBlockiert.lines().count { it.trimStart().startsWith("-") },
      "eine Zeile fuer den einen Grund: $nurBlockiert")
    assertEquals(1, nurVoll.lines().count { it.trimStart().startsWith("-") },
      "eine Zeile fuer den einen Grund: $nurVoll")
    // Rendered through `forkText` like the builder itself, so the check does not depend on the
    // JVM's default locale. With an empty name it is exactly the sentence with a hole in it that
    // an unconditional second line produces: "the working days of  are already taken".
    assertFalse(nurBlockiert.contains(forkText("fork.levelling.nodate.full", "")),
      "ohne vollen Tag darf die Kapazitaetszeile nicht stehen: $nurBlockiert")
  }

  /**
   * THE TWO REASONS STAY TWO SENTENCES, and neither has been replaced by the other.
   *
   * This is what became of P6's `the two messages are not the same text`. That one guarded a thing
   * the stage was told not to do — fold two blocks into one shared builder. P7 was told to do
   * exactly that, so the guard moves inwards: the reasons now share a builder and an entry, and
   * what must not collapse is what they SAY. Two remedies that drifted into one sentence would
   * lose the reader the choice between them, which is the whole gain of the merge.
   */
  @Test
  fun `the two reasons are not the same text`() {
    val blocked = ForkI18n.textOrNull("fork.levelling.nodate.blocked", Locale.GERMANY, "Petra")
    val full = ForkI18n.textOrNull("fork.levelling.nodate.full", Locale.GERMANY, "Petra")

    assertTrue(blocked!!.isNotBlank() && full!!.isNotBlank(), "beide Gruende muessen etwas sagen")
    assertFalse(blocked == full, "die zwei Gruende duerfen nicht derselbe Text sein")
  }

  /**
   * Nothing to report means no text at all — not an empty heading, not a bullet with nothing
   * behind it. The caller appends two blank lines in front of this block, and an empty block would
   * leave them standing in the dialog.
   */
  @Test
  fun `nothing to report yields no text`() {
    assertEquals("", noPossibleDateText(emptyList(), taskName, personName))
  }

  /**
   * THE POOL OF THE UNASSIGNED TASKS IS NAMED, not left as a blank.
   *
   * [SHARED_POOL] is the empty string, and it is a perfectly ordinary entry in
   * [LevelConflict.NoPossibleDate.fullFor] — Tasks nobody is assigned to occupy time in it just
   * like everybody else. Sent through the person lookup it would come back as an empty string or
   * as a resource id of -1, and the row would read "…: the working days of  are already taken".
   * That is a sentence with a hole in it, and a hole reads as a bug rather than as an answer.
   *
   * IT COULD NOT BE RED AGAINST THE STATE BEFORE P6 — before it, there was no message to put a
   * hole in — and it was green against 08a3244b8, where P6's builder already had the special case.
   * It has been seen to fail all the same: with the special case taken back out it failed, in P6,
   * with
   *
   *   der gemeinsame Topf darf nicht durch die Personensuche laufen: … • Abnahme Pruefstand: die
   *   Arbeitstage von PERSON: sind an jedem in Frage kommenden Termin schon belegt
   *   ==> expected: <false> but was: <true>
   *
   * and it was broken again the same way after the merge; that output is in the P7 report.
   */
  @Test
  fun `the shared pool is named rather than left blank`() {
    val text = noPossibleDateText(listOf(ohnePlatz("12", SHARED_POOL)), taskName, personName)

    assertFalse(text.contains("PERSON:"),
      "der gemeinsame Topf darf nicht durch die Personensuche laufen: $text")
    assertTrue(text.contains(forkText("fork.levelling.nodate.shared")),
      "der gemeinsame Topf braucht einen eigenen Namen: $text")
  }

  /**
   * An id with no name behind it still produces a usable line.
   *
   * This is not decoration: the Task may have been deleted between the calculation and the dialog,
   * and a person may have. A report that answers such a case with "null" or with a blank says less
   * than the bare id does. Both lists go through the same fallback, so both are checked.
   */
  @Test
  fun `an unknown id falls back to the id instead of a blank`() {
    val text = noPossibleDateText(
      listOf(beides("999", listOf("888"), listOf("777"))), { it }, { it })

    assertTrue(text.contains("999"), "der unbekannte Vorgang muss wenigstens als Nummer stehen: $text")
    assertTrue(text.contains("888"), "die unbekannte blockierende Person fehlt: $text")
    assertTrue(text.contains("777"), "die unbekannte volle Person fehlt: $text")
  }

  /**
   * A long list is cut off after five, exactly as the neighbouring blocks of the preview are, and
   * the cut is SAID. A dialog that silently shows five of two hundred is the same kind of quiet
   * this whole line of stages is about.
   */
  @Test
  fun `more than five are cut off and the cut is mentioned`() {
    val viele = (1..7).map { blockiert(it.toString(), "3") }

    val text = noPossibleDateText(viele, { it }, personName)

    assertEquals(5, text.lines().count { it.trimStart().startsWith("•") },
      "es duerfen hoechstens fuenf Zeilen stehen: $text")
    assertTrue(text.contains(forkText("fork.levelling.more", 2)),
      "die zwei uebrigen muessen erwaehnt werden: $text")
  }

  /**
   * THE CUT COUNTS TASKS, NOT LINES. Five Tasks with two reasons each are five entries and ten
   * reason lines, and nothing is dropped in the middle of an entry.
   *
   * Worth its own assertion because the obvious way to write the cut — on the string's lines — is
   * green for every one-reason case above and would name a Task and then stop before saying why.
   *
   * IT COULD NOT BE RED AGAINST 08a3244b8: with one reason per kind there were never two lines per
   * entry to miscount. It was made to fail on purpose; the output is in the stage report.
   */
  @Test
  fun `the cut counts tasks and not reason lines`() {
    val viele = (1..5).map { beides(it.toString(), listOf("3"), listOf("7")) }

    val text = noPossibleDateText(viele, { it }, personName)

    assertEquals(5, text.lines().count { it.trimStart().startsWith("•") },
      "fuenf Vorgaenge muessen alle fuenf genannt werden: $text")
    assertEquals(10, text.lines().count { it.trimStart().startsWith("-") },
      "und jeder von ihnen mit beiden Gruenden: $text")
    assertFalse(text.contains("…"),
      "bei genau fuenf Vorgaengen darf kein Abschnitt gemeldet werden: $text")
  }

  /**
   * The five keys are really in both bundles, in the language they belong to.
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
   *
   * IN ITS RED RUN AGAINST 08a3244b8 this test failed with
   *
   *   org.opentest4j.AssertionFailedError: fork.levelling.nodate fehlt im deutschen Buendel
   *   ==> expected: <true> but was: <false>
   */
  @Test
  fun `both bundles carry the five keys`() {
    listOf("fork.levelling.nodate", "fork.levelling.nodate.task",
      "fork.levelling.nodate.blocked", "fork.levelling.nodate.full",
      "fork.levelling.nodate.shared").forEach { key ->
      assertTrue(ForkI18n.textOrNull(key, Locale.GERMANY, 1, "x")?.isNotBlank() == true,
        "$key fehlt im deutschen Buendel")
      assertTrue(ForkI18n.textOrNull(key, Locale.US, 1, "x")?.isNotBlank() == true,
        "$key fehlt im englischen Buendel")
    }
    assertTrue(ForkI18n.textOrNull("fork.levelling.nodate", Locale.GERMANY, 1)!!
      .contains("Vorgang"), "der deutsche Text ist nicht deutsch")
    assertTrue(ForkI18n.textOrNull("fork.levelling.nodate", Locale.US, 1)!!
      .contains("task"), "der englische Text ist nicht englisch")
  }

  /**
   * THE KEYS OF THE TWO OLD MESSAGES ARE GONE FROM BOTH BUNDLES.
   *
   * A merge that leaves the old keys lying about leaves two texts to drift apart from the one that
   * is shown, and the next reader has no way of telling which is live. `ForkI18nTest` checks that
   * every key has a translation, not that every key is used, so nothing else would notice.
   *
   * IT COULD NOT BE RED AGAINST 08a3244b8 — there the keys were supposed to be there. It was made
   * to fail on purpose by putting one back; the output is in the stage report.
   */
  @Test
  fun `the two old message keys are gone`() {
    listOf("fork.levelling.blocked", "fork.levelling.blocked.row", "fork.levelling.nocapacity",
      "fork.levelling.nocapacity.row", "fork.levelling.nocapacity.shared").forEach { key ->
      assertTrue(ForkI18n.textOrNull(key, Locale.GERMANY, 1, "x") == null,
        "$key steht noch im deutschen Buendel, obwohl niemand ihn mehr liest")
      assertTrue(ForkI18n.textOrNull(key, Locale.US, 1, "x") == null,
        "$key steht noch im englischen Buendel, obwohl niemand ihn mehr liest")
    }
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
    val einer = ForkI18n.textOrNull("fork.levelling.nodate", Locale.GERMANY, 1)
    val mehrere = ForkI18n.textOrNull("fork.levelling.nodate", Locale.GERMANY, 3)

    assertTrue(einer!!.startsWith("Ein Vorgang"), "Einzahl: $einer")
    assertTrue(mehrere!!.startsWith("3 Vorgaenge"), "Mehrzahl: $mehrere")
  }
}
