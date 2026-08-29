export const codeLanguages = ['cURL', 'JavaScript', 'Python', 'Java'] as const;
export type CodeLanguage = (typeof codeLanguages)[number];

export function buildCodeSample(
  language: CodeLanguage,
  url: string,
  method: string,
  headers: Record<string, string>,
  bodyText?: string,
) {
  const body = bodyText ? JSON.parse(bodyText) as unknown : undefined;
  const serializedBody = body === undefined ? undefined : JSON.stringify(body);
  const headerLines = Object.entries(headers);
  if (language === 'cURL') {
    return [
      `curl --request ${method} ${quotePosix(url)}`,
      ...headerLines.map(([key, value]) => `  --header ${quotePosix(`${key}: ${value}`)}`),
      ...(serializedBody ? [`  --data ${quotePosix(serializedBody)}`] : []),
    ].join(' \\\n');
  }
  if (language === 'JavaScript') {
    const payload = body === undefined ? '' : `\n  body: JSON.stringify(${JSON.stringify(body, null, 2)}),`;
    return `const response = await fetch(${JSON.stringify(url)}, {\n  method: ${JSON.stringify(method)},\n  headers: ${JSON.stringify(headers, null, 2)},${payload}\n});\nconst payload = await response.json();`;
  }
  if (language === 'Python') {
    const payload = body === undefined ? '' : `\n    json=${pythonLiteral(body, 1)},`;
    return `import requests\n\nresponse = requests.${method.toLowerCase()}(\n    ${pythonLiteral(`http://127.0.0.1:18080${url}`)},\n    headers=${pythonLiteral(headers, 1)},${payload}\n)\nprint(response.status_code, response.json())`;
  }
  const payload = serializedBody
    ? `HttpRequest.BodyPublishers.ofString(${javaString(serializedBody)})`
    : 'HttpRequest.BodyPublishers.noBody()';
  return `var request = HttpRequest.newBuilder()\n    .uri(URI.create(${javaString(`http://127.0.0.1:18080${url}`)}))${headerLines.map(([key, value]) => `\n    .header(${javaString(key)}, ${javaString(value)})`).join('')}\n    .method(${javaString(method)}, ${payload})\n    .build();\nvar response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());`;
}

function quotePosix(value: string) {
  return `'${value.replaceAll("'", `'"'"'`)}'`;
}

function pythonLiteral(value: unknown, depth = 0): string {
  if (value === null) return 'None';
  if (value === true) return 'True';
  if (value === false) return 'False';
  if (typeof value === 'string') return JSON.stringify(value);
  if (typeof value === 'number') return Number.isFinite(value) ? String(value) : 'None';
  if (Array.isArray(value)) {
    if (value.length === 0) return '[]';
    return `[${value.map((item) => pythonLiteral(item, depth + 1)).join(', ')}]`;
  }
  if (typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>);
    if (entries.length === 0) return '{}';
    const indent = '    '.repeat(depth + 1);
    const closing = '    '.repeat(depth);
    return `{\n${entries.map(([key, item]) => `${indent}${JSON.stringify(key)}: ${pythonLiteral(item, depth + 1)}`).join(',\n')}\n${closing}}`;
  }
  throw new TypeError(`Unsupported JSON value: ${typeof value}`);
}

function javaString(value: string) {
  return `"${value
    .replaceAll('\\', '\\\\')
    .replaceAll('"', '\\"')
    .replaceAll('\r', '\\r')
    .replaceAll('\n', '\\n')
    .replaceAll('\t', '\\t')}"`;
}
