# tracks-dsp-raider

Java toolkit for accessing and reverse-engineering a DSP408 via a local proxy stack.

The project combines read, write, scripting, and GUI-capture tooling so that the DSP protocol can be analyzed in a structured way.  
A key use case is **continuous AI-assisted decoding**: while the original GUI stays connected through the proxy, Codex can repeatedly observe GUI writes, read back DSP blocks, compare dumps, and refine protocol hypotheses in an ongoing decode loop.

The project bundles multiple tools:

- **Reader CLI**
  - reads handshake, device/system info, and all known DSP blocks
- **Writer CLI**
  - sends payloads in a controlled way through the proxy to the DSP
- **Raw Write Tool**
  - sends raw payloads or full frames directly to a target device
- **Script CLI**
  - small DSL to automate read, write, dump, and GUI-capture flows
- **GUI Capture**
  - records which frames the original GUI sends via the proxy stream

---

## Why this project exists

The DSP408 protocol is not encoded symmetrically between GUI writes and block reads.

That means:

- a GUI action usually produces a **write command**
- the resulting value is often stored somewhere else in the DSP **read blocks**
- the read representation is often **not identical** to the write representation
- therefore protocol decoding requires **write observation + readback comparison**

This toolkit is designed exactly for that workflow.

---

## Continuous AI Decode Loop in Codex

One of the most useful workflows is to keep the **original DSP GUI connected through the proxy** and let an AI agent such as **Codex in Codex IDE** continuously drive the decode process.

### Core idea

The AI does not need a full protocol map up front.  
It can iteratively discover fields by repeating this loop:

1. observe which write frame the GUI sends
2. perform or confirm a controlled GUI change
3. read the DSP blocks again
4. compare old vs new block dumps
5. identify which bytes changed
6. correlate:
   - GUI action
   - write command
   - changed read-block offsets
7. store the new hypothesis in documentation or a field library
8. repeat

### Why this works well

Because the proxy exposes both sides:

- **stream side**
  - what the GUI writes / what the DSP answers
- **control side**
  - controlled scripted access for readback and testing

This allows a continuous reverse-engineering loop where the AI can move forward step by step instead of requiring the full protocol in advance.

### Typical AI-assisted decode cycle

A practical cycle looks like this:

1. original GUI is connected through the proxy
2. AI arms GUI capture
3. user changes exactly one setting in the GUI
4. AI captures the last interesting write frame
5. AI performs a new full dump or selected block reads
6. AI diffs the new dump against the previous dump
7. AI searches for changed offsets
8. AI proposes mapping candidates
9. AI documents confirmed findings
10. AI repeats with the next value or next parameter

### Important consequence

To find read mappings, it is usually **not enough** to inspect the write payload alone.

In many cases you must:

- trigger a write
- re-read DSP blocks
- compare the before/after dumps
- decode the changed read locations

This is the main reason the project combines:

- GUI write capture
- automated re-dump
- block diffing
- scripting
- repeatable AI workflows

---

## Overview

The project is split into several areas:

- `de.drremote.dsp408.proxy`
  - proxy connection, control/stream channel, session handling
- `de.drremote.dsp408.dump`
  - full DSP dump and helper functions for byte decoding
- `de.drremote.dsp408.raw`
  - direct raw access with payload or frame sending
- `de.drremote.dsp408.script`
  - scripting language, parser, runtime, GUI-capture helpers
- `de.drremote.dsp408.tool`
  - small public tool facade for read/write/script
- `de.drremote.dsp408.model`
  - response, status, capture, and frame models
- `de.drremote.dsp408.util`
  - hex, JSON, and wait helpers

---

## Core Functions

### 1. DSP Dump
`DSP408ProxyDump` opens a proxy connection, prepares the session, and reads:

- `handshake_init`
- `device_info`
- `system_info`
- optional `login`
- all blocks from `0x00` to `0x1C`

The result is collected as a `DumpResult` and can be output as JSON.

### 2. Proxy Client
`ProxyClient` encapsulates:

