# Sync-Server — Anforderungen und Aufbau

**Für die Server-Sitzung.** Das Gegenstück für die App-Sitzung ist
`HANDOVER-Sync-Client.md`. Dieses Dokument ist die **normative Quelle für das
Protokoll**; der Client richtet sich danach, nicht umgekehrt.

Stand: 14. August 2026. Der Client ist noch nicht gebaut — diese Spezifikation
ist der Vertrag, gegen den beide Seiten entstehen.

---

## 1. Wozu das Ganze

Eine `.gan`-Datei in einem synchronisierten Ordner hat keine Instanz, die
entscheidet. Telefon und PC können beide schreiben, der letzte gewinnt —
lautlos. Die Android-App erkennt das im Nachhinein (Inhaltsfingerabdruck,
`Fingerprint.kt`) und weigert sich zu überschreiben, aber **Erkennen ist nicht
Verhindern**: Speichert der PC über eine Telefonänderung, die noch nicht
synchronisiert war, ist sie weg, und nichts auf dem Telefon holt sie zurück.

Der Server schafft die fehlende Instanz. Er hält eine Version pro Datei und
weist ein Schreiben zurück, das gegen einen veralteten Stand gemacht wurde.

## 2. Warum WebDAV und kein eigenes Protokoll

Weil **das Desktop-GanttProject WebDAV bereits vollständig spricht**, samt
Sperren. Nachgeprüft im Quellcode dieses Repos, nicht vermutet:

| Fähigkeit | Fundstelle |
|---|---|
| Sperre beim Öffnen | `ganttproject/…/webdav/HttpDocument.java:130` `acquireLock()` |
| Freigabe beim Schließen | `…/HttpDocument.java:151` `releaseLock()` |
| Sperre mit Ablaufzeit | `…/MiltonResourceImpl.java:166` `lock(timeout)` |
| Schreiben **mit** Sperr-Token | `…/MiltonResourceImpl.java:282` `IfMatchCheck(lockToken, …)` |
| Erkennt Server ohne Sperre | `…/MiltonResourceImpl.java:341` `CanLockStatus.LOCK_UNSUPPORTED` |

Ein eigenes Protokoll hieße: Server schreiben **und** den Desktop-Fork
umbauen. WebDAV heißt: Server aufsetzen, Desktop unverändert lassen. Und es
ist trotzdem eine eigene, feste, dokumentierte API auf eigener Hardware —
genau das Ziel.

---

## 3. Was der Server können muss

Nummeriert, damit der Client (`HANDOVER-Sync-Client.md`) darauf verweisen
kann. **MUSS** = ohne das funktioniert es nicht. **SOLL** = ohne das
funktioniert es schlechter, aber es bricht nichts.

### S1 — HTTP-Methoden (MUSS)

`OPTIONS`, `HEAD`, `GET`, `PUT`, `DELETE`, `PROPFIND`, `LOCK`, `UNLOCK`,
`MKCOL`.

Achtung bei Reverse-Proxys: Viele Vorlagen lassen nur `GET`/`POST`/`HEAD`
durch. `PROPFIND`, `LOCK`, `UNLOCK` sind keine Standardmethoden und werden
gern stillschweigend mit `405` beantwortet.

### S2 — ETag bei GET (MUSS)

Jede `GET`- und `HEAD`-Antwort auf eine Projektdatei **muss** einen `ETag`
mitliefern, und der ETag **muss sich bei jeder Inhaltsänderung ändern**.

Ein ETag, der nur aus der Änderungszeit mit Sekundenauflösung gebildet wird,
ist **nicht ausreichend**: Zwei Schreibvorgänge in derselben Sekunde bekämen
denselben ETag, und genau das ist der Fall, den wir abfangen wollen. Inode +
Größe + Nanosekunden, oder ein Hash über den Inhalt.

### S3 — Bedingtes Schreiben (MUSS)

`PUT` mit `If-Match: "<etag>"`:

* ETag stimmt → `200` oder `204`, Datei ersetzt
* ETag stimmt nicht → **`412 Precondition Failed`**, Datei **unverändert**

`PUT` mit `If-None-Match: *` legt neu an und liefert `412`, wenn die Datei
schon da ist.

