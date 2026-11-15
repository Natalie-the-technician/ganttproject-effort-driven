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
import biz.ganttproject.core.model.task.CapacityChange
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.core.time.CalendarFactory.LocaleApi
import biz.ganttproject.core.time.CalendarFactory.setLocaleApi
import net.sourceforge.ganttproject.GanttProjectImpl
import net.sourceforge.ganttproject.IGanttProject
import net.sourceforge.ganttproject.PrjInfos
import net.sourceforge.ganttproject.io.GanttXMLOpen
import net.sourceforge.ganttproject.io.GanttXMLSaver
import net.sourceforge.ganttproject.parser.ParserFactory
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.task.algorithm.availableHoursPerDay
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
import java.time.LocalDate
import java.util.Locale

/**
 * Files of the effort driven fork kept the hours per day, the utilization and the changes of the working hours of a
 * resource as custom properties `hours_per_day`, `utilisation_percent` and `hours_schedule`. Reading such a file has to
 * give the resource the same hours and the same utilization the fork worked with, through the desktop reader and
 * through the importer alike, and saving it has to write only the attributes.
 */
class ForkFreeTextReadTest {
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

  @Test
  fun `a file written by the fork opens with the hours and the utilization of the fork`() {
    read(forkFile())
    val chris = project.humanResourceManager.getById(2)
    assertEquals(80, chris.utilization)
    assertEquals(0, BigDecimal("6").compareTo(chris.hoursPerDay))
    // The fork: hours_per_day x utilisation / 100 x load / 100 = 6 x 0.8 x 1 = 4.8 hours a day on task 4
    val assignment = project.taskManager.getTask(4).assignments.single { it.resource == chris }
    assertEquals(0, BigDecimal("4.8").compareTo(availableHoursPerDay(assignment)), "${availableHoursPerDay(assignment)}")
    val anna = project.humanResourceManager.getById(0)
    assertNull(anna.utilization, "100 percent is no utilization")
    assertEquals(0, BigDecimal("8").compareTo(anna.hoursPerDay))
  }

  @Test
  fun `a file written by the fork keeps no custom property for what became an attribute`() {
    read(forkFile())
    val definitions = project.resourceCustomPropertyManager.definitions.map { it.id }
    assertFalse("utilisation_percent" in definitions, "$definitions")
    assertFalse("hours_per_day" in definitions, "$definitions")
  }

  @Test
  fun `the importer reads a file written by the fork the same way`() {
    val imported = XmlProjectImporter().import(forkFile())
    val chris = imported.humanResourceManager.getById(2)
    assertEquals(80, chris.utilization)
    assertEquals(0, BigDecimal("6").compareTo(chris.hoursPerDay))
    assertFalse("utilisation_percent" in imported.resourceCustomPropertyManager.definitions.map { it.id })
  }

