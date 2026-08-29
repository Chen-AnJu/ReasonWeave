import { expect, test, type BrowserContext, type Page, type Route } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { readFile } from 'node:fs/promises';

const eventId = 'evt_fixture_k8s_pod_001';
const evidenceId = 'ev_fixture_k8s_api_01';
const run1 = run('inv_run_01', 1, 58, 0.62, [evidenceId]);
const run2 = run('inv_run_02', 2, 82, 0.88, [evidenceId, 'ev_new']);
const browserProblems = new WeakMap<Page, string[]>();

function envelope(data: unknown) {
  return { data, meta: { request_id: 'req_e2e' } };
}

function run(id: string, sequence: number, score: number, coverage: number, evidenceIds: string[]) {
  return {
    id,
    event_id: eventId,
    sequence_no: sequence,
    status: 'COMPLETED',
    event_version: sequence,
    evidence_snapshot_schema_version: 2,
    evidence_snapshot_hash: `hash_${sequence}`,
    evidence_snapshot: { schema_version: 2, evidence_ids: evidenceIds, evidence: [] },
    model_policy_version: 'grounded-policy-v1',
    rule_pack_version: '1.0.0',
    domain_pack_key: 'kubernetes-pod-diagnostics',
    domain_pack_version: '1.0.0',
    domain_pack_fingerprint: 'f'.repeat(64),
    knowledge_index_version: 'kubernetes-pod-diagnostics/1.0.0:index',
    retrieval_run_id: `ret_${sequence}`,
    event_ir_snapshot: eventIr(),
    stale: sequence === 1,
    started_at: '2026-08-27T02:59:00Z',
    completed_at: '2026-08-27T03:00:00Z',
    created_at: '2026-08-27T03:00:00Z',
    result: {
      support_index_disclaimer: '支持指数不是概率，只表示当前证据与规则下的相对支持程度',
      pipeline: 'QUERY_PLAN -> KNOWLEDGE_CONTEXT -> GROUNDED_HYPOTHESIS -> EXPECTED_EVIDENCE -> EVIDENCE_RELATION -> SCORE_COVERAGE -> GAP -> NEXT_EVIDENCE',
      evidence_snapshot: { evidence_ids: evidenceIds },
      hypotheses: [{
        id: `hyp_${sequence}`,
        code: 'image_acquisition_failure',
        title: 'Image acquisition failure',
        description: '受控领域假设',
        score,
        band: 'SUPPORTED',
        coverage,
        positive: 0.8,
        negative: 0,
        missing_penalty: 0,
        expected_evidence: [{ predicate: 'image_pull_backoff', weight: 1, relation: 'STRONGLY_SUPPORTS', required: true, origin: 'DOMAIN_RULE', rule_id: 'image.pull.backoff' }],
        contributions: [{ rule_id: 'image.pull.backoff', predicate: 'image_pull_backoff', relation: 'STRONGLY_SUPPORTS', value: 0.9 }],
        citation_ids: ['cit_01'],
        grounding_status: 'GROUNDED',
        knowledge_limitations: [],
      }],
    },
  };
}

function eventIr() {
  return { schema_version: 'eventir/0.1', event: { type: 'kubernetes_pod_failure', title: 'default/api Pod 镜像拉取失败', domain_pack: 'kubernetes-pod-diagnostics/1.0.0' }, subjects: [{ id: 'subj_1', type: 'kubernetes_pod', label: 'default/api', attributes: { namespace: 'default', pod_name: 'api' } }] };
}

