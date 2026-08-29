#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { existsSync } from 'node:fs';
import {
  cp,
  lstat,
  mkdir,
  mkdtemp,
  open,
  readFile,
  readdir,
  rename,
  rm,
  stat,
  writeFile,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, extname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import * as tar from 'tar';
import YAML from 'yaml';

const MAX_FILES = 500;
const MAX_FILE_BYTES = 5 * 1024 * 1024;
const MAX_TOTAL_BYTES = 50 * 1024 * 1024;
const ALLOWED_EXTENSIONS = new Set(['.yaml', '.yml', '.json', '.md', '.txt', '.sha256']);
const REQUIRED_FILES = [
  'manifest.yaml',
  'event-definitions.yaml',
  'vocabulary.yaml',
  'hypotheses.yaml',
  'rules.yaml',
  'next-evidence.yaml',
  'retrieval-config.yaml',
  'presentation.zh-CN.yaml',
  'knowledge/metadata.yaml',
  'knowledge/golden-queries.yaml',
  'LICENSES.yaml',
  'NOTICE.md',
];
const SCRIPT_ROOT = dirname(fileURLToPath(import.meta.url));
const MANIFEST_SCHEMA = resolve(SCRIPT_ROOT, 'manifest.schema.json');
const ENGINE_VERSION = '0.4.1';
const RULE_FIELDS = new Set([
  'id', 'version', 'hypothesis', 'predicate', 'when', 'relation', 'expected_weight', 'required',
]);
const RULE_RELATIONS = new Set([
  'STRONGLY_SUPPORTS', 'SUPPORTS', 'PARTIALLY_SUPPORTS', 'NEUTRAL', 'INSUFFICIENT',
  'PARTIALLY_CONTRADICTS', 'CONTRADICTS', 'STRONGLY_CONTRADICTS',
]);

function usage() {
  return `ReasonWeave Domain Pack CLI

Usage:
  rwpack init <directory> --key <key> [--version 0.1.0]
  rwpack validate <directory>
  rwpack pack <directory> [--out <file.rwpack>]
  rwpack verify <file.rwpack>
  rwpack install <file.rwpack> --root <domain-pack-root>
  rwpack list --root <domain-pack-root>`;
}

function option(args, name, fallback) {
  const index = args.indexOf(name);
  return index >= 0 && index + 1 < args.length ? args[index + 1] : fallback;
}

function fail(message) {
  throw new Error(message);
}

function compareSemver(left, right) {
  const leftParts = left.split('.').map(Number);
  const rightParts = right.split('.').map(Number);
  for (let index = 0; index < 3; index += 1) {
    if (leftParts[index] !== rightParts[index]) return leftParts[index] - rightParts[index];
  }
  return 0;
}

function safeRelativePath(value) {
  const normalized = value.replaceAll('\\', '/').replace(/\/$/, '');
  if (!normalized || normalized.startsWith('/') || /^[A-Za-z]:/.test(normalized)) return false;
  return !normalized.split('/').some((part) => part === '..' || part === '' || part === '.');
}

async function filesUnder(root) {
  const output = [];
  let entryCount = 0;
  async function walk(directory) {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      entryCount += 1;
      if (entryCount > MAX_FILES) fail(`Package has more than ${MAX_FILES} entries`);
      const absolute = join(directory, entry.name);
      const name = relative(root, absolute).split(sep).join('/');
      if (!safeRelativePath(name)) fail(`Unsafe package path: ${name}`);
      const info = await lstat(absolute);
      if (info.isSymbolicLink()) fail(`Links are not allowed: ${name}`);
      if (entry.isDirectory()) {
        await walk(absolute);
      } else if (entry.isFile()) {
        if (!ALLOWED_EXTENSIONS.has(extname(entry.name))) fail(`File type is not allowed: ${name}`);
        if ((info.mode & 0o111) !== 0) fail(`Executable files are not allowed: ${name}`);
        output.push({ name, absolute, size: info.size });
      } else {
        fail(`Unsupported package entry: ${name}`);
      }
    }
  }
  await walk(root);
  const total = output.reduce((sum, item) => sum + item.size, 0);
  if (total > MAX_TOTAL_BYTES) fail('Package exceeds the 50 MiB unpacked limit');
  for (const item of output) {
    if (item.size > MAX_FILE_BYTES) fail(`File exceeds the 5 MiB limit: ${item.name}`);
  }
  return output.sort((left, right) => (left.name < right.name ? -1 : left.name > right.name ? 1 : 0));
}

