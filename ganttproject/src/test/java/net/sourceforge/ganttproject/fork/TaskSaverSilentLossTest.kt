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

import biz.ganttproject.core.io.parseXmlProject
import biz.ganttproject.core.io.walkTasksDepthFirst
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.lib.fx.SimpleTreeCollapseView
import net.sourceforge.ganttproject.GanttProjectImpl
import net.sourceforge.ganttproject.io.GanttXMLSaver
import net.sourceforge.ganttproject.parser.TaskLoader
import net.sourceforge.ganttproject.parser.loadDependencyGraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Locale

/**
 * Auftrag `tasksaver-still-zementiert`, 12.10.2026.
 *
 * Wird der stille Ladefehler aus `gan-laden-laut-oder-still` (08.10.2026) beim naechsten
 * Speichern ENDGUELTIG aus der Datei entfernt, oder bleibt er nur ein Lesefehler? Dafuer muss
 * hier NICHT die GUI bedient werden -- das Laden und Speichern laeuft ueber genau dieselben
 * Klassen, die die Oberflaeche benutzt:
 *
 * LADEN: Menu File->Open -> ProjectOpenStrategy.openFileAsIs() (ProjectOpenStrategy.kt:174/181)
 * -> project.open(document) (GanttProject.java extends GanttProjectBase) -> document.read()
 * auf der ProxyDocument, die die eigentliche Datei umschliesst -> ProxyDocument.read()
 * (ProxyDocument.java:162) -> doParse() (ProxyDocument.java:260) -> GanttXMLOpen als GPParser
 * (ParserFactoryImpl.newParser(), GanttProject.java:1029) liest die Bytes per SAX ein
 * (XmlParser.java:110, syntaktisch irrelevant fuer Abhaengigkeiten) UND baut daraus ein
 * XmlProject (XmlParser.java:130) -- genau das, was TaskTagHandler.process(xmlProject)
 * (TaskTagHandler.java:43-51) konsumiert: `TaskLoader.loadTask()` fuer jeden Vorgang, danach
 * `TaskSerializerKt.loadDependencyGraph(...)` (TaskTagHandler.java:50) fuer die Kanten. Diese
 * Funktion ist hier UNVERAENDERT nachgebaut (gleiche Klassen, gleiche Methoden, gleiche
 * Reihenfolge) -- nur die SAX-Huelle (XmlParser/GanttXMLOpen) fehlt, weil TaskTagHandler selbst
 * KEIN TagHandler ist und auf startElement/endElement nie reagiert (siehe TaskTagHandler.java:
 * die Klasse implementiert das Interface nicht). Ein Abweichen von der Oberflaeche waere also
 * nur in der uebersprungenen SAX-Huelle moeglich, nicht im Ladevorgang der Abhaengigkeiten
 * selbst.
 *
 * SPEICHERN: Menu File->Save -> ParserFactoryImpl.newSaver() (GanttProject.java:1037) ->
 * `new GanttXMLSaver(...)` -> GanttXMLSaver.save() -> `new TaskSaver(collapseView).save(project,
 * handler)` (GanttXMLSaver.java:160) -- das ist exakt `GanttXMLSaver(project).save(out)`, das
 * dieser Test wie jeder andere Rundlauf-Test in diesem Paket benutzt (siehe
 * AssignmentAxesLegacyFileTest, BaselineRoundTripTest).
 *
 * Die drei .gan-Dateien unten sind woertliche Kopien aus
 * Claude-arbeits-repo/ganttproject-fork/belege-2026-10-08-gan-laden/gan-dateien/ (nicht von dort
 * gelesen, damit der Test von keinem Arbeitsverzeichnis abhaengt) -- siehe der Auftrag fuer das
 * Original.
 */
class TaskSaverSilentLossTest {

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

  private fun saveToXml(project: GanttProjectImpl): String =
    ByteArrayOutputStream().also { GanttXMLSaver(project).save(it) }.toString(Charsets.UTF_8)

  /**
   * Laedt wie das Programm laedt: derselbe TaskLoader, dieselbe loadDependencyGraph-Funktion,
   * dieselbe Reihenfolge wie TaskTagHandler.process() (TaskTagHandler.java:43-51).
   */
  private fun ladenWieDasProgramm(xml: String): GanttProjectImpl {
    val projekt = GanttProjectImpl()
    val xmlProject = parseXmlProject(xml)
    val loader = TaskLoader(projekt.taskManager, SimpleTreeCollapseView())
    loader.loadTaskCustomPropertyDefinitions(xmlProject)
    xmlProject.walkTasksDepthFirst { parent, child -> loader.loadTask(parent, child); true }
    loadDependencyGraph(loader.dependencies, projekt.taskManager, loader.legacyFixedStartTasks)
    return projekt
  }

