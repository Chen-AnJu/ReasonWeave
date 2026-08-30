#!/usr/bin/env node
import { pathToFileURL } from 'node:url';

const scenarios = {
  kubernetes: {
    domainPack: 'kubernetes-pod-diagnostics/1.0.0',
    eventType: 'kubernetes_pod_failure',
    eventTypePath: 'kubernetes-pod-diagnostics/versions/1.0.0/event-types/kubernetes_pod_failure',
    create(suffix) {
      const label = `default/reasonweave-demo-${suffix}`;
      return {
        event: {
          event_ir: {
            schema_version: 'eventir/0.1',
            event: {
              type: this.eventType,
              title: '镜像拉取失败调查',
              reference_code: `EVT-RW-K8S-${suffix}`,
              domain_pack: this.domainPack,
            },
            subjects: [{
              type: 'kubernetes_pod',
              label,
              attributes: { namespace: 'default', pod_name: `reasonweave-demo-${suffix}` },
            }],
          },
        },
        bundle: {
          schema_version: 'observation-bundle/1.0',
          domain_pack: this.domainPack,
          event_type: this.eventType,
          target_version: 'v1.37.0',
          subject: {
            type: 'kubernetes_pod',
            label,
            attributes: { namespace: 'default', pod_name: `reasonweave-demo-${suffix}` },
          },
          evidence_items: [{
            external_id: `quickstart-pod-status-${suffix}`,
            source_type: 'kubernetes_api',
            captured_at: new Date().toISOString(),
            observations: [{
              predicate: 'image_pull_backoff',
              value: true,
              confidence: 1,
              source_locator: { kind: 'Pod', field: 'status.containerStatuses.state.waiting.reason' },
            }],
          }],
        },
      };
    },
  },
  'cold-holding': {
    domainPack: 'cold-holding-excursion-diagnostics/1.0.0',
    eventType: 'cold_holding_temperature_excursion',
    eventTypePath: 'cold-holding-excursion-diagnostics/versions/1.0.0/event-types/cold_holding_temperature_excursion',
    create(suffix) {
      const label = `demo-site/unit-${suffix}`;
      return {
        event: {
          event_ir: {
            schema_version: 'eventir/0.1',
            event: {
              type: this.eventType,
              title: '冷藏单元开门热负荷调查',
              reference_code: `EVT-RW-COLD-${suffix}`,
              domain_pack: this.domainPack,
              occurred_at: { start: '2026-08-28T00:00:00Z', end: '2026-08-28T02:00:00Z' },
            },
            subjects: [{
              type: 'cold_holding_unit',
              label,
              attributes: {
                site_id: 'demo-site',
                unit_id: `unit-${suffix}`,
                unit_type: 'walk_in_cooler',
                temperature_limit_c: 5,
                minimum_excursion_minutes: 15,
                maximum_sample_gap_minutes: 10,
                sensor_tolerance_c: 1,
                policy_reference: 'Demo operating threshold',
              },
            }],
          },
        },
        bundle: {
          schema_version: 'observation-bundle/1.0',
          domain_pack: this.domainPack,
          event_type: this.eventType,
          subject: {
            type: 'cold_holding_unit',
            label,
            attributes: { site_id: 'demo-site', unit_id: `unit-${suffix}` },
          },
          evidence_items: [{
            external_id: `quickstart-cold-summary-${suffix}`,
            source_type: 'collector_derived',
            captured_at: '2026-08-28T02:00:00Z',
            observations: [
              'temperature_excursion_detected',
              'operational_heat_load_detected',
              'prolonged_door_open_overlaps_excursion',
              'warm_load_introduced_before_excursion',
            ].map((predicate) => ({
              predicate,
              value: true,
              confidence: 1,
              source_locator: {
                kind: 'cold_holding_telemetry_summary',
                algorithm_version: 'quickstart/1.0.0',
              },
            })),
          }],
        },
      };
    },
  },
};

