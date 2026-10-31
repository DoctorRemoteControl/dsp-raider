# CLI reference

[Overview](README.md) · [Workflows](WORKFLOWS.md) · [Development](DEVELOPMENT.md)

Run all commands from `tracks-dsp-raider` after building. The current common
entry point is `target/dsp-raider.jar`. Older imported JARs in the project root
are not updated by the build.

```bash
java -jar target/dsp-raider.jar --help
```

## Commands and options

| Command | Required options | Other options | Access |
| --- | --- | --- | --- |
| `check` | `--in script.dspd` | `--format text\|json`, `--out` | offline |
| `run` | `--in script.dspd` | proxy options | script-dependent; can be active |
| `apply` | `--in baseline.json`, `--changes changes.json` | proxy options, `--format text\|json`, `--out` | active parameter application |
| `decode` | `--in snapshot-or-capture.json` | `--lib`, `--out` | offline |
| `diff` | `--before`, `--after` | `--out` | offline |
| `plan` | `--in snapshot.json`, `--changes changes.json` | `--format text\|json`, `--out` | offline |
| `connect` | none | proxy options, `--out` | active session through proxy |
| `identify` | none | proxy options, `--out` | read identity/system |
| `dump` | none | proxy options, `--end-block`, `--out` | read configuration |
| `capture` | `--out frames.json` | `--stream-host`, `--stream-port`, `--duration-ms` | passive stream only |

