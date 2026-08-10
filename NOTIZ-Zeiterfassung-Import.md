# Erweiterungsvorschlag: Ist-Zeiten aus Toggl Track importieren

Status: **VORSCHLAG, noch nicht beauftragt.** Nicht ungefragt umsetzen.
Setzt `NOTIZ-Ist-Stunden.md` voraus — ohne das Feld `effort_actual_hours` gibt es nichts zu
befüllen. **Reihenfolge: erst Stufe 1, dann Ist-Stunden-Feld, dann dieser Import.**

Aufgenommen: 10.08.2026. Recherche in derselben Sitzung durchgeführt.

---

## 1. Ausgangslage: PDF reicht nicht, CSV kostet, API ist frei

Natalie erfasst ihre Zeit mit **Toggl Track** im kostenlosen Plan.

**Der PDF-Export taugt nicht als Datenquelle.** Ein echter Export (Summary Report 2026) zeigt
Summen je Projekt und je Eintragstext — aber **kein Datum je Eintrag**, nur einen groben
Monatsbalken. Für den Schätzfehler wäre das notdürftig verwertbar, für die Kapazitätsquote
nicht: Die braucht Stunden pro Zeitraum. Dazu kommt, dass PDF-Parsen an jeder Layoutänderung
zerbricht.

**CSV ist im Free-Plan gesperrt.** Laut Toggl-Wissensdatenbank unterstützt der Free-Plan von
Toggl Track nur PDF-Exporte; CSV und andere Formate setzen einen bezahlten Plan voraus.

**Die API ist der richtige Weg und im Free-Plan zugänglich** (recherchiert 10.08.2026):

| | |
|---|---|
| Basis-URL | `https://api.track.toggl.com/api/v9` |
| Authentifizierung | HTTP Basic: **Benutzername = API-Token**, **Passwort = die Zeichenkette `api_token`** |
| Token holen | Toggl Track → Profileinstellungen, unten auf der Seite |
| Zeiteinträge | `GET /me/time_entries` mit Zeitraumfilter |
| Rate Limit | ca. **1 Request pro Sekunde**, sonst HTTP 429 |
| Kontingent erschöpft | HTTP 402, Header `X-Toggl-Quota-Remaining` beachten |

Ein Zeiteintrag liefert unter anderem: `id`, `start`, `stop`, `duration` (Sekunden),
`description`, `project_id`, `task_id`, `tags`, `workspace_id`, `user_id`.

*Häufiger Fehler:* Token als **Passwort** statt als Benutzername einsetzen. Ergibt 403.

---

## 2. Das Zuordnungsproblem

Die Toggl-Projekte heißen „Rex Hybrid" und „Smartpod" — das sind **Sparten, keine Vorgänge**.
Ein Eintrag wie „Auto Programm Logik integriert 01:10:43" lässt sich keinem Plan-Vorgang
zweifelsfrei zuordnen. Es braucht eine Zuordnungsebene.

### 2a. Bevorzugt: eindeutige Kennung im Eintrag

Vorgangsnummer im Beschreibungstext oder als Tag, etwa `#332 Firmware Sensorik`. Dann ist die
Zuordnung eindeutig statt geraten. **Diese Konvention mit Natalie abstimmen, bevor gebaut
wird** — sie muss beim Erfassen mitgemacht werden, sonst hilft sie nicht.

### 2b. Wenn nicht eindeutig: vorschlagen, nicht raten

Für jeden nicht eindeutig zuordenbaren Eintrag **Vorschläge** anbieten, nach Verlässlichkeit
sortiert. Sinnvolle Signale:

- gelernte Zuordnung aus früheren Importen (siehe 2c) — stärkstes Signal
- Toggl-Projekt → Plangruppe, sofern eine Verknüpfung hinterlegt ist
- Textähnlichkeit zwischen Eintragstext und Vorgangsnamen
- Zeitliche Plausibilität: Vorgänge, die zum Eintragsdatum laut Plan überhaupt laufen

**Immer muss auch ein völlig anderer Vorgang wählbar sein** — freie Auswahl aus allen
Vorgängen, nicht nur aus den Vorschlägen. Die Vorschläge sind eine Abkürzung, keine
Einschränkung.

**Ein Eintrag muss auf mehrere Vorgänge aufteilbar sein**, wenn wirklich an mehrerem gearbeitet
wurde — Aufteilung nach Stunden oder Prozent, mit Prüfung, dass die Summe stimmt.

