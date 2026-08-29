import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiContractError, apiRequest } from './client';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('apiRequest success contract', () => {
  it('returns data only after validating the response envelope', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      data: { id: 'evt_01' },
      meta: { request_id: 'req_body_01' },
    })));

    await expect(apiRequest<{ id: string }>('/api/v1/events/evt_01'))
      .resolves.toEqual({ id: 'evt_01' });
  });

  it('rejects a successful response without data and prefers the body Request ID', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(
      { meta: { request_id: 'req_body_02' } },
      { 'X-ReasonWeave-Request-Id': 'req_header_02' },
    )));

    const error = await apiRequest('/api/v1/runtime').catch((value: unknown) => value);
    expect(error).toBeInstanceOf(ApiContractError);
    expect(error).toMatchObject({
      code: 'API_CONTRACT_INVALID',
      requestId: 'req_body_02',
      status: 200,
    });
  });

  it('rejects non-JSON success responses and keeps the response-header Request ID', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('not-json', {
      status: 200,
      headers: {
        'Content-Type': 'text/plain',
        'X-ReasonWeave-Request-Id': 'req_header_03',
      },
    })));

    const error = await apiRequest('/api/v1/runtime').catch((value: unknown) => value);
    expect(error).toBeInstanceOf(ApiContractError);
    expect(error).toMatchObject({ requestId: 'req_header_03', status: 200 });
  });

  it('rejects a success envelope with an invalid meta Request ID', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(
      { data: {}, meta: { request_id: '' } },
      { 'X-ReasonWeave-Request-Id': 'req_header_04' },
    )));

    const error = await apiRequest('/api/v1/runtime').catch((value: unknown) => value);
    expect(error).toBeInstanceOf(ApiContractError);
    expect(error).toMatchObject({ requestId: 'req_header_04', status: 200 });
  });
});

function jsonResponse(body: unknown, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
}
