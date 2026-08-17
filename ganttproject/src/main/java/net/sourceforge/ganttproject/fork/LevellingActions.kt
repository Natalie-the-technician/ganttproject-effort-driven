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

import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.action.GPAction
import net.sourceforge.ganttproject.GanttPreviousState
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.storage.ProjectDatabase
import net.sourceforge.ganttproject.task.TaskManager
import net.sourceforge.ganttproject.undo.GPUndoManager
import java.awt.event.ActionEvent
import java.time.LocalDate

/**
 * Die beiden Menuepunkte der Kapazitaetsverteilung.
 *
 * BEIDE FRAGEN VORHER. Das ist keine Hoeflichkeit: die Verteilung kann 167 von 226 Vorgaengen
 * verschieben, das Befuellen 226 anfassen. Beides ungefragt auszufuehren waere genau die stille
 * Aenderung, die dieser Fork an mehreren Stellen bereits gefunden hat. Und beides ist EIN
 * Rueckgaengig-Schritt.
 *
 * Die Vorschau nennt auch, was NICHT passiert und warum -- uebersprungene Vorgaenge mit Grund,
 * Konflikte mit Datum. Eine Vorschau, die nur die Erfolgszahl zeigt, verschweigt das Wesentliche.
 */

/**
 * Was der Aufrufer anzeigen soll: Text, und ein Rueckruf mit der Antwort.
 *
 * `fun interface` statt `typealias`, weil die Verdrahtung in `GanttProject.java` liegt: ein
 * Kotlin-typealias auf einen Funktionstyp ist aus Java nicht ansprechbar.
 *
 * Der Rueckruf statt eines Rueckgabewerts ist ebenfalls kein Zierrat: `showOptionDialog` blockiert
 * nicht, die Antwort kommt also spaeter.
 */
fun interface AskBeforeWriting {
  fun ask(message: String, answer: (Boolean) -> Unit)
}

/** Aufwand aus der Dauer ableiten und alles einer Person zuordnen. */
class BackfillAction(
  private val taskManager: TaskManager,
  private val resourceManager: HumanResourceManager,
  private val taskProperties: CustomPropertyManager,
  private val resourceProperties: CustomPropertyManager,
  private val projectDatabase: ProjectDatabase,
  private val undoManager: GPUndoManager,
  private val report: (Boolean, String) -> Unit,
  private val ask: AskBeforeWriting
) : GPAction("levelling.backfill") {

  // Die Beschriftung kommt aus dem Buendel dieses Forks: GPAction kennt nur die Schluessel des
  // Originals und wuerde sonst den nackten Schluessel anzeigen.
  override fun getLocalizedName(): String = forkText("fork.levelling.backfill")

  override fun actionPerformed(event: ActionEvent?) {
    val resources = resourceManager.resources
    if (resources.isEmpty()) {
      report(true, forkText("fork.levelling.noResource"))
      return
    }
    // Bei genau einer Person ist die Zuordnung eindeutig. Bei mehreren wird nicht geraten.
    if (resources.size > 1) {
      report(true, forkText("fork.levelling.manyResources", resources.size))
      return
    }
    // Ein fehlerhafter Stundenplan wuerde still auf die feste Stundenzahl zurueckfallen. Lieber
    // gar nicht rechnen als plausibel falsch rechnen.
    val probleme = capacityProblems(taskManager, taskProperties, resourceManager, resourceProperties)
    if (probleme.hasErrors) {
      val text = StringBuilder()
      probleme.errors.forEach { (person, fehler) ->
        text.append(forkText("fork.capacity.error.title", person)).appendLine()
        fehler.forEach { text.append("  • ").append(it).appendLine() }
      }
      text.appendLine().append(forkText("fork.capacity.error.consequence"))
      report(true, text.toString())
      return
    }
    val resource = resources[0]
    val hoursPerDay = resource.dailyHours(resourceProperties)
    val proposal = proposeBackfill(collectBackfillTasks(taskManager, taskProperties), hoursPerDay)
    val ohneUrsprung = tasksMissingOriginalEffort(taskManager, taskProperties).size

    if (proposal.changeCount == 0 && ohneUrsprung == 0) {
      report(false, forkText("fork.levelling.backfill.nothing"))
      return
    }
    val skipped = proposal.skipped.values.groupingBy { it }.eachCount()
    val message = forkText("fork.levelling.backfill.preview",
      proposal.effortHours.size, proposal.assignTo.size, resource.name, hoursPerDay,
      skipped[BackfillSkip.CONTAINER] ?: 0,
      skipped[BackfillSkip.MILESTONE] ?: 0,
      skipped[BackfillSkip.ALREADY_HAS_EFFORT] ?: 0,
      skipped[BackfillSkip.WAIT_ONLY] ?: 0)
    val text = StringBuilder(message)
    if (ohneUrsprung > 0) {
      text.appendLine().appendLine().append(forkText("fork.levelling.backfill.original", ohneUrsprung))
    }
    ask.ask(text.toString()) { confirmed ->
      if (!confirmed) {
        return@ask
      }
      val touched = applyBackfillAsSingleEdit(proposal, resource, taskManager, taskProperties,
        projectDatabase, undoManager, forkText("fork.levelling.backfill.undo"))
      report(false, forkText("fork.levelling.backfill.done", touched))
    }
  }
}

