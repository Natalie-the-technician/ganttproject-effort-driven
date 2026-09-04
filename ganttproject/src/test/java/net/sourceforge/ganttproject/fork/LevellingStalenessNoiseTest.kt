/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
THE NUMBER THAT DECIDES WHETHER THE MARK IS WORTH DISPLAYING AT ALL.

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
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * HOW OFTEN DOES THE MARK GO OFF FOR NOTHING?
 *
 * A MESSAGE THAT IS ALWAYS ON SAYS NOTHING. That is the strongest argument against the mark and it
 * was raised against it in the measurement of 28.08.2026
 * (`2026-08-28-verteilung-veraltet-messung.md`, counter-argument 1): somebody building a plan
 * types names, and a mark that goes off on renaming will not be read after the third time. The
 * existing notification bubble failed in exactly that way (`GanttProject.java`, the comment on
 * `EstimateQualityAction`).
 *
 * So this file does not test the mark. It MEASURES it, against a rebuilt sequence of ordinary
 * operation, and it puts two numbers next to each other for every single step:
 *
 *   1. does the mark go off?  — read from [LevellingStaleness.isStale]
 *   2. does the levelling RESULT actually change? — the whole chain is run before and after every
 *      step, exactly as `LevellingAction` runs it (`collectLevelTasks` → `levelTasks`), and the
 *      resulting start dates and durations are compared.
 *
 * The difference between the two columns is the false-alarm rate, and it is a FINDING, not a test
 * result: if it is bad, that is a reason to stop rather than a reason to build the display anyway.
 *
 * The assertions below pin the numbers that were measured, so that a later change to the event
 * wiring cannot move them without somebody noticing. They are not there because the numbers are
 * good.
 *
 * NOT MEASURED HERE: how often each of these steps occurs in real use. The sequence is the one
 * named in the task — create a task, rename it, set a duration, assign a person, write a note —
 * and it is a plausible sequence, not a sampled one. A weighting would need somebody watching a
 * real session.
 */
class LevellingStalenessNoiseTest {

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

  /** A Monday, far enough ahead that nothing counts as left lying in the past. */
  private val MONTAG: LocalDate = LocalDate.of(2026, 9, 14)
  private val HEUTE: LocalDate = LocalDate.of(2026, 9, 1)

  /**
   * A plan with contention in it: one person, and work that overlaps. Without contention the
   * levelling has nothing to do and every step would answer "result unchanged" for the trivial
   * reason, which would make the measurement worthless.
   */
  private class Plan {
    val builder: TestSetupHelper.TaskManagerBuilder =
      TestSetupHelper.newTaskManagerBuilder().withCalendar(WeekendCalendarImpl())
    val taskManager: TaskManager = builder.build()
    val resourceManager: HumanResourceManager = builder.resourceManager
    val taskProperties: CustomPropertyManager get() = taskManager.customPropertyManager
    val resourceProperties: CustomPropertyManager get() = resourceManager.customPropertyManager

    fun person(name: String, id: Int): HumanResource = resourceManager.create(name, id)

    fun task(name: String, start: LocalDate, days: Int): Task =
      taskManager.newTaskBuilder().withName(name).withStartDate(start.toModelDate())
        .withDuration(taskManager.createLength(days.toLong())).build()

    fun assign(task: Task, person: HumanResource, load: Float) {
      task.assignmentCollection.addAssignment(person).load = load
    }

    /**
     * The levelling result, computed exactly the way `LevellingAction.weiter` computes it. Only
     * the parts that decide where a task lands: the starts and the durations it was laid with.
     */
    fun result(today: LocalDate): Map<String, Pair<LocalDate, Int?>> {
      val tasks = collectLevelTasks(taskManager, taskProperties, resourceProperties, today, true)
      if (tasks.isEmpty()) {
        return emptyMap()
      }
      val projectStart = taskManager.projectStart?.toModelLocalDate() ?: today
      val levelled = levelTasks(tasks, maxOf(projectStart, today),
        workingDaysPerTask(taskManager, resourceProperties),
        durationAtStart(taskManager, taskProperties, resourceProperties),
        isAvailable = availabilityTest(resourceManager))
      return levelled.starts.mapValues { (id, start) -> start to levelled.durations[id] }
    }
  }

