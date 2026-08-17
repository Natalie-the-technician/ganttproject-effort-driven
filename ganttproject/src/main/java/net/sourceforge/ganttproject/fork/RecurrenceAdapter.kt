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

import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.customproperty.CustomPropertyClass
import biz.ganttproject.customproperty.CustomPropertyDefinition
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.storage.ProjectDatabase
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import net.sourceforge.ganttproject.task.algorithm.effortHours
import net.sourceforge.ganttproject.task.algorithm.findEffortDefinition
import net.sourceforge.ganttproject.undo.GPUndoManager
import java.time.LocalDate

/**
 * Serienvorgaenge im Projektmodell: aus einem Vorgang mit Wiederholung werden viele.
 *
 * DREI EIGENSCHAFTEN, die dieses Hilfsmittel brauchbar statt gefaehrlich machen:
 *
 * 1. **Es ist wiederholbar.** Jede angelegte Wiederholung traegt die Kennung ihres
 *    Ausgangsvorgangs UND ihren Termin. Ein zweiter Aufruf legt deshalb nichts doppelt an --
 *    weder nach einer Planaenderung noch nach einem Abbruch mittendrin. Ein Hilfsmittel, das beim
 *    zweiten Klick 40 Doppelvorgaenge erzeugt, benutzt man genau einmal.
 * 2. **Es fasst Vorhandenes nicht an.** Weder den Ausgangsvorgang noch bereits angelegte
 *    Wiederholungen: wer eine davon verschoben oder abgehakt hat, behaelt das.
 * 3. **Es haengt die Wiederholungen NEBEN den Ausgangsvorgang**, in dieselbe Gruppe. Sie unter
 *    den Ausgangsvorgang zu haengen wuerde ihn zu einer Gruppe machen -- und eine Gruppe leitet
 *    ihre Termine aus den Kindern ab, womit der urspruengliche Vorgang seine eigene Dauer
 *    verloere.
 */

/** Die Wiederholungsregel als Text. [Fork-Aenderung] */
const val TASK_RECURRENCE = "recurrence"

/**
 * Kennzeichen einer angelegten Wiederholung: "Kennung des Ausgangsvorgangs @ Termin".
 *
 * WARUM AUCH DER TERMIN darin steht und nicht nur die Kennung: ohne ihn liesse sich nicht
 * erkennen, WELCHER Termin schon existiert. Nach einer Aenderung der Regel -- aus "bis 2027" wird
 * "bis 2030" -- muessen die fehlenden Termine nachgelegt werden, ohne die vorhandenen zu
 * verdoppeln.
 */
const val TASK_RECURRENCE_OF = "recurrence_of"

fun findOrCreateRecurrence(manager: CustomPropertyManager): CustomPropertyDefinition =
  manager.findEffortDefinition(TASK_RECURRENCE)
    ?: manager.createDefinition(TASK_RECURRENCE, CustomPropertyClass.TEXT.iD,
                                forkText("fork.column.recurrence"), null)

fun findOrCreateRecurrenceOf(manager: CustomPropertyManager): CustomPropertyDefinition =
  manager.findEffortDefinition(TASK_RECURRENCE_OF)
    ?: manager.createDefinition(TASK_RECURRENCE_OF, CustomPropertyClass.TEXT.iD,
                                forkText("fork.column.recurrenceOf"), null)

/** Der eingetragene Wiederholungstext, oder null. */
fun Task.recurrenceText(manager: CustomPropertyManager): String? {
  val def = manager.findEffortDefinition(TASK_RECURRENCE) ?: return null
  return this.customValues.getValue(def)?.toString()?.takeIf { it.isNotBlank() }
}

/** Das Kennzeichen einer angelegten Wiederholung, oder null. */
fun Task.recurrenceOf(manager: CustomPropertyManager): String? {
  val def = manager.findEffortDefinition(TASK_RECURRENCE_OF) ?: return null
  return this.customValues.getValue(def)?.toString()?.takeIf { it.isNotBlank() }
}

/**
 * Ist dieser Vorgang die Sammelgruppe einer Serie?
 *
 * NOETIG, WEIL BEIDE DASSELBE FELD BENUTZEN: die Gruppe und ihre Termine tragen ihr Kennzeichen in
 * derselben Spalte. Wer nur auf "Kennzeichen vorhanden" prueft, haelt die Gruppe fuer einen Termin
 * -- und liest dann ihre ABGELEITETE Dauer als die eines Vorgangs. Genau das haben vier eigene
 * Tests sofort gemeldet (26 Tage statt 3).
 */
fun Task.isRecurrenceGroup(manager: CustomPropertyManager): Boolean =
  this.recurrenceOf(manager)?.endsWith("@Serie") == true

/** Das Kennzeichen, wenn dieser Vorgang ein einzelner Serientermin ist (nicht die Gruppe). */
fun Task.recurrenceOccurrenceOf(manager: CustomPropertyManager): String? =
  this.recurrenceOf(manager)?.takeIf { !it.endsWith("@Serie") }

