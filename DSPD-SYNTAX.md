# DSP408 Script Syntax

This file describes the current `.dspd` DSL used in `tracks-dsp-raider`.

The language is designed for:

- proxy access
- GUI capture
- decode workflows
- byte inspection
- diffing and write-series analysis

---

## Syntax Styles

The DSL supports both:

- modern camelCase, for example `readBlock()`, `guiActionCapture()`
- legacy kebab-case, for example `read-block()`, `gui-action-capture()`

Both styles can be mixed.

```dspd
connect();
ensureSession();
handshake();

let resp = readBlock(0x04);
print(resp.payloadHex);
```

```dspd
connect
ensure-session
handshake

let resp = read-block 0x04
print $resp.payloadHex
```

---

## Basics

- statements are separated by newline or `;`
- strings use `"`
- comments start with `#` or `//`
- blocks use `{ ... }` or legacy `end`
- variables use `let`
- the last evaluated value is stored in `_`

Examples:

```txt
let x = 123;
print(x);

if (x == 123) {
    print("ok");
}
```

```txt
let x = 123
print $x

if $x == 123
    print "ok"
end
```

---

## Core Commands

### Connection

- `connect`
- `disconnect`
- `status`
- `attach-session`
- `reset-session`
- `ensure-session`
- `clear-frames`
- `handshake`
- `handshake-init`
- `device-info`
- `system-info`
- `login`
- `read-block`
- `read-blocks`
- `read-config-blocks`
- `read-save-config`

Examples:

```txt
connect()
attachSession()
handshake()
```

```txt
let b = readBlock(0x00);
print(b.payloadHex);
```

```txt
let blocks = readConfigBlocks(0x00, 0x1F);
print(blocks.count);
print(blocks.dataLen);
```

For a full DSP408/FIR408 config dump plus files:

```txt
let blocks = readSaveConfig("out/fir/current", 0x00, 0x1F);
print(blocks);
```

### Write

- `write(...)`
- `send-payload ...`
- `tx ...`

Examples:

```txt
write("00 01 03 35 04 01")
write("00 01 02 27 00", 0x24)
```

### Save

- `save-text / saveText`
- `save-diff-report / saveDiffReport`
- `save-capture-read-blocks / saveCaptureReadBlocks`
- `save-read-blocks / saveReadBlocks`

Examples:

```txt
saveText("out/result.txt", "Hello World");
saveDiffReport("out/diff.txt", before, after);
saveCaptureReadBlocks(cap, "out/capture-blocks");
saveReadBlocks(blocks, "out/config-blocks");
```

---

## GUI Capture

### `gui-connect / guiConnect`

Connects the GUI sniffer to the proxy stream.

```txt
guiConnect()
guiConnect("127.0.0.1", 19081)
```

### `gui-disconnect / guiDisconnect`

```txt
guiDisconnect()
```

### `gui-capture / guiCapture`

Time-window based capture.
The engine arms capture, waits for the configured action window, then records until the quiet phase.

```txt
let cap = guiCapture("Perform one GUI action now", 15000, 1500, 12000);
```

Parameters:

- optional note text
- optional `actionWindowMs`
- optional `quietMs`
- optional `maxWaitMs`

### `gui-action-capture / guiActionCapture`

Event-based capture for live GUI decoding.

This mode:

- waits for the first real GUI write
- ignores command `0x40` background traffic
- ends automatically when the action becomes quiet

This is the preferred mode for:

- toggles
- button actions
- fader moves
- permanent GUI keepalive traffic

```txt
let cap = guiActionCapture("Move one fader now", 45000, 1200, 6000);
print(cap);
print(lastWriteExcluding(cap, 0x40));
```

Parameters:

- optional note text
- optional max wait until the first real GUI action
- optional quiet time after action
- optional max capture time after action start

### `gui-begin-capture / guiBeginCapture`

```txt
guiBeginCapture();
sleep(5000);
let cap = guiEndCapture(1500, 12000);
```

### `gui-end-capture / guiEndCapture`

```txt
let cap = guiEndCapture();
let cap = guiEndCapture(1500, 12000);
```

---

## Decode Helpers

### Read / frame helpers

- `read-block-index / readBlockIndex`
- `read-block-payload / readBlockPayload`
- `cmd`
- `payload`
- `raw`
- `payload-hex / payloadHex`
- `payload-ascii / payloadAscii`

### Diff helpers

- `diff-bytes / diffBytes`
- `diff-u16le / diffU16le`
- `diff-report / diffReport`
- `changed-offsets / changedOffsets`
- `assembled-data / assembledData`

### FIR408 helpers

- `decode-fir408-config / decodeFir408Config`
- `fir408-safe-pings / fir408SafePings`
- `fir408-cmd56-readonly-sweep / fir408Cmd56ReadonlySweep`
- `fir408-cmd56-readonly-offsets / fir408Cmd56ReadonlyOffsets`
- `fir408-upload-test-fir / fir408UploadTestFir`

Example:

```txt
let before = readSaveConfig("out/fir/before", 0x00, 0x1F);
saveText("out/fir/static-decode.md", decodeFir408Config(before));
saveText("out/fir/safe-pings.md", fir408SafePings(before));
let after = readSaveConfig("out/fir/after", 0x00, 0x1F);
saveDiffReport("out/fir/before-after-diff.md", assembledData(before), assembledData(after));
```

`decodeFir408Config` renders every matrix crosspoint as decoded dB plus its
raw u16 value. `script-example/413-read-current-matrix-gains-readonly.dspd`
provides a read-only live capture for all 32 gains.

