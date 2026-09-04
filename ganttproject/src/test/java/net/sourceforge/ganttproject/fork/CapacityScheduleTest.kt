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
*/
package net.sourceforge.ganttproject.fork

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.ceil

class CapacityScheduleTest {

  private val montagBisFreitag: (LocalDate) -> Boolean = {
    it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY
  }

  /**
   * The same week as ONE grid for every task -- [levelTasks] asks its working-day test per task
   * since 04.09.2026. Nothing in this file is about differing grids: these checks are about the
   * daily rate, the ordering and the capacity, and one Monday-to-Friday week is the whole calendar
   * they need. [oneGridForAllTasks] states that at the hand-over instead of leaving it implied.
   */
  private val einRaster: (LevelTask, LocalDate) -> Boolean = oneGridForAllTasks(montagBisFreitag)

  private fun plan(text: String, base: Double = 4.0) = CapacitySchedule.parse(text, base)

  // ---- Parsing --------------------------------------------------------------------------

  @Test
  fun `ohne eintrag gilt die normale tagesleistung`() {
    val ergebnis = plan("")
    assertTrue(ergebnis.schedule.isConstant)
    assertEquals(4.0, ergebnis.schedule.hoursOn(LocalDate.of(2030, 1, 1)))
    assertTrue(ergebnis.errors.isEmpty())
  }

  @Test
  fun `ab dem datum gilt die neue zahl, davor die alte`() {
    val p = plan("2027-04-01: 8").schedule
    assertEquals(4.0, p.hoursOn(LocalDate.of(2027, 3, 31)), "der Tag davor")
    assertEquals(8.0, p.hoursOn(LocalDate.of(2027, 4, 1)), "der Tag selbst gehoert schon dazu")
    assertEquals(8.0, p.hoursOn(LocalDate.of(2030, 1, 1)), "und alles danach")
  }

  @Test
  fun `mehrere abschnitte, auch unsortiert eingegeben`() {
    val p = plan("2027-10-01: 8; 2027-04-01: 6").schedule
    assertEquals(4.0, p.hoursOn(LocalDate.of(2027, 1, 1)))
    assertEquals(6.0, p.hoursOn(LocalDate.of(2027, 5, 1)))
    assertEquals(8.0, p.hoursOn(LocalDate.of(2027, 11, 1)))
  }

  @Test
  fun `zeilenumbruch und gleichheitszeichen und komma sind erlaubt`() {
    val p = plan("2027-04-01 = 6,5\n2027-10-01: 8").schedule
    assertEquals(6.5, p.hoursOn(LocalDate.of(2027, 5, 1)))
    assertEquals(8.0, p.hoursOn(LocalDate.of(2027, 11, 1)))
  }

  @Test
  fun `bei doppeltem datum gewinnt der spaetere eintrag`() {
    val p = plan("2027-04-01: 6; 2027-04-01: 8").schedule
    assertEquals(8.0, p.hoursOn(LocalDate.of(2027, 4, 1)))
    assertEquals(1, p.changes.size)
  }

  // ---- Errors, and that they are NOT swallowed -------------------------------------------

  @Test
  fun `ein tippfehler wird gemeldet, nicht ignoriert`() {
    val ergebnis = plan("2027-04-01: acht")
    assertEquals(1, ergebnis.errors.size)
    assertTrue(ergebnis.hasErrors)
    // The usable remainder still stands: the display should be able to show something.
    assertEquals(4.0, ergebnis.schedule.hoursOn(LocalDate.of(2027, 5, 1)))
  }

  @Test
  fun `deutsches datum wird abgelehnt, weil es mehrdeutig ist`() {
    assertEquals(1, plan("01.04.2027: 8").errors.size)
  }

  @Test
  fun `fehlendes trennzeichen, negative und unmoegliche stundenzahl`() {
    assertEquals(1, plan("2027-04-01 8").errors.size)
    assertEquals(1, plan("2027-04-01: -2").errors.size)
    assertEquals(1, plan("2027-04-01: 30").errors.size)
    // Counter-check: exactly at the limit it is still valid.
    assertTrue(plan("2027-04-01: 24").errors.isEmpty())
  }

