# Übergabe: Zeiterfassung (Branch `zeiterfassung`)

Stand 11.08.2026, nach den Web-Sitzungen 4 und 5. **Diese Datei ersetzt die Fassung vom Vormittag.**
Adressat ist die **lokale Sitzung auf Natalies Rechner** — dort, wo sich bauen lässt.

**Zuerst lesen:** `CLAUDE-NOTES.md`, Abschnitte 13 und 14 (Warum und Fallen).
Diese Datei sagt nur, **was fertig ist, was ungeprüft ist und was als Nächstes zu tun ist**.

---

## 0. Das Wichtigste in fünf Zeilen

1. Schritte 1 bis 3 sind gebaut. **Schritt 3 ist geprüft, die Schritte 1 und 2 sind es nicht.**
2. Grund: In der Web-Umgebung ließ sich Gradle nicht ausführen. Auf deinem Rechner geht es.
3. **Erste Handlung der lokalen Sitzung: bauen und Abschnitt 4 dieser Datei abarbeiten.**
   Vorher keinen neuen Code schreiben.
4. Erst danach Schritt 4 (Speicherung der importierten Eintrags-IDs).
5. Falls lokal noch ungepushte Arbeit liegt: **erst zusammenführen**, dann weiterarbeiten.

---

## 1. Lage in einem Absatz

GanttProject soll die tatsächlich aufgewendeten Stunden festhalten und sie aus Toggl Track
importieren können. Rechen- und Entscheidungslogik, der Leser für die Ist-Stunden, das
Eingabefeld und die HTTP-Anbindung sind gebaut. Was fehlt: die Speicherung der schon
importierten Einträge, die Übernahme in die Aufgaben, der Zuordnungsdialog und die Token-Ablage.

---

## 2. Branchstand

| Branch | Stand |
|---|---|
| `zeiterfassung` | **`c000b1eec`** — hier weiterarbeiten |
| `claude/zeiterfassung-handover-i15418` | derselbe Commit, nur die Ablage der Web-Sitzung |
| `effort-driven` | Stufe 1, ohne den Zeiterfassungscode |
| `master` | Spiegel des Originals plus Notizdateien |

```bash
git fetch origin && git checkout zeiterfassung && git pull --ff-only
```

**Wichtig:** In der Web-Sitzung vom 11.08. wurde gemeldet, es seien lokal „neue Features
nachgerüstet" worden. **In Git ist davon nichts angekommen** — weder auf `zeiterfassung` noch
sonst wo, und es gibt keine Pull Requests. Wenn auf dem Rechner noch etwas liegt: **zuerst
committen und zusammenführen, bevor neuer Code entsteht.** Sonst gibt es Konflikte in genau den
Dateien, die unten stehen.

---

## 3. Was auf `zeiterfassung` liegt

### Fertig und geprüft

| Datei | Inhalt | Tests |
|---|---|---|
| `.../task/algorithm/EffortDrivenDurationAlgorithm.kt` | Stufe 1, plus `actualEffortHours(...)`, `TASK_EFFORT_ACTUAL_HOURS`, `findOrCreateTaskActualEffort(...)` | 7 |
| `.../timetracking/TogglClient.kt` | Abruf, Auth, Fehlerarten, JSON-Leser | 15 |
| `.../timetracking/TimeEntryMatching.kt` | Zuordnung, Aufteilung, Doppelimport-Schutz | 23 |
| `.../timetracking/HttpClientBackend.kt` | **neu, Schritt 3** — echtes HTTP, Wartezeit, Zeitlimits | 14 |

### Gebaut, aber NICHT geprüft — hier liegt die Arbeit

| Datei | Was daran neu ist |
|---|---|
| `.../gui/taskproperties/TaskResourcesPanel.kt` | Feld „Ist-Stunden", `applyActualEffort(...)` |
| `.../gui/taskproperties/TaskProperties.kt` | `applyActualEffort` eingehängt, Reihenfolge des Spaltenabgleichs festgeschrieben |
| `ganttproject/src/test/.../storage/EffortPropertyStorageTest.kt` | 3 neue Speichertests |

