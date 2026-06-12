'use client';

import { useState } from 'react';
import { PortfolioSummary } from '@/app/lib/api';
import { PriceUpdate } from '@/app/hooks/useWebSocket';
import { ChevronDownIcon } from './TabIcons';

interface Props {
  summary: PortfolioSummary | null;
  connected: boolean;
  prices?: Record<string, PriceUpdate>;
}

function fmt(n: number | null | undefined, decimals = 2) {
  if (n == null) return '—';
  const sign = n >= 0 ? '+' : '−';
  return `${sign}$${Math.abs(n).toFixed(decimals)}`;
}

function pnlColor(n: number | null | undefined, mutedWhenOffline = false) {
  if (n == null) return 'text-zinc-400';
  if (mutedWhenOffline) return 'text-zinc-400';
  if (n > 0) return 'text-emerald-400';
  if (n < 0) return 'text-red-400';
  return 'text-zinc-400';
}

/**
 * Mobile-only vital strip: connection status + total P&L always visible on one
 * row; the four secondary metrics expand on demand. Replaces the desktop
 * MetricsBar + ticker pair below `lg` (the live prices moved into the
 * instrument selector pills).
 */
export default function MobileVitalStrip({ summary, connected, prices }: Props) {
  const [expanded, setExpanded] = useState(false);
  const s = summary;

  const priceEntries = Object.values(prices ?? {});
  const marketClosed = connected && priceEntries.length > 0 &&
    priceEntries.every(p => p.source === 'STALE' || p.source === 'FALLBACK_DB');

  const status: 'live' | 'closed' | 'offline' =
    !connected ? 'offline' : marketClosed ? 'closed' : 'live';

  const dotClass = status === 'live'
    ? 'bg-emerald-400 animate-pulse'
    : status === 'closed' ? 'bg-amber-400' : 'bg-zinc-500';
  const statusLabel = status === 'live' ? 'Live' : status === 'closed' ? 'Marché fermé' : 'Hors ligne';

  return (
    <div className="bg-zinc-900 border-b border-zinc-800">
      <button
        onClick={() => setExpanded(v => !v)}
        aria-expanded={expanded}
        className="w-full flex items-center gap-2.5 px-3 py-2 min-h-[44px] text-left"
      >
        <span className={`w-2 h-2 rounded-full flex-shrink-0 ${dotClass}`} />
        <span className="text-[11px] text-zinc-500 flex-shrink-0">{statusLabel}</span>
        <span className="w-px h-5 bg-zinc-800 flex-shrink-0" />
        <span className="text-[11px] text-zinc-500 flex-shrink-0">P&amp;L total</span>
        <span className={`font-mono tabular-nums text-[17px] font-semibold ${pnlColor(s?.totalPnL, status === 'offline')}`}>
          {fmt(s?.totalPnL)}
        </span>
        <span className={`font-mono tabular-nums text-xs ${pnlColor(s?.totalUnrealizedPnL, status === 'offline')}`}>
          unrl {fmt(s?.totalUnrealizedPnL)}
        </span>
        <ChevronDownIcon className={`ml-auto text-zinc-500 transition-transform ${expanded ? 'rotate-180' : ''}`} />
      </button>

      {expanded && (
        <div className="grid grid-cols-2 gap-x-5 gap-y-1.5 px-3 pb-2.5">
          <div className="flex justify-between items-baseline">
            <span className="text-[11px] text-zinc-500">Réalisé jour</span>
            <span className={`font-mono tabular-nums text-xs ${pnlColor(s?.todayRealizedPnL)}`}>{fmt(s?.todayRealizedPnL)}</span>
          </div>
          <div className="flex justify-between items-baseline">
            <span className="text-[11px] text-zinc-500">Positions</span>
            <span className="font-mono tabular-nums text-xs text-zinc-300">{s?.openPositionCount ?? '—'}</span>
          </div>
          <div className="flex justify-between items-baseline">
            <span className="text-[11px] text-zinc-500">Exposition</span>
            <span className="font-mono tabular-nums text-xs text-zinc-300">
              {s?.totalExposure != null ? `$${(s.totalExposure / 1000).toFixed(1)}k` : '—'}
            </span>
          </div>
          <div className="flex justify-between items-baseline">
            <span className="text-[11px] text-zinc-500">Marge</span>
            <span className={`font-mono tabular-nums text-xs ${
              (s?.marginUsedPct ?? 0) > 80 ? 'text-red-400' : (s?.marginUsedPct ?? 0) > 60 ? 'text-amber-400' : 'text-emerald-400'
            }`}>
              {s?.marginUsedPct != null ? `${s.marginUsedPct.toFixed(1)}%` : '—'}
            </span>
          </div>
        </div>
      )}

      {status === 'offline' && (
        <div className="flex items-center gap-2 px-3 py-1.5 bg-amber-950/60 border-t border-amber-900">
          <span className="text-xs text-amber-300">Flux temps réel interrompu — reconnexion automatique…</span>
        </div>
      )}
    </div>
  );
}
