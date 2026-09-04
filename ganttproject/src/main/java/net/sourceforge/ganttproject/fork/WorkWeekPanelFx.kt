/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.

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
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import net.sourceforge.ganttproject.gui.showDatePicker
import net.sourceforge.ganttproject.resource.HumanResource
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * How the working week is chosen, [fork change]: a date is asked for and handed back.
 *
 * A FUNCTION AND NOT A CALL, so that the panel can be driven without a screen. The real
 * implementation opens [showDatePicker], the modal calendar the days-off tab already uses; a test
 * hands in one that answers straight away. Without this seam every check of the second button
 * would need a window, and the button would be the one thing in this file nobody ever measured.
 */
typealias DateChoice = ((LocalDate) -> Unit) -> Unit

/** The real chooser: the same modal calendar the days-off tab opens, one day out of it. */
private val screenDateChoice: DateChoice = { onChosen ->
  showDatePicker { interval ->
    // showDatePicker hands back an interval; only its first day interests us. The conversion goes
    // through the system zone because that is the zone the picker built the java.util.Date in.
    onChosen(interval.start.toInstant().atZone(ZoneId.systemDefault()).toLocalDate())
  }
}

/**
 * [fork change] THE INPUT FOR THE WORKING WEEK — seven boxes and two buttons.
 *
 * A1 built the model and the storage, A2 the effect. Until now the only way in was the raw text
 * column „Arbeitswoche", where somebody had to type `1,2,5,6; 2026-03-01: 1,2,3` by hand and where
 * a typo is silently a different week. This is the way in that does not require knowing the format.
 *
 * NATALIE'S SHAPE, and it is hers: „Es soll eine Einstellung mit sieben abhakkästen für die tage
 * sein, also pro Tag einer. Darunter dann zwei knöpfe, einer für ab jetzt übernehmen und einer ab
 * seitpunkt in der Zukunft, der öffnet dann eine Datums Auswahl um den Zeitpunkt festzulegen."
 * Sunday is a box like every other, not a special case of the weekend.
 *
 * ═══ THE ONE DISTINCTION THIS FILE EXISTS TO KEEP ═══
 *
 * PRESELECTION IS NOT DEFAULT. The boxes open with Monday to Friday TICKED, because that is what
 * most people work and nobody should have to tick five boxes for the ordinary case. But in the
 * model „nothing entered" means „ask the project calendar" and [WorkWeekSchedule.worksOn] answers
 * `null` for it — deliberately neither `false` nor `true`, so that swapping the program version
 * changes no existing plan.
 *
 * So the preselection must never become an entry by itself. Whoever opens this tab, looks at it and
 * closes the dialog with Ok still has nothing entered. ONLY A PRESS OF A BUTTON WRITES, and even
 * that only reaches the person when the dialog is closed with Ok — [save] is what the Ok button
 * calls, and until then [edited] is a value nobody else can see. Cancel therefore cancels.
 *
 * The guarantee is one line, `val schedule = edited ?: return`, and it is the most-checked line of
 * this package: `WorkWeekPanelTest.wer den reiter nur aufmacht und wieder zugeht traegt nichts ein`
 * looks at the stored column text, at the parsed schedule and at the query itself, because each of
 * the three alone can be right for the wrong reason.
 *
 * ═══ WHAT THE TWO BUTTONS WRITE ═══
 *
 * BOTH WRITE A DATED SECTION and both LEAVE THE OTHER SECTIONS STANDING. „Ab jetzt" is a section
 * beginning today, not an undated one: an undated section applies from the beginning of the plan
 * and would re-plan what has already happened, which is not what „ab jetzt" says. Somebody who
 * really means „works Mon, Tue, Fri, Sat and always has" — the undated case [WorkWeekSchedule]
 * allows — can still type it in the text column, and this panel shows it and does not destroy it.
 * That case has no button because Natalie's two buttons are the two she asked for, and inventing a
 * third would be inventing a requirement.
 *
 * Leaving the other sections standing is what makes the second button worth having: somebody drops
 * to three days from 1 December, and what they worked before that has to stay written down.
 * [WorkWeekSchedule]'s own constructor resolves a repeated date — the later entry wins — so
 * pressing a button twice for the same day replaces rather than duplicates.
 *
 * ═══ THE SECTION WITHOUT A DAY ═══
 *
 * REFUSED HERE, ACCEPTED BY THE MODEL. The split is the one the report of 03.09.2026 proposed, and
 * both halves have a reason. What stands in a file has to stay readable, so [WorkWeekSchedule.parse]
 * goes on accepting `2026-05-01:` — refusing it there would turn a hand-typed line into „no
 * statement" and let the person fall back to the project calendar without anybody seeing it. But
 * nothing has to be able to CREATE one: a section without a day says this person never works again
 * from that date on, and whoever means that means an absence — and an absence has an end.
 *
 * The cost is measured, not assumed: `WorkWeekEmptySectionTest` counts 20 000 calls of
 * `isWorkingDay`, about 54 years paced out day by day, before the guards stop the two walks. The
 * guards have been in the code since `endloser-lauf`; this is the input declining to produce the
 * case at all.
 *
 * ═══ AN UNREADABLE COLUMN LOCKS THE BUTTONS ═══
 *
 * [WorkWeekSchedule.parse] DROPS a section it cannot read whole and reports it. If the panel built
 * its new schedule on that remainder, one press of a button would silently delete a line somebody
 * typed by hand: the panel would look as though it had merely added a section and would in fact
 * have removed one. So while the column text has errors the buttons are disabled and the message
 * names what could not be read. Refusing and saying why is the only honest answer; the fork's other
 * option — carry on with the usable remainder — is right for a DISPLAY and wrong for a WRITE.
 *
 * @param today the day „ab jetzt" means. A parameter so that a test does not depend on the calendar
 * of the machine it runs on.
 * @param chooseDate how the second button asks for a date; see [DateChoice].
 */
