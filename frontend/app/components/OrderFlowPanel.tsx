'use client';

import { useEffect, useState } from 'react';
import {
  useOrderFlow,
  OrderFlowMetrics,
  DepthMetrics,
  AbsorptionEvent,
  SpoofingEvent,
  IcebergEvent,
} from '@/app/hooks/useOrderFlow';
import { api } from '@/app/lib/api';

const DEPTH_INSTRUMENTS = ['MNQ', 'MCL', 'MGC'] as const;
const HISTORY_LIMIT = 20;

interface OrderFlowPanelProps {
  selectedInstrument?: string;
}

// ---------------------------------------------------------------------------
// Types & helpers for the historical event lists
// ---------------------------------------------------------------------------

/**
 * Unified shape for persisted+live events in the UI. The REST endpoint returns
 * the same field names as the WebSocket payload, so a single type covers both.
 */
type IcebergRow = IcebergEvent;
type AbsorptionRow = AbsorptionEvent & { absorptionScore?: number; priceMoveTicks?: number; totalVolume?: number };
type SpoofingRow = SpoofingEvent & { durationSeconds?: number; priceCrossed?: boolean };

function prependUnique<T extends { timestamp: string; instrument: string }>(
  list: T[],
  incoming: T,
  cap: number,
): T[] {
  // Avoid duplicating the same event if it was already seeded via REST.
  const exists = list.some(
    e => e.timestamp === incoming.timestamp && e.instrument === incoming.instrument,
  );
  if (exists) return list;
  return [incoming, ...list].slice(0, cap);
}

function formatRelativeTime(iso: string): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return iso;
  const diffSec = Math.max(0, Math.floor((Date.now() - then) / 1000));
  if (diffSec < 60) return `${diffSec}s ago`;
  if (diffSec < 3600) return `${Math.floor(diffSec / 60)}m ago`;
  if (diffSec < 86400) return `${Math.floor(diffSec / 3600)}h ago`;
  return `${Math.floor(diffSec / 86400)}d ago`;
}

// ---------------------------------------------------------------------------
// Live metric sub-components
// ---------------------------------------------------------------------------

function DeltaBar({ metrics }: { metrics: OrderFlowMetrics }) {
  const totalVolume = metrics.buyVolume + metrics.sellVolume;
  const buyPct = totalVolume > 0 ? (metrics.buyVolume / totalVolume) * 100 : 50;
  const sellPct = 100 - buyPct;
  const isRealTicks = metrics.source === 'REAL_TICKS';

  return (
    <div className="flex flex-col gap-1 p-2 rounded bg-zinc-800/60">
      <div className="flex items-center justify-between text-xs">
        <span className="font-medium text-zinc-300">{metrics.instrument}</span>
        <div className="flex items-center gap-2">
          <span className={metrics.delta >= 0 ? 'text-emerald-400' : 'text-red-400'}>
            {metrics.delta >= 0 ? '+' : ''}{metrics.delta.toLocaleString()}
          </span>
          <span className={`px-1.5 py-0.5 rounded text-[10px] font-semibold ${
            isRealTicks ? 'bg-emerald-900/60 text-emerald-400' : 'bg-yellow-900/60 text-yellow-400'
          }`}>
            {isRealTicks ? 'REAL' : 'CLV'}
          </span>
        </div>
      </div>

      {/* Buy/Sell bar */}
      <div className="flex h-3 rounded overflow-hidden">
        <div
          className="bg-emerald-600 transition-all duration-300"
          style={{ width: `${buyPct}%` }}
        />
        <div
          className="bg-red-600 transition-all duration-300"
          style={{ width: `${sellPct}%` }}
        />
      </div>

      <div className="flex justify-between text-[10px] text-zinc-500">
        <span>Buy {metrics.buyVolume.toLocaleString()} ({buyPct.toFixed(1)}%)</span>
        <span className="text-zinc-600">Cum: {metrics.cumulativeDelta.toLocaleString()}</span>
        <span>Sell {metrics.sellVolume.toLocaleString()} ({sellPct.toFixed(1)}%)</span>
      </div>
    </div>
  );
}

