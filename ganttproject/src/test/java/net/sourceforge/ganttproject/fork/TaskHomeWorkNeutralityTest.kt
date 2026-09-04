/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.

This file is part of GanttProject, an opensource project management tool.

GanttProject is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

GanttProject is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with GanttProject.  If not, see <http://www.gnu.org/licenses/>.
*/
package net.sourceforge.ganttproject.fork

import biz.ganttproject.core.io.XmlProjectImporter
import biz.ganttproject.core.time.CalendarFactory
import net.sourceforge.ganttproject.GanttProjectImpl
import net.sourceforge.ganttproject.io.GanttXMLSaver
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.algorithm.findEffortDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * A WHOLE PLAN THAT NOBODY MARKED, driven through every path that touches it.
 *
 * The single-task checks next door ask whether one read answers correctly. This one asks the
 * question the way it actually matters: take a plan of several tasks the way it comes out of an
 * older version, open it, save it, load it again -- and afterwards not one task may have left the
 * "nobody decided" set, and not one value may stand in the file.
 *
 * WHY THE SET AND NOT THE DATES. The obvious guard would compare the schedule before and after.
 * It would be worthless here, and knowingly so: nothing reads this mark yet -- the rule that will
 * (package B3) is not built -- so no break in this package can move a date, and a date comparison
 * could not go red however badly the mark were wired. A guard that cannot fail is not a guard.
 * What CAN go wrong today, and what this measures, is the set itself: which tasks the future rule
 * will find marked when it starts asking. In a plan nobody touched that set has to be empty, and
 * the "undecided" set has to still contain everything.
 *
 * The one thing that DOES change for an untouched plan is measured too, in
 * [ein unberuehrter plan gewinnt nur die spalte selbst]: the file gains the column DEFINITION,
 * because the column has to exist for anybody to be able to fill it in. No task value, no default
 * value, no changed date.
 */
class TaskHomeWorkNeutralityTest {

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

  private fun saveToXml(project: GanttProjectImpl): String =
    ByteArrayOutputStream().also { GanttXMLSaver(project).save(it) }.toString(Charsets.UTF_8)

  /** Five tasks, a person, a dependency -- a plan out of an older version, nothing marked. */
  private fun alterPlan(): GanttProjectImpl {
    val project = GanttProjectImpl()
    project.humanResourceManager.create("Natalie", 0)
    val tasks = (1..5).map { nr ->
      project.taskManager.newTaskBuilder()
        .withName("Vorgang $nr")
        .withStartDate(LocalDate.of(2026, 9, nr).toModelDate())
        .withDuration(project.taskManager.createLength(2))
        .build()
    }
    tasks.first().assignmentCollection.addAssignment(project.humanResourceManager.getById(0))
      .also { it.load = 100f }
    return project
  }

  private fun GanttProjectImpl.zustaende(): List<HomeWorkMark> =
    this.taskManager.tasks.map { it.homeWorkMark(this.taskManager.customPropertyManager) }

  private fun GanttProjectImpl.termine(): List<Triple<String, String, String>> =
    this.taskManager.tasks.map { t: Task ->
      Triple(t.name, t.start.toString(), t.end.toString())
    }

  // ------------------------------------------------------------------------------------------

  /**
   * THE GUARD. Open, confirm, save, load -- and afterwards every single task is still undecided
   * and the file holds no value for any of them.
   *
   * `ensureCapacityColumns` is the real one the program runs when a project is opened; it is what
   * brings the column into a plan that never had it, so it belongs in this path rather than a
   * hand-made findOrCreate.
   */
  @Test
  fun `ein unberuehrter plan bleibt in jedem schritt unentschieden`() {
    val project = alterPlan()
    val props = project.taskManager.customPropertyManager
    val termineVorher = project.termine()

    assertEquals(List(5) { HomeWorkMark.NOT_DECIDED }, project.zustaende(),
      "Schritt 0, frisch gebaut")

    // 1. Opening the project creates the fork's columns.
    project.ensureCapacityColumns()
    assertNotNull(props.findEffortDefinition(TASK_ON_SITE_ONLY), "die Spalte muss jetzt da sein")
    assertEquals(List(5) { HomeWorkMark.NOT_DECIDED }, project.zustaende(),
      "Schritt 1, nach dem Anlegen der Spalten")

    // 2. Every task's dialog opened and confirmed without anybody choosing anything. This is the
    // path on which the empty string turns into false -- see TaskHomeWorkDialogTest.
    project.taskManager.tasks.forEach { task ->
      val holder = task.customValues.copyOf()
      props.definitions.forEach { def -> holder.addCustomProperty(def, "") }
      applyHomeWorkMark(holder, props, HomeWorkMark.NOT_DECIDED)
      task.customValues.importFrom(holder)
    }
    assertEquals(List(5) { HomeWorkMark.NOT_DECIDED }, project.zustaende(),
      "Schritt 2, nach Oeffnen und Bestaetigen des Dialogs an jedem Vorgang")

    // 3. Saved and read back.
    val xml = saveToXml(project)
    assertFalse(xml.contains("taskproperty-id=\"$TASK_ON_SITE_ONLY\""),
      "Schritt 3, kein Vorgang darf einen Wert in die Datei geschrieben haben:\n$xml")

    val geladen = XmlProjectImporter(GanttProjectImpl()).import(xml) as GanttProjectImpl
    assertEquals(List(5) { HomeWorkMark.NOT_DECIDED }, geladen.zustaende(),
      "Schritt 4, nach dem Laden")

    // And the set the future rule will look at is empty, while everything is still undecided.
    val eingeschraenkt = geladen.taskManager.tasks.filter {
      !it.mayRunOnHomeWorkingDay(geladen.taskManager.customPropertyManager)
    }
    assertTrue(eingeschraenkt.isEmpty(),
      "die Menge der eingeschraenkten Vorgaenge muss leer sein, sie ist: " +
        eingeschraenkt.joinToString { it.name })

    assertEquals(termineVorher, geladen.termine(), "kein Termin darf sich verschoben haben")
  }

  /**
   * What an untouched plan DOES gain, stated so that nobody has to guess: the column definition,
   * and nothing else of this feature. Without the definition the column could not be seen or
   * filled in at all -- the same reason the four columns beside it are created there.
   */
  @Test
  fun `ein unberuehrter plan gewinnt nur die spalte selbst`() {
    val ohne = saveToXml(alterPlan())
    val mit = saveToXml(alterPlan().also { it.ensureCapacityColumns() })

    assertFalse(ohne.contains(TASK_ON_SITE_ONLY),
      "Vorbedingung: ohne ensureCapacityColumns kennt die Datei die Spalte nicht")
    assertTrue(mit.contains("id=\"$TASK_ON_SITE_ONLY\""),
      "die Spaltendefinition muss in der Datei stehen")
    assertFalse(mit.contains("taskproperty-id=\"$TASK_ON_SITE_ONLY\""),
      "aber kein Vorgang darf einen Wert dafuer haben")

    // Only the definitions block differs; no <task> line changed. The uid is cut out first: it
    // is drawn afresh for every task that is built, so two separately built plans differ in it
    // whatever else happens. Measured -- the first run of this test went red on exactly that and
    // on nothing else.
    fun vorgangszeilen(xml: String) = xml.lines().filter { it.contains("<task ") }
      .map { it.replace(Regex(" uid=\"[0-9a-f]*\""), "") }
    assertEquals(vorgangszeilen(ohne), vorgangszeilen(mit),
      "keine einzige Vorgangszeile darf sich durch diese Aenderung unterscheiden")
  }
}
