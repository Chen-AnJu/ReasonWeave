import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { ClipboardClock, Download, Filter, GitCommitHorizontal, UserRound } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { queries } from '../api/queries';
import { reasonweaveApi } from '../api/reasonweave';
import type { AuditEntry } from '../api/types';
import { EventTabs } from '../components/EventTabs';
import { Button, Card, EmptyState, ErrorState, LoadingState, PageHeader, Select, Tag, TechnicalDetails } from '../components/ui';
import { formatDate } from '../shared/format';
import { jsonRecord, jsonString } from '../shared/json';
import { auditActionLabel, auditFieldLabel, auditResourceLabel } from '../shared/presentation';

const actions = [
  'event.created',
  'evidence.created',
  'evidence.uploaded',
  'evidence.processing_failed',
  'evidence.reprocess_requested',
  'observation.updated',
  'investigation.started',
  'retrieval.completed',
  'hypotheses.generated',
  'investigation.completed',
  'investigation.failed',
  'investigation.interrupted',
];

export function AuditPage() {
  const { eventId = '' } = useParams();
  const event = useQuery(queries.event(eventId));
  const runs = useInfiniteQuery(queries.investigations(eventId));
  const runItems = runs.data?.pages.flatMap((page) => page.items) ?? [];
  const [action, setAction] = useState('');
  const [runId, setRunId] = useState('');
  const [actorId, setActorId] = useState('');
  const [selectedId, setSelectedId] = useState<string>();
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState('');
  const audit = useInfiniteQuery({
    queryKey: ['event-audit', eventId, { action, runId, actorId }],
    queryFn: ({ pageParam }) => reasonweaveApi.audit(eventId, {
      cursor: pageParam || undefined,
      action: action || undefined,
      runId: runId || undefined,
      actorId: actorId || undefined,
    }),
    initialPageParam: '',
    getNextPageParam: (last) => last.next_cursor,
  });
  const items = useMemo(() => audit.data?.pages.flatMap((page) => page.items) ?? [], [audit.data]);
  const selected = items.find((item) => item.id === selectedId) ?? items[0];
  const exportUrl = reasonweaveApi.auditExportUrl(eventId, {
    action: action || undefined,
    runId: runId || undefined,
    actorId: actorId || undefined,
  });
  const exportAudit = async () => {
    setExporting(true);
    setExportError('');
    try {
      const response = await fetch(exportUrl, {
        headers: { Accept: 'application/x-ndjson' },
        credentials: 'same-origin',
      });
      if (!response.ok) {
        throw new Error(`审计导出失败（HTTP ${response.status}）`);
      }
      const blob = await response.blob();
      const objectUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = objectUrl;
      link.download = `reasonweave-audit-${eventId}.jsonl`;
      document.body.append(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(objectUrl);
    } catch (error) {
      setExportError(error instanceof Error ? error.message : '审计导出失败');
    } finally {
      setExporting(false);
    }
  };

  if (event.isPending || runs.isPending) return <LoadingState label="正在读取审计时间线" />;
  if (event.isError || runs.isError) return <ErrorState error={event.error ?? runs.error} onRetry={() => { event.refetch(); runs.refetch(); }} />;

  return (
    <div className="rw-stack">
      <PageHeader
        eyebrow="只读操作记录"
        title="审计时间线"
        description={`${event.data.title} · 所有记录按发生时间和记录 ID 稳定排序。`}
        actions={<Button onClick={() => void exportAudit()} disabled={exporting}><Download size={15} />{exporting ? '正在导出' : '导出 JSONL'}</Button>}
      />
      <EventTabs eventId={eventId} />
      {exportError && <div className="rw-callout rw-callout--danger"><p>{exportError}</p></div>}
      <div className="rw-audit-filters">
        <div className="rw-cluster"><Filter size={16} /><strong>筛选记录</strong></div>
        <label><span>操作</span><Select value={action} onChange={(value) => { setAction(value.target.value); setSelectedId(undefined); }}><option value="">全部操作</option>{actions.map((value) => <option key={value} value={value}>{auditActionLabel(value)}</option>)}</Select></label>
        <label><span>调查运行</span><Select value={runId} onChange={(value) => { setRunId(value.target.value); setSelectedId(undefined); }}><option value="">全部运行</option>{runItems.map((run) => <option key={run.id} value={run.id}>第 {run.sequence_no} 轮</option>)}</Select></label>
        {runs.hasNextPage && <Button variant="ghost" onClick={() => runs.fetchNextPage()} disabled={runs.isFetchingNextPage}>{runs.isFetchingNextPage ? '正在加载' : '加载更早运行'}</Button>}
        <label><span>操作者</span><Select value={actorId} onChange={(value) => { setActorId(value.target.value); setSelectedId(undefined); }}><option value="">全部操作者</option><option value="local_api">本地 API</option><option value="reasonweave">系统</option></Select></label>
      </div>

      <div className="rw-audit-layout">
        <Card title="操作记录" eyebrow={`${items.length} 条已加载`}>
          {audit.isPending ? <LoadingState label="正在加载审计记录" /> : audit.isError && items.length === 0 ? <ErrorState error={audit.error} onRetry={() => audit.refetch()} /> : items.length === 0 ? <EmptyState title="没有匹配的审计记录" description="调整操作、调查运行或操作者筛选条件。" /> : <>
            {audit.isError && <div className="rw-callout rw-callout--warning"><p>后续分页读取失败，当前已加载记录仍可查看。</p></div>}
            <ol className="rw-audit-timeline">
              {items.map((item) => <li key={item.id} className={selected?.id === item.id ? 'is-selected' : ''}>
                <button onClick={() => setSelectedId(item.id)} aria-pressed={selected?.id === item.id}>
                  <span className="rw-audit-dot"><GitCommitHorizontal size={13} /></span>
                  <span className="rw-audit-copy"><strong>{auditActionLabel(item.action)}</strong><small>{resourceLabel(item)}</small><time>{formatDate(item.occurred_at)}</time></span>
                </button>
              </li>)}
            </ol>
            {audit.hasNextPage && <div className="rw-audit-more"><Button variant="secondary" onClick={() => audit.fetchNextPage()} disabled={audit.isFetchingNextPage}>{audit.isFetchingNextPage ? '正在加载' : '加载更多'}</Button></div>}
          </>}
        </Card>
        <Card title="记录详情" eyebrow="结构化差异">
          {!selected ? <EmptyState title="选择一条记录" description="查看操作者、资源、请求 ID 和变更详情。" /> : <AuditDetail entry={selected} />}
        </Card>
      </div>
    </div>
  );
}

function AuditDetail({ entry }: { entry: AuditEntry }) {
  const changes = topLevelChanges(entry.before_state, entry.after_state);
  const actor = jsonRecord(entry.actor);
  const resource = jsonRecord(entry.resource);
  return (
    <div className="rw-card__body rw-stack">
      <div className="rw-audit-detail-heading"><div className="rw-audit-detail-icon"><ClipboardClock size={20} /></div><div><h3>{auditActionLabel(entry.action)}</h3><p>{formatDate(entry.occurred_at)}</p></div></div>
      <div className="rw-definition-list">
        <div><span>操作者</span><strong><UserRound size={13} /> {jsonString(actor.id) === 'local_api' ? '本地 API' : (jsonString(actor.id) || '系统')}</strong></div>
        <div><span>资源</span><strong>{auditResourceLabel(jsonString(resource.type))}</strong></div>
        <div><span>请求 ID</span><strong>{entry.request_id ? '已记录' : '当前版本未记录'}</strong></div>
      </div>
      <div>
        <div className="rw-eyebrow">字段变化</div>
        {changes.length === 0 ? <p className="rw-muted">此操作没有可展开的字段差异。</p> : <div className="rw-audit-diff">{changes.map((change) => <details key={change.key}><summary>{auditFieldLabel(change.key)}<Tag tone="info">已变更</Tag></summary><div><span>变更前</span><pre>{formatJson(change.before)}</pre><span>变更后</span><pre>{formatJson(change.after)}</pre></div></details>)}</div>}
      </div>
      <TechnicalDetails summary="完整技术记录"><pre>{JSON.stringify(entry, null, 2)}</pre></TechnicalDetails>
    </div>
  );
}

function topLevelChanges(beforeValue: unknown, afterValue: unknown) {
  const before = jsonRecord(beforeValue);
  const after = jsonRecord(afterValue);
  const keys = new Set([...Object.keys(before), ...Object.keys(after)]);
  return [...keys].filter((key) => JSON.stringify(before[key]) !== JSON.stringify(after[key]))
    .map((key) => ({ key, before: before[key], after: after[key] }));
}

function formatJson(value: unknown) {
  return value === undefined ? '未记录' : JSON.stringify(value, null, 2);
}

function resourceLabel(entry: AuditEntry) {
  return auditResourceLabel(jsonString(jsonRecord(entry.resource).type));
}
