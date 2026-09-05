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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.util.Locale

/**
 * B3 IN THE WINDOW SEARCH — the calculation on its own, with no model behind it.
 *
 * `ResourceLevelling.kt` deliberately knows no GanttProject types, so the rule can be measured
 * without a running program. What is measured here is the calculation's half of the rule:
 *
 *  * an unmarked task is laid exactly as it was laid before, and TWICE OVER — once because
 *    [LevelTask.requiresPresence] defaults to false, and once because the `isAtWorkplace` channel
 *    defaults to „everybody is at their workplace". Either default alone is enough, which is why
 *    both are asked separately below;
 *  * a marked task does not lie on a day on which somebody it cannot proceed without is at home;
 *  * a task that can never lie anywhere GIVES UP rather than searching for ever, and says why;
 *  * and the reason it gives is „at home" and not „away" — decision E2 of 04.09.2026.
 *
 * WHY E2 MATTERS AND IS NOT A MATTER OF WORDING. „These people were away" put in front of somebody
 * sitting at their desk at home names the wrong remedy: nobody's days off have to change, the
 * marking of the TASK does. A message that sends a person to the wrong dialog is worse than a
 * shorter one.
 *
 * 7 September 2026 is a Monday.
 */
class HomeWorkLevellingTest {

  private val montag = LocalDate.of(2026, 9, 7)
  private val mittwoch = montag.plusDays(2)

  private val werktags: (LevelTask, LocalDate) -> Boolean = { _, tag ->
    tag.dayOfWeek != DayOfWeek.SATURDAY && tag.dayOfWeek != DayOfWeek.SUNDAY
  }

  private fun vorgang(id: String, tage: Int) =
    LevelTask(id = id, orderInPlan = 0, priority = 0, durationDays = tage,
      loads = mapOf("p" to 100), blocking = setOf("p"))

  /** P is at their workplace every day except the one named. */
  private fun zuHauseAm(tag: LocalDate): (String, LocalDate) -> Boolean = { _, d -> d != tag }

  private val immerZuHause: (String, LocalDate) -> Boolean = { _, _ -> false }
  private val immerDa: (String, LocalDate) -> Boolean = { _, _ -> true }

  // ===============================================================================================
  // The two independent reasons an unmarked plan does not move.
  // ===============================================================================================

  /**
   * REASON ONE: [LevelTask.requiresPresence] defaults to false. Even with the channel wired up and
   * answering „at home, every day, everybody", a task nobody marked does not move a single day.
   *
   * This is the short circuit in the search written as a requirement: `requiresPresence && …` never
   * evaluates its right-hand side for a task in the state every task of every existing plan is in.
   */
  @Test
  fun `ohne markierung bewegt auch dauerhafte heimarbeit nichts`() {
    val ergebnis = levelTasks(listOf(vorgang("a", 3)), montag, werktags,
      isAtWorkplace = immerZuHause)

    assertEquals(montag, ergebnis.starts["a"],
      "der Vorgang traegt keine Markierung -- die Heimarbeit darf ihn nicht einen Tag bewegen")
    assertTrue(ergebnis.conflicts.isEmpty(),
      "und sie darf auch nichts melden, gemeldet wurde: ${ergebnis.conflicts}")
  }

  /**
   * REASON TWO, INDEPENDENT OF THE FIRST: the channel's own default. A marked task in a run where
   * nobody wired `isAtWorkplace` up is laid exactly as it was — the unfilled parameter means the
   * state of things as they were, not „send everybody home".
   *
   * The direction is chosen and not accidental; it is the same choice `isAvailable` beside it
   * carries, and it is what keeps a forgotten wiring a silent nothing rather than a silent
   * catastrophe. (That it is silent is the reason `HomeWorkWiringTest` exists.)
   */
  @Test
  fun `ein unverdrahteter kanal schickt niemanden nach hause`() {
    val markiert = vorgang("a", 3).copy(requiresPresence = true)
    val ergebnis = levelTasks(listOf(markiert), montag, werktags)

    assertEquals(montag, ergebnis.starts["a"],
      "ohne verdrahteten Kanal muss der markierte Vorgang liegen, wo er immer lag")
    assertTrue(ergebnis.conflicts.isEmpty(),
      "und ohne Meldung, gemeldet wurde: ${ergebnis.conflicts}")
  }

