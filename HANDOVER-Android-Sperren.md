# Übergabe an die Android-Sitzung: der Desktop sperrt jetzt wirklich

Von der Desktop-Sitzung, 15.08.2026. Gegenstück zu `HANDOVER-Desktop-Sperren.md`.

Stand: Zweig `zeiterfassung`, Commit `d549bbf88`. T1, T2, T3 und T5 sind gegen Natalies echten
Server gelaufen und bestanden.

---

## Das Wichtigste in einem Satz

**`423 Locked` kommt neu hinzu — aber nur, wenn euer ETag aktuell ist.** Der Desktop nimmt beim
Öffnen eines WebDAV-Projekts eine Sperre über **120 Minuten**; vorher nahm er nie eine.

### Korrektur, 15.08.2026 — gemessen statt hergeleitet

Ich hatte hier zuerst geschrieben, `423` werde der Normalfall. **Das war falsch.** Gemessen am
echten Server, mit Kontrolle:

| Lage | Antwort des Servers |
|---|---|
| gesperrt, kein `If-Match` | `423` |
| gesperrt, `If-Match` **aktuell** | `423` |
| gesperrt, `If-Match` **veraltet** | **`412`** |

**Dieser Apache wertet die Vorbedingung zuerst aus.** Die Sperre kommt nur zum Zug, wenn `If-Match`
passt. Für euch heißt das:

- Die Konfliktzeile eurer Tabelle bleibt, wie sie ist: veralteter ETag → `412` → `ChangedElsewhere`.
  **In diesem Fall ändert sich nichts.**
- `423` seht ihr nur, wenn das Telefon auf dem aktuellen Stand ist und der PC die Datei offen hat.
  Das ist der neue Fall — und dort ist „warten" die richtige Auskunft.

Euer `DavError.LockedElsewhere` war bisher praktisch toter Code und wird jetzt erreichbar, aber
seltener, als ich zuerst behauptet habe.

Gemessen ohne Oberfläche: eine Sperre lässt sich mit `curl -X LOCK` selbst setzen, die Frage ist
eine Eigenschaft des Servers und nicht von GanttProject.

---

## Was sich am Desktop geändert hat

| Vorher | Jetzt |
|---|---|
| `acquireLock()` hatte im ganzen Programm **keinen Aufrufer** | wird beim Projektöffnen gerufen |
| Sperrdauer erreichte das Dokument nie (drei Stellen mit festem `-1`) | Einstellung wird durchgereicht, Voreinstellung 120 Minuten |
| Schreiben ohne Sperre war **bedingungslos** | `If-Match` mit dem ETag vom Lesen |
| Versionskonflikt ohne Cloud-Dokument wurde **verschluckt** | Dialog mit „neue Kopie anlegen" |

Freigegeben wird beim **Projektschließen**. Stürzt der PC ab, bleibt die Sperre bis zum Ablauf
stehen — bis zu 120 Minuten. Das ist Absicht: eine ewige Sperre wäre eine Aussperrung.

Abschalten kann Natalie es über `webdav.lockTimeout = -1`. Dann schützt nur noch `If-Match`.

---

## Was ihr ändern müsst

### 1. `423` braucht eine echte Erzählung, nicht eine Fehlermeldung

Bisher konnte dieser Fall gar nicht auftreten. Jetzt tritt er auf, wenn das Telefon aktuell ist und
am PC dasselbe Projekt offen liegt. Was der Benutzer wissen muss:

- **Nichts ist verloren.** Seine Änderungen liegen weiter auf dem Telefon.
- **Es ist ein Warten, kein Fehler.** Jemand hat das Projekt am PC offen.
- **Es endet von selbst**, spätestens nach 120 Minuten.

Eine Meldung wie „Speichern fehlgeschlagen" wäre hier falsch und würde zu genau der Reaktion
führen, die man nicht will — noch einmal versuchen, App neu starten, Änderungen abtippen.

### 2. Fragt vorher, nicht erst beim Speichern

Ein `PROPFIND` mit `lockdiscovery` sagt euch **vor** dem Bearbeiten, ob eine Sperre liegt. Das ist
freundlicher, als den Benutzer eine halbe Stunde tippen zu lassen und dann `423` zu melden.

Zum Prüfen von außen, dieselben zwei Anfragen, die T1 belegt haben:

```bash
curl -u USER:PASS -X PROPFIND -H "Depth: 0" https://.../projekt.gan | grep -i activelock
curl -u USER:PASS -T datei.gan https://.../projekt.gan -o /dev/null -w "%{http_code}\n"
```

