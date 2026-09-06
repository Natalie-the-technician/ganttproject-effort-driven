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

import biz.ganttproject.core.calendar.GPCalendar
import biz.ganttproject.core.calendar.GPCalendarCalc
import biz.ganttproject.customproperty.CustomPropertyClass
import biz.ganttproject.customproperty.CustomPropertyDefinition
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.task.algorithm.findEffortDefinition

/**
 * [fork change] A4 — THE PROJECT SETTING „ALLOW WORK ON HOLIDAYS", and the one rule that reads it.
 *
 * Natalie on 03.09.2026: „Arbeit an Feiertagen zulassen soll es als eine Einstellung geben,
 * normalerweise aus, wenn es jemand an macht werden Feiertage ignoriert."
 *
 * ═══ OFF MEANS: NOTHING CHANGES ═══
 *
 * The default is OFF, and OFF has to be indistinguishable from the program as it was before this
 * file existed — not „nearly", but on the day, in every plan. That is the whole risk of this
 * package: it reaches into the day grid the planning is computed on, and every plan that exists
 * today was computed with the switch off. [HolidayRule.OFF] is therefore not a value with a
 * neutral effect, it is the OLD ARITHMETIC WRITTEN OUT — see [HolidayRule.isWorking], whose
 * `false` branch is literally the expression `workingDayTest` used to contain.
 *
 * ═══ IT IS A PROJECT SETTING, NOT A PROGRAM SETTING ═══
 *
 * Decided on 03.09.2026 and worth keeping: the switch changes COMPUTED DATES. In the program's
 * options the same `.gan` would produce two different plans on two machines, and neither of them
 * would be wrong in any way anybody could point at. So it travels in the file.
 *
 * ═══ WHERE IT IS KEPT IN THE FILE, AND WHY IT IS AN ODD PLACE ═══
 *
 * In `<resources>`, as the `default-value` of a custom property definition that no person carries:
 *
 *     <custom-property-definition id="fork.allow_holiday_work" name="…" type="text"
 *                                 default-value="true"/>
 *
 * This is not where such a thing belongs, and the place was not chosen, it was MEASURED. The
 * report `2026-09-05-a4-projekteinstellung.md` drove fifteen candidate places through an
 * unmodified upstream GanttProject (`fdc825057`), one JVM per file. Eight of them — a new
 * attribute on `<project>`, a child element, an attribute on `<calendars>`, a child next to
 * `<only-show-weekends>`, an `<option>` in the `<view>`, an XML comment, a processing instruction,
 * an attribute in its own namespace — produced ONE single output between them, byte for byte the
 * unchanged baseline, md5 `2f953edd37cf16dc350700f8f19ff867`. A foreign GanttProject cannot tell
 * those eight files apart, and it says nothing while it drops them.
 *
 * The definition above survives instead, byte for byte, and it survives in every shape the switch
 * needs: with `true`, with `false`, with an empty value, with no value, next to A1's `work_week`
 * property, and in a project with no people in it at all (cases `k06` and `m1`–`m7` of that
 * report). `false` surviving as well as `true` is the part that matters and the part that is easy
 * to miss: a place that only carries „on" cannot carry a switch, because „off" would then be
 * indistinguishable from „this file has been through another program".
 *
 * WHAT THE PLACE COSTS is written down rather than smoothed over: the setting shows up in the
 * custom-columns list of the RESOURCE table, where it reads like a property of a person. It is
 * not one. The report proposes a second, honest copy on `<calendars>` that a foreign program
 * throws away, so that its absence next to a surviving mirror would prove the file had been
 * through a foreign program. That is a separate feature — a detector for the format loss of
 * 27.08. — and it is not built here; see the report of this session.
 *
 * THE ID CARRIES A DOT ON PURPOSE. `CustomColumnsManager.createId` hands out `tpc0`, `tpc1`, … and
 * an id that can never match that pattern can never collide with one it invents.
 */

/** [fork change] The project setting, as it is named in the file. */
const val PROJECT_ALLOW_HOLIDAY_WORK = "fork.allow_holiday_work"