async function mockApi(context: BrowserContext, problems: string[]) {
  let confirmed = false;
  await context.route('**/api/v1/**', async (route: Route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    if (path === '/api/v1/runtime') return json(route, envelope({ api_version: 'v1', deployment_mode: 'self_hosted', instance_name: 'ReasonWeave 测试实例', capabilities: { authentication: false, multi_tenancy: false, asynchronous_investigation: false } }));
    if (path === '/api/v1/domain-packs') return json(route, envelope([domainPackSummary(), coldDomainPackSummary()]));
    if (path === '/api/v1/domain-packs/kubernetes-pod-diagnostics/versions/1.0.0') return json(route, envelope(domainPackDetail()));
    if (path === '/api/v1/domain-packs/kubernetes-pod-diagnostics/versions/1.0.0/event-types/kubernetes_pod_failure') return json(route, envelope(kubernetesEventTypeDefinition()));
    if (path === '/api/v1/domain-packs/cold-holding-excursion-diagnostics/versions/1.0.0') return json(route, envelope(coldDomainPackDetail()));
    if (path === '/api/v1/domain-packs/cold-holding-excursion-diagnostics/versions/1.0.0/event-types/cold_holding_temperature_excursion') return json(route, envelope(coldEventTypeDefinition()));
    if (path === '/api/v1/events' && method === 'POST') return json(route, envelope(eventDetail('evt_created')), 201);
    if (path === '/api/v1/events/evt_created') return json(route, envelope(eventDetail('evt_created')));
    if (path === '/api/v1/events/evt_created/view') return json(route, envelope({ event: eventDetail('evt_created'), evidence: [], latest_investigation: null, stale: false, unresolved_gaps: [] }));
    if (path === '/api/v1/events/evt_created/investigations') return json(route, envelope({ items: [], limit: 20, total: 0 }));
    if (path === `/api/v1/events/${eventId}`) return json(route, envelope(eventDetail(eventId)));
    if (path === `/api/v1/events/${eventId}/view`) return json(route, envelope({ event: eventDetail(eventId), evidence: [evidenceDetail(confirmed).evidence], latest_investigation: run2, stale: false, unresolved_gaps: [] }));
    if (path === `/api/v1/events/${eventId}/investigations`) return json(route, envelope({ items: [run2, run1], limit: 20, total: 2 }));
    if (path === `/api/v1/investigations/${run2.id}/next-evidence`) return json(route, envelope([{ id: 'gap_1', title: '检查镜像引用与拉取事件', reason: '可区分当前排名前两位假设', discriminates: ['image_acquisition_failure', 'configuration_or_mount_failure'], estimated_impact: 'HIGH', acquisition_cost: 'MEDIUM', priority_score: 0.7 }]));
    if (path === `/api/v1/investigations/${run2.id}/diff`) return json(route, envelope({ base_run_id: run1.id, current_run_id: run2.id, event_version_delta: 1, evidence_snapshot_changed: true, knowledge_index_changed: false, hypothesis_changes: [{ code: 'image_acquisition_failure', title: 'Image acquisition failure', before_score: 58, after_score: 82, score_delta: 24, before_coverage: 0.62, after_coverage: 0.88 }], added_evidence_ids: ['ev_new'], removed_evidence_ids: [] }));
    if (path === `/api/v1/events/${eventId}/graph`) return json(route, envelope(graphResult()));
    if (path === `/api/v1/events/${eventId}/audit/export`) return route.fulfill({ status: 200, contentType: 'application/x-ndjson', headers: { 'Content-Disposition': `attachment; filename="reasonweave-audit-${eventId}.jsonl"` }, body: `${JSON.stringify(auditItems()[0])}\n` });
    if (path === `/api/v1/events/${eventId}/audit`) return json(route, envelope({ items: auditItems(), limit: 50 }));
    if (path === `/api/v1/evidence/${evidenceId}` && method === 'GET') return json(route, envelope(evidenceDetail(confirmed)));
    if (path === `/api/v1/events/${eventId}/evidence/bundles` && method === 'POST') {
      const payload = request.postDataJSON() as { domain_pack?: string; event_type?: string };
      if (payload.domain_pack !== 'kubernetes-pod-diagnostics/1.0.0' || payload.event_type !== 'kubernetes_pod_failure') problems.push('Observation Bundle 请求作用域错误');
      return json(route, envelope({ schema_version: 'observation-bundle/1.0', bundle_hash: 'b'.repeat(64), duplicate: false, evidence: [evidenceDetail(false)] }), 201);
    }
    if (path === '/api/v1/observations/obs_01' && method === 'PATCH') { confirmed = true; return json(route, envelope({ ...evidenceDetail(true).observations[0] })); }
    if (path === '/api/v1/retrieval/debug' && method === 'POST') return json(route, envelope(retrievalResult()));
    if (path === '/api/v1/openapi') return json(route, openApiFixture());
    if (path === '/api/v1/knowledge/sources/ks_01') return json(route, envelope(knowledgeSourceDetail()));
    if (path === '/api/v1/knowledge/sources/ks_01/units') return json(route, envelope({ items: [knowledgeUnitSummary()], limit: 50 }));
    if (path === '/api/v1/knowledge/units/ku_01') return json(route, envelope(knowledgeUnitDetail()));
    if (path === '/api/v1/knowledge/units/ku_01/citation-usages') return json(route, envelope({ items: knowledgeUnitDetail().citation_usages, limit: 20, total: 1 }));
    if (path === '/api/v1/knowledge/units/ku_01/retrieval-usages') return json(route, envelope({ items: knowledgeUnitDetail().retrieval_usages, limit: 20, total: 1 }));
    if (path === '/api/v1/events') {
      const cursor = new URL(request.url()).searchParams.get('cursor');
      return json(route, envelope(cursor
        ? { items: [eventSummary('evt_page_2', '第二个 Pod 故障事件')], limit: 50, total: 2 }
        : { items: [eventSummary(eventId, 'default/api Pod 镜像拉取失败')], next_cursor: 'events-page-2', limit: 50, total: 2 }));
    }
    if (path === '/api/v1/evidence') return json(route, envelope({ items: [], limit: 50, total: 0 }));
    if (path === '/api/v1/knowledge/sources') return json(route, envelope([]));
    if (path === '/api/v1/knowledge/documents') return json(route, envelope([]));
    problems.push(`未匹配 API 请求: ${method} ${path}`);
    return json(route, { error: { code: 'UNMATCHED_MOCK_API', message: '测试未配置此 API' }, meta: { request_id: 'req_unmatched' } }, 500);
  });
}

test.beforeEach(async ({ context, page }) => {
  const messages: string[] = [];
  browserProblems.set(page, messages);
  page.on('console', (message) => {
    if (message.type() === 'warning' || message.type() === 'error') messages.push(`${message.type()}: ${message.text()}`);
  });
  page.on('pageerror', (error) => messages.push(`pageerror: ${error.message}`));
  page.on('requestfailed', (request) => {
    if (new URL(request.url()).pathname.startsWith('/api/v1/')) {
      messages.push(`API 请求失败: ${request.method()} ${request.url()} · ${request.failure()?.errorText ?? '未知原因'}`);
    }
  });
  await mockApi(context, messages);
});

test.afterEach(async ({ page }) => {
  expect(browserProblems.get(page) ?? [], '浏览器控制台不应出现错误或警告').toEqual([]);
});

test('开源核心导航不显示设置并支持加载更多事件', async ({ page }) => {
  await page.goto('/events');
  await expect(page.getByRole('link', { name: '设置' })).toHaveCount(0);
  await expect(page.getByRole('link', { name: /default\/api Pod 镜像拉取失败/ })).toBeVisible();
  await page.getByRole('button', { name: /加载更多/ }).click();
  await expect(page.getByRole('link', { name: /第二个 Pod 故障事件/ })).toBeVisible();
});

test('根路径进入本地实例且旧兼容入口不再重定向', async ({ page }) => {
  await page.goto('/');
  await expect(page).toHaveURL('/overview');

  for (const path of ['/login', '/sign-in', '/app/legacy', '/workspaces/ws_primary/events']) {
    await page.goto(path);
    await expect(page.getByText('页面不存在', { exact: true })).toBeVisible();
    await expect(page).toHaveURL(path);
  }
});

test('创建事件时实时校验 EventIR 并提交', async ({ page }) => {
  await page.goto('/events/new');
  await expect(page.locator('select[name="domainPackKey"]')).toHaveValue('');
  await expect(page.locator('select[name="eventType"]')).toBeDisabled();
  await page.locator('select[name="domainPackKey"]').selectOption('kubernetes-pod-diagnostics/1.0.0');
  await expect(page.locator('select[name="eventType"]')).toHaveValue('kubernetes_pod_failure');
  await expect(page.getByText('通用事件', { exact: true })).toHaveCount(0);
  await page.getByLabel('事件标题').fill('default/api Pod 镜像拉取失败测试');
  await page.locator('input[name="subjectAttributes.namespace"]').fill('default');
  await page.locator('input[name="subjectAttributes.pod_name"]').fill('api-7d8f4c9b6-x2k9p');
  await expect(page.getByText('双重校验有效')).toBeVisible();
  await page.getByRole('button', { name: '创建事件' }).click();
  await expect(page).toHaveURL(new RegExp('/events/evt_created$'));
});

