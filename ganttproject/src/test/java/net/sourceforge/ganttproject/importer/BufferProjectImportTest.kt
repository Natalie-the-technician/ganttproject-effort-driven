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
import org.junit.jupiter.api.Assertions.assertFalse
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

  /**
   * Fall C aus `2026-10-08-import-spalten-sichtbarkeit.md`: eine eigene Spalte, die in der QUELLE
   * unsichtbar ist, darf der Import nicht sichtbar machen. `ViewSaver.writeColumns` schreibt nur
   * sichtbare Felder -- eine unsichtbare Spalte hat deshalb ueberhaupt keinen Eintrag in
   * `bufferProject.visibleFields`. Gegenprobe gegen eine zu grobe Loesung ("einfach alles sichtbar
   * machen"): die Spaltendefinition muss ankommen (Werte bleiben erhalten, Befund aus dem
   * Ursprungsbericht), aber NICHT in der sichtbaren Liste landen.
   */
  @Test
  fun `import does not turn on a custom column that was hidden in the source`() {
    val target = GanttProjectImpl()
    val targetDef = target.taskCustomColumnManager.createDefinition(CustomPropertyClass.TEXT, "Zielspalte", null)

    val buffer = BufferProject(target, ConsoleUIFacade(null))
    // Non-colliding id (like "tpc9" in the measured report) -- the point here is visibility, not
    // the id collision from the test above.
    val hiddenSourceDef = buffer.taskCustomColumnManager.createDefinition(
      "tpc9", CustomPropertyClass.TEXT.iD, "VerborgeneQuellspalte", null
    )
    // Deliberately NOT added to buffer.visibleFields -- that is what "hidden in the source" means.

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

    val importedDef = target.taskCustomColumnManager.definitions.find { it.name == "VerborgeneQuellspalte" }
    assertEquals(
      "VerborgeneQuellspalte", importedDef?.name,
      "Die Spaltendefinition muss ankommen -- das ist kein Datenverlust-Fall, siehe Befund im Ursprungsbericht"
    )
    val visibleIds = taskColumnList.columns().filter { it.isVisible }.map { it.id }.toSet()
    assertFalse(
      visibleIds.contains(importedDef!!.id),
      "Eine in der Quelle unsichtbare Spalte darf der Import nicht einschalten: sichtbar sind $visibleIds"
    )
  }

  /**
   * Der vierte Codepfad aus `CustomColumnsManager.kt` (`findByName`, siehe
   * "Was ich NICHT geprueft habe" im Ursprungsbericht): die Quellspalte kollidiert NICHT in der id
   * (hier "tpc5", frei erfunden), trifft aber per Name+Typ auf eine schon vorhandene Zieldefinition.
   * `importData` legt dafuer KEINE neue Spalte an, sondern bildet die Quelle auf die VORHANDENE
   * Zieldefinition ab -- deren id kann von der Quell-id abweichen. Ohne die Uebersetzung in dieser
   * Aenderung wuerde die unuebersetzte Quell-id ("tpc5") im Ziel auf keine Definition treffen, am
   * Filter in TreeTableColumns.kt haengen bleiben und verschwinden; weil sie der EINZIGE Eintrag in
   * dieser Testaufstellung ist, griffe sogar der "keine Spalte sichtbar" - Rueckfall auf die
   * eingebauten Spalten (ColumnListImpl.importData, Zeile ~100).
   */
  @Test
  fun `import matches a non-colliding same-name source column to the existing target definition`() {
    val target = GanttProjectImpl()
    val targetDef = target.taskCustomColumnManager.createDefinition(CustomPropertyClass.TEXT, "Spalte", null)

    val buffer = BufferProject(target, ConsoleUIFacade(null))
    val sourceDef = buffer.taskCustomColumnManager.createDefinition(
      "tpc5", CustomPropertyClass.TEXT.iD, "Spalte", null
    )
    buffer.visibleFields.add(sourceDef.id, -1, -1)

    // The target's own column is not currently shown -- empty storage, no UI column for it.
    val taskColumnList = ColumnListImpl(
      columnList = mutableListOf(),
      customPropertyManager = target.taskCustomColumnManager,
      tableColumns = { emptyList() },
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

    assertEquals(
      1, target.taskCustomColumnManager.definitions.size,
      "findByName muss die Quellspalte an die VORHANDENE Zieldefinition binden, nicht eine zweite anlegen"
    )
    val visibleIds = taskColumnList.columns().filter { it.isVisible }.map { it.id }.toSet()
    assertEquals(
      setOf(targetDef.id), visibleIds,
      "Die per Name gebundene Spalte muss unter der ZIEL-id sichtbar werden, nicht unter der unuebersetzten Quell-id 'tpc5'"
    )
  }
}
