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

import biz.ganttproject.core.calendar.GPCalendar
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.customproperty.CustomPropertyClass
import biz.ganttproject.customproperty.CustomPropertyDefinition
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.storage.ProjectDatabase
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskImpl
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import net.sourceforge.ganttproject.task.algorithm.availableHoursPerDay
import net.sourceforge.ganttproject.task.algorithm.capacitySchedule
import net.sourceforge.ganttproject.task.algorithm.effortHours
import net.sourceforge.ganttproject.task.algorithm.findEffortDefinition
import net.sourceforge.ganttproject.task.algorithm.hoursPerDay
import net.sourceforge.ganttproject.undo.GPUndoManager
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * Verbindet das Projektmodell mit den reinen Rechnungen in [levelTasks] und [proposeBackfill].
 *
 * Getrennt gehalten, und zwar aus dem Grund, der in den Notizen steht: In Stufe 1 steckten zwei
 * Fehler hinter 300 gruenen Tests, weil genau diese Verdrahtung nicht pruefbar war. Die Rechnungen
 * kennen keine GanttProject-Typen und werden ohne Programm geprueft; hier liegt alles, was das
 * Modell anfasst, an einer Stelle und ist damit ueberschaubar.
 */

/** Der Haken „Termin fest". [Fork-Aenderung] */
const val TASK_DATE_FIXED = "date_fixed"

/**
 * Der Haken „Warten". [Fork-Aenderung]
 *
 * Ein Wartevorgang kostet KEINE Arbeitszeit, bestimmt aber die Reihenfolge: die Bearbeitung beim
 * Amt, eine Lieferfrist, ein Bescheid. Ohne diese Unterscheidung belegt jede Wartezeit die Person,
 * als saesse sie die ganze Zeit daran -- im Plan sind das 12 Vorgaenge mit zusammen 1092
 * Tagen. Nachtraeglich vorgeschlagen, und der Vorschlag trifft genau den Punkt.
 */
const val TASK_WAIT_ONLY = "wait_only"

fun findOrCreateWaitOnly(manager: CustomPropertyManager): CustomPropertyDefinition =
  manager.findEffortDefinition(TASK_WAIT_ONLY)
    ?: manager.createDefinition(TASK_WAIT_ONLY, CustomPropertyClass.BOOLEAN.iD,
                                forkText("fork.column.waitOnly"), null)

/** Ist der Vorgang reine Wartezeit? */
fun Task.isWaitOnly(manager: CustomPropertyManager): Boolean {
  val def = manager.findEffortDefinition(TASK_WAIT_ONLY) ?: return false
  val raw = this.customValues.getValue(def) ?: return false
  return raw as? Boolean ?: raw.toString().equals("true", ignoreCase = true)
}

/**
 * Spaetestes Ende. [Fork-Aenderung]
 *
 * Die harten Termine im Plan sind ENDtermine -- Umsatzsteuervoranmeldung zum 10., Jahresabschluss,
 * Antragsfristen. "Termin fest" haelt dagegen einen ANFANG. Beides ist noetig, und beides bedeutet
 * etwas anderes.
 */
const val TASK_DEADLINE = "deadline"

fun findOrCreateDeadline(manager: CustomPropertyManager): CustomPropertyDefinition =
  manager.findEffortDefinition(TASK_DEADLINE)
    ?: manager.createDefinition(TASK_DEADLINE, CustomPropertyClass.DATE.iD,
                                forkText("fork.column.deadline"), null)

/**
 * Traegt die Frist ein.
 *
 * ES MUSS EIN `GregorianCalendar` SEIN, kein `Date`. Eine Spalte vom Typ Datum lehnt ein `Date`
 * ab: "value class=class java.util.Date, column class=class java.util.GregorianCalendar". Am
 * Rechner gemessen, als die Serienvorgaenge ihre Frist weitergeben sollten.
 */
fun Task.setDeadline(manager: CustomPropertyManager, date: LocalDate) {
  this.customValues.setValue(findOrCreateDeadline(manager),
    CalendarFactory.createGanttCalendar(date.toModelDate()))
}

/** Die eingetragene Frist, oder null. */
fun Task.deadlineDate(manager: CustomPropertyManager): LocalDate? {
  val def = manager.findEffortDefinition(TASK_DEADLINE) ?: return null
  return when (val raw = this.customValues.getValue(def)) {
    null -> null
    is LocalDate -> raw
    is java.util.Date -> raw.toModelLocalDate()
    is java.util.GregorianCalendar -> raw.time.toModelLocalDate()
    else -> runCatching { LocalDate.parse(raw.toString()) }.getOrNull()
  }
}