test('同一动态表单渲染 Kubernetes 与中性设备领域', async ({ page }) => {
  await page.route(/\/api\/v1\/domain-packs$/, (route) => json(
    route,
    envelope([domainPackSummary(), equipmentDomainPackSummary()]),
  ));
  await page.route(
    /\/api\/v1\/domain-packs\/equipment-fault-test\/versions\/1\.0\.0$/,
    (route) => json(route, envelope(equipmentDomainPackDetail())),
  );
  await page.route(
    /\/api\/v1\/domain-packs\/equipment-fault-test\/versions\/1\.0\.0\/event-types\/equipment_fault$/,
    (route) => json(route, envelope(equipmentEventTypeDefinition())),
  );

  await page.goto('/events/new');
  await page.locator('select[name="domainPackKey"]').selectOption('kubernetes-pod-diagnostics/1.0.0');
  await expect(page.locator('input[name="subjectAttributes.namespace"]')).toBeVisible();
  await page.locator('select[name="domainPackKey"]').selectOption('equipment-fault-test/1.0.0');
  await expect(page.locator('select[name="eventType"]')).toHaveValue('equipment_fault');
  await expect(page.locator('input[name="subjectAttributes.asset_id"]')).toBeVisible();
  await expect(page.locator('input[name="subjectAttributes.site"]')).toBeVisible();
  await expect(page.locator('input[name="subjectAttributes.namespace"]')).toHaveCount(0);
  await page.getByLabel('事件标题').fill('循环泵温度异常');
  await page.locator('input[name="subjectAttributes.asset_id"]').fill('pump-001');
  await expect(page.getByText('双重校验有效')).toBeVisible();
});

test('冷藏领域包使用同一动态表单并强制完整时间窗', async ({ page }) => {
  await page.goto('/events/new');
  await page.locator('select[name="domainPackKey"]').selectOption('cold-holding-excursion-diagnostics/1.0.0');
  await expect(page.locator('select[name="eventType"]')).toHaveValue('cold_holding_temperature_excursion');
  await expect(page.locator('input[name="subjectAttributes.site_id"]')).toBeVisible();
  await expect(page.locator('input[name="subjectAttributes.temperature_limit_c"]')).toHaveAttribute('min', '-50');
  await expect(page.locator('input[name="subjectAttributes.temperature_limit_c"]')).toHaveAttribute('max', '20');
  await expect(page.getByText(/要求填写完整的发生开始和结束时间/)).toBeVisible();

  await page.getByLabel('事件标题').fill('一号冷藏间温度异常');
  await page.locator('input[name="subjectAttributes.site_id"]').fill('site-a');
  await page.locator('input[name="subjectAttributes.unit_id"]').fill('unit-1');
  await page.locator('select[name="subjectAttributes.unit_type"]').selectOption('walk_in_cooler');
  await page.locator('input[name="subjectAttributes.temperature_limit_c"]').fill('5');
  await page.locator('input[name="subjectAttributes.minimum_excursion_minutes"]').fill('15');
  await page.locator('input[name="subjectAttributes.maximum_sample_gap_minutes"]').fill('10');
  await page.locator('input[name="subjectAttributes.sensor_tolerance_c"]').fill('1');
  await page.locator('input[name="subjectAttributes.policy_reference"]').fill('Site SOP CH-04');
  await page.getByLabel('发生开始时间（必填）').fill('2026-08-01T08:00');
  await page.getByLabel('发生结束时间（必填）').fill('2026-08-01T10:00');
  await expect(page.getByText('双重校验有效')).toBeVisible();
});

test('Evidence Observation 支持人工确认', async ({ page }) => {
  await page.goto(`/evidence/${evidenceId}`);
  await expect(page.getByText('待复核', { exact: true }).first()).toBeVisible();
  await page.getByRole('button', { name: '确认' }).click();
  await expect(page.getByText('已确认', { exact: true }).first()).toBeVisible();
});

test('事件概览校验并导入 Kubernetes Observation Bundle', async ({ page }) => {
  await page.goto(`/events/${eventId}`);
  await page.locator('input[accept="application/json,.json"]').setInputFiles({
    name: 'pod-observations.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(observationBundleFixture())),
  });
  await expect(page.getByText(/已导入 1 项证据/)).toBeVisible();
  await expect(page.getByText(/请逐项打开并人工确认/)).toBeVisible();
});

test('检索检查器展示三路排名且明确评分隔离', async ({ page }) => {
  await page.goto('/retrieval');
  await page.getByLabel('查询').fill('镜像拉取失败');
  await page.getByRole('button', { name: '运行检索' }).click();
  await expect(page.getByText('容器镜像获取')).toBeVisible();
  await expect(page.getByText(/均禁止进入假设支持指数/)).toBeVisible();
});

test('调查工作台、Run Diff 与下一步取证可访问', async ({ page }) => {
  await page.goto(`/events/${eventId}/investigation`);
  await expect(page.getByText(/不是发生概率/)).toBeVisible();
  await page.getByRole('link', { name: '假设对比' }).click();
  await expect(page.getByText('+24')).toBeVisible();
  await page.getByRole('link', { name: '下一步取证' }).click();
  await expect(page.getByText('检查镜像引用与拉取事件')).toBeVisible();
  await expect(page.getByText('区分 镜像获取失败 / 配置依赖或卷挂载失败')).toBeVisible();
  expect(await page.locator('body').innerText()).not.toContain('image_acquisition_failure');
});

test('1024×900 折叠 Sidebar 且无横向滚动', async ({ page }, testInfo) => {
  test.skip(!testInfo.project.name.includes('1024'), '仅在 1024 项目验证');
  await page.goto(`/events/${eventId}/investigation`);
  const sizes = await page.evaluate(() => ({ scrollWidth: document.documentElement.scrollWidth, clientWidth: document.documentElement.clientWidth }));
  expect(sizes.scrollWidth).toBeLessThanOrEqual(sizes.clientWidth);
  await expect(page.locator('.rw-logo-full')).toBeHidden();
  await expect(page.locator('.rw-logo-mark')).toBeVisible();
});

test('键盘可进入交互控件且焦点清晰可见', async ({ page }) => {
  await page.goto(`/events/${eventId}/investigation`);
  await expect(page.getByText(/不是发生概率/)).toBeVisible();
  await page.keyboard.press('Tab');
  const focused = page.locator(':focus');
  await expect(focused).toBeVisible();
  expect(await focused.evaluate((element) => ['A', 'BUTTON', 'INPUT', 'SELECT', 'TEXTAREA'].includes(element.tagName))).toBe(true);
  expect(await focused.evaluate((element) => getComputedStyle(element).outlineWidth)).toBe('2px');
});

