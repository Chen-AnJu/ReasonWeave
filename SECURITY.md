# Security policy

## Supported versions

The public `main` branch currently carries `0.4.1`, but no tagged Git release
has been issued. Until the first tag, security fixes target the latest public
commit on the `0.4.x` code line; older commits are not supported.

## Reporting a vulnerability

Do not disclose a suspected vulnerability in a public issue. Use GitHub Private
Vulnerability Reporting when the repository exposes “Report a vulnerability”.
If it is unavailable, contact the repository owner through a private channel
listed on the owner's GitHub profile and include:

- the affected version and component;
- a minimal reproduction or proof of impact;
- whether credentials, Kubernetes metadata, or user data may be exposed;
- any known workaround.

Do not include live secrets, production kubeconfig files, or production data.
Maintainers should acknowledge a complete report privately before discussing a
public advisory or release date.

## Deployment security boundary

- ReasonWeave 0.4.1 is a single-instance service and has no authentication or
  RBAC.
  Bind the backend to loopback or another trusted private interface; do not
  expose it directly to the public Internet.
- The API playground calls the same-origin local API and can invoke allowed
  write endpoints. It is not an administrative security boundary.
- Domain Packs are data-only archives. `rwpack` rejects links, executable files,
  unsafe paths, undeclared files, excessive unpacked sizes, and checksum drift.
- `rw-evidence` reads a bounded Pod status projection and related Kubernetes
  Events. Generated bundles can still contain operational metadata; review and
  anonymize them before sharing.
- `rw-evidence cold-holding` reads local JSON/CSV only, rejects out-of-window
  and malformed records, and excludes full raw telemetry from its Bundle.
  Source IDs and summary facts may still be operationally sensitive; review the
  output before sharing it.
- The Kubernetes pack suggests read-only diagnostics and never performs an
  automatic remediation.
- Embedding and Vision endpoints may receive investigation content. The default
  production-ready path uses local Ollama; configure any external provider only
  after reviewing its privacy and retention terms.

Dependency, source, secret, and runtime-image scans are release gates, but they
do not replace deployment hardening or a security review for Internet exposure.