  @Test
  fun `gute und schlechte eintraege zusammen ergeben beides`() {
    val ergebnis = plan("2027-04-01: 6; kaputt; 2027-10-01: 8")
    assertEquals(1, ergebnis.errors.size)
    assertEquals(2, ergebnis.schedule.changes.size)
  }

  // ---- Computing ------------------------------------------------------------------------

  @Test
  fun `ohne abschnitte rechnet es genau wie bisher`() {
    // The safety property: the new path must change nothing about the old behaviour.
    val montag = LocalDate.of(2026, 8, 17)
    listOf(1.0, 4.0, 7.5, 8.0, 12.0).forEach { stunden ->
      val p = CapacitySchedule(stunden)
      (1..40).forEach { aufwand ->
        assertEquals(
          ceil(aufwand / stunden).toInt().coerceAtLeast(1),
          daysNeeded(aufwand.toDouble(), montag, p, isWorkingDay = montagBisFreitag),
          "$aufwand Std. bei $stunden Std./Tag")
      }
    }
  }

  @Test
  fun `ein vorgang ueber die grenze wird anteilig gerechnet`() {
    // From Wednesday on eight hours instead of four. 20 h of effort from Monday:
    // Mon 4, Tue 4 (8 used up), Wed 8 (16), Thu 8 -> finished on the fourth day.
    val montag = LocalDate.of(2026, 8, 17)
    val p = plan("2026-08-19: 8").schedule
    assertEquals(4, daysNeeded(20.0, montag, p, isWorkingDay = montagBisFreitag))
    // Counter-check: four hours throughout would be five days, eight throughout would be three.
    assertEquals(5, daysNeeded(20.0, montag, CapacitySchedule(4.0), isWorkingDay = montagBisFreitag))
    assertEquals(3, daysNeeded(20.0, montag, CapacitySchedule(8.0), isWorkingDay = montagBisFreitag))
  }

  @Test
  fun `wochenenden zaehlen nicht mit`() {
    // Friday, 4 h/day, 8 h of effort: Friday and Monday -- two working days.
    val freitag = LocalDate.of(2026, 8, 21)
    assertEquals(2, daysNeeded(8.0, freitag, CapacitySchedule(4.0), isWorkingDay = montagBisFreitag))
  }

  @Test
  fun `ein abschnitt mit null stunden endet nie und wird gemeldet`() {
    val p = plan("2026-08-18: 0").schedule
    // On Monday the effort is not yet enough, from Tuesday on nothing proceeds any further.
    assertNull(daysNeeded(100.0, LocalDate.of(2026, 8, 17), p, isWorkingDay = montagBisFreitag),
      "statt endlos zu laufen, muss die Rechnung aufgeben und den Aufrufer melden lassen")
    // Counter-check: what still fits before the zero is very much computed.
    assertEquals(1, daysNeeded(4.0, LocalDate.of(2026, 8, 17), p, isWorkingDay = montagBisFreitag))
  }

  @Test
  fun `eine halbe zuordnung halbiert die verfuegbaren stunden`() {
    val montag = LocalDate.of(2026, 8, 17)
    assertEquals(4, daysNeeded(16.0, montag, CapacitySchedule(8.0), loadFactor = 0.5,
      isWorkingDay = montagBisFreitag))
  }

  @Test
  fun `der text laesst sich wieder lesen`() {
    val p = plan("2027-04-01: 6; 2027-10-01: 8").schedule
    val nochmal = CapacitySchedule.parse(p.toString(), 4.0)
    assertTrue(nochmal.errors.isEmpty(), "der eigene Text muss lesbar sein: ${p}")
    assertEquals(p.changes, nochmal.schedule.changes)
  }

  // ---- Interplay with levelling -----------------------------------------------------------