/**
 * The definition that carries the setting, created on first write.
 *
 * NOT CREATED WHEN THE SETTING IS ONLY READ. A plan that has never had the switch touched carries
 * nothing at all, and that is what makes „a file without the setting" the ordinary case rather
 * than an error case — see [CustomPropertyManager.allowsHolidayWork].
 */
fun findOrCreateAllowHolidayWork(manager: CustomPropertyManager): CustomPropertyDefinition =
  manager.findEffortDefinition(PROJECT_ALLOW_HOLIDAY_WORK)
    ?: manager.createDefinition(PROJECT_ALLOW_HOLIDAY_WORK, CustomPropertyClass.TEXT.iD,
                                forkText("fork.column.allowHolidayWork"), VALUE_OFF)

/**
 * Does this project allow work on public holidays?
 *
 * `false` FOR EVERYTHING THAT IS NOT THE WORD `true`: no definition, an empty value, a value from
 * some other program, a word nobody recognises. A file that does not carry the setting is not a
 * broken file and must not be reported as one — it is every plan written before today, and „off"
 * is exactly the behaviour it has always had.
 *
 * @receiver the RESOURCE property manager of the project (`IGanttProject.resourceCustomPropertyManager`).
 */
fun CustomPropertyManager.allowsHolidayWork(): Boolean =
  this.findEffortDefinition(PROJECT_ALLOW_HOLIDAY_WORK)
    ?.defaultValueAsString?.trim().equals(VALUE_ON, ignoreCase = true)

/**
 * Writes the setting, creating the definition the first time.
 *
 * BOTH STATES ARE WRITTEN OUT, „off" as the word `false` rather than as an absence. Removing the
 * definition again would be the same file as one that never had it, which is true today and would
 * stop being true the moment anything else is stored beside it.
 */
fun CustomPropertyManager.setAllowHolidayWork(allow: Boolean) {
  findOrCreateAllowHolidayWork(this).defaultValueAsString = if (allow) VALUE_ON else VALUE_OFF
}

private const val VALUE_ON = "true"
private const val VALUE_OFF = "false"

/**
 * [fork change] How a day mask of the project calendar is read once the setting has been applied.
 *
 * ═══ THE SWITCH MOVES HOLIDAYS AND NOTHING ELSE ═══
 *
 * `WeekendCalendarImpl.getDayMask` tells the two apart already, and it does so cleanly — the
 * question A4 was told to check before building anything. Written out, its answer is
 *
 *     if (isWeekend)  { result |= WEEKEND;  if (one-off WORKING_DAY) result |= WORKING }
 *     if (isHoliday)  { result |= HOLIDAY;  return result }          // <- returns without WORKING
 *     if (!isWeekend || myOnlyShowWeekends) result |= WORKING
 *
 * so a day carries `HOLIDAY` when a calendar event says so and `WEEKEND` when the WEEKDAY says so,
 * and the two bits are independent. This class only ever grants a day back on the strength of the
 * `HOLIDAY` bit. A weekend stays a weekend under every setting.
 *
 * ═══ THE BOUNDARY AGAINST A2, WHICH MUST STAY SHARP ═══
 *
 * A2 gave the fork a second way for a task to reach a day the project calendar calls free: if
 * EVERY involved person's working week names that weekday, the task may use it ([taskWorksOn]).
 * That rule is about WEEKDAYS and it does not know holidays exist. Natalie's distinction: a
 * weekend is a habit, a holiday is an announcement.
 *
 * So the two never meet in the middle:
 *
 *  * Switch OFF — a public holiday stays free even when every single person on the task works
 *    that weekday. [isFreeForWeekdayReasons] answers `false` for it, and the extension half of
 *    A2 is never consulted.
 *  * Switch ON — the holiday is simply not there any more. The day is then decided by the
 *    weekday, which is to say by the project calendar and the working weeks, exactly as any other
 *    day of that weekday would be. A Saturday that is also a holiday does NOT become workable
 *    because of the switch; it becomes an ordinary Saturday, and whether the task gets it is A2's
 *    question and not this one.
 *
 * That last line is the whole reason [isFreeForWeekdayReasons] has two branches instead of one.
 *
 * ═══ WHY `weekendsAreWorkingDays` IS CARRIED ═══
 *
 * `onlyShowWeekends` is the project option „weekends are only drawn, not taken out of the
 * scheduling". With it set, `getDayMask` gives every non-holiday day `WORKING`, so a holiday is
 * then the ONLY non-working day there is. A rule that read the `WEEKEND` bit alone would keep such
 * a holiday free with the switch on — the one case in which „ignore the holiday" and „look at the
 * weekend bit" give different answers. It is carried so that this class means what it says:
 * ON answers exactly what the calendar would have answered if the day were not a holiday.
 */
