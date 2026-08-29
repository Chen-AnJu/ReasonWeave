type Labels = Readonly<Record<string, string>>;

const STATUS_LABELS: Labels = {
  DRAFT: '草稿',
  COLLECTING: '取证中',
  INVESTIGATING: '调查中',
  COMPLETED: '已完成',
  RUNNING: '运行中',
  FAILED: '失败',
  VERIFIED: '已确认',
  CONFIRMED: '已确认',
  REJECTED: '已拒绝',
  PENDING: '待复核',
  NEEDS_REVIEW: '待复核',
  NORMALIZED: '已标准化',
  EXTRACTING: '提取中',
  PUBLISHED: '已发布',
  PARSING: '解析中',
  ACTIVE: '有效',
  OPEN: '待处理',
  SUPPORTED: '较强支持',
  LEANING_SUPPORTED: '偏向支持',
  INCONCLUSIVE: '证据不足',
  CONTRADICTED: '偏向反驳',
};

const EVIDENCE_TYPE_LABELS: Labels = {
  TEXT: '文本',
  IMAGE: '图片',
  DOCUMENT: '文档',
  OBSERVATION_BUNDLE: '标准观察证据包',
};

const EVIDENCE_SOURCE_LABELS: Labels = {
  USER_TEXT: '用户录入',
  UPLOAD: '文件上传',
  FIXTURE: '开发夹具',
  INTEGRATION_TEST: '集成测试',
};

const INTENT_LABELS: Labels = {
  CAUSE_CANDIDATES: '原因候选',
  EXPECTED_EVIDENCE: '预期证据',
  INVESTIGATION_ACTIONS: '调查动作',
};

const RELATION_LABELS: Labels = {
  STRONGLY_SUPPORTS: '强支持',
  SUPPORTS: '支持',
  PARTIALLY_SUPPORTS: '部分支持',
  NEUTRAL: '中性',
  INSUFFICIENT: '信息不足',
  PARTIALLY_CONTRADICTS: '部分反驳',
  CONTRADICTS: '反驳',
  STRONGLY_CONTRADICTS: '强反驳',
  RELATES_TO: '关联',
  OBSERVED_FROM: '观察来源',
  EXPLAINS: '解释',
  GROUNDED_BY: '知识依据',
  MISSING_FOR: '待补证据',
};

const PIPELINE_LABELS: Labels = {
  QUERY_PLAN: '查询计划',
  KNOWLEDGE_CONTEXT: '知识上下文',
  GROUNDED_HYPOTHESIS: '有依据的原因假设',
  EXPECTED_EVIDENCE: '预期证据',
  EVIDENCE_RELATION: '证据关系',
  SCORE_COVERAGE: '评分与覆盖率',
  GAP: '证据缺口',
  NEXT_EVIDENCE: '下一步取证',
};

const LEVEL_LABELS: Labels = {
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
};

const ENVIRONMENT_LABELS: Labels = {
  DEVELOPMENT: '开发环境',
  TEST: '测试环境',
  PRODUCTION: '正式环境',
};

const NODE_TYPE_LABELS: Labels = {
  EVENT: '事件',
  SUBJECT: '调查对象',
  EVIDENCE: '证据',
  OBSERVATION: '观察',
  HYPOTHESIS: '原因假设',
  KNOWLEDGE: '领域知识',
  GAP: '证据缺口',
};

const ACTION_LABELS: Labels = {
  'event.created': '事件已创建',
  'evidence.created': '已添加文本证据',
  'evidence.uploaded': '已上传证据文件',
  'evidence.processing_failed': '证据处理失败',
  'evidence.reprocess_requested': '已请求重新处理',
  'evidence.reprocessed': '证据已重新处理',
  'evidence.bundle_imported': '已导入标准观察证据包',
  'observation.updated': '观察已复核',
  'investigation.started': '调查运行已开始',
  'retrieval.completed': '知识检索已完成',
  'hypotheses.generated': '原因假设已生成',
  'investigation.completed': '调查运行已完成',
  'investigation.failed': '调查运行失败',
  'investigation.interrupted': '调查运行已中断',
};

const AUDIT_RESOURCE_LABELS: Labels = {
  event: '事件',
  evidence: '证据',
  observation: '观察',
  investigation_run: '调查运行',
  retrieval_run: '检索运行',
  knowledge_source: '知识源',
  knowledge_unit: '知识单元',
  observation_bundle: '标准观察证据包',
};

const AUDIT_FIELD_LABELS: Labels = {
  pipeline: '调查流水线',
  hypotheses: '原因假设',
  event_version: '事件版本',
  next_evidence: '下一步取证',
  planner_version: '规划器版本',
  retrieval_run_id: '检索运行引用',
  evidence_snapshot: '证据快照',
  evidence_snapshot_hash: '证据快照校验值',
  knowledge_context_hash: '知识上下文校验值',
  knowledge_index_version: '知识索引版本',
  support_index_disclaimer: '支持指数说明',
  status: '状态',
  version: '版本',
  review_status: '复核状态',
};

