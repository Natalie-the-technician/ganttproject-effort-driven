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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Locale

/**
 * Auftrag `tasksaver-still-zementiert` (12.10.2026), behoben in `gan-laden-laut-oder-still`
 * (Auftrag `tasksaver-stillen-verlust-beheben`, selber Tag).
 *
 * Der stille Ladefehler aus `gan-laden-laut-oder-still` (08.10.2026) wurde beim naechsten
 * Speichern ENDGUELTIG aus der Datei entfernt, UND OHNE JEDE MELDUNG. Das zweite ist jetzt
 * behoben: `TaskLoader.loadCustomProperties` und `loadDependencyGraph` sammeln beide Faelle in
 * einem `TaskLoadProblems`, das `TaskTagHandler` herausgibt und `ProxyDocument.doParse()` nach dem
 * Laden als EINE Benachrichtigung zeigt (`ProxyDocument.reportTaskLoadProblems`, Vorbild
 * `reportVacationProblems`). Das ERSTE -- die haengende Abhaengigkeit kann nicht erfunden werden,
 * der falsch getypte Wert bleibt false, weil die Spalte keinen anderen Wert anzubieten hat --
 * aendert sich bewusst NICHT: Melden, nicht Verweigern oder Erfinden.
 *
 * Dafuer muss hier NICHT die GUI bedient werden -- das Laden und Speichern laeuft ueber genau
 * dieselben Klassen, die die Oberflaeche benutzt:
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
   * Eine Ladung: das Projekt UND die dabei gesammelten Probleme (`TaskLoader.taskLoadProblems`,
   * `TaskTagHandler.getTaskLoadProblems()` im echten Betrieb).
   */
  private class Ladung(val projekt: GanttProjectImpl, val probleme: TaskLoadProblems)

  /**
   * Laedt wie das Programm laedt: derselbe TaskLoader, dieselbe loadDependencyGraph-Funktion,
   * dieselbe Reihenfolge wie TaskTagHandler.process() (TaskTagHandler.java:43-51) -- EINSCHLIESSLICH
   * des `TaskLoadProblems`, das TaskTagHandler seit der Behebung an ProxyDocument herausgibt.
   */
  private fun ladenWieDasProgramm(xml: String): Ladung {
    val projekt = GanttProjectImpl()
    val xmlProject = parseXmlProject(xml)
    val loader = TaskLoader(projekt.taskManager, SimpleTreeCollapseView())
    loader.loadTaskCustomPropertyDefinitions(xmlProject)
    xmlProject.walkTasksDepthFirst { parent, child -> loader.loadTask(parent, child); true }
    loadDependencyGraph(loader.dependencies, projekt.taskManager, loader.legacyFixedStartTasks, loader.taskLoadProblems)
    return Ladung(projekt, loader.taskLoadProblems)
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
    val ladung = ladenWieDasProgramm(basisGan)
    val taskB = ladung.projekt.taskManager.getTask(1)
    assertEquals(1, taskB.dependenciesAsDependee.toArray().size,
      "Vorbedingung: Aufgabe B muss nach dem Laden genau eine Abhaengigkeit im Modell tragen")
    assertNull(ladung.probleme.message(),
      "eine Datei ohne jeden Fehler darf keine Meldung erzeugen -- eine, die immer kommt, liest niemand")

    val wieder = saveToXml(ladung.projekt)
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
  fun `k3a -- die haengende Abhaengigkeit fehlt weiterhin im Modell, wird aber jetzt gemeldet`() {
    assertEquals(1, Regex("<depend id=\"999\"").findAll(k3aHaengendeAbhaengigkeit).count(),
      "Vorbedingung: die Quelldatei muss die haengende Abhaengigkeit tragen, sonst misst dieser Test nichts")

    val ladung = ladenWieDasProgramm(k3aHaengendeAbhaengigkeit)
    val taskB = ladung.projekt.taskManager.getTask(1)
    assertEquals(0, taskB.dependenciesAsDependee.toArray().size,
      "es gibt keine Aufgabe 999, der die Abhaengigkeit angehaengt werden koennte -- " +
        "loadDependencyGraph (TaskSerializer.kt) kann sie weiterhin nicht ins Modell aufnehmen")
    val taskC = ladung.projekt.taskManager.getTask(2)
    assertEquals(1, taskC.dependenciesAsDependee.toArray().size,
      "die GUELTIGE Abhaengigkeit (C->B) muss dagegen im Modell stehen")

    // Das ist der eigentliche Unterschied zu vorher: die haengende Abhaengigkeit ist jetzt
    // GEMELDET, nicht nur verschwunden.
    assertEquals(1, ladung.probleme.problems.size,
      "genau ein Problem (die haengende Abhaengigkeit von Aufgabe B) muss gesammelt sein")
    val meldung = ladung.probleme.message()
    assertNotNull(meldung, "eine haengende Abhaengigkeit und keine Meldung -- wieder stumm")
    assertTrue(meldung!!.contains("Aufgabe B") && meldung.contains("999"),
      "die Meldung muss die betroffene Aufgabe (\"Aufgabe B\") UND die fehlende Ziel-id " +
        "(\"999\") nennen, sonst kann niemand die Datei reparieren:\n$meldung")
  }

  @Test
  fun `k3a -- beim Speichern bleibt die haengende Abhaengigkeit draussen, weil sie nie ins Modell konnte`() {
    val ladung = ladenWieDasProgramm(k3aHaengendeAbhaengigkeit)
    val wieder = saveToXml(ladung.projekt)

    assertFalse(wieder.contains("id=\"999\""),
      "die haengende Abhaengigkeit darf nach dem Speichern nicht mehr in der Datei stehen -- " +
        "es gibt keine Aufgabe 999, der sie angehaengt werden koennte:\n$wieder")
    assertEquals(1, Regex("<depend ").findAll(wieder).count(),
      "von den zwei <depend>-Elementen der Quelldatei darf nach dem Rundlauf nur die GUELTIGE " +
        "(C->B) uebrig sein, nicht die haengende:\n$wieder")

    // Der Datenverlust ist nicht auf eine einzelne Reparatur begrenzt: wird die einmal
    // geschriebene (bereits bereinigte) Datei ERNEUT geladen und gespeichert, bleibt sie
    // identisch. Das war schon vor der Behebung so und bleibt so -- die Entscheidung dieses
    // Auftrags ist LAUT MELDEN, nicht das Loch erfinden oder das Oeffnen verweigern.
    val zweiteLadung = ladenWieDasProgramm(wieder)
    val zweiterRundlauf = saveToXml(zweiteLadung.projekt)
    assertEquals(wieder.replace(Regex("<project [^>]*>"), ""),
      zweiterRundlauf.replace(Regex("<project [^>]*>"), ""),
      "ein zweiter Rundlauf veraendert die bereits bereinigte Datei nicht mehr -- der fehlende " +
        "Vorgang ist beim ERSTEN Speichern endgueltig weg, nicht erst nach mehreren")
    // Die bereinigte Datei hat keine haengende Abhaengigkeit mehr -- also auch keine Meldung beim
    // zweiten Laden. Die Meldung ist kein Dauerzustand der Datei, sondern haengt am Fund.
    assertNull(zweiteLadung.probleme.message(),
      "die bereits bereinigte Datei darf beim erneuten Laden keine Meldung mehr erzeugen")
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
  fun `k3f -- der falsch getypte Wert wird weiterhin als false geladen, aber jetzt gemeldet`() {
    val ladung = ladenWieDasProgramm(k3fFalscherSpaltentyp)
    val taskA = ladung.projekt.taskManager.getTask(0)
    val propDef = ladung.projekt.taskManager.customPropertyManager.definitions.single { it.id == "tpc0" }

    assertTrue(taskA.customValues.hasOwnValue(propDef),
      "Aufgabe A muss nach dem Laden einen EIGENEN Wert fuer die Spalte tragen (nicht den Vorgabewert)")
    assertEquals(false, taskA.customValues.getValue(propDef),
      "die Spalte ist vom Typ boolean und bietet keinen anderen Wert an als true/false -- " +
        "java.lang.Boolean.valueOf(\"apfelsine\") liefert weiterhin false, das aendert die " +
        "Behebung bewusst NICHT (TaskSerializer.kt, loadCustomProperties)")

    // Das ist der Unterschied zu vorher: die Umwandlung ist jetzt GEMELDET.
    assertEquals(1, ladung.probleme.problems.size,
      "genau ein Problem (die Spalte \"Erledigt\" von Aufgabe A) muss gesammelt sein")
    val meldung = ladung.probleme.message()
    assertNotNull(meldung, "ein falsch getypter Wert und keine Meldung -- wieder stumm")
    assertTrue(meldung!!.contains("Aufgabe A") && meldung.contains("Erledigt") && meldung.contains("apfelsine"),
      "die Meldung muss die Aufgabe (\"Aufgabe A\"), die Spalte (\"Erledigt\") UND den " +
        "Ursprungstext (\"apfelsine\") nennen, sonst kann niemand die Datei reparieren:\n$meldung")
  }

  @Test
  fun `k3f -- beim Speichern steht false in der Datei, der Ursprungstext ist weg`() {
    val ladung = ladenWieDasProgramm(k3fFalscherSpaltentyp)
    val wieder = saveToXml(ladung.projekt)

    assertFalse(wieder.contains("apfelsine"),
      "der Ursprungstext \"apfelsine\" darf nach dem Speichern nicht mehr in der Datei stehen:\n$wieder")
    assertTrue(wieder.contains("taskproperty-id=\"tpc0\" value=\"false\""),
      "an seiner Stelle muss die Datei jetzt value=\"false\" tragen:\n$wieder")
  }

  @Test
  fun `k3a und k3f zusammen ergeben EINE Meldung, die beide Fundstellen nennt`() {
    // Eine von Hand oder von einem anderen Werkzeug beschaedigte Datei zeigt selten nur einen
    // Fehler. Wer beide Probleme hat, soll EINE Benachrichtigung sehen, nicht zwei Fenster --
    // genau das Verhalten, das fork.vacation.* fuer mehrere verdrehte Urlaube schon hat.
    val k3aUndK3f = k3fFalscherSpaltentyp.replace(
      "<depend id=\"0\" type=\"2\" difference=\"0\" hardness=\"Strong\"/>",
      "<depend id=\"999\" type=\"2\" difference=\"0\" hardness=\"Strong\"/>")
    assertTrue(k3aUndK3f.contains("apfelsine") && k3aUndK3f.contains("id=\"999\""),
      "Vorbedingung: die zusammengesetzte Datei muss beide Fehler tragen")

    val probleme = ladenWieDasProgramm(k3aUndK3f).probleme
    assertEquals(2, probleme.problems.size, "beide Probleme (Abhaengigkeit UND Spaltenwert) muessen gesammelt sein")
    val meldung = probleme.message()
    assertNotNull(meldung, "zwei Probleme und keine Meldung")
    assertTrue(meldung!!.contains("999") && meldung.contains("apfelsine"),
      "die EINE Meldung muss beide Fundstellen nennen, nicht nur eine:\n$meldung")
  }

  // ------------------------------------------------------------------------------------------
  // Negativkontrolle: ein Verlust, den ICH erzeuge, muss der Messweg auch SEHEN -- sonst zaehlt
  // das "Element weg" oben nicht als Befund, sondern koennte ein blinder Messweg sein.
  // ------------------------------------------------------------------------------------------

  @Test
  fun `negativkontrolle -- eine selbst geloeschte Abhaengigkeit verschwindet ebenfalls aus der Datei`() {
    val ladung = ladenWieDasProgramm(basisGan)
    val taskB = ladung.projekt.taskManager.getTask(1)
    val abhaengigkeiten = taskB.dependenciesAsDependee.toArray()
    assertEquals(1, abhaengigkeiten.size, "Vorbedingung fuer die Negativkontrolle")
    assertNull(ladung.probleme.message(),
      "eine gewoehnliche, gueltige Abhaengigkeit darf keine Meldung erzeugen")

    abhaengigkeiten[0].delete()
    assertEquals(0, taskB.dependenciesAsDependee.toArray().size,
      "die Abhaengigkeit muss nach dem expliziten delete() aus dem Modell verschwunden sein")

    val wieder = saveToXml(ladung.projekt)
    assertEquals(1, Regex("<depend ").findAll(wieder).count(),
      "von den zwei Abhaengigkeiten der Quelldatei darf nach dem SELBST erzeugten Loeschen nur " +
        "noch die unberuehrte (C->B) in der Datei stehen -- sieht der Messweg das nicht, ist er " +
        "blind fuer Verlust und das 'weg' bei k3a waere kein Befund:\n$wieder")
  }
}
