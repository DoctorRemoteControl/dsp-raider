# Checked execution and explicit application

[Overview](README.md) · [CLI](CLI.md) · [DSPD syntax](../DSPD-SYNTAX.md) ·
[Change preview](CHANGE-PREVIEW.md)

Run commands from `tracks-dsp-raider` after building. `check` validates without
executing; `run` executes a checked script. `plan` prepares changes offline;
`apply` explicitly writes a request against a saved baseline.

## Run a checked DSPD script

These supplied scripts need no device connection:

```bash
java -jar target/dsp-raider.jar check --in examples/offline-run.dspd
java -jar target/dsp-raider.jar run --in examples/offline-run.dspd
java -jar target/dsp-raider.jar run --in examples/offline-parameters.dspd
```

[offline-run.dspd](../examples/offline-run.dspd) exercises output, assertions,
loops and interpolation without device access or file writes.
[offline-parameters.dspd](../examples/offline-parameters.dspd) reads a saved
fixture and inspects/quantizes parameters without connecting or writing.
`examples/offline-check.dspd` is an offline **check** example containing
`connect`; executing that script would attempt a proxy connection.

`run --in script.dspd` compiles the entire source before creating a runtime.
A compile failure in any branch prevents every script function, including
connection, file writes and waits. The checked executor walks the resulting
AST, retaining real source positions for runtime errors and closing resources
on success or failure. Compilation does not prove that runtime files, device
connections or ACKs will succeed. An error after execution starts does not
undo earlier script effects.

`run` accepts the common proxy options and `--verbose`; it has no `--out` or
`--format`. Script output goes to stdout. Compilation or runtime failure returns
exit code 1 with the source position where available; invalid CLI options
return 2. Arbitrary scripts may read/write the device, write files, sleep or
pause. The separate `tracks-dsp-script-all.jar --file` interpreter remains
available and does not automatically compile/check first.

## Inspect and plan saved parameters

The checked executor supports `loadSnapshot`, `parameter`, `planChange` and
`applyChanges` plus their kebab-case aliases. The original separate interpreter
does not implement these new operations.

```dspd
let snapshot = loadSnapshot("src/test/resources/live/fir408-v0113p-20261031.json");
let gain = parameter(snapshot, "Out2", "gain");
assert(gain.status == "CONFIRMED");
print(gain.value);

let mute = parameter(snapshot, "InA", "mute");
assert(mute.status == "INFERRED");
assert(mute.value == null);

let change = planChange(snapshot, "Out2", "gain", -3.04);
assert(change.status == "READY");
assert(change.targetValue == -3);
assert(change.rounded);
print(change.payloadHex);
```

`loadSnapshot` reads and validates saved wire data. `parameter` uses the exact
firmware catalog; unconfirmed readings keep their status and raw candidate,
with null `value`. `planChange` is pure planning: READY has prepared bytes,
UNCHANGED and BLOCKED have no payload. It uses the same dB/ms units, mute 0/1
or false/true, ranges, evidence rules and quantization as CLI `plan`.
Properties are listed in the [syntax reference](../DSPD-SYNTAX.md#parameters-and-explicit-application).

## Apply a change request

Use a fresh baseline from the intended device and a request following the
[change-file schema](CHANGE-PREVIEW.md#input-file). With the proxy running:

```bash
java -jar target/dsp-raider.jar dump --out before.json
java -jar target/dsp-raider.jar plan \
  --in before.json --changes changes.json --format json --out preview.json
java -jar target/dsp-raider.jar apply \
  --in before.json --changes changes.json --format json --out application.json
```

The final command is active and can change the device. `apply` reads the
baseline and request, not a saved preview report. All requested fields must be
valid and confirmed. A single BLOCKED entry rejects the whole request before
CLI session construction, produces a failure report and returns 1. Invalid
parameter requests/options return 2; file/JSON/snapshot errors return 1.
The supplied `change-preview.json` deliberately contains a blocked FIR mute
example and is not an all-ready application request.

The shared `ChangeApplier` performs these steps:

1. Validate baseline wire blocks and plan the entire request offline. Reject
   blocked fields before live reads or writes.
2. Read a fresh full configuration snapshot and require matching firmware and
   all requested baseline parameter values, including UNCHANGED entries.
3. For each READY entry in request order, recheck requested values against the
   latest snapshot, transmit its quantized payload, validate the actual DSP ACK
   frame/payload, then read a full snapshot and verify the encoded target value.
4. Preserve each verified result and check the requested state again before
   reporting success. Stop at the first failure.

UNCHANGED entries send nothing. A nonempty all-UNCHANGED request still checks
fresh state. An empty request succeeds without live reads or writes. Mute
comparisons use the requested known bit, so unrelated bits in the stored mask
byte do not make that parameter stale.

The workflow checks firmware and requested values; it does not verify a unique
physical serial. Configuration snapshots are sequential reads and are not
atomic against another writer. There is no automatic retry or rollback.

## Results and partial failures

| Status | Meaning |
| --- | --- |
| APPLIED | write ACK and target readback verified |
| UNCHANGED | target already matched the baseline; no write issued |
| BLOCKED | missing, invalid or unconfirmed parameter evidence prevents application |
| FAILED | current entry failed; inspect `write_attempted` and details |
| NOT_ATTEMPTED | planned READY entry has not been sent, including entries after a failure |

An entry with `write_attempted: true` may have changed the device even when its
status is FAILED. A missing/bad ACK or failed readback does not establish that
nothing happened. Earlier APPLIED entries remain in the report. A later session
cleanup failure also retains the results but makes the overall result fail.

`apply` defaults to text and supports `--format json`. JSON contains
`schema_version: 1`, `type: "change_apply"`, `ok`, `device_version`, `error` and
`entries`. Each entry includes `channel`, `parameter`, `status`,
`write_attempted`, `requested_value`, `target_value`, `readback_value` and
`detail`. `readback_value` can be null. Exit code is 0 for success and 1 for an
application failure, including BLOCKED. `--out` creates a new file and never
overwrites an existing one; without it, the result is printed to stdout.

Known invalid output destinations are rejected before session creation. If
saving fails after application, the full result falls back to stdout and the
command returns 1. A cleanup error is appended to any original application
failure; it does not replace the reason or discard earlier results.

## Apply from a checked script

This example is active and requires your own baseline/request files:

```dspd
let baseline = loadSnapshot("before.json");
connect();
let applied = applyChanges(baseline, "changes.json");
print(applied.ok);
```

`applyChanges` requires an existing connection and uses the same application
service. Blocked preflight rejects the request before application. An
application failure stops the script with a source-positioned exception
containing the partial application report; its underlying `ChangeApplicationException`
retains the `ApplyResult` for Java callers. Successful results expose `ok`,
`deviceVersion`, `entries` and `error`; entries expose `status`, `readback`,
`writeAttempted` and `detail`. A readback, when present, has the same properties
as `parameter` readings.

Raw DSPD `write`/`tx`/`sendPayload`, older writer tools and the separate interpreter
retain their own active behavior. They do not automatically use this service.
Automated coverage uses injected sessions and saved/synthetic data. No physical
writes were performed while implementing or verifying this workflow; real
device validation remains a separate integration step.
