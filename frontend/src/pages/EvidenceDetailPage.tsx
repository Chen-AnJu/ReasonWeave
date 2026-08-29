import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, Check, RefreshCw, ShieldAlert, X } from 'lucide-react';
import { Link, useParams } from 'react-router-dom';
import { queries } from '../api/queries';
import { reasonweaveApi } from '../api/reasonweave';
import { Button, Card, EmptyState, ErrorState, LoadingState, Mono, PageHeader, Progress, StatusTag, TechnicalDetails } from '../components/ui';
import { useDomainLabels } from '../shared/domainPresentationContext';
import { formatDate } from '../shared/format';
import { evidenceSourceLabel, evidenceTypeLabel } from '../shared/presentation';

export function EvidenceDetailPage() {
  const { evidenceId = '' } = useParams();
  const { predicateLabel, sourceProfileLabel } = useDomainLabels();
  const queryClient = useQueryClient();
  const evidence = useQuery(queries.evidenceDetail(evidenceId));
  const invalidate = async () => {
    await queryClient.invalidateQueries({ queryKey: ['evidence-detail', evidenceId] });
    await queryClient.invalidateQueries({ queryKey: ['evidence'] });
  };
  const verify = useMutation({
    mutationFn: ({ id, version, status }: { id: string; version: number; status: string }) => reasonweaveApi.verifyObservation(id, version, status),
    onSuccess: invalidate,
  });
  const reprocess = useMutation({ mutationFn: () => reasonweaveApi.reprocessEvidence(evidenceId), onSuccess: invalidate });
  if (evidence.isPending) return <LoadingState label="正在读取证据详情" />;
  if (evidence.isError) return <ErrorState error={evidence.error} onRetry={() => evidence.refetch()} />;
  const detail = evidence.data;
  return (
    <div className="rw-stack">
      <PageHeader eyebrow="证据详情" title={detail.evidence.original_name ?? evidenceTypeLabel(detail.evidence.type)} description={`${evidenceTypeLabel(detail.evidence.type)} · ${sourceProfileLabel(detail.evidence.source, undefined, evidenceSourceLabel(detail.evidence.source))}`} actions={<><Link className="rw-button rw-button--ghost" to={`/evidence`}><ArrowLeft size={15} />返回证据库</Link><StatusTag status={detail.evidence.status} />{detail.evidence.content_type?.startsWith('image/') && <Button variant="secondary" onClick={() => reprocess.mutate()} disabled={reprocess.isPending}><RefreshCw size={15} />重新处理</Button>}</>} />
      {(verify.isError || reprocess.isError) && <div className="rw-callout rw-callout--danger"><ShieldAlert size={17} /><p>{verify.error?.message ?? reprocess.error?.message}</p></div>}
      <div className="rw-grid rw-grid--sidebar">
        <Card title="观察复核" eyebrow="人工复核">
          {detail.observations.length === 0 ? <EmptyState title="还没有观察结果" description="可以重新处理图片，或由人工补充结构化事实。" /> : <div className="rw-observations">{detail.observations.map((observation) => <article className="rw-observation" key={observation.id}><div className="rw-observation__header"><div><strong>{predicateLabel(observation.predicate)}</strong><p>{observation.description}</p></div><StatusTag status={observation.verification_status} /></div><div className="rw-observation__value"><span>结构化值</span><code>{JSON.stringify(observation.value)}</code></div><div className="rw-observation__confidence"><div><span>提取置信</span><strong>{Math.round(observation.model_confidence * 100)}%</strong></div><Progress value={observation.model_confidence} label={`${predicateLabel(observation.predicate)}的提取置信度`} /></div><TechnicalDetails><div className="rw-definition-list"><div><span>Predicate</span><Mono>{observation.predicate}</Mono></div><div><span>观察 ID</span><Mono>{observation.id}</Mono></div><div><span>版本</span><Mono>v{observation.version}</Mono></div></div></TechnicalDetails><div className="rw-observation__footer"><span>{formatDate(observation.updated_at)}</span><div className="rw-cluster"><Button variant="danger" disabled={verify.isPending || observation.verification_status === 'REJECTED'} onClick={() => verify.mutate({ id: observation.id, version: observation.version, status: 'REJECTED' })}><X size={14} />拒绝</Button><Button disabled={verify.isPending || observation.verification_status === 'CONFIRMED'} onClick={() => verify.mutate({ id: observation.id, version: observation.version, status: 'CONFIRMED' })}><Check size={14} />确认</Button></div></div></article>)}</div>}
        </Card>
        <div className="rw-stack">
          <Card title="完整性" eyebrow="来源与校验"><div className="rw-card__body rw-definition-list"><div><span>可靠性</span><strong>{Math.round(detail.evidence.reliability * 100)}%</strong></div><div><span>创建时间</span><strong>{formatDate(detail.evidence.created_at)}</strong></div></div><TechnicalDetails><div className="rw-definition-list"><div><span>证据 ID</span><Mono>{detail.evidence.id}</Mono></div><div><span>SHA-256</span><Mono>{detail.evidence.checksum_sha256 ?? '—'}</Mono></div><div><span>Blob Key</span><Mono>{detail.blob_key ?? '—'}</Mono></div><div><span>Content-Type</span><Mono>{detail.evidence.content_type ?? '—'}</Mono></div></div></TechnicalDetails></Card>
          <Card title="元数据" eyebrow="不可信输入"><div className="rw-code-panel"><pre>{JSON.stringify(detail.metadata, null, 2)}</pre></div></Card>
          <div className="rw-callout"><ShieldAlert size={17} /><p>模型输出必须先通过结构校验并经人工确认。拒绝或待复核的观察不参与调查评分。</p></div>
        </div>
      </div>
    </div>
  );
}
