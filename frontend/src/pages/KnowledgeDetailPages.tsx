import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { ArrowLeft, BookOpenCheck, DatabaseZap, ExternalLink, FileText, Quote } from 'lucide-react';
import Markdown from 'react-markdown';
import { Link, useParams } from 'react-router-dom';
import { queries } from '../api/queries';
import { reasonweaveApi } from '../api/reasonweave';
import type { KnowledgeUnitDetail } from '../api/types';
import { Button, Card, EmptyState, ErrorState, LoadingState, Metric, Mono, PageHeader, StatusTag, Tag, TechnicalDetails } from '../components/ui';
import { useDomainLabels } from '../shared/domainPresentationContext';
import { formatDate, truncateHash } from '../shared/format';
import { jsonRecord, jsonString, jsonStringArray } from '../shared/json';
import { knowledgeUnitLabel, retrievalIntentLabel, sourceLicenseLabel } from '../shared/presentation';

export function KnowledgeSourceDetailPage() {
  const { sourceId = '' } = useParams();
  const { domainPackLabel } = useDomainLabels();
  const source = useQuery(queries.source(sourceId));
  const units = useInfiniteQuery({
    queryKey: ['knowledge-units', sourceId, 'infinite'],
    queryFn: ({ pageParam }) => reasonweaveApi.knowledgeUnits(sourceId, pageParam || undefined),
    initialPageParam: '',
    getNextPageParam: (last) => last.next_cursor,
  });
  if (source.isPending || units.isPending) return <LoadingState label="正在读取知识源详情" />;
  if (source.isError || units.isError) return <ErrorState error={source.error ?? units.error} onRetry={() => { source.refetch(); units.refetch(); }} />;
  const value = source.data;
  const unitItems = units.data.pages.flatMap((page) => page.items);
  const embedding = value.embedding_provenance;
  return (
    <div className="rw-stack">
      <PageHeader
        eyebrow="知识源详情"
        title={domainPackLabel(value.source.domain_pack_key)}
        description="只读展示真实来源、文档、索引与使用情况；当前页面不提供发布或重建索引操作。"
        actions={<Link className="rw-button rw-button--ghost" to={`/knowledge`}><ArrowLeft size={15} />返回知识中心</Link>}
      />
      {!value.source.production_allowed && <div className="rw-callout rw-callout--warning"><p>此知识源仅供开发与测试，不可用于正式业务判断。</p></div>}
      <div className="rw-metrics"><Metric label="文档" value={value.source.document_count} /><Metric label="已发布知识单元" value={value.published_unit_count} tone="brand" /><Metric label="引用次数" value={value.citation_count} tone="hypothesis" /><Metric label="检索使用" value={value.retrieval_usage_count} tone="info" /></div>
      <div className="rw-grid rw-grid--sidebar">
        <div className="rw-stack">
          <Card title="知识单元" eyebrow={`${unitItems.length} 条已加载`}>
            {unitItems.length === 0 ? <EmptyState title="没有知识单元" description="该知识源尚未形成已发布切分单元。" /> : <div className="rw-knowledge-unit-list">{unitItems.map((unit) => <Link key={unit.id} to={`/knowledge/units/${unit.id}`}><div className="rw-source-icon"><BookOpenCheck size={18} /></div><div><strong>{knowledgeUnitLabel(unit.topic, unit.title)}</strong><span>领域包知识文档</span><div className="rw-cluster"><StatusTag status={unit.status} /><Tag tone={unit.embedding_present ? 'success' : 'warning'}>{unit.embedding_present ? '已有向量' : '无向量'}</Tag></div></div><ExternalLink size={15} /></Link>)}</div>}
            {units.hasNextPage && <div className="rw-audit-more"><Button variant="secondary" onClick={() => units.fetchNextPage()} disabled={units.isFetchingNextPage}>{units.isFetchingNextPage ? '正在加载' : '加载更多知识单元'}</Button></div>}
          </Card>
          <Card title="来源文档" eyebrow="真实文档记录"><div className="rw-table-wrap"><table className="rw-table"><thead><tr><th>文档</th><th>语言</th><th>状态</th><th>单元</th><th>导入时间</th></tr></thead><tbody>{value.documents.map((document) => <tr key={document.id}><td>{document.title}</td><td>{document.language === 'en' ? '英语原文' : document.language}</td><td><StatusTag status={document.parse_status} /></td><td>{document.unit_count}</td><td>{formatDate(document.created_at)}</td></tr>)}</tbody></table></div></Card>
        </div>
        <div className="rw-stack">
          <Card title="来源与适用性" eyebrow="来源追溯"><div className="rw-card__body rw-definition-list"><div><span>类型</span><strong>{value.source.source_type === 'DOMAIN_PACK' ? '领域包' : '用户知识源'}</strong></div><div><span>版本</span><strong>{value.source.version}</strong></div><div><span>授权说明</span><strong>{sourceLicenseLabel(value.source.license)}</strong></div><div><span>仅开发夹具</span><strong>{value.source.fixture_only ? '是' : '否'}</strong></div><div><span>正式环境允许</span><strong>{value.source.production_allowed ? '是' : '否'}</strong></div></div></Card>
          <Card title="索引状态" eyebrow="当前版本"><div className="rw-card__body rw-stack"><div className="rw-runtime-row"><span><DatabaseZap size={15} /> 已生成向量</span><strong>{value.embedding_unit_count} / {value.source.unit_count}</strong></div><div className="rw-runtime-row"><span>Embedding</span><strong>{embedding ? `${embedding.provider} / ${embedding.model} · ${embedding.dimension} 维` : '当前版本未记录'}</strong></div>{embedding && <div className="rw-runtime-row"><span>生产就绪</span><Tag tone={embedding.production_ready ? 'success' : 'warning'}>{embedding.production_ready ? '是' : '否'}</Tag></div>}<TechnicalDetails><div className="rw-definition-list"><div><span>知识源 ID</span><Mono>{value.source.id}</Mono></div><div><span>领域包键</span><Mono>{value.source.domain_pack_key}</Mono></div><div><span>索引版本</span><Mono>{value.current_index_version ?? '未建立'}</Mono></div><div><span>模型摘要</span><Mono>{embedding?.model_digest ?? '当前版本未记录'}</Mono></div><div><span>索引配置指纹</span><Mono>{embedding?.index_profile_fingerprint ?? '当前版本未记录'}</Mono></div></div></TechnicalDetails></div></Card>
        </div>
      </div>
    </div>
  );
}