async function yamlFile(root, name) {
  return YAML.parse(await readFile(join(root, name), 'utf8'));
}

function stringSet(values, label) {
  if (!Array.isArray(values) || values.length === 0) fail(`${label} must be a non-empty array`);
  const result = new Set();
  for (const value of values) {
    if (typeof value !== 'string' || !value.trim() || result.has(value)) fail(`${label} contains an invalid or duplicate value`);
    result.add(value);
  }
  return result;
}

async function checksumEntries(root, includeExisting = false) {
  const files = await filesUnder(root);
  const output = [];
  for (const file of files) {
    if (!includeExisting && file.name === 'checksums.sha256') continue;
    const hash = createHash('sha256').update(await readFile(file.absolute)).digest('hex');
    output.push(`${hash}  ${file.name}`);
  }
  return output;
}

async function writeChecksums(root) {
  const entries = await checksumEntries(root);
  await writeFile(join(root, 'checksums.sha256'), `${entries.join('\n')}\n`, 'utf8');
}

async function verifyChecksums(root) {
  const checksumPath = join(root, 'checksums.sha256');
  if (!existsSync(checksumPath)) fail('checksums.sha256 is missing');
  const declared = (await readFile(checksumPath, 'utf8')).trim().split(/\r?\n/).filter(Boolean);
  const actual = await checksumEntries(root);
  if (declared.length !== actual.length || declared.some((value, index) => value !== actual[index])) {
    fail('Package checksums do not match its content');
  }
}