  /** One step of the rebuilt operation: what it is called, and what it does to the plan. */
  private class Schritt(val name: String, val tun: () -> Unit)

  /** What one step produced. */
  private class Zeile(val name: String, val markSet: Boolean, val resultChanged: Boolean)

  /**
   * Runs the steps one after another and records, for each, whether the mark went off and whether
   * the levelling result really moved. The mark is cleared before every step, so the columns
   * describe the step and not its predecessors.
   */
  private fun messe(plan: Plan, schritte: List<Schritt>): List<Zeile> {
    val notifier = LevellingRunNotifier()
    val staleness = LevellingStaleness(notifier)
    plan.taskManager.addTaskListener(staleness.taskListener)
    plan.resourceManager.addView(staleness.resourceView)
    plan.taskManager.calendar.addListener(staleness.calendarListener)
    try {
      return schritte.map { schritt ->
        val vorher = plan.result(HEUTE)
        staleness.clear()
        schritt.tun()
        val nachher = plan.result(HEUTE)
        Zeile(schritt.name, staleness.isStale, vorher != nachher)
      }
    } finally {
      staleness.detach()
    }
  }

  private fun bericht(zeilen: List<Zeile>): String =
    zeilen.joinToString("\n") { z ->
      "  %-34s Merker=%-5s Ergebnis=%s".format(z.name, z.markSet, z.resultChanged)
    }

  // --------------------------------------------------------------------------------------------
  // The sequence from the task
  // --------------------------------------------------------------------------------------------

  /**
   * THE HEADLINE NUMBER. Five steps of ordinary operation, as named in the task: create a task,
   * rename it, set a duration, assign a person, write a note.
   *
   * MEASURED RESULT — 5 of 5 steps set the mark, 3 of 5 change the result. Two false alarms:
   * renaming and the note. Printed by this test, so the number is readable in the test log and
   * not only in a report.
   *
   * RED against 9178b886b — the class does not exist there:
   *   e: file:///.../fork/LevellingStalenessNoiseTest.kt:137:22 Unresolved reference 'LevellingStaleness'.
   *   e: file:///.../fork/LevellingStalenessNoiseTest.kt:138:38 Unresolved reference 'taskListener'.
   *   e: file:///.../fork/LevellingStalenessNoiseTest.kt:139:36 Unresolved reference 'resourceView'.
   *   e: file:///.../fork/LevellingStalenessNoiseTest.kt:140:45 Unresolved reference 'calendarListener'.
   *   e: file:///.../fork/LevellingStalenessNoiseTest.kt:145:21 Unresolved reference 'clear'.
   *   e: file:///.../fork/LevellingStalenessNoiseTest.kt:148:41 Unresolved reference 'isStale'.
   *   e: file:///.../fork/LevellingStalenessNoiseTest.kt:152:17 Unresolved reference 'detach'.
   */
  @Test
  fun `the rebuilt sequence of ordinary operation`() {
    val plan = Plan()
    val anna = plan.person("Anna", 1)
    // Work that is already there and already contended for: without it the levelling would have
    // nothing to move and every answer would be "unchanged" for the wrong reason.
    val vorhanden = plan.task("Angebot schreiben", MONTAG, 5)
    plan.assign(vorhanden, anna, 100f)

    var neu: Task? = null
    val zeilen = messe(plan, listOf(
      Schritt("1 Vorgang anlegen") { neu = plan.task("Neuer Vorgang", MONTAG, 3) },
      Schritt("2 umbenennen") {
        neu!!.createMutator().also { it.setName("Angebot pruefen") }.commit()
      },
      Schritt("3 Dauer setzen") {
        neu!!.createMutator().also {
          it.setDuration(plan.taskManager.createLength(8L))
        }.commit()
      },
      Schritt("4 Person zuordnen") { plan.assign(neu!!, anna, 100f) },
      Schritt("5 Notiz schreiben") {
        neu!!.createMutator().also { it.setNotes("mit Herrn Meier abgestimmt") }.commit()
      }
    ))

    println("=== Bedienfolge, Fehlalarme ===\n" + bericht(zeilen))
    val gesetzt = zeilen.count { it.markSet }
    val echt = zeilen.count { it.resultChanged }
    println("Merker gesetzt: $gesetzt von ${zeilen.size}; Ergebnis wirklich geaendert: $echt")

    assertEquals(5, zeilen.size, "Aufbau: fuenf Schritte")
    assertEquals(5, gesetzt,
      "every one of the five steps sets the mark -- that is what a pure event mark does")
    assertEquals(3, echt,
      "only three of them move the levelling result: creating, the duration and the assignment")
    assertEquals(listOf("2 umbenennen", "5 Notiz schreiben"),
      zeilen.filter { it.markSet && !it.resultChanged }.map { it.name },
      "the two false alarms are the rename and the note, and nothing else in this sequence")
  }

