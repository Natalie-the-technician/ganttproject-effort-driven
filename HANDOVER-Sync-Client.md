# Sync-Client in der Android-App — Umsetzungsplan

**Für die App-Sitzung.** Das Gegenstück für die Server-Sitzung ist
`HANDOVER-Sync-Server.md`. **Dort steht das Protokoll**, hier steht nur, was
die App damit tut. Bei Widersprüchen gilt das Server-Dokument; die Anforderungen
werden hier als S1…S9 referenziert.

Stand: 14. August 2026. **Teilweise gebaut** — siehe §11 für den genauen
Stand. Der Rest dieses Dokuments bleibt der Plan.

---

## 1. Was sich dadurch ändert

Heute liegt das Projekt als Datei in einem synchronisierten Ordner, geöffnet
über das Android-Dokumentensystem (SAF). Die App vergleicht vor jedem
Speichern einen Inhaltsfingerabdruck und verweigert das Überschreiben, wenn
sich die Datei seit dem Öffnen geändert hat.

Mit dem Server wird daraus dasselbe Verfahren, nur mit einer **echten**
Version statt einer geratenen: Der `ETag` kommt vom Server, und der Server
selbst weist das Schreiben zurück (S3). Das Zeitfenster zwischen „prüfen" und
„schreiben", das lokal unvermeidbar ist, verschwindet — die Prüfung passiert
beim Schreiben, nicht davor.

**Der lokale Weg bleibt.** Nicht jedes Projekt liegt auf dem Server, und
offline muss die App weiter etwas taugen. Es kommt eine zweite Herkunft dazu,
die erste wird nicht ersetzt.

## 2. Wo das im vorhandenen Code andockt

Die Vorarbeit steht weitgehend. Was schon da ist und wiederverwendet wird:

| Vorhanden | Rolle beim Sync |
|---|---|
| `gantt-core/…/TogglClient.kt` → `HttpBackend` | die HTTP-Naht; der WebDAV-Client benutzt dieselbe |
| `app/…/net/AndroidHttpBackend.kt` | die Android-Umsetzung davon |
| `gantt-core/…/Fingerprint.kt` `FileChangeState` | Zustandsmodell für „anderswo geändert" — bleibt, wird nur vom ETag gespeist |
| `app/…/data/ProjectStore.kt` `OpenProject` | hält `lastKnownFingerprint`; bekommt daneben `lastKnownETag` |
| `app/…/data/Preferences.kt` `SecureStore` | AES-GCM mit Schlüssel im Keystore, nicht exportierbar, vom Backup ausgenommen — hier landet das Server-Passwort |
| `gantt-core/…/EditPolicy.kt` `SyncGuarantee` | genau hierfür gebaut, siehe §7 |
| Konfliktdialog in `AppScaffold.kt` | unverändert nutzbar, nur ein zweiter Auslöser |

Neu zu bauen ist im Kern **eine** Datei: ein WebDAV-Client. Das ist weniger,
als der Name verspricht — `GET` und `PUT` mit zwei Kopfzeilen.

## 3. Neue Bausteine

### `gantt-core/…/WebDavClient.kt`

Im Core, nicht in der App, damit er ohne Emulator testbar ist — genau wie
`TogglClient`. Bekommt `HttpBackend` hereingereicht, ruft selbst nichts
Android-spezifisches auf.

```kotlin
data class RemoteProject(val bytes: ByteArray, val etag: String?)

sealed interface DavError {
  data object Unauthorized : DavError        // 401  → S9
  data object Forbidden : DavError           // 403
  data object NotFound : DavError            // 404
  data object ChangedElsewhere : DavError    // 412  → Konfliktdialog
  data object LockedElsewhere : DavError     // 423  → "am PC geöffnet"
  data class Server(val code: Int) : DavError
  data class Network(val detail: String) : DavError
  data object Insecure : DavError            // http:// abgelehnt, siehe §6
}
```

Verpflichtend, weil es sonst beim ersten Sonderfall auseinanderfällt:

* **Der `HttpBackend`-Vertrag gilt weiter:** Ein Antwortcode außerhalb 2xx
  darf **keine** Ausnahme werfen, sondern muss als Wert zurückkommen. Das ist
  bei `TogglClient` genauso und wird dort getestet.