/** Wie eine angelegte Wiederholung gekennzeichnet wird. */
fun recurrenceMark(sourceTaskId: Int, date: LocalDate): String = "$sourceTaskId@$date"

/**
 * Kennzeichen der Sammelgruppe einer Serie.
 *
 * WOZU EINE GRUPPE: ohne sie steht jeder Termin als eigene Zeile mit demselben Text in der
 * Tabelle -- bei monatlich ueber zwei Jahre 24 gleichnamige Zeilen. Die Anforderung dazu, am
 * 17.08.2026 festgehalten: nebeneinander angelegt, mit nur einem Text links.
 *
 * Ein einziger Balkenstrang in EINER Zeile geht nicht: die Balken eines Vorgangs entstehen
 * ausschliesslich aus dem Kalender (`TaskActivitiesAlgorithm.recalculateActivities` fragt
 * `calendar.getActivities(start, end)`), ein Vorgang mit eigenen Luecken ist im Modell nicht
 * vorgesehen. Eine Gruppe leistet dasselbe fuer das Auge: zugeklappt eine Zeile, aufgeklappt alle
 * Termine.
 *
 * Das Kennzeichen macht den zweiten Lauf eindeutig -- er findet die Gruppe wieder, statt eine
 * zweite anzulegen.
 */
fun recurrenceGroupMark(sourceTaskId: Int): String = "$sourceTaskId@Serie"

/** Ein Termin, der angelegt werden soll. */
data class PlannedOccurrence(
  val sourceTaskId: Int,
  val sourceName: String,
  val date: LocalDate,
  val mark: String
)

/** Was beim Anlegen herauskommt, BEVOR etwas geschrieben wird. */
data class RecurrencePlan(
  val occurrences: List<PlannedOccurrence>,
  /** Serien mit unlesbarer Regel: Vorgangsname -> Fehler. */
  val errors: Map<String, List<String>>,
  /** Serien, die an der Obergrenze abgeschnitten wurden. */
  val truncated: List<String>,
  /** Anzahl der Serien, die etwas beizutragen haben. */
  val seriesCount: Int
) {
  val hasErrors: Boolean get() = errors.isNotEmpty()
}

/**
 * Sammelt, was anzulegen waere. Schreibt nichts.
 *
 * Bereits vorhandene Wiederholungen werden ueber ihr Kennzeichen erkannt -- deshalb ist ein
 * zweiter Aufruf folgenlos.
 */
fun planRecurrences(
  taskManager: TaskManager,
  taskProperties: CustomPropertyManager
): RecurrencePlan {
  val isWorkingDay = workingDayTest(taskManager.calendar)
  val vorhanden = taskManager.tasks.mapNotNull { it.recurrenceOf(taskProperties) }.toSet()

  val geplant = mutableListOf<PlannedOccurrence>()
  val errors = mutableMapOf<String, List<String>>()
  val truncated = mutableListOf<String>()
  var serien = 0

  taskManager.tasks.forEach { task ->
    val text = task.recurrenceText(taskProperties) ?: return@forEach
    val gelesen = RecurrenceRule.parse(text)
    if (gelesen.hasErrors || gelesen.rule == null) {
      errors[task.name ?: task.taskID.toString()] = gelesen.errors
      return@forEach
    }
    val start = task.start?.time?.toModelLocalDate() ?: return@forEach
    val termine = occurrences(gelesen.rule, start, isWorkingDay)
    if (isTruncated(gelesen.rule, start, isWorkingDay)) {
      truncated.add(task.name ?: task.taskID.toString())
    }
    // Der erste Termin IST der Ausgangsvorgang -- er wird nicht noch einmal angelegt.
    val fehlend = termine.drop(1)
      .map { PlannedOccurrence(task.taskID, task.name.orEmpty(), it, recurrenceMark(task.taskID, it)) }
      .filter { it.mark !in vorhanden }
    if (fehlend.isNotEmpty()) {
      serien++
      geplant.addAll(fehlend)
    }
  }
  return RecurrencePlan(geplant, errors, truncated, serien)
}

/**
 * Legt die geplanten Wiederholungen an, als EIN Rueckgaengig-Schritt.
 *
 * @return die Zahl der angelegten Vorgaenge.
 */
