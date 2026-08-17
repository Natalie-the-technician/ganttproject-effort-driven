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
import java.time.temporal.ChronoUnit

/**
 * Serienvorgaenge: "das passiert jeden Monat wieder".
 *
 * WOZU: Der Plan enthaelt Arbeit, die sich wiederholt -- Umsatzsteuervoranmeldung,
 * Jahresabschluss, wiederkehrende Berichte. Bisher steht so etwas entweder gar nicht im Plan
 * (dann fehlt die Kapazitaet dafuer, und die Verteilung rechnet zu optimistisch) oder es wurde von
 * Hand vervielfaeltigt (dann stimmt es beim ersten Umplanen nicht mehr).
 *
 * Reine Rechnung, keine GanttProject-Typen -- pruefbar ohne laufendes Programm.
 *
 * FORMAT, so wie es in der Spalte "Wiederholung" steht:
 *
 *     monatlich; bis 2027-12-31
 *     woechentlich; alle 2; Anzahl 10
 *     jaehrlich; bis 2030-01-01
 *
 * Erlaubt sind `taeglich`, `woechentlich`, `monatlich`, `jaehrlich` (auch `daily`, `weekly`,
 * `monthly`, `yearly`), dazu `alle N` und genau eine Begrenzung: `bis JJJJ-MM-TT` oder `Anzahl N`.
 *
 * ZWEI ENTSCHEIDUNGEN, die man auch anders treffen koennte -- deshalb stehen sie hier:
 *
 * 1. **Eine Begrenzung ist Pflicht.** Eine Serie ohne Ende waere in einem Plan mit Enddatum eine
 *    stille Falle: irgendeine Zahl muesste erfunden werden, und die stuende nirgends. Fehlt die
 *    Begrenzung, wird der Eintrag abgelehnt und gemeldet.
 * 2. **Faellt ein Termin auf einen freien Tag, rueckt er VOR, nicht zurueck.** Zurueck koennte
 *    hinter den vorigen Termin derselben Serie rutschen oder vor den Anfang; vor kann das nicht.
 *    Die Reihenfolge der Serie bleibt damit in jedem Fall erhalten.
 */
enum class RecurrenceUnit { TAG, WOCHE, MONAT, JAHR }

