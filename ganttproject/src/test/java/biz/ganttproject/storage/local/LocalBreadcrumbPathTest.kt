/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.

This file is part of GanttProject, an opensource project management tool.
Licensed under the GNU General Public License, version 3 or later.
*/
package biz.ganttproject.storage.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Which path the local storage opens with.
 *
 * FOUND ON SCREEN: after a write conflict the program offered "Speichern unter", and there the
 * WEBDAV ADDRESS stood in the path field, with a red error message. The path worked (click the
 * server on the left), but the offer led into a dead end first -- and that at exactly the moment
 * when one is trying to rescue one's work.
 */
class LocalBreadcrumbPathTest {

  private val standardordner = File(System.getProperty("java.io.tmpdir"), "gp-test")

  @Test
  fun `eine webdav-adresse landet NICHT im pfadfeld`() {
    val pfad = localBreadcrumbPath("https://server.example/dav/plan.gan", "plan.gan",
      standardordner)
    assertTrue(!pfad.toString().contains("://"), "die Adresse darf kein Pfad werden: $pfad")
    assertEquals(standardordner.toPath().resolve("plan.gan"), pfad)
  }

  @Test
  fun `ein oertliches dokument behaelt seinen pfad`() {
    // Counter-check: otherwise the test above would pass even if the default folder came ALWAYS.
    val datei = File(standardordner, "meinplan.gan")
    val pfad = localBreadcrumbPath(datei.absolutePath, datei.name, standardordner)
    assertEquals(datei.toPath(), pfad)
  }

  @Test
  fun `ohne pfad kommt der standardordner`() {
    assertEquals(standardordner.toPath().resolve("neu.gan"),
      localBreadcrumbPath(null, "neu.gan", standardordner))
    assertEquals(standardordner.toPath().resolve("neu.gan"),
      localBreadcrumbPath("", "neu.gan", standardordner))
  }

  @Test
  fun `ein relativer pfad wird nicht uebernommen`() {
    // Relative means: relative to the working directory of the program. That is not the place
    // where anyone looks for their plan.
    val pfad = localBreadcrumbPath("unterordner/plan.gan", "plan.gan", standardordner)
    assertEquals(standardordner.toPath().resolve("plan.gan"), pfad)
  }
}
