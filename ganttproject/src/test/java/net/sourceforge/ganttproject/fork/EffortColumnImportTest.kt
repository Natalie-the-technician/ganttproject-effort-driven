/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.

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
package net.sourceforge.ganttproject.fork

import biz.ganttproject.customproperty.CustomColumnsManager
import biz.ganttproject.customproperty.CustomPropertyClass
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties.TASK_EFFORT_HOURS
import net.sourceforge.ganttproject.task.algorithm.findEffortDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Importing a project into a running one must not detach the effort column.
 *
 * WHAT THIS IS ABOUT. `CustomColumnsManager.importData` matched the target column by its *display
 * name*. For every column of the original program that is harmless, because there every column name
 * was typed by the user and is therefore the same string in every language. This fork is the first
 * to give a column a FIXED id with a TRANSLATED display name
 * ([EffortDrivenProperties.findOrCreateTaskEffort], the name coming out of the fork's text bundle) —
 * and that makes the name a poor identity: "Effort (h)" does not find "Aufwand (Std.)".
 *
 * WHAT WENT WRONG. The name lookup failed, so the import created a SECOND column; and because the
 * id `effort_hours` was already taken, the new one got a generated id (`tpc0`). The imported figures
 * were then plainly visible in a column of their own — and completely inert, because the scheduling
 * looks the column up by its id ([findEffortDefinition]) and kept finding the old, empty one. The
 * one feature this fork exists for was switched off by an import, without a word of warning.
 *
 * WHY EVERY TEST HERE NAMES ITS LANGUAGES. The defect only appears ACROSS a language boundary.
 * `gleiche sprache auf beiden seiten` is the positive control: with one language on both sides the
 * name matches and the import was always correct. A test that did not vary the language would stay
 * green on the broken code and prove nothing.
 */
class EffortColumnImportTest {

  /**
   * The case that was broken: the file comes out of an English installation, the import happens in
   * a German one.
   */
  @Test
  fun `aufwandsspalte ueber die sprachgrenze importiert`() {
    val source = managerWithEffortColumn(Locale.ENGLISH)
    val target = managerWithEffortColumn(Locale.GERMAN)
    assertNotEquals(source.definitions.single().name, target.definitions.single().name,
      "Vorbedingung: die beiden Sprachen muessen der Spalte wirklich verschiedene Namen geben")

    val thatColumn = source.definitions.single()
    val mapping = target.importData(source)

    assertEquals(1, target.definitions.size,
      "der Import darf keine zweite Aufwandsspalte anlegen, er muss die vorhandene wiederfinden")
    assertEquals(TASK_EFFORT_HOURS, target.definitions.single().id,
      "die Kennung der Aufwandsspalte darf nicht durch eine erfundene ersetzt werden")
    assertSame(target.findEffortDefinition(TASK_EFFORT_HOURS), mapping[thatColumn],
      "die importierten Zahlen muessen in genau der Spalte landen, die die Terminrechnung liest")
    assertEquals(effortLabelIn(Locale.GERMAN), target.definitions.single().name,
      "der Anzeigename der laufenden Sitzung bleibt stehen, die Sprache des Imports gewinnt nicht")
  }

  /**
   * Positive control for the one above: within a single language the name already matched, so this
   * passes on the broken code too. It is here to pin down that the defect is language-dependent --
   * and to catch a "fix" that would merge columns regardless of the language.
   */
  @Test
  fun `gleiche sprache auf beiden seiten`() {
    val source = managerWithEffortColumn(Locale.GERMAN)
    val target = managerWithEffortColumn(Locale.GERMAN)

    val thatColumn = source.definitions.single()
    val mapping = target.importData(source)

    assertEquals(1, target.definitions.size, "bei einer Sprache war der Import immer richtig")
    assertSame(target.findEffortDefinition(TASK_EFFORT_HOURS), mapping[thatColumn])
  }

  /** The other direction, so the fix cannot be one that happens to work for German targets only. */
  @Test
  fun `aufwandsspalte in die englische sitzung importiert`() {
    val source = managerWithEffortColumn(Locale.GERMAN)
    val target = managerWithEffortColumn(Locale.ENGLISH)

    val thatColumn = source.definitions.single()
    val mapping = target.importData(source)

    assertEquals(1, target.definitions.size)
    assertSame(target.findEffortDefinition(TASK_EFFORT_HOURS), mapping[thatColumn])
    assertEquals(effortLabelIn(Locale.ENGLISH), target.definitions.single().name)
  }

  /**
   * First counter-case, and the reason the fix may not simply match on the id: a column the USER
   * creates gets a GENERATED id (`tpc0`, `tpc1`, …) that carries no meaning. Two unrelated projects
   * both start at `tpc0`, so matching those by id would merge two columns that have nothing to do
   * with each other. For generated ids the name stays the identity.
   */
  @Test
  fun `benutzerspalten mit gleicher erzeugter kennung bleiben getrennt`() {
    val source = CustomColumnsManager()
    source.createDefinition(CustomPropertyClass.TEXT, "Lieferant", null)
    val target = CustomColumnsManager()
    target.createDefinition(CustomPropertyClass.TEXT, "Raum", null)
    assertEquals(source.definitions.single().id, target.definitions.single().id,
      "Vorbedingung: beide bekommen dieselbe erzeugte Kennung")

    target.importData(source)

    assertEquals(2, target.definitions.size,
      "zwei verschiedene Benutzerspalten duerfen nicht verschmelzen, nur weil die Kennung erzeugt gleich ist")
    assertTrue(target.definitions.map { it.name }.containsAll(listOf("Raum", "Lieferant")))
  }

  /**
   * Second counter-case, and the reason the rule is a DECLARED LIST and not "any id that was not
   * generated": an id written into a file is not a promise either. Two projects may both carry
   * `col1` and mean something different by it, and GanttProject requires those to stay apart --
   * `CustomPropertyImportTest.import preserves property id` and
   * `ImportTasksTestCase.testImportCustomColumns` say so, the latter by checking that no column
   * loses its values. Mirrored here so a later attempt to widen the rule fails inside the fork's own
   * tests too, and not only in the inherited ones.
   */
  @Test
  fun `undeklarierte kennungen bleiben getrennt`() {
    val source = CustomColumnsManager()
    source.createDefinition("col1", CustomPropertyClass.TEXT.iD, "bar", null)
    val target = CustomColumnsManager()
    target.createDefinition("col1", CustomPropertyClass.TEXT.iD, "foo", null)

    target.importData(source)

    assertEquals(2, target.definitions.size,
      "eine Kennung aus einer Datei ist keine Zusage -- nur erklaerte Kennungen gelten als Identitaet")
    assertTrue(target.definitions.map { it.name }.containsAll(listOf("foo", "bar")))
  }

  /**
   * Builds the manager of an installation running in [locale]: same id, same type and the same
   * display name that [EffortDrivenProperties.findOrCreateTaskEffort] would give the column there.
   * Set up by hand rather than by switching `Locale.getDefault()`, which would reach into every
   * other test running in the same JVM.
   */
  private fun managerWithEffortColumn(locale: Locale): CustomColumnsManager =
    CustomColumnsManager().also {
      it.createDefinition(TASK_EFFORT_HOURS, CustomPropertyClass.DOUBLE.iD, effortLabelIn(locale), null)
    }
}

/**
 * The label the fork's bundle gives the effort column in [locale]. Fails loudly instead of falling
 * back to the key: a bundle missing from the test classpath would otherwise make every test above
 * compare two identical key strings and pass for the wrong reason.
 */
private fun effortLabelIn(locale: Locale): String =
  requireNotNull(ForkI18n.textOrNull("fork.column.effort", locale)) {
    "das Textbuendel des Forks liefert 'fork.column.effort' in $locale nicht -- Messung waere hohl"
  }
