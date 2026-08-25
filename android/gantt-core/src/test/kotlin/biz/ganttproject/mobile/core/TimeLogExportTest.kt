/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.YearMonth
import java.time.ZoneOffset

class TimeLogExportTest {

  private fun at(day: Int, hour: Int, minute: Int = 0, offsetHours: Int = 2): OffsetDateTime =
    OffsetDateTime.of(2026, 7, day, hour, minute, 0, 0, ZoneOffset.ofHours(offsetHours))

  private fun record(
    id: String = "r1",
    taskUid: String = "abc123",
    start: OffsetDateTime = at(3, 9),
    seconds: Long = 3600,
    description: String = "Prototype board bring-up",
    person: String? = null
  ) = TimeRecord(
    id = id,
    taskUid = taskUid,
    start = start,
    durationSeconds = seconds,
    description = description,
    person = person
  )

  /** Reads the export back with this module's own JSON parser. */
  private fun entriesOf(json: String): List<Map<String, JsonValue>> =
    Json.parse(json).asObject()?.get("data").asArray()?.mapNotNull { it.asObject() }.orEmpty()

  // ----------------------------------------------------------- The format

  @Test
  @DisplayName("dur is in milliseconds, not seconds")
  fun `duration is milliseconds`() {
    // Readers of this shape divide by 1000. Writing seconds yields a report
    // with a thousandth of the hours -- small but not impossible numbers, so
    // no plausibility check downstream would catch it. This is the most likely
    // serious defect of the whole export.
    val json = TimeLogExport.toTogglV2Json(listOf(record(seconds = 3600)))
    assertEquals(3_600_000L, entriesOf(json).single()["dur"].asLong())

    val quarter = TimeLogExport.toTogglV2Json(listOf(record(seconds = 900)))
    assertEquals(900_000L, entriesOf(quarter).single()["dur"].asLong())
  }

  @Test
  fun `what comes out is valid JSON`() {
    // Checked with this module's own reader rather than by eye: a writer that
    // is never parsed produces text that only looks like JSON.
    val json = TimeLogExport.toTogglV2Json(
      listOf(record(id = "a"), record(id = "b", start = at(4, 9))),
      mapOf("abc123" to listOf("V203"))
    )
    val root = Json.parse(json).asObject()
    assertEquals(2L, root?.get("total_count").asLong())
    assertEquals(2, entriesOf(json).size)
  }

  @Test
  @DisplayName("total_count is always written, even for an empty log")
  fun `completeness is always stated`() {
    // Without it a reader can only take the file's word that nothing was cut
    // off -- fue-report says so explicitly when the field is missing.
    val json = TimeLogExport.toTogglV2Json(emptyList())
    assertEquals(0L, Json.parse(json).asObject()?.get("total_count").asLong())
    assertEquals(0, entriesOf(json).size)
  }

  @Test
  fun `the fields fue-report needs are all there`() {
    val json = TimeLogExport.toTogglV2Json(
      listOf(record(id = "x", seconds = 5400, description = "Measured the rail")),
      mapOf("abc123" to listOf("V203"))
    )
    val entry = entriesOf(json).single()
    assertEquals("x", entry["id"].asString())
    assertEquals("Measured the rail", entry["description"].asString())
    assertEquals(at(3, 9).toString(), entry["start"].asString())
    assertEquals(at(3, 10, 30).toString(), entry["end"].asString())
    assertEquals(5_400_000L, entry["dur"].asLong())
    assertEquals(listOf("V203"), entry["tags"].asArray()?.map { it.asString() })
  }

  // ------------------------------------------------------------- The tags

  @Test
  fun `tags come from the map and mean nothing here`() {
    val records = listOf(record(id = "a", taskUid = "t1"), record(id = "b", taskUid = "t2", start = at(4, 9)))
    val json = TimeLogExport.toTogglV2Json(records, mapOf("t1" to listOf("V203", "extra")))
    val entries = entriesOf(json)
    assertEquals(listOf("V203", "extra"), entries[0]["tags"].asArray()?.map { it.asString() })
    assertEquals(emptyList<String>(), entries[1]["tags"].asArray()?.map { it.asString() })
  }