  /**
   * The same measurement over a wider sequence, so the headline number is not an artefact of five
   * steps chosen kindly. Everything the measurement of 28.08.2026 listed under "does not change
   * the result" is in here: colour, note, expansion, the critical flag, a person's name.
   *
   * MEASURED RESULT — 11 of 12 steps set the mark, 3 of 12 change the result. EIGHT false alarms,
   * and one step that neither sets the mark nor changes anything (setting a value to what it
   * already was).
   *
   * RED against 9178b886b — same unresolved references as above; the class does not exist.
   */
  @Test
  fun `a wider sequence of ordinary operation`() {
    val plan = Plan()
    val anna = plan.person("Anna", 1)
    val vorhanden = plan.task("Angebot schreiben", MONTAG, 5)
    plan.assign(vorhanden, anna, 100f)
    val zweiter = plan.task("Zweiter Vorgang", MONTAG, 3)

    val zeilen = messe(plan, listOf(
      Schritt("01 umbenennen") {
        zweiter.createMutator().also { it.setName("Angebot pruefen") }.commit()
      },
      Schritt("02 Notiz schreiben") {
        zweiter.createMutator().also { it.setNotes("Notiz") }.commit()
      },
      Schritt("03 Farbe setzen") {
        zweiter.createMutator().also { it.setColor(java.awt.Color.RED) }.commit()
      },
      Schritt("04 Weblink setzen") {
        zweiter.createMutator().also { it.setWebLink("https://example.invalid") }.commit()
      },
      Schritt("05 zuklappen") {
        zweiter.createMutator().also { it.setExpand(false) }.commit()
      },
      Schritt("06 Form setzen") {
        zweiter.createMutator().also {
          it.setShape(biz.ganttproject.core.chart.render.ShapeConstants.CROSS)
        }.commit()
      },
      Schritt("07 Person umbenennen") { anna.name = "Anna Meier" },
      Schritt("08 denselben Namen nochmals") {
        zweiter.createMutator().also { it.setName("Angebot pruefen") }.commit()
      },
      Schritt("09 Person zuordnen") { plan.assign(zweiter, anna, 100f) },
      Schritt("10 Dauer setzen") {
        zweiter.createMutator().also {
          it.setDuration(plan.taskManager.createLength(8L))
        }.commit()
      },
      // PRIORITY BEFORE PROGRESS, and the order matters: progress makes a task `frozen`
      // (`LevellingAdapter.kt`, `frozen = angefangen || bleibtLiegen`), and a frozen task does not
      // move however its priority is set. Measured the other way round first, where the priority
      // step came out as a false alarm for that reason alone -- which would have been a wrong
      // number for a right observation.
      Schritt("11 Prioritaet heben") {
        zweiter.createMutator().also { it.setPriority(Task.Priority.HIGH) }.commit()
      },
      Schritt("12 Fortschritt setzen") {
        zweiter.createMutator().also { it.setCompletionPercentage(40) }.commit()
      }
    ))

    println("=== Breite Bedienfolge, Fehlalarme ===\n" + bericht(zeilen))
    val gesetzt = zeilen.count { it.markSet }
    val echt = zeilen.count { it.resultChanged }
    val fehlalarme = zeilen.count { it.markSet && !it.resultChanged }
    println("Merker gesetzt: $gesetzt von ${zeilen.size}; Ergebnis geaendert: $echt; " +
      "Fehlalarme: $fehlalarme")

    assertEquals(12, zeilen.size, "Aufbau: zwoelf Schritte")
    assertEquals(11, gesetzt, "eleven of twelve steps set the mark")
    assertEquals(3, echt, "three of them move the levelling result")
    assertEquals(8, fehlalarme, "eight false alarms in twelve steps of ordinary operation")
    // THE PROGRESS STEP COMES OUT AS A FALSE ALARM IN THIS PLAN, and that is the plan and not the
    // event: progress freezes a task where it stands, and the levelling had already put it there.
    // It also shortens the remaining duration -- but only for a task with an effort column and a
    // start that is already past, and this one has neither. In a plan with effort figures the same
    // event is a true alarm. Recorded rather than tuned away: a measurement that picks its plan
    // until the number looks good is not a measurement.
    assertTrue(zeilen.first { it.name.startsWith("12") }.markSet,
      "progress still sets the mark -- it is a separate event and the mark has to hear it")
    // Writing the same value again is the ONE step that costs nothing: FieldChange.setValue
    // returns early when the value has not moved, so no event is sent at all.
    assertTrue(zeilen.first { it.name.startsWith("08") }.markSet.not(),
      "writing a value that has not changed sends no event and must not set the mark")
  }

