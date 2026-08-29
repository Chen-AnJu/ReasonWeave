import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, ArrowLeft, Play, Scale } from 'lucide-react';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { queries } from '../api/queries';
import { reasonweaveApi } from '../api/reasonweave';
import type { InvestigationRun, NextEvidence, RunDiff } from '../api/types';
import { Button, Card, EmptyState, ErrorState, LoadingState, Mono, PageHeader, Progress, StatusTag, Tag, TechnicalDetails } from '../components/ui';
import { EventTabs } from '../components/EventTabs';
import { useDomainLabels } from '../shared/domainPresentationContext';
import { formatDate, formatPercent, truncateHash } from '../shared/format';
import { groundingStatusLabel, levelLabel, pipelineStepLabel, relationLabel, statusLabel } from '../shared/presentation';

export function InvestigationPage({ mode = 'workbench' }: { mode?: 'workbench' | 'compare' | 'next' }) {
  const { eventId = '' } = useParams();
  const queryClient = useQueryClient();
  const event = useQuery(queries.event(eventId));
  const runs = useInfiniteQuery(queries.investigations(eventId));
  const [selectedRunId, setSelectedRunId] = useState<string>();
  const runItems = runs.data?.pages.flatMap((page) => page.items) ?? [];
  const activeRun = runItems.find((run) => run.id === selectedRunId) ?? runItems[0];
  const nextEvidence = useQuery({ queryKey: ['next-evidence', activeRun?.id], queryFn: () => reasonweaveApi.nextEvidence(activeRun!.id), enabled: Boolean(activeRun) });
  const diff = useQuery({ queryKey: ['run-diff', activeRun?.id], queryFn: () => reasonweaveApi.runDiff(activeRun!.id), enabled: Boolean(activeRun && runItems.length > 1) });
  const start = useMutation({
    mutationFn: () => reasonweaveApi.startInvestigation(eventId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['investigations', eventId] });
      await queryClient.invalidateQueries({ queryKey: ['event-view', eventId] });
    },
  });
  if (event.isPending || runs.isPending) return <LoadingState label="正在打开调查工作台" />;
  if (event.isError || runs.isError) return <ErrorState error={event.error ?? runs.error} onRetry={() => { event.refetch(); runs.refetch(); }} />;
  const pageTitle = mode === 'compare' ? '假设对比' : mode === 'next' ? '下一步取证' : '调查工作台';
  return (
    <div className="rw-stack">
      <PageHeader eyebrow={<span><Mono>{event.data.reference_code}</Mono> · 调查运行 {activeRun?.sequence_no ?? '—'}</span>} title={pageTitle} description={event.data.title} actions={<><Link className="rw-button rw-button--ghost" to={`/events/${eventId}`}><ArrowLeft size={15} />事件概览</Link><Button variant="secondary" onClick={() => start.mutate()} disabled={start.isPending}><Play size={15} />创建新调查运行</Button></>} />
      {activeRun?.stale && <div className="rw-callout rw-callout--warning"><AlertTriangle size={17} /><p>此调查运行已过期，但历史快照不会被更新。创建新调查运行后可比较变化。</p></div>}
      {start.isError && <div className="rw-callout rw-callout--danger"><p>{start.error.message}</p></div>}
      <EventTabs eventId={eventId} />
      <div className="rw-run-selector"><label htmlFor="investigation-run">调查运行</label><select id="investigation-run" value={activeRun?.id ?? ''} onChange={(event) => setSelectedRunId(event.target.value)}>{runItems.map((run) => <option key={run.id} value={run.id}>第 {run.sequence_no} 轮 · {formatDate(run.completed_at)} · {statusLabel(run.status)}</option>)}</select>{runs.hasNextPage && <Button variant="ghost" onClick={() => runs.fetchNextPage()} disabled={runs.isFetchingNextPage}>{runs.isFetchingNextPage ? '正在加载' : '加载更早运行'}</Button>}</div>
      {!activeRun ? <Card><EmptyState title="还没有调查运行" description="开始调查后，结果会作为只追加快照保存。" action={<Button onClick={() => start.mutate()}><Play size={15} />开始调查</Button>} /></Card> : activeRun.status === 'FAILED' ? <Card><div className="rw-state rw-state--error"><AlertTriangle /><strong>调查运行失败</strong><p>{activeRun.error_message}</p></div></Card> : mode === 'compare' ? <CompareView current={activeRun} runs={runItems} diff={diff.data} /> : mode === 'next' ? <NextEvidenceView run={activeRun} items={nextEvidence.data ?? []} /> : <WorkbenchView run={activeRun} />}
    </div>
  );
}

