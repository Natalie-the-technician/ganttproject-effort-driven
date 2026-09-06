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

import biz.ganttproject.core.calendar.GPCalendar.DayMask
import biz.ganttproject.customproperty.CustomColumnsManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A4 — the setting itself: what it reads out of a file, what it writes into one, and the day
 * arithmetic that hangs off it.
 *
 * NO PROJECT, NO CALENDAR, NO PROGRAM. Everything here is either a property manager or an integer
 * of mask bits, so a failure names the arithmetic and not the wiring around it. What the switch
 * DOES to a plan is measured in `HolidayWorkEffectTest`, and what survives a file in
 * `HolidayWorkRoundTripTest`.
 *
 * THE SIX MASKS ARE EVERY MASK `WeekendCalendarImpl.getDayMask` CAN PRODUCE, and they are listed
 * here once so that every table below is complete rather than illustrative:
 *
 *   WORKING                 an ordinary weekday
 *   WEEKEND                 Saturday or Sunday
 *   WEEKEND|WORKING         a weekend day with a one-off working-day event, or `onlyShowWeekends`
 *   HOLIDAY                 a public holiday on a weekday
 *   WEEKEND|HOLIDAY         a public holiday that falls on a Saturday
 *   0                       cannot occur — kept in the table because a rule that answers „working"
 *                           to it would be reading the bits the wrong way round
 *
 * `WEEKEND|WORKING|HOLIDAY` is NOT in the list and cannot arise: the `WORKING` bit is only added
 * to a weekend for a one-off `WORKING_DAY` event, and such an event makes `isPublicHoliDay` answer
 * `false`, so the `HOLIDAY` bit is then absent. Read out of `WeekendCalendarImpl`, not assumed.
 */
class HolidayWorkTest {

  private val weekday = DayMask.WORKING
  private val weekend = DayMask.WEEKEND
  private val weekendWorked = DayMask.WEEKEND or DayMask.WORKING
  private val holiday = DayMask.HOLIDAY
  private val holidayOnSaturday = DayMask.WEEKEND or DayMask.HOLIDAY
  private val nothing = 0

  private val on = HolidayRule(allowHolidayWork = true, weekendsAreWorkingDays = false)

  // ---- DIE EINSTELLUNG IM MODELL ----------------------------------------------------------------

  @Test
  fun `ohne eintrag ist der schalter aus, und das ist kein fehlerfall`() {
    val manager = CustomColumnsManager()
    assertFalse(manager.allowsHolidayWork(),
      "ein Plan, in dem die Einstellung nie angefasst wurde, traegt sie nicht -- und das ist " +
        "jeder Plan, der vor A4 geschrieben wurde")
    assertNull(manager.getCustomPropertyDefinition(PROJECT_ALLOW_HOLIDAY_WORK),
      "Lesen darf die Merkmalsdefinition nicht anlegen: sonst haette jede geoeffnete Datei sie " +
        "nach dem naechsten Speichern, ob jemand die Einstellung benutzt hat oder nicht")
  }

  @Test
  fun `anschalten und wieder ausschalten`() {
    val manager = CustomColumnsManager()
    manager.setAllowHolidayWork(true)
    assertTrue(manager.allowsHolidayWork())
    manager.setAllowHolidayWork(false)
    assertFalse(manager.allowsHolidayWork())
    assertEquals("false",
      manager.getCustomPropertyDefinition(PROJECT_ALLOW_HOLIDAY_WORK)?.defaultValueAsString,
      "AUS wird als das Wort `false` geschrieben und nicht als Abwesenheit")
  }

  @Test
  fun `zweimal anschalten legt keine zweite definition an`() {
    val manager = CustomColumnsManager()
    manager.setAllowHolidayWork(true)
    manager.setAllowHolidayWork(true)
    assertEquals(1, manager.definitions.count { it.id == PROJECT_ALLOW_HOLIDAY_WORK })
  }

