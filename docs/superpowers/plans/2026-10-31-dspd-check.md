# DSPD check implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Complete offline DSPD parsing and conservative static checking with real source diagnostics.

**Architecture:** Pure `dspd` lexer/AST/parser/compiler and builtin catalog. Existing runtime delegates name lookup only; common CLI formats results. No dependency from compiler to sessions or scripts.

**Tech Stack:** Java21, existing Maven/JUnit/Jackson; no new dependency.

**Spec:** `../specs/2026-10-31-dspd-check-design.md`

## Global constraints

- Existing execution path and scripts stay unchanged; no hardware operations.
- Preserve modern and legacy documented syntax and runtime coercions.
- Positions: actual offsets and 1-based UTF16 lines/columns, CRLF once.
- Unknown dynamic values cannot be evaluated or declared valid device state.
- Canonical dirty checkout and branches preserved; before-snapshot and ledger.

## Review focus

- Comments/newlines/semicolons/multiline strings cannot corrupt error positions (Task1).
- All branches checked, with malformed delimiters/else/end rejected and nesting bounded (Task1).
- Name/signature metadata must match real runtime aliases/coercions (Task2).
- Variables introduced conditionally or by loops require conservative flow handling (Task2).
- Checking write/sleep/save/capture code must have zero side effects and correct error exit/report behavior (Task3).

## Task1: Source-aware syntax front-end

Files `dspd/{SourcePosition,Diagnostic,Token,DspdLexer,DspdAst,DspdParser}.java`,
tests lexer/parser. Lexer returns immutable tokens and diagnostics. Parser
consumes tokens and produces immutable Program AST or structured diagnostics.
AST covers literal/path/call/sequence/binary/unary expressions and let/call/assert/
if/for statements. Compatibility includes source whitespace and legacy syntax.

- [x] Add lexer/parser RED tests for source positions, quoted/comment delimiters, nested modern/legacy blocks and malformed inputs.
- [x] Implement source model, lexer, AST and parser; run targeted tests GREEN.

## Task2: Builtin catalog and compiler analysis

Files `dspd/{DspdBuiltins,DspdCompiler,DspdAnalyzer}.java`; runtime changes only
`ScriptFunctions` name/expression-starter delegation. `DspdCompiler.compile(String)`
returns Compilation(program,diagnostics), ok only without errors; invalid result
exposes no checked program. Analyzer never executes a function. Validate arity,
explicit variable paths/interpolation and statically provable numeric failures.

- [x] Add RED tests for alias/arity, undefined references, branch flow, wrong known values and dead-branch errors.
- [x] Extract pure name catalog and implement analyzer/compiler, preserve runtime dispatch behavior; targeted GREEN then full Raider suite.
- [x] Offline corpus analysis of all397 original examples; fix grammar mismatches, document genuine invalid examples without modifying them.

## Task3: CLI, docs, artifacts and final review

Files `dspd/DspdCheckReport.java`, `tool/DspRaiderCli.java`, tests
`tool/DspdCheckCliTest.java`, syntax/README docs and sample scripts.

- [x] RED tests for CLI success/failure text+JSON, file protection, options, source positions and zero execution side effects.
- [x] Implement offline `check --in [--format text|json] [--out]`, expected GREEN.
- [x] Document syntax/check scope, run workspace and standalone verification, packaged CLI and corpus checks; archive reproducible outputs.
- [x] One independent read-only review; Important/Critical fixes RED/GREEN and full suite; preservation audit and final report.

## Outcome and rulings

Full12-project verification: 404 tests, no failures/errors/skips. Renamed
standalone Raider: 226 tests and packaging GREEN. Packaged CLI validates good,
bad-syntax and unreachable-error scripts as text/JSON with correct exit codes,
real CRLF line/column and overwrite protection. All397 original examples parse;
392 pass static analysis, five pre-existing failures are documented and tested.
Source hashes prove original examples/JSON evidence unchanged.

One independent review produced seven Important and one Minor findings. One
regression/fix pass addressed raw-byte block ranges and ordering, widening of
loop-carried constants, implicit `_` updates, full path/index analysis, legacy
phrase context, omitted numeric positions, modern/legacy GUI arities and
independent alias expectations. RED/GREEN proofs and reviewer-scope rulings are
in the task ledger and REVIEW.md. No second review or execution changes.

Artifacts: `/home/xla/drremote/Archiv/DSP408/dspd-check-20261031-225006/`.
Before-tree, ledger, build/review logs and preservation audit:
`/home/xla/drremote/.codex-backups/dsp-dspd-check-20261031-lo5g4l4g/`.
The checked AST is a frontend result; legacy run remains explicit and unchanged.
New work remains uncommitted on original branches, with existing changes intact.