function WorkbenchView({ run }: { run: InvestigationRun }) {
  const { domainPackLabel, hypothesisDescription, hypothesisLabel, predicateLabel } = useDomainLabels();
  if (!run.result) return <Card><LoadingState label="调查结果正在写入" /></Card>;
  const result = run.result;
  const hypotheses = result.hypotheses;
  const scopedKey = `${run.domain_pack_key}/${run.domain_pack_version}`;
  const hasRequiredCauseEvidence = hypotheses.some((hypothesis) => hypothesis.expected_evidence
    .filter((expected) => expected.required)
    .some((expected) => hypothesis.contributions.some((contribution) => contribution.predicate === expected.predicate)));
  return <>
    {!hasRequiredCauseEvidence && <div className="rw-callout rw-callout--warning"><AlertTriangle size={17} /><p>当前没有任何原因假设取得领域包要求的必需强证据，结果为证据不足；以下内容仅是待核查的候选原因，不构成归因。</p></div>}
    <div className="rw-investigation-bar"><div><span>证据快照</span><strong>{result.evidence_snapshot.evidence_ids.length} 项证据</strong></div><div><span>知识索引</span><strong>调查时版本已冻结</strong></div><div><span>领域规则</span><strong>{domainPackLabel(scopedKey)} {run.domain_pack_version}</strong></div><div><StatusTag status={run.status} /></div><TechnicalDetails summary="查看快照标识"><div className="rw-definition-list"><div><span>证据快照 Hash</span><Mono>{truncateHash(run.evidence_snapshot_hash, 18)}</Mono></div><div><span>知识索引版本</span><Mono>{run.knowledge_index_version}</Mono></div><div><span>领域包指纹</span><Mono>{truncateHash(run.domain_pack_fingerprint, 18)}</Mono></div><div><span>规则包版本</span><Mono>{run.rule_pack_version}</Mono></div></div></TechnicalDetails></div>
    <div className="rw-grid rw-grid--sidebar">
      <Card title={hasRequiredCauseEvidence ? '有依据的原因假设' : '证据不足：待核查原因'} eyebrow="最多 4 个">
        <div className="rw-hypothesis-cards">{hypotheses.map((hypothesis, index) => <article className="rw-hypothesis-card" key={hypothesis.id}>
          <div className="rw-hypothesis-card__heading"><div className="rw-rank">H{index + 1}</div><div><h3>{hypothesisLabel(hypothesis.code, hypothesis.title, scopedKey)}</h3></div><div className="rw-score"><strong>{hypothesis.score}</strong><span>/100</span></div></div>
          <p>{hypothesisDescription(hypothesis.code, hypothesis.description, scopedKey)}</p>
          <div className="rw-cluster"><StatusTag status={hypothesis.band} /><Tag tone="info">覆盖率 {formatPercent(hypothesis.coverage)}</Tag><Tag tone={hypothesis.grounding_status === 'GROUNDED' ? 'knowledge' : 'warning'}>{groundingStatusLabel(hypothesis.grounding_status)} · {hypothesis.citation_ids.length} 条引用</Tag></div>
          {hypothesis.knowledge_limitations.map((limitation) => <div className="rw-callout rw-callout--warning" key={limitation}><AlertTriangle size={16} /><p>{limitation}</p></div>)}
          <Progress value={hypothesis.coverage} tone="hypothesis" label={`${hypothesisLabel(hypothesis.code, hypothesis.title, scopedKey)} 的证据覆盖率`} />
          <div className="rw-contribution-list">{hypothesis.contributions.length === 0 ? <span className="rw-muted">尚无可用现实观察；缺失证据不会自动视为反驳。</span> : hypothesis.contributions.map((item, itemIndex) => <div key={`${item.rule_id}-${itemIndex}`}><span className={Number(item.value) >= 0 ? 'is-positive' : 'is-negative'}>{Number(item.value) >= 0 ? '+' : ''}{Number(item.value).toFixed(3)}</span><div><strong>{predicateLabel(item.predicate, scopedKey)}</strong><small>{relationLabel(item.relation)}</small><TechnicalDetails summary="技术原值"><Mono>{item.predicate} · {item.relation} · {item.rule_id}</Mono></TechnicalDetails></div></div>)}</div>
          <TechnicalDetails summary="假设技术详情"><div className="rw-definition-list"><div><span>假设代码</span><Mono>{hypothesis.code}</Mono></div><div><span>依据状态原值</span><Mono>{hypothesis.grounding_status}</Mono></div><div><span>原始标题</span><span>{hypothesis.title}</span></div><div><span>原始说明</span><span>{hypothesis.description}</span></div></div></TechnicalDetails>
        </article>)}</div>
      </Card>
      <div className="rw-stack"><Card title="评分边界" eyebrow="确定性规则"><div className="rw-card__body rw-stack"><div className="rw-callout"><Scale size={17} /><p>支持指数用于规则化比较，不是发生概率。</p></div><TechnicalDetails summary="查看评分公式"><div className="rw-formula"><code>contribution = weight × relation × reliability × confidence × relevance</code><code>score = 50 + 50 × normalized</code><Mono>{result.support_index_disclaimer}</Mono></div></TechnicalDetails></div></Card><Card title="固定流水线" eyebrow="可重放"><ol className="rw-pipeline">{result.pipeline.split(' -> ').map((step, index) => <li key={step}><span>{index + 1}</span>{pipelineStepLabel(step)}</li>)}</ol></Card></div>
    </div>
  </>;
}

