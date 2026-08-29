import { describe, expect, it } from 'vitest';
import type { DomainPackDetail } from '../api/types';
import { createDomainLabels } from './domainPresentationLabels';

const detail = {
  summary: {
    key: 'example-diagnostics',
    version: '1.2.3',
  },
  manifest: {
    source_profiles: {
      example_api: { label: 'Protocol label' },
    },
  },
  presentation: {
    name: '示例故障诊断',
    event_types: {
      example_failure: {
        label: '示例故障',
        subject_type: 'example_subject',
        subject_label: '示例调查对象',
      },
    },
    predicates: {
      example_predicate: '示例观察',
    },
    hypotheses: {
      example_hypothesis: {
        title: '示例原因',
        description: '由领域包提供的中文说明。',
      },
    },
    source_profiles: {
      example_api: '示例 API 状态',
    },
  },
} as unknown as DomainPackDetail;

describe('领域包展示元数据', () => {
  const labels = createDomainLabels([detail]);
  const scope = 'example-diagnostics/1.2.3';

  it('从指定领域包读取事件、Predicate、假设与来源中文标签', () => {
    expect(labels.domainPackLabel(scope)).toBe('示例故障诊断');
    expect(labels.eventTypeLabel('example_failure', scope)).toBe('示例故障');
    expect(labels.predicateLabel('example_predicate', scope)).toBe('示例观察');
    expect(labels.hypothesisLabel('example_hypothesis', undefined, scope)).toBe('示例原因');
    expect(labels.hypothesisDescription('example_hypothesis', undefined, scope)).toContain('领域包');
    expect(labels.sourceProfileLabel('example_api', scope)).toBe('示例 API 状态');
    expect(labels.subjectLabel('example_subject', 'example_failure', scope)).toBe('示例调查对象');
  });

  it('未知技术值不会直接泄漏到普通界面', () => {
    expect(labels.predicateLabel('unknown_predicate', scope)).toBe('未命名观察');
    expect(labels.hypothesisLabel('unknown_hypothesis', 'Raw English', scope)).toBe('未命名原因假设');
    expect(labels.sourceProfileLabel('unknown_source', scope)).toBe('领域采集来源');
  });
});
