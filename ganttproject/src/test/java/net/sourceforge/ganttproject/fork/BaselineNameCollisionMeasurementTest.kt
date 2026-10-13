/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.

WHAT DOES THE PROGRAM DO TODAY WHEN TWO BASELINES HAVE THE SAME NAME?

A MEASUREMENT AND NOT A REQUIREMENT. Nothing here says what SHOULD happen — the question of what
an import does with a baseline whose name the target project already uses is Natalie's to decide,
and this file exists so that she decides it against a measured state of affairs rather than
against a guess. Every case below records what happens as of 08.10.2026 and says so in its name.

Measured on the real loading path: `ProxyDocument.java:277` reads a project file by handing the
parsed XML to `BaselineSerializer.loadBaselines`, which is exactly what is called here.

THE SHORT ANSWER, for whoever reads only this comment: nothing happens. There is no duplicate
check anywhere. `IGanttProject.baselines` is a plain `MutableList` with no key, the loader appends
whatever the file contains, and the baseline dialog's delete path matches BY NAME and stops at the
first hit — so with two baselines called "Basis", deleting the second one removes the first.
*/
package net.sourceforge.ganttproject.fork

import biz.ganttproject.core.io.parseXmlProject
import biz.ganttproject.core.time.CalendarFactory
import net.sourceforge.ganttproject.GanttPreviousState
import net.sourceforge.ganttproject.parser.BaselineSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.text.DateFormat
import java.util.Locale

class BaselineNameCollisionMeasurementTest {

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

  /** Two baselines, both called "Basis", differing only in what they recorded. */
  private val zweiGleichnamige = """
    <?xml version="1.0" encoding="UTF-8"?>
    <project name="ZWEI GLEICHNAMIGE" company="" webLink="" view-date="2026-10-01" version="3.4.3395" locale="en_US">
      <tasks empty-milestones="true">
        <task id="0" uid="gggggggggggg4ggg8gggggggggggggg1" name="Vorgang" color="#8cb6ce" meeting="false" start="2026-10-05" duration="5" complete="0" expand="true"/>
      </tasks>
      <previous>
        <previous-tasks name="Basis">
          <previous-task id="0" start="2026-10-05" duration="5" meeting="false" super="false"/>
        </previous-tasks>
        <previous-tasks name="Basis">
          <previous-task id="0" start="2026-10-19" duration="9" meeting="false" super="false"/>
        </previous-tasks>
      </previous>
      <roles roleset-name="Default"/>
    </project>
  """.trimIndent()

  private fun loadBaselines(xml: String): MutableList<GanttPreviousState> =
    mutableListOf<GanttPreviousState>().also {
      BaselineSerializer().loadBaselines(parseXmlProject(xml), it)
    }

  @Test
  fun `gemessen 08-10-2026 - gleichnamige basisplaene werden beide geladen, ohne jede meldung`() {
    val baselines = loadBaselines(zweiGleichnamige)

    assertEquals(2, baselines.size, "beide werden geladen -- es gibt keine Duplikatpruefung")
    assertEquals(listOf("Basis", "Basis"), baselines.map { it.name }, "und beide behalten denselben Namen")
    // They are two distinct objects with distinct content, which is why the collision is not
    // harmless: a reader cannot tell them apart by anything the interface shows.
    assertNotEquals(
      baselines[0].getTasks().first().duration, baselines[1].getTasks().first().duration,
      "verschiedener Inhalt (5 gegen 9 Tage), gleicher Name -- in der Liste nicht unterscheidbar"
    )
  }

  @Test
  fun `gemessen 08-10-2026 - wer einen von zwei gleichnamigen per namen sucht, findet den ersten`() {
    val baselines = loadBaselines(zweiGleichnamige)
    val zweiter = baselines[1]

    // THE LOOP OF BaselineDialogAction.deleteValue, Zeile 85-92, written out: it walks the list,
    // compares getName(), and `break`s at the first match. Reproduced here rather than invoked
    // because the original needs a Swing dialog -- what is measured is the LOOKUP RULE, and the
    // consequence of it is shown at the running program in the report of 08.10.2026.
    var getroffen: GanttPreviousState? = null
    for (baseline in baselines) {
      if (baseline.name == zweiter.name) {
        getroffen = baseline
        break
      }
    }

    assertEquals(baselines[0], getroffen, "gesucht war der ZWEITE, getroffen wird der ERSTE")
    assertNotEquals(zweiter, getroffen, "die Suche per Namen ist bei Namensgleichheit nicht eindeutig")
  }
}
