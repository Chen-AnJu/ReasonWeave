# Changelog

All notable changes are documented here. The project uses semantic versioning
for application and CLI artifacts.

## [0.4.1] - Unreleased

This entry tracks the public `0.4.1` code line. Packaging and validation are
implemented; a Git tag and standalone release archive have not yet been issued.

### Added

- EventIR, Evidence/Observation, hybrid retrieval, immutable Investigation Runs,
  deterministic scoring, graph, audit, and Chinese product UI.
- Domain Pack Format 1, `rwpack`, and immutable multi-pack runtime registration.
- Production-oriented `kubernetes-pod-diagnostics/1.0.0` with attributed
  Kubernetes documentation, bounded expert rules, and Golden fixtures.
- Production-oriented `cold-holding-excursion-diagnostics/1.0.0` with
  jurisdiction-neutral site thresholds, attributed FDA/DOE summaries, four
  auditable cause categories, and Golden fixtures.
- Read-only `rw-evidence kubernetes collect` Observation Bundle collector.
- Local `rw-evidence cold-holding collect` streaming CSV collector with strict
  time-window, source, unit, size, row-count, hashing, and atomic-output gates.
- Ollama/Qwen3 1024-dimensional embedding path and index readiness checks.
- Deterministic CLI packaging with archive integrity and clean-install checks.
- API-first local-instance endpoints, a fixed OpenAPI contract, and a
  domain-neutral dynamic console driven by Domain Pack event definitions.
- A standard loopback-only Compose entry point for the frontend, backend,
  PostgreSQL/pgvector, Ollama, and the required embedding model.
- Prebuilt Linux/amd64 images pinned by immutable GHCR digests, with a
  separate Compose override for auditable source builds.
- A neutral `equipment-fault-test` pack that verifies the core without relying
  on Kubernetes semantics.
- A product-first public README with console screenshots, an embedded demo,
  API workflow, deployment boundary, and discoverable reference documents.

### Changed

- Public APIs and the console use one implicit local instance scope.
- Backend and CLI artifact versions are aligned on `0.4.1`.
- Spring Boot was updated to 3.5.16, the PostgreSQL driver to 42.7.12, and
  runtime Alpine packages are upgraded during image construction.

### Security

- Domain Pack extraction rejects traversal, links, executable and undeclared
  files, oversized payloads, and content drift.
- Kubernetes collection uses narrow status projections and excludes secret and
  environment values, tokens, and complete logs.
- Cold-holding collection keeps full telemetry local and emits only bounded
  summaries, source locators, hashes, and deterministic derived facts.
