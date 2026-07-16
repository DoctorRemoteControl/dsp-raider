# FIR408 Phase Readback Verification

- Time: `2026-07-16T14:31:38.858278400+02:00`
- DSP: `192.168.0.166:9761`
- All channels verified: **false**
- Full config restored byte-for-byte: **true**
- Safety restore: all channels phase-normal and muted

| Channel | Index | Block | Offset | 0° | 180° | Restored | Changed offsets | Verified |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| InA | `00` | `03` | 5 | 0 | 0 | 0 | `[]` | false |
| InB | `01` | `05` | 45 | 2 | 2 | 2 | `[41]` | false |
| InC | `02` | `08` | 35 | 20563 | 20563 | 20563 | `[29]` | false |
| InD | `03` | `0B` | 25 | 24400 | 24400 | 24400 | `[17]` | false |
| Out1 | `04` | `0D` | 29 | 1 | 1 | 1 | `[25]` | false |
| Out2 | `05` | `0F` | 33 | 0 | 1 | 0 | `[33]` | true |
| Out3 | `06` | `11` | 37 | 140 | 140 | 140 | `[41]` | false |
| Out4 | `07` | `13` | 41 | 299 | 299 | 299 | `[49]` | false |
| Out5 | `08` | `15` | 45 | 15 | 15 | 15 | `[]` | false |
| Out6 | `09` | `17` | 49 | 285 | 285 | 285 | `[]` | false |
| Out7 | `0A` | `19` | 53 | 35 | 35 | 35 | `[]` | false |
| Out8 | `0B` | `1C` | 7 | 120 | 120 | 120 | `[31]` | false |