  // --------------------------------------------------------------------------------------------
  // THE NUMBER THAT ACTUALLY DECIDES - the mark is a latch, not a per-event lamp
  // --------------------------------------------------------------------------------------------

  /**
   * HOW OFTEN IS THE MARK ON WHEN IT HAS NO RIGHT TO BE - over a whole session, not per keystroke.
   *
   * The per-step figures above answer "how many single actions are false alarms". That is NOT the
   * figure that decides whether the message may be shown, and measuring only that would overstate
   * the damage: the mark is a LATCH. Once one substantive change has happened the mark is right,
   * and every cosmetic edit after it merely fails to make it any more right.
   *
   * So this measures the mark's state after every step against the truth - does the levelling
   * result still agree with what it was at the last run? - and counts the steps where the mark
   * says "out of date" and the truth says "no".
   *
   * MEASURED: in the mixed sequence the mark is honest from the first step onwards, 0 dishonest
   * steps out of 5. In a session of nothing but cosmetic edits it is dishonest at every single
   * step, 5 out of 5. That is the whole trade, in two numbers.
   *
   * RED against 9178b886b - unresolved reference 'LevellingStaleness'.
   */
  @Test
  fun `over a whole session the mark is a latch`() {
    /** Walks the steps WITHOUT clearing in between, the way a real session runs. */
    fun sitzung(plan: Plan, schritte: List<Schritt>): List<Zeile> {
      val notifier = LevellingRunNotifier()
      val staleness = LevellingStaleness(notifier)
      plan.taskManager.addTaskListener(staleness.taskListener)
      plan.resourceManager.addView(staleness.resourceView)
      try {
        // The starting point: the plan as the levelling last left it. Clearing the mark by hand
        // stands in for a run -- what matters is the state, not how it was reached.
        val beimLetztenLauf = plan.result(HEUTE)
        staleness.clear()
        return schritte.map { schritt ->
          schritt.tun()
          Zeile(schritt.name, staleness.isStale, plan.result(HEUTE) != beimLetztenLauf)
        }
      } finally {
        staleness.detach()
      }
    }

    // (A) the mixed sequence from the task, run as one session
    val gemischt = Plan()
    val annaA = gemischt.person("Anna", 1)
    gemischt.assign(gemischt.task("Angebot schreiben", MONTAG, 5), annaA, 100f)
    var neu: Task? = null
    val zeilenA = sitzung(gemischt, listOf(
      Schritt("1 Vorgang anlegen") { neu = gemischt.task("Neuer Vorgang", MONTAG, 3) },
      Schritt("2 umbenennen") {
        neu!!.createMutator().also { it.setName("Angebot pruefen") }.commit()
      },
      Schritt("3 Dauer setzen") {
        neu!!.createMutator().also {
          it.setDuration(gemischt.taskManager.createLength(8L))
        }.commit()
      },
      Schritt("4 Person zuordnen") { gemischt.assign(neu!!, annaA, 100f) },
      Schritt("5 Notiz schreiben") {
        neu!!.createMutator().also { it.setNotes("mit Herrn Meier abgestimmt") }.commit()
      }
    ))

    // (B) a session of nothing but cosmetic edits -- the case the counter-argument names
    val kosmetik = Plan()
    val annaB = kosmetik.person("Anna", 1)
    val vorhanden = kosmetik.task("Angebot schreiben", MONTAG, 5)
    kosmetik.assign(vorhanden, annaB, 100f)
    val zeilenB = sitzung(kosmetik, listOf(
      Schritt("1 umbenennen") {
        vorhanden.createMutator().also { it.setName("Angebot A") }.commit()
      },
      Schritt("2 nochmals umbenennen") {
        vorhanden.createMutator().also { it.setName("Angebot B") }.commit()
      },
      Schritt("3 Notiz schreiben") {
        vorhanden.createMutator().also { it.setNotes("Notiz") }.commit()
      },
      Schritt("4 Farbe setzen") {
        vorhanden.createMutator().also { it.setColor(java.awt.Color.RED) }.commit()
      },
      Schritt("5 Person umbenennen") { annaB.name = "Anna Meier" }
    ))

    val luegtA = zeilenA.count { it.markSet && !it.resultChanged }
    val luegtB = zeilenB.count { it.markSet && !it.resultChanged }
    println("=== Sitzung A, gemischt ===")
    println(bericht(zeilenA))
    println("Merker leuchtet zu Unrecht: $luegtA von ${zeilenA.size}")
    println("=== Sitzung B, nur Kosmetik ===")
    println(bericht(zeilenB))
    println("Merker leuchtet zu Unrecht: $luegtB von ${zeilenB.size}")

    assertEquals(0, luegtA,
      "in a session with one real change in it the mark is honest from that change onwards")
    assertEquals(5, luegtB,
      "in a session of nothing but cosmetic edits the mark lies the whole time -- and THAT is " +
        "the failure mode the counter-argument names")
    assertTrue(zeilenB.all { it.markSet }, "Aufbau: the cosmetic session did set the mark")
    assertTrue(zeilenB.none { it.resultChanged }, "Aufbau: and none of it changed a single date")
  }