/**
 * Der urspruenglich geschaetzte Aufwand. [Fork-Aenderung]
 *
 * Wird GENAU EINMAL gesetzt und danach nie wieder angefasst -- das ist der ganze Sinn. Wer eine
 * Schaetzung nachbessert, vergleicht die Ist-Stunden sonst gegen die nachgebesserte Zahl und lernt
 * nichts mehr ueber seine Schaetzguete: die Abweichung verschwindet genau in dem Moment, in dem
 * man sie bemerkt.
 *
 * Der Punkt dazu, festgehalten am 17.08.2026: dass am Ende 15 statt 9 Stunden gebraucht wurden,
 * ist die Aussage -- ueber welchen Zeitraum die Stunden verteilt waren, ist dafuer egal.
 */
const val TASK_EFFORT_ORIGINAL = "effort_original_hours"

fun findOrCreateOriginalEffort(manager: CustomPropertyManager): CustomPropertyDefinition =
  manager.findEffortDefinition(TASK_EFFORT_ORIGINAL)
    ?: manager.createDefinition(TASK_EFFORT_ORIGINAL, CustomPropertyClass.DOUBLE.iD,
                                forkText("fork.column.effortOriginal"), null)

/** Die urspruengliche Schaetzung, oder null. */
fun Task.originalEffortHours(manager: CustomPropertyManager): Double? {
  val def = manager.findEffortDefinition(TASK_EFFORT_ORIGINAL) ?: return null
  val raw = this.customValues.getValue(def) ?: return null
  return (raw as? Number)?.toDouble() ?: raw.toString().toDoubleOrNull()
}

/**
 * Haelt die heutige Schaetzung fest, falls noch keine festgehalten ist.
 *
 * @return true, wenn etwas geschrieben wurde.
 */
fun Task.rememberOriginalEffort(manager: CustomPropertyManager): Boolean {
  if (this.originalEffortHours(manager) != null) {
    return false
  }
  val heute = this.effortHours(manager) ?: return false
  this.customValues.setValue(findOrCreateOriginalEffort(manager), heute)
  return true
}

fun findOrCreateDateFixed(manager: CustomPropertyManager): CustomPropertyDefinition =
  manager.findEffortDefinition(TASK_DATE_FIXED)
    ?: manager.createDefinition(TASK_DATE_FIXED, CustomPropertyClass.BOOLEAN.iD,
                                forkText("fork.column.dateFixed"), null)

/**
 * Traegt der Vorgang den Haken „Termin fest"?
 *
 * Gesucht wird ueber id ODER Name -- dieselbe Falle wie beim Aufwand: eine vom Benutzer selbst
 * angelegte Spalte traegt den getippten Text als Namen und eine erzeugte Kennung (`tpc0`).
 */
fun Task.isDateFixed(manager: CustomPropertyManager): Boolean {
  val def = manager.findEffortDefinition(TASK_DATE_FIXED) ?: return false
  val raw = this.customValues.getValue(def) ?: return false
  return raw as? Boolean ?: raw.toString().equals("true", ignoreCase = true)
}

/**
 * Vorgaenge, die in der Vergangenheit liegen und an denen noch nicht gearbeitet wurde.
 *
 * Sie sind der Grund fuer die Rueckfrage vor dem Verteilen: sie stehen zu lassen waere eine Luege
 * ueber den Plan, sie ungefragt zu verschieben ein Umschreiben der Vergangenheit.
 */
fun unstartedInThePast(taskManager: TaskManager, today: LocalDate = LocalDate.now()): List<Task> {
  val hierarchy = taskManager.taskHierarchy
  return taskManager.tasks.filter { task ->
    hierarchy.getNestedTasks(task).isEmpty() &&
      task.completionPercentage == 0 &&
      task.start.time.toModelLocalDate() < today
  }
}

/**
 * Vorgaenge, die einen Aufwand tragen, deren URSPRUENGLICHER aber noch nicht festgehalten ist.
 *
 * WOZU: Der Plan, an dem dieser Fork entwickelt wurde, hatte 162 Vorgaenge mit Aufwand, bevor es
 * die Spalte "Aufwand urspruenglich" ueberhaupt gab. Ohne einmaliges Nachziehen haette die
 * Auswertung "geschaetzt gegen gebraucht" fuer diese Vorgaenge NIE eine Grundlage -- sie wuerde
 * bis in alle Zukunft melden, es gebe nichts zu vergleichen.
 *
 * Nachgezogen wird nur auf ausdrueckliche Nachfrage, nicht beim Oeffnen: es ist ein Schreibvorgang
 * am Plan, und der gehoert nicht in einen Ladevorgang.
 */
