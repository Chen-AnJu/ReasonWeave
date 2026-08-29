import { z } from 'zod';

const apiMetaSchema = z.object({
  request_id: z.string().min(1),
  schema_version: z.string().nullable().optional(),
}).passthrough();

export class ApiClientError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId?: string;
  readonly details?: Record<string, unknown>;

  constructor(status: number, payload: unknown, fallbackRequestId?: string) {
    const body = asRecord(payload);
    const error = asRecord(body.error);
    const meta = asRecord(body.meta);
    super(asString(error.message) || `请求失败（HTTP ${status}）`);
    this.name = 'ApiClientError';
    this.status = status;
    this.code = asString(error.code) || 'HTTP_ERROR';
    this.requestId = asString(meta.request_id) || fallbackRequestId || undefined;
    const details = asRecord(error.details);
    this.details = Object.keys(details).length > 0 ? details : undefined;
  }
}

export class ApiContractError extends Error {
  readonly status: number;
  readonly code = 'API_CONTRACT_INVALID';
  readonly requestId?: string;

  constructor(status: number, requestId: string | undefined, reason: string) {
    super(`接口成功响应不符合契约：${reason}`);
    this.name = 'ApiContractError';
    this.status = status;
    this.requestId = requestId;
  }
}

function asRecord(value: unknown): Record<string, unknown> {
  return value != null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
}

function asString(value: unknown) {
  return typeof value === 'string' ? value : '';
}

export async function apiRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers);
  headers.set('Accept', 'application/json');
  if (init?.body && !(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json');
  }
  const response = await fetch(path, { ...init, headers });
  const headerRequestId = response.headers.get('X-ReasonWeave-Request-Id') || undefined;
  let payload: unknown;
  try {
    payload = await response.json() as unknown;
  } catch {
    if (!response.ok) {
      throw new ApiClientError(response.status, null, headerRequestId);
    }
    throw new ApiContractError(response.status, headerRequestId, '响应不是有效 JSON');
  }
  if (!response.ok) {
    throw new ApiClientError(response.status, payload, headerRequestId);
  }
  const body = asRecord(payload);
  const bodyRequestId = asString(asRecord(body.meta).request_id) || undefined;
  const contractRequestId = bodyRequestId || headerRequestId;
  if (!Object.prototype.hasOwnProperty.call(body, 'data')) {
    throw new ApiContractError(response.status, contractRequestId, '缺少 data 字段');
  }
  const meta = apiMetaSchema.safeParse(body.meta);
  if (!meta.success) {
    throw new ApiContractError(response.status, contractRequestId, 'meta.request_id 缺失或无效');
  }
  return body.data as T;
}