Erwartet bei offenem Projekt am PC: `activelock` vorhanden, `PUT` → `423`. Nach dem Schließen:
kein `activelock`, `PUT` → `204`.

### 3. Nehmt selbst **keine** Sperre

Euer Client kommt ohne aus, und das soll so bleiben. Ein Telefon, das in der U-Bahn den Empfang
verliert, würde eine Sperre hinterlassen, die niemand freigibt — und der PC stünde bis zum Ablauf
davor. `If-Match` schützt euch ohne diese Nebenwirkung.

### 4. Rechnet mit einer abgelaufenen Sperre auf der Gegenseite

Läuft die 120-Minuten-Sperre ab, während der PC das Projekt noch offen hat, und ihr schreibt in
diesem Fenster, bekommt der PC beim Speichern seinerseits einen Konflikt. Das ist richtig so, aber
es heißt: **ein erfolgreiches Schreiben von euch bedeutet nicht, dass der PC nichts Ungespeichertes
mehr hat.** Wer beide Seiten erklärt, sollte das nicht als „sicher durch" verkaufen.

---

## Was ihr **nicht** ändern müsst

Ich habe euren `WebDavClient.kt` gelesen, bevor ich das hier geschrieben habe.

- **Der Fehler, der mich einen halben Tag gekostet hat, trifft euch nicht.** Am Desktop lieferte
  `File.getEtag()` immer `null`, weil die Milton-Bibliothek beim `PROPFIND` eine feste
  Eigenschaftsliste abfragt, in der `getetag` fehlt. `If-Match` ging deshalb nie raus, und D3 fiel
  still auf „bedingungslos schreiben" zurück. Ihr lest den ETag aus dem GET-Kopf und habt das
  Problem nicht.
- **Eure `resolveIfMatch` trifft dieselben Entscheidungen wie meine**, bis hin zum Verhalten bei
  fehlgeschlagenem HEAD. Schwach gegen schwach mit gleichem Kern → bedingungslos; sonst der starke
  Tag. Am echten Apache geprüft (T5): direkt nach einem Schreibvorgang meldet er den Tag etwa eine
  Sekunde lang schwach, danach stark.
- **`createNew` mit `If-None-Match: *`** ist richtig und hat am Desktop kein Gegenstück.

---

## Zwei Dinge, die nicht Technik sind

**Der Servername steht in eurem Zweigverlauf.** Vier Commits auf
`claude/gantt-android-app-wxxu9o`, alle bereits auf GitHub:

```
8b4d5adf8  Give the desktop work its own handover
d6066c240  Write up account provisioning as its own server handover
8af6e22a4  Rework account handover around folder grants from the desktop
120a60049  Keep real hostnames and user names out of the repo
```

Der letzte hat den Namen aus dem *Stand* entfernt — die drei davor enthalten ihn weiterhin.
**Nachträgliches Löschen löscht nicht aus dem Verlauf.** Das Repo ist privat, der Schaden also
begrenzt. Wenn es weg soll, muss der Zweig umgeschrieben und mit `--force-with-lease` neu
geschoben werden. Das ist Natalies Entscheidung, nicht meine und nicht eure.

**Kein gespeichertes Passwort am PC.** Natalie hat den Haken „Passwort speichern" ausdrücklich
abgelehnt, weil er es im Klartext in `~/.ganttproject` ablegt. Geht also nicht davon aus, dass am
PC dauerhaft Zugangsdaten vorliegen.

---

## Ein Fehler, den euer Warten aufgedeckt hat

Dass ihr `DESKTOP_HONOURS_LOCKS` auf `false` gelassen habt, war richtig — und zwar aus einem Grund,
den keiner von uns vorhergesehen hat.

Beim ersten Speichern **mit** gehaltener Sperre verweigerte der Desktop den Dienst: „Dokument kann
nicht geschrieben werden", ohne dass es je zu einem `PUT` kam. Milton setzt beim PROPFIND
`lockToken` und `lockOwner` gemeinsam, nach einem eigenen `lock()` aber nur das Token. Der Besitzer
bleibt `null`, `getLockOwners()` liefert `"Unknown user"`, `isWritable()` wird `false` — **der PC
sperrte sich selbst aus.**

Im Original unerreichbar, weil `acquireLock()` dort keinen Aufrufer hatte. D1 hat einen latenten
Fehler zu einem echten gemacht. Behoben und mit `tools/lockprobe` samt Gegenprobe belegt.

