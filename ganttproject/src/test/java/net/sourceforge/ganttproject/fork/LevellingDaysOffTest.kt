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

import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import biz.ganttproject.core.calendar.GanttDaysOff
import biz.ganttproject.core.calendar.WeekendCalendarImpl
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.customproperty.CustomPropertyManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * Levelling and the days off of the people involved -- THE PASS-THROUGH ONLY.
 *
 * WHAT THIS STAGE IS ABOUT AND WHAT IT IS NOT: measured on 27.08.2026, levelling does not know
 * about days off AT ALL -- `grep -ci daysoff` gives 0 in `ResourceLevelling.kt`,
 * `LevellingAdapter.kt` and `LevellingActions.kt`. Without a channel there is nothing a rule
 * could be cut against. So a channel is built here and NOTHING ELSE. The rule "the absence of a
 * blocking person moves the Task" is a later stage; whoever looks for it here will not find it,
 * and that is deliberate.
 *
 * The consequence, and it is checked below: as long as nobody is marked as blocking -- and at
 * this stage NOBODY is, because the marking does not exist yet -- levelling has to deliver
 * exactly the same results as before. The information may arrive and stay unused.
 */
class LevellingDaysOffTest {

  // GanttDaysOff builds its dates through CalendarFactory, and that has to be woken up first --
  // the same opening as in LevellingWriteBackTest, and for the same reason.
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

  /**
   * A project with a real resource manager, a real Task and a real day off.
   *
   * NOT hand-built objects, and that is the point of the exercise: what is to be checked is
   * whether the information survives the conversion out of the model -- a `LevelTask` typed by
   * hand would answer a question nobody asked.
   */
  private class Project {
    val builder: TestSetupHelper.TaskManagerBuilder =
      TestSetupHelper.newTaskManagerBuilder().withCalendar(WeekendCalendarImpl())
    val taskManager: TaskManager = builder.build()
    val resourceManager: HumanResourceManager = builder.resourceManager
    val resourceProperties: CustomPropertyManager get() = resourceManager.customPropertyManager
    val taskProperties: CustomPropertyManager get() = taskManager.customPropertyManager

    fun resource(name: String, id: Int): HumanResource = resourceManager.create(name, id)

    fun task(name: String, start: LocalDate, effortHours: Double): Task =
      taskManager.newTaskBuilder().withName(name).withStartDate(start.toModelDate())
        .withDuration(taskManager.createLength(1L)).build().also {
          it.customValues.setValue(
            EffortDrivenProperties.findOrCreateTaskEffort(taskProperties), effortHours)
        }

    fun assign(task: Task, resource: HumanResource, load: Float) {
      task.assignmentCollection.addAssignment(resource).load = load
    }

    /**
     * A day off, entered the way the program enters it.
     *
     * THE END IS EXCLUSIVE. That is not decided here, it is what the surrounding code does --
     * the reasoning together with the five places it was measured at is in `DaysOffDuration.kt`.
     * `GanttDaysOff(27., 28.)` is the 27th ALONE.
     */
    fun dayOff(resource: HumanResource, from: LocalDate, toExclusive: LocalDate) {
      resource.addDaysOff(GanttDaysOff(from.toModelDate(), toExclusive.toModelDate()))
    }

    /** The real conversion, called exactly as `collectLevelTasks` is called in the action. */
    fun levelTasks(): List<LevelTask> =
      collectLevelTasks(taskManager, taskProperties, resourceProperties,
        LocalDate.of(2026, 9, 1), true)
  }

  /** A Monday, far enough in the future that nothing counts as left lying. */
  private val MONTAG: LocalDate = LocalDate.of(2026, 9, 14)

  /**
   * THE RED TEST OF THIS STAGE: levelling has no channel at all for absence.
   *
   * WHY THIS ONE IS WRITTEN WITH REFLECTION, which is otherwise not the style here: a direct call
   * with the additional argument would not COMPILE before the change, and a build that does not
   * come up is not a falling test -- it is no test. Reflection lets the check run against the
   * state of the day and fail with a sentence one can read. A check one has never seen fail is
   * worth nothing.
   *
   * It stays in afterwards, because it pins the one thing the rest of the file cannot see: that
   * the channel is part of the SIGNATURE and does not merely happen to be filled at one call
   * site.
   */
  @Test
  fun `die verteilung nimmt eine auskunft ueber ausfallzeiten entgegen`() {
    val levelTasks = Class.forName("net.sourceforge.ganttproject.fork.ResourceLevellingKt")
      .methods.single { it.name == "levelTasks" }
    assertEquals(6, levelTasks.parameterCount,
      "levelTasks hat ${levelTasks.parameterCount} Parameter: " +
        levelTasks.parameterTypes.joinToString { it.simpleName } +
        " -- es fehlt der, ueber den die Abwesenheit einer Person hereinkommt. Ohne ihn gibt es " +
        "nichts, wogegen eine Regel geschnitten werden koennte.")
  }

