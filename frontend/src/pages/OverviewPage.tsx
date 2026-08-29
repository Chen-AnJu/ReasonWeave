import { useQuery } from '@tanstack/react-query';
import { ArrowRight, FileCheck2, FlaskConical, ShieldCheck, Sparkles } from 'lucide-react';
import { Link } from 'react-router-dom';
import { queries } from '../api/queries';
import { Card, EmptyState, ErrorState, LoadingState, Metric, PageHeader, Progress, StatusTag, Tag } from '../components/ui';
import { formatDate, formatPercent } from '../shared/format';

export function OverviewPage() {
  const events = useQuery(queries.events());
  const evidence = useQuery(queries.evidence());
  const sources = useQuery(queries.sources());
  const domainPacks = useQuery(queries.domainPacks());
  if (events.isPending || evidence.isPending || sources.isPending || domainPacks.isPending) return <LoadingState label="正在汇总工作台" />;
  if (events.isError || evidence.isError || sources.isError || domainPacks.isError) return <ErrorState error={events.error ?? evidence.error ?? sources.error ?? domainPacks.error} onRetry={() => { events.refetch(); evidence.refetch(); sources.refetch(); domainPacks.refetch(); }} />;
  const eventItems = events.data.items;
  const completed = eventItems.filter((item) => item.latest_score != null);
  const avgCoverage = completed.length
    ? completed.reduce((sum, item) => sum + (item.latest_coverage ?? 0), 0) / completed.length
    : 0;
  const productionPacks = domainPacks.data.filter((pack) => pack.production_allowed && !pack.fixture_only);
  const productionReady = productionPacks.length > 0 && productionPacks.every((pack) => pack.ready);
  return (
    <div className="rw-stack">
      <PageHeader
        eyebrow="ReasonWeave · 核心调查链"
        title="调查工作台"
        description="把现实证据、领域知识与可重放评分组织为不可变调查快照。"
        actions={<Tag tone={productionReady ? 'success' : 'warning'}><ShieldCheck size={13} /> {productionReady ? '生产链路就绪' : '生产链路未就绪'}</Tag>}
      />

      <div className="rw-metrics">
        <Metric label="事件总数" value={events.data.total} meta="按当前工作区统计" tone="brand" />
        <Metric label="证据总数" value={evidence.data.total} meta="现实证据，与知识独立" tone="warning" />
        <Metric label="已发布知识单元" value={sources.data?.reduce((sum, item) => sum + item.unit_count, 0) ?? 0} meta="仅已发布内容参与检索" tone="info" />
        <Metric label="最近事件平均覆盖" value={formatPercent(avgCoverage)} meta="基于当前加载的最近事件" tone="hypothesis" />
      </div>

      <div className="rw-grid rw-grid--sidebar">
        <Card title="最近事件" eyebrow="事件中心" className="rw-overflow-hidden">
          {eventItems.length === 0 ? (
            <EmptyState title="还没有事件" description="从一份可校验的 EventIR 开始调查。" action={<Link className="rw-button rw-button--primary" to={`/events/new`}>新建事件</Link>} />
          ) : (
            <div className="rw-table-wrap">
              <table className="rw-table">
                <thead><tr><th>事件</th><th>状态</th><th>证据</th><th>当前结论</th><th>更新</th></tr></thead>
                <tbody>
                  {eventItems.slice(0, 6).map((event) => (
                    <tr key={event.id}>
                      <td><Link to={`/events/${event.id}`}><span>{event.title}</span><br /><code className="rw-mono">{event.reference_code}</code></Link></td>
                      <td><StatusTag status={event.status} /></td>
                      <td>{event.evidence_count}</td>
                      <td>{event.latest_score == null ? <span className="rw-muted">尚未调查</span> : <div className="rw-score-cell"><strong>{event.latest_score}</strong><Progress value={event.latest_coverage ?? 0} tone="hypothesis" label={`${event.title} 的证据覆盖率`} /></div>}</td>
                      <td>{formatDate(event.updated_at)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>

        <div className="rw-stack">
          <Card title="系统边界" eyebrow="运行状态">
            <div className="rw-card__body rw-stack">
              <div className="rw-runtime-row"><span><Sparkles size={15} /> 向量嵌入</span><Tag tone={productionReady ? 'success' : 'warning'}>{productionReady ? '生产 Provider 已就绪' : '尚未满足生产门禁'}</Tag></div>
              <div className="rw-runtime-row"><span><FileCheck2 size={15} /> EventIR</span><Tag tone="success">契约强校验</Tag></div>
              <div className="rw-runtime-row"><span><FlaskConical size={15} /> 领域包</span><Tag tone="knowledge">{productionPacks.length} 个生产包</Tag></div>
              <div className="rw-callout rw-callout--warning"><p>支持指数不是概率。知识检索分数只负责排序，不会写入假设贡献项。</p></div>
            </div>
          </Card>
          <Link className="rw-action-card" to={`/retrieval`}>
            <div><span>检索检查器</span><strong>查看 FTS、向量与 RRF 每一路排名</strong></div><ArrowRight size={18} />
          </Link>
        </div>
      </div>
    </div>
  );
}
