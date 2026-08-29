import { useQuery } from '@tanstack/react-query';
import { ArrowLeft, Boxes, CheckCircle2, PackageCheck, ShieldAlert } from 'lucide-react';
import { Link, useParams } from 'react-router-dom';
import { queries } from '../api/queries';
import { Card, EmptyState, ErrorState, LoadingState, Metric, Mono, PageHeader, StatusTag, Tag, TechnicalDetails } from '../components/ui';
import { useDomainLabels } from '../shared/domainPresentationContext';
import { jsonNumber, jsonRecord, jsonRecordArray, jsonString } from '../shared/json';
import { readinessReasonLabel, vectorPolicyLabel } from '../shared/presentation';

export function DomainPacksPage() {
  const { eventTypeLabel } = useDomainLabels();
  const packs = useQuery(queries.domainPacks());
  if (packs.isPending) return <LoadingState label="正在读取领域包清单" />;
  if (packs.isError) return <ErrorState error={packs.error} onRetry={() => packs.refetch()} />;
  return (
    <div className="rw-stack">
      <PageHeader eyebrow="受控领域知识" title="领域包" description="清单来自真实 manifest、中文展示元数据与知识索引状态。" />
      <div className="rw-metrics"><Metric label="领域包" value={packs.data.length} /><Metric label="生产就绪" value={packs.data.filter((pack) => pack.ready).length} tone="brand" /><Metric label="原因假设" value={packs.data.reduce((sum, pack) => sum + pack.hypothesis_count, 0)} tone="hypothesis" /><Metric label="知识单元" value={packs.data.reduce((sum, pack) => sum + pack.unit_count, 0)} tone="info" /></div>
      {packs.data.length === 0 ? <Card><EmptyState title="没有领域包" description="当前运行目录没有可读取的领域包版本。" /></Card> : <div className="rw-domain-pack-grid">{packs.data.map((pack) => { const scopedKey = `${pack.key}/${pack.version}`; return <Card key={scopedKey} title={pack.name} eyebrow={`版本 ${pack.version}`} action={<Tag tone={pack.ready ? 'success' : 'warning'}>{pack.ready ? '生产链路就绪' : '索引未就绪'}</Tag>}><div className="rw-card__body rw-stack"><div className="rw-domain-pack-mark"><Boxes size={24} /></div><p className="rw-secondary">{pack.description || '当前版本未提供中文说明。'}</p><div className="rw-cluster">{pack.fixture_only && <Tag tone="warning">仅限开发夹具</Tag>}{!pack.production_allowed && <Tag tone="danger">不可用于正式环境</Tag>}<Tag tone="knowledge">EventIR {pack.compatible_eventir}</Tag><Tag tone="info">{vectorPolicyLabel(pack.vector_policy)}</Tag></div>{!pack.ready && <div className="rw-callout rw-callout--warning"><p>{pack.readiness_reasons.map(readinessReasonLabel).join('；')}</p></div>}<div className="rw-definition-list"><div><span>事件类型</span><strong>{pack.event_types.map((type) => eventTypeLabel(type, scopedKey)).join('、')}</strong></div><div><span>原因假设</span><strong>{pack.hypothesis_count}</strong></div><div><span>规则</span><strong>{pack.rule_count}</strong></div><div><span>文档</span><strong>{pack.document_count}</strong></div><div><span>知识单元</span><strong>{pack.unit_count}</strong></div></div><Link className="rw-button rw-button--secondary" to={`/domain-packs/${pack.key}/versions/${pack.version}`}>查看只读详情</Link><TechnicalDetails><div className="rw-definition-list"><div><span>领域包键</span><Mono>{pack.key}</Mono></div><div><span>指纹</span><Mono>{pack.fingerprint}</Mono></div><div><span>索引版本</span><Mono>{pack.knowledge_index_version ?? '未建立'}</Mono></div><div><span>展示语言</span><Mono>{pack.presentation_locale}</Mono></div></div></TechnicalDetails></div></Card>; })}</div>}
    </div>
  );
}