  // ===============================================================================================
  // The rule itself.
  // ===============================================================================================

  /**
   * THE ONE LINE, AS A REQUIREMENT: a marked task does not lie on a window containing the
   * home-office day of somebody it cannot proceed without — and the unmarked comparison case beside
   * it proves the difference is the MARKING and not the day.
   *
   * Three working days from Monday would cover Monday, Tuesday and Wednesday. P is at home on
   * Wednesday, so the search moves on and the earliest window that works starts on Wednesday+1.
   */
  @Test
  fun `mit markierung ist der heimarbeitstag der zwingenden person kein tag fuer den vorgang`() {
    val ohne = levelTasks(listOf(vorgang("a", 3)), montag, werktags,
      isAtWorkplace = zuHauseAm(mittwoch))
    assertEquals(montag, ohne.starts["a"],
      "Vergleichsfall: ohne Markierung aendert die Heimarbeit nichts")

    val mit = levelTasks(listOf(vorgang("a", 3).copy(requiresPresence = true)), montag, werktags,
      isAtWorkplace = zuHauseAm(mittwoch))
    assertEquals(mittwoch.plusDays(1), mit.starts["a"],
      "mit Markierung liegen die drei Tage hinter dem Heimarbeitstag")
  }

  /**
   * THE HOME OFFICE IS ASKED OF THE BLOCKING SET AND OF NOBODY ELSE.
   *
   * P is indispensable and at their workplace; Q is on the task, is at home every day, and is not
   * marked as indispensable. Natalie: „wenn sie es nicht ist, kann der Rest ohne sie
   * weiterarbeiten." The window search therefore has nothing to say about Q at all — what Q's home
   * office does to the task is a matter of DURATION and is measured in `HomeWorkDurationTest`.
   */
  @Test
  fun `die fenstersuche fragt nur die zwingenden personen`() {
    val mitZweiten = LevelTask(id = "a", orderInPlan = 0, priority = 0, durationDays = 3,
      loads = mapOf("p" to 100, "q" to 100), blocking = setOf("p"), requiresPresence = true)
    val nurQZuHause: (String, LocalDate) -> Boolean = { person, _ -> person != "q" }

    val ergebnis = levelTasks(listOf(mitZweiten), montag, werktags, isAtWorkplace = nurQZuHause)

    assertEquals(montag, ergebnis.starts["a"],
      "Q ist nicht als zwingend notwendig eingetragen -- ihre Heimarbeit verschiebt den Vorgang " +
        "nicht")
    // THE SECOND HALF, AND IT IS NOT DECORATION. Measured on 05.09.2026: with only the date
    // asserted, this check stayed GREEN under the break that asks `task.pools` instead of
    // `task.blocking`. Under that break NO day works, the search runs into its bound, and the
    // fallback lays the task at its EARLIEST possible date -- which is the very Monday the line
    // above demands. The right answer and the give-up answer are the same date here, and only the
    // conflict list tells them apart.
    assertTrue(ergebnis.conflicts.isEmpty(),
      "und der Vorgang liegt am Montag, WEIL er dort passt und nicht, weil die Suche aufgegeben " +
        "hat -- gemeldet wurde: ${ergebnis.conflicts}")
  }

  // ===============================================================================================
  // W12 — giving up instead of running for ever, and saying why.
  // ===============================================================================================

