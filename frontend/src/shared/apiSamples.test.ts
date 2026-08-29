import { describe, expect, it } from 'vitest';
import { buildCodeSample } from './apiSamples';

const body = JSON.stringify({
  statement: "it's true, but null stays text",
  nested: { enabled: true, empty: null, path: 'C:\\kubernetes\\"pod"' },
});
const headers = { Accept: 'application/json', 'X-Test': "operator's \\ value" };

describe('API code samples', () => {
  it('quotes cURL values without exposing shell delimiters', () => {
    const sample = buildCodeSample('cURL', "/api/v1/events?query=seal's", 'POST', headers, body);
    expect(sample).toContain(`'"'"'`);
    expect(sample).toContain('--data');
  });

  it('keeps JSON strings intact in JavaScript and Python', () => {
    const javascript = buildCodeSample('JavaScript', '/api/v1/events', 'POST', headers, body);
    const python = buildCodeSample('Python', '/api/v1/events', 'POST', headers, body);
    expect(javascript).toContain("it's true, but null stays text");
    expect(python).toContain('"enabled": True');
    expect(python).toContain('"empty": None');
    expect(python).toContain('null stays text');
  });

  it('escapes Java string literals structurally', () => {
    const sample = buildCodeSample('Java', '/api/v1/events', 'POST', headers, body);
    const serializedBody = JSON.stringify(JSON.parse(body));
    expect(sample).toContain('BodyPublishers.ofString');
    expect(sample).toContain(`BodyPublishers.ofString(${JSON.stringify(serializedBody)})`);
    expect(sample).toContain('operator\'s');
  });
});
