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
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * [fork change] WHERE THE THREE BRANCHES OF 03.09.2026 CROSS.
 *
 * Three changes were merged into `main` on that day and each brought its own checks:
 *
 *  * `arbeitswoche-wirkung` -- the working day test is built PER TASK out of the working weeks of
 *    the people on it (`WorkWeekWorkingDays.forTask`).
 *  * `ausfall-nimmt-tag` -- the absence of somebody marked `isBlocking` takes the WHOLE day in
 *    `daysNeededWithDaysOff`, not merely that person's hours.
 *  * `nivellierung-ausfallzeiten` -- levelling derives its durations from that very walk, and
 *    remembers per task what it has read out of the model and out of the calendar.
 *
 * WHY A FILE OF ITS OWN. Not one of the three sets of checks puts two of the properties into the
 * same plan, so a resolution of the merge could drop one of them entirely and every one of the
 * three sets would stay green. What is checked here is only the crossings -- each one is a
 * statement that cannot be made on any of the three branches alone.
 *
 * THE DATES: Monday 07.09.2026, the day the measurements of 03.09.2026 were made on. 12.09. is the
 * Saturday of that week, 14.09. the following Monday.
 */
class ZusammenfuehrungKreuzungTest {

  // GanttDaysOff builds its dates through CalendarFactory, which has to be woken up first --
  // the same opening as in LevellingDaysOffDurationTest, and for the same reason.
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

  private class Projekt {
    val builder: TestSetupHelper.TaskManagerBuilder =
      TestSetupHelper.newTaskManagerBuilder().withCalendar(WeekendCalendarImpl())
    val taskManager: TaskManager = builder.build()
    val resourceManager: HumanResourceManager = builder.resourceManager
    val resourceProperties: CustomPropertyManager get() = resourceManager.customPropertyManager
    val taskProperties: CustomPropertyManager get() = taskManager.customPropertyManager

    /** Eight hours a day for everybody; [woche] is the working week, `null` meaning none entered. */
    fun person(name: String, id: Int, woche: String? = null): HumanResource =
      resourceManager.create(name, id).also { person ->
        person.setValue(
          EffortDrivenProperties.findOrCreateResourceHours(resourceProperties), 8.0)
        if (woche != null) {
          person.setWorkWeek(resourceProperties, WorkWeekSchedule.parse(woche).schedule)
        }
      }

    fun vorgang(name: String, start: LocalDate, aufwand: Double, tage: Long = 1L): Task =
      taskManager.newTaskBuilder().withName(name).withStartDate(start.toModelDate())
        .withDuration(taskManager.createLength(tage)).build().also {
          it.customValues.setValue(
            EffortDrivenProperties.findOrCreateTaskEffort(taskProperties), aufwand)
        }

    fun zuordnen(vorgang: Task, person: HumanResource, last: Float = 100f,
                 blockierend: Boolean = false) {
      vorgang.assignmentCollection.addAssignment(person).also {
        it.load = last
        if (blockierend) it.isBlocking = true
      }
    }

    /** A day off, entered the way the program enters it. THE END IS EXCLUSIVE. */
    fun ausfall(person: HumanResource, von: LocalDate, bisAusschliesslich: LocalDate) {
      person.addDaysOff(GanttDaysOff(von.toModelDate(), bisAusschliesslich.toModelDate()))
    }

    /** The day grid this task actually gets -- the working weeks of the people on it. */
    fun gitter(vorgang: Task): (LocalDate) -> Boolean =
      WorkWeekWorkingDays(taskManager.calendar, resourceProperties).forTask(vorgang)

    /** What the scheduler writes, asked on the grid of the task itself. */
    fun geplanteDauer(vorgang: Task, start: LocalDate): Int? =
      vorgang.durationDaysWithDaysOff(taskProperties, resourceProperties, start, gitter(vorgang))

    fun levelTaskListe(): List<LevelTask> =
      collectLevelTasks(taskManager, taskProperties, resourceProperties, HEUTE, true)

    /**
     * What levelling lays the window with -- for SEVERAL tasks out of ONE run.
     *
     * The tasks have to go through the same [durationAtStart] object, because that object is where
     * the memories live and it is those memories that the third crossing check is about.
     */
    fun verteilteDauern(start: LocalDate, vararg vorgaenge: Task): List<Int> {
      val liste = levelTaskListe()
      val dauerAn = durationAtStart(taskManager, taskProperties, resourceProperties)
      return vorgaenge.map { v -> dauerAn(liste.single { it.id == v.taskID.toString() }, start) }
    }

    fun verteilteDauer(vorgang: Task, start: LocalDate): Int =
      verteilteDauern(start, vorgang).single()
  }

  companion object {
    /** Monday, 07.09.2026. */
    private val MONTAG: LocalDate = LocalDate.of(2026, 9, 7)
    private val MITTWOCH: LocalDate = LocalDate.of(2026, 9, 9)
    private val DONNERSTAG: LocalDate = LocalDate.of(2026, 9, 10)
    private val SAMSTAG: LocalDate = LocalDate.of(2026, 9, 12)
    private val HEUTE: LocalDate = LocalDate.of(2026, 9, 1)
  }