Ein `PUT` **ohne** `If-Match` überschreibt bedingungslos. Das ist erlaubt und
wird gebraucht (bewusstes „trotzdem überschreiben"), darf aber nie der
Normalfall des Clients sein.

### S4 — ETag in der PUT-Antwort (SOLL)

Die Antwort auf ein erfolgreiches `PUT` **soll** den neuen `ETag` enthalten.
Fehlt er, muss der Client ein zusätzliches `HEAD` hinterherschicken — das
kostet einen Umlauf und ein Zeitfenster, in dem ein fremdes Schreiben
unbemerkt dazwischenrutschen kann.

### S5 — Sperren (SOLL, aber der eigentliche Gewinn)

`LOCK` mit exklusivem Schreib-Sperrbereich nach RFC 4918, `UNLOCK` mit
`Lock-Token`. Ein Schreiben ohne gültiges Token auf eine gesperrte Datei
antwortet `423 Locked`.

**Der Punkt, der sonst still danebengeht:** Der Desktop erkennt
Sperrfähigkeit ausschließlich über die PROPFIND-Eigenschaft
`<D:supportedlock>` (`MiltonResourceImpl.java:129`, `:345`). Meldet der Server
sie nicht mit einem **exclusive write**-Eintrag, geht der Desktop von
`LOCK_UNSUPPORTED` aus und **sperrt nie** — ohne Fehlermeldung, ohne dass
irgendwo etwas rot wird. Es sieht aus, als liefe alles, und der Schutz fehlt.
Das ist die wichtigste einzelne Abnahmeprüfung (siehe A5).

Bekannte Fallstricke bei der Serverwahl:

* **nginx**, eingebautes `ngx_http_dav_module`: kann nach meinem Kenntnisstand
  **kein** `LOCK` und kein `PROPFIND`. Auch mit `nginx-dav-ext-module` kommt
  `PROPFIND`/`OPTIONS` dazu, aber weiterhin kein `LOCK`. **Bitte prüfen**, ich
  konnte es hier nicht nachschlagen (Netzwerk gesperrt).
* **Apache `mod_dav`** mit `mod_dav_fs` und `DavLockDB`: kann Sperren.
* **Nextcloud**: kann Sperren, Versionierung und Geräte-Passwörter.

Ohne Sperre funktioniert alles weiter — man fällt auf S3 zurück, also auf
Erkennen statt Verhindern. Das ist der Stand von heute, nur mit einer echten
Version statt eines Dateizeitstempels.

### S6 — Bytegenaue Ablage (MUSS)

Was hochgeladen wurde, muss beim `GET` **Byte für Byte identisch** wieder
herauskommen. Keine XML-Umformatierung, keine Neukodierung, kein eingefügtes
BOM, keine Zeilenendenumwandlung, keine Transkodierung durch einen Proxy.

Das ist keine Pedanterie. Die gesamte App ist darauf gebaut, Teile der Datei,
die sie nicht versteht — Ansichten, Kalender, Rollen, Baselines, Notizen —
unangetastet durchzureichen. Ein Server, der die XML-Datei „aufräumt",
zerstört diese Eigenschaft, und zwar unsichtbar.

### S7 — Transportsicherheit (MUSS)

Nur HTTPS mit gültigem Zertifikat. Kein `http://`, auch nicht im internen
Netz, auch nicht „vorübergehend zum Testen". Der Client wird `http://`-Adressen
ablehnen und die Zertifikatsprüfung nicht abschaltbar machen.

Grund: Die Zugangsdaten gehen bei Basic Auth in jedem Request mit.

### S8 — Anmeldung (MUSS)

HTTP Basic über TLS genügt und ist das, was der Desktop-Client von sich aus
kann (`MiltonResourceImpl.java:296`, `NotAuthorizedException` bei 401).

**Pro Gerät ein eigenes Passwort**, kein gemeinsames. Dann lässt sich das
Telefon einzeln sperren, ohne den PC auszusperren. Nextcloud kann das von
Haus aus („App-Passwörter"); bei Apache genügen mehrere Einträge in der
`htpasswd`, die auf denselben Ordner berechtigt sind.

### S9 — Statuscodes, die der Client auswertet

| Code | Bedeutung für den Client |
|---|---|
| `200`/`204` | in Ordnung |
| `201` | neu angelegt |
| `401` | Zugangsdaten falsch → Einstellungen öffnen |
| `403` | angemeldet, aber kein Schreibrecht → schreibgeschützt anzeigen |
| `404` | Projekt weg oder umbenannt |
| `412` | **Datei wurde anderswo geändert** → Konfliktdialog |
| `423` | **jemand hält die Sperre** → „am PC geöffnet" anzeigen |
| `5xx` | Serverfehler → erneut versuchen anbieten, nichts verwerfen |

Diese Zuordnung ist verbindlich. Ein Server, der bei einem Versionskonflikt
`409` statt `412` liefert, führt zu einer falschen Meldung beim Nutzer.

---

## 4. Abläufe

### Konfliktfreier Fall

```
App                                Server
 |-- GET /projekte/haus.gan ------->|
 |<-- 200, ETag: "a1", <bytes> -----|
 |   (bearbeiten)                   |
 |-- PUT, If-Match: "a1" ---------->|
 |<-- 204, ETag: "a2" --------------|
```

### Konflikt

```
App                                Server        (PC hat inzwischen gespeichert)
 |-- PUT, If-Match: "a1" ---------->|
 |<-- 412 Precondition Failed ------|
 |   Konfliktdialog: Kopie speichern / trotzdem überschreiben
```

### Datei am PC geöffnet

```
App                                Server
 |-- PUT, If-Match: "a2" ---------->|
 |<-- 423 Locked -------------------|
 |   Hinweis: "wird gerade am PC bearbeitet"
```

---

## 5. Vorgeschlagener Aufbau

Bewusst klein gehalten. Das hier ist Dateiablage mit Versionsprüfung, keine
Groupware.

```
/srv/gantt/
  projekte/            <- der WebDAV-Ordner
    haus.gan
    kunde-xy.gan
  locks/               <- DavLockDB, falls Apache
```

* **Ein** WebDAV-Ordner, flach oder mit wenigen Unterordnern. Keine
  Nutzertrennung nötig, solange nur du darauf zugreifst.
* Kein automatisches Aufräumen, kein Umbenennen, kein Verschieben durch
  Skripte. Alles, was Dateien anfasst, ohne dass der Client es weiß, erzeugt
  genau die Konflikte, die wir abschaffen wollen.
* **Backup**: mindestens täglich, versioniert, außerhalb des Servers. Der
  Server ist jetzt die einzige Quelle der Wahrheit — vorher lag noch eine
  Kopie in OneDrive und eine auf dem PC.
* **Aufbewahrung alter Fassungen**: sehr empfehlenswert. Eine simple Variante
  ist ein Cronjob, der den Ordner stündlich in ein Git-Repository **außerhalb**
  des WebDAV-Ordners committet. Damit ist jeder Konflikt, der doch einmal falsch
  aufgelöst wird, nachträglich reparierbar.
  Das Repository darf **nicht** im WebDAV-Ordner selbst liegen.

### Was ausdrücklich **nicht** gebaut werden soll

* Kein eigenes HTTP-Protokoll. Jede Abweichung von WebDAV kostet die
  Desktop-Unterstützung, die es umsonst gibt.
* Keine serverseitige Zusammenführung zweier Fassungen. Ein Server, der zwei
  `.gan`-Dateien „vereinigt", produziert Pläne, die niemand geprüft hat.
* Keine Validierung oder Reparatur der XML-Datei durch den Server. Siehe S6.
* Keine Benachrichtigungen, kein WebSocket, kein Hintergrunddienst. Nicht in
  der ersten Fassung.

---

## 6. Abnahmeprüfungen

Jede Anforderung mit einer Prüfung, die **fehlschlagen muss**, wenn sie nicht
erfüllt ist. Eine Prüfung, die nie gescheitert ist, ist wertlos — deshalb
steht bei jeder dabei, wie man den Fehlerfall absichtlich erzeugt.

Ersetze `$U`, `$P`, `$B` durch Benutzer, Passwort und Basis-URL.

**A1 — Methoden erlaubt**
```sh
curl -sSI -u "$U:$P" -X OPTIONS "$B/projekte/" | grep -i '^allow\|^dav'
```
Erwartung: `DAV: 1,2` (die `2` ist die Sperrunterstützung) und eine
`Allow`-Liste mit `PROPFIND`, `LOCK`, `PUT`.
*Gegentest:* Fehlt die `2`, ist S5 nicht erfüllt, egal was sonst funktioniert.

**A2 — ETag vorhanden und stabil**
```sh
curl -sSI -u "$U:$P" "$B/projekte/test.gan" | grep -i '^etag'
```
*Gegentest:* Zweimal innerhalb einer Sekunde unterschiedliche Inhalte
hochladen und die ETags vergleichen — sie **müssen** sich unterscheiden.
```sh
printf 'A' | curl -sS -u "$U:$P" -T - "$B/projekte/test.gan"; E1=$(curl -sSI -u "$U:$P" "$B/projekte/test.gan" | grep -i '^etag')
printf 'B' | curl -sS -u "$U:$P" -T - "$B/projekte/test.gan"; E2=$(curl -sSI -u "$U:$P" "$B/projekte/test.gan" | grep -i '^etag')
[ "$E1" != "$E2" ] && echo "ok" || echo "FEHLER: ETag ändert sich nicht"
```

**A3 — If-Match wird durchgesetzt**
```sh
curl -sS -o /dev/null -w '%{http_code}\n' -u "$U:$P" \
  -H 'If-Match: "voellig-falscher-etag"' -T datei.gan "$B/projekte/test.gan"
```
Erwartung: `412`.
*Gegentest:* Kommt `200` oder `204`, ignoriert der Server die Bedingung — das
ist der gefährlichste Fehlerfall überhaupt, weil alles zu funktionieren
scheint und der Schutz komplett fehlt. Danach prüfen, dass die Datei
**unverändert** ist.

**A4 — bytegenaue Ablage**
```sh
curl -sS -u "$U:$P" -T HouseBuildingSample.gan "$B/projekte/rt.gan"
curl -sS -u "$U:$P" "$B/projekte/rt.gan" -o zurueck.gan
cmp HouseBuildingSample.gan zurueck.gan && echo "byteidentisch" || echo "FEHLER"
```
Die Datei liegt im Repo unter `android/gantt-core/src/test/resources/`.

**A5 — supportedlock wird gemeldet** *(der stille Killer)*
```sh
curl -sS -u "$U:$P" -X PROPFIND -H 'Depth: 0' "$B/projekte/test.gan" \
  | grep -i 'supportedlock\|lockscope\|exclusive'
```
Erwartung: ein `<D:supportedlock>` mit `<D:exclusive/>` und `<D:write/>`.
*Gegentest:* Fehlt das, sperrt der Desktop **nie** — ohne Fehlermeldung. Man
merkt es erst, wenn Arbeit verloren gegangen ist.

**A6 — Sperre wirkt**
```sh
# Sperren
curl -sS -u "$U:$P" -X LOCK -H 'Timeout: Second-600' \
  --data '<?xml version="1.0"?><D:lockinfo xmlns:D="DAV:"><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype></D:lockinfo>' \
  "$B/projekte/test.gan" | grep -i 'locktoken\|opaquelocktoken'
# Ohne Token schreiben
curl -sS -o /dev/null -w '%{http_code}\n' -u "$U:$P" -T datei.gan "$B/projekte/test.gan"
```
Erwartung des zweiten Aufrufs: `423`.
*Gegentest:* Kommt `204`, ist die Sperre reine Dekoration.

**A7 — Desktop-Gegenprobe** *(die einzige, die wirklich zählt)*
Projekt im Desktop-GanttProject über WebDAV öffnen, geöffnet lassen, dann von
einer zweiten Maschine (oder per `curl`) schreiben wollen. Muss abgelehnt
werden. Danach im Desktop speichern, schließen, erneut schreiben — muss jetzt
gehen.

---

## 7. Fragen an die Server-Sitzung

Beantwortet das bitte aus dem laufenden System heraus, nicht aus der
Erinnerung — davon hängt ab, welchen Server ich in der Client-Spezifikation
voraussetze.

1. **Was läuft schon?** Betriebssystem und Version, Webserver bzw. Reverse
   Proxy (nginx / Apache / Caddy / Traefik) und wie TLS gelöst ist (certbot,
   Traefik-ACME, manuell).
2. **Container?** Docker, Podman, compose, oder alles direkt auf dem Host?
3. **Ist Nextcloud installiert?** Falls ja: Version, und ob der WebDAV-Endpunkt
   `/remote.php/dav/` von außen erreichbar ist. Das entscheidet, ob wir
   überhaupt etwas bauen müssen.
4. **Freier Hostname?** Eine Subdomain, auf die ich in der Doku verweisen kann,
   und ob DNS dort selbst verwaltet wird.
5. **Verfügbarer Arbeitsspeicher und Plattenplatz** — Nextcloud gegen ein
   schlankes `mod_dav` ist im Wesentlichen diese Entscheidung.
6. **Backup:** Gibt es eines, was ist eingeschlossen, wie oft, wohin, und wurde
   eine Rücksicherung schon einmal geprüft? Der Server wird die einzige Quelle
   der Wahrheit — das ist der Punkt, an dem ein ungeprüftes Backup teuer wird.
7. **Firewall und fail2ban:** Wird 443 gefiltert, gibt es Ratenbegrenzung? Ein
   WebDAV-Endpunkt mit Basic Auth wird gescannt, sobald er im Netz steht.
8. **Falls Apache in Frage kommt:** Sind `mod_dav`, `mod_dav_fs` und
   `mod_auth_basic` verfügbar, und wo dürfte die `DavLockDB` liegen (die
   braucht Schreibrecht für den Webserver-Nutzer)?
9. **Läuft dort schon ein Git-Server oder ein Repository-Verzeichnis**, in das
   der stündliche Sicherungs-Commit aus Abschnitt 5 gehen könnte?

Wenn ihr zu einer dieser Fragen einen Vorschlag habt, der von diesem Dokument
abweicht: gern, aber bitte gegen Abschnitt 3 prüfen. Die Anforderungen dort
stehen nicht aus Geschmack drin — jede einzelne hängt an einer Stelle im
Client oder im Desktop-Code, die sonst still das Falsche tut.
