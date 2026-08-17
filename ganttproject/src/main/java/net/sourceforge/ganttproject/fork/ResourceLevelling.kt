/*
Copyright 2026

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
 * ENTSCHEIDUNGEN, am 17.08.2026 getroffen und hier festgehalten, damit sie nicht
 * spaeter als Annahme gelesen werden:
 *  - Reihenfolge bei Gleichstand: erst Prioritaet, dann Reihenfolge im Plan.
 *  - Gleichzeitige Arbeit ist erlaubt, solange die Summe der Auslastungen 100 % nicht ueberschreitet.
 *  - Feste Termine bleiben stehen. Passen sie nicht, wird der Konflikt GEMELDET, nicht aufgeloest
 *    -- eine Frist stillschweigend zu verschieben, versteckt das Problem.
 *  - Meilensteine sind NICHT fest: sie tragen kein eigenes Datum, sondern folgen ihren
 *    Abhaengigkeiten. (Nachtraeglich eingewandt, und der Einwand ist richtig.)
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
  val earliestStart: LocalDate? = null,
  /**
   * Die Personen, deren Kapazitaet dieser Vorgang belegt.
   *
   * WARUM DAS NOETIG IST: bis dahin hatte die Verteilung EINEN Kapazitaetstopf. Bei zwei Personen
   * haette sie deren Arbeit hintereinander gelegt, als koennten sie nicht gleichzeitig arbeiten --
   * still und plausibel aussehend. Dass im Pruefbetrieb nur eine Person plant, darf aber nicht
   * der Grund sein, warum es richtig aussieht.
   *
   * Leer heisst: niemand ist zugeordnet. Diese Vorgaenge teilen sich einen gemeinsamen Topf --
   * sie belegen Zeit, von der man nur nicht weiss, wessen.
   */
  val resourceIds: List<String> = emptyList(),
  /**
   * Fertige oder angefangene Arbeit: bleibt genau da, wo sie liegt.
   *
   * WARUM DAS NOETIG IST: die Verteilung war ohne dieses Feld ein EINMALWERKZEUG. Beim zweiten
   * Lauf haette sie auch abgehakte Vorgaenge neu gelegt und die Vergangenheit umgeschrieben.
   * Eingefrorene Vorgaenge belegen ihre Kapazitaet weiterhin -- sonst plante die Verteilung
   * angefangene Arbeit doppelt.
   *
   * Der Termin, an dem sie liegen, steht in [fixedStart]: fuer eingefrorene Arbeit ist der
   * heutige Termin per Definition der feste. Ein eigenes Feld waere eine zweite Wahrheit ueber
   * denselben Sachverhalt.
   */
  val frozen: Boolean = false,
  /**
   * Spaetestes Ende. Wird NICHT erzwungen -- wer eine Frist erzwingt, verschiebt nur das Problem
   * an eine Stelle, an der es niemand sieht. Ist sie nicht zu halten, wird sie gemeldet.
   */
  val deadline: LocalDate? = null
)

sealed interface LevelConflict {
  /**
   * Ein fester Termin liegt vor dem fruehestmoeglichen. Der Termin wird gehalten -- gemeldet wird,
   * dass er nicht sauber erreichbar ist.
   */
  data class FixedDateNotReachable(
    val id: String, val fixedStart: LocalDate, val earliestPossible: LocalDate) : LevelConflict

  /** An diesem Tag verlangt die Summe der Vorgaenge mehr als 100 %. Entsteht nur durch feste Termine. */
  data class Overload(
    val day: LocalDate, val percent: Int, val ids: List<String>,
    /** Wessen Kapazitaet ueberschritten ist. Leer: der Topf der nicht zugeordneten Vorgaenge. */
    val resourceId: String = ""
  ) : LevelConflict

  /** Die Vorgaenge haengen im Kreis. Sie werden nicht verteilt. */
  data class Cycle(val ids: List<String>) : LevelConflict