  // ---- CROSSING 1: a working week AND a day off on the same person --------------------------

  /**
   * A person with a WORKING WEEK entered AND a day off: levelling plans the duration with both.
   *
   * THE PLAN IS CUT SO THAT EACH OF THE TWO PROPERTIES CHANGES THE NUMBER ON ITS OWN, which is
   * what no check on any of the three branches does. The person works Mon, Tue, Thu, Fri -- no
   * Wednesday -- at eight hours, and is away on the Wednesday AND on the Thursday. 32 hours:
   *
   *  * BOTH: Wednesday is not a day of this task at all, so the absence on it costs nothing; the
   *    Thursday is one and the absence on it costs a day. Mon 8, Tue 16, Thu 0, Fri 24, Mon 32 --
   *    FIVE days.
   *  * WORKING WEEK LOST (the project calendar instead): the Wednesday becomes a day of the task
   *    and the absence on it costs a second day. Mon 8, Tue 16, Wed 0, Thu 0, Fri 24, Mon 32,
   *    Tue 40 -- SIX days.
   *  * DAYS OFF LOST (`daysNeeded` instead of `daysNeededWithDaysOff`): four days of eight hours
   *    and nothing in the way -- FOUR days.
   *
   * Five, six, four: the answer names which of the two went missing.
   */
  @Test
  fun `arbeitswoche und ausfallzeit zugleich - die nivellierung plant mit beidem`() {
    val p = Projekt()
    val a = p.person("Anke", 1, woche = "1,2,4,5")
    val t = p.vorgang("T", MONTAG, 32.0)
    p.zuordnen(t, a)
    p.ausfall(a, MITTWOCH, MITTWOCH.plusDays(1))
    p.ausfall(a, DONNERSTAG, DONNERSTAG.plusDays(1))

    assertEquals(5, p.geplanteDauer(t, MONTAG),
      "der Planer rechnet auf dem Raster der Arbeitswoche UND mit der Ausfallzeit: der Mittwoch " +
        "ist gar kein Tag dieses Vorgangs, der Donnerstag ist einer und faellt aus")
    assertEquals(5, p.verteilteDauer(t, MONTAG),
      "die Nivellierung muss dasselbe Fenster legen -- vier Tage hiesse, die Ausfallzeit ist " +
        "verlorengegangen, sechs Tage hiesse, die Arbeitswoche ist verlorengegangen")
  }

  // ---- CROSSING 2: a blocking person away, and a working week on top -------------------------

  /**
   * The absence of an INDISPENSABLE person falls on a day that the WORKING WEEK has already taken
   * out of the task: the day drops out for both reasons, and the duration does not grow twice.
   *
   * Anke has nothing entered, Bodo works Mon, Tue, Thu, Fri and is marked `isBlocking`. The grid
   * of the task is therefore Mon, Tue, Thu, Fri -- Bodo's Wednesday is missing and Anke cannot
   * win it back. Bodo is away exactly on that Wednesday. 56 hours at 16 a day.
   *
   * A day that is not a day of the task is never walked over, so axis A is never asked about it
   * and cannot charge for it a second time. The check is that the answer is the one the same plan
   * gives WITHOUT the absence -- four days.
   *
   * IF THE WORKING WEEK IS LOST the Wednesday becomes a day of the task, axis A does fire on it,
   * and the answer is five. That is the double count this check exists for.
   */
  @Test
  fun `ein ausfall auf einem tag, den die arbeitswoche schon genommen hat, kostet nicht zweimal`() {
    val ohne = Projekt()
    val ohneAnke = ohne.person("Anke", 1)
    val ohneBodo = ohne.person("Bodo", 2, woche = "1,2,4,5")
    val ohneT = ohne.vorgang("T", MONTAG, 56.0)
    ohne.zuordnen(ohneT, ohneAnke)
    ohne.zuordnen(ohneT, ohneBodo, blockierend = true)

    val p = Projekt()
    val anke = p.person("Anke", 1)
    val bodo = p.person("Bodo", 2, woche = "1,2,4,5")
    val t = p.vorgang("T", MONTAG, 56.0)
    p.zuordnen(t, anke)
    p.zuordnen(t, bodo, blockierend = true)
    p.ausfall(bodo, MITTWOCH, MITTWOCH.plusDays(1))

    assertEquals(4, ohne.verteilteDauer(ohneT, MONTAG),
      "Vergleichsfall ohne jede Abwesenheit: Mo, Di, Do, Fr zu je 16 Stunden sind 56 Stunden am " +
        "vierten Tag")
    assertEquals(4, p.verteilteDauer(t, MONTAG),
      "der Mittwoch ist wegen der Arbeitswoche gar kein Tag dieses Vorgangs -- die Abwesenheit " +
        "an ihm darf ihn nicht ein zweites Mal kosten")
  }

