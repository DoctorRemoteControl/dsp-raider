# Live FIR408 validation

[Overview](README.md) · [Checked execution](EXECUTION.md) · [Parameter evidence](PARAMETERS.md)

On **2026-11-01, Europe/Berlin**, the packaged checked executor and explicit
application workflow were tested against the connected **FIR408 V0113P** at
`192.168.0.166:9761`. This records one bounded integration test, not confirmation
of other firmware or every parameter family.

## Read-only execution

The initial snapshot contained 32 blocks and 1,556 configuration bytes. A checked
DSPD script established the session, performed the device handshake and read all
32 blocks. It asserted every returned payload against the snapshot and verified
the firmware and assembled data length. A second snapshot had no differences.

The first attempted test script omitted the device handshake and timed out on
`deviceInfo`. The corrected sequence succeeded:

```dspd
connect();
ensureSession();
handshake();
let identity = deviceInfo();
assert(contains(identity.payloadAscii, "FIR408 V0113P"));
let blocks = readConfigBlocks(0, 0x1F);
assert(blocks.count == 32);
assert(blocks.dataLen == 1556);
disconnect();
```

`ensureSession` establishes proxy readiness. Raw DSPD calls retain their explicit
handshake sequence; the common CLI's `dump` performs that sequence itself.

## One parameter change and restoration

The test used the confirmed **Out8 gain** mapping. It lowered the gain by 0.1 dB
and restored the exact starting value. It changed neither mute nor presets.

| Operation | Value | Payload | Result |
| --- | --- | --- | --- |
| Temporary attenuation | −14.0 → −14.1 dB | `00 01 04 34 0B 8B 00` | ACK and readback verified; APPLIED |
| Restore original | −14.1 → −14.0 dB | `00 01 04 34 0B 8C 00` | ACK and readback verified; APPLIED |

Both operations used the public `apply` command with a saved baseline and a
strict change request. The final full configuration matched the initial
configuration byte-for-byte:

```text
parameter_changes=0
unmapped_bytes=0
changed=0 added=0 removed=0
```

The wire log contains exactly two parameter writes, both command `0x34` above.
All other outgoing commands were handshake/identity/system or block reads.
There were no invalid frames. The temporary proxy was stopped afterward.

## Evidence and limits

The test used the packaged implementation from commit `cc23a75`; the repository
documentation revision at test start was `a6d94db`. The original snapshots,
requests, previews, command outputs, corrected/failed test scripts and complete
proxy log are retained in the local archive:

```text
Archiv/DSP408/live-validation-20261101-7tt6xi7m/
```

The archived `wire-audit.json` records the configuration digest, frame counts,
write payloads and artifact hashes. The path is relative to the local `drremote`
workspace; the archive is additional evidence, not a build dependency.

The initial and restored configuration data also match the committed
[reference fixture](../src/test/resources/live/fir408-v0113p-20261031.json).
SHA-256 of the 1,556 assembled configuration bytes, excluding response headers:

```text
b0013872dd13b9d52fc266dddfd742114c549b5ce964bd472dc77fb84adc9cd2
```

This validates checked raw reads and the Out8 gain change/restore path on this
firmware. The Windows Editor comparison, ten inferred FIR mute mappings, other
write families, communication-failure recovery and concurrent external writers
remain separate tests. No firmware updater was run.
