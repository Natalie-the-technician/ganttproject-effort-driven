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

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Die Tagesleistung einer Person ueber die Zeit.
 *
 * WOZU: bisher war die Tagesleistung EINE Zahl fuer den ganzen Plan. Geplant wird neben einem
 * Hauptberuf; sobald der reduziert wird, steigt die Stundenzahl. Mit einer einzigen Zahl gibt es
 * dafuer nur zwei Moeglichkeiten, und beide sind falsch: die kleine Zahl behalten (dann rechnet
 * sich die Zukunft zu pessimistisch) oder die grosse eintragen (dann rechnet sich die
 * Vergangenheit zu schoen). Deshalb Abschnitte: **ab einem Datum gilt eine andere Zahl.**
 *
 * Reine Rechnung, keine GanttProject-Typen -- pruefbar ohne laufendes Programm.
 *
 * FORMAT, so wie es in der Spalte "Stundenplan" steht:
 *
 *     2027-04-01: 6; 2027-10-01: 8
 *
 * Vor dem ersten Datum gilt die normale Tagesleistung der Person. Trennzeichen zwischen den
 * Abschnitten ist `;` oder ein Zeilenumbruch, zwischen Datum und Stunden `:` oder `=`.
 *
 * WARUM DAS ISO-DATUM UND NICHT `01.04.2027`: die Angabe wird auch von der Android-App und von
 * Auswertungen gelesen, und `03.04.05` ist in drei Laendern drei verschiedene Tage. Ein Format,
 * das man falsch verstehen kann, ist in einer geteilten Datei eine Fehlerquelle. Die Fehlermeldung
 * nennt das erwartete Format ausdruecklich.
 *
 * FEHLER WERDEN NICHT VERSCHLUCKT. Ein Tippfehler koennte sonst dazu fuehren, dass still mit der
 * alten Zahl weitergerechnet wird -- der Plan saehe plausibel aus und waere falsch. [parse] liefert
 * die Fehler zurueck; die Aufrufer zeigen sie und verweigern die Arbeit.
 */
data class CapacityChange(
  /** Ab diesem Tag gilt [hoursPerDay], einschliesslich. */
  val from: LocalDate,
  val hoursPerDay: Double
)

class CapacitySchedule(
  /** Die Tagesleistung vor dem ersten Abschnitt: die normale Angabe der Person. */
  val base: Double,
  changes: List<CapacityChange> = emptyList()
) {
  /** Nach Datum sortiert. Bei doppeltem Datum gewinnt der spaetere Eintrag der Eingabe. */
  val changes: List<CapacityChange> = changes
    .associateBy { it.from }.values.sortedBy { it.from }

  /** Die Stunden, die an diesem Tag zur Verfuegung stehen. */
  fun hoursOn(day: LocalDate): Double =
    changes.lastOrNull { !it.from.isAfter(day) }?.hoursPerDay ?: base

  /** Ob ueberhaupt etwas zeitabhaengig ist. Ohne Abschnitte rechnet alles wie bisher. */
  val isConstant: Boolean get() = changes.isEmpty()

  override fun toString(): String =
    changes.joinToString("; ") { "${it.from}: ${formatHours(it.hoursPerDay)}" }

  companion object {
    /** Mehr als das kann kein Mensch an einem Tag leisten; darueber ist es ein Tippfehler. */
    const val MAX_HOURS_PER_DAY = 24.0

    /**
     * Obergrenze fuer [daysNeeded]. Ohne sie wuerde ein Abschnitt mit 0 Stunden zu einer
     * Endlosschleife: der Aufwand wird nie abgearbeitet. Die Zahl entspricht rund 40 Jahren
     * Arbeitstagen -- wer daraufstoesst, hat kein Rundungsproblem, sondern eine Luecke im Plan.
     */
    const val MAX_DAYS = 10_000

    fun parse(text: String?, base: Double): CapacityParseResult {
      val entries = (text ?: "").split(';', '\n')
        .map { it.trim() }.filter { it.isNotEmpty() }
      if (entries.isEmpty()) {
        return CapacityParseResult(CapacitySchedule(base), emptyList())
      }
      val changes = mutableListOf<CapacityChange>()
      val errors = mutableListOf<String>()
      entries.forEach { entry ->
        val separator = entry.indexOfFirst { it == ':' || it == '=' }
        if (separator < 0) {
          errors.add(forkText("fork.capacity.error.noSeparator", entry))
          return@forEach
        }
        val dateText = entry.substring(0, separator).trim()
        val hoursText = entry.substring(separator + 1).trim().replace(',', '.')
        val date = try {
          LocalDate.parse(dateText)
        } catch (e: DateTimeParseException) {
          errors.add(forkText("fork.capacity.error.date", dateText))
          return@forEach
        }
        val hours = hoursText.toDoubleOrNull()
        when {
          hours == null -> errors.add(forkText("fork.capacity.error.hours", hoursText))
          hours < 0.0 -> errors.add(forkText("fork.capacity.error.negative", hoursText))
          hours > MAX_HOURS_PER_DAY ->
            errors.add(forkText("fork.capacity.error.tooMany", hoursText, MAX_HOURS_PER_DAY))
          else -> changes.add(CapacityChange(date, hours))
        }
      }
      return CapacityParseResult(CapacitySchedule(base, changes), errors)
    }
  }
}

/**
 * Ergebnis des Lesens: immer ein brauchbarer Plan UND die Fehler.
 *
 * Beides zusammen, damit der Aufrufer die Wahl hat -- eine Spaltenanzeige kann mit dem
 * unvollstaendigen Plan weiterarbeiten, die Verteilung verweigert bei Fehlern die Arbeit. Ein
 * `null` an dieser Stelle haette die Anzeige gezwungen, still auf die alte Zahl zurueckzufallen.
 */
data class CapacityParseResult(val schedule: CapacitySchedule, val errors: List<String>) {
  val hasErrors: Boolean get() = errors.isNotEmpty()
}

/**
 * Wie viele Arbeitstage ein Aufwand braucht, wenn er an [start] beginnt.
 *
 * Das ist der Punkt, an dem die Zeitabschnitte wirken: ein Vorgang, der ueber eine Grenze laeuft,
 * wird VOR der Grenze mit der alten und danach mit der neuen Stundenzahl gerechnet -- nicht
 * durchgehend mit einer der beiden.
 *
 * @return die Anzahl Arbeitstage, mindestens 1, oder null, wenn der Aufwand in [CapacitySchedule
 * .MAX_DAYS] Arbeitstagen nicht zu leisten ist (etwa in einem Abschnitt mit 0 Stunden).
 */
fun daysNeeded(
  effortHours: Double,
  start: LocalDate,
  schedule: CapacitySchedule,
  loadFactor: Double = 1.0,
  isWorkingDay: (LocalDate) -> Boolean
): Int? {
  if (effortHours <= 0.0) {
    return 1
  }
  var remaining = effortHours
  var day = start
  var days = 0
  var guard = 0
  while (guard++ < CapacitySchedule.MAX_DAYS * 2) {
    if (isWorkingDay(day)) {
      days++
      remaining -= schedule.hoursOn(day) * loadFactor
      if (remaining <= 1e-9) {
        return days
      }
      if (days >= CapacitySchedule.MAX_DAYS) {
        return null
      }
    }
    day = day.plusDays(1)
  }
  return null
}

/** Ohne Nachkommastellen, wenn es keine gibt: "8" statt "8.0". */
internal fun formatHours(hours: Double): String =
  if (hours == hours.toLong().toDouble()) hours.toLong().toString() else hours.toString()
