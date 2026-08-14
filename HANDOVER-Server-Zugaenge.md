# Zugänge und Ordnerfreigaben

**Für die Server-Sitzung**, mit einem Abschnitt für die Desktop-Sitzung (§3).
Nicht für die App-Sitzung — die Android-App verbraucht nur Zugangsdaten.

Stand: 14. August 2026. **Nichts davon ist gebaut.** Vorhanden ist
`gantt-benutzer.sh`, das Konten anlegt und entzieht und das erzeugte Passwort
nach `/root/gantt-zugang-<name>.txt` schreibt.

---

## 0. Der Entwurf

Natalies Vorgabe, und sie ist in einem wichtigen Punkt besser als das, was
ich zuerst vorgeschlagen hatte:

* Passwörter werden **gesetzt**, nicht selbst zurückgesetzt — kein Mailversand
* Freigegeben wird **pro Ordner**, nicht pro Datei
* Bedient wird das aus dem **Desktop-GanttProject** heraus, über ein
  **bestehendes Admin-Konto**

**Warum das sicherer ist:** Es gibt keinen Endpunkt ohne Anmeldung. Die
Selbstbedienung per Mail hätte einen öffentlich erreichbaren Dienst gebraucht,
der Konten anlegen darf — auf einem Server, auf dem `ufw` inaktiv ist, kein
fail2ban läuft und SSH über `sslh` auf 443 mithört. Diese Fläche entfällt
vollständig. Die Verwaltung liegt hinter derselben Basic-Auth wie alles andere,
beschränkt auf ein Konto.

**Was dadurch offen bleibt:** Wie das gesetzte Passwort zum Menschen kommt.
Dazu §4 — es ist kleiner als es klingt, aber es löst sich nicht von selbst.

### Was weiterhin nicht geht

`htpasswd` speichert einen Hash. Ein gesetztes Passwort ist danach niemandem
mehr zugänglich, auch nicht root. Möglich sind nur: einmalig beim Setzen
anzeigen, oder ein neues setzen. Das ist der Grund, warum das Verfahren sicher
ist, kein Mangel.

---

## 1. Ordner sind Pfade — darauf baut alles auf

Genau so ist es, und deshalb ist die Freigabe pro Ordner leicht und die pro
Datei wäre es nicht.

```
/srv/webdav/projects/
  intern/          → Require group intern
  kunde-mueller/   → Require group kunde-mueller
  privat/          → Require group privat
```

**Ein einziger Block für alle Ordner**, kein Block je Ordner. Der
Ordnername ist zugleich der Gruppenname und wird pro Anfrage aufgelöst:

```apache
<DirectoryMatch "^/var/www/webdav/(?<PROJEKT>[^/]+)(/|$)">
    AuthType Basic
    AuthName "GanttProject"
    AuthUserFile /srv/gantt/htpasswd
    AuthGroupFile /srv/gantt/gruppen
    Require group %{env:MATCH_PROJEKT}
    # Ohne dies antwortet Apache bei fehlender Gruppenzugehörigkeit mit 401
    # statt 403 — siehe Z1.
    AuthzSendForbiddenOnFailure On
</DirectoryMatch>
```

**Der Pfad ist der Pfad im Container**, nicht der auf dem Host. Ein Block mit
Hostpfad besteht `httpd -t` mit „Syntax OK" und greift trotzdem **nie** —
also genau die Sorte Fehler, die aussieht wie Erfolg. (Fand die Server-Sitzung
in einer früheren Fassung dieses Dokuments, wo `/srv/webdav/projects/…`
stand.)

**Die Gruppendatei muss über ein Verzeichnis eingebunden werden, nicht als
Einzeldatei.** Eine einzeln gebundene Datei hängt an ihrer Inode; ein
Austausch per `tmp`+`mv` erzeugt eine neue, und der Container sieht die
Änderung nie. Das ist dieselbe Falle wie beim Caddyfile.

Und die Gruppendatei bestimmt, wer drin ist:

```
intern: anna bernd
kunde-mueller: anna
privat: anna
```

### Der Punkt, auf den es ankommt: zwei verschiedene Häufigkeiten

