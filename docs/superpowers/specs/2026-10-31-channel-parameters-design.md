# Shared channel parameter model

The user approved central metadata, semantic comparison and explicit unknowns,
starting with Gain, Mute and Delay. Scope is Raider's snapshot, FIR and passive
write-decoding workflows; no device writes, runtime deployment or new UI.

## Design

A pure Java `parameters` package holds parameter kinds (unit, value/raw ranges,
encoding and write command), channel definitions, read locations, evidence,
decoded values and version-specific catalogs. Keeping this inside Raider avoids
adding an OSGi dependency before another application needs this API. The model
has no socket, Jackson, CLI or device-state dependencies.

Catalogs apply only to the evidenced `DSP408 V0104` and `FIR408 V0113P` versions.
Unknown firmware does not inherit confirmed parameter positions. Existing JSON
libraries/captures remain unchanged as evidence; references and their hashes are
recorded in the task backup. Payload offsets in those sources are normalized to
block-data offsets by subtracting the five-byte response header.

FIR Gain and Delay use the verified 12-channel maps. FIR Mute uses the newer
controller reference: block 1C, input data offset 32 and output data offset 34.
Only InD bit 08 and Out8 bit 80 have explicit FIR-specific confirmations in that
reference; other bits remain inferred, despite inherited DSP observations.
The stale Raider FIR mute offsets are never used as confirmed read positions.

Decoded values distinguish confirmed, inferred, missing/unknown and invalid.
Inferred storage may expose its raw candidate but never a confirmed engineering
value. Values outside Gain 0..400, Delay 0..65280 or Mute wire states 0/1 are not
silently clamped. Gain is -60..12 dB with its existing piecewise mapping, Delay is
0..680 ms at 96 counts/ms. Pure write builders validate inputs but do not transmit.

Snapshot decoding shows the three parameter families and evidence status. FIR
details retain their established layout while sharing Gain/Delay conversions
and read definitions. The passive write decoder uses the same scalar codecs.
Semantic diff precedes the existing raw diff and says e.g.
`Out1 Gain: -6.0 dB -> -3.0 dB`. Added/missing/invalid values stay explicit.
Changed bits outside confirmed fields are listed separately, including unknown
bits sharing a byte with a confirmed mute bit. All raw changes remain available.
This section means unassigned by the initial three-parameter model, not unknown
to every historical decoder in the project.

## Verification

Test scalar boundaries/quantization/invalid values, golden write payloads,
version/profile separation, missing data, conservative mute evidence and
bit-level coverage. Use the unchanged real FIR fixture and synthetic edited
copies for semantic changes; never alter the physical device to create cases.
Run the complete workspace build, CLI offline smoke checks and an independent
review. Preserve original branches and pre-existing changes.
