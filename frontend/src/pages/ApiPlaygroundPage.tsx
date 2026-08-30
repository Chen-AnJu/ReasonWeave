import { useQuery } from '@tanstack/react-query';
import { Check, Clipboard, Code2, Play, Search, ShieldCheck, Terminal } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { queries } from '../api/queries';
import { Button, Card, EmptyState, ErrorState, Field, Input, LoadingState, Mono, PageHeader, Tag, Textarea } from '../components/ui';
import { buildCodeSample, codeLanguages, type CodeLanguage } from '../shared/apiSamples';

type Endpoint = {
  method: string;
  path: string;
  summary: string;
  tag: string;
  operation: JsonRecord;
  pathParameters: OpenApiParameter[];
  queryParameters: OpenApiParameter[];
  headerParameters: OpenApiParameter[];
  hasJsonBody: boolean;
  multipart: boolean;
};

type JsonRecord = Record<string, unknown>;

type OpenApiParameter = JsonRecord & {
  name: string;
  in: string;
  required: boolean;
  description?: string;
  example?: unknown;
  schema?: JsonRecord;
};

type RunResult = {
  status: number;
  statusText: string;
  duration: number;
  requestId?: string;
  payload: unknown;
};

const writeMethods = new Set(['POST', 'PATCH', 'PUT']);

