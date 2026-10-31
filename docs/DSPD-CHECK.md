# Checking DSPD offline

[Overview](README.md) · [Syntax](../DSPD-SYNTAX.md) · [CLI](CLI.md)

Run commands from `tracks-dsp-raider` after building.

```bash
java -jar target/dsp-raider.jar check --in examples/offline-check.dspd
java -jar target/dsp-raider.jar check --in script.dspd --format json --out check.json
```

`check` reads the complete script, creates a syntax tree and checks it
statically. It creates no device connection. Script functions such as writing,
reading, pausing, sleeping or saving are never executed. Only the explicitly
requested check report can be created as a new file.

## Compiler structure

The new Java compiler frontend in `de.drremote.dsp408.dspd` consists of:

1. `DspdLexer`: tokens with positions in the unchanged source text.
2. `DspdParser` / `DspdAst`: complete modern and legacy statement/block forms.
3. `DspdAnalyzer`: static checks with conservative variable flow analysis.
4. `DspdCompiler`: immutable result containing a checked program or errors.
5. `DspdBuiltins`: shared function names, aliases and call signatures.

`run` now passes a successfully checked program to `CheckedDspScriptEngine` and
`DspdExecutor`; the whole source is compiled before runtime construction. It
also provides `loadSnapshot`, `parameter`, `planChange` and explicit active
`applyChanges`. See [Execution](EXECUTION.md) and the
[parameter syntax](../DSPD-SYNTAX.md#parameters-and-explicit-application).

The original separate interpreter uses the same catalog for name recognition,
but retains its independent execution path without an automatic compiler check.
The new parameter operations are implemented by the checked executor. `check`
produces neither machine code nor an executable command file, and it still
executes no functions. There is no `compile` CLI subcommand.

## Checked language

Supported features include existing function calls and camelCase aliases,
legacy commands without parentheses, optional semicolons, newlines, `let`,
`assert`, `if / else if / else`, `for ... in start..end`, `{ ... }` and `end`.
Strings, escape sequences, `#`/`//` comments, `${...}` interpolation and
variable/property/index paths are considered. As in the runtime, ranges are
inclusive and may descend. Details: [DSPD-SYNTAX.md](../DSPD-SYNTAX.md).

The checker detects:

- Malformed strings, parentheses, declarations, ranges and block endings.
- Unknown functions and incorrect argument counts.
- Explicit variable accesses (`$name`, `${name}`) that are not definitely defined.
- Invalid numeric arguments identifiable from literals or fixed variable values,
  such as `readBlocks(0, 256)` or `sleep(-1)`.
- Errors in every branch, including `if (false)`.
- Statically known invalid channel names, parameter kinds or target values in
  `parameter`/`planChange`, using the shared channel and parameter rules.

Existing numeric conversions are retained: integer arguments truncate `Number`
values; `toInt` also accepts false/true as 0/1 and numeric strings, including
hexadecimal numbers. `sleep` uses `toLong` instead. Unknown unquoted words can
still be strings. Unquoted legacy phrases remain strings. Builtin function
names take precedence in expressions: a variable with the same name requires
an explicit access such as `$raw`.

After a conditional, newly introduced variables are definitely available only
when defined in every branch. The loop variable is scoped to the loop;
previous values with the same name are restored afterward. The inclusive loop
visits at least one value, so other variables created inside it remain
available as before.

Values of variables modified inside a loop are conservatively treated as
unknown. A single abstract iteration must not produce false constants for
later iterations or the state afterward. Implicit changes to the last-result
variable `_` are also considered. Variable references and indices in `${...}`
are checked completely; index values follow the existing runtime's resolver,
including variables with names such as `len`.

DSPD remains a language for raw protocol investigation: block range functions
support 0..255 and check the order of their bounds. Individual `readBlock`
calls retain the existing integer-to-wire-byte conversion. The checker does
not impose any particular DSP model's memory limits. Modern GUI capture calls
allow 0..4 arguments; legacy forms without parentheses retain their own
1..3 argument limit.

Device-dependent values and properties are neither fabricated nor loaded from
the network. `check` cannot prove their actual contents, connection, ACKs,
current device state or firmware mapping. A successful check is static
validation, not a guarantee of a successful device operation. `run` performs
this validation first, then executes only a successful checked program. Runtime
errors retain source positions but can follow earlier effects. The
`examples/offline-run.dspd` and `examples/offline-parameters.dspd` scripts are
offline execution examples; `examples/offline-check.dspd` contains `connect`
and must only be checked when no connection is intended.

## Messages and exit codes

Text errors include `file:line:column`, an error code and a description.
Positions refer to the original text: lines/columns start at 1, offsets at 0.
The unit is UTF16, matching Java; CRLF counts as one newline. Comments, multiple
statements per line and multiline calls do not change this mapping. A
structural syntax error stops parsing; a completely parsed tree can produce
multiple static errors. Syntax nesting and recursive expression analysis are
limited to depth 128.

| Exit code | Meaning |
| --- | --- |
| 0 | static checking without errors |
| 1 | script error or file read/write failure |
| 2 | invalid or missing CLI options |

`--out` can save a report even for an invalid script. Existing files are not
overwritten. Network or execution options are unavailable for `check`. JSON
output contains `schema_version: 1`, `type: "dspd_check"`, `ok`, `executed: false`,
source path, statement count and diagnostics with code, message, offset, line
and column. An invalid program is not supplied as a checked program; its
statement count is 0.

## Existing example scripts

In the recorded corpus from 2026-10-31, all 397 preserved scripts are recognized
syntactically. 392 also pass static checking. Five existing errors remain
documented in the originals:

| Script | Finding |
| --- | --- |
| `366-auto-series-out1-iir-highpass-slope-long.dspd` | `byteSeries()` is absent from the existing runtime |
| `367-auto-series-out1-iir-lowpass-slope-long.dspd` | `byteSeries()` is absent |
| `370-auto-series-out8-iir-highpass-slope-long.dspd` | `byteSeries()` is absent |
| `373-auto-series-out8-iir-lowpass-slope-long.dspd` | `byteSeries()` is absent |
| `412-read-current-out8-peq9-frequency.dspd` | `raw` resolves to a function rather than a variable; `$raw` would be the explicit access |

The last finding was also reproduced with the existing runtime without a device
connection. Original scripts and protocol evidence remain unchanged. The corpus
test prevents additional unnoticed deviations.
