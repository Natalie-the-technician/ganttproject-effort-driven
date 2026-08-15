# Desktop-GanttProject: Sperren und bedingtes Schreiben

**Für die Sitzung, die den Desktop-Fork bearbeitet.** Die Gegenstücke sind
`HANDOVER-Sync-Server.md` (Protokoll) und `HANDOVER-Sync-Client.md`
(Android-App). Dieses Dokument steht für sich — man muss die anderen beiden
nicht gelesen haben.

Stand: 15. August 2026. **D1 und D3 sind gebaut**, auf Branch `zeiterfassung`:

| | Commit | Dateien |
|---|---|---|
| **D3 Teil 1** — `If-Match` beim Schreiben | `0aa1d9c` | `IfMatchResolution.kt` (neu), `IfMatchResolutionTest.kt` (neu), `MiltonResourceImpl.java`, `WebDavResource.java` |
| **D1 + D3 Teil 2** — Sperre wird genommen, Konfliktdialog | `ca82016` | `DocumentCreator.java`, `HttpDocument.java`, `HttpDocumentOutputStream.java`, `WebDavStorageImpl.java`, `ProjectUIFacadeImpl.kt`, `i18n*.properties` |

**Noch offen:** D2 (reine Beschriftung) und **T4** (braucht das Telefon).
T1, T2, T3 und T5 sind gegen den echten Server gelaufen und bestanden.

### D1c — was D1 dabei freigelegt hat

Beim ersten Lauf mit gesetzter Sperre **konnte der PC nicht mehr speichern**.
Nicht `423`, nicht `412` — es kam nie zu einem `PUT`.

Ursache, aus Miltons Bytecode belegt: Beim `PROPFIND` setzt Milton `lockToken`
und `lockOwner` gemeinsam; nach einem **eigenen** `lock()` aber nur das Token.
Der Besitzer bleibt `null`, `getLockOwners()` liefert `"Unknown user"`, das
passt nie zum eigenen Benutzernamen — und `isWritable()` meldet „nicht
schreibbar". **Der PC sperrte sich selbst aus.**

Im Original war das **unerreichbar**, weil `acquireLock()` dort keinen Aufrufer
hatte. D1 hat den Fehler geweckt, nicht verursacht. Behoben: Wer das Token
selbst hält, ist schreibfähig.

