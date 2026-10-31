# Kanalparameter und Nachweise

`de.drremote.dsp408.parameters` enthält die gemeinsamen Definitionen für Gain,
Mute und Delay auf InA–InD und Out1–Out8. Snapshot-Auswertung, FIR-Detailbericht
und passiver Schreibdecoder verwenden dieses Modell. Es hat keine Verbindung
zum Gerät und keine Abhängigkeit von CLI, Jackson oder OSGi. Der Controller
verwendet vorerst seine bestehenden Parameterklassen.

| Parameter | Wertebereich | Rohwert | Kodierung | Schreibbefehl |
| --- | --- | --- | --- | --- |
| Gain | −60 bis +12 dB | 0 bis 400 | u16 little endian, stückweise Skalierung | 0x34 |
| Mute | AUS / AN | 0 / 1 beim Schreiben | ein Bit in einem gelesenen Maskenbyte | 0x35 |
| Delay | 0 bis 680 ms | 0 bis 65280 | u16 little endian, 96 Schritte/ms | 0x38 |

Gain wird bis Rohwert 80 als `raw / 2 − 60`, darüber als `raw / 10 − 28`
berechnet. Schreibwerte werden auf den nächsten darstellbaren Rohwert gerundet.
Ungültige Werte, NaN und Unendlich werden abgewiesen. `writePayload` erzeugt
ausschließlich Bytes; die Methode sendet nichts.

## Bestätigung und Firmware

Lesepositionen gelten ausschließlich für `DSP408 V0104` und `FIR408 V0113P`.
Andere Firmware erhält keine übernommenen, als bestätigt dargestellten Werte.
Der ausführliche ältere FIR-Speicherdecoder gilt ebenfalls nur für V0113P.

| Status | Bedeutung |
| --- | --- |
| CONFIRMED | Zuordnung durch die erhaltenen Protokollnachweise belegt |
| INFERRED | Unbestätigte Zuordnung; Rohwertkandidat sichtbar, fachlicher Wert unbekannt |
| UNKNOWN | Leseposition oder benötigte Blockdaten fehlen |
| INVALID | Zuordnung bekannt, Rohwert außerhalb des belegten Bereichs |

Ein bestätigter Nachweis stammt aus den vorhandenen Quellen. Er bedeutet keine
erneute Messung oder einen Abgleich mit der Windows-Oberfläche in dieser Sitzung.
Schreibformat und Leseposition haben getrennte Nachweise: Ein bekannter
Schreibbefehl bestätigt keine Position im gelesenen Speicher.

Für FIR408 sind Gain und Delay auf allen zwölf Kanälen bestätigt. Bei Mute
sind ausschließlich InD (Block 1C, Datenoffset 32, Bit 08) und Out8 (Block 1C,
Datenoffset 34, Bit 80) durch explizite FIR-Beobachtungen belegt. Die übrigen
zehn Mute-Bits bleiben unbestätigt. Bei DSP408 sind Delay InB/InC und Mute
Out5–Out8 unbestätigt. Alle Positionen verwenden Datenoffsets **ohne** den
fünf Byte langen Antwortheader.

## Quellen

Die Originaldateien bleiben unverändert. `ParameterProfiles` verweist pro
Definition auf einen JSON-Pointer relativ zum DSP408-Arbeitsbereich.
Ein Regressionstest gleicht sämtliche 72 Positionen gegen einen unveränderten
Auszug dieser Dateien ab. Der [eigenständige Testnachweis](../src/test/resources/parameters/channel-locations.json)
enthält die ursprünglichen JSON-Pointer, ausgewählten Quellknoten und SHA-256
der vollständigen Quellen. Die Integrität des Auszugs wird ebenfalls per Hash
geprüft. Dadurch funktioniert der Test auch in einem allein ausgecheckten,
beliebig benannten Raider-Repository ohne benachbartes Controller-Projekt.

- [DSP408-Bibliothek](../DspLib-408.json): DSP408 V0104, Gain/Delay/Mute.
- [Raider-FIR-Bibliothek](../DspLib-408-fir.json): Gain-Allkanalnachweis vom
  16.07.2026 sowie Delay-Allkanal-Readback mit 0/680 ms und zusätzlichem
  20-ms-Nachweis beider Bytes. Die älteren Mute-Positionen dieser Datei werden
  nicht verwendet.
- [Controller-FIR-Bibliothek](../../DSP408Controller/dsp408-parent/docs/protocol-spec/DspLib-408-fir.json):
  neuere Mute-Positionen und ausdrückliche InD-/Out8-Beobachtungen vom 06.08.2026.
  Übernommene DSP-Beobachtungen gelten nicht als FIR-Bestätigung.
- [Realer Referenzdump](../src/test/resources/live/fir408-v0113p-20261031.json)
  mit [Herkunftsnachweis](../src/test/resources/live/fir408-v0113p-20261031.provenance.json):
  32 Blöcke und 1556 Datenbytes vom 31.10.2026; alle zwölf Gain-Werte sind als
  feste Erwartungswerte getestet. Änderungen in den Diff-Tests sind synthetisch.

SHA-256 der drei Bibliotheken bei Übernahme:

```text
d76a78486dd4a726c753e2c2fe99356d4cec95f1e9d83e283d1cc21054979a68  DspLib-408.json
d84e6696908a4f479a8851252b95289c3f19e206c097c2da139ba6b12e9585be  DspLib-408-fir.json (Raider)
86d92ea1a8c15fbd434a447ba16dc2ee556bbbaacf860f44712300e8331c584e  DspLib-408-fir.json (Controller)
```

## Offline-Auswertung

```bash
java -jar target/dsp-raider.jar decode --in snapshot.json --out decoded.md
java -jar target/dsp-raider.jar diff --before snapshot.json --after later.json
```

`decode` zeigt Wert, Status, Rohwert, Leseposition, Schreibbefehl und Lesenachweis
für alle 36 Kanalparameter. Bei Mute bezeichnet der Rohwert das ganze
Maskenbyte. Die alte FIR-Detailtabelle bleibt erhalten und verwendet dieselben
Definitionen für Gain, Mute und Delay.

`diff` zeigt zunächst fachliche Änderungen, beispielsweise
`Out2 Gain: -6.0 dB -> -3.0 dB`. Danach folgen nicht zugeordnete Änderungen
mit Bitmaske und schließlich alle Rohänderungen einschließlich hinzugefügter,
entfernter oder verkürzter Blöcke. Die Bitmaske verhindert, dass ein bekanntes
Mute-Bit andere Änderungen im selben Byte verdeckt. „Nicht zugeordnet“ bezieht
sich auf die drei Parameterfamilien dieses Modells; weitere historische Decoder
können zusätzliche Felder kennen. Unbekannte oder unterschiedliche
Firmwareprofile erhalten keinen semantischen Vergleich.

Passive Capture-Auswertung erfordert weiterhin eine explizite `--lib`-Datei.
Die drei Schreibbefehle prüfen Kanal, Länge und Rohwertbereich und melden
ungültige Werte als `[UNKNOWN]`, ohne daraus einen plausiblen Wert zu erzeugen.
Weitere Parameterfamilien und aktive Schreibabläufe gehören nicht zu dieser
Umstellung.