/** Die Vorgaenge so verteilen, dass niemand mehr als 100 % gleichzeitig leisten muss. */
class LevellingAction(
  private val taskManager: TaskManager,
  private val resourceManager: HumanResourceManager,
  private val taskProperties: CustomPropertyManager,
  private val resourceProperties: CustomPropertyManager,
  private val undoManager: GPUndoManager,
  /**
   * Die Basisplaene des Projekts. Vor dem Verteilen wird angeboten, den heutigen Stand zu
   * sichern -- ohne das sind die bisherigen Termine nach dem Speichern weg, und Rueckgaengig
   * hilft nur, solange das Programm laeuft.
   *
   * EIN ZULIEFERER UND KEINE LISTE, und das ist am Rechner gemessen: `GanttProject` ERSETZT sein
   * Feld `myPreviousStates` beim Schliessen eines Projekts durch eine neue ArrayList
   * (`GanttProject.java:819`). Die Menuepunkte entstehen beim Start; wer die Liste dort einmal
   * festhaelt, schreibt nach dem ersten Oeffnen einer Datei in eine Liste, die niemand mehr liest.
   * Gemessen: der Basisplan wurde bestaetigt, gemeldet -- und stand nicht in der Datei.
   */
  private val baselines: () -> MutableList<GanttPreviousState>,
  private val today: () -> LocalDate = { LocalDate.now() },
  private val report: (Boolean, String) -> Unit,
  private val ask: AskBeforeWriting
) : GPAction("levelling.run") {

  override fun getLocalizedName(): String = forkText("fork.levelling.run")

  override fun actionPerformed(event: ActionEvent?) {
    // Ein fehlerhafter Stundenplan wuerde still auf die feste Stundenzahl zurueckfallen. Lieber
    // gar nicht rechnen als plausibel falsch rechnen.
    val probleme = capacityProblems(taskManager, taskProperties, resourceManager, resourceProperties)
    if (probleme.hasErrors) {
      val text = StringBuilder()
      probleme.errors.forEach { (person, fehler) ->
        text.append(forkText("fork.capacity.error.title", person)).appendLine()
        fehler.forEach { text.append("  • ").append(it).appendLine() }
      }
      text.appendLine().append(forkText("fork.capacity.error.consequence"))
      report(true, text.toString())
      return
    }
    // DIE FRAGE ZUR VERGANGENHEIT, und sie kommt VOR allem anderen -- die Antwort aendert die
    // Rechnung, nicht nur das Schreiben. Gestellt wird sie nur, wenn es solche Vorgaenge gibt:
    // eine Frage ohne Anlass ist eine Frage, die man wegklickt.
    val liegengeblieben = unstartedInThePast(taskManager, today())
    if (liegengeblieben.isEmpty()) {
      weiter(moveUnstartedPast = true, verschobeneAusDerVergangenheit = 0)
      return
    }
    val frage = StringBuilder(forkText("fork.levelling.past.ask", liegengeblieben.size))
      .appendLine().appendLine().append(forkText("fork.levelling.past.what"))
      .appendLine().appendLine().append(forkText("fork.levelling.past.hint"))
    liegengeblieben.take(5).forEach {
      frage.appendLine().append("  - ").append(it.name)
    }
    ask.ask(frage.toString()) { verschieben ->
      weiter(verschieben, if (verschieben) liegengeblieben.size else 0)
    }
  }

  private fun weiter(moveUnstartedPast: Boolean, verschobeneAusDerVergangenheit: Int) {
    val tasks = collectLevelTasks(taskManager, taskProperties, resourceProperties, today(),
      moveUnstartedPast)
    if (tasks.isEmpty()) {
      report(false, forkText("fork.levelling.noTasks"))
      return
    }
    // toModelLocalDate, nicht java.time: siehe LegacyDates.kt.
    val projectStart = taskManager.projectStart?.toModelLocalDate() ?: today()
    // AB HEUTE, nicht ab Projektbeginn: unerledigte Arbeit in die Vergangenheit zu legen ergibt
    // keinen Plan. Was schon angefangen ist, bleibt trotzdem liegen -- das regelt `frozen`.
    val abWann = maxOf(projectStart, today())
    val auslastung = resourceManager.resources.associate {
      it.id.toString() to it.utilisationPercent(resourceProperties)
    }
    // Die Packgrenze bleibt bei 100 %: der Auslastungsgrad wirkt auf die verfuegbaren STUNDEN
    // (siehe availableHoursPerDay) und steckt damit bereits in den Dauern. Ihn hier ein zweites
    // Mal anzuwenden hiesse, denselben Puffer zweimal abzuziehen.
    val result = levelTasks(tasks, abWann, workingDayTest(taskManager.calendar),
      durationAtStart(taskManager, taskProperties, resourceProperties))

    val cycles = result.conflicts.filterIsInstance<LevelConflict.Cycle>()
    if (cycles.isNotEmpty()) {
      report(true, forkText("fork.levelling.cycle"))
      return
    }

    val moved = result.starts.count { (id, start) ->
      val task = taskManager.getTask(id.toIntOrNull() ?: return@count false) ?: return@count false
      task.start.time.toModelLocalDate() != start
    }
    if (moved == 0 && result.conflicts.isEmpty()) {
      report(false, forkText("fork.levelling.nothing"))
      return
    }

    val fristen = result.conflicts.filterIsInstance<LevelConflict.DeadlineMissed>()
    val eingefroren = tasks.count { it.frozen }
    val unreachable = result.conflicts.filterIsInstance<LevelConflict.FixedDateNotReachable>()
    val overloads = result.conflicts.filterIsInstance<LevelConflict.Overload>()
    val message = StringBuilder(forkText("fork.levelling.preview", moved, tasks.size))
    if (unreachable.isNotEmpty()) {
      message.append("\n\n").append(forkText("fork.levelling.unreachable", unreachable.size))
      unreachable.take(5).forEach {
        val name = taskManager.getTask(it.id.toIntOrNull() ?: 0)?.name ?: it.id
        message.append("\n  • ").append(
          forkText("fork.levelling.unreachable.row", name, it.fixedStart, it.earliestPossible))
      }
      if (unreachable.size > 5) {
        message.append("\n  … ").append(forkText("fork.levelling.more", unreachable.size - 5))
      }
    }
    if (overloads.isNotEmpty()) {
      message.append("\n\n").append(forkText("fork.levelling.overload", overloads.size))
    }
    // FRISTEN, EINGEFRORENE ARBEIT UND AUSLASTUNG GEHOEREN IN DIE VORSCHAU. Sie hatten einmal
    // hier gestanden und sind bei einem spaeteren Umbau verlorengegangen -- am Bildschirm
    // aufgefallen: die Rechnung kannte eine verpasste Frist, der Dialog schwieg darueber. Eine
    // Vorschau, die den wichtigsten Befund verschweigt, ist schlimmer als keine.
    if (fristen.isNotEmpty()) {
      message.append("\n\n").append(forkText("fork.levelling.deadline", fristen.size))
      fristen.take(5).forEach {
        val name = taskManager.getTask(it.id.toIntOrNull() ?: 0)?.name ?: it.id
        message.append("\n  • ").append(forkText("fork.levelling.deadline.row",
          name, it.deadline, it.actualEnd, it.missingDays))
      }
      if (fristen.size > 5) {
        message.append("\n  … ").append(forkText("fork.levelling.more", fristen.size - 5))
      }
    }
    if (eingefroren > 0) {
      message.append("\n\n").append(forkText("fork.levelling.frozen", eingefroren))
    }
    val gradWerte = auslastung.values.distinct()
    if (gradWerte.size == 1 && gradWerte[0] != 100) {
      message.append("\n\n").append(forkText("fork.levelling.utilisation", gradWerte[0]))
    }
    ask.ask(message.toString()) { confirmed ->
      if (!confirmed) {
        return@ask
      }
      // ZUERST der Basisplan, DANN das Verteilen. Andersherum haelt er die schon verschobenen
      // Termine fest und ist wertlos.
      val baselineText = StringBuilder(forkText("fork.baseline.ask"))
        .appendLine().appendLine().append(forkText("fork.baseline.what"))
        .appendLine().appendLine().append(forkText("fork.baseline.hint"))
      ask.ask(baselineText.toString()) { sichern ->
        val meldung = StringBuilder()
        if (sichern) {
          val name = forkText("fork.baseline.name", today().toString())
          val basisplan = GanttPreviousState(name, GanttPreviousState.createTasks(taskManager))
          // init() UND saveFile() SIND PFLICHT, und das sieht man dem Konstruktor nicht an: ein
          // Basisplan haelt seine Vorgaenge NICHT im Speicher, sondern in einer Temporaerdatei.
          // `load()` -- was der Speicherer und das Diagramm aufrufen -- liest genau diese Datei.
          // Ohne die beiden Aufrufe ist `myFile` null: der Basisplan waere in der Liste sichtbar,
          // im Diagramm unsichtbar und beim Speichern eine Ausnahme. Am Code nachgesehen
          // (GanttPreviousState.java:55/75/99), nachdem der erste Anlauf sie weggelassen hatte.
          try {
            basisplan.init()
            basisplan.saveFile()
            baselines().add(basisplan)
            meldung.append(forkText("fork.baseline.done", name)).appendLine()
          } catch (e: java.io.IOException) {
            // Kein Basisplan ist schlecht; ein halber waere schlimmer. Lieber melden und
            // weitermachen -- die Verteilung selbst ist davon unberuehrt.
            net.sourceforge.ganttproject.GPLogger.log(e)
            meldung.append(forkText("fork.baseline.failed")).appendLine()
          }
        }
        val written = applyLevellingAsSingleEdit(result.starts, taskManager, undoManager,
          forkText("fork.levelling.undo"), result.durations)
        report(false, meldung.append(forkText("fork.levelling.done", written)).toString())
      }
    }
  }
}
