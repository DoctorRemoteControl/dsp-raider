# Änderungen offline vorbereiten

`plan` vergleicht gewünschte Gain-, Mute- und Delay-Werte mit einem gespeicherten
Snapshot. Die Vorschau zeigt Ausgangswert, Wunschwert, darstellbaren Zielwert,
Rundung und die vorbereiteten Befehlsbytes. Sie öffnet keine Geräteverbindung
und sendet keine Befehle.

Beispiel im Raider-Projekt nach `mvn verify`:

```bash
java -jar target/dsp-raider.jar plan \
  --in src/test/resources/live/fir408-v0113p-20261031.json \
  --changes examples/change-preview.json
```

Das [Beispiel](../examples/change-preview.json) enthält ausschließlich simulierte
Wünsche gegen den erhaltenen Referenzdump. Es ändert die Referenz nicht:

- Out2 Gain −3,04 dB wird als −3,0 dB vorbereitet.
- Out8 Delay 20,004 ms wird als 20,000 ms vorbereitet.
- InD Mute AN kann mit bestätigtem Ausgangswert vorbereitet werden.
- InA Mute bleibt wegen unbestätigter Leseposition blockiert.
- Out3 Gain −6 dB entspricht bereits dem Referenzwert und erzeugt keinen Befehl.

Die Standardausgabe ist Text. JSON mit vollständiger numerischer Präzision:

```bash
java -jar target/dsp-raider.jar plan \
  --in snapshot.json --changes changes.json --format json --out preview.json
```

`--out` ist optional. Vorhandene Dateien werden nicht überschrieben. Netzwerk-
optionen und `--apply` sind für `plan` nicht verfügbar.

## Eingabedatei

```json
{
  "device_version": "FIR408 V0113P",
  "changes": [
    { "channel": "Out2", "parameter": "gain", "value": -3.04 },
    { "channel": "Out8", "parameter": "delay", "value": 20 },
    { "channel": "InD", "parameter": "mute", "value": true }
  ]
}
```

`device_version` muss mit der aus den Drahtdaten des Snapshots bestimmten
Firmware übereinstimmen. Groß-/Kleinschreibung und äußere Leerzeichen werden
ignoriert. Bestätigte Kataloge gibt es für DSP408 V0104 und FIR408 V0113P.

`changes` enthält bis zu 36 Einträge; ein leeres Array ist zulässig. Kanäle
sind InA–InD und Out1–Out8, Parameter `gain`, `mute`, `delay`. Ihre Schreibweise
ist unabhängig von Groß-/Kleinschreibung. Zahlen verwenden dB für Gain und ms
für Delay. Mute akzeptiert die Zahlen 0/1 oder die JSON-Werte false/true.
Andere Felder und Einheiten sind nicht zulässig. Doppelte JSON-Schlüssel,
mehrere Werte für denselben Kanalparameter, ungültige Werte und unbekannte
Namen werden abgewiesen. Eine ungültige Anfrage erzeugt keine Teilvorschau.

## Status und Rundung

| Status | Bedeutung | Payload |
| --- | --- | --- |
| READY | Bestätigter Ausgangswert, belegtes Schreibformat, abweichender Zielwert | vorbereitet, nicht gesendet |
| UNCHANGED | Kodierter Zielwert entspricht dem Ausgangswert | keiner |
| BLOCKED | Ausgangswert fehlt, ist ungültig/unbestätigt oder Firmware/Schreibformat unbekannt | keiner |

Der Vergleich verwendet die darstellbaren Rohwerte. Ein Wunsch wie −6,01 dB
ergibt bei bereits eingestellten −6,0 dB deshalb UNCHANGED. Bei Mute wird nur
das jeweilige Bit verglichen; andere Bits im gespeicherten Maskenbyte dürfen
keinen falschen Änderungsbedarf auslösen. Gain unterstützt −60..12 dB, Delay
0..680 ms. Über- oder Unterschreitungen werden nicht auf Grenzen geklemmt.

Die Reihenfolge der Anfrage bleibt erhalten. Verfügbare Felder eines partiellen
Snapshots können bewertet werden; angefragte fehlende Felder sind BLOCKED.
Vorhandene Blöcke müssen gültige Drahtdaten und die erwartete Größe haben.
Die [Nachweisregeln des Parametermodells](PARAMETERS.md) gelten unverändert.

## JSON-Ausgabe und Rückgabewerte

JSON hat `schema_version: 1`, `type: "change_plan"`, `preview_only: true`,
`device_version`, `summary` und `entries`. Einträge enthalten unter anderem:

- `before_value` und `before_status`: bestätigter Fachwert oder null mit Status.
- `before_storage_raw`: ursprünglicher Speicherwert; bei Mute das ganze Maskenbyte.
- `requested_value`, `target_value`, `target_write_raw`, `rounded`: Wunsch und tatsächliche Kodierung.
- `read_evidence`, `write_evidence`: getrennte Nachweise, gegebenenfalls null.
- `status`, `reason`, `payload_hex`: Bewertung und bei READY vorbereiteter Payload.

Eine gültige Vorschau liefert Exitcode 0, auch wenn einzelne Einträge BLOCKED
sind. Falsche Optionen oder fachlich ungültige Anfragen liefern 2; Datei-/JSON-/
Snapshot-Lesefehler liefern 1. Bei Fehlern entsteht keine Ausgabedatei.

Die Vorschau bezieht sich auf den gespeicherten Zustand und führt nichts aus.
Ein späterer Ausführungsablauf muss den aktuellen Gerätezustand erneut prüfen
und Änderungen durch Auslesen bestätigen; dieser Ablauf ist hier noch nicht
enthalten.
