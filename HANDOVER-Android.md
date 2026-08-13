# Übergabe: Android-App (Branch `claude/gantt-android-app-wxxu9o`)

Erstellt 13.08.2026.

**Zuerst lesen:** `CLAUDE-NOTES.md` Abschnitt 10 (Entscheidungen, Umgebung, Fallen).
Diese Datei sagt, **was fertig ist, was geprüft wurde und was du prüfen musst**.

---

## 1. Lage in einem Absatz

Es gibt eine eigenständige Android-App unter `android/`, die GanttProject-Dateien
liest und bearbeitet: Diagramm ansehen, Fortschritt setzen, geplanten Aufwand und
Ist-Stunden eintragen, Stunden pro Tag je Ressource ändern, Auslastung sehen,
Ressourcen zuweisen, Zeiten aus Toggl importieren. Die **gesamte Logik ist gebaut
und mit 133 Tests abgesichert**, die Gegentests sind durchgeführt. Was fehlt, ist
deine Handprüfung auf einem echten Gerät — die Oberfläche lässt sich im Container
nicht starten.

Der Desktop-Build ist unverändert. `android/` ist ein eigenes Gradle-Projekt.

---

## 2. Wie du die App bekommst

Es gibt keine APK aus dem Container — das Android-SDK liegt hinter
`dl.google.com`, und das ist durch die Netzregel gesperrt. Die APK entsteht
deshalb bei GitHub:

1. Im Repo auf **Actions** → Workflow **Android app** → letzter Lauf auf
   `claude/gantt-android-app-wxxu9o`
2. Unten unter **Artifacts**: `ganttproject-mobile-debug-apk` herunterladen
3. ZIP entpacken, APK auf das Telefon, Installation aus unbekannter Quelle erlauben

Braucht Android 8.0 oder neuer.

---

## 3. Aufbau — und warum er so ist

```
android/
├── gantt-core/   reines Kotlin, KEIN Android, 133 Tests
└── app/          Jetpack Compose, Oberfläche deutsch und englisch
```

Alles, was eine Projektdatei still beschädigen kann — Schreiben, Datumsrechnung,
Zuordnungsregeln, Schutz vor Doppelimport — liegt im Kern und ist **ohne Emulator
prüfbar**. Im `app`-Modul steht keine solche Regel. Fehlt dort etwas, gehört es
als reine Funktion in den Kern, mit Test.

`settings.gradle.kts` bindet `:app` nur ein, wenn ein Android-SDK vorhanden ist.
Ohne diese Bedingung wäre schon das Konfigurieren gescheitert und hätte jede
Prüfung des Kerns mit blockiert.

```bash
cd android && ./gradlew :gantt-core:test     # läuft überall
cd android && ./gradlew :app:assembleDebug   # braucht ein SDK
```

---

## 4. Der wichtigste Befund dieser Sitzung

**Der Standard-XML-Serialisierer sortiert alle Attribute alphabetisch um.**
Gemessen, nicht vermutet. Aus

```xml
<project name="" company="" webLink="" view-date="..." version="3.3.3309" locale="en">
```

wird

```xml
<project company="" gantt-divider-location="693" locale="en" name="" ...>
```

Kein Datenverlust — aber jede Speicherung schreibt **jede Zeile der Datei neu**,
und der Desktop sortiert beim nächsten Speichern wieder zurück. Für Dateien im
Vault oder unter Git wäre der Diff unbrauchbar.

Deshalb ein eigener kleiner XML-Baum auf SAX-Basis (`XmlTree.kt`). SAX liefert
Attribute in Dokumentreihenfolge und gibt es auf Android — StAX nicht. Der Diff
einer Änderung enthält jetzt nur die tatsächlich geänderten Zeilen.

---

## 5. Was geprüft ist

**133 Tests, 0 Fehler.** Die Beispieldatei ist die echte
`HouseBuildingSample.gan` aus dem Desktop-Projekt, keine vereinfachte Nachbildung.

Belegt unter anderem:

- Speichern ohne Änderung erhält Ansichten, Spaltenbreiten, Kalender, Rollen,
  Urlaube, Baselines, CDATA und Notizen
- zweimal speichern ergibt exakt dieselbe Datei (die Ausgabe stabilisiert sich)
- Dauer zählt Arbeitstage — belegt an der Abhängigkeit in der Datei selbst
- externe Entitäten werden nicht aufgelöst (XXE)

### Gegentests: 10 Sabotagen, 10 gefangen

Deine Regel — eine Prüfung, die nie gescheitert ist, ist wertlos. Ich habe jede
kritische Regel absichtlich gebrochen, die Tests laufen lassen und die Sabotage
zurückgenommen:

| Sabotage | gefangen von |
|---|---|
| Doppelimport-Schutz übergeht bereits Importiertes | 5 Tests |
| blanke Zahl als Vorgangsnummer zugelassen | 1 Test |
| Summenprüfung der Aufteilung entfernt | 1 Test |
| Dauer zählt Kalendertage statt Arbeitstage | 3 Tests |
| Attributreihenfolge nicht mehr erhalten | 6 Tests |
| Fortschritt am Sammelvorgang zugelassen | 1 Test |
| Rückfall auf 8 h/Tag entfernt | 2 Tests |
| Auslastung bei der Verfügbarkeit ignoriert | 2 Tests |
| CDATA als escapter Text geschrieben | 2 Tests |
| Sammelvorgänge als Importziel angeboten | 1 Test |

