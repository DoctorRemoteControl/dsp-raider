# Channel parameters and evidence

[Overview](README.md) · [CLI](CLI.md) · [Change preview](CHANGE-PREVIEW.md)

`de.drremote.dsp408.parameters` contains shared gain, mute and delay definitions
for InA–InD and Out1–Out8. Snapshot analysis, the detailed FIR report, passive
write decoder, checked DSPD parameter functions and explicit application use
this model. It has no device connection or dependency
on CLI, Jackson or OSGi. The controller continues to use its existing parameter
classes for now.

| Parameter | Range | Raw value | Encoding | Write command |
| --- | --- | --- | --- | --- |
| Gain | −60 to +12 dB | 0 to 400 | u16 little endian, piecewise scale | 0x34 |
| Mute | OFF / ON | 0 / 1 when writing | one bit in a read mask byte | 0x35 |
| Delay | 0 to 680 ms | 0 to 65280 | u16 little endian, 96 steps/ms | 0x38 |

Gain is calculated as `raw / 2 − 60` up to raw value 80 and `raw / 10 − 28`
above that. Write values are rounded to the nearest representable raw value.
Invalid values, NaN and infinity are rejected. `writePayload` only creates
bytes; the method sends nothing.

## Confirmation and firmware

Read positions apply exclusively to `DSP408 V0104` and `FIR408 V0113P`. Other
firmware receives no inherited values presented as confirmed. The detailed
older FIR memory decoder also applies only to V0113P.

| Status | Meaning |
| --- | --- |
| CONFIRMED | mapping proven by preserved protocol evidence |
| INFERRED | unconfirmed mapping; candidate raw value visible, parameter value unknown |
| UNKNOWN | read position or required block data missing |
| INVALID | known mapping, raw value outside the proven range |

Confirmed evidence comes from existing sources. It does not mean a fresh
measurement or comparison with the Windows interface in this session. Write
format and read position have separate evidence: a known write command does
not confirm a position in read memory.

For FIR408, gain and delay are confirmed on all twelve channels. For mute,
only InD (block 1C, data offset 32, bit 08) and Out8 (block 1C, data offset 34,
bit 80) have explicit FIR observations. The other ten mute bits remain
unconfirmed. For DSP408, delay InB/InC and mute Out5–Out8 are unconfirmed.
All positions use data offsets **excluding** the five-byte response header.

## Sources

Original files remain unchanged. For each definition, `ParameterProfiles`
references a JSON pointer relative to the DSP408 workspace. A regression test
checks all 72 positions against an unchanged extract from these files. The
[self-contained test evidence](../src/test/resources/parameters/channel-locations.json)
contains the original JSON pointers, selected source nodes and SHA-256 hashes
of the complete sources. The extract's integrity is also checked by hash.
This lets the test work in a standalone Raider checkout with any directory
name, without a neighboring controller project.

- [DSP408 library](../DspLib-408.json): DSP408 V0104, gain/delay/mute.
- [Raider FIR library](../DspLib-408-fir.json): gain evidence for all channels
  from 2026-07-16, plus delay readback for all channels at 0/680 ms and
  additional 20 ms evidence for both bytes. Older mute positions in this file
  are not used.
- [Controller FIR source excerpt](../src/test/resources/parameters/channel-locations.json):
  newer mute positions and explicit InD/Out8 observations from 2026-08-06.
  Inherited DSP observations do not count as FIR confirmation.
- [Real reference dump](../src/test/resources/live/fir408-v0113p-20261031.json)
  with [provenance](../src/test/resources/live/fir408-v0113p-20261031.provenance.json):
  32 blocks and 1556 data bytes from 2026-10-31; all twelve gain values are
  tested as fixed expected values. Changes in diff tests are synthetic.

SHA-256 hashes of the three libraries when imported:

```text
d76a78486dd4a726c753e2c2fe99356d4cec95f1e9d83e283d1cc21054979a68  DspLib-408.json
d84e6696908a4f479a8851252b95289c3f19e206c097c2da139ba6b12e9585be  DspLib-408-fir.json (Raider)
86d92ea1a8c15fbd434a447ba16dc2ee556bbbaacf860f44712300e8331c584e  DspLib-408-fir.json (Controller)
```

## Offline analysis

Run commands from `tracks-dsp-raider` after building. Filenames refer to your
own saved snapshots; supplied offline examples are in the
[workflows](WORKFLOWS.md#1-start-without-hardware).

```bash
java -jar target/dsp-raider.jar decode --in snapshot.json --out decoded.md
java -jar target/dsp-raider.jar diff --before snapshot.json --after later.json
```

`decode` shows value, status, raw value, read position, write command and read
evidence for all 36 channel parameters. For mute, raw value means the entire
mask byte. The older detailed FIR table remains available and uses the same
gain, mute and delay definitions.

`diff` first shows parameter changes, such as
`Out2 Gain: -6.0 dB -> -3.0 dB`. Next come unmapped changes with bit masks,
followed by all raw changes, including added, removed or shortened blocks.
The bit mask prevents a known mute bit from hiding other changes in the same
byte. “Unmapped” refers to the three parameter families in this model; other
historical decoders may know additional fields. Unknown or different firmware
profiles receive no semantic comparison.

Passive capture analysis still requires an explicit `--lib` file. The three
write commands check channel, length and raw range and report invalid values
as `[UNKNOWN]` without turning them into plausible values. The explicit [application workflow](EXECUTION.md) uses these same codecs and
separate read/write evidence; adding execution does not confirm any additional
read positions. Further parameter families require their own evidence.