  /**
   * The counter-check to it, and the one that has to see axis A: the same plan, with the absence
   * on a day the working week DOES leave in.
   *
   * Bodo is away on the Thursday. That day is a day of the task, Bodo is indispensable, so NOBODY
   * contributes on it -- Anke's eight hours go back into her own pot. Five days.
   *
   * IF AXIS A IS LOST -- if only Bodo's own hours were taken away instead of the whole day -- the
   * Thursday still delivers Anke's eight hours and the answer is four. That is the crossing: on
   * this branch the working week decides WHICH days exist and axis A decides what happens on one
   * of them.
   */
  @Test
  fun `auf einem tag, den die arbeitswoche laesst, nimmt der ausfall der zwingenden person alles`() {
    val p = Projekt()
    val anke = p.person("Anke", 1)
    val bodo = p.person("Bodo", 2, woche = "1,2,4,5")
    val t = p.vorgang("T", MONTAG, 56.0)
    p.zuordnen(t, anke)
    p.zuordnen(t, bodo, blockierend = true)
    p.ausfall(bodo, DONNERSTAG, DONNERSTAG.plusDays(1))

    assertEquals(5, p.geplanteDauer(t, MONTAG),
      "am Donnerstag fehlt die zwingend noetige Person: an dem Tag traegt niemand etwas bei, " +
        "auch Anke nicht")
    assertEquals(5, p.verteilteDauer(t, MONTAG),
      "die Nivellierung muss dasselbe sagen -- vier Tage hiessen, Ankes Stunden sind an dem " +
        "blockierten Donnerstag doch verbraucht worden")
  }

  // ---- CROSSING 3: two tasks, two different working weeks, ONE run ---------------------------

  /**
   * THE MOST IMPORTANT CHECK OF THIS FILE, and the only one that can see the mistake the merge was
   * most likely to make.
   *
   * `nivellierung-ausfallzeiten` remembers the calendar's answers so that the walk does not ask
   * the same day twice, and it remembered them in a map from DAY to answer. That is right exactly
   * as long as there is ONE answer per day. `arbeitswoche-wirkung` ended that: the test is built
   * per task, so whoever works Saturdays and whoever does not must get DIFFERENT answers for the
   * same Saturday. A map keyed by the day alone would hand the second task whatever the first one
   * asked -- and no check on either branch could see it, because neither branch puts two different
   * working weeks into one levelling run.
   *
   * THE PLAN IS CUT SO THAT THE POISONING CHANGES BOTH NUMBERS, in opposite directions. Both
   * people are away on Saturday 12.09.
   *
   *  * Sam works Mon-Sat. The Saturday is a day of his task, he is away on it, so it costs a day:
   *    Mon-Fri are 40 hours, the Saturday nothing, the following Monday the last 8 -- SEVEN days.
   *  * Wolf works Mon-Fri. The Saturday is not a day of his task at all, so his absence on it
   *    costs nothing: Mon-Fri 40 hours, the following Monday the last 8 -- SIX days.
   *
   * Under a shared memory the two come out EQUAL -- 7 and 7 or 6 and 6, depending on which task
   * asked first. The check therefore also states outright that they must differ.
   */
  @Test
  fun `zwei vorgaenge mit verschiedenen arbeitswochen bekommen verschiedene antworten`() {
    val p = Projekt()
    val sam = p.person("Sam", 1, woche = "1,2,3,4,5,6")
    val wolf = p.person("Wolf", 2, woche = "1,2,3,4,5")
    val samstagsarbeit = p.vorgang("samstagsarbeit", MONTAG, 48.0)
    val wochenarbeit = p.vorgang("wochenarbeit", MONTAG, 48.0)
    p.zuordnen(samstagsarbeit, sam)
    p.zuordnen(wochenarbeit, wolf)
    p.ausfall(sam, SAMSTAG, SAMSTAG.plusDays(1))
    p.ausfall(wolf, SAMSTAG, SAMSTAG.plusDays(1))

    // Both out of ONE durationAtStart -- that object is where the memories live.
    val (samDauer, wolfDauer) = p.verteilteDauern(MONTAG, samstagsarbeit, wochenarbeit)

    assertEquals(7, samDauer,
      "Sam arbeitet samstags: der Samstag ist ein Tag seines Vorgangs, er fehlt an ihm, und das " +
        "kostet einen Tag")
    assertEquals(6, wolfDauer,
      "Wolf arbeitet nicht samstags: der Samstag ist gar kein Tag seines Vorgangs, seine " +
        "Abwesenheit an ihm kostet nichts")
    assertNotEquals(samDauer, wolfDauer,
      "zwei Vorgaenge mit verschiedenen Beteiligten haben verschiedene Arbeitswochen und muessen " +
        "im selben Lauf verschiedene Antworten bekommen -- ein Gedaechtnis, das je Tag EINE " +
        "Antwort haelt, gibt dem zweiten Vorgang die des ersten")
    // And the scheduler, asked on each task's own grid, has to name the same two numbers.
    assertEquals(7, p.geplanteDauer(samstagsarbeit, MONTAG))
    assertEquals(6, p.geplanteDauer(wochenarbeit, MONTAG))
  }
}
