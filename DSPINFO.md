# DSPINFO.md

> Historical protocol notes for **DSP408 V0104**. Block ranges and memory
> mappings do not generally transfer to FIR408 or other firmware. The current
> gain/mute/delay overview, including evidence status, is in
> [PARAMETERS.md](docs/PARAMETERS.md).
> [Architecture](docs/ARCHITECTURE.md) describes shared framing;
> [Workflows](docs/WORKFLOWS.md) shows current read and capture commands.
> The original observations are retained below as source context.

## Overview

This document describes the **roughly decoded communication layout** of the **t.racks / Thomann Track DSP 408** (`DSP408`) and explains the most important differences between **Read** and **Write**.

Important:
This project follows a strict evidence rule:

- Only facts are used that are directly backed by
  - captures,
  - dumps,
  - proxy logs,
  - script tests,
  - or a controlled GUI change plus read verification.

That means:

- **known** = observed or decoded
- **unknown** = not proven yet
- **partially decoded** = some fields observed, but structure not fully understood

---

## Device

- **Vendor/Label:** t.racks / Thomann
- **Product:** Track DSP 408
- **Short name:** DSP408
- **Observed protocol version:** `DSP408 V0104`

### Channel count

- **4 inputs**
- **8 outputs**
- **12 channels total**

### Channel index mapping

| Channel | Index |
|------|------:|
| InA  | 0 |
| InB  | 1 |
| InC  | 2 |
| InD  | 3 |
| Out1 | 4 |
| Out2 | 5 |
| Out3 | 6 |
| Out4 | 7 |
| Out5 | 8 |
| Out6 | 9 |
| Out7 | 10 |
| Out8 | 11 |

---

## Transport

Communication runs over **TCP**.

### Observed default target address

- **IP:** `192.168.0.166`
- **Port:** `9761`

### Proxy defaults

- **Listen Host:** `127.0.0.1`
- **Listen Port:** `9761`
- **Target IP:** `192.168.0.166`
- **Target Port:** `9761`

---

## Rough Frame Layout

The protocol is frame-based and uses DLE/STX start and DLE/ETX end markers.

### Frame structure

```text
10 02 + payload + 10 03 + xor
```

### Observed frame components

* **Header:** `10 02`
* **Footer:** `10 03`
* **Checksum:** 1 byte XOR
* **Frame detection:** from `DLE STX` to `DLE ETX`
* **No separate outer frame length field**

### Checksum

The checksum is:

* **XOR over all bytes**
* from **DLE STX** to **DLE ETX** inclusive
* followed by **1 checksum byte**

---

## Payload Layout

The payload is the actual protocol core.

### Observed byte offsets in the payload

| Offset | Meaning                                |
| -----: | ---------------------------------------- |
|      0 | direction marker                          |
|      1 | observed counter byte / prefix structure  |
|      2 | length                                    |
|      3 | command                                   |
|     4+ | arguments / data                          |

### Direction marker

Observed values:

* `00` = PC -> DSP
* `01` = DSP -> PC

This is still a working hypothesis but matches observed captures.

### Length field

The length field (offset 2) appears to be the **payload length including command + data**, not counting the leading header.

---

## Command Overview (Known)

This is the minimal set that is clearly confirmed.

### Command bytes (observed)

| Command | Direction | Meaning                     |
| ------: | --------- | --------------------------- |
| `0x10`  | PC->DSP   | handshake init              |
| `0x13`  | DSP->PC   | handshake ack / version     |
| `0x12`  | PC->DSP   | keepalive                   |
| `0x2C`  | PC->DSP   | system info request         |
| `0x2D`  | PC->DSP   | login PIN                   |
| `0x27`  | PC->DSP   | read parameter block        |
| `0x24`  | DSP->PC   | parameter block response    |
| `0x34`  | PC->DSP   | set gain                    |
| `0x35`  | PC->DSP   | set mute                    |
| `0x29`  | PC->DSP   | preset name request (likely)|

The command list may be incomplete.

---

## Read vs Write (Key Concept)

The **read** side (block dumps) and the **write** side (commands) are **logically connected** but often **encoded differently**.

This is the most important finding:

> **Read and Write are related, but not identical in structure or offsets.**

### Consequence

You cannot safely infer write offsets from read dumps, or vice versa. Both directions must be documented separately.

---

## Read (Block Dumps)

### Blocks

Known blocks range from:

* `0x00`
* to `0x1C`

A block read uses command `0x27` and returns `0x24`.

### Block size

Observed block sizes are fixed per block and differ between blocks. The payload often contains:

* ASCII names
* numeric fields
* mixed bit flags

### Example: channel data is spread

Channel data is not stored in a single contiguous structure per channel. Instead, channel fields are spread across multiple blocks.

---

## Write (Commands)

Write commands use a compact payload structure. The effect is visible in read dumps, but the actual read location can be elsewhere.

### Gain write (`0x34`)

* applies to a single channel
* uses a command payload that is not identical to the read dump format

### Mute write (`0x35`)

* applies to a single channel
* in read dumps, mute is stored as a **bit mask** rather than an individual field per channel

---

## Evidence Rule (Working Practice)

When decoding a new parameter:

1. perform exactly **one GUI change** (or one write)
2. take a **before dump**
3. take an **after dump**
4. compare blocks
5. analyze only the **changed bytes**
6. document read and write **separately**

This is the only reliable way to avoid false mappings.

---

## Observed Read/Write Differences (Examples)

### Mute

* **Write:** single channel + state
* **Read:** a bit mask in block `0x1C`

### Gain

* **Write:** a single command with channel + encoded value
* **Read:** 16-bit values in channel-specific offsets spread across blocks

### Delay

* **Read and Write may share the same raw encoding**, but still live in different structures

---

## Not Fully Decoded Yet

Not fully decoded (or only partially):

- PEQ readback/state modeling
- crossover
- routing
- delay editing
- limiter
- polarity
- many UI parameters in blocks

---

## Read/Write Separation Rule

Keep these rules in mind:

* read and write do **not** need to share the same structure
* multiple changes at once make diffs unusable
* only evaluate changed bytes

---

## Protocol Summary

The DSP408 uses a **TCP-based, frame-oriented binary protocol** with:

* direction-dependent payload structure
* DLE/STX framing
* XOR checksum

The key technical finding remains:

> **Read and Write are logically connected, but not identically encoded.**

---

## Status Note

This is **not** a complete official protocol.
It is an evidence-based working foundation for:

* reverse engineering
* automation
* controlled testing

---

## Practical decoding workflow

A recommended workflow:

1. start proxy
2. connect original software through proxy
3. perform a single UI change
4. take a before/after dump
5. compare blocks
6. document offsets and fields

---

## Read-Diff experiments

Read-diff experiments are required whenever:

* the read structure is unclear
* write is known but read mapping is unknown
* multiple offsets change at once

---

## Notes on ASCII names

ASCII names have been observed spread across multiple blocks.
Do not assume one name per block.

---

## Summary

DSP408 is a block-based state space with:

* fixed block sizes
* mixed ASCII and numeric fields
* channel data spread across multiple blocks
* many UI parameters not yet localized in read dumps

The most important rule remains:

**Read and Write are related but encoded differently.**