- stream connection
- control connection
- session reset
- session initialization
- handshake
- login
- block read
- controlled sending of a payload with expected DSP response

### 3. Raw Write
`DspRawWriteTool` is intended for direct low-level tests.

It can:

- send raw payloads
- send complete frames
- optionally run handshake and login beforehand
- display response frames including checksum, payload hex, and ASCII

### 4. Script Engine
The script engine allows small `.dspd` scripts for:

- connect / disconnect
- status checks
- handshake / login
- read blocks
- send payloads
- conditions with `if / else if / else`
- loops with `for`
- GUI capture
- saving output to files
- byte evaluation (`u8`, `u16le`, `u32le`, `ascii`, `hex`, `slice`)
- event-based GUI action capture that ignores `0x40` background traffic
- write-series helpers for fader decoding such as command/channel filtering and `u16` series extraction

### 5. GUI Capture
With `GuiSnifferClient` or `GuiCaptureDaemon`, frames streamed through the proxy can be captured to analyze actions from the original software.

This is especially useful for AI-assisted reverse engineering, because the captured write frames can immediately be correlated with readback changes from fresh dumps.

---

## Requirements

To use proxy functions, the matching counterpart services must be running.

Typical defaults:

- **Stream Host:** `127.0.0.1`
- **Stream Port:** `19081`
- **Control Host:** `127.0.0.1`
- **Control Port:** `19082`

For GUI decode/capture workflows:

- the original software should already be connected **through the proxy**
- the project notes mention `127.0.0.1:9761` for this
- while the GUI remains connected, scripts and AI tooling can continue capturing writes and performing readback analysis

For raw access, default values are:

- **Host:** `192.168.0.166`
- **Port:** `9761`

You can override these via the CLI.

---

## Build Output

The build produces three separate fat JARs in `target/`:

- `target/tracks-dsp-reader-all.jar`
- `target/tracks-dsp-writer-all.jar`
- `target/tracks-dsp-script-all.jar`

This keeps daily usage on Windows simple:

```powershell
java -jar target\tracks-dsp-reader-all.jar ...
java -jar target\tracks-dsp-writer-all.jar ...
java -jar target\tracks-dsp-script-all.jar ...
```

Build command:

```powershell
mvn -q -DskipTests package
```

---

## Key Classes

## `ProxyClient`
Central class for normal proxy operation.

Important methods:

- `connect()`
- `status()`
- `resetSession()`
- `ensureSession()`
- `handshakeInit()`
- `deviceInfo()`
- `systemInfo()`
- `handshake()`
- `login(pin)`
- `readBlock(blockIndex)`
- `sendPayload(payload, expectedCommand, strictResponse, label)`
- `prepareSession(pin)`

---

## `DSP408ProxyDump`
Reads a full set of DSP data and returns a `DumpResult`.

Flow:

1. connect
2. reset session
3. ensure session
4. read handshake
5. optional login
6. read all blocks
7. produce JSON result

---

## `DumpResult`
Serializes a dump in JSON with:

- `ok`
- `summary`
- `handshake`
- `login`
- `blocks`
- optional `error`

Example structure:

