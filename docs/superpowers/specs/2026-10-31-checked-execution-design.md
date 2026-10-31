# Checked DSPD execution and verified parameter changes

The user approved continuing through the documented roadmap without further
check-ins. The maintained project language is English. Preserve independent
repositories, existing local changes, original protocol evidence and branches.

## Intended behavior

1. `run --in script.dspd` compiles the complete source before constructing its
   runtime. Compilation failure cannot connect, write files, wait or execute any
   other script function. The checked AST drives statements and expressions.
2. Reuse the current runtime's builtins and value conversions. Share lazy
   argument evaluation with the interpreter rather than maintaining another
   transport/function implementation. Keep the old runner available for
   compatibility. Runtime errors retain physical file/line/column and cause;
   cleanup failures are suppressed on the original failure.
3. Modern/legacy calls, phrase literals, interpolation, paths, inclusive ascending
   and descending loops, restored loop variables, `_`, boolean short-circuiting,
   comparisons and nested calls work through the AST. Thread interruption is
   preserved. No hardware is used by implementation tests.
4. Add offline DSPD operations `loadSnapshot(path)`,
   `parameter(snapshot, channel, kind)`, `planChange(snapshot, channel, kind, value)`
   and explicit `applyChanges(snapshot, changesPath)` using the existing codecs
   and planning rules. Gain is dB, delay ms, mute boolean or 0/1. Reading an
   unconfirmed parameter returns its status, without an invented engineering
   value. Planning never connects or sends a command.
5. `apply --in baseline.json --changes request.json` is an explicit active CLI.
   The application validates the baseline and complete request, refuses any
   BLOCKED entry before sending, reads fresh state, binds the firmware and all
   requested baseline parameter values, and only then transmits READY entries.
   UNCHANGED entries send nothing. Each transmitted command requires a validated
   ACK and immediate confirmed readback of the encoded target. Stop on the first
   failure and report earlier results; never silently retry or promise rollback.
   A final report records firmware and partial results. No preview file is an
   executable authority. Firmware is the available identity, not a unique serial.

## Boundaries

`dspd` remains a pure frontend. The AST executor lives in `script`, which already
depends on the frontend and session/runtime adapters. Parameter application lives
in `execution`, separate from pure `planning`; CLI and checked scripting use the
same service. A default snapshot reader uses the existing checked read flow.
Tests inject a deterministic session/reader at the hardware boundary.

Legacy execution stays available. Its lexical and function semantics remain
compatible; the checked route rejects the five already documented corpus errors.
Parameter operations are checked-language extensions, not a rewrite of existing
raw scripts. Later protocol families and Windows GUI investigations remain
evidence work, outside this software implementation.

## Alternatives and decisions

Re-parsing source snippets in the executor would retain two language parsers;
the chosen AST traversal avoids it. Duplicating all builtins would create another
transport implementation; shared lazy arguments retain their existing evaluation
order. Trusting saved payloads would allow stale state; application replans from
the request and compares fresh confirmed parameter values before transmission.

Work remains in the canonical checkout to retain the user's existing uncommitted
frontend and documentation; no branch switch or history rewrite is authorized.
Git line-ending noise is addressed by clean-filter policy, without rewriting
the original working files. Focused commits exclude unrelated Controller edits.

## Acceptance

Full Maven reactor passes; checked execution has real side-effect/preflight,
loop, compatibility and failure-position tests. Application tests cover blocked,
stale, mismatched firmware, invalid ACK, wrong readback, no-op and partial failure.
Packaged offline run/check/plan examples work. English docs and relative links
match actual CLI behavior. Original corpus/captures retain their byte hashes.