  @Test
  fun `a person is written when known and left out when not`() {
    val withPerson = entriesOf(TimeLogExport.toTogglV2Json(listOf(record(person = "nat"))))
    assertEquals("nat", withPerson.single()["user"].asString())

    val without = entriesOf(TimeLogExport.toTogglV2Json(listOf(record(person = null))))
    assertNull(without.single()["user"])
  }

  // -------------------------------------------------------------- Escaping

  @Test
  fun `free text does not break the file`() {
    val nasty = "quote\" backslash\\ newline\n tab\t brace}"
    val json = TimeLogExport.toTogglV2Json(listOf(record(description = nasty)))
    assertEquals(nasty, entriesOf(json).single()["description"].asString())
  }

  @Test
  fun `a newline in the text is escaped, not passed through`() {
    // A raw newline inside a JSON string is invalid JSON, and a description is
    // free text that will eventually contain one.
    val json = TimeLogExport.toTogglV2Json(listOf(record(description = "ab\nc")))
    assertTrue(json.contains("""ab\nc"""), "the newline must appear escaped")
    assertEquals("ab\nc", entriesOf(json).single()["description"].asString())
  }

  @Test
  fun `an exotic control character becomes a unicode escape`() {
    // Below 0x20 there is no short escape for most characters, so they have to
    // go out as \uXXXX. A bell can arrive through copy and paste from a
    // terminal, and one raw byte would make the whole file unreadable.
    val json = TimeLogExport.toTogglV2Json(listOf(record(description = "a\u0007b")))
    assertTrue(json.contains("""\u0007"""), "must be written as a unicode escape")
    assertFalse(json.contains('\u0007'), "and must not appear raw")
    assertEquals("a\u0007b", entriesOf(json).single()["description"].asString())
  }

  // ------------------------------------------------------------ Stability

  @Test
  fun `the same log always produces the same file`() {
    val a = record(id = "a", start = at(3, 9))
    val b = record(id = "b", start = at(4, 9))
    val c = record(id = "c", start = at(3, 9))
    assertEquals(
      TimeLogExport.toTogglV2Json(listOf(a, b, c)),
      TimeLogExport.toTogglV2Json(listOf(c, b, a))
    )
  }

  @Test
  @DisplayName("nothing is rounded on the way out")
  fun `odd seconds survive the export`() {
    val json = TimeLogExport.toTogglV2Json(listOf(record(seconds = 1)))
    assertEquals(1_000L, entriesOf(json).single()["dur"].asLong())
  }

  // -------------------------------------------------------------- Period

