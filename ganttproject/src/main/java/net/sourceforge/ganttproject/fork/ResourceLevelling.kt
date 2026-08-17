/*
Copyright 2026 Noctuvo

NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.
Kapazitaetsverteilung (levelling). Siehe CLAUDE-NOTES.md, Abschnitt 3, Punkt 2.

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

import java.time.LocalDate

/**
 * Verteilt Vorgaenge so ueber die Zeit, dass eine Person nicht mehr als 100 % gleichzeitig
 * leisten muss.
 *
 * WARUM ES DAS BRAUCHT: GanttProject plant ausschliesslich nach Abhaengigkeiten und legt jeden
 * Vorgang so frueh, wie diese es zulassen. Ressourcen kommen im Planer nicht vor -- `SchedulerImpl`
 * erwaehnt sie kein einziges Mal. Zwanzig Vorgaenge derselben Person zur selben Zeit ueberlappen
 * einfach; das Ressourcendiagramm faerbt es rot, aufgeraeumt wird nichts. Auch die
 * aufwandsgetriebene Dauer dieses Forks aendert daran nichts: sie rechnet jeden Vorgang fuer sich.
 *
 * KEINE GANTTPROJECT-TYPEN HIER, und das ist Absicht. Die Lehre aus Stufe 1 steht in den Notizen:
 * zwei Fehler steckten hinter 300 gruenen Tests, weil die Verdrahtung nicht pruefbar war. Diese
 * Datei rechnet mit `LocalDate` und einfachen Werten und laesst sich ohne laufendes Programm
 * pruefen. Der Kalender kommt als Funktion herein.
 *
 * ENTSCHEIDUNGEN, von Natalie am 17.08.2026 getroffen und hier festgehalten, damit sie nicht
 * spaeter als Annahme gelesen werden:
 *  - Reihenfolge bei Gleichstand: erst Prioritaet, dann Reihenfolge im Plan.
 *  - Gleichzeitige Arbeit ist erlaubt, solange die Summe der Auslastungen 100 % nicht ueberschreitet.
 *  - Feste Termine bleiben stehen. Passen sie nicht, wird der Konflikt GEMELDET, nicht aufgeloest
 *    -- eine Frist stillschweigend zu verschieben, versteckt das Problem.
 *  - Meilensteine sind NICHT fest: sie tragen kein eigenes Datum, sondern folgen ihren
 *    Abhaengigkeiten. (Natalies Einwand, und er ist richtig.)
 */

/** Ein Vorgang, so wie die Verteilung ihn braucht. */
data class LevelTask(
  val id: String,

  /** Reihenfolge im Plan, von oben nach unten. Entscheidet bei gleicher Prioritaet. */
  val orderInPlan: Int,

  /**
   * Groesser heisst wichtiger.
   *
   * ACHTUNG BEIM UMRECHNEN: Die in der Projektdatei gespeicherten Prioritaetswerte von
   * GanttProject sind NICHT nach Wichtigkeit sortiert -- `LOWEST("3")`, `LOW("0")`,
   * `NORMAL("1")`, `HIGH("2")`, `HIGHEST("4")`. Wer nach dieser Zahl sortiert, stellt die
   * niedrigste Prioritaet zwischen HIGH und HIGHEST. Richtig ist die Reihenfolge im Enum
   * (`Priority.ordinal`), und genau die gehoert hier herein.
   */
  val priority: Int,

  /** Dauer in Arbeitstagen. Kommt bereits aus der aufwandsgetriebenen Rechnung. */
  val durationDays: Int,

  /** Auslastung dieser Zuordnung in Prozent. 100 heisst: die Person ist voll gebunden. */
  val loadPercent: Int,

  /** Vorgaenger, Ende-Anfang. */
  val predecessors: List<String> = emptyList(),

  /** „Termin fest": der Vorgang bleibt auf diesem Datum, auch wenn es eng wird. */
  val fixedStart: LocalDate? = null,

  /** „Fruehester Beginn": nicht vor diesem Datum, spaeter aber schon. */
  val earliestStart: LocalDate? = null
)

sealed interface LevelConflict {
  /**
   * Ein fester Termin liegt vor dem fruehestmoeglichen. Der Termin wird gehalten -- gemeldet wird,
   * dass er nicht sauber erreichbar ist.
   */
  data class FixedDateNotReachable(
    val id: String, val fixedStart: LocalDate, val earliestPossible: LocalDate) : LevelConflict

  /** An diesem Tag verlangt die Summe der Vorgaenge mehr als 100 %. Entsteht nur durch feste Termine. */
  data class Overload(val day: LocalDate, val percent: Int, val ids: List<String>) : LevelConflict

  /** Die Vorgaenge haengen im Kreis. Sie werden nicht verteilt. */
  data class Cycle(val ids: List<String>) : LevelConflict
}

data class LevelResult(
  val starts: Map<String, LocalDate>,
  val conflicts: List<LevelConflict>
)

/**
 * @param projectStart frueheste Zeit ueberhaupt.
 * @param isWorkingDay der Kalender, als Funktion. So bleibt die Rechnung ohne Programm pruefbar,
 * und Feiertage kommen aus GanttProjects eigenem Kalender statt aus einer zweiten Wochenendlogik.
 */
