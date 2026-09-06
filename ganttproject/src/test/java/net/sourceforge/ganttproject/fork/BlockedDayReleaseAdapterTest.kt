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
 * THE EIGHT SETUPS OF THE MEASUREMENT OF 05.09.2026, TURNED INTO CHECKS — through the real
 * conversion, not against hand-built [LevelTask] values.
 *
 * WHY THIS FILE EXISTS BESIDE `LevellingBlockedDayCapacityTest`. That one measures the rule in the
 * calculation, where a task's duration is a number somebody typed. Here the durations are the ones
 * the program really computes: 40 hours of effort on two people at full load is three working
 * days, and a day on which an indispensable person is away delivers NOTHING, so the same three
 * days of work become four calendar days with a hole in the middle (`DaysOffDuration.kt`). The
 * hole is what this whole undertaking is about, and a hand-typed duration cannot produce it.
 *
 * WHAT IS PINNED, and it is deliberately the whole picture rather than a single date: for every
 * setup the run is written as one line, `t1=<start>+<days>|t2=<start>+<days>`, and compared with
 * the line the measurement recorded. A single date would be green for a version that got the date
 * right and the length wrong — and the length is half of what the rule changes.
 *
 * THE NUMBERS COME FROM `2026-09-05-freigabe-gemessen.md`, sections 2 and 4, and they were
 * measured against `main` at a6e408ae7. This file re-measures them against today's `main`, which
 * has the home-work series in it. Where a number no longer holds, this file is what says so.
 *
 * ROWS 6 AND 7 ARE A RECONSTRUCTION AND ARE MARKED AS ONE. The report names them „zwei zwingende,
 * versetzt" without writing the two absences out; the setup here — `B` away on the Wednesday and
 * `A` away on the Thursday, both marked — is the reading that reproduces all four of the numbers
 * the report does write down for those rows. That agreement is the evidence for the reading, and
 * it is worth naming as evidence rather than presenting the setup as quoted.
 *
 * 14 September 2026 is a Monday.
 */
class BlockedDayReleaseAdapterTest {

  // GanttDaysOff builds its dates through CalendarFactory, which has to be woken up first -- the
  // same opening as in LevellingBlockingTest, and for the same reason.
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

    /** THE END IS EXCLUSIVE -- the reasoning is in `DaysOffDuration.kt`. */
    fun dayOff(resource: HumanResource, from: LocalDate, toExclusive: LocalDate) {
      resource.addDaysOff(GanttDaysOff(from.toModelDate(), toExclusive.toModelDate()))
    }

    fun fixDate(task: Task) {
      task.customValues.setValue(findOrCreateDateFixed(taskProperties), true)
    }

    /** Levelling with exactly the argument list of the call site in `LevellingActions`. */
    fun verteile(): LevelResult = levelTasks(
      collectLevelTasks(taskManager, taskProperties, resourceProperties, HEUTE, true),
      MONTAG, workingDaysPerTask(taskManager, resourceProperties),
      durationAtStart(taskManager, taskProperties, resourceProperties),
      isAvailable = availabilityTest(resourceManager),
      isAtWorkplace = presenceTest(resourceManager, resourceProperties))