fun tasksMissingOriginalEffort(
  taskManager: TaskManager, taskProperties: CustomPropertyManager
): List<Task> {
  val hierarchy = taskManager.taskHierarchy
  return taskManager.tasks.filter { task ->
    hierarchy.getNestedTasks(task).isEmpty() &&
      task.effortHours(taskProperties) != null &&
      task.originalEffortHours(taskProperties) == null
  }
}

/** Arbeitstage von [from] (einschliesslich) bis [to] (ausschliesslich). */
internal fun workingDaysBetween(
  from: LocalDate, to: LocalDate, isWorkingDay: (LocalDate) -> Boolean
): Int {
  var tage = 0
  var tag = from
  var schutz = 0
  while (tag < to && schutz++ < 100_000) {
    if (isWorkingDay(tag)) tage++
    tag = tag.plusDays(1)
  }
  return tage
}

/** Der Kalender des Projekts als Funktion, damit die Rechnung ihn ohne Modell benutzen kann. */
fun workingDayTest(calendar: GPCalendar): (LocalDate) -> Boolean = { day ->
  calendar.getDayMask(day.toLegacyDate()) and GPCalendar.DayMask.WORKING != 0
}

// Die Umrechnung liegt in LegacyDates.kt und benutzt AUSDRUECKLICH NICHT java.time:
// GanttProject verbiegt beim Start die Standard-Zeitzone, und java.time sieht die Verbiegung
// nicht. Die Begruendung samt Messung steht dort.
private fun LocalDate.toLegacyDate(): Date = this.toModelDate()

private fun Date.toLocalDate(): LocalDate = this.toModelLocalDate()

/**
 * Sammelt die Blattvorgaenge fuer die Verteilung.
 *
 * NUR BLAETTER, und das ist keine Vereinfachung: Gruppen leiten ihre Termine aus den Kindern ab.
 * Einer Gruppe einen Start zuzuweisen wuerde vom Modell stillschweigend verworfen -- derselbe
 * Grund, aus dem der Aufwand nur an Blaettern haengt.
 */
fun collectLevelTasks(
  taskManager: TaskManager,
  taskProperties: CustomPropertyManager,
  resourceProperties: CustomPropertyManager,
  /** Der heutige Tag. Alles davor ist Vergangenheit. */
  today: LocalDate = LocalDate.now(),
  /**
   * Ob nicht begonnene Vorgaenge aus der Vergangenheit nach vorn geschoben werden duerfen.
   *
   * DIESE ANTWORT GIBT DER MENSCH, nicht der Algorithmus. Ein Vorgang ohne erfasste Zeit, der in
   * der Vergangenheit liegt, ist liegengeblieben -- ihn stehen zu lassen waere eine Luege ueber
   * den Plan, ihn ungefragt zu verschieben ein Umschreiben der Vergangenheit.
   */
  moveUnstartedPast: Boolean = true
): List<LevelTask> {
  val hierarchy = taskManager.taskHierarchy
  val isWorkingDay = workingDayTest(taskManager.calendar)

  // Kennung -> Blaetter darunter. Fuer ein Blatt es selbst, fuer eine Gruppe alle ihre Blaetter.
  //
  // WARUM DAS NOETIG IST: Abhaengigkeiten zeigen in einem gewachsenen Plan auch auf GRUPPEN
  // ("nach Abschluss von Kapitel 3"). Die Verteilung rechnet aber nur mit Blaettern -- eine
  // Gruppe hat keinen eigenen Termin. Ohne diese Aufloesung faellt so eine Verknuepfung
  // stillschweigend weg.
  //
  // AM RECHNER GEMESSEN, bevor das hier stand: 46 von 162 Vorgaengen wurden nach dem Ausgleich vom
  // Planer wieder nach hinten geschoben, einer um 10644 Tage. Der Ausgleich hatte die Verknuepfung
  // nicht gesehen, der Planer setzte sie hinterher durch -- und die Kapazitaetsrechnung war damit
  // wertlos, obwohl sie fuer sich richtig gerechnet hatte.
  val leavesUnder = mutableMapOf<String, List<String>>()
  fun collectLeaves(task: Task): List<String> {
    val nested = hierarchy.getNestedTasks(task)
    // MEILENSTEINE ZAEHLEN MIT, obwohl sie keine Arbeit sind.
    //
    // AM RECHNER GEMESSEN, als sie hier ausgeschlossen waren: 46 von 162 Vorgaengen wurden nach
    // dem Ausgleich vom Planer wieder verschoben, einer von 2027 nach 2056. Seine Vorgaenger waren
    // zwei Meilensteine -- die Verknuepfung fehlte in der Rechnung und wurde hinterher durchgesetzt.
    // 23 der 28 Meilensteine im Plan haben Nachfolger.
    //
    // Sie kommen mit Auslastung 0 herein: sie ordnen, ohne Kapazitaet zu kosten.
    val leaves = if (nested.isEmpty()) {
      listOf(task.taskID.toString())
    } else {
      nested.flatMap { collectLeaves(it) }
    }
    leavesUnder[task.taskID.toString()] = leaves
    return leaves
  }
  hierarchy.getNestedTasks(hierarchy.rootTask).forEach { collectLeaves(it) }

  val result = mutableListOf<LevelTask>()
  var order = 0
  fun walk(task: Task) {
    val nested = hierarchy.getNestedTasks(task)
    if (nested.isEmpty()) {
      result.add(task.toLevelTask(order++, taskProperties, resourceProperties, leavesUnder, today,
        isWorkingDay, moveUnstartedPast))
    } else {
      nested.forEach { walk(it) }
    }
  }
  hierarchy.getNestedTasks(hierarchy.rootTask).forEach { walk(it) }
  return result
}

