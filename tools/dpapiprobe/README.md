# DPAPI-Probe

NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.

## Wozu

Prüft, ob die Windows-Verschlüsselung (DPAPI) auf dem **ausgelieferten** Klassenpfad überhaupt
läuft. Daran hängt `SecretStore`, und damit, ob das WebDAV-Passwort verschlüsselt statt im Klartext
gespeichert wird.

**Warum das nicht selbstverständlich ist:** `jna-platform` kommt nur mittelbar über `net.harawata:appdirs`
herein, und der Kern `jna` ist dort ausgeschlossen (`ganttproject/build.gradle:52`). Ausgeliefert
werden dadurch zwei verschiedene Stände — `com.sun.jna_5.13.0` als Eclipse-Bündel und
`jna-platform-5.16.0`. Eine solche Mischung fällt nicht beim Übersetzen auf, sondern erst zur
Laufzeit. Ohne diesen Lauf wäre die Aufwandsschätzung geraten gewesen.

## Ausführen

Vorher bauen: `./gradlew :ganttproject-builder:distApp`

```bash
ROOT=ganttproject-builder/dist-app/GanttProject/app
CP=""; for f in $(find "$ROOT" \( -name "*.jar.lib" -o -name "*.jar" \)); do CP="$CP;$(cygpath -w "$f")"; done; CP="${CP#;}"
javac -encoding UTF-8 -cp "$CP" -d /tmp/dpapiprobe tools/dpapiprobe/DpapiProbe.java
java -Dfile.encoding=UTF-8 -cp "$(cygpath -w /tmp/dpapiprobe);$CP" DpapiProbe
```

Erwartet:

```
Chiffrelaenge          : 246 Bytes
enthaelt Klartext?     : false
Rundlauf gleich?       : true
ERGEBNIS=OK
```

Beide Hälften zählen: „kommt zurück" allein würde auch eine Ablage bestehen, die gar nichts
verschlüsselt — deshalb wird zusätzlich geprüft, dass der gespeicherte Wert das Passwort **nicht**
enthält.

Auf Nicht-Windows-Systemen ist ein Fehlschlag der erwartete Ausgang; `SecretStore.protect` liefert
dort `null` und der Aufrufer speichert dann nicht.
