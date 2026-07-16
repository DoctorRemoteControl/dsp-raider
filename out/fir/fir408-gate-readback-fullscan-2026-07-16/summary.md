# FIR408 Gate Readback Verification

- Time: `2026-07-16T14:51:33.099791300+02:00`
- DSP: `192.168.0.166:9761`
- All tested fields verified: **true**
- Full config restored byte-for-byte: **true**
- Safety: all 12 channels muted; no unmute command was sent

## Original gate values

| Channel | Attack raw | Release raw | Hold raw | Threshold raw |
| --- | ---: | ---: | ---: | ---: |
| InA | 0 | 9 | 9 | 0 |
| InD | 0 | 99 | 99 | 56 |

## Readback locations

| Channel | Field | Block | Payload offset | Data offset | Baseline | Marker | Restored | Verified |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| InA | attack | `00` | 29 | 24 | 0 | 998 | 0 | true |
| InA | release | `00` | 31 | 26 | 9 | 2999 | 9 | true |
| InA | hold | `00` | 33 | 28 | 9 | 998 | 9 | true |
| InA | threshold | `00` | 35 | 30 | 0 | 180 | 0 | true |
| InD | attack | `08` | 43 | 38 | 0 | 998 | 0 | true |
| InD | release | `08` | 45 | 40 | 99 | 2999 | 99 | true |
| InD | hold | `08` | 47 | 42 | 99 | 998 | 99 | true |
| InD | threshold | `08` | 49 | 44 | 56 | 180 | 56 | true |
