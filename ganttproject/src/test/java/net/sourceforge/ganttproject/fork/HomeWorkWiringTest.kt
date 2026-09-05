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

import biz.ganttproject.core.calendar.WeekendCalendarImpl
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.GanttPreviousState
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import net.sourceforge.ganttproject.undo.GPUndoListener
import net.sourceforge.ganttproject.undo.GPUndoManager
import net.sourceforge.ganttproject.undo.UndoableEditTxnFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * W9 — THE WIRING, AND IT IS THE ONE MISTAKE THAT LEAVES EVERYTHING GREEN.
 *
 * `levelTasks` takes the home-work channel as a parameter with a default that says „everybody is at
 * their workplace". That default is right — an unfilled parameter must mean the state of things as
 * they were — and it has a consequence: FORGETTING TO FILL IT IN COMPILES. Every check on
 * `levelTasks` stays green, because those checks inject the function themselves. And in the running
 * program nothing whatever happens.
 *
 * That exact mistake was found in this fork once already, on 04.09.2026: `LevellingActions` had
 * kept the global project calendar while both of its neighbours had moved to a grid per task. It
 * was found on screen, not by a check.
 *
 * SO THIS FILE DRIVES `LevellingAction` ITSELF — the menu item, with a real task manager, a real
 * resource manager and a real levelling run behind it — and asks whether the plan MOVED. Take the
 * `isAtWorkplace = presenceTest(…)` argument out of `LevellingActions.kt` and this is the only
 * check in the tree that goes red.
 *
 * WHAT IT COSTS, and it is worth it: an undo manager, an answering machine for the two questions
 * the action asks, and a baseline list. `LevellingNoDateMessageTest` records that no test in this
 * tree drove the action and names that gap; this is the part of the gap B3 had to close, because
 * B3's failure mode lives inside it.
 *
 * 7 September 2026 is a Monday, the 9th its Wednesday.
 */
class HomeWorkWiringTest {

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

  private val montag = LocalDate.of(2026, 9, 7)
  private val mittwoch = montag.plusDays(2)

  /** Runs the edit at once; the levelling only needs it as a bracket. */
  private class SofortUndoManager : GPUndoManager {
    override fun undoableEdit(localizedName: String, runnableEdit: Runnable) = runnableEdit.run()
    override fun canUndo() = false
    override fun canRedo() = false
    override fun undo() = Unit
    override fun redo() = Unit
    override val undoPresentationName = ""
    override val redoPresentationName = ""
    override fun addUndoableEditListener(listener: GPUndoListener) = Unit
    override fun removeUndoableEditListener(listener: GPUndoListener) = Unit
    override fun die() = Unit
    override fun addUndoableEditTxnFactory(factory: UndoableEditTxnFactory) = Unit
  }

  /**
   * Answers the questions the action asks, in order: YES to the preview („shall I write this"),
   * NO to the baseline („shall I save the current dates first"). The baseline is declined on
   * purpose — it writes a temporary file, and this check is about the levelling and not about that.
   */
  private class Antwortmaschine : AskBeforeWriting {
    val fragen = mutableListOf<String>()
    override fun ask(message: String, answer: (Boolean) -> Unit) {
      fragen.add(message)
      answer(fragen.size == 1)
    }
  }

  private class Projekt {
    val builder: TestSetupHelper.TaskManagerBuilder =
      TestSetupHelper.newTaskManagerBuilder().withCalendar(WeekendCalendarImpl())
    val taskManager: TaskManager = builder.build()
    val resourceManager: HumanResourceManager = builder.resourceManager
    val resourceProperties: CustomPropertyManager get() = resourceManager.customPropertyManager
    val taskProperties: CustomPropertyManager get() = taskManager.customPropertyManager
    val meldungen = mutableListOf<Pair<Boolean, String>>()
    val fragen = Antwortmaschine()

    fun person(name: String): HumanResource =
      resourceManager.create(name, 0).also {
        it.setValue(EffortDrivenProperties.findOrCreateResourceHours(resourceProperties), 8.0)
      }

