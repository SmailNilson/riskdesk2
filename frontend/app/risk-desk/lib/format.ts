// Number / time formatting helpers.

// Pin to en-US so SSR (host OS locale) matches CSR (browser locale)
const LOCALE = 'en-US';

export function fmtNum(n: number | null | undefined, d = 2): string {
  if (n == null || Number.isNaN(n)) return '—';
  return n.toLocaleString(LOCALE, { minimumFractionDigits: d, maximumFractionDigits: d });
}

export function fmtMoney(n: number | null | undefined, d = 2): string {
  if (n == null) return '—';
  const sign = n < 0 ? '−' : '';
  return `${sign}$${Math.abs(n).toLocaleString(LOCALE, { minimumFractionDigits: d, maximumFractionDigits: d })}`;
}

export function fmtTime(t: number, fmt: 'HH:mm' | 'HH:mm:ss' = 'HH:mm:ss'): string {
  const d = new Date(t);
  const pad = (n: number) => String(n).padStart(2, '0');
  if (fmt === 'HH:mm') return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

export function fmtAgo(t: number, nowMs?: number): string {
  const now = nowMs ?? Date.now();
  const s = Math.max(0, Math.floor((now - t) / 1000));
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86400)}d ago`;
}
