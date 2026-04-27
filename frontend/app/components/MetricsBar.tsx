'use client';

import { PortfolioSummary } from '@/app/lib/api';
import { PriceUpdate } from '@/app/hooks/useWebSocket';

interface Props {
  summary: PortfolioSummary | null;
  connected: boolean;
  prices?: Record<string, PriceUpdate>;
}

function fmt(n: number | null | undefined, decimals = 2, prefix = '$') {
  if (n == null) return '—';
  const sign = n >= 0 ? '+' : '';
  return `${prefix}${sign}${n.toFixed(decimals)}`;
}

function pnlColor(n: number | null | undefined) {
  if (n == null) return 'text-zinc-400';
  if (n > 0) return 'text-emerald-400';
  if (n < 0) return 'text-red-400';
  return 'text-zinc-400';
}

function marginColor(pct: number) {
  if (pct > 80) return 'text-red-400';
  if (pct > 60) return 'text-amber-400';
  return 'text-emerald-400';
}

export default function MetricsBar({ summary, connected, prices }: Props) {
  const s = summary;
  const priceEntries = Object.values(prices ?? {});
  const marketClosed = connected && priceEntries.length > 0 &&
    priceEntries.every(p => p.source === 'STALE' || p.source === 'FALLBACK_DB');

  // Any instrument currently being served from the DB fallback (IBKR farm
  // degraded or market closed mid-session). Surfaced as per-ticker badges so
  // the trader sees exactly which instrument is stale, not just a global flag.
  const fallbackInstruments = priceEntries
    .filter(p => p.source === 'FALLBACK_DB' || p.source === 'STALE')
    .map(p => ({ instrument: p.instrument, source: p.source ?? 'FALLBACK_DB' }));

  let statusDot = 'bg-red-500';
  let statusText = 'STREAM OFFLINE';
  if (connected && marketClosed) {
    statusDot = 'bg-amber-400';
    statusText = 'MARKET CLOSED';
  } else if (connected) {
    statusDot = 'bg-emerald-400';
    statusText = 'STREAM LIVE';
  }

  return (
    <div className="flex flex-wrap items-center gap-4 px-4 py-3 bg-zinc-900 border-b border-zinc-700">
      {/* Connection status */}
      <div className="flex items-center gap-1.5 mr-2">
        <span className={`w-2 h-2 rounded-full ${statusDot}`} />
        <span className="text-xs text-zinc-500">{statusText}</span>
      </div>

      {/* Source badges — only rendered for instruments that are NOT LIVE.
          LIVE stays silent to avoid clutter; the dot above already signals it. */}
      {fallbackInstruments.length > 0 && (
        <div className="flex items-center gap-1.5 mr-2">
          {fallbackInstruments.map(({ instrument, source }) => (
            <span
              key={instrument}
              title={source === 'STALE'
                ? `${instrument}: price stream stale — no fresh IBKR ticks`
                : `${instrument}: DB fallback — live IBKR unavailable`}
              className="px-1.5 py-0.5 rounded border text-[10px] font-mono font-semibold uppercase tracking-wide border-amber-700 bg-amber-950/60 text-amber-300"
            >
              {instrument} · {source === 'STALE' ? 'STALE' : 'DB'}
            </span>
          ))}
        </div>
      )}

      <Metric label="Unrealized P&L"
        value={fmt(s?.totalUnrealizedPnL)}
        valueClass={pnlColor(s?.totalUnrealizedPnL)} />

      <Metric label="Today Realized"
        value={fmt(s?.todayRealizedPnL)}
        valueClass={pnlColor(s?.todayRealizedPnL)} />

      <Metric label="Total P&L"
        value={fmt(s?.totalPnL)}
        valueClass={pnlColor(s?.totalPnL)} />

      <div className="w-px h-8 bg-zinc-700" />

      <Metric label="Open Positions" value={String(s?.openPositionCount ?? '—')} />

      <Metric label="Exposure"
        value={s?.totalExposure != null ? `$${(s.totalExposure / 1000).toFixed(1)}k` : '—'} />

      <Metric label="Margin Used"
        value={s?.marginUsedPct != null ? `${s.marginUsedPct.toFixed(1)}%` : '—'}
        valueClass={marginColor(s?.marginUsedPct ?? 0)} />
    </div>
  );
}

function Metric({ label, value, valueClass = 'text-white' }: {
  label: string; value: string; valueClass?: string;
}) {
  return (
    <div className="flex flex-col">
      <span className="text-[10px] font-medium uppercase tracking-wider text-zinc-500">{label}</span>
      <span className={`text-sm font-mono font-semibold ${valueClass}`}>{value}</span>
    </div>
  );
}
