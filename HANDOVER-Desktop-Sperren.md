# Desktop-GanttProject: Sperren und bedingtes Schreiben

**Für die Sitzung, die den Desktop-Fork bearbeitet.** Die Gegenstücke sind
`HANDOVER-Sync-Server.md` (Protokoll) und `HANDOVER-Sync-Client.md`
(Android-App). Dieses Dokument steht für sich — man muss die anderen beiden
nicht gelesen haben.

Stand: 14. August 2026. **Nichts davon ist gebaut.** D1 ist eine Einstellung,
D2 eine Beschriftung, D3 die eigentliche Arbeit.

---

**Zugänge gehören nicht hierher.** Konten anlegen, Passwörter ausliefern und
zurücksetzen steht in `HANDOVER-Server-Zugaenge.md` — der Desktop verbraucht
nur Zugangsdaten, er verwaltet keine.

## 0. Worum es geht, in drei Sätzen

Die Projektdateien liegen jetzt auf einem WebDAV-Server
(`<serveradresse>`, Apache mit `mod_dav_fs`), der Versionen prüft und
sperren kann. Die Android-App nutzt beides: Sie schickt bei jedem Speichern
`If-Match` mit der Version, auf der ihre Änderungen beruhen, und der Server
weist ein veraltetes Schreiben mit `412` ab.

**Der Desktop tut das nicht.** Er kann sperren, tut es im Auslieferungszustand
aber nie, und er schreibt ohne `If-Match`. Damit ist der Schutz einseitig: Das
Telefon kann nichts vom PC überschreiben, der PC vom Telefon schon.

---

## 1. Die drei Befunde

Alle im Quellcode dieses Repos nachgesehen, nicht vermutet.

### D1 — Die Sperre ist standardmäßig aus (Konfiguration, kritisch)

```java
// ganttproject/…/webdav/WebDavStorageImpl.java:61
private final IntegerOption myWebDavLockTimeoutOption =
    new DefaultIntegerOption("webdav.lockTimeout", -1);
```

```java
// ganttproject/…/webdav/HttpDocument.java:130
public boolean acquireLock() {
  if (locked || myTimeout < 0) {
    return true;          // ← meldet Erfolg, ohne gesperrt zu haben
  }
  …
  getWebdavResource().lock(myTimeout * 60);
```

Voreinstellung `-1` bedeutet: `acquireLock()` sperrt **nicht** und gibt
**trotzdem `true` zurück**. Der Aufrufer hat keine Möglichkeit, das zu
bemerken. GanttProject läuft also gegen einen sperrfähigen Server und nutzt
die Sperre nie — ohne Meldung, ohne Logzeile, ohne irgendein Anzeichen.

**Sofort, ohne Codeänderung:** In den Einstellungen unter WebDAV die
Sperrdauer auf einen positiven Wert in Minuten setzen. 120 ist ein
vernünftiger Anfang: lang genug für eine Arbeitssitzung, kurz genug, dass eine
vergessene Sperre nach einem Absturz von selbst verfällt.

**Im Fork:** Voreinstellung auf einen positiven Wert ändern. Eine
Schutzfunktion, die standardmäßig aus ist **und das verschweigt**, ist
schlimmer als gar keine — sie erzeugt Vertrauen, das nicht gedeckt ist.

Beim Ändern mit prüfen, ob `acquireLock()` bei `myTimeout < 0` weiterhin
`true` liefern soll. Ehrlicher wäre, „nicht gesperrt" von „Sperre
fehlgeschlagen" zu unterscheiden; das ist aber eine größere Änderung, weil
die Aufrufer den Rückgabewert heute kaum auswerten.

### D2 — „Ohne Sperre öffnen" ist ein eigener Knopf (Beschriftung)

```java
// ganttproject/…/webdav/WebDavStorageImpl.java:179
receiver.setDocument(new HttpDocument(
    myWebDavFactory.createResource(webDavUri),
    chooser.getUsername(), chooser.getPassword(),
    HttpDocument.NO_LOCK));          // NO_LOCK == -1
```

`createNoLockAction` erzeugt bewusst ein Dokument ohne Sperre. Das ist eine
legitime Wahl und soll bleiben. Wer sie trifft, hat für diese Sitzung aber
keinen Schutz, und der Knopf sagt das heute nicht. Falls der Fork ohnehin
angefasst wird: benennen, was er abschaltet.

### D3 — Der Desktop schreibt ohne `If-Match` (Code, die eigentliche Lücke)

