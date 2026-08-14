/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * This policy is the only thing standing between a home-screen tap and a
 * write into a synced file, so every branch of it is pinned down here —
 * including the ones that must *not* fire, because a warning that appears on
 * the safe choice is a warning nobody reads.
 */
class EditPolicyTest {

  // -------------------------------------------------------- What each level permits

  @Test
  fun `read-only permits nothing`() {
    assertFalse(EditScope.READ_ONLY.allowsAppEdits)
    assertFalse(EditScope.READ_ONLY.allowsWidgetEdits)
  }

  @Test
  fun `app-only permits the app but not the widget`() {
    assertTrue(EditScope.APP_ONLY.allowsAppEdits)
    assertFalse(EditScope.APP_ONLY.allowsWidgetEdits)
  }

  @Test
  fun `the top level permits both`() {
    assertTrue(EditScope.APP_AND_WIDGET.allowsAppEdits)
    assertTrue(EditScope.APP_AND_WIDGET.allowsWidgetEdits)
  }

  @Test
  @DisplayName("widget editing always implies app editing")
  fun `no level allows the widget while forbidding the app`() {
    // The nonsense state the ordering exists to make unrepresentable.
    EditScope.entries.forEach { scope ->
      if (scope.allowsWidgetEdits) assertTrue(scope.allowsAppEdits, "$scope")
    }
  }

  // ------------------------------------------------------------------- Ordering

  @Test
  fun `ranks are strictly ascending in declaration order`() {
    // Guards the assumption the widening check rests on. If someone inserts a
    // level in the middle without giving it a rank between its neighbours,
    // this fails before the warning logic starts lying.
    val ranks = EditScope.entries.map { it.rank }
    assertEquals(ranks.sorted(), ranks, "declaration order must match rank order")
    assertEquals(ranks.distinct().size, ranks.size, "ranks must be unique")
  }

  @Test
  fun `widening is only upward`() {
    assertTrue(isWidening(EditScope.READ_ONLY, EditScope.APP_ONLY))
    assertTrue(isWidening(EditScope.READ_ONLY, EditScope.APP_AND_WIDGET))
    assertTrue(isWidening(EditScope.APP_ONLY, EditScope.APP_AND_WIDGET))
  }

  @Test
  fun `narrowing and staying put are not widening`() {
    assertFalse(isWidening(EditScope.APP_AND_WIDGET, EditScope.APP_ONLY))
    assertFalse(isWidening(EditScope.APP_AND_WIDGET, EditScope.READ_ONLY))
    assertFalse(isWidening(EditScope.APP_ONLY, EditScope.READ_ONLY))
    EditScope.entries.forEach { assertFalse(isWidening(it, it), "$it to itself") }
  }

  // -------------------------------------------------------------------- Warning

  @Test
  fun `turning editing up on an unmanaged file warns`() {
    assertTrue(
      needsUnmanagedStorageWarning(
        EditScope.READ_ONLY, EditScope.APP_ONLY, SyncGuarantee.UNMANAGED_FILE
      )
    )
    assertTrue(
      needsUnmanagedStorageWarning(
        EditScope.APP_ONLY, EditScope.APP_AND_WIDGET, SyncGuarantee.UNMANAGED_FILE
      )
    )
  }

  @Test
  @DisplayName("a versioned backend removes the reason to warn")
  fun `managed storage never warns`() {
    EditScope.entries.forEach { from ->
      EditScope.entries.forEach { to ->
        assertFalse(
          needsUnmanagedStorageWarning(from, to, SyncGuarantee.MANAGED_SERVER),
          "$from -> $to"
        )
      }
    }
  }

  @Test
  @DisplayName("turning editing down is never warned about")
  fun `narrowing never warns`() {
    EditScope.entries.forEach { from ->
      EditScope.entries.forEach { to ->
        if (to.rank <= from.rank) {
          assertFalse(
            needsUnmanagedStorageWarning(from, to, SyncGuarantee.UNMANAGED_FILE),
            "$from -> $to"
          )
        }
      }
    }
  }

  // -------------------------------------------------------------------- Storage

  @Test
  fun `keys survive a round trip`() {
    EditScope.entries.forEach { assertEquals(it, EditScope.fromKey(it.key)) }
  }

  @Test
  @DisplayName("an unreadable stored value falls back rather than throwing")
  fun `unknown and missing keys fall back to the default`() {
    // A preference file outlives a release; a renamed constant must not crash
    // the app or, worse, silently land on a more permissive level.
    assertEquals(EditScope.DEFAULT, EditScope.fromKey(null))
    assertEquals(EditScope.DEFAULT, EditScope.fromKey(""))
    assertEquals(EditScope.DEFAULT, EditScope.fromKey("everything"))
    assertEquals(EditScope.DEFAULT, EditScope.fromKey("APP_AND_WIDGET"))
  }

  @Test
  @DisplayName("the default does not include the widget")
  fun `the default leaves the blind surface switched off`() {
    // The point of the default: the widget writes without the project in
    // front of you, so it has to be asked for.
    assertFalse(EditScope.DEFAULT.allowsWidgetEdits)
  }

  @Test
  fun `keys are distinct and stable`() {
    val keys = EditScope.entries.map { it.key }
    assertEquals(keys.distinct().size, keys.size)
    // Pinned literally: changing one silently resets every installed copy of
    // the app to the default.
    assertEquals(listOf("read_only", "app_only", "app_and_widget"), keys)
  }
}
