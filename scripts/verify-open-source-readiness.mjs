#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { existsSync, lstatSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { isIPv4 } from 'node:net';
import { basename, dirname, extname, isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const issues = [];
const issue = (message) => issues.push(message);
const posix = (path) => path.replaceAll('\\', '/');
const pathOf = (relativePath) => resolve(root, relativePath);
const text = (relativePath) => readFileSync(pathOf(relativePath), 'utf8');
const sha256 = (relativePath) => createHash('sha256').update(readFileSync(pathOf(relativePath))).digest('hex');

const requiredPaths = [
  '.gitattributes', '.gitleaks.toml', 'ASSET_PROVENANCE.md', 'CHANGELOG.md',
  'CONTRIBUTING.md', 'LICENSE', 'NOTICE', 'README.md', 'README.en.md', 'SECURITY.md',
  'SUPPORT.md',
  'backend', 'contracts', 'design', 'domain-packs', 'fixtures', 'frontend', 'scripts',
  'compose.yml', 'compose.build.yml', 'frontend/Dockerfile', 'frontend/nginx.conf',
  'infra/compose.test.yml', 'infra/compose.e2e.yml', 'infra/postgres/Dockerfile',
  'infra/ollama/Dockerfile', 'infra/ollama/ensure-model.sh',
  'tools/domain-pack-cli', 'tools/domain-pack-cli/README.md',
  'tools/evidence-cli', 'tools/evidence-cli/README.md',
  'docs/architecture.md', 'docs/api-quickstart.md', 'docs/api-quickstart.en.md',
  'docs/troubleshooting.md', 'docs/troubleshooting.en.md',
  'docs/examples/kubernetes-investigation-summary.json',
  'docs/examples/cold-holding-investigation-summary.json',
  'docs/media/reasonweave-demo.gif',
  'docs/media/reasonweave-demo.mp4', 'docs/screenshots/reasonweave-domain-packs.webp',
  'docs/screenshots/reasonweave-graph.webp', 'docs/screenshots/reasonweave-investigation.webp',
  'docs/screenshots/reasonweave-result.webp',
  'docs/screenshots/reasonweave-retrieval.webp', 'contracts/openapi/reasonweave-v1.json',
  'scripts/init-local.ps1', 'scripts/init-local.sh', 'scripts/init-local-config.mjs',
  'scripts/kubernetes-kind-e2e.sh', 'scripts/package-source.mjs', 'examples/quickstart/run.mjs',
  '.github/ISSUE_TEMPLATE/bug_report.yml', '.github/ISSUE_TEMPLATE/feature_request.yml',
  '.github/ISSUE_TEMPLATE/domain_pack.yml', '.github/pull_request_template.md',
  'fixtures/domain-packs/equipment-fault-test/1.0.0/manifest.yaml',
  'domain-packs/kubernetes-pod-diagnostics/1.0.0/manifest.yaml',
  'domain-packs/kubernetes-pod-diagnostics/1.0.0/LICENSES.yaml',
  'domain-packs/kubernetes-pod-diagnostics/1.0.0/NOTICE.md',
  'domain-packs/kubernetes-pod-diagnostics/1.0.0/checksums.sha256',
  'domain-packs/cold-holding-excursion-diagnostics/1.0.0/manifest.yaml',
  'domain-packs/cold-holding-excursion-diagnostics/1.0.0/event-definitions.yaml',
  'domain-packs/cold-holding-excursion-diagnostics/1.0.0/LICENSES.yaml',
  'domain-packs/cold-holding-excursion-diagnostics/1.0.0/NOTICE.md',
  'domain-packs/cold-holding-excursion-diagnostics/1.0.0/checksums.sha256',
  'fixtures/cold-holding/zenodo-15130001/README.md',
  'fixtures/cold-holding/zenodo-15130001/event-ir.json',
  'fixtures/cold-holding/zenodo-15130001/sources.json',
  'fixtures/cold-holding/zenodo-15130001/telemetry.csv',
];
for (const path of requiredPaths) if (!existsSync(pathOf(path))) issue(`missing-required:${path}`);

for (const markdownPath of [
  'README.md', 'README.en.md', 'docs/api-quickstart.md', 'docs/api-quickstart.en.md',
  'docs/troubleshooting.md', 'docs/troubleshooting.en.md', 'CONTRIBUTING.md', 'SUPPORT.md',
]) {
  if (!existsSync(pathOf(markdownPath))) continue;
  for (const target of localMarkdownTargets(text(markdownPath))) {
    const [rawPath, fragment = ''] = target.split('#', 2);
    if (!rawPath) {
      if (fragment && !markdownAnchors(text(markdownPath)).has(fragment.toLowerCase())) {
        issue(`markdown-anchor:${markdownPath}#${fragment}`);
      }
      continue;
    }
    let decodedPath;
    try { decodedPath = decodeURIComponent(rawPath); } catch { issue(`markdown-link-encoding:${markdownPath}:${rawPath}`); continue; }
    const resolvedTarget = resolve(dirname(pathOf(markdownPath)), decodedPath);
    if (!isInsideRoot(resolvedTarget) || !existsSync(resolvedTarget)) {
      issue(`markdown-link:${markdownPath}:${target}`);
    }
  }
}

const contractCopies = {
  'contracts/eventir/eventir-0.1.schema.json': 'backend/src/main/resources/contracts/eventir/eventir-0.1.schema.json',
  'contracts/observation-bundle/1.0/observation-bundle.schema.json': 'backend/src/main/resources/contracts/observation-bundle/observation-bundle-1.schema.json',
  'contracts/domain-pack/1.0/manifest.schema.json': 'backend/src/main/resources/contracts/domain-pack/manifest-1.schema.json',
};
for (const [source, copy] of Object.entries(contractCopies)) {
  if (!existsSync(pathOf(source)) || !existsSync(pathOf(copy))) issue(`contract-copy-missing:${source}`);
  else if (sha256(source) !== sha256(copy)) issue(`contract-copy-drift:${source}`);
}
if (!existsSync(pathOf('tools/domain-pack-cli/src/manifest.schema.json'))
  || sha256('tools/domain-pack-cli/src/manifest.schema.json') !== sha256('contracts/domain-pack/1.0/manifest.schema.json')) {
  issue('contract-copy-drift:tools/domain-pack-cli/src/manifest.schema.json');
}

const packagePaths = ['package.json', 'frontend/package.json', 'tools/domain-pack-cli/package.json', 'tools/evidence-cli/package.json'];
for (const packagePath of packagePaths) {
  if (!existsSync(pathOf(packagePath))) continue;
  const manifest = JSON.parse(text(packagePath));
  if (manifest.license !== 'Apache-2.0') issue(`license:${packagePath}`);
  if (manifest.version !== '0.4.1') issue(`version:${packagePath}:${manifest.version}`);
  if (packagePath.startsWith('tools/')) {
    if (manifest.private === true) issue(`package-private:${packagePath}`);
    if (!manifest.files?.includes('README.md')) issue(`package-files:${packagePath}`);
    if (manifest.engines?.node !== '>=22') issue(`package-node-engine:${packagePath}`);
  }
}
if (existsSync(pathOf('backend/pom.xml'))) {
  const pom = text('backend/pom.xml');
  if (!/<name>Apache License, Version 2\.0<\/name>/.test(pom)) issue('license:backend/pom.xml');
  const projectVersion = pom.match(/<artifactId>reasonweave-backend<\/artifactId>\s*<version>([^<]+)<\/version>/)?.[1];
  if (projectVersion !== '0.4.1') issue(`version:backend/pom.xml:${projectVersion ?? 'missing'}`);
}

for (const relativePath of ['backend/.dockerignore', 'backend/Dockerfile', 'backend/Dockerfile.runtime']) {
  if (!existsSync(pathOf(relativePath))) continue;
  const content = text(relativePath);
  if (content.includes('0.4.1-SNAPSHOT')) issue(`snapshot-artifact:${relativePath}`);
  if ((relativePath.includes('Dockerfile') || relativePath.endsWith('.dockerignore'))
    && !content.includes('reasonweave-backend-0.4.1.jar')) issue(`backend-artifact-version:${relativePath}`);
}

for (const relativePath of walk('scripts').filter((path) => ['.ps1', '.sh'].includes(extname(path)))) {
  if (/(^|\n)\s*&\s+rtk\b/m.test(text(relativePath))) issue(`local-tool-dependency:${basename(relativePath)}`);
}
for (const [name, manifestPath] of Object.entries({
  kubernetes: 'domain-packs/kubernetes-pod-diagnostics/1.0.0/manifest.yaml',
  'cold-holding': 'domain-packs/cold-holding-excursion-diagnostics/1.0.0/manifest.yaml',
})) {
  if (!existsSync(pathOf(manifestPath))) continue;
  const manifest = text(manifestPath);
  if (!/^production_allowed:\s*true\s*$/m.test(manifest)) issue(`domain-pack:${name}-production-not-allowed`);
  if (/^fixture_only:\s*true\s*$/m.test(manifest)) issue(`domain-pack:${name}-marked-fixture`);
  if (!/^vector_policy:\s*required\s*$/m.test(manifest)) issue(`domain-pack:${name}-vector-not-required`);
}
if (existsSync(pathOf('domain-packs/cargo-damage'))) issue('domain-pack:cargo-removed-from-runtime');
if (existsSync(pathOf('fixtures/domain-packs/cargo-damage'))) issue('domain-pack:cargo-fixture-must-be-removed');

const declaredAssets = new Map();
if (existsSync(pathOf('ASSET_PROVENANCE.md'))) {
  for (const line of text('ASSET_PROVENANCE.md').split(/\r?\n/)) {
    const match = line.match(/^([0-9a-f]{64})\s{2}(.+)$/);
    if (!match) continue;
    const [, expectedHash, relativePathRaw] = match;
    const relativePath = posix(relativePathRaw.trim());
    if (declaredAssets.has(relativePath)) { issue(`asset-duplicate:${relativePath}`); continue; }
    declaredAssets.set(relativePath, expectedHash);
    if (!isInsideRoot(pathOf(relativePath))) issue(`asset-path:${relativePath}`);
    else if (!existsSync(pathOf(relativePath)) || !statSync(pathOf(relativePath)).isFile()) issue(`asset-missing:${relativePath}`);
    else if (sha256(relativePath) !== expectedHash) issue(`asset-checksum:${relativePath}`);
  }
}
const assetFiles = [
  'design/assets', 'frontend/public/brand', 'frontend/public/icons',
  'fixtures/evidence', 'docs/media', 'docs/screenshots',
].flatMap((directory) => existsSync(pathOf(directory))
  ? readdirSync(pathOf(directory), { withFileTypes: true })
    .filter((entry) => entry.isFile()).map((entry) => `${directory}/${entry.name}`)
  : []).sort();
for (const relativePath of assetFiles) if (!declaredAssets.has(relativePath)) issue(`asset-undocumented:${relativePath}`);
for (const relativePath of declaredAssets.keys()) if (!assetFiles.includes(relativePath)) issue(`asset-out-of-scope:${relativePath}`);

if (existsSync(pathOf('compose.yml'))) {
  const compose = text('compose.yml');
  if ((compose.match(/^\s{4}ports:\s*$/gm) ?? []).length !== 1) issue('compose:only-frontend-may-publish-ports');
  if (!compose.includes('127.0.0.1:${RW_HTTP_PORT:-8080}:8080')) issue('compose:frontend-not-loopback-bound');
  if (/^\s*container_name:/m.test(compose)) issue('compose:global-container-name');
  if (!compose.includes('name: reasonweave-ollama-model-cache') || /\n\s+external:\s*true/.test(compose)) {
    issue('compose:model-cache-must-be-automatic');
  }
  if (/^\s+build:/m.test(compose)) issue('compose:default-must-not-build');
  for (const image of ['reasonweave-backend', 'reasonweave-frontend', 'reasonweave-postgres', 'reasonweave-ollama']) {
    if (!compose.includes(`ghcr.io/chen-anju/${image}:`)) issue(`compose:preview-image-missing:${image}`);
  }
  if (!compose.includes('reasonweave-ensure-model') || compose.includes('pgvector/pgvector:pg16')) {
    issue('compose:hardened-runtime-images-required');
  }
}
if (existsSync(pathOf('compose.build.yml'))) {
  const compose = text('compose.build.yml');
  for (const dockerfile of ['backend/Dockerfile', 'frontend/Dockerfile', 'infra/postgres/Dockerfile', 'infra/ollama/Dockerfile']) {
    if (!compose.includes(dockerfile.replace('backend/', '')) && !compose.includes(dockerfile)) {
      issue(`compose-build:dockerfile-missing:${dockerfile}`);
    }
  }
}

for (const [relativePath, markers] of Object.entries({
  'infra/postgres/Dockerfile': ['PGVECTOR_SHA256=', 'apk upgrade --no-cache', 'USER postgres', 'org.opencontainers.image.source'],
  'infra/ollama/Dockerfile': ['OLLAMA_SOURCE_SHA256=', 'GO_SHA256=', 'golang.org/x/crypto@v0.53.0', 'USER 10001:10001', 'org.opencontainers.image.source'],
  'frontend/Dockerfile': ['nginx:1.31.4-alpine3.24', 'apk upgrade --no-cache', 'USER nginx', 'org.opencontainers.image.source'],
  'backend/Dockerfile': ['USER reasonweave', 'org.opencontainers.image.source'],
})) {
  if (!existsSync(pathOf(relativePath))) continue;
  const content = text(relativePath);
  for (const marker of markers) if (!content.includes(marker)) issue(`image-hardening:${relativePath}:${marker}`);
}
if (existsSync(pathOf('infra/compose.e2e.yml'))) {
  const compose = text('infra/compose.e2e.yml');
  if (!compose.includes('name: reasonweave-ollama-model-cache') || !compose.includes('external: true')) {
    issue('compose-e2e:model-cache-must-be-external');
  }
}
if (existsSync(pathOf('scripts/init-local-config.mjs'))) {
  const initializer = text('scripts/init-local-config.mjs');
  for (const marker of ['mode: 0o700', "open(passwordPath, 'wx', 0o644)", 'chmod(secretDirectory, 0o700)', 'chmod(passwordPath, 0o644)']) {
    if (!initializer.includes(marker)) issue('local-init:compose-secret-permissions');
  }
}

if (existsSync(pathOf('contracts/openapi/reasonweave-v1.json'))) {
  try {
    const raw = text('contracts/openapi/reasonweave-v1.json');
    const document = JSON.parse(raw);
    if (!String(document.openapi).startsWith('3.')) issue('openapi:unsupported-version');
    if (document.info?.title !== 'ReasonWeave API' || document.info?.version !== 'v1') issue('openapi:identity-mismatch');
    if (document.servers) issue('openapi:export-listener-leaked');
    if (document.paths?.['/api/v1/session']) issue('openapi:session-must-not-exist');
    if (!document.paths?.['/api/v1/runtime']) issue('openapi:runtime-missing');
    if (raw.includes('workspace_id')) issue('openapi:workspace-field-must-not-exist');
    if (document.components?.schemas?.EventTypeView?.properties?.evidence_inputs?.type !== 'array') issue('openapi:evidence-inputs-must-be-structured');
    if (document.components?.schemas?.DomainPackDetail?.properties?.event_definitions?.type !== 'array') issue('openapi:event-definitions-must-be-structured');
    if (document.components?.schemas?.EventTypeView?.properties?.event_requirements?.$ref !== '#/components/schemas/EventRequirementsView'
      || document.components?.schemas?.EventRequirementsView?.properties?.time_range?.type !== 'string') {
      issue('openapi:event-time-range-requirement-missing');
    }
  } catch { issue('openapi:invalid-json'); }
}

for (const directory of ['backend/src/main/java', 'frontend/src']) {
  for (const relativePath of walk(directory)) {
    if (relativePath === 'frontend/src/api/schema.d.ts' || /\.test\.[^.]+$/.test(relativePath)) continue;
    if (/(kubernetes|k8s|kubectl|pod_name|namespace)/i.test(text(relativePath))) issue(`domain-coupling:${relativePath}`);
  }
}

const ignoreProbes = [
  '.artifacts/.open-source-probe', '.pnpm-store/.open-source-probe', 'backend/target/.open-source-probe',
  'frontend/dist/.open-source-probe', 'frontend/test-results/.open-source-probe',
  'frontend/playwright-report/.open-source-probe', 'frontend/output/playwright/.open-source-probe',
  'output/playwright/.open-source-probe', '.local/.open-source-probe', 'node_modules/.open-source-probe',
  'tools/domain-pack-cli/node_modules/.open-source-probe', 'tools/evidence-cli/node_modules/.open-source-probe',
];
for (const probe of ignoreProbes) {
  const result = git(['check-ignore', '--quiet', '--no-index', '--', probe], false);
  if (result.status !== 0) issue(`not-ignored:${probe}`);
}
const candidateResult = git(['ls-files', '--cached', '--others', '--exclude-standard', '-z'], false);
if (candidateResult.status !== 0) throw new Error('Unable to enumerate the open-source candidate with git.');
const candidateFiles = candidateResult.stdout.split('\0').filter(Boolean).map(posix);
const forbiddenPrefixes = [
  '.artifacts/', '.local/', '.pnpm-store/', 'backend/target/', 'frontend/dist/',
  'frontend/test-results/', 'frontend/playwright-report/', 'frontend/output/playwright/', 'output/playwright/',
];
for (const candidate of candidateFiles) {
  const lower = candidate.toLowerCase();
  if (lower.startsWith('node_modules/') || lower.includes('/node_modules/')
    || forbiddenPrefixes.some((prefix) => lower.startsWith(prefix.toLowerCase()))) issue(`candidate-generated:${candidate}`);
  if (/^(scripts|infra)\/[^/]*(deploy|release|restore|tunnel|remote|ssh|verify-live|server)[^/]*(\/|$)/i.test(candidate)) {
    issue(`environment-specific-operation:${candidate}`);
  }
}

const forbiddenText = [
  ['absolute-file-uri', /\bfile:[a-z]:[\\/]/i],
  ['personal-windows-home', /[a-z]:[\\/](Users|Documents and Settings)[\\/]/i],
  ['local-windows-workspace', /(^|[^A-Za-z0-9_.-])[a-z]:[\\/](project|projects|repos|repository|workspace|src)[\\/]/i],
  ['personal-posix-home', /\/(home|Users)\/[A-Za-z0-9._-]+\/(Desktop|Documents|Downloads|Projects?|Repos?|repository|workspace|src)\//i],
  ['private-key', /-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----/],
  ['openai-style-secret', /(^|[^A-Za-z0-9])sk-[A-Za-z0-9_-]{20,}/],
  ['aws-access-key', /(^|[^A-Z0-9])AKIA[A-Z0-9]{16}([^A-Z0-9]|$)/],
];
if (process.env.RW_PUBLICATION_DENYLIST) {
  const denylist = resolve(process.env.RW_PUBLICATION_DENYLIST);
  if (isInsideRoot(denylist)) issue('publication-denylist:must-be-outside-repository');
  else if (!existsSync(denylist) || !statSync(denylist).isFile()) issue('publication-denylist:not-found');
  else {
    let index = 0;
    for (const line of readFileSync(denylist, 'utf8').split(/\r?\n/)) {
      const pattern = line.trim();
      if (!pattern || pattern.startsWith('#')) continue;
      index += 1;
      try { forbiddenText.push([`local-denylist-${index}`, new RegExp(pattern)]); }
      catch { issue(`publication-denylist:invalid-pattern:${index}`); }
    }
  }
}
const textExtensions = new Set(['.css', '.d.ts', '.html', '.java', '.js', '.json', '.md', '.mjs', '.properties', '.ps1', '.sh', '.sql', '.svg', '.ts', '.tsx', '.txt', '.xml', '.yaml', '.yml']);
const textNames = new Set(['.gitattributes', '.gitignore', 'Dockerfile', 'LICENSE', 'NOTICE', 'mvnw']);
for (const candidate of candidateFiles) {
  const absolute = pathOf(candidate);
  if (!existsSync(absolute) || !statSync(absolute).isFile() || statSync(absolute).size > 5 * 1024 * 1024) continue;
  const extension = candidate.endsWith('.d.ts') ? '.d.ts' : extname(candidate).toLowerCase();
  if (!textExtensions.has(extension) && !textNames.has(basename(candidate))) continue;
  const content = readFileSync(absolute, 'utf8');
  for (const [name, pattern] of forbiddenText) if (pattern.test(content)) issue(`${name}:${candidate}`);
  for (const match of content.matchAll(/(?<![0-9])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![0-9])/g)) {
    if (isPublicIpv4(match[0])) issue(`public-ipv4:${candidate}`);
  }
}

if (issues.length) throw new Error(`Open-source readiness verification failed: ${[...new Set(issues)].join(', ')}`);
console.log(`Open-source readiness verified: ${candidateFiles.length} candidate files, ${declaredAssets.size} documented assets.`);

function walk(relativeDirectory) {
  if (!existsSync(pathOf(relativeDirectory))) return [];
  const result = [];
  for (const entry of readdirSync(pathOf(relativeDirectory), { withFileTypes: true })) {
    const child = posix(`${relativeDirectory}/${entry.name}`);
    if (entry.isDirectory()) result.push(...walk(child));
    else if (entry.isFile()) result.push(child);
  }
  return result;
}

function localMarkdownTargets(content) {
  const targets = new Set();
  const record = (rawTarget) => {
    const target = rawTarget.trim().replace(/^<|>$/g, '').split(/\s+["']/u, 1)[0];
    if (!target || /^(?:https?:|mailto:|data:|javascript:)/iu.test(target)) return;
    targets.add(target);
  };
  for (const match of content.matchAll(/!?\[[^\]]*\]\(([^)]+)\)/gu)) record(match[1]);
  for (const match of content.matchAll(/\b(?:href|src)="([^"]+)"/gu)) record(match[1]);
  return targets;
}

function markdownAnchors(content) {
  const anchors = new Set();
  const counts = new Map();
  for (const match of content.matchAll(/^#{1,6}\s+(.+)$/gmu)) {
    const base = match[1].replace(/<[^>]+>/gu, '').trim().toLowerCase()
      .replace(/[^\p{L}\p{N}\s_-]/gu, '').replace(/\s+/gu, '-');
    const seen = counts.get(base) ?? 0;
    counts.set(base, seen + 1);
    anchors.add(seen === 0 ? base : `${base}-${seen}`);
  }
  return anchors;
}

function git(args, throwOnError = true) {
  const result = spawnSync('git', ['-C', root, ...args], { encoding: 'utf8' });
  if (throwOnError && result.status !== 0) throw new Error(result.stderr || `git ${args.join(' ')} failed`);
  return result;
}

function isInsideRoot(absolutePath) {
  const relativePath = relative(root, resolve(absolutePath));
  return relativePath !== '' && !relativePath.startsWith(`..${sep}`) && relativePath !== '..' && !isAbsolute(relativePath);
}

function isPublicIpv4(value) {
  if (!isIPv4(value)) return false;
  const [a, b] = value.split('.').map(Number);
  return !(a === 0 || a === 10 || a === 127 || (a === 100 && b >= 64 && b <= 127)
    || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31)
    || (a === 192 && b === 168) || a >= 224);
}
