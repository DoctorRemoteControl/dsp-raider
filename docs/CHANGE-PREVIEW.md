# Preparing changes offline

[Overview](README.md) · [CLI](CLI.md) · [Parameters](PARAMETERS.md) · [Execution](EXECUTION.md)

`plan` compares requested gain, mute and delay values with a saved snapshot.
The preview shows the starting value, requested value, representable target
value, rounding and prepared command bytes. It opens no device connection and
sends no commands.

Example from `tracks-dsp-raider` after [building](DEVELOPMENT.md#building):

```bash
java -jar target/dsp-raider.jar plan \
  --in src/test/resources/live/fir408-v0113p-20261031.json \
  --changes examples/change-preview.json
```

The [example](../examples/change-preview.json) contains only simulated requests
against the preserved reference dump. It does not change the reference:

- Out2 gain −3.04 dB is prepared as −3.0 dB.
- Out8 delay 20.004 ms is prepared as 20.000 ms.
- InD mute ON can be prepared with a confirmed starting value.
- InA mute remains blocked because its read position is unconfirmed.
- Out3 gain −6 dB already matches the reference value and produces no command.

Default output is text. JSON with full numeric precision:

```bash
java -jar target/dsp-raider.jar plan \
  --in snapshot.json --changes changes.json --format json --out preview.json
```

`--out` is optional. Existing files are not overwritten. Network options and
`--apply` are unavailable for `plan`.

## Input file

```json
{
  "device_version": "FIR408 V0113P",
  "changes": [
    { "channel": "Out2", "parameter": "gain", "value": -3.04 },
    { "channel": "Out8", "parameter": "delay", "value": 20 },
    { "channel": "InD", "parameter": "mute", "value": true }
  ]
}
```

`device_version` must match the firmware determined from the snapshot's wire
data. Case and surrounding whitespace are ignored. Confirmed catalogs exist
for DSP408 V0104 and FIR408 V0113P.

`changes` contains up to 36 entries; an empty array is allowed. Channels are
InA–InD and Out1–Out8; parameters are `gain`, `mute`, `delay`. Names are
case-insensitive. Numbers use dB for gain and ms for delay. Mute accepts numeric
0/1 or JSON false/true. Other fields and units are not allowed. Duplicate JSON
keys, multiple values for the same channel parameter, invalid values and
unknown names are rejected. An invalid request produces no partial preview.

## Status and rounding

| Status | Meaning | Payload |
| --- | --- | --- |
| READY | confirmed starting value, proven write format, different target value | prepared, not sent |
| UNCHANGED | encoded target value matches the starting value | none |
| BLOCKED | starting value missing, invalid/unconfirmed, or firmware/write format unknown | none |

Comparison uses representable raw values. A request such as −6.01 dB therefore
produces UNCHANGED when −6.0 dB is already set. For mute, only the relevant bit
is compared; other bits in the stored mask byte must not create a false need
for change. Gain supports −60..12 dB and delay 0..680 ms. Out-of-range values
are not clamped to the limits.

Request order is preserved. Available fields in a partial snapshot can be
evaluated; requested missing fields are BLOCKED. Existing blocks must contain
valid wire data and have the expected size. The
[parameter model's evidence rules](PARAMETERS.md) apply unchanged.

## JSON output and exit codes

JSON has `schema_version: 1`, `type: "change_plan"`, `preview_only: true`,
`device_version`, `summary` and `entries`. Entries include:

- `before_value` and `before_status`: confirmed parameter value, or null with status.
- `before_storage_raw`: original storage value; the entire mask byte for mute.
- `requested_value`, `target_value`, `target_write_raw`, `rounded`: request and actual encoding.
- `read_evidence`, `write_evidence`: separate evidence, possibly null.
- `status`, `reason`, `payload_hex`: assessment and, for READY, prepared payload.

A valid preview returns exit code 0 even if some entries are BLOCKED. Invalid
options or invalid parameter requests return 2; file/JSON/snapshot read errors
return 1. Errors produce no output file.

The preview refers to saved state and executes nothing. Explicit CLI `apply`
and checked DSPD `applyChanges` now use the same planner in a separate active
workflow. They accept a baseline plus the original change request, not this
preview JSON; they check fresh firmware/requested values, validate ACKs and
confirm targets by full-snapshot readback. A blocked request is rejected as a
whole. Failures retain partial results and do not roll back earlier effects.
See [Execution](EXECUTION.md#apply-a-change-request). `plan` itself remains
offline and has no `--apply` option.
