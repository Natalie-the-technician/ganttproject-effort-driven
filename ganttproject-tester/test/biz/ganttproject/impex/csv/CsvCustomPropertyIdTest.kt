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
import junit.framework.AssertionFailedError
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
 *
 * ---------------------------------------------------------------------------------------------
 * B1 AND B2 ARE PINNED AS EXPECTED-TO-FAIL. WHY, AND WHY NOT SILENTLY.
 *
 * THE DECISION: on 01.10.2026 Natalie decided to LEAVE CSVImport.kt:144 AS IT IS -- confirmed on
 * 08.10.2026, when she also decided that B1 and B2 are to be MARKED as expected-to-fail rather
 * than removed. They still record the open defect; they no longer count as a failure. The branch
 * `csv-typisierung-beheben` fixes B3 (the type guess) and deliberately does NOT fix line 144.
 *
 * WHAT THE PIN DOES: [pinnedUntil144IsFixed] runs the body, expects an `AssertionFailedError`, and
 * asserts its message against the wording measured on 08.10.2026. So the pin is bound to the
 * MESSAGE, not to "some failure":
 *
 *   - today, with line 144 unfixed, the body fails with exactly that message and the test is GREEN;
 *   - once line 144 is fixed, the body no longer throws, and the pin fails LOUDLY with its own
 *     message telling the reader to remove it. An expected-to-fail test that goes quiet when the
 *     defect is gone would be worth nothing;
 *   - if the body fails for a DIFFERENT reason -- changed fixture, changed message, a thrown
 *     exception that is not an assertion -- the pin does not swallow it: the message comparison
 *     fails (printing the pinned and the actual wording side by side), or the exception passes
 *     straight through.
 *
 * WHY NOT `@Disabled`, `@Ignore`, `@ExpectedToFail` OR `assumeTrue` -- measured, not assumed:
 *
 *   - `junit-pioneer`, which carries `@ExpectedToFail`, is NOT on the classpath. Measured
 *     08.10.2026: `grep -rn pioneer` over every `*.gradle`, `*.kts`, `*.toml` and `*.properties`
 *     in the tree gives zero hits; `ganttproject-tester/build.gradle` has junit 4.13.2, the
 *     Jupiter API, the Jupiter engine and the Vintage engine, and nothing else.
 *   - `@Disabled` (Jupiter) and `@Ignore` (JUnit 4) do not apply to this class at all: it extends
 *     `junit.framework.TestCase`, i.e. JUnit 3, and runs through the Vintage engine's
 *     `JUnit38ClassRunner`. They are also both SILENT, which is the thing to avoid.
 *   - `assumeTrue` was tried in this fork and REVERTED, for a reason that applies verbatim here:
 *     on a JUnit 3 `TestCase` the Vintage runner reports `AssumptionViolatedException` as an
 *     ERROR, not as a skip -- measured on a Linux VM on 20.08.2026, `tests=4 failures=4
 *     errors=0 skipped=0` plus `BUILD FAILED`. See `TokenEncryptionTest` and FORK.md
 *     ("`assumeTrue` was tried here and reverted").
 *
 * The house convention this follows is `TokenEncryptionTest`'s: where a test cannot assert the
 * right thing, assert the WRONG thing it can see, positively and by its wording, so that the day
 * the behaviour changes the test says so out loud.
 *
 * TO REMOVE THE PIN, once CSVImport.kt:144 looks the definition up by its name: unwrap the two
 * bodies out of `pinnedUntil144IsFixed`, delete the helper, and delete this block.
 * ---------------------------------------------------------------------------------------------
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
   * The expected-to-fail pin. See the block at the top of this file for the decision behind it and
   * for why no annotation is used. Three ways out, all of them loud except the first:
   *
   *   1. the body fails with exactly [expectedMessage] -- line 144 is still as it was, test green;
   *   2. the body does not fail -- line 144 was fixed; fails here, naming the pin to be removed;
   *   3. the body fails with another message, or throws something that is not an assertion failure
   *      -- fails here with both wordings side by side, or the exception is passed straight on.
   */
  private fun pinnedUntil144IsFixed(pin: String, expectedMessage: String, body: () -> Unit) {
    val failure = try {
      body()
      null
    } catch (e: AssertionFailedError) {
      e
    }
    if (failure == null) {
      fail(
        "$pin is pinned as expected-to-fail, and it PASSED: CSVImport.kt:144 appears to be fixed. " +
          "Remove this pin -- unwrap the body out of pinnedUntil144IsFixed -- and let the " +
          "assertions stand on their own."
      )
    }
    assertEquals(
      "$pin is pinned to the failure of CSVImport.kt:144, but it failed for a DIFFERENT reason. " +
        "Pinned wording (expected) against what really happened (actual):",
      expectedMessage, failure!!.message
    )
  }

  /**
   * B1: the project already has the column the CSV file is about. It was created the normal way --
   * by the user, or loaded from a project file -- so its id is "tpc0" and its name is "Department".
   *
   * PINNED as expected-to-fail; the body below is unchanged and is what states the damage.
   */
  fun testExistingColumnIsFoundByItsName() = pinnedUntil144IsFixed(
    "B1",
    "The CSV import created a second custom column with the same name. Columns now: " +
      "id=tpc0 name=Department class=text | id=Department name=Department class=text " +
      "expected:<1> but was:<2>"
  ) {
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
   *
   * PINNED as expected-to-fail; the body below is unchanged and is what states the damage.
   */
  fun testColumnHeaderSpellingAnIdIsNotTheSameColumn() = pinnedUntil144IsFixed(
    "B2",
    "A CSV column headed 'tpc0' was written into the project's column named 'Department'"
  ) {
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