  /**
   * Everything that is not the word `true` is OFF. The list is what a file can actually contain:
   * the other state, an emptied value, a value some other program left behind, and the value a
   * person typed into the custom-columns dialog of a foreign GanttProject.
   */
  @Test
  fun `alles was nicht true heisst ist aus`() {
    listOf("false", "", "   ", "0", "ja", "vielleicht", "TRUE ist es nicht").forEach { wert ->
      val manager = CustomColumnsManager()
      findOrCreateAllowHolidayWork(manager).defaultValueAsString = wert
      assertFalse(manager.allowsHolidayWork(), "<$wert> darf nicht als AN gelesen werden")
    }
  }

  @Test
  fun `true wird auch mit umgebenden leerzeichen und in grossbuchstaben gelesen`() {
    listOf("true", " true ", "True", "TRUE").forEach { wert ->
      val manager = CustomColumnsManager()
      findOrCreateAllowHolidayWork(manager).defaultValueAsString = wert
      assertTrue(manager.allowsHolidayWork(), "<$wert> muss als AN gelesen werden")
    }
  }

  /**
   * The id may never look like one the program hands out itself. `CustomColumnsManager.createId`
   * counts up `tpc0`, `tpc1`, … and would collide with anything of that shape.
   */
  @Test
  fun `der schluessel kann nicht mit einem selbstvergebenen kollidieren`() {
    assertFalse(PROJECT_ALLOW_HOLIDAY_WORK.matches(Regex("tpc\\d+")))
    assertTrue(PROJECT_ALLOW_HOLIDAY_WORK.contains('.'))
  }

  // ---- DIE REGEL: AUS IST DAS ALTE PROGRAMM -----------------------------------------------------

  /**
   * THE GUARD OF THIS PACKAGE IN ITS SMALLEST FORM. With the switch off, `isWorking` has to be the
   * expression `workingDayTest` contained before A4 — for every mask, not for the ones that came
   * to mind.
   */
  @Test
  fun `aus ist bitweise das alte programm`() {
    listOf(weekday, weekend, weekendWorked, holiday, holidayOnSaturday, nothing).forEach { mask ->
      assertEquals(mask and DayMask.WORKING != 0, HolidayRule.OFF.isWorking(mask),
        "Maske $mask: AUS muss `mask and WORKING != 0` sein und sonst nichts")
    }
  }

  @Test
  fun `aus laesst die A2-frage nach dem wochentag unveraendert`() {
    listOf(weekday, weekend, weekendWorked, holiday, holidayOnSaturday, nothing).forEach { mask ->
      assertEquals(
        mask and DayMask.WORKING == 0 && mask and DayMask.HOLIDAY == 0,
        HolidayRule.OFF.isFreeForWeekdayReasons(mask),
        "Maske $mask: die Erweiterungsfrage aus A2 darf der Schalter nicht anfassen, solange er aus ist")
    }
  }

  @Test
  fun `aus laesst den feiertag frei, auch wenn alle an dem wochentag arbeiten`() {
    assertFalse(HolidayRule.OFF.isWorking(holiday))
    assertFalse(HolidayRule.OFF.isFreeForWeekdayReasons(holiday),
      "das ist die Grenze zu A2: ein Feiertag ist nicht `wegen des Wochentags frei`, also darf " +
        "die Erweiterungsregel ihn nicht zurueckholen")
  }

  // ---- DIE REGEL: AN BEWEGT NUR FEIERTAGE -------------------------------------------------------

  @Test
  fun `an macht den feiertag zum arbeitstag`() {
    assertFalse(HolidayRule.OFF.isWorking(holiday))
    assertTrue(on.isWorking(holiday))
  }

  @Test
  fun `an ruehrt das wochenende nicht an`() {
    assertFalse(on.isWorking(weekend),
      "der Schalter kennt nur Feiertage; ein Samstag bleibt ein Samstag")
    assertEquals(HolidayRule.OFF.isWorking(weekend), on.isWorking(weekend))
    assertEquals(HolidayRule.OFF.isWorking(weekendWorked), on.isWorking(weekendWorked))
    assertEquals(HolidayRule.OFF.isWorking(weekday), on.isWorking(weekday))
  }

