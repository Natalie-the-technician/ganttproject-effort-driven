/*
Copyright 2026

NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.

This file is part of GanttProject, an opensource project management tool.
Licensed under the GNU General Public License, version 3 or later.
*/
package net.sourceforge.ganttproject.fork

import biz.ganttproject.core.time.CalendarFactory
import net.sourceforge.ganttproject.GanttPreviousState
import net.sourceforge.ganttproject.GanttProjectImpl
import net.sourceforge.ganttproject.io.GanttXMLSaver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * Haelt ein Basisplan das, was er verspricht?
 *
 * NATALIES FRAGE, am 17.08.2026: "Teste danach ob das mit dem basisplan so klappt auch mit der
 * klassischen gant ansicht."
 *
 * Was hier geprueft wird, ist die Haelfte, die ohne Bildschirm messbar ist: dass der Basisplan
 * ueberhaupt in die Projektdatei kommt und dass er die richtigen Termine haelt -- naemlich die
 * VOR dem Verteilen. Ein Basisplan, der nach dem Verschieben aufgenommen wird, sieht genauso aus
 * und ist wertlos.
 *
 * Was hier NICHT geprueft wird und auch nicht geprueft werden kann: ob das Diagramm den zweiten
 * Balken zeichnet. Das ist Bildschirm.
 */
class BaselineRoundTripTest {

  init {
    object : CalendarFactory() {
      init {
        setLocaleApi(object : CalendarFactory.LocaleApi {
          override fun getLocale(): Locale = Locale.GERMANY
          override fun getShortDateFormat(): DateFormat =
            DateFormat.getDateInstance(DateFormat.SHORT, Locale.GERMANY)
        })
      }
    }
  }

  private fun saveToXml(project: GanttProjectImpl): String {
    val out = ByteArrayOutputStream()
    GanttXMLSaver(project).save(out)
    return out.toString(Charsets.UTF_8)
  }

  @Test
  fun `ein basisplan landet in der projektdatei`() {
    val project = GanttProjectImpl()
    val task = project.taskManager.newTaskBuilder()
      .withName("Vorgang").withStartDate(LocalDate.of(2026, 8, 17).toModelDate())
      .withDuration(project.taskManager.createLength(5)).build()

    // init() und saveFile() gehoeren dazu: ein Basisplan haelt seine Vorgaenge in einer
    // Temporaerdatei, nicht im Speicher. Ohne sie ist er in der Liste sichtbar, im Diagramm
    // unsichtbar und beim Speichern eine Ausnahme.
    project.baselines.add(
      GanttPreviousState("Vor der Verteilung 2026-08-17",
        GanttPreviousState.createTasks(project.taskManager)).also {
          it.init()
          it.saveFile()
        })

    val xml = saveToXml(project)
    assertTrue(xml.contains("previous"), "kein Basisplan in der Datei:\n$xml")
    assertTrue(xml.contains("Vor der Verteilung 2026-08-17"), "der Name fehlt")
    assertTrue(xml.contains("2026-08-17"), "der gesicherte Termin fehlt")
    assertEquals(1, project.baselines.size)
    // Gegenprobe: ohne Basisplan steht das Wort auch nicht in der Datei -- sonst haette der Test
    // oben nur bewiesen, dass GanttProject immer etwas mit "previous" schreibt.
    val ohne = saveToXml(GanttProjectImpl())
    assertTrue(!ohne.contains("Vor der Verteilung"), "Gegenprobe: der Name darf nicht erscheinen")
  }

  @Test
  fun `der basisplan haelt die termine VOR dem verschieben fest`() {
    // Das ist der Punkt, an dem die Reihenfolge im Programm zaehlt: erst sichern, dann verteilen.
    // Andersherum haelt der Basisplan die schon verschobenen Termine -- er saehe richtig aus und
    // waere wertlos.
    val project = GanttProjectImpl()
    val tm = project.taskManager
    val task = tm.newTaskBuilder().withName("Vorgang")
      .withStartDate(LocalDate.of(2026, 8, 17).toModelDate())
      .withDuration(tm.createLength(5)).build()

    project.baselines.add(GanttPreviousState("vorher", GanttPreviousState.createTasks(tm)).also {
      it.init()
      it.saveFile()
    })

    // Jetzt verschieben, wie es die Verteilung tut.
    val mutator = task.createMutator()
    mutator.setStart(CalendarFactory.createGanttCalendar(
      LocalDate.of(2026, 9, 21).toModelDate()))
    mutator.commit()

    // load() liest die Temporaerdatei -- genau der Weg, den der Speicherer (HistorySaver:45) und
    // das Diagramm (GanttGraphicArea:237) gehen. Ein Test gegen das Feld im Speicher haette die
    // fehlenden init()/saveFile() nicht bemerkt.
    val gesichert = project.baselines[0].load().first { it.id == task.taskID }
    assertEquals(LocalDate.of(2026, 8, 17), gesichert.start.time.toModelLocalDate(),
      "der Basisplan haelt den August, nicht den September")
    assertEquals(5, gesichert.duration)
    assertEquals(LocalDate.of(2026, 9, 21), task.start.time.toModelLocalDate(),
      "der Vorgang selbst ist verschoben")
  }
}
