#!/usr/bin/env node
import { createHash, randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { executable, isWindows, run } from './process-utils.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const artifactRoot = resolve(root, '.artifacts');
const argument = process.argv[2];
const outputRoot = argument ? resolve(argument) : resolve(artifactRoot, 'cli-release');
const outputRelative = relative(artifactRoot, outputRoot);
if (!outputRelative || outputRelative === '..' || outputRelative.startsWith(`..${sep}`) || isAbsolute(outputRelative)) {
  throw new Error(`CLI output must be inside ${artifactRoot}`);
}
rmSync(outputRoot, { recursive: true, force: true });
mkdirSync(outputRoot, { recursive: true });

for (const directory of ['tools/domain-pack-cli', 'tools/evidence-cli']) {
  run(executable('pnpm'), ['--dir', resolve(root, directory), 'pack', '--pack-destination', outputRoot], { stdio: 'inherit' });
}
const archives = readdirSync(outputRoot).filter((name) => name.endsWith('.tgz')).sort();
if (archives.length !== 2) throw new Error(`Expected exactly two CLI archives, found ${archives.length}.`);
for (const archive of archives) {
  const archivePath = resolve(outputRoot, archive);
  const entries = run('tar', ['-tzf', archivePath]).stdout.split(/\r?\n/).filter(Boolean);
  for (const required of ['package/package.json', 'package/README.md', 'package/LICENSE', 'package/src/cli.mjs']) {
    if (!entries.includes(required)) throw new Error(`${archive} is missing ${required}.`);
  }
  if (archive.includes('domain-pack-cli') && !entries.includes('package/src/manifest.schema.json')) {
    throw new Error(`${archive} is missing package/src/manifest.schema.json.`);
  }
  const forbidden = entries.filter((entry) => /(^|\/)node_modules\//.test(entry)
    || entry.startsWith('package/test/') || entry.startsWith('package/.artifacts/'));
  if (forbidden.length) throw new Error(`${archive} contains forbidden entries: ${forbidden.join(', ')}`);
}
writeFileSync(resolve(outputRoot, 'SHA256SUMS'), archives.map((archive) => {
  const hash = createHash('sha256').update(readFileSync(resolve(outputRoot, archive))).digest('hex');
  return `${hash}  ${archive}`;
}).join('\n') + '\n', 'utf8');

const smokeRoot = resolve(tmpdir(), `reasonweave-cli-smoke-${process.pid}-${randomUUID()}`);
try {
  mkdirSync(smokeRoot, { recursive: true });
  writeFileSync(resolve(smokeRoot, 'package.json'), '{\n  "private": true\n}\n', 'utf8');
  const store = run(executable('pnpm'), ['store', 'path']).stdout.trim().split(/\r?\n/).at(-1);
  run(executable('pnpm'), [
    '--dir', smokeRoot, '--store-dir', store, 'add', '--offline', '--ignore-scripts',
    ...archives.map((archive) => resolve(outputRoot, archive)),
  ], { stdio: 'inherit' });
  const bin = (name) => resolve(smokeRoot, 'node_modules', '.bin', `${name}${isWindows ? '.cmd' : ''}`);
  for (const [name, marker] of [['rwpack', 'rwpack validate'], ['rw-evidence', 'rw-evidence kubernetes collect']]) {
    if (!existsSync(bin(name))) throw new Error(`Installed archive did not expose ${name}.`);
    const help = run(bin(name), ['--help'], { shell: isWindows }).stdout;
    if (!help.includes(marker)) throw new Error(`${name} --help failed after archive installation.`);
  }
  const fixture = resolve(root, 'fixtures/domain-packs/equipment-fault-test/1.0.0');
  const archive = resolve(smokeRoot, 'equipment-fault-test.rwpack');
  const installed = resolve(smokeRoot, 'installed');
  run(bin('rwpack'), ['validate', fixture], { shell: isWindows });
  run(bin('rwpack'), ['pack', fixture, '--out', archive], { shell: isWindows });
  run(bin('rwpack'), ['verify', archive], { shell: isWindows });
  run(bin('rwpack'), ['install', archive, '--root', installed], { shell: isWindows });
  if (!run(bin('rwpack'), ['list', '--root', installed], { shell: isWindows }).stdout.includes('equipment-fault-test')) {
    throw new Error('Installed rwpack failed to list the installed fixture.');
  }
  const coldFixture = resolve(root, 'fixtures/cold-holding/zenodo-15130001');
  const bundle = resolve(smokeRoot, 'cold-holding-bundle.json');
  run(bin('rw-evidence'), [
    'cold-holding', 'collect', '--event-ir', resolve(coldFixture, 'event-ir.json'),
    '--sources', resolve(coldFixture, 'sources.json'), '--telemetry', resolve(coldFixture, 'telemetry.csv'),
    '--out', bundle,
  ], { shell: isWindows });
  if (!JSON.parse(readFileSync(bundle, 'utf8')).evidence_items?.length) {
    throw new Error('Installed rw-evidence produced an empty cold-holding bundle.');
  }
} finally {
  rmSync(smokeRoot, { recursive: true, force: true });
}
console.log(`CLI release artifacts passed archive and install smoke checks: ${outputRoot}`);