  /**
   * W12: A TASK THAT CAN NEVER LIE ANYWHERE COMES BACK, and it comes back with a message that names
   * the right thing to change.
   *
   * Axis A already opened one way of never finding a window — several indispensable people whose
   * available times never overlap. Home working opens a second: one indispensable person who is
   * always at home, on a task that needs somebody present. The bound in the search is what catches
   * both; without it the menu item would simply seem to do nothing, which is the most expensive
   * failure this file knows and has been seen on this machine twice.
   */
  @Test
  fun `unbegrenzte heimarbeit gibt auf statt endlos zu laufen`() {
    val markiert = vorgang("a", 2).copy(requiresPresence = true)

    val ergebnis = assertTimeoutPreemptively(Duration.ofSeconds(30), ThrowingSupplier {
      levelTasks(listOf(markiert), montag, werktags, isAtWorkplace = immerZuHause)
    })

    assertNotNull(ergebnis.starts["a"],
      "die Verteilung muss zurueckkommen, statt zu haengen")
    assertEquals(montag, ergebnis.starts["a"],
      "ohne moeglichen Termin bleibt der fruehestmoegliche stehen -- ein sichtbar falsches Datum " +
        "ist der billigste der moeglichen Fehlschlaege")

    val gemeldet = ergebnis.conflicts.filterIsInstance<LevelConflict.NoPossibleDate>()
    assertEquals(1, gemeldet.size,
      "genau eine Meldung fuer den einen unmoeglichen Vorgang, gemeldet wurde: ${ergebnis.conflicts}")
    assertEquals(listOf("p"), gemeldet[0].blocking,
      "die Meldung muss die Person nennen, um die es geht")
    assertEquals(setOf(AbsenceKind.AT_HOME), gemeldet[0].blockingReasons,
      "und sie muss sagen, dass es um HEIMARBEIT ging und nicht um Abwesenheit -- P war an " +
        "keinem Tag abwesend")
    assertTrue(gemeldet[0].fullFor.isEmpty(),
      "hier war kein Tag voll, der Kapazitaetsgrund darf nicht mitgenannt werden: ${gemeldet[0]}")
  }

  /**
   * BOTH REASONS AT ONCE, on different days, in one search — and the message carries both.
   *
   * An `else` between the two conditions, or a single-valued reason, would drop one of them, and
   * the reader would be sent to fix half of a plan. P is away on even epoch days and at home on the
   * others: there is no day at all, and both reasons are true of the search.
   */
  @Test
  fun `abwesenheit und heimarbeit an verschiedenen tagen werden beide gemeldet`() {
    val abwesendGerade: (String, LocalDate) -> Boolean = { _, tag -> tag.toEpochDay() % 2 != 0L }
    val zuHauseUngerade: (String, LocalDate) -> Boolean = { _, tag -> tag.toEpochDay() % 2 == 0L }
    val markiert = vorgang("a", 2).copy(requiresPresence = true)

    val ergebnis = assertTimeoutPreemptively(Duration.ofSeconds(30), ThrowingSupplier {
      levelTasks(listOf(markiert), montag, werktags,
        isAvailable = abwesendGerade, isAtWorkplace = zuHauseUngerade)
    })

    val gemeldet = ergebnis.conflicts.filterIsInstance<LevelConflict.NoPossibleDate>()
    assertEquals(1, gemeldet.size, "eine Meldung, zwei Gruende: ${ergebnis.conflicts}")
    assertEquals(setOf(AbsenceKind.AWAY, AbsenceKind.AT_HOME), gemeldet[0].blockingReasons,
      "beide Gruende sind eingetreten, und beide muessen genannt werden -- wer nur einen behebt, " +
        "hat den Plan nicht gerettet")
  }

  /**
   * THE OLD REASON STILL ARRIVES ALONE when only it applies. An absence-only dead end must not
   * start talking about home offices; that is the same lie in the other direction.
   */
  @Test
  fun `ein reiner abwesenheitsfall meldet nur abwesenheit`() {
    val abwechselnd: (String, LocalDate) -> Boolean = { person, tag ->
      if (person == "p") tag.toEpochDay() % 2 == 0L else tag.toEpochDay() % 2 != 0L
    }
    val zwei = LevelTask(id = "a", orderInPlan = 0, priority = 0, durationDays = 2,
      loads = mapOf("p" to 100, "q" to 100), blocking = setOf("p", "q"), requiresPresence = true)

    val ergebnis = assertTimeoutPreemptively(Duration.ofSeconds(30), ThrowingSupplier {
      levelTasks(listOf(zwei), montag, werktags,
        isAvailable = abwechselnd, isAtWorkplace = immerDa)
    })

    val gemeldet = ergebnis.conflicts.filterIsInstance<LevelConflict.NoPossibleDate>()
    assertEquals(1, gemeldet.size, "eine Meldung: ${ergebnis.conflicts}")
    assertEquals(setOf(AbsenceKind.AWAY), gemeldet[0].blockingReasons,
      "hier war niemand zu Hause -- der Heimarbeitsgrund darf nicht mitgenannt werden")
  }

