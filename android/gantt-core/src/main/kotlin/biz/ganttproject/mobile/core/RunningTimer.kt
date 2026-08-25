/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/*
 * A timer that is currently running.
 *
 * Only the arithmetic and the encoding live here. Everything Android — the
 * foreground service, the notification, surviving process death — is the app's
 * business, and none of it can be tested without a device. What can be tested
 * is the part that decides how many seconds get written down, so that part is
 * here and has no clock in it: `now` is always an argument.
 */

/**
 * The state of a timer between starting and stopping.
 *
 * Held as a value rather than a running counter on purpose. A counter has to
 * keep ticking to stay right, and on a phone it will not: the process is killed,
 * the device sleeps, the app is swapped out. A start timestamp survives all of
 * that, and the elapsed time is a subtraction whenever somebody asks.
 */
data class RunningTimer(
  val taskUid: String,
  val startedAt: OffsetDateTime,
  val description: String = "",
  val person: String? = null
) {
  /**
   * Seconds elapsed at [now], never negative.
   *
   * A negative result means the clock moved backwards — a time-zone database
   * update, a manual correction, a network time sync. Clamping to zero rather
   * than reporting a negative duration keeps that out of the arithmetic;
   * [stop] is where it turns into a refusal.
   */
  fun elapsedSecondsAt(now: OffsetDateTime): Long =
    Duration.between(startedAt, now).seconds.coerceAtLeast(0L)

  /**
   * Turns the timer into a record, or `null` when nothing measurable elapsed.
   *
   * Null rather than a zero-length record: a stretch of work that took no time
   * is a mis-tap, and writing it down would put a row in a report that nobody
   * can explain later.
   *
   * @param recordId supplied by the caller, like every other record id, so the
   *   result is reproducible and a retry does not create a second row.
   */
  fun stop(now: OffsetDateTime, recordId: String): TimeRecord? {
    val seconds = elapsedSecondsAt(now)
    if (seconds <= 0L) return null
    return TimeRecord(
      id = recordId,
      taskUid = taskUid,
      start = startedAt,
      durationSeconds = seconds,
      description = description,
      person = person,
      source = TimeSource.TIMER,
      createdAt = now
    )
  }

  /**
   * Whether the timer has been running longer than [threshold].
   *
   * The threshold is a parameter with no default, deliberately. "Too long" is
   * a judgement — a night shift and a forgotten timer look identical from here,
   * and the funding scheme, the employer or the person decides which it is.
   * The module supplies the measurement, not the verdict.
   */
  fun looksForgotten(now: OffsetDateTime, threshold: Duration): Boolean =
    elapsedSecondsAt(now) > threshold.seconds

  /**
   * One line, safe to keep in a preference or a file.
   *
   * Same separators and escaping as the log, for the same reason: the text may
   * end up somewhere that normalises whitespace, and one encoding to get right
   * beats two that drift apart.
   */
  fun encode(): String = TimeLogCodec.encodeTimer(this)

  companion object {
    fun decode(text: String?): RunningTimer? = TimeLogCodec.decodeTimer(text)
  }
}

// The timer's encoding sits next to the log's so the two cannot diverge.
private const val TIMER_FIELD = '|'

internal fun TimeLogCodec.encodeTimer(timer: RunningTimer): String =
  listOf(
    timer.taskUid,
    timer.startedAt.toString(),
    timer.person.orEmpty(),
    timer.description
  ).joinToString(TIMER_FIELD.toString()) { escapeForTimer(it) }

internal fun TimeLogCodec.decodeTimer(text: String?): RunningTimer? {
  if (text.isNullOrBlank()) return null
  val fields = text.split(TIMER_FIELD).map { unescapeForTimer(it) }
  if (fields.size < 2) return null
  val taskUid = fields[0].ifBlank { return null }
  val startedAt = try {
    OffsetDateTime.parse(fields[1].trim())
  } catch (e: DateTimeParseException) {
    return null
  }
  return RunningTimer(
    taskUid = taskUid,
    startedAt = startedAt,
    description = fields.getOrNull(3).orEmpty(),
    person = fields.getOrNull(2)?.takeIf { it.isNotBlank() }
  )
}

private fun escapeForTimer(text: String): String = buildString(text.length) {
  for (ch in text) {
    when (ch) {
      '\\' -> append("\\\\")
      '\t' -> append("\\t")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      TIMER_FIELD -> append("\\p")
      else -> append(ch)
    }
  }
}

private fun unescapeForTimer(text: String): String = buildString(text.length) {
  var i = 0
  while (i < text.length) {
    val ch = text[i]
    if (ch != '\\' || i == text.lastIndex) {
      append(ch); i++; continue
    }
    when (val next = text[i + 1]) {
      '\\' -> append('\\')
      't' -> append('\t')
      'n' -> append('\n')
      'r' -> append('\r')
      'p' -> append(TIMER_FIELD)
      else -> { append(ch); append(next) }
    }
    i += 2
  }
}