```java
// ganttproject/…/webdav/MiltonResourceImpl.java:282
if (myImpl != null && myImpl.getLockToken() != null) {
  parentFolder.upload(getName(), is, (long) byteArray.length,
      "application/xml", new IfMatchCheck(myImpl.getLockToken(), false, true), null);
} else {
  parentFolder.upload(getName(), is, (long) byteArray.length, null);  // ← bedingungslos
}
```

Der Desktop sendet **nur** das Sperr-Token, und nur wenn er eines hält. Ohne
Sperre — also mit D1 im Auslieferungszustand, nach Ablauf der Sperrdauer, an
einem Server ohne Sperrunterstützung, oder nach „ohne Sperre öffnen" —
überschreibt er **bedingungslos**. Genau das lautlose Überschreiben, das
dieses ganze Vorhaben abschaffen soll.

---

## 2. Wie D3 zu bauen ist

Die Milton-Bibliothek liegt im Repo unter
`biz.ganttproject.app.libs/lib/milton-client-2.7.4.4-bs.jar` und kann alles
Nötige. Nachgesehen mit `javap`, nicht angenommen:

```java
public class io.milton.httpclient.IfMatchCheck {
  public IfMatchCheck(String etag);
  public IfMatchCheck(String etag, boolean setIfMatch, boolean setIf);
}
public class io.milton.httpclient.File {
  public String getEtag();
}
public class io.milton.httpclient.PropFindResponse {
  public String getEtag();
}
```

**Der heutige Aufruf ist `(lockToken, setIfMatch=false, setIf=true)`** — er
setzt also den `If:`-Header mit dem Sperr-Token, **nicht** `If-Match`. Für
D3 wird `(etag, true, false)` gebraucht.

`IfMatchCheck` trägt genau **einen** String. Sperr-Token und ETag lassen sich
über diesen Weg also nicht gleichzeitig senden. Das ist kein Problem, sondern
passt: Gebraucht wird `If-Match` genau dann, wenn **kein** Sperr-Token
vorliegt — mit Sperre ist der Fall bereits abgedeckt.

### Vorgehen

1. **ETag beim Lesen merken.** `MiltonResourceImpl` hält `myImpl` (ein
   `io.milton.httpclient.File`). Nach dem Lesen `getEtag()` abholen und in
   `HttpDocument` neben dem Dokument aufbewahren — dasselbe Muster, das die
   App mit `lastKnownETag` verwendet.

2. **Beim Schreiben senden.** Im `else`-Zweig oben statt `null` ein
   `new IfMatchCheck(gemerkterEtag, true, false)` übergeben, sofern ein ETag
   vorliegt. Liegt keiner vor, bleibt es beim heutigen Verhalten — dann ist
   aber ohnehin nichts bekannt, worauf man sich beziehen könnte.

3. **`412` als Konflikt behandeln, nicht als allgemeinen Fehler.** Milton
   wirft bei `412` vermutlich `HttpException` mit Statuscode; das ist beim
   Bauen zu prüfen. Der Nutzer braucht dort dieselbe Wahl wie in der App:
   *unter anderem Namen speichern* oder *trotzdem überschreiben*. „Trotzdem"
   schickt denselben Upload **ohne** `IfMatchCheck`.

4. **Nach erfolgreichem Schreiben den neuen ETag holen.** Achtung, hier
   lauert derselbe Fallstrick, in den die App schon getreten ist:

> **Der Apache liefert bei `PUT` keinen ETag**, nur bei `HEAD`/`GET`/`PROPFIND`.
> Und **innerhalb einer Sekunde nach dem Schreiben meldet er den ETag als
> schwach** (`W/"…"`), danach denselben Wert stark.
>
> `If-Match` wird nach RFC 7232 **stark** verglichen — ein schwacher ETag
> scheitert daran gegen alles, auch gegen sich selbst. Wer den frisch
> geholten Wert unverändert zurückschickt, bekommt bei **jedem** Speichern
> `412` und zeigt dem Nutzer einen Konflikt, den es nicht gibt.
>
> Die App löst das beim Senden: Ist der gemerkte ETag schwach, wird per
> `HEAD` nachgesehen — anderer Wert heißt echter Konflikt, gleicher Wert und
> inzwischen stark wird benutzt, gleicher Wert und weiter schwach wird
> bedingungslos geschrieben, weil das `HEAD` von eben belegt hat, dass der
> Inhalt unserer ist. Siehe `WebDavClient.resolveIfMatch` in
> `android/gantt-core/…/WebDavClient.kt` — dort steht die Begründung
> ausführlich, und die sieben Tests dazu sind übertragbar.

