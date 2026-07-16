# FIR408 Phase Readback Verification

- Time: `2026-07-16T14:34:36.778958200+02:00`
- DSP: `192.168.0.166:9761`
- All channels verified: **true**
- Full config restored byte-for-byte: **true**
- Safety restore: all channels phase-normal and muted

| Channel | Index | Observed block | Offset | 0° | 180° | Restored | Legacy match | Verified |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| InA | `00` | `02` | 53 | 0 | 1 | 0 | false | true |
| InB | `01` | `05` | 41 | 0 | 1 | 0 | false | true |
| InC | `02` | `08` | 29 | 0 | 1 | 0 | false | true |
| InD | `03` | `0B` | 17 | 0 | 1 | 0 | false | true |
| Out1 | `04` | `0D` | 25 | 0 | 1 | 0 | false | true |
| Out2 | `05` | `0F` | 33 | 0 | 1 | 0 | true | true |
| Out3 | `06` | `11` | 41 | 0 | 1 | 0 | false | true |
| Out4 | `07` | `13` | 49 | 0 | 1 | 0 | false | true |
| Out5 | `08` | `16` | 7 | 0 | 1 | 0 | false | true |
| Out6 | `09` | `18` | 15 | 0 | 1 | 0 | false | true |
| Out7 | `0A` | `1A` | 23 | 0 | 1 | 0 | false | true |
| Out8 | `0B` | `1C` | 31 | 0 | 1 | 0 | false | true |