  @Test
  fun `die verteilung rechnet die dauer am gelegten termin, nicht am alten`() {
    val montag = LocalDate.of(2026, 8, 17)
    // From 1.9. on there are eight hours instead of four.
    val plan = plan("2026-09-01: 8").schedule
    val aufwand = mapOf("a" to 40.0, "b" to 40.0)
    val durationAt = { task: LevelTask, start: LocalDate ->
      daysNeeded(aufwand.getValue(task.id), start, plan, isWorkingDay = montagBisFreitag) ?: 1
    }
    // Two Tasks, both 40 h, both fully loaded: they cannot run at the same time.
    val tasks = listOf(
      LevelTask(id = "a", orderInPlan = 0, priority = 2, durationDays = 10,
        loads = mapOf(SHARED_POOL to 100)),
      LevelTask(id = "b", orderInPlan = 1, priority = 2, durationDays = 10,
        loads = mapOf(SHARED_POOL to 100)))

    val ergebnis = levelTasks(tasks, montag, einRaster, durationAt)

    // The first begins on 17.8. -- four hours a day, until 1.9. After that eight.
    // Mon 17.8. to Fri 28.8. is 10 working days at 4 h = 40 h: exactly ten days.
    assertEquals(montag, ergebnis.starts["a"])
    assertEquals(10, ergebnis.durations["a"], "vor der Grenze: vier Stunden am Tag")
    // The second starts on Monday 31.8. -- and that is ONE DAY BEFORE the boundary. So:
    // Mon 31.8. 4 h, then from Tue 1.9. eight: 4+8+8+8+8+8 = 44 >= 40 on the sixth day.
    //
    // THE EXPECTATION HERE WAS 5 AT FIRST, and it was wrong: 31.8. had already been counted as
    // part of the eight-hour section. The number deliberately stands next to this derivation --
    // a number without a derivation invites simply adjusting it at the next failure.
    assertEquals(6, ergebnis.durations["b"],
      "vier Stunden am ersten Tag, danach acht")
    // Counter-check: if the second Task lay wholly in the eight-hour section, it would be five days.
    assertEquals(5, daysNeeded(40.0, LocalDate.of(2026, 9, 1), plan, isWorkingDay = montagBisFreitag))
    assertTrue(ergebnis.conflicts.isEmpty(), "niemand ist ueberlastet: ${ergebnis.conflicts}")
  }

  @Test
  fun `ohne stundenplan verhaelt sich die verteilung wie vorher`() {
    // Counter-check to the previous one: the same setup, but without sections. The duration then
    // has to be exactly the one from the LevelTask -- the new path must change nothing about the
    // old behaviour.
    val montag = LocalDate.of(2026, 8, 17)
    val tasks = listOf(
      LevelTask(id = "a", orderInPlan = 0, priority = 2, durationDays = 10,
        loads = mapOf(SHARED_POOL to 100)),
      LevelTask(id = "b", orderInPlan = 1, priority = 2, durationDays = 10,
        loads = mapOf(SHARED_POOL to 100)))
    val ergebnis = levelTasks(tasks, montag, einRaster)
    assertEquals(10, ergebnis.durations["a"])
    assertEquals(10, ergebnis.durations["b"])
  }
}

/**
 * Levelling with SEVERAL people.
 *
 * WHAT FOR: until 17.08.2026 levelling had a single capacity pool. Two people could not have
 * worked at the same time with it -- levelling would have laid their Tasks one after another and
 * delivered a result that looks plausible and lengthens the plan by months. That only one person
 * plans in trial use must not be the reason why it is right.
 */
class MehrerePersonenTest {
  private val montagBisFreitag: (LocalDate) -> Boolean = {
    it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY
  }

  /**
   * The same week as ONE grid for every task -- [levelTasks] asks its working-day test per task
   * since 04.09.2026. Nothing in this file is about differing grids: these checks are about the
   * daily rate, the ordering and the capacity, and one Monday-to-Friday week is the whole calendar
   * they need. [oneGridForAllTasks] states that at the hand-over instead of leaving it implied.
   */
  private val einRaster: (LevelTask, LocalDate) -> Boolean = oneGridForAllTasks(montagBisFreitag)
  private val montag = LocalDate.of(2026, 8, 17)

