import { describe, expect, it } from 'vitest';
import {
  auditActionLabel,
  applicabilityReasonLabel,
  evidenceSourceLabel,
  auditFieldLabel,
  auditResourceLabel,
  groundingStatusLabel,
  knowledgeUnitLabel,
  pipelineStepLabel,
  relationLabel,
  sourceLicenseLabel,
  statusLabel,
} from './presentation';

describe('中文展示词典', () => {
  it('将产品状态和技术枚举转换为中文', () => {
    expect(statusLabel('COMPLETED')).toBe('已完成');
    expect(evidenceSourceLabel('USER_TEXT')).toBe('用户录入');
    expect(relationLabel('STRONGLY_SUPPORTS')).toBe('强支持');
    expect(pipelineStepLabel('KNOWLEDGE_CONTEXT')).toBe('知识上下文');
  });

  it('不会把未知原始枚举直接暴露到普通页面', () => {
    expect(statusLabel('NEW_BACKEND_STATE')).toBe('未识别状态');
    expect(evidenceSourceLabel('new_domain_source')).toBe('其他来源');
  });

  it('将审计技术字段和英语知识标题转换为中文主显示', () => {
    expect(auditResourceLabel('investigation_run')).toBe('调查运行');
    expect(auditFieldLabel('knowledge_index_version')).toBe('知识索引版本');
    expect(knowledgeUnitLabel('expected_evidence', 'Expected evidence')).toBe('预期证据');
    expect(knowledgeUnitLabel('unknown', 'Unknown heading')).toBe('知识章节（英语原文）');
  });

  it('展示新增失败和中断审计动作的中文语义', () => {
    expect(auditActionLabel('evidence.processing_failed')).toBe('证据处理失败');
    expect(auditActionLabel('investigation.interrupted')).toBe('调查运行已中断');
  });

  it('展示知识适用性、依据状态和开源许可', () => {
    expect(applicabilityReasonLabel('EVENT_AND_CONTEXT_MATCH')).toBe('事件类型与上下文均匹配');
    expect(applicabilityReasonLabel('EVENT_MATCH')).toBe('事件类型匹配');
    expect(groundingStatusLabel('LIMITED')).toBe('知识依据有限');
    expect(sourceLicenseLabel('Apache-2.0')).toBe('Apache-2.0 开源许可');
  });
});
