/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.

Do the baselines of an imported project survive the import boundary, and do they end up pointing
at the RIGHT tasks?

THE SECOND HALF IS THE INTERESTING ONE. A baseline records task IDS, and the desktop import hands
out new ids, so an untranslated entry does not point nowhere -- it points at a different, existing
task of the target project, because low ids are taken there too. The case
`der basisplan zeigt auf die NEUEN vorgaenge, nicht auf die gleichnamigen ids des ziels` is built
so that it fails when the translation is dropped: the target project owns a task under exactly the
id the imported baseline brought, and the two differ in duration, so a baseline laid over the
wrong task is visible as a wrong number rather than as nothing.

WHAT IS NOT DECIDED HERE: which name an imported baseline gets when the target already has one of
that name. All four possibilities are tested, none is a default, and the choice is Natalie's --
see BaselineNameCollisionMeasurementTest for what the program does today.
*/
package net.sourceforge.ganttproject.fork

import biz.ganttproject.core.time.CalendarFactory
import net.sourceforge.ganttproject.GanttPreviousState
import net.sourceforge.ganttproject.GanttPreviousStateTask
import net.sourceforge.ganttproject.TestSetupHelper
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.util.Locale

class BaselineImportTest {

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

  private fun taskManager(): TaskManager = TestSetupHelper.newTaskManagerBuilder().build()

  private fun task(manager: TaskManager, name: String, duration: Int): Task =
    manager.newTaskBuilder().withName(name).withDuration(manager.createLength(duration.toLong())).build()

  /** A baseline with entries, written to its temporary file the way the program does it. */
  private fun baseline(name: String, vararg entries: Pair<Int, Int>): GanttPreviousState =
    GanttPreviousState(name, entries.map { (id, duration) ->
      GanttPreviousStateTask(id, CalendarFactory.createGanttCalendar(2026, 9, 5), duration, false, false)
    }).also {
      it.init()
      it.saveFile()
    }

  @Test
  fun `der basisplan zeigt auf die NEUEN vorgaenge, nicht auf die gleichnamigen ids des ziels`() {
    val manager = taskManager()
    // The target project owns a task under the id the imported baseline carries. This is the trap
    // the translation exists for, and it is not hypothetical: every GanttProject file starts
    // numbering at 0, so the target has a task 0 and so does every file imported into it.
    val zielVorgang = task(manager, "Ziel A", 5)
    val quellVorgangOriginal = task(manager, "Quelle A (original)", 4)
    val quellVorgangImportiert = task(manager, "Quelle A (importiert)", 4)

    val quellBasisplan = baseline("Basis", quellVorgangOriginal.taskID to 4)
    val zielBasisplaene = mutableListOf<GanttPreviousState>()

    val result = importBaselines(
      listOf(quellBasisplan), zielBasisplaene,
      mapOf(quellVorgangOriginal to quellVorgangImportiert),
      BaselineNameCollision.APPEND_AS_IS
    )

    assertEquals(1, zielBasisplaene.size, "der Basisplan kommt an")
    val eintraege = zielBasisplaene.single().load()!!
    assertEquals(1, eintraege.size)
    assertEquals(
      quellVorgangImportiert.taskID, eintraege.single().id,
      "der Eintrag traegt die NEUE Vorgangs-ID"
    )
    assertTrue(
      eintraege.single().id != quellVorgangOriginal.taskID,
      "und ausdruecklich nicht mehr die alte -- sonst zeigte er auf einen fremden Vorgang"
    )
    assertTrue(
      eintraege.single().id != zielVorgang.taskID,
      "und schon gar nicht auf den Vorgang, der im Ziel unter der alten Nummer liegt"
    )
    assertEquals(emptyMap<String, Int>(), result.droppedEntries, "nichts ging verloren")
  }

  @Test
  fun `die eintraege sind in der datei und nicht nur im hauptspeicher`() {
    // THE OTHER THING THE CLOUD IMPORT PATH GETS WRONG: a GanttPreviousState keeps its entries in
    // a temporary file, and load() -- what the chart and the saver call -- reads exactly that
    // file. Without init() + saveFile() the baseline is visible in the list, invisible in the
    // chart and an exception when saving. load() returning the entries IS the proof the file was
    // written, because nothing else could answer.
    val manager = taskManager()
    val original = task(manager, "A", 3)
    val importiert = task(manager, "A importiert", 3)
    val ziel = mutableListOf<GanttPreviousState>()

    importBaselines(
      listOf(baseline("Basis", original.taskID to 3)), ziel,
      mapOf(original to importiert), BaselineNameCollision.APPEND_AS_IS
    )

    assertEquals(3, ziel.single().load()!!.single().duration, "aus der Datei gelesen, nicht geraten")
  }

  @Test
  fun `ein eintrag ohne gegenstueck wird weggelassen und gezaehlt`() {
    // A baseline is a record of a PAST state and may know a task that has been deleted since.
    // Such an entry has no counterpart, and keeping its id would point it at a stranger.
    val manager = taskManager()
    val bleibt = task(manager, "bleibt", 2)
    val bleibtImportiert = task(manager, "bleibt importiert", 2)
    val geloescht = task(manager, "seit damals geloescht", 7)
    val ziel = mutableListOf<GanttPreviousState>()

    val result = importBaselines(
      listOf(baseline("Basis", bleibt.taskID to 2, geloescht.taskID to 7)), ziel,
      mapOf(bleibt to bleibtImportiert), BaselineNameCollision.APPEND_AS_IS
    )

    assertEquals(1, ziel.single().load()!!.size, "nur der Eintrag mit Gegenstueck kommt an")
    assertEquals(mapOf("Basis" to 1), result.droppedEntries, "der andere wird GEMELDET, nicht verschwiegen")
  }

