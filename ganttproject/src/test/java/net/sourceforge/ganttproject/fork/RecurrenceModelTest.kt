/*
Copyright 2026

NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.

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
 * Serienvorgaenge im ECHTEN Projektmodell.
 *
 * WOZU NEBEN [RecurrenceTest]: die Terminrechnung ist dort geprueft. Was hier geprueft wird, ist
 * das, was in dieser Sitzung schon zweimal schiefging und keine reine Rechnung finden kann -- das
 * Zusammenspiel mit Mutator, Planer, Kalender und Eigenschaftsverwaltung. Insbesondere die
 * Zusage "ein zweiter Aufruf legt nichts doppelt an": genau die entscheidet, ob man das
 * Hilfsmittel mehr als einmal benutzen kann.
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

  // Eine ECHTE Datenbank, keine Attrappe: `onCustomColumnChange` ist genau die Stelle, an der in
  // Sitzung 3 das Schreiben scheiterte ("Column effort_hours not found"). Eine Attrappe haette
  // das nicht gefunden. Jeder Test bekommt seine eigene, sonst ueberlebt eine Spalte aus dem
  // vorigen Test und die Pruefung ist wertlos.
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
    // 3 Wiederholungen + Ausgangsvorgang + die SAMMELGRUPPE, die denselben Namen traegt.
    assertEquals(5, tm.tasks.count { it.name == "Umsatzsteuervoranmeldung" })
    assertEquals(1, tm.tasks.count { it.isRecurrenceGroup(props) })
  }

  @Test
  fun `ein zweiter aufruf legt nichts doppelt an`() {
    // DIE ENTSCHEIDENDE ZUSAGE. Ein Hilfsmittel, das beim zweiten Klick alles verdoppelt,
    // benutzt man genau einmal -- und traut sich danach nie wieder.
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

    // Aus 3 werden 6: es duerfen genau die drei fehlenden dazukommen.
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

    // recurrenceOccurrenceOf, NICHT recurrenceOf: sonst faellt hier die Sammelgruppe herein, und
    // ihre Dauer ist abgeleitet (26 Tage statt 3).
    val kopie = tm.tasks.first { it.recurrenceOccurrenceOf(props) != null }
    assertEquals(3, kopie.duration.length, "die Dauer des Ausgangsvorgangs")
    assertEquals(24.0, kopie.customValues.getValue(effortDef), "und sein Aufwand")
    assertNotNull(kopie.recurrenceOf(props))
  }

  @Test
  fun `die wiederholung traegt selbst keine regel`() {
    // Sonst erzeugte der naechste Lauf Wiederholungen von Wiederholungen -- und der uebernaechste
    // Wiederholungen davon.
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
    // Wuerden die Wiederholungen UNTER den Ausgangsvorgang gehaengt, leitete er seine Termine aus
    // ihnen ab und verlore seine eigene Dauer.
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
    // Die Aktion legt bei Fehlern nichts an; hier wird nachgewiesen, dass der Fehler ueberhaupt
    // bis zum Aufrufer kommt, statt still uebersprungen zu werden.
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
 * Die Spalten dieses Forks muessen ENTSTEHEN, sonst kann sie niemand ausfuellen.
 *
 * AM 17.08.2026 GEMESSEN: vier von ihnen -- "Fertig bis", "Auslastung", "Warten", "Termin fest" --
 * wurden nirgends angelegt. Sie existierten im Code, die Verteilung las sie brav und fand immer
 * nichts. Von aussen ist das nicht von "funktioniert nicht" zu unterscheiden.
 *
 * Dieser Test ist die Wache dagegen. Er prueft die Kennungen, nicht die Anzeigenamen: die
 * Anzeigenamen sind uebersetzt, die Kennungen nicht.
 */
class ForkColumnsExistTest {
  init {
    // GanttProjectImpl baut einen Kalender; ohne diese Anmeldung stirbt schon der Konstruktor.
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
    // Genau das war der Grund, warum die Spalten NICHT im Konstruktor entstehen duerfen: eine
    // zweite Anlage derselben Kennung sprengt beim Laden die ganze Datei.
    val project = net.sourceforge.ganttproject.GanttProjectImpl()
    project.ensureCapacityColumns()
    val vorher = project.taskCustomColumnManager.definitions.size
    project.ensureCapacityColumns()
    assertEquals(vorher, project.taskCustomColumnManager.definitions.size)
  }
}

