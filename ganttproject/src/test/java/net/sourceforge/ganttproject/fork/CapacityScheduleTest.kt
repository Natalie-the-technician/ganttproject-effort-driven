/*
Copyright 2026 Noctuvo

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

  private fun plan(text: String, base: Double = 4.0) = CapacitySchedule.parse(text, base)

  // ---- Lesen ----------------------------------------------------------------------------

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

  // ---- Fehler, und dass sie NICHT verschluckt werden -------------------------------------

  @Test
  fun `ein tippfehler wird gemeldet, nicht ignoriert`() {
    val ergebnis = plan("2027-04-01: acht")
    assertEquals(1, ergebnis.errors.size)
    assertTrue(ergebnis.hasErrors)
    // Der brauchbare Rest bleibt trotzdem stehen: die Anzeige soll etwas zeigen koennen.
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
    // Gegenprobe: genau an der Grenze ist es noch gueltig.
    assertTrue(plan("2027-04-01: 24").errors.isEmpty())
  }

  @Test
  fun `gute und schlechte eintraege zusammen ergeben beides`() {
    val ergebnis = plan("2027-04-01: 6; kaputt; 2027-10-01: 8")
    assertEquals(1, ergebnis.errors.size)
    assertEquals(2, ergebnis.schedule.changes.size)
  }

  // ---- Rechnen --------------------------------------------------------------------------

  @Test
  fun `ohne abschnitte rechnet es genau wie bisher`() {
    // Die Sicherheitseigenschaft: der neue Weg darf am alten Verhalten nichts aendern.
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
    // Ab Mittwoch acht statt vier Stunden. 20 Std. Aufwand ab Montag:
    // Mo 4, Di 4 (8 verbraucht), Mi 8 (16), Do 8 -> fertig am vierten Tag.
    val montag = LocalDate.of(2026, 8, 17)
    val p = plan("2026-08-19: 8").schedule
    assertEquals(4, daysNeeded(20.0, montag, p, isWorkingDay = montagBisFreitag))
    // Gegenprobe: durchgehend vier Stunden waeren fuenf Tage, durchgehend acht waeren drei.
    assertEquals(5, daysNeeded(20.0, montag, CapacitySchedule(4.0), isWorkingDay = montagBisFreitag))
    assertEquals(3, daysNeeded(20.0, montag, CapacitySchedule(8.0), isWorkingDay = montagBisFreitag))
  }

  @Test
  fun `wochenenden zaehlen nicht mit`() {
    // Freitag, 4 Std./Tag, 8 Std. Aufwand: Freitag und Montag -- zwei Arbeitstage.
    val freitag = LocalDate.of(2026, 8, 21)
    assertEquals(2, daysNeeded(8.0, freitag, CapacitySchedule(4.0), isWorkingDay = montagBisFreitag))
  }

  @Test
  fun `ein abschnitt mit null stunden endet nie und wird gemeldet`() {
    val p = plan("2026-08-18: 0").schedule
    // Montag reicht der Aufwand noch nicht, ab Dienstag geht nichts mehr weiter.
    assertNull(daysNeeded(100.0, LocalDate.of(2026, 8, 17), p, isWorkingDay = montagBisFreitag),
      "statt endlos zu laufen, muss die Rechnung aufgeben und den Aufrufer melden lassen")
    // Gegenprobe: was vor der Null noch hineinpasst, wird sehr wohl gerechnet.
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

  // ---- Zusammenspiel mit der Verteilung ---------------------------------------------------

  @Test
  fun `die verteilung rechnet die dauer am gelegten termin, nicht am alten`() {
    val montag = LocalDate.of(2026, 8, 17)
    // Ab dem 1.9. gibt es acht statt vier Stunden.
    val plan = plan("2026-09-01: 8").schedule
    val aufwand = mapOf("a" to 40.0, "b" to 40.0)
    val durationAt = { task: LevelTask, start: LocalDate ->
      daysNeeded(aufwand.getValue(task.id), start, plan, isWorkingDay = montagBisFreitag) ?: 1
    }
    // Zwei Vorgaenge, beide 40 Std., beide voll ausgelastet: sie koennen nicht gleichzeitig laufen.
    val tasks = listOf(
      LevelTask(id = "a", orderInPlan = 0, priority = 2, durationDays = 10, loadPercent = 100),
      LevelTask(id = "b", orderInPlan = 1, priority = 2, durationDays = 10, loadPercent = 100))

    val ergebnis = levelTasks(tasks, montag, montagBisFreitag, durationAt)

    // Der erste beginnt am 17.8. -- vier Stunden am Tag, bis zum 1.9. Danach acht.
    // Mo 17.8. bis Fr 28.8. sind 10 Arbeitstage a 4 Std. = 40 Std.: genau zehn Tage.
    assertEquals(montag, ergebnis.starts["a"])
    assertEquals(10, ergebnis.durations["a"], "vor der Grenze: vier Stunden am Tag")
    // Der zweite faengt am Montag, 31.8. an -- und das ist EIN TAG VOR der Grenze. Also:
    // Mo 31.8. 4 Std., dann ab Di 1.9. acht: 4+8+8+8+8+8 = 44 >= 40 am sechsten Tag.
    //
    // MEINE ERWARTUNG WAR HIER ZUERST 5, und sie war falsch: ich hatte den 31.8. schon zum
    // Acht-Stunden-Abschnitt gezaehlt. Die Zahl steht bewusst mit dieser Rechnung daneben --
    // eine Zahl ohne Herleitung laedt dazu ein, sie beim naechsten Fehlschlag einfach anzupassen.
    assertEquals(6, ergebnis.durations["b"],
      "vier Stunden am ersten Tag, danach acht")
    // Gegenprobe: laege der zweite Vorgang ganz im Acht-Stunden-Abschnitt, waeren es fuenf Tage.
    assertEquals(5, daysNeeded(40.0, LocalDate.of(2026, 9, 1), plan, isWorkingDay = montagBisFreitag))
    assertTrue(ergebnis.conflicts.isEmpty(), "niemand ist ueberlastet: ${ergebnis.conflicts}")
  }

  @Test
  fun `ohne stundenplan verhaelt sich die verteilung wie vorher`() {
    // Gegenprobe zur vorigen: dieselbe Aufstellung, aber ohne Abschnitte. Die Dauer muss dann
    // exakt die aus dem LevelTask sein -- der neue Weg darf am alten Verhalten nichts aendern.
    val montag = LocalDate.of(2026, 8, 17)
    val tasks = listOf(
      LevelTask(id = "a", orderInPlan = 0, priority = 2, durationDays = 10, loadPercent = 100),
      LevelTask(id = "b", orderInPlan = 1, priority = 2, durationDays = 10, loadPercent = 100))
    val ergebnis = levelTasks(tasks, montag, montagBisFreitag)
    assertEquals(10, ergebnis.durations["a"])
    assertEquals(10, ergebnis.durations["b"])
  }
}

/**
 * Die Verteilung mit MEHREREN Personen.
 *
 * WOZU: bis zum 17.08.2026 hatte die Verteilung einen einzigen Kapazitaetstopf. Zwei Personen
 * haetten damit nicht gleichzeitig arbeiten koennen -- die Verteilung haette ihre Vorgaenge
 * hintereinandergelegt und ein Ergebnis geliefert, das plausibel aussieht und den Plan um Monate
 * verlaengert. Natalie plant heute allein; das darf aber nicht der Grund sein, warum es stimmt.
 */