  @Test
  fun `zwei personen arbeiten gleichzeitig`() {
    val tasks = listOf(
      LevelTask("a", 0, 2, durationDays = 5, loads = mapOf("1" to 100)),
      LevelTask("b", 1, 2, durationDays = 5, loads = mapOf("2" to 100)))
    val ergebnis = levelTasks(tasks, montag, einRaster)
    assertEquals(montag, ergebnis.starts["a"])
    assertEquals(montag, ergebnis.starts["b"], "die zweite Person hat ihre eigene Kapazitaet")
    assertTrue(ergebnis.conflicts.isEmpty())
  }

  @Test
  fun `dieselbe person kann es nicht gleichzeitig`() {
    // Counter-check: the same setup, but both Tasks with the same person.
    val tasks = listOf(
      LevelTask("a", 0, 2, durationDays = 5, loads = mapOf("1" to 100)),
      LevelTask("b", 1, 2, durationDays = 5, loads = mapOf("1" to 100)))
    val ergebnis = levelTasks(tasks, montag, einRaster)
    assertEquals(montag, ergebnis.starts["a"])
    assertEquals(LocalDate.of(2026, 8, 24), ergebnis.starts["b"], "erst danach")
  }

  @Test
  fun `ein vorgang mit zwei personen belegt beide`() {
    val tasks = listOf(
      // Both people are FULLY committed to the shared Task -- 100 % each, not 100 % split between
      // them. Under the old field this had to be written as one number plus a list of people,
      // which happened to give the same result here and the wrong one at 50 % each.
      LevelTask("gemeinsam", 0, 2, durationDays = 5, loads = mapOf("1" to 100, "2" to 100)),
      LevelTask("nur1", 1, 2, durationDays = 5, loads = mapOf("1" to 100)),
      LevelTask("nur2", 2, 2, durationDays = 5, loads = mapOf("2" to 100)))
    val ergebnis = levelTasks(tasks, montag, einRaster)
    assertEquals(montag, ergebnis.starts["gemeinsam"])
    // Both pools are occupied, so BOTH following Tasks have to wait.
    assertEquals(LocalDate.of(2026, 8, 24), ergebnis.starts["nur1"])
    assertEquals(LocalDate.of(2026, 8, 24), ergebnis.starts["nur2"])
  }

  @Test
  fun `die ueberlastmeldung nennt die person`() {
    val tasks = listOf(
      LevelTask("a", 0, 2, durationDays = 5, loads = mapOf("7" to 100), fixedStart = montag),
      LevelTask("b", 1, 2, durationDays = 5, loads = mapOf("7" to 100), fixedStart = montag))
    val ergebnis = levelTasks(tasks, montag, einRaster)
    val ueberlast = ergebnis.conflicts.filterIsInstance<LevelConflict.Overload>()
    assertTrue(ueberlast.isNotEmpty(), "zwei feste Termine am selben Tag sprengen die Kapazitaet")
    assertEquals("7", ueberlast.first().resourceId)
  }

  @Test
  fun `ohne zuordnung teilen sich alle einen topf`() {
    // That was the previous behaviour and has to stay: a Task without an assignment occupies
    // time, only one does not know whose. Treating it as free would be the more dangerous
    // assumption.
    val tasks = listOf(
      LevelTask("a", 0, 2, durationDays = 5, loads = mapOf(SHARED_POOL to 100)),
      LevelTask("b", 1, 2, durationDays = 5, loads = mapOf(SHARED_POOL to 100)))
    val ergebnis = levelTasks(tasks, montag, einRaster)
    assertEquals(LocalDate.of(2026, 8, 24), ergebnis.starts["b"])
  }
}

/**
 * The three extensions of 17.08.2026: frozen work, deadlines, utilisation.
 *
 * WHAT FOR: without the first, levelling was a ONE-OFF TOOL -- on the second run it would have
 * re-laid Tasks already ticked off and rewritten the past.
 */
