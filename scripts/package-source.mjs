#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';
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
const worktreeStatus = run('git', ['-C', root, 'status', '--porcelain=v1', '--untracked-files=all']).stdout.trim();
if (worktreeStatus) {
  throw new Error('Source packages must be created from a clean Git worktree. Run git status, then commit or remove the changes first.');
}
const candidates = run('git', ['-C', root, 'ls-files', '-z']).stdout.split('\0').filter(Boolean).sort();
if (!candidates.length) throw new Error('The source candidate is empty.');

mkdirSync(dirname(output), { recursive: true });
rmSync(output, { force: true });
rmSync(`${output}.sha256`, { force: true });
const tarPath = resolve(artifactRoot, `.reasonweave-source-${process.pid}.tar`);
try {
  run('git', ['-C', root, 'archive', '--format=tar', '--output', tarPath, 'HEAD'], { stdio: 'inherit' });
  writeFileSync(output, gzipSync(readFileSync(tarPath), { level: 9, mtime: 0 }));
} finally {
  rmSync(tarPath, { force: true });
}
const initializerEntry = run('tar', ['-tvzf', output, 'scripts/init-local.sh']).stdout.trim();
if (!initializerEntry.startsWith('-rwx')) {
  rmSync(output, { force: true });
  throw new Error('Source archive lost the executable mode for scripts/init-local.sh.');
}
const hash = createHash('sha256').update(readFileSync(output)).digest('hex');
writeFileSync(`${output}.sha256`, `${hash}  ${output.split(/[\\/]/).at(-1)}\n`, 'utf8');
console.log(JSON.stringify({ archive: output, sha256: hash, files: candidates.length }, null, 2));
