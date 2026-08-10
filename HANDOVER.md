# Übergabe an Claude Code

Stand: 10.08.2026, Ende Sitzung 2 (Chat-Oberfläche, Container ohne Zugriff auf lokale Dateien).
Ab hier wird lokal weitergearbeitet.

**Vor allem anderen lesen:** `CLAUDE-NOTES.md` im Wurzelverzeichnis. Dort stehen die
Befunde aus der Codeanalyse, der Umfangsbericht und die Arbeitsregeln der Nutzerin.
Diese Datei hier ergänzt sie nur um den Übergabestand.

---

## 1. Worum es geht

GanttProject kann keine aufwandsgetriebene Terminplanung. Weist man einer Aufgabe eine
Ressource mit 50 % Auslastung zu, passiert mit der Dauer nichts — die Zahl steht ungenutzt im
Datenmodell. Das ist Upstream-Issue #83, offen seit 2013.

**Ziel Stufe 1:** Dauer aus Aufwand und Tagesverfügbarkeit rechnen.
20 h Aufwand bei 2 h/Tag → 10 Tage; Ressource auf 4 h/Tag umgestellt → 5 Tage.

**Ziel Stufe 2 (später, noch nicht begonnen):** Kapazitätsverteilung mit Konfliktabfrage —
gleich verteilen, einer Aufgabe zuschlagen, oder nach Reihenfolgenummer. Siehe
`CLAUDE-NOTES.md` Abschnitt 5 und 8.

---

## 2. Was fertig ist

Auf Branch **`effort-driven`**:

| Datei | Inhalt |
|---|---|
| `ganttproject/src/main/java/net/sourceforge/ganttproject/task/algorithm/EffortDrivenDurationAlgorithm.kt` | Rechenkern und Modellanbindung |
| `ganttproject-tester/test/net/sourceforge/ganttproject/task/algorithm/EffortDrivenDurationTest.kt` | 11 Tests der reinen Rechnung |
| `ganttproject-tester/test/net/sourceforge/ganttproject/task/algorithm/EffortDrivenModelTest.kt` | 11 Tests gegen das echte Aufgabenmodell |

**22 Tests, alle grün.** Beide Testklassen wurden gegengetestet: Aufrunden durch Abrunden
ersetzt → `testPartialDayIsRoundedUp` schlug fehl; Auslastungsgewichtung entfernt →
`testLoadIsApplied` schlug fehl. Beide Sabotagen zurückgenommen.

### Die getroffene Entwurfsentscheidung

Aufwand und Tagesstunden liegen als **Custom Properties**, nicht als neue XML-Attribute.

Begründung im Code nachgewiesen: `TaskSaver.kt` schreibt eine fest verdrahtete Attributliste.
Ein neues Attribut würde die Original-GanttProject-Version beim Speichern stillschweigend
verwerfen — die Runde Fork → Original → Fork würde die Daten verlieren. Custom Properties
schreibt das Original regulär mit, weil es ein eigenes Feature ist.

Konstanten in `EffortDrivenProperties`:
`effort_hours` (Aufgabe), `hours_per_day` (Ressource), Rückfallwert 8 h/Tag.

**Das Feature ist je Aufgabe opt-in:** ohne eingetragenen Aufwand bleibt die Dauer unverändert.

---

## 3. Wo Claude Code weitermachen soll

Reihenfolge ist bewusst so gewählt — jeder Schritt ist für sich testbar und committbar.

### Schritt 1 — Algorithmusklasse

Neu: `EffortDrivenDurationAlgorithm` als Klasse über `AlgorithmBase`.

Vorlage ist
`ganttproject/src/main/java/net/sourceforge/ganttproject/task/algorithm/RecalculateTaskCompletionPercentageAlgorithm.java`
— rund 40 Zeilen, macht strukturell dasselbe: läuft über die Hierarchie und schreibt einen
abgeleiteten Wert über `task.createMutator()` / `commit()`.

Ablauf je Blattaufgabe:

```
effort = task.effortHours(taskCustomPropertyManager)   // null -> überspringen
avail  = task.availableHoursPerDay(resourceCustomPropertyManager)
if (avail <= 0) -> überspringen (nichts zugewiesen)
days = computeDurationDays(effort, avail)
mutator.setDuration(taskManager.createLength(dayUnit, days))
```

**Achtung:** Nur Blattaufgaben. Container-Dauern leitet GanttProject aus den Kindern ab —
dort `setDuration` aufzurufen wäre falsch.

