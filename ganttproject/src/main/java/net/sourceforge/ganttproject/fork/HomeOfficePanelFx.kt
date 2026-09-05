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
import javafx.collections.FXCollections
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.Separator
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import net.sourceforge.ganttproject.gui.showDatePicker
import net.sourceforge.ganttproject.resource.HumanResource
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * How a PERIOD is chosen, [fork change]: a start and an EXCLUSIVE end are asked for and handed back.
 *
 * A function and not a call, the same seam [DateChoice] is for one date: the real implementation
 * opens the modal calendar the days-off tab uses, a test hands in one that answers straight away.
 * Without it the two period buttons would be the only controls in this file nobody ever measured.
 *
 * THE SECOND DATE IS THE EXCLUSIVE END, the day AFTER the last day at home. That is what
 * `DateInterval.createFromVisibleDates` produces and what [HomeOfficePeriod] stores, so nothing
 * has to be shifted between the picker and the model — and nothing can be shifted by mistake.
 */
typealias PeriodChoice = ((LocalDate, LocalDate) -> Unit) -> Unit

/**
 * The real chooser: the modal calendar of the days-off tab, and the interval it already computes.
 *
 * The conversion goes through [toModelLocalDate] and NOT through `atZone(systemDefault())`, and
 * that is a deliberate difference from [WorkWeekPanelFx]. GanttProject bends the default time zone
 * at startup; `DateInterval.createFromVisibleDates` builds its `end` with `GPTimeUnitStack.DAY
 * .adjustRight`, which runs on the BENT calendar, so the date has to be read back off the same
 * calendar. `LegacyDates.kt` records what reading it off the other one cost the plan once.
 */
/**
 * The real chooser for ONE date, for the pattern's second button.
 *
 * A copy of the one in [WorkWeekPanelFx] rather than a shared value, because that one is private
 * to its file and widening it would make a seam that exists for testability into public API of the
 * package. The conversion differs, and deliberately — see [screenPeriodChoice].
 */
private val screenDateChoiceForHomeOffice: DateChoice = { onChosen ->
  showDatePicker { interval -> onChosen(interval.start.toModelLocalDate()) }
}

private val screenPeriodChoice: PeriodChoice = { onChosen ->
  showDatePicker { interval ->
    val end = interval.end
    if (end != null) {
      onChosen(interval.start.toModelLocalDate(), end.toModelLocalDate())
    }
  }
}

/**
 * [fork change] THE INPUT FOR THE HOME OFFICE — seven boxes and two buttons for the weekly pattern,
 * a list and two buttons for the periods.
 *
 * ═══ WHAT IS BEING ENTERED HERE, AND WHAT IS NOT ═══
 *
 * HOME OFFICE IS NOT AN ABSENCE. The person is working. The tab therefore sits next to the days-off
 * tab and is emphatically not part of it: whoever enters a holiday here would take working days out
 * of the plan that the person actually worked. [HomeOffice] carries the same sentence over the
 * model, and this is the second place it has to survive.
 *
 * NOTHING ENTERED MEANS NO HOME OFFICE. Not „unknown", not the project calendar — none, on every
 * day. Which is why the seven boxes open EMPTY and why this panel has no preselection at all.
 *
 * That is a real difference from [WorkWeekPanelFx], which opens with Monday to Friday ticked, and
 * it is the same reasoning that produced the preselection there: the boxes should show the
 * ordinary case. For a working week the ordinary case is Mon–Fri; for a home office the ordinary
 * case is nobody. Preselecting anything here would suggest a home office that nobody agreed, and —
 * unlike over there, where preselection and meaning genuinely differ — it would be preselecting
 * something that CONTRADICTS what an untouched person means.
 *
 * ═══ NOTHING PRESSED, NOTHING WRITTEN ═══
 *
 * The guarantee of [WorkWeekPanelFx], kept here TWICE OVER because there are two halves: [save]
 * writes the pattern only when a pattern button has been pressed and the periods only when a period
 * button has been pressed. Whoever opens this tab, looks at it and closes the dialog with Ok has
 * still entered nothing — and not even the two column definitions come into a project that has
 * none, because [setHomeOfficeWeek] is what would create them and it is not reached.
 *
 * A press alone does not write either. It only fills [editedWeek] / [editedPeriods], which nobody
 * outside this object can see; the write happens when the person dialog closes with Ok and calls
 * [save]. So Cancel cancels.
 *
 * ═══ THE END OF A PERIOD IS EXCLUSIVE IN THE MODEL AND INCLUSIVE ON SCREEN ═══
 *
 * `HomeOfficePeriod(1 June, 4 June)` is the 1st to the 3rd. The list shows „2026-06-01 to
 * 2026-06-03" — the LAST DAY — because that is what the days-off list one tab over shows
 * (`DateInterval.visibleEnd`) and because a person reading „to 4 June" would count four days.
 *
 * The picker hands back the exclusive end already, so nothing is converted on the way in either.
 * The exclusive form appears in exactly one place a person can see it, the raw text column, and
 * that is the place where it has to agree with `<vacation>`.
 *
 * ═══ AN UNREADABLE COLUMN LOCKS ITS OWN BUTTONS ═══
 *
 * Taken over from [WorkWeekPanelFx], for the reason in `2026-09-04-arbeitswoche-oberflaeche.md`:
 * [HomeOfficeWeek.parse] DROPS a section it cannot read whole, so a button press built on the
 * remainder would silently delete a line somebody typed by hand.
 *
 * WHAT IS NEW HERE IS THAT THE LOCK IS HALF-WIDE. A mistyped date among the periods locks the two
 * period buttons and leaves the seven weekday boxes working, and the other way round. That is the
 * benefit the two separate properties were chosen for, and it is measured rather than asserted —
 * see the comment over `HomeOfficeStorage.kt`.
 *
 * @param today the day „from now on" means. A parameter so that a test does not depend on the
 * calendar of the machine it runs on.
 * @param chooseDate how the pattern's second button asks for a date.
 * @param choosePeriod how the period button asks for a range; the second date is EXCLUSIVE.
 */
