import { Ajv2020 } from 'ajv/dist/2020.js';
import addMetaSchema2020 from 'ajv/dist/refs/json-schema-2020-12/index.js';
import addFormats from 'ajv-formats';
import { describe, expect, it } from 'vitest';
import eventIrSchema from '../../../contracts/eventir/eventir-0.1.schema.json';
import { buildEventIr, eventFormSchema } from './eventForm';

describe('Event form contract', () => {
  it('builds an EventIR 0.1 payload accepted by the shared JSON Schema', () => {
    const form = eventFormSchema.parse({
      title: 'default/api Pod 镜像拉取失败',
      description: 'Kubernetes Pod 故障事件',
      domainPackKey: 'kubernetes-pod-diagnostics/1.0.0',
      eventType: 'kubernetes_pod_failure',
      referenceCode: 'EVT-TEST-001',
      occurredStart: '2026-08-20T08:00',
      occurredEnd: '2026-08-20T13:00',
      locationName: '中国四川成都',
      latitude: '30.5728',
      longitude: '104.0668',
      subjectAttributes: { namespace: 'default', pod_name: 'api-7d8f4c9b6-x2k9p' },
    });
    const eventIr = buildEventIr(form, {
      subjectType: 'kubernetes_pod',
      labelTemplate: '{namespace}/{pod_name}',
      attributesSchema: {
        type: 'object',
        required: ['namespace', 'pod_name'],
        properties: { namespace: { type: 'string' }, pod_name: { type: 'string' } },
      },
    });
    const ajv = new Ajv2020({ allErrors: true, strict: false });
    if (!ajv.getSchema('https://json-schema.org/draft/2020-12/schema')) {
      addMetaSchema2020.call(ajv);
    }
    addFormats(ajv);
    const validate = ajv.compile(eventIrSchema);

    expect(validate(eventIr), JSON.stringify(validate.errors)).toBe(true);
    expect(eventIr.event.location).toEqual(expect.objectContaining({ latitude: 30.5728, longitude: 104.0668 }));
    expect(eventIr.subjects).toHaveLength(1);
    expect(eventIr.subjects[0]).toEqual(expect.objectContaining({
      type: 'kubernetes_pod',
      label: 'default/api-7d8f4c9b6-x2k9p',
    }));
  });

  it('uses the same builder for a non-Kubernetes equipment domain', () => {
    const form = eventFormSchema.parse({
      title: '泵组温度异常',
      domainPackKey: 'equipment-fault-test/1.0.0',
      eventType: 'equipment_fault',
      subjectAttributes: { asset_id: 'pump-001', site: 'workshop-a' },
    });
    const eventIr = buildEventIr(form, {
      subjectType: 'equipment_asset',
      labelTemplate: '{asset_id}',
      attributesSchema: {
        type: 'object',
        required: ['asset_id'],
        properties: { asset_id: { type: 'string' }, site: { type: 'string' } },
      },
    });

    expect(eventIr.event.type).toBe('equipment_fault');
    expect(eventIr.subjects[0]).toEqual(expect.objectContaining({
      type: 'equipment_asset',
      label: 'pump-001',
      attributes: { asset_id: 'pump-001', site: 'workshop-a' },
    }));
  });

  it('rejects an inverted event time range before API submission', () => {
    const result = eventFormSchema.safeParse({
      title: '时间错误事件',
      domainPackKey: 'kubernetes-pod-diagnostics/1.0.0',
      eventType: 'kubernetes_pod_failure',
      occurredStart: '2026-08-20T13:00',
      occurredEnd: '2026-08-20T08:00',
      subjectAttributes: { namespace: 'default', pod_name: 'api-pod' },
    });
    expect(result.success).toBe(false);
  });
});