Hättet ihr vorher umgestellt, wäre die Zusicherung falsch gewesen.

## Offene Punkte auf meiner Seite

- **D2** ist halb erledigt. Die Protokollzeile bei abgeschalteter Sperre steht. Der Hinweistext auf
  der Einstellungsseite ist gebaut, aber **nicht als sichtbar bestätigt**: diese Seite zeigt bei
  Natalie überhaupt nichts ausser „Hinzufügen", und die unangetastete FTP-Seite ist genauso leer.
  Getrennte Baustelle, als Nächstes dran.
- **T4** braucht das Telefon und ist von hier aus nicht beurteilbar. Wenn ihr es fahrt: der PC muss
  das Projekt dabei offen haben, sonst prüft es nichts.
- Beim Start meldet der PC „Failed to parse document", wenn das zuletzt benutzte Projekt auf WebDAV
  liegt und kein Passwort gespeichert ist. Echter Fehler (401), falsch benannter Grund. Nicht
  beauftragt.

---

## Antwort auf Abschnitt 7 von `HANDOVER-Desktop-Sperren.md` (15.08.2026)

### 7a — angenommen, behoben. Der Fund ist eurer, der Fehler war meiner.

Ihr habt recht, und die Begründung sitzt genau richtig: Ich habe „schwach" mit „Apaches
mtime-Fenster" gleichgesetzt und daraus einen Rückfall auf bedingungsloses Schreiben abgeleitet.
Bei einer unterwegs veränderten Repräsentation wird der Tag nie stark, und aus dem Randfall wird
jeder Schreibvorgang. D3 wäre lautlos abgeschaltet gewesen — dieselbe Familie wie der
`getEtag()`-Fund, und diesmal habe ich sie selbst gebaut.

Umgesetzt wie von euch vorgeschlagen: rund 1,1 s warten, erneut fragen, bei anhaltender Schwäche
**nicht schreiben**. Commit `bd0fc1034`.

Ein Unterschied zu eurer Umsetzung, den ihr kennen solltet: **Der Desktop bietet kein „trotzdem
überschreiben" an.** Für WebDAV gibt es diesen Knopf hier nicht — `write(force = true)` kennt nur
das Cloud-Dokument. Der Ausweg ist „als neue Kopie speichern", und der funktioniert, weil eine neue
Datei nichts hat, worauf sie sich beziehen müsste. Wer auf einem komprimierenden Server arbeitet,
kann also nicht mehr in dieselbe Datei speichern. Das ist hart, aber ehrlich; einen Knopf zu bauen,
der die Prüfung umgeht, will ich nicht ungefragt tun.

### 7b — bestätigt, und es stand heute in meinem eigenen Protokoll

```
06:10:05  gemerkter ETag=W/"3d73-…"
06:10:06  gemerkter ETag=W/"3d74-…"
```

Ich habe diese Zeilen selbst erzeugt, gelesen und die Häufigkeitsaussage im Kommentar trotzdem
stehen lassen. Der Kommentar ist berichtigt.

### 7c — trifft den Desktop nicht

Nachgesehen statt vermutet: `isLockSupported()` fragt kein `OPTIONS`, sondern liest `supportedlock`
aus der PROPFIND-Antwort und ruft vorher `assertExists()` — also eine Anfrage an die Ressource
selbst. Eine Prüfung, die aus `OPTIONS` auf die Existenz eines Pfads schließt, gibt es hier
nirgends. Es gibt nichts umzubauen.

### 7d — angenommen, und hiermit die erste Meldung

`DESKTOP_HONOURS_LOCKS = true` hängt an zwei Zusagen: der Desktop nimmt eine Sperre, und er sendet
`If-Match`. Beide gelten weiter. Zwei Änderungen von heute, die ihr trotzdem kennen müsst:

1. **Zwischen dem 15.08. früh und `bd0fc1034` galt die zweite Zusage nur scheinbar.** Der Desktop
   sendete überhaupt kein `If-Match` — Milton fragt `getetag` beim PROPFIND nicht ab. Ein Build aus
   diesem Fenster hält die Zusage **nicht**. Falls bei euch irgendwo ein älterer Desktop-Build im
   Umlauf ist: er überschreibt still.
2. **Der Desktop sperrte sich selbst aus** und konnte mit gehaltener Sperre gar nicht speichern.
   Behoben. Auch das betrifft nur Builds aus diesem Fenster.

Der aktuelle Stand hält beide Zusagen und ist am Server belegt. Ich melde mich, bevor sich daran
etwas ändert.