Das ist der Grund, warum die Android-App **keine** Sperre nimmt und das per
Test festgehalten wird (`WebDavClientTest`, „the client never locks"). Ein
Client, der nie sperrt, kann diese Fehlerklasse nicht haben.

Beide Commits führen **keine** `HANDOVER-*`, `NOTIZ-*` oder `ENTWURF-*` mit;
sie sind damit einzeln übernehmbar.

---

**Zugänge gehören nicht hierher.** Konten anlegen, Passwörter ausliefern und
zurücksetzen steht in `HANDOVER-Server-Zugaenge.md` — der Desktop verbraucht
nur Zugangsdaten, er verwaltet keine.

## 0. Worum es geht, in drei Sätzen

Die Projektdateien liegen jetzt auf einem WebDAV-Server
(Apache mit `mod_dav_fs`; die Adresse steht im Serverlog, nicht hier), der Versionen prüft und
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

**Korrektur vom 14. August 2026.** Eine frühere Fassung dieses Dokuments
nannte D1 „eine Einstellung, sofort ohne Codeänderung erledigt". **Das war
falsch**, und die Desktop-Sitzung hat es im Quelltext belegt. Es sind
**zwei unabhängige Defekte**, und keiner davon lässt sich konfigurieren:

**D1a — Die Sperrdauer erreicht das Dokument nie.**

```java
// HttpDocument.java:59-61 — der Konstruktor, den DocumentCreator benutzt
public HttpDocument(String url, String username, String password, StringOption proxyOption) {
  this(new MiltonResourceFactory(...).createResource(...), username, password, -1);
}                                                                              // ↑ fest verdrahtet
```

Beide Aufrufstellen verdrahten den Wert fest: `DocumentCreator.java:116`
implizit über den Konstruktor oben, `WebDavStorageImpl.java:178` als
`HttpDocument.NO_LOCK`. Die Option `webdav.lockTimeout` wird angezeigt,
gespeichert — und von **keiner** Stelle gelesen, die ein Dokument erzeugt.

**D1b — `acquireLock()` wird nirgends aufgerufen.**

Im ganzen Repo gibt es keine Aufrufstelle. Die einzige Fundstelle außerhalb
von Deklarationen ist `ProxyDocument.java:106`, eine Weiterreichung, die
selbst niemand aufruft. Toter Code.

**Folge:** Die WebDAV-Sperre wird **nie** genommen, unabhängig von jeder
Einstellung. Wer 120 einträgt, ist genauso ungeschützt wie vorher — und
glaubt, geschützt zu sein. Das ist schlimmer als offensichtlich kaputt.

**Zu tun, beides im Code:**
1. Die Option bis zu `HttpDocument` durchreichen — `DocumentCreator` hat sie
   bereits zur Hand (`DocumentCreator.java:66`), sie kommt nur nicht am
   Konstruktor an.
2. `acquireLock()` beim Öffnen aufrufen und `releaseLock()` beim Schließen,
   und den Rückgabewert **auswerten**: Scheitert die Sperre, muss das dem
   Menschen gesagt werden, nicht verschwiegen.
3. Erst danach die Voreinstellung von `-1` auf einen positiven Wert ändern.

Die Einstellung darf trotzdem gesetzt werden — sie schadet nicht und wird
gebraucht, sobald 1 und 2 stehen. Sie ist bis dahin nur **kein Schutz**.

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

`$BASIS` ist die WebDAV-Sammlung — Adresse aus dem Serverlog, sie gehört
nicht in dieses Repo. Ob sie auf der Wurzel liegt oder auf einem Unterpfad — **welches von beiden, ist hier nicht belegt.** Die
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

1. **D1a + D1b** — Option durchreichen, `acquireLock()` aufrufen und
   auswerten. Danach T1 und T2; erst damit ist belegt, dass überhaupt gesperrt
   wird. **Es gibt keine Abkürzung über die Einstellungen.**
2. **D3** — `If-Match`, schließt die Lücke, die auch bei gesetzter Sperre
   bleibt (abgelaufene Sperre, ohne Sperre geöffnet, Server ohne Sperren).
3. **D2** — Beschriftung, wenn ohnehin jemand in der Datei ist.

**Bis D1 und D3 stehen, ist der Schutz einseitig**, und zwar vollständig: Das
Telefon kann nichts vom PC überschreiben, der PC überschreibt vom Telefon
alles, jederzeit, ohne Sperre und ohne Bedingung. Die Android-App hält
deshalb ihre Warnung zum Bearbeitungsschutz aufrecht und stuft den Speicher
**nicht** als verwaltet ein, obwohl der Server sperren kann — siehe
`ProjectViewModel.DESKTOP_HONOURS_LOCKS`. Diese Konstante ist in derselben
Änderung auf `true` zu setzen, die D1 und D3 abschließt.

---

## 6. Antwort auf eure Übergabe vom 15.08.2026

### 6a. T4 sind zwei Läufe, nicht einer — bitte mit T4a anfangen

Eure Messung nehme ich an: Dieser Apache wertet die Vorbedingung **vor** der
Sperre aus. Ich hatte in einem Testkommentar die umgekehrte Reihenfolge
behauptet, hergeleitet statt gemessen; korrigiert.

Genau daraus folgt aber, dass euer vorgeschlagener T4 nicht prüft, was er
prüfen soll. In Schritt 2 sperrt ihr **und ändert die Datei**. Damit ist das
Telefon veraltet, der Server antwortet `412`, und die App zeigt einen
Konflikt — den Weg, den `412` schon vor D1 nahm. Der Sperrweg wird dabei nie
betreten.

* **T4a — der neue Weg.** Sperren, **nichts ändern**. Telefon ist aktuell.
  Erwartung: `423` → „wird gerade am PC bearbeitet", kein Konfliktdialog.
* **T4b — die Gegenprobe.** Sperren **und** ändern. Erwartung: `412` →
  Konfliktdialog.

**Nur T4a kann `DESKTOP_HONOURS_LOCKS = true` tragen.** T4b allein belegt die
Zusicherung nicht, weil der Weg, um den es dabei geht, ungegangen bliebe.
Beide zusammen zeigen, dass die App die Fälle auseinanderhält.

### 6b. Der Android-Zweig ist umgeschrieben — nicht ziehen, sondern zurücksetzen

Natalie hat ja gesagt, und ich habe es auf meinem eigenen Zweig selbst
gemacht; ihr müsst ihn nicht anfassen. `claude/gantt-android-app-wxxu9o` ist
mit `--force-with-lease` neu geschoben.

**Wenn ihr den Zweig lokal habt, nicht `git pull`.** Ein Zusammenführen holt
die alten Commits zurück und macht die Bereinigung rückgängig:

```
git fetch origin claude/gantt-android-app-wxxu9o
git reset --hard origin/claude/gantt-android-app-wxxu9o   # nur falls ausgecheckt
```

Zwei Berichtigungen zu eurer Fassung:

* Es waren **fünf** Commits, nicht vier: `8b4d5adf8`, `4a9d48a25`,
  `d6066c240`, `8af6e22a4`, `8e7b47502`. Eure Zählung ging vermutlich von den
  Commits aus, die die Dateien *anfassen*; der Wert steht aber auch im Baum
  jedes Commits dazwischen.
* **Der Force-Push entfernt es nicht von GitHub.** Verwaiste Commits bleiben
  über ihre SHA abrufbar. Nachgeprüft, nicht vermutet: `8e7b47502` liefert
  nach dem Umschreiben weiter eine vollständige Antwort über die API. Wer das
  wirklich weg haben will, kommt an GitHub-Support nicht vorbei. Für den
  geplanten öffentlichen Fork ist das ohnehin der falsche Weg — der bekommt
  eine **frische** Historie, nicht diese.

Belegt ist die Bereinigung so: Baum-Hash von HEAD vorher und nachher
identisch (`3686532060318fa91c5d14e21ed53f5df7044851`), 43 Commits vorher wie
nachher, Betreffzeilen und geänderte Dateien je Commit identisch, und beide
Werte in keinem Commit des Zweigs mehr auffindbar.

### 6c. Was auf der Android-Seite steht

Keine Änderung am Produktivcode: `412` → `ChangedElsewhere` (Konflikt),
`423` → `LockedElsewhere` (warten). Diese Zuordnung war richtig, nur meine
Begründung war es nicht. 256 Kerntests, keine Fehler.
`DESKTOP_HONOURS_LOCKS` bleibt `false` bis T4a.

---

## 7. Aus den T4-Messungen vom 15.08.2026 — bitte anpassen

Drei Befunde, die eure Seite betreffen. Der erste ist der dringende.

### 7a. Euer `Unconditional`-Zweig hat ein Loch, und es ist meines gewesen

`IfMatchResolution.kt` ist eine Portierung meines Codes, letzte Zeile:

```kotlin
return if (isWeakEtag(current)) IfMatchDecision.Unconditional else IfMatchDecision.Send(current)
```

**Diesen Zweig habe ich heute entfernt.** Die Begründung, die in eurem
Kommentar wie in meinem stand — „nur die Millisekunden, in denen jemand
zweimal innerhalb einer Sekunde speichert" — setzt voraus, dass Schwäche
immer nur Apaches mtime-Fenster ist. Am Server gemessen:

* **Kurz schwach:** Sub-Sekunden-mtime. Vergeht von selbst.
* **Dauerhaft schwach:** Wird die Repräsentation unterwegs verändert,
  verlangt RFC 9110 einen schwachen ETag — `mod_deflate`, nginx mit `gzip`,
  jeder komprimierende Proxy oder ein CDN. Das vergeht **nie**.

Im zweiten Fall ist der Zweig kein Randfall, sondern **jeder Schreibvorgang**.
D3 fiele damit still auf den Zustand vor D3 zurück: bedingungslos schreiben,
ohne Anzeichen, dass der Schutz weg ist.

Dass es heute nicht auftritt, ist Zufall der Konfiguration — in Caddy steht
kein `encode`, `mod_deflate` ist nicht geladen. Gemessen, mit drei
`Accept-Encoding`-Varianten. Wird Komprimierung irgendwann eingeschaltet,
bricht es lautlos.

**Was ich stattdessen gebaut habe:** rund 1,1 s warten, erneut fragen. Wird
der Wert stark, normal weiter — das deckt das echte Fenster ab. Bleibt er
schwach, **den Schreibvorgang ablehnen** und sagen, dass dieser Server keine
Versionsprüfung beantworten kann. Das bewusste „trotzdem überschreiben" des
Benutzers geht weiterhin durch; nur die App tut es nicht mehr von selbst.

### 7b. Euer Kommentar hat die Häufigkeit vertauscht

> Called ONLY when [remembered] is weak — one extra request in a rare case,
> none in the normal one.

Für euer eigenes Aufrufumfeld stimmt das nicht. `MiltonResourceImpl.java:360`
setzt `myEtagAtRead = fetchCurrentEtag()` **unmittelbar nach dem PUT** — genau
in der Sekunde, in der Apache schwach meldet. Der gemerkte ETag ist nach jedem
Speichern schwach, und der „seltene" Weg ist der, den ihr ab dem zweiten
Speichern **immer** geht.

Heute folgenlos, weil bis zum nächsten Speichern eine Sekunde vergeht und die
Nachfrage einen starken Wert liefert. Aber es heißt: Was in 7a steht, trifft
euch auf dem Hauptweg, nicht in einer Ecke.

### 7c. `OPTIONS` beweist nichts über den Pfad

Am Server gemessen: `OPTIONS` auf einen Pfad, den es **nicht gibt**, antwortet
`200` und meldet `DAV: 1,2`. `OPTIONS` beschreibt den Server, nicht die
Ressource. Falls ihr irgendwo eine Verbindungsprüfung habt, die daraus
schließt, dass die Adresse stimmt: Sie kann eine falsche Adresse nicht
erkennen. Der Unterschied steckt allein im `Allow` — ohne `PROPFIND` und `GET`
darin gibt es den Pfad nicht. Meine Prüfung fragt jetzt zusätzlich per
`PROPFIND Depth 0` nach der Sammlung selbst.

### 7d. Und eine Verpflichtung, die neu auf euch liegt

`ProjectViewModel.DESKTOP_HONOURS_LOCKS` steht seit heute auf `true`. Die App
sagt ihren Benutzern damit **nicht mehr**, dass der PC ihre Arbeit jederzeit
überschreiben kann.

Diese Aussage hängt vollständig an euch: Sobald ein Desktop-Build keine Sperre
mehr nimmt **oder** kein `If-Match` mehr sendet — auch still, wie in 7a —,
ist sie falsch, und die App verschweigt eine Gefahr, die es wieder gibt.
**Sagt bitte Bescheid, bevor sich daran etwas ändert**, dann setze ich sie
zurück.
