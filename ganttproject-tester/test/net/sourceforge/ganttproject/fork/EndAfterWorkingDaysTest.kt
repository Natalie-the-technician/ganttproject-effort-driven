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

import junit.framework.TestCase
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.function.ThrowingSupplier
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate

/**
 * The bounds of `endAfterWorkingDays`, the date arithmetic of the levelling write-back.
 *
 * WHAT IS AT STAKE HERE IS NOT A DATE BUT A HANG. The function is called from the write-back of
 * levelling, one menu item away from the user. All three of its loops advance by one day at a time
 * until they see a working day; an `isWorkingDay` that never says true therefore used to let the
 * program spin for ever, with no dialog and no message. That failure mode has been measured on
 * this machine before -- `ResourceLevelling.nextWorkingDay` records how the test runner was
 * cleared away by the operating system without a single word -- and the rule taken from it is the
 * one these tests pin: better a visibly wrong date than a hanging program.
 *
 * WHY EVERY CHECK CARRIES A TIME LIMIT, and it is not decoration. A test for "the program comes
 * back" cannot be written as a plain assertion: if the bound it checks were ever removed again,
 * the test would not fail, it would hang, and it would take the whole suite with it. The time
 * limit is what turns that back into an ordinary red test. It is the same reason the bounds exist
 * in the first place, applied to the tests.
 *
 * THE PRICE OF `assertTimeoutPreemptively` IS WORTH KNOWING: it runs the call on a thread of its
 * own and, on expiry, calls `shutdownNow`, which interrupts. A tight loop over `LocalDate` does
 * not answer an interrupt, and the thread that JUnit creates for this is NOT a daemon (checked in
 * junit-jupiter-api 5.12.2: `TimeoutThreadFactory` calls `new Thread(runnable, name)` and never
 * `setDaemon`). So a genuine regression would fail this test AND leave one spinning thread behind
 * that can keep the test JVM from exiting. That is a bad day, but a visible one -- which is the
 * whole point, and strictly better than the silent version.
 *
 * THE THREE LOOPS ARE CHECKED SEPARATELY, and the three cases below are built so that each one
 * reaches exactly one of them: with only its own bound removed, exactly its own test hangs and the
 * other two still pass. Which one each case lands in is written on the case.
 */
class EndAfterWorkingDaysTest : TestCase() {

  /** Monday. All calculations in this test start from this week, as in `ResourceLevellingTest`. */
  private val monday: LocalDate = LocalDate.of(2026, 8, 17)

  /** An ordinary week: five working days, Saturday and Sunday off. */
  private val ordinaryWeek: (LocalDate) -> Boolean =
    { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }

  /**
   * A calendar without a single working day.
   *
   * NOT AN INVENTED CASE. It is what a person's working week produces once a section of it has no
   * day ticked, and what a broken weekend setting or an empty holiday list produce today.
   */
  private val never: (LocalDate) -> Boolean = { false }

  /** A calendar whose only working days are the ones handed in. */
  private fun onlyOn(vararg days: LocalDate): (LocalDate) -> Boolean = { it in days.toSet() }

  /**
   * The call under a time limit. Ten seconds: the bounded function needs some hundred thousand
   * `LocalDate.plusDays` calls in its very worst case, which is milliseconds, so ten seconds
   * cannot go off by accident on a loaded machine and still ends the run quickly when it does.
   */
  private fun boundedCall(
    start: LocalDate, days: Int, isWorkingDay: (LocalDate) -> Boolean
  ): LocalDate = assertTimeoutPreemptively(
    Duration.ofSeconds(10), ThrowingSupplier { endAfterWorkingDays(start, days, isWorkingDay) })

  /**
   * The function exactly as it stood before the bounds were added.
   *
   * IT IS HERE TO BE THE JUDGE OF THE NORMAL CASE. "The bound does not bite where it must not
   * bite" is not something an expected value written by hand can show for more than the handful of
   * dates one bothers to write down; comparing against the unbounded original shows it for every
   * date the comparison covers, and shows it as identity rather than as agreement with a guess.
   * It terminates safely because it is only ever called with a calendar that has working days.
   */
  private fun unbounded(start: LocalDate, days: Int, isWorkingDay: (LocalDate) -> Boolean): LocalDate {
    var day = start
    while (!isWorkingDay(day)) {
      day = day.plusDays(1)
    }
    var counted = 0
    while (true) {
      if (isWorkingDay(day)) {
        counted++
      }
      if (counted >= maxOf(days, 1)) {
        break
      }
      day = day.plusDays(1)
    }
    var end = day.plusDays(1)
    while (!isWorkingDay(end)) {
      end = end.plusDays(1)
    }
    return end
  }