  @Test
  fun `ohne basisplaene in der quelle geschieht nichts`() {
    val ziel = mutableListOf(baseline("Zielbasis", 0 to 1))
    val result = importBaselines(emptyList(), ziel, emptyMap(), BaselineNameCollision.APPEND_AS_IS)
    assertEquals(listOf("Zielbasis"), ziel.map { it.name }, "der eigene Basisplan des Ziels bleibt")
    assertEquals(BaselineImportResult(), result, "und es wird nichts gemeldet")
  }

  // ------------------------------------------------------------------------------------------
  // The four possibilities for a name the target already uses. NONE of them is the default --
  // the decision is Natalie's, and these cases exist so that whichever she picks is already
  // measured rather than built afterwards in a hurry.
  // ------------------------------------------------------------------------------------------

  /** Target and source both have a baseline called "Basis", with different durations. */
  private fun kollisionsaufbau(): Triple<MutableList<GanttPreviousState>, List<GanttPreviousState>, Map<Task, Task>> {
    val manager = taskManager()
    val zielVorgang = task(manager, "Ziel", 5)
    val quelleOriginal = task(manager, "Quelle", 9)
    val quelleImportiert = task(manager, "Quelle importiert", 9)
    val ziel = mutableListOf(baseline("Basis", zielVorgang.taskID to 5))
    val quelle = listOf(baseline("Basis", quelleOriginal.taskID to 9))
    return Triple(ziel, quelle, mapOf(quelleOriginal to quelleImportiert))
  }

  @Test
  fun `APPEND_AS_IS - beide heissen danach gleich`() {
    val (ziel, quelle, mapping) = kollisionsaufbau()
    val result = importBaselines(quelle, ziel, mapping, BaselineNameCollision.APPEND_AS_IS)
    assertEquals(listOf("Basis", "Basis"), ziel.map { it.name })
    assertEquals(emptyMap<String, String>(), result.renamed)
    assertEquals(listOf(5, 9), ziel.map { it.load()!!.single().duration }, "verschiedener Inhalt, gleicher Name")
  }

  @Test
  fun `PREFIX_WITH_PROJECT_NAME - der importierte bekommt den projektnamen davor`() {
    val (ziel, quelle, mapping) = kollisionsaufbau()
    val result = importBaselines(
      quelle, ziel, mapping, BaselineNameCollision.PREFIX_WITH_PROJECT_NAME, "QUELLPROJEKT"
    )
    assertEquals(listOf("Basis", "QUELLPROJEKT: Basis"), ziel.map { it.name })
    assertEquals(mapOf("Basis" to "QUELLPROJEKT: Basis"), result.renamed, "die Umbenennung wird gemeldet")
  }

  @Test
  fun `PREFIX_WITH_PROJECT_NAME - zweimal dieselbe datei gibt keinen zweiten gleichen namen`() {
    val (ziel, quelle, mapping) = kollisionsaufbau()
    importBaselines(quelle, ziel, mapping, BaselineNameCollision.PREFIX_WITH_PROJECT_NAME, "QUELLPROJEKT")
    importBaselines(quelle, ziel, mapping, BaselineNameCollision.PREFIX_WITH_PROJECT_NAME, "QUELLPROJEKT")
    assertEquals(
      listOf("Basis", "QUELLPROJEKT: Basis", "QUELLPROJEKT: Basis (2)"), ziel.map { it.name },
      "sonst waere die Mehrdeutigkeit wieder da, die diese Moeglichkeit gerade vermeidet"
    )
  }

  @Test
  fun `PREFIX_WITH_PROJECT_NAME - ein namenloses projekt bekommt keinen doppelpunkt-namen`() {
    val (ziel, quelle, mapping) = kollisionsaufbau()
    importBaselines(quelle, ziel, mapping, BaselineNameCollision.PREFIX_WITH_PROJECT_NAME, "   ")
    assertEquals(listOf("Basis", "Imported: Basis"), ziel.map { it.name })
  }

  @Test
  fun `REPLACE_EXISTING - der basisplan des ziels ist danach weg`() {
    val (ziel, quelle, mapping) = kollisionsaufbau()
    val result = importBaselines(quelle, ziel, mapping, BaselineNameCollision.REPLACE_EXISTING)
    assertEquals(listOf("Basis"), ziel.map { it.name }, "nur noch einer")
    assertEquals(9, ziel.single().load()!!.single().duration, "und es ist der importierte")
    assertEquals(listOf("Basis"), result.replaced, "der Verlust wird gemeldet")
  }

  @Test
  fun `SKIP_COLLIDING - der importierte kommt nicht an, der des ziels bleibt`() {
    val (ziel, quelle, mapping) = kollisionsaufbau()
    val result = importBaselines(quelle, ziel, mapping, BaselineNameCollision.SKIP_COLLIDING)
    assertEquals(listOf("Basis"), ziel.map { it.name })
    assertEquals(5, ziel.single().load()!!.single().duration, "es ist der des Ziels")
    assertEquals(listOf("Basis"), result.skippedForNameCollision, "und der Verlust wird gemeldet")
  }

  @Test
  fun `SKIP_COLLIDING - ein nicht kollidierender name kommt trotzdem an`() {
    // The positive control for the case above: without it, "nothing arrived" could just as well
    // mean the policy drops everything.
    val (ziel, _, _) = kollisionsaufbau()
    val manager = taskManager()
    val original = task(manager, "X", 4)
    val importiert = task(manager, "X importiert", 4)
    importBaselines(
      listOf(baseline("Ganz anderer Name", original.taskID to 4)), ziel,
      mapOf(original to importiert), BaselineNameCollision.SKIP_COLLIDING
    )
    assertEquals(listOf("Basis", "Ganz anderer Name"), ziel.map { it.name })
  }
}
