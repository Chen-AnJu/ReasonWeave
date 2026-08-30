#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
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
const entries = readCommittedFiles();
const committedPaths = entries.map(({ path }) => path);
if (JSON.stringify(committedPaths) !== JSON.stringify(candidates)) {
  throw new Error('Tracked-file enumeration does not match the committed Git tree.');
}
const compressed = gzipSync(createTarArchive(entries), { level: 9, mtime: 0 });
// zlib writes a platform-specific OS byte (Windows=10, Unix=3). It is not
// covered by the gzip CRC, so normalize it after compression.
compressed[9] = 255;
writeFileSync(output, compressed);
const initializerEntry = run('tar', ['-tvzf', output, 'scripts/init-local.sh']).stdout.trim();
if (!initializerEntry.startsWith('-rwx')) {
  rmSync(output, { force: true });
  throw new Error('Source archive lost the executable mode for scripts/init-local.sh.');
}
const hash = createHash('sha256').update(readFileSync(output)).digest('hex');
writeFileSync(`${output}.sha256`, `${hash}  ${output.split(/[\\/]/).at(-1)}\n`, 'utf8');
console.log(JSON.stringify({ archive: output, sha256: hash, files: candidates.length }, null, 2));

function readCommittedFiles() {
  const tree = gitBuffer(['ls-tree', '-rz', '--full-tree', 'HEAD']);
  const entries = tree.toString('utf8').split('\0').filter(Boolean).map((record) => {
    const separator = record.indexOf('\t');
    if (separator < 0) throw new Error('Git returned an invalid tree record.');
    const [mode, type, object] = record.slice(0, separator).split(' ');
    const path = record.slice(separator + 1);
    if (type !== 'blob' || !['100644', '100755'].includes(mode)) {
      throw new Error(`Source packages only support regular tracked files: ${path} (${mode} ${type})`);
    }
    return { path, mode: mode === '100755' ? 0o755 : 0o644, object };
  }).sort((left, right) => Buffer.compare(Buffer.from(left.path), Buffer.from(right.path)));

  const batch = gitBuffer(['cat-file', '--batch'], `${entries.map(({ object }) => object).join('\n')}\n`);
  let offset = 0;
  for (const entry of entries) {
    const headerEnd = batch.indexOf(0x0a, offset);
    if (headerEnd < 0) throw new Error(`Git blob header is missing for ${entry.path}.`);
    const [object, type, rawSize] = batch.subarray(offset, headerEnd).toString('utf8').split(' ');
    const size = Number(rawSize);
    if (object !== entry.object || type !== 'blob' || !Number.isSafeInteger(size) || size < 0) {
      throw new Error(`Git returned an invalid blob header for ${entry.path}.`);
    }
    const contentStart = headerEnd + 1;
    const contentEnd = contentStart + size;
    if (contentEnd >= batch.length || batch[contentEnd] !== 0x0a) {
      throw new Error(`Git returned a truncated blob for ${entry.path}.`);
    }
    entry.content = batch.subarray(contentStart, contentEnd);
    offset = contentEnd + 1;
  }
  if (offset !== batch.length) throw new Error('Git returned unexpected trailing blob data.');
  return entries;
}

function gitBuffer(args, input) {
  const result = spawnSync('git', ['-C', root, ...args], {
    input,
    maxBuffer: 128 * 1024 * 1024,
    windowsHide: true,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(result.stderr.toString('utf8') || `git ${args.join(' ')} failed`);
  return result.stdout;
}

function createTarArchive(entries) {
  const blocks = [];
  for (const entry of entries) {
    const header = Buffer.alloc(512);
    const { name, prefix } = splitTarPath(entry.path);
    writeText(header, 0, 100, name);
    writeOctal(header, 100, 8, entry.mode);
    writeOctal(header, 108, 8, 0);
    writeOctal(header, 116, 8, 0);
    writeOctal(header, 124, 12, entry.content.length);
    writeOctal(header, 136, 12, 0);
    header.fill(0x20, 148, 156);
    header[156] = 0x30;
    writeText(header, 257, 6, 'ustar\0');
    writeText(header, 263, 2, '00');
    writeText(header, 345, 155, prefix);
    const checksum = header.reduce((sum, byte) => sum + byte, 0);
    writeChecksum(header, checksum);
    blocks.push(header, entry.content);
    const padding = (512 - (entry.content.length % 512)) % 512;
    if (padding) blocks.push(Buffer.alloc(padding));
  }
  blocks.push(Buffer.alloc(1024));
  return Buffer.concat(blocks);
}

function splitTarPath(path) {
  if (Buffer.byteLength(path) <= 100) return { name: path, prefix: '' };
  for (let index = path.lastIndexOf('/'); index > 0; index = path.lastIndexOf('/', index - 1)) {
    const prefix = path.slice(0, index);
    const name = path.slice(index + 1);
    if (Buffer.byteLength(prefix) <= 155 && Buffer.byteLength(name) <= 100) return { name, prefix };
  }
  throw new Error(`Tracked path cannot be represented by the USTAR format: ${path}`);
}

function writeText(buffer, offset, length, value) {
  const encoded = Buffer.from(value, 'utf8');
  if (encoded.length > length) throw new Error(`USTAR field exceeds ${length} bytes: ${value}`);
  encoded.copy(buffer, offset);
}

function writeOctal(buffer, offset, length, value) {
  const encoded = `${value.toString(8).padStart(length - 1, '0')}\0`;
  writeText(buffer, offset, length, encoded);
}

function writeChecksum(buffer, value) {
  writeText(buffer, 148, 8, `${value.toString(8).padStart(6, '0')}\0 `);
}
