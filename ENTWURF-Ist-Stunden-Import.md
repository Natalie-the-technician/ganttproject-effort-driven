# Entwurf: Ist-Stunden und Zeiterfassungs-Import

Status: **ENTWURF, nicht beauftragt.** Natalie gibt das Signal, Teil für Teil.

Baut auf dem Stand nach Sitzung 3 auf (Stufe 1 fertig und in der laufenden Anwendung geprüft,
309+ Tests grün). Ersetzt nicht `NOTIZ-Ist-Stunden.md` und `NOTIZ-Zeiterfassung-Import.md` —
dort steht das **Warum**, hier das **Wie**.

Erstellt: 10.08.2026.

---

## 0. Warum dieser Entwurf jetzt Sinn ergibt

Sitzung 3 hat sieben Fallen dokumentiert. **Fünf davon treffen genau das, was hier gebaut
wird** — Abschnitt 4 ordnet sie den Schritten zu. Ohne dieses Wissen wäre der Import ein
Minenfeld; mit ihm ist er überschaubar.

---

## Teil A — Feld für Ist-Stunden (klein, Voraussetzung für alles Weitere)

Ziel: ein drittes Custom Property `effort_actual_hours` an der Aufgabe. Nur festhalten, nicht
rechnen.

### A1. Konstante und Sucher

In `EffortDrivenProperties` ergänzen, **nach demselben Muster wie `effort_hours`**:

```kotlin
const val TASK_EFFORT_ACTUAL_HOURS = "effort_actual_hours"
```

**Zwingend den `findEffortDefinition(idOrName)`-Weg nutzen**, nicht die reine Kennungssuche.
Grund steht in `CLAUDE-NOTES.md` Abschnitt 12: Eigenschaften, die der *Nutzer* über den
Spaltenverwalter anlegt, tragen den getippten Text als **Name**, die Kennung wird
automatisch vergeben (`tpc0`, `tpc1`, …). Wer nur die Kennung sucht, findet nichts und fällt
still auf einen Standardwert zurück — genau der Fehler, der bei Stufe 1 die 3 Tage statt
10 Tage erzeugt hat.

### A2. Lesefunktion

`Task.actualEffortHours(manager)` analog zu `effortHours(...)`. Rückgabe `null`, wenn nicht
gesetzt.

### A3. Datenbankspalte sicherstellen

**Beim Anlegen der Definition muss `projectDatabase.onCustomColumnChange(...)` gerufen
werden**, sonst existiert die Definition ohne Spalte und jeder weitere Schreibvorgang
scheitert (Abschnitt 12 der Notizen, der Fehler aus Natalies Handtest). Der Aufruf ist
idempotent.

### A4. Eingabefeld

Im Aufgabendialog neben „Aufwand". **In denselben Halter schreiben**, den
`CustomColumnsPanel.save { }` committet — nicht direkt auf `task.customValues`. Sonst
überschreibt die veraltete Kopie den Wert stillschweigend (Abschnitt 11).

Eingabeauswertung über eine **reine Funktion** wiederverwenden: `parseEffortInput(text)`
existiert bereits und akzeptiert Komma als Dezimaltrennzeichen. Nicht duplizieren.

### A5. Ausdrücklich NICHT

- **Kein Auslöser.** Ist-Stunden dürfen die Dauer **nicht** verändern. Sonst würde ein
  laufender Vorgang seine eigene Planung verschieben. Der bestehende Auslöser hört auf
  Ressourcenereignisse und ist davon ohnehin nicht betroffen — das aber **im Test festhalten**,
  damit es nicht versehentlich später eingebaut wird.
- Keine Berechnung des Fortschritts aus Ist-Stunden.

### A6. Tests

Modelltests nach Vorbild `EffortDrivenModelTest`, plus **ein Speichertest** nach Vorbild
`EffortPropertyStorageTest` (eigene H2-Datenbank je Test, Wert **zurücklesen**, nicht auf
Ausnahmen prüfen — Datenbankfehler werden in `MutatorImpl.commit()` verschluckt).

Gegentest: Wert schreiben, Spaltenabgleich weglassen, Rücklesen muss fehlschlagen.

**Umfang: klein.** Vergleichbar mit Schritt 4 aus Sitzung 3.

---

## Teil B — Import aus Toggl Track

