import { z } from 'zod';
import type { EventIr } from '../api/types';
import { jsonRecord, jsonString } from '../shared/json';

const subjectValueSchema = z.union([z.string(), z.number(), z.boolean()]);

export const eventFormSchema = z.object({
  title: z.string().trim().min(2, '至少输入 2 个字符').max(200),
  description: z.string().trim().max(2000).optional(),
  domainPackKey: z.string().trim().min(1, '请选择领域包'),
  eventType: z.string().trim().min(1, '请选择事件类型'),
  referenceCode: z.string().trim().max(32).optional(),
  occurredStart: z.string().optional(),
  occurredEnd: z.string().optional(),
  locationName: z.string().trim().max(300).optional(),
  latitude: z.string().optional(),
  longitude: z.string().optional(),
  subjectAttributes: z.record(z.string(), subjectValueSchema),
}).superRefine((value, context) => {
  if (value.occurredStart && value.occurredEnd && new Date(value.occurredEnd) <= new Date(value.occurredStart)) {
    context.addIssue({ code: 'custom', path: ['occurredEnd'], message: '结束时间必须晚于开始时间' });
  }
  const latitude = value.latitude ? Number(value.latitude) : undefined;
  const longitude = value.longitude ? Number(value.longitude) : undefined;
  if (latitude != null && (Number.isNaN(latitude) || latitude < -90 || latitude > 90)) {
    context.addIssue({ code: 'custom', path: ['latitude'], message: '纬度范围为 -90 到 90' });
  }
  if (longitude != null && (Number.isNaN(longitude) || longitude < -180 || longitude > 180)) {
    context.addIssue({ code: 'custom', path: ['longitude'], message: '经度范围为 -180 到 180' });
  }
});

export type EventFormValues = z.infer<typeof eventFormSchema>;

export type EventDefinitionInput = {
  subjectType: string;
  labelTemplate: string;
  attributesSchema: unknown;
};

export function normalizeSubjectAttributes(
  values: Record<string, string | number | boolean> | undefined,
  attributesSchema: unknown,
): Record<string, unknown> {
  const properties = jsonRecord(jsonRecord(attributesSchema).properties);
  const normalized: Record<string, unknown> = {};
  for (const [field, raw] of Object.entries(values ?? {})) {
    if (raw === '' || raw == null) continue;
    const property = jsonRecord(properties[field]);
    const type = jsonString(property.type, 'string');
    if (type === 'number' || type === 'integer') {
      const number = typeof raw === 'number' ? raw : Number(raw);
      normalized[field] = number;
    } else if (type === 'boolean') {
      normalized[field] = raw === true || raw === 'true';
    } else {
      normalized[field] = String(raw);
    }
  }
  return normalized;
}

export function buildEventIr(
  value: Partial<EventFormValues>,
  definition: EventDefinitionInput,
): EventIr {
  const occurred = value.occurredStart || value.occurredEnd ? {
    ...(value.occurredStart ? { start: new Date(value.occurredStart).toISOString() } : {}),
    ...(value.occurredEnd ? { end: new Date(value.occurredEnd).toISOString() } : {}),
  } : undefined;
  const location = value.locationName || value.latitude || value.longitude ? {
    ...(value.locationName ? { name: value.locationName } : {}),
    ...(value.latitude ? { latitude: Number(value.latitude) } : {}),
    ...(value.longitude ? { longitude: Number(value.longitude) } : {}),
  } : undefined;
  const attributes = normalizeSubjectAttributes(value.subjectAttributes, definition.attributesSchema);
  const label = definition.labelTemplate.replace(/\{([a-z_][a-z0-9_]*)\}/g, (_match, field: string) =>
    String(attributes[field] ?? ''),
  );
  return {
    schema_version: 'eventir/0.1',
    event: {
      type: value.eventType ?? '',
      title: value.title ?? '',
      ...(value.description ? { description: value.description } : {}),
      ...(value.referenceCode ? { reference_code: value.referenceCode } : {}),
      domain_pack: value.domainPackKey ?? '',
      ...(occurred ? { occurred_at: occurred } : {}),
      ...(location ? { location } : {}),
    },
    subjects: [{
      id: 'subj_primary',
      type: definition.subjectType,
      label,
      attributes,
    }],
    claims: [], evidence: [], observations: [], hypotheses: [], contradictions: [], unknowns: [],
  };
}
