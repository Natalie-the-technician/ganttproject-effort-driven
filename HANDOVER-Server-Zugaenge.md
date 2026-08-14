# Zugänge: anlegen, ausliefern, zurücksetzen

**Für die Server-Sitzung.** Nicht für die Desktop-Sitzung — Konten, Passwörter
und Mailversand haben mit GanttProject nichts zu tun. Der Desktop und die App
verbrauchen nur Zugangsdaten; woher sie kommen, entscheidet sich hier.

Stand: 14. August 2026. **Nichts davon ist gebaut.** Vorhanden ist
`gantt-benutzer.sh`, das Konten anlegt und entzieht und das erzeugte Passwort
nach `/root/gantt-zugang-<name>.txt` schreibt.

---

## 0. Der Wunsch, und was davon technisch geht

Gewünscht ist: neue Benutzer anlegen können, die Betroffenen bekommen ihr
Passwort **angezeigt** und können es **per Mail zurücksetzen**.

**Das mittlere Stück geht so nicht.** `htpasswd` speichert einen Hash. Ein
gesetztes Passwort ist danach niemandem mehr zugänglich — auch nicht dem
Server, auch nicht root. Möglich sind nur zwei Dinge:

* das Passwort **einmalig im Moment der Erzeugung** ausliefern, oder
* ein **neues** setzen und dieses ausliefern.

Das ist keine Einschränkung des Skripts, sondern der Grund, warum das
Verfahren sicher ist. Eine Ablage, aus der sich Passwörter wieder auslesen
lassen, wäre genau das, was man nicht will — und die heutige Datei
`/root/gantt-zugang-<name>.txt` ist bereits ein Kompromiss, der nur deshalb
vertretbar ist, weil sie root gehört und nach der Übergabe gelöscht werden
kann.

Alles Weitere hier ist deshalb: **wie kommt ein frisch erzeugtes Passwort zum
richtigen Menschen**, und **wie stößt dieser Mensch selbst eine Neuvergabe an**.

---

## 1. Drei Ausbaustufen

Bewusst gestaffelt. Stufe 2 ist die Empfehlung; Stufe 3 ist das, was wörtlich
gewünscht war, und kostet deutlich mehr.

### Stufe 1 — was heute schon geht

`gantt-benutzer.sh <name>` legt an, schreibt das Passwort in eine
root-Datei. Die Weitergabe passiert von Hand über einen Kanal, den man
ohnehin hat (Signal, Telefon, persönlich). Zurücksetzen heißt: Skript erneut
laufen lassen.

**Reicht für eine Handvoll Leute vollständig aus.** Kein zusätzlicher Dienst,
keine offene Fläche, nichts, was ausfallen kann.

### Stufe 2 — Einmal-Link statt Passwort per Hand *(Empfehlung)*

Das Skript erzeugt zusätzlich einen Link:

```
https://<serveradresse>/zugang/<token>
```

Der zeigt das Passwort **genau einmal** an und macht das Token danach
ungültig. Verfällt außerdem nach 24 Stunden.

Warum das besser ist als das Passwort per Mail:

* Ein Passwort in einer Mail bleibt dort — im Postausgang, im Postfach, in
  jedem Backup beider Seiten, unverschlüsselt auf jedem Zwischenserver.
* Ein verbrauchter Einmal-Link ist wertlos. Wird er abgefangen und **vor**
  dem Empfänger eingelöst, merkt der Empfänger es sofort, weil sein Link
  nicht mehr funktioniert. Ein abgefangenes Passwort merkt niemand.
* Du kannst den Link über denselben Kanal schicken wie bisher, oder ihn
  vorlesen.

Zu bauen ist wenig: ein Verzeichnis mit Token-Dateien
(`/srv/gantt/zugang/<token>` mit Passwort und Verfallszeit), ein winziger
Endpunkt, der die Datei ausliest, anzeigt und **vor** der Anzeige löscht.
Kein Mailversand, kein Formular, keine Eingabe von außen.

**Der Endpunkt darf nicht hinter der Basic-Auth liegen** — wer das Passwort
noch nicht hat, kommt sonst nicht heran. Er braucht also einen eigenen
Caddy-Block ohne Auth. Genau deshalb ist er so klein zu halten wie möglich.

### Stufe 3 — Selbstbedienung per Mail *(das wörtlich Gewünschte)*

Zwei Endpunkte, beide ohne Anmeldung erreichbar:

1. `POST /zugang/vergessen` — Mailadresse rein, Token raus, Mail geht an die
   **hinterlegte** Adresse dieses Kontos
2. `GET /zugang/<token>` — neues Passwort setzen und einmalig anzeigen

Was das zusätzlich braucht, und zwar alles davon:

* **Eine Zuordnung Konto → Mailadresse.** `htpasswd` hat dafür kein Feld;
  es braucht eine eigene Datei. Damit speicherst du **personenbezogene Daten
  Dritter** — ab dem ersten fremden Nutzer ist das DSGVO-relevant.
* **Mailversand, der ankommt.** Ohne SPF, DKIM und DMARC für
  `noctuvo-group.de` landen Rücksetzmails im Spam oder werden abgelehnt.
  Entweder ein Relay eines Anbieters oder die drei DNS-Einträge selbst
  einrichten. Das ist der zeitaufwendigste Teil, nicht der Code.
