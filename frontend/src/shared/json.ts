export type JsonRecord = Record<string, unknown>;

export function jsonRecord(value: unknown): JsonRecord {
  return value != null && typeof value === 'object' && !Array.isArray(value)
    ? value as JsonRecord
    : {};
}

export function jsonRecordArray(value: unknown): JsonRecord[] {
  return Array.isArray(value) ? value.map(jsonRecord) : [];
}

export function jsonString(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback;
}

export function jsonNumber(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

export function jsonStringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];
}
