# DSP Raider documentation

Commands in these guides run from the `tracks-dsp-raider` project directory
unless another working directory is explicitly stated. Bash uses `\` for line
continuation; in PowerShell, put commands on one line or use its continuation
syntax.

## Start with your task

| I want to… | Start here |
| --- | --- |
| build and try it without hardware | [Project README](../README.md#quick-start-without-a-dsp) |
| look up a command or its exit code | [CLI reference](CLI.md) |
| read the DSP or record GUI traffic | [Workflows](WORKFLOWS.md) |
| connect my own stream/control client | [Proxy interfaces](https://github.com/DoctorRemoteControl/dsp408-proxy/blob/main/PROTOCOL.md) |
| write a DSPD script | [DSPD syntax](../DSPD-SYNTAX.md) |
| understand what the compiler can check | [DSPD check](DSPD-CHECK.md) |
| execute a checked script or apply parameter changes | [Execution](EXECUTION.md) |
| prepare changes with old/new values | [Change preview](CHANGE-PREVIEW.md) |
| assess confidence in a read position or unit | [Parameters and evidence](PARAMETERS.md) |
| extend the code | [Development](DEVELOPMENT.md) and [Architecture](ARCHITECTURE.md) |
| choose the next work | [Roadmap](ROADMAP.md) |
| contribute and reproduce verification | [Contributing](../CONTRIBUTING.md) |

## Scope

The [live validation record](LIVE-VALIDATION.md) documents checked raw reads and
one verified Out8 gain change/restoration on FIR408 V0113P.

`check`, `decode`, `diff` and `plan` work offline. `capture` only receives stream
data. `connect`, `identify` and `dump` use a proxy device session. `run` compiles
the whole script before executing it; effects depend on its functions. Explicit
`apply` and checked DSPD `applyChanges` can write confirmed parameters using
fresh-state checks, ACK validation and readback. The separate DSPD runner and
older writers retain their independent active behavior.

The key distinction is that **checking**, **preparing** and **executing** are
separate operations. `plan` remains offline. `apply` consumes the same pure
planner through a separate active workflow; raw writers do not automatically
use it. See [Execution](EXECUTION.md) for partial failure handling and limits.

## Sources and history

[DSPINFO.md](../DSPINFO.md) primarily contains older DSP408 V0104 observations;
[script-example.md](../script-example.md) is the additional extensive script
reference. Captures, dumps and Markdown reports under `out/` retain their
historical context. Versioned live fixtures under
[src/test/resources/live](../src/test/resources/live/) are reproducible test
inputs, rather than current device measurements.

`superpowers/specs/` and `superpowers/plans/` document individual development
steps and the verification results at that time. The manuals linked above are
the current starting point. Local verification archives outside the repository
are additional work records and are not required for the offline examples
shown here.
