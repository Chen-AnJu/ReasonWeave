import { useQuery } from '@tanstack/react-query';
import { BookOpenCheck, ExternalLink, FlaskConical } from 'lucide-react';
import { Link } from 'react-router-dom';
import { queries } from '../api/queries';
import { Card, EmptyState, ErrorState, LoadingState, Metric, Mono, PageHeader, StatusTag, Tag, TechnicalDetails } from '../components/ui';
import { useDomainLabels } from '../shared/domainPresentationContext';
import { formatDate, truncateHash } from '../shared/format';

export function KnowledgePage() {
  const { domainPackLabel } = useDomainLabels();
  const sources = useQuery(queries.sources());
  const documents = useQuery(queries.documents());
  if (sources.isPending || documents.isPending) return <LoadingState label="正在读取知识索引" />;
  if (sources.isError || documents.isError) return <ErrorState error={sources.error ?? documents.error} onRetry={() => { sources.refetch(); documents.refetch(); }} />;
  const published = sources.data.filter((source) => source.status === 'PUBLISHED');
  return (
    <div className="rw-stack">
      <PageHeader eyebrow="知识与检索" title="知识中心" description="知识单元只提供可引用背景与预期证据，不会直接贡献支持指数。" actions={<Link className="rw-button rw-button--primary" to={`/retrieval`}><ExternalLink size={15} />打开检索检查器</Link>} />
      <div className="rw-metrics"><Metric label="知识源" value={sources.data.length} /><Metric label="已发布" value={published.length} tone="brand" /><Metric label="文档" value={documents.data.length} tone="info" /><Metric label="知识单元" value={sources.data.reduce((sum, item) => sum + item.unit_count, 0)} tone="hypothesis" /></div>
      <Card title="知识源" eyebrow="发布边界">
        {sources.data.length === 0 ? <EmptyState title="没有知识源" description="领域包初始化程序会幂等导入随包知识。" /> : <div className="rw-source-grid">{sources.data.map((source) => <article className="rw-source-card" key={source.id}><div className="rw-source-card__top"><div className="rw-source-icon"><BookOpenCheck size={20} /></div><StatusTag status={source.status} /></div><h3><Link to={`/knowledge/sources/${source.id}`}>{domainPackLabel(source.domain_pack_key)}</Link></h3><p>{source.fixture_only ? '仅用于软件开发和测试，不是行业标准或操作指南。' : (source.license ?? '授权信息待补充')}</p><div className="rw-cluster"><Tag tone="knowledge">领域知识</Tag>{source.fixture_only && <Tag tone="warning"><FlaskConical size={12} />仅开发夹具</Tag>}</div><dl><div><dt>版本</dt><dd>{source.version}</dd></div><div><dt>文档</dt><dd>{source.document_count}</dd></div><div><dt>知识单元</dt><dd>{source.unit_count}</dd></div><div><dt>正式环境允许</dt><dd>{source.production_allowed ? '是' : '否'}</dd></div></dl><Link className="rw-button rw-button--secondary" to={`/knowledge/sources/${source.id}`}>查看只读详情</Link><TechnicalDetails><div className="rw-definition-list"><div><span>知识源 ID</span><Mono>{source.id}</Mono></div><div><span>领域包键</span><Mono>{source.domain_pack_key}</Mono></div><div><span>原始名称</span><Mono>{source.name}</Mono></div></div></TechnicalDetails></article>)}</div>}
      </Card>
      <Card title="文档与切分" eyebrow="Markdown 结构化切分"><div className="rw-table-wrap"><table className="rw-table"><thead><tr><th>文档</th><th>语言</th><th>解析状态</th><th>知识单元</th><th>SHA-256</th><th>导入时间</th></tr></thead><tbody>{documents.data.map((document) => <tr key={document.id}><td>{document.title}<br /><Mono>{document.id}</Mono></td><td>{document.language === 'en' ? '英语原文' : document.language}</td><td><StatusTag status={document.parse_status} /></td><td>{document.unit_count}</td><td><Mono>{truncateHash(document.checksum_sha256)}</Mono></td><td>{formatDate(document.created_at)}</td></tr>)}</tbody></table></div></Card>
    </div>
  );
}
