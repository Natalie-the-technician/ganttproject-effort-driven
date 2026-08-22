/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.

This file is part of GanttProject, an opensource project management tool.
Licensed under the GNU General Public License, version 3 or later.
*/
package net.sourceforge.ganttproject.fork

import biz.ganttproject.core.calendar.WeekendCalendarImpl
import biz.ganttproject.core.time.CalendarFactory
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.storage.ProjectDatabase
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import net.sourceforge.ganttproject.undo.GPUndoListener
import net.sourceforge.ganttproject.undo.GPUndoManager
import net.sourceforge.ganttproject.undo.UndoableEditTxnFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import net.sourceforge.ganttproject.storage.SQL_PROJECT_DATABASE_OPTIONS
import net.sourceforge.ganttproject.storage.SqlProjectDatabaseImpl
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInfo
import java.text.DateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * Recurring Tasks in the REAL project model.
 *
 * WHY THIS EXISTS ALONGSIDE [RecurrenceTest]: the date calculation is checked there. What is
 * checked here is what went wrong twice already in this session and what no pure calculation can
 * find -- the interplay with mutator, scheduler, calendar and property management. In particular
 * the promise "a second call creates nothing twice": that is what decides whether the tool can be
 * used more than once.
 */
class RecurrenceModelTest {

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

  private class RunOnlyUndoManager : GPUndoManager {
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

  // A REAL database, not a mock: `onCustomColumnChange` is exactly the place where writing
  // failed in session 3 ("Column effort_hours not found"). A mock would not have found that.
  // Every test gets its own, otherwise a column survives from the previous test and the check is
  // worthless.
  private lateinit var dataSource: JdbcDataSource
  private lateinit var db: ProjectDatabase

  @BeforeEach
  fun init(testInfo: TestInfo) {
    dataSource = JdbcDataSource().also {
      it.setURL("jdbc:h2:mem:serie${testInfo.displayName.hashCode()}$SQL_PROJECT_DATABASE_OPTIONS")
    }
    db = SqlProjectDatabaseImpl(dataSource).also { it.init() }
  }

  @AfterEach
  fun clear() {
    dataSource.connection.use { it.createStatement().execute("shutdown") }
  }

  private fun taskManager(): TaskManager =
    TestSetupHelper.newTaskManagerBuilder().withCalendar(WeekendCalendarImpl()).build()

  private fun TaskManager.task(name: String, start: LocalDate, days: Int): Task =
    this.newTaskBuilder().withName(name).withStartDate(start.toModelDate())
      .withDuration(this.createLength(days.toLong())).build()

  private fun Task.setRecurrence(manager: biz.ganttproject.customproperty.CustomPropertyManager,
                                 text: String) {
    this.customValues.setValue(findOrCreateRecurrence(manager), text)
  }

  private val montag = LocalDate.of(2026, 8, 17)

  @Test
  fun `aus einem vorgang mit wiederholung werden viele`() {
    val tm = taskManager()
    val props = tm.customPropertyManager
    val quelle = tm.task("Umsatzsteuervoranmeldung", montag, 1)
    quelle.setRecurrence(props, "monatlich; Anzahl 4")

    val plan = planRecurrences(tm, props)
    assertEquals(1, plan.seriesCount)
    assertEquals(3, plan.occurrences.size, "der erste Termin ist der Vorgang selbst")

    val angelegt = applyRecurrencesAsSingleEdit(plan, tm, props, db, RunOnlyUndoManager(), "Test")
    assertEquals(3, angelegt)
    // 3 occurrences + the source Task + the COLLECTING GROUP, which carries the same name.
    assertEquals(5, tm.tasks.count { it.name == "Umsatzsteuervoranmeldung" })
    assertEquals(1, tm.tasks.count { it.isRecurrenceGroup(props) })
  }