function CompareView({ current, runs, diff }: { current: InvestigationRun; runs: InvestigationRun[]; diff?: RunDiff }) {
  const { hypothesisLabel } = useDomainLabels();
  if (runs.length < 2) return <Card><EmptyState title="需要至少两个调查运行" description="新增证据后创建新调查运行，才能比较支持指数、覆盖率和快照变化。" /></Card>;
  const scopedKey = `${current.domain_pack_key}/${current.domain_pack_version}`;
  return <div className="rw-stack"><div className="rw-grid rw-grid--2"><Card title={`调查运行 ${runs[1].sequence_no}`} eyebrow="基准快照"><SnapshotSummary run={runs[1]} /></Card><Card title={`调查运行 ${current.sequence_no}`} eyebrow="当前快照"><SnapshotSummary run={current} /></Card></div><Card title="变化明细" eyebrow="不可变运行差异">{!diff ? <LoadingState label="正在计算运行差异" /> : <div className="rw-table-wrap"><table className="rw-table"><thead><tr><th>假设</th><th>前一支持指数</th><th>当前支持指数</th><th>变化</th><th>前一覆盖率</th><th>当前覆盖率</th></tr></thead><tbody>{diff.hypothesis_changes.map((change) => <tr key={change.code}><td><strong>{hypothesisLabel(change.code, change.title, scopedKey)}</strong><br /><TechnicalDetails summary="假设技术详情"><div className="rw-definition-list"><div><span>假设代码</span><Mono>{change.code}</Mono></div><div><span>原始标题</span><span>{change.title}</span></div></div></TechnicalDetails></td><td>{change.before_score ?? '—'}</td><td>{change.after_score ?? '—'}</td><td className={Number(change.score_delta) >= 0 ? 'rw-positive' : 'rw-negative'}>{change.score_delta == null ? '—' : `${change.score_delta >= 0 ? '+' : ''}${change.score_delta}`}</td><td>{formatPercent(change.before_coverage)}</td><td>{formatPercent(change.after_coverage)}</td></tr>)}</tbody></table></div>}</Card></div>;
}

function SnapshotSummary({ run }: { run: InvestigationRun }) {
  return <div className="rw-card__body rw-stack"><div className="rw-definition-list"><div><span>事件版本</span><strong>v{run.event_version}</strong></div><div><span>完成时间</span><strong>{formatDate(run.completed_at)}</strong></div></div><TechnicalDetails summary="快照技术标识"><div className="rw-definition-list"><div><span>证据快照 Hash</span><Mono>{truncateHash(run.evidence_snapshot_hash, 18)}</Mono></div><div><span>知识索引版本</span><Mono>{run.knowledge_index_version}</Mono></div></div></TechnicalDetails></div>;
}

function NextEvidenceView({ run, items }: { run: InvestigationRun; items: NextEvidence[] }) {
  const { hypothesisLabel } = useDomainLabels();
  const scopedKey = `${run.domain_pack_key}/${run.domain_pack_version}`;
  return <div className="rw-grid rw-grid--sidebar"><Card title="优先取证建议" eyebrow={`调查运行 ${run.sequence_no}`}>{items.length === 0 ? <EmptyState title="没有开放缺口" description="当前领域规则没有生成额外取证建议。" /> : <div className="rw-gap-list">{items.map((item, index) => <article key={item.id}><div className="rw-gap-rank">{index + 1}</div><div><div className="rw-cluster"><h3>{item.title}</h3><Tag tone="warning">优先级 {Number(item.priority_score).toFixed(2)}</Tag></div><p>{item.reason}</p><div className="rw-cluster"><Tag tone="info">影响 {levelLabel(item.estimated_impact)}</Tag><Tag>成本 {levelLabel(item.acquisition_cost)}</Tag><Tag tone="hypothesis">区分 {item.discriminates.map((code) => hypothesisLabel(code, undefined, scopedKey)).join(' / ')}</Tag></div><TechnicalDetails summary="技术详情"><div className="rw-definition-list"><div><span>区分假设代码</span><Mono>{item.discriminates.join(' / ')}</Mono></div><div><span>影响原值</span><Mono>{item.estimated_impact}</Mono></div><div><span>成本原值</span><Mono>{item.acquisition_cost}</Mono></div><div><span>缺口 ID</span><Mono>{item.id}</Mono></div></div></TechnicalDetails></div></article>)}</div>}</Card><Card title="规划原则" eyebrow="证据缺口"><div className="rw-card__body rw-stack"><p className="rw-secondary">建议会优先区分排名前两位假设，而不是泛化地要求“提供更多材料”。</p><TechnicalDetails summary="查看优先级公式"><div className="rw-formula"><code>priority = unresolved × discriminative × impact × availability / cost</code></div></TechnicalDetails><Tag tone="knowledge">知识建议必须带可追溯引用</Tag></div></Card></div>;
}