class WiederholtVerteilenTest {
  private val montagBisFreitag: (LocalDate) -> Boolean = {
    it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY
  }

  /**
   * The same week as ONE grid for every task -- [levelTasks] asks its working-day test per task
   * since 04.09.2026. Nothing in this file is about differing grids: these checks are about the
   * daily rate, the ordering and the capacity, and one Monday-to-Friday week is the whole calendar
   * they need. [oneGridForAllTasks] states that at the hand-over instead of leaving it implied.
   */
  private val einRaster: (LevelTask, LocalDate) -> Boolean = oneGridForAllTasks(montagBisFreitag)
  private val montag = LocalDate.of(2026, 8, 17)

  @Test
  fun `eingefrorene arbeit bleibt liegen und belegt trotzdem`() {
    val fertig = LevelTask("fertig", 0, 2, durationDays = 5, loads = mapOf(SHARED_POOL to 100),
      frozen = true, fixedStart = montag)
    val offen = LevelTask("offen", 1, 2, durationDays = 5, loads = mapOf(SHARED_POOL to 100))
    val ergebnis = levelTasks(listOf(fertig, offen), montag, einRaster)

    assertEquals(montag, ergebnis.starts["fertig"], "die erledigte Arbeit wird nicht verschoben")
    assertEquals(LocalDate.of(2026, 8, 24), ergebnis.starts["offen"],
      "und sie belegt weiter -- sonst plant die Verteilung dieselbe Woche zweimal")
  }

  @Test
  fun `eingefrorene arbeit wird auch dann nicht verschoben, wenn sie sich ueberlappt`() {
    // Two begun Tasks on the same day: that is reality, not a bug in levelling. It must change
    // nothing about it -- report yes, re-lay no.
    val a = LevelTask("a", 0, 2, durationDays = 3, loads = mapOf(SHARED_POOL to 100),
      frozen = true, fixedStart = montag)
    val b = LevelTask("b", 1, 2, durationDays = 3, loads = mapOf(SHARED_POOL to 100),
      frozen = true, fixedStart = montag)
    val ergebnis = levelTasks(listOf(a, b), montag, einRaster)
    assertEquals(montag, ergebnis.starts["a"])
    assertEquals(montag, ergebnis.starts["b"])
    assertTrue(ergebnis.conflicts.any { it is LevelConflict.Overload }, "aber gemeldet wird es")
  }

  @Test
  fun `eine nicht zu haltende frist wird gemeldet, nicht erzwungen`() {
    val a = LevelTask("a", 0, 2, durationDays = 10, loads = mapOf(SHARED_POOL to 100))
    val b = LevelTask("b", 1, 2, durationDays = 10, loads = mapOf(SHARED_POOL to 100),
      deadline = LocalDate.of(2026, 8, 31))
    val ergebnis = levelTasks(listOf(a, b), montag, einRaster)

    val verpasst = ergebnis.conflicts.filterIsInstance<LevelConflict.DeadlineMissed>()
    assertEquals(1, verpasst.size)
    assertEquals("b", verpasst[0].id)
    assertTrue(verpasst[0].missingDays > 0, "die Zahl der fehlenden Tage gehoert dazu")
    // The Task was NOT pulled forward: enforcing the deadline would only move the problem.
    assertEquals(LocalDate.of(2026, 8, 31), ergebnis.starts["b"])
  }

  @Test
  fun `eine haltbare frist meldet nichts`() {
    // Counter-check: otherwise the test above would pass even with a report for every Task.
    val a = LevelTask("a", 0, 2, durationDays = 3, loads = mapOf(SHARED_POOL to 100),
      deadline = LocalDate.of(2026, 12, 31))
    val ergebnis = levelTasks(listOf(a), montag, einRaster)
    assertTrue(ergebnis.conflicts.none { it is LevelConflict.DeadlineMissed })
  }

