/*
NEW FILE IN THIS FORK -- not present in the original GanttProject.

This file is part of GanttProject, an opensource project management tool.
Licensed under the GNU General Public License, version 3 or later.
*/
package biz.ganttproject.core.chart.scene.gantt

import biz.ganttproject.customproperty.CustomColumnsManager
import biz.ganttproject.customproperty.CustomPropertyClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Misst die Vermutung aus dem NACHTRAG zu `2026-10-12-import-spalten-sichtbarkeit-gebaut.md`
 * (hergeleitet in `2026-10-12-712-berechnete-spalten-kollision.md`, Z. 197-235, dort ausdruecklich
 * NICHT nachgestellt): wer `bufferProject.visibleFields` auf Ziel-Kennungen uebersetzt, muesse auch
 * die vier `taskLabelUp/Down/Left/Right`-Optionen (`TaskColumnEnumerationOption` in
 * `TaskLabelSceneBuilderApi.kt`) mituebersetzen, sonst verschwinde eine Spalte nach dem Import
 * lautlos aus der Chart-Beschriftung.
 *
 * Gemessener Befund: die Vermutung trifft auf DIESEN Bau NICHT zu, aus zwei Gruenden --
 *
 * 1. Diese vier Optionen gehoeren zum lebenden Chart-Renderer des ZIELPROJEKTS
 *    (`GanttChartSceneBuilder`, instanziiert ueber `input.getCustomPropertyManager()` = das
 *    Ziel-Projekt). `BufferProject` (die Quelle wahrend des Imports) baut nie einen eigenen
 *    `GanttChartSceneBuilder` auf -- es gibt auf der Quellseite also gar keinen taskLabel-Optionswert,
 *    der zu uebersetzen waere.
 * 2. `CustomColumnsManager.importData` vergibt nur der KOLLIDIERENDEN QUELLspalte eine neue id
 *    (`thisColumn.id = findById(thatColumn.id)?.let { createId() } ?: thatColumn.id`, siehe
 *    `CustomColumnsManager.kt:91`) -- die id einer bereits vorhandenen ZIELspalte wird dabei nie
 *    angefasst. Eine `taskLabelUp`-Auswahl, die vor dem Import auf die eigene Zielspalte zeigte,
 *    zeigt danach also immer noch auf dieselbe id.
 *
 * Dieser Test bildet genau das measured-Szenario nach: eine Kollision wie in Fall B des
 * Ursprungsberichts, mit `taskLabelUp` vorher auf die Zielspalte gesetzt. `reload()` -- das tut,
 * was `GanttChartSceneBuilder`s eigener `CustomPropertyListener` bei jedem `EVENT_REBUILD` von
 * `importData` auslöst -- baut nur die Werteliste neu auf; `DefaultEnumerationOption` haelt die
 * Auswahl selbst als reine Kennungs-Zeichenkette (`getValue()`/`setValue()`), die `reload()` nicht
 * antastet.
 */
class TaskColumnEnumerationOptionImportTest {
  @Test
  fun `a taskLabel option pointing at the target's own column survives a colliding import untranslated`() {
    val target = CustomColumnsManager()
    val targetDef = target.createDefinition(CustomPropertyClass.TEXT, "Zielspalte", null)

    val option = TaskColumnEnumerationOption("taskLabelUp", target.definitions)
    option.loadPersistentValue(targetDef.id)
    assertEquals(targetDef.id, option.selectedValue?.id, "Vorbedingung: die Auswahl zeigt auf die Zielspalte")

    // Zwei unabhaengig entstandene .gan-Dateien, beide mit eigener Spalte "tpc0" -- wie Fall B im
    // Ursprungsbericht. Die Quellspalte selbst hat (anders als die Zielspalte) in diesem Test gar
    // keine eigene taskLabel-Option -- BufferProject baut nie einen GanttChartSceneBuilder auf.
    val source = CustomColumnsManager()
    source.createDefinition(targetDef.id, CustomPropertyClass.TEXT.iD, "Quellspalte", null)

    target.importData(source)
    // Was GanttChartSceneBuilders eigener CustomPropertyListener bei jedem EVENT_REBUILD tut:
    option.reload(target.definitions)

    assertEquals(
      targetDef.id, option.selectedValue?.id,
      "Die Auswahl darf durch eine id-Kollision im Import nicht verschoben werden"
    )
    assertEquals("Zielspalte", target.getCustomPropertyDefinition(option.selectedValue!!.id)?.name)
  }
}
