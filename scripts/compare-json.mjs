#!/usr/bin/env node
import { readFile } from 'node:fs/promises';

const [expectedPath, actualPath] = process.argv.slice(2);
if (!expectedPath || !actualPath) {
  console.error('Usage: node compare-json.mjs <expected.json> <actual.json>');
  process.exit(2);
}

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]));
  }
  return value;
}

const expected = canonical(JSON.parse(await readFile(expectedPath, 'utf8')));
const actual = canonical(JSON.parse(await readFile(actualPath, 'utf8')));
if (JSON.stringify(expected) !== JSON.stringify(actual)) {
  console.error(`JSON documents differ: ${expectedPath} != ${actualPath}`);
  process.exit(1);
}
