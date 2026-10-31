# DSP408 Script Syntax

> Additional detailed script reference. Start with the current
> [DSPD syntax](DSPD-SYNTAX.md); offline checking is described in
> [DSPD check](docs/DSPD-CHECK.md). For CLI options and build commands, see
> [CLI](docs/CLI.md) and [Development](docs/DEVELOPMENT.md).
> The following examples describe execution: they can send device commands
> and write files. `check` does not execute these functions; the existing
> separate interpreter does not check with the compiler automatically. Common
> CLI `run` now compiles the complete script before checked AST execution; see
> [Execution](docs/EXECUTION.md) for that path and the new parameter functions.

This file describes the syntax of `.dspd` scripts in `tracks-dsp-raider`.

The script language is intentionally small and pragmatic.
It is designed for:

- connecting to the proxy
- reading and sending DSP commands
- evaluating GUI captures
- simple conditions and loops
- byte/hex inspection
- diff-based decode workflows
- saving results

---

## Modern Syntax (Preferred)

The DSL supports a modern C/Java-like style **and** the legacy syntax.
Both can be mixed in the same script.

```dspd
connect();
ensureSession();
handshake();

let pin = "1234";
login(pin);

let resp = readBlock(0x04);
print(resp.payloadHex);

if (resp.command == 0x24) {
    print("read block ok");
} else {
    print("unexpected response");
}

for (let ch in 0x04..0x07) {
    let block = readBlock(ch);
    print(block.commandHex);
}
```

Legacy syntax (`end`, kebab-case commands, no `;`) remains supported.

Examples:

```dspd
connect
ensure-session
handshake

let resp = read-block 0x04
print $resp.payloadHex
```

---

## Naming Style

Most commands/functions can be used in:

- **modern camelCase**, for example `readBlock()`, `ensureSession()`, `saveDiffReport()`
- **legacy kebab-case**, for example `read-block()`, `ensure-session()`, `save-diff-report()`

Examples:

```dspd
readBlock(0x00)
read-block(0x00)

ensureSession()
ensure-session()
```

---

## File Format

- scripts are plain text files
- recommended extension: `.dspd`
- encoding: `UTF-8`

---

## Comments

Comments start with `#` or `//`.

```txt
# This is a comment
connect
// another comment
```

Comments inside strings are **not** treated as comments.

```txt
print("Hello # not a comment");
```

---

## Statements

A statement can be separated by:

- newline
- `;`

Examples:

```txt
connect
status
```

```txt
connect(); status(); handshake();
```

```txt
connect
status
handshake
```

---

## Strings

Strings use double quotes:

```txt
print("Hello World");
```

Supported escape sequences inside strings include:

- `\"`
- `\\`
- `\n`
- `\r`
- `\t`

---

## Variables

Variables are created with `let`:

```txt
let x = 123;
let name = "Test";
let block = 0x10;
```

The last evaluated expression is also stored in `_`.

Example:

```txt
status();
print(_);
```

---

## Values and Literals

Supported base values:

- `null`
- `true`
- `false`
- integers, for example `123`
- hex integers, for example `0x2C`
- decimals, for example `12.5`
- strings, for example `"abc"`

---

## Variable Access

### Direct access

Variables can be used directly by name:

```txt
let x = 10;
print(x);
```

### Access with `$`

Access can also start with `$`:

```txt
let x = 10;
print($x);
```

### Property access

Properties can be read from objects:

```txt
let s = status();
print(s.sessionActive);
print($s.injectReady);
```

### Index access

Lists, strings, and byte arrays can be indexed:

```txt
let cap = guiEndCapture();
print(cap.frames[0]);
print(cap.frames[0].commandHex);
```

---

## String Interpolation

Tokens and strings can use placeholders with `${...}`.

```txt
let i = 5;
print("Block ${i}");
save-text("out/block-${i}.txt", "Hello");
```

Paths/properties are also possible:

```txt
let s = status();
print("ready=${s.injectReady}");
```

---

## Blocks

Blocks can be closed with `}` (preferred) or `end` (legacy).

Used by:

- `if`
- `for`

Examples:

```dspd
if (true) {
    print("ok");
}
```

```dspd
if true
    print "ok"
end
```

---

# Control Flow

## if / else if / else

### Modern style

```dspd
if (x == 1) {
    print("one");
} else if (x == 2) {
    print("two");
} else {
    print("other");
}
```

### Legacy style

```dspd
if $x == 1
    print "one"
else if $x == 2
    print "two"
else
    print "other"
end
```

---

## Comparison Operators

Supported:

- `==`
- `!=`
- `>`
- `>=`
- `<`
- `<=`