`fir408SafePings` sends current-value writes only. It intentionally skips mute,
IIR crossover remembered-state writes, compressor, and limiter fields.

`fir408Cmd56ReadonlySweep(dir)` probes FIR coefficient/readback command `0x56`
for selectors `0x00..0x03` and offsets `0..511` in 13-float windows. It writes
`summary.md`, `results.csv`, and per-response hex files under `dir`.

`fir408Cmd56ReadonlyOffsets(dir, selector, offsets, [attempts])` probes only a
comma/space separated list of offsets, for example
`fir408Cmd56ReadonlyOffsets("out/fir/probe", 0x00, "0,13,38,39,177", 1)`.
This is preferred after a broad sweep because missing offsets are recorded as
`no_response` instead of making the decode run expand into a long scan.

`fir408UploadTestFir(selector, pattern, [name8])` uploads a deterministic
512-tap External FIR test pattern using the observed `0x4F`, `0x4E`, and `0x5B`
sequence. Supported patterns: `first`, `center`, `two-tap`, `small-ramp`,
`strong-ramp`. This changes the selected input FIR slot.

### Capture helpers

- `first-write / firstWrite`
- `last-write / lastWrite`
- `first-response / firstResponse`
- `last-response / lastResponse`
- `writes`
- `responses`
- `capture-count / captureCount`
- `capture-write-count / captureWriteCount`
- `capture-response-count / captureResponseCount`
- `capture-frame / captureFrame`
- `last-write-excluding / lastWriteExcluding`
- `recent-writes / recentWrites`
- `recent-writes-excluding / recentWritesExcluding`

### Write-series helpers

- `writes-by-command / writesByCommand`
- `writes-by-command-and-channel / writesByCommandAndChannel`
- `payload-series / payloadSeries`
- `u16-series / u16Series`
- `changing-offsets-across-writes / changingOffsetsAcrossWrites`

Examples:

```txt
let series = writesByCommandAndChannel(cap, 0x34, 0x00);
print(payloadSeries(series));
print(changingOffsetsAcrossWrites(series));
print(u16Series(series, 5));
```

---

## Generic Helpers

- `len`
- `contains`
- `starts-with / startsWith`
- `ends-with / endsWith`
- `upper`
- `lower`
- `trim`
- `join`
- `at`
- `split`
- `replace`

---

## Byte Functions

- `bytes`
- `hex`
- `slice`
- `ascii`
- `u8`
- `u16le`
- `u32le`

All multibyte values are little endian.

---

## Return Types

### `ProxyStatus`

Properties:

- `sessionActive`
- `injectReady`
- `rawResponse`

### `ProxyResponse`

Properties:

- `raw`
- `payload`
- `checksumOk`
- `command`
- `commandHex`
- `readBlockIndex`
- `rawHex`
- `payloadHex`
- `payloadAscii`
- `rawLen`
- `payloadLen`

### `ReadBlockSet`

Returned by `readBlocks`, `readConfigBlocks`, and `readSaveConfig`.

Properties:

- `responses` / `blocks`
- `count` / `size` / `len`
- `isEmpty`
- `first`
- `last`
- `blockIndices`
- `data`
- `dataHex`
- `dataLen`
- `allBlocksHex`

### `GuiCaptureResult`

Properties:

- `frames`
- `totalFrames`
- `writeCount`
- `responseCount`
- `isEmpty`
- `firstFrame`
- `lastFrame`
- `firstWrite`
- `lastWrite`
- `firstResponse`
- `lastResponse`
- `writes`
- `responses`
- `readBlockResponses`

### `SniffedFrame`

Properties:

- `direction`
- `frame`
- `payload`
- `frameHex`
- `payloadHex`
- `payloadAscii`
- `command`
- `commandHex`
- `readBlockIndex`
- `payloadLen`
- `checksumOk`

### `byte[]`

Property-style access:

- `hex`
- `ascii`
- `len`
- `command`
- `commandHex`
- `readBlockIndex`

---

## Examples

### Single GUI action

```txt
guiConnect();
let cap = guiActionCapture("Toggle one GUI value now", 45000, 1200, 6000);
print(lastWriteExcluding(cap, 0x40));
```

### Fader series

```txt
guiConnect();
let cap = guiActionCapture("Move the InA gain fader now", 45000, 1200, 6000);
let series = writesByCommandAndChannel(cap, 0x34, 0x00);

print(payloadSeries(series));
print(changingOffsetsAcrossWrites(series));
print(u16Series(series, 5));
```

### Diff two payloads

```txt
let before = bytes("00 01 04 34 04 18 01");
let after = bytes("00 01 04 34 04 34 01");

print(diffBytes(before, after));
print(diffU16le(before, after));
print(diffReport(before, after));
```

---

## Quick Reference

- `connect`
- `disconnect`
- `status`
- `attach-session`
- `reset-session`
- `ensure-session`
- `handshake`
- `login`
- `read-block`
- `write`
- `gui-connect`
- `gui-capture`
- `gui-action-capture`
- `gui-begin-capture`
- `gui-end-capture`
- `save-text`
- `save-diff-report`
- `save-capture-read-blocks`
- `save-read-blocks`
- `read-block-payload`
- `read-blocks`
- `read-config-blocks`
- `read-save-config`
- `last-write-excluding`
- `writes-by-command`
- `writes-by-command-and-channel`
- `payload-series`
- `u16-series`
- `changing-offsets-across-writes`
- `diff-report`
- `assembled-data`
- `decode-fir408-config`
- `fir408-safe-pings`
- `fir408-cmd56-readonly-sweep`
- `fir408-upload-test-fir`
