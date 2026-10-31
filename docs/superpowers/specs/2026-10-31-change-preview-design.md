# Offline change planning

The user approved the next step with “weiter”: prepare and preview Gain/Mute/
Delay changes against a saved snapshot. This implements the proposed planning
step only; applying changes and live readback are subsequent work.

## Approach and scope

Keep Java 21, the existing parameter catalog and CLI. A pure `planning` core
compares requested values with `BlockData`; JSON parsing and reporting are
adapters in that package. The CLI adds `plan --in snapshot.json --changes
changes.json [--format text|json] [--out preview-file]`. Text is the default.
No socket/session dependency or send operation is introduced.

Alternatives: generating raw write scripts alone loses baseline/evidence and
rounding information; implementing apply/readback now exceeds the selected
offline step. The chosen approach provides reusable immutable requests/plans
and a reviewable CLI preview first. Keep existing branches and canonical local
edits as previously authorized; take a complete before-snapshot, do not commit
or stage pre-existing work.

## Requests and result

Request JSON has exactly `device_version` (nonblank string) and `changes`
(array, 0..36 entries). An entry has exactly `channel`, `parameter`, `value`.
Channels InA..InD/Out1..Out8 and kinds gain/mute/delay are case insensitive.
Numeric values use dB/ms or Mute 0/1; Mute also accepts JSON booleans. Reject
unknown fields, repeated JSON keys, duplicate channel/kind pairs, nonnumeric
values, nonfinite/out-of-range values and unsupported channel/kind names.
Require the requested firmware to match the actual snapshot identity (ignoring
case/outer whitespace); no implicit firmware conversion.

Each entry shows requested and representable target values, rounding, original
engineering/storage values and read/write evidence when available. Compare
encoded scalar values, including bit extraction for Mute, so unchanged targets
produce no command. Preserve input order. Entry states:

- READY: confirmed valid baseline and confirmed write layout, target differs;
  show the prepared command payload.
- UNCHANGED: same encoded scalar value; show no payload.
- BLOCKED: missing/invalid/inferred baseline, unknown firmware or unconfirmed
  write layout; retain reason and target, show no payload.

Known profiles remain DSP408 V0104/FIR408 V0113P. Do not promote inferred mute
bits. Partial snapshots may preview available requested fields while missing
ones are blocked. The CLI checks sizes of present profile blocks in addition to
existing wire checks. Plan output is explicitly a preview, never executable.
The input snapshot/request and original protocol evidence remain untouched.
Existing output files cannot be replaced. JSON output is versioned and retains
full requested/target precision; text makes quantization visible separately.

## Verification

Golden payload: Out2 -3 dB = `00 01 04 34 05 FA 00`; Out8 20 ms =
`00 01 04 38 0B 80 07`; InD mute on = `00 01 03 35 03 01`.
Use the unchanged real FIR fixture and synthetic variants, no hardware. Test
quantization/no-ops, shared mask bits, blocked states, invalid/duplicate/typo
requests, firmware mismatch, text/JSON output, overwrite protection and an
offline-only CLI factory. Run all 12 workspace modules and renamed standalone
Raider, then one independent read-only feature review. Changes remain local.
