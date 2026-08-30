#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { executable, run } from './process-utils.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const contractPath = resolve(root, 'contracts/openapi/reasonweave-v1.json');
const expectedTypesPath = resolve(root, 'frontend/src/api/schema.d.ts');
const artifactDirectory = resolve(root, '.artifacts');
const generatedTypesPath = resolve(artifactDirectory, 'openapi-schema.generated.d.ts');
const liveContractPath = resolve(artifactDirectory, 'openapi-live.json');
const args = process.argv.slice(2);
let baseUrl = '';

for (let index = 0; index < args.length; index += 1) {
  if (args[index] !== '--base-url' || !args[index + 1]) {
    throw new Error('Usage: node scripts/verify-openapi-types.mjs [--base-url http://127.0.0.1:8080]');
  }
  baseUrl = args[index + 1];
  index += 1;
}

mkdirSync(artifactDirectory, { recursive: true });
if (baseUrl) {
  const response = await fetch(`${baseUrl.replace(/\/$/, '')}/api/v1/openapi`);
  if (!response.ok) throw new Error(`Unable to fetch live OpenAPI: HTTP ${response.status}`);
  const live = await response.json();
  delete live.servers;
  writeFileSync(liveContractPath, JSON.stringify(live), 'utf8');
  const fixed = JSON.parse(readFileSync(contractPath, 'utf8'));
  if (canonical(fixed) !== canonical(live)) throw new Error(`Backend OpenAPI has drifted from ${contractPath}`);
}

run(executable('pnpm'), [
  '--dir', 'frontend', 'exec', 'openapi-typescript',
  '../contracts/openapi/reasonweave-v1.json', '-o', '../.artifacts/openapi-schema.generated.d.ts',
], { cwd: root, stdio: 'inherit' });

const expectedHash = hash(expectedTypesPath);
const generatedHash = hash(generatedTypesPath);
if (expectedHash !== generatedHash) {
  throw new Error('Generated TypeScript API types have drifted. Regenerate from contracts/openapi/reasonweave-v1.json.');
}
console.log(JSON.stringify({
  fixed_contract: contractPath,
  generated_types: expectedTypesPath,
  sha256: expectedHash,
  live_checked: Boolean(baseUrl),
  status: 'MATCHED',
}, null, 2));

function hash(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex');
}

function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}
