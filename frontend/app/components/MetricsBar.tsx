'use client';

import { PortfolioSummary } from '@/app/lib/api';

interface Props {
  summary: PortfolioSummary | null;
  connected: boolean;
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

export default function MetricsBar({ summary, connected }: Props) {
  const s = summary;
  return (
    <div className="flex flex-wrap items-center gap-4 px-4 py-3 bg-zinc-900 border-b border-zinc-700">
      {/* Connection status */}
      <div className="flex items-center gap-1.5 mr-2">
        <span className={`w-2 h-2 rounded-full ${connected ? 'bg-emerald-400' : 'bg-red-500'}`} />
        <span className="text-xs text-zinc-500">{connected ? 'LIVE' : 'DISCONNECTED'}</span>
      </div>

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