  // ===============================================================================================
  // E2 — the message the person actually reads.
  // ===============================================================================================

  /**
   * THE TWO REASONS PRODUCE TWO DIFFERENT SENTENCES, and the home-work one does not say „away".
   *
   * Measured on the rendered text rather than on the conflict object, because the conflict is not
   * what anybody reads. What has to be true of the text: the home-work case names the marking of
   * the task as the thing to change, and the absence case names the days off — and neither borrows
   * the other's remedy.
   */
  @Test
  fun `die meldung unterscheidet heimarbeit von abwesenheit`() {
    val namen: (String) -> String = { if (it == "a") "Abnahme" else "Natalie" }
    fun text(vararg gruende: AbsenceKind) = noPossibleDateText(
      listOf(LevelConflict.NoPossibleDate("a", listOf("p"), emptyList(), gruende.toSet())),
      namen, namen)

    // AGAINST THE BUNDLE AND NOT AGAINST GERMAN WORDS: which language this runs in is the JVM's
    // business, and a check that spelled the sentence out would be measuring the locale.
    val heimSatz = forkText("fork.levelling.nodate.homework", "Natalie")
    val abwesendSatz = forkText("fork.levelling.nodate.blocked", "Natalie")

    val heim = text(AbsenceKind.AT_HOME)
    assertTrue(heim.contains("Abnahme"), "der Vorgang fehlt in der Meldung:\n$heim")
    assertTrue(heim.contains(heimSatz), "der Heimarbeitssatz fehlt:\n$heim")
    assertTrue(!heim.contains(abwesendSatz),
      "und der Abwesenheitssatz darf nicht dabeistehen -- niemand war abwesend:\n$heim")

    val abwesend = text(AbsenceKind.AWAY)
    assertTrue(abwesend.contains(abwesendSatz),
      "Gegenprobe: der Abwesenheitsfall behaelt seinen eigenen Satz:\n$abwesend")
    assertTrue(!abwesend.contains(heimSatz),
      "und er darf nicht von Heimarbeit sprechen:\n$abwesend")

    val beides = text(AbsenceKind.AWAY, AbsenceKind.AT_HOME)
    assertTrue(beides.contains(abwesendSatz) && beides.contains(heimSatz),
      "wo beide Gruende gelten, muessen beide Saetze dastehen -- ein 'else' zwischen ihnen baut " +
        "das Schweigen wieder auf, das die Zusammenfuehrung beendet hat:\n$beides")

    // A conflict from before B3 -- three arguments, no reason -- still gets the older sentence.
    // The alternative would be a report that names people and says nothing about them.
    val ohneGrund = noPossibleDateText(
      listOf(LevelConflict.NoPossibleDate("a", listOf("p"), emptyList())), namen, namen)
    assertTrue(ohneGrund.contains(abwesendSatz),
      "ein Konflikt ohne Merkmal behaelt den Satz, den seine Namen frueher bedeuteten:\n$ohneGrund")
  }

  /**
   * AND THE REMEDIES ARE DIFFERENT IN BOTH LANGUAGES, which is the whole reason for a second key.
   * The absence sentence sends somebody to the days off; the home-work one to the marking of the
   * task. A translation that lost that difference would be green everywhere else.
   */
  @Test
  fun `beide sprachen nennen bei heimarbeit die markierung und nicht die ausfallzeiten`() {
    val de = ForkI18n.textOrNull("fork.levelling.nodate.homework", Locale.GERMANY, "Natalie")!!
    assertTrue(de.contains("Markierung"), "der deutsche Satz muss die Markierung nennen: $de")
    assertTrue(!de.contains("Ausfallzeiten"),
      "und er darf nicht auf Ausfallzeiten verweisen: $de")

    val en = ForkI18n.textOrNull("fork.levelling.nodate.homework", Locale.US, "Natalie")!!
    assertTrue(en.contains("marking"), "der englische Satz muss die Markierung nennen: $en")
    assertTrue(!en.contains("days off"),
      "und er darf nicht auf Ausfallzeiten verweisen: $en")
  }
}