Das ist am Desktop weniger dringlich als in der App (dort speichert die
Automatik im Hintergrund, hier drückt ein Mensch auf Speichern), aber
„zweimal schnell hintereinander speichern" gibt es auch am PC.

---

## 3. Abnahme

Ohne diese Prüfungen ist nicht belegt, dass etwas besser geworden ist —
insbesondere D1 sieht vorher und nachher identisch aus.

`$BASIS` ist die WebDAV-Sammlung, also `https://<serveradresse>` oder
ein Unterpfad davon — **welches von beiden, ist hier nicht belegt.** Die
Server-Sitzung nennt die Wurzel als Zugang und meldet dort `401`; der
Ablageort `/srv/webdav/projects` sagt nichts über den URL-Pfad. Vor T1 einmal
mit `curl -sSI -u "$U:$P" -X OPTIONS "$BASIS/"` klären: `DAV: 1,2` heißt
richtig, `404` heißt falscher Pfad.

**T1 — Die Sperre wird tatsächlich genommen.**
Projekt am PC über WebDAV öffnen und offen lassen. Dann von einer zweiten
Stelle schreiben wollen:
```sh
curl -sS -o /dev/null -w '%{http_code}\n' -u "$U:$P" \
  -T beliebig.gan "$BASIS/haus.gan"
```
Erwartung `423`. *Vor D1 kommt hier `204` — das ist der Beweis, dass die
Voreinstellung den Schutz aushebelt.*

**T2 — Die Sperre wird wieder freigegeben.**
Projekt schließen, T1 wiederholen. Erwartung: geht durch.

**T3 — `If-Match` wird gesendet (D3).**
Projekt am PC öffnen, **ohne** Sperre (über „ohne Sperre öffnen"). Von außen
etwas anderes schreiben. Dann am PC speichern.
Erwartung: **Konfliktmeldung**, Datei auf dem Server unverändert.
*Vor D3 überschreibt der PC hier klaglos die fremde Änderung — das ist die
Lücke in einem Satz.*

**T4 — Gegenprobe zur App.**
Projekt am Telefon öffnen, am PC ändern und speichern, dann am Telefon
speichern. Erwartung: Das Telefon meldet einen Konflikt. Das funktioniert
schon heute und muss nach der Änderung weiter funktionieren.

**T5 — Zweimal schnell speichern.**
Am PC speichern und sofort noch einmal. Erwartung: beide Male in Ordnung,
keine Konfliktmeldung. Das ist die Probe auf den schwachen ETag aus §2.4.

---

## 4. Was ausdrücklich **nicht** zu tun ist

* **Kein Umbau auf ein eigenes Protokoll.** WebDAV ist der Grund, warum der
  Desktop überhaupt ohne Änderung funktioniert hat.
* **Keine automatische Zusammenführung zweier Fassungen.** Ein Programm, das
  zwei Projektpläne vereinigt, erfindet Termine, die niemand geprüft hat.
* **Kein Abschalten der Zertifikatsprüfung**, auch nicht zum Testen. Bei
  Basic Auth gehen die Zugangsdaten in jedem Request mit.
* **Den `W/`-Präfix nicht generell abstreifen.** Auf diesem Apache ginge es
  gut, weil der Wert die Unter-Sekunden-mtime trägt. Als allgemeine Regel im
  Client würde es Schreibvorgänge durchlassen, die abgewiesen gehören.

---

## 5. Reihenfolge

1. **D1 als Einstellung** — sofort, kostet nichts, deckt den Alltagsfall ab.
   Danach T1 und T2 durchführen; erst damit ist belegt, dass gesperrt wird.
2. **D1 im Fork** — Voreinstellung ändern, damit es nicht an einer manuellen
   Einstellung hängt, die beim nächsten Rechner wieder fehlt.
3. **D3** — die eigentliche Arbeit, schließt die verbleibende Lücke.
4. **D2** — Beschriftung, wenn ohnehin jemand in der Datei ist.

Bis D3 steht, gilt: Der Schutz ist einseitig. Das ist kein Grund zu warten —
mit gesetzter Sperrdauer deckt die Sperre den Alltag bereits ab —, aber es
sollte niemand glauben, es sei fertig.
