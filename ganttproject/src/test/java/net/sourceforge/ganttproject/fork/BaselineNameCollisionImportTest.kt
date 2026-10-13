/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.

Does an unanswered baseline-name question really leave the target project alone, end to end
through [ImporterFromGanttFile.run] -- the same guarantee [ImportCalendarQuestionTest] measures
for the calendar question, now for the fifth collision policy ("ask") added on 10.10.2026.

NOT "THE RETURNED LIST IS EMPTY". [BaselineNameCollisionQuestionTest] already proves the pure
logic; what is missing there is whether the WIRING into the real importer honours it -- whether a
`null` from the (faked) dialog actually stops [ImporterFromGanttFile.run] before the undo
transaction, as opposed to, say, importing with some fallback policy because a null happened to
compare unequal to nothing in particular. The positive control proves the opposite failure mode:
a version that always refuses would make the "ohne Antwort" case pass for the wrong reason.

WHY A REAL FILE, READ FOR REAL. [GanttPreviousState]-only collision detection cannot, by itself,
prove that [ImporterFromGanttFile.run] calls it with the right lists at the right point --
only that the function in isolation behaves. The file here is written with [GanttXMLSaver] (same
tool [BaselineRoundTripTest] uses to prove the reverse direction) and read back through the real
[Document.read] that production code uses, baselines included.

NOT TESTED HERE, AND SAID OUT LOUD: whether the REAL modal dialog
([net.sourceforge.ganttproject.fork.showBaselineNameCollisionDialog]) itself opens, shows the
right names, and reacts to a real click or Escape. That needs a display this machine does not
have. What is tested is everything on both sides of it: the question [ImporterFromGanttFile] asks,
and the three answers [BaselineNameCollisionQuestionTest] already covers.

This file is part of GanttProject, an opensource project management tool.
Licensed under the GNU General Public License, version 3 or later.
*/
package net.sourceforge.ganttproject.fork

import biz.ganttproject.core.calendar.ImportCalendarOption
import biz.ganttproject.core.time.CalendarFactory
import net.sourceforge.ganttproject.GanttPreviousState
import net.sourceforge.ganttproject.GanttProjectImpl
import net.sourceforge.ganttproject.export.ConsoleUIFacade
import net.sourceforge.ganttproject.gui.UIFacade
import net.sourceforge.ganttproject.importer.BufferProject
import net.sourceforge.ganttproject.importer.ImporterFromGanttFile
import net.sourceforge.ganttproject.importer.ImporterWizardModel
import net.sourceforge.ganttproject.io.GanttXMLSaver
import net.sourceforge.ganttproject.resource.MergeResourcesEnum
import net.sourceforge.ganttproject.undo.GPUndoManager
import net.sourceforge.ganttproject.undo.GPUndoListener
import org.easymock.EasyMock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.osgi.service.prefs.Preferences
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Locale
import java.util.function.Function

/**
 * Runs the Runnable right away and records nothing -- [ConsoleUIFacade.getUndoManager] has no
 * real implementation, and the positive-control test needs the import to actually run, not an
 * undo recording.
 */
private class ImmediateUndoManager : GPUndoManager {
  override fun undoableEdit(localizedName: String, runnableEdit: Runnable) = runnableEdit.run()
  override fun canUndo() = false
  override fun canRedo() = false
  override fun undo() {}
  override fun redo() {}
  override val undoPresentationName = ""
  override val redoPresentationName = ""
  override fun addUndoableEditListener(listener: GPUndoListener) {}
  override fun removeUndoableEditListener(listener: GPUndoListener) {}
  override fun die() {}
  override fun addUndoableEditTxnFactory(factory: net.sourceforge.ganttproject.undo.UndoableEditTxnFactory) {}
}

private class TestUIFacade : ConsoleUIFacade(null) {
  override fun getUndoManager(): GPUndoManager = ImmediateUndoManager()
}

class BaselineNameCollisionImportTest {

  init {
    object : CalendarFactory() {
      init {
        setLocaleApi(object : CalendarFactory.LocaleApi {
          override fun getLocale(): Locale = Locale.GERMANY
          override fun getShortDateFormat(): DateFormat =
            DateFormat.getDateInstance(DateFormat.SHORT, Locale.GERMANY)
        })
      }
    }
  }

  /** A real, readable .gan file whose only content of interest is one baseline named [name]. */
  private fun savedFileWithBaseline(name: String): File {
    val project = GanttProjectImpl()
    project.baselines.add(GanttPreviousState(name, emptyList()))
    val file = File.createTempFile("BaselineNameCollisionImportTest", ".gan").also { it.deleteOnExit() }
    FileOutputStream(file).use { out -> GanttXMLSaver(project).save(out) }
    return file
  }

  private fun nicePreferences(): Preferences {
    val node = EasyMock.createNiceMock<Preferences>(Preferences::class.java)
    EasyMock.expect(node.get(EasyMock.anyString(), EasyMock.anyString()))
      .andReturn(MergeResourcesEnum.DEFAULT.name.lowercase()).anyTimes()
    EasyMock.replay(node)
    val prefs = EasyMock.createNiceMock<Preferences>(Preferences::class.java)
    EasyMock.expect(prefs.node(EasyMock.anyString())).andReturn(node).anyTimes()
    EasyMock.replay(prefs)
    return prefs
  }

