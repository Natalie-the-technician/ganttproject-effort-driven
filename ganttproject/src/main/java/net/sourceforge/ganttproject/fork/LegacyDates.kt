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
import java.util.Date
import java.util.GregorianCalendar

/**
 * Umrechnung zwischen [LocalDate] und den [Date]-Werten des Projektmodells.
 *
 * **`ZoneId.systemDefault()` DARF HIER NICHT VERWENDET WERDEN.** Der Grund ist eine Eigenart des
 * Originals, und sie kostet einen Tag:
 *
 * `GanttLanguage.setLocale` ersetzt beim Start die Standard-Zeitzone der JVM --
 * `TimeZone.getTimeZone("UTC")`, dann `setRawOffset(oertlicher Versatz)`. Die Kennung bleibt dabei
 * "UTC", der Versatz ist aber der oertliche. Die alte `TimeZone`-Schnittstelle liefert daraufhin
 * den verbogenen Versatz, `java.time` dagegen loest die Kennung "UTC" auf und liefert **null**.
 *
 * AM RECHNER GEMESSEN (`ZeitzoneProbeTest`), Sprache Deutsch, Sommerzeit:
 *
 *     TimeZone.getDefault():  Kennung "UTC", Versatz  2 h
 *     ZoneId.systemDefault(): UTC,           Versatz  0 h
 *     Mitternacht des 4.11.2026 laut GanttProject: 1793743200000
 *     Mitternacht des 4.11.2026 laut java.time:    1793750400000   (2 h spaeter)
 *     das GanttProject-Datum als LocalDate gelesen: 2026-11-03     (ein Tag zu FRUEH)
 *
 * WAS DAS ANGERICHTET HAT, bevor diese Datei entstand: die Kapazitaetsverteilung fand die
 * Feiertage des Projekts nicht (`myOneOffEvents` ist nach dem genauen Zeitpunkt geschluesselt, und
 * der lag zwei Stunden daneben). Sie rechnete Feiertage als Arbeitstage und schrieb daraufhin zu
 * frueh liegende Enden. Im Plan verloren dadurch vier Vorgaenge Dauer -- 11 Tage wurden
 * 8, 26 wurden 16. Am Aufwand gemessen fehlten 24 bzw. 80 Stunden Arbeit, die der Plan vorher
 * kannte. Kein Test der reinen Rechnung konnte das finden: sie rechnete richtig, sie bekam falsche
 * Kalenderauskuenfte.
 *
 * WARUM `GregorianCalendar` und nicht ein `ZoneOffset` aus `rawOffset`: GanttProject faltet die
 * Sommerzeit in den festen Versatz hinein. Wer selbst rechnet, muss diese Faltung nachbauen und
 * liegt daneben, sobald sie fehlt (etwa in Tests ohne gesetzte Sprache). `GregorianCalendar` nimmt
 * genau den Weg, den auch `CalendarFactory` nimmt, und ist damit in beiden Faellen richtig.
 *
 * Das Original hat fuer denselben Zweck `DateParser.toJavaDate`/`toLocalDate` und `GanttCalendar
 * .toLocalDate`; sie gehen ueber eine ISO-Zeichenkette und sind damit ebenfalls richtig. Sie
 * benutzen aber ein GETEILTES, nicht abgesichertes `SimpleDateFormat` (`DateParser.java:287`) --
 * bei der Verteilung laufen diese Umrechnungen tausendfach, und `SimpleDateFormat` ist nicht
 * threadsicher. Der Weg hier braucht keinen geteilten Zustand.
 */

/** Mitternacht dieses Tages, in der Zeitrechnung des Projektmodells. */
fun LocalDate.toModelDate(): Date =
  GregorianCalendar(this.year, this.monthValue - 1, this.dayOfMonth).time

/** Der Kalendertag, auf den dieser Zeitpunkt im Projektmodell faellt. */
fun Date.toModelLocalDate(): LocalDate {
  val calendar = GregorianCalendar()
  calendar.time = this
  return LocalDate.of(
    calendar.get(GregorianCalendar.YEAR),
    calendar.get(GregorianCalendar.MONTH) + 1,
    calendar.get(GregorianCalendar.DAY_OF_MONTH))
}
