# Arbeitsnotizen für Claude

**Zweck dieser Datei:** Claude beginnt jede Sitzung ohne Erinnerung und mit leerem Container.
Hier steht, was bereits herausgefunden wurde, damit Sackgassen nicht wiederholt werden.
**Claude schreibt diese Datei am Ende jeder Sitzung fort.**

Zuletzt geändert: 10.08.2026 (Sitzung 3, erste lokale Sitzung mit Claude Code)

---

## 1. Was dieses Repo ist

Privater Spiegel von `bardsoftware/ganttproject` (nicht Fork — Forks öffentlicher Repos sind
bei GitHub zwangsweise öffentlich). 285 Branches, 66 Tags.

Original als zweite Quelle nachtragen, falls noch nicht geschehen:

```
git remote add upstream https://github.com/bardsoftware/ganttproject.git
```

**Ziel:** effort-driven scheduling und Ressourcen-Kapazitätsplanung nachrüsten.
GanttProject kann beides nicht — siehe Abschnitt 3.

---

## 2. Umgebung einrichten (der Container ist jedes Mal leer)

Dieser Ablauf ist geprüft und funktioniert. Ohne ihn scheitert der Build.

```bash
# 1. JDK-Compiler nachinstallieren (Container hat nur die JRE)
apt-get update && apt-get install -y openjdk-21-jdk-headless

# 2. JDK MIT JavaFX holen — das Ubuntu-JDK hat kein JavaFX,
#    der Build scheitert sonst mit "Unresolved reference 'javafx'"
cd /tmp
curl -sL -o zulu.tar.gz \
  "https://cdn.azul.com/zulu/bin/zulu21.44.17-ca-fx-jdk21.0.8-linux_x64.tar.gz"
tar xzf zulu.tar.gz
Z=/tmp/zulu21.44.17-ca-fx-jdk21.0.8-linux_x64

# 3. Zertifikate übernehmen — sonst bricht :ganttproject:downloadSass ab mit
#    "PKIX path building failed" (der Egress-Proxy ist dem neuen JDK unbekannt)
cp /etc/ssl/certs/java/cacerts $Z/lib/security/cacerts

# 4. Für jeden Gradle-Aufruf setzen
export JAVA_HOME=$Z
export PATH="$JAVA_HOME/bin:$PATH"
```

**Gradle-Aufrufe abgekoppelt starten**, sonst sterben sie am Zeitlimit des Werkzeugs:

```bash
nohup setsid ./gradlew --no-daemon <task> > /tmp/x.log 2>&1 < /dev/null &
# danach in getrennten Aufrufen pollen
```

`disown` gibt es in der sh dieses Containers nicht — `setsid` reicht.

### Laufzeiten (gemessen, Sitzung 1)

| Aufgabe | Dauer |
|---|---|
| `:biz.ganttproject.core:compileKotlin` | ~1,5 min |
| `:ganttproject:compileKotlin compileJava` | ~1 min (nach Warmlauf) |
| gezielte Tests (siehe unten) | ~2 min |
| `build -x test` (alle Module) | > 10 min, in Sitzung 1 nie durchgelaufen |

Vollen Build nur starten, wenn wirklich nötig.

---

## 3. Der eigentliche Befund

### getLoad() beeinflusst KEINE Termine

`ResourceAssignment` hat `getLoad()` / `setLoad(float)` — die Auslastung in Prozent ist im
Datenmodell vorhanden. Sie wird verwendet in:

- `ProjectFileExporter` (MS-Project-Export)
- `ResourceAssignmentCollectionImpl` (Kopieren von Zuweisungen)
- `AllocationTagHandler` (Laden aus der Datei)
- `TaskManagerImpl` (Kopieren)
- `CostAlgorithmImpl` (Kostenrechnung)

**Kein einziger Aufruf im Scheduler.** Die Auslastung ist reine Buchhaltung. Ändert man sie,
passiert mit der Dauer nichts. Das ist Upstream-Issue #83 („Support effort-driven scheduling"),
offen seit 2013, mit Issue 813 zusammengelegt.

### Zwei getrennte Probleme, nicht verwechseln

1. **Effort-driven scheduling:** Dauer *einer* Aufgabe aus ihrem eigenen Aufwand und der
   Verfügbarkeit rechnen. Beispiel: 10 Tage bei 2 h/Tag → auf 4 h/Tag umgestellt → 5 Tage.
2. **Kapazitätsverteilung (levelling):** verhindern, dass zwanzig Aufgaben gleichzeitig je
   2 h/Tag derselben Person verlangen. Das ist das eigentliche Problem des Nutzers.

Punkt 1 ohne Punkt 2 lässt Überlastung unbemerkt. Der Nutzer will beides, mit einer Abfrage
beim Konflikt (siehe Abschnitt 5).

---

## 4. Relevante Dateien

| Datei | Zeilen | Rolle |
|---|---|---|
| `ganttproject/.../task/algorithm/SchedulerImpl.java` | 228 | Scheduler-Kern |
| `ganttproject/.../task/algorithm/Schedulers.kt` | | Scheduler-Verdrahtung |
| `ganttproject/.../task/ResourceAssignment.java` | | `getLoad`/`setLoad` |
| `ganttproject/.../task/ResourceAssignmentCollectionImpl.java` | | Zuweisungsverwaltung |
| `ganttproject/.../task/MutableTask.java` | Z. 44 | `setDuration(TimeDuration)` |
| `ganttproject/.../task/TaskMutator.java` | | erweitert `MutableTask`, **kein** eigenes setDuration |
| `biz.ganttproject.core/.../time/` | | Zeiteinheiten (`TimeUnitStack` u. a.) |

**Sackgasse vermeiden:** In `TaskMutator` nach `setDuration` zu suchen bringt nichts — die
Methode steckt in `MutableTask`.

### Wie SchedulerImpl arbeitet (gelesen, Sitzung 1)

`doRun()` → `schedule(Node)` je Knoten des Abhängigkeitsgraphen. Aus den eingehenden Kanten
werden mit Guava-`Range` erlaubte Start- und Endbereiche geschnitten (stark/schwach getrennt,
Teilaufgaben gesondert). Danach:

- `modifyTaskStart(task, newStart)` — bei Aufgaben **ohne** Unteraufgaben über
  `task.createShiftMutator()` und `shift(...)`: **die Dauer bleibt erhalten**, die Aufgabe
  wird nur verschoben. Bei Aufgaben mit Unteraufgaben über `setStart`.
- `modifyTaskEnd(task, newEnd)` — `mutator.setEnd(...)`.

**Wichtigste Erkenntnis:** Der Scheduler *propagiert* nur Termine durch den Graphen. Er
berechnet **nie** eine Dauer aus Aufwand. Effort-driven scheduling ist deshalb kein Umbau des
Schedulers, sondern ein **zusätzlicher Schritt davor**, der die Dauer setzt; danach propagiert
der vorhandene Scheduler wie bisher weiter.

### Einhängepunkt für einen neuen Algorithmus

`AlgorithmCollection` sammelt die Algorithmen, u. a.:
`RecalculateTaskScheduleAlgorithm`, `AdjustTaskBoundsAlgorithm`,
`RecalculateTaskCompletionPercentageAlgorithm`, `CriticalPathAlgorithm`, `myScheduler`.

Ein neuer `EffortDrivenDurationAlgorithm` (Arbeitstitel) würde sich hier einreihen —
Vorbild ist `RecalculateTaskCompletionPercentageAlgorithm`, das strukturell dasselbe tut:
einen abgeleiteten Wert aus anderen Feldern neu berechnen.

### Tests, die es schon gibt

```bash
./gradlew --no-daemon :ganttproject-tester:test \
  --tests "*SchedulerTest*" --tests "*TestResourceAssignments*"
```

Sitzung 1: **20 Tests, 0 Fehler** (SchedulerTest 13, TestResourceAssignments 7).
Ergebnisse als XML unter `ganttproject-tester/build/test-results/test/`.

**Diese Tests laufen ohne Bildschirm.** Die Rechenlogik ist damit prüfbar, auch wenn die
JavaFX-Oberfläche im Container nicht startbar ist. Oberflächenprüfung übernimmt der Nutzer
per Screenshot oder Computer-Use.

### Vorlage: RecalculateTaskCompletionPercentageAlgorithm (gelesen, Sitzung 1)

Sehr kurz und genau das Muster, das wir brauchen:

```java
public abstract class RecalculateTaskCompletionPercentageAlgorithm extends AlgorithmBase {
  @Override public void run() {
    if (!isEnabled()) return;
    TaskContainmentHierarchyFacade facade = createContainmentFacade();
    recalculate...(facade.getRootTask(), facade);
  }
  // rekursiv ueber die Hierarchie, Aenderung ueber:
  //   var mutator = task.createMutator();
  //   mutator.setCompletionPercentage(x);
  //   mutator.commit();
  protected abstract TaskContainmentHierarchyFacade createContainmentFacade();
}
```

Fuer uns analog: ueber die Blattaufgaben laufen, Dauer aus Aufwand und Verfuegbarkeit rechnen,
`mutator.setDuration(...)`, committen. Danach laeuft der vorhandene Scheduler.

### Ereigniskette bei Zuweisungsaenderung (verfolgt, Sitzung 1)

```
HumanResource.createAssignment() / setLoad() / swapAssignments()
  -> fireAssignmentsChanged()
  -> HumanResourceManager.fireAssignmentsChanged(resource)
  -> ResourceView.resourceAssignmentsChanged(ResourceEvent) an alle Views
```

Hoerer heute: `GanttProject.java` (setzt nur `setAskForSave(true)`),
`ResourceLoadGraphicArea`, `ResourceTable.kt`.

**Wichtig: Kein Hoerer stoesst einen Algorithmus an.** Eine Zuweisungsaenderung loest heute
KEINE Neuberechnung aus — nur „Datei geaendert". Genau hier muss der neue Algorithmus
angehaengt werden.

Zum Vergleich, wer Algorithmen heute anstoesst: `TaskManagerImpl` (u. a. `processCriticalPath`),
`WeekendsSettingsPanel` (Wochenendwechsel), die MS-Project-Importer.

`setLoad(...)` wird aufgerufen in `AssignmentToggleAction` (fest auf 100) und
`ClipboardTaskProcessor` (kopieren). Bearbeitet wird die Auslastung in
`gui/taskproperties/TaskResourcesPanel.kt`.

---

## 5. Was gebaut werden soll (Vorgabe des Nutzers)

Bei Überlastung einer Ressource soll eine **Abfrage** erscheinen mit drei Auflösungen:

1. **Gleich verteilen** — Arbeit gleichmäßig auf die konkurrierenden Aufgaben aufteilen
2. **Einer Aufgabe zuschlagen/abziehen** — eine Aufgabe trägt die Differenz
3. **Nach Priorität** — Priorität ist eine **fortlaufende Reihenfolgenummer je Aufgabe**
   (1, 2, 3 …), **keine Stufe** wie hoch/mittel/niedrig. Wichtig: GanttProject hat ein
   Prioritätsfeld mit Stufen — das ist etwas anderes und reicht nicht.

Ressource bekommt eine einstellbare Stundenzahl pro Tag.

---

## 6. Arbeitsregeln des Nutzers (gelten auch hier)

- Nicht raten, sondern fragen.
- Behauptungen prüfen, auch die über eigene Fähigkeiten.
- **Jede Prüfung gegentesten:** absichtlich einen Fall erzeugen, in dem sie anschlagen muss.
  Eine Prüfung, die nie gescheitert ist, ist wertlos.
- Automatische Prüfungen finden Formfehler, keine Sinnfehler — zusätzlich von Hand prüfen,
  ob das Ergebnis inhaltlich stimmt.