Verortung entschieden (siehe `NOTIZ-Zeiterfassung-Import.md` Abschnitt 5): **Import und
Zuordnung im Fork**, Auswertung bleibt in der Planungskette. Schnittstelle ist die `.gan`-Datei.

Vier Schritte, jeder für sich lauffähig und committbar.

### B1. Abruf ohne Oberfläche

Reine Kotlin-Klasse, keine JavaFX-Abhängigkeit — damit headless testbar.

| | |
|---|---|
| Basis-URL | `https://api.track.toggl.com/api/v9` |
| Auth | HTTP Basic, **Benutzername = Token**, **Passwort = die Zeichenkette `api_token`** |
| Endpunkt | `GET /me/time_entries?start_date=…&end_date=…` |
| Rate Limit | ca. 1 Request/Sekunde, sonst 429 |
| Kontingent | 402 mit Header `X-Toggl-Quota-Remaining` |

Ergebnis als eigenes Datenmodell: `id`, `start`, `stop`, `durationSeconds`, `description`,
`projectId`, `tags`.

**Test ohne Netz:** Die Abrufschicht hinter eine Schnittstelle legen und im Test durch
aufgezeichnete Antworten ersetzen. Ein Test, der echtes Netz braucht, ist kein Test.
Mindestens ein Fall je Fehlerart: 403 (Token als Passwort verwendet — häufigster Fehler),
429, 402, leere Antwort.

**Token nicht in die Projektdatei.** Die wird geteilt und liegt im Vault. Ablage in den
Anwendungseinstellungen.

### B2. Zuordnung

Der schwierigste Teil, aber der mit dem klarsten Auftrag (siehe Notiz, Abschnitt 2).

**Reihenfolge der Signale:**

1. Vorgangsnummer im Text oder Tag, etwa `#332` — eindeutig, keine Rückfrage
2. gelernte Zuordnung aus früheren Importen
3. hinterlegte Verknüpfung Toggl-Projekt → Plangruppe
4. Textähnlichkeit
5. zeitliche Plausibilität (welche Vorgänge laufen zum Eintragsdatum überhaupt)

**Pflichtverhalten:**

- Freie Auswahl **aller** Vorgänge muss immer möglich sein, nicht nur der Vorschläge.
- Ein Eintrag muss auf mehrere Vorgänge **aufteilbar** sein, mit Summenprüfung.
- Getroffene Zuordnungen werden gelernt und beim nächsten Mal vorgeschlagen.
- Gelernte Zuordnungen müssen **korrigierbar** sein.

**Speicherung der gelernten Zuordnung:** Custom Property `toggl_match_keys` am Vorgang. Es
gelten dieselben zwei Regeln wie in A1 und A3 — Suche über Kennung **oder** Name, und
Spaltenabgleich vor dem Schreiben.

**Die Zuordnungslogik gehört in reine Funktionen** außerhalb der Oberfläche, wie
`parseEffortInput`. Der Dialog ruft sie nur auf. Sonst ist sie nicht prüfbar.

### B3. Doppelimport verhindern

**Der wahrscheinlichste schwere Fehler dieses Features.** Ohne Schutz verdoppeln sich die
Ist-Stunden bei jedem Lauf, und zwar unbemerkt.

Jeder Toggl-Eintrag hat eine eindeutige `id`. Bereits verarbeitete IDs festhalten —
projektweit, nicht je Vorgang, sonst greift der Schutz nicht mehr, wenn ein Eintrag später
einem anderen Vorgang zugeordnet wird.

**Gegentest ist Pflicht:** denselben Import zweimal laufen lassen, Stundensumme muss gleich
bleiben. Zusätzlich: ein bereits importierter Eintrag, dessen Dauer sich in Toggl geändert hat —
was soll gelten? **Vorschlag: aktualisieren statt addieren**, mit Meldung.

### B4. Übernahme in die Aufgaben

**Vorschau vor der Übernahme**, kein direktes Schreiben. Erst wenn bestätigt wird, wird
geschrieben.

Drei Punkte, die aus Sitzung 3 folgen:

- **Als eine Undo-Transaktion.** Ein Import fasst viele Aufgaben an; einzeln rückgängig zu
  machen wäre unbrauchbar.
- **Nicht während eines laufenden Mutator-Commits ausführen.** `MutatorReentered.commit()`
  tut nichts, dort gesetzte Werte sind verloren (Abschnitt 12). Der Import läuft aus einem
  eigenen Menüpunkt, nicht aus einem Dialog-Commit heraus.
