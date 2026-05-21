'use client';

import { useEffect, useState } from 'react';
import {
  useOrderFlow,
  OrderFlowMetrics,
  DepthMetrics,
  AbsorptionEvent,
  SpoofingEvent,
  IcebergEvent,
  DistributionEvent,
  MomentumEvent,
  CycleEvent,
} from '@/app/hooks/useOrderFlow';
import { api } from '@/app/lib/api';
import { useQuantStream } from '@/app/hooks/useQuantStream';
import {
  parseDeltaAndTrend,
  parseAbsorption,
  parseDistAccu,
} from './quant/QuantGatePanel';
import { type QuantGateView } from './quant/types';

const DEPTH_INSTRUMENTS = ['MNQ', 'MCL', 'MGC'] as const;
const HISTORY_LIMIT = 20;

interface FrontendThresholds {
  strongDelta: number;
  highDelta: number;
}

const INSTRUMENT_THRESHOLDS: Record<string, FrontendThresholds> = {
  MNQ: { strongDelta: 200, highDelta: 400 },
  MGC: { strongDelta: 50, highDelta: 100 },
  MCL: { strongDelta: 50, highDelta: 100 },
  E6:  { strongDelta: 50, highDelta: 100 },
};

interface OrderFlowPanelProps {
  selectedInstrument?: string;
}

// ---------------------------------------------------------------------------
// Types & helpers for the historical event lists
// ---------------------------------------------------------------------------

type IcebergRow = IcebergEvent;
type AbsorptionRow = AbsorptionEvent & {
  absorptionScore?: number;
  aggressiveDelta?: number;
  priceMoveTicks?: number;
  totalVolume?: number;
};
type SpoofingRow = SpoofingEvent & { durationSeconds?: number; priceCrossed?: boolean };
type DistributionRow = DistributionEvent;
type MomentumRow = MomentumEvent & { momentumScore?: number; aggressiveDelta?: number };
type CycleRow = CycleEvent;

function prependUnique<T extends { timestamp: string; instrument: string }>(
  list: T[],
  incoming: T,
  cap: number,
): T[] {
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
  const { snapshots } = useQuantStream();
  const snap = snapshots[metrics.instrument] || null;
  const gates = snap?.gates ?? [];
  const { delta: qDelta, trend } = parseDeltaAndTrend(gates);

  const totalVolume = metrics.buyVolume + metrics.sellVolume;
  const buyPct = totalVolume > 0 ? (metrics.buyVolume / totalVolume) * 100 : 50;
  const sellPct = 100 - buyPct;
  const isRealTicks = metrics.source === 'REAL_TICKS';

  const thresholds = INSTRUMENT_THRESHOLDS[metrics.instrument] || { strongDelta: 200, highDelta: 400 };
  const highDelta = thresholds.highDelta;
  const strongDelta = thresholds.strongDelta;

  return (
    <div className="flex flex-col gap-1.5 p-2 rounded bg-zinc-800/60 border border-zinc-700/30">
      <div className="flex items-center justify-between text-xs">
        <div className="flex items-center gap-2">
          <span className="font-bold text-zinc-300">{metrics.instrument}</span>
          <span className={`px-1.5 py-0.5 rounded text-[10px] font-semibold ${
            isRealTicks ? 'bg-emerald-900/60 text-emerald-400' : 'bg-yellow-900/60 text-yellow-400'
          }`}>
            {isRealTicks ? 'REAL' : 'CLV'}
          </span>
        </div>
        <div className="flex items-center gap-1.5">
          {/* CVD Trend mini-bars */}
          {trend.length > 0 && (
            <div className="flex items-end gap-[1.5px] h-3.5 px-0.5 bg-zinc-950/40 rounded border border-zinc-700/40" title="CVD Trend Scans (Live)">
              {trend.map((val, idx) => {
                const heightPercent = Math.min(100, (Math.abs(val) / highDelta) * 100);
                const isPositive = val >= 0;
                const barBg = isPositive ? 'bg-cyan-500/80' : 'bg-rose-500/80';
                return (
                  <div
                    key={idx}
                    className={`w-[3px] rounded-t transition-all duration-300 ${barBg}`}
                    style={{ height: `${Math.max(20, heightPercent)}%` }}
                  />
                );
              })}
            </div>
          )}

          <span className={metrics.delta >= 0 ? 'text-emerald-400 font-mono font-bold' : 'text-red-400 font-mono font-bold'}>
            {metrics.delta >= 0 ? '+' : ''}{metrics.delta.toLocaleString()}
          </span>
        </div>
      </div>

      {/* Buy/Sell bar */}
      <div className="relative flex h-3 rounded overflow-hidden border border-zinc-700/30">
        <div
          className="bg-emerald-600 transition-all duration-300"
          style={{ width: `${buyPct}%` }}
        />
        <div
          className="bg-red-600 transition-all duration-300"
          style={{ width: `${sellPct}%` }}
        />
        {/* CVD overlay line if qDelta exists */}
        {qDelta !== null && (
          <div 
            className={`absolute top-0 bottom-0 w-[2px] transition-all duration-300 ${
              qDelta >= strongDelta ? 'bg-cyan-400 shadow-[0_0_8px_#22d3ee]' : qDelta <= -strongDelta ? 'bg-rose-400 shadow-[0_0_8px_#fb7185]' : 'bg-white/85'
            }`}
            style={{ left: `calc(50% + ${(qDelta / highDelta) * 50}%)` }}
            title={`Live Quant CVD: ${qDelta >= 0 ? '+' : ''}${qDelta.toFixed(0)}`}
          />
        )}
      </div>

      <div className="flex justify-between text-[10px] text-zinc-500 font-mono">
        <span>Buy {metrics.buyVolume.toLocaleString()} ({buyPct.toFixed(1)}%)</span>
        <span className="text-zinc-400">
          {qDelta !== null ? (
            <span className={qDelta >= 0 ? 'text-cyan-400 font-bold' : 'text-rose-400 font-bold'}>
              CVD: {qDelta >= 0 ? '+' : ''}{qDelta.toFixed(0)}
            </span>
          ) : (
            `Cum: ${metrics.cumulativeDelta.toLocaleString()}`
          )}
        </span>
        <span>Sell {metrics.sellVolume.toLocaleString()} ({sellPct.toFixed(1)}%)</span>
      </div>
    </div>
  );
}