  /** An importer with its calendar question already answered, so only the baseline one is live. */
  private fun contextualizedImporter(target: GanttProjectImpl, uiFacade: UIFacade, file: File): ImporterFromGanttFile {
    val model = ImporterWizardModel()
    model.file = file
    return ImporterFromGanttFile().also {
      it.setContext(target, uiFacade, nicePreferences())
      it.setModel(model)
      it.calendarOptionForTesting.setSelectedValue(ImportCalendarOption.Values.NO)
    }
  }

  @Test
  fun `voraussetzung -- ein basisplan kommt beim einlesen durch BufferProject tatsaechlich an`() {
    // Grundlage fuer die beiden Faelle unten: wenn DIESER Fall rot waere, haetten beide anderen
    // nichts gemessen -- "ohne Antwort passiert nichts" waere dann wahr, weil nie etwas zum
    // Entscheiden ankommt, nicht weil die Sperre haelt.
    val datei = savedFileWithBaseline("Basis")
    val ziel = GanttProjectImpl()
    val uiFacade: UIFacade = ConsoleUIFacade(null)
    val buffer = BufferProject(ziel, uiFacade)
    val doc = buffer.documentManager.getDocument(datei.absolutePath)
    doc.read()
    assertEquals(
      listOf("Basis"), buffer.baselines.map { it.name },
      "der Basisplan muss nach dem Einlesen in bufferProject.baselines stehen"
    )
  }

  @Test
  fun `ohne antwort auf die basisplan-frage bleibt das zielprojekt unangetastet`() {
    val ziel = GanttProjectImpl()
    ziel.baselines.add(GanttPreviousState("Basis", emptyList()))
    val basisplaeneVorher = ziel.baselines.toList()
    val vorgaengeVorher = ziel.taskManager.tasks.size

    val datei = savedFileWithBaseline("Basis")
    val uiFacade: UIFacade = TestUIFacade()

    val importer = contextualizedImporter(ziel, uiFacade, datei)
    // DIE GEFAELSCHTE ANTWORT: der Dialog wurde ohne Wahl geschlossen.
    importer.baselineCollisionAskForTesting = Function { null }

    importer.run()

    assertEquals(
      basisplaeneVorher, ziel.baselines,
      "kein Basisplan uebernommen, keiner verworfen -- die Liste muss UNVERAENDERT sein"
    )
    assertEquals(
      vorgaengeVorher, ziel.taskManager.tasks.size,
      "der GANZE Import unterbleibt, nicht nur der Basisplan-Teil -- genau wie bei der Kalenderfrage"
    )
  }

  @Test
  fun `positivkontrolle -- mit der antwort 'behalten' kommt der basisplan an, zweimal gleich benannt`() {
    // Belegt den Gegenbeweis: eine Fassung, die IMMER verweigert, wuerde den Fall oben auch
    // bestehen, aber aus dem falschen Grund.
    val ziel = GanttProjectImpl()
    ziel.baselines.add(GanttPreviousState("Basis", emptyList()))

    val datei = savedFileWithBaseline("Basis")
    val uiFacade: UIFacade = TestUIFacade()

    val importer = contextualizedImporter(ziel, uiFacade, datei)
    var askCalls = 0
    var gefragteNamen: List<String>? = null
    importer.baselineCollisionAskForTesting = Function { namen -> askCalls++; gefragteNamen = namen; BaselineCollisionAnswer.KEEP_BOTH }

    importer.run()

    assertEquals(1, askCalls, "die Frage muss GENAU EINMAL gestellt werden, mit den Namen: $gefragteNamen")
    assertEquals(
      2, ziel.baselines.count { it.name == "Basis" },
      "'behalten' heisst: beide bleiben, der Name 'Basis' muss danach ZWEIMAL in der Liste stehen"
    )
  }
}

/** The importer's calendar option, reachable for the test without opening it to everybody. */
private val ImporterFromGanttFile.calendarOptionForTesting: ImportCalendarOption
  get() = ImporterFromGanttFile::class.java.getDeclaredField("myImportCalendarOption")
    .also { it.isAccessible = true }
    .get(this) as ImportCalendarOption

/**
 * The function the importer asks for an answer to the baseline-name question, reachable for the
 * test so it can fake the answer instead of opening a real window -- see the comment on
 * `myBaselineCollisionAsk` in [ImporterFromGanttFile].
 */
private var ImporterFromGanttFile.baselineCollisionAskForTesting: Function<List<String>, BaselineCollisionAnswer?>
  get() = ImporterFromGanttFile::class.java.getDeclaredField("myBaselineCollisionAsk")
    .also { it.isAccessible = true }
    .get(this) as Function<List<String>, BaselineCollisionAnswer?>
  set(value) {
    ImporterFromGanttFile::class.java.getDeclaredField("myBaselineCollisionAsk").also {
      it.isAccessible = true
      it.set(this, value)
    }
  }