  @Test
  fun `saving a file written by the fork writes only the attributes`() {
    read(forkFile())
    val saved = save(project)
    assertTrue(saved.contains("""utilization="80""""), saved)
    assertTrue(saved.contains("hours-per-day=\"6"), saved)
    assertFalse(saved.contains("utilisation_percent"), saved)
    assertFalse(saved.contains("hours_per_day"), saved)
  }

  @Test
  fun `a schedule of the fork becomes changes of the working hours`() {
    read(projectXml(hoursSchedule = "2027-04-01: 6; 2027-10-01=7,5", utilisation = "50"))
    val resource = resource()
    assertEquals(listOf(change("2027-04-01", "6"), change("2027-10-01", "7.5")), resource.hoursSchedule)
    assertEquals(50, resource.utilization)
    val saved = save(project)
    assertTrue(saved.contains("""<hours-change date="2027-04-01" hours="6"/>"""), saved)
    assertFalse(saved.contains("hours_schedule"), saved)
  }

  @Test
  fun `a schedule of the fork is read by the importer as well`() {
    val imported = XmlProjectImporter().import(projectXml(hoursSchedule = "2027-04-01: 6"))
    assertEquals(listOf(change("2027-04-01", "6")), imported.humanResourceManager.getById(0).hoursSchedule)
  }

  @Test
  fun `a utilization of zero in the fork stays full, since the fork read it as 100`() {
    read(projectXml(utilisation = "0", hoursPerDay = "4.0"))
    val resource = resource()
    assertNull(resource.utilization, "0 means no hours now, the fork worked with 100")
    val assignment = project.taskManager.getTask(1).assignments.single()
    assertEquals(0, BigDecimal("4").compareTo(availableHoursPerDay(assignment)), "${availableHoursPerDay(assignment)}")
    assertFalse(save(project).contains("utilisation_percent"))
  }

  @Test
  fun `a utilization out of range in the fork stays full`() {
    read(projectXml(utilisation = "120"))
    assertNull(resource().utilization)
    assertFalse(save(project).contains("utilisation_percent"))
  }

  @Test
  fun `hours per day of zero in the fork stay the default, since the fork read them as 8`() {
    read(projectXml(hoursPerDay = "0.0"))
    assertNull(resource().hoursPerDay)
    assertFalse(save(project).contains("hours_per_day"))
  }

  @Test
  fun `missing and empty values of the fork leave the resource as it is and are dropped`() {
    read(projectXml(hoursSchedule = "", utilisation = "", hoursPerDay = ""))
    val resource = resource()
    assertNull(resource.utilization)
    assertNull(resource.hoursPerDay)
    assertEquals(emptyList<CapacityChange>(), resource.hoursSchedule)
    val saved = save(project)
    assertFalse(saved.contains("hours_schedule") || saved.contains("utilisation_percent") || saved.contains("hours_per_day"), saved)
  }

  @Test
  fun `a resource without the values of the fork next to one with them keeps nothing`() {
    read(projectXml(utilisation = "80", secondResource = true))
    assertEquals(80, resource().utilization)
    assertNull(project.humanResourceManager.getById(1).utilization)
  }

  @Test
  fun `an unreadable schedule is not guessed and stays as text`() {
    read(projectXml(hoursSchedule = "2027-04-01: 6; tomorrow: 8"))
    val resource = resource()
    assertEquals(emptyList<CapacityChange>(), resource.hoursSchedule, "no entry of a faulty schedule is taken")
    assertEquals("2027-04-01: 6; tomorrow: 8", customValue(resource, "hours_schedule"))
    assertTrue(save(project).contains("2027-04-01: 6; tomorrow: 8"))
  }

  @Test
  fun `an unreadable utilization is not guessed and stays as text`() {
    read(projectXml(utilisation = "62.5"))
    val resource = resource()
    assertNull(resource.utilization)
    assertEquals("62.5", customValue(resource, "utilisation_percent")?.toString())
    assertTrue(save(project).contains("62.5"))
  }

  @Test
  fun `unreadable hours per day are not guessed and stay as text`() {
    read(projectXml(hoursPerDay = "eight"))
    val resource = resource()
    assertNull(resource.hoursPerDay)
    assertEquals("eight", customValue(resource, "hours_per_day")?.toString())
    assertTrue(save(project).contains("eight"))
  }

  @Test
  fun `an attribute already in the file wins over the text of the fork, which stays`() {
    read(projectXml(utilisation = "80", resourceAttrs = """ utilization="40""""))
    val resource = resource()
    assertEquals(40, resource.utilization)
    assertEquals("80", customValue(resource, "utilisation_percent")?.toString())
  }

  @Test
  fun `a property of the fork is found by its name as well, as the fork looked it up`() {
    read(projectXml(utilisation = "80", utilisationId = "tpc7"))
    assertEquals(80, resource().utilization)
    assertFalse(save(project).contains("tpc7"))
  }

  private fun resource(): HumanResource = project.humanResourceManager.getById(0)

  private fun customValue(resource: HumanResource, id: String): Any? =
    project.resourceCustomPropertyManager.definitions.single { it.id == id }.let { resource.getCustomField(it) }

  private fun change(date: String, hours: String) = CapacityChange(LocalDate.parse(date), BigDecimal(hours))

  private fun save(project: IGanttProject): String {
    val out = ByteArrayOutputStream()
    GanttXMLSaver(project).save(out)
    return out.toString(Charsets.UTF_8)
  }

  private fun projectXml(hoursSchedule: String? = null, utilisation: String? = null, hoursPerDay: String? = null,
                         resourceAttrs: String = "", utilisationId: String = "utilisation_percent",
                         secondResource: Boolean = false): String {
    val props = listOfNotNull(
      hoursPerDay?.let { """<custom-property definition-id="hours_per_day" value="$it"/>""" },
      hoursSchedule?.let { """<custom-property definition-id="hours_schedule" value="$it"/>""" },
      utilisation?.let { """<custom-property definition-id="$utilisationId" value="$it"/>""" }
    ).joinToString("")
    val second = if (secondResource) """<resource id="1" name="S" function="Default:0" contacts="" phone=""/>""" else ""
    return """
      <project name="p">
        <tasks>
          <task id="1" uid="u1" name="T" start="2026-11-09" duration="1"/>
        </tasks>
        <resources>
          <custom-property-definition id="hours_per_day" name="Hours per day" type="double"/>
          <custom-property-definition id="hours_schedule" name="Hours schedule" type="text"/>
          <custom-property-definition id="$utilisationId" name="utilisation_percent" type="int"/>
          <resource id="0" name="R" function="Default:0" contacts="" phone=""$resourceAttrs>$props</resource>
          $second
        </resources>
        <allocations>
          <allocation task-id="1" resource-id="0" function="Default:0" responsible="true" load="100.0"/>
        </allocations>
      </project>""".trimIndent()
  }

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
