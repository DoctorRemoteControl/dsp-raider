# Change preview implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Preview parameter changes against saved snapshots with visible quantization and evidence-based blocking.

**Architecture:** Pure immutable planning objects consume the existing parameter catalog and BlockData. JSON/text adapters and the existing CLI expose the result; planning has no session dependency.

**Tech Stack:** Java 21, existing Maven/JUnit/Jackson. No additional dependencies.

**Spec:** `../specs/2026-10-31-change-preview-design.md`

## Global constraints

- Offline only: no apply command, physical DSP access, deployment or restarts.
- Exact evidenced catalogs; preserve unknown/inferred/invalid distinctions.
- Gain -60..12 dB, Delay 0..680 ms, Mute 0/1; shared codecs determine rounding.
- Preserve original JSON, captures, branches and pre-existing edits. Keep changes uncommitted.
- Canonical checkout with before-snapshot, as authorized in the ongoing work.

## Review focus

- Quantized targets equal to baseline must not produce commands (Task 1 test).
- Unknown/inferred/invalid baseline, even with known write command, must block (Task 1 test).
- Mute compares one bit, not the whole stored mask (Task 1 test).
- Typos, JSON duplicate keys/pairs and mismatched firmware cannot be silently accepted (Tasks 1/2 tests).
- Offline command must never create a session or overwrite an existing output (Task 2 test).

## Task 1: Pure planning core

Create `planning/{ParameterChange,ChangeRequest,ChangePlan,ChangePlanner}.java`.
Test `planning/ChangePlannerTest.java` against the real fixture plus synthetic
missing/invalid/mute-mask cases.

Interfaces: `ParameterChange(int channelIndex, ParameterKind kind, double value)`
validates and exposes `channel()`, `writeRaw()`, `targetValue()`.
`ChangeRequest(String deviceVersion,List<ParameterChange> changes)` validates
version, max36 entries and duplicate pairs. `ChangePlanner.plan(String version,
BlockData baseline,ChangeRequest request)` returns immutable `ChangePlan` with
entries containing change, before reading, READY/UNCHANGED/BLOCKED and reason.
Only READY entries expose `payloadHex()`; counts are derived from entries.

- [x] Add tests for golden payloads, order, quantization/no-op, mute mask, blocked states, invalid requests and version mismatch.
- [x] Run `mvn -B -ntp -Dtest=ChangePlannerTest test`; expected RED because planning API is absent.
- [x] Implement the four focused pure classes, using existing codecs/catalogs only.
- [x] Rerun the same command; expected GREEN.

## Task 2: Strict JSON, preview reporting and CLI

Create `planning/{ChangeRequestFiles,ChangePlanReport}.java` and tests.
`ChangeRequestFiles.read(Path)` returns ChangeRequest with strict schema/key
validation. `ChangePlanReport.text(ChangePlan)` / `json(ChangePlan)` return
preview reports; JSON schema_version=1, type=change_plan, preview_only=true.
Modify `snapshot/Snapshot.java` with immutable `blockData()` projection, reuse
it in SnapshotParameters. Modify `tool/DspRaiderCli.java` to add offline plan
dispatch/options/help and present-block size checks.

- [x] Add parser tests for valid numeric/boolean requests, malformed values, duplicate keys, unknown fields and duplicate pairs.
- [x] Add CLI tests using a session factory that throws if touched; assert text/JSON, precision, blocked entries, corrupt/truncated snapshots, overwrite/option/version failures.
- [x] Run new tests; expected RED before implementation.
- [x] Implement parser, report and offline CLI integration; rerun tests, expected GREEN.

## Task 3: Documentation, artifacts and review

- [x] Add a synthetic request example and document schema, statuses, quantization and preview-only scope.
- [x] Run workspace `mvn -B -ntp verify`, expected all12 projects GREEN; verify renamed standalone Raider too.
- [x] Run packaged CLI text/JSON previews using saved reference; assert outputs and input hashes, retain artifacts in Archiv/DSP408.
- [x] Independent read-only review against this task's before snapshot; fix Important/Critical findings with regression tests.
- [x] Record final verification and unchanged original evidence/refs in task ledger, report actual scope.

## Outcome

All12 workspace projects pass verification (316 tests); renamed standalone
Raider passes all138 of its tests and packaging. Packaged text/JSON preview
matches the golden payloads, preserves input hashes and reports three READY,
one UNCHANGED and one BLOCKED example entry. Independent review had no Critical
or Important findings. Its minor text-evidence omission was reproduced RED and
fixed GREEN; no second review was needed.

Ruling: exact gain decimal anchors exposed cancellation noise in the old shared
raw decoder. An algebraically equivalent integer-subtraction-before-division
formula removes false rounding reports without changing encoding or ranges.

Artifacts: `/home/xla/drremote/Archiv/DSP408/change-preview-20261031-215317/`
(final reports: `preview-final.txt` and `preview-final.json`).
Ledger, before-tree, review and build logs:
`/home/xla/drremote/.codex-backups/dsp-change-plan-20261031-d8mhnpyx/`.
Original evidence, branches and existing edits remain intact. This step adds
offline planning only; no physical DSP operation or execution API.