* **Ratenbegrenzung.** Ein offenes Formular, das Mails auslöst, ist sonst ein
  Werkzeug, um jemanden zuzumüllen — und um herauszufinden, welche Adressen
  Konten haben.
* **Keine Auskunft darüber, ob es das Konto gibt.** Die Antwort muss immer
  gleich lauten („falls ein Konto existiert, ist eine Mail unterwegs"),
  sonst ist der Endpunkt ein Verzeichnis eurer Nutzer.
* **Ein Dienst, der `htpasswd` schreiben darf.** Damit kann dieser Dienst
  Zugang zum WebDAV vergeben. Er ist ab dann die privilegierteste Komponente
  im ganzen Aufbau und gehört entsprechend behandelt: eigener Nutzer, nur
  Schreibrecht auf genau diese Datei, kein Shell-Zugang.

**Die Abwägung, die ihr selbst schon getroffen habt:** Die Server-Sitzung hat
bewusst keine Weboberfläche gebaut, weil das „für eine Handvoll Leute mehr
Angriffsfläche als Nutzen" wäre. Stufe 3 ist genau diese Weboberfläche,
zusätzlich mit Mailversand und einem Dienst, der Zugänge vergeben kann — auf
einem Server, auf dem `ufw` inaktiv ist, kein fail2ban läuft und SSH über
`sslh` auf 443 mithört. Das Urteil von damals wird dadurch nicht falsch.

---

## 2. Empfehlung

**Stufe 2 bauen, Stufe 3 aufheben, bis es mehr als eine Handvoll Leute sind.**

Sie löst das eigentliche Problem — ein Passwort sicher zum richtigen Menschen
bringen — ohne einen Dienst, der Konten vergeben kann, und ohne Mailversand.
Zurücksetzen bleibt ein Anruf und ein Skriptaufruf, was bei fünf Leuten
seltener vorkommt als der Aufwand, es zu automatisieren.

Der Umstieg auf Stufe 3 ist danach klein: Der Einmal-Link aus Stufe 2 ist
bereits die Hälfte davon; es kommen nur die Anforderung per Mail und die
Adressverwaltung dazu.

Wenn ihr Stufe 3 trotzdem sofort wollt, ist das in Ordnung — dann aber bitte
**zuerst fail2ban und eine Ratenbegrenzung**, nicht danach.

---

## 3. Abnahme

Für Stufe 2:

**Z1 — Der Link zeigt das Passwort genau einmal.**
Konto anlegen, Link zweimal aufrufen. Erster Aufruf: Passwort. Zweiter:
Fehlermeldung, kein Passwort. *Gegentest: Löschung vor der Anzeige entfernen —
der zweite Aufruf zeigt dann erneut das Passwort, und die Prüfung muss rot
werden.*

**Z2 — Abgelaufene Token sind wertlos.**
Verfallszeit in der Token-Datei in die Vergangenheit setzen, aufrufen.
Erwartung: Fehlermeldung, und die Datei ist danach weg.

**Z3 — Geratene Token führen nirgendwohin.**
`/zugang/aaaa`, `/zugang/../../etc/passwd`, `/zugang/` ohne Token. Erwartung:
jeweils dieselbe nichtssagende Fehlermeldung, kein Pfad verlässt das
Token-Verzeichnis.

**Z4 — Der Endpunkt öffnet nichts anderes.**
`https://<serveradresse>/` muss weiterhin `401` liefern. *Der
Zugangs-Endpunkt ist die einzige Stelle ohne Anmeldung; wenn dabei die
Basic-Auth für die Projekte fällt, ist das der Totalschaden.*

**Z5 — Das erzeugte Passwort steht nirgends im Log.**
Nach dem Anlegen und dem Abruf: `docker logs`, `journalctl`, Caddy-Log
durchsuchen. Erwartung: kein Treffer. *Ein Passwort in einer Zugriffszeile
überlebt jede Rotation und jedes Backup.*

**Z6 — Der Zugang funktioniert wirklich.**
Mit dem ausgelieferten Passwort ein `OPTIONS` gegen die Sammlung. Erwartung
`DAV: 1,2`. Erst damit ist belegt, dass Anlegen und Ausliefern
zusammenpassen — ein Passwort, das schön angezeigt wird und nicht
funktioniert, ist schlimmer als keins.

Für Stufe 3 zusätzlich:

**Z7 — Die Antwort verrät nicht, ob es das Konto gibt.**
Anfrage mit vorhandener und mit erfundener Adresse. Die Antworten müssen
zeichengleich sein, auch in der Antwortzeit — sonst ist der Unterschied
messbar.

**Z8 — Ratenbegrenzung greift.**
Zwanzig Anfragen hintereinander. Erwartung: Abweisung, und **keine** zwanzig
Mails.

---

## 4. Was ausdrücklich nicht gebaut werden soll

* **Keine Ablage, aus der sich Passwörter wieder auslesen lassen.** Auch nicht
  „nur für den Notfall", auch nicht verschlüsselt mit einem Schlüssel, der auf
  demselben Server liegt.
* **Kein Passwort im Mailtext.** Nur Links.
* **Keine Selbstregistrierung.** Konten legt genau eine Person an.
* **Kein Weiterverwenden der Datei `/root/gantt-zugang-<name>.txt` als
  Dauerablage.** Nach der Übergabe löschen. Sie ist ein Übergabepunkt, kein
  Speicher.
