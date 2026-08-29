# Contributing

ReasonWeave welcomes focused bug fixes, tests, documentation improvements, and
auditable Domain Packs. Keep changes small and avoid mixing unrelated cleanup
with a functional change.

## Development checks

Use Node.js 22, pnpm 11, and Java 21. Before proposing a change, run the checks
that cover the affected component:

```console
pnpm install --frozen-lockfile
pnpm verify:open-source
pnpm cli:test
pnpm frontend:check
pnpm frontend:test
pnpm frontend:build
```

Backend unit tests run with Java 21. PostgreSQL/pgvector integration tests use
disposable infrastructure. End-to-end checks must use test-only resources and
must never target a production database or cluster.

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