Test: neue Klasse neben den bestehenden, die prüft, dass sich die Dauer der Aufgabe wirklich
ändert. Gegentest nicht vergessen.

### Schritt 2 — Einreihen

`ganttproject/src/main/java/net/sourceforge/ganttproject/task/algorithm/AlgorithmCollection.java`
um ein Feld plus Getter erweitern, analog zu den vorhandenen. Konstruktoraufrufer suchen und
mitziehen.

**Offene Frage dabei:** Reihenfolge gegenüber `RecalculateTaskScheduleAlgorithm` und
`myScheduler`. Der neue Algorithmus muss **vor** dem Scheduler laufen — er setzt die Dauer,
der Scheduler propagiert danach die Termine. Läuft er danach, sind die Termine veraltet.

### Schritt 3 — Auslöser

Heute stößt eine Zuweisungsänderung **nichts** an. Kette:

```
HumanResource.setLoad() / createAssignment() / swapAssignments()
  -> fireAssignmentsChanged()
  -> HumanResourceManager.fireAssignmentsChanged(resource)
  -> ResourceView.resourceAssignmentsChanged(ResourceEvent)
```

Der Hörer in `GanttProject.java` (Zeile ~722) setzt nur `setAskForSave(true)`. Dort den
Algorithmus anstoßen.

**Vorsicht vor Rückkopplung:** Der Algorithmus ändert Aufgaben, das kann wieder Ereignisse
auslösen. `AlgorithmBase` hat `isEnabled()` und `SchedulerImpl` ein `isRunning`-Flag — beides
als Muster gegen Endlosschleifen ansehen und übernehmen.

### Schritt 4 — Oberfläche

`ganttproject/src/main/java/net/sourceforge/ganttproject/gui/taskproperties/TaskResourcesPanel.kt`
— Aufwandsfeld je Aufgabe und Tagesstunden je Ressource bedienbar machen.

**Diesen Schritt kann Claude nicht selbst prüfen** (JavaFX ohne Bildschirm). Natalie testet
mit Screenshots oder Computer-Use. Vorher genau sagen, was zu prüfen ist.

### Schritt 5 — Konflikt Dauer/Aufwand

Wenn jemand die Datei im Original-GanttProject öffnet und dort die Dauer ändert, passen
Aufwand und Dauer nicht mehr zusammen. Beim Öffnen erkennen und auflösen — mit Rückfrage,
nicht stillschweigend.

---

## 4. Lokale Einrichtung

```bash
git clone https://github.com/Natalie-the-technician/ganttproject-resource-planer.git
cd ganttproject-resource-planer
git checkout effort-driven
```

**JDK 17+ mit JavaFX-Modulen ist Pflicht.** Ein normales JDK ohne JavaFX lässt den Build mit
`Unresolved reference 'javafx'` scheitern. Empfohlen: BellSoft Liberica Full JRE oder Azul Zulu
mit FX (im Container wurde Zulu 21 mit FX verwendet).

Tests laufen ohne Bildschirm:

```bash
./gradlew :ganttproject-tester:test --tests "*EffortDriven*"
```

Ergebnisse als XML unter `ganttproject-tester/build/test-results/test/`.

Vollen Build (`./gradlew build`) meiden — dauert lang und wird für diese Arbeit nicht gebraucht.

---

## 5. Was Claude Code besser kann als die Chat-Sitzung

- Kein Zeitlimit auf Gradle-Aufrufe (im Container mussten sie abgekoppelt und gepollt werden)
- Echte Git-Historie statt Datei-für-Datei über die REST-Schnittstelle
- Kein Token nötig — **der bisherige kann widerrufen werden**
- Der Zustand bleibt zwischen den Sitzungen erhalten

Trotzdem gilt weiter: **`CLAUDE-NOTES.md` am Ende jeder Sitzung fortschreiben.** Der Nutzen
hängt daran, dass die Datei aktuell bleibt.

---

## 6. Arbeitsregeln (gekürzt, vollständig in CLAUDE-NOTES.md)

- Nicht raten, sondern fragen.
- Behauptungen prüfen, auch die über eigene Fähigkeiten.
- **Jede Prüfung gegentesten** — absichtlich scheitern lassen. Eine Prüfung, die nie
  gescheitert ist, ist wertlos.
- Automatische Prüfungen finden Formfehler, keine Sinnfehler.
- Geschätzte Zahlen als Schätzung kennzeichnen und sagen, von wem sie stammen.
- Antworten auf Deutsch. Anrede: Natalie.