  @Test
  fun `ein zweiter aufruf legt nichts doppelt an`() {
    // THE DECISIVE PROMISE. A tool that duplicates everything on the second click gets used
    // exactly once -- and after that one never dares again.
    val tm = taskManager()
    val props = tm.customPropertyManager
    tm.task("Bericht", montag, 1).setRecurrence(props, "woechentlich; Anzahl 5")

    applyRecurrencesAsSingleEdit(planRecurrences(tm, props), tm, props, db,
      RunOnlyUndoManager(), "Test")
    val nachDemErsten = tm.tasks.size

    val zweiterPlan = planRecurrences(tm, props)
    assertEquals(0, zweiterPlan.occurrences.size, "beim zweiten Mal ist nichts mehr zu tun")
    applyRecurrencesAsSingleEdit(zweiterPlan, tm, props, db, RunOnlyUndoManager(), "Test")
    assertEquals(nachDemErsten, tm.tasks.size)
  }

  @Test
  fun `eine erweiterte serie legt nur die fehlenden termine nach`() {
    val tm = taskManager()
    val props = tm.customPropertyManager
    val quelle = tm.task("Bericht", montag, 1)
    quelle.setRecurrence(props, "woechentlich; Anzahl 3")
    applyRecurrencesAsSingleEdit(planRecurrences(tm, props), tm, props, db,
      RunOnlyUndoManager(), "Test")
    assertEquals(4, tm.tasks.count { it.name == "Bericht" }, "drei Termine plus Sammelgruppe")

    // 3 become 6: exactly the three missing ones may be added.
    quelle.setRecurrence(props, "woechentlich; Anzahl 6")
    val plan = planRecurrences(tm, props)
    assertEquals(3, plan.occurrences.size)
    applyRecurrencesAsSingleEdit(plan, tm, props, db, RunOnlyUndoManager(), "Test")
    assertEquals(7, tm.tasks.count { it.name == "Bericht" }, "sechs Termine plus Sammelgruppe")
    assertEquals(6, tm.tasks.count { it.recurrenceOccurrenceOf(props) != null || it == quelle },
      "sechs wirkliche Termine, die Gruppe zaehlt nicht mit")
  }

  @Test
  fun `wiederholungen erben dauer, aufwand und zuordnung`() {
    val tm = taskManager()
    val props = tm.customPropertyManager
    val quelle = tm.task("Abschluss", montag, 3)
    val effortDef = EffortDrivenProperties.findOrCreateTaskEffort(props)
    quelle.customValues.setValue(effortDef, 24.0)
    quelle.setRecurrence(props, "monatlich; Anzahl 2")

    applyRecurrencesAsSingleEdit(planRecurrences(tm, props), tm, props, db,
      RunOnlyUndoManager(), "Test")

    // recurrenceOccurrenceOf, NOT recurrenceOf: otherwise the collecting group falls in here,
    // and its duration is derived (26 days instead of 3).
    val kopie = tm.tasks.first { it.recurrenceOccurrenceOf(props) != null }
    assertEquals(3, kopie.duration.length, "die Dauer des Ausgangsvorgangs")
    assertEquals(24.0, kopie.customValues.getValue(effortDef), "und sein Aufwand")
    assertNotNull(kopie.recurrenceOf(props))
  }

  @Test
  fun `die wiederholung traegt selbst keine regel`() {
    // Otherwise the next run would produce occurrences of occurrences -- and the one after that
    // occurrences of those.
    val tm = taskManager()
    val props = tm.customPropertyManager
    tm.task("Bericht", montag, 1).setRecurrence(props, "woechentlich; Anzahl 3")
    applyRecurrencesAsSingleEdit(planRecurrences(tm, props), tm, props, db,
      RunOnlyUndoManager(), "Test")

    val kopien = tm.tasks.filter { it.recurrenceOccurrenceOf(props) != null }
    assertEquals(2, kopien.size)
    kopien.forEach { assertEquals(null, it.recurrenceText(props)) }
  }

