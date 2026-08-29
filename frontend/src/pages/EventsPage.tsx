import { useInfiniteQuery } from '@tanstack/react-query';
import { Filter, Plus, Search } from 'lucide-react';
import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { queries } from '../api/queries';
import { Card, EmptyState, ErrorState, Input, LoadingState, PageHeader, Progress, Select, StatusTag } from '../components/ui';
import { useDomainLabels } from '../shared/domainPresentationContext';
import { formatDate, formatPercent } from '../shared/format';

export function EventsPage() {
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState('');
  const { eventTypeLabel } = useDomainLabels();
  const events = useInfiniteQuery(queries.eventPages(query, status));
  const items = useMemo(() => events.data?.pages.flatMap((page) => page.items) ?? [], [events.data]);
  const total = events.data?.pages[0]?.total ?? 0;
  return (
    <div className="rw-stack">
      <PageHeader
        eyebrow="事件管理"
        title="事件中心"
        description="每个事件都是证据、知识检索和只追加调查运行的聚合边界。"
        actions={<Link className="rw-button rw-button--primary" to={`/events/new`}><Plus size={15} />新建事件</Link>}
      />
      <Card title="事件列表" eyebrow={`共 ${total} 条`} action={
        <div className="rw-cluster rw-filter-bar">
          <div className="rw-inline-input"><Search size={15} /><Input aria-label="搜索事件" placeholder="标题或事件编号" value={query} onChange={(event) => setQuery(event.target.value)} /></div>
          <div className="rw-inline-input"><Filter size={15} /><Select aria-label="筛选状态" value={status} onChange={(event) => setStatus(event.target.value)}><option value="">全部状态</option><option value="DRAFT">草稿</option><option value="COLLECTING">取证中</option><option value="INVESTIGATING">调查中</option></Select></div>
        </div>
      }>
        {events.isPending ? <LoadingState label="正在读取事件" /> : events.isError ? <ErrorState error={events.error} onRetry={() => events.refetch()} /> : items.length === 0 ? <EmptyState title="没有匹配事件" description="调整筛选条件，或创建一份新的 EventIR。" /> : (
          <><div className="rw-table-wrap"><table className="rw-table">
            <thead><tr><th>事件</th><th>类型 / 地点</th><th>状态</th><th>证据</th><th>首要假设</th><th>支持指数</th><th>覆盖率</th><th>更新时间</th></tr></thead>
            <tbody>{items.map((item) => <tr key={item.id}>
              <td><Link to={`/events/${item.id}`}>{item.title}<br /><code className="rw-mono">{item.reference_code}</code></Link></td>
              <td>{eventTypeLabel(item.event_type, item.domain_pack_key)}<br /><span className="rw-muted">{item.location_name ?? '地点待补充'}</span></td>
              <td><StatusTag status={item.status} /></td>
              <td>{item.evidence_count}</td>
              <td>{item.top_hypothesis ?? <span className="rw-muted">—</span>}</td>
              <td>{item.latest_score == null ? '—' : `${item.latest_score} / 100`}</td>
              <td><div className="rw-coverage-cell"><span>{formatPercent(item.latest_coverage)}</span><Progress value={item.latest_coverage ?? 0} tone="hypothesis" label={`${item.title} 的证据覆盖率`} /></div></td>
              <td>{formatDate(item.updated_at)}</td>
            </tr>)}</tbody>
          </table></div>{events.hasNextPage && <div className="rw-card__footer"><button className="rw-button rw-button--secondary" type="button" onClick={() => events.fetchNextPage()} disabled={events.isFetchingNextPage}>{events.isFetchingNextPage ? '正在加载' : `加载更多（已显示 ${items.length} / ${total}）`}</button></div>}</>
        )}
      </Card>
    </div>
  );
}
