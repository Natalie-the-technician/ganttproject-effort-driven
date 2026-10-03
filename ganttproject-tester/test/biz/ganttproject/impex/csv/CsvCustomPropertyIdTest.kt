/*
 * Copyright (c) 2026 BarD Software s.r.o.
 *
 * This file is part of GanttProject, an open-source project management tool.
 *
 * GanttProject is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 * GanttProject is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with GanttProject.  If not, see <http://www.gnu.org/licenses/>.
 */
package biz.ganttproject.impex.csv

import biz.ganttproject.customproperty.CustomColumnsManager
import biz.ganttproject.customproperty.CustomPropertyClass
import biz.ganttproject.customproperty.CustomPropertyDefinition
import biz.ganttproject.customproperty.CustomPropertyManager
import com.google.common.base.Charsets
import junit.framework.TestCase
import org.apache.commons.csv.CSVFormat
import java.io.ByteArrayInputStream

/**
 * MEASUREMENT, NOT A FIX.
 *
 * CSVImport.kt:144 looks an existing custom property definition up by the column header:
 *
 *     mgr.getCustomPropertyDefinition(fieldName)
 *
 * but the parameter of that method is the definition's *id* ("Get a definition by its id",
 * CustomProperty.kt:177; the implementation is a lookup in mapIdCustomColum,
 * CustomColumnsManager.kt:97). An id is "normally tpc<Num>" (CustomProperty.kt:57); the column
 * header is the property's *name*. The lookup can therefore only succeed where id == name, which
 * holds for the definitions CSVImport.kt:146 creates itself -- it passes fieldName as both id and
 * name -- and in CustomPropertyExportImportTest, whose fixture is createDefinition("F1", "int",
 * "F1", "0").
 *
 * These four cases measure what that costs. They are written to be red where the behaviour is
 * wrong, so that the failure message states the damage.
 */
class CsvCustomPropertyIdTest : TestCase() {

  private fun readHeaderAndDataRow(header: String, data: String): Pair<SpreadsheetRecord, SpreadsheetRecord> {
    val bytes = "$header\n$data\n".toByteArray(Charsets.UTF_8)
    val it = CsvReaderImpl(ByteArrayInputStream(bytes), CSVFormat.DEFAULT).iterator()
    return it.next() to it.next()
  }

  private fun describe(mgr: CustomPropertyManager) =
    mgr.definitions.joinToString(" | ") { "id=${it.id} name=${it.name} class=${it.propertyClass}" }

  private fun readInto(mgr: CustomPropertyManager, header: String, data: String)
      : Map<CustomPropertyDefinition, String?> {
    val (headerRecord, dataRecord) = readHeaderAndDataRow(header, data)
    val written = LinkedHashMap<CustomPropertyDefinition, String?>()
    readCustomProperties(headerRecord, listOf(header), dataRecord, mgr) { def, value ->
      written[def] = value
    }
    return written
  }

  /**
   * B1: the project already has the column the CSV file is about. It was created the normal way --
   * by the user, or loaded from a project file -- so its id is "tpc0" and its name is "Department".
   */
  fun testExistingColumnIsFoundByItsName() {
    val mgr = CustomColumnsManager()
    val existing = mgr.createDefinition(CustomPropertyClass.TEXT, "Department", null)
    println("B1 before: ${describe(mgr)}")

    val written = readInto(mgr, "Department", "Sales")

    println("B1 after:  ${describe(mgr)}")
    println("B1 written into: ${written.keys.joinToString { "id=${it.id} name=${it.name}" }}")
    assertEquals(
      "The CSV import created a second custom column with the same name. Columns now: ${describe(mgr)}",
      1, mgr.definitions.size
    )
    assertEquals(
      "The CSV value was not written into the column the project already had",
      existing, written.keys.single()
    )
  }

  /**
   * B2: the other direction. A column header which happens to spell an existing definition's id is
   * accepted as that definition, whatever that definition is named.
   */
  fun testColumnHeaderSpellingAnIdIsNotTheSameColumn() {
    val mgr = CustomColumnsManager()
    val department = mgr.createDefinition(CustomPropertyClass.TEXT, "Department", null)
    println("B2 the project's column: id=${department.id} name=${department.name}")

    val written = readInto(mgr, department.id, "Sales")

    println("B2 after:  ${describe(mgr)}")
    println("B2 written into: ${written.keys.joinToString { "id=${it.id} name=${it.name}" }}")
    assertFalse(
      "A CSV column headed '${department.id}' was written into the project's column named " +
        "'${department.name}'",
      written.keys.contains(department)
    )
  }

  /**
   * B3: on the import path the buffer project is merged into the target project by
   * CustomColumnsManager.importData, which matches by NAME. Does that mask B1? Not when the types
   * differ -- and a CSV column is always read as TEXT (CsvRecordImpl.getType).
   */
  fun testProjectLevelMergeDoesNotMaskItWhenTheTypeDiffers() {
    val target = CustomColumnsManager()
    target.createDefinition(CustomPropertyClass.DOUBLE, "Budget", null)

    val buffer = CustomColumnsManager()
    buffer.createDefinition("Budget", CustomPropertyClass.TEXT.iD, "Budget", null)

    println("B3 target before: ${describe(target)}")
    println("B3 buffer:        ${describe(buffer)}")
    target.importData(buffer)
    println("B3 target after:  ${describe(target)}")

    assertEquals(
      "The target project has two custom columns with the same name after the import: ${describe(target)}",
      1, target.definitions.size
    )
  }

  /**
   * B3b: and where the types do agree, the name-based merge at project level does mask it. This one
   * is expected to be green -- it is the reason the defect is invisible on the ordinary import path.
   */
  fun testProjectLevelMergeMasksItWhenTheTypeAgrees() {
    val target = CustomColumnsManager()
    target.createDefinition(CustomPropertyClass.TEXT, "Department", null)

    val buffer = CustomColumnsManager()
    buffer.createDefinition("Department", CustomPropertyClass.TEXT.iD, "Department", null)

    target.importData(buffer)
    println("B3b target after: ${describe(target)}")
    assertEquals("B3b: ${describe(target)}", 1, target.definitions.size)
  }
}
