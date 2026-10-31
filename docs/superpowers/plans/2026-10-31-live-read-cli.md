# Live reference and common CLI implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Preserve a real FIR408 reference, test against it and provide one CLI for the existing read/analysis workflows.

**Architecture:** Reuse DspSession and the proxy; put snapshot reading/validation and offline analysis beneath a thin CLI. Keep all legacy entry points.

**Tech Stack:** Java 21, Maven, existing Jackson and JUnit 5.

**Spec:** `../specs/2026-10-31-live-read-cli-design.md`

## Global constraints

- Preserve repositories, branches, local edits and historical protocol evidence.
- No parameter/preset/FIR writes during live tests; no reset of an existing session in new read commands.
- Preserve legacy CLIs and snapshot JSON compatibility.
- Record GUI comparison as unperformed until the Windows editor is available.

## Review focus

- Malformed/corrupt raw frames cannot become valid snapshots through trusted JSON metadata.
- Missing/duplicate blocks and mismatched indices cannot shift interpreted memory.
- Added/removed/trailing bytes appear in diff reports.
- Capture exits after its deadline even when the stream is idle or continuously busy.
- Help, offline commands and argument errors do not open device connections.

## Task 1: Read-only snapshot service and real fixture

Files: `dump/ReadOnlySnapshotReader.java`, snapshot model/validation classes,
`src/test/resources/live/fir408-v0113p-20261031.json`, corresponding tests.

- [x] Preserve live handshake and two configuration reads, plus provenance/hashes.
- [x] Add failing tests for session reuse, profile block range, strict response validation and real fixture parsing.
- [x] Implement the reader using only DspSession read/handshake operations, retaining partial error context.
- [x] Run targeted tests; verify checksums/block assembly against live evidence.

## Task 2: Offline decode/diff and common CLI

Files: `snapshot/*`, `tool/DspRaiderCli.java`, CLI/snapshot tests and POM shade entry.

- [x] Add failing tests for strict file validation, offline decode, block/length differences and CLI argument handling.
- [x] Implement connect/identify/dump/decode/diff/capture with existing session and sniffer adapters.
- [x] Verify idle capture and legacy CLI help compatibility; build the new runnable JAR.

## Task 3: Live verification, documentation and review

- [x] Use the new entry point for the real-device identify/dump/decode/diff/capture flow.
- [x] Audit every outbound frame; compare the new dump against the untouched reference.
- [x] Run the complete workspace Maven verify and an independent code review.
- [x] Document commands, reference artifacts, results and the Windows editor comparison limit.

Final result: all planned source/build/live-read work is verified (231 tests).
The direct Windows editor screen comparison remains unperformed because its
runtime is unavailable; see the archived live report for this explicit limit.
