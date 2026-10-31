# DSPD compiler front-end and offline check

The user approved building a complete offline DSPD check after reviewing the
current interpreter. Preserve existing DSPD forms and the existing execution
path. This step adds a source-aware lexer, immutable AST, parser, conservative
static analysis and `check`; no new language features or hardware operations.

## Approach

Use a small handwritten Java21 front-end in `de.drremote.dsp408.dspd`, without
new dependencies. Parse the entire source, including all branches and loop
bodies, before declaring success. An immutable compilation result exposes a
program only after syntax/static checks pass. Builtin names/aliases/signatures
live in a pure catalog shared with the legacy runtime's name lookup; adapters
never create ScriptRuntime or call ScriptFunctions while checking.

Alternatives considered: only balancing delimiters misses invalid statements
and dead-branch errors; replacing the whole execution engine now increases the
compatibility scope; a generated parser adds a dependency/build tool before the
grammar is stabilized. The selected front-end provides the foundation for a
later checked-program executor while keeping today's run behavior intact.

## Compatibility and diagnostics

- Function calls/camelCase aliases, legacy kebab commands without parentheses,
  optional semicolons/newlines, let, assert, if/else-if/else, for inclusive ranges,
  braces/end, strings/escapes/comments, interpolation and member/index paths.
- Track actual source offsets and 1-based UTF16 line/column; CRLF is one newline.
  Newlines/comments/semicolons must never turn positions into statement numbers.
- Reject malformed strings, delimiters, control blocks, declarations, calls and
  unsupported statement names; inspect code even in unreachable branches.
- Static checks validate known builtin signatures, explicit variable references
  and interpolation, plus definitely known invalid numeric arguments/ranges.
  Preserve runtime coercions and unquoted legacy strings. Bare unknown atoms may
  be strings; do not invent static types for device-dependent values.
- Data-dependent values, properties, connection/device state and firmware-specific
  parameter mappings remain runtime concerns, clearly documented. No claim that a
  successful check guarantees device behavior. No new gain/mute/delay DSL calls.
- Keep original scripts, evidence, branches and local changes. Existing canonical
  checkout preference continues with a complete before-snapshot. No automatic
  commit/push or change to execution semantics.

## CLI

`java -jar target/dsp-raider.jar check --in script.dspd [--format text|json]
[--out new-report]`. Output includes source path, outcome and diagnostics with
code, message, offset, line and column. JSON uses schema_version1/type dspd_check.
Valid check exits0; invalid source/reportable diagnostics exits1, with report
still available; IO errors exit1; invalid options exit2. Existing output files
cannot be overwritten. Reject network/apply options. No session/file-producing
script function, pause, sleep, GUI capture or write can execute during checking.

## Verification

TDD lexer/parser/semantic/CLI tests, source-position goldens, malformed modern
and legacy forms, nested/dead branches and offline side-effect tests. Check the
397 preserved script examples as a corpus; report genuine pre-existing invalid
scripts separately rather than silently accepting them or editing originals.
Run all12 workspace projects, standalone Raider and packaged CLI, followed by one
independent read-only feature review. Keep an audit of before/after source hashes.