- **Zurücklesen statt Ausnahmen prüfen.** Datenbankfehler werden verschluckt.

**Was der Import nicht anfasst:** `complete`, Status, geplante Dauer, geplanter Aufwand.
Ein Hinweis bei deutlicher Überschreitung ist erwünscht — als Meldung, nicht als Änderung.

---

## Teil C — Token je Ressource

Nötig, sobald mehrere Personen erfassen (Gate C2, Entscheidungspunkt D21 im Gesamtplan). Jetzt
mitdenken kostet wenig, später nachrüsten wäre ein Umbau am Datenmodell.

- Ein Token gehört **einer Ressource**, nicht dem Projekt.
- Mehrere Token nebeneinander, je Ressource eines.
- Importierte Stunden werden der jeweiligen Ressource zugeschrieben.
- **Niemals in der Projektdatei speichern.**

Umsetzbar als Zuordnung `Ressourcen-ID → Token-Kennung` in den Anwendungseinstellungen.

---

## 4. Fallen aus Sitzung 3, die hier zuschlagen werden

| Falle | Wo sie hier trifft |
|---|---|
| Kennung vs. Name bei Custom Properties | A1, B2 — beide neuen Properties |
| Definition ohne Datenbankspalte | A3, B2 — beim ersten Schreiben |
| Verschluckte Datenbankfehler | A6, B4 — Tests müssen **zurücklesen** |
| Geteilte H2-Datenbank zwischen Tests | alle Speichertests — eigene Datenbank je Test |
| `CustomColumnsPanel.save()` überschreibt | A4 — gleicher Schreibweg |
| Mutator-Wiedereintritt | B4 — Import nicht aus einem Commit heraus |
| Vor jedem Handtest neu bauen | jede Prüfung durch Natalie |

---

## 5. Reihenfolge und Umfang

| Teil | Umfang (Schätzung Claude) | Abhängig von |
|---|---|---|
| A — Feld für Ist-Stunden | klein | Stufe 1 (fertig) |
| B1 — Abruf | klein bis mittel | A |
| B2 — Zuordnung | **groß**, der eigentliche Aufwand | B1 |
| B3 — Doppelimport-Schutz | klein, aber kritisch | B1 |
| B4 — Übernahme | mittel | A, B2, B3 |
| C — Token je Ressource | klein, wenn früh mitgedacht | B1 |

**Empfehlung:** Teil A einzeln bauen und von Natalie prüfen lassen, bevor B beginnt. A ist für
sich nützlich — Ist-Stunden lassen sich auch von Hand eintragen, und die Auswertung in der
Planungskette wartet bereits fertig gebaut darauf (`kalibrierung.py`, Abschnitt „Auswertung auf
Stundenbasis"). Damit hat der Import schon einen erprobten Abnehmer, wenn er kommt.

---

## 6. Was Natalie prüfen muss

Wie immer alles, was Oberfläche ist. Konkret:

**Nach Teil A:** Feld sichtbar, Wert bleibt nach erneutem Öffnen erhalten, Aufwand und
Ist-Stunden gemeinsam speicherbar, ungültige Eingabe lässt den alten Wert stehen, **und die
Dauer ändert sich beim Eintragen von Ist-Stunden nicht**.

**Nach Teil B:** Import mit echtem Token gegen echte Daten, Vorschläge plausibel, freie Auswahl
erreichbar, Aufteilung rechnet richtig, zweiter Lauf verdoppelt nichts, Rückgängig macht den
gesamten Import rückgängig.

---

## 7. Was NICHT gebaut wird

- Kein automatischer Hintergrund-Sync.
- Keine Rückrichtung nach Toggl.
- Kein PDF-Parser (der Free-Plan liefert nur PDF ohne Datum je Eintrag — als Datenquelle
  untauglich, Begründung in der Notiz).
- Keine Ableitung von Fortschritt oder Fertigstellung aus Zeitdaten.

---

## 8. Empfehlung an Natalie, unabhängig vom Code

**Ab sofort die Vorgangsnummer in den Toggl-Eintrag schreiben**, etwa `#332 Firmware Sensorik`.
Das kostet zwei Sekunden je Eintrag und macht aus dem schwersten Teil (B2) einen einfachen.
Rückwirkend geht es nicht — deshalb früh anfangen, auch wenn der Import erst später kommt.