  /**
   * Eine Frist ist mit der vorhandenen Kapazitaet nicht zu halten.
   *
   * @property missingDays um so viele Arbeitstage ist es zu spaet. Die Zahl steht dabei, weil
   * "zu spaet" allein keine Entscheidung erlaubt: zwei Tage loest man anders als vier Monate.
   */
  data class DeadlineMissed(
    val id: String, val deadline: LocalDate, val actualEnd: LocalDate, val missingDays: Int
  ) : LevelConflict
}

data class LevelResult(
  val starts: Map<String, LocalDate>,
  val conflicts: List<LevelConflict>,
  /**
   * Die Dauer, mit der der Vorgang tatsaechlich gelegt wurde.
   *
   * Sie kann von [LevelTask.durationDays] ABWEICHEN, sobald die Tagesleistung zeitabhaengig ist:
   * derselbe Aufwand braucht in einem Abschnitt mit vier Stunden mehr Tage als in einem mit acht.
   * Wer die Termine zurueckschreibt, muss DIESE Dauer verwenden -- sonst stuende im Plan ein Ende,
   * das zur gerechneten Belegung nicht passt.
   */
  val durations: Map<String, Int> = emptyMap()
)

/**
 * @param projectStart frueheste Zeit ueberhaupt.
 * @param isWorkingDay der Kalender, als Funktion. So bleibt die Rechnung ohne Programm pruefbar,
 * und Feiertage kommen aus GanttProjects eigenem Kalender statt aus einer zweiten Wochenendlogik.
 */
/**
 * @param capacityOf wie viel eines Arbeitstages bei dieser Person verplant werden darf. 100
 * heisst: jeder Tag randvoll. Ein Plan, der jeden Tag zu 100 % verplant, geht bei der ersten
 * Stoerung kaputt -- dieser Plan reicht bis 2063, da ist jede Woche eine Stoerung.
 *
 * Eine FUNKTION und keine Zahl, weil der Auslastungsgrad zur Person gehoert: wer den Hauptberuf
 * noch hat, plant anders als jemand in Vollzeit. Der Wert wirkt NUR auf die Suche nach einem
 * freien Fenster; feste Termine und eingefrorene Arbeit bleiben, wo sie sind.
 */
/** Obergrenze der Fenstersuche in Arbeitstagen -- rund 200 Jahre. Wer sie erreicht, hat keinen
 * Rundungsfehler, sondern eine Endlosschleife. */
private const val MAX_SEARCH_DAYS = 50_000

