# Übergabe an die Server-Sitzung — Sperrprobe T4

Von der Android-Sitzung, 15.08.2026. Zweig `claude/gantt-android-app-wxxu9o`.

Du wirst hier für eine einzige Sache gebraucht, und sie dauert wenige Minuten:
**eine WebDAV-Sperre halten, damit Natalie vom Telefon aus prüfen kann, was
die App meldet.** Kein Umbau, keine Konfigurationsänderung.

Das ist der letzte offene Punkt vor dem Release der Android-App.

---

## 1. Warum du und nicht der PC

Ursprünglich sollte die Desktop-Sitzung das fahren. Sie hat dabei selbst
festgestellt, dass es den PC gar nicht braucht:

> Gemessen ohne Oberfläche: eine Sperre lässt sich mit `curl -X LOCK` selbst
> setzen, die Frage ist eine Eigenschaft des Servers und nicht von
> GanttProject.

Genau so ist es. Für die Probe zählt, dass **irgendjemand** eine Sperre hält —
wer, ist gleichgültig. Natalie ist gerade nicht am PC, du bist auf der Maschine.

---

## 2. Was geprüft wird, und warum es zwei Läufe sind

Du hast am 15.08. gemessen, dass dieser Apache die Vorbedingung **vor** der
Sperre auswertet:

| Lage | Antwort |
|---|---|
| gesperrt, `If-Match` aktuell | `423` |
| gesperrt, `If-Match` veraltet | `412` |

Daraus folgt der Punkt, auf den es hier ankommt: **Ob `423` oder `412` kommt,
hängt nicht daran, ob gesperrt ist, sondern daran, ob das Telefon aktuell ist.**

Ein Lauf, in dem du sperrst **und** die Datei änderst, macht das Telefon
veraltet. Dann antwortet der Server `412`, und geprüft wäre nur der Konfliktweg
— der Weg, den `412` schon vorher nahm. Der Sperrweg bliebe ungegangen.

* **T4a — der neue Weg.** Sperren, **nichts ändern**. Erwartung: `423`.
  Die App soll „wird gerade am PC bearbeitet" zeigen, **keinen** Konfliktdialog.
* **T4b — die Gegenprobe.** Sperren **und** ändern. Erwartung: `412`,
  Konfliktdialog.
* **T4c — die Auflösung.** Sperre lösen, Natalie speichert erneut. Muss
  durchgehen.

**T4c ist nicht optional.** Ohne sie belegt ein `423` nur, dass irgendetwas
fehlschlug — nicht, dass die Sperre der Grund war. Erst wenn dieselbe Aktion
nach dem Entsperren gelingt, ist die Ursache festgenagelt.

Nur **T4a** kann die Zusicherung in der App tragen
(`ProjectViewModel.DESKTOP_HONOURS_LOCKS`). T4b allein belegt sie nicht.

---

## 3. Die Falle, an der diese Probe still scheitern kann

**Wenn das Sperren selbst den ETag ändert, wird aus T4a unbemerkt T4b.**
Das Telefon wäre dann veraltet, der Server antwortete `412`, und wir hielten
das für ein Ergebnis über den Sperrweg — obwohl der nie betreten wurde.

`mod_dav_fs` legt Sperren in der `DavLockDB` ab und fasst die Datei nicht an,
der ETag sollte also stehen bleiben. **Sollte.** Deshalb wird er gemessen,
vorher und nachher, und die Probe gilt nur, wenn er gleich ist.

---

## 4. Ablauf

Werte aus `/root/SERVER-UMBAU-LOG.md`. **Nichts davon gehört in dieses Repo** —
weder Hostname noch Benutzername noch Passwort, auch nicht in einem Beispiel.

```bash
# Aus dem Log holen, nicht hier eintragen:
HOST=…            # z. B. gantt.example.de
PFAD=…            # Ordner des Projekts
DATEI=t4-probe.gan
CRED=…:…          # Benutzer:Passwort
URL="https://$HOST/$PFAD/$DATEI"
```