export function ApiPlaygroundPage() {
  const openApi = useQuery(queries.openApi());
  const runtime = useQuery(queries.runtime());
  const [search, setSearch] = useState('');
  const endpoints = useMemo(() => openApi.data ? extractEndpoints(openApi.data) : [], [openApi.data]);
  const filtered = endpoints.filter((endpoint) => `${endpoint.method} ${endpoint.path} ${endpoint.summary}`.toLowerCase().includes(search.toLowerCase()));
  const [selectedKey, setSelectedKey] = useState('');
  const selected = endpoints.find((endpoint) => `${endpoint.method} ${endpoint.path}` === selectedKey) ?? endpoints[0];
  const [parameterValues, setParameterValues] = useState<Record<string, string>>({});
  const [headers, setHeaders] = useState<Record<string, string>>({});
  const [body, setBody] = useState('{}');
  const [language, setLanguage] = useState<CodeLanguage>('cURL');
  const [result, setResult] = useState<RunResult>();
  const [running, setRunning] = useState(false);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!selected) return;
    setParameterValues(Object.fromEntries([...selected.pathParameters, ...selected.queryParameters].map((parameter) => [
      parameter.name,
      String(parameter.example ?? parameter.schema?.example ?? ''),
    ])));
    setHeaders(Object.fromEntries(selected.headerParameters
      .filter((parameter) => ['Idempotency-Key', 'If-Match'].includes(parameter.name))
      .map((parameter) => [parameter.name, parameter.name === 'Idempotency-Key' ? crypto.randomUUID() : ''])));
    setBody(selected.hasJsonBody ? prettyExample(openApi.data, selected.operation) : '');
    setResult(undefined);
  }, [selected, openApi.data]);

  if (openApi.isPending || runtime.isPending) return <LoadingState label="正在读取实时 OpenAPI" />;
  if (openApi.isError || runtime.isError) return <ErrorState error={openApi.error ?? runtime.error} onRetry={() => { openApi.refetch(); runtime.refetch(); }} />;
  if (!selected) return <EmptyState title="没有可调试接口" description="实时 OpenAPI 中没有同源 /api/v1 接口。" />;

  const jsonError = selected.hasJsonBody ? validateJson(body) : '';
  const pathError = selected.pathParameters.find((parameter) => !parameterValues[parameter.name]?.trim());
  const headerError = selected.headerParameters.find((parameter) => (
    parameter.required
      && ['Idempotency-Key', 'If-Match'].includes(parameter.name)
      && !headers[parameter.name]?.trim()
  ));
  const disabledReason = selected.method === 'DELETE'
    ? 'DELETE 在开发调试台中默认禁用'
    : selected.multipart
      ? '调试台不支持 multipart 文件上传'
      : pathError
        ? `请填写路径参数 ${pathError.name}`
        : headerError
          ? `请填写请求头 ${headerError.name}`
        : jsonError;
  const request = buildRequest(selected, parameterValues, headers, body);
  const sample = jsonError
    ? '请先修正 JSON 请求体，再生成代码示例。'
    : buildCodeSample(language, window.location.origin, request.url, selected.method, request.headers, selected.hasJsonBody ? body : undefined);

  const execute = async () => {
    if (disabledReason) return;
    if (writeMethods.has(selected.method) && !window.confirm(`即将执行 ${selected.method} 写操作。是否继续？`)) return;
    setRunning(true);
    const start = performance.now();
    try {
      const response = await fetch(request.url, {
        method: selected.method,
        headers: request.headers,
        body: selected.hasJsonBody ? body : undefined,
      });
      const payload = await response.json().catch(() => null);
      setResult({
        status: response.status,
        statusText: response.statusText,
        duration: performance.now() - start,
        requestId: response.headers.get('X-ReasonWeave-Request-Id') ?? responseRequestId(payload),
        payload,
      });
    } catch (error) {
      setResult({ status: 0, statusText: '网络请求失败', duration: performance.now() - start, payload: { error: error instanceof Error ? error.message : String(error) } });
    } finally {
      setRunning(false);
    }
  };

  const copySample = async () => {
    await navigator.clipboard.writeText(sample);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1200);
  };

  return (
    <div className="rw-stack">
      <PageHeader eyebrow="开发者工具" title="API 调试台" description="接口清单来自实时 OpenAPI；只允许当前自托管实例的同源 /api/v1 请求。" />
      <div className="rw-callout"><ShieldCheck size={17} /><p>{runtime.data.instance_name || 'ReasonWeave'} 本地实例不提供用户身份。建议先读取领域包，再读取事件定义后构造 EventIR；调试台不接受任意 URL、API Key 或 multipart 文件上传。</p></div>
      <div className="rw-api-layout">
        <Card className="rw-api-sidebar" title="接口列表" eyebrow={`${endpoints.length} 个操作`}>
          <div className="rw-api-search"><Search size={15} /><Input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="搜索路径或说明" aria-label="搜索接口" /></div>
          <div className="rw-api-endpoints">{filtered.map((endpoint) => {
            const key = `${endpoint.method} ${endpoint.path}`;
            return <button key={key} className={key === `${selected.method} ${selected.path}` ? 'is-active' : ''} onClick={() => setSelectedKey(key)}><MethodTag method={endpoint.method} /><span><strong>{endpoint.summary}</strong><Mono>{endpoint.path}</Mono></span></button>;
          })}</div>
        </Card>

        <div className="rw-stack rw-api-main">
          <Card title={selected.summary} eyebrow={selected.tag} action={<MethodTag method={selected.method} />}>
            <div className="rw-card__body rw-stack">
              <div className="rw-api-path"><Mono>{selected.path}</Mono><Tag tone="info">同源 /api/v1</Tag></div>
              {selected.pathParameters.length > 0 && <section className="rw-api-parameter-grid"><h3>路径参数</h3>{selected.pathParameters.map((parameter) => <Field key={parameter.name} label={parameter.name} hint={parameter.description}><Input value={parameterValues[parameter.name] ?? ''} onChange={(event) => setParameterValues((current) => ({ ...current, [parameter.name]: event.target.value }))} required={parameter.required} /></Field>)}</section>}
              {selected.queryParameters.length > 0 && <section className="rw-api-parameter-grid"><h3>查询参数</h3>{selected.queryParameters.map((parameter) => <Field key={parameter.name} label={parameter.name} hint={parameter.description}><Input value={parameterValues[parameter.name] ?? ''} onChange={(event) => setParameterValues((current) => ({ ...current, [parameter.name]: event.target.value }))} /></Field>)}</section>}
              {Object.keys(headers).length > 0 && <section className="rw-api-parameter-grid"><h3>允许的请求头</h3>{Object.keys(headers).map((name) => <Field key={name} label={name}><Input value={headers[name]} onChange={(event) => setHeaders((current) => ({ ...current, [name]: event.target.value }))} /></Field>)}</section>}
              {selected.hasJsonBody && <Field label="JSON 请求体" error={jsonError || undefined} hint="仅校验 JSON 格式；业务契约由后端再次校验。"><Textarea className="rw-api-body" value={body} onChange={(event) => setBody(event.target.value)} spellCheck={false} /></Field>}
              {selected.multipart && <div className="rw-callout rw-callout--warning"><p>该接口使用 multipart；本调试台不会提供文件上传能力。</p></div>}
              <div className="rw-api-execute"><div><span>费用</span><strong>当前接口未提供</strong><small>显示值不是账单数据</small></div><Button onClick={execute} disabled={Boolean(disabledReason) || running}><Play size={15} />{running ? '正在请求' : '执行请求'}</Button></div>
              {disabledReason && <p className="rw-field__error">{disabledReason}</p>}
            </div>
          </Card>

          <div className="rw-grid rw-grid--2 rw-api-output-grid">
            <Card title="代码示例" eyebrow="固定同源请求" action={<Button variant="ghost" onClick={copySample}>{copied ? <Check size={15} /> : <Clipboard size={15} />}{copied ? '已复制' : '复制'}</Button>}>
              <div className="rw-code-tabs">{codeLanguages.map((value) => <button key={value} className={language === value ? 'is-active' : ''} onClick={() => setLanguage(value)}>{value}</button>)}</div>
              <pre className="rw-api-code"><code>{sample}</code></pre>
            </Card>
            <Card title="响应" eyebrow={result ? `${result.duration.toFixed(0)} 毫秒` : '尚未执行'} action={result && <Tag tone={result.status >= 200 && result.status < 300 ? 'success' : 'danger'}>{result.status || '网络错误'}</Tag>}>
              {!result ? <div className="rw-state rw-state--compact"><Terminal /><strong>等待执行</strong><p>响应状态、耗时和请求 ID 会显示在这里。</p></div> : <div className="rw-api-response"><div className="rw-definition-list"><div><span>状态</span><strong>{result.status} {result.statusText}</strong></div><div><span>耗时</span><strong>{result.duration.toFixed(1)} ms</strong></div><div><span>请求 ID</span>{result.requestId ? <Mono>{result.requestId}</Mono> : <span>未提供</span>}</div></div><pre>{JSON.stringify(result.payload, null, 2)}</pre></div>}
            </Card>
          </div>
        </div>
      </div>
    </div>
  );
}

