# Übergabe: Zeiterfassung (Branch `zeiterfassung`)

Erstellt 11.08.2026 aus der Chat-Oberfläche. Ab hier wird lokal weitergearbeitet.

**Zuerst lesen:** `CLAUDE-NOTES.md` (Einrichtung, Fallen, Stand nach Sitzung 3) und
`ENTWURF-Ist-Stunden-Import.md` (das Warum, die Fallen je Schritt).
Diese Datei sagt nur, **was fertig ist und was als Nächstes zu tun ist**.

---

## 1. Lage in einem Absatz

GanttProject soll die tatsächlich aufgewendeten Stunden festhalten und sie aus Toggl Track
importieren können. Die **Rechen- und Entscheidungslogik ist gebaut und mit Gegentests
abgesichert**. Was fehlt, ist Oberfläche und Verdrahtung.

## 2. Branches

| Branch | Inhalt |
|---|---|
| `master` | Spiegel des Originals plus Notizdateien |
| `effort-driven` | Stufe 1 (effort-driven scheduling), fertig und in der Anwendung geprüft |
| **`zeiterfassung`** | **hier weiterarbeiten** — abgezweigt von `effort-driven`, enthält den neuen Code |

```bash
git fetch && git checkout zeiterfassung
```

## 3. Was auf `zeiterfassung` liegt

| Datei | Inhalt | Tests |
|---|---|---|
| `.../task/algorithm/EffortDrivenDurationAlgorithm.kt` (ergänzt) | `actualEffortHours(...)`, Konstante `TASK_EFFORT_ACTUAL_HOURS`, `findOrCreateTaskActualEffort(...)` | 7 |
| `.../timetracking/TogglClient.kt` | Abruf, Auth, Fehlerarten, JSON-Leser | 15 |
| `.../timetracking/TimeEntryMatching.kt` | Zuordnung, Aufteilung, Doppelimport-Schutz | 23 |
| `ganttproject-tester/test/.../ActualEffortTest.kt` | | |
| `ganttproject-tester/test/.../timetracking/TogglClientTest.kt` | | |
| `ganttproject-tester/test/.../timetracking/TimeEntryMatchingTest.kt` | | |

**45 neue Tests, gesamtes Testmodul 356 Tests, 0 Fehler** (Stand 11.08.2026).

```bash
./gradlew :ganttproject-tester:test --tests "*ActualEffort*" --tests "*Toggl*" --tests "*TimeEntryMatching*"
```

### Gegentests, die durchgeführt wurden

- Ist-Stunden: Namenssuche entfernt, Null-Schutz entfernt → 3 Tests schlugen fehl.
- Zuordnung: Doppelimport-Schutz ausgehebelt, blanke Zahl als Vorgangsnummer zugelassen,
  Summenprüfung der Aufteilung entfernt → **5 Tests** schlugen fehl.

Alle Sabotagen zurückgenommen, danach wieder grün.

---

## 4. ZUERST PRÜFEN — ein ungeklärter Punkt

In `EffortDrivenDurationAlgorithm.kt` standen `TASK_EFFORT_ACTUAL_HOURS` und
`findOrCreateTaskActualEffort` bereits, obwohl `HEAD` von `effort-driven` sie nicht enthält.
Der Widerspruch ließ sich in der Chat-Sitzung nicht auflösen. Der Test wurde an den
vorgefundenen Namen angeglichen, statt eine Ursache zu erfinden.

**Bitte einmal nachsehen**, ob es nicht zwei Fassungen derselben Funktion gibt und ob der Name
`findOrCreateTaskActualEffort` der gewollte ist. Falls doppelt: eine Fassung entfernen, Tests
laufen lassen.

---

## 5. Nächste Schritte, in dieser Reihenfolge

Jeder Schritt ist für sich lauffähig und committbar.

### Schritt 1 — Spaltenabgleich beim ersten Schreiben (klein, aber kritisch)

Beim Anlegen von `effort_actual_hours` muss `projectDatabase.onCustomColumnChange(...)` gerufen
werden, sonst existiert die Definition ohne Datenbankspalte und jedes Schreiben scheitert.
**Genau der Fehler aus Natalies Handtest in Sitzung 3.** Der Aufruf ist idempotent.

Test: Speichertest mit **eigener H2-Datenbank**, Wert **zurücklesen**. Nicht auf Ausnahmen
prüfen — `MutatorImpl.commit()` verschluckt Datenbankfehler.

### Schritt 2 — Eingabefeld für Ist-Stunden

Im Aufgabendialog neben „Aufwand". **In denselben Halter schreiben**, den
`CustomColumnsPanel.save { }` committet, nicht direkt auf `task.customValues` — sonst
überschreibt die veraltete Kopie den Wert still.

`parseEffortInput(text)` wiederverwenden (akzeptiert Komma), nicht duplizieren.

Danach ist Teil A **für sich nutzbar**: Stunden lassen sich von Hand eintragen, und die
Auswertung in Natalies Planungskette wartet bereits fertig gebaut darauf. **Hier eine Prüfung
durch Natalie einplanen, bevor es weitergeht.**