function DepthGauge({ metrics }: { metrics: DepthMetrics }) {
  const { snapshots } = useQuantStream();
  const snap = snapshots[metrics.instrument] || null;
  const gates = snap?.gates ?? [];
  const abs = parseAbsorption(gates);

  const gaugePosition = ((metrics.imbalance + 1) / 2) * 100;

  return (
    <div className="flex flex-col gap-1.5 p-2 rounded bg-zinc-800/60 border border-zinc-700/30">
      <div className="flex items-center justify-between text-xs">
        <span className="font-bold text-zinc-300">{metrics.instrument}</span>
        <span className="text-zinc-500">Spread: {metrics.spread.toFixed(2)}</span>
      </div>

      {/* Imbalance gauge */}
      <div className="relative h-4 rounded bg-zinc-700 overflow-hidden border border-zinc-600/30">
        <div className="absolute inset-0 flex">
          <div className="w-1/2 bg-emerald-950/20" />
          <div className="w-1/2 bg-red-950/20" />
        </div>
        {/* Center line */}
        <div className="absolute left-1/2 top-0 bottom-0 w-px bg-zinc-500/50" />
        {/* Indicator needle */}
        <div
          className="absolute top-0 bottom-0 w-1.5 rounded bg-white/90 shadow-[0_0_4px_white] transition-all duration-300"
          style={{ left: `calc(${gaugePosition}% - 3px)` }}
        />
      </div>

      <div className="flex justify-between text-[10px] font-mono">
        <span className="text-emerald-400">
          Bid: {metrics.totalBidSize.toLocaleString()}
          {metrics.bidWall && (
            <span className="ml-1 text-emerald-300/80">
              @{metrics.bidWall.price.toFixed(2)} ({metrics.bidWall.size.toLocaleString()})
            </span>
          )}
        </span>
        <span className="text-red-400">
          Ask: {metrics.totalAskSize.toLocaleString()}
          {metrics.askWall && (
            <span className="ml-1 text-red-300/80">
              @{metrics.askWall.price.toFixed(2)} ({metrics.askWall.size.toLocaleString()})
            </span>
          )}
        </span>
      </div>

      <div className="flex justify-between items-center text-[10px] text-zinc-400 font-mono mt-0.5">
        <span>Imbalance: {(metrics.imbalance * 100).toFixed(1)}%</span>
        {abs.n8 !== null && abs.n8 >= 8 && (
          <span className={`text-[9px] font-bold px-1.5 py-0.5 rounded shadow-[0_0_4px_rgba(99,102,241,0.2)] animate-pulse ${
            abs.dominantSide === 'BULL'
              ? 'bg-emerald-950/80 text-emerald-300 border border-emerald-900/40'
              : 'bg-red-950/80 text-red-300 border border-red-900/40'
          }`}>
            🛡️ {abs.dominantSide === 'BULL' ? 'BULL ABS' : 'BEAR ABS'}
          </span>
        )}
      </div>

      {/* Passive LOB Absorption LED block meter */}
      {abs.n8 !== null && (
        <div className="flex flex-col gap-1 mt-1 border-t border-zinc-700/20 pt-1.5">
          <div className="flex justify-between items-center text-[9px] text-zinc-500 font-mono">
            <span>Passive Absorption (n8)</span>
            <span>{abs.n8}/16</span>
          </div>
          <div className="flex gap-[1px] h-2.5 bg-zinc-950 p-[1px] rounded border border-zinc-800/60">
            {Array.from({ length: 16 }).map((_, idx) => {
              const isActive = abs.n8 !== null && idx < abs.n8;
              const isThresholdZone = idx >= 8;

              let blockColor = 'bg-zinc-900/40';
              if (isActive) {
                if (abs.dominantSide === 'BULL') {
                  blockColor = isThresholdZone
                    ? 'bg-emerald-400 shadow-[0_0_4px_#34d399]'
                    : 'bg-emerald-600/75';
                } else {
                  blockColor = isThresholdZone
                    ? 'bg-rose-500 shadow-[0_0_4px_#f43f5e]'
                    : 'bg-rose-700/75';
                }
              } else if (isThresholdZone) {
                blockColor = 'bg-zinc-950 border-l border-zinc-900/30';
              }

              return (
                <div
                  key={idx}
                  className={`flex-1 rounded-[1px] transition-all duration-300 ${blockColor}`}
                />
              );
            })}
          </div>
        </div>
      )}
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
  const score = event.absorptionScore ?? event.score;
  const delta = event.delta ?? event.aggressiveDelta;
  const type = event.absorptionType ?? 'CLASSIC';
  const isDivergence = type === 'DIVERGENCE';
  const explanation = event.explanation ?? '';
  return (
    <div className="flex items-center gap-2 px-2 py-1 text-[11px] rounded bg-zinc-800/40 hover:bg-zinc-800/70 transition-colors">
      <span className="shrink-0 px-1.5 py-0.5 rounded text-[9px] font-bold bg-purple-900/60 text-purple-300">
        ABS
      </span>
      <span className={`shrink-0 px-1 py-0.5 rounded text-[9px] font-semibold ${
        isDivergence ? 'bg-violet-900/60 text-violet-200' : 'bg-indigo-900/40 text-indigo-300'
      }`} title={isDivergence ? 'Delta and price oppose — strongest absorption signal' : 'Delta and price agree — trend confirmation'}>
        {isDivergence ? 'DIVERG' : 'CLASSIC'}
      </span>
      <span className="text-zinc-400 shrink-0">{event.instrument}</span>
      <span className={`shrink-0 ${isBull ? 'text-emerald-400' : 'text-red-400'}`}>
        {isBull ? 'BULL' : 'BEAR'}
      </span>
      <span className="text-zinc-500 truncate">
        {explanation ? <span className="text-zinc-400">{explanation} · </span> : null}
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

function DistributionRowView({ event }: { event: DistributionRow }) {
  const isDistribution = event.type === 'DISTRIBUTION';
  const confColor =
    event.confidenceScore >= 80 ? 'text-red-300'
    : event.confidenceScore >= 60 ? 'text-orange-300'
    : 'text-yellow-300';

  return (
    <div className="flex items-center gap-2 px-2 py-1 text-[11px] rounded bg-zinc-800/40 hover:bg-zinc-800/70 transition-colors border-l-2 border-l-amber-600/60">
      <span className="shrink-0 px-1.5 py-0.5 rounded text-[9px] font-bold bg-amber-900/60 text-amber-300">
        {isDistribution ? 'DIST' : 'ACCUM'}
      </span>
      <span className="text-zinc-400 shrink-0">{event.instrument}</span>
      <span className={`shrink-0 ${isDistribution ? 'text-red-400' : 'text-emerald-400'}`}>
        {isDistribution ? 'BEAR' : 'BULL'}
      </span>
      <span className="text-zinc-500 truncate">
        ×{event.consecutiveCount} · avg: {event.avgScore?.toFixed(1) ?? '—'}
        {' · conf: '}<span className={confColor}>{event.confidenceScore}</span>
        {event.resistanceLevel != null ? ` · lvl@${event.resistanceLevel.toFixed(2)}` : ''}
      </span>
      <span className="ml-auto text-zinc-600 shrink-0" title={event.timestamp}>
        {formatRelativeTime(event.timestamp)}
      </span>
    </div>
  );
}

function MomentumRowView({ event }: { event: MomentumRow }) {
  const isBull = event.side === 'BULLISH_MOMENTUM';
  const score = event.momentumScore ?? event.score;
  const delta = event.delta ?? event.aggressiveDelta;
  return (
    <div className="flex items-center gap-2 px-2 py-1 text-[11px] rounded bg-zinc-800/40 hover:bg-zinc-800/70 transition-colors border-l-2 border-l-cyan-600/60">
      <span className="shrink-0 px-1.5 py-0.5 rounded text-[9px] font-bold bg-cyan-900/60 text-cyan-300">
        MOM
      </span>
      <span className="text-zinc-400 shrink-0">{event.instrument}</span>
      <span className={`shrink-0 ${isBull ? 'text-emerald-400' : 'text-red-400'}`}>
        {isBull ? 'BULL' : 'BEAR'}
      </span>
      <span className="text-zinc-500 truncate">
        score: {score?.toFixed(1) ?? '—'} · delta: {delta?.toLocaleString() ?? '—'}
        {event.priceMoveTicks != null ? ` · move: ${event.priceMoveTicks.toFixed(1)}t` : ''}
      </span>
      <span className="ml-auto text-zinc-600 shrink-0" title={event.timestamp}>
        {formatRelativeTime(event.timestamp)}
      </span>
    </div>
  );
}

function CyclePhaseBar({ phase }: { phase: CycleRow['currentPhase'] }) {
  const steps: Array<{ key: CycleRow['currentPhase']; label: string }> = [
    { key: 'PHASE_1', label: 'P1' },
    { key: 'PHASE_2', label: 'P2' },
    { key: 'PHASE_3', label: 'P3' },
    { key: 'COMPLETE', label: 'DONE' },
  ];
  const activeIndex = steps.findIndex(s => s.key === phase);

  return (
    <div className="flex items-center gap-0.5">
      {steps.map((s, i) => {
        const active = i <= activeIndex;
        return (
          <span
            key={s.key}
            className={`px-1.5 py-0.5 rounded text-[9px] font-semibold ${
              active
                ? i === steps.length - 1
                  ? 'bg-emerald-900/80 text-emerald-200'
                  : 'bg-indigo-900/80 text-indigo-200'
                : 'bg-zinc-800 text-zinc-600'
            }`}
          >
            {s.label}
          </span>
        );
      })}
    </div>
  );
}

function CycleRowView({ event }: { event: CycleRow }) {
  const isBearish = event.cycleType === 'BEARISH_CYCLE';
  const complete = event.currentPhase === 'COMPLETE';
  const confColor =
    event.confidence >= 90 ? 'text-red-300'
    : event.confidence >= 80 ? 'text-orange-300'
    : 'text-yellow-300';
  const move = Math.round(event.totalPriceMove);

  return (
    <div className={`flex items-center gap-2 px-2 py-1 text-[11px] rounded bg-zinc-800/40 hover:bg-zinc-800/70 transition-colors border-l-2 ${
      complete ? 'border-l-emerald-500' : 'border-l-indigo-500/60'
    }`}>
      <span className={`shrink-0 px-1.5 py-0.5 rounded text-[9px] font-bold ${
        complete ? 'bg-emerald-900/60 text-emerald-300' : 'bg-indigo-900/60 text-indigo-300'
      }`}>
        CYCLE
      </span>
      <span className="text-zinc-400 shrink-0">{event.instrument}</span>
      <span className={`shrink-0 ${isBearish ? 'text-red-400' : 'text-emerald-400'}`}>
        {isBearish ? '▼ BEAR' : '▲ BULL'}
      </span>
      <CyclePhaseBar phase={event.currentPhase} />
      <span className="text-zinc-500 truncate">
        conf: <span className={confColor}>{event.confidence}</span>
        {complete ? ` · move: ${move}pts · ${event.totalDurationMinutes.toFixed(1)}min` : ''}
      </span>
      <span className="ml-auto text-zinc-600 shrink-0" title={event.timestamp}>
        {formatRelativeTime(event.timestamp)}
      </span>
    </div>
  );
}

function parseBuyPct(gates: QuantGateView[]): number | null {
  const g4 = gates?.find((g) => g.gate === 'G4_BUY_PCT_LOW');
  const l4 = gates?.find((g) => g.gate === 'L4_BUY_PCT_HIGH');
  const gate = g4 || l4;
  if (!gate || !gate.reason) return null;

  const match = gate.reason.match(/buy%=([\d.]+)/);
  return match ? parseFloat(match[1]) : null;
}

interface QuantTelemetryDashboardProps {
  gates: QuantGateView[];
  active: string;
}

function QuantTelemetryDashboard({ gates, active }: QuantTelemetryDashboardProps) {
  const thresholds = INSTRUMENT_THRESHOLDS[active];
  const { delta, trend } = parseDeltaAndTrend(gates);
  const buyPct = parseBuyPct(gates);
  const abs = parseAbsorption(gates);
  const da = parseDistAccu(gates);

  const strongDelta = thresholds?.strongDelta ?? 100;
  const highDelta = thresholds?.highDelta ?? 200;

  return (
    <div className="bg-zinc-950/40 p-4 rounded-xl border border-zinc-800/60 backdrop-blur-md relative shadow-lg">
      <div className="flex items-center justify-between mb-3 border-b border-zinc-800/40 pb-2">
        <div className="flex items-center gap-2">
          <div className="relative flex h-2 w-2">
            <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
            <span className="relative inline-flex rounded-full h-2 w-2 bg-emerald-500"></span>
          </div>
          <span className="text-[11px] font-bold text-zinc-200 tracking-wider uppercase">LOB Microstructure Telemetry ({active})</span>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-3.5">
        {/* Widget 1: CVD Momentum */}
        <div className="flex flex-col gap-2 p-3 bg-zinc-900/40 rounded-lg border border-zinc-800/40">
          <div className="flex justify-between items-center">
            <div className="flex items-center gap-1.5">
              <svg className="w-3.5 h-3.5 text-cyan-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M13 10V3L4 14h7v7l9-11h-7z" />
              </svg>
              <span className="text-[11px] font-bold text-zinc-300 font-sans">CVD Momentum (Delta)</span>
            </div>
            <div className="flex items-center gap-2">
              {trend.length > 0 && (
                <div className="flex items-center gap-1">
                  <span className="text-[8px] text-zinc-500 font-mono">Trend:</span>
                  <div className="flex items-end gap-[2px] h-4 px-1 bg-zinc-950/60 rounded border border-zinc-800/40">
                    {trend.map((val, idx) => {
                      const heightPercent = Math.min(100, (Math.abs(val) / highDelta) * 100);
                      const isPositive = val >= 0;
                      const barBg = isPositive ? 'bg-cyan-500/75' : 'bg-rose-500/75';
                      return (
                        <div
                          key={idx}
                          className={`w-1 rounded-t transition-all duration-300 ${barBg}`}
                          style={{ height: `${Math.max(20, heightPercent)}%` }}
                          title={`Scan t-${trend.length - 1 - idx}: ${val >= 0 ? '+' : ''}${val}`}
                        />
                      );
                    })}
                  </div>
                </div>
              )}
              <span className={`text-[10px] font-mono font-extrabold px-1.5 py-0.5 rounded border ${
                delta !== null && Math.abs(delta) >= strongDelta
                  ? delta >= 0
                    ? 'bg-cyan-950/80 text-cyan-300 border-cyan-700/80 shadow-[0_0_6px_rgba(34,211,238,0.25)]'
                    : 'bg-rose-950/80 text-rose-300 border-rose-700/80 shadow-[0_0_6px_rgba(244,63,94,0.25)]'
                  : 'bg-zinc-950/60 text-zinc-400 border-zinc-800/60'
              }`}>
                {delta !== null ? `Δ = ${delta >= 0 ? '+' : ''}${delta.toFixed(0)}` : 'Δ = —'}
              </span>
            </div>
          </div>

          <div className="relative mt-1">
            <div className="h-1.5 w-full bg-zinc-950 rounded-full border border-zinc-800/50 overflow-hidden flex">
              <div className="w-1/2 h-full flex justify-end">
                {delta !== null && delta < 0 && (
                  <div
                    className={`h-full transition-all duration-500 ${
                      Math.abs(delta) >= highDelta
                        ? 'bg-gradient-to-l from-red-600 to-rose-500 shadow-[0_0_8px_#f43f5e]'
                        : Math.abs(delta) >= strongDelta
                        ? 'bg-gradient-to-l from-rose-500 to-rose-400 shadow-[0_0_4px_#fb7185]'
                        : 'bg-gradient-to-l from-rose-400/80 to-zinc-800'
                    }`}
                    style={{ width: `${Math.min(100, (Math.abs(delta) / highDelta) * 100)}%` }}
                  />
                )}
              </div>
              <div className="w-[1px] h-full bg-zinc-700 z-10" />
              <div className="w-1/2 h-full flex justify-start">
                {delta !== null && delta > 0 && (
                  <div
                    className={`h-full transition-all duration-500 ${
                      delta >= highDelta
                        ? 'bg-gradient-to-r from-cyan-500 to-indigo-500 shadow-[0_0_8px_#06b6d4]'
                        : delta >= strongDelta
                        ? 'bg-gradient-to-r from-cyan-400 to-cyan-500 shadow-[0_0_4px_#22d3ee]'
                        : 'bg-gradient-to-r from-cyan-400/80 to-zinc-800'
                    }`}
                    style={{ width: `${Math.min(100, (delta / highDelta) * 100)}%` }}
                  />
                )}
              </div>
            </div>

            <div className="flex justify-between items-center text-[7px] text-zinc-500 font-mono mt-1 px-0.5">
              <span className="w-1/5 text-left">-High (±{highDelta})</span>
              <span className="w-1/5 text-center">-Strong (±{strongDelta})</span>
              <span className="w-1/5 text-center font-bold text-zinc-600">0</span>
              <span className="w-1/5 text-center">+Strong (±{strongDelta})</span>
              <span className="w-1/5 text-right">+High (±{highDelta})</span>
            </div>
          </div>
        </div>

        {/* Widget 2: Order Flow Balance */}
        <div className="flex flex-col gap-2 p-3 bg-zinc-900/40 rounded-lg border border-zinc-800/40">
          <div className="flex justify-between items-center">
            <div className="flex items-center gap-1.5">
              <svg className="w-3.5 h-3.5 text-emerald-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M3 6l3 1m0 0l-3 9a5.002 5.002 0 006.001 0M6 7l3 9M6 7l6-2m6 2l3-1m-3 1l-3 9a5.002 5.002 0 006.001 0M18 7l3 9m-3-9l-6-2m0-2v2m0 16V5m0 16H9m3 0h3" />
              </svg>
              <span className="text-[11px] font-bold text-zinc-300 font-sans">Order Flow Balance</span>
            </div>
            <div className="flex gap-1.5">
              <span className="text-[9px] font-mono font-bold bg-emerald-950/60 text-emerald-400 border border-emerald-900/40 px-1 rounded">
                B: {buyPct !== null ? `${buyPct.toFixed(1)}%` : '—'}
              </span>
              <span className="text-[9px] font-mono font-bold bg-rose-950/60 text-rose-400 border border-rose-900/40 px-1 rounded">
                S: {buyPct !== null ? `${(100 - buyPct).toFixed(1)}%` : '—'}
              </span>
            </div>
          </div>

          <div className="relative mt-1">
            <div className="h-1.5 w-full bg-zinc-950 rounded-full border border-zinc-800/50 overflow-hidden flex">
              <div
                className="h-full bg-gradient-to-r from-emerald-600 to-emerald-500 transition-all duration-500"
                style={{ width: buyPct !== null ? `${buyPct}%` : '50%' }}
              />
              <div className="h-full bg-gradient-to-r from-rose-500 to-rose-600 transition-all duration-500 flex-1" />
            </div>

            <div className="absolute inset-0 top-0 h-1.5 pointer-events-none flex justify-between px-[2px]">
              <div className="w-[1px] h-2.5 bg-zinc-600/40" style={{ marginLeft: '48%' }} />
              <div className="w-[1px] h-2.5 bg-zinc-600/40" style={{ marginRight: '48%' }} />
            </div>

            <div className="flex justify-between items-center text-[7px] text-zinc-500 font-mono mt-1 px-0.5">
              <span>0%</span>
              <span className="text-rose-400/70 font-semibold">Bearish Limit (48%)</span>
              <span className="font-bold text-zinc-600">50%</span>
              <span className="text-emerald-400/70 font-semibold">Bullish Limit (52%)</span>
              <span>100%</span>
            </div>
          </div>
        </div>

        {/* Widget 3: Passive LOB Absorption */}
        <div className="flex flex-col gap-2 p-3 bg-zinc-900/40 rounded-lg border border-zinc-800/40">
          <div className="flex justify-between items-center">
            <div className="flex items-center gap-1.5">
              <svg className="w-3.5 h-3.5 text-indigo-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z" />
              </svg>
              <span className="text-[11px] font-bold text-zinc-300 font-sans">Passive LOB Absorption</span>
            </div>
            {abs.n8 !== null && abs.n8 >= 8 && (
              <span className={`text-[8px] font-mono font-extrabold px-1.5 py-0.5 rounded shadow-[0_0_6px_rgba(99,102,241,0.25)] animate-pulse ${
                abs.dominantSide === 'BULL'
                  ? 'bg-emerald-950/80 text-emerald-300 border border-emerald-800/50'
                  : 'bg-red-950/80 text-red-300 border border-red-800/50'
              }`}>
                🛡️ {abs.dominantSide === 'BULL' ? 'BULL ABS' : 'BEAR ABS'}
              </span>
            )}
          </div>

          <div className="flex flex-col gap-1 mt-0.5">
            <div className="flex justify-between items-center text-[9px] text-zinc-400 font-mono mb-0.5">
              <span>Passive Scans (n8): <strong className="text-zinc-200 font-semibold">{abs.n8 !== null ? abs.n8 : '—'}</strong> / 16</span>
              <span className="text-zinc-500">Dominance: <strong className={abs.dominantSide === 'BULL' ? 'text-emerald-400' : abs.dominantSide === 'BEAR' ? 'text-rose-400' : 'text-zinc-400'}>{abs.dominantSide || 'None'}</strong></span>
            </div>

            <div className="flex gap-[2px] h-2.5 bg-zinc-950 p-[2px] rounded border border-zinc-800/50">
              {Array.from({ length: 16 }).map((_, idx) => {
                const isActive = abs.n8 !== null && idx < abs.n8;
                const isThresholdZone = idx >= 8;

                let blockColor = 'bg-zinc-900/30';
                if (isActive) {
                  if (abs.dominantSide === 'BULL') {
                    blockColor = isThresholdZone
                      ? 'bg-emerald-400 shadow-[0_0_4px_#34d399]'
                      : 'bg-emerald-600/75';
                  } else {
                    blockColor = isThresholdZone
                      ? 'bg-rose-500 shadow-[0_0_4px_#f43f5e]'
                      : 'bg-rose-700/75';
                  }
                } else if (isThresholdZone) {
                  blockColor = 'bg-zinc-950 border-l border-zinc-900/40';
                }

                return (
                  <div
                    key={idx}
                    className={`flex-1 rounded-[1px] transition-all duration-300 ${blockColor}`}
                  />
                );
              })}
            </div>

            <div className="flex justify-between text-[7px] text-zinc-500 font-mono mt-0.5 px-0.5">
              <span>0 (Low)</span>
              <span className="text-indigo-400/70 font-semibold" style={{ marginRight: '34%' }}>Wall Threshold (8)</span>
              <span>16 (Max)</span>
            </div>
          </div>
        </div>

        {/* Widget 4: A/D Market Veto */}
        <div className={`flex flex-col gap-2 p-3 rounded-lg border transition-all duration-500 ${
          da.status === 'BLOQUE'
            ? 'bg-amber-950/15 border-amber-800/60 shadow-[0_0_10px_rgba(217,119,6,0.12)]'
            : 'bg-zinc-900/40 border-zinc-800/40'
        }`}>
          <div className="flex justify-between items-center">
            <div className="flex items-center gap-1.5">
              <svg className="w-3.5 h-3.5 text-amber-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
              </svg>
              <span className="text-[11px] font-bold text-zinc-300 font-sans">A/D Structural Veto</span>
            </div>
            {da.status === 'BLOQUE' ? (
              <span className="text-[8px] font-mono font-extrabold bg-red-950 text-red-400 border border-red-800/50 px-1.5 py-0.5 rounded animate-pulse">
                🚫 VETO ACTIVE
              </span>
            ) : da.type !== null ? (
              <span className="text-[8px] font-mono font-bold bg-zinc-950 text-emerald-400 border border-emerald-900/40 px-1.5 py-0.5 rounded">
                ✅ PASS
              </span>
            ) : null}
          </div>

          {da.type !== null ? (
            <div className="flex flex-col gap-1 mt-0.5">
              <div className="flex justify-between items-center text-[9px] font-mono">
                <span className="text-zinc-400">Process: <strong className={da.type === 'ACCUMULATION' ? 'text-purple-400' : 'text-amber-400'}>{da.type}</strong></span>
                <span className="text-zinc-400">Force: <strong className="text-zinc-200">{da.conf}%</strong></span>
              </div>

              <div className="relative h-1.5 w-full bg-zinc-950 rounded-full border border-zinc-800/50 overflow-hidden mt-0.5">
                <div
                  className={`h-full transition-all duration-500 ${
                    da.status === 'BLOQUE'
                      ? 'bg-gradient-to-r from-amber-600 to-red-600'
                      : 'bg-gradient-to-r from-purple-600 to-indigo-500'
                  }`}
                  style={{ width: `${da.conf}%` }}
                />

                <div
                  className="absolute inset-y-0 w-[2px] bg-red-500 shadow-[0_0_4px_#ef4444] z-10"
                  style={{ left: da.threshold !== null ? `${da.threshold}%` : '50%' }}
                />
              </div>

              <div className="flex justify-between text-[7px] text-zinc-500 font-mono mt-0.5 px-0.5">
                <span>0%</span>
                <span className="text-red-400/70 font-semibold" style={{ left: da.threshold !== null ? `${da.threshold}%` : '50%', transform: 'translateX(-50%)', position: 'absolute' }}>Veto Limit ({da.threshold}%)</span>
                <span>100%</span>
              </div>
            </div>
          ) : (
            <div className="flex flex-col items-center justify-center h-[34px] text-center px-1">
              <p className="text-[9px] text-zinc-500 font-mono leading-tight">
                No active accumulation or distribution scans.
              </p>
            </div>
          )}
        </div>
      </div>
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
    distributionEvents,
    momentumEvents,
    cycleEvents,
    connected,
  } = useOrderFlow();

  const { snapshots } = useQuantStream();

  // Historical lists seeded on mount via REST, then kept fresh by merging
  // WebSocket events (prepend, cap at HISTORY_LIMIT).
  const [icebergHistory, setIcebergHistory] = useState<IcebergRow[]>([]);
  const [absorptionHistory, setAbsorptionHistory] = useState<AbsorptionRow[]>([]);
  const [spoofingHistory, setSpoofingHistory] = useState<SpoofingRow[]>([]);
  const [distributionHistory, setDistributionHistory] = useState<DistributionRow[]>([]);
  const [momentumHistory, setMomentumHistory] = useState<MomentumRow[]>([]);
  const [cycleHistory, setCycleHistory] = useState<CycleRow[]>([]);

  // --- REST seed on mount / when selected instrument changes -----------------
  useEffect(() => {
    if (!selectedInstrument) {
      setIcebergHistory([]);
      setAbsorptionHistory([]);
      setSpoofingHistory([]);
      setDistributionHistory([]);
      setMomentumHistory([]);
      setCycleHistory([]);
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

    api
      .getDistributionEvents(selectedInstrument, HISTORY_LIMIT)
      .then(rows => {
        if (!cancelled) setDistributionHistory(rows as unknown as DistributionRow[]);
      })
      .catch(() => { /* best-effort */ });

    api
      .getMomentumEvents(selectedInstrument, HISTORY_LIMIT)
      .then(rows => {
        if (!cancelled) setMomentumHistory(rows as unknown as MomentumRow[]);
      })
      .catch(() => { /* best-effort */ });

    api
      .getCycleEvents(selectedInstrument, HISTORY_LIMIT)
      .then(rows => {
        if (!cancelled) setCycleHistory(rows as unknown as CycleRow[]);
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

  useEffect(() => {
    if (!selectedInstrument || distributionEvents.length === 0) return;
    const latest = distributionEvents[0];
    if (latest.instrument !== selectedInstrument) return;
    setDistributionHistory(prev => prependUnique(prev, latest, HISTORY_LIMIT));
  }, [distributionEvents, selectedInstrument]);

  useEffect(() => {
    if (!selectedInstrument || momentumEvents.length === 0) return;
    const latest = momentumEvents[0];
    if (latest.instrument !== selectedInstrument) return;
    setMomentumHistory(prev => prependUnique(prev, latest as MomentumRow, HISTORY_LIMIT));
  }, [momentumEvents, selectedInstrument]);

  useEffect(() => {
    if (!selectedInstrument || cycleEvents.length === 0) return;
    const latest = cycleEvents[0];
    if (latest.instrument !== selectedInstrument) return;
    setCycleHistory(prev => prependUnique(prev, latest, HISTORY_LIMIT));
  }, [cycleEvents, selectedInstrument]);

  // --- Live metrics filtering ------------------------------------------------
  const flowEntries = Array.from(orderFlowData.values());
  const filteredFlow = selectedInstrument
    ? flowEntries.filter(m => m.instrument === selectedInstrument)
    : flowEntries;

  const depthEntries = Array.from(depthData.values())
    .filter(m => DEPTH_INSTRUMENTS.includes(m.instrument as typeof DEPTH_INSTRUMENTS[number]));
  const filteredDepth = selectedInstrument
    ? depthEntries.filter(m => m.instrument === selectedInstrument)
    : depthEntries;

  // --- Live combined event log (quick cross-type glance) ---------------------
  const liveFeed = [
    ...absorptionEvents.map(e => ({ event: e, type: 'absorption' as const })),
    ...spoofingEvents.map(e => ({ event: e, type: 'spoofing' as const })),
    ...icebergEvents.map(e => ({ event: e, type: 'iceberg' as const })),
  ].sort((a, b) => new Date(b.event.timestamp).getTime() - new Date(a.event.timestamp).getTime())
   .slice(0, HISTORY_LIMIT);

  const filteredLiveFeed = selectedInstrument
    ? liveFeed.filter(e => e.event.instrument === selectedInstrument)
    : liveFeed;

  // --- Parse Dist/Accu for selected instrument (fallback to MNQ) --------------
  const activeInstrument = selectedInstrument || 'MNQ';
  const activeSnap = snapshots[activeInstrument] || null;
  const activeGates = activeSnap?.gates ?? [];
  const da = parseDistAccu(activeGates);

  return (
    <div className="flex flex-col gap-3 p-3 rounded-lg bg-zinc-900 border border-zinc-800">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-zinc-200">Order Flow</h3>
        <span className={`w-2 h-2 rounded-full ${connected ? 'bg-emerald-400' : 'bg-red-500'}`} />
      </div>

      {/* LOB Microstructure Telemetry Dashboard */}
      <QuantTelemetryDashboard gates={activeGates} active={activeInstrument} />

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

      {/* Section 2b: Smart Money Cycle (highest-signal meta-detector) */}
      <div>
        <h4 className="text-[10px] uppercase tracking-wider text-zinc-500 mb-1.5">
          Smart Money Cycle{selectedInstrument ? ` — ${selectedInstrument}` : ''}
          <span className="text-zinc-600 normal-case tracking-normal"> (conf ≥ 70)</span>
        </h4>
        <div className="flex flex-col gap-0.5 max-h-48 overflow-y-auto scrollbar-thin scrollbar-thumb-zinc-700">
          {cycleHistory.length > 0
            ? cycleHistory.map((e, i) => (
                <CycleRowView key={`cycle-${e.timestamp}-${i}`} event={e} />
              ))
            : <p className="text-xs text-zinc-600 italic">
                {selectedInstrument ? 'No cycle events yet' : 'Select an instrument to see smart money cycles'}
              </p>
          }
        </div>
      </div>

      {/* Section 2c: Distribution / Accumulation setups */}
      <div>
        <h4 className="text-[10px] uppercase tracking-wider text-zinc-500 mb-1.5">
          Distribution / Accumulation{selectedInstrument ? ` — ${selectedInstrument}` : ''}
        </h4>

        {/* Glow-highlighted Market Veto Slider */}
        {da.type !== null && (
          <div className={`mb-2 p-2 rounded bg-zinc-800/40 border border-zinc-700/30 transition-all duration-500 ${
            da.status === 'BLOQUE'
              ? 'bg-amber-950/15 border-amber-800/40 shadow-[0_0_8px_rgba(217,119,6,0.15)] animate-pulse'
              : ''
          }`}>
            <div className="flex justify-between items-center mb-1">
              <span className="text-[10px] font-semibold text-zinc-300">
                Veto scan:{' '}
                <span className={da.type === 'ACCUMULATION' ? 'text-purple-400 font-bold' : 'text-amber-400 font-bold'}>
                  {da.type}
                </span>
              </span>
              {da.status === 'BLOQUE' ? (
                <span className="text-[8px] font-mono font-extrabold bg-red-950/80 text-red-400 border border-red-900/40 px-1.5 py-0.5 rounded shadow-[0_0_4px_#ef4444]">
                  🚫 VETO ACTIVE
                </span>
              ) : (
                <span className="text-[8px] font-mono font-bold bg-zinc-950/60 text-emerald-400 border border-emerald-900/40 px-1.5 py-0.5 rounded">
                  ✅ PASS
                </span>
              )}
            </div>

            <div className="relative h-1.5 w-full bg-zinc-950 rounded-full border border-zinc-800 overflow-hidden">
              <div
                className={`h-full transition-all duration-500 ${
                  da.status === 'BLOQUE'
                    ? 'bg-gradient-to-r from-amber-600 to-red-600'
                    : 'bg-gradient-to-r from-purple-600 to-indigo-500'
                }`}
                style={{ width: `${da.conf}%` }}
              />

              <div
                className="absolute top-0 bottom-0 w-[2px] bg-red-500 shadow-[0_0_4px_#ef4444] z-10"
                style={{ left: da.threshold !== null ? `${da.threshold}%` : '50%' }}
              />
            </div>
            <div className="flex justify-between text-[7px] text-zinc-500 font-mono mt-0.5">
              <span>Force: {da.conf}%</span>
              <span className="text-red-400/80 font-semibold">Veto Limit ({da.threshold}%)</span>
              <span>100%</span>
            </div>
          </div>
        )}

        <div className="flex flex-col gap-0.5 max-h-40 overflow-y-auto scrollbar-thin scrollbar-thumb-zinc-700">
          {distributionHistory.length > 0
            ? distributionHistory.map((e, i) => (
                <DistributionRowView key={`dist-${e.timestamp}-${i}`} event={e} />
              ))
            : <p className="text-xs text-zinc-600 italic">
                {selectedInstrument ? 'No distribution setups yet' : 'Select an instrument'}
              </p>
          }
        </div>
      </div>

      {/* Section 2d: Aggressive Momentum Bursts */}
      <div>
        <h4 className="text-[10px] uppercase tracking-wider text-zinc-500 mb-1.5">
          Momentum Bursts{selectedInstrument ? ` — ${selectedInstrument}` : ''}
        </h4>
        <div className="flex flex-col gap-0.5 max-h-40 overflow-y-auto scrollbar-thin scrollbar-thumb-zinc-700">
          {momentumHistory.length > 0
            ? momentumHistory.map((e, i) => (
                <MomentumRowView key={`mom-${e.timestamp}-${i}`} event={e} />
              ))
            : <p className="text-xs text-zinc-600 italic">
                {selectedInstrument ? 'No recent momentum bursts' : 'Select an instrument'}
              </p>
          }
        </div>
      </div>

      {/* Section 3: Iceberg Activity */}
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

      {/* Section 4: Absorption history */}
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

      {/* Section 5: Spoofing history */}
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

      {/* Section 6: Live combined feed */}
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