/**
 * Was am Stundenplan der Beteiligten nicht stimmt.
 *
 * WOZU EIN EIGENER DURCHGANG: die Rechnung selbst faellt bei einem fehlerhaften Stundenplan auf
 * die feste Stundenzahl zurueck -- sie kann mitten im Durchlauf keinen Dialog aufmachen. Genau
 * dieser Rueckfall ist aber die gefaehrliche Stelle: der Plan saehe richtig aus und waere es nicht.
 * Deshalb wird VOR der Arbeit gefragt, und der Mensch entscheidet.
 */
data class CapacityProblems(
  /** Lesefehler im Text, je Person einmal. */
  val errors: Map<String, List<String>>,
  /** Vorgaenge, die mit der eingetragenen Tagesleistung nie fertig werden (Abschnitt mit 0 Std.). */
  val unreachable: List<String>
) {
  val hasErrors: Boolean get() = errors.isNotEmpty()
}

fun capacityProblems(
  taskManager: TaskManager,
  taskProperties: CustomPropertyManager,
  resourceManager: HumanResourceManager,
  resourceProperties: CustomPropertyManager
): CapacityProblems {
  val errors = mutableMapOf<String, List<String>>()
  resourceManager.resources.forEach { resource ->
    val result = resource.capacitySchedule(resourceProperties)
    if (result.hasErrors) {
      errors[resource.name ?: resource.id.toString()] = result.errors
    }
  }
  val isWorkingDay = workingDayTest(taskManager.calendar)
  val unreachable = mutableListOf<String>()
  if (errors.isEmpty()) {
    taskManager.tasks.forEach { task ->
      val effort = task.effortHours(taskProperties) ?: return@forEach
      val schedule = task.capacitySchedule(resourceProperties)
      if (schedule.schedule.isConstant) return@forEach
      val start = task.start?.time?.toModelLocalDate() ?: return@forEach
      if (daysNeeded(effort, start, schedule.schedule, isWorkingDay = isWorkingDay) == null) {
        unreachable.add(task.name ?: task.taskID.toString())
      }
    }
  }
  return CapacityProblems(errors, unreachable)
}

/**
 * Die Dauer eines Vorgangs, wenn er an einem bestimmten Tag beginnt.
 *
 * Ohne zeitabhaengige Tagesleistung ist das immer dieselbe Zahl, und die Verteilung verhaelt sich
 * wie zuvor. Mit Abschnitten haengt die Dauer vom Starttag ab -- deshalb eine Funktion und keine
 * Zahl im [LevelTask].
 */
fun durationAtStart(
  taskManager: TaskManager,
  taskProperties: CustomPropertyManager,
  resourceProperties: CustomPropertyManager
): (LevelTask, LocalDate) -> Int {
  val isWorkingDay = workingDayTest(taskManager.calendar)
  return fabrik@{ levelTask, start ->
    val task = taskManager.getTask(levelTask.id.toIntOrNull() ?: return@fabrik levelTask.durationDays)
      ?: return@fabrik levelTask.durationDays
    val effort = task.effortHours(taskProperties) ?: return@fabrik levelTask.durationDays
    val schedule = task.capacitySchedule(resourceProperties)
    if (schedule.hasErrors || schedule.schedule.isConstant) {
      return@fabrik levelTask.durationDays
    }
    daysNeeded(effort, start, schedule.schedule, isWorkingDay = isWorkingDay)
      ?: levelTask.durationDays
  }
}