test('调查工作台通过自动化 WCAG A/AA 审计', async ({ page }) => {
  await page.goto(`/events/${eventId}/investigation`);
  await expect(page.getByText(/不是发生概率/)).toBeVisible();
  const result = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();
  expect(result.violations, JSON.stringify(result.violations, null, 2)).toEqual([]);
});

test('普通调查页面仅显示中文状态、关系与流水线', async ({ page }) => {
  await page.goto(`/events/${eventId}/investigation`);
  await expect(page.getByRole('heading', { name: '有依据的原因假设' })).toBeVisible();
  const visibleText = await page.locator('body').innerText();
  expect(visibleText).not.toContain('COMPLETED');
  expect(visibleText).not.toContain('STRONGLY_SUPPORTS');
  expect(visibleText).not.toContain('QUERY_PLAN');
  expect(visibleText).toContain('强支持');
  expect(visibleText).toContain('查询计划');
});

test('因果图支持路径高亮、知识计分隔离和 PNG 下载', async ({ page }) => {
  await page.goto(`/events/${eventId}/graph`);
  await expect(page.getByText('不参与支持指数')).toBeVisible();
  await expect(page.locator('.rw-flow-node--hypothesis')).toHaveCount(1);
  await expect(page.locator('.rw-flow-node--evidence')).toHaveCount(1);
  await expect(page.getByLabel('关系类型').locator('option')).toHaveText([
    '全部关系', '关联', '观察来源', '支持', '反驳', '解释', '知识依据', '待补证据',
  ]);
  const hypothesisNode = page.locator('.react-flow__node').filter({ has: page.locator('.rw-flow-node--hypothesis') }).first();
  await hypothesisNode.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: '镜像获取失败' })).toBeVisible();
  if ((page.viewportSize()?.width ?? 1440) <= 1100) {
    await page.keyboard.press('Escape');
    await expect(hypothesisNode).toBeFocused();
  } else {
    await page.keyboard.press('Escape');
  }
  await hypothesisNode.focus();
  await page.keyboard.press('Space');
  await expect(page.getByRole('heading', { name: '镜像获取失败' })).toBeVisible();
  if ((page.viewportSize()?.width ?? 1440) <= 1100) await page.keyboard.press('Escape');
  await page.locator('.rw-flow-node--observation').first().click();
  await expect(page.locator('.rw-flow-node--knowledge')).toHaveCSS('opacity', '0.24');
  await expect(page.getByText('该节点只提供可追溯知识背景，不会影响支持指数。')).not.toBeVisible();
  if ((page.viewportSize()?.width ?? 1440) <= 1100) await page.keyboard.press('Escape');
  await page.locator('.rw-flow-node--knowledge').click();
  await expect(page.getByText('该节点只提供可追溯知识背景，不会影响支持指数。')).toBeVisible();
  if ((page.viewportSize()?.width ?? 1440) <= 1100) {
    await expect(page.getByRole('dialog', { name: '节点检查器' })).toBeVisible();
    await expect(page.getByRole('button', { name: '关闭节点检查器' }).last()).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog', { name: '节点检查器' })).not.toBeVisible();
  }
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: '导出 PNG' }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toMatch(/reasonweave-graph-.*\.png/);
  const downloadPath = await download.path();
  expect(downloadPath).toBeTruthy();
  const image = await readFile(downloadPath!);
  expect(image.subarray(0, 8)).toEqual(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
  expect(image.readUInt32BE(16)).toBeGreaterThan(320);
  expect(image.readUInt32BE(20)).toBeGreaterThan(240);
});

test('审计详情使用真实记录并可导出 JSONL', async ({ page }) => {
  await page.goto(`/events/${eventId}/audit`);
  await expect(page.getByRole('button', { name: /调查运行已完成/ })).toBeVisible();
  await page.getByRole('button', { name: /已添加文本证据/ }).click();
  await expect(page.getByText('字段变化')).toBeVisible();
  await expect(page.getByText('请求 ID')).toBeVisible();
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: '导出 JSONL' }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toContain('reasonweave-audit');
  const downloadPath = await download.path();
  expect(downloadPath).toBeTruthy();
  const lines = (await readFile(downloadPath!, 'utf8')).trim().split('\n');
  expect(lines).toHaveLength(1);
  expect(JSON.parse(lines[0])).toMatchObject({ id: 'aud_02', action: 'investigation.completed' });
});