fun applyRecurrencesAsSingleEdit(
  plan: RecurrencePlan,
  taskManager: TaskManager,
  taskProperties: CustomPropertyManager,
  projectDatabase: ProjectDatabase,
  undoManager: GPUndoManager,
  editName: String
): Int {
  if (plan.occurrences.isEmpty()) {
    return 0
  }
  val markDef = findOrCreateRecurrenceOf(taskProperties)
  val effortDef = EffortDrivenProperties.findOrCreateTaskEffort(taskProperties)
  // Ohne diesen Aufruf gibt es die Definition ohne Datenbankspalte, und jedes Schreiben scheitert.
  // In Sitzung 3 genau so passiert.
  projectDatabase.onCustomColumnChange(taskProperties)

  var angelegt = 0
  undoManager.undoableEdit(editName) {
    // Wie bei der Verteilung ruht der Planer waehrend des Schreibens: jeder neue Vorgang wuerde
    // ihn sonst ueber den ganzen Abhaengigkeitsgraphen laufen lassen.
    val scheduler = taskManager.algorithmCollection.scheduler
    val wasEnabled = scheduler.isEnabled
    scheduler.isEnabled = false
    try {
      // Je Serie eine Sammelgruppe: zugeklappt eine Zeile statt zwoelf gleichnamiger.
      val gruppen = mutableMapOf<Int, net.sourceforge.ganttproject.task.Task>()
      plan.occurrences.map { it.sourceTaskId }.distinct().forEach { quellId ->
        val quelle = taskManager.getTask(quellId) ?: return@forEach
        val vorhandene = taskManager.tasks.firstOrNull {
          it.recurrenceOf(taskProperties) == recurrenceGroupMark(quellId)
        }
        if (vorhandene != null) {
          gruppen[quellId] = vorhandene
          return@forEach
        }
        // Die Gruppe entsteht dort, wo der Ausgangsvorgang stand -- die Gliederung bleibt.
        val gruppe = taskManager.newTaskBuilder()
          .withName(quelle.name)
          .withStartDate(quelle.start.time)
          .withDuration(quelle.duration)
          .withParent(taskManager.taskHierarchy.getContainer(quelle))
          .build()
        gruppe.customValues.setValue(markDef, recurrenceGroupMark(quellId))
        // Der Ausgangsvorgang zieht mit hinein: alle Termine der Serie stehen zusammen.
        // ERST DIE GRUPPE, DANN DER UMZUG -- ein Vorgang, der in sich selbst zoege, verschwaende
        // seine Termine.
        taskManager.taskHierarchy.move(quelle, gruppe)
        gruppen[quellId] = gruppe
      }
      plan.occurrences.forEach { termin ->
        val quelle = taskManager.getTask(termin.sourceTaskId) ?: return@forEach
        val neu = taskManager.newTaskBuilder()
          .withName(quelle.name)
          .withStartDate(termin.date.toModelDate())
          .withDuration(quelle.duration)
          .withParent(gruppen[termin.sourceTaskId]
            ?: taskManager.taskHierarchy.getContainer(quelle))
          .withColor(quelle.color)
          .withPriority(quelle.priority)
          .build()
        neu.customValues.setValue(markDef, termin.mark)
        // Aufwand und Zuordnung mitnehmen: ohne beides kennt die Kapazitaetsverteilung die
        // Wiederholung nicht, und der ganze Zweck -- die wiederkehrende Arbeit sichtbar zu
        // machen -- waere verfehlt.
        quelle.effortHours(taskProperties)?.let { neu.customValues.setValue(effortDef, it) }
        quelle.assignments.forEach { zuordnung ->
          zuordnung.resource?.let { person ->
            neu.assignmentCollection.addAssignment(person).load = zuordnung.load
          }
        }
        // TERMINBINDUNG UND ART MITNEHMEN. Eine Nachfrage am 17.08.2026 hat die Luecke
        // aufgedeckt: eine Umsatzsteuervoranmeldung ist zum 10. faellig, nicht "irgendwann im
        // Monat". Ohne diese Zeilen haette die Kapazitaetsverteilung sie auf den naechsten freien
        // Tag geschoben -- und die Serie waere wertlos geworden.
        //
        // Was der Ausgangsvorgang traegt, tragen seine Wiederholungen auch:
        if (quelle.isDateFixed(taskProperties)) {
          neu.customValues.setValue(findOrCreateDateFixed(taskProperties), true)
        }
        if (quelle.isWaitOnly(taskProperties)) {
          neu.customValues.setValue(findOrCreateWaitOnly(taskProperties), true)
        }
        // DIE FRIST WANDERT MIT, mit demselben Abstand zum Anfang. Sie einfach zu kopieren waere
        // falsch: dann haetten alle zwoelf Monatsvorgaenge dieselbe Frist im Januar. Der ABSTAND
        // ist das, was sich wiederholt -- "drei Tage nach Beginn" bleibt in jedem Monat gleich.
        val quellStart = quelle.start?.time?.toModelLocalDate()
        val quellFrist = quelle.deadlineDate(taskProperties)
        if (quellStart != null && quellFrist != null) {
          val abstand = java.time.temporal.ChronoUnit.DAYS.between(quellStart, quellFrist)
          neu.setDeadline(taskProperties, termin.date.plusDays(abstand))
        }
        // Die Wiederholung selbst traegt KEINE Wiederholungsregel -- sonst erzeugte die naechste
        // Ausfuehrung Wiederholungen von Wiederholungen.
        angelegt++
      }
    } finally {
      scheduler.isEnabled = wasEnabled
    }
    scheduler.run()
  }
  return angelegt
}

/** Nur fuer die Vorschau: ein Datum, wie GanttProject es anzeigt. */
internal fun LocalDate.forDisplay(): String =
  CalendarFactory.createGanttCalendar(this.toModelDate()).toString()
