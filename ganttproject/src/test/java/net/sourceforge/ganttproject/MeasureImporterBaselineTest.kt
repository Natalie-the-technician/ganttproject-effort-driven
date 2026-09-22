/*
Measurement only. Not for submission.

Reproduces the claim in PR #2838 that XmlProjectImporter (used by the Colloboque server)
creates baselines with no temporary file, and that load() on one of those throws today.
This file sits on master, so it measures today's behaviour, not the behaviour of #2838.
*/
package net.sourceforge.ganttproject

import biz.ganttproject.core.io.XmlProjectImporter
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.core.time.CalendarFactory.LocaleApi
import biz.ganttproject.core.time.CalendarFactory.setLocaleApi
import net.sourceforge.ganttproject.io.HistorySaver
import net.sourceforge.ganttproject.io.SaverBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Locale
import javax.xml.transform.stream.StreamResult

class MeasureImporterBaselineTest : SaverBase() {

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

  private val withOneTask = """
    <project name="p">
      <previous>
        <previous-tasks name="a baseline">
          <previous-task id="1" start="2026-09-11" duration="3" meeting="false" super="false"/>
        </previous-tasks>
      </previous>
    </project>
  """.trimIndent()

  private val withNoTasks = """
    <project name="p">
      <previous>
        <previous-tasks name="empty baseline"/>
      </previous>
    </project>
  """.trimIndent()

  @Test
  fun `a baseline that came in through XmlProjectImporter can be read back`() {
    val project = XmlProjectImporter().import(withOneTask)
    val baseline = project.baselines.single()
    assertEquals("a baseline", baseline.name)

    assertNotNull(baseline.load(), "an imported baseline has to hand out its tasks")
  }

  @Test
  fun `a project with an imported baseline can be saved again`() {
    val project = XmlProjectImporter().import(withOneTask)
    val out = ByteArrayOutputStream()
    val handler = createHandler(StreamResult(out))
    handler.startDocument()
    HistorySaver().saveBaseline(project.baselines.single(), handler)
    handler.endDocument()

    assertEquals(true, out.toString(Charsets.UTF_8).contains("2026-09-11"),
      "saving a project with an imported baseline has to write the baseline out")
  }
  @Test
  fun `a project with an imported empty baseline can be saved again`() {
    val project = XmlProjectImporter().import(withNoTasks)
    val out = ByteArrayOutputStream()
    val handler = createHandler(StreamResult(out))
    handler.startDocument()
    HistorySaver().saveBaseline(project.baselines.single(), handler)
    handler.endDocument()

    assertEquals(true, out.toString(Charsets.UTF_8).contains("empty baseline"),
      "saving a project with an imported empty baseline has to write the baseline out")
  }
}
