# Contributing

Use **English** for maintained documentation, new comments, diagnostics, commit
messages and pull requests. Preserve original captures, protocol evidence and
historical script text in their original form.

## Build and review

Use JDK 21 and Maven. In the common DSP408 workspace run `mvn -B -ntp verify`.
For a standalone checkout, provide the independent `dsp408-protocol` source
checkout alongside Raider, then run:

```bash
bash ci/verify.sh
```

An alternative location is accepted through `DSP408_PROTOCOL_DIR`. The script
installs that shared library locally before verifying Raider. The library is
not currently published by this repository, so cloning Raider alone does not
provide its source or Maven artifact. The workspace lock records committed
component revisions; see [Development](docs/DEVELOPMENT.md).

Keep commits focused: compiler/executor, parameter application, and documentation
can be reviewed separately. Stage explicit paths, review `git diff --cached`,
and run the relevant tests before committing. Finish code changes with the
complete Maven verification. Existing Controller changes are a separate review.

## Source and evidence

- Keep pure planning separate from active application and transport.
- Test hardware-dependent behavior with saved wire fixtures and injected
  sessions. Automated verification must not contact a physical DSP.
- Document firmware, units, raw encoding and separate read/write evidence.
  Inferred mappings remain inferred, including the ten unconfirmed FIR mute bits.
- A check never executes a script. `run`, `apply`, existing writers and raw
  scripts can perform real operations and must be described accordingly.
- Keep protocol JSON, original `.dspd` scripts and `out/` captures intact.
  Add derived analysis with source references instead of rewriting a recording.

## Git hygiene

Text is normalized to LF in Git; Windows command scripts may use CRLF on
checkout. Existing working files do not need a mass newline rewrite. The local
`core.autocrlf=input` setting can remove CRLF-only status noise without modifying
working bytes. Avoid bulk `git add --renormalize` over evidence.

Build output belongs in `target/`. New root JARs and temporary `out/local/`
experiments are ignored. Already tracked historical JARs and captures stay
tracked; ignore rules do not establish that those originals are redundant.
Use the current `target/dsp-raider.jar` for verification.

Do not commit credentials or runtime state. Publishing, choosing a license and
setting remote repository policy are repository-owner decisions. This checkout
does not yet contain a license file or a hosted CI workflow. `ci/verify.sh`
provides the reproducible verification command for a correctly assembled checkout.