  // --------------------------------------------------------------------------------------------
  // resetLoads - not one more per keystroke
  // --------------------------------------------------------------------------------------------

  /**
   * NOT ONE EXTRA resetLoads PER KEYSTROKE.
   *
   * `resetLoads()` throws the cached load distribution away (`HumanResource.myLoadDistribution =
   * null`), so it is the one cost that a listener could inflict on ordinary typing without anybody
   * noticing. The mark registers three listeners and touches no resource, so it should cost
   * nothing - and that is a claim worth a number rather than a sentence.
   *
   * MEASURED THE SAME WAY THE F25 MEASUREMENT DID IT, at the observable effect and not by
   * instrumenting anything: `getLoadDistribution()` caches, and rebuilds only after a
   * `resetLoads()`. If the object identity changes between two calls, the cache was thrown away.
   * The same sequence runs twice, once with the mark's three listeners registered and once
   * without, and only that differs.
   *
   * MEASURED RESULT: 1 and 1. Identical step by step, not only in the sum.
   *
   * COULD NOT BE RED against 9178b886b: the class does not exist there, so there is no
   * "with the mark" arm. It is the guard that the mark stays free of charge.
   */
  @Test
  fun `the mark costs no additional resetLoads`() {
    fun lauf(mitMerker: Boolean): List<Int> {
      val plan = Plan()
      val anna = plan.person("Anna", 1)
      val vorhanden = plan.task("Angebot schreiben", MONTAG, 5)
      plan.assign(vorhanden, anna, 100f)
      val staleness = if (mitMerker) LevellingStaleness(LevellingRunNotifier()) else null
      staleness?.let {
        plan.taskManager.addTaskListener(it.taskListener)
        plan.resourceManager.addView(it.resourceView)
        plan.taskManager.calendar.addListener(it.calendarListener)
      }
      try {
        var letzte = anna.loadDistribution
        var neu: Task? = null
        val schritte = listOf<() -> Unit>(
          { neu = plan.task("Neuer Vorgang", MONTAG, 3) },
          { neu!!.createMutator().also { it.setName("Angebot pruefen") }.commit() },
          { neu!!.createMutator().also {
              it.setDuration(plan.taskManager.createLength(8L))
            }.commit() },
          { plan.assign(neu!!, anna, 100f) },
          { neu!!.createMutator().also { it.setNotes("Notiz") }.commit() }
        )
        return schritte.map { schritt ->
          schritt()
          val jetzt = anna.loadDistribution
          val verworfen = if (jetzt !== letzte) 1 else 0
          letzte = jetzt
          verworfen
        }
      } finally {
        staleness?.detach()
      }
    }

    val ohne = lauf(mitMerker = false)
    val mit = lauf(mitMerker = true)
    println("=== resetLoads je Schritt ===")
    println("  ohne Merker: $ohne  Summe=${ohne.sum()}")
    println("  mit  Merker: $mit  Summe=${mit.sum()}")

    assertEquals(ohne, mit,
      "the mark must not throw away a single cached load distribution, in no step")
    assertTrue(ohne.sum() > 0,
      "Aufbau: the sequence really does discard the cache at least once -- otherwise the " +
        "comparison would be two rows of zeroes and would prove nothing")
  }