private fun Task.toLevelTask(
  order: Int, taskProperties: CustomPropertyManager, resourceProperties: CustomPropertyManager,
  leavesUnder: Map<String, List<String>>, today: LocalDate, isWorkingDay: (LocalDate) -> Boolean,
  moveUnstartedPast: Boolean
): LevelTask {
  // Die Auslastung aus den Zuordnungen. Ohne Zuordnung gilt 100 %: der Vorgang belegt den Tag,
  // auch wenn niemand eingetragen ist. Ihn als kostenlos zu behandeln waere die gefaehrlichere
  // Annahme -- er verschwaende Kapazitaet, die es nicht gibt.
  // Meilensteine und Wartezeiten kosten keine Arbeitszeit. Alles andere belegt den Tag voll, auch
  // ohne Zuordnung: einen unzugeordneten Vorgang als kostenlos zu behandeln waere die
  // gefaehrlichere Annahme -- er verbraucht Zeit, die der Plan dann nicht kennt.
  val load = when {
    this.isMilestone || this.isWaitOnly(taskProperties) -> 0
    else -> this.assignments.sumOf { it.load.toDouble() }.toInt().let { if (it <= 0) 100 else it }
  }
  // DREI FAELLE, und sie sind nicht dasselbe. Die Regel dazu, festgelegt am 17.08.2026:
  //
  //   Ein Vorgang, mit dem noch gar nicht begonnen wurde, auf dem also keine Zeit liegt, muss
  //   aufgeschoben werden -- dafuer braucht es aber vorher die Frage, ob das der Fall ist.
  //   Was auf keinen Fall geht: etwas zu veraendern, das schon abgeschlossen ist. Und steht ab
  //   heute doppelt so viel Zeit zur Verfuegung, werden auch die bereits angefangenen Vorgaenge
  //   schneller fertig.
  //
  // 1. ABGESCHLOSSEN (100 %): unantastbar. Weder Termin noch Dauer werden angefasst -- auch die
  //    Dauer nicht, denn sie ist gemessene Vergangenheit und keine Vorhersage mehr.
  // 2. ANGEFANGEN (0 < % < 100): der ANFANG steht, er ist Vergangenheit. Der REST wird mit der
  //    heutigen Tagesleistung gerechnet: mehr Stunden am Tag heissen frueher fertig.
  // 3. NICHT BEGONNEN (0 %): darf verschoben werden. Liegt so ein Vorgang in der Vergangenheit,
  //    FRAGT die Aktion vorher -- ungefragt die Vergangenheit umzuschreiben waere genau das, was
  //    nicht passieren darf.
  val startTag = this.start.time.toLocalDate()
  val fertig = this.completionPercentage >= 100
  val angefangen = this.completionPercentage > 0
  val available = this.availableHoursPerDay(resourceProperties)
  val effort = this.effortHours(taskProperties)
  val duration = when {
    // Fall 1: gemessene Vergangenheit, keine Rechnung.
    fertig -> this.duration.length.coerceAtLeast(1)
    // Fall 2: verstrichener Teil plus der Rest zur heutigen Tagesleistung.
    angefangen && effort != null && available > 0.0 -> {
      val restAnteil = (100 - this.completionPercentage).coerceIn(0, 100) / 100.0
      val verstrichen = if (startTag < today) workingDaysBetween(startTag, today, isWorkingDay) else 0
      verstrichen + durationFromEffort(effort * restAnteil, available)
    }
    effort != null && available > 0.0 -> durationFromEffort(effort, available)
    else -> this.duration.length.coerceAtLeast(1)
  }
  // Fest steht der Termin bei 1 und 2; bei 3 nur, wenn der Haken "Termin fest" gesetzt ist --
  // oder wenn der Mensch entschieden hat, liegengebliebene Arbeit NICHT nach vorn zu schieben.
  val liegengeblieben = !angefangen && startTag < today
  val bleibtLiegen = liegengeblieben && !moveUnstartedPast
  val fixed = if (this.isDateFixed(taskProperties) || angefangen || bleibtLiegen) startTag else null
  val earliest = if (this.thirdDateConstraint == TaskImpl.EARLIESTBEGIN && this.third != null) {
    this.third.time.toLocalDate()
  } else {
    null
  }
  return LevelTask(
    id = this.taskID.toString(),
    orderInPlan = order,
    // Priority.ordinal, NICHT der gespeicherte Wert: der ist nicht nach Wichtigkeit sortiert.
    priority = this.priority.ordinal,
    durationDays = duration,
    loadPercent = load,
    // Eine Abhaengigkeit auf eine Gruppe heisst: nach ALLEN Blaettern darunter.
    predecessors = this.dependenciesAsDependant.toArray()
      .mapNotNull { it.dependee?.taskID?.toString() }
      .flatMap { leavesUnder[it] ?: listOf(it) }
      .distinct(),
    fixedStart = fixed,
    earliestStart = earliest,
    frozen = angefangen || bleibtLiegen,
    deadline = this.deadlineDate(taskProperties),
    // Wessen Kapazitaet belegt wird. Meilensteine und Wartevorgaenge belegen mit 0 % ohnehin
    // nichts; sie bekommen trotzdem ihre Zuordnung mit, damit die Auswertung stimmt.
    resourceIds = this.assignments.mapNotNull { it.resource?.id?.toString() }.distinct()
  )
}

