import { expect, test, type APIRequestContext, type APIResponse, type Page } from '@playwright/test';
import { execFile } from 'node:child_process';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { promisify } from 'node:util';

const runFile = promisify(execFile);

test('真实栈完成 Kubernetes Pod 调查并保持旧调查快照不变', async ({ page, request }) => {
  test.setTimeout(180_000);
  const browserProblems: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error' || message.type() === 'warning') {
      browserProblems.push(`${message.type()}: ${message.text()}`);
    }
  });
  page.on('pageerror', (error) => browserProblems.push(`pageerror: ${error.message}`));
  page.on('requestfailed', (request) => {
    if (new URL(request.url()).pathname.startsWith('/api/v1/')) {
      browserProblems.push(
        `API 请求失败: ${request.method()} ${request.url()} · ${request.failure()?.errorText ?? '未知原因'}`,
      );
    }
  });

  const runtime = readRecord(await getData(request, '/api/v1/runtime'));
  expect(readString(runtime, 'deployment_mode')).toBe('self_hosted');

  const suffix = `${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;
  await page.goto('/events/new');
  await expect(page.locator('select[name="domainPackKey"]')).toHaveValue('');
  await page.locator('select[name="domainPackKey"]')
    .selectOption('kubernetes-pod-diagnostics/1.0.0');
  await expect(page.locator('select[name="domainPackKey"]')).toHaveValue('kubernetes-pod-diagnostics/1.0.0');
  await expect(page.locator('select[name="eventType"]')).toHaveValue('kubernetes_pod_failure');
  await page.getByLabel('事件标题').fill(`真实栈 Pod 镜像故障 ${suffix}`);
  const podName = `image-pull-${suffix}`.slice(0, 63);
  await page.locator('input[name="subjectAttributes.namespace"]').fill('default');
  await page.locator('input[name="subjectAttributes.pod_name"]').fill(podName);
  await expect(page.getByText('双重校验有效')).toBeVisible();
  await page.getByRole('button', { name: '创建事件' }).click();
  await expect(page).toHaveURL(/\/events\/evt_[0-9A-HJKMNP-TV-Z]{26}$/);
  const eventId = page.url().split('/').at(-1) ?? '';
  expect(eventId).not.toBe('');

  const uploadResponsePromise = page.waitForResponse((response) =>
    response.request().method() === 'POST'
      && response.url().endsWith(`/api/v1/events/${eventId}/evidence/bundles`),
  );
  const bundle = observationBundle(eventId, podName);
  await page.locator('input[accept="application/json,.json"]').setInputFiles({
    name: 'pod-observations.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(bundle)),
  });
  const uploadDetail = readRecord(await responseData(await uploadResponsePromise));
  const importedDetail = readRecord(readArray(uploadDetail, 'evidence')[0]);
  const uploadedEvidence = readRecord(importedDetail.evidence);
  const evidenceId = readString(uploadedEvidence, 'id');
  expect(evidenceId).not.toBe('');
  await expect(page.getByText(/已导入 1 项证据/)).toBeVisible();

  await page.goto(`/evidence/${evidenceId}`);
  await expect(page.getByText('待复核', { exact: true }).first()).toBeVisible();
  const verifyResponsePromise = page.waitForResponse((response) =>
    response.request().method() === 'PATCH' && response.url().includes('/api/v1/observations/'),
  );
  await page.getByRole('button', { name: '确认' }).first().click();
  await responseData(await verifyResponsePromise);
  await expect(page.getByText('已确认', { exact: true }).first()).toBeVisible();

  await page.goto(`/events/${eventId}`);
  const firstRun = await startInvestigation(page, eventId, '重新调查');
  const firstRunId = readString(firstRun, 'id');
  const firstSnapshotHash = readString(firstRun, 'evidence_snapshot_hash');
  const firstSnapshot = structuredClone(readRecord(firstRun.evidence_snapshot));
  const firstResult = structuredClone(readRecord(firstRun.result));
  expect(readNumber(firstRun, 'evidence_snapshot_schema_version')).toBe(2);
  expect(firstRunId).not.toBe('');
  expect(firstSnapshotHash).not.toBe('');

  const knowledgeContext = readRecord(await getData(
    request,
    `/api/v1/investigations/${firstRunId}/knowledge-context`,
  ));
  expect(readArray(knowledgeContext, 'citations').length).toBeGreaterThan(0);

  const textEvidence = `Pod 仍处于 ImagePullBackOff，记录 ${suffix}`;
  await page.getByLabel('文本证据').fill(textEvidence);
  const textResponsePromise = page.waitForResponse((response) =>
    response.request().method() === 'POST'
      && response.url().endsWith(`/api/v1/events/${eventId}/evidence/text`),
  );
  await page.getByRole('button', { name: '添加现象描述' }).click();
  const textDetail = readRecord(await responseData(await textResponsePromise));
  expect(readString(readRecord(textDetail.evidence), 'id')).not.toBe('');
  await expect(page.getByLabel('文本证据')).toHaveValue('');
  await expect(page.getByText(/最近一轮调查已过期/)).toBeVisible();

  const firstRunAfterNewEvidence = readRecord(await getData(
    request,
    `/api/v1/investigations/${firstRunId}`,
  ));
  expect(readRecord(firstRunAfterNewEvidence.evidence_snapshot)).toEqual(firstSnapshot);
  expect(readRecord(firstRunAfterNewEvidence.result)).toEqual(firstResult);
  expect(readString(firstRunAfterNewEvidence, 'evidence_snapshot_hash')).toBe(firstSnapshotHash);

  const secondRun = await startInvestigation(page, eventId, '重新调查');
  const secondRunId = readString(secondRun, 'id');
  expect(secondRunId).not.toBe(firstRunId);
  expect(readNumber(secondRun, 'sequence_no')).toBeGreaterThan(readNumber(firstRun, 'sequence_no'));
  expect(readNumber(secondRun, 'evidence_snapshot_schema_version')).toBe(2);
  expect(readString(secondRun, 'evidence_snapshot_hash')).not.toBe(firstSnapshotHash);

  await page.goto(`/events/${eventId}/investigation`);
  await expect(page.getByRole('heading', { name: '调查工作台' })).toBeVisible();
  await expect(page.getByText(/不是发生概率/).first()).toBeVisible();
  await expect(page.getByLabel('调查运行')).toHaveValue(secondRunId);

  await page.goto(`/events/${eventId}/graph?investigation_id=${secondRunId}`);
  await expect(page.getByRole('heading', { name: '因果关系图' })).toBeVisible();
  await expect(page.locator('.react-flow__node')).not.toHaveCount(0);
  await expect(page.getByText(/不参与支持指数/).first()).toBeVisible();

  const auditPageResponses = Promise.all([
    page.waitForResponse((response) => response.url().endsWith(`/api/v1/events/${eventId}`)),
    page.waitForResponse((response) => new URL(response.url()).pathname === `/api/v1/events/${eventId}/investigations`),
    page.waitForResponse((response) => response.url().includes(`/api/v1/events/${eventId}/audit?`)),
  ]);
  await page.goto(`/events/${eventId}/audit`);
  for (const response of await auditPageResponses) {
    expect(response.ok(), `审计页依赖接口返回 HTTP ${response.status()}`).toBeTruthy();
  }
  await expect(page.getByRole('heading', { name: '审计时间线' })).toBeVisible();
  const timeline = page.locator('.rw-audit-timeline');
  await expect(timeline.getByText('调查运行已完成', { exact: true }).first()).toBeVisible();
  await expect(timeline.getByText('已添加文本证据', { exact: true }).first()).toBeVisible();
  await expect(timeline.getByText('已导入标准观察证据包', { exact: true }).first()).toBeVisible();

  const openApi = readRecord(await getRawJson(request, '/api/v1/openapi'));
  const paths = readRecord(openApi.paths);
  expect(readRecord(paths['/api/v1/knowledge/sources']).post).toBeUndefined();
  expect(readRecord(paths['/api/v1/knowledge/sources/{sourceId}/documents']).post).toBeUndefined();
  expect(readRecord(paths['/api/v1/events/{eventId}/evidence/bundles']).post).toBeDefined();
  expect(browserProblems, '真实栈浏览器控制台不应出现错误或警告').toEqual([]);
});

test('真实栈用冷藏采集器完成第二领域的完整 API 调查链路', async ({ request }) => {
  test.setTimeout(180_000);
  const fixture = resolve(process.cwd(), '../fixtures/cold-holding/zenodo-15130001');
  const temporary = await mkdtemp(join(tmpdir(), 'reasonweave-cold-real-'));
  const bundlePath = join(temporary, 'bundle.json');
  try {
    const packs = await getData(request, '/api/v1/domain-packs');
    expect(Array.isArray(packs)).toBeTruthy();
    expect((packs as unknown[]).some((value) => (
      readString(readRecord(value), 'key') === 'cold-holding-excursion-diagnostics'
      && readRecord(value).ready === true
    ))).toBeTruthy();

    const definition = readRecord(await getData(
      request,
      '/api/v1/domain-packs/cold-holding-excursion-diagnostics/versions/1.0.0/event-types/cold_holding_temperature_excursion',
    ));
    expect(readString(definition, 'subject_type')).toBe('cold_holding_unit');
    expect(readString(readRecord(definition.event_requirements), 'time_range')).toBe('required');

    await runFile(process.execPath, [
      resolve(process.cwd(), '../tools/evidence-cli/src/cli.mjs'),
      'cold-holding', 'collect',
      '--event-ir', join(fixture, 'event-ir.json'),
      '--sources', join(fixture, 'sources.json'),
      '--telemetry', join(fixture, 'telemetry.csv'),
      '--out', bundlePath,
    ], { windowsHide: true, timeout: 60_000 });
    const eventIr = JSON.parse(await readFile(join(fixture, 'event-ir.json'), 'utf8')) as unknown;
    const bundle = JSON.parse(await readFile(bundlePath, 'utf8')) as unknown;
    const suffix = `${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;

    const event = readRecord(await responseData(await request.post('/api/v1/events', {
      headers: { 'Idempotency-Key': `cold-real-event-${suffix}` },
      data: { event_ir: eventIr },
    })));
    const eventId = readString(event, 'id');
    expect(eventId).not.toBe('');

    const imported = readRecord(await responseData(await request.post(
      `/api/v1/events/${eventId}/evidence/bundles`,
      { data: bundle },
    )));
    const importedEvidence = readRecord(readArray(imported, 'evidence')[0]);
    const observations = readArray(importedEvidence, 'observations').map(readRecord);
    expect(observations.length).toBeGreaterThan(0);
    for (const observation of observations) {
      await responseData(await request.patch(`/api/v1/observations/${readString(observation, 'id')}`, {
        headers: { 'If-Match': String(readNumber(observation, 'version')) },
        data: { verification_status: 'CONFIRMED' },
      }));
    }

    const retrieval = readRecord(await responseData(await request.post('/api/v1/retrieval/debug', {
      data: {
        query: '冷藏间开门和较热物品装入后温度升高',
        event_type: 'cold_holding_temperature_excursion',
        observed_predicates: ['operational_heat_load_detected'],
        intent: 'CAUSE_CANDIDATES',
        domain_pack_key: 'cold-holding-excursion-diagnostics/1.0.0',
      },
    })));
    const selectedHits = readArray(readRecord(readArray(retrieval, 'intents')[0]), 'hits')
      .map(readRecord)
      .filter((value) => value.selected === true);
    expect(selectedHits.length).toBeGreaterThan(0);
    expect(selectedHits.some((value) => readNumber(value, 'vector_rank') > 0)).toBeTruthy();

    const run = readRecord(await responseData(await request.post(
      `/api/v1/events/${eventId}/investigations`,
      { headers: { 'Idempotency-Key': `cold-real-investigation-${suffix}` } },
    )));
    const runId = readString(run, 'id');
    const result = readRecord(run.result);
    const hypotheses = readArray(result, 'hypotheses').map(readRecord);
    expect(readString(hypotheses[0], 'code')).toBe('operational_heat_load_or_airflow');
    expect(readString(hypotheses[0], 'grounding_status')).toBe('GROUNDED');
    expect(readArray(hypotheses[0], 'citation_ids').length).toBeGreaterThan(0);
    expect(hypotheses.flatMap((value) => readArray(value, 'contributions').map(readRecord))
      .some((value) => readString(value, 'relation') === 'KNOWLEDGE_HIT')).toBeFalsy();

    expect(Array.isArray(await getData(request, `/api/v1/investigations/${runId}/next-evidence`)))
      .toBeTruthy();
    const graph = readRecord(await getData(
      request, `/api/v1/events/${eventId}/graph?investigation_id=${runId}`,
    ));
    expect(readArray(graph, 'edges').map(readRecord)
      .filter((edge) => readString(edge, 'type') === 'GROUNDED_BY'))
      .not.toHaveLength(0);
    expect(readArray(graph, 'edges').map(readRecord)
      .filter((edge) => readString(edge, 'type') === 'GROUNDED_BY')
      .every((edge) => edge.score_affecting === false)).toBeTruthy();
    const audit = readRecord(await getData(request, `/api/v1/events/${eventId}/audit?limit=100`));
    expect(readArray(audit, 'items').map(readRecord)
      .some((entry) => readString(entry, 'action') === 'investigation.completed')).toBeTruthy();
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

function observationBundle(eventId: string, podName: string) {
  return {
    schema_version: 'observation-bundle/1.0',
    domain_pack: 'kubernetes-pod-diagnostics/1.0.0',
    event_type: 'kubernetes_pod_failure',
    target_version: 'v1.37.0',
    subject: {
      type: 'kubernetes_pod',
      label: `default/${podName}`,
      attributes: { namespace: 'default', pod_name: podName },
    },
    evidence_items: [{
      external_id: `pod-status:${eventId}`,
      source_type: 'kubernetes_api',
      captured_at: new Date().toISOString(),
      observations: [{
        predicate: 'image_pull_backoff', value: true, confidence: 1,
        description: '容器等待原因为 ImagePullBackOff',
        source_locator: { kind: 'Pod', namespace: 'default', field: 'status.containerStatuses[].state.waiting.reason' },
      }],
    }],
  };
}

async function startInvestigation(page: Page, eventId: string, buttonName: string) {
  const responsePromise = page.waitForResponse((response) =>
    response.request().method() === 'POST'
      && response.url().endsWith(`/api/v1/events/${eventId}/investigations`),
  );
  await page.getByRole('button', { name: buttonName }).click();
  return readRecord(await responseData(await responsePromise));
}

async function getData(request: APIRequestContext, path: string) {
  return responseData(await request.get(path));
}

async function getRawJson(request: APIRequestContext, path: string) {
  const response = await request.get(path);
  expect(response.ok(), `${path} should return a successful response`).toBeTruthy();
  return response.json() as Promise<unknown>;
}

async function responseData(response: APIResponse) {
  const payload = await response.json() as unknown;
  expect(response.ok(), `HTTP ${response.status()}: ${JSON.stringify(payload)}`).toBeTruthy();
  return readRecord(payload).data;
}

function readRecord(value: unknown): Record<string, unknown> {
  return value != null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
}

function readString(record: Record<string, unknown>, key: string) {
  return typeof record[key] === 'string' ? record[key] : '';
}

function readNumber(record: Record<string, unknown>, key: string) {
  return typeof record[key] === 'number' ? record[key] : Number.NaN;
}

function readArray(record: Record<string, unknown>, key: string) {
  return Array.isArray(record[key]) ? record[key] : [];
}