fun levelTasks(
  tasks: List<LevelTask>,
  projectStart: LocalDate,
  isWorkingDay: (LocalDate) -> Boolean,
  durationAt: (LevelTask, LocalDate) -> Int = { task, _ -> task.durationDays },
  capacityOf: (String) -> Int = { 100 }
): LevelResult {
  val byId = tasks.associateBy { it.id }
  val conflicts = mutableListOf<LevelConflict>()

  val order = topologicalOrder(tasks)
  if (order == null) {
    return LevelResult(emptyMap(), listOf(LevelConflict.Cycle(tasks.map { it.id })))
  }

  // Belegung je PERSON und Arbeitstag, in Prozent. Nur Tage, an denen etwas liegt, stehen darin.
  // Der Schluessel "" ist der Topf der nicht zugeordneten Vorgaenge.
  val used = mutableMapOf<String, MutableMap<LocalDate, Int>>()
  val starts = mutableMapOf<String, LocalDate>()
  val durations = mutableMapOf<String, Int>()
  val ends = mutableMapOf<String, LocalDate>()

  // ERST die eingefrorene Arbeit eintragen, und zwar vor allem anderen: sie belegt Kapazitaet,
  // die fuer den Rest nicht mehr zur Verfuegung steht. Wuerde sie in der normalen Reihenfolge
  // abgearbeitet, koennte ein beweglicher Vorgang sich vorher auf denselben Tag legen.
  tasks.filter { it.frozen }.forEach { task ->
    val liegtAuf = nextWorkingDay(task.fixedStart ?: projectStart, isWorkingDay)
    val days = workingDays(liegtAuf, durationAt(task, liegtAuf), isWorkingDay)
    task.pools.forEach { pool ->
      val belegung = used.getOrPut(pool) { mutableMapOf() }
      days.forEach { belegung[it] = (belegung[it] ?: 0) + task.loadPercent }
    }
    starts[task.id] = days.first()
    durations[task.id] = days.size
    ends[task.id] = nextWorkingDay(days.last().plusDays(1), isWorkingDay)
  }

  for (id in order) {
    val task = byId.getValue(id)
    if (task.frozen) {
      continue
    }

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
      days = workingDays(start, durationAt(task, start), isWorkingDay)
    } else {
      days = findEarliestWindow(earliest, task, durationAt, used, isWorkingDay, capacityOf)
    }

    task.pools.forEach { pool ->
      val belegung = used.getOrPut(pool) { mutableMapOf() }
      days.forEach { belegung[it] = (belegung[it] ?: 0) + task.loadPercent }
    }
    starts[id] = days.first()
    durations[id] = days.size
    ends[id] = nextWorkingDay(days.last().plusDays(1), isWorkingDay)
  }

  // Fristen: gemeldet, nicht erzwungen. Geprueft wird das ENDE, denn eine Frist ist ein Endtermin.
  tasks.forEach { task ->
    val frist = task.deadline ?: return@forEach
    val ende = ends[task.id] ?: return@forEach
    // ends[] ist der erste Arbeitstag NACH dem Vorgang; der letzte Arbeitstag liegt davor.
    val letzterTag = generateSequence(ende.minusDays(1)) { it.minusDays(1) }
      .first { isWorkingDay(it) || it < projectStart }
    if (letzterTag.isAfter(frist)) {
      val fehlend = workingDays(nextWorkingDay(frist.plusDays(1), isWorkingDay),
        1, isWorkingDay).let {
        var tage = 0
        var tag = nextWorkingDay(frist.plusDays(1), isWorkingDay)
        while (!tag.isAfter(letzterTag) && tage < 100_000) {
          if (isWorkingDay(tag)) tage++
          tag = tag.plusDays(1)
        }
        tage
      }
      conflicts.add(LevelConflict.DeadlineMissed(task.id, frist, letzterTag, fehlend))
    }
  }

  // Ueberlast kann nach dem Verteilen nur noch dort stehen, wo feste Termine sie erzwungen haben.
  used.toSortedMap().forEach { (pool, belegung) ->
    belegung.filterValues { it > capacityOf(pool) }.toSortedMap().forEach { (day, percent) ->
      val onThatDay = tasks.filter { t ->
        if (!t.pools.contains(pool)) return@filter false
        val s = starts[t.id] ?: return@filter false
        workingDays(s, durations[t.id] ?: t.durationDays, isWorkingDay).contains(day)
      }.map { it.id }
      conflicts.add(LevelConflict.Overload(day, percent, onThatDay, pool))
    }
  }

  return LevelResult(starts, conflicts, durations)
}

/** Die Kapazitaetstoepfe, die dieser Vorgang belegt. Ohne Zuordnung der gemeinsame Topf "". */
internal val LevelTask.pools: List<String>
  get() = if (resourceIds.isEmpty()) listOf("") else resourceIds

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

/**
 * Der naechste Arbeitstag ab [from], einschliesslich.
 *
 * MIT SCHRANKE, und die ist kein Zierrat: ein Kalender ohne einen einzigen Arbeitstag -- durch
 * einen Fehler in den Wochenendeinstellungen oder eine kaputte Feiertagsliste -- laesst diese
 * Schleife sonst ewig laufen. Am Rechner passiert: der Testlaeufer wurde vom Betriebssystem
 * abgeraeumt, ohne eine einzige Meldung. Eine Endlosschleife ist der teuerste Fehlerausgang, weil
 * man ihr nichts ansieht; lieber ein sichtbar falsches Datum als ein haengendes Programm.
 */
