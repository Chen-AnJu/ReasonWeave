# @reasonweave/domain-pack-cli

`rwpack` is the cross-platform CLI for ReasonWeave Domain Pack Format 1.

It can create a template, validate a pack directory, build a deterministic
`.rwpack` archive, verify an archive, and atomically install an immutable pack
version.

```console
rwpack --help
rwpack validate ./domain-packs/example/1.0.0
rwpack pack ./domain-packs/example/1.0.0 --out ./example-1.0.0.rwpack
```

Domain Packs are data only. The validator rejects executable files, links,
undeclared files, unsafe paths, missing provenance, and checksum drift.

License: Apache-2.0.