  /**
   * THE GUARD AGAINST THE CURE. An ordinary Task in an ordinary week has to come out exactly as
   * before, to the day. The dates are written out by hand here rather than computed, so that a
   * change of the arithmetic cannot quietly move both sides of the comparison at once.
   */
  fun testAnOrdinaryWeekIsUnchangedToTheDay() {
    // The end is EXCLUSIVE: three working days from Monday end on Thursday.
    assertEquals(LocalDate.of(2026, 8, 18), endAfterWorkingDays(monday, 1, ordinaryWeek))
    assertEquals(LocalDate.of(2026, 8, 20), endAfterWorkingDays(monday, 3, ordinaryWeek))
    // Five days end on Friday, and the exclusive end is pushed over the weekend to Monday.
    assertEquals(LocalDate.of(2026, 8, 24), endAfterWorkingDays(monday, 5, ordinaryWeek))
    assertEquals(LocalDate.of(2026, 8, 25), endAfterWorkingDays(monday, 6, ordinaryWeek))
    assertEquals(LocalDate.of(2026, 8, 31), endAfterWorkingDays(monday, 10, ordinaryWeek))
    // A start on the weekend is carried to the Monday first.
    assertEquals(LocalDate.of(2026, 8, 25), endAfterWorkingDays(LocalDate.of(2026, 8, 22), 1, ordinaryWeek))
    assertEquals(LocalDate.of(2026, 8, 26), endAfterWorkingDays(LocalDate.of(2026, 8, 23), 2, ordinaryWeek))
  }

  /**
   * The same guard, but for every start day of a quarter and every duration up to a working year,
   * against the function as it stood before. 91 start days times 260 durations: 23 660 pairs, and
   * not one of them may differ.
   */
  fun testTheBoundChangesNothingWhereThereAreWorkingDays() {
    for (offset in 0L until 91L) {
      val start = monday.plusDays(offset)
      for (days in 1..260) {
        assertEquals(
          "start $start, $days working days must come out as before the bound",
          unbounded(start, days, ordinaryWeek), endAfterWorkingDays(start, days, ordinaryWeek))
      }
    }
  }

  /**
   * LOOP ONE: the search for the first working day, before any counting.
   *
   * Without a single working day anywhere it never ends. The fallback is a Task of one day,
   * because nothing at all is known about the calendar beyond the start.
   *
   * WITH ONLY THIS BOUND REMOVED this test hangs and the other two loop tests still pass: both of
   * them start on a day that IS a working day, so this loop ends for them on its first look.
   */
  fun testWithoutAnyWorkingDayItComesBackInsteadOfHanging() {
    assertEquals("the end has to fall back to one day after the start",
      LocalDate.of(2026, 8, 18), boundedCall(monday, 3, never))
  }

  /**
   * LOOP TWO: the counting of the working days. THE ONE THAT LOOKS SAFE AND IS NOT -- it carries a
   * `break`, but the break tests `counted`, and `counted` only ever grows on a working day.
   *
   * Monday and Tuesday work, nothing after them, and five days are asked for: the count stops at
   * two and the break is never reached. The fallback is the day after the last working day the
   * count did find -- Tuesday plus one -- because those days are the only thing the loop learned.
   *
   * WITH ONLY THIS BOUND REMOVED this test hangs and the other two still pass: the first case
   * returns from loop one's bound before ever reaching here, and the third asks for one single day,
   * which the break catches on the first pass.
   */
  fun testWithTooFewWorkingDaysItComesBackInsteadOfHanging() {
    val mondayAndTuesday = onlyOn(monday, monday.plusDays(1))
    assertEquals("the end has to fall back behind the last working day there was",
      LocalDate.of(2026, 8, 19), boundedCall(monday, 5, mondayAndTuesday))
  }

  /**
   * LOOP THREE: pushing the exclusive end onto a working day, after the counting is done.
   *
   * Monday alone works and one day is asked for, so the counting is finished before the loop above
   * can bite; the end lands on Tuesday, which is not a working day, and the push never ends. The
   * fallback is that unpushed end -- the same choice `ResourceLevelling.nextWorkingDay` makes when
   * it gives `from` back.
   *
   * WITH ONLY THIS BOUND REMOVED this test hangs and the other two still pass: they both return
   * from an earlier bound and never arrive here.
   */
  fun testWithoutAWorkingDayAfterTheLastOneItComesBackInsteadOfHanging() {
    assertEquals("the end has to stay where the counting left it",
      LocalDate.of(2026, 8, 18), boundedCall(monday, 1, onlyOn(monday)))
  }

  /**
   * The property that all three fallbacks share, and the one the caller depends on: the end lies
   * AFTER the start. `mutator.setEnd` gets this date, and an end at or before the start is not a
   * short Task but a broken one.
   *
   * The upper half is the other half of the same statement, and the more important one: the
   * fallback must not be the day the search gave up on. That day lies 50 000 days out, and setting
   * it as an end is exactly the disaster the call site records -- Tasks that occupied years
   * instead of days.
   */
  fun testEveryFallbackEndIsShortAndLiesAfterTheStart() {
    val cases = listOf(
      Triple(3, never, "no working day at all"),
      Triple(5, onlyOn(monday, monday.plusDays(1)), "too few working days"),
      Triple(1, onlyOn(monday), "no working day after the last one"))
    for ((days, calendar, name) in cases) {
      val end = boundedCall(monday, days, calendar)
      assertTrue("$name: the end must lie after the start, got $end", end.isAfter(monday))
      assertTrue("$name: the fallback must be a short Task, not one reaching to the bound, got $end",
        end.isBefore(monday.plusDays(10)))
    }
  }
}
