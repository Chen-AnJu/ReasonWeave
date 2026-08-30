#!/usr/bin/env node
import { existsSync, lstatSync, readdirSync, rmSync, statSync } from 'node:fs';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const targets = [
  '.artifacts', 'backend/target', 'frontend/dist', 'frontend/test-results',
  'frontend/playwright-report', 'frontend/output/playwright',
];
let directories = 0;
let bytes = 0;
for (const target of targets) {
  const absolute = resolve(root, target);
  const pathFromRoot = relative(root, absolute);
  if (!pathFromRoot || pathFromRoot === '..' || pathFromRoot.startsWith(`..${sep}`) || isAbsolute(pathFromRoot)) {
    throw new Error(`Refusing cleanup target outside the repository: ${absolute}`);
  }
  if (!existsSync(absolute)) continue;
  if (lstatSync(absolute).isSymbolicLink()) throw new Error(`Refusing cleanup target that is a symbolic link: ${absolute}`);
  bytes += sizeOf(absolute);
  rmSync(absolute, { recursive: true, force: true });
  directories += 1;
}
console.log(`Generated artifact cleanup removed ${directories} directorie(s), ${bytes} byte(s).`);

function sizeOf(path) {
  const stats = statSync(path);
  if (stats.isFile()) return stats.size;
  return readdirSync(path).reduce((total, entry) => total + sizeOf(resolve(path, entry)), 0);
}
