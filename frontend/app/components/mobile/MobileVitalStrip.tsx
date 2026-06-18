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
  return `${sign}$${Math.abs(n).toLocaleString('en-US', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  })}`;
}

function pnlColor(n: number | null | undefined, mutedWhenOffline = false) {
  if (n == null) return 'text-zinc-400';
  if (mutedWhenOffline) return 'text-zinc-400';
  if (n > 0) return 'text-emerald-400';
  if (n < 0) return 'text-red-400';
  return 'text-zinc-400';
}

/**
 * Mobile-only vital strip: hero total P&L (24px) + compact sub-line always
 * visible; the four secondary metrics expand on demand. Replaces the desktop
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
    : status === 'closed' ? 'bg-amber-400' : 'bg-red-500';
  const statusLabel = status === 'live' ? 'LIVE' : status === 'closed' ? 'MARCHÉ FERMÉ' : 'HORS LIGNE';

  const openCount = s?.openPositionCount ?? 0;

  return (
    <div className="sticky top-0 z-30 bg-zinc-900/95 backdrop-blur border-b border-zinc-800">
      <button
        onClick={() => setExpanded(v => !v)}
        aria-expanded={expanded}
        aria-controls="mobile-vital-strip-details"
        className="w-full flex items-center gap-3 px-4 py-2 text-left"
      >
        <div className="flex-1 min-w-0">
          {/* Row 1 — Hero P&L */}
          <div
            className={`font-mono tabular-nums text-2xl font-bold leading-none ${pnlColor(s?.totalPnL, status === 'offline')}`}
          >
            {fmt(s?.totalPnL)}
          </div>
          {/* Row 2 — sub-line */}
          <div className="text-[11px] text-zinc-500 mt-1 flex items-center gap-1.5">
            <span className={`w-1.5 h-1.5 rounded-full flex-shrink-0 ${dotClass}`} />
            <span className="truncate">
              {openCount} {openCount === 1 ? 'position' : 'positions'} · {statusLabel}
            </span>
          </div>
        </div>
        <span className="min-w-[44px] min-h-[44px] inline-flex items-center justify-center text-zinc-500 flex-shrink-0">
          <ChevronDownIcon className={`transition-transform ${expanded ? 'rotate-180' : ''}`} />
        </span>
      </button>

      {expanded && (
        <div
          id="mobile-vital-strip-details"
          className="grid grid-cols-2 gap-2 mt-3 mx-4 mb-3 p-3 bg-zinc-950/50 border border-zinc-800 rounded-lg"
        >
          <div className="flex flex-col">
            <span className="text-[10px] uppercase tracking-widest text-zinc-500">Non réalisé</span>
            <span className={`font-mono tabular-nums text-sm font-medium ${pnlColor(s?.totalUnrealizedPnL, status === 'offline')}`}>
              {fmt(s?.totalUnrealizedPnL)}
            </span>
          </div>
          <div className="flex flex-col">
            <span className="text-[10px] uppercase tracking-widest text-zinc-500">Réalisé jour</span>
            <span className={`font-mono tabular-nums text-sm font-medium ${pnlColor(s?.todayRealizedPnL)}`}>
              {fmt(s?.todayRealizedPnL)}
            </span>
          </div>
          <div className="flex flex-col">
            <span className="text-[10px] uppercase tracking-widest text-zinc-500">Exposition</span>
            <span className="font-mono tabular-nums text-sm font-medium text-zinc-300">
              {s?.totalExposure != null ? `$${(s.totalExposure / 1000).toFixed(1)}k` : '—'}
            </span>
          </div>
          <div className="flex flex-col">
            <span className="text-[10px] uppercase tracking-widest text-zinc-500">Marge</span>
            <span className={`font-mono tabular-nums text-sm font-medium ${
              (s?.marginUsedPct ?? 0) > 80 ? 'text-red-400' : (s?.marginUsedPct ?? 0) > 60 ? 'text-amber-400' : 'text-emerald-400'
            }`}>
              {s?.marginUsedPct != null ? `${s.marginUsedPct.toFixed(1)}%` : '—'}
            </span>
          </div>
        </div>
      )}

      {status === 'offline' && (
        <div className="flex items-center gap-2 px-4 py-1.5 bg-amber-950/60 border-t border-amber-900">
          <span className="text-xs text-amber-300">Flux temps réel interrompu — reconnexion automatique…</span>
        </div>
      )}
    </div>
  );
}