async function validate(rootInput, requireChecksums = false) {
  const root = resolve(rootInput);
  if (!(await stat(root)).isDirectory()) fail(`Not a directory: ${root}`);
  const files = await filesUnder(root);
  const names = new Set(files.map((item) => item.name));
  for (const required of REQUIRED_FILES) if (!names.has(required)) fail(`Required file is missing: ${required}`);

  const manifest = await yamlFile(root, 'manifest.yaml');
  const ajv = new Ajv2020({ allErrors: true, strict: false });
  addFormats(ajv);
  const schema = JSON.parse(await readFile(MANIFEST_SCHEMA, 'utf8'));
  const validateManifest = ajv.compile(schema);
  if (!validateManifest(manifest)) {
    fail(`manifest.yaml is invalid: ${ajv.errorsText(validateManifest.errors, { separator: '; ' })}`);
  }
  const engineRange = /^>=(\d+\.\d+\.\d+) <(\d+\.\d+\.\d+)$/.exec(manifest.engine);
  if (!engineRange || compareSemver(ENGINE_VERSION, engineRange[1]) < 0
      || compareSemver(ENGINE_VERSION, engineRange[2]) >= 0) {
    fail(`Domain Pack engine range ${manifest.engine} does not include rwpack ${ENGINE_VERSION}`);
  }

  const declaredPaths = [
    manifest.event_definitions,
    manifest.hypotheses,
    manifest.rules,
    manifest.next_evidence,
    manifest.knowledge?.metadata,
    manifest.knowledge?.retrieval_config,
    manifest.knowledge?.golden_queries,
    manifest.knowledge?.golden_investigations,
  ].filter(Boolean);
  for (const path of declaredPaths) {
    if (!safeRelativePath(path) || !names.has(path)) fail(`Declared package path is invalid or missing: ${path}`);
  }

  const eventTypes = stringSet(manifest.event_types, 'manifest.event_types');
  const vocabulary = await yamlFile(root, 'vocabulary.yaml');
  const predicates = new Set(Object.keys(vocabulary?.predicates ?? {}));
  if (predicates.size === 0) fail('vocabulary.predicates must not be empty');
  for (const [predicate, definition] of Object.entries(vocabulary.predicates)) {
    if (!definition?.label || !definition?.value_schema) fail(`Predicate ${predicate} requires label and value_schema`);
    ajv.compile(definition.value_schema);
  }

  const eventDefinitionsDocument = await yamlFile(root, manifest.event_definitions);
  const eventDefinitions = eventDefinitionsDocument?.event_types;
  if (!eventDefinitions || typeof eventDefinitions !== 'object' || Array.isArray(eventDefinitions)) {
    fail('event-definitions.event_types must be a non-empty object');
  }
  const definedEventTypes = new Set(Object.keys(eventDefinitions));
  if (definedEventTypes.size !== eventTypes.size
      || [...eventTypes].some((eventType) => !definedEventTypes.has(eventType))) {
    fail('Event definitions must exactly match manifest.event_types');
  }
  const sourceProfiles = new Set(Object.keys(manifest.source_profiles ?? {}));
  for (const [eventType, definition] of Object.entries(eventDefinitions)) {
    const requirements = definition?.event_requirements ?? {};
    const unknownRequirements = Object.keys(requirements).filter((field) => field !== 'time_range');
    if (unknownRequirements.length > 0) {
      fail(`Event definition ${eventType} has unsupported event requirements: ${unknownRequirements.join(', ')}`);
    }
    if (!['optional', 'required'].includes(requirements.time_range ?? 'optional')) {
      fail(`Event definition ${eventType} has an invalid time_range requirement`);
    }
    const subject = definition?.subject;
    if (!subject?.type || !Array.isArray(subject.identity_fields) || subject.identity_fields.length === 0) {
      fail(`Event definition ${eventType} requires a subject type and identity fields`);
    }
    if (subject.attributes_schema?.type !== 'object' || !subject.attributes_schema?.properties) {
      fail(`Event definition ${eventType} subject attributes_schema must describe an object`);
    }
    ajv.compile(subject.attributes_schema);
    const identities = stringSet(subject.identity_fields, `event definition ${eventType} identity_fields`);
    for (const field of identities) {
      if (!Object.hasOwn(subject.attributes_schema.properties, field)) {
        fail(`Event definition ${eventType} identity field is not in attributes_schema: ${field}`);
      }
    }
    if (typeof subject.label_template !== 'string' || !subject.label_template.trim()) {
      fail(`Event definition ${eventType} requires subject.label_template`);
    }
    const placeholders = [...subject.label_template.matchAll(/\{([a-z_]+)\}/g)].map((match) => match[1]);
    if (new Set(placeholders).size !== identities.size || [...identities].some((field) => !placeholders.includes(field))) {
      fail(`Event definition ${eventType} label_template must reference every identity field`);
    }
    const inputs = definition.evidence_inputs;
    if (!inputs || typeof inputs !== 'object' || Array.isArray(inputs) || Object.keys(inputs).length === 0) {
      fail(`Event definition ${eventType} requires evidence_inputs`);
    }
    for (const [name, input] of Object.entries(inputs)) {
      if (!['observation_bundle', 'text', 'file', 'image'].includes(name)) {
        fail(`Event definition ${eventType} declares unsupported evidence input: ${name}`);
      }
      if (typeof input?.enabled !== 'boolean') fail(`Evidence input ${name} requires enabled`);
    }
    if (inputs.text?.enabled) {
      if (!sourceProfiles.has(inputs.text.source_profile)) fail('Text evidence references an unknown source profile');
      if (!predicates.has(inputs.text.predicate)) fail('Text evidence references an unknown predicate');
      if (!['PENDING', 'CONFIRMED'].includes(inputs.text.verification_status)) {
        fail('Text evidence verification_status must be PENDING or CONFIRMED');
      }
    }
    if (inputs.file?.enabled && (!Array.isArray(inputs.file.content_types) || inputs.file.content_types.length === 0)) {
      fail('File evidence requires at least one content type');
    }
    if (inputs.file?.enabled && !sourceProfiles.has(inputs.file.source_profile)) {
      fail('File evidence references an unknown source profile');
    }
    if (inputs.image?.enabled && (!Array.isArray(inputs.image.content_types) || inputs.image.content_types.length === 0)) {
      fail('Image evidence requires at least one content type');
    }
    if (inputs.image?.enabled && !sourceProfiles.has(inputs.image.source_profile)) {
      fail('Image evidence references an unknown source profile');
    }
  }

  const presentation = await yamlFile(root, 'presentation.zh-CN.yaml');
  for (const [eventType, definition] of Object.entries(eventDefinitions)) {
    const eventPresentation = presentation?.event_types?.[eventType];
    if (!eventPresentation || typeof eventPresentation !== 'object') {
      fail(`Presentation is missing event type: ${eventType}`);
    }
    const subjectProperties = definition.subject.attributes_schema.properties;
    const fields = eventPresentation.fields;
    if (!fields || typeof fields !== 'object' || Array.isArray(fields) || Object.keys(fields).length === 0) {
      fail(`Presentation fields are required for event type: ${eventType}`);
    }
    for (const [fieldName, field] of Object.entries(fields)) {
      if (!Object.hasOwn(subjectProperties, fieldName)) {
        fail(`Presentation references an unknown subject field: ${fieldName}`);
      }
      if (!['text', 'number', 'select', 'boolean'].includes(field?.control)) {
        fail(`Presentation uses an unsupported control: ${field?.control ?? '<missing>'}`);
      }
    }
  }

  const hypothesesDocument = await yamlFile(root, manifest.hypotheses);
  const hypothesisCodes = new Set();
  for (const hypothesis of hypothesesDocument?.hypotheses ?? []) {
    if (!hypothesis.code || hypothesisCodes.has(hypothesis.code)) fail('Hypothesis codes must be unique and non-empty');
    hypothesisCodes.add(hypothesis.code);
  }
  if (hypothesisCodes.size === 0 || hypothesisCodes.size > (manifest.hypothesis_limit ?? 4)) fail('Hypothesis count is outside the declared limit');

  const rules = await yamlFile(root, manifest.rules);
  const ruleIds = new Set();
  const hypothesesWithRules = new Set();
  for (const rule of rules?.rules ?? []) {
    for (const field of Object.keys(rule ?? {})) {
      if (!RULE_FIELDS.has(field)) fail(`Rule ${rule?.id ?? '<unknown>'} contains an unsupported field: ${field}`);
    }
    if (!rule.id || ruleIds.has(rule.id)) fail('Rule IDs must be unique and non-empty');
    ruleIds.add(rule.id);
    if (typeof rule.version !== 'string' || rule.version.trim() === '') fail(`Rule ${rule.id} requires a version`);
    if (!hypothesisCodes.has(rule.hypothesis)) fail(`Rule ${rule.id} references an unknown hypothesis`);
    hypothesesWithRules.add(rule.hypothesis);
    if (!predicates.has(rule.predicate)) fail(`Rule ${rule.id} references an unknown predicate`);
    if (rule.when !== 'present') fail(`Rule ${rule.id} uses an unsupported when operator`);
    if (!RULE_RELATIONS.has(rule.relation)) fail(`Rule ${rule.id} uses an unsupported relation`);
    if (!Number.isFinite(rule.expected_weight) || rule.expected_weight <= 0 || rule.expected_weight > 1) {
      fail(`Rule ${rule.id} expected_weight must be greater than 0 and at most 1`);
    }
    if (rule.required !== undefined && typeof rule.required !== 'boolean') {
      fail(`Rule ${rule.id} required must be a boolean`);
    }
  }
  if (ruleIds.size === 0) fail('At least one rule is required');
  const hypothesesWithoutRules = [...hypothesisCodes].filter((code) => !hypothesesWithRules.has(code));
  if (hypothesesWithoutRules.length > 0) fail(`Hypotheses without rules: ${hypothesesWithoutRules.join(', ')}`);

  const nextEvidence = await yamlFile(root, manifest.next_evidence);
  for (const recommendation of nextEvidence?.recommendations ?? []) {
    if (!predicates.has(recommendation.expected_predicate)) fail(`Recommendation ${recommendation.id} references an unknown predicate`);
    for (const code of recommendation.discriminates ?? []) {
      if (!hypothesisCodes.has(code)) fail(`Recommendation ${recommendation.id} references an unknown hypothesis`);
    }
  }

  const retrieval = await yamlFile(root, manifest.knowledge.retrieval_config);
  const allowedRetrievalKeys = new Set([
    'keyword_top_k', 'vector_top_k', 'final_top_k', 'vector_policy',
    'embedding_query_instruction', 'fusion', 'weights', 'source_diversity', 'minimum_score', 'query_intents',
  ]);
  for (const key of Object.keys(retrieval ?? {})) {
    if (!allowedRetrievalKeys.has(key)) fail(`Retrieval config contains an unsupported field: ${key}`);
  }
  for (const key of ['keyword_top_k', 'vector_top_k', 'final_top_k']) {
    if (!Number.isInteger(retrieval?.[key]) || retrieval[key] <= 0) fail(`Retrieval config ${key} must be a positive integer`);
  }
  if (retrieval?.vector_policy !== (manifest.vector_policy ?? 'optional')) {
    fail('Retrieval config vector_policy must match manifest.vector_policy');
  }
  if (retrieval?.fusion?.type !== 'rrf' || !Number.isInteger(retrieval?.fusion?.k) || retrieval.fusion.k <= 0) {
    fail('Retrieval config fusion must be rrf with a positive integer k');
  }
  if (!Number.isFinite(retrieval?.weights?.applicability) || retrieval.weights.applicability <= 0) {
    fail('Retrieval config weights.applicability must be positive');
  }
  if (!Number.isInteger(retrieval?.source_diversity?.max_units_per_document)
      || retrieval.source_diversity.max_units_per_document <= 0) {
    fail('Retrieval config source_diversity.max_units_per_document must be a positive integer');
  }
  if (!Number.isFinite(retrieval?.minimum_score) || retrieval.minimum_score < 0) {
    fail('Retrieval config minimum_score must be zero or positive');
  }
  if (manifest.vector_policy === 'required'
      && (typeof retrieval.embedding_query_instruction !== 'string'
        || retrieval.embedding_query_instruction.trim() === '')) {
    fail('A vector-required package must declare embedding_query_instruction');
  }
  if (!Array.isArray(retrieval?.query_intents) || retrieval.query_intents.length === 0
      || retrieval.query_intents.length > 6) {
    fail('Retrieval query_intents must contain between 1 and 6 entries');
  }
  const intentTypes = new Set();
  const allowedPlaceholders = new Set([
    'title', 'description', 'event_type', 'subject_label', 'predicates', 'predicate_labels',
  ]);
  for (const intent of retrieval.query_intents) {
    if (typeof intent?.type !== 'string' || !/^[A-Z][A-Z0-9_]{2,63}$/.test(intent.type)
        || intentTypes.has(intent.type)) fail('Retrieval query intent types must be unique upper snake case values');
    intentTypes.add(intent.type);
    if (typeof intent.template !== 'string' || !intent.template.trim()) fail(`Query intent ${intent.type} requires a template`);
    for (const match of intent.template.matchAll(/\{([a-z_]+)\}/g)) {
      if (!allowedPlaceholders.has(match[1])) fail(`Query intent ${intent.type} uses an unsupported placeholder: ${match[1]}`);
    }
    const remainder = intent.template.replaceAll(/\{[a-z_]+\}/g, '');
    if (remainder.includes('{') || remainder.includes('}')) {
      fail(`Query intent ${intent.type} contains an invalid placeholder`);
    }
  }

  const knowledge = await yamlFile(root, manifest.knowledge.metadata);
  const documentIds = new Set();
  const declaredKnowledgePaths = new Set();
  for (const document of knowledge?.documents ?? []) {
    if (!document.id || documentIds.has(document.id)) fail('Knowledge document IDs must be unique and non-empty');
    documentIds.add(document.id);
    const path = `knowledge/${document.path}`;
    if (!safeRelativePath(path) || !names.has(path)) fail(`Knowledge document path is invalid: ${path}`);
    declaredKnowledgePaths.add(path);
    for (const eventType of document.applicability?.event_types ?? []) {
      if (!eventTypes.has(eventType)) fail(`Knowledge document ${document.id} references an unknown event type`);
    }
    for (const predicate of [...(document.applicability?.context_predicates ?? []), ...(document.expected_predicates ?? [])]) {
      if (!predicates.has(predicate)) fail(`Knowledge document ${document.id} references an unknown predicate`);
    }
    if (!document.source_url || !document.source_license || !document.source_revision) fail(`Knowledge document ${document.id} is missing provenance`);
  }

  const golden = await yamlFile(root, manifest.knowledge.golden_queries);
  for (const query of golden?.queries ?? []) {
    for (const id of query.expected_document_ids ?? []) if (!documentIds.has(id)) fail(`Golden query ${query.id} references an unknown document`);
  }
  const goldenInvestigations = await yamlFile(root, manifest.knowledge.golden_investigations);
  const goldenInvestigationIds = new Set();
  for (const investigation of goldenInvestigations?.investigations ?? []) {
    if (!investigation.id || goldenInvestigationIds.has(investigation.id)) fail('Golden Investigation IDs must be unique and non-empty');
    goldenInvestigationIds.add(investigation.id);
    if (investigation.expected_top_hypothesis) {
      if (!hypothesisCodes.has(investigation.expected_top_hypothesis)) fail(`Golden Investigation ${investigation.id} references an unknown hypothesis`);
    } else if (investigation.expected_outcome !== 'EVIDENCE_INSUFFICIENT') {
      fail(`Golden Investigation ${investigation.id} requires expected_top_hypothesis or expected_outcome=EVIDENCE_INSUFFICIENT`);
    }
    if (!Array.isArray(investigation.observations) || investigation.observations.length === 0) fail(`Golden Investigation ${investigation.id} requires observations`);
    for (const predicate of investigation.observations) {
      if (!predicates.has(predicate)) fail(`Golden Investigation ${investigation.id} references an unknown predicate`);
    }
  }
  if (goldenInvestigationIds.size === 0) fail('At least one Golden Investigation is required');
  const licenses = await yamlFile(root, 'LICENSES.yaml');
  if (!Array.isArray(licenses?.components) || licenses.components.length === 0) fail('LICENSES.yaml must declare at least one component');
  const declaredLicenses = new Set();
  for (const component of licenses.components) {
    if (!component?.scope || !component?.license || !component?.source || !component?.revision) {
      fail('Every LICENSES.yaml component requires scope, license, source, and revision');
    }
    for (const [relativePath, expectedHash] of Object.entries(component.derived_content_sha256 ?? {})) {
      if (!safeRelativePath(relativePath) || !names.has(relativePath)) {
        fail(`LICENSES.yaml derived content path is invalid: ${relativePath}`);
      }
      if (!/^[0-9a-f]{64}$/.test(expectedHash)) {
        fail(`LICENSES.yaml derived content hash is invalid: ${relativePath}`);
      }
      const actualHash = createHash('sha256').update(await readFile(join(root, relativePath))).digest('hex');
      if (actualHash !== expectedHash) fail(`LICENSES.yaml derived content hash mismatch: ${relativePath}`);
    }
    declaredLicenses.add(component.license);
  }
  for (const document of knowledge?.documents ?? []) {
    if (!declaredLicenses.has(document.source_license)) {
      fail(`Knowledge document ${document.id} uses an undeclared license: ${document.source_license}`);
    }
  }

  const fixedFiles = new Set([
    'manifest.yaml',
    'vocabulary.yaml',
    'presentation.zh-CN.yaml',
    'LICENSES.yaml',
    'NOTICE.md',
    'checksums.sha256',
    ...declaredPaths,
    ...declaredKnowledgePaths,
  ]);
  for (const name of names) {
    if (!fixedFiles.has(name)) fail(`Package contains an undeclared file: ${name}`);
  }
  if (requireChecksums) await verifyChecksums(root);
  return { root, manifest, fingerprint: createHash('sha256').update((await checksumEntries(root)).join('\n')).digest('hex') };
}

