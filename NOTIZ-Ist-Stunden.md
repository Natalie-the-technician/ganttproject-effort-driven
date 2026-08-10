# Erweiterungsvorschlag: Ist-Stunden erfassen

Status: **VORSCHLAG, noch nicht beauftragt.** Natalie entscheidet, wann und ob dies in Stufe 1
einfließt. Nicht ungefragt umsetzen — diese Datei ist eine Vormerkung, kein Auftrag.

Aufgenommen: 10.08.2026, aus der Chat-Sitzung. Ergänzt `HANDOVER.md` und `CLAUDE-NOTES.md`.

---

## 1. Worum es geht

Der aktuelle Entwurf speichert nur den **geplanten** Aufwand:

| Custom Property | Träger | Inhalt |
|---|---|---|
| `effort_hours` | Aufgabe | geplanter Aufwand in Stunden |
| `hours_per_day` | Ressource | tägliche Verfügbarkeit in Stunden |

Vorschlag: ein drittes Feld für den **tatsächlichen** Aufwand.

| `effort_actual_hours` | Aufgabe | tatsächlich aufgewendete Stunden |

Ebenfalls als Custom Property, aus demselben Grund wie die anderen beiden: Nur so übersteht die
Datei die Runde Fork → Original-GanttProject → Fork. Begründung im Code nachgewiesen, siehe
`CLAUDE-NOTES.md` Abschnitt 9.

**Warum jetzt und nicht später:** Die Formatentscheidung ist gerade getroffen und frisch
belegt. Ein drittes Feld kostet an dieser Stelle fast nichts. Nachträglich hieße es, die
Kompatibilitätsfrage noch einmal aufzumachen und Bestandsdateien zu migrieren.

---

## 2. Wozu das gebraucht wird

Außerhalb dieses Repos existiert eine Planungs-Werkzeugkette (Python-Simulator, Kapazitäts- und
Terminmodell für das Noctuvo-Gesamtprojekt). Sie enthält seit dem 10.08.2026 eine
**Kalibrierung**, die aus abgeschlossenen Vorgängen einen Korrekturfaktor für die
Restschätzungen ableitet.

Diese Kalibrierung hat eine **prinzipielle Schwäche**, die nur mit Ist-Stunden verschwindet:

> Sie vergleicht Ist-Dauer gegen Plan-Dauer, also **Kalenderspannen**. Eine Überschreitung kann
> bedeuten, dass die Aufwandsschätzung zu niedrig war — oder dass weniger Kapazität zur
> Verfügung stand als angenommen. Aus Kalenderdaten allein ist das nicht trennbar.

Mit erfassten Ist-Stunden werden daraus **zwei getrennte Kennzahlen**:

```
Schätzfehler      = Ist-Stunden / geplante Stunden
Kapazitätsfehler  = Ist-Stunden / im Zeitraum verfügbare Stunden
```

Das ist der Unterschied zwischen „es dauerte länger" und „ich weiß, warum".

### Konkreter Nutzen

- **Die Kapazitätsannahme wird messbar.** Das gesamte Terminmodell steht auf „9 Stunden pro
  Woche netto". Diese Zahl ist bis heute eine Annahme; laut Sensitivitätsrechnung bedeuten
  ±25 % rund sieben Monate Unterschied. Nach einem Quartal Erfassung wäre sie belegt.
- **Faktoren je Bereich statt eines globalen.** Softwareschätzungen liegen anders daneben als
  Behördengänge. Bei genügend Stichproben ließe sich das trennen.
- **Effort-driven scheduling wird selbstkorrigierend.** Die geplanten Stunden lassen sich an
  gemessenen Erfahrungswerten ausrichten, statt dauerhaft geraten zu bleiben.

---

## 3. Was zu bedenken ist

**Die Erfassung existiert bereits — teilweise.** Ab 17.08.2026 wird für die
**Forschungszulage** eine Stundenaufzeichnung geführt (das ist Vorgang 103 im Gesamtplan, laut
Bewertung der ertragreichste Vorgang überhaupt). Die Disziplin ist also aus steuerlichen
Gründen ohnehin Pflicht. Offen ist nur, ob diese Stunden auch in die Planung zurückfließen.

**Zwei Vorbehalte:**

1. **Abdeckungslücke.** Die FuE-Aufzeichnung erfasst nur Forschung und Entwicklung — nicht
   Buchhaltung, Vertrieb, Behördengänge. Für diese Vorgänge bliebe es beim gemischten Faktor,
   sofern sie nicht zusätzlich erfasst werden.
2. **Eine führende Quelle.** Wenn GanttProject Stunden hält und die
   Forschungszulagen-Aufzeichnung ebenfalls, muss klar sein, welche maßgeblich ist. Doppelte,
   auseinanderlaufende Buchführung wäre schlechter als gar keine. **Vor der Umsetzung mit
   Natalie klären.**

---

## 4. Umfang (Schätzung Claude)

Klein, wenn es mit Stufe 1 zusammen gemacht wird:

- Konstante in `EffortDrivenProperties` ergänzen, plus `findOrCreate`-Helfer
- Lesefunktion analog zu `Task.effortHours(...)`
- Eingabefeld in `TaskResourcesPanel.kt` neben dem geplanten Aufwand
- Tests analog zu `EffortDrivenModelTest` — **mit Gegentest**

Der Rechenkern (`computeDurationDays`) bleibt unberührt: Ist-Stunden fließen **nicht** in die
Dauerberechnung ein, sie werden nur festgehalten. Sonst würde ein laufender Vorgang seine
eigene Planung verändern.

**Bewusst NICHT vorgeschlagen:** automatische Rückkopplung von Ist auf Plan innerhalb von
GanttProject. Die Auswertung gehört in die Planungskette, nicht in die Oberfläche — dort ist
sie prüfbar und wird nicht ungefragt wirksam.
