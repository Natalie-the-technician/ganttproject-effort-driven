/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Does a file written by the desktop fork survive a trip through the app?
 *
 * The app belongs to somebody who does not make the plan. It therefore knows
 * almost nothing about what the planner puts in the file — levelling, axes,
 * deadlines, working weeks, home-office rules — and it must not need to: what
 * it cannot read it must still hand back unchanged.
 *
 * That is a claim about the writer, not about the reader, and it was read out
 * of the code rather than measured before this file existed. The fixture
 * carries every fork feature that could be found in the desktop sources on
 * 05.09.2026, and the assertions below name each one separately: "the file
 * still looks about right" is not a result.
 */
class ForkRoundTripTest {

  private fun fixture(): String =
    checkNotNull(javaClass.getResourceAsStream("/ForkFeatures.gan")) {
      "ForkFeatures.gan missing from the test classpath"
    }.readBytes().toString(Charsets.UTF_8)

  private fun md5(text: String): String =
    MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }

  /**
   * Everything the planner may have put in the file, as the literal text that
   * has to come back out.
   *
   * Deliberately checked as strings against the written file rather than
   * through the app's model: the model does not know most of these, and asking
   * it would only confirm that it does not know them.
   */
  private val mustSurvive = linkedMapOf(
    // Allocation attributes -- the two axes and the load
    "allocation blocking=true" to """blocking="true"""",
    "allocation no-effort=true" to """no-effort="true"""",
    "allocation load=50.0" to """load="50.0"""",
    "allocation function" to """function="Default:1"""",
    // Task custom properties of the fork
    "deadline (definition)" to """name="deadline"""",
    "deadline (value)" to """value="2026-09-30"""",
    "wait_only (definition)" to """name="wait_only"""",
    "date_fixed (definition)" to """name="date_fixed"""",
    "on_site_only (definition)" to """name="on_site_only"""",
    "effort_original_hours" to """name="effort_original_hours"""",
    "recurrence (value)" to """value="WEEKLY:2"""",
    // Resource custom properties
    "hours_per_day (definition)" to """name="hours_per_day"""",
    "utilisation_percent" to """name="utilisation_percent"""",
    "work_week (definition)" to """name="work_week"""",
    "work_week (value)" to """value="MO,DI,MI"""",
    // Calendar, absences, baseline, roles
    "holiday with a year" to """date year="2026" month="10" date="3"""",
    "recurring holiday" to """date year="" month="12" date="24"""",
    "default-week" to """<default-week""",
    "vacation" to """<vacation start="2026-09-20"""",
    "baseline block" to """<previous-tasks name="Basisplan A"""",
    "baseline entry" to """<previous-task id="1"""",
    "roles" to """<role id="1" name="Messtechnik"/>""",
    // Things the app does not model at all
    "dependency" to """<depend id="2"""",
    "view field" to """<field id="tpc4" name="Frist"""",
    "timeline CDATA" to """<timeline><![CDATA[1]]></timeline>""",
    "XML comment" to """<!-- Kommentar, den die App nicht kennt -->""",
    // CDATA, not entities: the fixture writes the note as a CDATA section and
    // XmlTree keeps it that way. Expecting escaped entities here was my
    // mistake in the first run, not the app's -- worth keeping as a check that
    // the section survives with its awkward characters intact.
    "notes as CDATA" to """<![CDATA[Notiz mit ] und & und <spitzen>]]>"""
  )

  private fun missing(xml: String): List<String> =
    mustSurvive.filterNot { (_, needle) -> xml.contains(needle) }.keys.toList()

  // ------------------------------------------------- Load and save untouched

  @Test
  @DisplayName("loading and saving without an edit keeps every fork feature")
  fun `an untouched round trip loses nothing`() {
    val before = fixture()
    val after = GanttDocument.parse(before).toXmlString()

    println("== Rundweg ohne Bearbeitung ==")
    println("md5 vorher : ${md5(before)}")
    println("md5 nachher: ${md5(after)}")
    println("gleich     : ${before == after}")
    println("Laenge     : ${before.length} -> ${after.length}")
    val lost = missing(after)
    println("verloren   : ${if (lost.isEmpty()) "nichts" else lost.joinToString(", ")}")

    // Written out so the difference can be looked at rather than guessed. A
    // round trip that is not byte-identical has to be explained, not excused.
    java.io.File("build/rundweg-vorher.gan").also { it.parentFile.mkdirs() }.writeText(before)
    java.io.File("build/rundweg-nachher.gan").writeText(after)

    assertTrue(lost.isEmpty(), "diese Merkmale fehlen nach dem Rundweg: $lost")
  }

  // --------------------------------------------- Load, book a time, and save

  @Test
  @DisplayName("booking a time entry keeps every fork feature")
  fun `an edit loses nothing else`() {
    val before = fixture()
    val doc = GanttDocument.parse(before)

    val booked = doc.addTimeRecord(
      TimeRecord(
        id = "rt-1",
        taskUid = "bbbb1111bbbb1111bbbb1111bbbb1111",
        start = OffsetDateTime.of(2026, 9, 2, 9, 0, 0, 0, ZoneOffset.ofHours(2)),
        durationSeconds = 5400,
        description = "Messreihe aufgebaut",
        person = "Natalie"
      )
    )
    val after = doc.toXmlString()

    println("== Rundweg mit einer Zeitbuchung ==")
    println("gebucht    : $booked")
    println("md5 vorher : ${md5(before)}")
    println("md5 nachher: ${md5(after)}")
    println("Laenge     : ${before.length} -> ${after.length}")
    val lost = missing(after)
    println("verloren   : ${if (lost.isEmpty()) "nichts" else lost.joinToString(", ")}")

    assertTrue(booked, "die Buchung selbst muss gelingen")
    assertTrue(lost.isEmpty(), "diese Merkmale fehlen nach der Buchung: $lost")
    assertTrue(after.contains("Messreihe aufgebaut"), "der neue Satz muss drinstehen")
  }

  // ------------------------------------------------------------- The ordering

  @Test
  @DisplayName("the order of the elements under <project> is kept")
  fun `nothing is reordered`() {
    // A round trip that keeps everything but shuffles it makes every diff
    // unreadable, which costs the planner more than a missing attribute would.
    val before = fixture()
    val after = GanttDocument.parse(before).toXmlString()

    fun topLevel(xml: String): List<String> =
      Regex("""\n {4}<([a-z-]+)""").findAll(xml).map { it.groupValues[1] }.toList()

    val a = topLevel(before)
    val b = topLevel(after)
    println("== Reihenfolge unter <project> ==")
    println("vorher : $a")
    println("nachher: $b")
    assertEquals(a, b, "die Kinder von <project> haben ihre Reihenfolge verloren")
  }

  @Test
  @DisplayName("the app reads what it does know from the fixture")
  fun `the reader still works on this file`() {
    // A positive control. Without it, a round trip could "lose nothing" simply
    // because the parser gave up and handed the bytes back untouched.
    val model = GanttDocument.parse(fixture()).read()
    assertEquals("Rundwegprobe", model.name)
    assertEquals(2, model.tasks.size, "ein Sammelvorgang und ein Meilenstein")
    assertEquals(2, model.resources.size)
    assertEquals(3, model.allocations.size)
    assertEquals(16.0, model.task("1")?.effortHours)
    assertEquals(6.0, model.resource("0")?.hoursPerDay)
    println("== Positivkontrolle ==")
    println("Projekt    : ${model.name}")
    println("Vorgaenge  : ${model.flatTasks.map { it.id }}")
    println("Zuordnungen: ${model.allocations.size}")
  }
}