async function initPack(directory, key, version) {
  if (!key) fail('--key is required');
  const root = resolve(directory);
  if (existsSync(root)) fail(`Target already exists: ${root}`);
  await mkdir(join(root, 'knowledge'), { recursive: true });
  await writeFile(join(root, 'manifest.yaml'), `format_version: "1.0"\nkey: ${key}\nversion: ${version}\nname: ${key}\nengine: ">=0.4.1 <0.5.0"\ncompatible_eventir: "0.1"\nevent_types: [example_event]\nevent_definitions: event-definitions.yaml\nfixture_only: false\nproduction_allowed: false\ncapabilities:\n  observation_bundle: true\n  knowledge_retrieval: true\nvector_policy: optional\nsource_profiles:\n  structured_export: {label: Structured export, reliability: 0.8}\n  human_report: {label: Human report, reliability: 0.75}\n  uploaded_file: {label: Uploaded file, reliability: 0.8}\nobservation_bundle: {schema_version: observation-bundle/1.0}\nknowledge:\n  required: true\n  metadata: knowledge/metadata.yaml\n  retrieval_config: retrieval-config.yaml\n  golden_queries: knowledge/golden-queries.yaml\n  golden_investigations: knowledge/golden-investigations.yaml\nhypotheses: hypotheses.yaml\nrules: rules.yaml\nnext_evidence: next-evidence.yaml\nhypothesis_limit: 1\n`);
  await writeFile(join(root, 'event-definitions.yaml'), 'event_types:\n  example_event:\n    subject:\n      type: example_subject\n      identity_fields: [asset_id]\n      label_template: "{asset_id}"\n      attributes_schema:\n        type: object\n        required: [asset_id]\n        properties:\n          asset_id: {type: string, minLength: 1, maxLength: 160}\n        additionalProperties: false\n    evidence_inputs:\n      observation_bundle: {enabled: true}\n      text: {enabled: true, source_profile: human_report, predicate: example_fact, verification_status: PENDING}\n      file: {enabled: true, source_profile: uploaded_file, content_types: [text/plain, application/json]}\n      image: {enabled: false}\n');
  await writeFile(join(root, 'vocabulary.yaml'), 'predicates:\n  example_fact:\n    label: Example fact\n    value_schema: {type: boolean}\n');
  await writeFile(join(root, 'hypotheses.yaml'), 'hypotheses:\n  - code: example_cause\n    title: Example cause\n    description: Replace with a bounded domain hypothesis.\n');
  await writeFile(join(root, 'rules.yaml'), 'rules:\n  - id: example.rule\n    version: "1"\n    hypothesis: example_cause\n    predicate: example_fact\n    when: present\n    relation: SUPPORTS\n    expected_weight: 1.0\n');
  await writeFile(join(root, 'next-evidence.yaml'), 'recommendations:\n  - id: inspect_example\n    title: Inspect the source system\n    discriminates: [example_cause]\n    expected_predicate: example_fact\n    estimated_impact: medium\n    acquisition_cost: low\n');
  await writeFile(join(root, 'retrieval-config.yaml'), 'keyword_top_k: 20\nvector_top_k: 20\nfinal_top_k: 6\nvector_policy: optional\nquery_intents:\n  - {type: CAUSE_CANDIDATES, template: "{title} {description} {predicate_labels} possible causes"}\n  - {type: EXPECTED_EVIDENCE, template: "{title} {predicate_labels} expected evidence"}\n  - {type: INVESTIGATION_ACTIONS, template: "{title} {predicate_labels} next checks"}\nfusion: {type: rrf, k: 60}\nweights: {applicability: 1.25}\nsource_diversity: {max_units_per_document: 2}\nminimum_score: 0.01\n');
  await writeFile(join(root, 'presentation.zh-CN.yaml'), `locale: zh-CN\nname: ${key}\nevent_types:\n  example_event:\n    label: 示例事件\n    subject_type: example_subject\n    subject_label: 调查对象\n    fields:\n      asset_id: {label: 资产编号, control: text, placeholder: asset-001}\n    evidence_inputs:\n      observation_bundle: {label: 上传 Observation Bundle, help: 导入结构化证据并逐项复核。}\n      text: {label: 添加文本证据}\n      file: {label: 上传证据文件}\nhypotheses:\n  example_cause: {title: 示例原因, description: 请替换为领域说明}\npredicates:\n  example_fact: 示例事实\n`);
  await writeFile(join(root, 'knowledge/example.md'), '# Example knowledge\n\nReplace this file with attributed domain knowledge.\n');
  await writeFile(join(root, 'knowledge/metadata.yaml'), 'fixture_only: false\nproduction_allowed: false\nauthor: Replace me\nlicenses: [Apache-2.0]\ndocuments:\n  - id: example\n    path: example.md\n    language: en\n    source_title: Replace me\n    source_url: https://example.invalid/source\n    source_revision: replace-me\n    source_license: Apache-2.0\n    modified: true\n    applicability:\n      event_types: [example_event]\n      context_predicates: [example_fact]\n    expected_predicates: [example_fact]\n');
  await writeFile(join(root, 'knowledge/golden-queries.yaml'), 'queries:\n  - id: example\n    query: example fact\n    expected_document_ids: [example]\n');
  await writeFile(join(root, 'knowledge/golden-investigations.yaml'), 'investigations:\n  - id: example\n    observations: [example_fact]\n    expected_top_hypothesis: example_cause\n');
  await writeFile(join(root, 'LICENSES.yaml'), 'components:\n  - scope: all\n    license: Apache-2.0\n    source: https://example.invalid/source\n    revision: replace-me\n    modified: true\n');
  await writeFile(join(root, 'NOTICE.md'), '# Attribution\n\nReplace this notice before distribution.\n');
  console.log(`Created ${root}`);
}