/**
 * Schreibt die verteilten Termine, als EIN Rueckgaengig-Schritt.
 *
 * @return die Zahl der tatsaechlich verschobenen Vorgaenge. Nichts zu verschieben heisst: kein
 * Eintrag in der Rueckgaengig-Liste. Ein leerer Schritt, der aussieht als waere etwas passiert,
 * ist schlimmer als keiner -- dieselbe Regel wie beim Toggl-Import.
 */
fun applyLevellingAsSingleEdit(
  starts: Map<String, LocalDate>,
  taskManager: TaskManager,
  undoManager: GPUndoManager,
  editName: String,
  /**
   * Die Dauer, mit der die Verteilung gerechnet hat. Fehlt ein Eintrag, bleibt die bisherige
   * Dauer stehen.
   *
   * WARUM DAS NOETIG IST: bei zeitabhaengiger Tagesleistung braucht derselbe Aufwand in einem
   * Abschnitt mit vier Stunden mehr Tage als in einem mit acht. Wer beim Zurueckschreiben die
   * ALTE Dauer nimmt, schreibt ein Ende, das zur gerechneten Belegung nicht passt -- und die
   * Verteilung waere fuer die betroffenen Tage wertlos.
   */
  durations: Map<String, Int> = emptyMap()
): Int {
  val isWorkingDay = workingDayTest(taskManager.calendar)
  val moves = starts.mapNotNull { (id, newStart) ->
    val task = taskManager.getTask(id.toIntOrNull() ?: return@mapNotNull null)
      ?: return@mapNotNull null
    val neueDauer = durations[id] ?: task.duration.length
    if (task.start.time.toLocalDate() == newStart && neueDauer == task.duration.length) null
    else Triple(task, newStart, neueDauer.coerceAtLeast(1))
  }
  if (moves.isEmpty()) {
    return 0
  }
  undoManager.undoableEdit(editName) {
    // WARUM DER PLANER WAEHRENDDESSEN AUS IST: jedes commit() stoesst ihn sonst erneut an, und er
    // laeuft ueber den ganzen Abhaengigkeitsgraphen. Bei 162 Vorgaengen wird daraus quadratischer
    // Aufwand. AM RECHNER GEMESSEN, bevor das hier stand: nach 767 Sekunden Rechenzeit und 2 GB
    // Speicher war das Programm immer noch nicht fertig und musste abgebrochen werden.
    //
    // Dasselbe Muster benutzt das Original bei Sammeloperationen, siehe TaskActions.kt:207.
    val scheduler = taskManager.algorithmCollection.scheduler
    val wasEnabled = scheduler.isEnabled
    scheduler.isEnabled = false
    try {
      moves.forEach { (task, newStart, keepDays) ->
        val mutator = task.createMutator()
        val calendar = CalendarFactory.createGanttCalendar(newStart.toLegacyDate())
        mutator.setStart(calendar)
        // DAS ENDE MUSS MITGESETZT WERDEN, und zwar das Ende, nicht die Dauer.
        //
        // setStart allein verschiebt nur den Anfang und laesst das Ende stehen -- GanttProject
        // DEHNT den Vorgang dadurch. AM RECHNER GEMESSEN: die fuenf "Jahresblock"-Vorgaenge hatten
        // davor 3 Tage Dauer und danach 25, 286, 545, 803 und 1060. Sie belegten Jahre statt Tage,
        // und der Ausgleich lieferte 1204 ueberlastete Tage mit Spitze 600 % -- genau das, was er
        // verhindern soll. Der Fehler sah aus wie ein Rechenfehler und lag in der Verdrahtung.
        //
        // setDuration() HILFT HIER NICHT, auch das ist gemessen: MutatorImpl.commit() wendet die
        // Dauer nur ueber `myDurationChange.ifChanged` an. Die Dauer soll aber gleich BLEIBEN --
        // der Aufruf ist damit keine Aenderung und wird uebersprungen. Das Ende ist der Wert, der
        // sich tatsaechlich aendert.
        // Meilensteine haben keine Dauer -- ein Ende zu setzen wuerde aus ihnen einen Vorgang
        // machen. Sie werden nur verschoben.
        if (!task.isMilestone) {
          mutator.setEnd(CalendarFactory.createGanttCalendar(
            endAfterWorkingDays(newStart, keepDays, isWorkingDay).toLegacyDate()))
        }
        // DER START ALLEIN UEBERLEBT DEN PLANER NICHT. SchedulerImpl legt jeden Vorgang so frueh,
        // wie die Abhaengigkeiten es zulassen, und laeuft bei jedem Oeffnen und jeder Aenderung.
        // Ein verteilter Termin, der nur als Start gesetzt ist, wird beim naechsten Lauf
        // zurueckgezogen.
        //
        // AM RECHNER GEMESSEN, bevor diese Zeile hier stand: nach dem Ausgleich blieben 1204 Tage
        // ueberlastet, Spitze 600 % -- der Ausgleich hatte gerechnet und der Planer es teilweise
        // wieder eingerissen.
        //
        // Die untere Schranke ("fruehester Beginn") ist das einzige, was er respektiert. Sie
        // verhindert nur das Vorziehen: waechst spaeter eine Dauer, rutscht der Vorgang weiterhin
        // nach hinten.
        mutator.setThird(calendar, TaskImpl.EARLIESTBEGIN)
        mutator.commit()
      }
    } finally {
      scheduler.isEnabled = wasEnabled
    }
    // Einmal am Ende: die Gruppen muessen ihre abgeleiteten Termine nachziehen.
    scheduler.run()
  }
  return moves.size
}