    /** One line per run: where the two tasks lie and how long they are. */
    fun zeile(vararg tasks: Pair<String, Task>): String {
      val r = verteile()
      return tasks.joinToString("|") { (name, t) ->
        val id = t.taskID.toString()
        "$name=${r.starts[id]}+${r.durations[id]}"
      }
    }
  }

  companion object {
    /** Far enough in the past that nothing in these plans counts as left lying. */
    private val HEUTE: LocalDate = LocalDate.of(2026, 9, 1)
    private val MONTAG: LocalDate = LocalDate.of(2026, 9, 14)
    private val MITTWOCH: LocalDate = MONTAG.plusDays(2)
    private val DONNERSTAG: LocalDate = MONTAG.plusDays(3)
    private val FREITAG: LocalDate = MONTAG.plusDays(4)
    private val MITTWOCH_DRAUF: LocalDate = MONTAG.plusDays(9)
  }

  /**
   * The shape all eight setups share: T1 is 40 hours on `A` and `B` at full load — three working
   * days at 16 hours a day — and T2 is the task `A` could be doing instead, 8 hours of `A` alone.
   *
   * @param blockiert whether `B` is marked as the one who has to be there. Setting it is the whole
   * of the difference the measurement is about.
   */
  private class Aufbau(val blockiert: Boolean, t2Stunden: Double = 8.0) {
    val projekt = Project()
    val a: HumanResource = projekt.resource("A", 1)
    val b: HumanResource = projekt.resource("B", 2)
    val t1: Task = projekt.task("T1", MONTAG, 40.0)
    val t2: Task = projekt.task("T2", MONTAG, t2Stunden)
    val zuordnungA: ResourceAssignment = projekt.assign(t1, a, 100f)
    val zuordnungB: ResourceAssignment = projekt.assign(t1, b, 100f).also {
      it.isBlocking = blockiert
    }

    init {
      projekt.assign(t2, a, 100f)
    }

    fun zeile(): String = projekt.zeile("t1" to t1, "t2" to t2)
  }

  /** `B` is away on the Wednesday alone. */
  private fun Aufbau.bWegAmMittwoch() = projekt.dayOff(b, MITTWOCH, DONNERSTAG)

  /** `B` is away for five working days: Wednesday to the Tuesday of the following week. */
  private fun Aufbau.bWegEineWoche() = projekt.dayOff(b, MITTWOCH, MITTWOCH_DRAUF)

  // ===============================================================================================
  // Rows 1, 3, 4 and 6: the release changes nothing here, and the measurement says why.
  // ===============================================================================================

  /**
   * ROW 1 — freely movable, one day of absence. The window search pushes the whole task behind the
   * absence, so the blocked day never enters the task's window and there is nothing to release.
   * `A`'s Monday becomes free and T2 takes it, three working days earlier than without the marking.
   *
   * THE COMPARISON RUN IS THE POINT OF THE ROW: it is where the measurement of 05.09.2026 found
   * that in the ordinary case Natalie's sentence already holds without anybody building anything.
   */
  @Test
  fun `zeile 1 frei beweglich mit einem ausfalltag`() {
    val ohne = Aufbau(blockiert = false).also { it.bWegAmMittwoch() }
    assertEquals("t1=2026-09-14+3|t2=2026-09-17+1", ohne.zeile(),
      "ohne Markierung: T1 liegt Mo-Mi und T2 kommt am Donnerstag")

    val mit = Aufbau(blockiert = true).also { it.bWegAmMittwoch() }
    assertEquals("t1=2026-09-17+3|t2=2026-09-14+1", mit.zeile(),
      "mit Markierung wandert T1 hinter den Urlaub und T2 nimmt den freien Montag -- die " +
        "Freigabe darf daran nichts aendern")
  }

  /**
   * ROW 4 — the same, with five days of absence. The task moves a whole week; the second one still
   * takes the Monday. The row is here because it is the one that shows the movement is not capped.
   */
  @Test
  fun `zeile 4 frei beweglich mit fuenf ausfalltagen`() {
    val ohne = Aufbau(blockiert = false).also { it.bWegEineWoche() }
    assertEquals("t1=2026-09-14+3|t2=2026-09-17+1", ohne.zeile(),
      "ohne Markierung aendert die ganze Urlaubswoche an der Lage nichts")

    val mit = Aufbau(blockiert = true).also { it.bWegEineWoche() }
    assertEquals("t1=2026-09-23+3|t2=2026-09-14+1", mit.zeile(),
      "mit Markierung liegt T1 hinter der Urlaubswoche und T2 auf dem freien Montag")
  }

  /**
   * ROW 3 — begun work. The marking does not act at all, and the two runs are character for
   * character the same.
   *
   * WHY, and it is worth having pinned rather than remembered: `durationAtStart` falls back to the
   * stored duration for `scheduleIsConstant && begun`, and axis A is not asked for frozen work at
   * all. Whoever ticks a task to 1 % switches the rule off for it. The measurement of 05.09.2026
   * found this and it stands in neither of the two earlier reports; if it ever stops being true,
   * this is the check that says so.
   */
  @Test
  fun `zeile 3 angefangene arbeit bleibt von der regel unberuehrt`() {
    val ohne = Aufbau(blockiert = false).also {
      it.t1.completionPercentage = 50
      it.bWegAmMittwoch()
    }
    val mit = Aufbau(blockiert = true).also {
      it.t1.completionPercentage = 50
      it.bWegAmMittwoch()
    }

    val ohneZeile = ohne.zeile()
    assertEquals("t1=2026-09-14+2|t2=2026-09-16+1", ohneZeile,
      "die halb erledigte Arbeit braucht noch zwei Tage, T2 kommt am Mittwoch")
    assertEquals(ohneZeile, mit.zeile(),
      "bei angefangener Arbeit wirkt die Markierung nicht -- beide Laeufe muessen Zeichen fuer " +
        "Zeichen dasselbe liefern")
  }

  /**
   * ROW 6 — two indispensable people with absences on different days, freely movable.
   * RECONSTRUCTED SETUP, see the head comment: `B` away on the Wednesday, `A` on the Thursday.
   *
   * That both of the report's numbers for this row come out — T1 on the Friday and T2 on the
   * Monday for TWO days rather than one — is what makes the reconstruction credible. T2's second
   * day is `A`'s own Thursday off, which lengthens T2 without moving it: the ordinary treatment of
   * a day off, on the very same absence that stops T1 entirely. The two rules side by side in one
   * plan.
   */
  @Test
  fun `zeile 6 zwei zwingende personen versetzt und frei beweglich`() {
    val ohne = Aufbau(blockiert = false).also {
      it.zuordnungA.isBlocking = false
      it.projekt.dayOff(it.b, MITTWOCH, DONNERSTAG)
      it.projekt.dayOff(it.a, DONNERSTAG, FREITAG)
    }
    assertEquals("t1=2026-09-14+3|t2=2026-09-17+2", ohne.zeile(),
      "ohne Markierung: T1 liegt Mo-Mi, T2 beginnt am Donnerstag und wird durch A's Urlaub " +
        "einen Tag laenger")

    val mit = Aufbau(blockiert = true).also {
      it.zuordnungA.isBlocking = true
      it.projekt.dayOff(it.b, MITTWOCH, DONNERSTAG)
      it.projekt.dayOff(it.a, DONNERSTAG, FREITAG)
    }
    assertEquals("t1=2026-09-18+3|t2=2026-09-14+1", mit.zeile(),
      "beide markiert: T1 findet erst ab Freitag drei gemeinsame Tage, T2 nimmt den Montag")
  }

  // ===============================================================================================
  // Rows 2, 5, 7 and 8: a fixed date, where the window search may not search. THE LOSS AND ITS END.
  // ===============================================================================================

  /**
   * ROW 2 — a fixed date and one day of absence. THE CENTRAL ROW OF THE WHOLE MEASUREMENT.
   *
   * Three runs, and the middle one is the state this stage ends. Without the marking T2 comes on
   * the Thursday. WITH the marking and WITHOUT the release T2 came on the Friday: T1 was stretched
   * over the blocked Wednesday and booked `A` on a day `A` had nothing to do — one working day
   * lost, exactly the loss the measurement called linear. With the release T2 comes on the
   * WEDNESDAY, earlier than in a plan with no marking at all, because it fills the hole.
   *
   * TWO WORKING DAYS, and the report's figure for this row.
   */
  @Test
  fun `zeile 2 fester termin mit einem ausfalltag gibt zwei arbeitstage frei`() {
    val ohne = Aufbau(blockiert = false).also {
      it.projekt.fixDate(it.t1)
      it.bWegAmMittwoch()
    }
    assertEquals("t1=2026-09-14+3|t2=2026-09-17+1", ohne.zeile(),
      "ohne Markierung: drei Tage, T2 am Donnerstag")

    val mit = Aufbau(blockiert = true).also {
      it.projekt.fixDate(it.t1)
      it.bWegAmMittwoch()
    }
    assertEquals("t1=2026-09-14+4|t2=2026-09-16+1", mit.zeile(),
      "T1 bleibt auf dem festen Termin und wird durch den blockierten Mittwoch vier Tage lang " +
        "-- aber der Mittwoch belegt A nicht mehr, also gehoert T2 dorthin")
  }

  /**
   * ROW 5 — a fixed date and five days of absence. The loss was five working days and it is the
   * row that showed it grows one for one; the release ends all five of them at once, because the
   * whole week of holes is free for `A` and T2 needs one day of it.
   */
  @Test
  fun `zeile 5 fester termin mit fuenf ausfalltagen gibt fuenf arbeitstage frei`() {
    val ohne = Aufbau(blockiert = false).also {
      it.projekt.fixDate(it.t1)
      it.bWegEineWoche()
    }
    assertEquals("t1=2026-09-14+3|t2=2026-09-17+1", ohne.zeile(),
      "ohne Markierung: drei Tage, T2 am Donnerstag")

    val mit = Aufbau(blockiert = true).also {
      it.projekt.fixDate(it.t1)
      it.bWegEineWoche()
    }
    assertEquals("t1=2026-09-14+8|t2=2026-09-16+1", mit.zeile(),
      "T1 ist acht Tage lang, fuenf davon Loecher -- T2 nimmt das erste davon")
  }

  /**
   * ROW 7 — two indispensable people, offset absences, a fixed date. RECONSTRUCTED as row 6 is,
   * and the reconstruction is confirmed by the length: the report's five days for T1 come out only
   * if exactly two of its days are blocked.
   *
   * Two days lost before the release, and the release gives them back.
   */
  @Test
  fun `zeile 7 zwei zwingende personen mit festem termin geben zwei arbeitstage frei`() {
    val ohne = Aufbau(blockiert = false).also {
      it.zuordnungA.isBlocking = false
      it.projekt.fixDate(it.t1)
      it.projekt.dayOff(it.b, MITTWOCH, DONNERSTAG)
      it.projekt.dayOff(it.a, DONNERSTAG, FREITAG)
    }
    assertEquals("t1=2026-09-14+3|t2=2026-09-17+2", ohne.zeile(),
      "ohne Markierung wie in Zeile 6, nur dass der Termin ohnehin nicht wandern muesste")

    val mit = Aufbau(blockiert = true).also {
      it.zuordnungA.isBlocking = true
      it.projekt.fixDate(it.t1)
      it.projekt.dayOff(it.b, MITTWOCH, DONNERSTAG)
      it.projekt.dayOff(it.a, DONNERSTAG, FREITAG)
    }
    assertEquals("t1=2026-09-14+5|t2=2026-09-16+1", mit.zeile(),
      "zwei blockierte Tage machen T1 fuenf Tage lang; der Mittwoch ist frei und T2 nimmt ihn " +
        "-- den Donnerstag koennte T2 nicht nehmen, da ist A selbst im Urlaub")
  }

  /**
   * ROW 8 — THE LIMIT OF THE WHOLE EXERCISE, and it belongs in the corpus exactly because it is
   * the row in which the release brings nothing.
   *
   * T2 is 24 hours of `A`, three working days, and it does not fit into a one-day hole. The window
   * search does not break a task into pieces — that is a decision written down in
   * `findEarliestWindow` — so a released day is only of use to a task that FITS IN IT. Without this
   * row the file would read as though the release always helps.
   */
  @Test
  fun `zeile 8 ein zu langer nachfolger passt nicht in die luecke`() {
    val ohne = Aufbau(blockiert = false, t2Stunden = 24.0).also {
      it.projekt.fixDate(it.t1)
      it.bWegAmMittwoch()
    }
    assertEquals("t1=2026-09-14+3|t2=2026-09-17+3", ohne.zeile(),
      "ohne Markierung beginnt T2 am Donnerstag und dauert drei Tage")

    val mit = Aufbau(blockiert = true, t2Stunden = 24.0).also {
      it.projekt.fixDate(it.t1)
      it.bWegAmMittwoch()
    }
    assertEquals("t1=2026-09-14+4|t2=2026-09-18+3", mit.zeile(),
      "der freigewordene Mittwoch nuetzt nichts: T2 braucht drei zusammenhaengende Tage und " +
        "der Donnerstag gehoert noch T1")
  }

  // ===============================================================================================
  // The overload report, through the real conversion.
  // ===============================================================================================

  /**
   * THE FOUR MESSAGES BECOME THREE, and the one that goes is the one that was not true.
   *
   * Two tasks nailed to the same Monday, both claiming all of `A` — the report of 05.09.2026 notes
   * that only fixed dates can produce an overload at all, which is why the setup looks like this.
   * `B` has to be there for T1 and is away on the Wednesday. On that Wednesday `A` contributes
   * nothing to T1, so `A` is claimed once and not twice.
   *
   * The three remaining days are written out and not counted, because „three messages" would also
   * be satisfied by a version that dropped the wrong one.
   */
  @Test
  fun `die ueberlastmeldung am blockierten tag verschwindet und die anderen bleiben`() {
    val projekt = Project()
    val a = projekt.resource("A", 1)
    val b = projekt.resource("B", 2)
    val t1 = projekt.task("T1", MONTAG, 40.0)
    val t2 = projekt.task("T2", MONTAG, 40.0)
    projekt.assign(t1, a, 100f)
    projekt.assign(t1, b, 100f).isBlocking = true
    projekt.assign(t2, a, 100f)
    projekt.fixDate(t1)
    projekt.fixDate(t2)
    projekt.dayOff(b, MITTWOCH, DONNERSTAG)

    val ergebnis = projekt.verteile()
    val ueberlast = ergebnis.conflicts.filterIsInstance<LevelConflict.Overload>()
      .filter { it.resourceId == a.id.toString() }

    assertEquals(listOf("2026-09-14", "2026-09-15", "2026-09-17"),
      ueberlast.map { it.day.toString() }.sorted(),
      "A ist an Montag, Dienstag und Donnerstag doppelt belegt -- am Mittwoch nicht, denn dort " +
        "traegt A nichts zu T1 bei")
    assertEquals(setOf(200), ueberlast.map { it.percent }.toSet(),
      "und die drei richtigen Meldungen nennen unveraendert 200 %")
  }
}
