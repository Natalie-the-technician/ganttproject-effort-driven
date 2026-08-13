/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The tree exists for one reason — keeping a saved file diffable against the
 * original — so that is what these tests pin down.
 */
class XmlTreeTest {

  private fun roundTrip(xml: String): String =
    XmlWriter.write(XmlParser.parse(xml.toByteArray()))

  @Test
  @DisplayName("attribute order survives, which the standard DOM serialiser does not manage")
  fun `attribute order is preserved`() {
    val xml = """<?xml version="1.0" encoding="UTF-8"?><project name="X" company="Y" webLink="Z"/>"""
    val out = roundTrip(xml)
    assertTrue(
      out.contains("""<project name="X" company="Y" webLink="Z"/>"""),
      "attributes were reordered: $out"
    )
  }

  @Test
  fun `updating an attribute keeps its position`() {
    val root = XmlParser.parse("""<project name="X" company="Y" webLink="Z"/>""".toByteArray())
    root.setAttr("company", "New")
    assertTrue(XmlWriter.write(root).contains("""name="X" company="New" webLink="Z""""))
  }

  @Test
  fun `a new attribute is appended rather than inserted`() {
    val root = XmlParser.parse("""<project name="X"/>""".toByteArray())
    root.setAttr("locale", "de")
    assertTrue(XmlWriter.write(root).contains("""<project name="X" locale="de"/>"""))
  }

  @Test
  fun `CDATA sections stay CDATA`() {
    val out = roundTrip("""<project><notes><![CDATA[a < b & c]]></notes></project>""")
    assertTrue(out.contains("<![CDATA[a < b & c]]>"), "CDATA was escaped away: $out")
  }

  @Test
  fun `text containing a CDATA terminator is split safely`() {
    val root = XmlElement("notes")
    root.children.add(XmlText("before ]]> after", cdata = true))
    val written = XmlWriter.write(root)
    // Re-reading must give the original text back, which is the only thing
    // that actually matters about the splitting trick.
    assertEquals("before ]]> after", XmlParser.parse(written.toByteArray()).textContent())
  }

  @Test
  fun `special characters in text are escaped and read back unchanged`() {
    val root = XmlElement("notes")
    root.children.add(XmlText("a < b & c > d"))
    val reparsed = XmlParser.parse(XmlWriter.write(root).toByteArray())
    assertEquals("a < b & c > d", reparsed.textContent())
  }

  @Test
  fun `quotes and newlines in attributes survive`() {
    val root = XmlElement("task")
    root.setAttr("name", "say \"hello\"\nand <goodbye> & go")
    val reparsed = XmlParser.parse(XmlWriter.write(root).toByteArray())
    assertEquals("say \"hello\"\nand <goodbye> & go", reparsed.attr("name"))
  }

  @Test
  fun `comments are preserved`() {
    val out = roundTrip("""<project><!-- keep me --><tasks/></project>""")
    assertTrue(out.contains("<!-- keep me -->"), "comment lost: $out")
  }

  @Test
  fun `empty elements are written self-closing`() {
    assertTrue(roundTrip("""<project><description></description></project>""")
      .contains("<description/>"))
  }

  @Test
  fun `indentation-only whitespace does not accumulate`() {
    val once = roundTrip("""<project>
        <tasks>
            <task id="1"/>
        </tasks>
    </project>""")
    assertEquals(once, roundTrip(once), "output must be stable across saves")
  }

  @Test
  fun `malformed XML throws`() {
    val error = runCatching { XmlParser.parse("<project><tasks>".toByteArray()) }.exceptionOrNull()
    assertTrue(error != null, "malformed input must not parse silently")
  }
}