/** Sammelt die Vorgaenge fuer die Ableitung von Aufwand und Zuordnung. */
fun collectBackfillTasks(
  taskManager: TaskManager, taskProperties: CustomPropertyManager
): List<BackfillTask> {
  val hierarchy = taskManager.taskHierarchy
  val result = mutableListOf<BackfillTask>()
  fun walk(task: Task) {
    val nested = hierarchy.getNestedTasks(task)
    result.add(BackfillTask(
      id = task.taskID.toString(),
      name = task.name ?: "",
      durationDays = task.duration.length,
      isContainer = nested.isNotEmpty(),
      isMilestone = task.isMilestone,
      isWaitOnly = task.isWaitOnly(taskProperties),
      existingEffortHours = task.effortHours(taskProperties),
      assignmentCount = task.assignments.size))
    nested.forEach { walk(it) }
  }
  hierarchy.getNestedTasks(hierarchy.rootTask).forEach { walk(it) }
  return result
}

/**
 * Schreibt Aufwand und Zuordnungen, als EIN Rueckgaengig-Schritt.
 *
 * @return die Zahl der geaenderten Vorgaenge.
 */
fun applyBackfillAsSingleEdit(
  proposal: BackfillProposal,
  resource: HumanResource,
  taskManager: TaskManager,
  taskProperties: CustomPropertyManager,
  projectDatabase: ProjectDatabase,
  undoManager: GPUndoManager,
  editName: String
): Int {
  // KEIN frueher Ausstieg bei changeCount == 0: es kann nichts abzuleiten geben und trotzdem
  // etwas zu tun sein, naemlich den urspruenglichen Aufwand nachzuziehen. AM RECHNER GEMESSEN:
  // im Plan war genau das der Fall -- der Dialog sagte "bei 162 Vorgaengen wird der
  // heutige Aufwand als urspruenglicher festgehalten", geschrieben wurde nichts, weil die
  // Funktion vorher zurueckkam.
  if (proposal.changeCount == 0 &&
    tasksMissingOriginalEffort(taskManager, taskProperties).isEmpty()) {
    return 0
  }
  val effortDef = EffortDrivenProperties.findOrCreateTaskEffort(taskProperties)
  // Ohne diesen Aufruf existiert die Definition ohne Datenbankspalte, und jedes Schreiben
  // scheitert. In Sitzung 3 genau so passiert. Uebergeben wird der MANAGER, nicht die einzelne
  // Definition -- die Datenbank gleicht alle Spalten ab.
  projectDatabase.onCustomColumnChange(taskProperties)

  var touched = 0
  undoManager.undoableEdit(editName) {
    // Wie bei der Verteilung: waehrend des Schreibens ruht der Planer. Jede einzelne Zuordnung
    // wuerde sonst die aufwandsgetriebene Rechnung UND den Planer ueber den ganzen Graphen
    // anstossen -- bei 162 Vorgaengen dauert das laenger als jede Geduld.
    val scheduler = taskManager.algorithmCollection.scheduler
    val wasEnabled = scheduler.isEnabled
    scheduler.isEnabled = false
    try {
    proposal.effortHours.forEach { (id, hours) ->
      taskManager.getTask(id.toIntOrNull() ?: return@forEach)?.let { task ->
        task.customValues.setValue(effortDef, hours)
        // Die Schaetzung zugleich als URSPRUENGLICHE festhalten. Nur beim ersten Mal -- danach
        // ist sie der feste Vergleichswert fuer die Ist-Stunden.
        task.rememberOriginalEffort(taskProperties)
        touched++
      }
    }
    // Den urspruenglichen Aufwand fuer ALLE Vorgaenge festhalten, die schon einen tragen -- nicht
    // nur fuer die gerade befuellten. Sonst bliebe ein Plan, der vor dieser Spalte entstanden ist,
    // fuer die Auswertung fuer immer unbrauchbar.
    tasksMissingOriginalEffort(taskManager, taskProperties).forEach { task ->
      if (task.rememberOriginalEffort(taskProperties)) {
        touched++
      }
    }
    proposal.assignTo.forEach { id ->
      taskManager.getTask(id.toIntOrNull() ?: return@forEach)?.let { task ->
        if (task.assignments.none { it.resource == resource }) {
          task.assignmentCollection.addAssignment(resource).load = 100.0f
          touched++
        }
      }
    }
    } finally {
      scheduler.isEnabled = wasEnabled
    }
    scheduler.run()
  }
  return touched
}