fun levelTasks(
  tasks: List<LevelTask>,
  projectStart: LocalDate,
  isWorkingDay: (LocalDate) -> Boolean
): LevelResult {
  val byId = tasks.associateBy { it.id }
  val conflicts = mutableListOf<LevelConflict>()

  val order = topologicalOrder(tasks)
  if (order == null) {
    return LevelResult(emptyMap(), listOf(LevelConflict.Cycle(tasks.map { it.id })))
  }

  // Belegung je Arbeitstag, in Prozent. Nur Tage, an denen etwas liegt, stehen darin.
  val used = mutableMapOf<LocalDate, Int>()
  val starts = mutableMapOf<String, LocalDate>()
  val ends = mutableMapOf<String, LocalDate>()

  for (id in order) {
    val task = byId.getValue(id)

    var earliest = maxOf(projectStart, task.earliestStart ?: projectStart)
    for (p in task.predecessors) {
      ends[p]?.let { earliest = maxOf(earliest, it) }
    }
    earliest = nextWorkingDay(earliest, isWorkingDay)

    val days: List<LocalDate>
    if (task.fixedStart != null) {
      // Termin halten, auch wenn er zu frueh liegt oder die Kapazitaet sprengt. Beides wird
      // gemeldet -- das war die ausdrueckliche Entscheidung: eine Frist ist eine Frist.
      val start = nextWorkingDay(task.fixedStart, isWorkingDay)
      if (start < earliest) {
        conflicts.add(LevelConflict.FixedDateNotReachable(id, start, earliest))
      }
      days = workingDays(start, task.durationDays, isWorkingDay)
    } else {
      days = findEarliestWindow(earliest, task.durationDays, task.loadPercent, used, isWorkingDay)
    }

    days.forEach { used[it] = (used[it] ?: 0) + task.loadPercent }
    starts[id] = days.first()
    ends[id] = nextWorkingDay(days.last().plusDays(1), isWorkingDay)
  }

  // Ueberlast kann nach dem Verteilen nur noch dort stehen, wo feste Termine sie erzwungen haben.
  used.filterValues { it > 100 }.toSortedMap().forEach { (day, percent) ->
    val onThatDay = tasks.filter { t ->
      val s = starts[t.id] ?: return@filter false
      workingDays(s, t.durationDays, isWorkingDay).contains(day)
    }.map { it.id }
    conflicts.add(LevelConflict.Overload(day, percent, onThatDay))
  }

  return LevelResult(starts, conflicts)
}

/**
 * Reihenfolge der Abarbeitung: nur Vorgaenge, deren Vorgaenger schon liegen, und unter diesen der
 * wichtigste, bei Gleichstand der im Plan obere.
 *
 * @return null, wenn die Abhaengigkeiten im Kreis laufen.
 */
private fun topologicalOrder(tasks: List<LevelTask>): List<String>? {
  val open = tasks.associateBy { it.id }.toMutableMap()
  val placed = mutableSetOf<String>()
  val result = mutableListOf<String>()
  while (open.isNotEmpty()) {
    val ready = open.values
      .filter { t -> t.predecessors.all { it in placed || it !in open } }
      // Wichtigstes zuerst; bei Gleichstand das im Plan obere.
      .sortedWith(compareByDescending<LevelTask> { it.priority }.thenBy { it.orderInPlan })
    val next = ready.firstOrNull() ?: return null
    result.add(next.id)
    placed.add(next.id)
    open.remove(next.id)
  }
  return result
}

private fun nextWorkingDay(from: LocalDate, isWorkingDay: (LocalDate) -> Boolean): LocalDate {
  var d = from
  while (!isWorkingDay(d)) {
    d = d.plusDays(1)
  }
  return d
}

/** Die [count] Arbeitstage ab [start] einschliesslich. */
private fun workingDays(
  start: LocalDate, count: Int, isWorkingDay: (LocalDate) -> Boolean): List<LocalDate> {
  val days = mutableListOf<LocalDate>()
  var d = nextWorkingDay(start, isWorkingDay)
  while (days.size < maxOf(count, 1)) {
    if (isWorkingDay(d)) {
      days.add(d)
    }
    if (days.size < maxOf(count, 1)) {
      d = d.plusDays(1)
    }
  }
  return days
}

/**
 * Das frueheste Fenster ab [earliest], in dem der Vorgang durchgehend Platz hat.
 *
 * Es wird NICHT zerstueckelt: ein Vorgang laeuft an aufeinanderfolgenden Arbeitstagen. Eine
 * Unterbrechung waere zwar dichter gepackt, aber ein Plan, in dem eine Aufgabe dreimal fuer je
 * zwei Tage auftaucht, ist nicht mehr lesbar -- und lesbar zu bleiben ist der Zweck der Uebung.
 */
private fun findEarliestWindow(
  earliest: LocalDate,
  durationDays: Int,
  loadPercent: Int,
  used: Map<LocalDate, Int>,
  isWorkingDay: (LocalDate) -> Boolean
): List<LocalDate> {
  var candidate = nextWorkingDay(earliest, isWorkingDay)
  while (true) {
    val window = workingDays(candidate, durationDays, isWorkingDay)
    val blockedAt = window.firstOrNull { (used[it] ?: 0) + loadPercent > 100 }
    if (blockedAt == null) {
      return window
    }
    // Erst nach dem blockierenden Tag weitersuchen: alles davor faellt aus demselben Grund aus.
    candidate = nextWorkingDay(blockedAt.plusDays(1), isWorkingDay)
  }
}
