/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
The calendar question of the import wizard, asked every time and with no answer pre-selected.

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
package net.sourceforge.ganttproject.importer

import biz.ganttproject.core.calendar.ImportCalendarOption
import net.sourceforge.ganttproject.fork.forkText

/**
 * [ImportCalendarOption] that starts out unanswered and knows how to label that state.
 *
 * WHY A SUBCLASS AND NOT A PARAMETER. [ImportCalendarOption.Values.UNDECIDED] is new in this fork,
 * and the bundle that holds the labels of the other three values lives in a repository this fork
 * cannot write to — a key added there is simply not found. The fork's own bundle is reachable from
 * this module and not from `biz.ganttproject.core`, so the text is resolved here. [forkText] asks
 * the fork bundle first and falls through to GanttProject's own texts, which is why all four
 * values can go through the same call and only the new one actually comes from the fork.
 *
 * STARTING VALUE IS THE WHOLE POINT. Pre-selecting "leave existing" — what this importer did until
 * now — meant a project with working hours of its own computed differently after an import than
 * before, with nobody asked and nothing said. Natalie decided on 08.10.2026: ask every time, with
 * neither answer pre-selected.
 */
class AskingImportCalendarOption : ImportCalendarOption(Values.UNDECIDED) {
  override fun labelOf(value: Values): String =
    forkText("optionValue.importCalendar_${value.name.lowercase()}.label")
}

/**
 * What the user is told when the import is refused because the calendar question has no answer.
 *
 * Read through a function and not stored in a field: the language can change while the program
 * runs, and a captured string would keep the one that was current at startup.
 */
fun unansweredCalendarQuestionText(): String = forkText("fork.import.calendar.unanswered")
