/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.

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
 * Does a baseline keep what it promises?
 *
 * A QUESTION RAISED ON 17.08.2026: test afterwards whether the baseline works in the classic
 * Gantt view as well.
 *
 * What is checked here is the half that is measurable without a screen: that the baseline gets
 * into the project file at all and that it holds the right dates -- namely the ones from BEFORE
 * levelling. A baseline recorded after the moving looks exactly the same and is worthless.
 *
 * What is NOT checked here and cannot be checked: whether the chart draws the second bar. That is
 * a matter for the screen.
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

    // init() and saveFile() are part of it: a baseline holds its Tasks in a temporary file, not
    // in memory. Without them it is visible in the list, invisible in the chart and an exception
    // when saving.
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
    // Counter-check: without a baseline the word does not appear in the file either -- otherwise
    // the test above would only have proved that GanttProject always writes something with
    // "previous".
    val ohne = saveToXml(GanttProjectImpl())
    assertTrue(!ohne.contains("Vor der Verteilung"), "Gegenprobe: der Name darf nicht erscheinen")
  }

  @Test
  fun `der basisplan haelt die termine VOR dem verschieben fest`() {
    // This is the point at which the order in the program counts: save first, then level. The
    // other way round the baseline holds the dates that have already been moved -- it would look
    // right and be worthless.
    val project = GanttProjectImpl()
    val tm = project.taskManager
    val task = tm.newTaskBuilder().withName("Vorgang")
      .withStartDate(LocalDate.of(2026, 8, 17).toModelDate())
      .withDuration(tm.createLength(5)).build()

    project.baselines.add(GanttPreviousState("vorher", GanttPreviousState.createTasks(tm)).also {
      it.init()
      it.saveFile()
    })

    // Now move, the way levelling does.
    val mutator = task.createMutator()
    mutator.setStart(CalendarFactory.createGanttCalendar(
      LocalDate.of(2026, 9, 21).toModelDate()))
    mutator.commit()

    // load() reads the temporary file -- exactly the path the saver (HistorySaver:45) and the
    // chart (GanttGraphicArea:237) take. A test against the field in memory would not have
    // noticed the missing init()/saveFile().
    val gesichert = project.baselines[0].load().first { it.id == task.taskID }
    assertEquals(LocalDate.of(2026, 8, 17), gesichert.start.time.toModelLocalDate(),
      "der Basisplan haelt den August, nicht den September")
    assertEquals(5, gesichert.duration)
    assertEquals(LocalDate.of(2026, 9, 21), task.start.time.toModelLocalDate(),
      "der Vorgang selbst ist verschoben")
  }
}