| Vorgang | Was nötig ist | Reload? |
|---|---|---|
| **Person zu Ordner hinzufügen** | eine Zeile in `gruppen` | nein |
| **Person entziehen** | eine Zeile in `gruppen` | nein |
| **Neuen Ordner anlegen** | `mkdir` + eine Zeile in `gruppen` | nein |

**Gemessen, nicht angenommen** (Server-Sitzung, 14. August 2026, gegen den
Produktivbuild Apache 2.4.68 in einem Wegwerf-Container):
`mod_authz_groupfile` hält **keinen** Cache — auch keinen prozess- oder
verbindungslokalen. Der harte Nachweis war eine einzige offene
Keep-alive-Verbindung mit drei Anfragen desselben Benutzers und einer
Änderung der Gruppendatei dazwischen: `401 → 200 → 401`, bedient von
demselben Prozess und Thread. Kaputte, leere und fehlende Gruppendatei werden
sämtlich abgewiesen — *fail closed*.

Mit `DirectoryMatch` braucht **auch ein neuer Ordner keinen Reload**. Damit
entfällt die generierte Konfiguration vollständig — und mit ihr die
Wiederholung der Caddyfile-Falle, die diesen Abschnitt in der ersten Fassung
noch beschäftigt hat.

### Ordnername ≠ Anzeigename

Der Ordner steht in der URL. Er sollte deshalb kurz, klein und ohne Umlaute
sein (`kunde-mueller`, nicht `Kunde Müller GmbH & Co`). Wer einen schönen
Namen will, hinterlegt ihn separat — nicht im Pfad.

---

## 2. Was der Server dafür braucht

Eine kleine Verwaltungs-Schnittstelle, erreichbar **nur** mit dem Admin-Konto.
Kein öffentlicher Zugang, keine Selbstregistrierung.

```
GET    /admin/benutzer                       Liste
POST   /admin/benutzer                       anlegen, erzeugtes Passwort zurück
POST   /admin/benutzer/<name>/passwort       neu setzen, Passwort zurück
DELETE /admin/benutzer/<name>                entziehen

GET    /admin/ordner                          Liste, mit Mitgliedern
POST   /admin/ordner                          anlegen (Config + graceful reload)
PUT    /admin/ordner/<ordner>/mitglied/<name> freischalten
DELETE /admin/ordner/<ordner>/mitglied/<name> entziehen
```

Vorgaben, die nicht verhandelbar sind:

* **Nur das Admin-Konto.** `Require user <admin>` auf `/admin`, nicht
  `Require valid-user`. Ein normaler Nutzer, der sich selbst in fremde Ordner
  einträgt, wäre der Totalschaden.
* **Kein Admin-Konto in der Gruppendatei für Projektordner**, oder umgekehrt:
  Das Admin-Konto braucht keinen Projektzugriff, um Rechte zu verwalten. Zwei
  Aufgaben, zwei Konten — auch wenn dieselbe Person dahintersteht.
* **Name prüfen, bevor er in eine Datei geht.** Nur `[a-z0-9_-]`. Ein Name mit
  Leerzeichen, Doppelpunkt oder Zeilenumbruch zerlegt `htpasswd` und die
  Gruppendatei still. Ein `..` im Ordnernamen ist ein Pfadwechsel.
* **Schreiben über eine temporäre Datei und `rename`.** Ein abgebrochener
  Schreibvorgang mitten in `htpasswd` sperrt sonst alle aus.
* **Gleichzeitige Änderungen serialisieren.** Zwei parallele Aufrufe, die
  beide die Gruppendatei lesen, ändern und schreiben, verlieren eine der
  beiden Änderungen. Eine Sperrdatei genügt.
* **Passwörter tauchen im Log nicht auf** — weder im Zugriffs- noch im
  Anwendungslog.

Die Schnittstelle darf ruhig winzig sein: ein Shell-Skript hinter CGI tut es,
solange die Punkte oben eingehalten sind. `gantt-benutzer.sh` ist bereits die
halbe Miete und ist getestet — es aufzurufen ist besser, als seine Logik
nachzubauen.

---

