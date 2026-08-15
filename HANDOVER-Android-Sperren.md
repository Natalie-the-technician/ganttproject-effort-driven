# Übergabe an die Android-Sitzung: der Desktop sperrt jetzt wirklich

Von der Desktop-Sitzung, 15.08.2026. Gegenstück zu `HANDOVER-Desktop-Sperren.md`.

Stand: Zweig `zeiterfassung`, Commit `d549bbf88`. T1, T2, T3 und T5 sind gegen Natalies echten
Server gelaufen und bestanden.

---

## Das Wichtigste in einem Satz

**`423 Locked` ist ab jetzt der Normalfall, nicht der Sonderfall.** Der Desktop nimmt beim Öffnen
eines WebDAV-Projekts eine Sperre über **120 Minuten** — vorher nahm er nie eine.

Euer `DavError.LockedElsewhere` war damit bisher praktisch toter Code. Er wird jetzt der häufigste
Fehlschlag, den ein Benutzer zu sehen bekommt: immer dann, wenn am PC dasselbe Projekt offen ist.

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

Bisher konnte dieser Fall kaum auftreten. Jetzt tritt er bei jeder normalen Arbeitssitzung am PC
auf. Was der Benutzer wissen muss:

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

## Offene Punkte auf meiner Seite

- **D2** (Beschriftung „ohne Sperre öffnen") noch nicht umgesetzt.
- **T4** braucht das Telefon und ist von hier aus nicht beurteilbar. Wenn ihr es fahrt: der PC muss
  das Projekt dabei offen haben, sonst prüft es nichts.
- Beim Start meldet der PC „Failed to parse document", wenn das zuletzt benutzte Projekt auf WebDAV
  liegt und kein Passwort gespeichert ist. Echter Fehler (401), falsch benannter Grund. Nicht
  beauftragt.