/**
 * Der urspruengliche Aufwand wird auch dann nachgezogen, wenn es sonst nichts zu befuellen gibt.
 *
 * AM RECHNER GEMESSEN, am Plan: der Dialog sagte "bei 162 Vorgaengen wird der heutige
 * Aufwand als urspruenglicher festgehalten" -- geschrieben wurde nichts, weil
 * `applyBackfillAsSingleEdit` bei `changeCount == 0` vorher zurueckkam. Eine Zusage im Dialog, die
 * das Programm nicht einhaelt, ist schlimmer als eine fehlende Zusage.
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

  // Eine ECHTE Datenbank statt GanttProjectImpl: dessen vorgegebener Datenbankvertreter wirft
  // "Not supposed to be called", sobald eine Spalte angelegt wird.
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

    // Genau die Lage aus dem Plan: alles hat Aufwand, es gibt nichts abzuleiten.
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
    // Der ganze Wert der Spalte haengt daran: sie darf sich NIE wieder aendern.
    val builder = TestSetupHelper.newTaskManagerBuilder()
    val tm = builder.build()
    val props = tm.customPropertyManager
    val task = tm.newTaskBuilder().withName("t").withDuration(tm.createLength(5)).build()
    val effortDef = EffortDrivenProperties.findOrCreateTaskEffort(props)
    task.customValues.setValue(effortDef, 40.0)
    val person = builder.resourceManager.create("Natalie", 0)
    applyBackfillAsSingleEdit(proposeBackfill(collectBackfillTasks(tm, props), 8.0), person, tm,
      props, db, RunOnly(), "Test")

    // Schaetzung nachgebessert -- der Ursprung bleibt.
    task.customValues.setValue(effortDef, 80.0)
    applyBackfillAsSingleEdit(proposeBackfill(collectBackfillTasks(tm, props), 8.0), person, tm,
      props, db, RunOnly(), "Test")
    assertEquals(40.0, task.originalEffortHours(props),
      "die nachgebesserte Schaetzung darf den Ursprung nicht ueberschreiben")
  }
}

/**
 * Was eine Wiederholung vom Ausgangsvorgang erbt -- und warum das ueber Leben und Tod der Serie
 * entscheidet.
 *
 * NATALIES FRAGE, 17.08.2026: "Funktionieren die Serientermine eigentlich?" Sie taten es, aber
 * nur halb: die erzeugten Vorgaenge trugen weder "Termin fest" noch eine Frist. Die
 * Kapazitaetsverteilung haette eine Umsatzsteuervoranmeldung vom 10. auf den naechsten freien Tag
 * geschoben -- und eine Steuerfrist, die verschoben wird, ist keine Frist mehr.
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
    // Frist: drei Tage nach Beginn. Dieser ABSTAND ist das, was sich wiederholt.
    quelle.setDeadline(props, start.plusDays(3))

    applyRecurrencesAsSingleEdit(planRecurrences(tm, props), tm, props, db, RunOnly(), "Test")

    val kopien = tm.tasks.filter { it.recurrenceOccurrenceOf(props) != null }
      .sortedBy { it.start.time }
    assertEquals(2, kopien.size)
    kopien.forEach {
      assertTrue(it.isDateFixed(props), "ohne 'Termin fest' verschiebt die Verteilung die Frist")
    }
    // Die Frist wandert MIT DEMSELBEN ABSTAND, sie wird nicht kopiert: sonst haetten alle
    // Wiederholungen die Frist des ersten Monats.
    kopien.forEach { kopie ->
      val kopieStart = kopie.start.time.toModelLocalDate()
      assertEquals(kopieStart.plusDays(3), kopie.deadlineDate(props),
        "die Frist muss drei Tage nach dem eigenen Beginn liegen, nicht im ersten Monat")
    }
  }

  @Test
  fun `ohne festen termin bleibt die wiederholung beweglich`() {
    // Gegenprobe: die Bindung wird geerbt, nicht erfunden. Ein Vorgang, der frei liegen darf,
    // erzeugt frei liegende Wiederholungen -- sonst waere jede Serie kuenstlich festgenagelt.
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
 * Die Sammelgruppe je Serie.
 *
 * NATALIES FRAGE, 17.08.2026: "Werden sie auch richtig angelegt, also nebeneinander mit nur einem
 * Text links?" Ein einziger Balkenstrang in EINER Zeile geht nicht -- die Balken eines Vorgangs
 * entstehen ausschliesslich aus dem Kalender. Eine Gruppe leistet dasselbe fuers Auge.
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
    // Der Umzug in eine Gruppe darf ihn nicht antasten -- eine Gruppe LEITET ihre Termine ab,
    // ihre Kinder behalten die eigenen.
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

