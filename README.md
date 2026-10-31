# DSP Raider

Java tool for **t.racks DSP408 and FIR408**: read device state, investigate
protocol traffic, compare saved snapshots, prepare changes, execute checked
DSPD scripts and explicitly apply confirmed parameter changes.

## Quick start without a DSP

Requirements: JDK 21 and Maven. Build in the shared
[DSP408 workspace](../README.md):

```bash
mvn -B -ntp verify
```

Then, from `tracks-dsp-raider`:

```bash
java -jar target/dsp-raider.jar --help
java -jar target/dsp-raider.jar check --in examples/offline-run.dspd
java -jar target/dsp-raider.jar run --in examples/offline-run.dspd
java -jar target/dsp-raider.jar run --in examples/offline-parameters.dspd
java -jar target/dsp-raider.jar decode --in src/test/resources/live/fir408-v0113p-20261031.json
java -jar target/dsp-raider.jar plan \
  --in src/test/resources/live/fir408-v0113p-20261031.json \
  --changes examples/change-preview.json
```

The reference dump contains real, preserved FIR408 V0113P data. The requested
changes file is a simulated example. These commands open no device connection.
For an individual build, install `../dsp408-protocol` locally first; see
[Development](docs/DEVELOPMENT.md).

## Which command should I use?

| Command | Purpose | Device access |
| --- | --- | --- |
| `check` | Statically check a complete DSPD script | offline; no execution |
| `run` | Compile completely, then execute the checked AST | depends on script; can read/write device or files |
| `apply` | Explicitly apply a change request with fresh-state, ACK and readback checks | active through proxy |
| `decode` | Analyze a snapshot or capture | offline |
| `diff` | Compare two snapshots as parameters and raw bytes | offline |
| `plan` | Prepare gain/mute/delay changes with rounding and evidence status | offline; no transmission |
| `connect` | Check/establish readiness of the proxy's device session | through proxy |
| `identify` | Read identity and system information | through proxy |
| `dump` | Read the detected model's configuration | through proxy |
| `capture` | Record a bounded section of the event stream | passive stream access only |

All options, file formats and exit codes are documented in the
[CLI reference](docs/CLI.md). Default read endpoints are `127.0.0.1:19081`
(stream) and `127.0.0.1:19082` (control). The
[proxy](../tracks-dsp-proxy/README.md#start-for-raider) must have both listeners
enabled. Reading also works without the original Windows software.

## Checking and running DSPD

```bash
java -jar target/dsp-raider.jar check --in examples/offline-run.dspd --format json
```

`check` uses a lexer, parser, immutable syntax tree and conservative static
analysis. Messages retain real lines and columns. `run --in script.dspd` uses
that same compiler before executing the checked AST. Any compile error prevents
all script functions, including connection, file writes and waits. Runtime errors
retain source positions; script output goes to stdout. The new
[parameter functions](DSPD-SYNTAX.md#parameters-and-explicit-application) read
saved snapshots, inspect evidence and prepare or explicitly apply changes.

The existing runner remains a separate execution path:

```bash
java -jar target/tracks-dsp-script-all.jar --help
```

Its `--file` option actually executes script functions. A successful check does
not execute the script, and the runner does not yet require it automatically.
The [syntax reference](DSPD-SYNTAX.md) describes modern and legacy forms;
[DSPD check](docs/DSPD-CHECK.md) explains static limits and five known errors
in the preserved example scripts.

## Parameters and change planning

Gain, mute and delay share definitions for all twelve channels. Catalogs apply
to the documented versions **DSP408 V0104** and **FIR408 V0113P**. Known write
formats and confirmed read positions have separate evidence. Unconfirmed,
missing and invalid values remain explicitly marked.

For FIR408, all twelve gain and delay positions are confirmed. For mute, only
InD and Out8 have explicit FIR-specific evidence so far. `plan` produces no
payload for blocked or already unchanged fields.

- [Parameters, ranges and sources](docs/PARAMETERS.md)
- [Change file, preview and JSON schema](docs/CHANGE-PREVIEW.md)

Use explicit [`apply`](docs/EXECUTION.md#apply-a-change-request) to transmit a
request against a saved baseline. It rechecks firmware and all requested values
against fresh state, then validates ACK and readback for each change. A failed
write may already have effects; reports retain partial results and there is no
retry or rollback. Existing writer tools and raw DSPD `write` keep their own
behavior and do not automatically use this workflow.

## Documentation

| Topic | Document |
| --- | --- |
| Getting started and reading order | [Documentation overview](docs/README.md) |
| Common CLI, older tools, output and exit codes | [CLI](docs/CLI.md) |
| Reading, capture and protocol investigation | [Workflows](docs/WORKFLOWS.md) |
| Build, packages, tests and extensions | [Development](docs/DEVELOPMENT.md) |
| System boundaries and shared library | [Architecture](../ARCHITECTURE.md) |
| Checked scripts, explicit apply and partial results | [Execution](docs/EXECUTION.md) |
| Implemented features, open work, next stages | [Roadmap](docs/ROADMAP.md) |
| Contribution, source dependencies and verification | [Contributing](CONTRIBUTING.md) |

[Additional script examples](script-example.md) and
[DSP408 protocol notes](DSPINFO.md) remain as older detailed references.
JSON libraries, original `.dspd` scripts and captures under `out/` are evidence.
An assumption alone does not turn them into confirmed mappings.

## Status

The reference capture from 2026-10-31 contains 32 FIR408 blocks with 1556 data
bytes. The [fixture](src/test/resources/live/fir408-v0113p-20261031.json) and
[provenance record](src/test/resources/live/fir408-v0113p-20261031.provenance.json)
are in the repository. They do not represent the current live state.

The tool supports protocol development. Direct value comparison with Windows
Editor V1.12 and further FIR mute confirmations remain open. The supplied
firmware updater was not run during this work. The current project contains
no separate license text or hosted CI workflow. [Contributing](CONTRIBUTING.md)
describes the local verification script and required protocol source checkout.
