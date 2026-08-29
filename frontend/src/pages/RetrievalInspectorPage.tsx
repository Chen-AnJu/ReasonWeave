import { useMutation, useQuery } from '@tanstack/react-query';
import { Braces, Search, ShieldCheck } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { queries } from '../api/queries';
import { reasonweaveApi } from '../api/reasonweave';
import type { RetrievalRun } from '../api/types';
import { Button, Card, EmptyState, ErrorState, Field, Input, LoadingState, Mono, PageHeader, Select, StatusTag, Tag, TechnicalDetails } from '../components/ui';
import { useDomainLabels } from '../shared/domainPresentationContext';
import { truncateHash } from '../shared/format';
import { applicabilityReasonLabel, retrievalIntentLabel } from '../shared/presentation';

export function RetrievalInspectorPage() {
  const { eventTypeLabel, predicateLabel } = useDomainLabels();
  const [query, setQuery] = useState('');
  const [intent, setIntent] = useState('CAUSE_CANDIDATES');
  const [domainPackKey, setDomainPackKey] = useState('');
  const [eventType, setEventType] = useState('');
  const [observedPredicates, setObservedPredicates] = useState('');
  const packs = useQuery(queries.domainPacks());
  const availablePacks = useMemo(
    () => packs.data?.filter((pack) => pack.production_allowed && !pack.fixture_only) ?? [],
    [packs.data],
  );
  const selectedPack = availablePacks.find((pack) => `${pack.key}/${pack.version}` === domainPackKey);
  useEffect(() => {
    if (!domainPackKey && availablePacks[0]) setDomainPackKey(`${availablePacks[0].key}/${availablePacks[0].version}`);
  }, [availablePacks, domainPackKey]);
  useEffect(() => {
    if (selectedPack && !selectedPack.event_types.includes(eventType)) setEventType(selectedPack.event_types[0] ?? '');
  }, [eventType, selectedPack]);
  const retrieval = useMutation({
    mutationFn: () => reasonweaveApi.debugRetrieval(
      query,
      eventType,
      domainPackKey,
      intent,
      observedPredicates.split(',').map((value) => value.trim()).filter(Boolean),
    ),
  });
  const result: RetrievalRun | undefined = retrieval.data;
  if (packs.isPending) return <LoadingState label="正在读取领域包检索配置" />;
  if (packs.isError) return <ErrorState error={packs.error} onRetry={() => packs.refetch()} />;
  if (availablePacks.length === 0) return <Card><EmptyState title="没有生产领域包" description="检索调试需要一个已安装且允许正式使用的领域包。" /></Card>;
  return (
    <div className="rw-stack">
      <PageHeader eyebrow="混合检索" title="检索检查器" description="逐项检查 FTS 前 20、Qwen3 1024 维向量前 20、RRF k=60 与最终前 6。融合分数只用于排序。" actions={<Tag tone="success"><ShieldCheck size={13} />评分隔离已启用</Tag>} />
      <Card title="查询计划" eyebrow="调试输入">
        <div className="rw-card__body rw-retrieval-form">
          <Field label="领域包"><Select value={domainPackKey} onChange={(event) => setDomainPackKey(event.target.value)}>{availablePacks.map((pack) => <option key={`${pack.key}/${pack.version}`} value={`${pack.key}/${pack.version}`}>{pack.name} · {pack.version}</option>)}</Select></Field>
          <Field label="事件类型"><Select value={eventType} onChange={(event) => setEventType(event.target.value)}>{(selectedPack?.event_types ?? []).map((type) => <option key={type} value={type}>{eventTypeLabel(type, domainPackKey)}</option>)}</Select></Field>
          <Field label="查询"><Input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="输入原因、预期证据或调查动作" /></Field>
          <Field label="意图"><Select value={intent} onChange={(event) => setIntent(event.target.value)}><option value="CAUSE_CANDIDATES">原因候选</option><option value="EXPECTED_EVIDENCE">预期证据</option><option value="INVESTIGATION_ACTIONS">调查动作</option></Select></Field>
          <Field label="已观察 Predicate" hint="可选；多个技术代码用英文逗号分隔"><Input value={observedPredicates} onChange={(event) => setObservedPredicates(event.target.value)} placeholder="predicate_a,predicate_b" /></Field>
          <Button onClick={() => retrieval.mutate()} disabled={!query.trim() || !domainPackKey || !eventType || retrieval.isPending}><Search size={15} />{retrieval.isPending ? '正在检索…' : '运行检索'}</Button>
        </div>
      </Card>
      {retrieval.isError && <div className="rw-callout rw-callout--danger"><p>{retrieval.error.message}</p></div>}
      {!result ? <Card><EmptyState title="等待检索" description="运行一个随包基准查询，检查每一路排名与来源定位。" /></Card> : <>
        <div className="rw-grid rw-grid--2">
          <Card title="检索快照" eyebrow="只追加"><div className="rw-card__body rw-definition-list"><div><span>运行 ID</span><Mono>{result.id}</Mono></div><div><span>索引版本</span><Mono>{result.index_version}</Mono></div><div><span>向量模型</span><strong>{result.embedding_model}</strong></div><div><span>上下文 Hash</span><Mono>{truncateHash(result.context_hash, 20)}</Mono></div></div></Card>
          <Card title="检索契约" eyebrow="安全边界"><div className="rw-card__body rw-stack"><div className="rw-callout"><Braces size={17} /><p>入选结果可以生成不可变引用；关键词、向量和融合分数均禁止进入假设支持指数。</p></div><div className="rw-cluster"><Tag tone="info">FTS 前 20</Tag><Tag tone="hypothesis">向量 1024 维</Tag><Tag tone="knowledge">RRF k=60</Tag><Tag tone="success">最终前 6</Tag></div></div></Card>
        </div>
        {result.intents.map((intentResult) => <Card key={intentResult.type} title={intentResult.query} eyebrow={retrievalIntentLabel(intentResult.type)} action={<StatusTag status={result.status} technical />}>
          <div className="rw-table-wrap"><table className="rw-table rw-retrieval-table"><thead><tr><th>选择</th><th>知识单元（原文片段）</th><th>FTS</th><th>向量</th><th>RRF</th><th>适用性</th><th>来源定位</th></tr></thead><tbody>{intentResult.hits.map((hit) => <tr key={hit.knowledge_unit_id} className={hit.selected ? 'is-selected' : ''}><td>{hit.selected ? <Tag tone="success">第 {hit.fusion_rank} 名</Tag> : <span className="rw-muted">候选</span>}</td><td><strong>{hit.title}</strong><p>{hit.content.slice(0, 130)}{hit.content.length > 130 ? '…' : ''}</p><Mono>{hit.knowledge_unit_id}</Mono></td><td>{hit.keyword_rank ? <><strong>#{hit.keyword_rank}</strong><br /><Mono>{hit.keyword_score?.toFixed(5)}</Mono></> : '—'}</td><td>{hit.vector_rank ? <><strong>#{hit.vector_rank}</strong><br /><Mono>{hit.vector_score?.toFixed(5)}</Mono></> : '—'}</td><td><strong>#{hit.fusion_rank}</strong><br /><Mono>{hit.fusion_score.toFixed(6)}</Mono></td><td><strong>× {hit.applicability_score.toFixed(2)}</strong><br /><span className="rw-muted">{applicabilityReasonLabel(hit.applicability_reason)}</span><TechnicalDetails summary="预期观察">{Array.isArray(hit.expected_predicates) ? hit.expected_predicates.map((value) => predicateLabel(String(value), domainPackKey)).join('、') : '当前版本未记录'}</TechnicalDetails></td><td><Mono>{JSON.stringify(hit.source_locator)}</Mono><br /><span className="rw-muted">v{hit.source_version}</span></td></tr>)}</tbody></table></div>
        </Card>)}
      </>}
    </div>
  );
}