- Geschätzte Zahlen als Schätzung kennzeichnen und sagen, von wem sie stammen.
- Antworten auf Deutsch. Anrede: Natalie (rechtlicher Name Oliver Frank).
- **Logzeiten immer mit Zeitzonenhinweis melden.** `ganttproject.log` schreibt in UTC, die
  Systemuhr steht auf MESZ — 2 Stunden Versatz. In Sitzung 3 führte das Gleichsetzen beider
  Zeiten zu einer falschen Schlussfolgerung („du hast die alte Version getestet"), die Natalie
  korrigieren musste. Vorschlag stammt von Natalie.

---

## 7. Stand und nächste Schritte

**Erledigt (Sitzung 1):**
- Spiegel angelegt und gepusht
- Build zum Laufen gebracht, beide Hindernisse dokumentiert (JavaFX, Zertifikate)
- Testsuite im Zielbereich läuft grün
- Ansatzpunkt gefunden: `getLoad()` ohne Wirkung auf Termine
- `SchedulerImpl` gelesen: propagiert nur Termine, berechnet nie Dauer → neuer Schritt davor
- Einhängepunkt gefunden: `AlgorithmCollection`
- Vorlage gelesen, Ereigniskette verfolgt: Zuweisungsänderung stößt heute nichts an

**Offen:**
- [ ] Default-Branch im Repo auf `master` stellen (macht der Nutzer, Token hat kein Adminrecht)
- [ ] Entscheiden, wo der Aufwand in Stunden gespeichert wird — siehe Abschnitt 8
- [ ] `HumanResource`: Feld „Stunden pro Tag" ergänzen (heute gibt es nur Auslastung in %)
- [ ] Konfliktabfrage entwerfen (Abschnitt 5) — braucht die Reihenfolgenummer je Aufgabe
- [ ] Entscheiden, wo der Aufwand in Stunden gespeichert wird (neues Feld oder vorhandene
      Struktur) — betrifft auch das Dateiformat und damit die Abwärtskompatibilität
- [ ] Erst danach: Umfangsbericht statt geratener Aufwandszahl

---

## 9. Sitzung 2 — Stufe 1 begonnen

### Entwurfsfrage ENTSCHIEDEN: Custom Properties

Im Code nachgewiesen, nicht vermutet:

- `TaskSaver.kt` schreibt eine **fest verdrahtete Liste** von Attributen aus dem Modell.
  Ein neues XML-Attribut würde von der Original-GanttProject-Version beim Speichern
  **stillschweigend verworfen** — Daten weg, ohne Fehlermeldung.
- Custom Properties dagegen werden regulär geschrieben: `TaskSaver` läuft über
  `customPropertyManager.definitions` und schreibt `<customproperty taskproperty-id=... value=...>`.
  Das Original kennt das als eigenes Feature.

→ **Runde Fork → Original → Fork überlebt.** Vorgabe der Nutzerin („kompatibel zum normalen
GanttProject") ist damit erfüllt.

`HumanResource` implementiert `CustomPropertyHolder`, Ressourcen können also ebenfalls
Custom Properties tragen.

**Bekannte Nebenwirkungen:**
- Aufwand erscheint im Original als normale Spalte, dort editierbar, aber ohne Wirkung.
- Ändert jemand im Original die Dauer, passen Aufwand und Dauer nicht mehr zusammen.
  Der Algorithmus muss das beim Öffnen bemerken und auflösen — **noch nicht umgesetzt**.

### Was auf Branch `effort-driven` liegt

`EffortDrivenDurationAlgorithm.kt` (Paket `task.algorithm`):

- `EffortDrivenProperties` — Namen der beiden Custom Properties, Standardwert 8 h/Tag,
  `findOrCreate...`-Helfer
- `Task.effortHours(...)` — Aufwand oder `null`; ohne Aufwand bleibt die Dauer unangetastet,
  das Feature ist **je Aufgabe opt-in**
- `HumanResource.hoursPerDay(...)` — mit Rückfall auf 8, damit alte Projekte weiterlaufen
- `Task.availableHoursPerDay(...)` — Summe über Zuweisungen, gewichtet mit `load`
- `computeDurationDays(effort, availability)` — **bewusst modellfrei**, damit unittestbar:
  `max(1, ceil(effort / availability))`, wirft bei Werten <= 0

`EffortDrivenDurationTest.kt`: **11 Tests, alle grün.** Darunter vier Negativfälle
(null/negativer Aufwand, null/negative Verfügbarkeit).

**Gegentest durchgeführt:** Aufrunden absichtlich durch Abrunden ersetzt →
`testPartialDayIsRoundedUp` schlug fehl, Sabotage zurückgenommen, wieder 11/11 grün.
Die Tests sind also nachweislich wirksam.

### Lücke geschlossen: Modelltests

`EffortDrivenModelTest.kt` — **11 Tests, alle grün.** Aufbau nach Vorbild
`TestResourceAssignments`: echter `TaskManager` über `TaskManager.Access.newInstance` mit
anonymem `TaskManagerConfig`, `HumanResourceManager` mit eigenem `CustomColumnsManager`.

**Wichtig für künftige Tests:** Aufgaben- und Ressourcen-Properties brauchen **getrennte**
`CustomColumnsManager`-Instanzen. Werte setzen über `task.customValues.setValue(def, wert)`
bzw. `resource.setValue(def, wert)`, lesen über `getValue` bzw. `getCustomField`.

Abgedeckt: Aufwand nicht gesetzt → `null`; Aufwand lesen; Null-Aufwand gilt als nicht gesetzt;
Ressource ohne Wert → Rückfall 8 h; Tagesstunden lesen; nichtpositive Werte → Rückfall;
keine Zuweisung → 0 h verfügbar; eine Zuweisung; **Auslastung wird angewendet**; zwei
Zuweisungen addieren sich; Beispiel der Vorgabe durchs ganze Modell (20 h bei 2 h/Tag = 10 Tage,
bei 4 h/Tag = 5 Tage).

**Gegentest:** Auslastungsgewichtung absichtlich entfernt (`* load / 100.0` gestrichen) →
`testLoadIsApplied` schlug fehl, danach zurückgenommen, wieder 22/22 grün über beide Klassen.

### Nächste Schritte für Stufe 1

- [ ] `EffortDrivenDurationAlgorithm` als echte `AlgorithmBase`-Klasse (Vorlage:
      `RecalculateTaskCompletionPercentageAlgorithm`), die über die Blattaufgaben läuft und
      `mutator.setDuration(...)` setzt
- [ ] In `AlgorithmCollection` einreihen
- [ ] Auslöser: an `resourceAssignmentsChanged` hängen (heute setzt der Hörer in
      `GanttProject.java` nur `setAskForSave(true)`)
- [ ] Oberfläche: Spalten in `gui/taskproperties/TaskResourcesPanel.kt`
- [ ] Konflikt Dauer/Aufwand nach Bearbeitung im Original auflösen

---

## 10. Sitzung 3 — erste lokale Sitzung (Claude Code auf dem Rechner der Nutzerin)

### Umgebung: gelöst, einmalig einzurichten

Der Klon liegt unter `C:\Users\ofran\Documents\GitHub\ganttproject-resource-planer`.

**Das JDK war der Blocker.** `JAVA_HOME` zeigte auf Microsoft OpenJDK 21 — ein JDK **ohne**
JavaFX. Damit scheitert `:biz.ganttproject.core:compileKotlin` reproduzierbar an
`Unresolved reference 'javafx'` (in Sitzung 3 einmal vollständig durchlaufen und belegt).

Wichtige Unterscheidung, die Zeit spart:
- Modul `ganttproject` wendet `org.openjfx.javafxplugin` an, holt JavaFX also über Gradle.
- Modul **`biz.ganttproject.core` tut das nicht** und erwartet JavaFX **aus dem JDK**.
  Deshalb genügt ein normales JDK nicht, obwohl das Plugin im Projekt vorkommt.

Gelöst mit BellSoft Liberica **Full** JDK 21 (enthält JavaFX):

```
C:\Users\ofran\jdks\jdk-21.0.12-full
```

Geprüft: `java --list-modules` zeigt javafx.base/controls/fxml/graphics/media/swing/web 21.0.12.

**Für jeden Gradle-Aufruf setzen** (PowerShell), Arbeitsverzeichnis explizit setzen, weil
Hintergrundaufrufe sonst in `C:\` landen und `gradlew.bat` nicht finden:

```powershell
Set-Location "C:\Users\ofran\Documents\GitHub\ganttproject-resource-planer"
$env:JAVA_HOME = "C:\Users\ofran\jdks\jdk-21.0.12-full"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat --no-daemon :ganttproject-tester:test --tests "*EffortDriven*"
```

Laufzeit gemessen: Erstlauf mit Kompilierung ~2,5 min, danach ~25 s.
`curl` in der Git-Bash scheitert an Zertifikaten — für Downloads PowerShell nehmen.

### Schritt 1 erledigt: EffortDrivenDurationAlgorithm ist eine echte Algorithmusklasse

In `EffortDrivenDurationAlgorithm.kt` ergänzt: `abstract class EffortDrivenDurationAlgorithm`
über `AlgorithmBase`, Vorbild `RecalculateTaskCompletionPercentageAlgorithm`.

- läuft rekursiv über die Hierarchie, rechnet **nur auf Blattaufgaben**
- überspringt Aufgaben ohne Aufwand (opt-in) und ohne Verfügbarkeit (nichts zugewiesen)
- schreibt über `task.createMutator()` / `setDuration` / `commit()`
- **schreibt nicht, wenn sich die Dauer nicht ändert** — Vorsorge gegen Rückkopplung,
  wenn der Algorithmus in Schritt 3 an Ereignisse gehängt wird
- meldet jede geänderte Aufgabe über `diagnostic.addModifiedTask(...)`

Neu: `EffortDrivenAlgorithmTest.kt`, 5 Tests.
**Stand: 27 Tests grün** (Algorithm 5, Duration 11, Model 11).

### Befund aus dem Gegentest: Container sind vom Modell geschützt — Tests darauf sind blind

Der erste Container-Test prüfte, ob die **Dauer** des Containers unverändert bleibt.
Gegentest (Blattprüfung entfernt, Container wie Blätter behandelt): **Test blieb grün.**

Grund, empirisch belegt: Der sabotierte Code rief `setDuration(40 Tage)` samt `commit()` auf
dem Container auf — die Dauer blieb trotzdem stehen. **GanttProject verwirft eine auf einen
Container geschriebene Dauer selbst.** Der Test prüfte also eine Zusicherung von GanttProject,
nicht den eigenen Blattfilter. Nach Arbeitsregel wertlos.

**Reparatur:** Der Test prüft jetzt über den `Diagnostic`-Haken von `AlgorithmBase`, **welche**
Aufgaben der Algorithmus anfasst, statt was am Modell hängen bleibt. Gegentest wiederholt:
`testContainerIsNotTouched` schlug fehl („the container must not be touched"), Sabotage
zurückgenommen, wieder grün.

**Übertragbare Lehre:** Wirkung am Modell zu messen ist trügerisch, wo das Modell selbst
korrigiert. Was der eigene Code *entscheidet*, muss beobachtbar gemacht werden.

### Schritt 2 erledigt: in AlgorithmCollection eingereiht

- `AlgorithmCollection`: Feld, Konstruktorparameter und
  `getEffortDrivenDurationAlgorithm()` ergänzt. **Nur ein Aufrufer** des Konstruktors
  (`TaskManagerImpl`), daher überschaubar.
- `TaskManagerImpl` (~Z. 242): anonyme Unterklasse wie bei den anderen Algorithmen,
  `createContainmentFacade()` liefert `getTaskHierarchy()`.

**Wichtig — Ressourcen-Properties erst zur Laufzeit auflösen.** `getResourceManager()` darf
**null** sein (Tests, kopfloser Import) und wird erst nach dem TaskManager verdrahtet. Der
Konstruktor nimmt deshalb einen `Supplier<CustomPropertyManager?>`, aufgelöst in jedem `run()`;
ist er null, tut der Algorithmus nichts. Vorbild für das Muster: `myHierarchySupplier` in
`TaskManagerImpl` Z. 82. Ohne diesen Umweg wäre der Konstruktor auf einen halb fertigen
Projektzustand angewiesen.

**Reihenfolge (offene Frage der Übergabe, jetzt festgehalten):** Der Algorithmus muss **vor**
dem Scheduler laufen — er setzt Dauern, der Scheduler propagiert danach Termine. Das steht als
Kommentar am Getter. **Noch nicht erzwungen**, weil noch niemand ihn aufruft; das entscheidet
sich in Schritt 3.

Tests: 48 grün — EffortDriven 6/11/11, dazu **SchedulerTest 13 und TestResourceAssignments 7
ohne Regression** (mitgelaufen, weil `TaskManagerImpl` angefasst wurde).

**Gegentest 3:** Null-Schutz entfernt (`?: return` durch `!!` ersetzt) →
`testWithoutResourcePropertiesNothingHappens` schlug mit `NullPointerException` fehl,
Sabotage zurückgenommen.

### Schritt 3 erledigt: Auslöser — das Feature wirkt jetzt

Neu: `EffortDrivenTrigger.kt` — eine Klasse, die `ResourceView` umsetzt und über
`humanResourceManager.addView(...)` registriert wird. Registriert in **`GanttProjectImpl`**
(init-Block), nicht in `GanttProject.java`.

**Warum nicht wie in der Übergabe vorgeschlagen in `GanttProject.java`:** Das ist die
Oberflächenklasse; alles darin ist headless nicht prüfbar. `addView(ResourceView)` ist
öffentlich, `GanttProjectImpl` hält beide Manager und ist oberflächenfrei. Damit wirkt der
Auslöser auch bei Import und Kommandozeile — und ist testbar.

**Korrektur an der Übergabe — wichtig:** Die Übergabe nennt nur `resourceAssignmentsChanged`.
Das **verfehlt das Kernbeispiel der Nutzerin**. Im Code nachgewiesen:
`HumanResource.setValue(...)` (Z. 205–207) feuert `fireResourceChanged()`. Das Umstellen der
Tagesstunden ist also **kein** Zuweisungsereignis. Der Auslöser hört deshalb auf drei Ereignisse:

| Ereignis | Fall |
|---|---|
| `resourceChanged` | Tagesstunden bearbeitet — **das Kernbeispiel** (auf 4 h/Tag → 5 Tage) |
| `resourceAssignmentsChanged` | Zuweisung hinzu/entfernt, Auslastung geändert |
| `resourcesRemoved` | von zwei zugewiesenen Personen fällt eine weg → Verfügbarkeit halbiert |

`resourceAdded` bleibt bewusst leer: eine neue Ressource trägt noch keine Zuweisung.

**Reihenfolge jetzt erzwungen:** erst `effortDrivenDurationAlgorithm`, dann `scheduler`.
**Schleifenschutz:** `isRunning` mit `try/finally`, wörtlich das Muster aus `SchedulerImpl`.

Neu: `EffortDrivenTriggerTest.kt`, 3 Tests, **alle über echte Ereignisse**, kein direkter
Aufruf des Algorithmus. Darunter das Beispiel der Vorgabe von Anfang bis Ende und der Fall
„Zuweisung entfernt → Aufgabe wird länger".

**Stand: das gesamte Testmodul ist grün — 300 Tests, 0 Fehler.**
(Voller Modullauf `:ganttproject-tester:test`, ~30 s. War angebracht, weil `GanttProjectImpl`
eine zentrale Klasse ist.)

**Gegentest 4:** `resourceChanged` auf leer gesetzt → `testChangingDailyHoursChangesTheDuration`
schlug fehl (`expected:<10> but was:<3>`), Sabotage zurückgenommen.

### Falle für künftige Tests: Endlosrekursion in anonymen TaskManagerConfig-Objekten

Beim ersten Lauf gab es `StackOverflowError` in **allen drei** neuen Tests. Ursache lag im
Test, nicht im Produktionscode:

```kotlin
override fun getResourceManager(): HumanResourceManager = resourceManager  // ruft sich selbst!
```

In Kotlin löst `resourceManager` auf die synthetische Eigenschaft **des anonymen Objekts** auf,
also auf genau diesen Getter. Richtig ist die Qualifizierung:

```kotlin
override fun getResourceManager() = this@EffortDrivenTriggerTest.resourceManager
```

Die anderen Testklassen sind nicht betroffen, weil sie `null` zurückgeben.

### Lücke geschlossen: ist der Auslöser überhaupt eingebaut?

`EffortDrivenTriggerTest` registriert den Auslöser **selbst**. Damit ist bewiesen, dass er
funktioniert — **nicht**, dass ihn jemals jemand einschaltet. Die Registrierung in
`GanttProjectImpl` war durch nichts geprüft.

Neu: `EffortDrivenProjectWiringTest.kt` (1 Test). Er baut ein echtes `GanttProjectImpl`,
ändert **nur** die Tagesstunden der Ressource und erwartet, dass die Dauer von selbst folgt.

**Gegentest 5:** Registrierungszeile auskommentiert → **nur dieser eine Test** schlug fehl
(`expected:<10> but was:<1>`), die anderen 31 blieben grün. Genau das war die Gefahr: Eine
gelöschte Zeile hätte das Feature stillgelegt, ohne dass irgendeine Prüfung angeschlagen hätte.

**Stand: 301 Tests grün.**

### Änderungen im Fremdcode sind markiert

Auf Wunsch der Nutzerin trägt **jede** Zeile, die gegenüber dem Original-GanttProject geändert
wurde, die Marke `[Fork-Aenderung]`. Neue Dateien tragen im Kopf
„NEUE DATEI DIESES FORKS". So findet man alles wieder:

```bash
grep -rn "Fork-Aenderung" ganttproject/src
grep -rln "NEUE DATEI DIESES FORKS" ganttproject/src ganttproject-tester/test
```

Geänderte Originaldateien sind bislang nur drei:
`AlgorithmCollection.java`, `TaskManagerImpl.java`, `GanttProjectImpl.kt`.
Alles andere liegt in neuen Dateien — das hält die Angriffsfläche beim Abgleich mit dem
Original klein.

### Stufe 1 von Hand ausprobieren (ohne eigene Oberfläche)

Es gibt noch keine Bedienfelder; Aufwand und Tagesstunden werden als Custom Properties über die
normale Spaltenverwaltung eingetragen:

1. Aufgaben-Spalte anlegen: Name **`effort_hours`**, Typ Dezimalzahl. Wert = Aufwand in Stunden.
2. Ressourcen-Spalte anlegen: Name **`hours_per_day`**, Typ Dezimalzahl. Wert = Stunden pro Tag.
3. Ressource der Aufgabe zuweisen (Auslastung wirkt: 50 % halbiert die Tagesstunden).
4. Tagesstunden ändern → die Dauer der Aufgabe muss sich anpassen.

Erwartung laut Vorgabe: 20 h bei 2 h/Tag = 10 Tage; auf 4 h/Tag = 5 Tage.
Ohne Aufwandswert bleibt alles wie bisher (opt-in je Aufgabe). Ohne `hours_per_day` gilt 8 h.

**Wichtig:** Die Namen müssen exakt so lauten — sie stehen als Konstanten in
`EffortDrivenProperties`.

**Was hier NICHT geprüft ist:** das Verhalten in der laufenden Anwendung. Alle Nachweise sind
Modell- und Projekttests ohne Bildschirm. Ob Eingabe, Anzeige und Neuzeichnen stimmen, muss
Natalie prüfen.

## 11. Sitzung 3, Schritt 4 — Oberfläche

### Zwei Befunde, die den Zuschnitt geändert haben

**1. Die Werte waren schon bedienbar.** Der Aufgabendialog enthält bereits eine Registerkarte
für Custom Properties (`CustomColumnsPanel`, eingehängt in `TaskProperties.kt:51`), und
Ressourcen-Custom-Properties erscheinen als Spalten in der Ressourcentabelle. Schritt 4 war
also **Bequemlichkeit, nicht Fähigkeit** — anders, als die Übergabe nahelegt.

**2. Falle: zwei Editoren für denselben Wert.** `CustomColumnsPanel.save()` (Z. 232–243)
schreibt **alle** Custom Properties aus seiner Tabelle in eine **beim Öffnen gezogene Kopie**
(`task.customValues.copyOf()`) und setzt darüber den gesamten Satz neu. Ein Aufwandsfeld, das
direkt auf `task.customValues` schreibt, würde beim Klick auf OK von dieser veralteten Kopie
**stillschweigend überschrieben** — Datenverlust ohne Fehlermeldung.

**Lösung:** `TaskResourcesPanel.applyEffort(holder)` schreibt in **denselben Halter**, den die
Registerkarte gleich committet. Verdrahtet in `TaskProperties.save()`:

```kotlin
customPropertiesPanel.save {
  resourcesPanel.applyEffort(it)   // [Fork-Aenderung] gleicher Schreibweg
  mutator.setCustomProperties(it)
}
```

### Entscheidung der Nutzerin: Tagesstunden nur anzeigen

Aufwandsfeld im Aufgabendialog (bearbeitbar), Spalte „Std./Tag" in der Zuweisungstabelle
**nur zur Anzeige**. Begründung: Tagesstunden gelten **global** für alle Aufgaben einer
Ressource. Im Aufgabendialog editierbar zu machen hieße, aus einem Aufgabendialog heraus fremde
Termine zu verschieben, ohne dass man es bemerkt. Bearbeitet werden sie in der
Ressourcenverwaltung.

### Beschriftungen sind fest verdrahtet — mit Grund

Die Übersetzungsdateien liegen im **Submodul** `biz.ganttproject.app.localization`, das auf
`bardsoftware/...` zeigt. Dort darf nichts hinein, das wäre ein fremdes Repository.
`RootLocalizer.formatText` liefert bei fehlendem Schlüssel **den Schlüssel selbst** zurück — im
Dialog stünde dann wörtlich `effortDriven.hoursPerDay`. Deshalb feste Beschriftungen als
Konstanten am Ende von `TaskResourcesPanel.kt`: „Aufwand", „Stunden", „Std./Tag".

Wer später übersetzen will, braucht einen fork-eigenen Ressourcenpfad — nicht das Submodul.

### Eingabelogik aus JavaFX herausgelöst, damit sie prüfbar ist

Ein `TextField` lässt sich ohne JavaFX-Toolkit nicht erzeugen; Logik im Panel wäre headless
nicht testbar. Deshalb liegt die Auswertung als **reine Funktion** `parseEffortInput(text)` im
Algorithmus-Paket, mit `EffortInput.Clear / Hours / Invalid`.

| Eingabe | Ergebnis |
|---|---|
| leer, nur Leerzeichen, null | `Clear` — Aufwand wird entfernt, Dauer bleibt |
| `20`, `20.5`, `20,5`, `" 8 "` | `Hours` — **Komma wird als Dezimaltrennzeichen akzeptiert** |
| `acht`, `8h`, `0`, `-5`, `Infinity` | `Invalid` — gespeicherter Wert bleibt unangetastet |

`EffortInputTest`: 8 Tests. **Gegentest 6:** Kommabehandlung und Unendlich-Prüfung entfernt →
`testDecimalComma` und `testInfinityIsInvalid` schlugen fehl, zurückgenommen.

Die Property-Definition wird **erst angelegt, wenn wirklich ein Wert eingetragen wird** —
Projekte ohne das Feature bekommen keine ungefragte Spalte.

**Stand: 309 Tests grün.**

### WAS NATALIE PRÜFEN MUSS (von Claude nicht prüfbar)

JavaFX startet hier nicht — Aussehen und Bedienung sind **ungetestet**. Zu prüfen:

1. Aufgabendialog → Registerkarte Ressourcen: Erscheint rechts unter den Kostenfeldern der
   Abschnitt „Aufwand" mit Eingabefeld? Ist das Feld sichtbar und nicht abgeschnitten?
2. Spalte „Std./Tag" in der Zuweisungstabelle: erscheint sie, zeigt sie 8 bei Ressourcen ohne
   eigenen Wert und den eingetragenen Wert bei anderen?
3. Aufwand eintragen, OK → wird der Wert nach erneutem Öffnen wieder angezeigt?
4. **Der wichtige Fall:** Aufwand im Feld eintragen **und** in derselben Sitzung etwas in der
   Custom-Property-Registerkarte ändern, dann OK. Bleiben **beide** Änderungen erhalten?
   (Das ist der Punkt, an dem das Überschreiben zuschlagen würde.)
5. Ungültige Eingabe („acht") → bleibt der vorherige Wert stehen, ohne Absturz?

---

## 12. Fehler aus dem Handtest — und was er über Testen lehrt

Natalie hat Schritt 4 in der laufenden Anwendung geprüft. Die Oberfläche war in Ordnung
(Feld „Aufwand", Spalte „Std./Tag" zeigte 8), aber **das Speichern schlug fehl**:

```
UPDATE Task SET effort_hours=2.0 ... Column "effort_hours" not found
```

Danach ließ sich **kein Vorgang mehr anlegen**, bis zum Neustart.

### Ursache

GanttProject spiegelt Aufgaben in eine H2-Datenbank; jede Custom Property braucht dort eine
**Spalte** (`ALTER TABLE Task ADD COLUMN <id>`), erzeugt aus dem Ereignis `customPropertyChange`.
Unser Aufwandsfeld legte die Definition **während des Dialog-Commits** an — als einziges im
ganzen Programm; alles andere legt Spalten im Spaltenverwalter an, außerhalb einer laufenden
Undo-Transaktion (`UndoableEditImpl` startet die Transaktion **vor** dem Commit).

Ergebnis: Definition vorhanden, Spalte fehlt. Und weil jeder weitere Schreibvorgang alle
Definitionen in das UPDATE aufnimmt, scheitert **ab dann alles** — daher „kein Vorgang mehr
anlegbar".

### Behebung

`TaskPropertiesController.save()` ruft jetzt vor dem Commit ausdrücklich
`projectDatabase.onCustomColumnChange(...)`. Der Aufruf ist idempotent (vergleicht Definitionen
mit Spalten) und hängt nicht davon ab, ob der Hörer feuert.

### DIE WICHTIGSTE LEHRE: verschluckte Datenbankfehler

`MutatorImpl.commit()` in `TaskImpl.kt:283`:

```kotlin
try { taskUpdateBuilder.commit() }
catch (e: ProjectDatabaseException) { GPLogger.log(e) }   // nur geloggt!
```

**Der Datenbankfehler wird verschluckt.** Folgen:

1. Die Anwendung lief nach dem Fehler scheinbar weiter — der Schaden fiel erst später auf.
2. **Drei meiner Reproduktionsversuche waren grün und damit wertlos.** Ich hielt drei Hypothesen
   für widerlegt, dabei hatte ich nur die verschluckte Ausnahme gemessen. Erst der vierte
   Anlauf, der den **Datenbankinhalt zurückliest**, zeigte den Fehler.

**Regel für dieses Projekt:** Bei allem, was über `TaskUpdateBuilder` in die Datenbank geht,
niemals auf Ausnahmen prüfen, sondern **den gespeicherten Wert zurücklesen**.

### Zweite Falle: geteilte H2-Datenbank zwischen Tests

`jdbc:h2:mem:<name>` überlebt zwischen Tests derselben Klasse. Ein Test legte die Spalte an,
der nächste fand sie vor und war deshalb grün, ohne etwas zu prüfen. In
`EffortPropertyStorageTest` bekommt daher **jeder Test eine eigene Datenbank**
(Name aus `TestInfo`).

### Neu: EffortPropertyStorageTest (Modul ganttproject, nicht ganttproject-tester)

3 Tests. Der wichtigste stellt den kaputten Zustand her (Definition ohne Spalte), weist nach,
dass der Wert dabei **verloren geht**, und belegt, dass der explizite Abgleich ihn heilt.
Die Vorbedingung ist ausdrücklich geprüft — schlägt sie fehl, sagt der Test das.

### DIE URSACHE: ein Fehler im Original-GanttProject

Gefunden durch Natalies Handtest per Computernutzung, entscheidend war die Zeile, die **fehlte**.

`ProjectUIFacadeImpl.createProject` (Z. 288–305) macht beim Anlegen eines neuen Projekts:

1. `project.close()` → `fireProjectClosed()` → `ProjectEventListenerImpl.projectClosed()`
   → **`isProjectOpen = false`**
2. danach `fireProjectCreated()`

**`projectCreated` wurde nirgends behandelt** (`ProjectEventListener.Stub` erbt es leer). Die
Sperre blieb also dauerhaft zu. `LazyProjectDatabaseProxy.onCustomColumnChange` prüft aber:

```kotlin
override fun onCustomColumnChange(...) { if (isProjectOpen) { getDatabase()... } }
```

→ Ab „Projekt → Neu" wurde **jede** Spaltenänderung **stillschweigend verworfen**: kein Fehler,
kein Logeintrag, keine Spalte. Der erste Schreibvorgang auf eine benutzerdefinierte Eigenschaft
scheiterte dann mit `Column "..." not found`, und weil `MutatorImpl.commit()` Datenbankfehler
nur protokolliert, lief die Anwendung scheinbar weiter — bis gar kein Vorgang mehr anlegbar war.

**Das betrifft nicht nur die aufwandsgetriebene Planung.** Jede benutzerdefinierte Spalte, die
nach „Projekt → Neu" angelegt wird, war betroffen. Unser Feature trifft es nur zuverlässig,
weil es die Definition selbst anlegt.

**Behebung:** `ProjectEventListenerImpl.projectCreated()` verwirft die Spiegeldatenbank und baut
sie frisch auf — genau wie `projectRestoring` es tut.

**Gegentest 7:** Behandlung wieder entfernt → `custom columns still reach the database after a
new project was created` schlug mit exakt Natalies Fehlermeldung fehl
(`Column "effort_hours" not found`). Zurückgenommen, wieder grün.

### Wie der Befund zustande kam — Methodik, die sich gelohnt hat

Fünf Hypothesen aufgestellt, alle fünf im Test widerlegt (Transaktion, Zwischenspeicher,
Init-Skript, `isProjectOpen`-Vorgabewert, Proxy-Nachbau). Erst gezielte Diagnoseausgaben an drei
Stellen brachten die Wahrheit — und zwar durch eine **fehlende** Zeile: die Storage-Meldung
erschien nur beim Start, nicht beim Speichern. Damit war klar, dass die Spaltenverwaltung gar
nicht erreicht wurde.

**Lehre:** Wenn Instrumentierung eingebaut wird, ist das Ausbleiben einer erwarteten Zeile ein
genauso starker Befund wie ihr Inhalt.

### STUFE 1 IST FERTIG — in der laufenden Anwendung geprüft

Natalie hat am 10.08.2026 per Computernutzung (Cowork) geprüft. **Alle Werte abgelesen, nicht
geschätzt:**

| Prüfung | Ergebnis |
|---|---|
| 20 h Aufwand, keine Tagesstunden gesetzt | 3 Tage (20 ÷ 8, aufgerundet) |
| `hours_per_day` = 2 | **10 Tage** |
| `hours_per_day` = 4 | **5 Tage** |
| Aufwand 20 → 40 geändert, Ressource **nicht** angefasst | **10 Tage** (40 ÷ 4) |
| zweiter Vorgang anlegbar | ja |
| ERROR/WARN im Log | keine (Filter positiv und negativ gegengeprüft) |

Damit tut GanttProject das, was Upstream-Issue #83 seit 2013 offen hat.

### Zwei Fehler, die NUR der Handtest gefunden hat

Beide waren bei 300+ grünen Tests unsichtbar, weil beide die Verdrahtung zur echten Bedienung
betrafen — nicht die Rechnung.

**1. Spalte wurde nicht gefunden (Kennung vs. Name).**
`ColumnManager.kt:131` legt Spalten über `createDefinition(type, title, defaultValue)` an. Diese
Überladung erzeugt die **Kennung selbst** (`tpc0`, `tpc1`, …); der vom Nutzer getippte Text wird
nur der **Name**. Unser Code suchte nach der Kennung, fand nichts und fiel auf 8 h/Tag zurück —
daher exakt 20 ÷ 8 = 3 Tage, unabhängig vom eingetragenen Wert.
Behoben: `findEffortDefinition(idOrName)` sucht nach **Kennung oder Name**.

**Merke:** Eigenschaften, die *wir* anlegen, tragen unsere Kennung. Eigenschaften, die der
*Nutzer* anlegt, tragen sie im Namen. Immer beides prüfen.

**2. Auslöser griff zu früh (Wiedereintritt des Mutators).**
Der Aufwand ist eine Eigenschaft der **Aufgabe**. Ein Aufgabenereignis trifft ein, während der
Mutator derselben Aufgabe noch committet — und **`MutatorReentered.commit()` tut nichts**. Eine
dort gesetzte Dauer ist verloren. Ein `TaskListener` kann diesen Fall deshalb grundsätzlich
nicht lösen; der Versuch schlug im Test sofort fehl.
Behoben in `GanttDialogProperties`: der Algorithmus läuft **nach** `mutator.commit()` und **vor**
dem Terminalgorithmus.

Das erklärt auch Natalies Beobachtung, dass die Dauer erst beim späteren Bearbeiten der Ressource
ansprang: nur der Ressourcenweg lief außerhalb eines Commits.

### Bekannte Lücke

Aufwand **direkt in eine Tabellenspalte** getippt (statt in den Dialog) löst noch nichts aus —
dort gilt derselbe Commit-Zeitpunkt. Wenn Natalie so arbeiten will, muss das nachgebaut werden.

### Fehlerbericht ans Original

`ISSUE-upstream-projectCreated.md` liegt fertig zum Einreichen bei
`bardsoftware/ganttproject`. Belegt mit einer **gewöhnlichen** Textspalte (`tpc0`), damit klar
ist: der Fehler steckt im Original, nicht in unserem Feature.

### Stolperfalle beim Handtest: vor jedem Test neu bauen

Der erste Prüflauf nach dem Fix testete noch die **alte** Programmversion — `dist-bin` war vor
der Korrektur gebaut worden. Das kostete einen kompletten Durchgang.

**Vor jedem Handtest neu bauen** und den Zeitstempel vergleichen:

```powershell
.\gradlew.bat :ganttproject-builder:distBin
# Kontrolle: muss juenger sein als die letzte Quelltextaenderung
ls ganttproject-builder\dist-bin\plugins\base\ganttproject\lib\ganttproject-*.jar.lib
```

`:ganttproject-builder:runApp` baut zwar selbst, aber wer das Fenster aus einem alten Lauf
offen hat, testet weiter den alten Stand.

### Nächster Schritt

Stufe 1 ist abgeschlossen und geprüft. Offen, in der Reihenfolge der Übergabe:

- **Schritt 5:** Konflikt Dauer/Aufwand, wenn jemand die Datei im Original-GanttProject öffnet
  und dort die Dauer ändert. Beim Öffnen erkennen und mit Rückfrage auflösen, nicht
  stillschweigend.
- **Stufe 2:** Kapazitätsverteilung mit Konfliktabfrage (Abschnitt 5 und 8). Deutlich größer und
  im vorhandenen Code ohne Vorbild. Der Aufwand lässt sich jetzt — mit Stufe 1 im Rücken —
  realistischer schätzen als in Sitzung 1.
- Kleinere Lücke: Aufwand direkt in einer Tabellenspalte (siehe oben).
- Vormerkungen der Nutzerin: `NOTIZ-Ist-Stunden.md`, `NOTIZ-Zeiterfassung-Import.md`.

### Kleinigkeit, offen

Compilerwarnung im eigenen Code: `EffortDrivenDurationAlgorithm.kt:99` „No cast needed" —
`assignment.resource as? HumanResource` ist überflüssig, weil `getResource()` bereits
`HumanResource` liefert. Stammt aus Sitzung 2, ohne Wirkung, bei Gelegenheit aufräumen.

---

## 8. Umfangsbericht (Sitzung 1, nach Codeanalyse)

Kein geratener Aufwand — hier steht, welche Arbeit anfällt und welche Unbekannten bleiben.

### Stufe 1: Dauer aus Aufwand rechnen (Issue #83)

| Baustein | Umfang | Risiko |
|---|---|---|
| Feld „Stunden pro Tag" an `HumanResource` | klein | gering |
| Aufwand in Stunden je Aufgabe speichern | klein–mittel | **Dateiformat, siehe unten** |
| `EffortDrivenDurationAlgorithm` nach Vorlage | klein (Vorlage ist ~40 Zeilen) | gering |
| In `AlgorithmCollection` einreihen | klein | gering |
| Auslöser an `resourceAssignmentsChanged` hängen | klein | mittel — Reihenfolge zu anderen Algorithmen |
| Oberfläche in `TaskResourcesPanel.kt` | mittel | **nur vom Nutzer prüfbar** |
| Unit-Tests neben `TestResourceAssignments` | klein | gering |

**Offene Entwurfsfrage — die einzige echte Weiche:** Wo wird der Aufwand gespeichert?

- *Neues Feld im Dateiformat:* sauber, aber Dateien werden vom Original-GanttProject nicht
  mehr vollständig gelesen. Rückweg versperrt.
- *Vorhandene Custom Columns:* keine Formatänderung, Dateien bleiben kompatibel, dafür
  umständlicher im Code und für den Nutzer sichtbar als normale Spalte.

Diese Frage vor dem ersten Code klären, sie zieht alles Weitere nach sich.

### Stufe 2: Kapazitätsverteilung mit Konfliktabfrage

Deutlich größer als Stufe 1 und im vorhandenen Code **ohne Vorbild**:

- Überlastung überhaupt erkennen (Summe je Ressource und Tag über alle Aufgaben)
- Reihenfolgenummer je Aufgabe als neues Feld (GanttProjects Prioritätsfeld hat Stufen,
  keine Reihenfolge — reicht nicht)
- drei Auflösungsstrategien implementieren
- Dialog dafür bauen (JavaFX)
- Wechselwirkung mit dem Scheduler: Verteilung ändert Dauern, das verschiebt Termine, was
  die Verteilung wieder ändern kann — **Konvergenz ist nicht selbstverständlich**

**Empfehlung:** Stufe 1 zuerst vollständig, inklusive Tests und Oberflächenprüfung. Stufe 2
erst danach bewerten — mit Stufe 1 im Rücken lässt sich der Aufwand dann realistisch schätzen,
jetzt wäre jede Zahl geraten.

### Was den Aufwand unkalkulierbar macht

Nicht die Codemenge, sondern der Zuschnitt der Sitzungen: Der Container startet leer, JDK und
Klon sind weg, jede Sitzung beginnt mit ~10 Minuten Einrichtung. Änderungen müssen deshalb in
sitzungsgroße Stücke geschnitten werden, die jeweils für sich getestet und committet werden.
Diese Datei ist das Gegenmittel — sie aktuell zu halten ist Teil der Arbeit, nicht Beiwerk.

---

## 13. Sitzung 4 — Zeiterfassung, Schritte 1 und 2 (Web-Sitzung)

Gearbeitet auf Branch `zeiterfassung`, aus der Chat-Oberfläche (Claude Code on the web).

### BLOCKER: In dieser Umgebung ließ sich nichts bauen

`./gradlew :ganttproject-tester:test` bricht bei der Abhängigkeitsauflösung ab:

```
Could not resolve com.sandec:mdfx:0.2.12
  > https://dl.google.com/dl/android/maven2/...  403 Forbidden
  > https://sandec.jfrog.io/artifactory/repo/... 403 Forbidden
```

Die 403 kommen **nicht** von den Servern, sondern vom Egress-Proxy dieser Umgebung: beide Hosts
stehen nicht auf der Freigabeliste (`curl $HTTPS_PROXY/__agentproxy/status` nennt sie unter
`recentRelayFailures` mit `connect_rejected`). Geprüft: `mdfx` liegt **nicht** auf Maven Central
(dort 404), Maven Central selbst ist erreichbar. Der mitgelieferte
`biz.ganttproject.app.libs/lib/mdfx-0.2.0-SNAPSHOT.jar` taugt nicht als Ersatz — er enthält
`MDFXNode`, aber **nicht** `MarkdownView`, das `UIFacadeImpl.java` und `MajorUpdateUi.kt` brauchen.

Umgehen wäre möglich gewesen (Ersatz-Jar bauen), ist aber laut Proxy-Anleitung ausdrücklich
untersagt: Richtlinienabweisungen melden, nicht umgehen. **Also gemeldet statt gebastelt.**

Zusätzlich fehlt in dieser Umgebung `cdn.azul.com` (das JDK mit JavaFX aus Abschnitt 2 lässt sich
nicht laden, ebenfalls 403). Das System-JDK 21 hat kein JavaFX; ob der `org.openjfx.javafxplugin`
das auffängt, konnte wegen des mdfx-Abbruchs nicht mehr festgestellt werden.

**Folge:** Der Code dieser Sitzung ist **nicht kompiliert und nicht getestet**. Kein einziger
Testlauf, kein Gegentest. Das ist eine echte Lücke, keine Formalie — siehe „Was Natalie prüfen
muss" unten.

**Damit die nächste Sitzung wieder bauen kann:** entweder `sandec.jfrog.io` und `cdn.azul.com` in
der Netzwerk-Richtlinie der Umgebung freigeben (Einstellungen der Web-Umgebung, siehe
code.claude.com/docs/en/claude-code-on-the-web), oder lokal auf Natalies Rechner arbeiten, wo
Sitzung 3 nachweislich gebaut hat.

### „ZUERST PRÜFEN" aus der Übergabe: aufgelöst, nichts kaputt

Die Übergabe vermutete zwei Fassungen von `TASK_EFFORT_ACTUAL_HOURS` /
`findOrCreateTaskActualEffort`. **Es gibt nur eine.** Beide Namen kommen im Hauptcode je genau
einmal vor, `object EffortDrivenProperties` existiert genau einmal, und es gibt keine
Namensvarianten (Suche ohne Beachtung der Groß-/Kleinschreibung über `.kt/.java/.xml/.properties`).

Der Widerspruch entstand durch den Vergleichspunkt: eingeführt hat beide der Commit `74a95585e`
(„Teil A", 11.08.2026 10:01) — unmittelbar **vor** dem Test-Commit `57b8abb98` derselben Sitzung.
Verglichen wurde aber gegen `effort-driven`, und `zeiterfassung` zweigt nicht von dessen Spitze ab,
sondern von `eaf15c6b9`; `effort-driven` hat den Teil-A-Code nie bekommen. Der Name
`findOrCreateTaskActualEffort` ist damit der gewollte — er ist das Gegenstück zu
`findOrCreateTaskEffort` und steht in derselben Reihe wie `findOrCreateResourceHours`.

**Gegentest der Prüfung** (das Einzige, was ohne Build gegenzutesten war): eine zweite Datei mit
derselben Konstante und Funktion angelegt — die Suche meldete sofort beide Fundstellen. Sabotage
gelöscht, Suche zeigt wieder eine. Die Prüfung war also nicht blind.

### Schritt 1 — Spaltenabgleich: kein neuer Aufruf nötig, aber eine Reihenfolge

Der Abgleich `projectDatabase.onCustomColumnChange(...)` in `TaskPropertiesController.save()` gilt
für den **ganzen** Property-Manager, nicht für eine einzelne Definition. Er deckt `effort_actual_hours`
also mit ab — **vorausgesetzt, das Feld hat die Definition vorher angelegt.** Deshalb wurde kein
zweiter Aufruf ergänzt, sondern die Reihenfolge festgeschrieben und kommentiert:

```
resourcesPanel.applyEffort(it)
resourcesPanel.applyActualEffort(it)      // beide apply* ZUERST
projectDatabase.onCustomColumnChange(...) // dann der Abgleich
mutator.setCustomProperties(it)
```

Ein drittes Feld gehört über die Abgleichzeile. Steht es darunter, fehlt die Spalte, das UPDATE
scheitert mit `Column "..." not found`, und danach lässt sich **kein Vorgang mehr anlegen** — genau
Natalies Fehler aus Sitzung 3.

Absichtlich **keine** Variante `findOrCreateTaskActualEffort(manager, projectDatabase)` gebaut: sie
hätte in dieser Sitzung keinen Aufrufer und wäre toter Code. **Für Schritt 4/5 (Import) gilt aber:**
der Import läuft über einen eigenen Menüpunkt, nicht über den Dialog — er muss
`onCustomColumnChange` **selbst** aufrufen, bevor er schreibt.

Neu in `EffortPropertyStorageTest` (Modul `ganttproject`), **ungelaufen**:

| Test | Was er beweist |
|---|---|
| `actual effort can be stored right after its definition was created` | Definition spät angelegt, Wert kommt trotzdem in der Spiegeltabelle an |
| `an explicit column sync repairs an actual effort definition without a column` | kaputter Zustand hergestellt, Verlust nachgewiesen, Abgleich heilt ihn |
| `effort and actual effort are stored together by one dialog commit` | die Reihenfolge oben — beide Spalten, ein Commit |

Beim dritten Test war der erste Entwurf **wertlos**: mit registriertem Hörer legt dieser die Spalten
ohnehin an, der Test wäre auch ohne den Abgleich grün gewesen. Deshalb wird der Hörer jetzt
abgemeldet, damit der ausdrückliche Abgleich die einzige Quelle der Spalten ist. Alle drei lesen
den Wert **zurück** und prüfen nicht auf Ausnahmen — `MutatorImpl.commit()` verschluckt sie.

### Schritt 2 — Eingabefeld „Ist-Stunden"

In `TaskResourcesPanel` neben „Aufwand → Stunden", Beschriftung fest verdrahtet (`Ist-Stunden`),
aus demselben Grund wie bei den übrigen neuen Beschriftungen: die i18n-Dateien liegen im Submodul
des Original-Repositories.

- `applyActualEffort(holder)` schreibt in **denselben Halter** wie `applyEffort`, nie direkt auf
  `task.customValues`.
- `parseEffortInput(...)` wiederverwendet — Komma als Dezimaltrennzeichen gilt also für beide Felder.
- Leeres Feld löscht den Wert, legt die Definition aber nicht neu an.
- Ungültige Eingabe lässt den gespeicherten Wert stehen und den getippten Text im Feld sichtbar,
  damit ein Tippfehler korrigierbar bleibt statt beim OK zu verschwinden.
- Auf die Dauer wirkt das Feld nicht: `EffortDrivenDurationAlgorithm` liest ausschließlich
  `effortHours`, nie `actualEffortHours`.

**Bekannte Nebenwirkung, nicht neu:** `GanttDialogProperties` lässt den Aufwandsalgorithmus bei
**jedem** OK laufen. Hat ein Vorgang geplanten Aufwand und eine dazu unpassende Dauer von Hand,
wird die Dauer beim OK korrigiert — auch wenn nur die Ist-Stunden geändert wurden. Das war schon
vor dieser Sitzung so.

### WAS NATALIE PRÜFEN MUSS (in dieser Sitzung nichts davon prüfbar)

Zuerst und vor allem: **bauen und die Tests laufen lassen.** Erst danach sind die Aussagen oben
mehr als Absicht.

```powershell
.\gradlew.bat :ganttproject:test --tests "*EffortPropertyStorage*"
.\gradlew.bat :ganttproject-tester:test --tests "*ActualEffort*" --tests "*Toggl*" --tests "*TimeEntryMatching*"
```

Gegentest zu Schritt 1 (bitte wirklich ausführen): in `TaskPropertiesController.save()` die Zeile
`projectDatabase.onCustomColumnChange(...)` **über** die beiden `apply*`-Aufrufe schieben →
`effort and actual effort are stored together by one dialog commit` **muss** fehlschlagen.
Danach zurücknehmen.

Handtest zu Schritt 2 (vorher `:ganttproject-builder:distBin` neu bauen, sonst startet die alte
Fassung):

- Feld „Ist-Stunden" sichtbar unter „Stunden".
- Wert eintragen, OK, Dialog erneut öffnen → Wert steht noch da.
- Aufwand und Ist-Stunden **gemeinsam** speicherbar.
- Ungültige Eingabe (`abc`) → alter Wert bleibt erhalten.
- **Dauer ändert sich beim Eintragen von Ist-Stunden nicht.**
- Nach dem Speichern noch ein Vorgang anlegbar (das war in Sitzung 3 der Folgeschaden).
- Log ohne ERROR/WARN.

---

## 14. Sitzung 5 — Schritt 3, und ein Weg zum Prüfen trotz gesperrtem Build

### WICHTIG: Teilübersetzung ohne Gradle — geprüft und benutzt

Der volle Build bleibt gesperrt (`mdfx`, siehe Abschnitt 13). **Aber:** Dateien, die weder das
GanttProject-Modell noch JavaFX anfassen, lassen sich einzeln übersetzen und ausführen. Der
Kotlin-Compiler und alles Nötige liegen auf Maven Central, und das ist erreichbar. `mdfx` wird
dabei nie angefragt — das ist keine Umgehung der Sperre, sondern ein anderer Bauweg für Dateien,
die die gesperrte Bibliothek gar nicht brauchen.

```bash
# Von Maven Central: kotlin-compiler-embeddable, kotlin-stdlib, kotlin-reflect,
# kotlin-script-runtime, kotlin-daemon-embeddable, kotlinx-coroutines-core-jvm (der Compiler
# braucht sie, sonst NoClassDefFoundError CoroutineScope), trove4j, annotations-13.0,
# kotlinx-serialization-json-jvm + -core-jvm, junit 4.13.2, hamcrest-core.
CP=$(ls lib/*.jar | tr '\n' ':')
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -classpath "$CP" -d out src/*.kt
java -cp "out:$CP" org.junit.runner.JUnitCore net.sourceforge.ganttproject.timetracking.HttpClientBackendTest
```

**Damit geprüft: 52 Tests grün** — 14 neue plus die 15 Toggl- und 23 Zuordnungstests aus
Sitzung 4. Dass die bestehenden 38 hier genau so durchlaufen wie gemeldet, ist zugleich der Beleg,
dass dieser Behelfsaufbau dem echten Build entspricht.

**Grenze, die man kennen muss:** Es funktioniert nur für Dateien ohne Bezug auf das Modell, die
Oberfläche oder die Datenbank. **Die Schritte 1 und 2 aus Sitzung 4 sind damit weiterhin
ungeprüft** — sie hängen an `TaskManager`, H2 und JavaFX.

### Schritt 3 gebaut: echte HTTP-Umsetzung von `HttpBackend`

Neu: `.../timetracking/HttpClientBackend.kt` mit vier Bausteinen.

| Baustein | Aufgabe |
|---|---|
| `HttpClientBackend` | erfüllt `HttpBackend`, verdrahtet Wartezeit und Senden |
| `JdkHttpSender` | die einzigen zwei Zeilen, die wirklich ein Netz brauchen |
| `RequestThrottle` | hält die Sekunde zwischen Anfragen ein |
| `buildTogglRequest` | baut die Anfrage, getrennt zum Ansehen im Test |

Entscheidungen und ihre Gründe:

- **Der Vertrag bleibt: bei Nicht-2xx wird NICHT geworfen**, sondern Status und Rumpf
  zurückgegeben. Welcher Status was bedeutet, weiß `TogglClient` — und wird dort netzfrei
  getestet. Würde dieses Backend bei 403 werfen, läge dieses Wissen an zwei Stellen.
- **Nur eine ausgebliebene Antwort** (Netz weg, Zeitüberschreitung) wird zu
  `TogglException(UNAVAILABLE)` — genau die Bedeutung, die der Wert schon hatte.
- **Unterbrechung:** `Thread.currentThread().interrupt()` vor dem Werfen, sonst vergisst ein
  Faden, dass er anhalten sollte.
- **Wartezeit auf monotoner Uhr** (`System.nanoTime`), nie auf der Wanduhr: eine
  Zeitkorrektur würde sonst entweder einen Schwall Anfragen auslösen oder den Import blockieren.
- **Aufrunden auf ganze Millisekunden.** Abrunden verfehlt das Intervall um bis zu eine
  Millisekunde — genau der Fall, den Toggl mit 429 beantwortet.
- **Uhr und Schlafen sind injizierbar**, sonst dauert jeder Wartezeit-Test eine echte Sekunde.
- **Der Throttle gilt je Instanz.** Ein Importlauf muss deshalb EIN Backend für alle Anfragen
  verwenden — zwei Instanzen feuern zweimal in derselben Sekunde.
- **Zeitlimits (30 s Antwort, 15 s Verbindung) sind geschätzt, nicht gemessen — von Claude.**
  Ohne Zeitlimit blockiert ein hängender Server den Import, bis das Programm abgeschossen wird.
- **Der Token steht in keiner Meldung** dieser Datei. Ein Test wacht darüber.

**Noch kein Aufrufer** — den bekommt das Backend in Schritt 4/5. Das ist beabsichtigt: Schritt 3
ist als eigener, für sich lauffähiger Schritt zugeschnitten.

### Gegentest: sieben Sabotagen, sieben gefangen

Nicht behauptet, sondern ausgeführt — jede Sabotage einzeln eingebaut, übersetzt, Tests laufen
lassen, zurückgenommen (Skript im Arbeitsverzeichnis der Sitzung).

| Sabotage | Ergebnis |
|---|---|
| Aufrunden auf ganze Millisekunden entfernt | `testARemainderBelowOneMillisecondStillWaits` |
| verstrichene Zeit nicht abgezogen | dieser **und zwei weitere** Wartezeit-Tests |
| Wartezeit erst nach dem Senden | `testTheWaitHappensBeforeTheRequestIsSent` |
| wirft bei Nicht-2xx | `testNonSuccessIsReturnedAndNotThrown` |
| Unterbrechungsmerker nicht wiederhergestellt | `testAnInterruptedRequestKeepsTheInterruptFlag` |
| Token in die Fehlermeldung geschrieben | `testTheFailureMessageDoesNotCarryTheToken` |
| Zeitlimit von der Anfrage entfernt | `testRequestCarriesATimeout` |

Sechs Sabotagen wurden von **genau** dem vorgesehenen Test gefangen, eine zusätzlich von zwei
weiteren — erwartbar, weil sie die gesamte Zeitrechnung verbiegt. Ohne Sabotage: 52 Tests grün.

### Stand und nächster Schritt

- Schritte 1 und 2: gebaut, **ungeprüft** (Build gesperrt). Prüfliste am Ende von Abschnitt 13.
- Schritt 3: gebaut, übersetzt, 14 Tests, 7 Gegentests. **Ohne echtes Netz getestet** — dass
  Toggl wirklich antwortet, kann erst ein Lauf mit echtem Token zeigen (Schritt 5/6).
- Als Nächstes Schritt 4: Speicherung der importierten Eintrags-IDs, **projektweit**, nicht je
  Vorgang. Gegentest Pflicht: denselben Import zweimal laufen lassen, Stundensumme muss gleich
  bleiben.

---

## 15. Sitzung 6 (lokal) — Prüfliste der Übergabe abgearbeitet

Die Übergabe verlangte: erst bauen und prüfen, kein neuer Code. Ergebnis: **zwei echte Befunde**,
beide vorher unbemerkt.

### Gemessene Zahlen (nicht gerechnet)

`ganttproject-tester`: **370 Tests, 0 Fehler** — ActualEffort 7, TogglClient 15,
TimeEntryMatching 23, HttpClientBackend 14. `EffortPropertyStorageTest`: 10.
Die 14 Tests aus Schritt 3 laufen also auch unter Gradle, nicht nur außerhalb.

**Falle beim Zählen:** `:ganttproject:test` bricht am vorbestehenden `GPCloudDocumentTest` ab
(Windows-Pfadfehler im Original). In einem gemeinsamen Aufruf läuft `:ganttproject-tester:test`
dann **gar nicht** — man liest alte Ergebnisdateien und hält sie für neue. Getrennt aufrufen.
Mich hat das kurz 311 statt 370 sehen lassen.

### Befund 1 — Dezimalstellen gingen in der Spiegeltabelle verloren

Zwei Speichertests schlugen fehl: **12,5 kam als 13,0 zurück.**

`asSqlType()` bildet `DOUBLE` auf `"numeric"` ab. In H2 hat `NUMERIC` **ohne Angabe die
Nachkommastellen null** — jede Dezimalzahl wird auf eine ganze gerundet. Betrifft **jede**
benutzerdefinierte Dezimalspalte, auch den geplanten Aufwand; bisher unbemerkt, weil alle Tests
glatte Werte benutzten (20,0 statt 20,5).

Behoben: `"double precision"`. **Gegentest 9:** zurück auf `"numeric"` → beide Tests wieder rot.

Wieder ein Defekt im Original, nicht in der neuen Arbeit. Kandidat für einen zweiten
Fehlerbericht ans Original.

### Befund 2 — der geforderte Gegentest 4.2 schlug NICHT an

Die Übergabe verlangte ihn ausdrücklich, mit der richtigen Erwartung: *„Bleibt der Test grün,
sichert er nichts."* **Er blieb grün.**

Grund: `effort and actual effort are stored together by one dialog commit` schreibt seine
**eigene** Reihenfolge fest und ruft `TaskPropertiesController.save()` nie auf. Er konnte die
Produktionsreihenfolge nie absichern — der Kommentar im Test behauptete das Gegenteil.

Behoben, indem die Reihenfolge an **eine** prüfbare Stelle wandert:
`applyEffortFieldsThenSyncColumns` (neue Datei, ohne JavaFX-Typen). `save()` ruft sie, der neue
Test ruft dieselbe Funktion. **Gegentest 11:** Reihenfolge in der Funktion vertauscht →
`Column "effort_hours" not found`. Zurückgenommen.

Ein weiteres Feld kommt jetzt in die Liste in `save()`; die Reihenfolge kann dabei nicht mehr
verrutschen.

**Lehre:** Ein Test, der eine Reihenfolge nachbaut statt sie aufzurufen, prüft sich selbst. Wenn
eine Reihenfolge zählt, muss sie in einer Funktion stehen, die die Produktion wirklich benutzt.

### FALLE, die einen kompletten Handtest gekostet hat: alte Programmdateien bleiben liegen

Der Handtest am 12.08. meldete, das Feld „Ist-Stunden" **fehle komplett**. Es fehlte nicht.

Die Versionsnummer enthält das **Datum**: `ganttproject-26.08.10-SNAPSHOT.jar.lib`. Jeder Bau an
einem neuen Tag legt eine **weitere** Datei daneben, ohne die alte zu entfernen. Im
Plugin-Verzeichnis lagen drei:

```
ganttproject-26.08.10-SNAPSHOT.jar.lib   10.08.  <- geladen, ohne Ist-Stunden
ganttproject-26.08.11-SNAPSHOT.jar.lib   11.08.
ganttproject-26.08.12-SNAPSHOT.jar.lib   12.08.  <- frisch gebaut, MIT Ist-Stunden
```

Geladen wurde die **älteste**. `BUILD SUCCESSFUL` und „21 executed" sagen also nichts darüber,
welche Fassung startet — der Agent hatte korrekt gebaut und trotzdem den alten Stand vor sich.

**Nachgewiesen, nicht vermutet:** Im Archiv vom 10.08. steht „Aufwand" und „Std./Tag", aber kein
„Ist-Stunden"; im Archiv vom 12.08. stehen alle drei. Zusätzlich ließ sich die Datei vom 10.08.
nicht löschen („Device or resource busy") — die laufende Anwendung hielt genau sie offen.

**Richtiges Rezept ab jetzt:**

```powershell
.\gradlew.bat :ganttproject-builder:clean :ganttproject-builder:distBin
```

`clean` löscht `dist-bin` (siehe `ganttproject-builder/build.gradle`, Z. 30–32). Ohne `clean`
sammeln sich die Fassungen an. **Vor dem Bauen die Anwendung schließen**, sonst sind die Dateien
gesperrt und werden stillschweigend nicht ersetzt.

**Prüfen statt hoffen** — welche Fassung enthält was:

```bash
grep -a -o "Ist-Stunden" <pfad>/plugins/base/ganttproject/lib/ganttproject-*.jar.lib
```

### Schritt 2 ist geprüft — Bildschirm und Datei getrennt

Nach Natalies neuer Regel 0 aufgeteilt: was in einer Datei sichtbar ist, wird nicht am Bildschirm
geprüft.

**Am Bildschirm geprüft (Cowork, 12.08.):** Feld „Ist-Stunden" ist im Aufgabendialog unter
„Stunden" vorhanden, vollständig sichtbar, richtig beschriftet. Gegenprobe im selben Dialog:
ein erfundenes Feld „Soll-Stunden" gibt es **nicht** — die Beobachtung unterscheidet also.

**In der Datei geprüft (`EffortFileRoundTripTest`, 3 Tests):** Aufwand und Ist-Stunden landen als
Definition und Wert im `.gan`; 12,5 bleibt 12,5; Ist-Stunden ändern die geplante Dauer nicht; ein
unberührtes Projekt bekommt die Spalten nicht ungefragt.

Damit ist die Entwurfsentscheidung aus Sitzung 2 (Custom Properties statt neuer XML-Attribute)
erstmals **ausgeführt** belegt, nicht nur aus `TaskSaver.kt` gelesen.

**Richtigstellung fürs Protokoll:** Der Prüfbericht vermutete, das Feature sei „zwischenzeitlich
in den Build gekommen". Das stimmt nicht — es lag die ganze Zeit im Quelltext, die Anwendung lud
nur die alte Programmdatei (siehe die Falle oben). Es gibt keinen Commit, der das Feld
nachgereicht hätte.

**Gegentest 13, und was er über Testaufbau lehrt:** Ist-Stunden versuchsweise in die
Dauerberechnung eingeschleust → Test blieb **grün**. Die Testaufgabe hatte keine Ressource, der
Algorithmus brach vorher ab, der geprüfte Pfad wurde nie erreicht. Mit Zuweisung und einer
ausdrücklichen Vorbedingung („der Vorgang darf nicht schon die Dauer haben, die ein Leck erzeugen
würde") schlug er fehl. **Dieselbe Falle wie in Sitzung 3.**

### Schritte 4 und 5 (Kern) — gebaut, ohne Netz

**Schritt 4, Ablage entschieden.** Die Übergabe schlug „Custom Property am Projekt oder in den
Projektoptionen" vor. **Beides gibt es nicht:** `IGanttProject` kennt genau zwei Property-Manager,
für Aufgaben und Ressourcen. Natalie hat entschieden: **eine Eigenschaft je Vorgang
(`toggl_imported`), beim Import projektweit zusammengeführt.** Grund gegen die
Anwendungseinstellungen: Die Projektdatei liegt im Vault; ein Schutz auf einem Rechner ließe
einen Kollegen dieselben Stunden ein zweites Mal buchen.

`projectImportLedger(...)` liest **alle** Vorgänge — genau der Fall, den die Übergabe als Risiko
dieser Ablage nannte (ein Eintrag, der letztes Mal einem anderen Vorgang zugeordnet war).

**Schritt 5, Kern.** Zweigeteilt: `planTaskImport` rechnet nur (Vorschau kann verworfen werden),
`applyTaskImport` schreibt und entscheidet nichts. `applyImportAsSingleEdit` legt den ganzen
Import in **eine** Undo-Klammer — und öffnet **keine**, wenn nichts zu schreiben ist.

Unantastbar: Fortschritt, Status, geplante Dauer, geplanter Aufwand. Überschreitung wird über
`exceedsPlanned` gemeldet, nicht korrigiert.

### WICHTIGER BEFUND: „Zurücklesen" las das Modell, nicht die Datenbank

`applyTaskImport` liest nach dem Schreiben zurück — aber über `task.actualEffortHours(...)`, also
aus der Aufgabe **im Speicher**. Der Mutator aktualisiert das Modell unabhängig davon, was die
Datenbank tut.

**Gegentest 16b:** Spaltenabgleich gelöscht → die Prüfung meldete weiterhin Erfolg. Der Test war
wertlos. Umgestellt auf eine **direkte H2-Abfrage**, danach schlug er fehl.

**Lehre, über dieses Feature hinaus:** „Zurücklesen statt Ausnahmen prüfen" genügt nicht — es muss
aus der Quelle gelesen werden, um die es geht. Beim Modell zu bleiben prüft nur, dass der Mutator
funktioniert hat. Die Beschreibung von `applyTaskImport` sagt das jetzt ausdrücklich.

### Aufgeräumt

- Der Zweig „Feld geleert" suchte in beiden Aufwandsfeldern nur über die Kennung — eine selbst
  angelegte Spalte ließ sich damit nicht leeren. Jetzt Kennung ODER Name.
- Aufwand direkt in eine Tabellenspalte getippt löste keine Neuberechnung aus.
  `TaskTableModel.setValue` rechnet jetzt **nach** dem eigenen Commit nach (Gegentest 15).

### Schritt 7: Token-Ablage — mit einer bewussten Abweichung von der Übergabe

Ablage in den **Anwendungseinstellungen** (`~/.ganttproject`), Muster nach `GPCloudOptions`.
Niemals in der Projektdatei: die wird geteilt und liegt im Vault.

**Abweichung, begründet:** Die Übergabe sagt „Zuordnung **Ressourcen-ID** → Token". Das geht
nicht. Ressourcen-IDs werden **je Projekt** vergeben (1, 2, 3 …), die Einstellungen sind aber
**global**. Ressource 1 ist in jedem Projekt jemand anderes — zwei Projekte würden sich die
Token gegenseitig überschreiben, und schlimmer: Es würde der Token einer Person für die Einträge
einer anderen Person geschickt.

Der Schlüssel ist deshalb die **E-Mail-Adresse**, ersatzweise der Name. Die Adresse identifiziert
das Toggl-Konto und überlebt eine Umbenennung. Beides ist durch Tests belegt
(`testTwoPeopleWithTheSameIdInDifferentProjectsDoNotCollide`, `testRenamingKeepsTheKey`).

**Was NICHT geschützt ist, ausdrücklich:** `~/.ganttproject` ist eine Klartextdatei. Der Token
wird **kodiert, nicht verschlüsselt** — die Kodierung sorgt nur dafür, dass Trennzeichen im Token
das Format nicht zerreißen. Wer unter demselben Benutzerkonto etwas ausführt, kann ihn lesen.
Ziel ist, ihn aus der geteilten Projektdatei herauszuhalten; ein Schutz gegen den lokalen Rechner
ist es nicht, und der Code darf nicht so gelesen werden.

**Gegentest 18:** Kodierung entfernt → `testSeparatorsInsideATokenSurvive` und
`testAwkwardNamesSurvive` schlugen fehl. Ohne sie hätte ein Token mit Semikolon den Rest des
Speichers abgeschnitten, und der Token der nächsten Person wäre still verschwunden.

**Noch nicht verdrahtet:** `TogglTokenOptions.optionGroup` muss in `GanttProject.java` neben
`GPCloudOptions.INSTANCE.getOptionGroup()` registriert werden, damit die Werte wirklich in
`~/.ganttproject` landen. Dazu fehlt noch die Eingabemöglichkeit in der Ressourcenverwaltung.

### Token-Ablage geprüft — dateisichtbar, ohne Bildschirm

Nach Regel 0 aufgeteilt. Zwei Prüfungen, beide ohne Oberfläche:

**Der Token landet NICHT in der Projektdatei.** `EffortFileRoundTripTest` setzt einen Token wie
der Ressourcendialog, speichert das Projekt und durchsucht das XML. Das ist die
sicherheitsrelevante Zusage, und sie ist jetzt ausgeführt statt behauptet.
**Gegentest 20:** Token über die Projektbeschreibung in die Datei gebracht → der Test schlug fehl.
Die Prüfung sieht also wirklich in die gespeicherte Datei.

**Der Weg in die Einstellungsdatei trägt.** `GanttOptions.save()` schreibt fest nach
`~/.ganttproject` und ist nicht umleitbar — ein Test darf Natalies Einstellungen nicht anfassen.
Geprüft wird deshalb genau die Schicht, die dabei benutzt wird: `getPersistentValue` /
`loadPersistentValue`, also der Weg, den `OptionSaver` geht. Zusätzlich sind Gruppen- und
Optionsname festgenagelt (`toggl.resourceTokens`) — eine Umbenennung würde jeden gespeicherten
Token stillschweigend verwaisen lassen.

### Prüfpfad zur Toggl-Verbindung (liest nur)

`checkTogglConnection` holt sieben Tage Einträge und meldet Anzahl, unlesbare Einträge und
Fehlerart. **Schreibt nichts.** Zweck: Die beiden ungeprüften Annahmen klären, bevor etwas in
Vorgänge geschrieben wird — Token im **Benutzernamen** (`api_token` ins Passwort) und die
geschätzten Zeitlimits.

**Gegentest 19:** Token ins Passwort getauscht → der neue Test **und** der bestehende
`TogglClientTest.testTokenGoesIntoTheUsername` schlugen fehl.

`TogglClient` hat dafür `timeEntriesWithProblems` bekommen; der gemeinsame Teil steckt in
`fetchBody`. Die 15 vorhandenen Tests laufen unverändert.

### Übersetzungen — geklärt, mit eigenem Textbündel

Die offene Frage aus der letzten Sitzung ist beantwortet, und zwar durch Nachlesen im Quelltext,
nicht durch Vermutung.

**Warum das Submodul ausscheidet.** `.gitmodules` zeigt für `biz.ganttproject.app.localization` auf
`bardsoftware/…`, Zweig `BRANCH_3.3`. Ein dort hinzugefügter Text ließe sich aus diesem Fork nicht
pushen, und der Submodul-Zeiger im Fork verwiese auf ein Objekt, das niemand sonst holen kann —
jeder Klon wäre kaputt. Technische Grenze, keine rechtliche.

**Warum ein zweites Plugin AUCH nicht geht.** `InternationalizationImpl.kt:87` wählt seine Datei mit
`find` — der **erste** Anbieter von `i18n_de_DE.properties` gewinnt, alle weiteren werden ignoriert.
**Bündel verschmelzen nicht.** Das war bisher ausdrücklich als ungeprüft vermerkt; jetzt geprüft.

**Der gewählte Weg.** Eigenes Bündel in `resources/language/fork/`, davorgeschaltet, Fallkette
Land → Sprache → Englisch. Schlüssel tragen alle das Präfix `fork.`, ein Zusammenstoß mit Upstream
ist damit ausgeschlossen. **Kein einziger Upstream-Text wird angefasst** — das ist der Punkt: der
Fork bleibt zusammenführbar, und der Mechanismus wäre so, wie er ist, für das Original übernehmbar.

Zwei Dinge, die den Weg tragen und die ich verifiziert habe:
- `GanttLanguage.getText` (Swing) delegiert an `RootLocalizer` (JavaFX). **Ein** Mechanismus deckt
  beide Oberflächen ab.
- `ganttproject/build.gradle:44` legt `src/main/resources/resources` direkt auf den Klassenpfad,
  und Zeile 167 packt `resources/language/**` mit ins Plugin. Pfad im Test = Pfad im Betrieb.
- Der ausgelieferte Start geht über eclipsito mit `--app net.sourceforge.ganttproject.GanttProject`.
  `App.kt:main` ist nur der Entwicklerpfad — dort wird `RootLocalizer` überschrieben, im Betrieb
  nicht. `ForkLocalizer` liest `RootLocalizer` deshalb bei jedem Aufruf neu statt ihn zu merken.

**Gegentest 21 (bestanden):** Bündel als ISO-8859-1 gelesen → der Umlauttest schlug fehl, mit genau
dem „prÃ¼fen", das im Kommentar vorhergesagt war.

**Gegentest 22 (NICHT ausgelöst — wichtiger als die bestandenen).** Ich habe den Pfad auf
`/resources/language/fork` verbogen, und **alle 11 Tests blieben grün**. Ursache: `build.gradle`
meldet `src/main/resources/resources` als Klassenpfadeintrag (Z. 44) *und* `src/main/resources` als
Ressourcenwurzel (Z. 114). Unter Test antwortet das Bündel deshalb auf **beide** Pfade; im
verpackten Plugin nur auf den ersten, weil `plugin.xml` `resources/` zur Bibliothekswurzel macht.

**Daraus die Regel:** Ein Einheitstest in diesem Projekt kann einen betriebstauglichen
Ressourcenpfad nicht von einem falschen unterscheiden. Der Kommentar im Test behauptete das
zunächst — die Behauptung ist korrigiert, statt sie stehen zu lassen.

Geprüft wird die Verpackung darum außerhalb der Testsuite, mit **`tools/packcheck/`**. Das Programm
setzt als Klassenpfad ausschließlich die echte Bibliothekswurzel des gebauten Plugins und löst die
Schlüssel dort auf. Aufruf und Gegenprobe stehen in `tools/packcheck/README.md`.

**Gegentest 24 (bestanden):** dieselbe Prüfung mit der Wurzel eine Ebene höher → alle vier
Aussagen kippten (`ERGEBNIS=FEHLER(4)`). Die Prüfung unterscheidet also wirklich, anders als der
Einheitstest.

Wer den Ressourcenpfad ändert, muss `packcheck` laufen lassen. Grüne Tests sagen dazu nichts.

**Falle, die dabei fast zugeschnappt wäre:** `Properties.load(InputStream)` nimmt ISO-8859-1 an.
So gelesen wird aus „prüfen" ein „prÃ¼fen" — falsch, sieht aber aus wie Text und fällt erst im
Bildschirmfoto auf. `ForkI18n` liest darum ausdrücklich UTF-8, und ein Test prüft ein Wort mit
Umlaut.

### Feste Texte: alle Fork-Dateien durchgesehen

Nicht nur die zwei offensichtlichen. Ergebnis der Durchsicht aller 26 geänderten Produktivdateien:

| Fund | sichtbar? | erledigt |
|---|---|---|
| 6 Beschriftungen in `TaskResourcesPanel` / `MainPropertiesPanel` | ja | ins Bündel |
| **Anzeigenamen der eigenen Spalten** (`Effort (h)`, `Actual effort (h)`, `Hours per day`, `Toggl imported`) | ja — sie **sind** der Spaltenkopf | ins Bündel |
| Prüfmeldungen beim Aufteilen (`TimeEntryMatching`) | ja, im Zuordnungsdialog | `SplitResult.Invalid` trägt jetzt **Schlüssel + Zahlen** statt fertigem Satz |
| Meldungen in `TogglClient` / `HttpClientBackend` | **noch nicht** | siehe unten |
| Treffer in `GanttProject.java`, `TaskManagerImpl`, `TaskTableModel:174`, `GanttProjectImpl` | — | Upstream, nicht von uns |

Dass der Anzeigename einer benutzerdefinierten Eigenschaft der Spaltenkopf ist, steht nicht im
Kommentar irgendwo, sondern folgt aus `ColumnManager.kt:305` (`title = column.name`) und
`ColumnStub(def.id, def.name, …)` — nachgesehen, bevor ich es behauptet habe.

**Warum das Übersetzen der Spaltennamen ungefährlich ist:** `findEffortDefinition` sucht über die
technische **id** (`effort_hours`), der Anzeigename spielt dabei keine Rolle. Die id wird bewusst
NICHT übersetzt. Würde man sie mitübersetzen, fände eine englische Installation die Spalte eines
deutschen Projekts nicht mehr, der Aufwand läse sich als „nicht vorhanden", und jeder Vorgang fiele
stumm auf die Standarddauer zurück — ohne jede Fehlermeldung. Genau dagegen sichert ein Test.

**Noch offen und bewusst so gelassen:** `ConnectionCheckResult.Failed.message` reicht die englische
Meldung aus `TogglException` durch. Heute sieht die niemand — es gibt noch keinen Dialog. Beim Bau
des Verbindungstests darf dieser Text **nicht** einfach angezeigt werden; die Meldung gehört aus
`TogglFailure` über das Bündel aufgebaut. Sonst steht „Toggl refused the token…" auf Englisch im
deutschen Dialog.

### Verbindungstest: Menüpunkt gebaut

Sitzt im **Ressourcen-Menü**, weil der Token an der Ressource hängt. `TogglConnectionAction`.
**Liest nur, schreibt nichts** — kein Vorgang, keine Eigenschaft, kein Register.

Drei Entscheidungen, die man beim Lesen sonst für willkürlich hält:

- **Eigener Thread.** Die Antwort kann am Zeitlimit von 30 Sekunden hängen. Auf dem
  Oberflächen-Thread stünde das ganze Fenster so lange — kein Neuzeichnen, kein Menü, sieht aus
  wie ein Absturz. Zurück auf den Oberflächen-Thread geht es über `SwingUtilities.invokeLater`;
  eine Swing-Komponente vom Hintergrund-Thread anzufassen funktioniert meist und zerlegt
  gelegentlich die Anzeige.
- **Die Ressourcenliste wird VOR dem Thread gelesen**, auf dem Thread, dem sie gehört. Sonst wäre
  es ein Wettlauf um Werte, die gleichzeitig auf dem Bildschirm stehen.
- **Die Meldung wird aus `TogglFailure` gebaut, nicht aus `Failed.message`.** Letztere ist
  englischer Text fürs Protokoll. Zeigte man ihn an, stünde „Toggl refused the token…" im
  deutschen Dialog. Genau das war der Punkt, den ich beim Textdurchgang offen gelassen hatte.
  `connectionCheckMessage` ist bewusst eine **reine Funktion** — dadurch ist die Zusage prüfbar,
  ohne Oberfläche.

**Gegentest 23 (bestanden):** `connectionCheckMessage` gab für `Failed` die Ausnahmemeldung zurück
→ der Test schlug fehl, und zwar genau mit dem englischen Satz im Fehlertext.

Der Test läuft über **alle** Werte von `TogglFailure`. Kommt eine Fehlerart dazu, ohne dass ein
Text hinterlegt ist, fällt das hier auf und nicht vor der Benutzerin.

**Falle beim Einhängen:** Kotlin-Standardparameter sind für Java unsichtbar. `GanttProject.java`
konnte den Konstruktor nicht aufrufen, bis `@JvmOverloads` dranstand.

### Falle: dist-bin\ganttproject.exe startet NICHT

Kostete einen ganzen Testlauf, deshalb ausführlich.

`ganttproject-launch4j.xml` verweist auf eine mitgelieferte Laufzeit unter `.\runtime\`. Die legt
aber nur `distWin` an, **`distBin` nicht**. Ohne `runtime\` fällt die `.exe` auf die System-Java
zurück, und die ist hier ein Microsoft-JDK **ohne JavaFX**. Ergebnis: der Dialog „GanttProject
needs Java 21+ with JavaFX modules", bevor überhaupt ein Fenster erscheint.

**Zum Ausprobieren immer `tools/start-testbuild.bat` benutzen.** Das setzt `JAVA_HOME` auf das
Liberica-JDK mit JavaFX — dasselbe, mit dem gebaut wird — und ruft `ganttproject.bat` auf.
Geprüft: Oberfläche kommt hoch, `ProjectOpenActivityCompleted` im Protokoll.

Zwei Fallstricke, die beim Bauen dieses Starters aufgetreten sind:
- **PowerShells `Set-Location` ändert das Arbeitsverzeichnis fremder Programme nicht.** Ein
  `Start-Process cmd /c tools\...` scheitert dann mit „Befehl nicht gefunden", obwohl die Datei da
  ist. Immer mit vollem Pfad arbeiten.
- Im Starter selbst schlug `CALL ganttproject.bat` nach einem `CD /D` fehl, je nachdem wie der
  Starter aufgerufen wurde. Deshalb steht dort jetzt der volle Pfad.

### Was jetzt NUR noch Oberfläche ist

- Zuordnungsdialog (Schritt 6), bedient `TimeEntryMatching.kt`. Die Prüfmeldungen liegen als
  Schlüssel bereit, der Dialog kann also gar keinen unübersetzten Text zeigen.
- Menüpunkt für den Import selbst.

**Noch nie gegen den echten Dienst gelaufen.** `HttpClientBackend` hat bis heute nur mit
aufgezeichneten Antworten gesprochen. Der Verbindungstest ist gebaut, damit dieser erste echte
Lauf stattfindet, **bevor** etwas in Vorgänge geschrieben wird. Die beiden ungeprüften Annahmen:
Token im Benutzernamen, und die geschätzten Zeitlimits (15 s Verbindung, 30 s Antwort — meine
Schätzung, nicht gemessen).

### Noch offen aus dieser Sitzung

- **Handtest zu Schritt 2** (Abschnitt 4.3 der Übergabe) — von Claude nicht prüfbar.
- ~~Kleinigkeit, gesehen aber nicht geändert: In `applyEffort` und `applyActualEffort` benutzt der
  Zweig „Feld geleert" noch `getCustomPropertyDefinition(...)`, also **nur die Kennung**.~~
  **Erledigt** — nachgesehen in Sitzung 7: beide Zweige rufen `findEffortDefinition(...)`, also
  Kennung ODER Name (siehe „Aufgeräumt" weiter oben in diesem Abschnitt). Dieser Punkt war beim
  Schreiben der Notizen stehen geblieben und hätte die nächste Sitzung dieselbe Arbeit noch einmal
  machen lassen.

---

## 16. Sitzung 7 (Web) — Durchsicht der Sitzung 6, ein Fund in der Buchführung

Auftrag: ansehen, wie weit Sitzung 6 gekommen ist, und beitragen, was ohne Build geht. Die 370
Tests aus Sitzung 6 wurden **nicht** nachgerechnet — sie sind lokal gemessen und dokumentiert,
ein Nachlauf hier hätte nichts hinzugefügt. Stattdessen die neue Logik gelesen.

### FUND: derselbe Eintrag auf zwei Vorgängen verdoppelt die Stunden — still

`planTaskImport` nimmt `List<Pair<TogglTimeEntry, Task>>` und kennt damit **keine Anteile**.
Taucht ein Eintrag zweimal auf, bekommt **jeder** betroffene Vorgang die **vollen** Stunden:

- `hoursDelta()` liefert für einen neuen Eintrag `entry.hours`,
- `ledgerAfterImport(...)` schreibt ebenfalls die vollen Stunden in die Buchführung **jedes**
  Vorgangs.

Aus einer Vier-Stunden-Buchung werden acht — in den Vorgängen **und** in der Buchführung, ohne
Meldung. Also genau der Schaden, gegen den dieses Feature gebaut wurde.

Das ist kein theoretischer Fall: `ImportLedgerTest.testMergeAddsUpAnEntrySplitOverTwoTasks` hält
ausdrücklich fest, dass ein Eintrag über mehrere Vorgänge aufgeteilt sein kann und
`mergeLedgers` die Teile zusammenzählt. **Die Produktion kann solche Teile aber nie erzeugen** —
sie schreibt immer die vollen Stunden. Die beiden Hälften des Entwurfs widersprechen sich, und
`ImportApplyTest` hatte für die Aufteilung keinen Test.

**Vorerst abgesichert, nicht geraten:** `planTaskImport` weist eine doppelt zugeordnete Buchung
jetzt mit `require(...)` ab und nennt die Kennung. Eine Aufteilung ließe sich hier nicht raten —
gleichmäßig? nach Aufwand? Das ist eine Entscheidung der Bedienung, keine Rechenregel. Lieber
laut scheitern als falsch rechnen. Zwei neue Tests in `ImportApplyTest` (zwei Vorgänge; derselbe
Vorgang doppelt).

### Zweiter, selteneren Fall mit derselben Ursache: verschoben UND geändert

- Import 1: Eintrag 111 mit 2 h auf Vorgang A. A merkt sich `{111:2}`.
- Import 2: Toggl meldet für 111 jetzt 3 h, die Zuordnung wandert auf B.
  → UPDATE, Zuwachs 1 h auf B, B merkt sich `{111:3}`, A behält `{111:2}`.
- Zusammengezählt steht die Buchführung auf **5 h**, tatsächlich importiert wurden **3 h**.
- Wächst der Eintrag später auf 6 h, wird nur 1 h nachgetragen statt 3. **Zwei Stunden fallen
  still unter den Tisch.**

Reines Verschieben **ohne** Änderung ist sauber (SKIP, durch
`an entry reassigned to another task is not counted twice` belegt) — es ist die Kombination.

**Beide Fälle haben dieselbe Wurzel und dieselbe Lösung:** Die Buchführung eines Vorgangs muss
**seinen Anteil** festhalten, nicht die Gesamtstunden des Eintrags. Dann stimmt das
Zusammenzählen in `mergeLedgers` wieder, die Aufteilung wird möglich, und der Fall oben löst sich
mit auf.

**Konkret für Schritt 6:** je Zuordnung einen eigenen Stundenwert führen, etwa
`EntryAssignment(entry, task, hours)` statt eines Paars. `validateSplit(...)` liefert mit
`SplitPart(taskId, hours)` die Anteile bereits in der passenden Form. Solange das nicht gebaut
ist, hält die neue Vorbedingung den Schaden auf.

### Was hier NICHT geprüft werden konnte

Der Gradle-Build bleibt in der Web-Umgebung gesperrt (`mdfx`, Abschnitt 13); die geänderten
Dateien hängen am Modell und an H2, die Teilübersetzung aus Abschnitt 14 reicht dafür nicht.

Geprüft wurde deshalb nur, was ohne das Modul geht: der **zugefügte Ausdruck selbst** wurde gegen
Platzhaltertypen übersetzt und ausgeführt — sauber `[]`, zwei Vorgänge `[1]`, derselbe Vorgang
doppelt `[7]`, leere Liste `[]`, und die Meldung enthält die Kennung. Das schließt einen
API-Fehler aus, **nicht** mehr.

**Die beiden neuen Tests sind ungelaufen.** Bitte lokal:

```powershell
.\gradlew.bat :ganttproject:test --tests "*ImportApply*"
```

Gegentest dazu: die `require`-Zeile auskommentieren → beide neuen Tests müssen fehlschlagen.
Zusätzlich lohnt der Blick, ob der erste Test dann tatsächlich **8** statt 4 Stunden bucht — das
ist der Schaden, den die Vorbedingung verhindert.

Für die Token-Ablage gilt dasselbe: `TokenStoreTest` lief hier, der Dialog nicht.

```powershell
.\gradlew.bat :ganttproject-tester:test --tests "*TokenStore*" --tests "*TogglTokens*"
.\gradlew.bat :ganttproject:test --tests "*EffortFileRoundTrip*"
```

**Handprobe zum zweiten Fund** (von Claude nicht prüfbar): Ressource ohne E-Mail anlegen, Token
eintragen, speichern. Dann dieselbe Ressource öffnen, **nur die E-Mail nachtragen**, speichern.
Danach erneut öffnen — im Token-Feld muss der Token noch stehen. In `~/.ganttproject` darf unter
`toggl.resourceTokens` **nur ein** Eintrag stehen, nicht zwei.

### ZWEITER FUND: eine geänderte E-Mail-Adresse verlor den Token — samt Karteileiche

Der Schlüssel der Token-Ablage wird aus der E-Mail-Adresse gebildet, ersatzweise aus dem Namen.
**Beide werden in genau dem Dialog geändert, der auch den Token speichert** — und die Änderung
stand in `MainPropertiesPanel.save()` **vor** dem Token-Block:

```kotlin
emailOption.ifChanged(resource::setMail)      // Schluessel wechselt hier
...
togglTokenOption.ifChanged { ... }            // schreibt unter dem NEUEN Schluessel
```

Zwei Folgen, beide still:

1. **Der Token wird unauffindbar.** Wer nur die Adresse berichtigt und das Token-Feld nicht
   anfasst, löst `ifChanged` gar nicht aus — der Eintrag bleibt unter dem alten Schlüssel liegen.
   Der Verbindungstest meldet danach „kein Token", obwohl einer eingetragen ist.
2. **Ein Geheimnis bleibt zurück.** Der alte Eintrag steht weiter in `~/.ganttproject`, unter
   einem Schlüssel, den niemand mehr abfragt.

Der wahrscheinlichste Fall ist nicht einmal eine geänderte Adresse, sondern die übliche
Reihenfolge: Ressource mit Namen anlegen, Token eintragen, **später die E-Mail nachtragen** — in
dem Moment wechselt der Schlüssel von `name=…` auf `mail=…`.

Das widerspricht der Zusage aus Sitzung 6, die Adresse „überlebe eine Umbenennung".
`testRenamingKeepsTheKey` belegt den Fall *Name geändert, Adresse vorhanden* — nicht diesen.

**Behoben:** `save()` merkt sich den Schlüssel **vor** den Namens- und Adressänderungen und ruft
`movedToken(...)`: alter Eintrag raus, Token unter dem neuen Schlüssel rein. Bewusst **nicht** mehr
an `ifChanged` gehängt, denn der Umzug muss auch bei unberührtem Token-Feld stattfinden.
Geschrieben wird nur, wenn sich der Speicher wirklich ändert.

### Reine Speicherlogik abgetrennt — damit sie sich überhaupt prüfen lässt

`TogglTokens.kt` importierte `HumanResource` und war deshalb in einer Web-Sitzung nicht
übersetzbar. Die Textbehandlung (kodieren, entschlüsseln, Eintrag ändern) steht jetzt in
**`TokenStore.kt`**, ohne Modell, ohne Datenbank, ohne JavaFX. `TogglTokens.kt` behält die drei
Adapter, die eine Ressource kennen. Aufrufer sehen keinen Unterschied — gleiches Paket, gleiche
Namen.

Das war keine Ordnungsliebe: dadurch ließ sich der Fund **hier ausgeführt** absichern statt nur
behauptet. **13 Tests in `TokenStoreTest`, grün**, dazu die 29 der Toggl-Kette unverändert grün.

### Gegentest — und zwei Sabotagen, die NICHT ansprangen

| Sabotage | Ergebnis |
|---|---|
| alter Schlüssel wird nicht geräumt | **gefangen** (4 Tests, plus „andere Personen unberührt") |
| keine URL-Kodierung | **gefangen** — erst nach Nachbesserung, siehe unten |
| keine feste Reihenfolge | gefangen |
| leerer Token wird doch geschrieben | gefangen |
| leerer Token entfernt den neuen Schlüssel nicht | **nicht gefangen — und das ist richtig so** |

**Der blinde Test, wieder einmal.** `testSeparatorsInsideAKeySurviveAMove` blieb grün, als ich die
URL-Kodierung entfernte. Grund: ohne Kodierung zerfällt der Speichertext beim Lesen, der alte
Eintrag ist einfach weg — und der Umzug schreibt den neuen trotzdem. Beide Behauptungen des Tests
galten, während der Speicher kaputt war. Behoben durch eine **ausdrückliche Vorbedingung**: der
verquere Schlüssel muss die Kodierung erst einmal überstehen. Danach fiel die Sabotage.

**Die fünfte Sabotage ist kein Mangel des Tests.** Der Zweig „leerer Token entfernt den Eintrag"
ist doppelt abgesichert — `encodeTokenMap` lässt leere Werte ohnehin weg. Die Zeile ist damit für
einen Gegentest unerreichbar. Sie bleibt trotzdem stehen, weil sie den Vertrag schon auf der
Abbildung wahr macht; **an der Zeile steht jetzt, dass kein Test sie isoliert.** Lieber
vermerkt als stillschweigend für geprüft gehalten.

### Weitere Durchsicht, ohne Fund

Gelesen und für in Ordnung befunden: `TogglTokens` (URL-Kodierung deckt beide Trennzeichen ab,
leerer Speicher und Müll sauber behandelt), `ConnectionCheck`/`TogglConnectionAction` (liest nur,
eigener Thread, Meldung aus `TogglFailure` gebaut), die Registrierung der Optionsgruppe.

**Noch eine überholte Notiz aus Sitzung 6:** Unter Schritt 7 steht „Noch nicht verdrahtet:
`TogglTokenOptions.optionGroup` muss in `GanttProject.java` registriert werden … dazu fehlt noch
die Eingabemöglichkeit in der Ressourcenverwaltung." Beides ist inzwischen da
(`GanttProject.java:214`, und das Feld in `MainPropertiesPanel`), von späteren Commits derselben
Sitzung. Die Registrierung steht richtig **vor** `initOptions()`.

### Korrektur an den Notizen der Sitzung 6

Der letzte Punkt unter „Noch offen aus dieser Sitzung" behauptete, der Zweig „Feld geleert" suche
noch über die reine Kennung. Im Code steht `findEffortDefinition(...)`, also Kennung ODER Name —
die Korrektur ist drin und steht zwei Absätze weiter oben unter „Aufgeräumt" auch so beschrieben.
Der Punkt ist als erledigt markiert, statt die nächste Sitzung dieselbe Arbeit noch einmal machen
zu lassen.

---

## Offene Vormerkungen

- `NOTIZ-Ist-Stunden.md` — Vorschlag, ein drittes Custom Property `effort_actual_hours` zu
  ergaenzen. **Nicht beauftragt.** Natalie sagt Bescheid, wenn es umgesetzt werden soll.
  Nicht ungefragt einbauen.
- `NOTIZ-Zeiterfassung-Import.md` — Vorschlag, Ist-Zeiten aus Toggl Track ueber die API zu
  importieren, mit Zuordnungsvorschlaegen, Lernspeicher und Token je Ressource.
  **Nicht beauftragt.** Setzt NOTIZ-Ist-Stunden.md voraus.
- `ENTWURF-Ist-Stunden-Import.md` — Umsetzungsentwurf zu beiden Vormerkungen, aufbauend
  auf dem Stand nach Sitzung 3. Enthaelt die Zuordnung der bekannten Fallen zu den
  einzelnen Schritten. **Nicht beauftragt.**
