/*
Copyright 2026 BarD Software s.r.o., GanttProject Team

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
package net.sourceforge.ganttproject.document

import biz.ganttproject.core.io.XmlProjectImporter
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.core.time.CalendarFactory.LocaleApi
import biz.ganttproject.core.time.CalendarFactory.setLocaleApi
import net.sourceforge.ganttproject.GanttProjectImpl
import net.sourceforge.ganttproject.IGanttProject
import net.sourceforge.ganttproject.PrjInfos
import net.sourceforge.ganttproject.io.GanttXMLOpen
import net.sourceforge.ganttproject.io.GanttXMLSaver
import net.sourceforge.ganttproject.parser.ParserFactory
import net.sourceforge.ganttproject.task.Task
import org.easymock.EasyMock.createNiceMock
import org.easymock.EasyMock.expect
import org.easymock.EasyMock.replay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.text.DateFormat
import java.util.Locale

/**
 * Files of the effort driven fork kept the effort of a task in hours as the custom property `effort_hours`. Reading
 * such a file has to give the task the effort the fork worked with, through the desktop reader and through the
 * importer alike, and saving it has to write only the attribute.
 */
class ForkEffortReadTest {
  private lateinit var project: GanttProjectImpl

  @BeforeEach
  fun setUp() {
    object : CalendarFactory() {
      init {
        setLocaleApi(object : LocaleApi {
          override fun getLocale(): Locale = Locale.US
          override fun getShortDateFormat(): DateFormat = DateFormat.getDateInstance(DateFormat.SHORT, Locale.US)
        })
      }
    }
    project = GanttProjectImpl()
  }

  /** A file written by the fork: the demo plan of 2026-11-09, as it came from the WebDAV store. */
  private fun forkFile(): String =
    javaClass.getResource("fork-demo-website-relaunch.gan")!!.readText(Charsets.UTF_8)

  /** The efforts of the demo plan by task id, as the fork wrote them. */
  private val demoEfforts = mapOf(1 to "16", 2 to "12", 4 to "30", 5 to "40", 8 to "64", 9 to "30", 10 to "24")

  @Test
  fun `a file written by the fork opens with the effort of the fork on each task`() {
    read(forkFile())
    assertEquals(demoEfforts, efforts(project.taskManager.tasks))
  }

  @Test
  fun `the importer reads the effort of a file written by the fork the same way`() {
    val imported = XmlProjectImporter().import(forkFile())
    assertEquals(demoEfforts, efforts(imported.taskManager.tasks))
    assertFalse("effort_hours" in imported.taskCustomColumnManager.definitions.map { it.id })
  }

  @Test
  fun `saving a file written by the fork writes only the effort attribute`() {
    read(forkFile())
    assertFalse("effort_hours" in project.taskCustomColumnManager.definitions.map { it.id })
    val saved = save(project)
    assertTrue(saved.contains("""effort-hours="64"""), saved)
    assertFalse(saved.contains("effort_hours"), saved)
  }

  @Test
  fun `an effort of zero or less in the fork stays no effort, since the fork read it as none`() {
    read(projectXml(task(1, "0"), task(2, "-4"), task(3, "-0.5")))
    (1..3).forEach { assertNull(effort(it), "task $it") }
    assertFalse(save(project).contains("effort_hours"))
  }

  @Test
  fun `an empty effort of the fork leaves the task as it is and is dropped`() {
    read(projectXml(task(1, ""), task(2, "  "), task(3, null)))
    (1..3).forEach { assertNull(effort(it), "task $it") }
    assertFalse(save(project).contains("effort_hours"))
  }

  @Test
  fun `an unreadable effort is not guessed and stays as text`() {
    read(projectXml(task(1, "a lot"), task(2, "8")))
    assertNull(effort(1))
    assertEquals("a lot", customValue(1)?.toString())
    assertEquals(0, BigDecimal("8").compareTo(effort(2)))
    assertNull(customValue(2), "a value which is taken leaves no text behind")
    assertTrue(save(project).contains("a lot"))
  }

  @Test
  fun `an effort with a decimal comma is not guessed and stays as text`() {
    read(projectXml(task(1, "16,5")))
    assertNull(effort(1))
    assertEquals("16,5", customValue(1)?.toString())
  }