function extractEndpoints(spec: unknown): Endpoint[] {
  const result: Endpoint[] = [];
  const document = asRecord(spec);
  for (const [path, pathValue] of Object.entries(asRecord(document.paths))) {
    if (!path.startsWith('/api/v1')) continue;
    const pathItem = asRecord(pathValue);
    for (const method of ['get', 'post', 'patch', 'put', 'delete']) {
      const operation = asRecord(pathItem[method]);
      if (Object.keys(operation).length === 0) continue;
      const parameters = [...asArray(pathItem.parameters), ...asArray(operation.parameters)]
        .map((value) => parameterFrom(spec, resolveRef(spec, value)))
        .filter((value): value is OpenApiParameter => value != null);
      const content = asRecord(resolveRef(spec, operation.requestBody).content);
      result.push({
        method: method.toUpperCase(),
        path,
        summary: asString(operation.summary) || `${method.toUpperCase()} ${path}`,
        tag: asString(asArray(operation.tags)[0]) || '未分组接口',
        operation,
        pathParameters: parameters.filter((parameter) => parameter.in === 'path'),
        queryParameters: parameters.filter((parameter) => parameter.in === 'query'),
        headerParameters: parameters.filter((parameter) => parameter.in === 'header'),
        hasJsonBody: Boolean(content['application/json']),
        multipart: Boolean(content['multipart/form-data']),
      });
    }
  }
  return result.sort((left, right) => left.path.localeCompare(right.path) || left.method.localeCompare(right.method));
}

