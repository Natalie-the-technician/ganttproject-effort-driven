# ETag-Probe gegen einen echten WebDAV-Server

NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.

## Wozu

Prüft, ob der ETag beim PROPFIND tatsächlich ankommt. Daran hängt das bedingte Schreiben (D3):
ohne ETag sendet GanttProject kein `If-Match` und überschreibt fremde Änderungen wieder
kommentarlos — ohne Meldung, ohne Absturz, ohne Testfehler.

**Warum das kein Einheitstest sein kann:** Der Fehler lag im Zusammenspiel von Bibliothek und
Server. Milton fragt von sich aus eine feste Eigenschaftsliste ab (`creationdate`,
`getlastmodified`, `getcontentlength`, `displayname`, `resourcetype`, `iscollection`,
`lockdiscovery`) — `getetag` fehlt darin. `File.getEtag()` lieferte deshalb **immer** `null`. Ein
Test mit erfundenen Antworten stellt sich die Eingabe selbst und kann das nicht finden; die
vorhandenen Tests zu `resolveIfMatch` waren richtig und blieben grün, während der Schutz
wirkungslos war.

## Zugangsdaten

Stehen in einer Umgebungsdatei **ausserhalb des Repos**, mit drei Zeilen:

```
GP_DAV_BASE=https://server.example/testordner/
GP_DAV_USER=...
GP_DAV_PASS=...
```

Nicht ins Repo, nicht in den Code, nicht in ein Dokument. Nicht über die Befehlszeile übergeben —
die steht in der Prozessliste jedes anderen Benutzers. Ein eigener Testordner, nicht die
produktiven Projekte.

## Ausführen

Vorher bauen: `./gradlew :ganttproject-builder:distApp`

```bash
ROOT=ganttproject-builder/dist-app/GanttProject/app
CP=$(find "$ROOT" \( -name "*.jar.lib" -o -name "*.jar" \) -printf "%p;")
javac -encoding UTF-8 -cp "$CP" -d /tmp/etagprobe tools/etagprobe/EtagProbe.java
java -cp "/tmp/etagprobe;$CP" EtagProbe /pfad/zur/umgebungsdatei /haus.gan
```

Unter Git Bash auf Windows die Pfade mit `cygpath -w` ausschreiben — siehe
`tools/packcheck/README.md`, dort steht derselbe Stolperstein. Zusätzlich `MSYS_NO_PATHCONV=1`
voranstellen: sonst macht Git Bash aus dem Argument `/haus.gan` einen Windows-Pfad, der PROPFIND
läuft ins Leere und das Ergebnis sieht aus wie „Server liefert keinen ETag" — genau der Fehler, den
dieses Programm eigentlich ausschließen soll.

```bash
MSYS_NO_PATHCONV=1 java -cp "$OUT;$CP" EtagProbe "C:\Users\...\webdav-test.env" /haus.gan
```

Erwartet:

```
MIT angeforderter Eigenschaft : ETag="..."
OHNE angeforderte Eigenschaft : ETag=null
ERGEBNIS=OK
```

Die zweite Zeile ist die Gegenprobe und läuft im selben Aufruf mit: käme dort ebenfalls ein Wert,
wäre die gefundene Ursache nicht die Ursache.
