# DSP architecture

The user authorized improving the architecture of DSP Raider and its related
proxy/controller projects. This implements the proposed common protocol core
without changing their independent repositories or existing entry points.

## Intent and constraints

- Keep Java 21, existing CLI names, DSPD syntax, JSON libraries and recorded
  protocol evidence. Preserve current branches and pre-existing working changes.
- Share binary framing and payload metadata; keep sockets, CLI, JSON and OSGi
  services outside that dependency-free core.
- Handle binary `10 03` inside payloads, fragmented TCP data, bad checksums,
  truncated input and consecutive frames consistently.
- Separate FIR analysis from script evaluation. Put DSP session operations behind
  an interface so scripts and diagnostics can be tested without a DSP.
- Keep existing public codec/session helpers as adapters for compatibility.
- Support ordinary Maven builds and the controller's Karaf feature descriptor.
- No device connections, deployment, publication or runtime restart is required.

## Approaches

1. Package cleanup only: smallest build change, but framing stays duplicated.
2. Common Java protocol library, independent applications and injected session
   interfaces: selected following the user's authorization to improve all the
   identified issues. Removes duplication without moving whole repositories.
3. Merge everything into one OSGi application: unnecessary runtime coupling for
   the standalone reverse-engineering tools; not selected.

## Structure

`DSP408/dsp408-protocol` is an independent Maven library
`de.drremote:dsp408-protocol:0.1.0-SNAPSHOT`. It exports
`de.drremote.dsp408.protocol` as an OSGi bundle and has no runtime dependencies.
Its codec handles complete frames and stream decoding; a bounded incremental
decoder serves the proxy/raw readers. Payload metadata and hex formatting are
pure functions. Complete-frame diagnostics retain checksum status; callers
choose whether invalid frames are acceptable, rather than silently losing data.

Raider's session interface describes device/capture operations, without socket
or CLI types. ProxyClient implements that contract; the script runtime accepts a
factory for test or alternative sessions. Script builtins adapt script values to
typed FIR analysis operations. Legacy methods and constructors delegate to the
new implementation. CLI default wiring remains compatible.

The proxy and controller keep their existing public codec APIs but delegate
binary framing to the common library. The controller feature includes that
library, and a workspace aggregator builds it before all three consumers.

## Verification

Use known wire vectors, deliberately split frames, embedded marker bytes,
back-to-back frames, corrupt checksums, garbage prefixes and EOF cases.
Preserve the existing controller/proxy tests. Add Raider compatibility tests for
script expressions, captures/FIR decoding and injectable sessions; enforce the
allowed package direction with a source dependency test. Build every consumer
and inspect shaded JARs, OSGi imports/exports and the generated Karaf features.

## Implementation decisions

The imported canonical working copies are the requested delivery destination.
A complete local before-snapshot and the required drremote-backup protect the
existing work. Edits stay uncommitted and original branch references unchanged.
Protocol profile JSON and experimental capture evidence remain byte-for-byte
unchanged. Device-specific decoding is not generalized into the frame library.