class WorkWeekPanelFx @JvmOverloads constructor(
  private val person: HumanResource,
  private val resourceProperties: CustomPropertyManager,
  private val today: LocalDate = LocalDate.now(),
  private val chooseDate: DateChoice = screenDateChoice
) {
  /** What the person carries now, WITH whatever could not be read. The errors are not dropped. */
  private val stored: WorkWeekParseResult = person.workWeek(resourceProperties)

  /**
   * What a button press has built, or `null` while nobody has pressed one.
   *
   * `null` IS THE GUARANTEE, not merely an absent value: [save] returns on it without touching the
   * person, so the preselection in the boxes cannot become an entry on its own.
   */
  private var edited: WorkWeekSchedule? = null

  /**
   * Monday first, Sunday last — index + [WorkWeekSchedule.FIRST_DAY] is the ISO number.
   *
   * EVERY CONTROL IN THIS FILE IS `by lazy`, following [MainPropertiesPanel] one tab over and for
   * the same reason: [GanttDialogPerson] builds its panels in its own constructor, off the JavaFX
   * thread, and hands the nodes out inside the tab callback, which runs on it. Deferring the
   * construction to the first touch puts it on the right thread without the dialog having to know.
   */
  val dayBoxes: List<CheckBox> by lazy {
    (WorkWeekSchedule.FIRST_DAY..WorkWeekSchedule.LAST_DAY).map { number ->
      CheckBox(forkText("fork.workweek.ui.day.$number")).apply {
        isSelected = DayOfWeek.of(number) in daysShown()
      }
    }
  }

  /** What the last press did, or why it was refused. Never a dialog: the tab stays where it is. */
  val messageLabel: Label by lazy {
    Label(if (stored.hasErrors) unreadableMessage() else "").apply { prose() }
  }

  /**
   * Every section, in the format of the text column.
   *
   * The boxes can only show the section in force TODAY — that is what a set of seven ticks can
   * express. A section that starts in the future would otherwise be invisible here, and somebody
   * would enter a week over the top of it and wonder why it does not hold.
   */
  val summaryLabel: Label by lazy { Label(summaryText()).apply { prose() } }

  val applyFromTodayButton: Button by lazy {
    Button(forkText("fork.workweek.ui.applyNow")).apply {
      setOnAction { applyFrom(today) }
      isDisable = stored.hasErrors
    }
  }

  val applyFromDateButton: Button by lazy {
    Button(forkText("fork.workweek.ui.applyFrom")).apply {
      setOnAction { chooseDate { chosen -> applyFrom(chosen) } }
      isDisable = stored.hasErrors
    }
  }

  val node: Node by lazy { VBox(8.0).apply {
    // The same sheets the days-off tab pulls in, and for the same reason: without them the buttons
    // in this tab are styled differently from the buttons one tab over. Nothing here touches
    // -fx-background — modena resolves .label text colour as a ladder over it, and overriding it
    // yields white text on a light ground.
    stylesheets.add("/biz/ganttproject/task/TaskPropertiesDialog.css")
    stylesheets.add("/biz/ganttproject/app/buttons.css")
    styleClass.add("tab-contents")
    children.add(Label(forkText("fork.workweek.ui.intro")).apply { prose() })
    children.add(VBox(4.0).apply { children.addAll(dayBoxes) })
    children.add(HBox(8.0).apply { children.addAll(applyFromTodayButton, applyFromDateButton) })
    children.add(summaryLabel)
    children.add(messageLabel)
  } }

  /**
   * Which days the boxes show when the tab opens.
   *
   * The section in force TODAY where there is one, the preselection where there is none. Note that
   * a section which IS there but names no day shows as seven empty boxes and not as the
   * preselection — `?:` fires on a missing section, not on an empty one. That is deliberate: the
   * boxes have to show what is stored, however unwelcome, or they are not a display of it.
   */
  private fun daysShown(): Set<DayOfWeek> = stored.schedule.sectionOn(today)?.days ?: PRESELECTED

  private fun tickedDays(): Set<DayOfWeek> = dayBoxes.mapIndexedNotNull { index, box ->
    if (box.isSelected) DayOfWeek.of(index + WorkWeekSchedule.FIRST_DAY) else null
  }.toSet()

  /** What a further press builds on: the edit in progress, or the stored value if there is none. */
  private fun base(): WorkWeekSchedule = edited ?: stored.schedule

  /**
   * One press of a button. Internal rather than private so that a test can reach the refusals
   * without going through a disabled control.
   */
  internal fun applyFrom(from: LocalDate) {
    if (stored.hasErrors) {
      // The buttons are disabled in this state; this is the second lock, for the path that does not
      // go through a button. Overwriting now would drop the section parse could not read.
      messageLabel.text = unreadableMessage()
      return
    }
    val days = tickedDays()
    if (days.isEmpty()) {
      messageLabel.text = forkText("fork.workweek.ui.error.noDay")
      return
    }
    // The constructor sorts and resolves a repeated date, the later entry winning — so appending
    // both adds a new section and replaces one for the same day, and every other section stands.
    edited = WorkWeekSchedule(base().changes + WorkWeekChange(from, days))
    messageLabel.text = forkText("fork.workweek.ui.applied", from.toString())
    refreshSummary()
  }

  /**
   * A running text of this tab: wraps, and at a WIDTH OF ITS OWN rather than at the width of the
   * dialog.
   *
   * MEASURED ON SCREEN, 04.09.2026, before this was here: the person dialog comes up about 1080 px
   * wide — the General tab's full-width fields set that — and the container's screen is 1024x768,
   * so the dialog is already wider than the screen and its Ok button sits outside it. `isWrapText`
   * alone therefore wrapped at a width nobody could see, and the first line of the explanation ran
   * off the right edge mid-sentence. A cap makes the text wrap where it is still readable, whatever
   * the dialog does; it is a maximum, so a narrower dialog is unaffected.
   *
   * It does NOT repair the dialog being wider than the screen. That is not this package's and is
   * written up in the report.
   */
  private fun Label.prose() {
    isWrapText = true
    maxWidth = PROSE_WIDTH
  }

  private fun unreadableMessage(): String =
    forkText("fork.workweek.ui.error.unreadable", stored.errors.joinToString(" "))

  private fun summaryText(): String = base().let { shown ->
    if (shown.isEmpty) forkText("fork.workweek.ui.none")
    else forkText("fork.workweek.ui.current", shown.toString())
  }

  private fun refreshSummary() {
    summaryLabel.text = summaryText()
  }

  /**
   * What the Ok button of the person dialog calls.
   *
   * NOTHING PRESSED, NOTHING WRITTEN — the first line, and the point of the whole package. Note
   * what it also avoids: [setWorkWeek] would CREATE the column definition, so an untouched person
   * does not even bring the column into a project that has none.
   */
  fun save() {
    val schedule = edited ?: return
    person.setWorkWeek(resourceProperties, schedule)
  }

  companion object {
    /**
     * Monday to Friday — what a FRESH entry is OFFERED, and emphatically not what nothing entered
     * MEANS. See the class comment; this is the one constant of this file that could be misread as
     * a default value, and it is not one.
     */
    /** How wide a line of explanation in this tab may become. See [prose]. */
    private const val PROSE_WIDTH = 640.0

    val PRESELECTED: Set<DayOfWeek> = setOf(
      DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
  }
}