Danach alles zurückgenommen, wieder 133/133 grün.

Skript: `/tmp/sabotage.py` in der Sitzung; bei Bedarf neu schreiben, es ist
absichtlich nicht eingecheckt.

---

## 6. Was DU prüfen musst (Oberfläche)

Die Rechenlogik ist geprüft, die Bedienung nicht. Bitte mit einer **Kopie** einer
echten Projektdatei:

**Öffnen und Speichern**
- [ ] Datei aus deinem Cloud-Ordner öffnen (Drive/Nextcloud/…)
- [ ] ohne Änderung speichern, Datei danach am Desktop öffnen — sieht sie richtig aus?
- [ ] `git diff` bzw. Dateivergleich: ist der Diff wirklich klein?

**Fortschritt**
- [ ] Fortschritt an einem Blattvorgang setzen, speichern, am Desktop nachsehen
- [ ] bei einem Sammelvorgang ist das Feld gesperrt, mit Begründung

**Stunden**
- [ ] Aufwand und Ist-Stunden eintragen, App schließen, neu öffnen — Werte noch da?
- [ ] `7,5` mit Komma wird angenommen
- [ ] Unsinn (`acht`) lässt den alten Wert stehen und zeigt einen Fehler
- [ ] Runde App → Desktop → App: die Werte überleben

**Ressourcen**
- [ ] Stunden pro Tag ändern, Auslastung ändert sich passend
- [ ] Überlast wird gemeldet, wenn du zwei volle Zuweisungen überlappen lässt
- [ ] Zuweisung anlegen, Auslastung schieben, Zuweisung entfernen

**Toggl-Import**
- [ ] Token eintragen, „Token prüfen" bestätigt es
- [ ] Einträge laden, Vorschläge plausibel
- [ ] freie Auswahl aller Vorgänge erreichbar, nicht nur der Vorschläge
- [ ] **zweiter Lauf verdoppelt nichts** — Vorschau muss „nichts zu schreiben" zeigen
- [ ] ohne Speichern schließen macht den Import rückgängig

Vor jedem Handtest die aktuelle APK aus Actions ziehen — sonst testest du eine
alte Fassung.

---

## 7. Bewusst NICHT gebaut

- **Termine und Dauern ändern.** GanttProjects Terminplaner propagiert Termine
  durch den Abhängigkeitsgraphen. Der läuft auf dem Telefon nicht. Eine Änderung
  würde abhängige Vorgänge stehen lassen und eine Datei erzeugen, die richtig
  aussieht und still falsch ist. Termine werden angezeigt, nicht bearbeitet.
- Vorgänge anlegen oder löschen — gleicher Grund.
- Rückschreiben nach Toggl.
- Hintergrund-Sync — Import auf Anforderung, mit Vorschau.
- Fortschritt aus Zeitdaten ableiten.
- Automatische Korrektur des Plans bei Überschreitung — es gibt einen **Hinweis**,
  keine Änderung.

---

## 8. Offene Punkte

- [ ] **Import-Ledger liegt auf dem Gerät, nicht in der Datei.** Das GanttProject-
      Format hat keinen projektweiten Ablageort, den der Desktop nicht verwirft:
      unbekannte Elemente und Attribute wirft der `TaskSaver` weg, und Custom
      Properties hängen an Vorgang oder Ressource, nicht am Projekt — der Schutz
      muss aber allein an der Eintrags-ID hängen. Folge: Import derselben Periode
      von einem zweiten Gerät könnte doppelt buchen. Die Vorschau zeigt es vorher.
- [ ] **Aufteilen eines Eintrags auf mehrere Vorgänge** ist im Kern gebaut und
      getestet (`validateSplit`), in der Oberfläche noch nicht bedienbar.
- [ ] Kein Undo in der App. Ersatz: ohne Speichern schließen verwirft alles.
- [ ] Keine Instrumentierungstests der Oberfläche.

---

## 9. Veröffentlichung als eigenes Repo

`android/` ist absichtlich eigenständig: eigener Gradle-Wrapper, eigene `LICENSE`
(GPL v3, wie das Original), eigenes `README.md` auf Englisch. Code und Kommentare
sind Englisch, die Oberfläche zweisprachig, Fehler kommen als Typen aus dem Kern
und werden erst in der Oberfläche übersetzt — eine weitere Sprache braucht deshalb
**keine Codeänderung**, nur eine neue `strings.xml`.

Herauslösen mit Historie:

```bash
git subtree split --prefix=android -b ganttproject-mobile
git push git@github.com:<du>/ganttproject-mobile.git ganttproject-mobile:main
```

Danach im Workflow die `working-directory: android`-Zeilen und die
`paths:`-Filter entfernen.

**Vor dem Veröffentlichen prüfen:** kein Token, kein Schlüssel, keine echte
Projektdatei im Verlauf. Die einzige eingecheckte Datei mit Inhalt ist
`HouseBuildingSample.gan` — die stammt aus dem Original-GanttProject und ist
öffentlich.
