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
import biz.ganttproject.core.io.parseXmlProject
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.lib.fx.SimpleTreeCollapseView
import net.sourceforge.ganttproject.GanttProjectImpl
import net.sourceforge.ganttproject.io.GanttXMLSaver
import net.sourceforge.ganttproject.parser.TaskLoader
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.algorithm.findEffortDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * DOES THE MARK SURVIVE THE FILE, and above all: does "nobody has decided" survive it?
 *
 * The other two states are the easy half. The one this test exists for is the empty one: a task
 * nobody decided about must come back out of the file undecided and not as "can be done from
 * home". There are two ways it could fail, and both are silent:
 *
 *  - the saver could write an explicit `value="false"` for it, or
 *  - the definition could travel with a default value, and `CustomColumnsValues.getValue` falls
 *    back to `def.defaultValue` for every task with no value of its own -- one default would
 *    answer for the whole plan and there would be no undecided task left anywhere.
 *
 * Both are checked against the FILE TEXT and not only against the model, because a model that
 * remembers what the file forgot proves nothing.
 *
 * TWO READERS. The desktop reads `.gan` through [TaskLoader], the cloud through
 * [XmlProjectImporter], and they convert differently -- [TaskLoader] turns the text into a real
 * `Boolean`, the other path can hand the value over as a `String`. Both are measured, otherwise
 * the two halves of the program could disagree about the same file. That is also why the read in
 * `TaskHomeWork.kt` accepts both shapes.
 */
class TaskHomeWorkRoundTripTest {

