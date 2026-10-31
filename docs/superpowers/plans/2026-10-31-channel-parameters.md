# Channel parameters implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Central Gain/Mute/Delay metadata and codecs, evidence-aware readings and semantic snapshot differences.

**Architecture:** A pure `parameters` package is shared by snapshot/FIR/passive write decoding, below all adapters. Existing JSON evidence and raw diff remain intact.

**Tech Stack:** Java 21, existing Maven/JUnit/Jackson; no new dependencies.

**Spec:** `../specs/2026-10-31-channel-parameters-design.md`

## Global constraints

- Known versions only: DSP408 V0104 and FIR408 V0113P; unknown firmware stays unconfirmed.
- No device writes, branch changes, deployment or changes to historical JSON/captures.
- Gain -60..12 dB / raw 0..400; Delay 0..680 ms / raw 0..65280; Mute wire states 0/1.
- FIR mute InD/Out8 confirmed; other inferred mask bits must not become confirmed values.

## Review focus

- A stale/inferred/unknown-version mapping cannot silently produce confirmed values.
- Bit-level coverage must retain unknown changes in partially known mute bytes.
- Invalid raw values and absent blocks stay unknown/invalid, not clamped to plausible endpoints.
- Semantic reports must not hide raw, trailing, added or removed differences.
- Existing decoder/CLI/fixture behavior remains compatible and needs no hardware.

## Task 1: Pure parameter metadata, scalar codecs and profile catalogs

Files: `parameters/{ParameterKind,Evidence,ReadLocation,BlockData,ParameterDefinition,ParameterValue,ParameterProfile,ParameterProfiles}.java` and tests.

- [x] Add failing tests for anchored conversions, strict ranges and golden write bytes.
- [x] Implement the pure model and exact-version catalogs with evidence references.
- [x] Test confirmed/inferred/missing/invalid readings and the real FIR fixture.

## Task 2: Semantic read/diff reports and decoder integration

Files: `snapshot/SnapshotParameters.java`, `snapshot/SnapshotAnalysis.java`,
`fir/Fir408Memory.java`, `decode/{DspLibLookup,WriteDecodeService}.java`, tests.

- [x] Add failing semantic-diff, invalid-value and unknown-bit regressions.
- [x] Integrate shared readings/conversions; preserve raw diff and legacy FIR detail fields.
- [x] Verify passive write decode rejects invalid values without changing valid output.

## Task 3: Documentation, offline artifacts and review

- [x] Document catalog scope, source evidence and confidence semantics.
- [x] Build the workspace and exercise CLI decode/diff using real/synthetic fixtures.
- [x] Request independent review and fix important findings with regression tests.
- [x] Verify no device traffic, historical evidence edits or branch changes; record results.

## Verification outcome

All 12 reactor projects pass `mvn -B -ntp verify`: 265 tests, no failures/errors
or skips. A renamed, isolated Raider copy without Controller neighbors also
passes `mvn verify` (87 tests). Independent review found a sibling-path dependency
in the first evidence-alignment test; it was reproduced there (RED) and fixed
with a self-contained, hash-checked projection of original evidence (GREEN).
No production blocker or additional important finding was reported.

Packaged CLI offline checks cover real-fixture decode (26 confirmed / 10 inferred
values), identical comparison and a synthetic gain/delay/mute change including
an unconfirmed bit in the same mute byte. Artifacts:
`/home/xla/drremote/Archiv/DSP408/parameter-model-20261031-212109/`.
Audit and RED/GREEN/build logs:
`/home/xla/drremote/.codex-backups/dsp-parameters-20261031-vizjb0d5/`.
All original evidence, branches and pre-existing edits are retained. No physical
device connection, write, deployment or service restart was used in this step.