### 2c. Getroffene Zuordnungen werden gespeichert

Wenn ein Eintragstext einmal einem Vorgang zugeordnet wurde, merkt sich das Werkzeug das und
schlägt es beim nächsten Mal von selbst vor — besonders wichtig, wenn auf denselben Text über
Wochen weiter Zeit gebucht wird.

Speicherung: **Custom Property am Vorgang**, aus demselben Kompatibilitätsgrund wie bei
`effort_hours` (siehe `CLAUDE-NOTES.md` Abschnitt 9 — der `TaskSaver` verwirft unbekannte
Attribute stillschweigend). Vorschlag: `toggl_match_keys`, eine Liste bereits zugeordneter
Eintragstexte oder Tags.

**Gelernte Zuordnungen müssen korrigierbar sein.** Wer sich einmal vertut, darf nicht dauerhaft
falsch zugeordnet bekommen.

### 2d. Doppelimport verhindern

Jeder Toggl-Eintrag hat eine eindeutige `id`. Bereits importierte IDs festhalten, sonst
verdoppeln sich die Ist-Stunden bei jedem Lauf. **Das ist der wahrscheinlichste schwere Fehler
dieses Features — entsprechend testen, inklusive Gegentest mit doppeltem Import.**

---

## 3. Zuordnung zu Personen und Ressourcen

**Ein API-Token gehört genau einer Person.** Solange Natalie allein arbeitet, ist das egal —
sobald weitere Personen dazukommen (im Gesamtplan als Gate C2 und Entscheidungspunkt D21
angelegt), nicht mehr.

Deshalb von Anfang an:

- Ein Token wird **einer Ressource in GanttProject zugeordnet**, nicht global hinterlegt.
- Mehrere Token nebeneinander möglich, je Ressource eines.
- Beim Import werden die Stunden der jeweiligen Ressource zugeschrieben, nicht anonym
  aufsummiert.
- **Token niemals im Projektdatei-Format speichern** — die Datei wird geteilt und liegt im
  Vault. Ablage in den Anwendungseinstellungen, ersatzweise verschlüsselt.

Das kostet jetzt wenig und erspart später einen Umbau am Datenmodell.

---

## 4. Harte Grenze: Zeit heißt nicht fertig

**Der Import schreibt ausschließlich die Ist-Stunden.** Er verändert **nicht**:

- den Fortschritt (`complete`)
- den Status „abgeschlossen"
- die geplante Dauer oder den geplanten Aufwand

Begründung: Ein langer Zeiteintrag würde sonst einen Vorgang stillschweigend als erledigt
markieren. Fortschritt bleibt eine bewusste Eingabe von Hand.

Sinnvoll ist dagegen ein **Hinweis** — nicht eine Änderung —, wenn die Ist-Stunden den
geplanten Aufwand deutlich überschreiten und der Vorgang noch als offen geführt wird. Das ist
genau die Information, die man sehen will, aber es ist eine Meldung, keine Automatik.

---

## 5. Umfang und Verortung

**Deutlich größer als Stufe 1.** Enthält: API-Anbindung mit Authentifizierung und Rate Limit,
Zuordnungsdialog, Lernspeicher, Doppelimport-Schutz, Token-Verwaltung je Ressource.

**Vor dem Bauen zu klären:** Gehört das überhaupt in GanttProject? Die Auswertung der Stunden
(Schätzfehler, Kapazitätsquote) läuft ohnehin in der Planungskette außerhalb dieses Repos, wo
sie geprüft und versioniert ist. Denkbar wäre, dass der Import ebenfalls dorthin gehört und
GanttProject nur die fertigen Werte anzeigt. Das spart die Hälfte der Arbeit — Dialog und
Lernspeicher wären dann Sache der Planungskette.

**Empfehlung:** Diese Frage mit Natalie entscheiden, bevor eine Zeile Code entsteht.

---

## 6. Was NICHT gebaut werden soll

- **Kein automatischer Hintergrund-Sync.** Import auf Anforderung, mit Vorschau vor der
  Übernahme. Stille Datenänderungen in einer Planungsdatei sind ein Risiko, kein Komfort.
- **Keine Rückrichtung** (aus GanttProject Einträge in Toggl anlegen). Eine Richtung, eine
  führende Quelle.
- **Kein PDF-Parser** als Rückfallebene. Siehe Abschnitt 1 — er wäre nicht stabil zu halten.