  // ------------------------------------------------------------------------------------------
  // Positivkontrolle: OHNE Schaden muss die Abhaengigkeit den Rundlauf ueberstehen. Nur wenn das
  // hier haelt, kann ein spaeteres "Element weg" ueberhaupt etwas ueber den Schaden aussagen --
  // sonst waere es nur der eigene Messweg, der Elemente verliert.
  // ------------------------------------------------------------------------------------------

  private val basisGan = """
    <?xml version="1.0" encoding="UTF-8"?>
    <project name="Basis" company="" webLink="" view-date="2026-10-01" view-index="0" gantt-divider-location="300" resource-divider-location="300" version="3.3.3312" locale="de_DE">
        <description/>
        <view zooming-state="default:0" id="gantt-chart">
            <field id="tpd3" name="Name" width="200" order="0"/>
            <field id="tpd4" name="Begin date" width="75" order="1"/>
            <field id="tpd5" name="End date" width="75" order="2"/>
            <timeline><![CDATA[]]></timeline>
        </view>
        <view id="resourceTable">
            <field id="0" name="Name" width="210" order="0"/>
        </view>
        <tasks empty-milestones="true">
            <taskproperties>
                <taskproperty id="tpd0" name="type" type="default" valuetype="icon"/>
                <taskproperty id="tpd1" name="priority" type="default" valuetype="icon"/>
            </taskproperties>
            <task id="0" uid="aaaaaaaaaa" name="Aufgabe A" color="#8cb6ce" meeting="false" start="2026-10-01" duration="5" complete="0" expand="true"/>
            <task id="1" uid="bbbbbbbbbb" name="Aufgabe B" color="#8cb6ce" meeting="false" start="2026-10-06" duration="5" complete="0" expand="true">
                <depend id="0" type="2" difference="0" hardness="Strong"/>
            </task>
            <task id="2" uid="cccccccccc" name="Aufgabe C" color="#8cb6ce" meeting="false" start="2026-10-11" duration="5" complete="0" expand="true">
                <depend id="1" type="2" difference="0" hardness="Strong"/>
            </task>
        </tasks>
        <resources>
            <resource id="0" uid="r1111111111" name="Person Eins" function="Default:0" contacts="" phone=""/>
        </resources>
        <allocations>
            <allocation task-id="0" resource-id="0" function="Default:0" responsible="true" load="100.0"/>
        </allocations>
        <vacations/>
        <taskdisplaycolumns>
            <displaycolumn property-id="tpd3" order="0" width="200"/>
        </taskdisplaycolumns>
        <previous-state-tasks/>
        <roles roleset-name="Default"/>
    </project>
  """.trimIndent()

  @Test
  fun `positivkontrolle -- eine echte Abhaengigkeit ueberlebt Laden und Speichern`() {
    val projekt = ladenWieDasProgramm(basisGan)
    val taskB = projekt.taskManager.getTask(1)
    assertEquals(1, taskB.dependenciesAsDependee.toArray().size,
      "Vorbedingung: Aufgabe B muss nach dem Laden genau eine Abhaengigkeit im Modell tragen")

    val wieder = saveToXml(projekt)
    assertEquals(2, Regex("<depend ").findAll(wieder).count(),
      "beide echten Abhaengigkeiten (B->A, C->B) muessen nach dem Speichern in der Datei stehen:\n$wieder")
  }

  // ------------------------------------------------------------------------------------------
  // k3a: eine Abhaengigkeit zeigt auf eine NICHT VORHANDENE Vorgangs-UID (id="999").
  // ------------------------------------------------------------------------------------------

  private val k3aHaengendeAbhaengigkeit = """
    <?xml version="1.0" encoding="UTF-8"?>
    <project name="Basis" company="" webLink="" view-date="2026-10-01" view-index="0" gantt-divider-location="300" resource-divider-location="300" version="3.3.3312" locale="de_DE">
        <description/>
        <view zooming-state="default:0" id="gantt-chart">
            <field id="tpd3" name="Name" width="200" order="0"/>
            <field id="tpd4" name="Begin date" width="75" order="1"/>
            <field id="tpd5" name="End date" width="75" order="2"/>
            <timeline><![CDATA[]]></timeline>
        </view>
        <view id="resourceTable">
            <field id="0" name="Name" width="210" order="0"/>
        </view>
        <tasks empty-milestones="true">
            <taskproperties>
                <taskproperty id="tpd0" name="type" type="default" valuetype="icon"/>
                <taskproperty id="tpd1" name="priority" type="default" valuetype="icon"/>
            </taskproperties>
            <task id="0" uid="aaaaaaaaaa" name="Aufgabe A" color="#8cb6ce" meeting="false" start="2026-10-01" duration="5" complete="0" expand="true"/>
            <task id="1" uid="bbbbbbbbbb" name="Aufgabe B" color="#8cb6ce" meeting="false" start="2026-10-06" duration="5" complete="0" expand="true">
                <depend id="999" type="2" difference="0" hardness="Strong"/>
            </task>
            <task id="2" uid="cccccccccc" name="Aufgabe C" color="#8cb6ce" meeting="false" start="2026-10-11" duration="5" complete="0" expand="true">
                <depend id="1" type="2" difference="0" hardness="Strong"/>
            </task>
        </tasks>
        <resources>
            <resource id="0" uid="r1111111111" name="Person Eins" function="Default:0" contacts="" phone=""/>
        </resources>
        <allocations>
            <allocation task-id="0" resource-id="0" function="Default:0" responsible="true" load="100.0"/>
        </allocations>
        <vacations/>
        <taskdisplaycolumns>
            <displaycolumn property-id="tpd3" order="0" width="200"/>
        </taskdisplaycolumns>
        <previous-state-tasks/>
        <roles roleset-name="Default"/>
    </project>
  """.trimIndent()

