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

import biz.ganttproject.core.calendar.GanttDaysOff
import biz.ganttproject.core.calendar.WeekendCalendarImpl
import biz.ganttproject.core.chart.scene.CapacityHeatmapSceneBuilder
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.core.time.GanttCalendar
import biz.ganttproject.customproperty.CustomPropertyManager
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.resource.HumanResourceManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * ═══ WHAT GETS DRAWN, BEFORE ANYTHING IS DRAWN ═══
 *
 * B4 turns „this person is at home on that day" into rectangles. This file checks the turning, and
 * `HomeWorkKalenderTest` checks that the rectangles come out looking different from the ones beside
 * them. Split that way because the two fail for different reasons and are worth being told apart:
 * a wrong band is a wrong answer, a band that looks like a holiday is a wrong picture.
 *
 * THE ONE PROPERTY THE REST OF THE PACKAGE STANDS ON is that the bands DO NOT OVERLAP. The style of
 * a band is chosen downstream by comparing the accumulated load against
 * [CapacityHeatmapSceneBuilder.HOME_WORK_LOAD] (-2), and two bands over one day would accumulate to
 * -4, miss the comparison and be painted as an ORDINARY LOAD — a home-working day drawn as an
 * overload of -400 %. It is checked below against a pattern that names every weekday AND periods
 * that cover the same days again, which is how a person who has both models entered actually
 * looks.
 *
 * 7 September 2026 is a Monday.
 */
class HomeWorkBandTest {

  init {
    object : CalendarFactory() {
      init {
        setLocaleApi(object : CalendarFactory.LocaleApi {
          override fun getLocale(): Locale = Locale.GERMANY
          override fun getShortDateFormat(): DateFormat =
            DateFormat.getDateInstance(DateFormat.SHORT, Locale.GERMANY)
        })
      }
    }
  }

  private val montag = LocalDate.of(2026, 9, 7)
  private val woche = montag to montag.plusDays(7)

  private fun heimarbeit(vararg tage: LocalDate): HomeOffice =
    HomeOffice(periods = HomeOfficePeriods(tage.map { HomeOfficePeriod(it, it.plusDays(1)) }))

  private fun baender(
    homeOffice: HomeOffice,
    von: LocalDate = woche.first,
    bis: LocalDate = woche.second,
    urlaub: (LocalDate) -> Boolean = { false }
  ) = homeWorkBands(homeOffice, von, bis, urlaub)

  // =============================================================================================
  // The runs.
  // =============================================================================================

  @Test
  fun `ein einzelner heimarbeitstag wird ein band von einem tag`() {
    val ergebnis = baender(heimarbeit(montag.plusDays(2)))
    assertEquals(listOf(HomeWorkBand(montag.plusDays(2), montag.plusDays(3))), ergebnis)
    assertEquals(1, ergebnis.single().days)
  }

  @Test
  fun `aufeinanderfolgende heimarbeitstage werden EIN band und nicht drei`() {
    val ergebnis = baender(heimarbeit(montag, montag.plusDays(1), montag.plusDays(2)))
    assertEquals(listOf(HomeWorkBand(montag, montag.plusDays(3))), ergebnis)
    assertEquals(3, ergebnis.single().days)
  }

  @Test
  fun `getrennte heimarbeitstage werden getrennte baender`() {
    val ergebnis = baender(heimarbeit(montag, montag.plusDays(3)))
    assertEquals(
      listOf(
        HomeWorkBand(montag, montag.plusDays(1)),
        HomeWorkBand(montag.plusDays(3), montag.plusDays(4))
      ), ergebnis)
  }

  /**
   * THE PRECONDITION OF THE WHOLE PACKAGE — see the file comment. A person with a weekly pattern
   * over every weekday AND two periods on top of it, which is a person who has entered both models
   * for the same days.
   */
  @Test
  fun `die baender ueberlappen einander nie, auch wenn muster und zeitraum denselben tag nennen`() {
    val beides = HomeOffice(
      week = HomeOfficeWeek(listOf(HomeOfficeChange(null, DayOfWeek.values().toSet()))),
      periods = HomeOfficePeriods(listOf(
        HomeOfficePeriod(montag, montag.plusDays(3)),
        HomeOfficePeriod(montag.plusDays(1), montag.plusDays(5))))
    )
    val ergebnis = baender(beides)
    assertEquals(listOf(HomeWorkBand(woche.first, woche.second)), ergebnis)
    ergebnis.zipWithNext().forEach { (links, rechts) ->
      assertFalse(rechts.start.isBefore(links.endExclusive),
        "Band $rechts faengt an, bevor $links zu Ende ist")
    }
  }

