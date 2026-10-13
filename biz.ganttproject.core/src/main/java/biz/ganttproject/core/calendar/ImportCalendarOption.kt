/*
Copyright 2003-2026 Dmitry Barashev, BarD Software s.r.o

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
package biz.ganttproject.core.calendar

import biz.ganttproject.app.RootLocalizer
import biz.ganttproject.core.option.ObservableEnumerationOption
import biz.ganttproject.core.option.PropertyPaneBuilder
import java.util.*

open class ImportCalendarOption @JvmOverloads constructor(initialValue: Values = Values.NO) :
  ObservableEnumerationOption<ImportCalendarOption.Values>(
    "impex.importCalendar", initialValue, Arrays.stream<Values?>(
      Values.entries.toTypedArray()
    ).toList()
  ) {
  enum class Values {
    /**
     * NOBODY HAS ANSWERED THE CALENDAR QUESTION YET — and therefore nothing may happen.
     *
     * CHANGED IN THIS FORK. A project with working hours of its own computes differently after an
     * import than it did before, and the old default (NO, "leave existing") made that happen
     * without anybody being asked. An importer that uses this value as its starting value has
     * to refuse to run until the user picks one of the three below; it is never a wirkung of its
     * own, neither "keep the existing calendar" nor "take the imported one".
     *
     * EVERY PLACE THAT SWITCHES OVER THESE VALUES MUST NAME IT. The reason it is listed FIRST is
     * that `Values.entries` order is what a dropdown shows, and the unanswered state belongs at
     * the top. [biz.ganttproject.core.calendar.GPCalendar.importCalendar] implementations guard
     * against it explicitly: `WeekendCalendarImpl` used to be an if-chain without an else, where
     * an unknown value fell through into the merge tail and imported the week day types anyway.
     */
    UNDECIDED,
    NO, REPLACE, MERGE;

    override fun toString(): String {
      return "importCalendar_" + name.lowercase(Locale.getDefault())
    }
  }

  init {
    setSelectedValue(initialValue)
  }

  /**
   * True while the calendar question has no answer, i.e. the import must not run.
   *
   * A METHOD AND NOT A FIELD: the value changes while the wizard is open, and the wizard asks
   * again on every refresh.
   */
  fun isUndecided(): Boolean = getSelectedValue() == Values.UNDECIDED

  /**
   * The label of one dropdown entry.
   *
   * OPEN FOR A SUBCLASS BECAUSE [UNDECIDED] HAS NO KEY IN THE SHARED BUNDLE. The translations of
   * this project live in a separate repository that this fork cannot write to, so a value added
   * here has no text there. A subclass in the `ganttproject` module — which is where the fork's
   * own bundle is reachable — supplies it; see
   * `net.sourceforge.ganttproject.importer.AskingImportCalendarOption`.
   */
  protected open fun labelOf(value: Values): String =
    RootLocalizer.formatText("optionValue.importCalendar_${value.name.lowercase()}.label")

  override fun visitPropertyPaneBuilder(builder: PropertyPaneBuilder) {
    builder.dropdown(this.delegate) {
      value2string = { labelOf(it) }
    }
  }
}