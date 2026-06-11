'use client';

import { useEffect, useState } from 'react';
import { useOrderFlow, FootprintBar, FootprintLevel, ImbalanceZone } from '@/app/hooks/useOrderFlow';
import { api, isFootprintBar } from '@/app/lib/api';

function barTimeLabel(barTimestamp: number): string {
  const d = new Date(barTimestamp * 1000);
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

/** Compact strip of recently closed bars: open time, delta, POC. */
function HistoryStrip({ bars }: { bars: FootprintBar[] }) {
  if (bars.length === 0) return null;
  return (
    <div className="border-t border-zinc-800/60 px-2 py-1.5">
      <div className="text-[9px] text-zinc-600 font-medium mb-1">CLOSED BARS</div>
      <div className="flex flex-col gap-0.5 max-h-[120px] overflow-y-auto">
        {bars.map(b => (
          <div key={b.barTimestamp} className="flex items-center gap-2 text-[10px] font-mono">
            <span className="w-10 text-zinc-500">{barTimeLabel(b.barTimestamp)}</span>
            <span className={`w-12 text-right ${b.totalDelta >= 0 ? 'text-emerald-400' : 'text-red-400'}`}>
              {b.totalDelta >= 0 ? '+' : ''}{b.totalDelta}
            </span>
            <span className="w-16 text-right text-amber-400/80" title="Point of Control">
              {b.pocPrice.toFixed(2)}
            </span>
            <span className="w-8 text-center">
              {b.unfinishedHigh && <span className="text-cyan-400" title="Unfinished auction (high)">▲</span>}
              {b.unfinishedLow && <span className="text-fuchsia-400" title="Unfinished auction (low)">▼</span>}
            </span>
            <span className="flex-1 text-right text-zinc-600">
              B:{b.totalBuyVolume} S:{b.totalSellVolume}
              {(b.stackedBuyZones?.length ?? 0) > 0 && (
                <span className="ml-1 text-emerald-400" title="Stacked buy imbalance zone(s)">
                  ⊞{b.stackedBuyZones!.length}
                </span>
              )}
              {(b.stackedSellZones?.length ?? 0) > 0 && (
                <span className="ml-1 text-red-400" title="Stacked sell imbalance zone(s)">
                  ⊟{b.stackedSellZones!.length}
                </span>
              )}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

interface FootprintChartProps {
  selectedInstrument?: string;
}

function inZone(price: number, zones: ImbalanceZone[] | undefined): boolean {
  if (!zones || zones.length === 0) return false;
  return zones.some(z => price >= z.fromPrice - 1e-9 && price <= z.toPrice + 1e-9);
}

function LevelRow({ level, maxVolume, stackedBuy, stackedSell }: {
  level: FootprintLevel;
  maxVolume: number;
  stackedBuy: boolean;
  stackedSell: boolean;
}) {
  const totalVol = level.buyVolume + level.sellVolume;
  const buyPct = totalVol > 0 ? (level.buyVolume / totalVol) * 100 : 0;
  const barWidth = maxVolume > 0 ? (totalVol / maxVolume) * 100 : 0;

  return (
    <div className={`flex items-center gap-1 text-[10px] font-mono h-5 ${
      stackedBuy ? 'bg-emerald-500/10' : stackedSell ? 'bg-red-500/10' : ''
    }`}>
      {/* Sell volume — red right-border = diagonal sell imbalance at this level */}
      <div
        className={`w-12 text-right ${level.diagonalSellImbalance ? 'text-red-300 font-bold border-r-2 border-red-500 pr-0.5' : 'text-red-400'}`}
        title={level.diagonalSellImbalance ? 'Diagonal sell imbalance (vs buy 1 bucket higher)' : undefined}
      >
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
      </div>

      {/* Buy volume — emerald left-border = diagonal buy imbalance at this level */}
      <div
        className={`w-12 text-left ${level.diagonalBuyImbalance ? 'text-emerald-300 font-bold border-l-2 border-emerald-500 pl-0.5' : 'text-emerald-400'}`}
        title={level.diagonalBuyImbalance ? 'Diagonal buy imbalance (vs sell 1 bucket lower)' : undefined}
      >
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

      {/* Unfinished auction at the high: both sides traded at the top bucket */}
      {bar.unfinishedHigh && (
        <div className="flex items-center justify-end gap-1 text-[9px] text-cyan-400 pr-1"
             title="Unfinished auction at the high — both sides traded at the extreme; price often revisits">
          ▲ unfinished high
        </div>
      )}

      {sorted.map(level => (
        <LevelRow
          key={level.price}
          level={level}
          maxVolume={maxVolume}
          stackedBuy={inZone(level.price, bar.stackedBuyZones)}
          stackedSell={inZone(level.price, bar.stackedSellZones)}
        />
      ))}

      {/* Unfinished auction at the low */}
      {bar.unfinishedLow && (
        <div className="flex items-center justify-end gap-1 text-[9px] text-fuchsia-400 pr-1"
             title="Unfinished auction at the low — both sides traded at the extreme; price often revisits">
          ▼ unfinished low
        </div>
      )}
    </div>
  );
}

export default function FootprintChart({ selectedInstrument }: FootprintChartProps) {
  const { footprintData } = useOrderFlow();
  const [restBar, setRestBar] = useState<FootprintBar | null>(null);
  const [history, setHistory] = useState<FootprintBar[]>([]);

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

  // Closed-bar history: refresh on instrument switch and whenever the live bar
  // rolls into a new window (its barTimestamp changes => previous bar closed).
  const liveBarTimestamp = liveBar?.barTimestamp;
  useEffect(() => {
    if (!selectedInstrument) { setHistory([]); return; }
    let cancelled = false;
    api.getFootprintHistory(selectedInstrument).then(bars => {
      if (!cancelled) setHistory(Array.isArray(bars) ? bars : []);
    }).catch(() => { if (!cancelled) setHistory([]); });
    return () => { cancelled = true; };
  }, [selectedInstrument, liveBarTimestamp]);

  const totalDelta = bar?.totalDelta ?? 0;
  const totalBuy = bar?.totalBuyVolume ?? 0;
  const totalSell = bar?.totalSellVolume ?? 0;
  const poc = bar?.pocPrice;
  const levelCount = bar ? Object.keys(bar.levels).length : 0;
  const stackedBuyCount = bar?.stackedBuyZones?.length ?? 0;
  const stackedSellCount = bar?.stackedSellZones?.length ?? 0;

  return (
    <div className="rounded-lg border border-zinc-800 bg-zinc-900/50 overflow-hidden">
      {/* Panel header */}
      <div className="flex items-center justify-between px-3 py-2 border-b border-zinc-800/60">
        <div className="flex items-center gap-2">
          <span className="text-xs font-semibold text-zinc-300">Footprint</span>
          {bar && (
            <span className="text-[10px] text-zinc-600">
              {bar.timeframe} &middot; {barTimeLabel(bar.barTimestamp)} &middot; {levelCount} levels
            </span>
          )}
        </div>
        <div className="flex items-center gap-3 text-[10px]">
          {stackedBuyCount > 0 && (
            <span className="text-emerald-400" title="Stacked buy imbalance zones (≥3 consecutive diagonal flags)">
              ⊞ {stackedBuyCount}
            </span>
          )}
          {stackedSellCount > 0 && (
            <span className="text-red-400" title="Stacked sell imbalance zones (≥3 consecutive diagonal flags)">
              ⊟ {stackedSellCount}
            </span>
          )}
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

      {/* Closed-bar history */}
      <HistoryStrip bars={history} />
    </div>
  );
}
