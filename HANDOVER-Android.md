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
deshalb bei GitHub.

**Der bequeme Weg — Release (geht in der GitHub-App):**

1. **Actions** → **Android app** → **Run workflow**, Haken bei
   *Publish the APK as a GitHub release* stehen lassen
2. Wenn der Lauf grün ist: **Releases** öffnen, neuesten Eintrag antippen,
   die `.apk` antippen, Installation aus unbekannter Quelle erlauben

Kein ZIP, kein Browser, läuft nicht nach 90 Tagen ab.

**Wichtig:** Der Knopf „Run workflow" erscheint erst, wenn der Workflow auf dem
**Standard-Branch** liegt. Solange die Android-App nur auf
`claude/gantt-android-app-wxxu9o` liegt, ist er nicht da — dann bleibt der
Artefakt-Weg. Das ist eine Eigenart von GitHub, kein Fehler im Workflow.

**Der Notweg — Artefakt:** Jeder Push baut eine APK und hängt sie als
`ganttproject-mobile-debug-apk` an den Lauf. Nur über einen **Browser**
herunterladbar (die GitHub-App kann das nicht), kommt als ZIP, läuft nach
90 Tagen ab.

Beides sind Debug-Builds, signiert mit dem Standard-Debug-Schlüssel — zum
Seitwärtsinstallieren richtig, für den Play Store untauglich.

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

## 8. Nachtrag: der Import-Merker liegt jetzt in der Datei

In einer ersten Fassung lag der Merker, welche Toggl-Einträge schon importiert
wurden, auf dem Gerät. Natalies Rückfrage („hatten wir das nicht über die Custom
Columns gelöst?") war berechtigt — ich hatte einen projektweiten Ablageort für
unmöglich erklärt, ohne im Code nachzusehen.

**Was stimmt:** `GanttXMLSaver` schreibt unter `<project>` eine feste Kinderfolge
(views, calendar, tasks, resources, allocations, vacations, history, roles), und
`OptionSaver` schreibt nur registrierte Optionen. Ein eigenes Element oder eine
eigene Projektoption dort würde das Original-GanttProject beim nächsten Speichern
verwerfen. Beides im Code nachgesehen, nicht vermutet.

**Was ich übersehen hatte:** Die Vormerkung sagt „nicht je Vorgang" — das
betrifft den **Schlüssel** der Abbildung, nicht den **Ablageort**. Der Merker
liegt jetzt als Custom Property `toggl_imported` an den Vorgängen und wird beim
Lesen über **alle** Vorgänge vereinigt (`importedHoursByEntry()`). Damit ist er
projektweit, obwohl er je Vorgang gespeichert ist, und der Fall „Eintrag wird
beim zweiten Lauf einem anderen Vorgang zugeordnet" ist abgedeckt — es gibt
einen Test, der genau danach benannt ist.

**Zusätzlicher Gewinn:** Bricht man einen Import ab, indem man ohne Speichern
schließt, verschwindet der Merker zusammen mit den Stunden. Die Gerätefassung
hätte sich einen Import gemerkt, der nie in der Datei ankam — diese Stunden
wären dann nie wieder importierbar gewesen.

---

## 9. Offene Punkte

- [ ] **Aufteilen eines Eintrags auf mehrere Vorgänge** ist im Kern gebaut und
      getestet (`validateSplit`), in der Oberfläche noch nicht bedienbar.
- [ ] Kein Undo in der App. Ersatz: ohne Speichern schließen verwirft alles.
- [ ] Keine Instrumentierungstests der Oberfläche.

---

## 10. Veröffentlichung als eigenes Repo

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