Examples:

```txt
if (x == 10) {
    print("ok");
}
```

```txt
if ($gain > 0)
    print "positive"
end
```

---

## Logical Operators

Supported:

- `and`
- `or`
- `not`

Examples:

```txt
if ($a == 1 and $b == 2)
    print "both match"
end
```

```txt
if ($a == 1 or $b == 2)
    print "at least one matches"
end
```

```txt
if not ($x == 0)
    print "not zero"
end
```

---

## Parentheses in Conditions

Conditions may be parenthesized:

```txt
if (($a == 1 and $b == 2) or $c == 3) {
    print("ok");
}
```

---

## for Loop

Syntax:

```txt
for <variable> in <start>..<end>
    ...
end
```

Modern style:

```txt
for (let i in 0..5) {
    print(i);
}
```

Legacy style:

```txt
for i in 0..5
    print $i
end
```

Descending ranges are also supported:

```txt
for i in 5..0
    print $i
end
```

---

# Commands

## Connection

### connect

Connects to the default proxy:

```txt
connect
connect()
```

Or with explicit parameters:

```txt
connect("127.0.0.1", 19081, "127.0.0.1", 19082);
```

Legacy style:

```txt
connect "127.0.0.1" 19081 "127.0.0.1" 19082
```

### disconnect

```txt
disconnect
disconnect()
```

---

## Status / Session

### status

Reads the proxy status.

```txt
status
let s = status()
print($s.sessionActive)
print($s.injectReady)
```

### attach-session / attachSession

Attaches to an existing GUI/DSP session without rebuilding it via `reset-session`.
This is the recommended mode when the original GUI should stay connected.

```txt
connect();
attachSession();
handshake();
```

### reset-session / resetSession

```txt
reset-session
resetSession()
```

### ensure-session / ensureSession

```txt
ensure-session
ensureSession()
```

### clear-frames / clearFrames

```txt
clear-frames
clearFrames()
```

### handshake

Sends:

- `handshake_init`
- `device_info`
- `system_info`

```txt
handshake
handshake()
```

### handshake-init / handshakeInit

```txt
let r = handshakeInit();
print(r.payloadHex);
```

### device-info / deviceInfo

```txt
let r = deviceInfo();
print(r.payloadHex);
```

### system-info / systemInfo

```txt
let r = systemInfo();
print(r.payloadHex);
```

---

## Login

### login

```txt
login 1234
login("1234")
```

The PIN must be exactly 4 digits.

---

## Read Block

### read-block / readBlock

```txt
let b = readBlock(0x00);
print(b.payloadHex);
```

Allowed range:

- `0x00` to `0x1C`

---

## Send Payload

### Legacy Syntax

```txt
send-payload "00 01 03 35 04 01"
tx "00 01 03 35 04 01"
write "00 01 03 35 04 01"
```

With expected response:

```txt
send-payload "00 01 02 27 00" expect 0x24
```

### Function Style

```txt
write("00 01 03 35 04 01")
write("00 01 02 27 00", 0x24)
```

If an expected command is specified, response matching is strict.

---

# GUI Capture

## gui-connect / guiConnect

Connects the GUI sniffer to the stream.

```txt
gui-connect
guiConnect()
```

or:

```txt
guiConnect("127.0.0.1", 19081);
```

## gui-disconnect / guiDisconnect

```txt
gui-disconnect
guiDisconnect()
```

## gui-capture / guiCapture

Starts capture without interactive input.
The engine arms the sniffer, waits a configurable time window for the GUI action,
and then automatically collects until the quiet phase.

Preferred modern style:

```txt
let cap = guiCapture("Please perform the GUI action", 15000, 1500, 12000);
print(cap);
```

Parameters:

- optional note text
- optional `actionWindowMs`
- optional `quietMs`
- optional `maxWaitMs`

Examples:

```txt
let cap = guiCapture();
let cap = guiCapture(15000);
let cap = guiCapture("Move one slider now");
let cap = guiCapture("Move one slider now", 15000, 1500, 12000);
```

Legacy style is also supported:

```txt
let cap = gui-capture "Move one slider now" 15000 1500 12000
```

## gui-begin-capture / guiBeginCapture

```txt
guiBeginCapture();
sleep(5000);
let cap = guiEndCapture(1500, 12000);
```

## gui-end-capture / guiEndCapture

```txt
let cap = guiEndCapture();
let cap = guiEndCapture(1500, 12000);
```

---

# Assertions

## assert

```txt
assert $x == 10
assert not ($resp == null)
```

If the condition fails, the script aborts with an error.

---

# Waiting

## sleep

