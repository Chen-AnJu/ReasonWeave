import assert from 'node:assert/strict';
import test from 'node:test';
import { runScenario } from './run.mjs';

test('runs the public flow and keeps citations out of scoring evidence', async () => {
  const calls = [];
  const fetchImpl = async (url, request) => {
    calls.push({ url: String(url), request });
    const path = new URL(url).pathname;
    const response = responseFor(path, request.method);
    return new Response(JSON.stringify({ data: response, meta: { request_id: 'req_example' } }), {
      status: request.method === 'POST' ? 201 : 200,
      headers: { 'Content-Type': 'application/json' },
    });
  };

  const result = await runScenario({
    baseUrl: 'http://127.0.0.1:8080',
    scenarioName: 'kubernetes',
    fetchImpl,
  });

  assert.equal(result.top_hypothesis.code, 'image_acquisition_failure');
  assert.equal(result.knowledge_citations[0].score_affecting, false);
  assert.ok(result.scoring_evidence.every((item) => item.predicate === 'image_pull_backoff'));
  assert.ok(calls.some((call) => call.url.includes('/graph?investigation_id=inv_example')));
  assert.ok(calls.some((call) => call.request.headers['Idempotency-Key']));
  const createEvent = calls.find((call) => call.url.endsWith('/api/v1/events') && call.request.method === 'POST');
  assert.match(JSON.parse(createEvent.request.body).event_ir.event.reference_code, /^EVT-[A-Za-z0-9-]+$/);
});

function responseFor(path, method) {
  if (path.endsWith('/runtime')) return { api_version: 'v1', deployment_mode: 'self_hosted' };
  if (path.endsWith('/domain-packs')) return [{
    key: 'kubernetes-pod-diagnostics', version: '1.0.0', ready: true,
  }];
  if (path.includes('/event-types/')) return { subject_type: 'kubernetes_pod' };
  if (path.endsWith('/events') && method === 'POST') return { id: 'evt_example' };
  if (path.endsWith('/evidence/bundles')) return {
    evidence: [{ observations: [{ id: 'obs_example', version: 0 }] }],
  };
  if (path.includes('/observations/')) return { id: 'obs_example', version: 1 };
  if (path.endsWith('/investigations') && method === 'POST') return {
    id: 'inv_example', status: 'COMPLETED', result: {
      support_index_disclaimer: '支持指数不是概率',
      hypotheses: [{
        code: 'image_acquisition_failure', title: '镜像获取失败', score: 82, coverage: 0.88,
        band: 'SUPPORTED', grounding_status: 'GROUNDED', citation_ids: ['cit_example'],
        contributions: [{ predicate: 'image_pull_backoff', relation: 'SUPPORTS', value: 0.95, evidence_id: 'ev_example' }],
      }],
    },
  };
  if (path.endsWith('/knowledge-context')) return {
    citations: [{ id: 'cit_example', knowledge_unit_id: 'ku_example', source_locator: { url: 'https://kubernetes.io/' } }],
  };
  if (path.endsWith('/next-evidence')) return [{ title: '核对镜像引用', reason: '区分候选原因' }];
  if (path.endsWith('/graph')) return { nodes: [{ id: 'event' }], edges: [{ id: 'grounding' }] };
  if (path.endsWith('/audit')) return { items: [{ id: 'audit_example' }] };
  throw new Error(`Unhandled test request: ${method} ${path}`);
}