function DepthGauge({ metrics }: { metrics: DepthMetrics }) {
  // imbalance is -1 to +1; map to 0-100 for gauge position
  const gaugePosition = ((metrics.imbalance + 1) / 2) * 100;

  return (
    <div className="flex flex-col gap-1.5 p-2 rounded bg-zinc-800/60">
      <div className="flex items-center justify-between text-xs">
        <span className="font-medium text-zinc-300">{metrics.instrument}</span>
        <span className="text-zinc-500">Spread: {metrics.spread.toFixed(2)}</span>
      </div>

      {/* Imbalance gauge */}
      <div className="relative h-4 rounded bg-zinc-700 overflow-hidden">
        <div className="absolute inset-0 flex">
          <div className="w-1/2 bg-emerald-900/30" />
          <div className="w-1/2 bg-red-900/30" />
        </div>
        {/* Center line */}
        <div className="absolute left-1/2 top-0 bottom-0 w-px bg-zinc-500" />
        {/* Indicator needle */}
        <div
          className="absolute top-0 bottom-0 w-1.5 rounded bg-white/80 transition-all duration-300"
          style={{ left: `calc(${gaugePosition}% - 3px)` }}
        />
      </div>

      <div className="flex justify-between text-[10px]">
        <span className="text-emerald-400">
          Bid: {metrics.totalBidSize.toLocaleString()}
          {metrics.bidWall && (
            <span className="ml-1 text-emerald-300">
              Wall @{metrics.bidWall.price.toFixed(2)} ({metrics.bidWall.size.toLocaleString()})
            </span>
          )}
        </span>
        <span className="text-red-400">
          Ask: {metrics.totalAskSize.toLocaleString()}
          {metrics.askWall && (
            <span className="ml-1 text-red-300">
              Wall @{metrics.askWall.price.toFixed(2)} ({metrics.askWall.size.toLocaleString()})
            </span>
          )}
        </span>
      </div>

      <div className="text-center text-[10px] text-zinc-400">
        Imbalance: {(metrics.imbalance * 100).toFixed(1)}%
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Event row renderers
// ---------------------------------------------------------------------------

function IcebergRowView({ event }: { event: IcebergRow }) {
  const isBid = event.side === 'BID_ICEBERG';
  return (
    <div className="flex items-center gap-2 px-2 py-1 text-[11px] rounded bg-zinc-800/40 hover:bg-zinc-800/70 transition-colors">
      <span className="shrink-0 px-1.5 py-0.5 rounded text-[9px] font-bold bg-sky-900/60 text-sky-300">
        ICE
      </span>
      <span className="text-zinc-400 shrink-0">{event.instrument}</span>
      <span className={`shrink-0 ${isBid ? 'text-emerald-400' : 'text-red-400'}`}>
        {isBid ? 'BID' : 'ASK'}
      </span>
      <span className="text-zinc-500 truncate">
        @{event.priceLevel?.toFixed(2) ?? '—'} · recharges: {event.rechargeCount ?? '—'} · dur: {event.durationSeconds?.toFixed(1) ?? '—'}s · score: {event.icebergScore?.toFixed(1) ?? '—'}
      </span>
      <span className="ml-auto text-zinc-600 shrink-0" title={event.timestamp}>
        {formatRelativeTime(event.timestamp)}
      </span>
    </div>
  );
}

function AbsorptionRowView({ event }: { event: AbsorptionRow }) {
  const isBull = event.side === 'BULLISH_ABSORPTION' || event.side === 'BUY' || event.side === 'LONG';
  // REST history uses `absorptionScore`, live WS payload uses `score`.
  const score = event.absorptionScore ?? event.score;
  const delta = event.delta ?? event.aggressiveDelta;
  return (
    <div className="flex items-center gap-2 px-2 py-1 text-[11px] rounded bg-zinc-800/40 hover:bg-zinc-800/70 transition-colors">
      <span className="shrink-0 px-1.5 py-0.5 rounded text-[9px] font-bold bg-purple-900/60 text-purple-300">
        ABS
      </span>
      <span className="text-zinc-400 shrink-0">{event.instrument}</span>
      <span className={`shrink-0 ${isBull ? 'text-emerald-400' : 'text-red-400'}`}>
        {isBull ? 'BULL' : 'BEAR'}
      </span>
      <span className="text-zinc-500 truncate">
        score: {score?.toFixed(1) ?? '—'} · delta: {delta?.toLocaleString() ?? '—'}
      </span>
      <span className="ml-auto text-zinc-600 shrink-0" title={event.timestamp}>
        {formatRelativeTime(event.timestamp)}
      </span>
    </div>
  );
}

function SpoofingRowView({ event }: { event: SpoofingRow }) {
  const isBid = event.side === 'BID_SPOOF' || event.side === 'BUY' || event.side === 'LONG';
  return (
    <div className="flex items-center gap-2 px-2 py-1 text-[11px] rounded bg-zinc-800/40 hover:bg-zinc-800/70 transition-colors">
      <span className="shrink-0 px-1.5 py-0.5 rounded text-[9px] font-bold bg-orange-900/60 text-orange-300">
        SPOOF
      </span>
      <span className="text-zinc-400 shrink-0">{event.instrument}</span>
      <span className={`shrink-0 ${isBid ? 'text-emerald-400' : 'text-red-400'}`}>
        {isBid ? 'BID' : 'ASK'}
      </span>
      <span className="text-zinc-500 truncate">
        @{event.priceLevel?.toFixed(2) ?? '—'} · size: {event.wallSize?.toLocaleString() ?? '—'} · score: {event.spoofScore?.toFixed(1) ?? '—'}
        {event.priceCrossed ? ' · crossed' : ''}
      </span>
      <span className="ml-auto text-zinc-600 shrink-0" title={event.timestamp}>
        {formatRelativeTime(event.timestamp)}
      </span>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Main Panel
// ---------------------------------------------------------------------------

export default function OrderFlowPanel({ selectedInstrument }: OrderFlowPanelProps) {
  const {
    orderFlowData,
    depthData,
    absorptionEvents,
    spoofingEvents,
    icebergEvents,
    connected,
  } = useOrderFlow();

  // Historical lists seeded on mount via REST, then kept fresh by merging
  // WebSocket events (prepend, cap at HISTORY_LIMIT).
  const [icebergHistory, setIcebergHistory] = useState<IcebergRow[]>([]);
  const [absorptionHistory, setAbsorptionHistory] = useState<AbsorptionRow[]>([]);
  const [spoofingHistory, setSpoofingHistory] = useState<SpoofingRow[]>([]);

  // --- REST seed on mount / when selected instrument changes -----------------
  useEffect(() => {
    if (!selectedInstrument) {
      setIcebergHistory([]);
      setAbsorptionHistory([]);
      setSpoofingHistory([]);
      return;
    }
    let cancelled = false;

    api
      .getIcebergEvents(selectedInstrument, HISTORY_LIMIT)
      .then(rows => {
        if (!cancelled) setIcebergHistory(rows as IcebergRow[]);
      })
      .catch(() => { /* best-effort: leave the list empty */ });

    api
      .getAbsorptionEvents(selectedInstrument, HISTORY_LIMIT)
      .then(rows => {
        if (!cancelled) setAbsorptionHistory(rows as AbsorptionRow[]);
      })
      .catch(() => { /* best-effort */ });

    api
      .getSpoofingEvents(selectedInstrument, HISTORY_LIMIT)
      .then(rows => {
        if (!cancelled) setSpoofingHistory(rows as SpoofingRow[]);
      })
      .catch(() => { /* best-effort */ });

    return () => { cancelled = true; };
  }, [selectedInstrument]);

  // --- WS merge: prepend new events that match the selected instrument -------
  useEffect(() => {
    if (!selectedInstrument || icebergEvents.length === 0) return;
    const latest = icebergEvents[0];
    if (latest.instrument !== selectedInstrument) return;
    setIcebergHistory(prev => prependUnique(prev, latest as IcebergRow, HISTORY_LIMIT));
  }, [icebergEvents, selectedInstrument]);

  useEffect(() => {
    if (!selectedInstrument || absorptionEvents.length === 0) return;
    const latest = absorptionEvents[0];
    if (latest.instrument !== selectedInstrument) return;
    setAbsorptionHistory(prev => prependUnique(prev, latest as AbsorptionRow, HISTORY_LIMIT));
  }, [absorptionEvents, selectedInstrument]);

  useEffect(() => {
    if (!selectedInstrument || spoofingEvents.length === 0) return;
    const latest = spoofingEvents[0];
    if (latest.instrument !== selectedInstrument) return;
    setSpoofingHistory(prev => prependUnique(prev, latest as SpoofingRow, HISTORY_LIMIT));
  }, [spoofingEvents, selectedInstrument]);

  // --- Live metrics filtering (unchanged) ------------------------------------
  const flowEntries = Array.from(orderFlowData.values());
  const filteredFlow = selectedInstrument
    ? flowEntries.filter(m => m.instrument === selectedInstrument)
    : flowEntries;

  const depthEntries = Array.from(depthData.values())
    .filter(m => DEPTH_INSTRUMENTS.includes(m.instrument as typeof DEPTH_INSTRUMENTS[number]));
  const filteredDepth = selectedInstrument
    ? depthEntries.filter(m => m.instrument === selectedInstrument)
    : depthEntries;

  // --- Live combined event log (kept for quick cross-type glance) ------------
  const liveFeed = [
    ...absorptionEvents.map(e => ({ event: e, type: 'absorption' as const })),
    ...spoofingEvents.map(e => ({ event: e, type: 'spoofing' as const })),
    ...icebergEvents.map(e => ({ event: e, type: 'iceberg' as const })),
  ].sort((a, b) => new Date(b.event.timestamp).getTime() - new Date(a.event.timestamp).getTime())
   .slice(0, HISTORY_LIMIT);

  const filteredLiveFeed = selectedInstrument
    ? liveFeed.filter(e => e.event.instrument === selectedInstrument)
    : liveFeed;

  return (
    <div className="flex flex-col gap-3 p-3 rounded-lg bg-zinc-900 border border-zinc-800">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-zinc-200">Order Flow</h3>
        <span className={`w-2 h-2 rounded-full ${connected ? 'bg-emerald-400' : 'bg-red-500'}`} />
      </div>

      {/* Section 1: Delta Bars */}
      <div>
        <h4 className="text-[10px] uppercase tracking-wider text-zinc-500 mb-1.5">Delta</h4>
        <div className="flex flex-col gap-1.5">
          {filteredFlow.length > 0
            ? filteredFlow.map(m => <DeltaBar key={m.instrument} metrics={m} />)
            : <p className="text-xs text-zinc-600 italic">Waiting for order flow data...</p>
          }
        </div>
      </div>

      {/* Section 2: Depth Gauges */}
      <div>
        <h4 className="text-[10px] uppercase tracking-wider text-zinc-500 mb-1.5">Depth</h4>
        <div className="flex flex-col gap-1.5">
          {filteredDepth.length > 0
            ? filteredDepth.map(m => <DepthGauge key={m.instrument} metrics={m} />)
            : <p className="text-xs text-zinc-600 italic">Waiting for depth data...</p>
          }
        </div>
      </div>

      {/* Section 3: Iceberg Activity (persisted + live) */}
      <div>
        <h4 className="text-[10px] uppercase tracking-wider text-zinc-500 mb-1.5">
          Iceberg Activity{selectedInstrument ? ` — ${selectedInstrument}` : ''}
        </h4>
        <div className="flex flex-col gap-0.5 max-h-48 overflow-y-auto scrollbar-thin scrollbar-thumb-zinc-700">
          {icebergHistory.length > 0
            ? icebergHistory.map((e, i) => (
                <IcebergRowView key={`ice-${e.timestamp}-${i}`} event={e} />
              ))
            : <p className="text-xs text-zinc-600 italic">
                {selectedInstrument ? 'No iceberg events yet' : 'Select an instrument to see iceberg history'}
              </p>
          }
        </div>
      </div>

      {/* Section 4: Absorption history (persisted + live) */}
      <div>
        <h4 className="text-[10px] uppercase tracking-wider text-zinc-500 mb-1.5">
          Absorption History{selectedInstrument ? ` — ${selectedInstrument}` : ''}
        </h4>
        <div className="flex flex-col gap-0.5 max-h-40 overflow-y-auto scrollbar-thin scrollbar-thumb-zinc-700">
          {absorptionHistory.length > 0
            ? absorptionHistory.map((e, i) => (
                <AbsorptionRowView key={`abs-${e.timestamp}-${i}`} event={e} />
              ))
            : <p className="text-xs text-zinc-600 italic">
                {selectedInstrument ? 'No absorption events yet' : 'Select an instrument to see absorption history'}
              </p>
          }
        </div>
      </div>

      {/* Section 5: Spoofing history (persisted + live) */}
      <div>
        <h4 className="text-[10px] uppercase tracking-wider text-zinc-500 mb-1.5">
          Spoofing History{selectedInstrument ? ` — ${selectedInstrument}` : ''}
        </h4>
        <div className="flex flex-col gap-0.5 max-h-40 overflow-y-auto scrollbar-thin scrollbar-thumb-zinc-700">
          {spoofingHistory.length > 0
            ? spoofingHistory.map((e, i) => (
                <SpoofingRowView key={`spoof-${e.timestamp}-${i}`} event={e} />
              ))
            : <p className="text-xs text-zinc-600 italic">
                {selectedInstrument ? 'No spoofing events yet' : 'Select an instrument to see spoofing history'}
              </p>
          }
        </div>
      </div>

      {/* Section 6: Live combined feed (unchanged behaviour — quick glance) */}
      <div>
        <h4 className="text-[10px] uppercase tracking-wider text-zinc-500 mb-1.5">Live Feed</h4>
        <div className="flex flex-col gap-0.5 max-h-40 overflow-y-auto scrollbar-thin scrollbar-thumb-zinc-700">
          {filteredLiveFeed.length > 0
            ? filteredLiveFeed.map((e, i) => {
                if (e.type === 'iceberg') {
                  return <IcebergRowView key={`feed-ice-${e.event.timestamp}-${i}`} event={e.event as IcebergRow} />;
                }
                if (e.type === 'absorption') {
                  return <AbsorptionRowView key={`feed-abs-${e.event.timestamp}-${i}`} event={e.event as AbsorptionRow} />;
                }
                return <SpoofingRowView key={`feed-spoof-${e.event.timestamp}-${i}`} event={e.event as SpoofingRow} />;
              })
            : <p className="text-xs text-zinc-600 italic">No live events yet</p>
          }
        </div>
      </div>
    </div>
  );
}
