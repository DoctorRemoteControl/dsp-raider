# Status and next steps

[Overview](README.md) · [Architecture](../../ARCHITECTURE.md) · [Execution](EXECUTION.md)

As of 2026-10-31. The first three architecture stages are implemented. Physical
device validation of the new application workflow and repository publication
setup remain separate work.

## Available now

- Shared wire framing for Raider, proxy and OSGi controller; replaceable sessions
  and separate FIR analysis, diagnostics and uploads.
- Common CLI for reading, passive capture and offline analysis.
- Firmware-specific gain/mute/delay definitions for twelve channels, with
  separate read and write evidence.
- Offline change previews with quantization, READY/UNCHANGED/BLOCKED and a strict
  JSON request format.
- DSPD compiler frontend and offline `check`; explicit `run` executes its checked
  AST after whole-source validation, with original source positions on failures.
- Checked DSPD `loadSnapshot`, `parameter`, `planChange` and `applyChanges` share
  the snapshot, parameter, planning and application models.
- Explicit `apply` and `applyChanges` validate fresh firmware/values, DSP ACKs and
  readback, stop at the first failure and retain partial results.

## Completed architecture stages

**1. Checked AST execution:** `CheckedDspScriptEngine` compiles before creating a
runtime. `DspdExecutor` walks that AST without reparsing source. Modern and legacy
forms, variables, `_`, inclusive descending loops, conversions and resource
cleanup have compatibility coverage. The separate original interpreter remains
available without automatic checking. There is no general bytecode compiler or
`compile` CLI command.

**2. Parameter-oriented DSPD:** saved snapshots expose gain/mute/delay readings,
including status and raw values. Pure `planChange` uses the same codecs and
rounding as the CLI preview. Inferred readings retain null parameter values;
identical encoded targets produce no write payload.

**3. Explicit application:** `ChangeApplier` rejects a blocked request before
live work, checks a fresh snapshot against all requested baseline values,
validates an ACK and full-snapshot readback per write, and retains APPLIED,
UNCHANGED, BLOCKED, FAILED and NOT_ATTEMPTED results. No retry or rollback is
provided. These checks bind firmware and requested values, not a unique physical
serial, and cannot make snapshots atomic against other writers.

## Remaining protocol and integration work

- Compare values directly with Windows Editor V1.12 and collect controlled
  GUI/readback evidence for the ten unconfirmed FIR mute mappings.
- Validate the new checked execution and application workflow against an actual
  device in an explicitly authorized integration step. Current automated
  coverage uses saved fixtures and injected sessions; it is not physical write
  validation.
- Add further parameter families such as channel names, polarity, PEQ or
  crossover only with their own units, encodings and separate read/write sources.
- Complete repository-owner decisions for publication, license and hosted CI.
  The current local [verification script](../ci/verify.sh) requires the protocol
  source checkout; see [Contributing](../CONTRIBUTING.md).

Existing raw DSPD tools remain available for investigations. Firmware updates
and treating other firmware revisions as equivalent are outside this roadmap.