```json
{
  "ok": true,
  "summary": {
    "handshake_ok": true,
    "login_used": false,
    "blocks_total": 29,
    "blocks_bad_checksum": 0
  },
  "handshake": {
    "init": { "...": "..." },
    "device": { "...": "..." },
    "system": { "...": "..." }
  },
  "login": null,
  "blocks": [
    { "...": "..." }
  ]
}
````

---

## `DspReadCli`

CLI for full proxy-based DSP dumps.

Supports:

* `--stream-host <host>`
* `--stream-port <port>`
* `--control-host <host>`
* `--control-port <port>`
* `--pin <1234>`
* `--out <path>`
* `--verbose`

---

## `DspWriteCli`

CLI for controlled payload injection through the proxy.

Supports:

* `--payload <hex>`
* `--stream-host <host>`
* `--stream-port <port>`
* `--control-host <host>`
* `--control-port <port>`
* `--pin <1234>`
* `--expected-command <cmd>`
* `--label <text>`
* `--non-strict`
* `--verbose`

---

## `DspRawWriteTool`

CLI for quick raw tests against the device.

Supports:

* `--payload <hex>`
* `--frame <hex>`
* `--handshake`
* `--login <1234>`
* `--responses <n>`
* `--verbose`

---

## `DspScriptEngine`

Executes `.dspd` scripts.

Supports, among others:

* variables via `let`
* control flow with `if`, `else if`, `else`, `for`
* `assert`
* `print`
* `sleep`
* `save-text`
* connect / GUI-capture commands
* function style and legacy style for expressions

---

## CLI Usage

## 1. Reader CLI

Example:

```bash
java -jar target\tracks-dsp-reader-all.jar --pin 1234 --out out\dump.json
```

Options:

* `--pin <1234>`
* `--out <path>`
* `--stream-host <host>`
* `--stream-port <port>`
* `--control-host <host>`
* `--control-port <port>`
* `--verbose`

---

## 2. Writer CLI

Example:

```bash
java -jar target\tracks-dsp-writer-all.jar --payload "00 01 03 35 04 01"
```

Options:

* `--payload <hex>`
* `--pin <1234>`
* `--expected-command <cmd>`
* `--label <text>`
* `--non-strict`
* `--stream-host <host>`
* `--stream-port <port>`
* `--control-host <host>`
* `--control-port <port>`
* `--verbose`

---

## 3. Script CLI

Example:

```bash
java -jar target\tracks-dsp-script-all.jar --file script-example\01-status.dspd
```

Options:

* `--file <path>`
* `--stream-host <host>`
* `--stream-port <port>`
* `--control-host <host>`
* `--control-port <port>`
* `--verbose`

---

## 4. Raw Write Tool

Example:

```bash
java -cp target\tracks-dsp-script-all.jar de.drremote.dsp408.raw.DspRawWriteTool --host 192.168.0.166 --port 9761 --payload "00 01 03 35 04 01"
```

### FIR408 phase readback verification

For a direct, all-channel phase storage check with automatic safety restore:

```powershell
java -cp target\tracks-dsp-script-all.jar `
  de.drremote.dsp408.raw.Fir408PhaseReadVerifier
```

The verifier keeps all twelve channels muted, tests each phase as
`0 degrees -> 180 degrees -> 0 degrees`, and compares the complete config
before and after. Results are written below
`out/fir/fir408-phase-readback-verification`.

### FIR408 gate readback verification

For a direct storage check of all four gate fields on the write-confirmed
inputs InA and InD:

```powershell
java -cp target\tracks-dsp-script-all.jar `
  de.drremote.dsp408.raw.Fir408GateReadVerifier
