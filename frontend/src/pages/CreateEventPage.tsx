import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Ajv2020 } from 'ajv/dist/2020.js';
import addMetaSchema2020 from 'ajv/dist/refs/json-schema-2020-12/index.js';
import addFormats from 'ajv-formats';
import { ArrowLeft, CheckCircle2, Code2, Save, XCircle } from 'lucide-react';
import { useEffect, useMemo } from 'react';
import { useForm, type Path } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import eventIrSchema from '../../../contracts/eventir/eventir-0.1.schema.json';
import { queries } from '../api/queries';
import { reasonweaveApi } from '../api/reasonweave';
import { Button, Card, EmptyState, ErrorState, Field, Input, LoadingState, PageHeader, Select, Tag, Textarea } from '../components/ui';
import { buildEventIr, eventFormSchema, type EventDefinitionInput, type EventFormValues } from '../schemas/eventForm';
import { useDomainLabels } from '../shared/domainPresentationContext';
import { jsonRecord } from '../shared/json';
import { readinessReasonLabel } from '../shared/presentation';

const ajv = new Ajv2020({ allErrors: true, strict: false });
if (!ajv.getSchema('https://json-schema.org/draft/2020-12/schema')) {
  addMetaSchema2020.call(ajv);
}
addFormats(ajv);
const validateEventIr = ajv.compile(eventIrSchema);

