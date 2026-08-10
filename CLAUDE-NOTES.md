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

**Offen:**
- [ ] Default-Branch im Repo auf `master` stellen (macht der Nutzer, Token hat kein Adminrecht)
- [ ] `RecalculateTaskCompletionPercentageAlgorithm` als Vorlage lesen
- [ ] Auslöser klären: Was ruft die Algorithmen auf, wenn eine Zuweisung geändert wird?
- [ ] Entscheiden, wo der Aufwand in Stunden gespeichert wird (neues Feld oder vorhandene
      Struktur) — betrifft auch das Dateiformat und damit die Abwärtskompatibilität
- [ ] Erst danach: Umfangsbericht statt geratener Aufwandszahl

**Nicht angefangen:** Änderungen am Code. Der Stand ist unverändertes Upstream plus dieser Datei.