```

The verifier derives the current gate tuples from the assembled FIR408
configuration, mutes all twelve channels, changes only one field at a time,
and immediately restores the original tuple. It never sends an unmute
command. The complete configuration must match byte-for-byte after the test.
Results are written below
`out/fir/fir408-gate-readback-verification`.

Examples from the tool:

### Mute Out1

```bash
java -cp target\tracks-dsp-script-all.jar de.drremote.dsp408.raw.DspRawWriteTool --handshake --login 1234 --payload "00 01 03 35 04 01"
```

### Unmute Out1

```bash
java -cp target\tracks-dsp-script-all.jar de.drremote.dsp408.raw.DspRawWriteTool --handshake --login 1234 --payload "00 01 03 35 04 00"
```

### Set Out1 phase to 180 degrees

```bash
java -cp target\tracks-dsp-script-all.jar de.drremote.dsp408.raw.DspRawWriteTool --handshake --login 1234 --payload "00 01 03 36 04 01"
```

### Set Out1 gain to 0.0 dB

```bash
java -cp target\tracks-dsp-script-all.jar de.drremote.dsp408.raw.DspRawWriteTool --handshake --login 1234 --payload "00 01 04 34 04 18 01"
```

### Set Out1 delay to 20.0 ms

```bash
java -cp target\tracks-dsp-script-all.jar de.drremote.dsp408.raw.DspRawWriteTool --handshake --login 1234 --payload "00 01 04 38 04 80 07"
```

---

## 5. GUI Capture Daemon

Start the daemon:

```bash
java -cp target\tracks-dsp-script-all.jar de.drremote.dsp408.script.GuiCaptureDaemon
```

Use the CLI against it:

```bash
java -cp target\tracks-dsp-script-all.jar de.drremote.dsp408.script.GuiCaptureDaemonCli arm
java -cp target\tracks-dsp-script-all.jar de.drremote.dsp408.script.GuiCaptureDaemonCli finish 1800 12000
java -cp target\tracks-dsp-script-all.jar de.drremote.dsp408.script.GuiCaptureDaemonCli stop
```

---

## Script Language

## Basic Syntax

Preferred style uses function-call syntax with `()` and optional `;`. Legacy forms without parentheses are still supported for compatibility.

Comments:

```txt
// comment
# legacy comment
```

Variables:

```txt
let x = 123;
let name = "test";
```

Quoted strings always stay strings (no auto-typing), even if they look like numbers or booleans:

```txt
print("123");
print("true");
print("0012");
```

Output:

```txt
print($x);
print("Hello");
```

Conditions:

```txt
if ($x == 123) {
    print("ok");
} else if ($x > 100) {
    print("big");
} else {
    print("other");
}
```

Loops:

```txt
for (let i in 0..5) {
    print($i);
}
```

Assertions:

```txt
assert $x == 123;
assert not ($x == 0);
```

Write to file:

```txt
save-text("out/result.txt", "Hello World");
```

---

## Script Commands

### Connection / Session

* `connect`
* `connect(<streamHost>, <streamPort>, <controlHost>, <controlPort>)`
* `disconnect`
* `status`
* `reset-session`
* `ensure-session`
* `clear-frames`
* `handshake`
* `login(<pin>)`
* `read-block(<block>)`

### GUI

* `gui-connect`
* `gui-connect(<streamHost>, <streamPort>)`
* `gui-disconnect`
* `gui-capture(<note>, [quietMs], [maxWaitMs])`
* `gui-begin-capture()`
* `gui-end-capture([quietMs], [maxWaitMs])`

### Helper Functions

* `len(...)`
* `contains(...)`
* `starts-with(...)`
* `ends-with(...)`
* `upper(...)`
* `lower(...)`
* `trim(...)`
* `join(...)`
* `at(...)`
* `split(...)`
* `replace(...)`

### Byte/Hex Functions

* `bytes(...)`
* `hex(...)`
* `slice(...)`
* `ascii(...)`
* `u8(...)`
* `u16le(...)`
* `u32le(...)`

### Write

* `write(...)`
* `send-payload ...`
* `tx ...`

---

## Example Scripts

## Check status

```txt
connect();
let s = status();
print($s);
print($s.sessionActive);
print($s.injectReady);
```

## Handshake and read a block

```txt
connect();
reset-session();
ensure-session();
handshake();

let resp = read-block(0x00);
print($resp);
print($resp.payloadHex);
```

## Capture a GUI action

```txt
gui-connect();
let cap = gui-capture("Please perform the target action in the GUI now", 1500, 12000);
print($cap);
print($cap.lastWrite);
```

## Save multiple blocks

```txt
connect();
reset-session();
ensure-session();
handshake();