function resolveRef(spec: unknown, value: unknown): JsonRecord {
  const candidate = asRecord(value);
  const ref = asString(candidate.$ref);
  if (!ref) return candidate;
  let current: unknown = spec;
  for (const part of ref.replace(/^#\//, '').split('/')) {
    current = asRecord(current)[part];
  }
  const resolved = asRecord(current);
  return Object.keys(resolved).length > 0 ? resolved : candidate;
}

function prettyExample(spec: unknown, operation: JsonRecord) {
  if (!spec) return '{}';
  const content = asRecord(resolveRef(spec, operation.requestBody).content);
  const media = asRecord(content['application/json']);
  if (media.example !== undefined) return JSON.stringify(media.example, null, 2);
  const schema = resolveRef(spec, media.schema);
  return JSON.stringify(schemaExample(spec, schema), null, 2);
}

function schemaExample(spec: unknown, value: unknown, depth = 0): unknown {
  if (!value || depth > 4) return {};
  const schema = resolveRef(spec, value);
  if (schema.example !== undefined) return schema.example;
  if (schema.default !== undefined) return schema.default;
  const enumerated = asArray(schema.enum);
  if (enumerated.length) return enumerated[0];
  if (schema.type === 'array') return [schemaExample(spec, schema.items, depth + 1)];
  if (schema.type === 'boolean') return false;
  if (schema.type === 'integer' || schema.type === 'number') return 0;
  if (schema.type === 'string') return '';
  return Object.fromEntries(Object.entries(asRecord(schema.properties)).map(([key, property]) => [key, schemaExample(spec, property, depth + 1)]));
}

function validateJson(value: string) {
  if (!value.trim()) return 'JSON 请求体不能为空';
  try { JSON.parse(value); return ''; } catch (error) { return error instanceof Error ? `JSON 格式错误：${error.message}` : 'JSON 格式错误'; }
}

function buildRequest(endpoint: Endpoint, values: Record<string, string>, headerValues: Record<string, string>, body: string) {
  let path = endpoint.path;
  for (const parameter of endpoint.pathParameters) path = path.replace(`{${parameter.name}}`, encodeURIComponent(values[parameter.name] ?? ''));
  const query = new URLSearchParams();
  for (const parameter of endpoint.queryParameters) if (values[parameter.name]) query.set(parameter.name, values[parameter.name]);
  const url = `${path}${query.size ? `?${query}` : ''}`;
  if (!url.startsWith('/api/v1/') && url !== '/api/v1') throw new Error('仅允许 /api/v1 同源请求');
  const requestHeaders: Record<string, string> = { Accept: 'application/json' };
  if (endpoint.hasJsonBody) requestHeaders['Content-Type'] = 'application/json';
  for (const [key, value] of Object.entries(headerValues)) if (value && ['Idempotency-Key', 'If-Match'].includes(key)) requestHeaders[key] = value;
  return { url, headers: requestHeaders, body };
}

function MethodTag({ method }: { method: string }) {
  const tone = method === 'GET' ? 'info' : method === 'DELETE' ? 'danger' : method === 'PATCH' ? 'warning' : 'success';
  return <Tag tone={tone}><Code2 size={11} />{method}</Tag>;
}

function parameterFrom(spec: unknown, value: JsonRecord): OpenApiParameter | null {
  const name = asString(value.name);
  const location = asString(value.in);
  if (!name || !location) return null;
  return {
    ...value,
    name,
    in: location,
    required: value.required === true,
    description: asString(value.description) || undefined,
    example: value.example,
    schema: resolveRef(spec, value.schema),
  };
}

function responseRequestId(payload: unknown) {
  return asString(asRecord(asRecord(payload).meta).request_id) || undefined;
}

function asRecord(value: unknown): JsonRecord {
  return value != null && typeof value === 'object' && !Array.isArray(value)
    ? value as JsonRecord
    : {};
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function asString(value: unknown): string {
  return typeof value === 'string' ? value : '';
}