  @Test
  @DisplayName("the period is cut in the zone it states, not the device's")
  fun `period selection respects the zone`() {
    // The record is at 23:30 on 31 July at +02:00 -- still July in Berlin,
    // already August in Tokyo. A month boundary is exactly where a reporting
    // period ends, so getting this wrong moves hours into the wrong report.
    val late = record(id = "late", start = at(31, 23, 30))
    val early = record(id = "early", start = at(3, 9))

    val july = ExportPeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), ZoneId.of("Europe/Berlin"))
    assertEquals(listOf("early", "late"), recordsInPeriod(listOf(early, late), july).map { it.id }.sorted())

    val julyTokyo = ExportPeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), ZoneId.of("Asia/Tokyo"))
    assertEquals(listOf("early"), recordsInPeriod(listOf(early, late), julyTokyo).map { it.id })
  }

  @Test
  fun `both ends of the period are included`() {
    val first = record(id = "first", start = at(1, 0, 30))
    val last = record(id = "last", start = at(31, 22, 0))
    val july = ExportPeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), ZoneId.of("Europe/Berlin"))
    assertEquals(2, recordsInPeriod(listOf(first, last), july).size)
  }

  @Test
  @DisplayName("a stretch across midnight counts on the day it STARTED")
  fun `the month boundary follows the start`() {
    // 22:00 on the last day of July until 01:00 on the first of August. The
    // whole stretch counts as July, because a record is attributed to the day
    // it began on.
    //
    // Not arbitrary: it is the same rule the reporting tool uses, where the
    // date of an entry is the local date of its start. Two tools splitting a
    // night shift differently is exactly how two monthly sheets stop adding
    // up to the same number, so this is pinned rather than left to chance.
    val overnight = record(id = "night", start = at(31, 22), seconds = 3 * 3600)
    val july = ExportPeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), ZoneId.of("Europe/Berlin"))
    val august = ExportPeriod(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), ZoneId.of("Europe/Berlin"))

    assertEquals(listOf("night"), recordsInPeriod(listOf(overnight), july).map { it.id })
    assertTrue(recordsInPeriod(listOf(overnight), august).isEmpty())
    assertEquals(3 * 3600 * 1000L, entriesOf(TimeLogExport.toTogglV2Json(listOf(overnight)))
      .single()["dur"].asLong(), "and the whole stretch goes with it")
  }

  @Test
  @DisplayName("a record booked late still belongs to the month it was worked in")
  fun `when it was recorded does not move it`() {
    // Booked on 2 August for work done on 31 July: createdAt is August, start
    // is July, and July is where it counts. Which also means a month can still
    // grow after it has been exported -- see monthsWithRecords, and see the
    // note in the report about re-exporting a month that was already frozen.
    val late = TimeRecord(
      id = "late",
      taskUid = "t1",
      start = at(31, 9),
      durationSeconds = 3600,
      description = "forgot to book this yesterday",
      createdAt = OffsetDateTime.of(2026, 8, 2, 10, 0, 0, 0, ZoneOffset.ofHours(2))
    )
    val july = ExportPeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), ZoneId.of("Europe/Berlin"))
    assertEquals(listOf("late"), recordsInPeriod(listOf(late), july).map { it.id })
  }

  // -------------------------------------------------------- Which months

  @Test
  fun `the months that have records are offered newest first`() {
    val records = listOf(
      record(id = "a", start = at(3, 9)),
      record(id = "b", start = at(4, 9)),
      record(id = "c", start = at(3, 9).plusMonths(1)),
      record(id = "d", start = at(3, 9).minusMonths(2))
    )
    assertEquals(
      listOf(YearMonth.of(2026, 8), YearMonth.of(2026, 7), YearMonth.of(2026, 5)),
      monthsWithRecords(records, ZoneId.of("Europe/Berlin"))
    )
  }

  @Test
  @DisplayName("a month with records is reachable however long ago it was")
  fun `no month becomes unreachable`() {
    // The reason this exists: assuming "the previous month" is right for the
    // ordinary rhythm and wrong the moment one is missed. A month nobody can
    // select is a month whose hours cannot be shown to anybody.
    val old = record(id = "old", start = at(3, 9).minusYears(1))
    val months = monthsWithRecords(listOf(old), ZoneId.of("Europe/Berlin"))
    assertEquals(1, months.size)
    val period = monthPeriod(months.single(), ZoneId.of("Europe/Berlin"))
    assertEquals(listOf("old"), recordsInPeriod(listOf(old), period).map { it.id })
  }

  @Test
  fun `an empty log offers no months rather than a wrong one`() {
    assertTrue(monthsWithRecords(emptyList(), ZoneOffset.UTC).isEmpty())
  }

  @Test
  fun `a period that ends before it begins is refused`() {
    assertThrows<IllegalArgumentException> {
      ExportPeriod(LocalDate.of(2026, 7, 31), LocalDate.of(2026, 7, 1), ZoneOffset.UTC)
    }
  }

  @Test
  fun `exporting a period exports only that period`() {
    val records = listOf(record(id = "in", start = at(3, 9)), record(id = "out", start = at(3, 9).plusMonths(1)))
    val july = ExportPeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), ZoneId.of("Europe/Berlin"))
    val json = TimeLogExport.toTogglV2Json(recordsInPeriod(records, july))
    assertEquals(1L, Json.parse(json).asObject()?.get("total_count").asLong())
    assertEquals("in", entriesOf(json).single()["id"].asString())
    assertTrue(!json.contains("\"out\""))
  }
}