* **`ETag` unverändert durchreichen**, inklusive Anführungszeichen und einem
  eventuellen `W/`-Präfix. Nicht parsen, nicht normalisieren, nicht
  vergleichen außer auf Gleichheit. Ein ETag ist für uns ein undurchsichtiger
  String.
* **Fehlt der ETag in der PUT-Antwort** (S4 ist nur ein SOLL), direkt danach
  ein `HEAD` schicken und ihn von dort nehmen. Schlägt auch das fehl, den
  gespeicherten ETag **verwerfen** statt raten — beim nächsten Speichern
  erzwingt das eine Rückfrage, und das ist die sichere Richtung.

### `app/…/data/RemoteStore.kt`

Das Gegenstück zu `ProjectStore` für Server-Projekte: öffnen, speichern,
Kopie speichern. Gleiche `FileResult`-Form, damit das ViewModel beide
Herkünfte gleich behandelt.

### Einstellungen

Server-URL, Benutzername, Passwort, und eine Schaltfläche „Verbindung prüfen",
die ein `OPTIONS` schickt und meldet, ob `DAV: 1,2` zurückkommt (A1 im
Server-Dokument). Das ist die Prüfung, die dem Nutzer sagt, ob Sperren
überhaupt zur Verfügung stehen — und damit, ob er geschützt ist oder nur
gewarnt wird.

## 4. Abläufe

### Öffnen

1. `GET <basis>/<projekt>.gan`
2. `ETag` merken, Bytes durch `GanttDocument.load` schicken
3. Kein ETag geliefert → als „unbekannt" behandeln, nicht als „egal"

### Speichern

1. `PUT` mit `If-Match: <gemerkter ETag>`
2. `204`/`200` → neuen ETag übernehmen (oder per `HEAD` holen, siehe oben)
3. `412` → **Konfliktdialog**, exakt der vorhandene: Kopie speichern oder
   trotzdem überschreiben. „Trotzdem" schickt dasselbe `PUT` **ohne**
   `If-Match`.
4. `423` → **kein** Konfliktdialog, sondern der Hinweis „wird gerade am PC
   bearbeitet". Das ist ein anderer Fall und braucht eine andere Meldung:
   hier geht nichts verloren, man muss nur warten.

### Offline

`GET` scheitert mit `Network` → Meldung, kein Öffnen. **Bewusst kein
Offline-Zwischenspeicher in der ersten Fassung.** Ein Zwischenspeicher, den
man bearbeiten kann, ist wieder eine zweite Fassung ohne Schiedsrichter —
also genau das Problem, das der Server abschafft. Wer offline arbeiten will,
nimmt eine lokale Datei.

## 5. Was sich an vorhandenem Code ändert

* `OpenProject` bekommt eine Herkunft (lokal / Server) und `lastKnownETag`.
  `lastKnownFingerprint` bleibt für lokale Dateien zuständig.
* `ProjectViewModel.save` verzweigt nach Herkunft. Der Rest — Konfliktdialog,
  automatisches Speichern beim Verlassen, Rückgängig — bleibt unangetastet.
* Das **Widget** liest weiterhin nur lokale Dateien. Ein Widget, das bei jedem
  Zeichnen eine HTTP-Anfrage stellt, wäre genau die Art Hintergrundverkehr,
  die man auf einem fremden Server nicht macht und auf dem eigenen auch nicht
  braucht. Bei Server-Projekten zeigt das Widget einen Hinweis statt einer
  Liste.

## 6. Sicherheitsvorgaben

Nicht verhandelbar, weil bei Basic Auth die Zugangsdaten in **jedem** Request
mitgehen:

* **Nur `https://`.** Eine `http://`-URL wird in den Einstellungen abgelehnt
  (`DavError.Insecure`), nicht bloß gewarnt.
* **Zertifikatsprüfung nicht abschaltbar.** Kein „Zertifikat akzeptieren"-Knopf,
  kein `TrustManager`, der alles durchwinkt — auch nicht hinter einer
  Entwicklereinstellung. Solche Schalter überleben bis in die Auslieferung.
* **Passwort nur im `SecureStore`**, nie in `app_prefs`, nie in einem Log, nie
  in einer Fehlermeldung. Die Backup-Ausschlüsse in `res/xml/backup_rules.xml`
  und `res/xml/data_extraction_rules.xml` decken `secure_prefs` bereits ab —
  beim Hinzufügen prüfen, dass das so bleibt.