export async function runScenario({ baseUrl, scenarioName, fetchImpl = fetch }) {
  const scenario = scenarios[scenarioName];
  if (!scenario) throw new Error(`Unknown scenario '${scenarioName}'. Use kubernetes or cold-holding.`);

  const api = createApi(baseUrl, fetchImpl);
  const suffix = Date.now().toString(36);
  const payload = scenario.create(suffix);

  const runtime = await api('/api/v1/runtime');
  const packs = await api('/api/v1/domain-packs');
  const selectedPack = packs.find((pack) => `${pack.key}/${pack.version}` === scenario.domainPack);
  if (!selectedPack) throw new Error(`Domain Pack is not installed: ${scenario.domainPack}`);
  if (!selectedPack.ready) {
    throw new Error(`Domain Pack is not ready: ${(selectedPack.readiness_reasons ?? []).join('; ') || 'unknown reason'}`);
  }
  await api(`/api/v1/domain-packs/${scenario.eventTypePath}`);

  const event = await api('/api/v1/events', {
    method: 'POST',
    headers: { 'Idempotency-Key': `quickstart-event-${scenarioName}-${suffix}` },
    body: payload.event,
  });
  const imported = await api(`/api/v1/events/${event.id}/evidence/bundles`, {
    method: 'POST',
    body: payload.bundle,
  });

  const observations = imported.evidence.flatMap((entry) => entry.observations);
  for (const observation of observations) {
    await api(`/api/v1/observations/${observation.id}`, {
      method: 'PATCH',
      headers: { 'If-Match': String(observation.version) },
      body: { verification_status: 'CONFIRMED' },
    });
  }

  const run = await api(`/api/v1/events/${event.id}/investigations`, {
    method: 'POST',
    headers: { 'Idempotency-Key': `quickstart-investigation-${scenarioName}-${suffix}` },
  });
  const [knowledge, nextEvidence, graph, audit] = await Promise.all([
    api(`/api/v1/investigations/${run.id}/knowledge-context`),
    api(`/api/v1/investigations/${run.id}/next-evidence`),
    api(`/api/v1/events/${event.id}/graph?investigation_id=${encodeURIComponent(run.id)}`),
    api(`/api/v1/events/${event.id}/audit`),
  ]);

  const top = run.result?.hypotheses?.[0];
  if (!top) throw new Error(`Investigation ${run.id} completed without hypotheses.`);
  const citationIds = new Set(top.citation_ids ?? []);
  const citations = knowledge.citations.filter((citation) => citationIds.has(citation.id));

  return {
    scenario: scenarioName,
    runtime: { api_version: runtime.api_version, deployment_mode: runtime.deployment_mode },
    domain_pack: scenario.domainPack,
    domain_pack_fingerprint: run.domain_pack_fingerprint,
    knowledge_index_version: run.knowledge_index_version,
    evidence_snapshot_hash: run.evidence_snapshot_hash,
    event_id: event.id,
    investigation_id: run.id,
    status: run.status,
    top_hypothesis: {
      code: top.code,
      title: top.title,
      support_index: top.score,
      coverage: Number(top.coverage.toFixed(4)),
      band: top.band,
      grounding_status: top.grounding_status,
    },
    scoring_evidence: top.contributions.map((item) => ({
      predicate: item.predicate,
      relation: item.relation,
      contribution: Number(item.value.toFixed(4)),
      evidence_id: item.evidence_id,
    })),
    knowledge_citations: citations.map((citation) => ({
      citation_id: citation.id,
      knowledge_unit_id: citation.knowledge_unit_id,
      source_locator: citation.source_locator,
      score_affecting: false,
    })),
    next_evidence: nextEvidence.slice(0, 3).map((item) => ({ title: item.title, reason: item.reason })),
    graph: { nodes: graph.nodes.length, edges: graph.edges.length },
    audit_records: audit.items.length,
    disclaimer: run.result.support_index_disclaimer,
  };
}

function createApi(baseUrl, fetchImpl) {
  const origin = new URL(baseUrl);
  return async (path, options = {}) => {
    const headers = { Accept: 'application/json', ...options.headers };
    const request = { method: options.method ?? 'GET', headers };
    if (options.body !== undefined) {
      headers['Content-Type'] = 'application/json';
      request.body = JSON.stringify(options.body);
    }
    const response = await fetchImpl(new URL(path, origin), request);
    const text = await response.text();
    let payload;
    try { payload = JSON.parse(text); }
    catch { throw new Error(`${request.method} ${path} returned non-JSON HTTP ${response.status}.`); }
    if (!response.ok) {
      const code = payload?.error?.code ?? `HTTP_${response.status}`;
      const message = payload?.error?.message ?? response.statusText;
      const requestId = payload?.meta?.request_id ?? response.headers.get('x-reasonweave-request-id');
      throw new Error(`${request.method} ${path} failed: ${code}: ${message}${requestId ? ` (request_id=${requestId})` : ''}`);
    }
    if (!payload || !Object.hasOwn(payload, 'data')) {
      throw new Error(`${request.method} ${path} returned an invalid success envelope.`);
    }
    return payload.data;
  };
}

function usage() {
  return `ReasonWeave quick-start scenario runner

Usage:
  node examples/quickstart/run.mjs [--scenario kubernetes|cold-holding] [--base-url URL]

Defaults:
  --scenario kubernetes
  --base-url http://127.0.0.1:8080
`;
}

async function main() {
  const args = process.argv.slice(2);
  if (args.includes('--help') || args.includes('-h')) return console.log(usage());
  let scenarioName = 'kubernetes';
  let baseUrl = 'http://127.0.0.1:8080';
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    if (argument !== '--scenario' && argument !== '--base-url') {
      throw new Error(`Unknown argument '${argument}'.\n\n${usage()}`);
    }
    const value = args[index + 1];
    if (!value || value.startsWith('--')) throw new Error(`${argument} requires a value.`);
    if (argument === '--scenario') scenarioName = value;
    else baseUrl = value;
    index += 1;
  }
  const result = await runScenario({ baseUrl, scenarioName });
  console.log(JSON.stringify(result, null, 2));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    console.error(`reasonweave-example: ${error.message}`);
    process.exitCode = 1;
  });
}