  @Test
  fun `k3a -- die haengende Abhaengigkeit ist nach dem Laden bereits aus dem Modell verschwunden`() {
    assertEquals(1, Regex("<depend id=\"999\"").findAll(k3aHaengendeAbhaengigkeit).count(),
      "Vorbedingung: die Quelldatei muss die haengende Abhaengigkeit tragen, sonst misst dieser Test nichts")

    val projekt = ladenWieDasProgramm(k3aHaengendeAbhaengigkeit)
    val taskB = projekt.taskManager.getTask(1)
    assertEquals(0, taskB.dependenciesAsDependee.toArray().size,
      "die haengende Abhaengigkeit darf nach dem Laden nicht mehr im Modell stehen -- " +
        "loadDependencyGraph (TaskSerializer.kt:207-210) gibt bei einer fehlenden Ziel-UID " +
        "kommentarlos zurueck")
    val taskC = projekt.taskManager.getTask(2)
    assertEquals(1, taskC.dependenciesAsDependee.toArray().size,
      "die GUELTIGE Abhaengigkeit (C->B) muss dagegen im Modell stehen")
  }

  @Test
  fun `k3a -- beim Speichern wird die haengende Abhaengigkeit NICHT zurueckgeschrieben, weil sie nie im Modell war`() {
    val projekt = ladenWieDasProgramm(k3aHaengendeAbhaengigkeit)
    val wieder = saveToXml(projekt)

    assertFalse(wieder.contains("id=\"999\""),
      "die haengende Abhaengigkeit darf nach dem Speichern nicht mehr in der Datei stehen:\n$wieder")
    assertEquals(1, Regex("<depend ").findAll(wieder).count(),
      "von den zwei <depend>-Elementen der Quelldatei darf nach dem Rundlauf nur die GUELTIGE " +
        "(C->B) uebrig sein, nicht die haengende:\n$wieder")

    // Der Datenverlust ist nicht auf eine einzelne Reparatur begrenzt: wird die einmal
    // geschriebene (bereits bereinigte) Datei ERNEUT geladen und gespeichert, bleibt sie
    // identisch -- es gibt also keinen Mechanismus, der das Loch wieder sichtbar macht.
    val zweiterRundlauf = saveToXml(ladenWieDasProgramm(wieder))
    assertEquals(wieder.replace(Regex("<project [^>]*>"), ""),
      zweiterRundlauf.replace(Regex("<project [^>]*>"), ""),
      "ein zweiter Rundlauf veraendert die bereits bereinigte Datei nicht mehr -- der Verlust " +
        "ist beim ERSTEN Speichern endgueltig, nicht erst nach mehreren")
  }

  // ------------------------------------------------------------------------------------------
  // k3f: eine eigene Spalte ist als boolean deklariert, der Vorgang traegt einen Textwert, der
  // kein gueltiges boolean ist ("apfelsine").
  // ------------------------------------------------------------------------------------------

