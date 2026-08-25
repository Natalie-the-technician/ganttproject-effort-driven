/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Where records come from other than being typed in: the tracker, and a timer. */
class TimeLogSourcesTest {

  private fun at(day: Int, hour: Int, minute: Int = 0): OffsetDateTime =
    OffsetDateTime.of(2026, 7, day, hour, minute, 0, 0, ZoneOffset.ofHours(2))

  // ------------------------------------------------------- From a tracker

  private fun entry(
    id: Long = 123L,
    seconds: Long = 3600,
    startedAt: OffsetDateTime? = at(3, 9),
    description: String = "Bench measurements"
  ) = TogglTimeEntry(
    id = id,
    description = description,
    start = (startedAt ?: at(3, 0)).toLocalDate(),
    durationSeconds = seconds,
    projectId = null,
    projectName = null,
    tags = emptyList(),
    startedAt = startedAt
  )

  @Test
  @DisplayName("the record id is derived from the entry, so a second import replaces")
  fun `an imported record has a reproducible id`() {
    // This is what makes re-importing a month safe without a separate ledger:
    // the same entry always produces the same record id, and writing a record
    // with a known id replaces it.
    val record = entry(id = 987).toTimeRecord("uid-1")
    assertEquals("toggl:987", record?.id)
    assertEquals(togglRecordId(987), record?.id)
  }

  @Test
  fun `the full timestamp is carried over, not just the date`() {
    val record = entry(startedAt = at(3, 14, 30)).toTimeRecord("uid-1")
    assertEquals(at(3, 14, 30), record?.start)
    assertEquals(TimeSource.IMPORTED, record?.source)
    assertEquals(3600L, record?.durationSeconds)
  }

  @Test
  @DisplayName("without a timestamp and without a zone the entry is refused")
  fun `a bare date alone does not make a record`() {
    // Inventing a time would put a number in a record somebody may later have
    // to vouch for. Refusing hands the decision back to the caller.
    assertNull(entry(startedAt = null).toTimeRecord("uid-1"))
  }

  @Test
  fun `a zone turns a bare date into a placement at the start of the day`() {
    val record = entry(startedAt = null)
      .toTimeRecord("uid-1", fallbackZone = ZoneId.of("Europe/Berlin"))
    assertNotNull(record)
    assertEquals(LocalDate.of(2026, 7, 3), record!!.dateIn(ZoneId.of("Europe/Berlin")))
    assertEquals(0, record.start.hour, "start of day, and that is a placement")
  }

  @Test
  fun `importing the same entry twice leaves one record`() {
    val doc = GanttDocument.parse(
      checkNotNull(javaClass.getResourceAsStream("/HouseBuildingSample.gan"))
        .readBytes().toString(Charsets.UTF_8)
    )
    val uid = "11366eb6d1e34238866b347d1f40062d"

    doc.addTimeRecord(entry(id = 5, seconds = 3600).toTimeRecord(uid)!!)
    doc.addTimeRecord(entry(id = 5, seconds = 3600).toTimeRecord(uid)!!)

    assertEquals(1, doc.timeLog().records.size)
    assertEquals(3600L, doc.timeLog().records.single().durationSeconds)
  }

  @Test
  fun `the parser keeps the timestamp it used to throw away`() {
    val client = TogglClient(object : HttpBackend {
      override fun get(url: String, headers: Map<String, String>) = HttpResponse(200, "[]")
    }, "token")
    val body = """
      [{"id": 42, "duration": 1800, "start": "2026-07-03T14:30:00+02:00",
        "description": "Soldered the header", "tags": []}]
    """.trimIndent()

    val entries = (client.parseTimeEntries(body, emptyMap()) as TogglResult.Success).value
    assertEquals(1, entries.size)
    assertEquals(LocalDate.of(2026, 7, 3), entries.single().start)
    assertEquals(at(3, 14, 30), entries.single().startedAt)
  }

  @Test
  fun `an entry with only a date parses without a timestamp`() {
    assertNull(parseTogglInstant("2026-07-03"))
    assertEquals(LocalDate.of(2026, 7, 3), parseTogglDate("2026-07-03"))
  }

  // ---------------------------------------------------------- From a timer

  @Test
  fun `elapsed time is a subtraction, not a counter`() {
    val timer = RunningTimer("uid-1", at(3, 9))
    assertEquals(3600L, timer.elapsedSecondsAt(at(3, 10)))
    assertEquals(5400L, timer.elapsedSecondsAt(at(3, 10, 30)))
  }

  @Test
  fun `stopping produces a record of what was measured`() {
    val timer = RunningTimer("uid-1", at(3, 9), "Bench measurements", "nat")
    val record = timer.stop(at(3, 10, 30), "rec-1")

    assertEquals("rec-1", record?.id)
    assertEquals("uid-1", record?.taskUid)
    assertEquals(at(3, 9), record?.start)
    assertEquals(5400L, record?.durationSeconds)
    assertEquals(TimeSource.TIMER, record?.source)
    assertEquals("nat", record?.person)
    assertEquals(at(3, 10, 30), record?.createdAt, "created when it was stopped")
  }

  @Test
  @DisplayName("a timer stopped immediately writes nothing")
  fun `nothing measurable means no record`() {
    val timer = RunningTimer("uid-1", at(3, 9))
    assertNull(timer.stop(at(3, 9), "rec-1"))
  }

  @Test
  @DisplayName("a clock that moved backwards does not produce a negative record")
  fun `a backwards clock is refused`() {
    // Happens for real: a time-zone database update, a manual correction, a
    // network time sync. A negative duration must not reach the log.
    val timer = RunningTimer("uid-1", at(3, 10))
    assertEquals(0L, timer.elapsedSecondsAt(at(3, 9)))
    assertNull(timer.stop(at(3, 9), "rec-1"))
  }

  @Test
  fun `a long-running timer can be flagged, but the threshold is the caller's`() {
    val timer = RunningTimer("uid-1", at(3, 9))
    assertFalse(timer.looksForgotten(at(3, 17), Duration.ofHours(12)))
    assertTrue(timer.looksForgotten(at(4, 2), Duration.ofHours(12)))
  }

  @Test
  fun `a timer survives being written down and read back`() {
    // What the app needs when the process is killed: the timer is a value, and
    // the value goes into a preference as one line.
    val timer = RunningTimer("uid-1", at(3, 9), "pipe | and \\ backslash", "nat")
    val restored = RunningTimer.decode(timer.encode())

    assertEquals(timer, restored)
    assertEquals(1, timer.encode().lines().size)
  }

  @Test
  fun `unreadable timer state reads as no timer, not as a crash`() {
    assertNull(RunningTimer.decode(null))
    assertNull(RunningTimer.decode(""))
    assertNull(RunningTimer.decode("nonsense"))
    assertNull(RunningTimer.decode("uid-1|not-a-timestamp"))
    assertNull(RunningTimer.decode("|2026-07-03T09:00+02:00"), "no task, no timer")
  }

  @Test
  fun `a timer without a description or person still restores`() {
    val timer = RunningTimer("uid-1", at(3, 9))
    val restored = RunningTimer.decode(timer.encode())
    assertEquals("", restored?.description)
    assertNull(restored?.person)
  }
}