Kein Testlauf, keine Übersetzung, kein Gegentest — die Web-Umgebung konnte das Modul nicht bauen.

---

## 4. ZUERST TUN — die ungeprüften Sachen prüfen

Nichts hiervon ist Formsache. Zwei der drei Fehler, die dieses Vorhaben bisher aufgehalten haben,
hat **nur** der Handtest gefunden.

### 4.1 Bauen und die Tests laufen lassen

```powershell
.\gradlew.bat :ganttproject:test --tests "*EffortPropertyStorage*"
.\gradlew.bat :ganttproject-tester:test --tests "*ActualEffort*" --tests "*Toggl*" `
    --tests "*TimeEntryMatching*" --tests "*HttpClientBackend*"
```

Erwartung: **9** Tests aus `EffortPropertyStorageTest` (6 alte, 3 neue) und **59** aus dem zweiten
Aufruf (7 + 15 + 23 + 14). Das gesamte Testmodul `ganttproject-tester` sollte bei **370** stehen
(356 vorher plus 14 neue) — **diese Zahlen sind ausgezählt bzw. gerechnet, nicht gemessen.**

Die 14 Tests aus Schritt 3 sind bereits ausgeführt worden, allerdings außerhalb von Gradle
(Rezept in `CLAUDE-NOTES.md` Abschnitt 14). Wenn sie unter Gradle **nicht** grün sind, liegt es
am Zusammenspiel mit dem Modul, nicht an der Logik — dann bitte die Meldung genau lesen.

### 4.2 Gegentest zu Schritt 1 — bitte wirklich ausführen

In `TaskPropertiesController.save()` die Zeile `projectDatabase.onCustomColumnChange(...)`
**über** die beiden `apply*`-Aufrufe schieben.

→ `effort and actual effort are stored together by one dialog commit` **muss** fehlschlagen.
Bleibt der Test grün, sichert er nichts, und die Reihenfolge ist nicht belegt. Danach zurücknehmen.

### 4.3 Handtest zu Schritt 2

**Vorher neu bauen**, sonst startet die alte Fassung:

```powershell
.\gradlew.bat :ganttproject-builder:distBin
# Kontrolle: muss juenger sein als die letzte Quelltextaenderung
ls ganttproject-builder\dist-bin\plugins\base\ganttproject\lib\ganttproject-*.jar.lib
```

- [ ] Feld „Ist-Stunden" sichtbar, unter „Stunden" im Ressourcenreiter.
- [ ] Wert eintragen, OK, Dialog erneut öffnen → Wert steht noch da.
- [ ] Aufwand und Ist-Stunden **gemeinsam** speicherbar.
- [ ] Ungültige Eingabe (`abc`) → alter Wert bleibt erhalten, getippter Text bleibt im Feld stehen.
- [ ] Komma wird angenommen (`12,5`).
- [ ] **Dauer ändert sich beim Eintragen von Ist-Stunden nicht.**
- [ ] Danach lässt sich noch ein Vorgang anlegen — das war in Sitzung 3 der Folgeschaden.
- [ ] Log ohne ERROR/WARN (Filter positiv und negativ gegenprüfen).

**Eine Stolperstelle beim vorletzten Punkt:** `GanttDialogProperties` lässt den
Aufwandsalgorithmus bei **jedem** OK laufen. Hat ein Vorgang geplanten Aufwand und eine von Hand
dazu unpassende Dauer, wird die Dauer beim OK korrigiert — auch wenn nur die Ist-Stunden geändert
wurden. Das ist nicht neu und kein Fehler dieses Schritts. Zum Prüfen einen Vorgang nehmen, dessen
Dauer zum Aufwand passt, oder einen ganz ohne Aufwand.

### 4.4 Danach

Ergebnis in `CLAUDE-NOTES.md` festhalten — **abgelesene Werte, keine geschätzten.** Erst dann
weiter mit Schritt 4.

---

## 5. Nächste Schritte, in dieser Reihenfolge

Schritte 1 bis 3 sind erledigt (1 und 2 vorbehaltlich Abschnitt 4).

### Schritt 4 — Speicherung der importierten Eintrags-IDs

`planImport(entries, alreadyImported)` erwartet eine Abbildung `Toggl-Eintrags-ID → bereits
importierte Stunden`. Diese Abbildung muss **projektweit** gespeichert werden, nicht je Vorgang —
sonst greift der Schutz nicht mehr, wenn ein Eintrag später einem anderen Vorgang zugeordnet wird.

Ablage als Custom Property am Projekt oder in den Projektoptionen. **Nicht als neues
XML-Attribut** — der `TaskSaver` verwirft Unbekanntes still.

**Achtung, aus Sitzung 4:** Der Import läuft über einen eigenen Menüpunkt, nicht über den
Aufgabendialog. Er kann sich also **nicht** auf den Spaltenabgleich verlassen, den
`TaskPropertiesController.save()` macht — er muss `projectDatabase.onCustomColumnChange(...)`
**selbst** rufen, bevor er schreibt.

**Gegentest Pflicht:** denselben Import zweimal laufen lassen, Stundensumme muss gleich bleiben.

### Schritt 5 — Übernahme in die Aufgaben

- **Vorschau vor der Übernahme**, kein direktes Schreiben.
- **Eine einzige Undo-Transaktion** für den gesamten Import.
- **Nicht aus einem Mutator-Commit heraus ausführen** — `MutatorReentered.commit()` tut nichts,
  dort gesetzte Werte sind verloren. Eigener Menüpunkt.
- Nach dem Schreiben **zurücklesen**, nicht auf Ausnahmen vertrauen.
- **Ein Backend je Importlauf**: `RequestThrottle` gilt je Instanz, zwei Instanzen feuern zweimal
  in derselben Sekunde und holen sich ein 429.

**Unantastbar:** `complete`, Status, geplante Dauer, geplanter Aufwand. Ein **Hinweis** bei
deutlicher Überschreitung ist erwünscht — als Meldung, nicht als Änderung.

**Hier fällt auch der erste Lauf mit echtem Netz an.** `HttpClientBackend` hat noch nie mit Toggl
gesprochen; alle 14 Tests laufen gegen eingespielte Antworten. Erwartbare Stolperstellen: der
Token gehört in den **Benutzernamen** (das Wort `api_token` ins Passwort), und die Zeitlimits
(30 s Antwort, 15 s Verbindung) sind **geschätzt, nicht gemessen** — wenn sie greifen, anpassen.

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

## 6. Was NICHT gebaut wird

- Kein automatischer Hintergrund-Sync (Import auf Anforderung, mit Vorschau).
- Keine Rückrichtung nach Toggl.
- Kein PDF-Parser (der Free-Plan liefert PDF ohne Datum je Eintrag — untauglich).
- Keine Ableitung von Fortschritt oder Fertigstellung aus Zeitdaten.
- Keine Rückkopplung von Ist-Stunden auf die geplante Dauer.

---

## 7. Fallen, die dieses Vorhaben schon Zeit gekostet haben

Alle im Einzelnen in `CLAUDE-NOTES.md`. Kurzfassung, damit sie niemand zweimal tritt:

| Falle | Kurz |
|---|---|
| `MutatorImpl.commit()` verschluckt Datenbankfehler | Nie auf Ausnahmen prüfen, **immer zurücklesen** |
| Custom Property ohne H2-Spalte | `onCustomColumnChange(...)` rufen, **nach** dem Anlegen |
| Kennung vs. Name | Selbst angelegte tragen unsere Kennung, vom Nutzer angelegte nur den Namen |
| `MutatorReentered.commit()` tut nichts | Algorithmen **nach** dem Commit laufen lassen |
| Geteilte H2-Datenbank zwischen Tests | Jeder Test seine eigene (Name aus `TestInfo`) |
| Alte Fassung getestet | Vor jedem Handtest neu bauen, Zeitstempel prüfen |
| Test, der nichts sichert | Vorbedingung ausdrücklich prüfen — siehe 4.2 |

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