export function DomainPackDetailPage() {
  const { key = '', version = '' } = useParams();
  const { eventTypeLabel, predicateLabel } = useDomainLabels();
  const pack = useQuery(queries.domainPack(key, version));
  if (pack.isPending) return <LoadingState label="正在读取领域包版本" />;
  if (pack.isError) return <ErrorState error={pack.error} onRetry={() => pack.refetch()} />;
  const value = pack.data;
  const hypotheses = jsonRecordArray(jsonRecord(value.hypotheses).hypotheses);
  const rules = jsonRecordArray(jsonRecord(value.rules).rules);
  const presentationHypotheses = jsonRecord(jsonRecord(value.presentation).hypotheses);
  const retrieval = jsonRecord(value.retrieval_config);
  const fusion = jsonRecord(retrieval.fusion);
  const targetVersions = value.summary.supported_target_versions;
  const targetVersionLabel = jsonString(jsonRecord(value.presentation).target_version_label, '目标系统');
  const licenses = jsonRecordArray(jsonRecord(value.summary.licenses).components);
  return (
    <div className="rw-stack">
      <PageHeader eyebrow={`领域包版本 ${value.summary.version}`} title={value.summary.name} description={value.summary.description} actions={<Link className="rw-button rw-button--ghost" to={`/domain-packs`}><ArrowLeft size={15} />返回领域包</Link>} />
      {value.warnings.map((warning) => <div className="rw-callout rw-callout--warning" key={warning}><ShieldAlert size={17} /><p>{warning}</p></div>)}
      {!value.summary.ready && <div className="rw-callout rw-callout--warning"><ShieldAlert size={17} /><p>正式调查尚未就绪：{value.summary.readiness_reasons.map(readinessReasonLabel).join('；')}</p></div>}
      <div className="rw-grid rw-grid--sidebar">
        <div className="rw-stack">
          <Card title="受控原因假设" eyebrow={`${hypotheses.length} 个`}><div className="rw-domain-hypotheses">{hypotheses.map((hypothesis, index) => { const code = jsonString(hypothesis.code); const display = jsonRecord(presentationHypotheses[code]); return <article key={code || `hypothesis-${index}`}><span>H{index + 1}</span><div><h3>{jsonString(display.title, jsonString(hypothesis.title, '未命名假设'))}</h3><p>{jsonString(display.description, '当前版本未提供中文说明。')}</p><div className="rw-cluster">{rules.filter((rule) => jsonString(rule.hypothesis) === code).map((rule, ruleIndex) => { const predicate = jsonString(rule.predicate); return <Tag key={jsonString(rule.id, `${code}-${ruleIndex}`)} tone="info">{predicateLabel(predicate, `${key}/${version}`)}</Tag>; })}</div><TechnicalDetails summary="假设技术原值"><pre>{JSON.stringify(hypothesis, null, 2)}</pre></TechnicalDetails></div></article>; })}</div></Card>
          <Card title="事件与证据契约" eyebrow="领域包驱动"><div className="rw-card__body rw-stack">{value.event_definitions.map((definition) => { const enabledInputs = definition.evidence_inputs.filter((input) => input.enabled); const identityLabels = definition.identity_fields.map((name) => definition.presentation.fields.find((field) => field.name === name)?.label ?? name); const inputLabels: Record<string, string> = { observation_bundle: 'Observation Bundle', text: '文本', file: '文件', image: '图片' }; return <article key={definition.event_type}><strong>{eventTypeLabel(definition.event_type, `${key}/${version}`)}</strong><div className="rw-definition-list"><div><span>主调查对象</span><strong>{definition.presentation.subject_label}</strong></div><div><span>身份字段</span><strong>{identityLabels.join('、') || '未声明'}</strong></div><div><span>事件时间窗</span><strong>{definition.event_requirements.time_range === 'required' ? '必须提供完整开始与结束时间' : '可选'}</strong></div><div><span>证据入口</span><strong>{enabledInputs.map((input) => input.label ?? inputLabels[input.type] ?? input.type).join('、') || '无'}</strong></div></div><TechnicalDetails summary="事件定义技术原值"><pre>{JSON.stringify(definition, null, 2)}</pre></TechnicalDetails></article>; })}</div></Card>
          <Card title="知识索引状态" eyebrow="真实数据库投影"><div className="rw-card__body rw-definition-list"><div><span>状态</span><StatusTag status={value.summary.status} /></div><div><span>知识源</span><strong>{value.summary.knowledge_source_id ? '已建立' : '未建立'}</strong></div><div><span>文档</span><strong>{value.summary.document_count}</strong></div><div><span>知识单元</span><strong>{value.summary.unit_count}</strong></div>{value.summary.knowledge_source_id && <div><span>来源详情</span><Link to={`/knowledge/sources/${value.summary.knowledge_source_id}`}>打开知识源</Link></div>}</div></Card>
        </div>
        <div className="rw-stack">
          <Card title="兼容性门禁" eyebrow="校验"><div className="rw-card__body rw-stack"><div className="rw-runtime-row"><span><CheckCircle2 size={15} /> EventIR 契约</span><Tag tone="success">{value.summary.compatible_eventir}</Tag></div><div className="rw-runtime-row"><span><PackageCheck size={15} /> 来源追溯信息</span><Tag tone="success">已保留</Tag></div><div className="rw-runtime-row"><span>正式环境允许</span><strong>{value.summary.production_allowed ? '是' : '否'}</strong></div><div className="rw-runtime-row"><span>正式调查就绪</span><strong>{value.summary.ready ? '是' : '否'}</strong></div>{targetVersions?.minimum && <div className="rw-runtime-row"><span>目标系统版本</span><strong>{targetVersionLabel} {targetVersions.minimum} 至 {targetVersions.maximum_exclusive ?? '未限定'}（不含）</strong></div>}</div></Card>
          <Card title="许可证与上游" eyebrow="组件级追溯"><div className="rw-card__body rw-stack">{licenses.map((license, index) => <article key={jsonString(license.scope, `license-${index}`)}><strong>{jsonString(license.scope, '未命名组件')}</strong><div className="rw-cluster"><Tag tone="knowledge">{jsonString(license.license, '未记录')}</Tag>{license.modified === true && <Tag>已修改</Tag>}</div><p className="rw-muted">上游版本：{jsonString(license.revision, '未记录')}</p><TechnicalDetails summary="上游来源"><Mono>{jsonString(license.source, '未记录')}</Mono></TechnicalDetails></article>)}</div></Card>
          <Card title="检索配置" eyebrow="只读"><div className="rw-card__body rw-definition-list"><div><span>全文检索候选</span><strong>{jsonNumber(retrieval.keyword_top_k, 20)}</strong></div><div><span>向量候选</span><strong>{jsonNumber(retrieval.vector_top_k, 20)}</strong></div><div><span>最终选择</span><strong>{jsonNumber(retrieval.final_top_k, 6)}</strong></div><div><span>RRF 参数</span><Mono>k={jsonNumber(fusion.k, 60)}</Mono></div></div></Card>
          <TechnicalDetails summary="完整领域包技术详情"><pre>{JSON.stringify(value, null, 2)}</pre></TechnicalDetails>
        </div>
      </div>
    </div>
  );
}
