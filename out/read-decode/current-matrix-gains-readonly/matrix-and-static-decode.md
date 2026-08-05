# FIR408 DSPD Static Decode

- Assembled data bytes: 1556
- Method: DSPD `ReadBlockSet` static decode
- No GUI capture required

## Inputs

| Input | Label | Gain | Phase | Delay | Gate A/R/H/T | PEQ1 |
| --- | --- | ---: | ---: | ---: | --- | --- |
| InA | MAIN_L | -16.0dB | 0 | 0.00ms | 0/299/99/0 | 25.4Hz q=3.01 g=+0.0dB type=Peak |
| InB | MAIN_R | -16.0dB | 0 | 0.00ms | 0/299/99/0 | 25.4Hz q=3.01 g=+0.0dB type=Peak |
| InC | MEASURE | -10.0dB | 0 | 0.00ms | 0/299/99/0 | 25.4Hz q=3.01 g=+0.0dB type=Peak |
| InD | SPARE | -10.0dB | 0 | 0.00ms | 0/299/99/0 | 25.4Hz q=3.01 g=+0.0dB type=Peak |

## Outputs

| Output | Label | Gain | Phase | Delay | Matrix | FIR | PEQ1 |
| --- | --- | ---: | ---: | ---: | --- | --- | --- |
| Out1 | TOP_L1 | +0.0dB | 0 | 0.00ms | route=0x03 gains=InA=+0.0dB(raw=280) InB=+0.0dB(raw=280) InC=-60.0dB(raw=0) InD=+0.0dB(raw=280) | BYPASS/SINC hp=250.1Hz lp=20.16KHz taps=512 | 40.3Hz q=3.01 g=+0.0dB type=Peak |
| Out2 | TOP_L2 | -6.0dB | 0 | 0.00ms | route=0x0C gains=InA=+0.0dB(raw=280) InB=+0.0dB(raw=280) InC=+0.0dB(raw=280) InD=+0.0dB(raw=280) | BYPASS/SINC hp=250.1Hz lp=20.16KHz taps=512 | 40.3Hz q=3.01 g=+0.0dB type=Peak |
| Out3 | TOP_R1 | -6.0dB | 0 | 0.00ms | route=0x04 gains=InA=+0.0dB(raw=280) InB=+0.0dB(raw=280) InC=+0.0dB(raw=280) InD=+0.0dB(raw=280) | BYPASS/SINC hp=250.1Hz lp=20.16KHz taps=512 | 20.16KHz q=0.71 g=+12.0dB type=LP -6dB |
| Out4 | TOP_R2 | -6.0dB | 0 | 0.00ms | route=0x08 gains=InA=+0.0dB(raw=280) InB=+0.0dB(raw=280) InC=+0.0dB(raw=280) InD=+0.0dB(raw=280) | BYPASS/SINC hp=250.1Hz lp=20.16KHz taps=512 | 40.3Hz q=3.01 g=+0.0dB type=Peak |
| Out5 | BASS_MON | -15.0dB | 0 | 0.00ms | route=0x01 gains=InA=+0.0dB(raw=280) InB=-6.0dB(raw=220) InC=+0.0dB(raw=280) InD=+0.0dB(raw=280) | BYPASS/SINC hp=250.1Hz lp=20.16KHz taps=512 | 40.3Hz q=3.01 g=+0.0dB type=Peak |
| Out6 | RES_6 | -15.0dB | 0 | 0.00ms | route=0x02 gains=InA=+0.0dB(raw=280) InB=+0.0dB(raw=280) InC=+0.0dB(raw=280) InD=+0.0dB(raw=280) | BYPASS/SINC hp=250.1Hz lp=20.16KHz taps=512 | 40.3Hz q=3.01 g=+0.0dB type=Peak |
| Out7 | RES_7 | -15.0dB | 0 | 0.00ms | route=0x01 gains=InA=+0.0dB(raw=280) InB=+0.0dB(raw=280) InC=+0.0dB(raw=280) InD=+0.0dB(raw=280) | BYPASS/SINC hp=250.1Hz lp=20.16KHz taps=512 | 40.3Hz q=3.01 g=+0.0dB type=Peak |
| Out8 | RES_8 | -15.0dB | 0 | 0.00ms | route=0x02 gains=InA=+0.0dB(raw=280) InB=+0.0dB(raw=280) InC=+0.0dB(raw=280) InD=+0.0dB(raw=280) | BYPASS/SINC hp=250.1Hz lp=20.16KHz taps=512 | 20.16KHz q=3.01 g=+12.0dB type=Allpass1 |

## Notes

- Crossover active/remembered mode bytes are decoded statically but not written by the safe ping suite.
- Mute bytes are not decoded here yet; previous mask guesses were intentionally removed.
- Compressor/Limiter extended fields are intentionally excluded from the first DSPD safe ping pass.