  @Test
  fun `der ausgangsvorgang wird nicht zur gruppe`() {
    // If the occurrences were hung UNDER the source Task, it would derive its dates from them
    // and lose its own duration.
    val tm = taskManager()
    val props = tm.customPropertyManager
    val quelle = tm.task("Bericht", montag, 2)
    quelle.setRecurrence(props, "woechentlich; Anzahl 3")
    applyRecurrencesAsSingleEdit(planRecurrences(tm, props), tm, props, db,
      RunOnlyUndoManager(), "Test")

    assertTrue(tm.taskHierarchy.getNestedTasks(quelle).isEmpty(), "keine Kinder")
    assertEquals(2, quelle.duration.length, "und die eigene Dauer bleibt")
    assertEquals(montag, quelle.start.time.toModelLocalDate(), "der Termin auch")
  }

  @Test
  fun `eine unlesbare regel legt gar nichts an`() {
    val tm = taskManager()
    val props = tm.customPropertyManager
    tm.task("Gut", montag, 1).setRecurrence(props, "woechentlich; Anzahl 3")
    tm.task("Kaputt", montag, 1).setRecurrence(props, "monatlich")  // ohne Begrenzung

    val plan = planRecurrences(tm, props)
    assertTrue(plan.hasErrors)
    assertEquals(listOf("Kaputt"), plan.errors.keys.toList())
    // On errors the action creates nothing; what is demonstrated here is that the error reaches
    // the caller at all, instead of being skipped silently.
    assertTrue(plan.occurrences.any { it.sourceName == "Gut" },
      "die lesbare Serie ist geplant -- die Aktion fuehrt sie wegen des Fehlers trotzdem nicht aus")
  }

  @Test
  fun `ohne wiederholung passiert nichts`() {
    val tm = taskManager()
    val props = tm.customPropertyManager
    tm.task("Einmalig", montag, 1)
    val plan = planRecurrences(tm, props)
    assertEquals(0, plan.occurrences.size)
    assertTrue(plan.errors.isEmpty())
  }
}

/**
 * The fork's columns have to COME INTO BEING, otherwise nobody can fill them in.
 *
 * MEASURED ON 17.08.2026: four of them -- "Finish by", "Utilisation (%)", "Waiting", "Date fixed"
 * -- were created nowhere. They existed in the code, levelling read them dutifully and always
 * found nothing. From the outside that is indistinguishable from "does not work".
 *
 * This test is the guard against it. It checks the ids, not the display names: the display names
 * are translated, the ids are not.
 */
class ForkColumnsExistTest {
  init {
    // GanttProjectImpl builds a calendar; without this registration even the constructor dies.
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

  @Test
  fun `alle spalten dieses forks werden angelegt`() {
    val project = net.sourceforge.ganttproject.GanttProjectImpl()
    project.ensureCapacityColumns()

    val vorgangsSpalten = project.taskCustomColumnManager.definitions.map { it.id }.toSet()
    listOf(TASK_RECURRENCE, TASK_DEADLINE, TASK_WAIT_ONLY, TASK_DATE_FIXED,
      TASK_EFFORT_ORIGINAL).forEach {
      assertTrue(vorgangsSpalten.contains(it), "Vorgangsspalte fehlt: $it (vorhanden: $vorgangsSpalten)")
    }
    val ressourcenSpalten = project.resourceCustomPropertyManager.definitions.map { it.id }.toSet()
    listOf(EffortDrivenProperties.RESOURCE_HOURS_PER_DAY,
      EffortDrivenProperties.RESOURCE_HOURS_SCHEDULE, RESOURCE_UTILISATION).forEach {
      assertTrue(ressourcenSpalten.contains(it),
        "Ressourcenspalte fehlt: $it (vorhanden: $ressourcenSpalten)")
    }
  }

  @Test
  fun `ein zweiter aufruf legt nichts doppelt an`() {
    // That was exactly the reason why the columns must NOT come into being in the constructor: a
    // second creation of the same id blows up the whole file when loading.
    val project = net.sourceforge.ganttproject.GanttProjectImpl()
    project.ensureCapacityColumns()
    val vorher = project.taskCustomColumnManager.definitions.size
    project.ensureCapacityColumns()
    assertEquals(vorher, project.taskCustomColumnManager.definitions.size)
  }
}

/**
 * The original effort is caught up even when there is nothing else to fill in.
 *
 * MEASURED ON THE MACHINE, against the plan: the dialog said "bei 162 Vorgaengen wird der heutige
 * Aufwand als urspruenglicher festgehalten" (for 162 Tasks today's effort is recorded as the
 * original one) -- nothing was written, because `applyBackfillAsSingleEdit` returned beforehand
 * at `changeCount == 0`. A promise in a dialog that the program does not keep is worse than a
 * missing promise.
 */
class OriginalEffortBackfillTest {
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

