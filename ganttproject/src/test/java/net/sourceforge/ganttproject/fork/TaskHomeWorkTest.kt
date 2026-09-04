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

import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.customproperty.CustomPropertyClass
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.task.algorithm.findEffortDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.util.Locale

/**
 * THE THREE STATES OF THE HOME WORKING MARK, and the guard that a plan nobody touched keeps
 * behaving exactly as it did.
 *
 * The mark says whether a task can be done from home. Natalie asked for three states and not two:
 * nobody has said, said yes, said no. The first two behave alike -- neither keeps a task off a
 * home working day -- and they must nevertheless stay apart in storage, so that the plan can later
 * report the tasks about which nobody ever decided.
 *
 * WHY THE STATES ARE MEASURED IN FIVE DIFFERENT PROJECTS and not just one. The state "nobody has
 * said" can arise in four ways -- no column, no value, a `null` value, an empty string -- and a
 * check run against only one of them cannot see a break in the others. Three earlier sessions ran
 * into exactly this with exactly this kind of guard, so [alleStillenZustaende] runs every silent
 * state through the same assertion and names which one broke.
 */
class TaskHomeWorkTest {

  init {
    // TestSetupHelper builds a WeekendCalendarImpl, which asks CalendarFactory. Same opening as
    // the neighbouring fork tests; without it every test here dies in CalendarFactory.
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

  private fun vorgang(): Pair<Task, CustomPropertyManager> {
    val taskManager: TaskManager = TestSetupHelper.newTaskManagerBuilder().build()
    val task = taskManager.newTaskBuilder().withName("Vorgang").build()
    return task to taskManager.customPropertyManager
  }

  // ------------------------------------------------------------------------------------------
  // The three states, and that they really are three
  // ------------------------------------------------------------------------------------------

  /**
   * THE MOST IMPORTANT TEST OF THE WHOLE FEATURE. If "nobody has said" and "said it can be done
   * from home" ever answer the same here, the third state has no reason to exist and the report
   * Natalie wants to keep possible cannot be written.
   */
  @Test
  fun `nicht entschieden und geht-von-zu-Hause sind zweierlei`() {
    val (task, props) = vorgang()
    assertEquals(HomeWorkMark.NOT_DECIDED, task.homeWorkMark(props),
      "ein frischer Vorgang muss unentschieden sein")

    applyHomeWorkMark(task.customValues, props, HomeWorkMark.FROM_HOME)
    assertEquals(HomeWorkMark.FROM_HOME, task.homeWorkMark(props),
      "ein ausdrueckliches Ja muss als Ja zurueckkommen")

    assertNotEquals(HomeWorkMark.NOT_DECIDED, task.homeWorkMark(props),
      "ein ausdrueckliches Ja darf nicht wie \"niemand hat etwas gesagt\" aussehen -- genau " +
        "dafuer gibt es den dritten Zustand")
  }

  /** Setting, reading, clearing -- every step visible on its own. */
  @Test
  fun `setzen ablesen loeschen`() {
    val (task, props) = vorgang()
    assertEquals(HomeWorkMark.NOT_DECIDED, task.homeWorkMark(props), "Ausgangslage")

    applyHomeWorkMark(task.customValues, props, HomeWorkMark.ON_SITE)
    assertEquals(HomeWorkMark.ON_SITE, task.homeWorkMark(props), "gesetzt")

    applyHomeWorkMark(task.customValues, props, HomeWorkMark.FROM_HOME)
    assertEquals(HomeWorkMark.FROM_HOME, task.homeWorkMark(props), "umgesetzt")

    applyHomeWorkMark(task.customValues, props, HomeWorkMark.NOT_DECIDED)
    assertEquals(HomeWorkMark.NOT_DECIDED, task.homeWorkMark(props), "geloescht")

    // Cleared means cleared, not "false". A value of its own would land in the .gan file and would
    // make the task look decided.
    val def = props.findEffortDefinition(TASK_ON_SITE_ONLY)!!
    assertNull(task.customValues.getValue(def),
      "nach dem Loeschen darf der Vorgang keinen eigenen Wert mehr haben")
  }

  /**
   * All four silent states in one place. Each is a project in its own right, because a check that
   * only ever sees one of them cannot see a break in the other three -- which is how three
   * earlier sessions got a green guard over a broken default.
   */
  @Test
  fun `alleStillenZustaende`() {
    val faelle: List<Pair<String, Pair<Task, CustomPropertyManager>>> = listOf(
      "gar keine Spalte im Projekt" to vorgang().also { (_, props) ->
        assertNull(props.findEffortDefinition(TASK_ON_SITE_ONLY), "Vorbedingung des Falls")
      },
      "Spalte da, aber kein Wert" to vorgang().also { (_, props) ->
        findOrCreateOnSiteOnly(props)
      },
      "Wert ausdruecklich null" to vorgang().also { (task, props) ->
        task.customValues.setValue(findOrCreateOnSiteOnly(props), null)
      },
      "Wert ist die leere Zeichenkette" to vorgang().also { (task, props) ->
        // The empty string is the one that has to be caught before any boolean conversion:
        // java.lang.Boolean.valueOf("") is false, and false means "decided, can be done from home".
        // A TEXT column with the same id is the only way to get an empty string past setValue's
        // type check, and it is the shape the value has when it travels as text.
        val def = props.createDefinition(TASK_ON_SITE_ONLY, CustomPropertyClass.TEXT.iD,
                                         "Nur vor Ort", null)
        task.customValues.setValue(def, "")
      }
    )
    faelle.forEach { (name, paar) ->
      val (task, props) = paar
      assertEquals(HomeWorkMark.NOT_DECIDED, task.homeWorkMark(props),
        "stiller Zustand \"$name\" muss unentschieden ergeben, nicht entschieden")
      assertTrue(task.mayRunOnHomeWorkingDay(props),
        "stiller Zustand \"$name\" darf keinen Heimarbeitstag blockieren")
    }
  }

  // ------------------------------------------------------------------------------------------
  // The way out of the file: the value arrives as a String
  // ------------------------------------------------------------------------------------------

  /**
   * `TaskSaver` writes the value as `value?.toString()`, so what comes back out of a `.gan` file
   * is text. Reading only `as? Boolean` would make every mark ever saved look undecided.
   */
  @Test
  fun `der rohe Wert wird auch als Zeichenkette gelesen`() {
    listOf("true" to HomeWorkMark.ON_SITE,
           "TRUE" to HomeWorkMark.ON_SITE,
           "  true  " to HomeWorkMark.ON_SITE,
           "false" to HomeWorkMark.FROM_HOME,
           "False" to HomeWorkMark.FROM_HOME,
           "" to HomeWorkMark.NOT_DECIDED,
           "   " to HomeWorkMark.NOT_DECIDED,
           "vielleicht" to HomeWorkMark.NOT_DECIDED).forEach { (text, erwartet) ->
      val (task, props) = vorgang()
      val def = props.createDefinition(TASK_ON_SITE_ONLY, CustomPropertyClass.TEXT.iD,
                                       "Nur vor Ort", null)
      task.customValues.setValue(def, text)
      assertEquals(erwartet, task.homeWorkMark(props),
        "die Zeichenkette \"$text\" wurde falsch gelesen")
    }
  }

  /** And the boolean path, which is what a value set in this program looks like. */
  @Test
  fun `der rohe Wert wird auch als Wahrheitswert gelesen`() {
    listOf(true to HomeWorkMark.ON_SITE, false to HomeWorkMark.FROM_HOME).forEach { (wert, erwartet) ->
      val (task, props) = vorgang()
      task.customValues.setValue(findOrCreateOnSiteOnly(props), wert)
      assertEquals(erwartet, task.homeWorkMark(props), "der Wahrheitswert $wert wurde falsch gelesen")
    }
  }

  // ------------------------------------------------------------------------------------------
  // The fold
  // ------------------------------------------------------------------------------------------

  /**
   * The one place where "nobody said" is folded onto "may be done from home". Two of the three
   * states have to answer alike and the third differently -- if all three answered alike the fold
   * would be meaningless, and if all three differed the fold would not be a fold.
   */
  @Test
  fun `die Faltung stimmt`() {
    assertTrue(HomeWorkMark.NOT_DECIDED.allowsHomeWorkingDay,
      "unentschieden darf nicht blockieren -- sonst aendert jeder bestehende Plan sein Verhalten")
    assertTrue(HomeWorkMark.FROM_HOME.allowsHomeWorkingDay,
      "\"geht von zu Hause\" darf nicht blockieren")
    assertTrue(!HomeWorkMark.ON_SITE.allowsHomeWorkingDay,
      "\"geht nicht von zu Hause\" ist der einzige Zustand, der etwas einschraenkt")
  }

  // ------------------------------------------------------------------------------------------
  // Nothing is created behind anybody's back
  // ------------------------------------------------------------------------------------------

  /**
   * A plan in which nobody decided anything must not grow the column just because something asked.
   * Reading must not create it either -- a project opened, looked at and closed again has to come
   * out of it byte for byte as it went in.
   */
  @Test
  fun `unentschieden legt keine Spalte an`() {
    val (task, props) = vorgang()
    task.homeWorkMark(props)
    task.mayRunOnHomeWorkingDay(props)
    applyHomeWorkMark(task.customValues, props, HomeWorkMark.NOT_DECIDED)
    assertNull(props.findEffortDefinition(TASK_ON_SITE_ONLY),
      "weder Lesen noch ein unentschiedenes Schreiben darf die Spalte anlegen")
  }

  /**
   * And the definition this fork creates carries NO default value. `CustomColumnsValues.getValue`
   * falls back to `def.defaultValue` for every task without a value of its own, so a default here
   * would answer for the entire plan at once and there would be no undecided task left anywhere.
   */
  @Test
  fun `die Spalte hat keinen Vorgabewert`() {
    val (_, props) = vorgang()
    assertNull(findOrCreateOnSiteOnly(props).defaultValue,
      "ein Vorgabewert wuerde fuer JEDEN Vorgang ohne eigenen Wert antworten")
  }
}