class HomeOfficePanelFx @JvmOverloads constructor(
  private val person: HumanResource,
  private val resourceProperties: CustomPropertyManager,
  private val today: LocalDate = LocalDate.now(),
  private val chooseDate: DateChoice = screenDateChoiceForHomeOffice,
  private val choosePeriod: PeriodChoice = screenPeriodChoice
) {
  /** What the person carries now, WITH whatever could not be read. The errors are not dropped. */
  private val stored: HomeOfficeParseResult = person.homeOffice(resourceProperties)

  /**
   * What a press of a PATTERN button has built, or `null` while nobody has pressed one.
   *
   * `null` is the guarantee, not merely an absent value — [save] returns on it.
   */
  private var editedWeek: HomeOfficeWeek? = null

  /** What a press of a PERIOD button has built, or `null` while nobody has pressed one. */
  private var editedPeriods: HomeOfficePeriods? = null

  /**
   * Monday first, Sunday last — index + [HomeOfficeWeek.FIRST_DAY] is the ISO number.
   *
   * `by lazy` throughout, following [WorkWeekPanelFx] and [MainPropertiesPanel]: the person dialog
   * builds its panels in its own constructor, off the JavaFX thread, and hands the nodes out inside
   * the tab callback, which runs on it.
   */
  val dayBoxes: List<CheckBox> by lazy {
    (HomeOfficeWeek.FIRST_DAY..HomeOfficeWeek.LAST_DAY).map { number ->
      CheckBox(forkText("fork.homeoffice.ui.day.$number")).apply {
        isSelected = DayOfWeek.of(number) in daysShown()
      }
    }
  }

  /** What the last press did, or why it was refused. Never a dialog: the tab stays where it is. */
  val messageLabel: Label by lazy { Label(openingMessage()).apply { prose() } }

  /**
   * Every section of the pattern, in the format of the text column.
   *
   * The boxes can only show the section in force TODAY — that is what seven ticks can express — so
   * a section starting in the future would otherwise be invisible, and somebody would enter a
   * pattern over the top of it and wonder why it does not hold.
   */
  val weekSummaryLabel: Label by lazy { Label(weekSummaryText()).apply { prose() } }

  val applyFromTodayButton: Button by lazy {
    Button(forkText("fork.homeoffice.ui.applyNow")).apply {
      setOnAction { applyWeekFrom(today) }
      isDisable = stored.weekErrors.isNotEmpty()
    }
  }

  val applyFromDateButton: Button by lazy {
    Button(forkText("fork.homeoffice.ui.applyFrom")).apply {
      setOnAction { chooseDate { chosen -> applyWeekFrom(chosen) } }
      isDisable = stored.weekErrors.isNotEmpty()
    }
  }

  /**
   * The periods, one per line, shown with their LAST DAY and not with the exclusive end.
   *
   * The list is the display of the periods; there is no raw-text summary beside it, deliberately,
   * so that the exclusive form never reaches a reader who has not been told about it.
   */
  val periodList: ListView<HomeOfficePeriod> by lazy {
    ListView(FXCollections.observableArrayList(basePeriods().periods)).apply {
      prefHeight = PERIOD_LIST_HEIGHT
      maxWidth = PROSE_WIDTH
      setCellFactory {
        object : ListCell<HomeOfficePeriod>() {
          override fun updateItem(item: HomeOfficePeriod?, empty: Boolean) {
            super.updateItem(item, empty)
            text = if (empty || item == null) null else describe(item)
          }
        }
      }
      selectionModel.selectedItemProperty().addListener { _, _, _ -> refreshRemoveButton() }
    }
  }

  val addPeriodButton: Button by lazy {
    Button(forkText("fork.homeoffice.ui.addPeriod")).apply {
      setOnAction { choosePeriod { start, endExclusive -> addPeriod(start, endExclusive) } }
      isDisable = stored.periodErrors.isNotEmpty()
    }
  }

  val removePeriodButton: Button by lazy {
    Button(forkText("fork.homeoffice.ui.removePeriod")).apply {
      setOnAction { removeSelectedPeriod() }
      // Locked while nothing is selected AND while the column cannot be read — the second lock is
      // the one that matters: removing from a list built on a parsed remainder would drop the line
      // that could not be parsed along with the one that was picked.
      isDisable = true
    }
  }

  val node: Node by lazy { VBox(8.0).apply {
    // The same sheets the days-off tab pulls in, so that the buttons of this tab are not styled
    // differently from the buttons one tab over. Nothing here touches -fx-background — modena
    // resolves .label text colour as a ladder over it, and overriding it yields white on white.
    stylesheets.add("/biz/ganttproject/task/TaskPropertiesDialog.css")
    stylesheets.add("/biz/ganttproject/app/buttons.css")
    styleClass.add("tab-contents")
    children.add(Label(forkText("fork.homeoffice.ui.intro")).apply { prose() })
    children.add(Label(forkText("fork.homeoffice.ui.weekSection")).apply { prose() })
    children.add(VBox(4.0).apply { children.addAll(dayBoxes) })
    children.add(HBox(8.0).apply { children.addAll(applyFromTodayButton, applyFromDateButton) })
    children.add(weekSummaryLabel)
    children.add(Separator())
    children.add(Label(forkText("fork.homeoffice.ui.periodSection")).apply { prose() })
    children.add(periodList)
    children.add(HBox(8.0).apply { children.addAll(addPeriodButton, removePeriodButton) })
    children.add(messageLabel)
  } }

  /**
   * Which weekdays the boxes show when the tab opens.
   *
   * The section in force TODAY where there is one, NOTHING where there is none — there is no
   * preselection in this panel, see the class comment. A section that IS there but names no day
   * shows as seven empty boxes too, and that is right: it means the arrangement was ended.
   */
  private fun daysShown(): Set<DayOfWeek> =
    stored.homeOffice.week.sectionOn(today)?.days ?: emptySet()

  private fun tickedDays(): Set<DayOfWeek> = dayBoxes.mapIndexedNotNull { index, box ->
    if (box.isSelected) DayOfWeek.of(index + HomeOfficeWeek.FIRST_DAY) else null
  }.toSet()

  /** What a further pattern press builds on: the edit in progress, or what is stored. */
  private fun baseWeek(): HomeOfficeWeek = editedWeek ?: stored.homeOffice.week

  /** What a further period press builds on: the edit in progress, or what is stored. */
  private fun basePeriods(): HomeOfficePeriods = editedPeriods ?: stored.homeOffice.periods

  /**
   * One press of a pattern button. Internal rather than private so that a test can reach the
   * refusals without going through a disabled control.
   */
  internal fun applyWeekFrom(from: LocalDate) {
    if (stored.weekErrors.isNotEmpty()) {
      // The buttons are disabled in this state; this is the second lock, for the path that does not
      // go through a button. Overwriting now would drop the section parse could not read.
      messageLabel.text = unreadableWeekMessage()
      return
    }
    val days = tickedDays()
    if (days.isEmpty() && baseWeek().isEmpty) {
      // A section with no day ENDS an arrangement — see HomeOfficeChange. With nothing entered
      // there is nothing to end, and writing one would create two column definitions and a line in
      // the file that says exactly what the absence of the line already said.
      messageLabel.text = forkText("fork.homeoffice.ui.error.nothingToEnd")
      return
    }
    // The constructor sorts and resolves a repeated date, the later entry winning — so appending
    // both adds a section and replaces one for the same day, and every other section stands.
    editedWeek = HomeOfficeWeek(baseWeek().changes + HomeOfficeChange(from, days))
    messageLabel.text =
      if (days.isEmpty()) forkText("fork.homeoffice.ui.ended", from.toString())
      else forkText("fork.homeoffice.ui.applied", from.toString())
    weekSummaryLabel.text = weekSummaryText()
  }

  /**
   * One press of „add period". [endExclusive] is the day AFTER the last day at home.
   *
   * Internal for the same reason as [applyWeekFrom].
   */
  internal fun addPeriod(start: LocalDate, endExclusive: LocalDate) {
    if (stored.periodErrors.isNotEmpty()) {
      messageLabel.text = unreadablePeriodsMessage()
      return
    }
    if (!endExclusive.isAfter(start)) {
      // Zero days or fewer. The picker should not produce it — it always adds a day to the last
      // one selected — but a period that covers no day would sit in the list looking like an entry
      // and mean nothing, so it is refused where it can still be said out loud.
      messageLabel.text = forkText("fork.homeoffice.ui.error.emptyPeriod")
      return
    }
    val period = HomeOfficePeriod(start, endExclusive)
    editedPeriods = HomeOfficePeriods(basePeriods().periods + period)
    messageLabel.text = forkText("fork.homeoffice.ui.periodAdded", describe(period))
    refreshPeriodList()
  }

  /** One press of „remove". Does nothing when nothing is selected; the button is locked then. */
  internal fun removeSelectedPeriod() {
    if (stored.periodErrors.isNotEmpty()) {
      messageLabel.text = unreadablePeriodsMessage()
      return
    }
    val selected = periodList.selectionModel.selectedItem ?: return
    editedPeriods = HomeOfficePeriods(basePeriods().periods.filter { it != selected })
    messageLabel.text = forkText("fork.homeoffice.ui.periodRemoved", describe(selected))
    refreshPeriodList()
  }

  /**
   * One period as a person reads it: the first and the LAST day, both inclusive.
   *
   * A one-day period is named once rather than „from x to x", which reads like a mistake.
   */
  private fun describe(period: HomeOfficePeriod): String =
    if (period.days <= 1) forkText("fork.homeoffice.ui.periodItemOneDay", period.start.toString())
    else forkText("fork.homeoffice.ui.periodItem", period.start.toString(), period.lastDay.toString())

  private fun refreshPeriodList() {
    periodList.items.setAll(basePeriods().periods)
    refreshRemoveButton()
  }

  private fun refreshRemoveButton() {
    removePeriodButton.isDisable =
      stored.periodErrors.isNotEmpty() || periodList.selectionModel.selectedItem == null
  }

  /**
   * A running text of this tab: wraps, and at a WIDTH OF ITS OWN rather than at the dialog's.
   *
   * The reason is measured and recorded in [WorkWeekPanelFx]: the person dialog comes up about
   * 1080 px wide on a 1024 px screen, so `isWrapText` alone wraps at a width nobody can see. The
   * cap is a maximum, so a narrower dialog is unaffected.
   */
  private fun Label.prose() {
    isWrapText = true
    maxWidth = PROSE_WIDTH
  }

  /** What the message line says before anybody has pressed anything. */
  private fun openingMessage(): String = when {
    stored.weekErrors.isNotEmpty() && stored.periodErrors.isNotEmpty() ->
      unreadableWeekMessage() + " " + unreadablePeriodsMessage()
    stored.weekErrors.isNotEmpty() -> unreadableWeekMessage()
    stored.periodErrors.isNotEmpty() -> unreadablePeriodsMessage()
    else -> ""
  }

  private fun unreadableWeekMessage(): String =
    forkText("fork.homeoffice.ui.error.weekUnreadable", stored.weekErrors.joinToString(" "))

  private fun unreadablePeriodsMessage(): String =
    forkText("fork.homeoffice.ui.error.periodsUnreadable", stored.periodErrors.joinToString(" "))

  private fun weekSummaryText(): String = baseWeek().let { shown ->
    if (shown.isEmpty) forkText("fork.homeoffice.ui.weekNone")
    else forkText("fork.homeoffice.ui.weekCurrent", shown.toString())
  }

  /**
   * What the Ok button of the person dialog calls.
   *
   * NOTHING PRESSED, NOTHING WRITTEN — and separately per half, so that entering a period does not
   * also write an empty pattern over a hand-typed column, and the other way round.
   */
  fun save() {
    editedWeek?.let { person.setHomeOfficeWeek(resourceProperties, it) }
    editedPeriods?.let { person.setHomeOfficePeriods(resourceProperties, it) }
  }

  companion object {
    /** How wide a line of explanation in this tab may become. See [prose]. */
    private const val PROSE_WIDTH = 640.0

    /** Room for about five periods before the list scrolls. */
    private const val PERIOD_LIST_HEIGHT = 120.0
  }
}