  private class RunOnly : GPUndoManager {
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

  // A REAL database instead of GanttProjectImpl: its default database stand-in throws
  // "Not supposed to be called" as soon as a column is created.
  private lateinit var dataSource: JdbcDataSource
  private lateinit var db: ProjectDatabase

  @BeforeEach
  fun init(testInfo: TestInfo) {
    dataSource = JdbcDataSource().also {
      it.setURL("jdbc:h2:mem:urspr${testInfo.displayName.hashCode()}$SQL_PROJECT_DATABASE_OPTIONS")
    }
    db = SqlProjectDatabaseImpl(dataSource).also { it.init() }
  }

  @AfterEach
  fun clear() {
    dataSource.connection.use { it.createStatement().execute("shutdown") }
  }

  @Test
  fun `nichts abzuleiten, aber der ursprung wird trotzdem festgehalten`() {
    val builder = TestSetupHelper.newTaskManagerBuilder()
    val tm = builder.build()
    val props = tm.customPropertyManager
    val task = tm.newTaskBuilder().withName("schon gepflegt")
      .withDuration(tm.createLength(5)).build()
    task.customValues.setValue(EffortDrivenProperties.findOrCreateTaskEffort(props), 40.0)

    // Exactly the situation from the plan: everything has an effort, there is nothing to derive.
    val proposal = proposeBackfill(collectBackfillTasks(tm, props), 8.0)
    assertEquals(0, proposal.effortHours.size, "Aufbau: es gibt nichts abzuleiten")
    assertEquals(1, tasksMissingOriginalEffort(tm, props).size)

    val person = builder.resourceManager.create("Natalie", 0)
    applyBackfillAsSingleEdit(proposal, person, tm, props, db, RunOnly(), "Test")

    assertEquals(40.0, task.originalEffortHours(props),
      "der urspruengliche Aufwand muss geschrieben sein -- der Dialog hat es zugesagt")
    assertEquals(0, tasksMissingOriginalEffort(tm, props).size)
  }

  @Test
  fun `ein zweiter lauf aendert den ursprung nicht`() {
    // The whole value of the column depends on it: it must NEVER change again.
    val builder = TestSetupHelper.newTaskManagerBuilder()
    val tm = builder.build()
    val props = tm.customPropertyManager
    val task = tm.newTaskBuilder().withName("t").withDuration(tm.createLength(5)).build()
    val effortDef = EffortDrivenProperties.findOrCreateTaskEffort(props)
    task.customValues.setValue(effortDef, 40.0)
    val person = builder.resourceManager.create("Natalie", 0)
    applyBackfillAsSingleEdit(proposeBackfill(collectBackfillTasks(tm, props), 8.0), person, tm,
      props, db, RunOnly(), "Test")

    // The estimate has been improved -- the original stays.
    task.customValues.setValue(effortDef, 80.0)
    applyBackfillAsSingleEdit(proposeBackfill(collectBackfillTasks(tm, props), 8.0), person, tm,
      props, db, RunOnly(), "Test")
    assertEquals(40.0, task.originalEffortHours(props),
      "die nachgebesserte Schaetzung darf den Ursprung nicht ueberschreiben")
  }
}

/**
 * What an occurrence inherits from the source Task -- and why that decides over life and death
 * of the series.
 *
 * A QUESTION RAISED ON 17.08.2026: do the recurring dates actually work? They did, but only half
 * way: the Tasks produced carried neither "Date fixed" nor a deadline. Capacity levelling would
 * have pushed an advance VAT return from the 10th to the next free day -- and a tax deadline that
 * gets moved is no longer a deadline.
 */
class RecurrenceInheritanceTest {
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