export function CreateEventPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { eventTypeLabel } = useDomainLabels();
  const packs = useQuery(queries.domainPacks());
  const form = useForm<EventFormValues>({
    resolver: zodResolver(eventFormSchema),
    mode: 'onChange',
    defaultValues: {
      domainPackKey: '', eventType: '', title: '', description: '', referenceCode: '',
      occurredStart: '', occurredEnd: '', locationName: '', latitude: '', longitude: '',
      subjectAttributes: {},
    },
  });
  const values = form.watch();
  const eligiblePacks = useMemo(
    () => packs.data?.filter((pack) => pack.production_allowed && !pack.fixture_only) ?? [],
    [packs.data],
  );
  const selectedPack = eligiblePacks.find((pack) => `${pack.key}/${pack.version}` === values.domainPackKey);
  const selectedKey = selectedPack?.key ?? '';
  const selectedVersion = selectedPack?.version ?? '';
  const eventTypeSupported = selectedPack?.event_types.includes(values.eventType) === true;
  const eventDefinition = useQuery({
    ...queries.domainPackEventType(selectedKey, selectedVersion, values.eventType),
    enabled: Boolean(selectedKey && selectedVersion && values.eventType && eventTypeSupported),
  });
  const attributesSchema = jsonRecord(eventDefinition.data?.attributes_schema);
  const presentationFields = eventDefinition.data?.presentation.fields ?? [];
  const definition: EventDefinitionInput = useMemo(() => ({
    subjectType: eventDefinition.data?.subject_type ?? '',
    labelTemplate: eventDefinition.data?.label_template ?? '',
    attributesSchema,
  }), [attributesSchema, eventDefinition.data?.label_template, eventDefinition.data?.subject_type]);
  const validateSubject = useMemo(() => {
    if (!definition.subjectType || !definition.labelTemplate || Object.keys(attributesSchema).length === 0) return null;
    try {
      return ajv.compile(attributesSchema);
    } catch {
      return null;
    }
  }, [attributesSchema, definition.labelTemplate, definition.subjectType]);

  useEffect(() => {
    if (selectedPack && !selectedPack.event_types.includes(values.eventType)) {
      form.setValue('eventType', selectedPack.event_types[0] ?? '', { shouldValidate: true });
    }
  }, [form, selectedPack, values.eventType]);
  useEffect(() => {
    form.setValue('subjectAttributes', {}, { shouldValidate: true });
  }, [form, selectedKey, selectedVersion, values.eventType]);

  const eventIr = useMemo(() => buildEventIr(values, definition), [definition, values]);
  const contractValid = validateEventIr(eventIr);
  const subjectAttributes = jsonRecord(eventIr.subjects[0]?.attributes);
  const subjectValid = validateSubject ? validateSubject(subjectAttributes) : false;
  const definitionReady = Boolean(eventDefinition.data && validateSubject);
  const timeRangeRequired = eventDefinition.data?.event_requirements.time_range === 'required';
  const timeRangeValid = !timeRangeRequired || Boolean(values.occurredStart && values.occurredEnd);
  const mutation = useMutation({
    mutationFn: reasonweaveApi.createEvent,
    onSuccess: async (created) => {
      await queryClient.invalidateQueries({ queryKey: ['events'] });
      navigate(`/events/${created.id}`);
    },
  });

  if (packs.isPending) return <LoadingState label="正在读取可用领域包" />;
  if (packs.isError) return <ErrorState error={packs.error} onRetry={() => packs.refetch()} />;
  if (eligiblePacks.length === 0) {
    return <Card><EmptyState title="没有可创建事件的生产领域包" description="请先安装并校验允许正式使用的领域包。" /></Card>;
  }

  return (
    <div className="rw-stack">
      <PageHeader
        eyebrow="EventIR / 0.1"
        title="创建事件"
        description="调查对象字段和证据能力由所选领域包声明；提交前执行 EventIR 与领域事件 Schema 双重校验。"
        actions={<Link className="rw-button rw-button--ghost" to="/events"><ArrowLeft size={15} />返回事件中心</Link>}
      />
      <form className="rw-grid rw-create-grid" onSubmit={form.handleSubmit((data) => mutation.mutate(buildEventIr(data, definition)))}>
        <div className="rw-stack">
          <Card title="基本信息" eyebrow="事件">
            <div className="rw-card__body rw-form-grid">
              <Field label="领域包" error={form.formState.errors.domainPackKey?.message} hint="来源于后端已安装并通过启动校验的只读领域包">
                <Select {...form.register('domainPackKey')}><option value="">请选择领域包</option>{eligiblePacks.map((pack) => <option key={`${pack.key}/${pack.version}`} value={`${pack.key}/${pack.version}`}>{pack.name} · {pack.version}</option>)}</Select>
              </Field>
              <Field label="事件类型" error={form.formState.errors.eventType?.message}>
                <Select {...form.register('eventType')} disabled={!selectedPack}><option value="">请选择事件类型</option>{(selectedPack?.event_types ?? []).map((type) => <option key={type} value={type}>{eventTypeLabel(type, values.domainPackKey)}</option>)}</Select>
              </Field>
              <Field label="事件标题" error={form.formState.errors.title?.message}><Input autoFocus placeholder="简要描述待调查事件" {...form.register('title')} /></Field>
              <Field label="外部事件编号" hint="留空时由服务端生成 EVT-*"><Input placeholder="EVT-2026-001" {...form.register('referenceCode')} /></Field>
              <Field label="事件描述" error={form.formState.errors.description?.message}><Textarea placeholder="描述已知事实，不预设原因或责任。" {...form.register('description')} /></Field>
            </div>
          </Card>

          <Card title={eventDefinition.data?.presentation.subject_label ?? '主调查对象'} eyebrow={eventDefinition.data?.presentation.label ?? '领域对象'}>
            <div className="rw-card__body rw-form-grid">
              {eventDefinition.isPending && <LoadingState label="正在读取领域事件定义" />}
              {eventDefinition.isError && <ErrorState error={eventDefinition.error} onRetry={() => eventDefinition.refetch()} />}
              {!eventDefinition.isPending && !eventDefinition.isError && presentationFields.map((field) => {
                const path = `subjectAttributes.${field.name}` as Path<EventFormValues>;
                const property = jsonRecord(jsonRecord(attributesSchema.properties)[field.name]);
                const minimum = typeof property.minimum === 'number' ? property.minimum : typeof property.exclusiveMinimum === 'number' ? property.exclusiveMinimum : undefined;
                const maximum = typeof property.maximum === 'number' ? property.maximum : typeof property.exclusiveMaximum === 'number' ? property.exclusiveMaximum : undefined;
                const step = property.type === 'integer' ? 1 : typeof property.multipleOf === 'number' ? property.multipleOf : 'any';
                return (
                  <Field key={field.name} label={field.label} hint={field.required ? '必填身份或对象属性' : undefined}>
                    {field.control === 'boolean' ? (
                      <Select {...form.register(path)}><option value="">请选择</option><option value="true">是</option><option value="false">否</option></Select>
                    ) : field.control === 'select' ? (
                      <Select {...form.register(path)}><option value="">请选择</option>{field.options.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</Select>
                    ) : (
                      <Input type={field.control === 'number' ? 'number' : 'text'} min={field.control === 'number' ? minimum : undefined} max={field.control === 'number' ? maximum : undefined} step={field.control === 'number' ? step : undefined} placeholder={field.placeholder || undefined} {...form.register(path)} />
                    )}
                  </Field>
                );
              })}
              {!eventDefinition.isPending && !eventDefinition.isError && presentationFields.length === 0 && (
                <EmptyState title="事件定义缺少表单字段" description="领域包展示元数据未声明可渲染字段。" />
              )}
            </div>
          </Card>

          <Card title="时间与地点" eyebrow={timeRangeRequired ? '当前事件要求完整时间窗' : '可选事件上下文'}>
            <div className="rw-card__body rw-form-grid rw-form-grid--2">
              <Field label={timeRangeRequired ? '发生开始时间（必填）' : '发生开始时间'}><Input type="datetime-local" required={timeRangeRequired} {...form.register('occurredStart')} /></Field>
              <Field label={timeRangeRequired ? '发生结束时间（必填）' : '发生结束时间'} error={form.formState.errors.occurredEnd?.message}><Input type="datetime-local" required={timeRangeRequired} {...form.register('occurredEnd')} /></Field>
              <Field label="地点名称"><Input {...form.register('locationName')} /></Field><div />
              <Field label="纬度" error={form.formState.errors.latitude?.message}><Input inputMode="decimal" {...form.register('latitude')} /></Field>
              <Field label="经度" error={form.formState.errors.longitude?.message}><Input inputMode="decimal" {...form.register('longitude')} /></Field>
            </div>
          </Card>

          {definitionReady && !subjectValid && <div className="rw-callout rw-callout--warning"><p>调查对象尚未通过领域 Schema：{validateSubject?.errors?.slice(0, 4).map((error) => `${error.instancePath || '/'} ${error.message}`).join('；')}</p></div>}
          {timeRangeRequired && !timeRangeValid && <div className="rw-callout rw-callout--warning"><p>当前领域事件要求填写完整的发生开始和结束时间；采集器只处理该时间窗内的数据。</p></div>}
          {selectedPack && !selectedPack.ready && <div className="rw-callout rw-callout--warning"><p>该领域包尚未达到正式调查就绪状态：{selectedPack.readiness_reasons.map(readinessReasonLabel).join('、')}。事件可以创建，但正式调查会被后端阻止。</p></div>}
          {mutation.isError && <div className="rw-callout rw-callout--danger"><p>{mutation.error.message}</p></div>}
          <div className="rw-form-actions"><Button type="submit" disabled={!form.formState.isValid || !contractValid || !subjectValid || !definitionReady || !timeRangeValid || mutation.isPending}><Save size={15} />{mutation.isPending ? '正在创建…' : '创建事件'}</Button></div>
        </div>
        <aside className="rw-sticky-panel">
          <Card title="实时 EventIR" eyebrow="数据契约" action={contractValid && subjectValid && timeRangeValid ? <Tag tone="success"><CheckCircle2 size={13} />双重校验有效</Tag> : <Tag tone="danger"><XCircle size={13} />契约无效</Tag>}>
            <div className="rw-code-panel"><div className="rw-code-panel__title"><Code2 size={14} />eventir/0.1</div><pre>{JSON.stringify(eventIr, null, 2)}</pre></div>
            {!contractValid && <div className="rw-schema-errors">{validateEventIr.errors?.slice(0, 5).map((error, index) => <div key={`${error.instancePath}-${index}`}><code>{error.instancePath || '/'}</code> {error.message}</div>)}</div>}
          </Card>
        </aside>
      </form>
    </div>
  );
}