```txt
sleep 500
sleep(500)
```

Unit: milliseconds

---

# Output

## print

```txt
print("Hello");
print(resp);
print(resp.payloadHex);
```

---

# Save to File

## save-text / saveText

Syntax:

```txt
save-text <path> <expr>
saveText(path, expr)
```

Examples:

```txt
saveText("out/result.txt", "Hello World");
```

```txt
save-text "out/block.txt" $resp.payloadHex
```

## save-diff-report / saveDiffReport

Writes a formatted diff report to a file and returns the absolute path.

```txt
saveDiffReport("out/diff.txt", beforeBytes, afterBytes);
```

Legacy style:

```txt
save-diff-report "out/diff.txt" $before $after
```

## save-capture-read-blocks / saveCaptureReadBlocks

Writes all detected `read_block` responses from a capture to files.

Generated files:

- `block-XX.hex.txt`
- `block-XX.ascii.txt`

Example:

```txt
saveCaptureReadBlocks(cap, "out/capture-blocks");
```

Legacy style:

```txt
save-capture-read-blocks $cap "out/capture-blocks"
```

---

# Expressions

The script language supports two styles:

- **legacy style**
- **function style**

Both can be mixed.

---

## Legacy Style

Examples:

```txt
status
read-block 0x00
login 1234
len $cap.frames
hex $resp.payload
```

---

## Function Style

Examples:

```txt
status()
readBlock(0x00)
login("1234")
len(cap.frames)
hex(resp.payload)
```

---

# Decode / Reverse-Engineering Helper Functions

These helpers are especially useful for fast DSP decoding.

## read-block-index / readBlockIndex

Returns the read-block index if the value is a read-block response, otherwise `null`.

Works on:

- `ProxyResponse`
- `SniffedFrame`
- payload `byte[]`

```txt
let r = readBlock(0x04);
print(readBlockIndex(r));
```

---

## read-block-payload / readBlockPayload

Looks up the last captured read-block response for the given block index and returns its payload bytes.

Input:

- `GuiCaptureResult`
- block index

```txt
let payload = readBlockPayload(cap, 0x04);
print(hex(payload));
```

Returns `null` if not found.

---

## cmd

Returns the command byte as integer.

Works on:

- `ProxyResponse`
- `SniffedFrame`
- payload `byte[]`

```txt
print(cmd(resp));
print(cmd(frame));
```

---

## payload

Returns payload bytes.

Works on:

- `ProxyResponse`
- `SniffedFrame`
- payload `byte[]` passthrough

```txt
let p = payload(resp);
print(hex(p));
```

---

## raw

Returns raw frame bytes.

Works on:

- `ProxyResponse`
- `SniffedFrame`
- payload `byte[]` passthrough

```txt
let r = raw(resp);
print(hex(r));
```

---

## payload-hex / payloadHex

Shortcut for payload bytes as hex text.

```txt
print(payloadHex(resp));
print(payloadHex(frame));
```

---

## payload-ascii / payloadAscii

Shortcut for payload bytes as ASCII preview.

```txt
print(payloadAscii(resp));
print(payloadAscii(frame));
```

---

## diff-bytes / diffBytes

Formats byte-level differences between two byte arrays.

```txt
print(diffBytes(before, after));
```

Example output:

```txt
0x12: 01 -> 02
0x13: 10 -> 11
```

---

## diff-u16le / diffU16le

Formats little-endian 16-bit diff candidates between two byte arrays.

```txt
print(diffU16le(before, after));
```

Example output:

```txt
u16le@0x12: 280 -> 308 (0x0118 -> 0x0134)
```

---

## diff-report / diffReport

Builds a full decode-oriented report:

- before/after length
- byte diffs
- u16le candidates

```txt
print(diffReport(before, after));
```

---

## changed-offsets / changedOffsets

Returns a list of integer offsets that changed.

```txt
let offsets = changedOffsets(before, after);
print(offsets);
print(len(offsets));
```

---

# Helper Functions

## len

```txt
len("abc")
len($bytes)
len($cap.frames)
```

---

## contains

```txt
contains("abcdef", "cd")
contains($resp.payloadHex, "2C")
```

---

## starts-with / startsWith

```txt
startsWith("abcdef", "abc")
```

---

## ends-with / endsWith

```txt
endsWith("abcdef", "def")
```

---

## upper / lower / trim

```txt
upper("abc")
lower("ABC")
trim("  test  ")
```

---

## join

```txt
join(split("a,b,c", ","), " | ")
```

---

## at

```txt
at("Hello", 1)
at($list, 0)
at($bytes, 3)
```

---

## split

