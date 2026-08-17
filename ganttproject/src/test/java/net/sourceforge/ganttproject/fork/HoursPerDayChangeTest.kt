/*
Copyright 2026

NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.

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

import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenTrigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.util.Locale

/**
 * Aendert sich die Dauer, wenn sich die Tagesleistung aendert?
 *
 * WOZU: das ist die eigentliche Frage an den Umbau. Die Stundenzahl pro Tag steigt, sobald
 * der Hauptberuf reduziert wird -- und dann soll der Plan sich neu rechnen, ohne dass 162
 * Dauern von Hand angefasst werden. Der Ausloeser ([EffortDrivenTrigger]) war gebaut und verdrahtet,
 * aber durch keinen Test belegt. "Verdrahtet" und "wirkt" sind nicht dasselbe -- diese Sitzung hat
 * dafuer schon drei Gegenbeispiele gefunden (tote Menuepunkte, eine Einstellungsseite ohne
 * Speicherung, eine Sperre ohne If-Match).
 */
class HoursPerDayChangeTest {

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

  private fun setHoursPerDay(
    manager: CustomPropertyManager, resource: net.sourceforge.ganttproject.resource.HumanResource,
    hours: Double) {
    val definition = EffortDrivenProperties.findOrCreateResourceHours(manager)
    resource.setValue(definition, hours)
  }

  @Test
  fun `mehr stunden pro tag verkuerzen die dauer`() {
    // Der Ressourcenverwalter MUSS der des TaskManagers sein: der Algorithmus holt die
    // Eigenschaften ueber `config.getResourceManager()`. Mit einem eigenen Verwalter griff die
    // Rechnung auf den Vorgabewert 8 Std./Tag zurueck -- der Test war dann gruen, ohne etwas zu
    // pruefen. Beim ersten Lauf ist genau das passiert (5 statt 10 Tage im Aufbau).
    val builder = TestSetupHelper.newTaskManagerBuilder()
    val taskManager = builder.build()
    val resourceManager = builder.resourceManager
    // Genau die Verdrahtung aus GanttProjectImpl:109.
    resourceManager.addView(EffortDrivenTrigger(taskManager))

    val resource = resourceManager.create("Natalie", 0)
    setHoursPerDay(resourceManager.customPropertyManager, resource, 4.0)

    val task = taskManager.newTaskBuilder().withName("Firmware").build()
    task.assignmentCollection.addAssignment(resource).load = 100f
    // 40 Stunden Aufwand bei 4 Std./Tag: zehn Tage.
    val effortDefinition = EffortDrivenProperties.findOrCreateTaskEffort(taskManager.customPropertyManager)
    task.customValues.setValue(effortDefinition, 40.0)
    taskManager.algorithmCollection.effortDrivenDurationAlgorithm.run()
    assertEquals(10, task.duration.length, "Aufbau: 40 Std. bei 4 Std./Tag")

    // Hauptberuf reduziert: acht Stunden am Tag. NIEMAND fasst die Dauer an.
    setHoursPerDay(resourceManager.customPropertyManager, resource, 8.0)

    assertEquals(5, task.duration.length,
      "40 Std. bei 8 Std./Tag sind fuenf Tage -- die Aenderung der Tagesleistung muss die Dauer " +
        "von selbst nachziehen")
    assertEquals(40.0, task.customValues.getValue(effortDefinition),
      "der Aufwand selbst darf sich dabei NICHT aendern: die Arbeit bleibt dieselbe")
  }
}
