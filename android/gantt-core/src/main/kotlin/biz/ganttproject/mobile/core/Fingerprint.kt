/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

/**
 * A cheap content fingerprint, used to notice that a file changed underneath
 * us between opening it and saving it.
 *
 * ## Why this exists
 *
 * A project file that lives in a synced folder has more than one writer: the
 * phone, the desktop, and the sync client itself. Without a check, saving
 * means "write whatever I have over whatever is there", and an edit made on
 * the other machine disappears without a trace. With it, the app can stop and
 * ask.
 *
 * ## Why not a timestamp
 *
 * Sync clients rewrite modification times as they please — a download can
 * carry the *original* time, and some providers report the time of the local
 * cache rather than the file. Content is the only thing that actually says
 * whether something changed.
 *
 * ## Why not a cryptographic hash
 *
 * Nothing here defends against an adversary constructing a collision; the
 * question is only "did this file change by accident or by another editor".
 * FNV-1a over the bytes plus the length answers that with a chance of a false
 * match somewhere around one in eighteen quintillion, needs no platform
 * crypto API, and stays pure Kotlin — which keeps this module portable if the
 * core is ever lifted to Kotlin Multiplatform.
 */
fun contentFingerprint(bytes: ByteArray): String {
  var hash = 0xcbf29ce484222325UL
  for (byte in bytes) {
    hash = hash xor (byte.toUByte().toULong())
    hash *= 0x100000001b3UL
  }
  // The length is mixed in separately so that two files differing only by
  // trailing padding cannot land on the same value.
  return "${bytes.size}-${hash.toString(16)}"
}

/** What the file on disk looks like compared with what we last saw. */
enum class FileChangeState {
  /** Unchanged since we read it — saving is safe. */
  UNCHANGED,

  /** Changed by something else since we read it — saving would overwrite it. */
  CHANGED_ELSEWHERE,

  /** The file has gone, or could not be read to compare. */
  UNKNOWN
}

/**
 * Compares the fingerprint taken when the project was opened or last saved
 * with the file as it is now.
 *
 * @param expected fingerprint recorded when the file was last read or written
 * @param actual fingerprint of the file as it is right now, or null if it
 *   could not be read
 */
fun compareFingerprint(expected: String?, actual: String?): FileChangeState = when {
  expected == null || actual == null -> FileChangeState.UNKNOWN
  expected == actual -> FileChangeState.UNCHANGED
  else -> FileChangeState.CHANGED_ELSEWHERE
}
