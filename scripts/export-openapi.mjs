#!/usr/bin/env node
import { spawn } from 'node:child_process';
import { closeSync, copyFileSync, existsSync, mkdirSync, openSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { executable, isWindows, run } from './process-utils.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const backendRoot = resolve(root, 'backend');
const artifactRoot = resolve(root, '.artifacts');
const generatedContract = resolve(artifactRoot, 'reasonweave-v1.generated.json');
const stdoutPath = resolve(artifactRoot, 'openapi-export.stdout.log');
const stderrPath = resolve(artifactRoot, 'openapi-export.stderr.log');
const contractPath = resolve(root, 'contracts/openapi/reasonweave-v1.json');
const jarPath = resolve(backendRoot, 'target/reasonweave-backend-0.4.1.jar');
const domainPackRoot = resolve(root, 'domain-packs');

let port = 18081;
let skipBuild = false;
let javaHome = process.env.JAVA_HOME ?? '';
const args = process.argv.slice(2);
for (let index = 0; index < args.length; index += 1) {
  const argument = args[index];
  if (argument === '--skip-build') { skipBuild = true; continue; }
  if (argument !== '--port' && argument !== '--java-home') throw new Error(usage());
  const value = args[index + 1];
  if (!value) throw new Error(`${argument} requires a value.\n\n${usage()}`);
  if (argument === '--port') {
    port = Number(value);
    if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('--port must be between 1024 and 65535.');
  } else javaHome = value;
  index += 1;
}

if (!skipBuild) run(process.execPath, [resolve(root, 'scripts/test-backend.mjs'), '-DskipTests', 'package'], { stdio: 'inherit' });
if (!existsSync(jarPath)) throw new Error(`Backend executable JAR is missing: ${jarPath}`);
const java = javaHome ? resolve(javaHome, 'bin', isWindows ? 'java.exe' : 'java') : 'java';
const version = run(java, ['-version'], { allowFailure: true });
const versionText = `${version.stdout ?? ''}\n${version.stderr ?? ''}`;
if (version.status !== 0 || !/version "21(?:\.|\")/.test(versionText)) throw new Error(`Java 21 is required.\n${versionText.trim()}`);

mkdirSync(artifactRoot, { recursive: true });
mkdirSync(dirname(contractPath), { recursive: true });
const stdout = openSync(stdoutPath, 'w');
const stderr = openSync(stderrPath, 'w');
const child = spawn(java, [
  '-jar', jarPath,
  '--spring.profiles.active=openapi-export',
  `--server.port=${port}`,
  '--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/reasonweave',
  `--rw.domain-pack-roots=${domainPackRoot}`,
], { cwd: backendRoot, stdio: ['ignore', stdout, stderr], windowsHide: true });
let exited = false;
child.once('exit', () => { exited = true; });

try {
  const url = `http://127.0.0.1:${port}/api/v1/openapi`;
  let document;
  for (let attempt = 0; attempt < 120; attempt += 1) {
    if (exited) throw new Error(`OpenAPI export process exited before readiness.\n${readFileSync(stderrPath, 'utf8')}`);
    try {
      const response = await fetch(url);
      if (response.ok) { document = await response.json(); break; }
    } catch { /* listener is still starting */ }
    await delay(250);
  }
  if (!document) throw new Error(`OpenAPI endpoint was not ready within 30 seconds: ${url}`);
  const raw = JSON.stringify(document);
  if (!String(document.openapi).startsWith('3.')) throw new Error('Generated document is not an OpenAPI 3 contract.');
  if (!document.paths?.['/api/v1/runtime']) throw new Error('Generated contract is missing /api/v1/runtime.');
  if (document.paths?.['/api/v1/session'] || raw.includes('workspace_id')) {
    throw new Error('Generated contract exposes a removed session or workspace API field.');
  }
  delete document.servers;
  writeFileSync(generatedContract, JSON.stringify(document), 'utf8');
  copyFileSync(generatedContract, contractPath);
} finally {
  if (!exited) {
    child.kill();
    await Promise.race([
      new Promise((resolveExit) => child.once('exit', resolveExit)),
      delay(5_000),
    ]);
  }
  closeSync(stdout);
  closeSync(stderr);
}

run(executable('pnpm'), ['--dir', 'frontend', 'api:types'], { cwd: root, stdio: 'inherit' });
const document = JSON.parse(readFileSync(contractPath, 'utf8'));
console.log(JSON.stringify({
  contract: contractPath,
  paths: Object.keys(document.paths ?? {}).length,
  schemas: Object.keys(document.components?.schemas ?? {}).length,
}, null, 2));

function usage() {
  return 'Usage: node scripts/export-openapi.mjs [--java-home PATH] [--port 18081] [--skip-build]';
}
