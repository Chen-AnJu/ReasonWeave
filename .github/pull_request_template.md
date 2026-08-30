## What changed

Describe the user-visible result and keep unrelated cleanup out of this pull request.

## Trust boundaries

- [ ] Knowledge still contributes no evidence score.
- [ ] Investigation and retrieval snapshots remain immutable.
- [ ] Domain Packs remain data-only and collectors remain separate processes.
- [ ] No credentials, production evidence, private infrastructure, or generated artifacts are included.

## Verification

List the exact commands run and their results. For public contracts, migrations, scoring, pack validation, or collector privacy changes, include a focused regression test.

- [ ] `pnpm verify:open-source`
- [ ] `pnpm cli:test` (when CLI or pack behavior changes)
- [ ] `pnpm frontend:check && pnpm frontend:test && pnpm frontend:build` (when frontend changes)
- [ ] `pnpm api:types:check` (when API or types change)
- [ ] `pnpm backend:test` (when backend changes)
