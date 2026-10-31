# Live read reference and common DSP Raider entry point

The user authorized the proposed live read, reference capture, real-data tests
and a common entry point for connect, read, decode, compare and capture.

## Approach

Keep the existing proxy transport and DSPD/legacy CLIs. Add one application
service for non-destructive snapshot reads, strict snapshot-file validation and
offline analysis, and a thin CLI named `dsp-raider.jar` on top. This reuses the
existing protocol/session architecture instead of adding a second DSP client or
merging the proxy into Raider. Separate commands still work for compatibility.

## Behavior

- `connect` checks the proxy and establishes/reuses its device session.
- `identify` reads initialization, device and system information.
- `dump --out PATH` reads configuration blocks: 00..1F for recognized FIR408,
  00..1C for recognized DSP408; unknown versions require an explicit end block.
- `decode --in PATH` validates the complete wire frames and renders known FIR408
  memory using the existing decoder, or a clearly labelled block overview for
  other/unknown devices. Incomplete FIR snapshots are rejected for memory decode.
- `diff --before PATH --after PATH` compares blocks by index, including added,
  removed and length-changed data; it must not silently ignore trailing bytes.
- `capture --out PATH --duration-ms N` passively records stream frames for a
  bounded interval, including an idle period. It never sends setting commands.
- Common endpoint options remain stream/control host/port. `--help` never connects.
  Usage errors return 2, execution errors 1, and success 0.
- New readers may ensure/attach a session but never reset an existing session or
  write parameters, presets, FIR coefficients or login guesses. Every resource is
  closed on success/failure and captures are written without replacing old files.
- Legacy snapshot JSON remains readable. Raw and payload bytes, checksums, command
  metadata and block indices must agree; duplicate blocks and incomplete data are
  reported as errors rather than being interpreted as valid configuration.

## Evidence and verification

Real device: 192.168.0.166:9761 identified as FIR408 V0113P. Existing proxy/reader
successfully read all 32 configuration blocks with no bad checksums. Raw runs
belong in Betriebsdaten and permanent reference material in Archiv/DSP408. Add a
small immutable real-device fixture with provenance under test resources.

Test safe command order/session reuse with a fake session, wire validation and
all offline commands with the fixture, partial/length-changing diffs, CLI exit
codes, bounded idle capture, and actual proxy-to-device reads. Compare two live
snapshots and audit outbound wire commands against 10/13/2C/27 only.

The original Windows editor setup EXE exists on the backup disk, but no Wine
runtime is installed. A direct editor screen/value comparison requires that
software running on Windows or another suitable environment; do not claim it
has been performed from a wire-only capture. Complete the independent work and
record this remaining verification limit accurately.