async function packagePack(directory, output) {
  const validation = await validate(directory);
  const source = validation.root;
  const temporary = await mkdtemp(join(tmpdir(), 'rwpack-pack-'));
  try {
    const copyRootName = `${validation.manifest.key}-${validation.manifest.version}`;
    const copyRoot = join(temporary, copyRootName);
    await cp(source, copyRoot, { recursive: true });
    await writeChecksums(copyRoot);
    const { manifest } = await validate(copyRoot, true);
    const file = resolve(output ?? `${manifest.key}-${manifest.version}.rwpack`);
    await tar.create({
      cwd: temporary,
      file,
      gzip: { mtime: 0 },
      portable: true,
      noMtime: true,
      jobs: 1,
    }, [copyRootName]);
    console.log(file);
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
}

async function extractVerified(archive, temporaryParent = tmpdir()) {
  const absolute = resolve(archive);
  await mkdir(temporaryParent, { recursive: true });
  const temporary = await mkdtemp(join(temporaryParent, '.rwpack-stage-'));
  try {
    let count = 0;
    let total = 0;
    await tar.list({ file: absolute, onentry: (entry) => {
      count += 1;
      total += entry.size ?? 0;
      if (count > MAX_FILES || total > MAX_TOTAL_BYTES) fail('Archive exceeds package limits');
      if (!safeRelativePath(entry.path) || !['File', 'Directory'].includes(entry.type)) fail(`Unsafe archive entry: ${entry.path}`);
      if ((entry.mode & 0o111) !== 0 && entry.type === 'File') fail(`Executable archive entry is not allowed: ${entry.path}`);
      if ((entry.size ?? 0) > MAX_FILE_BYTES) fail(`Archive entry exceeds 5 MiB: ${entry.path}`);
    }});
    await tar.extract({ cwd: temporary, file: absolute, preservePaths: false, strict: true });
    const roots = (await readdir(temporary, { withFileTypes: true })).filter((entry) => entry.isDirectory());
    if (roots.length !== 1) fail('Archive must contain exactly one top-level directory');
    const root = join(temporary, roots[0].name);
    return { temporary, result: await validate(root, true) };
  } catch (error) {
    await rm(temporary, { recursive: true, force: true });
    throw error;
  }
}

async function main() {
  const [command, target, ...args] = process.argv.slice(2);
  if (!command || command === '--help' || command === '-h') return console.log(usage());
  if (command === 'init') return initPack(target, option(args, '--key'), option(args, '--version', '0.1.0'));
  if (command === 'validate') {
    const result = await validate(target, existsSync(join(resolve(target), 'checksums.sha256')));
    return console.log(JSON.stringify({ valid: true, key: result.manifest.key, version: result.manifest.version, fingerprint: result.fingerprint }, null, 2));
  }
  if (command === 'pack') return packagePack(target, option(args, '--out'));
  if (command === 'verify') {
    const extracted = await extractVerified(target);
    try { return console.log(JSON.stringify({ valid: true, key: extracted.result.manifest.key, version: extracted.result.manifest.version, fingerprint: extracted.result.fingerprint }, null, 2)); }
    finally { await rm(extracted.temporary, { recursive: true, force: true }); }
  }
  if (command === 'install') {
    const rootOption = option(args, '--root');
    if (!rootOption) fail('--root is required');
    const installRoot = resolve(rootOption);
    await mkdir(installRoot, { recursive: true });
    if ((await lstat(installRoot)).isSymbolicLink()) fail('Domain Pack install root must not be a symbolic link');
    const extracted = await extractVerified(target, installRoot);
    let lock;
    let lockPath;
    try {
      const destination = resolve(installRoot, extracted.result.manifest.key, extracted.result.manifest.version);
      const keyRoot = dirname(destination);
      await mkdir(keyRoot, { recursive: true });
      lockPath = join(keyRoot, `.${extracted.result.manifest.version}.install.lock`);
      try {
        lock = await open(lockPath, 'wx');
      } catch (error) {
        if (error?.code === 'EEXIST') fail(`Domain Pack version is already being installed: ${destination}`);
        throw error;
      }
      if (existsSync(destination)) fail(`Domain Pack version is already installed: ${destination}`);
      await rename(extracted.result.root, destination);
      console.log(destination);
    } finally {
      await lock?.close();
      if (lockPath) await rm(lockPath, { force: true });
      await rm(extracted.temporary, { recursive: true, force: true });
    }
    return;
  }
  if (command === 'list') {
    const rootOption = option([target, ...args].filter(Boolean), '--root');
    if (!rootOption) fail('--root is required');
    const root = resolve(rootOption);
    const packs = [];
    if (existsSync(root)) {
      for (const key of await readdir(root, { withFileTypes: true })) if (key.isDirectory() && !key.name.startsWith('.')) {
        for (const version of await readdir(join(root, key.name), { withFileTypes: true })) if (version.isDirectory()) {
          const result = await validate(join(root, key.name, version.name), existsSync(join(root, key.name, version.name, 'checksums.sha256')));
          packs.push({ key: result.manifest.key, version: result.manifest.version, fingerprint: result.fingerprint });
        }
      }
    }
    return console.log(JSON.stringify(packs, null, 2));
  }
  fail(`Unknown command: ${command}\n\n${usage()}`);
}

main().catch((error) => {
  console.error(`rwpack: ${error.message}`);
  process.exitCode = 1;
});