export function KnowledgeUnitDetailPage() {
  const { unitId = '' } = useParams();
  const unit = useQuery(queries.unit(unitId));
  if (unit.isPending) return <LoadingState label="正在读取知识单元" />;
  if (unit.isError) return <ErrorState error={unit.error} onRetry={() => unit.refetch()} />;
  return <KnowledgeUnitDetailView unitId={unitId} value={unit.data} />;
}

function KnowledgeUnitDetailView({
  unitId,
  value,
}: {
  unitId: string;
  value: KnowledgeUnitDetail;
}) {
  const { domainPackLabel, hypothesisLabel, predicateLabel } = useDomainLabels();
  const citations = useInfiniteQuery({
    queryKey: ['knowledge-unit-citation-usages', unitId],
    queryFn: ({ pageParam }) => reasonweaveApi.knowledgeCitationUsages(unitId, pageParam || undefined),
    initialPageParam: '',
    getNextPageParam: (last) => last.next_cursor,
    initialData: {
      pages: [{
        items: value.citation_usages,
        next_cursor: value.citation_usages_next_cursor,
        limit: 20,
        total: value.citation_usage_count,
      }],
      pageParams: [''],
    },
    staleTime: 60_000,
  });
  const retrievals = useInfiniteQuery({
    queryKey: ['knowledge-unit-retrieval-usages', unitId],
    queryFn: ({ pageParam }) => reasonweaveApi.knowledgeRetrievalUsages(unitId, pageParam || undefined),
    initialPageParam: '',
    getNextPageParam: (last) => last.next_cursor,
    initialData: {
      pages: [{
        items: value.retrieval_usages,
        next_cursor: value.retrieval_usages_next_cursor,
        limit: 20,
        total: value.retrieval_usage_count,
      }],
      pageParams: [''],
    },
    staleTime: 60_000,
  });
  const citationItems = citations.data.pages.flatMap((page) => page.items);
  const retrievalItems = retrievals.data.pages.flatMap((page) => page.items);
  const expectedPredicates = jsonStringArray(value.expected_predicates);
  const sourceLocator = jsonRecord(value.source_locator);
  return (
    <div className="rw-stack">
      <PageHeader
        eyebrow="知识单元详情"
        title={knowledgeUnitLabel(value.topic, value.title)}
        description="原始知识内容保持不翻译；中文标签仅用于界面展示。"
        actions={<Link className="rw-button rw-button--ghost" to={`/knowledge/sources/${value.source.id}`}><ArrowLeft size={15} />返回知识源</Link>}
      />
      <div className="rw-cluster"><StatusTag status={value.status} /><Tag tone="knowledge">不直接贡献支持指数</Tag></div>
      <div className="rw-grid rw-grid--sidebar">
        <div className="rw-stack">
          <Card title="知识内容" eyebrow={value.document.language === 'zh-CN' ? '中文派生摘要' : `${value.document.language} 原文`}><article className="rw-knowledge-content"><FileText size={20} /><div className="rw-markdown"><Markdown skipHtml>{value.content}</Markdown></div></article></Card>
          <Card title="引用使用" eyebrow={`${value.citation_usage_count} 次`}>
            {citationItems.length === 0 ? <EmptyState title="尚未被引用" description="当前知识单元尚未进入任何调查引用快照。" /> : <div className="rw-usage-list">{citationItems.map((usage) => <article key={usage.citation_id}><Quote size={17} /><div><strong>为假设“{hypothesisLabel(usage.target_code, usage.target_title, value.source.domain_pack_key)}”提供可回溯知识背景；不产生评分贡献</strong><span>{formatDate(usage.created_at)}</span><div className="rw-cluster"><Link className="rw-button rw-button--ghost" to={`/events/${usage.event_id}/graph?investigation_id=${usage.investigation_run_id}`}>打开引用图谱</Link><Tag tone="knowledge">调查运行快照</Tag></div></div><TechnicalDetails summary="引用技术详情"><pre>{JSON.stringify(usage, null, 2)}</pre></TechnicalDetails></article>)}</div>}
            {citations.hasNextPage && <div className="rw-audit-more"><Button variant="secondary" onClick={() => citations.fetchNextPage()} disabled={citations.isFetchingNextPage}>{citations.isFetchingNextPage ? '正在加载' : '加载更多引用记录'}</Button></div>}
            {citations.isError && <p className="rw-field__error">引用记录加载失败，请重试。</p>}
          </Card>
          <Card title="检索使用" eyebrow={`${value.retrieval_usage_count} 次`}>
            {retrievalItems.length === 0 ? <EmptyState title="尚未参与检索" description="当前知识单元没有检索命中记录。" /> : <div className="rw-table-wrap"><table className="rw-table"><thead><tr><th>检索意图</th><th>融合排名</th><th>是否选中</th><th>索引版本</th><th>时间</th></tr></thead><tbody>{retrievalItems.map((usage) => <tr key={`${usage.retrieval_run_id}-${usage.query_intent}`}><td>{retrievalIntentLabel(usage.query_intent)}</td><td>{usage.fusion_rank}</td><td>{usage.selected ? '是' : '否'}</td><td><Mono>{truncateHash(usage.index_version, 18)}</Mono></td><td>{formatDate(usage.created_at)}</td></tr>)}</tbody></table></div>}
            {retrievals.hasNextPage && <div className="rw-audit-more"><Button variant="secondary" onClick={() => retrievals.fetchNextPage()} disabled={retrievals.isFetchingNextPage}>{retrievals.isFetchingNextPage ? '正在加载' : '加载更多检索记录'}</Button></div>}
            {retrievals.isError && <p className="rw-field__error">检索记录加载失败，请重试。</p>}
          </Card>
        </div>
        <div className="rw-stack">
          <Card title="适用性" eyebrow="结构化元数据"><div className="rw-card__body rw-stack"><div><div className="rw-eyebrow">预期观察</div><div className="rw-cluster rw-top-space">{expectedPredicates.length === 0 ? <span className="rw-muted">当前版本未记录</span> : expectedPredicates.map((predicate) => <Tag key={predicate} tone="info">{predicateLabel(predicate, value.source.domain_pack_key)}</Tag>)}</div></div><TechnicalDetails><pre>{JSON.stringify({ applicability: value.applicability, expected_predicates: value.expected_predicates }, null, 2)}</pre></TechnicalDetails></div></Card>
          <Card title="来源定位" eyebrow="可追溯"><div className="rw-card__body rw-definition-list"><div><span>知识源</span><Link to={`/knowledge/sources/${value.source.id}`}>{domainPackLabel(value.source.domain_pack_key)}</Link></div><div><span>文档</span><strong>{value.document.title}</strong></div><div><span>章节</span><strong>{knowledgeUnitLabel(value.topic, jsonString(sourceLocator.section))}</strong></div><div><span>来源版本</span><strong>{value.source_version}</strong></div><div><span>向量</span><strong>{value.embedding_present ? '已存在' : '不存在'}</strong></div></div></Card>
          <TechnicalDetails summary="完整技术详情"><pre>{JSON.stringify({ id: value.id, content_hash: value.content_hash, source_locator: value.source_locator, embedding_provenance: value.embedding_provenance, original_title: value.title, original_license: value.source.license }, null, 2)}</pre></TechnicalDetails>
        </div>
      </div>
    </div>
  );
}
