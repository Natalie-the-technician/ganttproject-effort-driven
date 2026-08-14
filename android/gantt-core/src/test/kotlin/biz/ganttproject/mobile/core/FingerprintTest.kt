/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The fingerprint decides whether the app is allowed to overwrite a file, so
 * a false "unchanged" costs somebody their work. These tests pin down the
 * cases that would produce one.
 */
class FingerprintTest {

  @Test
  fun `the same bytes give the same fingerprint`() {
    assertEquals(
      contentFingerprint("<project/>".toByteArray()),
      contentFingerprint("<project/>".toByteArray())
    )
  }

  @Test
  fun `a single changed character changes the fingerprint`() {
    assertNotEquals(
      contentFingerprint("""<task complete="50"/>""".toByteArray()),
      contentFingerprint("""<task complete="51"/>""".toByteArray())
    )
  }

  @Test
  @DisplayName("a change deep inside a large file is still noticed")
  fun `a late change is noticed`() {
    val big = "<task/>".repeat(20_000)
    assertNotEquals(
      contentFingerprint((big + "<a/>").toByteArray()),
      contentFingerprint((big + "<b/>").toByteArray())
    )
  }

  @Test
  fun `swapped bytes change the fingerprint`() {
    // A hash that only summed bytes would call these equal, and then a
    // reordering edit would be waved through as "unchanged".
    assertNotEquals(
      contentFingerprint(byteArrayOf(1, 2, 3)),
      contentFingerprint(byteArrayOf(3, 2, 1))
    )
  }

  @Test
  fun `added trailing content changes the fingerprint`() {
    assertNotEquals(
      contentFingerprint("<project/>".toByteArray()),
      contentFingerprint("<project/>\n".toByteArray())
    )
  }

  @Test
  fun `high bytes do not collapse together`() {
    // Signed-byte handling done wrong makes every byte above 127 look alike.
    assertNotEquals(
      contentFingerprint(byteArrayOf(-1)),
      contentFingerprint(byteArrayOf(-2))
    )
  }

  @Test
  fun `an empty file has a fingerprint of its own`() {
    assertNotEquals(contentFingerprint(ByteArray(0)), contentFingerprint(byteArrayOf(0)))
  }

  @Test
  fun `comparison reports the three states`() {
    assertEquals(FileChangeState.UNCHANGED, compareFingerprint("a", "a"))
    assertEquals(FileChangeState.CHANGED_ELSEWHERE, compareFingerprint("a", "b"))
    assertEquals(FileChangeState.UNKNOWN, compareFingerprint("a", null))
    assertEquals(FileChangeState.UNKNOWN, compareFingerprint(null, "b"))
  }

  @Test
  fun `an unreadable file is UNKNOWN, never UNCHANGED`() {
    // UNKNOWN must not be treated as permission to overwrite; the store only
    // blocks on CHANGED_ELSEWHERE, so this is what keeps a vanished file from
    // silently counting as "same as before".
    assertNotEquals(FileChangeState.UNCHANGED, compareFingerprint("a", null))
  }
}