  @Test
  fun `ein auslastungsgrad unter 100 laesst luft`() {
    // Two Tasks at 60 % each do not fit together at 100 % (120), nor at 100 % -- but one at
    // 60 % and one at 30 % fit at 100 %, and no longer at 80 %.
    val a = LevelTask("a", 0, 2, durationDays = 5, loads = mapOf(SHARED_POOL to 60))
    val b = LevelTask("b", 1, 2, durationDays = 5, loads = mapOf(SHARED_POOL to 30))
    val voll = levelTasks(listOf(a, b), montag, einRaster)
    assertEquals(montag, voll.starts["b"], "bei 100 % passen 60 und 30 zusammen")

    val gebremst = levelTasks(listOf(a, b), montag, einRaster, capacityOf = { 80 })
    assertEquals(LocalDate.of(2026, 8, 24), gebremst.starts["b"],
      "bei 80 % nicht mehr -- genau das ist der Puffer")
  }
}

/**
 * Utilisation -- and the most expensive failure mode of this session.
 *
 * MEASURED ON THE MACHINE, 17.08.2026: the figure sat in levelling as a PACKING LIMIT. At 80 %
 * utilisation and Tasks with 100 % load no Task fitted on any day any more -- not even on a
 * completely empty one. The window search ran on without limit: no dialog, no message, a program
 * that hangs. After the loop was closed it reported EVERY day as overloaded, 37 reports for five
 * Tasks.
 *
 * Both are fixed, and both are recorded here.
 */
class AuslastungsgradTest {
  private val montagBisFreitag: (LocalDate) -> Boolean = {
    it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY
  }

  /**
   * The same week as ONE grid for every task -- [levelTasks] asks its working-day test per task
   * since 04.09.2026. Nothing in this file is about differing grids: these checks are about the
   * daily rate, the ordering and the capacity, and one Monday-to-Friday week is the whole calendar
   * they need. [oneGridForAllTasks] states that at the hand-over instead of leaving it implied.
   */
  private val einRaster: (LevelTask, LocalDate) -> Boolean = oneGridForAllTasks(montagBisFreitag)
  private val montag = LocalDate.of(2026, 8, 17)

  @Test
  fun `ein voller vorgang passt auch bei achtzig prozent`() {
    // Without the rule "the limit is at least the Task's own load" this test hangs.
    val tasks = listOf(LevelTask("a", 0, 2, durationDays = 5, loads = mapOf(SHARED_POOL to 100)))
    val ergebnis = levelTasks(tasks, montag, einRaster, capacityOf = { 80 })
    assertEquals(montag, ergebnis.starts["a"],
      "ein einzelner Vorgang muss liegen duerfen, sonst sucht die Verteilung endlos")
  }

  @Test
  fun `der grad begrenzt weiterhin, wie viel NEBENEINANDER liegt`() {
    // Counter-check: the figure has not become ineffective. Two half Tasks make 100 % and do not
    // fit together at 80 %.
    val tasks = listOf(
      LevelTask("a", 0, 2, durationDays = 5, loads = mapOf(SHARED_POOL to 50)),
      LevelTask("b", 1, 2, durationDays = 5, loads = mapOf(SHARED_POOL to 50)))
    val ergebnis = levelTasks(tasks, montag, einRaster, capacityOf = { 80 })
    assertEquals(LocalDate.of(2026, 8, 24), ergebnis.starts["b"])
  }

  @Test
  fun `die suche gibt auf, statt endlos zu laufen`() {
    // Second safeguard: even if the limit were wrong again one day, the search has to end. An
    // endless loop is the most expensive failure mode -- nothing about it is visible.
    val tasks = listOf(LevelTask("a", 0, 2, durationDays = 5, loads = mapOf(SHARED_POOL to 100)))
    val ergebnis = levelTasks(tasks, montag, oneGridForAllTasks { false })  // KEIN Tag ist Arbeitstag
    assertTrue(ergebnis.starts.containsKey("a"), "die Verteilung muss zurueckkommen")
  }
}

