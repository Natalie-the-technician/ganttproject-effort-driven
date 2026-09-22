/*
Measurement only. Not for submission.

What one baseline entry costs in the heap once baselines are kept in memory.
*/
package net.sourceforge.ganttproject

import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.core.time.CalendarFactory.LocaleApi
import biz.ganttproject.core.time.CalendarFactory.setLocaleApi
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.util.Locale

class MeasureBaselineFootprintTest {

  @BeforeEach
  fun setUp() {
    object : CalendarFactory() {
      init {
        setLocaleApi(object : LocaleApi {
          override fun getLocale(): Locale = Locale.US
          override fun getShortDateFormat(): DateFormat =
            DateFormat.getDateInstance(DateFormat.SHORT, Locale.US)
        })
      }
    }
  }

  private fun settledHeap(): Long {
    repeat(8) { System.gc(); Thread.sleep(40) }
    val rt = Runtime.getRuntime()
    return rt.totalMemory() - rt.freeMemory()
  }

  /** Builds n entries the way GanttPreviousState.createTasks does, and reads each one once. */
  private fun buildEntries(n: Int, read: Boolean): List<GanttPreviousStateTask> {
    val list = ArrayList<GanttPreviousStateTask>(n)
    for (i in 0 until n) {
      val start = CalendarFactory.createGanttCalendar(2026, 8, 11).clone()
      val e = GanttPreviousStateTask(i, start, 3, false, false)
      if (read) e.start.time
      list.add(e)
    }
    return list
  }

  private fun bytesPerEntry(n: Int, read: Boolean): Double {
    val before = settledHeap()
    val list = buildEntries(n, read)
    val after = settledHeap()
    // Keep the list alive across the second measurement.
    assertTrue(list.size == n)
    return (after - before).toDouble() / n
  }

  @Test
  fun `one baseline entry in memory`() {
    val results = linkedMapOf<String, Double>()
    for (read in listOf(false, true)) {
      for (n in listOf(25_000, 100_000, 400_000)) {
        val b = bytesPerEntry(n, read)
        results["read=$read n=$n"] = b
        println("BYTES PER BASELINE ENTRY  read=$read  n=$n  ->  %.1f".format(b))
      }
    }
    // The measurement is only worth reporting if it does not depend on n.
    for (read in listOf(false, true)) {
      val vals = results.filterKeys { it.startsWith("read=$read") }.values.toList()
      val spread = (vals.max() - vals.min()) / vals.max()
      println("SPREAD read=$read: %.3f".format(spread))
      assertTrue(spread < 0.25,
        "the per-entry figure has to be the same at every n, or it is not a per-entry figure: $vals")
    }
  }
}