  @Test
  fun `the importer keeps an unreadable effort as text as well`() {
    val imported = XmlProjectImporter().import(projectXml(task(1, "a lot")))
    assertNull(imported.taskManager.getTask(1).effort)
    val definition = imported.taskCustomColumnManager.definitions.single { it.id == "effort_hours" }
    assertEquals("a lot", imported.taskManager.getTask(1).customValues.getValue(definition)?.toString())
  }

  @Test
  fun `the effort of a summary task and of a milestone is taken as well`() {
    read(projectXml(
      task(1, "40", children = task(2, "16")),
      task(3, "2", attrs = """ meeting="true" duration="0"""")))
    assertEquals(0, BigDecimal("40").compareTo(effort(1)))
    assertEquals(0, BigDecimal("16").compareTo(effort(2)))
    assertEquals(0, BigDecimal("2").compareTo(effort(3)))
    assertFalse(save(project).contains("effort_hours"))
  }

  @Test
  fun `an effort attribute already in the file wins over the text of the fork, which stays`() {
    read(projectXml(task(1, "16", attrs = """ effort-hours="8"""")))
    assertEquals(0, BigDecimal("8").compareTo(effort(1)))
    assertEquals("16", customValue(1)?.toString())
  }

  @Test
  fun `the effort of the fork is found by its name as well, as the fork looked it up`() {
    read(projectXml(task(1, "16", propertyId = "tpc3"), propertyId = "tpc3"))
    assertEquals(0, BigDecimal("16").compareTo(effort(1)))
    assertFalse(save(project).contains("tpc3"))
  }

  private fun efforts(tasks: Array<Task>): Map<Int, String> =
    tasks.mapNotNull { task -> task.effort?.let { task.taskID to it.stripTrailingZeros().toPlainString() } }.toMap()

  private fun effort(id: Int): BigDecimal? = project.taskManager.getTask(id).effort

  private fun customValue(id: Int): Any? =
    project.taskCustomColumnManager.definitions.singleOrNull { it.id == "effort_hours" }
      ?.let { project.taskManager.getTask(id).customValues.getValue(it) }

  private fun save(project: IGanttProject): String {
    val out = ByteArrayOutputStream()
    GanttXMLSaver(project).save(out)
    return out.toString(Charsets.UTF_8)
  }

  private fun task(id: Int, effort: String?, attrs: String = "", children: String = "",
                   propertyId: String = "effort_hours"): String {
    val prop = effort?.let { """<customproperty taskproperty-id="$propertyId" value="$it"/>""" } ?: ""
    val duration = if (attrs.contains("duration=")) "" else """ duration="1""""
    return """<task id="$id" uid="u$id" name="T$id" start="2026-11-09"$duration$attrs>$prop$children</task>"""
  }

  /** A project with the effort definition of the fork and no resource properties of the fork. */
  private fun projectXml(vararg tasks: String, propertyId: String = "effort_hours"): String = """
      <project name="p">
        <tasks>
          <taskproperties>
            <taskproperty id="tpd3" name="name" type="default" valuetype="text"/>
            <taskproperty id="$propertyId" name="effort_hours" type="custom" valuetype="double"/>
          </taskproperties>
          ${tasks.joinToString("\n")}
        </tasks>
        <resources>
          <resource id="0" name="R" function="Default:0" contacts="" phone="" hours-per-day="8"/>
        </resources>
        <allocations>
          <allocation task-id="1" resource-id="0" function="Default:0" responsible="true" load="100.0"/>
        </allocations>
      </project>""".trimIndent()

  private fun read(xml: String) {
    val facade = testUiFacade()
    val parserFactory = object : ParserFactory {
      override fun newParser() = GanttXMLOpen(PrjInfos(), project.taskManager, facade)
      override fun newSaver() = throw UnsupportedOperationException()
    }
    val physical = createNiceMock<Document>(Document::class.java)
    expect(physical.inputStream).andStubAnswer { ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)) }
    replay(physical)
    testProxyDocument(physical, project, facade, parserFactory).read()
  }
}