### Schritt 3 — Echte HTTP-Umsetzung von `HttpBackend`

Eine Klasse mit `java.net.http.HttpClient`, die `HttpBackend` erfüllt. Der bestehende
Vertrag bleibt: **darf bei Nicht-2xx nicht werfen**, sondern Status und Rumpf zurückgeben —
die Fehlerzuordnung macht `TogglClient`.

Dazu die Wartezeit von **einer Sekunde zwischen Anfragen** (`TogglApi.MIN_REQUEST_INTERVAL`).

### Schritt 4 — Speicherung der importierten Eintrags-IDs

`planImport(entries, alreadyImported)` erwartet eine Abbildung `Toggl-Eintrags-ID → bereits
importierte Stunden`. Diese Abbildung muss **projektweit** gespeichert werden, nicht je
Vorgang — sonst greift der Schutz nicht mehr, wenn ein Eintrag später einem anderen Vorgang
zugeordnet wird.

Ablage als Custom Property am Projekt oder in den Projektoptionen. **Nicht als neues
XML-Attribut** — der `TaskSaver` verwirft Unbekanntes still (siehe `CLAUDE-NOTES.md`).

**Gegentest Pflicht:** denselben Import zweimal laufen lassen, Stundensumme muss gleich bleiben.

### Schritt 5 — Übernahme in die Aufgaben

- **Vorschau vor der Übernahme**, kein direktes Schreiben.
- **Eine einzige Undo-Transaktion** für den gesamten Import.
- **Nicht aus einem Mutator-Commit heraus ausführen** — `MutatorReentered.commit()` tut nichts,
  dort gesetzte Werte sind verloren. Eigener Menüpunkt.
- Nach dem Schreiben **zurücklesen**, nicht auf Ausnahmen vertrauen.

**Unantastbar:** `complete`, Status, geplante Dauer, geplanter Aufwand. Ein **Hinweis** bei
deutlicher Überschreitung ist erwünscht — als Meldung, nicht als Änderung.

### Schritt 6 — Zuordnungsdialog

Bedient die fertigen Funktionen aus `TimeEntryMatching.kt`:

- `suggestTasks(...)` liefert bewertete Vorschläge; `isCertain` heißt „keine Rückfrage nötig".
- **Freie Auswahl aller Vorgänge muss immer möglich sein**, nicht nur der Vorschläge.
- Aufteilung eines Eintrags über `validateSplit(...)`, Meldung bei falscher Summe.
- Getroffene Zuordnung als gelernten Schlüssel am Vorgang speichern
  (Custom Property `toggl_match_keys`), **korrigierbar**.

**Keine Logik in den Dialog schreiben.** Wenn etwas fehlt, gehört es als reine Funktion nach
`TimeEntryMatching.kt` — mit Test.

### Schritt 7 — Token je Ressource

Zuordnung `Ressourcen-ID → Token` in den **Anwendungseinstellungen**.
**Niemals in der Projektdatei** — die wird geteilt und liegt im Vault.

---

## 6. Was Natalie prüfen muss (Oberfläche)

**Nach Schritt 2:** Feld sichtbar, Wert bleibt nach erneutem Öffnen erhalten, Aufwand und
Ist-Stunden gemeinsam speicherbar, ungültige Eingabe lässt den alten Wert stehen, **Dauer
ändert sich beim Eintragen von Ist-Stunden nicht**.

**Nach Schritt 5/6:** Import mit echtem Token, Vorschläge plausibel, freie Auswahl erreichbar,
Aufteilung rechnet richtig, **zweiter Lauf verdoppelt nichts**, Rückgängig macht den gesamten
Import rückgängig.

Vor jedem Handtest neu bauen — sonst startet die alte Fassung.

---

## 7. Was NICHT gebaut wird

- Kein automatischer Hintergrund-Sync (Import auf Anforderung, mit Vorschau).
- Keine Rückrichtung nach Toggl.
- Kein PDF-Parser (der Free-Plan liefert PDF ohne Datum je Eintrag — untauglich).
- Keine Ableitung von Fortschritt oder Fertigstellung aus Zeitdaten.
- Keine Rückkopplung von Ist-Stunden auf die geplante Dauer.

---

## 8. Arbeitsregeln (unverändert)

- Nicht raten, sondern fragen.
- Behauptungen prüfen, auch die über eigene Fähigkeiten.
- **Jede Prüfung gegentesten** — absichtlich scheitern lassen.
- Automatische Prüfungen finden Formfehler, keine Sinnfehler.
- Geschätzte Zahlen als Schätzung kennzeichnen und sagen, von wem sie stammen.
- Neue Dateien mit `// NEUE DATEI DIESES FORKS`, Änderungen an Bestandsdateien mit
  `[Fork-Aenderung]` markieren.
- `CLAUDE-NOTES.md` am Ende jeder Sitzung fortschreiben.
- Antworten auf Deutsch. Anrede: Natalie.
