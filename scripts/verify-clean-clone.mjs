#!/usr/bin/env node
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { executable, run } from './process-utils.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
let repository = root;
let autocrlf = process.platform === 'win32' ? 'true' : 'input';
let keep = false;
const args = process.argv.slice(2);
for (let index = 0; index < args.length; index += 1) {
  const argument = args[index];
  if (argument === '--help' || argument === '-h') { console.log(usage()); process.exit(0); }
  if (argument === '--keep') { keep = true; continue; }
  if (argument !== '--repository' && argument !== '--autocrlf') throw new Error(`Unknown argument: ${argument}\n\n${usage()}`);
  const value = args[index + 1];
  if (!value) throw new Error(`${argument} requires a value.\n\n${usage()}`);
  if (argument === '--repository') repository = value;
  else {
    if (!['true', 'false', 'input'].includes(value)) throw new Error('--autocrlf must be true, false, or input.');
    autocrlf = value;
  }
  index += 1;
}

const temporaryRoot = mkdtempSync(resolve(tmpdir(), 'reasonweave-clean-clone-'));
const checkout = resolve(temporaryRoot, 'ReasonWeave');
const started = Date.now();
try {
  run('git', ['-c', `core.autocrlf=${autocrlf}`, 'clone', '--no-local', repository, checkout], { stdio: 'inherit' });
  run(executable('pnpm'), ['install', '--frozen-lockfile'], { cwd: checkout, stdio: 'inherit' });
  for (const pack of [
    'domain-packs/kubernetes-pod-diagnostics/1.0.0',
    'domain-packs/cold-holding-excursion-diagnostics/1.0.0',
  ]) run(executable('pnpm'), ['rwpack', 'validate', pack], { cwd: checkout, stdio: 'inherit' });
  for (const task of ['verify:open-source', 'cli:test', 'api:types:check']) {
    run(executable('pnpm'), [task], { cwd: checkout, stdio: 'inherit' });
  }
  console.log(JSON.stringify({
    status: 'PASSED',
    repository,
    autocrlf,
    elapsed_seconds: Number(((Date.now() - started) / 1000).toFixed(2)),
    checkout: keep ? checkout : undefined,
  }, null, 2));
} finally {
  if (!keep) rmSync(temporaryRoot, { recursive: true, force: true });
}

function usage() {
  return `ReasonWeave clean-clone verification

Usage:
  node scripts/verify-clean-clone.mjs [--repository URL_OR_PATH] [--autocrlf true|false|input] [--keep]

The command validates both production Domain Packs, open-source boundaries,
CLI tests, and OpenAPI type drift from a fresh checkout. It never starts Docker.`;
}