const KNOWLEDGE_UNIT_LABELS: Labels = {
  applicability: '适用性',
  expected_evidence: '预期证据',
  discriminating_evidence: '区分性证据',
  retrieval_note: '检索说明',
  Applicability: '适用性',
  'Expected evidence': '预期证据',
  'Discriminating evidence': '区分性证据',
  'Retrieval note': '检索说明',
};

const SOURCE_LICENSE_LABELS: Labels = {
  'Apache-2.0': 'Apache-2.0 开源许可',
  'CC-BY-4.0': 'CC BY 4.0 知识内容许可',
  'Generated solely for software development and testing; not an industry standard or operational guide.': '仅用于软件开发与测试；不是行业标准或操作指南。',
};

const GROUNDING_STATUS_LABELS: Labels = {
  GROUNDED: '知识依据完整',
  LIMITED: '知识依据有限',
};

const APPLICABILITY_REASON_LABELS: Labels = {
  EVENT_AND_CONTEXT_MATCH: '事件类型与上下文均匹配',
  EVENT_MATCH: '事件类型匹配',
  UNSPECIFIED: '当前版本未记录',
};

const READINESS_REASON_LABELS: Labels = {
  KNOWLEDGE_NOT_INDEXED: '知识尚未建立索引',
  KNOWLEDGE_NOT_PUBLISHED: '知识索引尚未发布',
  KNOWLEDGE_UNITS_MISSING: '知识索引没有可用单元',
  VECTOR_INDEX_NOT_READY: '向量索引未覆盖全部知识单元',
  PRODUCTION_EMBEDDING_NOT_READY: '生产向量模型服务未就绪',
  DOMAIN_PACK_FINGERPRINT_MISMATCH: '领域包内容与索引记录不一致',
  INDEX_PROFILE_MISMATCH: '向量模型或索引配置已变化，需要重建索引',
  PRODUCTION_NOT_ALLOWED: '领域包未允许正式调查',
};

const VECTOR_POLICY_LABELS: Labels = {
  required: '必须使用向量检索',
  optional: '可选向量检索',
  disabled: '不使用向量检索',
};

function labelFrom(labels: Labels, value: string | null | undefined, fallback: string) {
  if (!value) return fallback;
  return labels[value] ?? fallback;
}

export const statusLabel = (value?: string) => labelFrom(STATUS_LABELS, value, '未识别状态');
export const evidenceTypeLabel = (value?: string) => labelFrom(EVIDENCE_TYPE_LABELS, value, '其他证据');
export const evidenceSourceLabel = (value?: string) => labelFrom(EVIDENCE_SOURCE_LABELS, value, '其他来源');
export const retrievalIntentLabel = (value?: string) => labelFrom(INTENT_LABELS, value, '其他检索意图');
export const relationLabel = (value?: string) => labelFrom(RELATION_LABELS, value, '其他关系');
export const pipelineStepLabel = (value?: string) => labelFrom(PIPELINE_LABELS, value, '其他步骤');
export const levelLabel = (value?: string) => labelFrom(LEVEL_LABELS, value?.toUpperCase(), '未评估');
export const environmentLabel = (value?: string) => labelFrom(ENVIRONMENT_LABELS, value?.toUpperCase(), '未知环境');
export const nodeTypeLabel = (value?: string) => labelFrom(NODE_TYPE_LABELS, value, '其他节点');
export const auditActionLabel = (value?: string) => labelFrom(ACTION_LABELS, value, '其他操作');
export const auditResourceLabel = (value?: string) => labelFrom(AUDIT_RESOURCE_LABELS, value, '其他资源');
export const auditFieldLabel = (value?: string) => labelFrom(AUDIT_FIELD_LABELS, value, '其他字段');
export const knowledgeUnitLabel = (topic?: string, fallback?: string) => {
  const known = (topic && KNOWLEDGE_UNIT_LABELS[topic]) || (fallback && KNOWLEDGE_UNIT_LABELS[fallback]);
  if (known) return known;
  return fallback && /[\u3400-\u9fff]/u.test(fallback) ? fallback : '知识章节（英语原文）';
};
export const sourceLicenseLabel = (value?: string) => {
  if (!value) return '当前版本未记录';
  return SOURCE_LICENSE_LABELS[value] ?? (/[\u3400-\u9fff]/u.test(value) ? value : '许可原文见技术详情');
};
export const groundingStatusLabel = (value?: string) => labelFrom(GROUNDING_STATUS_LABELS, value, '知识依据状态未知');
export const applicabilityReasonLabel = (value?: string) => labelFrom(APPLICABILITY_REASON_LABELS, value, '其他适用性原因');
export const readinessReasonLabel = (value?: string) => labelFrom(READINESS_REASON_LABELS, value, '其他就绪问题');
export const vectorPolicyLabel = (value?: string) => labelFrom(VECTOR_POLICY_LABELS, value, '向量策略未知');

export function relationTone(value?: string) {
  if (value?.includes('CONTRADICT')) return 'danger';
  if (value?.includes('SUPPORT')) return 'success';
  if (value === 'GROUNDED_BY') return 'knowledge';
  if (value === 'MISSING_FOR') return 'warning';
  return 'neutral';
}
