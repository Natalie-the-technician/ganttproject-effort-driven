/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class TimeLogTest {

  private fun at(
    day: Int,
    hour: Int,
    minute: Int = 0,
    offsetHours: Int = 2
  ): OffsetDateTime = OffsetDateTime.of(
    2026, 7, day, hour, minute, 0, 0, ZoneOffset.ofHours(offsetHours)
  )

  private fun record(
    id: String = "r1",
    taskUid: String = "abc123",
    start: OffsetDateTime = at(3, 9),
    seconds: Long = 3600,
    description: String = "Prototype board bring-up",
    person: String? = null,
    source: TimeSource = TimeSource.MANUAL
  ) = TimeRecord(
    id = id,
    taskUid = taskUid,
    start = start,
    durationSeconds = seconds,
    description = description,
    person = person,
    source = source
  )

  // ------------------------------------------------------------ The record

  @Test
  fun `end follows from start and duration`() {
    val r = record(start = at(3, 9), seconds = 5400)
    assertEquals(at(3, 10, 30), r.end)
  }

  @Test
  @DisplayName("a record keeps raw seconds; nothing here rounds")
  fun `raw seconds survive`() {
    // Guards the rule that rounding belongs to whoever consumes the export.
    // If anybody ever adds rounding to this module, this test goes red — and
    // it should, because a rounded record cannot be un-rounded afterwards.
    val odd = record(seconds = 1)
    assertEquals(1L, odd.durationSeconds)
    assertEquals(1L, secondsByTaskUid(listOf(odd)).values.single())
    assertEquals(3601L, secondsByTaskUid(listOf(odd, record(id = "r2"))).values.single())
  }

  // ---------------------------------------------------------- The time zone

  @Test
  @DisplayName("the reporting day depends on the zone the question is asked in")
  fun `late work falls on different days in different zones`() {
    // 23:30 at +02:00 is 21:30 UTC on the same day, but 06:30 the NEXT day in
    // Tokyo. This is the whole reason the record carries an offset and dateIn()
    // takes a zone: a phone that travels must not move hours into another month.
    val r = record(start = at(31, 23, 30, offsetHours = 2))

    assertEquals(LocalDate.of(2026, 7, 31), r.dateIn(ZoneId.of("Europe/Berlin")))
    assertEquals(LocalDate.of(2026, 7, 31), r.dateIn(ZoneOffset.UTC))
    assertEquals(LocalDate.of(2026, 8, 1), r.dateIn(ZoneId.of("Asia/Tokyo")))
  }

  @Test
  fun `days are counted in the zone that was asked for`() {
    val records = listOf(
      record(id = "a", start = at(31, 23, 30, offsetHours = 2), seconds = 1800),
      record(id = "b", start = at(31, 8, 0, offsetHours = 2), seconds = 3600)
    )
    val berlin = secondsByDay(records, ZoneId.of("Europe/Berlin"))
    assertEquals(5400L, berlin[LocalDate.of(2026, 7, 31)])

    val tokyo = secondsByDay(records, ZoneId.of("Asia/Tokyo"))
    assertEquals(3600L, tokyo[LocalDate.of(2026, 7, 31)])
    assertEquals(1800L, tokyo[LocalDate.of(2026, 8, 1)])
  }

  // ------------------------------------------------------------- Encoding

  @Test
  fun `a record survives the round trip unchanged`() {
    val original = record(
      id = "rec-7",
      taskUid = "9f2c",
      start = at(3, 9, 15),
      seconds = 2700,
      description = "Measured supply rail, found ripple",
      person = "nat",
      source = TimeSource.TIMER
    )
    val decoded = TimeLogCodec.decodeRecord(TimeLogCodec.encodeRecord(original))
    assertEquals(original, decoded)
  }

  @Test
  @DisplayName("separators and newlines inside a description survive")
  fun `escaping holds`() {
    // A description is free text. If any of these got through unescaped, the
    // line would split and the record after it would be silently mangled.
    val nasty = "tab\there, newline\nthere, back\\slash, pipe|and;semicolon\r"
    val original = record(description = nasty)
    val line = TimeLogCodec.encodeRecord(original)

    assertEquals(1, line.lines().size, "an encoded record must stay on one line")
    assertEquals(nasty, TimeLogCodec.decodeRecord(line)?.description)
  }

  @Test
  fun `the same records always produce the same text`() {
    val a = record(id = "a", start = at(3, 9))
    val b = record(id = "b", start = at(4, 9))
    val c = record(id = "c", start = at(3, 9))

    assertEquals(
      TimeLogCodec.encode(listOf(a, b, c)),
      TimeLogCodec.encode(listOf(b, c, a))
    )
  }

  @Test
  @DisplayName("an older reader tolerates fields it does not know")
  fun `unknown trailing fields are ignored`() {
    val line = TimeLogCodec.encodeRecord(record()) + "\tsomething\tfrom\tthe\tfuture"
    val decoded = TimeLogCodec.decodeRecord(line)
    assertEquals("r1", decoded?.id)
    assertEquals(3600L, decoded?.durationSeconds)
  }

  @Test
  fun `a minimal line is readable with defaults`() {
    val line = listOf("r9", "uid9", at(3, 9).toString(), "1800").joinToString("\t")
    val decoded = TimeLogCodec.decodeRecord(line)
    assertEquals(TimeSource.MANUAL, decoded?.source)
    assertNull(decoded?.person)
    assertEquals("", decoded?.description)
    assertEquals(decoded?.start, decoded?.createdAt)
  }

  @Test
  @DisplayName("a damaged line is skipped and counted, the rest survives")
  fun `decoding reports its losses`() {
    val good = TimeLogCodec.encodeRecord(record(id = "good"))
    val text = listOf(good, "this is not a record", "r?\tuid\tnot-a-date\t60").joinToString("\n")

    val result = TimeLogCodec.decode(text)
    assertEquals(1, result.records.size)
    assertEquals("good", result.records.single().id)
    assertEquals(2, result.skippedLines)
    assertTrue(result.hasLosses)
  }

  @Test
  fun `an empty log decodes to nothing without complaint`() {
    assertEquals(0, TimeLogCodec.decode(null).records.size)
    assertEquals(0, TimeLogCodec.decode("").skippedLines)
  }

  @Test
  fun `a record without a task is refused by the reader`() {
    val line = listOf("r1", "", at(3, 9).toString(), "60").joinToString("\t")
    assertNull(TimeLogCodec.decodeRecord(line))
  }

  // ---------------------------------------------------------- Aggregation

  @Test
  fun `hours per task are summed from the records`() {
    val records = listOf(
      record(id = "a", taskUid = "t1", seconds = 3600),
      record(id = "b", taskUid = "t1", seconds = 1800),
      record(id = "c", taskUid = "t2", seconds = 900)
    )
    assertEquals(5400L, secondsByTaskUid(records)["t1"])
    assertEquals(1.5, hoursByTaskUid(records)["t1"])
    assertEquals(0.25, hoursByTaskUid(records)["t2"])
  }

  @Test
  fun `records of one task come back oldest first`() {
    val records = listOf(
      record(id = "late", start = at(5, 9)),
      record(id = "early", start = at(3, 9)),
      record(id = "aaa", start = at(3, 9))
    )
    assertEquals(
      listOf("aaa", "early", "late"),
      recordsOfTask(records, "abc123").map { it.id }
    )
  }

  // ---------------------------------------------------------- Validation

  @Test
  fun `a zero-length record is a problem`() {
    val problems = validateRecord(record(seconds = 0))
    assertTrue(problems.any { it is TimeLogProblem.NonPositiveDuration })
  }

  @Test
  fun `the same id twice is a problem`() {
    val problems = validateLog(listOf(record(id = "x"), record(id = "x", start = at(4, 9))))
    assertTrue(problems.any { it is TimeLogProblem.DuplicateId })
  }

  @Test
  @DisplayName("two stretches of the same person at the same time are reported")
  fun `overlaps of one person are found`() {
    val problems = validateLog(
      listOf(
        record(id = "a", person = "nat", start = at(3, 9), seconds = 7200),
        record(id = "b", person = "nat", start = at(3, 10), seconds = 3600)
      )
    )
    assertTrue(problems.any { it is TimeLogProblem.Overlap })
  }

  @Test
  fun `two people working at the same time is not a problem`() {
    val problems = validateLog(
      listOf(
        record(id = "a", person = "nat", start = at(3, 9), seconds = 7200),
        record(id = "b", person = "chris", start = at(3, 10), seconds = 3600)
      )
    )
    assertTrue(problems.none { it is TimeLogProblem.Overlap })
  }

  @Test
  fun `stretches that merely touch do not overlap`() {
    val problems = validateLog(
      listOf(
        record(id = "a", person = "nat", start = at(3, 9), seconds = 3600),
        record(id = "b", person = "nat", start = at(3, 10), seconds = 3600)
      )
    )
    assertTrue(problems.none { it is TimeLogProblem.Overlap })
  }

  // -------------------------------------------------------------- Merging

  @Test
  fun `merging the same log twice changes nothing`() {
    val log = listOf(record(id = "a"), record(id = "b", start = at(4, 9)))
    assertEquals(log.map { it.id }.sorted(), mergeLogs(log, log).map { it.id }.sorted())
    assertEquals(2, mergeLogs(log, log).size)
  }

  @Test
  fun `on a conflicting id the preferred side wins`() {
    val mine = record(id = "a", description = "what I wrote")
    val theirs = record(id = "a", description = "what the other device wrote")
    assertEquals("what I wrote", mergeLogs(listOf(mine), listOf(theirs)).single().description)
    assertNotEquals(
      mergeLogs(listOf(mine), listOf(theirs)).single().description,
      mergeLogs(listOf(theirs), listOf(mine)).single().description
    )
  }

  @Test
  fun `merging brings both sides together`() {
    val a = listOf(record(id = "a", start = at(3, 9)))
    val b = listOf(record(id = "b", start = at(4, 9)))
    assertEquals(listOf("a", "b"), mergeLogs(a, b).map { it.id })
  }
}
