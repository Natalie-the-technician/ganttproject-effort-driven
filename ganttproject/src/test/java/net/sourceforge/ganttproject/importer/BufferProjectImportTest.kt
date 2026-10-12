/*
NEW FILE IN THIS FORK -- not present in the original GanttProject.

This file is part of GanttProject, an opensource project management tool.
Licensed under the GNU General Public License, version 3 or later.
*/
package net.sourceforge.ganttproject.importer

import biz.ganttproject.core.table.ColumnList
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.customproperty.CustomPropertyClass
import biz.ganttproject.lib.fx.BuiltinColumns
import biz.ganttproject.lib.fx.ColumnListImpl
import javafx.scene.control.TreeTableColumn
import net.sourceforge.ganttproject.GanttProjectImpl
import net.sourceforge.ganttproject.export.ConsoleUIFacade
import net.sourceforge.ganttproject.resource.HumanResourceMerger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.util.Locale

/**
 * Reproduces the gap measured in `2026-10-08-import-spalten-sichtbarkeit.md`: importing a `.gan`
 * file whose own custom column happens to collide, by `id`, with the target project's own custom
 * column (both created through the UI, where the id is a mere per-project counter starting at
 * "tpc0") must leave BOTH columns visible afterwards -- not just the target's.
 */
class BufferProjectImportTest {
  init {
    object : CalendarFactory() {
      init {
        setLocaleApi(object : CalendarFactory.LocaleApi {
          override fun getLocale(): Locale = Locale.GERMANY
          override fun getShortDateFormat(): DateFormat = DateFormat.getDateInstance(DateFormat.SHORT, Locale.GERMANY)
        })
      }
    }
  }

  private val builtinColumns = BuiltinColumns(isZeroWidth = { false }, allColumns = { emptyList() })

  @Test
  fun `import keeps both the target's own column and a colliding source column visible`() {
    val target = GanttProjectImpl()
    val targetDef = target.taskCustomColumnManager.createDefinition(CustomPropertyClass.TEXT, "Zielspalte", null)

    val buffer = BufferProject(target, ConsoleUIFacade(null))
    // Two independently created .gan files both assign their own custom column the id "tpc0" --
    // CustomColumnsManager.createId() starts every project's counter at zero. Forcing the same id
    // here reproduces that collision deterministically.
    val sourceDef = buffer.taskCustomColumnManager.createDefinition(
      targetDef.id, CustomPropertyClass.TEXT.iD, "Quellspalte", null
    )
    buffer.visibleFields.add(sourceDef.id, -1, -1)

    val targetColumnStub = ColumnList.ColumnStub(targetDef.id, "Zielspalte", true, 0, 75)
    val targetFxColumn = TreeTableColumn<Any, Any>().apply {
      isVisible = true
      userData = targetColumnStub
    }
    val taskColumnList = ColumnListImpl(
      columnList = mutableListOf(targetColumnStub),
      customPropertyManager = target.taskCustomColumnManager,
      tableColumns = { listOf(targetFxColumn) },
      builtinColumns = builtinColumns
    )
    val resourceColumnList = ColumnListImpl(
      columnList = mutableListOf(),
      customPropertyManager = target.resourceCustomPropertyManager,
      tableColumns = { emptyList() },
      builtinColumns = builtinColumns
    )

    importBufferProject(
      targetProject = target,
      bufferProject = buffer,
      importApi = ImportBufferProjectApi(
        taskColumnList = { taskColumnList },
        resourceColumnList = { resourceColumnList },
        refresh = {}
      ),
      mergeOption = HumanResourceMerger.MergeResourcesOption(),
      importCalendarOption = null
    )

    val visibleCustomColumnNames = taskColumnList.columns().filter { it.isVisible }
      .mapNotNull { target.taskCustomColumnManager.getCustomPropertyDefinition(it.id)?.name }
      .toSet()
    assertEquals(
      setOf("Zielspalte", "Quellspalte"), visibleCustomColumnNames,
      "Nach dem Import muessen BEIDE eigenen Spalten sichtbar sein, trotz kollidierender id"
    )
  }
}
