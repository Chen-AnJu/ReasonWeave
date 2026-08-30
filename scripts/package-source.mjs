#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { run } from './process-utils.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const artifactRoot = resolve(root, '.artifacts', 'source');
const output = process.argv[2]
  ? resolve(process.argv[2])
  : resolve(artifactRoot, 'reasonweave-0.4.1-source.tar.gz');
const outputRelative = relative(resolve(root, '.artifacts'), output);
if (!outputRelative || outputRelative === '..' || outputRelative.startsWith(`..${sep}`) || isAbsolute(outputRelative)) {
  throw new Error(`Source package output must stay inside ${resolve(root, '.artifacts')}`);
}

run(process.execPath, [resolve(root, 'scripts/verify-open-source-readiness.mjs')], { cwd: root, stdio: 'inherit' });
const candidates = run('git', ['-C', root, 'ls-files', '--cached', '--others', '--exclude-standard', '-z'])
  .stdout.split('\0').filter(Boolean).filter((path) => existsSync(resolve(root, path))).sort();
if (!candidates.length) throw new Error('The source candidate is empty.');

mkdirSync(dirname(output), { recursive: true });
rmSync(output, { force: true });
rmSync(`${output}.sha256`, { force: true });
const listPath = resolve(artifactRoot, 'source-files.txt');
writeFileSync(listPath, `${candidates.join('\n')}\n`, 'utf8');
try {
  run('tar', ['-czf', output, '-T', listPath], { cwd: root, stdio: 'inherit' });
} finally {
  rmSync(listPath, { force: true });
}
const hash = createHash('sha256').update(readFileSync(output)).digest('hex');
writeFileSync(`${output}.sha256`, `${hash}  ${output.split(/[\\/]/).at(-1)}\n`, 'utf8');
console.log(JSON.stringify({ archive: output, sha256: hash, files: candidates.length }, null, 2));