  init {
    // GanttProjectImpl builds a WeekendCalendarImpl, which needs this. Same pattern as
    // AssignmentAxesRoundTripTest.
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

  /** One task, and the column present -- the smallest project that can carry the mark. */
  private fun planMit(mark: HomeWorkMark): GanttProjectImpl {
    val project = GanttProjectImpl()
    val task = project.taskManager.newTaskBuilder()
      .withName("Vorgang")
      .withStartDate(LocalDate.of(2026, 9, 4).toModelDate())
      .withDuration(project.taskManager.createLength(3))
      .build()
    findOrCreateOnSiteOnly(project.taskManager.customPropertyManager)
    applyHomeWorkMark(task.customValues, project.taskManager.customPropertyManager, mark)
    return project
  }

  /** Loads through the reader the DESKTOP uses. */
  private fun ladenWieDasProgramm(xml: String): Task {
    val ziel = GanttProjectImpl()
    val xmlProject = parseXmlProject(xml)
    val loader = TaskLoader(ziel.taskManager, SimpleTreeCollapseView())
    loader.loadTaskCustomPropertyDefinitions(xmlProject)
    xmlProject.tasks.tasks?.forEach { loader.loadTask(null, it) }
    return ziel.taskManager.tasks.single()
  }

  /** Loads through the reader the CLOUD uses. */
  private fun ladenUeberDenImporter(xml: String): Pair<Task, GanttProjectImpl> {
    val geladen = XmlProjectImporter(GanttProjectImpl()).import(xml) as GanttProjectImpl
    return geladen.taskManager.tasks.single() to geladen
  }

  // ------------------------------------------------------------------------------------------

  @Test
  fun `alle drei zustaende ueberstehen speichern und laden`() {
    HomeWorkMark.entries.forEach { mark ->
      val xml = saveToXml(planMit(mark))

      val ueberImporter = ladenUeberDenImporter(xml)
      assertEquals(mark, ueberImporter.first.homeWorkMark(
        ueberImporter.second.taskManager.customPropertyManager),
        "Zustand $mark ist auf dem Weg durch den Importer verlorengegangen:\n$xml")

      val ueberProgramm = ladenWieDasProgramm(xml)
      assertEquals(mark, ueberProgramm.homeWorkMark(ueberProgramm.manager.customPropertyManager),
        "Zustand $mark ist auf dem Weg durch den Lader des Programms verlorengegangen:\n$xml")
    }
  }

  /**
   * THE IMPORTANT HALF, measured against the file text. An undecided task must leave no trace in
   * the file at all -- no value, and no default value on the definition that would answer in its
   * place.
   */
  @Test
  fun `unentschieden steht nicht in der Datei`() {
    val xml = saveToXml(planMit(HomeWorkMark.NOT_DECIDED))

    assertTrue(xml.contains("id=\"$TASK_ON_SITE_ONLY\""),
      "die Spalte selbst muss in der Datei stehen, sonst misst dieser Test nichts:\n$xml")
    assertFalse(xml.contains("taskproperty-id=\"$TASK_ON_SITE_ONLY\""),
      "ein unentschiedener Vorgang darf keinen eigenen Wert in die Datei schreiben:\n$xml")
    assertFalse(xml.contains("defaultvalue"),
      "die Spalte darf keinen Vorgabewert mitnehmen -- der wuerde fuer JEDEN Vorgang ohne " +
        "eigenen Wert antworten:\n$xml")
  }

  /** And the two decided states DO stand in the file, under the agreed text. */
  @Test
  fun `die entschiedenen zustaende stehen in der Datei`() {
    val anOrt = saveToXml(planMit(HomeWorkMark.ON_SITE))
    assertTrue(anOrt.contains("taskproperty-id=\"$TASK_ON_SITE_ONLY\"") && anOrt.contains("value=\"true\""),
      "\"nur vor Ort\" steht nicht als true in der Datei:\n$anOrt")

    val vonZuHause = saveToXml(planMit(HomeWorkMark.FROM_HOME))
    assertTrue(vonZuHause.contains("taskproperty-id=\"$TASK_ON_SITE_ONLY\"") &&
                 vonZuHause.contains("value=\"false\""),
      "\"geht von zu Hause\" steht nicht als false in der Datei:\n$vonZuHause")
  }

  /**
   * THE LANGUAGE SWITCH. The column carries the label it had when it was created -- here the
   * German one -- and is found again by its IDENTIFIER, not by that label. Measured by loading the
   * German file with an English default locale and checking both halves: the mark still reads, and
   * the name in the loaded project is still the German text. If the lookup went by name, the
   * second assertion would hold and the first would fail.
   */
  @Test
  fun `die spalte wird ueber ihre kennung gefunden, nicht ueber ihren namen`() {
    val vorher = Locale.getDefault()
    val xml = try {
      Locale.setDefault(Locale.GERMANY)
      saveToXml(planMit(HomeWorkMark.ON_SITE))
    } finally {
      Locale.setDefault(vorher)
    }
    val deutscherName = forkTextIn(Locale.GERMANY)
    assertTrue(xml.contains("name=\"$deutscherName\""),
      "die Spalte sollte unter ihrem deutschen Namen gespeichert sein:\n$xml")

    try {
      Locale.setDefault(Locale.ENGLISH)
      val (task, geladen) = ladenUeberDenImporter(xml)
      val props = geladen.taskManager.customPropertyManager
      assertEquals(HomeWorkMark.ON_SITE, task.homeWorkMark(props),
        "die Markierung wurde nach einem Sprachwechsel nicht mehr gefunden -- dann geht die " +
          "Suche ueber den Namen und nicht ueber die Kennung")
      val def = props.findEffortDefinition(TASK_ON_SITE_ONLY)
      assertNotNull(def, "die Spalte selbst wurde nicht wiedergefunden")
      assertEquals(deutscherName, def!!.name,
        "der Name in der Datei bleibt der deutsche -- genau deshalb darf er nicht der " +
          "Suchschluessel sein")
      // And the definition is not created a second time under the English label.
      assertEquals(1, props.definitions.count { it.id == TASK_ON_SITE_ONLY },
        "nach dem Sprachwechsel darf keine zweite Spalte fuer dieselbe Sache entstehen")
      assertEquals(def, findOrCreateOnSiteOnly(props),
        "findOrCreate muss die vorhandene Spalte nehmen und keine neue anlegen")
    } finally {
      Locale.setDefault(vorher)
    }
  }

  /**
   * A FILE THAT DECLARES THE COLUMN AS TEXT. This is where the string path in `homeWorkMark`
   * actually earns its keep, and it is NOT the ordinary round trip: measured on 04.09.2026, both
   * readers convert a `boolean` column themselves -- `TaskLoader` with
   * `java.lang.Boolean.valueOf`, [XmlProjectImporter] with `toBoolean()` -- so a boolean column
   * never hands a `String` to anybody.
   *
   * `findEffortDefinition` finds a column by its id OR its name, though, so the definition the
   * read gets hold of need not be the boolean one this fork creates. A file written by an older
   * version, hand-edited, or carrying a column somebody made themselves can declare the same id as
   * text -- and then the value arrives as a `String`. `true` has to keep meaning `true` there
   * instead of quietly turning into "nobody decided".
   */
  @Test
  fun `eine als Text erklaerte spalte wird trotzdem richtig gelesen`() {
    fun mitTextspalte(wert: String): String = """
      <?xml version="1.0" encoding="UTF-8"?>
      <project name="Textspalte" view-date="2026-09-01" view-index="0">
        <calendars><day-types><day-type id="0"/><day-type id="1"/>
          <default-week id="1" name="default" sun="1" mon="0" tue="0" wed="0" thu="0" fri="0" sat="1"/>
          <only-show-weekends value="false"/><overriden-day-types/><days/>
        </day-types></calendars>
        <tasks>
          <taskproperties>
            <taskproperty id="$TASK_ON_SITE_ONLY" name="Nur vor Ort" type="custom" valuetype="text"/>
          </taskproperties>
          <task id="0" name="Vorgang" meeting="false" start="2026-09-04" duration="2" complete="0" expand="true">
            <customproperty taskproperty-id="$TASK_ON_SITE_ONLY" value="$wert"/>
          </task>
        </tasks>
      </project>
    """.trimIndent()

    listOf("true" to HomeWorkMark.ON_SITE,
           "false" to HomeWorkMark.FROM_HOME,
           "" to HomeWorkMark.NOT_DECIDED).forEach { (text, erwartet) ->
      val ueberProgramm = ladenWieDasProgramm(mitTextspalte(text))
      assertEquals(erwartet, ueberProgramm.homeWorkMark(ueberProgramm.manager.customPropertyManager),
        "die Textspalte mit dem Wert \"$text\" wurde vom Lader des Programms falsch gelesen")

      val (task, geladen) = ladenUeberDenImporter(mitTextspalte(text))
      assertEquals(erwartet, task.homeWorkMark(geladen.taskManager.customPropertyManager),
        "die Textspalte mit dem Wert \"$text\" wurde vom Importer falsch gelesen")
    }
  }

  /**
   * A task out of a file written BEFORE this change: the column does not exist at all. It has to
   * read as undecided and must not throw -- and reading must not create the column either.
   */
  @Test
  fun `ein vorgang aus einer alten datei ist unentschieden`() {
    val altesProjekt = GanttProjectImpl()
    altesProjekt.taskManager.newTaskBuilder()
      .withName("alter Vorgang")
      .withStartDate(LocalDate.of(2026, 1, 5).toModelDate())
      .withDuration(altesProjekt.taskManager.createLength(2))
      .build()
    val xml = saveToXml(altesProjekt)
    assertFalse(xml.contains(TASK_ON_SITE_ONLY), "Vorbedingung: die alte Datei kennt die Spalte nicht")

    val (task, geladen) = ladenUeberDenImporter(xml)
    val props = geladen.taskManager.customPropertyManager
    assertEquals(HomeWorkMark.NOT_DECIDED, task.homeWorkMark(props),
      "ein Vorgang ohne die Eigenschaft muss unentschieden sein, nicht entschieden")
    assertTrue(task.mayRunOnHomeWorkingDay(props),
      "ein Vorgang aus einer alten Datei darf keinen Heimarbeitstag blockieren")
    assertNull(props.findEffortDefinition(TASK_ON_SITE_ONLY),
      "das blosse Lesen darf die Spalte nicht anlegen")
  }
}

/** The label the fork bundle gives the column in [locale], without changing the running default. */
private fun forkTextIn(locale: Locale): String =
  ForkI18n.textOrNull("fork.column.onSiteOnly", locale) ?: "fork.column.onSiteOnly"
