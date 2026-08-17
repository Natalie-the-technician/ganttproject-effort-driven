/*
Copyright 2026

NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.

This file is part of GanttProject, an opensource project management tool.
Licensed under the GNU General Public License, version 3 or later.
*/
package biz.ganttproject.storage.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Mit welchem Pfad die oertliche Ablage aufmacht.
 *
 * AM BILDSCHIRM GEFUNDEN: nach einem Schreibkonflikt bot das Programm "Speichern unter" an, und
 * dort stand die WEBDAV-ADRESSE im Pfadfeld, mit roter Fehlermeldung. Der Weg funktionierte
 * (links den Server anklicken), aber das Angebot fuehrte erst einmal in eine Sackgasse -- und das
 * ausgerechnet in dem Moment, in dem man gerade seine Arbeit retten will.
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
    // Gegenprobe: sonst waere der Test oben auch erfuellt, wenn IMMER der Standardordner kaeme.
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
    // Relativ heisst: bezogen auf das Arbeitsverzeichnis des Programms. Das ist nicht der Ort,
    // an dem jemand seinen Plan sucht.
    val pfad = localBreadcrumbPath("unterordner/plan.gan", "plan.gan", standardordner)
    assertEquals(standardordner.toPath().resolve("plan.gan"), pfad)
  }
}
