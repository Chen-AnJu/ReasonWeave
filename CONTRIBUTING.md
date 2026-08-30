# Contributing

ReasonWeave welcomes focused bug fixes, tests, documentation improvements, and
auditable Domain Packs. Keep changes small and avoid mixing unrelated cleanup
with a functional change.

## Development checks

Use Node.js 22, pnpm 11, and Java 21. PowerShell is not required on Linux or
macOS. Install the Playwright browser once before frontend end-to-end tests:

```console
pnpm --dir frontend exec playwright install chromium
```

Before proposing a change, run the checks
that cover the affected component:

```console
pnpm install --frozen-lockfile
pnpm verify:open-source
pnpm cli:test
pnpm frontend:check
pnpm frontend:test
pnpm frontend:build
```

Backend unit tests run through the Maven Wrapper with Java 21:

```console
pnpm backend:test
# equivalent: cd backend && ./mvnw -B verify
```

On Windows the equivalent wrapper is `backend\mvnw.cmd`. PostgreSQL/pgvector
integration tests use disposable infrastructure. End-to-end checks must use
test-only resources and must never target a production database or cluster.

| Change | Minimum checks |
| --- | --- |
| Documentation only | `pnpm verify:open-source` and link review |
| Domain Pack or collector | `pnpm cli:test`, `pnpm cli:pack`, relevant Golden fixtures |
| Frontend | `pnpm frontend:check`, `pnpm frontend:test`, `pnpm frontend:build`; Playwright for flows |
| Backend/API | `pnpm backend:test`, `pnpm api:types:check`; integration tests for persistence |
| Compose/runtime image | clean Compose startup plus HIGH/CRITICAL image scanning |

## Core invariants

- Knowledge may ground and explain a hypothesis, but must never contribute to
  the evidence support score.
- Investigation Runs and their retrieval/evidence snapshots are immutable.
- A Domain Pack is data only. Do not add executable hooks or automatic fixes.
- New predicates require a value schema, Chinese presentation metadata, rules,
  attributed knowledge, and Golden Query/Investigation coverage.
- Collectors must minimize data and must not collect Secret values, service
  account tokens, environment-variable values, or complete logs by default.
- Do not commit credentials, production data, kubeconfig files, private
  operational material, or generated build output.

Changes to public contracts, migrations, scoring, pack validation, or collector
privacy boundaries should include a regression test and an explicit rationale.