**Bitte eine Wegwerfkopie nehmen, kein echtes Projekt.** Falls `$DATEI` noch
nicht existiert, leg sie als Kopie eines vorhandenen Projekts an und sag
Natalie, wie sie heißt — sie muss sie am Telefon öffnen können.

### Schritt 0 — ETag vor der Sperre

```bash
curl -sI -u "$CRED" "$URL" | grep -i '^etag'
```

Notieren. **Erst danach** sagst du Natalie: „öffne `t4-probe.gan` am Telefon".

### Schritt 1 — Sperre setzen

```bash
curl -i -u "$CRED" -X LOCK \
  -H "Timeout: Second-3600" \
  -H "Depth: 0" \
  -H "Content-Type: application/xml" \
  --data '<?xml version="1.0" encoding="utf-8"?>
<D:lockinfo xmlns:D="DAV:">
  <D:lockscope><D:exclusive/></D:lockscope>
  <D:locktype><D:write/></D:locktype>
  <D:owner><D:href>mailto:t4-probe</D:href></D:owner>
</D:lockinfo>' "$URL"
```

Aus der Antwort den **`Lock-Token`** merken (`<opaquelocktoken:…>`). Ohne ihn
bekommst du die Sperre nur noch über den Ablauf der Stunde wieder los.

### Schritt 2 — ETag nach der Sperre, und das ist die Bedingung

```bash
curl -sI -u "$CRED" "$URL" | grep -i '^etag'
```

**Gleich wie in Schritt 0 → weiter mit T4a.**
**Anders → abbrechen und melden.** Dann ist die Probe nicht T4a, und das
Ergebnis wäre wertlos. Lieber kein Ergebnis als ein falsches.

### Schritt 3 — T4a

Natalie sagen: **„fertig, bitte jetzt am Telefon etwas ändern und speichern"**.

Erwartung: **„wird gerade am PC bearbeitet"**, kein Konfliktdialog.
Meldet die App einen Konflikt, stimmt entweder die Messung aus §2 nicht oder
die App ordnet falsch zu — beides will die Android-Sitzung wissen.

### Schritt 4 — T4c, die Auflösung

```bash
curl -i -u "$CRED" -X UNLOCK -H "Lock-Token: <opaquelocktoken:…>" "$URL"
```

Natalie speichert erneut. **Muss durchgehen.**

### Schritt 5 — T4b, die Gegenprobe (optional, wenn noch Zeit ist)

Wieder sperren, dann die Datei **ändern** — ein `PUT` ohne `If-Match` genügt.
Danach speichert Natalie vom (jetzt veralteten) Telefon.

Erwartung: **Konfliktdialog**, nicht „am PC in Bearbeitung".

Danach entsperren und die Datei aufräumen.

---

## 5. Was zurückgemeldet werden soll

Kurz genügt, aber bitte vollständig:

1. ETag vor und nach dem Sperren — gleich oder nicht
2. Was Natalie in Schritt 3 gesehen hat, **wörtlich**
3. Ob Schritt 4 durchging
4. Falls T4b gefahren: was dort kam
5. Ob am Ende noch eine Sperre steht (`PROPFIND` auf `lockdiscovery`)

Punkt 5 nicht vergessen. Eine vergessene Sperre läuft zwar nach einer Stunde
ab, aber bis dahin kann niemand speichern, und die Ursache wäre nicht
naheliegend.

---

## 6. Was du ausdrücklich nicht tun sollst

* **Nichts an der Serverkonfiguration ändern.** Kommt ein unerwartetes
  Ergebnis, ist das das Ergebnis. Eine Konfiguration, die während der Messung
  nachgezogen wird, misst sich selbst.
* **Keine echten Projektdaten anfassen.** Nur die Wegwerfkopie.
* **Keine Zugangsdaten ins Repo**, auch nicht maskiert, auch nicht in einer
  Beispielzeile. Sie stehen im Serverlog, und dort bleiben sie.
