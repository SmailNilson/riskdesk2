'use client';

import { useEffect, useState } from 'react';
import { BookLevel, DepthMetrics } from '@/app/hooks/useOrderFlow';

interface DepthBookWidgetProps {
  instrument: string;
  depthData?: DepthMetrics;
}

const STALE_AFTER_SEC = 30;

/** True during the CME daily maintenance break (17:00-18:00 ET) — the book legitimately empties. */
function isCmeMaintenanceBreak(now: Date): boolean {
  const etHour = Number(
    new Intl.DateTimeFormat('en-US', { hour: 'numeric', hour12: false, timeZone: 'America/New_York' })
      .format(now),
  );
  return etHour === 17;
}

/**
 * Real L2 order book ladder (DOM). Renders the actual bid/ask levels pushed by
 * the backend (up to 10 per side from reqMktDepth) as a price ladder centered
 * on the spread: asks stacked above (best ask at the bottom of the ask block),
 * bids below (best bid at the top of the bid block). Wall levels (size > N x
 * average level size) are highlighted.
 */
function LadderRow({
  level,
  side,
  maxSize,
}: {
  level: BookLevel;
  side: 'bid' | 'ask';
  maxSize: number;
}) {
  const pct = maxSize > 0 ? Math.max((level.size / maxSize) * 100, 2) : 0;
  const barColor = side === 'bid' ? 'bg-emerald-700/50' : 'bg-red-700/50';
  const priceColor = side === 'bid' ? 'text-emerald-300' : 'text-red-300';

  return (
    <div className="flex items-center gap-1 h-[18px] text-[10px] font-mono">
      {/* Size (left) */}
      <div className="w-14 text-right text-zinc-400">
        {level.size.toLocaleString()}
      </div>

      {/* Volume bar */}
      <div className="flex-1 relative h-[14px] bg-zinc-800/40 rounded-sm overflow-hidden">
        <div
          className={`absolute top-0 bottom-0 left-0 ${barColor} ${
            level.wall ? 'ring-1 ring-inset ring-yellow-400/80' : ''
          } transition-all duration-200`}
          style={{ width: `${pct}%` }}
        />
        {level.wall && (
          <span className="absolute right-1 top-0 bottom-0 flex items-center text-[8px] text-yellow-300 font-semibold">
            WALL
          </span>
        )}
      </div>

      {/* Price (right) */}
      <div className={`w-20 text-right ${priceColor} ${level.wall ? 'font-semibold' : ''}`}>
        {level.price.toFixed(2)}
      </div>
    </div>
  );
}

export default function DepthBookWidget({ instrument, depthData }: DepthBookWidgetProps) {
  const bids = depthData?.bids ?? [];
  const asks = depthData?.asks ?? [];

  // 1s heartbeat so the staleness banner appears even when no new payload arrives
  // (a frozen feed is precisely the case where nothing re-renders us).
  const [nowMs, setNowMs] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNowMs(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  const lastUpdateMs = depthData?.dataTimestamp ? Date.parse(depthData.dataTimestamp) : NaN;
  const ageSec = Number.isNaN(lastUpdateMs) ? null : Math.max(0, (nowMs - lastUpdateMs) / 1000);
  const stale = depthData?.serverStale === true || (ageSec != null && ageSec > STALE_AFTER_SEC);
  const maintenanceBreak = isCmeMaintenanceBreak(new Date(nowMs));

  if (!depthData || (bids.length === 0 && asks.length === 0)) {
    return (
      <div className="flex flex-col items-center justify-center gap-1 p-3 rounded bg-zinc-900 border border-zinc-800 min-h-[120px]">
        <span className="text-xs text-zinc-500">{instrument}</span>
        <span className="text-[10px] text-zinc-600 italic">Waiting for order book levels…</span>
      </div>
    );
  }

  const maxSize = Math.max(...bids.map(l => l.size), ...asks.map(l => l.size), 1);
  // Asks render top-down: furthest ask first, best ask adjacent to the spread line.
  const asksTopDown = [...asks].reverse();
  const spreadLabel = depthData.spread > 0 ? depthData.spread.toFixed(2) : '—';
  const imbalancePct = (depthData.imbalance * 100).toFixed(1);

  return (
    <div className={`flex flex-col gap-1 p-2.5 rounded bg-zinc-900 border ${
      stale ? 'border-amber-700/60' : 'border-zinc-800'
    }`}>
      {/* Header */}
      <div className="flex items-center justify-between mb-0.5">
        <span className="text-xs font-semibold text-zinc-200">{instrument} — Order Book</span>
        <span className="text-[10px] text-zinc-500">
          {bids.length}×{asks.length} levels
        </span>
      </div>

      {/* Staleness banner — a frozen ladder must never read as a live book */}
      {stale && (
        <div className="flex items-center gap-1.5 px-2 py-1 rounded bg-amber-950/60 border border-amber-800/50">
          <span className="text-[10px] font-semibold text-amber-300">
            {maintenanceBreak ? 'PAUSE CME (17h-18h ET)' : 'FLUX FIGÉ'}
          </span>
          <span className="text-[9px] text-amber-400/80">
            {ageSec != null
              ? `dernier update il y a ${ageSec < 90 ? `${Math.round(ageSec)}s` : `${Math.round(ageSec / 60)}min`} — niveaux non fiables`
              : 'âge du carnet inconnu — niveaux non fiables'}
          </span>
        </div>
      )}

      {/* Column header */}
      <div className="flex items-center gap-1 text-[9px] text-zinc-600 font-medium px-0.5">
        <div className="w-14 text-right">SIZE</div>
        <div className="flex-1" />
        <div className="w-20 text-right">PRICE</div>
      </div>

      {/* Asks (above the spread) */}
      <div className="flex flex-col">
        {asksTopDown.map((level, i) => (
          <LadderRow key={`ask-${level.price}-${i}`} level={level} side="ask" maxSize={maxSize} />
        ))}
      </div>

      {/* Spread separator */}
      <div className="flex items-center gap-2 py-0.5">
        <div className="flex-1 h-px bg-zinc-700" />
        <span className="text-[9px] text-zinc-500 font-mono">spread {spreadLabel}</span>
        <div className="flex-1 h-px bg-zinc-700" />
      </div>

      {/* Bids (below the spread) */}
      <div className="flex flex-col">
        {bids.map((level, i) => (
          <LadderRow key={`bid-${level.price}-${i}`} level={level} side="bid" maxSize={maxSize} />
        ))}
      </div>

      {/* Footer: totals + imbalance */}
      <div className="flex justify-between text-[10px] mt-0.5">
        <span className="text-emerald-400">Bid {depthData.totalBidSize.toLocaleString()}</span>
        <span className={depthData.imbalance >= 0 ? 'text-emerald-400' : 'text-red-400'}>
          Imbalance {imbalancePct}%
        </span>
        <span className="text-red-400">Ask {depthData.totalAskSize.toLocaleString()}</span>
      </div>
    </div>
  );
}
