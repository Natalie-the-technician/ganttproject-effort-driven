/*
Measurement only. Not for submission.

The denominator for the baseline footprint: what a live task costs in the same heap, and what
a baseline entry costs on disk today.
*/
package net.sourceforge.ganttproject

import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.core.time.CalendarFactory.LocaleApi
import biz.ganttproject.core.time.CalendarFactory.setLocaleApi
import net.sourceforge.ganttproject.io.HistorySaver
import net.sourceforge.ganttproject.io.SaverBase
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Locale
import javax.xml.transform.stream.StreamResult

class MeasureTaskFootprintTest : SaverBase() {

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

  @Test
  fun `a live task in the same heap`() {
    for (n in listOf(250, 1000, 4000)) {
      val before = settledHeap()
      val taskManager = TestSetupHelper.newTaskManagerBuilder().build()
      val start = CalendarFactory.createGanttCalendar(2026, 8, 11)
      for (i in 0 until n) {
        taskManager.newTaskBuilder().withName("task $i").withStartDate(start.time).withDuration(
          taskManager.createLength(3)).build()
      }
      val after = settledHeap()
      println("BYTES PER LIVE TASK  n=$n  ->  %.1f".format((after - before).toDouble() / n))
      assertTrue(taskManager.taskCount == n, "n=$n but taskCount=${taskManager.taskCount}")
    }
  }

  @Test
  fun `a baseline entry on disk today`() {
    val start = CalendarFactory.createGanttCalendar(2026, 8, 11)
    for (n in listOf(100, 1000)) {
      val tasks = (1..n).map { GanttPreviousStateTask(it, start, 3, false, false) }
      val out = ByteArrayOutputStream()
      val handler = createHandler(StreamResult(out))
      handler.startDocument()
      HistorySaver().saveBaseline("a baseline", tasks, handler)
      handler.endDocument()
      println("BYTES PER ENTRY ON DISK  n=$n  ->  %.1f".format(out.size().toDouble() / n))
    }
  }
}
