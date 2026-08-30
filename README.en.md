<div align="center">
  <img src="design/assets/reasonweave-mark.svg" alt="ReasonWeave mark" width="96" height="96">
  <h1>ReasonWeave</h1>
  <p><strong>You need more than an answer. You need an evidence chain that proves how confirmed facts, domain rules, and attributable knowledge produced the conclusion.</strong></p>
  <p>A self-hosted, API-first, Domain-Pack-driven evidence reasoning engine.</p>
  <p>For API integrators, investigation systems, and equipment or operational anomaly diagnosis.</p>
  <p>
    <a href="#five-minute-start"><strong>Five-minute start</strong></a>
    · <a href="docs/api-quickstart.en.md">Pure API</a>
    · <a href="README.md">中文</a>
    · <a href="docs/media/reasonweave-demo.mp4">Demo video</a>
  </p>
  <p><code>0.4.1 preview</code> · <code>Apache-2.0</code> · <code>local-first</code> · <code>no built-in telemetry</code></p>
</div>

> [!CAUTION]
> This preview is an unauthenticated, single-instance service. Keep it loopback-bound or place it behind a trusted reverse proxy that you harden. It does not replace human judgment and does not issue repair, food-disposition, or regulatory decisions.

<p align="center">
  <img src="docs/media/reasonweave-demo.gif" alt="The complete flow from event creation and evidence confirmation to investigation, next evidence, graph, and audit" width="800">
</p>

<p align="center"><em>A 24-second flow on the real test stack with synthetic data. <a href="docs/media/reasonweave-demo.mp4">Watch the clear MP4</a>.</em></p>

## What an investigation result looks like

<p align="center">
  <img src="docs/screenshots/reasonweave-result.webp" alt="ReasonWeave workbench showing an image acquisition failure hypothesis, support index, coverage, scoring evidence, and citations" width="1120">
</p>

This is normalized output from an automated Golden Investigation running against real PostgreSQL, pgvector, and Qwen3 Embedding—not a hand-authored marketing score:

```json
{
  "confirmed_observation": "image_pull_backoff = true",
  "top_hypothesis": "Image acquisition failure",
  "support_index": 64,
  "coverage": 0.3571,
  "scoring_evidence": [
    { "predicate": "image_pull_backoff", "contribution": 0.95 }
  ],
  "citations": [
    { "section": "Container image acquisition failures", "score_affecting": false },
    { "section": "Observable states", "score_affecting": false }
  ],
  "next_evidence": "Inspect Pod scheduling conditions and related events"
}
```

- **Real-world evidence scores.** Only human-confirmed Observations contribute through deterministic rules.
- **Knowledge grounds.** Citations come from the frozen retrieval snapshot and explain or constrain a hypothesis; they never increase its support index.
- **Results remain investigable.** Coverage, gaps, and next-evidence recommendations state what is still missing.
- **The support index is not a probability.** `64` is relative support under the current evidence and rules, not a 64% chance that the cause is true.

See the complete normalized [Kubernetes result](docs/examples/kubernetes-investigation-summary.json) and [cold-holding result](docs/examples/cold-holding-investigation-summary.json).

## The problem it solves

Many diagnostic systems keep only a final answer. ReasonWeave persists how that answer was formed:

```text
Domain Pack → EventIR → Evidence / Observation → Retrieval Snapshot
→ Grounded Hypothesis → Deterministic Score / Coverage → Next Evidence / Graph / Audit
```

- **Reviewable:** every conclusion traces back to evidence, rules, citations, and source locators.
- **Immutable:** runs and their evidence/retrieval snapshots are append-only; new evidence creates a new Run without rewriting the old one.
- **Domain-driven:** event schemas, predicates, rules, knowledge, source reliability, and presentation metadata live in data-only `.rwpack` packages.
- **API-first:** the console and external systems use the same public API; integrations do not depend on a domain-specific UI.

ReasonWeave is not a chatbot, statistical prediction service, or automatic adjudicator. The investigation core does not call a generative Chat LLM. Production packs use real embeddings for knowledge retrieval and replayable rules for results.

## Five-minute start

### Requirements

- x86-64 Linux, macOS, or Windows
- Docker Engine / Docker Desktop
- Docker Compose v2
- At least 4 GiB available memory; 6 GiB or more is recommended for real vector retrieval

