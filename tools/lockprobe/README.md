# Sperr-Probe: sperrt der Desktop sich selbst aus?

NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.

## Wozu

Prüft an einem echten Server, ob eine Datei nach dem **eigenen** Sperren noch als schreibbar gilt.

**Der Fehler, den das abfängt:** Milton setzt beim PROPFIND `lockToken` und `lockOwner` gemeinsam,
nach einem eigenen `lock()` aber nur das Token. `getLockOwners()` liefert dann den Platzhalter
`"Unknown user"`, `doCanLock()` meldet `LOCK_UNAVAILABLE`, `isWritable()` ist `false` — und der
Desktop verweigert das Speichern der Datei, die er selbst gerade gesperrt hat. Am Bildschirm:
„Dokument kann nicht geschrieben werden", ohne dass es je zu einem `PUT` kommt.

Im Original war das unerreichbar, weil `acquireLock()` dort keinen Aufrufer hatte. D1 hat es
freigelegt: mit der Voreinstellung von 120 Minuten war Speichern über WebDAV kaputt.

**Warum das kein Einheitstest ist:** Der Zustand entsteht erst aus echten Antworten des Servers auf
`LOCK` und `PROPFIND`. Ein Test mit erfundenen Antworten stellt sich genau die Eingabe selbst, um
die es geht — dieselbe Lücke wie bei `tools/etagprobe`.

Das Programm **schreibt nicht**. Die Sperre wird am Ende wieder gelöst, auch im Fehlerfall.

## Zugangsdaten

Dieselbe Umgebungsdatei wie `tools/etagprobe`, ausserhalb des Repos. Siehe dort.

## Ausführen

Vorher bauen: `./gradlew :ganttproject-builder:distApp`

```bash
ROOT=ganttproject-builder/dist-app/GanttProject/app
CP=""; for f in $(find "$ROOT" \( -name "*.jar.lib" -o -name "*.jar" \)); do CP="$CP;$(cygpath -w "$f")"; done; CP="${CP#;}"
OUT=$(cygpath -w /tmp/lockprobe)
javac -encoding UTF-8 -cp "$CP" -d "$OUT" tools/lockprobe/LockProbe.java
MSYS_NO_PATHCONV=1 java -cp "$OUT;$CP" LockProbe "C:\Users\...\webdav-test.env" haus.gan
```

`MSYS_NO_PATHCONV=1` ist unter Git Bash nötig, sonst macht MSYS aus dem Dateinamen einen
Windows-Pfad. Siehe `tools/packcheck/README.md`, dort steht derselbe Stolperstein.

Erwartet:

```
schreibbar VOR dem Sperren  : true
schreibbar NACH dem Sperren : true
ERGEBNIS=OK
```

Die erste Zeile ist die eingebaute Gegenprobe: stünde dort schon `false`, prüfte das Programm etwas
anderes als gemeint — etwa fehlende Schreibrechte.

## Gegenprobe

Am 15.08.2026 gefahren, indem die Behebung in `MiltonResourceImpl.doCanLock()` vorübergehend
entfernt wurde (`git stash`, bauen, laufen lassen, `git stash pop`):

```
schreibbar VOR dem Sperren  : true
schreibbar NACH dem Sperren : false
ERGEBNIS=FEHLER (der Desktop sperrt sich selbst aus)
```

Ohne diesen Lauf wäre nicht belegt, dass das Programm den Fehler überhaupt sehen kann.
