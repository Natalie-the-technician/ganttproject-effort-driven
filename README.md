GanttProject — Fork mit Aufwandsplanung und Zeiterfassung
=========================================================

> **English:** this is a private fork of [bardsoftware/ganttproject](https://github.com/bardsoftware/ganttproject),
> adding effort-driven scheduling and Toggl Track time import. Documentation is in German.
> Upstream's own README is preserved unchanged in [`README`](README).

Dieser Fork von [GanttProject](https://github.com/bardsoftware/ganttproject) ergänzt zwei Dinge,
die das Original nicht hat: **die Dauer eines Vorgangs aus Aufwand und Verfügbarkeit rechnen**
und **die tatsächlich aufgewendeten Stunden festhalten**, wahlweise importiert aus
[Toggl Track](https://toggl.com/track/).

Der erste Punkt ist im Original seit 2013 als
[Issue #83](https://github.com/bardsoftware/ganttproject/issues/83) offen.

---

## Was dieser Fork kann — mit Prüfstand

Ehrlichkeit vor Vollständigkeit: hier steht auch, was gebaut, aber noch nicht in der laufenden
Anwendung geprüft ist.

| Baustein | Stand |
|---|---|
| **Dauer aus Aufwand** — Aufwandsfeld je Vorgang, Tagesstunden je Ressource, Dauer = Aufwand ÷ verfügbare Stunden | **fertig, in der Anwendung geprüft** |
| **Ist-Stunden** — Feld im Aufgabendialog, Ablage als Custom Property | **fertig, in Datei und Bildschirm geprüft** |
| **Toggl-Anbindung** — Abruf, Auth, Fehlerarten, Wartezeit zwischen Anfragen | gebaut, **nur gegen aufgezeichnete Antworten** getestet |
| **Zuordnung** — Vorschläge, Aufteilung, Schutz gegen Doppelimport | gebaut, mit Tests |
| **Übernahme in die Aufgaben** — Vorschau, eine einzige Undo-Klammer, Zurücklesen aus der Datenbank | gebaut, mit Tests |
| **Token je Person** — in den Anwendungseinstellungen, nie in der Projektdatei | gebaut, mit Tests |
| **Verbindungstest** — Menüpunkt „Ressourcen", liest nur | gebaut |
| **Eigenes Textbündel** — der Fork wird übersetzbar, ohne Upstream-Dateien anzufassen | gebaut, mit Verpackungsprüfung |
| **Zuordnungsdialog und Import-Menüpunkt** | **offen** — das ist der nächste Schritt |

**Noch nie gegen den echten Toggl-Dienst gelaufen.** Der Verbindungstest ist genau dafür gebaut:
den ersten echten Abruf zu machen, **bevor** etwas in Vorgänge geschrieben wird.

### Was ausdrücklich NICHT gebaut wird

- Kein automatischer Hintergrund-Abgleich — Import auf Anforderung, mit Vorschau.
- Keine Rückrichtung nach Toggl.
- Keine Ableitung von Fortschritt oder Fertigstellung aus Zeitdaten.
- **Keine Rückkopplung von Ist-Stunden auf die geplante Dauer.** Aufgewendete Zeit ist nicht
  dasselbe wie geleistete Arbeit; ein Vorgang darf seinen eigenen Plan nicht umschreiben, während
  er noch läuft.

---

## Bauen und starten

Gebraucht wird ein **JDK 21 mit JavaFX** (etwa Liberica oder Zulu „full"). Ein JDK ohne JavaFX
bricht den Bau mit `Unresolved reference 'javafx'` ab.

```bash
git clone <dieses Repo>
git submodule update --init        # Uebersetzungen des Originals
```

### Zum Ausprobieren von Hand

```powershell
.\gradlew.bat :ganttproject-builder:clean :ganttproject-builder:distBin
.\tools\start-testbuild.bat
```

Zwei Fallen, die hier je einen kompletten Prüflauf gekostet haben:

- **`clean` ist nicht optional.** Die Versionsnummer enthält das Datum, jeder Bau an einem neuen
  Tag legt eine **weitere** Programmdatei daneben, ohne die alte zu entfernen — und geladen wird
  die **älteste**. `BUILD SUCCESSFUL` sagt also nichts darüber, welche Fassung startet.
  Vor dem Bauen außerdem die laufende Anwendung schließen, sonst sind die Dateien gesperrt und
  werden stillschweigend nicht ersetzt.
- **`dist-bin\ganttproject.exe` startet nicht.** Sie erwartet eine mitgelieferte Laufzeit, die nur
  `distWin` anlegt, und fällt sonst auf die System-Java ohne JavaFX zurück.
  `tools\start-testbuild.bat` setzt stattdessen `JAVA_HOME` auf das JDK, mit dem gebaut wurde —
  **der Pfad darin gehört auf den eigenen Rechner angepasst.**

### Tests

**Getrennt aufrufen**, nicht in einem Befehl:

```powershell
.\gradlew.bat :ganttproject-tester:test
.\gradlew.bat :ganttproject:test
```

`:ganttproject:test` bricht am vorbestehenden `GPCloudDocumentTest` ab (Pfadfehler des Originals
unter Windows). In einem gemeinsamen Aufruf läuft `:ganttproject-tester:test` dann **gar nicht**,
und man liest alte Ergebnisdateien für neue.

Zuletzt lokal gemessen (Sitzung 6): **370 Tests im Modul `ganttproject-tester`, 0 Fehler.**
Danach hinzugekommene Tests sind darin nicht enthalten; die jeweils aktuellen Zahlen und der
Prüfstand stehen in `CLAUDE-NOTES.md`.

Wer den Ressourcenpfad des Textbündels ändert, muss zusätzlich `tools/packcheck/` laufen lassen —
ein Einheitstest kann einen betriebstauglichen Pfad hier nicht von einem falschen unterscheiden
(Begründung in `tools/packcheck/README.md`).

---

## Wo die Dokumentation liegt

| Datei | Inhalt |
|---|---|
| `CLAUDE-NOTES.md` | **das Arbeitsgedächtnis**: Stand jeder Sitzung, jede gefundene Falle, jeder Gegentest |
| `HANDOVER-Zeiterfassung.md` | Übergabe an die jeweils nächste Sitzung: was fertig, was ungeprüft, was als Nächstes |
| `ENTWURF-Ist-Stunden-Import.md` | der Umsetzungsentwurf mit den Fallen je Schritt |
| `NOTIZ-Ist-Stunden.md`, `NOTIZ-Zeiterfassung-Import.md` | die ursprünglichen Vormerkungen |
| `ISSUE-upstream-projectCreated.md` | Fehlerbericht ans Original, fertig zum Einreichen |

Wer hier weiterarbeitet, liest `CLAUDE-NOTES.md` und `HANDOVER-Zeiterfassung.md` **zuerst**.
Die Fallen darin sind teuer bezahlt: verschluckte Datenbankfehler, Custom Properties ohne
Datenbankspalte, Tests, die eine Reihenfolge nachbauen statt sie aufzurufen.

---

## Änderungen gegenüber dem Original finden

Der Fork ist absichtlich zusammenführbar gehalten — kein Upstream-Text wird angefasst, keine
Datei des Originals ohne Not verändert.

```bash
grep -rn "Fork-Aenderung" --include=*.kt --include=*.java .   # Aenderungen an Bestandsdateien
grep -rln "NEUE DATEI DIESES FORKS" .                          # neue Dateien
```

**Branches:** `master` spiegelt das Original plus Notizen, `effort-driven` trägt die
Aufwandsplanung, `zeiterfassung` ist der aktuelle Arbeitszweig.

---

## Zwei Befunde, die zurück ans Original gehören

Beim Bauen sind zwei Fehler im **Original** aufgefallen, nicht in dieser Arbeit:

1. **`projectCreated` blieb unbehandelt.** Nach „Projekt → Neu" wurde jede Änderung an
   benutzerdefinierten Spalten stillschweigend verworfen — kein Fehler, kein Logeintrag, keine
   Spalte. Betrifft jede benutzerdefinierte Spalte, nicht nur die dieses Forks.
   Fertiger Bericht: `ISSUE-upstream-projectCreated.md`.
2. **`DOUBLE` wurde auf `numeric` abgebildet.** In H2 hat `NUMERIC` ohne Angabe **null**
   Nachkommastellen — aus 12,5 wurde 13,0. Betrifft jede benutzerdefinierte Dezimalspalte.

---

## Lizenz und Herkunft

GanttProject ist freie Software unter der **GNU General Public License v3**; dieser Fork steht
unter derselben Lizenz. Alle Rechte und Verdienste am Original liegen bei
[BarD Software s.r.o. und den GanttProject-Mitwirkenden](https://github.com/bardsoftware/ganttproject).

Der Text des Originals steht unverändert in [`README`](README); mehr zum Original unter
[ganttproject.biz](https://www.ganttproject.biz).