```txt
split("a,b,c", ",")
```

---

## replace

```txt
replace("Hello World", "World", "DSP")
```

---

# Byte/Hex Functions

## bytes

Converts hex to `byte[]`.

```txt
let p = bytes("00 01 03 35 04 01")
```

---

## hex

Outputs bytes as hex.

```txt
hex($resp.payload)
hex($resp.payload, 0, 4)
```

---

## slice

```txt
slice($resp.payload, 0, 8)
```

---

## ascii

```txt
ascii($resp.payload, 0, 8)
ascii($resp.payload, 0, 8, true)
```

In legacy style, `trimzero` is also possible:

```txt
ascii $resp.payload 0 8 trimzero
```

---

## u8 / u16le / u32le

```txt
u8($resp.payload, 0)
u16le($resp.payload, 4)
u32le($resp.payload, 8)
```

All multi-byte values are **little endian**.

---

# Return Objects and Properties

## ProxyStatus

Properties:

- `sessionActive`
- `injectReady`
- `rawResponse`

Example:

```txt
let s = status()
print $s.sessionActive
print $s.injectReady
```

---

## ProxyResponse

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

Example:

```txt
let r = readBlock(0x00);
print(r.commandHex);
print(r.readBlockIndex);
print(r.payloadHex);
print(r.payloadLen);
```

---

## GuiCaptureResult

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

Example:

```txt
let cap = guiEndCapture();
print(cap.totalFrames);
print(cap.lastWrite);
print(cap.readBlockResponses);
```

---

## SniffedFrame

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

Example:

```txt
let f = cap.lastWrite;
print(f.commandHex);
print(f.payloadHex);
print(f.frameHex);
```

---

## byte[]

A byte array also supports property access in paths:

- `hex`
- `ascii`
- `len`
- `command`
- `commandHex`
- `readBlockIndex`

Example:

```txt
let p = bytes("00 01 02 24 04");
print(p.hex);
print(p.commandHex);
print(p.readBlockIndex);
```

---

# Useful Capture Functions

## first-write / last-write

```txt
firstWrite(cap)
lastWrite(cap)
```

---

## first-response / last-response

```txt
firstResponse(cap)
lastResponse(cap)
```

---

## writes / responses

```txt
writes(cap)
responses(cap)
```

---

## capture-count / capture-write-count / capture-response-count

```txt
captureCount(cap)
captureWriteCount(cap)
captureResponseCount(cap)
```

---

## capture-frame

```txt
captureFrame(cap, 0)
```

---

## last-write-excluding

```txt
lastWriteExcluding(cap, 0x40)
```

---

## recent-writes

```txt
recentWrites(cap, 10)
```

---

## recent-writes-excluding

```txt
recentWritesExcluding(cap, 10, 0x40)
```

---

# Truthiness

The following values are considered `false`:

- `null`
- `false`
- numeric `0`
- empty string
- empty byte array
- empty list

Everything else is `true`.

Example:

```txt
if $resp
    print "Response present"
end
```

---

# Complete Examples

## Example 1: Prepare session and read a block

```txt
connect();
resetSession();
ensureSession();
handshake();

let resp = readBlock(0x00);
print(resp);
print(resp.payloadHex);
print(resp.payloadAscii);
```

---

## Example 2: Login and write

```txt
connect();
resetSession();
ensureSession();
handshake();
login("1234");

let resp = write("00 01 03 35 04 01");
print(resp);
```

---

## Example 3: Decode a GUI action

```txt
guiConnect();

let cap = guiCapture("Please perform the action in the original GUI now", 15000, 1800, 12000);
print(cap);
print(cap.lastWrite);
print(cap.writes);
```

---

## Example 4: Read multiple blocks

```txt
connect();
resetSession();
ensureSession();
handshake();

for (let i in 0..5) {
    let r = readBlock(i);
    print("Block ${i}");
    print(r.payloadHex);
}
```

---

## Example 5: Conditions

```txt
connect();
let s = status();

if (s.sessionActive and s.injectReady) {
    print("Proxy is ready");
} else {
    print("Proxy is not ready");
}
```

---

## Example 6: Diff two payloads

```txt
let before = bytes("00 01 04 34 04 18 01");
let after  = bytes("00 01 04 34 04 34 01");

print(diffBytes(before, after));
print(diffU16le(before, after));
print(diffReport(before, after));
print(changedOffsets(before, after));
```

---

## Example 7: Save a decode diff report

```txt
let before = bytes("00 01 04 34 04 18 01");
let after  = bytes("00 01 04 34 04 34 01");

let path = saveDiffReport("out/gain-diff.txt", before, after);
print(path);
```

