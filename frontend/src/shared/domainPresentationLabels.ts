import type { DomainPackDetail } from '../api/types';
import { jsonRecord, jsonString } from './json';

export type DomainLabels = ReturnType<typeof createDomainLabels>;

export function createDomainLabels(details: DomainPackDetail[]) {
  const definitions = new Map<string, Record<string, unknown>>();
  for (const detail of details) {
    definitions.set(
      `${detail.summary.key}/${detail.summary.version}`,
      jsonRecord(detail.presentation),
    );
  }

  const candidates = (scopedKey?: string) => {
    if (scopedKey) {
      const exact = definitions.get(scopedKey);
      if (exact) return [exact];
      const versions = [...definitions.entries()]
        .filter(([key]) => key.startsWith(`${scopedKey}/`))
        .map(([, value]) => value);
      if (versions.length > 0) return versions;
    }
    return [...definitions.values()];
  };

  const domainPackLabel = (scopedKey?: string, fallback?: string) => {
    const presentation = candidates(scopedKey)[0];
    return jsonString(presentation?.name, chineseFallback(fallback, '未命名领域包'));
  };
  const eventTypeLabel = (value?: string, scopedKey?: string, fallback?: string) => {
    for (const presentation of candidates(scopedKey)) {
      const label = jsonString(jsonRecord(jsonRecord(presentation.event_types)[value ?? '']).label);
      if (label) return label;
    }
    return chineseFallback(fallback, '未命名事件类型');
  };
  const predicateLabel = (value?: string, scopedKey?: string, fallback?: string) => {
    for (const presentation of candidates(scopedKey)) {
      const label = jsonString(jsonRecord(presentation.predicates)[value ?? '']);
      if (label) return label;
    }
    return chineseFallback(fallback, '未命名观察');
  };
  const hypothesisLabel = (code?: string, fallback?: string, scopedKey?: string) => {
    for (const presentation of candidates(scopedKey)) {
      const title = jsonString(jsonRecord(jsonRecord(presentation.hypotheses)[code ?? '']).title);
      if (title) return title;
    }
    return chineseFallback(fallback, '未命名原因假设');
  };
  const hypothesisDescription = (code?: string, fallback?: string, scopedKey?: string) => {
    for (const presentation of candidates(scopedKey)) {
      const description = jsonString(
        jsonRecord(jsonRecord(presentation.hypotheses)[code ?? '']).description,
      );
      if (description) return description;
    }
    return chineseFallback(fallback, '受控领域假设；原始说明可在技术详情中查看。');
  };
  const sourceProfileLabel = (value?: string, scopedKey?: string, fallback?: string) => {
    for (const presentation of candidates(scopedKey)) {
      const label = jsonString(jsonRecord(presentation.source_profiles)[value ?? '']);
      if (label) return label;
    }
    return chineseFallback(fallback, '领域采集来源');
  };
  const subjectLabel = (
    subjectType?: string,
    eventType?: string,
    scopedKey?: string,
    fallback?: string,
  ) => {
    for (const presentation of candidates(scopedKey)) {
      const eventTypes = jsonRecord(presentation.event_types);
      const entries = eventType ? [[eventType, eventTypes[eventType]]] : Object.entries(eventTypes);
      for (const [, raw] of entries) {
        const metadata = jsonRecord(raw);
        if (subjectType && jsonString(metadata.subject_type) !== subjectType) continue;
        const label = jsonString(metadata.subject_label);
        if (label) return label;
      }
    }
    return chineseFallback(fallback, '调查对象');
  };

  return {
    domainPackLabel,
    eventTypeLabel,
    predicateLabel,
    hypothesisLabel,
    hypothesisDescription,
    sourceProfileLabel,
    subjectLabel,
  };
}

function chineseFallback(value: string | undefined, fallback: string) {
  return value && /[\u3400-\u9fff]/u.test(value) ? value : fallback;
}
