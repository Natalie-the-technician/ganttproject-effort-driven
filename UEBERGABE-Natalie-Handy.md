# Für Natalie — was ich von dir brauche

Stand 15.08.2026, Zweig `zeiterfassung`. Alles Folgende ist geschoben.

Kurz vorweg: **Ich komme allein weiter, außer bei drei Dingen.** Zwei davon gehen vom Handy.

---

## 1. T4 — geht vom Handy, ohne dass du an den PC musst

Das ist der einzige offene Test, und er blockiert das Release der Android-App.

**Der Trick:** Du brauchst den PC dafür nicht. Ich kann die Rolle des PCs übernehmen — Sperre setzen
und die Datei ändern, genau wie GanttProject es täte. Du brauchst nur das Telefon.

**Ablauf:**

1. Du schreibst mir: **„Telefon hat `haus.gan` offen"**
   → Öffne das Projekt in der App und lass es offen liegen.
2. Ich sperre die Datei und ändere sie — wie der PC beim Speichern.
3. Ich schreibe dir: **„fertig, jetzt speichern"**
4. Du änderst am Telefon etwas Kleines und speicherst.
5. Du schreibst mir, **was die App meldet** — Konflikt, Warten, Fehler, oder es geht durch.

Erwartet nach dem, was ich gemessen habe: **ein Konflikt**, nicht „am PC in Bearbeitung". Denn
dieser Apache prüft `If-Match` vor der Sperre — bei veraltetem Stand kommt `412`, nicht `423`.

Meldet die App „am PC in Bearbeitung", ist meine Messung unvollständig, und das will ich wissen.

Danach löse ich die Sperre und stelle die Datei zurück.

---

## 2. Zwei Blicke, wenn du wieder am PC bist

Die Einstellungsseiten zeigen fast nichts, und ich komme ohne Bildschirm nicht weiter.

- **`Bearbeiten → Einstellungen → WebDAV`** — Bildschirmfoto
- **Fenster vorher größer ziehen**, so groß es geht

Was ich wissen will: Liegen dort mehrere Felder **übereinander** (dann ist es das Layout), oder ist
wirklich nur eines da (dann sind die anderen ausgeblendet)? Auf der Seite „Allgemein" fehlen die
Beschriftungen neben den Bedienelementen — dasselbe Bild.

Solange das so ist, **kannst du die Sperrdauer über die Oberfläche gar nicht einstellen**, nur in
`C:\Users\ofran\.ganttproject`. Sie steht dort auf `120`.

Mein D2-Hinweistext sitzt genau auf dieser Seite. Er ist gebaut und im Bündel nachgewiesen, aber
ich hake ihn nicht ab, bevor ihn jemand gesehen hat.

---

## 3. Zwei Entscheidungen, die dir gehören

**Der Servername steht im Verlauf des Android-Zweigs.** Vier Commits auf
`claude/gantt-android-app-wxxu9o`, alle schon auf GitHub. Der jüngste hat ihn aus dem *Stand*
entfernt — die drei davor enthalten ihn weiter. Nachträgliches Löschen löscht nicht aus dem
Verlauf. Das Repo ist privat, der Schaden also begrenzt.

Soll ich den Zweig umschreiben und mit `--force-with-lease` neu schieben? **Antworte mit ja oder
nein**, ich fasse einen fremden Zweig nicht ungefragt an.

**Das Passwort aus meinem Fehler von heute früh** — falls noch nicht geändert, bitte ändern. Ich
hatte einen Teil davon in die Ausgabe gezogen.

---

## Was inzwischen fertig ist

| | |
|---|---|
| T1 Sperre wird genommen | bestanden |
| T2 Sperre wird freigegeben | bestanden |
| T3 Konflikt ohne Sperre | bestanden |
| T5 kein Fehlalarm | bestanden |
| Selbstaussperrung | gefunden und behoben |
| Protokollfrage `423`/`412` | gemessen |
| Übergabe an Android | geschrieben und korrigiert |

**Zwei Fehler, die nur der echte Server gezeigt hat:**

`D3 hat nie ein If-Match gesendet.` Milton fragt beim PROPFIND `getetag` gar nicht ab, der gemerkte
Wert war immer leer, und der Schutz fiel still auf „bedingungslos schreiben" zurück. Eingebaut,
angeschaltet, durch Tests gedeckt — und wirkungslos.

`Der PC sperrte sich selbst aus.` Nach dem eigenen Sperren hielt er die Datei für nicht schreibbar
und verweigerte das Speichern. Im Original unerreichbar, weil dort nie gesperrt wurde; D1 hat einen
schlafenden Fehler geweckt.

Beide sind behoben und mit eigenen Prüfprogrammen gegen den Server belegt (`tools/etagprobe`,
`tools/lockprobe`), jeweils mit Gegenprobe.

## Was noch offen ist

- **T4** — siehe oben, wartet auf dich
- **Leere Einstellungsseiten** — wartet auf einen Blick
- **Startmeldung „Failed to parse document"** beim Start: echter Fehler (401, weil kein Passwort
  gespeichert ist), aber falsch benannter Grund. Sauber wäre, beim Start nach dem Passwort zu
  fragen. Sag Bescheid, wenn ich das bauen soll.
- **„Speichern unter" nach einem Konflikt** wählt „Dieser Computer" vor und trägt die WebDAV-Adresse
  als örtlichen Pfad ein. Der Weg funktioniert über den Server links, die Vorauswahl ist falsch.

Nicht offen, aber zur Kenntnis: Ein Test (`GPCloudDocumentTest`) schlägt fehl. **Nicht von heute** —
er scheitert auf dem Stand vor dieser Sitzung genauso, an einem Windows-Pfadfehler im Test selbst.
