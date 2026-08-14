/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

/**
 * How much of the project this device is allowed to change.
 *
 * ## Why this exists
 *
 * A `.gan` file in a synced folder has no arbiter. Two devices can write it
 * and the last writer wins — silently. The app detects that after the fact
 * (see [contentFingerprint]) and refuses to overwrite a file that moved
 * underneath it, but detection is not prevention: if the desktop saves over a
 * phone edit that never finished syncing, the phone's work is gone and no
 * check on the phone can bring it back.
 *
 * The reliable way to not lose an edit is to not make it. This setting is that
 * choice, made once and up front, rather than a judgement call at every tap.
 *
 * ## Why three levels and not a checkbox
 *
 * The two editing surfaces carry different risk, so collapsing them into one
 * switch would force the wrong trade either way:
 *
 *  - In the app the user sees the project, gets the conflict dialog, and can
 *    choose to keep both versions. An edit here is deliberate and supervised.
 *  - In the widget a single thumb tap writes the file from the home screen,
 *    with no view of the plan and no room to explain a conflict. That is the
 *    convenience the widget exists for, and also what makes it the surface
 *    most likely to produce a change nobody remembers making.
 *
 * The levels are ordered, so "widget but not app" — which would be nonsense —
 * cannot be expressed at all.
 */
enum class EditScope(
  /** Stable key for storage. Never derive this from [name] or [ordinal]. */
  val key: String,
  /**
   * How permissive this level is. Explicit rather than [ordinal] so that
   * reordering the constants cannot silently change what counts as widening.
   */
  val rank: Int
) {
  /** Look, do not touch. Nothing on this device writes the project file. */
  READ_ONLY("read_only", 0),

  /** The app may write; the widget stays a display. */
  APP_ONLY("app_only", 1),

  /** Everything, including one-tap edits from the home screen. */
  APP_AND_WIDGET("app_and_widget", 2);

  val allowsAppEdits: Boolean get() = rank >= APP_ONLY.rank

  val allowsWidgetEdits: Boolean get() = rank >= APP_AND_WIDGET.rank

  companion object {
    /**
     * What a fresh install gets.
     *
     * The app, not the widget: editing in the app is what the app is for, and
     * it is the supervised surface. The widget — newer, blinder, and the one
     * that writes without the project in front of you — has to be turned on
     * deliberately, which is what puts [needsUnmanagedStorageWarning] in front
     * of the user exactly once.
     */
    val DEFAULT = APP_ONLY

    /** Tolerates null and unknown keys: a preference file outlives a release. */
    fun fromKey(key: String?): EditScope = entries.firstOrNull { it.key == key } ?: DEFAULT
  }
}

/**
 * What the storage behind the project file guarantees about concurrent writes.
 *
 * This is the difference between a folder that syncs and a service that
 * arbitrates, and it is the whole reason the warning exists.
 */
enum class SyncGuarantee {
  /**
   * A plain file, however it gets between devices — OneDrive, Drive, a cable.
   * Nothing decides which of two concurrent writes is the real one, so the
   * loser is simply lost.
   */
  UNMANAGED_FILE,

  /**
   * A server that versions writes and rejects one made against a stale
   * version, so a conflict surfaces as a refusal instead of a silent loss.
   *
   * Nothing produces this value yet — the app talks to files, not to a
   * server. It is here because it names the condition the warning is really
   * about: a warning worded around "no account connected" would have to be
   * rewritten the day any versioned backend appears, whereas this one simply
   * stops firing.
   */
  MANAGED_SERVER
}

/** Whether [to] permits strictly more than [from]. */
fun isWidening(from: EditScope, to: EditScope): Boolean = to.rank > from.rank

/**
 * Whether turning editing up from [from] to [to] should be confirmed first.
 *
 * Only on the way up, and only when nothing arbitrates concurrent writes.
 * Turning editing *down* is always safe and must never be nagged about —
 * a warning on the cautious choice teaches people to dismiss warnings.
 */
fun needsUnmanagedStorageWarning(
  from: EditScope,
  to: EditScope,
  sync: SyncGuarantee
): Boolean = isWidening(from, to) && sync == SyncGuarantee.UNMANAGED_FILE