Proxy options are `--stream-host` and `--stream-port` (default
`127.0.0.1:19081`), `--control-host` and `--control-port` (default
`127.0.0.1:19082`), plus `--verbose`. They apply to `connect`, `identify`, `dump`, `run` and
`apply`. `capture` uses only the two stream options.
The [proxy](https://github.com/DoctorRemoteControl/dsp408-proxy#start-for-raider) must have its
corresponding listeners explicitly enabled.

Specify options as `--name value`. Unknown, duplicate or command-inappropriate
options are rejected. Ports range from 1..65535. Numeric CLI options accept
decimal and `0x` hexadecimal numbers. `--help` or `-h` displays help without
operating the device.

## Check, analyze and plan offline

These examples use only supplied files:

```bash
java -jar target/dsp-raider.jar check --in examples/offline-check.dspd
java -jar target/dsp-raider.jar check --in examples/offline-check.dspd --format json
java -jar target/dsp-raider.jar decode --in src/test/resources/live/fir408-v0113p-20261031.json
java -jar target/dsp-raider.jar diff \
  --before src/test/resources/live/fir408-v0113p-20261031.json \
  --after src/test/resources/live/fir408-v0113p-20261031.json
java -jar target/dsp-raider.jar plan \
  --in src/test/resources/live/fir408-v0113p-20261031.json \
  --changes examples/change-preview.json --format json
```

Comparing the same file produces no change. The preview contains READY,
UNCHANGED and BLOCKED examples and sends nothing.

`check` checks the complete source, including unexecuted branches. The report
contains source positions. Functions are not invoked even when the script
contains `connect`, `write`, `sleep` or `saveText`.
Limits and JSON format: [DSPD check](DSPD-CHECK.md).

`decode` recognizes a capture by its `type: "capture"` field; otherwise it
expects a successful snapshot. The detailed FIR408 V0113P decoder requires
complete configuration memory. Other profiles receive channel parameters where
proven, plus a raw block overview. `diff` can also reveal missing, added and
differently sized blocks. Semantic comparison requires the same documented
firmware profile.

For captures, an explicitly selected field library supplements interpretation
of write commands. Without `--lib`, output remains raw frame analysis:

```bash
java -jar target/dsp-raider.jar decode \
  --in frames.json --lib DspLib-408-fir.json --out capture-report.md
```

`--lib` does not enable automatic device detection or modify the library.
The [change preview](CHANGE-PREVIEW.md) describes change request format,
rounding and evidence status.

## Execute a checked script or apply changes

```bash
java -jar target/dsp-raider.jar run --in examples/offline-run.dspd
java -jar target/dsp-raider.jar run --in examples/offline-parameters.dspd
```

`run` compiles the entire source before creating its runtime. Any compile error
prevents script functions; a later runtime failure retains its source position
but does not undo previous effects. `run` prints script output to stdout and
accepts no `--out` or `--format`. The two examples above are offline; other
scripts can perform real device operations, file writes and waits.
`examples/offline-check.dspd` contains `connect` and is intended for `check`,
not as an offline execution example.

With your own fresh baseline/request and a running proxy, explicit `apply` is:

```bash
java -jar target/dsp-raider.jar apply \
  --in before.json --changes changes.json --format json --out application.json
```

It rejects any blocked field before creating a CLI session, checks fresh firmware
and every requested baseline value, then validates ACK and full-snapshot readback
for each write. It stops at the first failure and retains partial results.
UNCHANGED entries write nothing; an empty request succeeds without live reads
or writes. Firmware/value checks do not identify a unique physical device, and
snapshots are not atomic against other writers. There is no retry or rollback.
See [Execution](EXECUTION.md) for the result schema, statuses, failure effects
and the equivalent checked DSPD `applyChanges` function.

## Read the device and record the stream

The following commands require a running proxy. `connect` establishes or reuses
a ready session. `identify` and `dump` read the handshake, identity and system
information; they do not reset an existing session or write parameters or
presets.

```bash
java -jar target/dsp-raider.jar connect
java -jar target/dsp-raider.jar identify
java -jar target/dsp-raider.jar dump --out snapshot.json
java -jar target/dsp-raider.jar capture --out frames.json --duration-ms 5000
```

| Detected model | Default `dump` range |
| --- | --- |
| DSP408 | 0x00..0x1C |
| FIR408 | 0x00..0x1F |
| unknown | explicit `--end-block` required |

`--end-block` always reads from block 0 and allows 0..31 inclusive. Model
detection for a dump and exact firmware approval for a parameter catalog are
separate decisions. A readable model does not by itself have confirmed
gain/mute/delay mappings. The FIR408 reference dump contains 31 blocks of
50 data bytes and a final block of 6 data bytes.

Snapshots are validated against their wire bytes: checksum, payload, command,
block index and any metadata must agree. A JSON flag `checksum_ok: true` does
not replace this validation. A failed online dump outputs its partial state
with `ok: false`; exit code 1 is retained. Such error documents are not valid
successful offline inputs.

`capture` sends no DSP commands and opens no control channel. Duration defaults
to 5000 ms, with 1..60000 ms allowed. An empty capture is valid when the stream
stays open but has no traffic. A premature disconnect or invalid frame is an
error. See [Workflows](WORKFLOWS.md) for GUI setup details.

## Output and exit codes

Without `--out`, the report appears on stdout. With `--out`, a new UTF-8 file is
written, missing parent directories are created and stdout reports its location.
Existing files are not replaced. This applies to common CLI report output;
`run` has no `--out`, and older tools or DSPD save functions retain their own
file behavior.

For `apply`, a failure to save after device work preserves the complete report
on stdout and returns 1. Cleanup failures preserve the original application
reason as well as the cleanup error. See [Execution](EXECUTION.md#results-and-partial-failures).

| Exit code | Meaning |
| --- | --- |
| 0 | command succeeded; for `plan`, even with BLOCKED entries |
| 1 | execution/file/connection failure, static DSPD error, or failed/blocked `apply` |
| 2 | invalid CLI options or invalid `plan`/`apply` parameter request |

An invalid script can still save a `check` report with exit code 1. A failed
`dump` can also save partial state. Automation must therefore consider both
the exit code and report contents. Failed applications can also save their
partial result; `write_attempted: true` may indicate effects even when FAILED.
`check`, `plan` and `apply` offer `--format text|json`; `dump`/`identify`
produce JSON, `decode`/`diff` produce text, `capture` produces JSON and `run`
prints script output.

## Existing separate tools

Maven produces six executable Raider JARs in `target/`:

| JAR | Entry point / behavior |
| --- | --- |
| `dsp-raider.jar` | common CLI documented here |
| `tracks-dsp-reader-all.jar` | existing reader; resets the session, default range 0x00..0x1C |
| `tracks-dsp-writer-all.jar` | active writer; can send DSP commands |
| `tracks-dsp-script-all.jar` | existing DSPD interpreter; `--file` executes the script |
| `tracks-dsp-emulator-all.jar` | separate meter emulator |
| `tracks-dsp-gui-write-decode-all.jar` | continuous passive GUI write decoder |

Help for these separate tools explains their own options:

```bash
java -jar target/tracks-dsp-reader-all.jar --help
java -jar target/tracks-dsp-writer-all.jar --help
java -jar target/tracks-dsp-script-all.jar --help
java -jar target/tracks-dsp-gui-write-decode-all.jar --help
java -cp target/dsp-raider.jar de.drremote.dsp408.raw.DspRawWriteTool --help
```

The raw writer has no separate shaded JAR entry point and speaks binary TCP
directly. `ReadTool` and `WriteTool` remain compatibility facades for Java
callers. New offline workflows start with the common CLI.

The common CLI provides checked `run` and explicit `apply`; there is no
`compile` subcommand or general bytecode compiler. The separate interpreter
performs no automatic compiler check, and existing writers use no automatic
`plan`/`apply` workflow.
