# Verpackungsprüfung für das Textbündel

NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.

## Wozu

Prüft, ob das eigene Textbündel des Forks im **gebauten Plugin** unter dem Pfad liegt, unter dem
das laufende Programm es sucht.

**Warum das nicht im normalen Testlauf steckt:** Es geht dort nicht. `ganttproject/build.gradle`
meldet `src/main/resources/resources` als Klassenpfadeintrag (Z. 44) *und* `src/main/resources`
als Ressourcenwurzel (Z. 114). Unter Test antwortet das Bündel deshalb auf **beide** Pfade. Im
verpackten Plugin nur auf einen, weil `plugin.xml` `resources/` zur Bibliothekswurzel macht.

Ein Einheitstest kann einen betriebstauglichen Pfad also nicht von einem falschen unterscheiden —
mit einem verbogenen Pfad blieben alle Tests grün und das gebaute Programm zeigte statt jeder
Beschriftung den nackten Schlüssel. Genau das hat Gegentest 22 gezeigt. Dieses Programm setzt als
Klassenpfad ausschließlich die echte Bibliothekswurzel und schließt die Lücke.

## Ausführen

Vorher bauen: `./gradlew :ganttproject-builder:clean :ganttproject-builder:distBin`

```bash
javac -encoding UTF-8 -d /tmp/packcheck tools/packcheck/PackCheck.java && java -Dfile.encoding=UTF-8 -cp "/tmp/packcheck;ganttproject-builder/dist-bin/plugins/base/ganttproject/resources" PackCheck
```

Erwartet: `ERGEBNIS=OK`, Rückgabewert 0.

## Gegenprobe

Denselben Aufruf mit der Wurzel **eine Ebene höher** — also
`ganttproject-builder/dist-bin/plugins/base/ganttproject` statt `.../resources`. Erwartet:
`ERGEBNIS=FEHLER(6)`. Kommt dort OK, prüft das Programm nicht mehr, was es soll.

## Unter Git Bash auf Windows

Der Aufruf oben schlägt dort mit `ClassNotFoundException` fehl: MSYS wandelt einen Pfad hinter
`-d` in einen Windows-Pfad um, in der zusammengesetzten Klassenpfad-Zeichenkette mit `;` aber
nicht. Pfade deshalb ausschreiben:

```bash
OUT=$(cygpath -w /tmp/packcheck)
RES=$(cygpath -w "$PWD/ganttproject-builder/dist-bin/plugins/base/ganttproject/resources")
java -Dfile.encoding=UTF-8 -cp "$OUT;$RES" PackCheck
```

Umlaute in der Ausgabe können dabei zerhackt aussehen — das ist die Zeichentabelle der Konsole,
nicht der Inhalt der Datei.
