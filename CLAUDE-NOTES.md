# Arbeitsnotizen für Claude

**Zweck dieser Datei:** Claude beginnt jede Sitzung ohne Erinnerung und mit leerem Container.
Hier steht, was bereits herausgefunden wurde, damit Sackgassen nicht wiederholt werden.
**Claude schreibt diese Datei am Ende jeder Sitzung fort.**

Zuletzt geändert: 10.08.2026 (Sitzung 1)

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