* **Kein Passwort in der Projektdatei.** Gleiche Regel wie beim Toggl-Token.
  Die Datei wird geteilt.
* Beim Protokollieren von Fehlern die URL kürzen: Ein Pfad kann Kundennamen
  enthalten.

## 7. Der Bearbeitungsschutz stellt sich selbst um

`EditPolicy.kt` wurde genau dafür gebaut. `warnBeforeWidening` ruft:

```kotlin
needsUnmanagedStorageWarning(from, target, SyncGuarantee.UNMANAGED_FILE)
```

Sobald ein Projekt vom Server kommt **und** der Server Sperren meldet, wird
daraus `SyncGuarantee.MANAGED_SERVER` — und die Warnung beim Einschalten der
Bearbeitung **verstummt von selbst**, ohne dass ein Text umgeschrieben werden
muss. Genau eine Zeile im ViewModel.

Wichtig: `MANAGED_SERVER` nur setzen, wenn der Server **tatsächlich** `DAV: 2`
gemeldet hat. Ein Server ohne Sperren ist bei Konflikten nicht besser als eine
Datei im Ordner — dort muss die Warnung bleiben. Die Einstufung darf aus der
gemessenen Fähigkeit kommen, nicht aus „ist ja ein Server".

## 8. Testplan

Im Core testbar, also ohne Gerät, mit einem gefälschten `HttpBackend` — wie
`TogglClientTest`:

* ETag wird unverändert weitergereicht, auch mit `W/`-Präfix
* `412` wird zu `ChangedElsewhere`, `423` zu `LockedElsewhere` — **nicht**
  vertauscht und **nicht** beide zu „Konflikt"
* PUT ohne ETag in der Antwort → HEAD wird nachgeschickt
* HEAD scheitert ebenfalls → gemerkter ETag wird **verworfen**, nicht behalten
* `http://`-URL wird abgelehnt, bevor irgendein Request rausgeht
* nicht-2xx wirft nie eine Ausnahme (der `HttpBackend`-Vertrag)
* Passwort taucht in keiner Fehlermeldung und keinem `toString()` auf

**Gegentests** — jede dieser Prüfungen muss einmal absichtlich zum Scheitern
gebracht werden, sonst ist sie nichts wert:

* `If-Match` weglassen → der Konflikttest muss rot werden
* `412` und `423` vertauschen → beide Zuordnungstests müssen rot werden
* ETag-Anführungszeichen abschneiden → der Durchreichetest muss rot werden
* die `http://`-Prüfung entfernen → der Sicherheitstest muss rot werden

Am Gerät bleibt zu prüfen, was kein Test abdeckt: dass ein am PC geöffnetes
Projekt vom Telefon aus wirklich `423` liefert, und dass ein Projekt nach
Telefon → PC → Telefon byteweise unverändert ist außer an den geänderten
Stellen.

### T4 sind zwei Läufe, nicht einer

Die Desktop-Sitzung hat am 15.08.2026 gemessen, dass dieser Apache die
Vorbedingung **vor** der Sperre auswertet:

| Lage | Antwort |
|---|---|
| gehalten, `If-Match` aktuell | `423` |
| gehalten, `If-Match` veraltet | `412` |

Damit hängt das Ergebnis von T4 nicht daran, ob gesperrt ist, sondern daran,
ob das Telefon aktuell ist. Ein Lauf, in dem die Gegenseite **sperrt und die
Datei ändert**, prüft deshalb nur den Konfliktweg — den Weg, den `412` schon
vor D1 nahm. Der neue Weg bleibt dabei unberührt.

* **T4a — Sperrweg (der neue).** Gegenseite sperrt und ändert **nichts**.
  Telefon ist aktuell. Erwartung: `423` → „wird gerade am PC bearbeitet",
  kein Konfliktdialog.
* **T4b — Konfliktweg (die Gegenprobe).** Gegenseite sperrt **und** ändert.
  Erwartung: `412` → Konfliktdialog.

Beide zusammen zeigen, dass die App die Fälle auseinanderhält. Nur T4b zu
fahren belegt `DESKTOP_HONOURS_LOCKS` nicht: der Weg, den die Zusicherung
betrifft, wäre nie gegangen worden.

## 9. Was bewusst nicht gebaut wird