class HolidayRule internal constructor(
  /** Whether the project allows work on public holidays. */
  val allowHolidayWork: Boolean,
  /** `GPCalendarCalc.getOnlyShowWeekends` — weekends are drawn but scheduled through. */
  private val weekendsAreWorkingDays: Boolean
) {
  /**
   * Is a day with this mask a working day of the project?
   *
   * WITH THE SWITCH OFF this is `mask and WORKING != 0` and nothing else — the expression
   * `workingDayTest` contained before A4, character for character. That is deliberate: the guard
   * „off changes nothing" should hold because there is no other arithmetic to go wrong, not
   * because two different formulas happen to agree.
   */
  fun isWorking(mask: Int): Boolean =
    mask and GPCalendar.DayMask.WORKING != 0 ||
      (allowHolidayWork && mask and GPCalendar.DayMask.HOLIDAY != 0 && !fallsOnAFreeWeekend(mask))

  /**
   * Is a day with this mask free FOR WEEKDAY REASONS — that is, may A2's extension half win it
   * back for a task whose people all work that weekday?
   *
   * WITH THE SWITCH OFF: free, and neither the weekday's fault nor a holiday. Since `WORKING` is
   * absent only for a weekend or for a holiday, `no WORKING and no HOLIDAY` implies weekend, and
   * that is the whole test — it is the expression A2 built and it is unchanged.
   *
   * WITH THE SWITCH ON the holiday is not there any more, so the only reason a day can still be
   * free is the weekday: „free" and „free for weekday reasons" become the same statement.
   */
  fun isFreeForWeekdayReasons(mask: Int): Boolean =
    if (allowHolidayWork) {
      !isWorking(mask)
    } else {
      mask and GPCalendar.DayMask.WORKING == 0 && mask and GPCalendar.DayMask.HOLIDAY == 0
    }

  /** A weekend that the project really does take out of the scheduling. */
  private fun fallsOnAFreeWeekend(mask: Int): Boolean =
    mask and GPCalendar.DayMask.WEEKEND != 0 && !weekendsAreWorkingDays

  override fun toString(): String =
    "HolidayRule(allowHolidayWork=$allowHolidayWork, weekendsAreWorkingDays=$weekendsAreWorkingDays)"

  companion object {
    /**
     * The program as it was before A4, and the answer for everything that cannot say otherwise.
     *
     * ONE SHARED OBJECT, so that a caller which keys anything by the identity of what it was given
     * does not open a bucket per call. [of] returns exactly this instance whenever the switch is
     * off, which is every project that has not been told otherwise.
     */
    @JvmField
    val OFF = HolidayRule(allowHolidayWork = false, weekendsAreWorkingDays = false)

    /**
     * The rule of this project.
     *
     * @param resourceProperties the RESOURCE property manager, where the setting lives. `null`
     * means: no way to know, and therefore [OFF] — the same answer this program gave before the
     * setting existed. Callers that have no resource properties to hand (`applyLevellingAsSingleEdit`
     * and `planRecurrences` both allow it) are exactly the callers that also plan on the project
     * calendar alone.
     */
    @JvmStatic
    fun of(calendar: GPCalendar, resourceProperties: CustomPropertyManager?): HolidayRule =
      if (resourceProperties?.allowsHolidayWork() != true) {
        OFF
      } else {
        HolidayRule(
          allowHolidayWork = true,
          weekendsAreWorkingDays = (calendar as? GPCalendarCalc)?.onlyShowWeekends ?: false)
      }
  }
}