/**
 * Das Ende eines Vorgangs, der an [start] beginnt und [days] Arbeitstage dauert.
 *
 * GanttProject fuehrt das Ende AUSSCHLIESSLICH: der erste Tag danach. Dieselbe Rechnung wie in der
 * Verteilung, damit beide Seiten dieselben Tage belegen.
 */
private fun endAfterWorkingDays(
  start: LocalDate, days: Int, isWorkingDay: (LocalDate) -> Boolean): LocalDate {
  var day = start
  while (!isWorkingDay(day)) {
    day = day.plusDays(1)
  }
  var counted = 0
  while (true) {
    if (isWorkingDay(day)) {
      counted++
    }
    if (counted >= maxOf(days, 1)) {
      break
    }
    day = day.plusDays(1)
  }
  var end = day.plusDays(1)
  while (!isWorkingDay(end)) {
    end = end.plusDays(1)
  }
  return end
}

/**
 * Der Auslastungsgrad einer Person in Prozent. [Fork-Aenderung]
 *
 * Ohne Eintrag 100. Werte ausserhalb 1..100 werden auf 100 zurueckgesetzt statt zu gelten: ein
 * Vertipper wuerde die Verteilung sonst still unbrauchbar machen -- bei 5 % passt nichts mehr
 * zusammen, und der Plan reichte ins naechste Jahrhundert.
 */
fun HumanResource.utilisationPercent(manager: CustomPropertyManager): Int {
  val def = manager.findEffortDefinition(RESOURCE_UTILISATION) ?: return 100
  val raw = this.getCustomField(def) ?: return 100
  val wert = (raw as? Number)?.toInt() ?: raw.toString().toIntOrNull() ?: return 100
  return if (wert in 1..100) wert else 100
}

/** Auslastungsgrad, als Eigenschaft der Person. [Fork-Aenderung] */
const val RESOURCE_UTILISATION = "utilisation_percent"

fun findOrCreateUtilisation(manager: CustomPropertyManager): CustomPropertyDefinition =
  manager.findEffortDefinition(RESOURCE_UTILISATION)
    ?: manager.createDefinition(RESOURCE_UTILISATION, CustomPropertyClass.INTEGER.iD,
                                forkText("fork.column.utilisation"), null)

/** Die Tagesleistung der Person, auf die sich die Ableitung stuetzt. */
fun HumanResource.dailyHours(resourceProperties: CustomPropertyManager): Double =
  this.hoursPerDay(resourceProperties)