## 3. Was der Desktop-Fork dafür braucht

Ein Dialog, der die Endpunkte aus §2 aufruft. In Java, im Fork, neben der
vorhandenen WebDAV-Unterstützung.

* Zugangsdaten für das Admin-Konto: **nicht** neben denen für die Projekte
  ablegen und **nicht** im Klartext in eine Einstellungsdatei. GanttProject
  hat für WebDAV bereits eine Ablage — dieselbe benutzen, nicht eine zweite
  erfinden.
* Nur `https`, Zertifikatsprüfung nicht abschaltbar. Gleiche Regel wie in der
  App: Bei Basic Auth gehen die Zugangsdaten in jedem Request mit.
* Das erzeugte Passwort wird **einmal** angezeigt, mit einem Knopf zum
  Kopieren und dem klaren Hinweis, dass es danach nicht mehr abrufbar ist.
* `403` vom Admin-Endpunkt heißt „dieses Konto darf das nicht" und muss auch
  so dastehen — nicht als allgemeiner Netzwerkfehler.

**Als eigenes Modul bauen**, nicht in `ganttproject` hinein. Das Projekt hat
diese Trennung bereits — `biz.ganttproject.impex.ical`,
`biz.ganttproject.impex.msproject2`, `org.ganttproject.chart.pert` sind alle
eigenständig in `settings.gradle`. Ein `biz.ganttproject.zugaenge` daneben
hält die Kontoverwaltung aus dem Kern heraus, und die aufwandsgetriebene
Terminplanung bleibt für sich übernehmbar.

Das ist kein Schönheitsargument: Wer den Terminplan übernehmen will, soll
nicht nebenbei Kontoverwaltung für einen fremden Server mitgeliefert bekommen.

---

### Wenn der Umzug auf GanttProject Cloud kommt

Dann entfällt dieser Abschnitt — und nur dieser. Es lohnt deshalb, **die
Serverseite zuerst zu bauen und das Desktop-Modul aufzuheben**: Die
Ordnerfreigaben über Gruppendatei sind ein knapper Tag Arbeit und über
`gantt-benutzer.sh` sofort benutzbar, das Modul ist der teure Teil und genau
der, der bei einem Umzug wertlos wird.

Was ein Umzug sonst kostet, damit die Entscheidung nicht nebenbei fällt:
`WebDavClient` und `RemoteStore` müssten gegen ein **undokumentiertes**
Protokoll neu geschrieben werden, und die Abrechnung ist dort 1 Kredit je
aktivem Teammitglied und Abrechnungszeitraum — der Eigentümer ausgenommen.
Solange du allein arbeitest, ist das gegenstandslos; ab dem zweiten Menschen
nicht mehr.

Unberührt bleibt in beiden Fällen alles, was in `gantt-core` steckt:
Dokumentmodell, Rückgängig, Toggl-Import, Bearbeitungsschutz. Das hängt an
keinem Speicherort.

## 4. Wie das Passwort zum Menschen kommt

Das löst dein Entwurf noch nicht: Du setzt es, siehst es einmal — und musst es
weitergeben.

**Für eine Handvoll Leute genügt der Weg, den du ohnehin hast**: vorlesen,
über Signal schicken, persönlich übergeben. Kein Aufwand, keine neue Fläche.

**Nicht per E-Mail.** Ein Passwort in einer Mail bleibt dort: im Postausgang,
im Postfach, in den Backups beider Seiten, unverschlüsselt auf jedem
Zwischenserver. Wenn es unbedingt schriftlich sein muss, dann ein
**Einmal-Link**, der es genau einmal zeigt und danach verfällt — dann steht in
der Mail kein Passwort, und ein abgefangener, bereits eingelöster Link fällt
dem Empfänger sofort auf, weil seiner nicht mehr funktioniert.

Der Einmal-Link braucht allerdings wieder einen Endpunkt ohne Anmeldung. Er
ist deutlich kleiner als die Selbstbedienung aus dem verworfenen Entwurf
(kein Formular, keine Eingabe, kein Mailversand), aber er ist nicht nichts.
**Aufheben, bis der Bedarf real ist.**

