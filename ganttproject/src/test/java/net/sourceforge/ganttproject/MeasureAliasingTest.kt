/*
Measurement only. Not for submission.

The same assertions exist word for word in the worktree that sits on master, where they use
load() instead of getTasks(). Everything else is identical, so a difference in the result is a
difference between the two designs and nothing else.
*/
package net.sourceforge.ganttproject

import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.core.time.CalendarFactory.LocaleApi
import biz.ganttproject.core.time.CalendarFactory.setLocaleApi
import net.sourceforge.ganttproject.io.HistorySaver
import net.sourceforge.ganttproject.io.SaverBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.lang.reflect.Modifier
import java.text.DateFormat
import java.util.Calendar
import java.util.Locale
import javax.xml.transform.stream.StreamResult

class MeasureAliasingTest : SaverBase() {

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

  private fun newBaseline(): GanttPreviousState {
    val start = CalendarFactory.createGanttCalendar(2026, 8, 11)
    val tasks = mutableListOf(GanttPreviousStateTask(1, start, 3, false, false))
    return GanttPreviousState("a baseline", tasks)
  }

  /** Counts, and names, the public instance mutators reachable on a GanttCalendar. */
  @Test
  fun `GanttCalendar has no public mutators`() {
    val mutators = biz.ganttproject.core.time.GanttCalendar::class.java.methods
      .filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) }
      .filter { it.name.startsWith("set") || it.name in setOf("add", "roll", "clear", "complete") }
      .map { "${it.declaringClass.simpleName}.${it.name}(${it.parameterTypes.joinToString(",") { p -> p.simpleName }})" }
      .sorted()
    println("PUBLIC MUTATORS ON GanttCalendar: ${mutators.size}")
    mutators.forEach { println("  $it") }
    assertEquals(emptyList<String>(), mutators,
      "the claim under measurement is that a GanttCalendar cannot be changed through its public API")
  }

  @Test
  fun `a stored baseline keeps its date when a caller changes the one it handed out`() {
    val baseline = newBaseline()
    baseline.tasks.single().start.add(Calendar.YEAR, 1)

    assertEquals("2026-09-11", baseline.tasks.single().start.toXMLString(),
      "the baseline has to keep the date it took, whatever a caller does to what it handed out")
  }

  @Test
  fun `a stored baseline keeps its tasks when a caller empties the list it handed out`() {
    val baseline = newBaseline()
    (baseline.tasks as MutableList).clear()

    assertEquals(1, baseline.tasks.size,
      "the baseline has to keep its tasks, whatever a caller does to the list it handed out")
  }

  @Test
  fun `the project file keeps the date after a caller changed what the baseline handed out`() {
    val baseline = newBaseline()
    baseline.tasks.single().start.add(Calendar.YEAR, 1)

    val out = ByteArrayOutputStream()
    val handler = createHandler(StreamResult(out))
    handler.startDocument()
    HistorySaver().saveBaseline(baseline, handler)
    handler.endDocument()
    val xml = out.toString(Charsets.UTF_8)

    assertEquals(true, xml.contains("start=\"2026-09-11\""),
      "the saved project has to hold the date the baseline took. The document was:\n$xml")
  }
}
