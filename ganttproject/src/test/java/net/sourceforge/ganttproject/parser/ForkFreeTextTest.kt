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
package net.sourceforge.ganttproject.parser

import biz.ganttproject.core.io.parseXmlProject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** What turning the custom properties of the effort driven fork into attributes reports, and what it leaves alone. */
class ForkFreeTextTest {
  private fun project(props: String, definitions: String = """
      <custom-property-definition id="hours_schedule" name="Hours schedule" type="text"/>
      <custom-property-definition id="utilisation_percent" name="Utilisation (%)" type="int"/>""") = parseXmlProject("""
    <project name="p">
      <resources>
        $definitions
        <custom-property-definition id="tpc0" name="Team" type="text"/>
        <resource id="0" name="R" function="Default:0" contacts="" phone="">$props</resource>
      </resources>
    </project>""".trimIndent())

  @Test
  fun `every value which is kept as text is reported, and only those`() {
    val reported = mutableListOf<String>()
    val xml = project("""<custom-property definition-id="hours_schedule" value="2027-04-01: x"/>""" +
      """<custom-property definition-id="utilisation_percent" value="70"/>""")
    xml.migrateForkFreeText { name, property, value -> reported.add("$name $property $value") }
    assertEquals(listOf("R hours_schedule 2027-04-01: x"), reported)
    assertEquals(70, xml.resources.resources.single().utilization)
  }

  @Test
  fun `a definition which keeps a value becomes text, one which keeps none is dropped, others stay`() {
    val xml = project("""<custom-property definition-id="utilisation_percent" value="much"/>""" +
      """<custom-property definition-id="hours_schedule" value="2027-04-01: 6"/>""" +
      """<custom-property definition-id="tpc0" value="Blue"/>""")
    xml.migrateForkFreeText()
    assertEquals(listOf("utilisation_percent text", "tpc0 text"), xml.resources.customProperties.map { "${it.id} ${it.type}" })
    assertEquals(listOf("utilisation_percent much", "tpc0 Blue"), xml.resources.resources.single().props.map { "${it.definitionId} ${it.value}" })
  }

  @Test
  fun `a file without the properties of the fork is left as it is`() {
    val xml = project("""<custom-property definition-id="tpc0" value="Blue"/>""", definitions = "")
    xml.migrateForkFreeText { _, _, _ -> throw AssertionError("nothing to report") }
    assertEquals(listOf("tpc0"), xml.resources.customProperties.map { it.id })
    assertEquals(1, xml.resources.resources.single().props.size)
  }
}
