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

/**
 * Mobile instrument selector with the live price inside each pill — merges the
 * desktop ticker row and the instrument tab group into one ~52px-tall control.
 * Price color follows the last tick direction; stale/fallback prices go muted.
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
    <div className="flex gap-2">
      {instruments.map(inst => {
        const p = prices[inst];
        const stale = p?.source === 'STALE' || p?.source === 'FALLBACK_DB';
        const dir = dirs[inst] ?? 0;
        const priceClass = stale
          ? 'text-zinc-500'
          : dir > 0 ? 'text-emerald-400' : dir < 0 ? 'text-red-400' : 'text-zinc-200';
        const isActive = inst === active;
        return (
          <button
            key={inst}
            onClick={() => onChange(inst)}
            className={`flex-1 min-w-0 rounded-lg border px-1 py-1.5 min-h-[48px] transition-colors ${
              isActive
                ? 'border-zinc-500 bg-zinc-800'
                : 'border-zinc-800 bg-transparent'
            }`}
          >
            <div className={`text-xs font-medium ${isActive ? 'text-white' : 'text-zinc-500'}`}>{inst}</div>
            <div className={`font-mono tabular-nums text-xs ${priceClass}`}>
              {p ? p.price.toFixed(decimalsFor(inst)) : '—'}
            </div>
          </button>
        );
      })}
    </div>
  );
}
