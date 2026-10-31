# Workflows

[Overview](README.md) · [CLI reference](CLI.md) · [Parameters and evidence](PARAMETERS.md)

Run commands from the Raider project. Output files such as `before.json` and
`frames.json` must be new; use a separate directory or filename for each
experiment. In the `drremote` workspace, local runtime data belongs in
`Betriebsdaten/DSP408` and historical reports in `Archiv/DSP408`.

## 1. Start without hardware

After [building](DEVELOPMENT.md#building), use the supplied inputs to explore
the compiler, decoder and change planning:

```bash
java -jar target/dsp-raider.jar check --in examples/offline-run.dspd
java -jar target/dsp-raider.jar run --in examples/offline-run.dspd
java -jar target/dsp-raider.jar run --in examples/offline-parameters.dspd
java -jar target/dsp-raider.jar decode --in src/test/resources/live/fir408-v0113p-20261031.json
java -jar target/dsp-raider.jar diff \
  --before src/test/resources/live/fir408-v0113p-20261031.json \
  --after src/test/resources/live/fir408-v0113p-20261031.json
java -jar target/dsp-raider.jar plan \
  --in src/test/resources/live/fir408-v0113p-20261031.json \
  --changes examples/change-preview.json
```

Expected results: a valid check, a FIR408 V0113P report, no raw change when
comparing the same file, checked-script output and a preview with three READY
entries, one UNCHANGED and one BLOCKED. The example requests are simulated. They
are not sent. `offline-run.dspd` uses no device or file writes;
`offline-parameters.dspd` only reads the saved fixture and plans changes. The
older `offline-check.dspd` contains `connect`; check it without executing when
no connection is intended.

## 2. Read current state

Start the proxy in its own open terminal with stream and control enabled:
[complete startup command](../../tracks-dsp-proxy/README.md#start-for-raider).
The target in the local reference setup is `192.168.0.166:9761`.

Then, in a second terminal in the Raider project:

```bash
java -jar target/dsp-raider.jar connect
java -jar target/dsp-raider.jar identify --out identity.json
java -jar target/dsp-raider.jar dump --out before.json
java -jar target/dsp-raider.jar decode --in before.json --out before-report.md
```

The Windows software does not need to run. Control establishes a device session
as needed; a ready session is reused. The dump chooses its block range from the
detected model. For an unknown model, it requires an explicit `--end-block`;
that still does not confirm any parameter mapping.

A saved reference dump shows state at the time it was captured. Later change
decisions require a fresh dump from the relevant device. Partial failures with
`ok: false` remain diagnostic evidence.

## 3. Record traffic passively

A capture reads only the event stream. It initiates neither a dump nor a device
action:

```bash
java -jar target/dsp-raider.jar capture --out frames.json --duration-ms 15000
java -jar target/dsp-raider.jar decode \
  --in frames.json --lib DspLib-408-fir.json --out capture-report.md
```

For DSP408 traffic, select the appropriate `DspLib-408.json` instead. Explicit
library selection prevents a mapping from being adopted solely because of a
filename or assumed device family.

An empty capture can be correct when nobody communicates with the DSP during
the time window. The proxy must stay open throughout recording.

## 4. Compare with the original Windows software

The original software is supplied as a Windows EXE. Direct GUI value comparison
and running the firmware updater have not been performed in the Linux workflow
so far. A firmware update is not required for the steps described here.

For GUI capture, the original software must use the proxy as its DSP target:

```text
Windows editor → Proxy GUI listener :9761 → DSP :9761
Raider capture → Proxy stream       :19081
Raider dump    → Proxy control      :19082 + Stream :19081
```

On the same computer, the GUI can use `127.0.0.1:9761`. On another Windows
computer, `127.0.0.1` refers to that computer's own loopback interface; use the
reachable address of the proxy computer and bind its GUI listener accordingly
with `--listen-host`. Stream and control can remain local if Raider continues
to run on the proxy computer.

Build evidence for a mapping step by step:

1. Record the firmware identity and initial GUI value; read a `before` snapshot.
2. Start capture, change exactly one agreed GUI value and record the displayed
   target value.
3. After capture ends, read a second `after` snapshot.
4. Analyze write traffic and compare the snapshots:

   ```bash
   java -jar target/dsp-raider.jar diff --before before.json --after after.json --out comparison.txt
   ```

5. Document channel, unit, raw bytes, block/data offset, firmware and source
   paths. Check further values and both directions before treating a scale or
   bit mapping as confirmed.

An observed write command does not confirm a read position. For mute, the
specific bit must be distinguished from unchanged neighboring bits. The
[parameter documentation](PARAMETERS.md) lists currently confirmed fields.
The ten remaining FIR mute mappings and complete GUI comparison remain open.

## 5. Prepare changes

Create your own change file following the [schema](CHANGE-PREVIEW.md) and check
it against the saved current dump:

```bash
java -jar target/dsp-raider.jar plan \
  --in before.json --changes changes.json --format json --out preview.json
```

READY means matching bytes with a proven starting value have been prepared.
UNCHANGED produces no command; neither does BLOCKED. This preview executes
nothing. Explicit application is a separate step described below. The old
writer and raw DSPD writes retain their independent active behavior.

## 6. Explicitly apply a request

With the intended device connected through the proxy, use your own fresh
`before.json` baseline and a reviewed `changes.json` request:

```bash
java -jar target/dsp-raider.jar apply \
  --in before.json --changes changes.json --format json --out application.json
```

This command can write parameters. It reads the original request, not
`preview.json`. A single BLOCKED field rejects the entire application before
CLI session creation. A valid request must match fresh firmware and all
requested baseline values, including unchanged ones. Each transmitted change
requires an actual DSP ACK and full-snapshot target readback. A nonempty
all-UNCHANGED request checks fresh state but sends no parameter writes; an
empty request does neither.

Read the report and exit code together. `write_attempted: true` may mean a
FAILED entry already affected the device. Earlier APPLIED entries remain
recorded, later READY entries remain NOT_ATTEMPTED, and there is no retry or
rollback. Firmware/value checks do not identify a unique physical serial;
snapshots are not atomic against other writers. The supplied preview example
contains a deliberately blocked FIR mute field and will be rejected as a whole.

[Execution](EXECUTION.md) gives complete statuses, output fields and checked
DSPD `applyChanges`. Automated verification of this workflow used injected
sessions; physical device write validation is still a separate integration step.

## Troubleshooting

| Observation | Next step |
| --- | --- |
| `dsp408-protocol` missing in an individual build | Build the shared workspace or install the library with Maven first |
| Stream/control unreachable | Both listener pairs configured? Ports match? Proxy terminal still open? |
| GUI reaches the DSP but Raider does not | Distinguish GUI port 9761 from stream/control ports 19081/19082 |
| Empty capture | Check traffic during the time window; GUI must connect through the proxy |
| Capture ends with EOF | Check proxy and connection; a disconnect is not an empty successful capture |
| Dump or offline read fails | Check exit code, `ok`, wire checksum and profile; do not alter preserved raw files to make them fit |
| `plan` contains BLOCKED | Check evidence status and required block data; read the entry's details |
| `check` succeeds but runner fails | Static checking proves neither runtime data, reachability nor ACKs |
| `apply` rejects the baseline | Re-read current firmware/requested values; inspect the report before changing the request |
| `apply` fails after a write attempt | Inspect partial results and actual device state; there is no rollback or automatic retry |

Retain original captures, JSON libraries and `.dspd` scripts. Add corrections
to an interpretation with source references; do not rewrite the underlying
raw data for that purpose.
