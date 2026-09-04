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

import biz.ganttproject.core.calendar.GanttDaysOff
import biz.ganttproject.core.calendar.WeekendCalendarImpl
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.task.ResourceAssignment
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * AXIS A IN LEVELLING: a Task may only lie where every person marked as BLOCKING is available.
 *
 * WHAT THE MARKING MEANS, and why the plain assignment cannot say it: today an assigned person's
 * day off takes their hours out of that day and nothing else -- the Task stays where it is (see
 * `DaysOffDuration.kt`). That is right for most people and wrong for some: whoever has to be
 * present for the Task to happen at all -- supervision, an instruction, an acceptance -- takes
 * the Task with them when they are away. Which of the two a person is cannot be read off the
 * assignment; it has to be marked, and `ResourceAssignment.isBlocking` is that marking.
 *
 * MEASURED HERE THROUGH THE REAL CONVERSION, not against hand-built [LevelTask] objects. The
 * lesson is recorded in `LevellingWriteBackTest`: `collectLevelTasks` had exactly one caller in
 * the whole tree and two defects lived in it undisturbed, because every test typed its input by
 * hand and therefore could not see them. A marking that is set on the model and never arrives at
 * the calculation would look exactly like a working one in a hand-built test.
 */
class LevellingBlockingTest {

  // GanttDaysOff builds its dates through CalendarFactory, and that has to be woken up first --
  // the same opening as in LevellingDaysOffTest, and for the same reason.
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
   * A project with a real resource manager, real Tasks, real assignments and real days off.
   *
   * The same shape as in `LevellingDaysOffTest`, with one addition: [assign] hands the assignment
   * back, because the marking of axis A sits on it.
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

    fun assign(task: Task, resource: HumanResource, load: Float): ResourceAssignment =
      task.assignmentCollection.addAssignment(resource).also { it.load = load }

    /**
     * A day off, entered the way the program enters it. THE END IS EXCLUSIVE -- the reasoning
     * together with the five places it was measured at is in `DaysOffDuration.kt`.
     */
    fun dayOff(resource: HumanResource, from: LocalDate, toExclusive: LocalDate) {
      resource.addDaysOff(GanttDaysOff(from.toModelDate(), toExclusive.toModelDate()))
    }

    /** The real conversion, called exactly as `collectLevelTasks` is called in the action. */
    fun levelTasks(): List<LevelTask> =
      collectLevelTasks(taskManager, taskProperties, resourceProperties,
        LocalDate.of(2026, 9, 1), true)

    /** Levelling with exactly the argument list of the call site in `LevellingActions`. */
    fun verteile(): LevelResult = levelTasks(
      levelTasks(), MONTAG, workingDaysPerTask(taskManager, resourceProperties),
      durationAtStart(taskManager, taskProperties, resourceProperties),
      isAvailable = availabilityTest(resourceManager))

