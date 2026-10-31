# Checked DSPD execution implementation plan

> **For agentic workers:** Use superpowers:executing-plans for the dependent code tasks; documentation translation and read-only Git audit are independent delegated tasks. Continue through all tasks as requested by the user.

**Goal:** Execute checked DSPD ASTs, share parameter planning/application, and
prepare English documentation and focused Git commits.

**Architecture:** Keep `dspd` pure. Traverse its AST in `script`, sharing lazy
builtin argument dispatch and value conversions with the existing interpreter.
Add `execution.ChangeApplier` with injected snapshots over `DspSession`.

**Tech Stack:** Java 21, Maven, Jackson, JUnit 5; no new dependencies.

**Spec:** `docs/superpowers/specs/2026-10-31-checked-execution-design.md`

## Global constraints

- Preserve original protocol JSON, DSPD corpus and captures byte-for-byte.
- No physical DSP writes or service changes during implementation/verification.
- Keep independent repositories, existing branches and unrelated local edits.
- All maintained documentation and new messages/comments are English.
- No push, deployment, firmware update or automatic rollback.

## Review focus

- Preflight error after an apparent connection/file write must cause no effects.
- Nested builtin calls must evaluate arguments once and in existing order.
- Legacy phrase/byte/write forms and loop variable restoration must remain usable.
- Stale baseline/no-op/inferred mute must never generate an unintended write.
- Invalid ACK/readback after earlier success must retain partial progress.

## Task 1: Checked executor

Files: new `script/CheckedDspScriptEngine.java`, `script/DspdExecutor.java`,
`script/ScriptArgument.java`; modify `ScriptFunctions.java`,
`ScriptValueResolver.java`, `tool/DspRaiderCli.java`; test
`script/CheckedDspScriptEngineTest.java`, `tool/DspdRunCliTest.java`.

Interfaces: `CheckedDspScriptEngine` accepts the existing endpoint defaults and
`DspSessionFactory`; `executeText(sourceName, source)` returns final variables.
`ScriptArgument.evaluate()` lazily supplies a builtin argument. The CLI `run`
uses that engine and accepts only input and proxy options, with its output sent
to the provided stream.

- [x] Write failing preflight/no-side-effects, physical-position, loops,
  interpolation/index and modern/legacy compatibility tests.
- [x] Run the targeted tests and confirm missing checked execution is the failure.
- [x] Share builtin dispatch through lazy arguments, implement AST traversal and
  compile before runtime construction; retain legacy runner behavior.
- [x] Add `run` parsing/help and tests for invalid script and offline execution.
- [x] Run targeted and full Raider tests. Commit only this task's source/tests
  together with the already developed frontend files it depends on.

## Task 2: Verified application service

Files: new `execution/ChangeApplier.java`, `execution/ApplyResult.java`,
`execution/ApplyReport.java`; modify `dump/ReadOnlySnapshotReader.java` as needed;
test `execution/ChangeApplierTest.java`.

Interfaces: `apply(DspSession, Snapshot baseline, ChangeRequest)` returns an
immutable result with per-entry states and an overall success flag.
`SnapshotReader.read(DspSession)` supplies checked snapshots. Default adapter
uses the existing read-only dump and strict SnapshotFiles validation.

- [x] Test confirmed literal payloads, strict ACK/readback, unchanged values,
  inferred/missing baseline refusal, firmware/stale-state refusal and partial
  failure after earlier success using saved fixture data and a recording session.
- [x] Observe RED, implement service, observe GREEN; no automatic retries.
- [x] Run full Raider tests and commit service/tests.

## Task 3: Model-based scripting and CLI application

Files: new `script/ScriptParameters.java`; modify `DspdBuiltins.java`,
`DspdAnalyzer.java`, `DspdExecutor.java`, `ScriptValueResolver.java`,
`DspRaiderCli.java`; tests for static signatures, offline parameter operations,
explicit application and CLI preflight/output handling.

Interfaces: `loadSnapshot(path)`, `parameter(snapshot,channel,kind)`,
`planChange(snapshot,channel,kind,value)`, `applyChanges(snapshot,changesPath)`;
`apply` CLI takes `--in`, `--changes`, proxy options and optional new `--out`.
Results expose status/value/quantization and application success to scripts.

- [x] Add failing offline parameter/model and active application tests.
- [x] Register/check extensions, implement shared-model adapters and explicit
  CLI. Reject invalid requests before session creation.
- [x] Run full Raider and root reactor verification; commit source/tests.

## Task 4: English documentation and Git readiness

Files: maintained READMEs/guides, `.gitattributes`, `.editorconfig`, ignore rules,
contribution instructions and actual component lock revisions where applicable.

- [ ] Finish delegated English translation; update syntax/CLI/workflows/roadmap
  for actual completed behavior and offline executable examples.
- [ ] Adopt preservation-safe line-ending policy; keep generated artifacts out
  of new commits, preserve already tracked historical files.
- [ ] Run packaged CLI offline examples, links/fences/JSON validation, source-
  evidence hash comparison and repository-state audit.
- [ ] Review the full change with an independent reviewer; fix substantive
  findings with regression tests, then rerun the reactor.
- [ ] Make focused local commits and update workspace lock to actual component
  commits, without adding unrelated Controller edits or pushing.

## Execution ledger

Ruling: Work in the canonical existing checkout without creating/switching a
branch — existing uncommitted frontend and user edits must stay available;
backup plus focused diffs protect them — isolation is weaker than a clean
worktree, so all stages use explicit paths and preserve unrelated content.

Baseline backup: `/home/xla/drremote/.codex-backups/dsp-execution-20261031-6us72pyc`.

Tasks 1–3 implemented and verified: baseline 226 Raider tests; checked execution,
parameter functions and application now 254 tests, all passing. Initial 12-project
reactor and post-review reactor both succeeded. Standalone `bash ci/verify.sh`
also passed with the shared protocol installed from source.

Final review: independent reviewer found five Important issues, no Critical or
Minor issues. Regression failures reproduced chained-property parsing, malformed
legacy write syntax, numeric legacy bytes, lost application errors on cleanup,
and lost reports on output failure. Each was corrected; full reactor passed.
An additional end-to-end checked apply test exercises the actual dump flow over
an injected session, including indexed/chained result properties.

Ruling: Commit the interdependent compiler/executor/application implementation
together after review, then commit English docs and Git metadata separately —
partial source commits would depend on unstaged types — the code commit is
larger, but each committed tree builds independently.

Final: Ruling: Physical protocol correctness, concurrent external writers and
undocumented firmware remain integration/evidence boundaries — only saved
fixtures and injected sessions are authorized for this implementation; docs and
Git integration are verified by the parent after code review — real-device
behavior and external races remain unproven, as documented in EXECUTION.md.

Implementation commit: `cc23a75` (51 focused source/test/example files).