  /**
   * A holiday that falls on a Saturday. With the switch on it does not become workable — it
   * becomes an ORDINARY SATURDAY, and whether the task gets it is A2's question: it is now free
   * for weekday reasons, so a team that all works Saturdays may win it back and a team that does
   * not may not. That is the one place where the two rules touch, and they touch in the right
   * order.
   */
  @Test
  fun `an macht aus einem feiertag am samstag einen gewoehnlichen samstag`() {
    assertFalse(on.isWorking(holidayOnSaturday),
      "der Schalter allein darf den Samstag nicht hergeben")
    assertTrue(on.isFreeForWeekdayReasons(holidayOnSaturday),
      "aber er ist danach `wegen des Wochentags frei` -- und damit ist es die Arbeitswoche, die " +
        "ueber ihn entscheidet, nicht der Schalter")
    assertFalse(HolidayRule.OFF.isFreeForWeekdayReasons(holidayOnSaturday),
      "ausgeschaltet bleibt derselbe Tag fuer die Arbeitswoche unerreichbar")
  }

  @Test
  fun `an laesst einen gewoehnlichen freien samstag genauso frei wie vorher`() {
    assertEquals(HolidayRule.OFF.isFreeForWeekdayReasons(weekend), on.isFreeForWeekdayReasons(weekend))
    assertTrue(on.isFreeForWeekdayReasons(weekend))
  }

  @Test
  fun `an nimmt keinem arbeitstag die arbeit weg`() {
    assertFalse(on.isFreeForWeekdayReasons(weekday))
    assertFalse(on.isFreeForWeekdayReasons(holiday),
      "ein Feiertag, an dem gearbeitet werden darf, ist ein Arbeitstag und kein freier Tag -- " +
        "die Erweiterungsregel aus A2 hat an ihm nichts mehr zu holen")
  }

  /**
   * `onlyShowWeekends` is the project option „weekends are drawn but scheduled through". With it,
   * a holiday is the only non-working day there is, and „ignore the holiday" therefore has to give
   * the Saturday back as well. The one case in which reading the WEEKEND bit alone would be wrong.
   */
  @Test
  fun `wenn wochenenden durchgeplant werden, gibt an auch den feiertag am samstag frei`() {
    val onlyShowWeekends = HolidayRule(allowHolidayWork = true, weekendsAreWorkingDays = true)
    assertTrue(onlyShowWeekends.isWorking(holidayOnSaturday))
    assertTrue(onlyShowWeekends.isWorking(holiday))
    assertFalse(onlyShowWeekends.isFreeForWeekdayReasons(holidayOnSaturday))
  }

  // ---- WOHER DIE REGEL KOMMT --------------------------------------------------------------------

  @Test
  fun `ohne merkmalsverwaltung ist die regel aus, und zwar dasselbe objekt`() {
    assertSame(HolidayRule.OFF, HolidayRule.of(WeekendCalendarForTest.plain(), null))
  }

  @Test
  fun `die regel wird aus der merkmalsverwaltung der personen gelesen`() {
    val manager = CustomColumnsManager()
    assertSame(HolidayRule.OFF, HolidayRule.of(WeekendCalendarForTest.plain(), manager))
    manager.setAllowHolidayWork(true)
    assertTrue(HolidayRule.of(WeekendCalendarForTest.plain(), manager).allowHolidayWork)
    manager.setAllowHolidayWork(false)
    assertSame(HolidayRule.OFF, HolidayRule.of(WeekendCalendarForTest.plain(), manager),
      "wieder ausgeschaltet muss es wieder genau dasselbe Objekt sein wie vor A4")
  }

  @Test
  fun `die regel liest onlyShowWeekends aus dem kalender`() {
    val manager = CustomColumnsManager().also { it.setAllowHolidayWork(true) }
    val calendar = WeekendCalendarForTest.plain().also { it.onlyShowWeekends = true }
    assertTrue(HolidayRule.of(calendar, manager).isWorking(holidayOnSaturday),
      "der Kalender plant Wochenenden durch, also ist auch der Feiertag am Samstag ein Arbeitstag")
  }
}

private object WeekendCalendarForTest {
  fun plain() = biz.ganttproject.core.calendar.WeekendCalendarImpl()
}