for (let i in 0..5) {
    let resp = read-block($i);
    save-text("out/block-${i}.txt", $resp.payloadHex);
}
```

---

## Reverse-Engineering Strategy

The most reliable strategy for finding real field mappings is:

1. create a baseline dump
2. change exactly one GUI value
3. capture the GUI write frame
4. create a second dump
5. diff both dumps
6. identify changed offsets
7. repeat with several values
8. confirm encoding and scaling

This is important because:

* write command structure and read storage structure are often different
* the same parameter may use one encoding on write and another in block storage
* isolated GUI write capture alone is usually not enough

---

## Codex Workflow

A strong setup is:

1. keep the original GUI connected through the proxy
2. let Codex run helper scripts / dumps / comparisons
3. trigger one GUI action at a time
4. let Codex analyze:

   * last write frame
   * changed blocks
   * changed offsets
   * value scaling candidates
5. update documentation and repeat

In practice, this means Codex can continuously decode the protocol in an iterative loop as long as the proxy and GUI stay active.

The project does not implement an AI by itself; it provides the infrastructure that makes such an AI loop practical.

---

## Public Tool Facade

The `de.drremote.dsp408.tool` package contains simple entry points:

### `ReadTool`

```java
String json = ReadTool.dumpAllJson(streamHost, streamPort, controlHost, controlPort, pin, verbose);
```

### `WriteTool`

```java
ProxyResponse response = WriteTool.sendPayloadOnce(
    streamHost,
    streamPort,
    controlHost,
    controlPort,
    pin,
    verbose,
    payload,
    expectedCommand,
    strictResponse,
    label
);
```

### `ScriptTool`

```java
ScriptTool.runScriptFile(streamHost, streamPort, controlHost, controlPort, verbose, file);
ScriptTool.runScriptText(streamHost, streamPort, controlHost, controlPort, verbose, scriptText);
```

---

## Technical Notes

## Response Matching

`StreamChannel.waitForResponse(...)` works in two steps:

1. it waits for the matching `PC_TO_DSP` frame to appear in the stream
2. then it searches for the next matching `DSP_TO_PC` response

Optionally it can check for a specific command code.

## Session Readiness

`ProxyClient.ensureSession()` waits actively until:

* `sessionActive == true`
* `injectReady == true`

## Checksum

Both dump mode and raw mode expose checksum status.

## Dump Blocks

Currently, blocks from:

* `0x00`
* to `0x1C`

are read.

---

## Safety / Caution

Write functions and raw access can change the DSP state.

Therefore:

* test with read/dump first
* only send known payloads for writes
* if possible, work against a test setup first
* use raw frame access only for low-level debugging

---

## Known Limits

* JSON is handled with a small in-house helper (`JsonUtil`), not an external JSON library
* the script language is small and deliberately pragmatic
* the parser is built for the internal DSL style, not for a general-purpose language
* `RawSocketClient` is intended for diagnostics and tests, not robust long-running use
* some defaults are strongly tied to your local proxy/DSP setup
* many parameters still require repeated GUI-write + readback-diff analysis before they can be considered decoded

---

## Typical Workflow

### Read / Analysis

1. start proxy
2. connect DSP/original software
3. `status`
4. `reset-session`
5. `ensure-session`
6. `handshake`
7. `read-block(...)` or full dump

### GUI Reverse Engineering

1. connect original software through proxy
2. keep the GUI connected
3. `gui-connect`
4. capture a GUI action
5. re-read DSP blocks
6. diff old vs new dumps
7. correlate write payload with changed read offsets
8. document the result
9. repeat in a continuous AI loop

### Event-Based GUI Capture

For setups where GUI and DSP communicate continuously, prefer event-based capture instead of fixed waiting windows.

Use:

- `guiActionCapture(...)`

This mode:

- waits for the first real GUI write
- ignores `0x40` background traffic
- automatically stops after the action settles

It is especially useful for:

- mute / phase toggles
- single parameter clicks
- gain or delay fader moves

### Fader Decoding Helpers

The DSL also supports write-series analysis helpers that make fader reverse engineering easier:

- `writesByCommand(...)`
- `writesByCommandAndChannel(...)`
- `payloadSeries(...)`
- `u16Series(...)`
- `changingOffsetsAcrossWrites(...)`

These helpers allow you to isolate one fader movement and identify which payload offsets and `u16le` values change across the write series.

### Write Test

1. prepare session
2. optional login
3. send known payload
4. verify response
5. verify stored value via fresh dump if needed

---

## License / Status

Internal reverse-engineering/debugging helper for DSP408-related tests and automation.

Project status: experimental, but already strongly structured around:

* Read
* Write
* Dump
* Script
* GUI Capture
* AI-assisted continuous protocol decoding

```
