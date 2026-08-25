/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class TimeLogSummaryTest {

  private val berlin: ZoneId = ZoneId.of("Europe/Berlin")

  private fun at(day: Int, hour: Int, minute: Int = 0): OffsetDateTime =
    OffsetDateTime.of(2026, 7, day, hour, minute, 0, 0, ZoneOffset.ofHours(2))

  private fun record(
    id: String = "r1",
    taskUid: String = "t1",
    start: OffsetDateTime = at(3, 9),
    seconds: Long = 3600,
    person: String? = null,
    source: TimeSource = TimeSource.MANUAL,
    description: String = "Bench measurements"
  ) = TimeRecord(id, taskUid, start, seconds, description, person, source)

  // --------------------------------------------------------------- Totals

  @Test
  fun `totals count records, seconds and the span of days`() {
    val totals = totalsOf(
      listOf(
        record(id = "a", start = at(3, 9), seconds = 3600),
        record(id = "b", start = at(7, 9), seconds = 1800, source = TimeSource.TIMER)
      ),
      berlin
    )
    assertEquals(2, totals.recordCount)
    assertEquals(5400L, totals.totalSeconds)
    assertEquals(1.5, totals.totalHours)
    assertEquals(LocalDate.of(2026, 7, 3), totals.firstDay)
    assertEquals(LocalDate.of(2026, 7, 7), totals.lastDay)
    assertEquals(3600L, totals.secondsBySource[TimeSource.MANUAL])
    assertEquals(1800L, totals.secondsBySource[TimeSource.TIMER])
  }

  @Test
  fun `an empty log has empty totals rather than nonsense`() {
    val totals = totalsOf(emptyList(), berlin)
    assertTrue(totals.isEmpty)
    assertEquals(0L, totals.totalSeconds)
    assertNull(totals.firstDay)
    assertNull(totals.lastDay)
  }

  @Test
  @DisplayName("summing stays in whole seconds")
  fun `nothing is rounded on the way to a total`() {
    val odd = (1..7).map { record(id = "r$it", seconds = 1, start = at(3, it)) }
    assertEquals(7L, totalsOf(odd, berlin).totalSeconds)
  }

  // -------------------------------------------------------------- Per person

  @Test
  fun `hours are counted per person, and nobody is not somebody`() {
    val byPerson = secondsByPerson(
      listOf(
        record(id = "a", person = "nat", seconds = 3600),
        record(id = "b", person = "nat", seconds = 1800, start = at(4, 9)),
        record(id = "c", person = null, seconds = 900, start = at(5, 9))
      )
    )
    assertEquals(5400L, byPerson["nat"])
    assertEquals(900L, byPerson[null], "records without a person keep their own bucket")
  }

  // --------------------------------------------------------------- Per label

  @Test
  @DisplayName("a task with two labels counts in full under each, and the sums do not add up")
  fun `labels do not partition the hours`() {
    // Splitting an hour between two labels would invent a distribution nobody
    // decided. Anything that needs a partition has to define one itself, and
    // this test is here so the surprising-but-correct behaviour is not
    // "fixed" later by somebody who assumes the total should match.
    val byLabel = secondsByLabel(
      listOf(record(id = "a", taskUid = "t1", seconds = 3600)),
      mapOf("t1" to listOf("V306", "billable"))
    )
    assertEquals(3600L, byLabel["V306"])
    assertEquals(3600L, byLabel["billable"])
    assertEquals(7200L, byLabel.values.sum(), "deliberately more than the hour that was worked")
  }

  @Test
  fun `a task without a label keeps its hours visible`() {
    val byLabel = secondsByLabel(listOf(record(taskUid = "t9", seconds = 1800)), emptyMap())
    assertEquals(1800L, byLabel[null])
  }

  // ------------------------------------------------------------ Per day

  @Test
  fun `day and task make the shape a timesheet form asks for`() {
    val byDayTask = secondsByDayAndTask(
      listOf(
        record(id = "a", taskUid = "t1", start = at(3, 9), seconds = 3600),
        record(id = "b", taskUid = "t1", start = at(3, 14), seconds = 1800),
        record(id = "c", taskUid = "t2", start = at(3, 16), seconds = 900)
      ),
      berlin
    )
    assertEquals(5400L, byDayTask[LocalDate.of(2026, 7, 3) to "t1"])
    assertEquals(900L, byDayTask[LocalDate.of(2026, 7, 3) to "t2"])
  }

  @Test
  fun `the day a record counts towards follows the zone that was asked for`() {
    val late = record(start = at(31, 23, 30), seconds = 1800)
    assertEquals(
      setOf(LocalDate.of(2026, 7, 31) to "t1"),
      secondsByDayAndTask(listOf(late), berlin).keys
    )
    assertEquals(
      setOf(LocalDate.of(2026, 8, 1) to "t1"),
      secondsByDayAndTask(listOf(late), ZoneId.of("Asia/Tokyo")).keys
    )
  }

  // ------------------------------------------------------------------ CSV

  @Test
  fun `the table has a header and one row per record`() {
    val csv = TimeLogCsv.write(
      listOf(record(id = "a"), record(id = "b", start = at(4, 9))),
      berlin
    )
    val lines = csv.trim().lines()
    assertEquals(3, lines.size)
    assertTrue(lines[0].startsWith("\"Date\""))
    assertEquals(TimeLogCsv.COLUMNS.size, lines[1].count { it == ';' } + 1)
  }

  @Test
  @DisplayName("seconds and hours are both written, so nobody has to trust our rounding")
  fun `the exact value is in the table too`() {
    val csv = TimeLogCsv.write(listOf(record(seconds = 5400)), berlin)
    val row = csv.trim().lines()[1]
    assertTrue(row.contains("\"5400\""), "the exact seconds")
    assertTrue(row.contains("\"1.5000\""), "and the hours for reading")
  }

  @Test
  fun `a decimal comma is available for spreadsheets that want one`() {
    val csv = TimeLogCsv.write(listOf(record(seconds = 5400)), berlin, decimalComma = true)
    assertTrue(csv.contains("\"1,5000\""))
  }

  @Test
  @DisplayName("free text cannot break a row")
  fun `quotes, separators and newlines in a description are handled`() {
    val nasty = "he said \"go\"; then a newline\nand a ; separator"
    val csv = TimeLogCsv.write(listOf(record(description = nasty)), berlin)

    assertEquals(2, csv.trim().lines().size, "still one row")
    assertTrue(csv.contains("\"\"go\"\""), "a quote is doubled, as CSV requires")
    assertTrue(csv.contains("then a newline and a ; separator"), "the newline became a space")
  }

  @Test
  fun `labels and person reach the table`() {
    val csv = TimeLogCsv.write(
      listOf(record(taskUid = "t1", person = "nat")),
      berlin,
      mapOf("t1" to listOf("V306", "billable"))
    )
    assertTrue(csv.contains("\"V306 billable\""))
    assertTrue(csv.contains("\"nat\""))
    assertTrue(csv.contains("\"MANUAL\""))
  }

  @Test
  fun `an empty log still writes its header`() {
    val csv = TimeLogCsv.write(emptyList(), berlin)
    assertEquals(1, csv.trim().lines().size)
    assertTrue(csv.startsWith("\"Date\""))
  }
}