    fun vorgang(name: String, start: LocalDate, aufwand: Double, tage: Long): Task =
      taskManager.newTaskBuilder().withName(name).withStartDate(start.toModelDate())
        .withDuration(taskManager.createLength(tage)).build().also {
          it.customValues.setValue(
            EffortDrivenProperties.findOrCreateTaskEffort(taskProperties), aufwand)
        }

    /** The menu item, built as `GanttProject` builds it. */
    fun nivellieren(heute: LocalDate) {
      LevellingAction(taskManager, resourceManager, taskProperties, resourceProperties,
        SofortUndoManager(), { mutableListOf<GanttPreviousState>() }, { heute },
        { fehler, text -> meldungen.add(fehler to text) }, fragen)
        .actionPerformed(null)
    }
  }

  private fun HumanResource.zuHauseAm(projekt: Projekt, tag: LocalDate) {
    this.setHomeOfficePeriods(projekt.resourceProperties,
      HomeOfficePeriods.parse(tag.toString()).periods)
  }

  private fun Task.zuordnen(person: HumanResource) {
    this.assignmentCollection.addAssignment(person).apply {
      load = 100f
      isBlocking = true
    }
  }

  /**
   * THE CHECK ITSELF: the menu item, run on a plan whose one task needs somebody on site and whose
   * one indispensable person is at home on the Wednesday it would otherwise cover.
   *
   * Three working days from Monday are Mon, Tue, Wed. With the channel wired up the run has to move
   * the task past the Wednesday; without it the task stays on Monday and nothing at all is said.
   */
  @Test
  fun `die nivellierung des laufenden programms reicht die heimarbeit wirklich durch`() {
    val projekt = Projekt()
    val a = projekt.person("Natalie")
    a.zuHauseAm(projekt, mittwoch)
    val vorgang = projekt.vorgang("Abnahme", montag, 24.0, tage = 3L)
    vorgang.zuordnen(a)
    applyHomeWorkMark(vorgang.customValues, projekt.taskProperties, HomeWorkMark.ON_SITE)

    assertEquals(montag, vorgang.start.time.toModelLocalDate(),
      "Vorbedingung: der Vorgang liegt vor dem Lauf am Montag")

    projekt.nivellieren(montag)

    assertTrue(projekt.fragen.fragen.isNotEmpty(),
      "der Lauf muss ueberhaupt bis zur Vorschau gekommen sein; gemeldet wurde " +
        "${projekt.meldungen}")
    val nachher = vorgang.start.time.toModelLocalDate()
    assertNotEquals(montag, nachher,
      "der Vorgang braucht jemanden vor Ort und Natalie ist am Mittwoch zu Hause -- das " +
        "laufende Programm muss ihn verschieben. Bleibt er liegen, ist der Kanal in " +
        "LevellingActions.kt nicht verdrahtet: es uebersetzt, jede Einheitenpruefung bleibt " +
        "gruen, und im Programm passiert nichts")
    assertTrue(nachher.isAfter(mittwoch),
      "und er muss hinter dem Heimarbeitstag liegen, gefunden wurde $nachher")
  }

  /**
   * THE COMPARISON CASE, THROUGH THE SAME DOOR: the same plan without the marking is not moved by
   * the run at all.
   *
   * Without it the check above would also pass if the wiring sent EVERY task off every home-office
   * day — the deadly mistake, arriving through the menu item instead of through the model.
   */
  @Test
  fun `ohne markierung verschiebt derselbe lauf nichts`() {
    val projekt = Projekt()
    val a = projekt.person("Natalie")
    a.zuHauseAm(projekt, mittwoch)
    val vorgang = projekt.vorgang("Auswertung", montag, 24.0, tage = 3L)
    vorgang.zuordnen(a)

    projekt.nivellieren(montag)

    assertEquals(montag, vorgang.start.time.toModelLocalDate(),
      "der Vorgang traegt keine Markierung: Natalie arbeitet am Mittwoch, nur von zu Hause, und " +
        "der Lauf darf ihn nicht bewegen")
  }
}
