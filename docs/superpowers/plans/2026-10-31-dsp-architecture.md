# DSP architecture implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Share wire protocol handling and separate Raider scripting, analysis and connection responsibilities.

**Architecture:** A dependency-free protocol library is consumed by independent
Raider, proxy and OSGi controller builds. Existing APIs are retained as adapters;
Raider device operations become injectable through a session interface.

**Tech Stack:** Java 21, Maven, JUnit 5, existing Jackson and Karaf/OSGi.

**Spec:** `../specs/2026-10-31-dsp-architecture-design.md`

## Global constraints

- Preserve Java 21, existing CLI entry points, DSPD syntax and JSON/capture data.
- Preserve independent repositories, branch references and existing edits.
- No device access, deployment, publication or runtime restart.
- Shared library: `de.drremote:dsp408-protocol:0.1.0-SNAPSHOT`.

## Review focus

- Fragmentation at every frame byte, including split start markers, retains data.
- Literal `10 03` in payloads does not terminate a frame early.
- Invalid checksums stay observable to diagnostics, and strict APIs reject them.
- Existing DSPD functions and CLI entry points still work after delegation.
- The shared dependency is available in shaded JARs and Karaf's feature graph.

## Task 1: Common protocol and reproducible workspace build

**Files:** Create `DSP408/pom.xml`, `dsp408-protocol/pom.xml`,
`dsp408-protocol/src/{main,test}/java/de/drremote/dsp408/protocol/*`.

**Interfaces:** `DspFrameCodec.encode(byte[])`, `decode(byte[])`,
`readFrame(InputStream)`; `DspFrameDecoder.accept(byte[])` returning decoded
frames; `DspPayloads.command(byte[])`, `readBlockIndex(byte[])`, `commandHex(Integer)`.

- [x] Add JUnit wire-vector, checksum, truncated/fragmented input and embedded-marker tests.
- [x] Run them before implementation and confirm missing protocol classes fail.
- [x] Implement pure framing, bounded stream accumulation and payload helpers.
- [x] Run `mvn -f dsp408-protocol/pom.xml verify`; expect zero failures.

## Task 2: Migrate every framing consumer

**Files:** Raider raw/emulator codecs and raw socket reader; proxy frame codec
and traffic logger; controller frame codec, core POM, parent version property
and feature descriptor; consumer POM dependency declarations.

**Interfaces:** Preserve existing codec names/signatures, delegate to Task 1.

- [x] Add adapter regression tests for embedded markers, fragmented frames,
  corrupt checksum and consecutive frames; observe failures on old adapters.
- [x] Replace duplicate implementations with compatibility adapters.
- [x] Verify all projects through the workspace reactor; inspect bundle metadata.

## Task 3: Session boundary and script compatibility

**Files:** Raider `session/DspSession.java`, `session/DspSessionFactory.java`,
`proxy/ProxyClient.java`, `script/ScriptRuntime.java`, `script/DspScriptEngine.java`,
`script/ScriptFunctions.java` and session/script regression tests.

**Interfaces:** DspSession retains current operations used by script/analysis;
DspSessionFactory opens a session from the existing endpoint values. Keep the
current engine constructor and add an overload accepting that factory.

- [x] Test a full DSPD read/handshake/close workflow using an in-memory session.
- [x] Observe the new injection API is absent before implementation.
- [x] Introduce the interface, factory and constructor; keep default proxy wiring.
- [x] Test connection failures and cleanup, plus legacy/function DSPD forms.

## Task 4: Extract FIR analysis and remove package cycles

**Files:** Raider FIR analysis services; script builtin adapters; model and
protocol utility imports; CLI/application facade and architecture regression test.

**Interfaces:** FIR analysis accepts captured bytes and DspSession, never script
tokens or CLI entry points. Payload metadata is provided by Task 1.

- [x] Add regression tests for FIR report values/short input and an acyclic
  package dependency graph; observe current dependency cycles fail the latter.
- [x] Extract device analysis/diagnostics from ScriptFunctions, preserve script names.
- [x] Remove script-to-CLI and model-to-utility back references.
- [x] Run all Raider tests and the whole workspace test suite.

## Task 5: Documentation, packaging and review

**Files:** `DSP408/ARCHITECTURE.md`, workspace README and affected project READMEs.

- [x] Document dependency direction, independent builds, compatibility and adapters.
- [x] Run `mvn -f DSP408/pom.xml verify` and CLI `--help` smoke checks.
- [x] Inspect shaded JAR contents, bundle manifests and generated features.
- [x] Request an independent code review, fix significant findings with regressions.
- [x] Verify protocol JSON/capture files and branch references against the snapshot.

No commits include the user's pre-existing changes. The complete changes remain
reviewable in the requested working directories.
