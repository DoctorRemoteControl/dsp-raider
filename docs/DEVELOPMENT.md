# Development

[Overview](README.md) · [Architecture](../../ARCHITECTURE.md) · [Roadmap](ROADMAP.md)

## Building

Requirements are JDK 21 and Maven. From the shared DSP408 workspace, one level
above `tracks-dsp-raider`:

```bash
mvn -B -ntp verify
```

The reactor builds twelve Maven projects, including controller modules. The
shared POM is an aggregator and does not replace their existing parents. It
changes no running services and installs no Karaf features.

For individual builds of the independent repositories in the DSP408 workspace:

```bash
mvn -B -ntp -f dsp408-protocol/pom.xml install
mvn -B -ntp -f tracks-dsp-raider/pom.xml verify
mvn -B -ntp -f tracks-dsp-proxy/pom.xml verify
mvn -B -ntp -f DSP408Controller/dsp408-parent/pom.xml verify
```

The last three builds are needed only for the applications you require.
`verify` alone in the protocol project does not make its artifact available to
later independent Maven invocations; that requires `install`.

The [CLI reference](CLI.md#existing-separate-tools) describes the six executable
Raider JARs and their entry classes. New builds are in `target/`. Historical
JARs in the project root are not reliable outputs of the current source.

For a standalone Raider checkout, [Contributing](../CONTRIBUTING.md) documents
`bash ci/verify.sh`. It requires a sibling `dsp408-protocol` source checkout or
`DSP408_PROTOCOL_DIR`, installs the shared library and then verifies Raider.
The protocol artifact is not published by this repository. There is no hosted
CI workflow or license file configured yet. Keep focused changes and explicit
staging consistent with that contribution policy; original evidence remains
unchanged.

## Packages and data flow

| Area under `de.drremote.dsp408` | Responsibility |
| --- | --- |
| `tool` | common CLI and existing tool entry points/facades |
| `session` | replaceable session contract and factory |
| `proxy` | TCP/JSON adapter for stream and control |
| `dump`, `model` | read workflow and immutable responses/identities |
| `snapshot` | validate wire data, decode offline and compare |
| `parameters` | pure gain/mute/delay definitions with firmware and evidence |
| `planning` | pure change requests, rounding, status and previews |
| `capture`, `decode` | passive capture and write traffic analysis |
| `dspd` | lexer, AST, parser, static analysis, check reports and builtin catalog |
| `script` | checked AST executor, original interpreter, values and function execution |
| `execution` | explicit parameter application, fresh-state checks, ACK/readback and partial results |
| `fir` | pure memory analysis, separate diagnostics and explicit test filter uploads |
| `raw`, `emu` | direct TCP tools and emulator |

Shared framing lives outside this application in
[`dsp408-protocol`](../../dsp408-protocol/README.md). Profiles, Jackson, CLI
options and sessions do not belong in that library. Raider uses Jackson for
structured input/output and also retains existing project-specific JSON helpers.

`DspSessionFactory` enables tests without a real DSP. Parameters and change
planning require no session. The compiler produces a checked program for
`CheckedDspScriptEngine`/`DspdExecutor`; the old interpreter continues to use
its own execution path without an automatic check. `ScriptParameters` connects
the checked executor to saved snapshots, pure planning and explicit
`ChangeApplier` application. The Raider parameter model has not yet been adopted
by the controller's parameter classes.

| Class | Extension boundary |
| --- | --- |
| `DspdCompiler`, `DspdBuiltins`, `DspdAnalyzer` | complete-source checking, names/aliases/signatures and static rules |
| `CheckedDspScriptEngine`, `DspdExecutor` | compile before runtime creation; execute the checked AST with source positions |
| `ScriptFunctions`, `ScriptParameters`, `ScriptValueResolver` | raw functions, checked parameter operations and exposed result properties |
| `ChangePlanner` | pure evidence-aware comparison and quantization |
| `ChangeApplier` | explicit active workflow with an injectable snapshot reader |
| `ApplyResult`, `ApplyReport`, `ChangeApplicationException` | immutable progress, text/JSON reports and script failure results |

## Existing Java facades

Public facades remain available for existing callers:

```java
String json = ReadTool.dumpAllJson(
    streamHost, streamPort, controlHost, controlPort, pin, verbose);

ProxyResponse response = WriteTool.sendPayloadOnce(
    streamHost, streamPort, controlHost, controlPort, pin, verbose,
    payload, expectedCommand, strictResponse, label);

ScriptTool.runScriptFile(
    streamHost, streamPort, controlHost, controlPort, verbose, file);
ScriptTool.runScriptText(
    streamHost, streamPort, controlHost, controlPort, verbose, scriptText);
```

These are active calls from `de.drremote.dsp408.tool`, not offline examples:
`ReadTool` uses the old dump workflow with a session reset; `WriteTool` prepares
the existing session and sends the payload. `ScriptTool` runs the existing
interpreter. New checked callers can use
`CheckedDspScriptEngine.execute(path, output)` or
`executeText(sourceName, source, output)`; these compile the complete source
before runtime construction. For online integration, `DspSession` remains the
replaceable boundary. [Execution](EXECUTION.md) describes the separate
`ChangeApplier` workflow and its failure results.

## Verification

Workspace `verify` includes existing protocol, Raider, proxy and controller
tests. They use preserved or synthetic data and local test connections without
operating the physical DSP.

Targeted Raider tests from the Raider project:

```bash
mvn -B -ntp '-Dtest=Dspd*Test' test
mvn -B -ntp -Dtest=PackageDependenciesTest test
mvn -B -ntp -Dtest=CheckedDspScriptEngineTest,ScriptParametersTest,ChangeApplierTest,ChangeApplyCliTest test
```

The library must be resolvable as for an individual build. Examples of the
boundaries covered:

- Framing: fragmented/batched frames, checksums, `10 03` in the payload and
  bounded buffering; raw client with real local socket timeouts.
- Sessions: replaceable adapter, no unnecessary resets in the new reader and
  resource cleanup after failures; package cycles are checked.
- Snapshots/parameters: wire bytes rather than trusted JSON flags, complete raw
  diffs, bit masks, firmware binding and source evidence.
- Previews: strict request format, quantization, no commands for unconfirmed or
  missing values or already identical raw values.
- DSPD: positions, complete syntax, variable flow, numeric argument limits,
  whole-source checks before effects, checked AST execution, loop/`_` behavior,
  runtime causes and compatibility with the preserved script corpus.
- Application: blocked requests before live work, stale firmware/baselines,
  unchanged targets, quantized payloads, validated ACKs, target readback,
  partial failures, interruption and cleanup failures using injected sessions.

Live verification is a separate step. A successful Maven build does not replace
it. For text-only changes, checking the affected commands, links and
documentation statements is sufficient; no DSP connections need to be started.

### Existing direct FIR verifiers

The classes
[`Fir408PhaseReadVerifier`](../src/main/java/de/drremote/dsp408/raw/Fir408PhaseReadVerifier.java)
and
[`Fir408GateReadVerifier`](../src/main/java/de/drremote/dsp408/raw/Fir408GateReadVerifier.java)
are active hardware tools. The phase verifier mutes all twelve channels and
sets phase to 0°, checks 0°/180° and ends with mute/phase 0° again. Its
configuration comparison refers to this already established baseline, not to
arbitrary previously configured phases or mutes. The gate verifier checks the
documented inputs InA/InD, restores their gate tuples and leaves the channels
muted. Neither verifier sends an unmute.

Their reports default to
`out/fir/fir408-phase-readback-verification` and
`out/fir/fir408-gate-readback-verification`, respectively. The existence of
these tools does not confirm adoption of phase or gate in the new parameter
model.

## Extensions

**New DSPD function:** consider names/aliases and signatures in `DspdBuiltins`,
runtime behavior in `ScriptFunctions` or checked-only `ScriptParameters`,
exposed properties in `ScriptValueResolver`, static argument rules in the
analyzer, and syntax/diagnostic/execution compatibility together. Keep AST
execution in `DspdExecutor`; do not reparse statements. The parser/compiler
must create no runtime or device connection. Update the
[syntax reference](../DSPD-SYNTAX.md), [compiler limits](DSPD-CHECK.md) and
[execution guide](EXECUTION.md). The original interpreter has its own behavior;
new checked-only operations must be described as such.

**New parameter or firmware:** establish write format and read position
separately. Record raw range, unit, rounding, bit masks and evidence status.
Unverified positions remain INFERRED/UNKNOWN. Do not treat versions as generally
equivalent. [PARAMETERS.md](PARAMETERS.md) describes source pointers and hash
evidence; do not alter original sources to fit a test result.

**New online workflow:** separate parameter calculations from transport and file
storage, inject the session through the existing contract and check its error
and close paths. Parameter application belongs in `ChangeApplier`, with shared
planner/codecs, current-state validation and explicit partial results. A proxy
send confirmation is insufficient: validate wire ACK and target readback. Keep
`writeAttempted` accurate even after failure and do not invent rollback. Online
functions must be recognizable in the CLI/documentation as active or reading
workflows.

## Repository and documentation maintenance

Raider, proxy, protocol library and controller retain separate Git histories.
The workspace file `PROJECTS.lock.json` lists the last recorded **committed**
component revisions. It captures no uncommitted changes and is not a submodule
configuration. After coordinated component commits, it can be updated to their
actual revisions.

The short project README is the starting point. Shared CLI options are in
`CLI.md`, guides in `WORKFLOWS.md`, and current language rules in
`DSPD-SYNTAX.md`. Historical protocol notes, original scripts, libraries and
captures remain source material. Plans under `docs/superpowers/` are development
records tied to their time and do not replace a current manual.

Reproducible fixtures, including provenance, belong in `src/test/resources`.
Local runtime data and verification archives are stored outside the source
according to the workspace layout. Successful analysis alone does not turn a
raw file into confirmed protocol evidence.
