import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { cp, mkdtemp, readFile, readdir, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { createHash } from 'node:crypto';
import { gzipSync } from 'node:zlib';
import test from 'node:test';

const runFile = promisify(execFile);
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const cli = join(root, 'tools/domain-pack-cli/src/cli.mjs');
const source = join(root, 'domain-packs/kubernetes-pod-diagnostics/1.0.0');

async function run(...args) {
  return runFile(process.execPath, [cli, ...args], { cwd: root, encoding: 'utf8' });
}

test('ships the canonical manifest schema beside the installed CLI entry point', async () => {
  const canonical = await readFile(join(root, 'contracts/domain-pack/1.0/manifest.schema.json'));
  const packaged = await readFile(join(root, 'tools/domain-pack-cli/src/manifest.schema.json'));
  assert.equal(createHash('sha256').update(packaged).digest('hex'), createHash('sha256').update(canonical).digest('hex'));
});

function tarEntry(name, size, type = '0') {
  const header = Buffer.alloc(512);
  header.write(name, 0, 100, 'utf8');
  header.write('0000644\0', 100, 8, 'ascii');
  header.write('0000000\0', 108, 8, 'ascii');
  header.write('0000000\0', 116, 8, 'ascii');
  header.write(`${size.toString(8).padStart(11, '0')}\0`, 124, 12, 'ascii');
  header.write('00000000000\0', 136, 12, 'ascii');
  header.fill(0x20, 148, 156);
  header.write(type, 156, 1, 'ascii');
  header.write('ustar\0', 257, 6, 'ascii');
  header.write('00', 263, 2, 'ascii');
  const checksum = [...header].reduce((sum, value) => sum + value, 0);
  header.write(`${checksum.toString(8).padStart(6, '0')}\0 `, 148, 8, 'ascii');
  const data = Buffer.alloc(Math.ceil(size / 512) * 512);
  return Buffer.concat([header, data]);
}

async function writeTarGzip(path, entries) {
  const archive = Buffer.concat([...entries, Buffer.alloc(1024)]);
  await writeFile(path, gzipSync(archive, { level: 9 }));
}

test('validates, deterministically packages, verifies, installs, and lists a pack', async () => {
  const temporary = await mkdtemp(join(tmpdir(), 'rwpack-test-'));
  const first = join(temporary, 'first.rwpack');
  const second = join(temporary, 'second.rwpack');
  const installed = join(temporary, 'installed');

  const validation = JSON.parse((await run('validate', source)).stdout);
  assert.equal(validation.valid, true);
  await run('pack', source, '--out', first);
  await run('pack', source, '--out', second);
  const digest = async (file) => createHash('sha256').update(await readFile(file)).digest('hex');
  assert.equal(await digest(first), await digest(second));
  assert.equal(JSON.parse((await run('verify', first)).stdout).fingerprint, validation.fingerprint);
  await run('install', first, '--root', installed);
  const listed = JSON.parse((await run('list', '--root', installed)).stdout);
  assert.deepEqual(listed, [{
    key: 'kubernetes-pod-diagnostics', version: '1.0.0', fingerprint: validation.fingerprint,
  }]);
  assert.ok((await readdir(installed)).every((name) => !name.startsWith('.rwpack-stage-')));
  await assert.rejects(run('install', first, '--root', installed), /already installed/);

  const drifted = join(temporary, 'drifted');
  await cp(source, drifted, { recursive: true });
  await writeFile(join(drifted, 'NOTICE.md'), `${await readFile(join(drifted, 'NOTICE.md'), 'utf8')}drift\n`);
  await assert.rejects(run('validate', drifted), /checksums do not match/);
});

test('reports its version and actionable missing-target errors', async () => {
  assert.equal((await run('--version')).stdout.trim(), '0.4.1');
  await assert.rejects(run('validate'), /Missing <target> for 'validate'.*rwpack --help/s);
});

test('initializes a minimal valid Domain Pack template', async () => {
  const temporary = await mkdtemp(join(tmpdir(), 'rwpack-init-'));
  const target = join(temporary, 'example-diagnostics');
  await run('init', target, '--key', 'example-diagnostics', '--version', '0.1.0');
  const validation = JSON.parse((await run('validate', target)).stdout);
  assert.equal(validation.key, 'example-diagnostics');
  assert.equal(validation.version, '0.1.0');
});

test('rejects links, oversized files, and missing license declarations', async () => {
  const temporary = await mkdtemp(join(tmpdir(), 'rwpack-invalid-'));

  const linked = join(temporary, 'linked');
  await cp(source, linked, { recursive: true });
  await symlink(join(linked, 'NOTICE.md'), join(linked, 'knowledge/link.md'));
  await assert.rejects(run('validate', linked), /Links are not allowed/);

  const oversized = join(temporary, 'oversized');
  await cp(source, oversized, { recursive: true });
  await writeFile(join(oversized, 'knowledge/oversized.md'), Buffer.alloc(5 * 1024 * 1024 + 1));
  await assert.rejects(run('validate', oversized), /5 MiB/);

  const unlicensed = join(temporary, 'unlicensed');
  await cp(source, unlicensed, { recursive: true });
  await writeFile(join(unlicensed, 'LICENSES.yaml'), 'components: []\n');
  await assert.rejects(run('validate', unlicensed), /at least one component/);
});

test('rejects incompatible engines and unsupported rule semantics', async () => {
  const temporary = await mkdtemp(join(tmpdir(), 'rwpack-semantics-'));

  const incompatible = join(temporary, 'incompatible');
  await cp(source, incompatible, { recursive: true });
  const manifest = join(incompatible, 'manifest.yaml');
  await writeFile(
    manifest,
    (await readFile(manifest, 'utf8')).replace('>=0.4.1 <0.5.0', '>=0.5.0 <0.6.0'),
  );
  await assert.rejects(run('validate', incompatible), /does not include rwpack 0\.4\.1/);

  const invalidRule = join(temporary, 'invalid-rule');
  await cp(source, invalidRule, { recursive: true });
  const rules = join(invalidRule, 'rules.yaml');
  await writeFile(
    rules,
    (await readFile(rules, 'utf8')).replace('relation: STRONGLY_SUPPORTS', 'relation: MAYBE_SUPPORTS'),
  );
  await assert.rejects(run('validate', invalidRule), /unsupported relation/);
});

test('rejects archive path traversal and highly compressed oversized content', async () => {
  const temporary = await mkdtemp(join(tmpdir(), 'rwpack-archive-safety-'));
  const traversal = join(temporary, 'traversal.rwpack');
  await writeTarGzip(traversal, [tarEntry('../escape.txt', 1)]);
  await assert.rejects(run('verify', traversal), /Unsafe archive entry/);

  const bomb = join(temporary, 'bomb.rwpack');
  const fiveMiB = 5 * 1024 * 1024;
  await writeTarGzip(
    bomb,
    Array.from({ length: 11 }, (_, index) => tarEntry(`pack/chunk-${index}.bin`, fiveMiB)),
  );
  await assert.rejects(run('verify', bomb), /Archive exceeds package limits/);
});