  private class RunOnly : GPUndoManager {
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

  private lateinit var dataSource: JdbcDataSource
  private lateinit var db: ProjectDatabase

  @BeforeEach
  fun init(testInfo: TestInfo) {
    dataSource = JdbcDataSource().also {
      it.setURL("jdbc:h2:mem:erbe${testInfo.displayName.hashCode()}$SQL_PROJECT_DATABASE_OPTIONS")
    }
    db = SqlProjectDatabaseImpl(dataSource).also { it.init() }
  }

  @AfterEach
  fun clear() {
    dataSource.connection.use { it.createStatement().execute("shutdown") }
  }

  @Test
  fun `fester termin, wartezeit und frist wandern mit`() {
    val tm = TestSetupHelper.newTaskManagerBuilder()
      .withCalendar(biz.ganttproject.core.calendar.WeekendCalendarImpl()).build()
    val props = tm.customPropertyManager
    val start = LocalDate.of(2026, 8, 17)
    val quelle = tm.newTaskBuilder().withName("Umsatzsteuervoranmeldung")
      .withStartDate(start.toModelDate()).withDuration(tm.createLength(1)).build()
    quelle.customValues.setValue(findOrCreateRecurrence(props), "monatlich; Anzahl 3")
    quelle.customValues.setValue(findOrCreateDateFixed(props), true)
    // Deadline: three days after the start. This DISTANCE is what repeats.
    quelle.setDeadline(props, start.plusDays(3))

    applyRecurrencesAsSingleEdit(planRecurrences(tm, props), tm, props, db, RunOnly(), "Test")

    val kopien = tm.tasks.filter { it.recurrenceOccurrenceOf(props) != null }
      .sortedBy { it.start.time }
    assertEquals(2, kopien.size)
    kopien.forEach {
      assertTrue(it.isDateFixed(props), "ohne 'Termin fest' verschiebt die Verteilung die Frist")
    }
    // The deadline moves AT THE SAME DISTANCE, it is not copied: otherwise all the occurrences
    // would have the deadline of the first month.
    kopien.forEach { kopie ->
      val kopieStart = kopie.start.time.toModelLocalDate()
      assertEquals(kopieStart.plusDays(3), kopie.deadlineDate(props),
        "die Frist muss drei Tage nach dem eigenen Beginn liegen, nicht im ersten Monat")
    }
  }

  @Test
  fun `ohne festen termin bleibt die wiederholung beweglich`() {
    // Counter-check: the binding is inherited, not invented. A Task that may lie freely produces
    // freely lying occurrences -- otherwise every series would be artificially nailed down.
    val tm = TestSetupHelper.newTaskManagerBuilder()
      .withCalendar(biz.ganttproject.core.calendar.WeekendCalendarImpl()).build()
    val props = tm.customPropertyManager
    val quelle = tm.newTaskBuilder().withName("Wochenbericht")
      .withStartDate(LocalDate.of(2026, 8, 17).toModelDate())
      .withDuration(tm.createLength(1)).build()
    quelle.customValues.setValue(findOrCreateRecurrence(props), "woechentlich; Anzahl 3")

    applyRecurrencesAsSingleEdit(planRecurrences(tm, props), tm, props, db, RunOnly(), "Test")

    tm.tasks.filter { it.recurrenceOccurrenceOf(props) != null }.forEach {
      assertTrue(!it.isDateFixed(props))
      assertEquals(null, it.deadlineDate(props))
    }
  }
}

/**
 * The collecting group per series.
 *
 * A QUESTION RAISED ON 17.08.2026: are they created correctly, that is, side by side with only
 * one text on the left? A single strand of bars in ONE row is not possible -- the bars of a Task
 * arise exclusively from the calendar. A group achieves the same thing for the eye.
 */
class RecurrenceGroupTest {
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