  /**
   * NACHWEIS 1, and the reason the file exists: the information arrives through the REAL
   * conversion.
   *
   * The person key is deliberately NOT written out as a literal but taken from
   * `collectLevelTasks`. That is the joint the whole pass-through hangs on: the availability is
   * asked for with the same string the capacity pools are named after. If the two halves ever
   * spoke different languages -- resource id here, resource name there -- the channel would be
   * green in itself and would report on nobody.
   */
  @Test
  fun `eine echte ausfallzeit erreicht die verteilung`() {
    val projekt = Project()
    val p = projekt.resource("P", 1)
    val z = projekt.task("Z", MONTAG, 8.0)
    projekt.assign(z, p, 100f)
    // Monday alone: the end is exclusive.
    projekt.dayOff(p, MONTAG, MONTAG.plusDays(1))

    val person = projekt.levelTasks().single().loads.keys.single()
    val istVerfuegbar = availabilityTest(projekt.resourceManager)

    assertFalse(istVerfuegbar(person, MONTAG),
      "am Montag hat P Urlaub -- die Verteilung muss das erfahren koennen")
    assertTrue(istVerfuegbar(person, MONTAG.plusDays(1)),
      "der Dienstag ist kein Urlaub: das Intervallende ist EXKLUSIV")
  }

  /**
   * The counter-check that keeps the one above honest. Without it, "always absent" would pass
   * just as well -- and a channel that reports everybody as absent is not a channel, it is a
   * fault.
   */
  @Test
  fun `wer keine ausfallzeit hat gilt an jedem tag als verfuegbar`() {
    val projekt = Project()
    val p = projekt.resource("P", 1)
    val q = projekt.resource("Q", 2)
    val z = projekt.task("Z", MONTAG, 8.0)
    projekt.assign(z, p, 100f)
    projekt.dayOff(p, MONTAG, MONTAG.plusDays(1))

    val istVerfuegbar = availabilityTest(projekt.resourceManager)
    assertTrue(istVerfuegbar(q.id.toString(), MONTAG),
      "Q hat keinen Urlaub eingetragen -- der von P darf nicht auf Q abfaerben")
    assertTrue(istVerfuegbar(SHARED_POOL, MONTAG),
      "der gemeinsame Topf ist keine Person und hat keinen Urlaub")
    assertTrue(istVerfuegbar("gibt-es-nicht", MONTAG),
      "eine unbekannte Kennung ist keine Abwesenheit: im Zweifel verfuegbar")
  }

  /**
   * NACHWEIS 2 on the wired path: with the days off passed in, levelling delivers EXACTLY what it
   * delivers without them.
   *
   * WHY THIS IS CHECKED AGAINST A SECOND RUN and not against dates written out by hand: written
   * dates would have to be adjusted the moment anything else about the calculation changes, and
   * then this test would say nothing about the pass-through any more. The comparison against the
   * run without the channel keeps saying the same thing for ever: THIS parameter changes nothing.
   */
  @Test
  fun `die durchreiche veraendert das ergebnis nicht`() {
    val projekt = Project()
    val p = projekt.resource("P", 1)
    val a = projekt.task("A", MONTAG, 24.0)
    val b = projekt.task("B", MONTAG, 16.0)
    projekt.assign(a, p, 100f)
    projekt.assign(b, p, 100f)
    // A fortnight of holiday right across both Tasks. It has to remain without effect.
    projekt.dayOff(p, MONTAG, MONTAG.plusDays(14))

    val tasks = projekt.levelTasks()
    val abWann = MONTAG
    val kalender = workingDaysPerTask(projekt.taskManager, projekt.resourceProperties)
    val dauer = durationAtStart(projekt.taskManager, projekt.taskProperties,
      projekt.resourceProperties)

    val ohne = levelTasks(tasks, abWann, kalender, dauer)
    // Exactly the argument list of the call site in LevellingActions.
    val mit = levelTasks(tasks, abWann, kalender, dauer,
      isAvailable = availabilityTest(projekt.resourceManager))

    assertEquals(ohne.starts, mit.starts,
      "P0 reicht die Ausfallzeit nur durch; sie darf keinen Termin bewegen")
    assertEquals(ohne.durations, mit.durations, "und auch keine Dauer aendern")
    assertEquals(ohne.conflicts, mit.conflicts, "und keine Meldung erzeugen")
  }
}
