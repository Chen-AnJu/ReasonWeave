import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Ajv2020 } from 'ajv/dist/2020.js';
import addMetaSchema2020 from 'ajv/dist/refs/json-schema-2020-12/index.js';
import addFormats from 'ajv-formats';
import { AlertTriangle, Beaker, FileJson2, FilePlus2, Play, RefreshCw, Scale, Upload } from 'lucide-react';
import { useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import observationBundleSchema from '../../../contracts/observation-bundle/1.0/observation-bundle.schema.json';
import { queries } from '../api/queries';
import { reasonweaveApi } from '../api/reasonweave';
import type { ObservationBundle } from '../api/types';
import { Button, Card, EmptyState, ErrorState, LoadingState, Metric, Mono, PageHeader, Progress, StatusTag, Tag, TechnicalDetails, Textarea } from '../components/ui';
import { EventTabs } from '../components/EventTabs';
import { useDomainLabels } from '../shared/domainPresentationContext';
import { formatDate, formatPercent, truncateHash } from '../shared/format';
import { evidenceSourceLabel, evidenceTypeLabel } from '../shared/presentation';

const bundleAjv = new Ajv2020({ allErrors: true, strict: false });
if (!bundleAjv.getSchema('https://json-schema.org/draft/2020-12/schema')) {
  addMetaSchema2020.call(bundleAjv);
}
addFormats(bundleAjv);
const validateObservationBundle = bundleAjv.compile(observationBundleSchema);

export function EventOverviewPage() {
  const { eventId = '' } = useParams();
  const { domainPackLabel, eventTypeLabel, hypothesisDescription, hypothesisLabel, sourceProfileLabel } = useDomainLabels();
  const queryClient = useQueryClient();
  const eventView = useQuery(queries.eventView(eventId));
  const runs = useInfiniteQuery(queries.investigations(eventId));
  const scopedPack = eventView.data?.event.domain_pack_key ?? '';
  const separator = scopedPack.lastIndexOf('/');
  const packKey = separator > 0 ? scopedPack.slice(0, separator) : '';
  const packVersion = separator > 0 ? scopedPack.slice(separator + 1) : '';
  const eventType = eventView.data?.event.event_type ?? '';
  const eventDefinition = useQuery({
    ...queries.domainPackEventType(packKey, packVersion, eventType),
    enabled: Boolean(packKey && packVersion && eventType),
  });
  const [textEvidence, setTextEvidence] = useState('');
  const fileInput = useRef<HTMLInputElement>(null);
  const bundleInput = useRef<HTMLInputElement>(null);
  const invalidate = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['event-view', eventId] }),
      queryClient.invalidateQueries({ queryKey: ['event', eventId] }),
      queryClient.invalidateQueries({ queryKey: ['events'] }),
      queryClient.invalidateQueries({ queryKey: ['evidence'] }),
      queryClient.invalidateQueries({ queryKey: ['evidence-pages'] }),
      queryClient.invalidateQueries({ queryKey: ['investigations', eventId] }),
    ]);
  };
  const investigate = useMutation({ mutationFn: () => reasonweaveApi.startInvestigation(eventId), onSuccess: invalidate });
  const addText = useMutation({
    mutationFn: () => reasonweaveApi.addTextEvidence(eventId, textEvidence),
    onSuccess: async () => { setTextEvidence(''); await invalidate(); },
  });
  const upload = useMutation({ mutationFn: (file: File) => reasonweaveApi.uploadEvidence(eventId, file), onSuccess: invalidate });
  const importBundle = useMutation({
    mutationFn: async (file: File) => {
      let parsed: unknown;
      try {
        parsed = JSON.parse(await file.text()) as unknown;
      } catch {
        throw new Error('Observation Bundle 必须是有效的 JSON 文件');
      }
      if (!validateObservationBundle(parsed)) {
        const detail = validateObservationBundle.errors?.slice(0, 3)
          .map((error) => `${error.instancePath || '/'} ${error.message}`)
          .join('；');
        throw new Error(`Observation Bundle 不符合 1.0 契约：${detail ?? '未知错误'}`);
      }
      return reasonweaveApi.importObservationBundle(eventId, parsed as ObservationBundle);
    },
    onSuccess: invalidate,
  });
  if (eventView.isPending || runs.isPending) return <LoadingState label="正在读取事件聚合视图" />;
  if (eventView.isError) return <ErrorState error={eventView.error} onRetry={() => eventView.refetch()} />;
  const { event } = eventView.data;
  const evidenceInputs = eventDefinition.data?.evidence_inputs ?? [];
  const textInput = evidenceInputs.find((input) => input.type === 'text');
  const bundleInputDefinition = evidenceInputs.find((input) => input.type === 'observation_bundle');
  const fileInputDefinition = evidenceInputs.find((input) => input.type === 'file');
  const imageInputDefinition = evidenceInputs.find((input) => input.type === 'image');
  const textEnabled = textInput?.enabled === true;
  const bundleEnabled = bundleInputDefinition?.enabled === true;
  const fileEnabled = fileInputDefinition?.enabled === true;
  const imageEnabled = imageInputDefinition?.enabled === true;
  const acceptedContentTypes = [
    ...(fileEnabled ? fileInputDefinition.content_types : []),
    ...(imageEnabled ? imageInputDefinition.content_types : []),
  ];
  const latest = runs.data?.pages[0]?.items[0];
  const latestResult = latest?.result;
  const top = latestResult?.hypotheses[0];
  return (
    <div className="rw-stack">
      <PageHeader
        eyebrow={<span><Mono>{event.reference_code}</Mono> · {eventTypeLabel(event.event_type, event.domain_pack_key)}</span>}
        title={event.title}
        description={event.description ?? '没有事件描述'}
        actions={<>
          <StatusTag status={event.status} />
          <Button variant="secondary" onClick={() => investigate.mutate()} disabled={investigate.isPending}><RefreshCw size={15} />重新调查</Button>
          <Link className="rw-button rw-button--primary" to={`/events/${eventId}/investigation`}><Beaker size={15} />调查工作台</Link>
        </>}
      />
      <EventTabs eventId={eventId} />
      {eventView.data.stale && <div className="rw-callout rw-callout--warning"><AlertTriangle size={17} /><p>新增或复核了证据，最近一轮调查已过期。旧调查运行保持不变；请创建新运行。</p></div>}
      {(investigate.isError || addText.isError || upload.isError || importBundle.isError) && <div className="rw-callout rw-callout--danger"><AlertTriangle size={17} /><p>{investigate.error?.message ?? addText.error?.message ?? upload.error?.message ?? importBundle.error?.message}</p></div>}
      {importBundle.data && <div className="rw-callout"><FileJson2 size={17} /><p>{importBundle.data.duplicate ? '该 Observation Bundle 已导入，本次返回已有证据。' : `已导入 ${importBundle.data.evidence.length} 项证据。请逐项打开并人工确认观察后再发起调查。`}</p></div>}

      <div className="rw-metrics">
        <Metric label="事件版本" value={`v${event.version}`} meta="证据变化会递增" />
        <Metric label="现实证据" value={eventView.data.evidence.length} meta="与领域知识完全分离" tone="brand" />
        <Metric label="首要支持指数" value={top ? `${top.score}` : '—'} meta="不是概率" tone="hypothesis" />
        <Metric label="首要覆盖率" value={formatPercent(top?.coverage)} meta="可用观察 / 预期证据" tone="info" />
      </div>

      <div className="rw-grid rw-grid--sidebar">
        <div className="rw-stack">
          <Card title="最近调查" eyebrow="调查快照" action={latest && <StatusTag status={latest.status} />}>
            {!latest ? <EmptyState title="尚未开始调查" description="系统将按固定顺序执行查询计划、检索、假设、评分与下一步取证。" action={<Button onClick={() => investigate.mutate()} disabled={investigate.isPending}><Play size={15} />开始调查</Button>} /> : latest.status === 'FAILED' ? <div className="rw-card__body"><div className="rw-callout rw-callout--danger"><p>{latest.error_message ?? '调查运行失败'}</p></div></div> : !latestResult ? <LoadingState label="调查结果正在写入" /> : (
              <div className="rw-card__body rw-stack">
                <div className="rw-snapshot-meta"><div><span>调查运行</span><strong>第 {latest.sequence_no} 轮</strong></div><div><span>证据数量</span><strong>{latestResult.evidence_snapshot.evidence_ids.length}</strong></div><div><span>完成时间</span><strong>{formatDate(latest.completed_at)}</strong></div></div>
                <TechnicalDetails><div className="rw-definition-list"><div><span>运行 ID</span><Mono>{latest.id}</Mono></div><div><span>证据快照 Hash</span><Mono>{truncateHash(latest.evidence_snapshot_hash)}</Mono></div><div><span>知识索引</span><Mono>{latest.knowledge_index_version}</Mono></div></div></TechnicalDetails>
                <div className="rw-hypothesis-list">{latestResult.hypotheses.map((hypothesis, index) => <div className="rw-hypothesis-row" key={hypothesis.id}><div className="rw-rank">H{index + 1}</div><div className="rw-hypothesis-row__body"><div className="rw-cluster"><strong>{hypothesisLabel(hypothesis.code, hypothesis.title, event.domain_pack_key)}</strong><StatusTag status={hypothesis.band} /></div><p>{hypothesisDescription(hypothesis.code, hypothesis.description, event.domain_pack_key)}</p><div className="rw-cluster"><span>覆盖 {formatPercent(hypothesis.coverage)}</span><Progress value={hypothesis.coverage} tone="hypothesis" label={`${hypothesisLabel(hypothesis.code, hypothesis.title, event.domain_pack_key)} 的证据覆盖率`} /></div></div><div className="rw-score"><strong>{hypothesis.score}</strong><span>/ 100</span></div></div>)}</div>
                <div className="rw-callout"><Scale size={17} /><p>{latestResult.support_index_disclaimer}</p></div>
              </div>
            )}
          </Card>
          <Card title="证据快照" eyebrow="现实证据" action={<Link className="rw-button rw-button--ghost" to={`/evidence?event=${eventId}`}>查看全部</Link>}>
            {eventView.data.evidence.length === 0 ? <EmptyState title="还没有现实证据" description="上传文件或添加文本证据。" /> : <div className="rw-table-wrap"><table className="rw-table"><thead><tr><th>证据</th><th>来源</th><th>状态</th><th>可靠性</th><th>加入时间</th></tr></thead><tbody>{eventView.data.evidence.map((item) => <tr key={item.id}><td><Link to={`/evidence/${item.id}`}>{item.original_name || evidenceTypeLabel(item.type)}<br /><Mono>{item.id}</Mono></Link></td><td>{sourceProfileLabel(item.source, event.domain_pack_key, evidenceSourceLabel(item.source))}</td><td><StatusTag status={item.status} /></td><td>{Math.round(Number(item.reliability) * 100)}%</td><td>{formatDate(item.created_at)}</td></tr>)}</tbody></table></div>}
          </Card>
        </div>

        <div className="rw-stack">
          <Card title="添加证据" eyebrow="证据录入">
            <div className="rw-card__body rw-stack">
              {eventDefinition.isPending && <LoadingState label="正在读取领域证据能力" />}
              {eventDefinition.isError && <ErrorState error={eventDefinition.error} onRetry={() => eventDefinition.refetch()} />}
              {textEnabled && <>
                <Textarea aria-label="文本证据" placeholder="输入可追溯的事实描述；不要在证据里混入责任结论。" value={textEvidence} onChange={(event) => setTextEvidence(event.target.value)} />
                <Button onClick={() => addText.mutate()} disabled={!textEvidence.trim() || addText.isPending}><FilePlus2 size={15} />{textInput?.label ?? '添加文本证据'}</Button>
              </>}
              {bundleEnabled && <>
                {textEnabled && <div className="rw-divider" />}
                <input ref={bundleInput} type="file" className="rw-sr-only" accept="application/json,.json" onChange={(event) => { const file = event.target.files?.[0]; if (file) importBundle.mutate(file); event.currentTarget.value = ''; }} />
                <Button onClick={() => bundleInput.current?.click()} disabled={importBundle.isPending}><FileJson2 size={15} />{importBundle.isPending ? '正在校验并导入…' : (bundleInputDefinition?.label ?? '上传 Observation Bundle')}</Button>
                {bundleInputDefinition?.help && <p className="rw-muted">{bundleInputDefinition.help}</p>}
                {bundleInputDefinition?.collector_command && <TechnicalDetails summary="采集器命令"><Mono>{bundleInputDefinition.collector_command}</Mono></TechnicalDetails>}
              </>}
              {(fileEnabled || imageEnabled) && <>
                {(textEnabled || bundleEnabled) && <div className="rw-divider" />}
                <input ref={fileInput} type="file" className="rw-sr-only" accept={acceptedContentTypes.join(',')} onChange={(event) => { const file = event.target.files?.[0]; if (file) upload.mutate(file); event.currentTarget.value = ''; }} />
                <Button variant="secondary" onClick={() => fileInput.current?.click()} disabled={upload.isPending}><Upload size={15} />{fileInputDefinition?.label ?? imageInputDefinition?.label ?? '上传证据文件'}</Button>
              </>}
              {!eventDefinition.isPending && !eventDefinition.isError && !textEnabled && !bundleEnabled && !fileEnabled && !imageEnabled && <EmptyState title="没有可用证据入口" description="当前领域事件定义未启用证据录入能力。" />}
            </div>
          </Card>
          <Card title="事件上下文" eyebrow="EventIR">
            <div className="rw-card__body rw-definition-list"><div><span>时间</span><strong>{formatDate(event.occurred_start)} — {formatDate(event.occurred_end)}</strong></div>{event.location_name && <div><span>地点</span><strong>{event.location_name}</strong></div>}<div><span>领域包</span><Tag tone="knowledge">{domainPackLabel(event.domain_pack_key)}</Tag></div><TechnicalDetails summary="领域包技术标识"><Mono>{event.domain_pack_key}</Mono></TechnicalDetails></div>
          </Card>
        </div>
      </div>
    </div>
  );
}