---

## 5. Abnahme

Alle Erwartungen unten setzen `AuthzSendForbiddenOnFailure On` voraus (§1).
**Ohne diese Direktive antwortet Apache bei fehlender Gruppenzugehörigkeit mit
`401` statt `403`**, und Z1/Z4/Z5/Z6 schlagen fehl, obwohl die Trennung
greift. Das ist nicht nur eine Zahl: Mit `401` bekommt ein nicht
freigeschalteter Mensch endlos die Passwortabfrage und hält sein Passwort für
falsch. Fehlende Anmeldung und falsches Passwort bleiben korrekt bei `401`.

**Z1 — Ohne Mitgliedschaft kein Zugriff.**
```sh
curl -sS -o /dev/null -w '%{http_code}\n' -u "neu:$P" "$BASIS/kunde-mueller/"
```
Erwartung `403`. *Gegentest: Kommt hier `200`, greift `Require group` nicht —
dann sind alle Ordner für alle offen, und das sieht man nirgends.*

**Z2 — Mit Mitgliedschaft Zugriff.**
Nach dem Freischalten derselbe Aufruf. Erwartung `207`/`200`.

**Z3 — Mitgliedschaft wirkt ohne Reload.** ✅ *bestanden, 14. August 2026*
Benutzer freischalten, **ohne** Apache anzufassen sofort Z2 wiederholen.
Erwartung: geht. Bei einer Wiederholung den Keep-alive-Fall mitprüfen — eine
offene Verbindung, drei Anfragen, Änderung dazwischen — sonst bliebe ein
verbindungslokaler Cache unentdeckt.

**Z4 — Entzug wirkt sofort.**
Mitgliedschaft entfernen, Z1 wiederholen. Erwartung `403`, ohne Reload.

**Z5 — Andere Ordner bleiben unberührt.**
Nach jeder Änderung: Zugriff auf einen Ordner, in dem der Benutzer **nicht**
ist. Erwartung `403`. *Eine Gruppendatei, die beim Schreiben durcheinander
gerät, öffnet sonst still fremde Ordner.*

**Z6 — Nur das Admin-Konto darf verwalten.**
`/admin/benutzer` mit einem normalen Projektkonto. Erwartung `403`.
*Gegentest: Mit `Require valid-user` statt `Require user <admin>` käme hier
`200` — und jeder Nutzer könnte sich selbst überall eintragen.*

**Z7 — Böse Namen werden abgewiesen.**
Anlegen mit `../root`, `a:b`, `a b`, einem Zeilenumbruch, einem leeren Namen.
Erwartung: jeweils Ablehnung, und `htpasswd` sowie `gruppen` danach
unverändert.

**Z8 — Ein abgebrochener Schreibvorgang sperrt niemanden aus.**
Verwaltungsdienst mitten im Anlegen beenden (`kill -9`). Danach muss ein
vorhandener Nutzer sich weiterhin anmelden können.

**Z9 — Passwörter stehen in keinem Log.**
Nach Anlegen und Zurücksetzen: `docker logs`, `journalctl`, Apache- und
Caddy-Log durchsuchen. Erwartung: kein Treffer.

**Z10 — Neuer Ordner greift sofort.**
Ordner anlegen (`mkdir` + Zeile in `gruppen`), ohne Apache anzufassen darauf
zugreifen. Erwartung: geht. *Mit `DirectoryMatch` gibt es keine generierte
Konfiguration mehr — der frühere Reload-Test ist damit gegenstandslos.*

**Z11 — `MOVE` und `COPY` prüfen auch das Ziel.** ⚠️ *ungeprüft in der ersten
Fassung dieses Dokuments; von der Server-Sitzung gefunden*

Ein Benutzer, der **nur** in `projekt-c` freigeschaltet ist:
```sh
# Datei aus dem eigenen Ordner in einen fremden verschieben
curl -sS -o /dev/null -w '%{http_code}\n' -u "bob:$P" -X MOVE \
  -H "Destination: $BASIS/projekt-b/geklaut.gan" "$BASIS/projekt-c/meins.gan"
# und auf eine vorhandene fremde Datei
curl -sS -o /dev/null -w '%{http_code}\n' -u "bob:$P" -X MOVE \
  -H "Destination: $BASIS/projekt-b/fremd.gan" "$BASIS/projekt-c/meins.gan"
```
Erwartung beides `403`. Dasselbe mit `COPY`.