test('API 调试台校验 JSON 并确认写操作', async ({ page }) => {
  await page.goto('/developer/api-playground');
  await page.getByRole('button', { name: /创建事件并校验 EventIR/ }).click();
  const editor = page.getByLabel('JSON 请求体');
  expect(JSON.parse(await editor.inputValue())).toHaveProperty('event_ir');
  await expect(page.getByLabel('Idempotency-Key')).not.toHaveValue('');
  await editor.fill('{ invalid');
  await expect(page.getByText(/JSON 格式错误/).first()).toBeVisible();
  await expect(page.getByRole('button', { name: '执行请求' })).toBeDisabled();
  await editor.fill(JSON.stringify({ event_ir: eventIr() }));
  page.once('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: '执行请求' }).click();
  await expect(page.getByText('201 Created')).toBeVisible();
  await expect(page.getByText('req_e2e', { exact: true })).toBeVisible();
});

test('知识源可进入知识单元并跳转引用图谱', async ({ page }) => {
  await page.goto('/knowledge/sources/ks_01');
  await page.getByRole('link', { name: /容器镜像获取/ }).click();
  await expect(page.getByText('中文派生摘要', { exact: true })).toBeVisible();
  await expect(page.locator('.rw-markdown')).toContainText('ImagePullBackOff');
  expect(await page.locator('body').innerText()).not.toContain('image_acquisition_failure');
  await page.getByRole('link', { name: '打开引用图谱' }).click();
  await expect(page).toHaveURL(new RegExp(`/events/${eventId}/graph\\?investigation_id=${run2.id}`));
  await expect(page.getByRole('heading', { name: '因果关系图' })).toBeVisible();
});

function eventDetail(id: string) {
  return { id, reference_code: 'EVT-K8S-001', event_type: 'kubernetes_pod_failure', title: 'default/api Pod 镜像拉取失败', description: 'Pod 处于 ImagePullBackOff', status: 'INVESTIGATING', domain_pack_key: 'kubernetes-pod-diagnostics/1.0.0', occurred_start: '2026-08-20T00:00:00Z', occurred_end: '2026-08-20T05:00:00Z', version: 2, event_ir: eventIr(), created_at: '2026-08-20T00:00:00Z', updated_at: '2026-08-27T03:00:00Z' };
}

function eventSummary(id: string, title: string) {
  return { id, reference_code: id === eventId ? 'EVT-K8S-001' : 'EVT-K8S-002', event_type: 'kubernetes_pod_failure', title, status: 'INVESTIGATING', domain_pack_key: 'kubernetes-pod-diagnostics/1.0.0', evidence_count: 2, top_hypothesis: '镜像获取失败', latest_score: 82, latest_coverage: 0.88, updated_at: '2026-08-27T03:00:00Z' };
}

function graphResult() {
  return {
    event_id: eventId,
    investigation_run_id: run2.id,
    stale: false,
    warnings: [],
    nodes: [
      { id: `event:${eventId}`, entity_id: eventId, type: 'EVENT', label: 'default/api Pod 镜像拉取失败', subtitle: 'EVT-K8S-001', status: 'COMPLETED', metadata: { event_type: 'kubernetes_pod_failure' } },
      { id: 'evidence:ev_1', entity_id: 'ev_1', type: 'EVIDENCE', label: 'Pod 状态', subtitle: '调查时证据快照', metadata: {} },
      { id: 'observation:obs_1', entity_id: 'obs_1', type: 'OBSERVATION', label: 'image_pull_backoff', metadata: { predicate: 'image_pull_backoff' } },
      { id: 'hypothesis:hyp_1', entity_id: 'hyp_1', type: 'HYPOTHESIS', label: 'Image acquisition failure', score: 82, coverage: 0.88, status: 'SUPPORTED', metadata: { code: 'image_acquisition_failure' } },
      { id: 'evidence:ev_2', entity_id: 'ev_2', type: 'EVIDENCE', label: 'Kubernetes Event', subtitle: '调查时证据快照', metadata: {} },
      { id: 'observation:obs_2', entity_id: 'obs_2', type: 'OBSERVATION', label: 'container_config_error', metadata: { predicate: 'container_config_error' } },
      { id: 'hypothesis:hyp_2', entity_id: 'hyp_2', type: 'HYPOTHESIS', label: 'Configuration or mount failure', score: 54, coverage: 0.45, status: 'INCONCLUSIVE', metadata: { code: 'configuration_or_mount_failure' } },
      { id: 'knowledge:ku_01', entity_id: 'ku_01', type: 'KNOWLEDGE', label: '容器镜像获取', subtitle: 'kubernetes-images', metadata: { content_hash: 'b'.repeat(64) } },
    ],
    edges: [
      { id: 'e1', source: 'evidence:ev_1', target: 'observation:obs_1', type: 'OBSERVED_FROM', score_affecting: false, metadata: {} },
      { id: 'e2', source: 'observation:obs_1', target: 'hypothesis:hyp_1', type: 'SUPPORTS', contribution: 0.9, score_affecting: true, metadata: { relation: 'STRONGLY_SUPPORTS' } },
      { id: 'e3', source: 'hypothesis:hyp_1', target: `event:${eventId}`, type: 'EXPLAINS', score_affecting: false, metadata: {} },
      { id: 'e4', source: 'evidence:ev_2', target: 'observation:obs_2', type: 'OBSERVED_FROM', score_affecting: false, metadata: {} },
      { id: 'e5', source: 'observation:obs_2', target: 'hypothesis:hyp_2', type: 'SUPPORTS', contribution: 0.3, score_affecting: true, metadata: {} },
      { id: 'e6', source: 'hypothesis:hyp_2', target: `event:${eventId}`, type: 'EXPLAINS', score_affecting: false, metadata: {} },
      { id: 'e7', source: 'knowledge:ku_01', target: 'hypothesis:hyp_1', type: 'GROUNDED_BY', score_affecting: false, explanation: '只提供知识背景', metadata: {} },
    ],
  };
}

function auditItems() {
  return [
    { id: 'aud_02', actor: { type: 'api', id: 'local_api' }, action: 'investigation.completed', resource: { type: 'investigation_run', id: run2.id }, before_state: {}, after_state: { investigation_run_id: run2.id, status: 'COMPLETED' }, request_id: 'req_e2e', occurred_at: '2026-08-27T04:00:00Z' },
    { id: 'aud_01', actor: { type: 'api', id: 'local_api' }, action: 'evidence.created', resource: { type: 'evidence', id: 'ev_new' }, before_state: {}, after_state: { status: 'NEEDS_REVIEW' }, request_id: 'req_evidence', occurred_at: '2026-08-27T03:00:00Z' },
  ];
}

function knowledgeUnitSummary() {
  return { id: 'ku_01', knowledge_source_id: 'ks_01', document_id: 'kd_01', topic: '容器镜像获取', title: '容器镜像获取', expected_predicates: ['image_pull_backoff'], source_locator: { document_id: 'kubernetes-images', section: '镜像拉取状态' }, source_version: '1.0.0', content_hash: 'b'.repeat(64), status: 'PUBLISHED', embedding_present: true, created_at: '2026-08-27T03:00:00Z' };
}

function knowledgeSourceDetail() {
  return { source: { id: 'ks_01', domain_pack_key: 'kubernetes-pod-diagnostics/1.0.0', name: 'Kubernetes Pod Diagnostics', source_type: 'DOMAIN_PACK', version: '1.0.0', license: 'CC-BY-4.0', status: 'PUBLISHED', fixture_only: false, production_allowed: true, document_count: 1, unit_count: 1, embedding_provider: 'ollama', embedding_model: 'qwen3-embedding:0.6b', embedding_dimension: 1024, embedding_model_digest: 'sha256:test', index_profile_fingerprint: 'f'.repeat(64), created_at: '2026-08-27T03:00:00Z' }, documents: [{ id: 'kd_01', knowledge_source_id: 'ks_01', external_id: 'kubernetes-images', title: '容器镜像获取', content_type: 'text/markdown', checksum_sha256: 'a'.repeat(64), language: 'zh-CN', parse_status: 'PUBLISHED', metadata: {}, unit_count: 1, created_at: '2026-08-27T03:00:00Z' }], published_unit_count: 1, embedding_unit_count: 1, citation_count: 1, retrieval_usage_count: 1, current_index_version: 'kubernetes-pod-diagnostics:index', embedding_provenance: { provider: 'ollama', model: 'qwen3-embedding:0.6b', dimension: 1024, model_digest: 'sha256:test', query_instruction: '检索 Kubernetes Pod 故障知识：', index_profile_fingerprint: 'f'.repeat(64), production_ready: true } };
}

function knowledgeUnitDetail() {
  return { ...knowledgeUnitSummary(), source: knowledgeSourceDetail().source, document: knowledgeSourceDetail().documents[0], domain_pack_key: 'kubernetes-pod-diagnostics/1.0.0', content: '# 容器镜像获取\n\n`ImagePullBackOff` 是拉取失败后的退避状态，应结合具体原因定位。', applicability: {}, embedding_provenance: knowledgeSourceDetail().embedding_provenance, citation_usages: [{ citation_id: 'cit_01', investigation_run_id: run2.id, event_id: eventId, target_type: 'HYPOTHESIS', target_id: 'hyp_1', target_code: 'image_acquisition_failure', target_title: 'Image acquisition failure', source_locator: { section: '镜像拉取状态' }, source_version: '1.0.0', content_hash: 'b'.repeat(64), usage_reason: '为假设 image_acquisition_failure 提供可回溯知识背景；不产生评分贡献', created_at: '2026-08-27T03:00:00Z' }], citation_usage_count: 1, retrieval_usages: [{ retrieval_run_id: 'ret_02', investigation_run_id: run2.id, query_intent: 'CAUSE_CANDIDATES', keyword_rank: 1, vector_rank: 1, fusion_rank: 1, fusion_score: 0.032, selected: true, selection_reason: 'RRF_TOP_K_AND_SOURCE_DIVERSITY', index_version: 'kubernetes-pod-diagnostics:index', embedding_model: 'qwen3-embedding:0.6b', created_at: '2026-08-27T03:00:00Z' }], retrieval_usage_count: 1 };
}

function domainPackSummary() {
  return {
    key: 'kubernetes-pod-diagnostics', version: '1.0.0', name: 'Kubernetes Pod 故障诊断',
    description: '面向 Pod 调度、镜像、配置、启动与健康故障的证据化诊断包。', status: 'PUBLISHED',
    fixture_only: false, production_allowed: true, compatible_eventir: '0.1', hypothesis_count: 4,
    rule_count: 15, document_count: 5, unit_count: 12, knowledge_source_id: 'ks_01',
    knowledge_index_version: 'kubernetes-pod-diagnostics:index', presentation_locale: 'zh-CN',
    fingerprint: 'f'.repeat(64), event_types: ['kubernetes_pod_failure'], vector_policy: 'required',
    observation_bundle: { schema_version: 'observation-bundle/1.0' },
    supported_target_versions: { scheme: 'semver', minimum: '1.35.0', maximum_exclusive: '1.38.0' },
    source_profiles: { kubernetes_api: { reliability: 0.95 }, kubernetes_event: { reliability: 0.65 } },
    licenses: { components: [{ scope: 'knowledge-summaries', license: 'CC-BY-4.0', source: 'https://kubernetes.io/docs/', revision: 'test-revision', modified: true }] },
    ready: true, readiness_reasons: [],
  };
}

function domainPackDetail() {
  return {
    summary: domainPackSummary(),
    manifest: { capabilities: { observation_bundle: true, knowledge_retrieval: true, external_collector: true, image_vision: false, automatic_remediation: false } },
    event_definitions: [kubernetesEventTypeDefinition()],
    vocabulary: { predicates: { image_pull_backoff: { label: '镜像拉取退避', value_schema: { type: 'boolean' } } } },
    presentation: {
      locale: 'zh-CN',
      name: 'Kubernetes Pod 故障诊断',
      event_types: {
        kubernetes_pod_failure: {
          label: 'Kubernetes Pod 故障', subject_type: 'kubernetes_pod', subject_label: 'Pod（namespace/name）',
        },
      },
      hypotheses: {
        scheduling_constraint: { title: '调度约束或资源不足', description: 'Pod 无法满足调度条件或缺少资源。' },
        image_acquisition_failure: { title: '镜像获取失败', description: '镜像拉取过程发生错误。' },
        configuration_or_mount_failure: { title: '配置依赖或卷挂载失败', description: '配置引用或卷挂载尚未满足。' },
        runtime_or_health_failure: { title: '容器运行时或健康检查失败', description: '容器退出、OOM 或探针失败。' },
      },
      predicates: {
        image_pull_backoff: '镜像拉取退避',
        container_config_error: '容器配置创建失败',
      },
      source_profiles: {
        kubernetes_api: 'Kubernetes API Pod 状态',
        kubernetes_event: 'Kubernetes Event（best-effort 补充信号）',
      },
    },
    hypotheses: { hypotheses: [{ code: 'image_acquisition_failure', title: 'Image acquisition failure' }] },
    rules: { rules: [{ id: 'image.pull.backoff', hypothesis: 'image_acquisition_failure', predicate: 'image_pull_backoff' }] },
    next_evidence: { recommendations: [] }, retrieval_config: { keyword_top_k: 20, vector_top_k: 20, final_top_k: 6, fusion: { k: 60 } },
    knowledge_metadata: {}, warnings: [],
  };
}

function kubernetesEventTypeDefinition() {
  return {
    domain_pack: 'kubernetes-pod-diagnostics/1.0.0',
    event_type: 'kubernetes_pod_failure',
    subject_type: 'kubernetes_pod',
    identity_fields: ['namespace', 'pod_name'],
    label_template: '{namespace}/{pod_name}',
    attributes_schema: {
      type: 'object',
      required: ['namespace', 'pod_name'],
      properties: {
        namespace: { type: 'string', minLength: 1, maxLength: 63 },
        pod_name: { type: 'string', minLength: 1, maxLength: 253 },
      },
      additionalProperties: false,
    },
    event_requirements: { time_range: 'optional' },
    evidence_inputs: [
      { type: 'observation_bundle', enabled: true, requires_human_confirmation: true, content_types: [], label: '上传 Observation Bundle', help: '导入只读采集器输出。' },
      { type: 'text', enabled: true, source_profile: 'human_report', source_reliability: 0.75, predicate: 'reported_symptom_text', verification_status: 'CONFIRMED', requires_human_confirmation: false, content_types: [], label: '添加人工报告' },
      { type: 'file', enabled: true, source_profile: 'uploaded_file', source_reliability: 0.8, requires_human_confirmation: true, content_types: ['text/plain', 'application/json'], label: '上传诊断文件' },
      { type: 'image', enabled: false, requires_human_confirmation: true, content_types: [] },
    ],
    target_versions: { scheme: 'semver', minimum: '1.35.0', maximum_exclusive: '1.38.0' },
    presentation: {
      label: 'Kubernetes Pod 故障',
      subject_label: 'Pod（namespace/name）',
      fields: [
        { name: 'namespace', label: '命名空间', control: 'text', placeholder: 'default', required: true, options: [] },
        { name: 'pod_name', label: 'Pod 名称', control: 'text', placeholder: 'api-7d8f4c9b6-x2k9p', required: true, options: [] },
      ],
    },
  };
}

function coldDomainPackSummary() {
  return {
    ...domainPackSummary(),
    key: 'cold-holding-excursion-diagnostics',
    name: '冷藏温度异常诊断',
    description: '面向冷藏单元的供电、设备响应、运行热负荷和测量系统异常调查。',
    event_types: ['cold_holding_temperature_excursion'],
    knowledge_source_id: 'ks_cold',
    knowledge_index_version: 'cold-holding-excursion-diagnostics:index',
    supported_target_versions: undefined,
    licenses: { components: [{ scope: 'knowledge-summaries', license: 'LicenseRef-US-Public-Domain', source: 'https://www.fda.gov/', revision: '2026-08-29', modified: true }] },
  };
}

function coldEventTypeDefinition() {
  return {
    domain_pack: 'cold-holding-excursion-diagnostics/1.0.0',
    event_type: 'cold_holding_temperature_excursion',
    subject_type: 'cold_holding_unit',
    identity_fields: ['site_id', 'unit_id'],
    label_template: '{site_id}/{unit_id}',
    attributes_schema: {
      type: 'object',
      required: ['site_id', 'unit_id', 'unit_type', 'temperature_limit_c', 'minimum_excursion_minutes', 'maximum_sample_gap_minutes', 'sensor_tolerance_c', 'policy_reference'],
      properties: {
        site_id: { type: 'string', minLength: 1 },
        unit_id: { type: 'string', minLength: 1 },
        unit_type: { type: 'string', enum: ['walk_in_cooler', 'commercial_refrigerator', 'display_case', 'cold_room', 'other'] },
        temperature_limit_c: { type: 'number', minimum: -50, maximum: 20 },
        minimum_excursion_minutes: { type: 'integer', minimum: 1, maximum: 1440 },
        maximum_sample_gap_minutes: { type: 'integer', minimum: 1, maximum: 1440 },
        sensor_tolerance_c: { type: 'number', exclusiveMinimum: 0, maximum: 10 },
        policy_reference: { type: 'string', minLength: 1 },
      },
      additionalProperties: false,
    },
    event_requirements: { time_range: 'required' },
    evidence_inputs: [
      { type: 'observation_bundle', enabled: true, requires_human_confirmation: true, content_types: [], label: '上传冷藏 Observation Bundle', help: '导入本地采集器输出。', collector_command: 'rw-evidence cold-holding collect --event-ir event-ir.json --sources sources.json --telemetry telemetry.csv --out cold-holding-bundle.json' },
      { type: 'text', enabled: true, source_profile: 'operator_report', source_reliability: 0.65, predicate: 'reported_cold_holding_symptom', verification_status: 'PENDING', requires_human_confirmation: true, content_types: [], label: '添加现场现象说明' },
      { type: 'image', enabled: false, requires_human_confirmation: true, content_types: [] },
    ],
    presentation: {
      label: '冷藏温度异常',
      subject_label: '冷藏单元（场所/单元）',
      fields: [
        { name: 'site_id', label: '场所编号', control: 'text', placeholder: 'store-001', required: true, options: [] },
        { name: 'unit_id', label: '冷藏单元编号', control: 'text', placeholder: 'walk-in-cooler-a', required: true, options: [] },
        { name: 'unit_type', label: '冷藏单元类型', control: 'select', required: true, options: [{ value: 'walk_in_cooler', label: '步入式冷藏间' }, { value: 'commercial_refrigerator', label: '商用冰箱' }, { value: 'display_case', label: '冷藏展示柜' }, { value: 'cold_room', label: '冷库' }, { value: 'other', label: '其他' }] },
        { name: 'temperature_limit_c', label: '现场温度上限（°C）', control: 'number', placeholder: '5', required: true, options: [] },
        { name: 'minimum_excursion_minutes', label: '最短异常持续时间（分钟）', control: 'number', placeholder: '15', required: true, options: [] },
        { name: 'maximum_sample_gap_minutes', label: '最大允许采样缺口（分钟）', control: 'number', placeholder: '10', required: true, options: [] },
        { name: 'sensor_tolerance_c', label: '传感器允许差异（°C）', control: 'number', placeholder: '1', required: true, options: [] },
        { name: 'policy_reference', label: '现场阈值依据', control: 'text', placeholder: 'Site SOP CH-04', required: true, options: [] },
      ],
    },
  };
}

function coldDomainPackDetail() {
  const summary = coldDomainPackSummary();
  return {
    summary,
    manifest: { capabilities: { observation_bundle: true, knowledge_retrieval: true, external_collector: true, image_vision: false, automatic_remediation: false } },
    event_definitions: [coldEventTypeDefinition()],
    vocabulary: { predicates: { operational_heat_load_detected: { label: '已检测到运行热负荷', value_schema: { type: 'boolean' } } } },
    presentation: {
      locale: 'zh-CN', name: summary.name,
      event_types: { cold_holding_temperature_excursion: coldEventTypeDefinition().presentation },
      hypotheses: { operational_heat_load_or_airflow: { title: '开门、装载或气流扰动', description: '运行操作形成额外热负荷。' } },
      predicates: { operational_heat_load_detected: '已检测到运行热负荷' },
      source_profiles: { collector_derived: '本地采集器派生事实', operator_report: '操作人员报告' },
    },
    hypotheses: { hypotheses: [{ code: 'operational_heat_load_or_airflow', title: 'Operational heat load' }] },
    rules: { rules: [{ id: 'cold-holding.operations.detected', hypothesis: 'operational_heat_load_or_airflow', predicate: 'operational_heat_load_detected' }] },
    next_evidence: { recommendations: [] },
    retrieval_config: { keyword_top_k: 20, vector_top_k: 20, final_top_k: 6, fusion: { k: 60 } },
    knowledge_metadata: {}, warnings: [],
  };
}

function equipmentDomainPackSummary() {
  return {
    ...domainPackSummary(),
    key: 'equipment-fault-test',
    version: '1.0.0',
    name: '设备故障测试包',
    description: '用于验证领域无关动态表单。',
    fixture_only: false,
    production_allowed: true,
    event_types: ['equipment_fault'],
    vector_policy: 'optional',
    supported_target_versions: undefined,
  };
}

function equipmentEventTypeDefinition() {
  return {
    domain_pack: 'equipment-fault-test/1.0.0',
    event_type: 'equipment_fault',
    subject_type: 'equipment_asset',
    identity_fields: ['asset_id'],
    label_template: '{asset_id}',
    attributes_schema: {
      type: 'object',
      required: ['asset_id'],
      properties: {
        asset_id: { type: 'string', minLength: 1, maxLength: 80 },
        site: { type: 'string', maxLength: 120 },
      },
      additionalProperties: false,
    },
    event_requirements: { time_range: 'optional' },
    evidence_inputs: [
      { type: 'observation_bundle', enabled: true, requires_human_confirmation: true, content_types: [], label: '上传 Observation Bundle' },
      { type: 'text', enabled: true, source_profile: 'human_report', source_reliability: 0.7, predicate: 'reported_issue', verification_status: 'PENDING', requires_human_confirmation: true, content_types: [], label: '添加设备现象' },
      { type: 'file', enabled: true, source_profile: 'uploaded_file', source_reliability: 0.8, requires_human_confirmation: true, content_types: ['text/plain', 'application/json'], label: '上传设备记录' },
      { type: 'image', enabled: false, requires_human_confirmation: true, content_types: [] },
    ],
    presentation: {
      label: '设备故障',
      subject_label: '设备资产',
      fields: [
        { name: 'asset_id', label: '资产编号', control: 'text', placeholder: 'pump-001', required: true, options: [] },
        { name: 'site', label: '所在站点', control: 'text', placeholder: 'workshop-a', required: false, options: [] },
      ],
    },
  };
}

function equipmentDomainPackDetail() {
  const summary = equipmentDomainPackSummary();
  return {
    summary,
    manifest: { capabilities: { observation_bundle: true, knowledge_retrieval: true } },
    event_definitions: [equipmentEventTypeDefinition()],
    vocabulary: { predicates: {} },
    presentation: { locale: 'zh-CN', name: summary.name, event_types: { equipment_fault: equipmentEventTypeDefinition().presentation } },
    hypotheses: { hypotheses: [] },
    rules: { rules: [] },
    next_evidence: { recommendations: [] },
    retrieval_config: {},
    knowledge_metadata: {},
    warnings: [],
  };
}

function observationBundleFixture() {
  return {
    schema_version: 'observation-bundle/1.0', domain_pack: 'kubernetes-pod-diagnostics/1.0.0',
    event_type: 'kubernetes_pod_failure', target_version: 'v1.37.0',
    subject: { type: 'kubernetes_pod', label: 'default/api', attributes: { namespace: 'default', pod_name: 'api' } },
    evidence_items: [{
      external_id: 'pod-status:uid:test', source_type: 'kubernetes_api', captured_at: '2026-08-28T00:00:00Z',
      observations: [{ predicate: 'image_pull_backoff', value: true, confidence: 1, source_locator: { kind: 'Pod', field: 'status.containerStatuses' } }],
    }],
  };
}

function openApiFixture() {
  return { openapi: '3.0.1', paths: { '/api/v1/events': { get: { tags: ['事件'], summary: '列出事件', responses: { 200: { description: '成功' } } }, post: { tags: ['事件'], summary: '创建事件并校验 EventIR', parameters: [{ name: 'Idempotency-Key', in: 'header', required: true, schema: { type: 'string' } }], requestBody: { required: true, content: { 'application/json': { schema: { type: 'object', properties: { event_ir: { type: 'object' } } } } } }, responses: { 201: { description: '已创建' } } } }, '/api/v1/events/{id}': { get: { tags: ['事件'], summary: '读取事件详情', parameters: [{ name: 'id', in: 'path', required: true, schema: { type: 'string' } }], responses: { 200: { description: '成功' } } } } }, components: { schemas: {} } };
}

function evidenceDetail(confirmed: boolean) {
  return { evidence: { id: evidenceId, event_id: eventId, type: 'OBSERVATION_BUNDLE', source: 'kubernetes_api', status: confirmed ? 'VERIFIED' : 'NEEDS_REVIEW', original_name: 'pod-status.json', content_type: 'application/vnd.reasonweave.observation-bundle+json', checksum_sha256: 'a'.repeat(64), generation: 0, reliability: 0.95, observation_count: 1, created_at: '2026-08-20T05:10:00Z' }, metadata: { domain_pack: 'kubernetes-pod-diagnostics/1.0.0' }, observations: [{ id: 'obs_01', predicate: 'image_pull_backoff', value: true, description: '容器等待原因为 ImagePullBackOff', model_confidence: 1, verification_status: confirmed ? 'CONFIRMED' : 'PENDING', provenance: { adapter: 'observation-bundle/1.0' }, generation: 0, version: confirmed ? 1 : 0, created_at: '2026-08-20T05:10:00Z', updated_at: '2026-08-20T05:10:00Z' }] };
}

function retrievalResult() {
  return { id: 'ret_debug', index_version: 'kubernetes-pod-diagnostics/1.0.0:index', embedding_provider: 'ollama', embedding_model: 'qwen3-embedding:0.6b', embedding_model_digest: 'sha256:test', index_profile_fingerprint: 'f'.repeat(64), status: 'COMPLETED', query_plan: {}, retrieval_config: {}, context_hash: 'hash_context', created_at: '2026-08-27T03:00:00Z', intents: [{ type: 'CAUSE_CANDIDATES', query: 'ImagePullBackOff 镜像拉取失败', hits: [{ knowledge_unit_id: 'ku_01', document_id: 'kd_01', source_id: 'ks_01', title: '容器镜像获取', content: 'ImagePullBackOff 表示镜像拉取失败后的退避状态。', keyword_rank: 1, keyword_score: 0.9, vector_rank: 1, vector_score: 0.88, fusion_rank: 1, fusion_score: 0.032, applicability_score: 1.25, applicability_reason: 'EVENT_AND_CONTEXT_MATCH', expected_predicates: ['image_pull_backoff', 'image_pull_error'], selected: true, selection_reason: 'RRF_TOP_K_AND_SOURCE_DIVERSITY', source_locator: { section: '镜像拉取状态' }, source_version: '1.0.0', content_hash: 'b'.repeat(64) }] }] };
}

async function json(route: Route, body: unknown, status = 200) {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}