---

## Example 8: Extract captured read-block payload

```txt
let cap = guiCapture("Trigger one GUI read action now", 15000, 1500, 12000);
let p = readBlockPayload(cap, 0x04);

if (p != null) {
    print(hex(p));
    print(p.readBlockIndex);
}
```

---

## Example 9: Event-based GUI action capture

```txt
guiConnect();

let cap = guiActionCapture("Toggle one GUI value now", 45000, 1200, 6000);
print(cap);
print(lastWriteExcluding(cap, 0x40));
```

---

## Example 10: Fader recorder helpers

```txt
guiConnect();

let cap = guiActionCapture("Move the InA gain fader now", 45000, 1200, 6000);
let series = writesByCommandAndChannel(cap, 0x34, 0x00);

print(payloadSeries(series));
print(changingOffsetsAcrossWrites(series));
print(u16Series(series, 5));
```

---

## Example 11: Start Gate decoding with isolated GUI capture

Use the dedicated Gate capture scripts to identify the real GUI write command before readback localization:

```txt
script-example/64-auto-capture-ina-gate-threshold-clean.dspd
script-example/65-auto-capture-ina-gate-attack-clean.dspd
script-example/66-auto-capture-ina-gate-hold-clean.dspd
script-example/67-auto-capture-ina-gate-release-clean.dspd
```

Recommended workflow:

1. Move exactly one InA gate control once.
2. Inspect `out/clean/.../last-write.txt`.
3. Inspect `recent-interesting-writes.txt` for related command series.
4. If the GUI emitted read-block responses, inspect the saved `blocks/`.
5. After the Gate write command is known, create focused before/after read-diff scripts like the existing mute, phase, and gain decode scripts.

---

# Event-Based Capture And Fader Helpers

## gui-action-capture / guiActionCapture

Event-based capture mode for live decoding.

It:

- waits for the first real GUI write
- ignores command `0x40`
- auto-stops after the action becomes quiet

```txt
let cap = guiActionCapture("Move one slider now", 45000, 1200, 6000);
print(lastWriteExcluding(cap, 0x40));
```

## writes-by-command / writesByCommand

```txt
let gainWrites = writesByCommand(cap, 0x34);
```

## writes-by-command-and-channel / writesByCommandAndChannel

```txt
let inAGain = writesByCommandAndChannel(cap, 0x34, 0x00);
```

## payload-series / payloadSeries

```txt
print(payloadSeries(series));
```

## u16-series / u16Series

```txt
print(u16Series(series, 5));
```

## changing-offsets-across-writes / changingOffsetsAcrossWrites

```txt
print(changingOffsetsAcrossWrites(series));
```

---

# Typical Errors

## Missing `end` or `}`

```txt
if true
    print "ok"
```

Error: block not closed.

---

## Invalid range expression

```txt
for i in 0-5
    print $i
end
```

Correct:

```txt
for i in 0..5
    print $i
end
```

---

## Invalid hex data

```txt
write("00 01 0G")
```

Error: `0G` is not valid hex.

---

## Invalid variable path

```txt
print $resp.unknownField
```

Error: the property does not exist.

---

# Quick Reference

## Statements

- `let <var> = <expr>`
- `connect`
- `disconnect`
- `gui-connect`
- `gui-disconnect`
- `status`
- `attach-session`
- `reset-session`
- `ensure-session`
- `clear-frames`
- `handshake`
- `assert <condition>`
- `sleep <ms>`
- `save-text <path> <expr>`
- `save-diff-report <path> <before> <after>`
- `save-capture-read-blocks <capture> <dir>`
- `print <expr>`

## Control Flow

- `if ...`
- `else if ...`
- `else`
- `for i in a..b`
- `end`
- `{ ... }`

## Comparison

- `==`
- `!=`
- `>`
- `>=`
- `<`
- `<=`

## Logic

- `and`
- `or`
- `not`

## Decode Helpers

- `read-block-index`
- `read-block-payload`
- `cmd`
- `payload`
- `raw`
- `payload-hex`
- `payload-ascii`
- `diff-bytes`
- `diff-u16le`
- `diff-report`
- `changed-offsets`
- `gui-action-capture`
- `writes-by-command`
- `writes-by-command-and-channel`
- `payload-series`
- `u16-series`
- `changing-offsets-across-writes`

## Byte / Helpers

- `bytes`
- `hex`
- `slice`
- `ascii`
- `u8`
- `u16le`
- `u32le`
- `len`
- `contains`
- `starts-with`
- `ends-with`
- `upper`
- `lower`
- `trim`
- `join`
- `at`
- `split`
- `replace`