data class RecurrenceRule(
  val unit: RecurrenceUnit,
  /** Abstand in Einheiten. `alle 2` bei WOCHE heisst: jede zweite Woche. */
  val interval: Int,
  /** Letzter Tag, an dem ein Termin noch liegen darf. Genau eines von beiden ist gesetzt. */
  val until: LocalDate? = null,
  /** Anzahl der Termine EINSCHLIESSLICH des ersten. */
  val count: Int? = null
) {
  override fun toString(): String {
    val einheit = when (unit) {
      RecurrenceUnit.TAG -> "taeglich"
      RecurrenceUnit.WOCHE -> "woechentlich"
      RecurrenceUnit.MONAT -> "monatlich"
      RecurrenceUnit.JAHR -> "jaehrlich"
    }
    val abstand = if (interval > 1) "; alle $interval" else ""
    val grenze = until?.let { "; bis $it" } ?: "; Anzahl $count"
    return "$einheit$abstand$grenze"
  }

  companion object {
    /** Mehr Termine erzeugt keine Serie. Darueber ist es ein Vertipper in der Begrenzung. */
    const val MAX_OCCURRENCES = 500

    fun parse(text: String?): RecurrenceParseResult {
      val teile = (text ?: "").split(';', ',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
      if (teile.isEmpty()) {
        return RecurrenceParseResult(null, emptyList())
      }
      var unit: RecurrenceUnit? = null
      var interval = 1
      var until: LocalDate? = null
      var count: Int? = null
      val errors = mutableListOf<String>()

      teile.forEach { teil ->
        val klein = teil.lowercase().replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
        when {
          klein == "taeglich" || klein == "daily" -> unit = RecurrenceUnit.TAG
          klein == "woechentlich" || klein == "weekly" -> unit = RecurrenceUnit.WOCHE
          klein == "monatlich" || klein == "monthly" -> unit = RecurrenceUnit.MONAT
          klein == "jaehrlich" || klein == "yearly" -> unit = RecurrenceUnit.JAHR
          klein.startsWith("alle ") || klein.startsWith("every ") -> {
            val zahl = klein.substringAfter(' ').trim().toIntOrNull()
            if (zahl == null || zahl < 1) {
              errors.add(forkText("fork.recurrence.error.interval", teil))
            } else {
              interval = zahl
            }
          }
          klein.startsWith("bis ") || klein.startsWith("until ") -> {
            val datum = teil.substringAfter(' ').trim()
            until = try {
              LocalDate.parse(datum)
            } catch (e: DateTimeParseException) {
              errors.add(forkText("fork.recurrence.error.date", datum))
              null
            }
          }
          klein.startsWith("anzahl ") || klein.startsWith("count ") -> {
            val zahl = klein.substringAfter(' ').trim().toIntOrNull()
            if (zahl == null || zahl < 1) {
              errors.add(forkText("fork.recurrence.error.count", teil))
            } else {
              count = zahl
            }
          }
          else -> errors.add(forkText("fork.recurrence.error.unknown", teil))
        }
      }

      val einheit = unit
      if (einheit == null) {
        errors.add(forkText("fork.recurrence.error.noUnit"))
        return RecurrenceParseResult(null, errors)
      }
      if (until == null && count == null) {
        errors.add(forkText("fork.recurrence.error.noLimit"))
        return RecurrenceParseResult(null, errors)
      }
      if (until != null && count != null) {
        errors.add(forkText("fork.recurrence.error.twoLimits"))
        return RecurrenceParseResult(null, errors)
      }
      if (errors.isNotEmpty()) {
        return RecurrenceParseResult(null, errors)
      }
      return RecurrenceParseResult(RecurrenceRule(einheit, interval, until, count), emptyList())
    }
  }
}

/**
 * Ergebnis des Lesens.
 *
 * `rule == null` heisst: unbrauchbar. Anders als beim Stundenplan gibt es hier keinen
 * "halb brauchbaren" Rest -- eine Serie mit unklarer Einheit oder ohne Ende hat keine sinnvolle
 * Teilbedeutung. Ein leeres Feld ist kein Fehler: der Vorgang wiederholt sich einfach nicht.
 */
data class RecurrenceParseResult(val rule: RecurrenceRule?, val errors: List<String>) {
  val hasErrors: Boolean get() = errors.isNotEmpty()
}

/**
 * Die Termine der Serie, beginnend beim [first].
 *
 * Der erste Termin ist IMMER dabei -- er ist der Vorgang, der schon im Plan steht. Die Aufrufer
 * legen deshalb nur die Termine ab dem zweiten an.
 *
 * @param isWorkingDay faellt ein Termin auf einen freien Tag, rueckt er auf den naechsten
 * Arbeitstag VOR. Siehe Entscheidung 2 im Klassenkommentar.
 */
fun occurrences(
  rule: RecurrenceRule,
  first: LocalDate,
  isWorkingDay: (LocalDate) -> Boolean
): List<LocalDate> {
  // Ein SATZ, keine Liste: zwei rohe Termine koennen auf denselben Arbeitstag vorruecken (etwa
  // taeglich ueber ein Wochenende). Wuerden die Doppelten mitgezaehlt, waere die Obergrenze
  // erreicht, bevor sie erreicht ist -- gemessen: "taeglich bis 2099" lieferte 357 Termine und
  // meldete sich nicht als abgeschnitten.
  val result = linkedSetOf<LocalDate>()
  var index = 0
  while (result.size < RecurrenceRule.MAX_OCCURRENCES) {
    val roh = when (rule.unit) {
      RecurrenceUnit.TAG -> first.plusDays((index.toLong() * rule.interval))
      RecurrenceUnit.WOCHE -> first.plusWeeks((index.toLong() * rule.interval))
      RecurrenceUnit.MONAT -> first.plusMonths((index.toLong() * rule.interval))
      RecurrenceUnit.JAHR -> first.plusYears((index.toLong() * rule.interval))
    }
    // Die Begrenzung gilt fuer den ROHEN Termin, nicht fuer den verschobenen: sonst haenge das
    // Ende einer Serie davon ab, ob der letzte Termin zufaellig auf einen Feiertag faellt.
    if (rule.until != null && roh.isAfter(rule.until)) {
      break
    }
    result.add(nextWorkingDay(roh, isWorkingDay))
    index++
    if (rule.count != null && result.size >= rule.count) {
      break
    }
  }
  // Zwei Vorgaenge am selben Tag mit demselben Namen waeren keine Serie, sondern Doppelarbeit --
  // der Kalender sagt hier, wie viele Termine wirklich moeglich sind.
  return result.toList()
}

/** Wie viele Termine eine Regel ab [first] ergibt, ohne die Liste zu bauen. */
fun occurrenceCount(
  rule: RecurrenceRule, first: LocalDate, isWorkingDay: (LocalDate) -> Boolean
): Int = occurrences(rule, first, isWorkingDay).size

/** Ob die Serie an der Obergrenze abgeschnitten wurde -- dann stimmt die Begrenzung nicht. */
fun isTruncated(
  rule: RecurrenceRule, first: LocalDate, isWorkingDay: (LocalDate) -> Boolean
): Boolean = occurrences(rule, first, isWorkingDay).size >= RecurrenceRule.MAX_OCCURRENCES

private fun nextWorkingDay(from: LocalDate, isWorkingDay: (LocalDate) -> Boolean): LocalDate {
  var day = from
  var guard = 0
  while (!isWorkingDay(day) && guard++ < 400) {
    day = day.plusDays(1)
  }
  return day
}

/** Nur fuer die Anzeige: der Abstand in Tagen zwischen erstem und letztem Termin. */
internal fun spanInDays(dates: List<LocalDate>): Long =
  if (dates.size < 2) 0 else ChronoUnit.DAYS.between(dates.first(), dates.last())