Running the service does not require Node, pnpm, Java, Maven, or a manually created Docker volume.

### Linux / macOS

```bash
git clone https://github.com/Chen-AnJu/ReasonWeave.git
cd ReasonWeave
./scripts/init-local.sh
docker compose up -d
curl --fail-with-body http://127.0.0.1:8080/api/v1/runtime
```

### Windows PowerShell

```powershell
git clone https://github.com/Chen-AnJu/ReasonWeave.git
Set-Location ReasonWeave
powershell -ExecutionPolicy Bypass -File .\scripts\init-local.ps1
docker compose up -d
Invoke-RestMethod http://127.0.0.1:8080/api/v1/runtime
```

Open <http://127.0.0.1:8080>. OpenAPI is available at <http://127.0.0.1:8080/api/v1/docs>.

The default Compose file pulls fixed preview images and exposes only the frontend on `127.0.0.1:8080`. Backend, PostgreSQL, and Ollama have no host ports. The first start downloads the approximately 639 MB `qwen3-embedding:0.6b` model into `reasonweave-ollama-model-cache`:

```bash
docker compose logs -f ollama-model
```

Normal restarts, upgrades, and `docker compose down` reuse that model volume. See [Troubleshooting](docs/troubleshooting.en.md) for resource, port, index, and volume issues.

Measured logical image sizes for this `linux/amd64` preview candidate are approximately 134 MiB for the backend, 28 MiB for the frontend, 113 MiB for PostgreSQL/pgvector, and 60 MiB for the CPU-only Ollama runtime; the model cache occupies 609.6 MiB. In an isolated acceptance run, downloading the 639 MB model into an empty cache took 164.74 seconds, a restart with cached model and index reached full health in 56.70 seconds, and the healthy Kubernetes and cold-holding end-to-end API examples took 3.99 and 3.58 seconds. These are reference measurements, not a performance guarantee; initial total time also depends on registry, model network, and disk performance.

> To build from source, run `docker compose -f compose.yml -f compose.build.yml up -d --build`. Only this developer path needs build tooling and additional time.

## Exercise the full API flow with one command

If Node.js 22 is installed, the dependency-free synthetic clients run either built-in domain:

```bash
node examples/quickstart/run.mjs --scenario kubernetes
node examples/quickstart/run.mjs --scenario cold-holding
```

They discover the pack, create an event, import a Bundle, confirm Observations, investigate, retrieve citations and next evidence, then fetch graph and audit data. Node is only needed by this optional client, not by the service.

## Pure API integration

Clients follow a discovery-first flow instead of hard-coding Kubernetes or cold-holding fields:

```text
GET  /api/v1/runtime
GET  /api/v1/domain-packs
GET  /api/v1/domain-packs/{key}/versions/{version}/event-types/{eventType}
POST /api/v1/events                                      Idempotency-Key
POST /api/v1/events/{eventId}/evidence/bundles
PATCH /api/v1/observations/{observationId}               If-Match
POST /api/v1/events/{eventId}/investigations             Idempotency-Key
GET  /api/v1/investigations/{investigationId}
GET  /api/v1/investigations/{investigationId}/next-evidence
GET  /api/v1/events/{eventId}/graph
GET  /api/v1/events/{eventId}/audit
```

Responses use `{data, meta}` or `{error, meta}` and preserve `meta.request_id` for log correlation. See the [English API Quick Start](docs/api-quickstart.en.md) for Bash, PowerShell, success/error responses, and complete payloads. The versioned contract is [`contracts/openapi/reasonweave-v1.json`](contracts/openapi/reasonweave-v1.json).

## Two built-in domains from different industries

| Domain Pack | Scope | Sources and boundary |
| --- | --- | --- |
| `kubernetes-pod-diagnostics/1.0.0` | Kubernetes 1.35–1.37 Pod scheduling, image, configuration/mount, startup, and health failures | Rules reference the Apache-2.0 K8sGPT Pod Analyzer; knowledge is derived from CC BY 4.0 Kubernetes documentation; no automatic remediation |
| `cold-holding-excursion-diagnostics/1.0.0` | Power/control, refrigeration response, operational heat load, and measurement anomalies in retail, hospitality, and cold rooms | Knowledge is based on public FDA/DOE material; site-supplied thresholds do not become food safety, disposal, compliance, or HACCP decisions |