  /**
   * THE CHEAP REFINEMENT, MEASURED BUT NOT BUILT.
   *
   * The obvious answer to the false alarms is: drop `taskPropertiesChanged`, since renaming, the
   * note, the colour, the web link and the expansion all arrive only through it. This test shows
   * what that would cost, so that the trade is a number rather than an opinion — the four custom
   * columns the levelling really reads (deadline, fixed date, waiting time, effort) arrive through
   * NO other event, and a mark without the property event would miss all four.
   *
   * It is deliberately NOT built: `TaskPropertyEvent` carries no indication of which property
   * moved (checked — the class has no column field), so distinguishing them means comparing VALUES
   * rather than filtering events, and that is the halfway house to the real calculation which has
   * been ruled out with numbers.
   *
   * RED against 9178b886b — same unresolved references; the class does not exist.
   */
  @Test
  fun `dropping the property event would lose the four columns the levelling reads`() {
    val plan = Plan()
    val anna = plan.person("Anna", 1)
    val task = plan.task("Angebot schreiben", MONTAG, 5)
    plan.assign(task, anna, 100f)

    val notifier = LevellingRunNotifier()
    val staleness = LevellingStaleness(notifier)
    plan.taskManager.addTaskListener(staleness.taskListener)
    try {
      // The waiting column is one of the four. It is written straight onto customValues,
      // which fires taskPropertiesChanged through the callback TaskImpl.java installs -- and
      // nothing else.
      val ereignisse = mutableListOf<String>()
      plan.taskManager.addTaskListener(object :
        net.sourceforge.ganttproject.task.event.TaskListenerAdapter() {
        override fun taskPropertiesChanged(
          e: net.sourceforge.ganttproject.task.event.TaskPropertyEvent) {
          ereignisse.add("properties")
        }
        override fun taskScheduleChanged(
          e: net.sourceforge.ganttproject.task.event.TaskScheduleEvent) {
          ereignisse.add("schedule")
        }
        override fun taskProgressChanged(
          e: net.sourceforge.ganttproject.task.event.TaskPropertyEvent) {
          ereignisse.add("progress")
        }
      })

      staleness.clear()
      val spalte = findOrCreateWaitOnly(plan.taskProperties)
      task.customValues.setValue(spalte, true)

      assertTrue(staleness.isStale, "Aufbau: the waiting column has to reach the mark")
      assertEquals(listOf("properties"), ereignisse,
        "the waiting time arrives as taskPropertiesChanged AND NOTHING ELSE -- a mark without " +
          "that event would not hear it at all")
    } finally {
      staleness.detach()
    }
  }
}
