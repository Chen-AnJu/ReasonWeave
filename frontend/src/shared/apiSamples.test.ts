import { describe, expect, it } from 'vitest';
import { buildCodeSample, codeLanguages } from './apiSamples';

const body = JSON.stringify({
  statement: "it's true, but null stays text",
  nested: { enabled: true, empty: null, path: 'C:\\kubernetes\\"pod"' },
});
const headers = { Accept: 'application/json', 'X-Test': "operator's \\ value" };
const origin = 'http://127.0.0.1:8080';

describe('API code samples', () => {
  it('quotes cURL values without exposing shell delimiters', () => {
    const sample = buildCodeSample('cURL', origin, "/api/v1/events?query=seal's", 'POST', headers, body);
    expect(sample).toContain(`'"'"'`);
    expect(sample).toContain('--data');
  });

  it('keeps JSON strings intact in JavaScript and Python', () => {
    const javascript = buildCodeSample('JavaScript', origin, '/api/v1/events', 'POST', headers, body);
    const python = buildCodeSample('Python', origin, '/api/v1/events', 'POST', headers, body);
    expect(javascript).toContain("it's true, but null stays text");
    expect(python).toContain('"enabled": True');
    expect(python).toContain('"empty": None');
    expect(python).toContain('null stays text');
  });

  it('escapes Java string literals structurally', () => {
    const sample = buildCodeSample('Java', origin, '/api/v1/events', 'POST', headers, body);
    const serializedBody = JSON.stringify(JSON.parse(body));
    expect(sample).toContain('BodyPublishers.ofString');
    expect(sample).toContain(`BodyPublishers.ofString(${JSON.stringify(serializedBody)})`);
    expect(sample).toContain('operator\'s');
  });

  it('uses the current public origin in every language', () => {
    for (const language of codeLanguages) {
      const sample = buildCodeSample(language, origin, '/api/v1/runtime', 'GET', {}, undefined);
      expect(sample).toContain('http://127.0.0.1:8080/api/v1/runtime');
      expect(sample).not.toContain('18080');
    }
  });
});