* **Kein Hintergrund-Sync**, kein periodisches Abfragen. Requests nur auf
  Nutzeraktion. Das ist auf dem eigenen Server eine Frage der Vernunft und
  wäre auf einem fremden eine Frage der Nutzungsbedingungen.
* **Keine Sperre vom Telefon aus.** Die Richtung, die zählt, ist: der PC
  sperrt, das Telefon erfährt es. Umgekehrt hieße es, dass ein vergessenes
  Telefon in der Hosentasche den PC aussperrt.
* **Kein automatisches Zusammenführen.** Zwei Fassungen eines Plans vereinigen
  heißt Termine erfinden, die niemand geprüft hat.
* **Keine Nutzerverwaltung in der App.** Ein Server, ein Konto, fertig.

## 10. Reihenfolge

1. `WebDavClient` im Core samt Tests und Gegentests — geht vollständig ohne
   Server, gegen ein gefälschtes `HttpBackend`.
2. Einstellungen und „Verbindung prüfen".
3. `RemoteStore` und die Verzweigung im ViewModel.
4. `SyncGuarantee`-Umstellung (§7).
5. Prüfung am Gerät gegen den echten Server, zusammen mit A7 aus dem
   Server-Dokument.

Schritt 1 kann sofort beginnen und hängt an keiner Antwort der Server-Sitzung.
Ab Schritt 2 brauche ich die Antworten aus `HANDOVER-Sync-Server.md` §7 —
vor allem, ob es Nextcloud wird oder ein schlankes `mod_dav`, weil das den
Basispfad und die Art des Geräte-Passworts bestimmt.


---

## 11. Stand der Umsetzung

**Fertig und geprüft (ohne Server testbar):**

* `gantt-core/…/WebDavClient.kt` — `OPTIONS`, `GET`, `PUT` mit `If-Match`,
  `PUT` mit `If-None-Match: *`, `PROPFIND`. Erfüllt S1–S4, S7, S9.
* `WebDavClientTest` — 27 Tests gegen ein gefälschtes `HttpExchange`.
  **Gegentest: 9 von 9 Sabotagen gefangen** (If-Match weggelassen, 412/423
  vertauscht, ETag-Anführungszeichen abgeschnitten, https-Prüfung entfernt,
  DAV-Klasse per Teilstring erkannt, alten ETag statt `null` behalten,
  HEAD-Nachfrage übersprungen, Bytes durch einen String gedreht).
* `AndroidHttpBackend` setzt jetzt zusätzlich `HttpExchange` um, mit binärem
  Rumpf, Antwort-Kopfzeilen und einem Umweg um die Methodenliste von
  `HttpURLConnection` — ohne den ginge `PROPFIND` nicht.
* Einstellungen: Adresse, Benutzername, Passwort und **Verbindung prüfen**.
  Adresse und Benutzername in `app_prefs`, das Passwort im `SecureStore`.
* Die geprüfte Sperrfähigkeit wird gespeichert und bei jeder Änderung der
  Einstellungen wieder verworfen.

**Noch nicht gebaut:** `RemoteStore`, das Öffnen und Speichern vom Server, die
`SyncGuarantee`-Umstellung (§7). Bewusst so: Diese Teile lassen sich erst
gegen einen echten Server prüfen, und ungeprüfter Code an genau der Stelle,
die Datenverlust verhindern soll, wäre das Gegenteil des Ziels.

---

## 12. Was am Desktop-Fork zu ändern ist

Ausgelagert nach **`HANDOVER-Desktop-Sperren.md`** — eigene Aufgabe, eigenes
Modul, eigene Abnahme. Kurz, damit hier keine Lücke bleibt:

* **D1** Die Sperre ist standardmäßig aus (`webdav.lockTimeout` = `-1`), und
  `acquireLock()` meldet trotzdem Erfolg. Der Desktop sperrt also nie, ohne
  dass es irgendwo auffällt. Eine Einstellung behebt es sofort.
* **D2** „Ohne Sperre öffnen" ist ein eigener Knopf, der nicht sagt, was er
  abschaltet.
* **D3** Der Desktop sendet kein `If-Match` und überschreibt ohne Sperre
  bedingungslos. Bis das behoben ist, ist der Schutz einseitig: Das Telefon
  kann nichts vom PC überschreiben, der PC vom Telefon schon.