  private val k3fFalscherSpaltentyp = """
    <?xml version="1.0" encoding="UTF-8"?>
    <project name="Basis" company="" webLink="" view-date="2026-10-01" view-index="0" gantt-divider-location="300" resource-divider-location="300" version="3.3.3312" locale="de_DE">
        <description/>
        <view zooming-state="default:0" id="gantt-chart">
            <field id="tpd3" name="Name" width="200" order="0"/>
            <field id="tpd4" name="Begin date" width="75" order="1"/>
            <field id="tpd5" name="End date" width="75" order="2"/>
            <timeline><![CDATA[]]></timeline>
        </view>
        <view id="resourceTable">
            <field id="0" name="Name" width="210" order="0"/>
        </view>
        <tasks empty-milestones="true">
            <taskproperties>
                <taskproperty id="tpd0" name="type" type="default" valuetype="icon"/>
                <taskproperty id="tpd1" name="priority" type="default" valuetype="icon"/>
                <taskproperty id="tpc0" name="Erledigt" type="custom" valuetype="boolean" defaultvalue="false"/>
            </taskproperties>
            <task id="0" uid="aaaaaaaaaa" name="Aufgabe A" color="#8cb6ce" meeting="false" start="2026-10-01" duration="5" complete="0" expand="true">
                <customproperty taskproperty-id="tpc0" value="apfelsine"/>
            </task>
            <task id="1" uid="bbbbbbbbbb" name="Aufgabe B" color="#8cb6ce" meeting="false" start="2026-10-06" duration="5" complete="0" expand="true">
                <depend id="0" type="2" difference="0" hardness="Strong"/>
            </task>
            <task id="2" uid="cccccccccc" name="Aufgabe C" color="#8cb6ce" meeting="false" start="2026-10-11" duration="5" complete="0" expand="true">
                <depend id="1" type="2" difference="0" hardness="Strong"/>
            </task>
        </tasks>
        <resources>
            <resource id="0" uid="r1111111111" name="Person Eins" function="Default:0" contacts="" phone=""/>
        </resources>
        <allocations>
            <allocation task-id="0" resource-id="0" function="Default:0" responsible="true" load="100.0"/>
        </allocations>
        <vacations/>
        <taskdisplaycolumns>
            <displaycolumn property-id="tpd3" order="0" width="200"/>
        </taskdisplaycolumns>
        <previous-state-tasks/>
        <roles roleset-name="Default"/>
    </project>
  """.trimIndent()

  @Test
  fun `k3f -- der falsch getypte Wert ist nach dem Laden bereits zu false umgewandelt, ohne jede Meldung`() {
    val projekt = ladenWieDasProgramm(k3fFalscherSpaltentyp)
    val taskA = projekt.taskManager.getTask(0)
    val propDef = projekt.taskManager.customPropertyManager.definitions.single { it.id == "tpc0" }

    assertTrue(taskA.customValues.hasOwnValue(propDef),
      "Aufgabe A muss nach dem Laden einen EIGENEN Wert fuer die Spalte tragen (nicht den Vorgabewert)")
    assertEquals(false, taskA.customValues.getValue(propDef),
      "java.lang.Boolean.valueOf(\"apfelsine\") liefert false -- die Umwandlung geschieht " +
        "kommentarlos in TaskLoader.loadCustomProperties (TaskSerializer.kt:167-196), ohne " +
        "Ausnahme und ohne Protokollzeile")
  }

  @Test
  fun `k3f -- beim Speichern steht false in der Datei, der Ursprungstext ist weg`() {
    val projekt = ladenWieDasProgramm(k3fFalscherSpaltentyp)
    val wieder = saveToXml(projekt)

    assertFalse(wieder.contains("apfelsine"),
      "der Ursprungstext \"apfelsine\" darf nach dem Speichern nicht mehr in der Datei stehen:\n$wieder")
    assertTrue(wieder.contains("taskproperty-id=\"tpc0\" value=\"false\""),
      "an seiner Stelle muss die Datei jetzt value=\"false\" tragen:\n$wieder")
  }

  // ------------------------------------------------------------------------------------------
  // Negativkontrolle: ein Verlust, den ICH erzeuge, muss der Messweg auch SEHEN -- sonst zaehlt
  // das "Element weg" oben nicht als Befund, sondern koennte ein blinder Messweg sein.
  // ------------------------------------------------------------------------------------------

  @Test
  fun `negativkontrolle -- eine selbst geloeschte Abhaengigkeit verschwindet ebenfalls aus der Datei`() {
    val projekt = ladenWieDasProgramm(basisGan)
    val taskB = projekt.taskManager.getTask(1)
    val abhaengigkeiten = taskB.dependenciesAsDependee.toArray()
    assertEquals(1, abhaengigkeiten.size, "Vorbedingung fuer die Negativkontrolle")

    abhaengigkeiten[0].delete()
    assertEquals(0, taskB.dependenciesAsDependee.toArray().size,
      "die Abhaengigkeit muss nach dem expliziten delete() aus dem Modell verschwunden sein")

    val wieder = saveToXml(projekt)
    assertEquals(1, Regex("<depend ").findAll(wieder).count(),
      "von den zwei Abhaengigkeiten der Quelldatei darf nach dem SELBST erzeugten Loeschen nur " +
        "noch die unberuehrte (C->B) in der Datei stehen -- sieht der Messweg das nicht, ist er " +
        "blind fuer Verlust und das 'weg' bei k3a waere kein Befund:\n$wieder")
  }
}