Both packs use the same Event, Bundle, retrieval, investigation, graph, and audit APIs. That is the proof that the core is not Kubernetes-specific. Component sources, licenses, upstream versions, and hashes live in each pack's `LICENSES.yaml` and `NOTICE.md`.

## Console

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/reasonweave-investigation.webp" alt="Investigation workbench"></td>
    <td width="50%"><img src="docs/screenshots/reasonweave-graph.webp" alt="Causal graph"></td>
  </tr>
  <tr>
    <td><strong>Investigation workbench</strong><br>Freeze evidence, knowledge index, and rules; inspect reproducible contributions, support, and coverage.</td>
    <td><strong>Graph</strong><br>Trace Evidence, Observation, Hypothesis, Knowledge, and Event paths; knowledge edges remain non-scoring.</td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/reasonweave-retrieval.webp" alt="Retrieval inspector"></td>
    <td><img src="docs/screenshots/reasonweave-domain-packs.webp" alt="Domain Packs"></td>
  </tr>
  <tr>
    <td><strong>Retrieval inspector</strong><br>Inspect FTS, vector, RRF, applicability, and final selection independently.</td>
    <td><strong>Domain Packs</strong><br>Read event types, rules, knowledge, licenses, and index readiness from real manifests.</td>
  </tr>
</table>

## Trust and privacy boundary

- No built-in telemetry; events, evidence, indexes, and results stay in local database and Blob volumes.
- A `.rwpack` contains only schemas, rules, knowledge, presentation, and licenses. It cannot execute third-party code.
- A Bundle must match its event's pack, event type, and primary subject. Any invalid item rolls back the whole Bundle.
- Every Run records the pack fingerprint, evidence snapshot, knowledge index, retrieval results, and citations.
- The Kubernetes collector does not read Secret values, environment-variable values, service-account tokens, or complete logs.
- The cold-holding collector does not access the network or embed complete raw telemetry in its Bundle.
- The preview has no login, API key, RBAC, multi-tenancy, rate limit, or public-Internet security boundary.

Read the [public architecture](docs/architecture.md) for the full component and trust boundary. Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md).

## Development and verification

Source development requires Node.js 22, pnpm 11, and Java 21:

```bash
pnpm install --frozen-lockfile
pnpm verify:open-source
pnpm cli:test
pnpm frontend:check
pnpm frontend:test
pnpm frontend:build
pnpm api:types:check
pnpm backend:test
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the minimum validation matrix. The project does not depend on GitHub Actions; maintainers can run the same public commands in any controlled environment.

## Repository layout

```text
backend/                 Spring Boot reasoning API
frontend/                React console and same-origin gateway
contracts/               EventIR, Domain Pack, Bundle, and OpenAPI contracts
domain-packs/            Built-in production Domain Packs
fixtures/domain-packs/   Domain-neutral test pack
tools/                   rwpack and rw-evidence CLIs
examples/                Optional pure-API clients
docs/                    API, architecture, examples, screenshots, and demo
infra/                   PostgreSQL/Ollama images and isolated test config
compose.yml              Prebuilt-image quick start
compose.build.yml        Source-build override
```

## Documentation

- [API Quick Start (English)](docs/api-quickstart.en.md) · [API 快速开始（中文）](docs/api-quickstart.md)
- [Public architecture](docs/architecture.md)
- [Domain Pack CLI](tools/domain-pack-cli/README.md)
- [Evidence collectors](tools/evidence-cli/README.md)
- [Troubleshooting](docs/troubleshooting.en.md) · [Support](SUPPORT.md)
- [Contributing](CONTRIBUTING.md) · [Security](SECURITY.md) · [Changelog](CHANGELOG.md)
- [Visual asset provenance and hashes](ASSET_PROVENANCE.md)

## Status

`0.4.1` has a complete two-domain investigation loop with real embeddings, a pure API, and a generic console. It remains an open-source preview. Authentication, RBAC, multi-tenancy, asynchronous investigations, webhooks, automatic remediation, a remote Domain Pack Registry, hot reload, billing, and a marketplace are explicitly deferred.

## License

Source code is available under the [Apache License 2.0](LICENSE); see [NOTICE](NOTICE) for third-party notices. Visual asset terms are recorded in [ASSET_PROVENANCE.md](ASSET_PROVENANCE.md). Domain Pack component licenses are declared in each pack's `LICENSES.yaml` and `NOTICE.md`.