class MehrerePersonenTest {
  private val montagBisFreitag: (LocalDate) -> Boolean = {
    it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY
  }
  private val montag = LocalDate.of(2026, 8, 17)

  @Test
  fun `zwei personen arbeiten gleichzeitig`() {
    val tasks = listOf(
      LevelTask("a", 0, 2, durationDays = 5, loadPercent = 100, resourceIds = listOf("1")),
      LevelTask("b", 1, 2, durationDays = 5, loadPercent = 100, resourceIds = listOf("2")))
    val ergebnis = levelTasks(tasks, montag, montagBisFreitag)
    assertEquals(montag, ergebnis.starts["a"])
    assertEquals(montag, ergebnis.starts["b"], "die zweite Person hat ihre eigene Kapazitaet")
    assertTrue(ergebnis.conflicts.isEmpty())
  }

  @Test
  fun `dieselbe person kann es nicht gleichzeitig`() {
    // Gegenprobe: dieselbe Aufstellung, aber beide Vorgaenge bei derselben Person.
    val tasks = listOf(
      LevelTask("a", 0, 2, durationDays = 5, loadPercent = 100, resourceIds = listOf("1")),
      LevelTask("b", 1, 2, durationDays = 5, loadPercent = 100, resourceIds = listOf("1")))
    val ergebnis = levelTasks(tasks, montag, montagBisFreitag)
    assertEquals(montag, ergebnis.starts["a"])
    assertEquals(LocalDate.of(2026, 8, 24), ergebnis.starts["b"], "erst danach")
  }

  @Test
  fun `ein vorgang mit zwei personen belegt beide`() {
    val tasks = listOf(
      LevelTask("gemeinsam", 0, 2, durationDays = 5, loadPercent = 100,
        resourceIds = listOf("1", "2")),
      LevelTask("nur1", 1, 2, durationDays = 5, loadPercent = 100, resourceIds = listOf("1")),
      LevelTask("nur2", 2, 2, durationDays = 5, loadPercent = 100, resourceIds = listOf("2")))
    val ergebnis = levelTasks(tasks, montag, montagBisFreitag)
    assertEquals(montag, ergebnis.starts["gemeinsam"])
    // Beide Toepfe sind belegt, also muessen BEIDE Folgevorgaenge warten.
    assertEquals(LocalDate.of(2026, 8, 24), ergebnis.starts["nur1"])
    assertEquals(LocalDate.of(2026, 8, 24), ergebnis.starts["nur2"])
  }

  @Test
  fun `die ueberlastmeldung nennt die person`() {
    val tasks = listOf(
      LevelTask("a", 0, 2, durationDays = 5, loadPercent = 100, resourceIds = listOf("7"),
        fixedStart = montag),
      LevelTask("b", 1, 2, durationDays = 5, loadPercent = 100, resourceIds = listOf("7"),
        fixedStart = montag))
    val ergebnis = levelTasks(tasks, montag, montagBisFreitag)
    val ueberlast = ergebnis.conflicts.filterIsInstance<LevelConflict.Overload>()
    assertTrue(ueberlast.isNotEmpty(), "zwei feste Termine am selben Tag sprengen die Kapazitaet")
    assertEquals("7", ueberlast.first().resourceId)
  }

  @Test
  fun `ohne zuordnung teilen sich alle einen topf`() {
    // Das war das bisherige Verhalten und muss so bleiben: ein Vorgang ohne Zuordnung belegt
    // Zeit, von der man nur nicht weiss, wessen. Ihn als kostenlos zu behandeln waere die
    // gefaehrlichere Annahme.
    val tasks = listOf(
      LevelTask("a", 0, 2, durationDays = 5, loadPercent = 100),
      LevelTask("b", 1, 2, durationDays = 5, loadPercent = 100))
    val ergebnis = levelTasks(tasks, montag, montagBisFreitag)
    assertEquals(LocalDate.of(2026, 8, 24), ergebnis.starts["b"])
  }
}