private fun nextWorkingDay(from: LocalDate, isWorkingDay: (LocalDate) -> Boolean): LocalDate {
  var d = from
  var schutz = 0
  while (!isWorkingDay(d)) {
    if (schutz++ > MAX_SEARCH_DAYS) {
      return from
    }
    d = d.plusDays(1)
  }
  return d
}

/** Die [count] Arbeitstage ab [start] einschliesslich. */
private fun workingDays(
  start: LocalDate, count: Int, isWorkingDay: (LocalDate) -> Boolean): List<LocalDate> {
  val days = mutableListOf<LocalDate>()
  var d = nextWorkingDay(start, isWorkingDay)
  var schutz = 0
  while (days.size < maxOf(count, 1)) {
    // Dieselbe Schranke wie oben, aus demselben Grund. Ohne Arbeitstage im Kalender wuerde diese
    // Schleife nie fertig -- und zwar lautlos.
    if (schutz++ > MAX_SEARCH_DAYS) {
      return if (days.isEmpty()) listOf(start) else days
    }
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
  task: LevelTask,
  durationAt: (LevelTask, LocalDate) -> Int,
  used: Map<String, MutableMap<LocalDate, Int>>,
  isWorkingDay: (LocalDate) -> Boolean,
  capacityOf: (String) -> Int
): List<LocalDate> {
  val loadPercent = task.loadPercent
  var candidate = nextWorkingDay(earliest, isWorkingDay)
  var schutz = 0
  while (true) {
    // ENDLOSSCHLEIFE VERHINDERN. AM RECHNER GEMESSEN: bei einem Auslastungsgrad von 80 % und
    // einem Vorgang mit 100 % Last war die Bedingung "passt hier" an JEDEM Tag falsch -- auch an
    // voellig leeren. Die Suche lief unbegrenzt weiter, die Verteilung kam nie zurueck, und am
    // Bildschirm sah es aus, als tue der Menuepunkt nichts.
    //
    // Die Schranke ist die zweite Sicherung; die erste ist die Grenze unten, die einen Vorgang
    // immer allein passen laesst. Beide zusammen, weil eine Endlosschleife der teuerste
    // Fehlerausgang ist: kein Dialog, keine Meldung, nur ein Programm, das haengt.
    if (schutz++ > MAX_SEARCH_DAYS) {
      return workingDays(nextWorkingDay(earliest, isWorkingDay), durationAt(task, earliest),
        isWorkingDay)
    }
    // Die Dauer haengt vom Starttag ab, sobald die Tagesleistung zeitabhaengig ist -- sie muss
    // deshalb FUER JEDEN KANDIDATEN neu gefragt werden, nicht einmal vorab.
    val window = workingDays(candidate, durationAt(task, candidate), isWorkingDay)
    // Ein Tag blockiert, sobald er fuer EINE der beteiligten Personen zu voll ist.
    val blockedAt = window.firstOrNull { day ->
      task.pools.any { pool ->
        // DIE GRENZE IST MINDESTENS DIE EIGENE LAST. Ein Vorgang, der fuer sich genommen mehr
        // verlangt als der Auslastungsgrad hergibt (100 % Last bei 80 % Auslastung), passt sonst
        // NIRGENDS -- und die Suche findet nie ein Fenster. Der Auslastungsgrad begrenzt, wie
        // viel ANDERE Arbeit danebenpasst; er kann einen einzelnen Vorgang nicht verbieten.
        val grenze = maxOf(capacityOf(pool), loadPercent)
        (used[pool]?.get(day) ?: 0) + loadPercent > grenze
      }
    }
    if (blockedAt == null) {
      return window
    }
    // Erst nach dem blockierenden Tag weitersuchen: alles davor faellt aus demselben Grund aus.
    candidate = nextWorkingDay(blockedAt.plusDays(1), isWorkingDay)
  }
}
