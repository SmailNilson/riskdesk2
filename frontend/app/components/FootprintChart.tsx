'use client';

import { useEffect, useState } from 'react';
import { useOrderFlow, FootprintBar, FootprintLevel } from '@/app/hooks/useOrderFlow';
import { api, isFootprintBar } from '@/app/lib/api';

interface FootprintChartProps {
  selectedInstrument?: string;
}

function LevelRow({ level, maxVolume }: { level: FootprintLevel; maxVolume: number }) {
  const totalVol = level.buyVolume + level.sellVolume;
  const buyPct = totalVol > 0 ? (level.buyVolume / totalVol) * 100 : 0;
  const barWidth = maxVolume > 0 ? (totalVol / maxVolume) * 100 : 0;

  return (
    <div className="flex items-center gap-1 text-[10px] font-mono h-5">
      {/* Sell volume */}
      <div className="w-12 text-right text-red-400">
        {level.sellVolume > 0 ? level.sellVolume : ''}
      </div>

      {/* Volume bar */}
      <div className="flex-1 relative h-4 bg-zinc-800/40 rounded-sm overflow-hidden">
        {/* Buy portion */}
        <div
          className="absolute left-0 top-0 bottom-0 bg-emerald-700/60 transition-all duration-300"
          style={{ width: `${barWidth * (buyPct / 100)}%` }}
        />
        {/* Sell portion stacked after buy */}
        <div
          className="absolute top-0 bottom-0 bg-red-700/60 transition-all duration-300"
          style={{
            left: `${barWidth * (buyPct / 100)}%`,
            width: `${barWidth * ((100 - buyPct) / 100)}%`,
          }}
        />
        {/* Delta label */}
        <span className={`absolute inset-0 flex items-center justify-center text-[9px] ${
          level.delta > 0 ? 'text-emerald-300' : level.delta < 0 ? 'text-red-300' : 'text-zinc-500'
        }`}>
          {level.delta !== 0 ? (level.delta > 0 ? `+${level.delta}` : level.delta) : ''}
        </span>
        {/* Imbalance indicator */}
        {level.imbalance && (
          <div className="absolute right-0.5 top-0.5 w-1.5 h-1.5 rounded-full bg-yellow-400" title="Imbalance 3:1" />
        )}
      </div>

      {/* Buy volume */}
      <div className="w-12 text-left text-emerald-400">
        {level.buyVolume > 0 ? level.buyVolume : ''}
      </div>

      {/* Price */}
      <div className="w-16 text-right text-zinc-400">
        {level.price.toFixed(2)}
      </div>
    </div>
  );
}

function FootprintBarView({ bar }: { bar: FootprintBar }) {
  const levels = Object.values(bar.levels);
  if (levels.length === 0) {
    return <div className="text-xs text-zinc-600 text-center py-4">No tick data yet</div>;
  }

  // Sort levels by price descending (highest first)
  const sorted = [...levels].sort((a, b) => b.price - a.price);
  const maxVolume = Math.max(...sorted.map(l => l.buyVolume + l.sellVolume));

  return (
    <div className="flex flex-col gap-0">
      {/* Header */}
      <div className="flex items-center gap-1 text-[9px] text-zinc-600 font-medium mb-1 px-0.5">
        <div className="w-12 text-right">SELL</div>
        <div className="flex-1 text-center">DELTA</div>
        <div className="w-12 text-left">BUY</div>
        <div className="w-16 text-right">PRICE</div>
      </div>

      {sorted.map(level => (
        <LevelRow
          key={level.price}
          level={level}
          maxVolume={maxVolume}
        />
      ))}
    </div>
  );
}

export default function FootprintChart({ selectedInstrument }: FootprintChartProps) {
  const { footprintData } = useOrderFlow();
  const [restBar, setRestBar] = useState<FootprintBar | null>(null);

  // REST fallback: fetch initial footprint on instrument switch so the panel
  // has data before the first WebSocket tick arrives. The backend may return
  // {available: false} or {error: ...} with HTTP 200 when ticks aren't flowing —
  // narrow with isFootprintBar() before storing so later reads don't crash.
  useEffect(() => {
    if (!selectedInstrument) { setRestBar(null); return; }
    let cancelled = false;
    api.getFootprint(selectedInstrument).then(result => {
      if (cancelled) return;
      setRestBar(isFootprintBar(result) ? (result as unknown as FootprintBar) : null);
    }).catch(() => { if (!cancelled) setRestBar(null); });
    return () => { cancelled = true; };
  }, [selectedInstrument]);

  // WebSocket data takes precedence; fall back to REST snapshot.
  const liveBar = selectedInstrument ? footprintData.get(selectedInstrument) : undefined;
  const bar = liveBar ?? restBar ?? undefined;

  const totalDelta = bar?.totalDelta ?? 0;
  const totalBuy = bar?.totalBuyVolume ?? 0;
  const totalSell = bar?.totalSellVolume ?? 0;
  const poc = bar?.pocPrice;
  const levelCount = bar ? Object.keys(bar.levels).length : 0;

  return (
    <div className="rounded-lg border border-zinc-800 bg-zinc-900/50 overflow-hidden">
      {/* Panel header */}
      <div className="flex items-center justify-between px-3 py-2 border-b border-zinc-800/60">
        <div className="flex items-center gap-2">
          <span className="text-xs font-semibold text-zinc-300">Footprint</span>
          {bar && (
            <span className="text-[10px] text-zinc-600">
              {bar.timeframe} &middot; {levelCount} levels
            </span>
          )}
        </div>
        <div className="flex items-center gap-3 text-[10px]">
          {poc != null && (
            <span className="text-amber-400" title="Point of Control">
              POC {poc.toFixed(2)}
            </span>
          )}
          <span className={totalDelta >= 0 ? 'text-emerald-400' : 'text-red-400'}>
            {totalDelta >= 0 ? '+' : ''}{totalDelta}
          </span>
          <span className="text-zinc-500">
            B:{totalBuy} S:{totalSell}
          </span>
        </div>
      </div>

      {/* Body */}
      <div className="p-2 max-h-[320px] overflow-y-auto">
        {bar ? (
          <FootprintBarView bar={bar} />
        ) : (
          <div className="text-xs text-zinc-600 text-center py-6">
            {selectedInstrument
              ? `Waiting for ${selectedInstrument} tick data...`
              : 'Select an instrument'}
          </div>
        )}
      </div>
    </div>
  );
}
