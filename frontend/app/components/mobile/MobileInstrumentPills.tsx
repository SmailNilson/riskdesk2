'use client';

import { useEffect, useRef, useState } from 'react';
import { PriceUpdate } from '@/app/hooks/useWebSocket';

interface Props<T extends string> {
  instruments: readonly T[];
  active: T;
  onChange: (instrument: T) => void;
  prices: Record<string, PriceUpdate>;
}

function decimalsFor(instrument: string) {
  return instrument === 'E6' ? 5 : instrument === 'DXY' ? 3 : 2;
}

// Best-effort session indicator — heuristic only (no calendar/weekend gating); refine later.
function nyHour(): number {
  try {
    const h = new Intl.DateTimeFormat('en-US', {
      timeZone: 'America/New_York',
      hour: 'numeric',
      hour12: false,
    }).format(new Date());
    return parseInt(h, 10);
  } catch {
    return 12;
  }
}

type Session = 'RTH' | 'ETH' | 'CLOSED';

function sessionForInstrument(instrument: string): Session {
  const h = nyHour();
  if (instrument === 'MGC' || instrument === 'MCL' || instrument === 'MNQ') {
    if (h >= 9 && h < 16) return 'RTH';
    if (h === 17) return 'CLOSED'; // brief gap between sessions
    return 'ETH';
  }
  if (instrument === 'E6' || instrument === '6E' || instrument === 'DXY') {
    if (h >= 9 && h < 16) return 'RTH';
    return 'ETH';
  }
  return 'ETH';
}

function sessionDotColor(s: Session): string {
  return s === 'RTH' ? 'bg-emerald-400' : s === 'ETH' ? 'bg-amber-400' : 'bg-zinc-500';
}

function sessionLabel(s: Session): string {
  return s === 'RTH' ? 'Regular trading hours' : s === 'ETH' ? 'Extended trading hours' : 'Closed';
}

/**
 * Mobile instrument selector with the live price inside each pill — merges the
 * desktop ticker row and the instrument tab group into one swipeable strip.
 * Price color follows the last tick direction; stale/fallback prices go muted.
 * Horizontal scroll-snap keeps overflow swipeable; a session dot (RTH/ETH/CLOSED)
 * sits in each pill's top-right corner.
 */
export default function MobileInstrumentPills<T extends string>({ instruments, active, onChange, prices }: Props<T>) {
  const lastRef = useRef<Record<string, number>>({});
  const [dirs, setDirs] = useState<Record<string, 1 | -1 | 0>>({});

  useEffect(() => {
    const next: Record<string, 1 | -1 | 0> = {};
    let changed = false;
    for (const inst of instruments) {
      const p = prices[inst]?.price;
      if (p == null) continue;
      const last = lastRef.current[inst];
      if (last != null && p !== last) {
        next[inst] = p > last ? 1 : -1;
        changed = true;
      }
      lastRef.current[inst] = p;
    }
    if (changed) setDirs(prev => ({ ...prev, ...next }));
  }, [prices, instruments]);

  return (
    <div
      className="flex gap-2 overflow-x-auto snap-x snap-mandatory px-3 py-2 bg-zinc-900 border-b border-zinc-800 [&::-webkit-scrollbar]:hidden"
      style={{ scrollbarWidth: 'none' }}
      role="tablist"
      aria-label="Instruments"
    >
      {instruments.map(inst => {
        const p = prices[inst];
        const stale = p?.source === 'STALE' || p?.source === 'FALLBACK_DB';
        const dir = dirs[inst] ?? 0;
        const priceClass = stale
          ? 'text-zinc-500'
          : dir > 0 ? 'text-emerald-400' : dir < 0 ? 'text-red-400' : 'text-white';
        const isActive = inst === active;
        const session = sessionForInstrument(inst);
        const dotClass = sessionDotColor(session);
        return (
          <button
            key={inst}
            type="button"
            onClick={() => onChange(inst)}
            role="tab"
            aria-selected={isActive}
            aria-label={`${inst} — ${sessionLabel(session)}`}
            className={`min-w-[100px] flex-shrink-0 snap-start relative rounded-xl p-2.5 text-left ${
              isActive
                ? 'bg-emerald-500/15 border-2 border-emerald-500 scale-[1.02] shadow-lg shadow-emerald-500/10 transition-all'
                : 'bg-zinc-800 border border-zinc-800 hover:bg-zinc-700/50 transition-colors'
            }`}
          >
            <span
              aria-hidden="true"
              className={`absolute top-1.5 right-1.5 w-1.5 h-1.5 rounded-full ${dotClass}`}
            />
            <div className="text-[10px] font-semibold uppercase tracking-wider text-zinc-300 mb-0.5">
              {inst}
            </div>
            <div className={`text-base font-bold tabular-nums font-mono ${priceClass}`}>
              {p ? p.price.toFixed(decimalsFor(inst)) : '—'}
            </div>
          </button>
        );
      })}
    </div>
  );
}