  private class RunOnly : GPUndoManager {
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

  private lateinit var dataSource: JdbcDataSource
  private lateinit var db: ProjectDatabase

  @BeforeEach
  fun init(testInfo: TestInfo) {
    dataSource = JdbcDataSource().also {
      it.setURL("jdbc:h2:mem:grp${testInfo.displayName.hashCode()}$SQL_PROJECT_DATABASE_OPTIONS")
    }
    db = SqlProjectDatabaseImpl(dataSource).also { it.init() }
  }

  @AfterEach
  fun clear() {
    dataSource.connection.use { it.createStatement().execute("shutdown") }
  }

  private fun aufbau(): Triple<net.sourceforge.ganttproject.task.TaskManager,
    biz.ganttproject.customproperty.CustomPropertyManager,
    net.sourceforge.ganttproject.task.Task> {
    val tm = TestSetupHelper.newTaskManagerBuilder()
      .withCalendar(biz.ganttproject.core.calendar.WeekendCalendarImpl()).build()
    val props = tm.customPropertyManager
    val quelle = tm.newTaskBuilder().withName("Umsatzsteuervoranmeldung")
      .withStartDate(LocalDate.of(2026, 8, 17).toModelDate())
      .withDuration(tm.createLength(1)).build()
    quelle.customValues.setValue(findOrCreateRecurrence(props), "monatlich; Anzahl 4")
    return Triple(tm, props, quelle)
  }

  @Test
  fun `alle termine liegen in einer gruppe, der ausgangsvorgang auch`() {
    val (tm, props, quelle) = aufbau()
    applyRecurrencesAsSingleEdit(planRecurrences(tm, props), tm, props, db, RunOnly(), "Test")

    val gruppe = tm.tasks.first { it.recurrenceOf(props) == recurrenceGroupMark(quelle.taskID) }
    val kinder = tm.taskHierarchy.getNestedTasks(gruppe)
    assertEquals(4, kinder.size, "drei Wiederholungen plus der Ausgangsvorgang")
    assertTrue(kinder.contains(quelle), "der Ausgangsvorgang zieht mit hinein")
    assertEquals("Umsatzsteuervoranmeldung", gruppe.name)
  }

  @Test
  fun `der ausgangsvorgang behaelt seinen termin und seine dauer`() {
    // The move into a group must not touch it -- a group DERIVES its dates, its children keep
    // their own.
    val (tm, props, quelle) = aufbau()
    applyRecurrencesAsSingleEdit(planRecurrences(tm, props), tm, props, db, RunOnly(), "Test")

    assertEquals(LocalDate.of(2026, 8, 17), quelle.start.time.toModelLocalDate())
    assertEquals(1, quelle.duration.length)
    assertTrue(tm.taskHierarchy.getNestedTasks(quelle).isEmpty(),
      "er selbst darf KEINE Gruppe geworden sein -- sonst verlaere er seine eigene Dauer")
  }

  @Test
  fun `ein zweiter lauf legt keine zweite gruppe an`() {
    val (tm, props, quelle) = aufbau()
    applyRecurrencesAsSingleEdit(planRecurrences(tm, props), tm, props, db, RunOnly(), "Test")
    val nachher = tm.tasks.size

    quelle.customValues.setValue(findOrCreateRecurrence(props), "monatlich; Anzahl 6")
    applyRecurrencesAsSingleEdit(planRecurrences(tm, props), tm, props, db, RunOnly(), "Test")

    val gruppen = tm.tasks.filter { it.recurrenceOf(props) == recurrenceGroupMark(quelle.taskID) }
    assertEquals(1, gruppen.size, "genau EINE Gruppe, auch nach dem Erweitern der Serie")
    assertEquals(nachher + 2, tm.tasks.size, "und genau die zwei fehlenden Termine kommen dazu")
    assertEquals(6, tm.taskHierarchy.getNestedTasks(gruppen[0]).size)
  }
}