**Ohne Absicherung liefert Apache `201` und `204`** — die Autorisierung prüft
nur die **Quelle**, nie das **Ziel**. Gemessen. Wer irgendwo schreiben darf,
darf damit überall schreiben, und die Ordnernamen sieht jeder per `PROPFIND`
auf der Wurzel. Eine Sperre fängt es ab, aber nur bei geöffnetem Plan.

Abhilfe über `mod_rewrite` ist von der Server-Sitzung gemessen: fremdes Ziel
`403`, innerhalb des eigenen Ordners weiterhin `201`, Z3 unberührt.

*Warum das hier steht:* **Keine** der Prüfungen Z1–Z10 hätte es gefunden — Z5
prüft nur Lesezugriff. Genau die Sorte Lücke, gegen die diese Liste gedacht
ist, und sie war trotzdem nicht drin.

---

## 5a. Was nicht in dieses Repo gehört

Weder Hostnamen noch Benutzernamen noch Pfade der echten Anlage. Nicht weil
sie geheim wären, sondern weil ein Repo veröffentlicht werden kann und dann
alles mitnimmt, was je darin stand — auch aus der Vorgeschichte.

Hostname plus Benutzername ist bei einem Basic-Auth-Endpunkt die halbe
Anmeldung, und solche Endpunkte werden gescannt, sobald sie im Netz stehen.

Der Ort dafür ist `/root/SERVER-UMBAU-LOG.md` auf dem Server. Hier stehen
Platzhalter.

## 5b. Wie diese Prüfungen zu schreiben sind

Eine Lehre aus dem Bau, formuliert von der Server-Sitzung und hier
festgehalten, weil sie für **jede** künftige Prüfliste gilt — auch für die
A-Liste im Protokolldokument und die Gerätetests der App.

Z1 bis Z10 fragen zwei Sorten Dinge:

* Darf der Berechtigte, was er darf?
* Wird der Unberechtigte abgewiesen?

**Beide Sorten übersehen dasselbe: was der Berechtigte darf, ohne dass es
jemand vorgesehen hat.** Falle 7 war genau das — ein Mitglied von `intern`
durfte `DELETE` auf `/intern` und damit den ganzen Ordner löschen. Der
Zugriff war erlaubt, das Konto war berechtigt, keine Prüfung war verletzt.
Auf keiner Liste stand die Frage, weil niemand sie sich gestellt hatte.

Gefunden wurde sie nicht durchs Messen allein, sondern dadurch, dass **nach**
dem Bauen noch einmal das Gegenteil geprüft wurde, statt zu bestätigen, dass
das Erlaubte funktioniert.

Für neue Prüfungen deshalb zusätzlich fragen: *Was kann ein völlig regulärer,
berechtigter Zugriff hier anrichten, das niemand gewollt hat?* Bei WebDAV
sind die üblichen Verdächtigen `DELETE` auf eine Sammlung statt eine Datei,
`MOVE`/`COPY` mit einem Ziel außerhalb, `MKCOL` an unerwarteter Stelle und
`PROPPATCH`.

## 6. Was ausdrücklich nicht gebaut werden soll

* **Keine Ablage, aus der Passwörter wieder auslesbar sind.** Auch nicht
  verschlüsselt mit einem Schlüssel, der auf demselben Server liegt.
* **Kein Passwort im Mailtext.**
* **Keine Selbstregistrierung**, kein öffentlicher Endpunkt, solange §4 nicht
  zwingend danach verlangt.
* **Keine Rechte pro Datei.** Ordner genügen, und Dateirechte wären in Apache
  ein Vielfaches an Konfiguration für denselben Zweck.
* **`/root/gantt-zugang-<name>.txt` nicht als Dauerablage weiterführen.** Nach
  der Übergabe löschen — Übergabepunkt, kein Speicher.