  @Test
  fun `ein urlaubstag faellt aus dem band heraus und zerteilt es`() {
    val mittwoch = montag.plusDays(2)
    val ergebnis = baender(
      heimarbeit(montag, montag.plusDays(1), mittwoch, montag.plusDays(3))
    ) { it == mittwoch }
    assertEquals(
      listOf(
        HomeWorkBand(montag, mittwoch),
        HomeWorkBand(montag.plusDays(3), montag.plusDays(4))
      ), ergebnis)
  }

  @Test
  fun `ohne heimarbeit gibt es kein band, und auch kein leeres`() {
    assertEquals(emptyList<HomeWorkBand>(), baender(HomeOffice()))
  }

  @Test
  fun `das band bleibt im gefragten zeitraum, dessen ende nicht dazugehoert`() {
    val ergebnis = baender(
      heimarbeit(montag.minusDays(1), montag.plusDays(6), montag.plusDays(7)),
      von = montag, bis = montag.plusDays(7))
    assertEquals(listOf(HomeWorkBand(montag.plusDays(6), montag.plusDays(7))), ergebnis)
  }

  @Test
  fun `ein zeitraum ohne tage liefert nichts`() {
    assertEquals(emptyList<HomeWorkBand>(),
      baender(heimarbeit(montag), von = montag, bis = montag))
  }

  // =============================================================================================
  // The bridge to the chart: a real person, real properties, real days off.
  // =============================================================================================

  private class Projekt {
    val builder: TestSetupHelper.TaskManagerBuilder =
      TestSetupHelper.newTaskManagerBuilder().withCalendar(WeekendCalendarImpl())
    init { builder.build() }
    val resourceManager: HumanResourceManager = builder.resourceManager
    val resourceProperties: CustomPropertyManager get() = resourceManager.customPropertyManager
    private var naechsteId = 0
    fun person(name: String): HumanResource = resourceManager.create(name, naechsteId++)
  }

  private fun HumanResource.urlaubAm(tag: LocalDate) {
    this.addDaysOff(
      GanttDaysOff(GanttCalendar.fromLocalDate(tag), GanttCalendar.fromLocalDate(tag.plusDays(1))))
  }

  @Test
  fun `eine person ohne eintrag liefert keine einzige last`() {
    val projekt = Projekt()
    val person = projekt.person("Ohne")
    val lasten = person.homeWorkLoads(
      projekt.resourceProperties, montag.toModelDate(), montag.plusDays(7).toModelDate())
    assertEquals(emptyList<CapacityHeatmapSceneBuilder.Load>(), lasten)
  }

  @Test
  fun `die last traegt den heimarbeitswert und die mitternachten des modells`() {
    val projekt = Projekt()
    val person = projekt.person("Zuhause")
    person.setHomeOfficePeriods(projekt.resourceProperties,
      HomeOfficePeriods.parse(montag.plusDays(1).toString()).periods)
    val lasten = person.homeWorkLoads(
      projekt.resourceProperties, montag.toModelDate(), montag.plusDays(7).toModelDate())
    assertEquals(1, lasten.size, "genau ein Band erwartet, bekommen: $lasten")
    assertEquals(CapacityHeatmapSceneBuilder.HOME_WORK_LOAD, lasten.single().load)
    assertEquals(montag.plusDays(1).toModelDate().time, lasten.single().startTs)
    assertEquals(montag.plusDays(2).toModelDate().time, lasten.single().endTs)
    assertEquals(null, lasten.single().taskId, "ein Band gehoert zu keinem Vorgang")
  }

  /**
   * A day that is BOTH. The person is away, not at home working — see `HomeWorkBand.kt` on why
   * this is decided in the drawing and changes nothing about the schedule.
   */
  @Test
  fun `urlaub und heimarbeit am selben tag ergeben kein heimarbeitsband`() {
    val projekt = Projekt()
    val person = projekt.person("Beides")
    person.setHomeOfficePeriods(projekt.resourceProperties,
      HomeOfficePeriods.parse(montag.toString()).periods)
    person.urlaubAm(montag)
    val lasten = person.homeWorkLoads(
      projekt.resourceProperties, montag.toModelDate(), montag.plusDays(7).toModelDate())
    assertTrue(lasten.isEmpty(), "erwartet: kein Band, bekommen: $lasten")
  }
}