    fun startOf(result: LevelResult, task: Task): LocalDate? = result.starts[task.taskID.toString()]
  }

  companion object {
    /** A Monday, far enough in the future that nothing counts as left lying. */
    private val MONTAG: LocalDate = LocalDate.of(2026, 9, 14)
    private val MITTWOCH: LocalDate = MONTAG.plusDays(2)
    private val DONNERSTAG: LocalDate = MONTAG.plusDays(3)
    private val FREITAG: LocalDate = MONTAG.plusDays(4)
  }

  /**
   * THE RED TEST OF THIS STAGE: a blocking person's holiday in the middle of the Task does not
   * move it.
   *
   * 24 hours of effort at 8 hours a day is three days: Monday, Tuesday, Wednesday. `P` is marked
   * as blocking and is away on the Wednesday. The Task can therefore not lie where it lies -- it
   * has to start on the Thursday, where all three of its days find `P` at work.
   *
   * Before axis A takes effect the Task stays on the Monday, because levelling reads the marking
   * not at all: the day off arrives (P0 laid that channel) and nothing is hung on it.
   */
  @Test
  fun `der urlaub einer blockierenden person verschiebt den vorgang`() {
    val projekt = Project()
    val p = projekt.resource("P", 1)
    val z = projekt.task("Z", MONTAG, 24.0)
    projekt.assign(z, p, 100f).isBlocking = true
    // The Wednesday alone: the end is exclusive.
    projekt.dayOff(p, MITTWOCH, DONNERSTAG)

    val ergebnis = projekt.verteile()

    assertEquals(DONNERSTAG, projekt.startOf(ergebnis, z),
      "P blockiert und hat am Mittwoch Urlaub; die drei Tage des Vorgangs muessen hinter den " +
        "Urlaub wandern")
  }

  /**
   * THE COUNTER-CHECK THAT KEEPS THE ONE ABOVE HONEST, and it is the more important half of
   * NACHWEIS 1: the same holiday, the same day, the same person -- only the marking removed.
   *
   * Without it, "the Task moves" would also be satisfied by a version that moves for EVERY day
   * off, and that version would be a different program: today an assigned person's holiday takes
   * their hours out of the day and leaves the Task alone, and for the vast majority of
   * assignments that is right. Axis A has to act on the marked ones AND ONLY on them.
   */
  @Test
  fun `der urlaub einer nicht blockierenden person verschiebt nichts`() {
    val projekt = Project()
    val p = projekt.resource("P", 1)
    val z = projekt.task("Z", MONTAG, 24.0)
    // Not marked. `isBlocking` is false by default -- see ResourceAssignment.setBlocking.
    projekt.assign(z, p, 100f)
    projekt.dayOff(p, MITTWOCH, DONNERSTAG)

    val ergebnis = projekt.verteile()

    assertEquals(MONTAG, projekt.startOf(ergebnis, z),
      "P blockiert NICHT; der Urlaub darf den Vorgang nicht anfassen -- sonst wirkt Achse A auf " +
        "jede Zuordnung und nicht auf die markierten")
  }

  /**
   * NACHWEIS 2, AND THE REASON THE WHOLE UNDERTAKING EXISTS: A yes, B no.
   *
   * `W` does the work, 24 hours of it at 8 hours a day, three days. `A` is the one who has to be
   * THERE -- supervision, an instruction, an acceptance -- and contributes nothing: load 0, and
   * `isNoEffort` set on top of it. That person must move the Task all the same.
   *
   * WHY THIS IS THE HARD CASE AND NOT A CORNER ONE: every plausible shortcut fails it. Reading
   * the marking off the load loses `A`, because their load is 0. Reading it off "does this person
   * contribute?" loses them as well, because `isNoEffort` says they do not. The two axes are
   * independent in the model, and the only way to get this case right is to keep them
   * independent in levelling too.
   *
   * WHAT IS CHECKED HERE AND WHAT IS NOT: that a set `isNoEffort` does not LIFT the blocking.
   * What axis B does to the duration is the neighbouring stage's business
   * (`EffortDrivenDurationAlgorithm.kt`), and nothing here asserts anything about it.
   */
  @Test
  fun `wer anwesend sein muss aber nicht mitarbeitet verschiebt den vorgang trotzdem`() {
    val projekt = Project()
    val w = projekt.resource("W", 1)
    val a = projekt.resource("A", 2)
    val z = projekt.task("Abnahme", MONTAG, 24.0)
    projekt.assign(z, w, 100f)
    projekt.assign(z, a, 0f).also {
      it.isBlocking = true
      // Axis B, set on purpose: it must not switch axis A off.
      it.isNoEffort = true
    }
    projekt.dayOff(a, MITTWOCH, DONNERSTAG)

    val umgerechnet = projekt.levelTasks().single()
    assertEquals(0, umgerechnet.loads[a.id.toString()],
      "Aufbau: A traegt nichts bei -- Last 0, wie eingetragen")
    assertEquals(setOf(a.id.toString()), umgerechnet.blocking,
      "A blockiert, obwohl A nichts beitraegt; W arbeitet, blockiert aber nicht")

    val ergebnis = projekt.verteile()

    assertEquals(DONNERSTAG, projekt.startOf(ergebnis, z),
      "A muss dabei sein und ist am Mittwoch weg; ein gesetztes isNoEffort hebt die " +
        "Blockierung nicht auf")
  }

  /**
   * NACHWEIS 3: two blocking people give the INTERSECTION of their time, not the union.
   *
   * `P` is away on the Wednesday, `Q` on the Thursday of the same week. Neither absence alone
   * would push the Task past the Friday -- but together they leave no room in this week at all:
   * Monday to Wednesday hits `P`, Tuesday to Thursday hits both, Wednesday to Friday hits both
   * again. The first three consecutive days on which both are at work begin on the Friday and run
   * into the following week.
   *
   * WHY THE UNION WOULD BE THE WRONG ANSWER AND WOULD LOOK RIGHT: it would let the Task start on
   * the Monday again, because for every day of that window SOMEBODY is there. "Everybody who has
   * to be there is there" is not "somebody is there", and the difference only becomes visible
   * once two people are marked -- with one, both readings agree.
   */
  @Test
  fun `zwei blockierende personen ergeben die schnittmenge ihrer zeit`() {
    val projekt = Project()
    val p = projekt.resource("P", 1)
    val q = projekt.resource("Q", 2)
    val z = projekt.task("Z", MONTAG, 24.0)
    projekt.assign(z, p, 50f).isBlocking = true
    projekt.assign(z, q, 50f).isBlocking = true
    projekt.dayOff(p, MITTWOCH, DONNERSTAG)
    projekt.dayOff(q, DONNERSTAG, FREITAG)

    val umgerechnet = projekt.levelTasks().single()
    assertEquals(setOf(p.id.toString(), q.id.toString()), umgerechnet.blocking,
      "Aufbau: beide sind als blockierend markiert")

    val ergebnis = projekt.verteile()

    assertEquals(FREITAG, projekt.startOf(ergebnis, z),
      "P fehlt am Mittwoch, Q am Donnerstag: erst ab Freitag sind BEIDE drei Tage lang da. " +
        "Der Montag waere die Antwort der Vereinigung und damit die falsche")
  }

  /**
   * The other half of NACHWEIS 3, and it is what tells the intersection apart from "any marking
   * at all pushes the Task to the end of the week": the SAME two blocking people, both away on
   * the SAME day. One day lost, not two.
   *
   * Without this the test above would also pass for a version that simply adds up every absence
   * of every blocking person, whether they fall on the same day or not.
   */
  @Test
  fun `zwei blockierende personen am selben tag kosten auch nur diesen tag`() {
    val projekt = Project()
    val p = projekt.resource("P", 1)
    val q = projekt.resource("Q", 2)
    val z = projekt.task("Z", MONTAG, 24.0)
    projekt.assign(z, p, 50f).isBlocking = true
    projekt.assign(z, q, 50f).isBlocking = true
    projekt.dayOff(p, MITTWOCH, DONNERSTAG)
    projekt.dayOff(q, MITTWOCH, DONNERSTAG)

    val ergebnis = projekt.verteile()

    assertEquals(DONNERSTAG, projekt.startOf(ergebnis, z),
      "beide fehlen am selben Mittwoch; ab Donnerstag sind beide drei Tage lang da")
  }

  /**
   * The whole point of the default, checked at the place where it is easiest to get wrong: a plan
   * in which NOBODY is marked comes out exactly as it did before, even when the people in it are
   * away for a fortnight.
   *
   * WHY THE COMPARISON IS AGAINST A SECOND RUN and not against dates written out by hand: written
   * dates would have to be adjusted the moment anything else about the calculation changes, and
   * this test would then stop saying anything about the marking. The comparison against the run
   * without the days off keeps saying the same thing for ever. The same reasoning, and the same
   * shape, as in `LevellingDaysOffTest`.
   */
  @Test
  fun `ohne markierung aendert die ausfallzeit nach wie vor nichts`() {
    val projekt = Project()
    val p = projekt.resource("P", 1)
    val a = projekt.task("A", MONTAG, 24.0)
    val b = projekt.task("B", MONTAG, 16.0)
    projekt.assign(a, p, 100f)
    projekt.assign(b, p, 100f)
    projekt.dayOff(p, MONTAG, MONTAG.plusDays(14))

    val tasks = projekt.levelTasks()
    assertEquals(setOf(emptySet<String>()), tasks.map { it.blocking }.toSet(),
      "Aufbau: keine Zuordnung ist markiert")

    val kalender = workingDaysPerTask(projekt.taskManager, projekt.resourceProperties)
    val dauer = durationAtStart(projekt.taskManager, projekt.taskProperties,
      projekt.resourceProperties)
    val ohne = levelTasks(tasks, MONTAG, kalender, dauer)
    val mit = levelTasks(tasks, MONTAG, kalender, dauer,
      isAvailable = availabilityTest(projekt.resourceManager))

    assertEquals(ohne.starts, mit.starts, "kein Termin darf sich bewegen")
    assertEquals(ohne.durations, mit.durations, "keine Dauer darf sich aendern")
    assertEquals(ohne.conflicts, mit.conflicts, "keine Meldung darf entstehen")
  }

  /**
   * A blocking person WITHOUT any day off changes nothing either.
   *
   * The marking on its own is not a restriction -- it only says which absences count. Somebody
   * who is never away restricts nothing, and a version that treated "marked" as "suspicious" and
   * shifted the Task to be safe would pass every test above and this one alone would catch it.
   */
  @Test
  fun `eine blockierende person ohne urlaub verschiebt nichts`() {
    val projekt = Project()
    val p = projekt.resource("P", 1)
    val z = projekt.task("Z", MONTAG, 24.0)
    projekt.assign(z, p, 100f).isBlocking = true

    val ergebnis = projekt.verteile()

    assertEquals(MONTAG, projekt.startOf(ergebnis, z),
      "markiert, aber nie abwesend: es gibt nichts zu umgehen")
  }
}
