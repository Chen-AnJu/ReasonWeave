import { useInfiniteQuery } from '@tanstack/react-query';
import { Search, ShieldCheck } from 'lucide-react';
import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { queries } from '../api/queries';
import { Card, EmptyState, ErrorState, Input, LoadingState, PageHeader, StatusTag, Tag } from '../components/ui';
import { useDomainLabels } from '../shared/domainPresentationContext';
import { formatDate, truncateHash } from '../shared/format';
import { evidenceSourceLabel, evidenceTypeLabel } from '../shared/presentation';

export function EvidenceLibraryPage() {
  const { sourceProfileLabel } = useDomainLabels();
  const [params] = useSearchParams();
  const eventId = params.get('event') ?? undefined;
  const evidence = useInfiniteQuery(queries.evidencePages(eventId));
  const [search, setSearch] = useState('');
  const items = useMemo(() => evidence.data?.pages.flatMap((page) => page.items) ?? [], [evidence.data]);
  const total = evidence.data?.pages[0]?.total ?? 0;
  const filtered = useMemo(() => items.filter((item) =>
    `${item.original_name ?? ''} ${item.type} ${item.source} ${item.id}`.toLowerCase().includes(search.toLowerCase()),
  ), [items, search]);
  return (
    <div className="rw-stack">
      <PageHeader eyebrow="证据管理" title="证据库" description="现实证据与领域知识使用独立数据模型；只有经确认的观察才可能进入评分。" actions={<Tag tone="info"><ShieldCheck size={13} />SHA-256 去重</Tag>} />
      <Card title="现实证据" eyebrow={eventId ? `当前事件 · 共 ${total} 条` : `全部事件 · 共 ${total} 条`} action={<div className="rw-inline-input"><Search size={15} /><Input aria-label="搜索已加载证据" placeholder="在已加载记录中搜索" value={search} onChange={(event) => setSearch(event.target.value)} /></div>}>
        {evidence.isPending ? <LoadingState label="正在读取证据库" /> : evidence.isError ? <ErrorState error={evidence.error} onRetry={() => evidence.refetch()} /> : filtered.length === 0 ? <EmptyState title="没有匹配证据" description="从事件详情添加文本、图片或结构化文件证据。" /> : <><div className="rw-table-wrap"><table className="rw-table"><thead><tr><th>证据</th><th>事件</th><th>类型 / 来源</th><th>处理状态</th><th>观察</th><th>可靠性</th><th>SHA-256</th><th>创建时间</th></tr></thead><tbody>{filtered.map((item) => <tr key={item.id}><td><Link to={`/evidence/${item.id}`}>{item.original_name ?? evidenceTypeLabel(item.type)}<br /><code className="rw-mono">{item.id}</code></Link></td><td><Link to={`/events/${item.event_id}`}><code className="rw-mono">{item.event_id}</code></Link></td><td>{evidenceTypeLabel(item.type)}<br /><span className="rw-muted">{sourceProfileLabel(item.source, undefined, evidenceSourceLabel(item.source))}</span></td><td><StatusTag status={item.status} /></td><td>{item.observation_count}</td><td>{Math.round(item.reliability * 100)}%</td><td><code className="rw-mono">{truncateHash(item.checksum_sha256)}</code></td><td>{formatDate(item.created_at)}</td></tr>)}</tbody></table></div>{evidence.hasNextPage && <div className="rw-card__footer"><button className="rw-button rw-button--secondary" type="button" onClick={() => evidence.fetchNextPage()} disabled={evidence.isFetchingNextPage}>{evidence.isFetchingNextPage ? '正在加载' : `加载更多（已加载 ${items.length} / ${total}）`}</button></div>}</>}
      </Card>
    </div>
  );
}
