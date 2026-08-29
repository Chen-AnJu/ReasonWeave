export function formatDate(value?: string) {
  if (!value) return '—';
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(new Date(value));
}

export function formatPercent(value?: number) {
  return value == null ? '—' : `${Math.round(value * 100)}%`;
}

export function truncateHash(value?: string, length = 12) {
  if (!value) return '—';
  return value.length <= length ? value : `${value.slice(0, length)}…`;
}

export function compactNumber(value: number) {
  return new Intl.NumberFormat('zh-CN', { notation: 'compact' }).format(value);
}
