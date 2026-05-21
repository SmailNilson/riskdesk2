'use client';

import { useEffect, useState } from 'react';
import { api } from '@/app/lib/api';
import { useQuantStream, type AutoArmInstrumentState } from '@/app/hooks/useQuantStream';
import QuantAdvisorBadge from './QuantAdvisorBadge';
import QuantNarrationPanel from './QuantNarrationPanel';
import QuantManualTradeModal from './QuantManualTradeModal';
import {
  GATE_LABELS,
  LONG_GATES,
  QUANT_INSTRUMENTS,
  SHORT_GATES,
  type AdviceView,
  type PatternView,
  type QuantGateView,
  type QuantInstrument,
  type QuantSnapshotView,
  type StructuralBlockView,
  type StructuralWarningView,
} from './types';

function scoreClass(score: number): string {
  if (score >= 7) return 'bg-emerald-600 text-white';
  if (score >= 6) return 'bg-amber-500 text-white';
  if (score >= 4) return 'bg-slate-600 text-white';
  return 'bg-slate-700 text-slate-300';
}

function formatPrice(p: number | null | undefined, instrument?: string): string {
  if (p === null || p === undefined || Number.isNaN(p)) return '—';
  if (instrument === 'E6') return p.toFixed(5);
  return p.toFixed(2);
}

function formatDayMove(move: number | null | undefined, instrument: string): string {
  if (move === null || move === undefined || Number.isNaN(move)) return '—';
  const prefix = move >= 0 ? '+' : '';
  if (instrument === 'E6') {
    return `${prefix}${move.toFixed(5)}`;
  }
  if (instrument === 'MCL') {
    return `${prefix}${move.toFixed(2)}pts`;
  }
  return `${prefix}${move.toFixed(0)}pts`;
}

function cleanReasonText(reason: string | null | undefined): string {
  if (!reason) return '';
  const index = reason.indexOf(' | Confirmations:');
  if (index !== -1) {
    return reason.substring(0, index).trim();
  }
  return reason;
}

interface FrontendThresholds {
  stableBand: string;
  strongDelta: number;
  highDelta: number;
}

const INSTRUMENT_THRESHOLDS: Record<string, FrontendThresholds> = {
  MNQ: { stableBand: '5.0 pts', strongDelta: 200, highDelta: 400 },
  MGC: { stableBand: '1.0 pts', strongDelta: 50, highDelta: 100 },
  MCL: { stableBand: '0.10 pts', strongDelta: 50, highDelta: 100 },
  E6:  { stableBand: '0.00050 px', strongDelta: 50, highDelta: 100 },
};

type PatternAction = 'TRADE' | 'WAIT' | 'AVOID';

/** Pattern action seen from {@code direction}. Reads {@code longAction} when
 *  the backend supplies it (PR #310); otherwise mirrors {@code action} on the
 *  fly so older backends still render correctly (TRADE↔AVOID, WAIT stays WAIT). */
function patternActionFor(
  pattern: PatternView | null | undefined,
  direction: 'SHORT' | 'LONG',
): PatternAction | null {
  if (!pattern) return null;
  if (direction === 'SHORT') return pattern.action;
  if (pattern.longAction) return pattern.longAction;
  switch (pattern.action) {
    case 'TRADE': return 'AVOID';
    case 'AVOID': return 'TRADE';
    case 'WAIT':  return 'WAIT';
    default:      return null;
  }
}

function patternActionClass(action: PatternAction): string {
  switch (action) {
    case 'TRADE': return 'bg-emerald-700 text-emerald-100 border-emerald-600';
    case 'AVOID': return 'bg-red-800 text-red-100 border-red-700';
    case 'WAIT':  return 'bg-slate-700 text-slate-300 border-slate-600';
  }
}

function patternActionGlyph(action: PatternAction): string {
  switch (action) {
    case 'TRADE': return '✅';
    case 'AVOID': return '🚫';
    case 'WAIT':  return '⏸';
  }
}

function parseConfirmations(reason: string | null | undefined): string[] {
  if (!reason) return [];
  const matches = reason.match(/\[[^\]]+\]/g);
  return matches ? matches.map(m => m.slice(1, -1)) : [];
}

function renderConfirmationPill(tag: string): JSX.Element | null {
  switch (tag) {
    case 'Δ CONFIRMED':
      return (
        <span key={tag} className="px-1.5 py-0.5 rounded text-[9px] font-mono font-bold bg-blue-950/60 text-blue-300 border border-blue-800/80">
          ⚡️ Δ Confirmed
        </span>
      );
    case 'ABS BULL ACTIVE':
      return (
        <span key={tag} className="px-1.5 py-0.5 rounded text-[9px] font-mono font-bold bg-emerald-950/60 text-emerald-300 border border-emerald-800/80">
          🛡️ Abs Bull
        </span>
      );
    case 'ABS BEAR ACTIVE':
      return (
        <span key={tag} className="px-1.5 py-0.5 rounded text-[9px] font-mono font-bold bg-red-950/60 text-red-300 border border-red-800/80">
          🛡️ Abs Bear
        </span>
      );
    case 'ACCU CONFIRMED':
      return (
        <span key={tag} className="px-1.5 py-0.5 rounded text-[9px] font-mono font-bold bg-purple-950/60 text-purple-300 border border-purple-800/80">
          📥 Accu Confirmed
        </span>
      );
    case 'DIST CONFIRMED':
      return (
        <span key={tag} className="px-1.5 py-0.5 rounded text-[9px] font-mono font-bold bg-amber-950/60 text-amber-300 border border-amber-800/80">
          📤 Dist Confirmed
        </span>
      );
    default:
      return null;
  }
}

function renderConfidencePill(confidence: 'LOW' | 'MEDIUM' | 'HIGH'): JSX.Element {
  switch (confidence) {
    case 'HIGH':
      return (
        <span className="px-1.5 py-0.5 rounded text-[9px] font-mono font-extrabold bg-violet-950/80 text-violet-300 border border-violet-700 shadow-[0_0_8px_rgba(139,92,246,0.3)] animate-pulse">
          ✨ HIGH CONFIDENCE
        </span>
      );
    case 'MEDIUM':
      return (
        <span className="px-1.5 py-0.5 rounded text-[9px] font-mono font-bold bg-cyan-950/60 text-cyan-300 border border-cyan-800/80">
          💎 MEDIUM CONF
        </span>
      );
    case 'LOW':
      return (
        <span className="px-1.5 py-0.5 rounded text-[9px] font-mono font-medium bg-slate-950/40 text-slate-400 border border-slate-800">
          LOW CONF
        </span>
      );
  }
}

// ── Microstructure live telemetry parser helpers ──────────────────────────

function parseDeltaAndTrend(gates: QuantGateView[]): { delta: number | null; trend: number[] } {
  const g3 = gates?.find((g) => g.gate === 'G3_DELTA_NEG');
  const l3 = gates?.find((g) => g.gate === 'L3_DELTA_POS');
  const gate = g3 || l3;
  if (!gate || !gate.reason) return { delta: null, trend: [] };

  const deltaMatch = gate.reason.match(/Δ=([\d.-]+)/);
  const delta = deltaMatch && deltaMatch[1] !== 'None' ? parseFloat(deltaMatch[1]) : null;

  const trendMatch = gate.reason.match(/\[([\d→\s.-]+)\]/);
  const trend = trendMatch
    ? trendMatch[1]
        .split('→')
        .map((v) => parseFloat(v.trim()))
        .filter((v) => !isNaN(v))
    : [];

  return { delta, trend };
}

function parseBuyPct(gates: QuantGateView[]): number | null {
  const g4 = gates?.find((g) => g.gate === 'G4_BUY_PCT_LOW');
  const l4 = gates?.find((g) => g.gate === 'L4_BUY_PCT_HIGH');
  const gate = g4 || l4;
  if (!gate || !gate.reason) return null;

  const match = gate.reason.match(/buy%=([\d.]+)/);
  return match ? parseFloat(match[1]) : null;
}

interface AbsorptionData {
  n8: number | null;
  dominantSide: 'BULL' | 'BEAR' | null;
  maxScore: number | null;
}

function parseAbsorption(gates: QuantGateView[]): AbsorptionData {
  const g1 = gates?.find((g) => g.gate === 'G1_ABS_BEAR');
  const l1 = gates?.find((g) => g.gate === 'L1_ABS_BULL');
  const gate = g1 || l1;
  if (!gate || !gate.reason) return { n8: null, dominantSide: null, maxScore: null };

  const passMatch = gate.reason.match(/n8=(\d+)\s+dom=(\w+)\s+maxSc=([\d.]+)/);
  if (passMatch) {
    return {
      n8: parseInt(passMatch[1], 10),
      dominantSide: passMatch[2] as 'BULL' | 'BEAR',
      maxScore: parseFloat(passMatch[3]),
    };
  }

  const failN8Match = gate.reason.match(/n8=(\d+)</);
  const domMatch = gate.reason.match(/dom=(\w+)/);
  return {
    n8: failN8Match ? parseInt(failN8Match[1], 10) : null,
    dominantSide: domMatch ? domMatch[1] as 'BULL' | 'BEAR' : (g1 ? 'BEAR' : 'BULL'),
    maxScore: null,
  };
}

interface DistAccuData {
  type: 'ACCUMULATION' | 'DISTRIBUTION' | null;
  conf: number | null;
  threshold: number | null;
  status: 'BLOQUE' | 'PASS' | 'INACTIVE';
}

function parseDistAccu(gates: QuantGateView[]): DistAccuData {
  const g5 = gates?.find((g) => g.gate === 'G5_ACCU_THRESHOLD');
  const l5 = gates?.find((g) => g.gate === 'L5_DIST_THRESHOLD');

  if (g5 && g5.reason && g5.reason.includes('ACCU')) {
    const match = g5.reason.match(/ACCU\s+(\d+)%\s+vs\s+seuil=(\d+)%/);
    return {
      type: 'ACCUMULATION',
      conf: match ? parseInt(match[1], 10) : null,
      threshold: match ? parseInt(match[2], 10) : null,
      status: g5.reason.includes('BLOQUE') ? 'BLOQUE' : 'PASS',
    };
  }

  if (l5 && l5.reason && l5.reason.includes('DIST')) {
    const match = l5.reason.match(/DIST\s+(\d+)%\s+vs\s+seuil=(\d+)%/);
    return {
      type: 'DISTRIBUTION',
      conf: match ? parseInt(match[1], 10) : null,
      threshold: match ? parseInt(match[2], 10) : null,
      status: l5.reason.includes('BLOQUE') ? 'BLOQUE' : 'PASS',
    };
  }

  return { type: null, conf: null, threshold: null, status: 'INACTIVE' };
}

interface QuantTelemetryDashboardProps {
  gates: QuantGateView[];
  active: QuantInstrument;
  onClose: () => void;
}

/**
 * Premium glassmorphic telemetry dashboard extracting real-time cumulative
 * volume delta, order flow imbalance, passive iceberg walls, and structural vetos
 * directly from 7-Gates reasons with high-fidelity visual elements.
 */
function QuantTelemetryDashboard({ gates, active, onClose }: QuantTelemetryDashboardProps) {
  const thresholds = INSTRUMENT_THRESHOLDS[active];
  const { delta, trend } = parseDeltaAndTrend(gates);
  const buyPct = parseBuyPct(gates);
  const abs = parseAbsorption(gates);
  const da = parseDistAccu(gates);

  const strongDelta = thresholds?.strongDelta ?? 100;
  const highDelta = thresholds?.highDelta ?? 200;

  return (
    <div className="mb-4 bg-slate-950/40 p-4 rounded-xl border border-slate-800/60 backdrop-blur-md relative shadow-lg">
      <div className="flex items-center justify-between mb-3 border-b border-slate-800/40 pb-2">
        <div className="flex items-center gap-2">
          <div className="relative flex h-2 w-2">
            <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
            <span className="relative inline-flex rounded-full h-2 w-2 bg-emerald-500"></span>
          </div>
          <span className="text-[11px] font-bold text-slate-200 tracking-wider uppercase">LOB Microstructure Telemetry ({active})</span>
        </div>
        <button
          type="button"
          onClick={onClose}
          className="text-slate-500 hover:text-slate-300 text-[10px] font-mono hover:bg-slate-800/60 px-1.5 py-0.5 rounded transition-colors"
        >
          ✕ Collapse
        </button>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-3.5">
        {/* Widget 1: CVD Momentum */}
        <div className="flex flex-col gap-2 p-3 bg-slate-900/40 rounded-lg border border-slate-800/40">
          <div className="flex justify-between items-center">
            <div className="flex items-center gap-1.5">
              <svg className="w-3.5 h-3.5 text-cyan-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M13 10V3L4 14h7v7l9-11h-7z" />
              </svg>
              <span className="text-[11px] font-bold text-slate-300 font-sans">CVD Momentum (Delta)</span>
            </div>
            <div className="flex items-center gap-2">
              {trend.length > 0 && (
                <div className="flex items-center gap-1">
                  <span className="text-[8px] text-slate-500 font-mono">Trend:</span>
                  <div className="flex items-end gap-[2px] h-4 px-1 bg-slate-950/60 rounded border border-slate-800/40">
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
                  : 'bg-slate-950/60 text-slate-400 border-slate-800/60'
              }`}>
                {delta !== null ? `Δ = ${delta >= 0 ? '+' : ''}${delta.toFixed(0)}` : 'Δ = —'}
              </span>
            </div>
          </div>

          <div className="relative mt-1">
            <div className="h-1.5 w-full bg-slate-950 rounded-full border border-slate-800/50 overflow-hidden flex">
              <div className="w-1/2 h-full flex justify-end">
                {delta !== null && delta < 0 && (
                  <div
                    className={`h-full transition-all duration-500 ${
                      Math.abs(delta) >= highDelta
                        ? 'bg-gradient-to-l from-red-600 to-rose-500 shadow-[0_0_8px_#f43f5e]'
                        : Math.abs(delta) >= strongDelta
                        ? 'bg-gradient-to-l from-rose-500 to-rose-400 shadow-[0_0_4px_#fb7185]'
                        : 'bg-gradient-to-l from-rose-400/80 to-slate-800'
                    }`}
                    style={{ width: `${Math.min(100, (Math.abs(delta) / highDelta) * 100)}%` }}
                  />
                )}
              </div>
              <div className="w-[1px] h-full bg-slate-700 z-10" />
              <div className="w-1/2 h-full flex justify-start">
                {delta !== null && delta > 0 && (
                  <div
                    className={`h-full transition-all duration-500 ${
                      delta >= highDelta
                        ? 'bg-gradient-to-r from-cyan-500 to-indigo-500 shadow-[0_0_8px_#06b6d4]'
                        : delta >= strongDelta
                        ? 'bg-gradient-to-r from-cyan-400 to-cyan-500 shadow-[0_0_4px_#22d3ee]'
                        : 'bg-gradient-to-r from-cyan-400/80 to-slate-800'
                    }`}
                    style={{ width: `${Math.min(100, (delta / highDelta) * 100)}%` }}
                  />
                )}
              </div>
            </div>

            <div className="flex justify-between items-center text-[7px] text-slate-500 font-mono mt-1 px-0.5">
              <span className="w-1/5 text-left">-High (±{highDelta})</span>
              <span className="w-1/5 text-center">-Strong (±{strongDelta})</span>
              <span className="w-1/5 text-center font-bold text-slate-600">0</span>
              <span className="w-1/5 text-center">+Strong (±{strongDelta})</span>
              <span className="w-1/5 text-right">+High (±{highDelta})</span>
            </div>
          </div>
        </div>

        {/* Widget 2: Order Flow Balance */}
        <div className="flex flex-col gap-2 p-3 bg-slate-900/40 rounded-lg border border-slate-800/40">
          <div className="flex justify-between items-center">
            <div className="flex items-center gap-1.5">
              <svg className="w-3.5 h-3.5 text-emerald-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M3 6l3 1m0 0l-3 9a5.002 5.002 0 006.001 0M6 7l3 9M6 7l6-2m6 2l3-1m-3 1l-3 9a5.002 5.002 0 006.001 0M18 7l3 9m-3-9l-6-2m0-2v2m0 16V5m0 16H9m3 0h3" />
              </svg>
              <span className="text-[11px] font-bold text-slate-300 font-sans">Order Flow Balance</span>
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
            <div className="h-1.5 w-full bg-slate-950 rounded-full border border-slate-800/50 overflow-hidden flex">
              <div
                className="h-full bg-gradient-to-r from-emerald-600 to-emerald-500 transition-all duration-500"
                style={{ width: buyPct !== null ? `${buyPct}%` : '50%' }}
              />
              <div className="h-full bg-gradient-to-r from-rose-500 to-rose-600 transition-all duration-500 flex-1" />
            </div>

            <div className="absolute inset-0 top-0 h-1.5 pointer-events-none flex justify-between px-[2px]">
              <div className="w-[1px] h-2.5 bg-slate-600/40" style={{ marginLeft: '48%' }} />
              <div className="w-[1px] h-2.5 bg-slate-600/40" style={{ marginRight: '48%' }} />
            </div>

            <div className="flex justify-between items-center text-[7px] text-slate-500 font-mono mt-1 px-0.5">
              <span>0%</span>
              <span className="text-rose-400/70 font-semibold">Bearish Limit (48%)</span>
              <span className="font-bold text-slate-600">50%</span>
              <span className="text-emerald-400/70 font-semibold">Bullish Limit (52%)</span>
              <span>100%</span>
            </div>
          </div>
        </div>

        {/* Widget 3: Passive LOB Absorption */}
        <div className="flex flex-col gap-2 p-3 bg-slate-900/40 rounded-lg border border-slate-800/40">
          <div className="flex justify-between items-center">
            <div className="flex items-center gap-1.5">
              <svg className="w-3.5 h-3.5 text-indigo-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z" />
              </svg>
              <span className="text-[11px] font-bold text-slate-300 font-sans">Passive LOB Absorption</span>
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
            <div className="flex justify-between items-center text-[9px] text-slate-400 font-mono mb-0.5">
              <span>Passive Scans (n8): <strong className="text-slate-200 font-semibold">{abs.n8 !== null ? abs.n8 : '—'}</strong> / 16</span>
              <span className="text-slate-500">Dominance: <strong className={abs.dominantSide === 'BULL' ? 'text-emerald-400' : abs.dominantSide === 'BEAR' ? 'text-rose-400' : 'text-slate-400'}>{abs.dominantSide || 'None'}</strong></span>
            </div>

            <div className="flex gap-[2px] h-2.5 bg-slate-950 p-[2px] rounded border border-slate-800/50">
              {Array.from({ length: 16 }).map((_, idx) => {
                const isActive = abs.n8 !== null && idx < abs.n8;
                const isThresholdZone = idx >= 8;

                let blockColor = 'bg-slate-900/30';
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
                  blockColor = 'bg-slate-950/40 border-l border-slate-900/40';
                }

                return (
                  <div
                    key={idx}
                    className={`flex-1 rounded-[1px] transition-all duration-300 ${blockColor}`}
                  />
                );
              })}
            </div>

            <div className="flex justify-between text-[7px] text-slate-500 font-mono mt-0.5 px-0.5">
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
            : 'bg-slate-900/40 border-slate-800/40'
        }`}>
          <div className="flex justify-between items-center">
            <div className="flex items-center gap-1.5">
              <svg className="w-3.5 h-3.5 text-amber-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
              </svg>
              <span className="text-[11px] font-bold text-slate-300 font-sans">A/D Structural Veto</span>
            </div>
            {da.status === 'BLOQUE' ? (
              <span className="text-[8px] font-mono font-extrabold bg-red-950 text-red-400 border border-red-800/50 px-1.5 py-0.5 rounded animate-pulse">
                🚫 VETO ACTIVE
              </span>
            ) : da.type !== null ? (
              <span className="text-[8px] font-mono font-bold bg-slate-950 text-emerald-400 border border-emerald-900/40 px-1.5 py-0.5 rounded">
                ✅ PASS
              </span>
            ) : null}
          </div>

          {da.type !== null ? (
            <div className="flex flex-col gap-1 mt-0.5">
              <div className="flex justify-between items-center text-[9px] font-mono">
                <span className="text-slate-400">Process: <strong className={da.type === 'ACCUMULATION' ? 'text-purple-400' : 'text-amber-400'}>{da.type}</strong></span>
                <span className="text-slate-400">Force: <strong className="text-slate-200">{da.conf}%</strong></span>
              </div>

              <div className="relative h-1.5 w-full bg-slate-950 rounded-full border border-slate-800/50 overflow-hidden mt-0.5">
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

              <div className="flex justify-between text-[7px] text-slate-500 font-mono mt-0.5 px-0.5">
                <span>0%</span>
                <span className="text-red-400/70 font-semibold" style={{ left: da.threshold !== null ? `${da.threshold}%` : '50%', transform: 'translateX(-50%)', position: 'absolute' }}>Veto Limit ({da.threshold}%)</span>
                <span>100%</span>
              </div>
            </div>
          ) : (
            <div className="flex flex-col items-center justify-center h-[34px] text-center px-1">
              <p className="text-[9px] text-slate-500 font-mono leading-tight">
                No active accumulation or distribution scans.
              </p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

interface DirectionSectionProps {
  label: 'SHORT' | 'LONG';
  score: number;
  finalScore: number | undefined;
  blocked: boolean | undefined;
  setup7_7: boolean;
  alert6_7: boolean;
  scoreModifier: number | undefined;
  blocks: StructuralBlockView[] | undefined;
  warnings: StructuralWarningView[] | undefined;
  gates: QuantGateView[];
  entry: number | null;
  sl: number | null;
  tp1: number | null;
  tp2: number | null;
  advisorSlot?: React.ReactNode;
  /** Order-flow pattern action seen FROM this direction's perspective.
   *  SHORT reads {@code pattern.action}; LONG reads {@code pattern.longAction}
   *  (PR #310 mirror). Resolved by {@link patternActionFor} so backends that
   *  pre-date PR #310 still render via the SHORT→LONG flip. */
  patternAction?: PatternAction | null;
  /** Human-readable label for the pattern badge tooltip (e.g. "Absorption haussière"). */
  patternLabel?: string | null;
  pattern?: PatternView | null;
  instrument?: string;
}

/**
 * Renders one direction's section (gates list, structural blocks/warnings,
 * suggested plan). Shared between SHORT and LONG so both sides have an
 * identical presentation.
 */
function DirectionSection(props: DirectionSectionProps) {
  const blocks = props.blocks ?? [];
  const warnings = props.warnings ?? [];
  return (
    <div>
      <div className="flex flex-col gap-1.5 mb-2">
        <div className="flex items-center gap-2 flex-wrap">
          <span className={`px-2 py-1 rounded text-xs font-bold ${scoreClass(props.score)}`}>
            {props.label} {props.score}/7
          </span>
          {props.scoreModifier !== undefined && props.scoreModifier !== 0 && (
            <span className="text-xs text-slate-400">
              → final{' '}
              <span className="font-mono text-slate-200">{props.finalScore ?? props.score}</span>
              <span className="font-mono ml-1">
                ({props.scoreModifier >= 0 ? '+' : ''}
                {props.scoreModifier})
              </span>
            </span>
          )}
          {props.patternAction && (
            <span
              className={`px-2 py-0.5 rounded text-[10px] font-mono font-semibold border ${patternActionClass(props.patternAction)}`}
              title={
                props.patternLabel
                  ? `Pattern order-flow: ${props.patternLabel} → ${props.patternAction} (${props.label} side)`
                  : `Pattern order-flow → ${props.patternAction} (${props.label} side)`
              }
            >
              {patternActionGlyph(props.patternAction)} flow {props.patternAction}
            </span>
          )}
          {props.pattern && props.patternAction && (
            <>
              {renderConfidencePill(props.pattern.confidence)}
              {parseConfirmations(props.pattern.reason).map(tag => renderConfirmationPill(tag))}
            </>
          )}
        </div>
        {props.pattern && props.patternAction && (
          <div className="p-2 rounded bg-slate-950/40 border border-slate-800/60 text-xs text-slate-300 shadow-[inset_0_1px_3px_rgba(0,0,0,0.4)]">
            <span className="font-semibold text-slate-200">{props.pattern.label}:</span>{' '}
            {cleanReasonText(props.pattern.reason)}
          </div>
        )}
      </div>

      <ul className="space-y-1 text-sm font-mono">
        {props.gates.map((g) => (
          <li
            key={g.gate}
            className={`flex items-start gap-2 px-2 py-1 rounded ${
              g.ok ? 'bg-emerald-950/40' : 'bg-red-950/30'
            }`}
          >
            <span className="w-5 shrink-0 text-base">{g.ok ? '✅' : '❌'}</span>
            <div className="flex-1">
              <div className="text-slate-200">{GATE_LABELS[g.gate] ?? g.gate}</div>
              <div className="text-xs text-slate-400">{g.reason}</div>
            </div>
          </li>
        ))}
      </ul>

      {blocks.length > 0 && (
        <ul className="mt-3 space-y-1 text-xs font-mono">
          {blocks.map((b) => (
            <li
              key={`block-${props.label}-${b.code}`}
              className="flex items-start gap-2 px-2 py-1 rounded bg-red-950/60 border border-red-800"
              title={b.evidence}
            >
              <span className="text-red-400">🚫</span>
              <span className="text-red-200 font-semibold">{b.code}</span>
              <span className="text-red-300/80 truncate">{b.evidence}</span>
            </li>
          ))}
        </ul>
      )}
      {warnings.length > 0 && (
        <ul className="mt-2 space-y-1 text-xs font-mono">
          {warnings.map((w, idx) => (
            <li
              key={`warn-${props.label}-${w.code}-${idx}`}
              className="flex items-start gap-2 px-2 py-1 rounded bg-amber-950/40 border border-amber-900/60"
              title={w.evidence}
            >
              <span className="text-amber-400">⚠️</span>
              <span className="text-amber-200 font-semibold w-10 shrink-0">
                {w.scoreModifier >= 0 ? `+${w.scoreModifier}` : w.scoreModifier}
              </span>
              <span className="text-amber-100">{w.code}</span>
              <span className="text-amber-300/80 truncate">{w.evidence}</span>
            </li>
          ))}
        </ul>
      )}

      {props.blocked && (
        <div className="mt-3 rounded border border-red-700 bg-red-950/40 p-3 text-sm">
          <div className="font-semibold text-red-300">
            ❌ {props.label} bloqué — {blocks.length} block{blocks.length === 1 ? '' : 's'} structurel(s)
          </div>
          <div className="text-xs text-red-200/80 mt-1">
            Le score quant est suffisant mais la structure de marché veto le {props.label}.
          </div>
        </div>
      )}

      {!props.blocked && props.score >= 6 && props.entry !== null && props.sl !== null && (
        <div className="mt-3 rounded border border-amber-700 bg-amber-950/40 p-3 text-sm">
          <div className="font-semibold mb-2 flex items-center gap-2 flex-wrap">
            {props.setup7_7 ? (
              <span className="text-emerald-400">🔔 {props.label} 7/7 — full setup</span>
            ) : (
              <span className="text-amber-400">⚠️ {props.label} 6/7 — early warning</span>
            )}
            {props.advisorSlot}
          </div>
          <div className="grid grid-cols-4 gap-2 text-xs font-mono">
            <div>
              <span className="text-slate-400">ENTRY</span>
              <br />
              {formatPrice(props.entry, props.instrument)}
            </div>
            <div>
              <span className="text-slate-400">SL</span>
              <br />
              {formatPrice(props.sl, props.instrument)}
            </div>
            <div>
              <span className="text-slate-400">TP1</span>
              <br />
              {formatPrice(props.tp1, props.instrument)}
            </div>
            <div>
              <span className="text-slate-400">TP2</span>
              <br />
              {formatPrice(props.tp2, props.instrument)}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * Live view of the Quant 7-Gates evaluator. Subscribes to the per-instrument
 * snapshot WebSocket topic and renders pass/fail for each gate (SHORT and
 * LONG side-by-side) plus the suggested plan when the corresponding score
 * reaches 6/7.
 */
export default function QuantGatePanel() {
  const [active, setActive] = useState<QuantInstrument>('MNQ');
  const [showTelemetry, setShowTelemetry] = useState(true);
  const { snapshots, narrations, advice: streamedAdvice, autoArm, connected } = useQuantStream();
  const [bootstrap, setBootstrap] = useState<Record<string, QuantSnapshotView>>({});
  const [manualAdvice, setManualAdvice] = useState<Record<string, AdviceView>>({});
  const [askingAi, setAskingAi] = useState<Record<string, boolean>>({});
  const [autoArmBusy, setAutoArmBusy] = useState<Record<number, boolean>>({});
  const [now, setNow] = useState<number>(() => Date.now());
  const [manualModalDirection, setManualModalDirection] = useState<'LONG' | 'SHORT' | null>(null);
  const [lastManualExecutionId, setLastManualExecutionId] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all(
      QUANT_INSTRUMENTS.map(async (instr) => {
        try {
          const snap = await api.getQuantSnapshot(instr);
          return [instr, snap] as const;
        } catch {
          return [instr, null] as const;
        }
      })
    ).then((entries) => {
      if (cancelled) return;
      const next: Record<string, QuantSnapshotView> = {};
      for (const [instr, snap] of entries) {
        if (snap) next[instr] = snap;
      }
      setBootstrap(next);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const snapshot = snapshots[active] ?? bootstrap[active] ?? null;
  const narration = narrations[active] ?? null;
  const advice = manualAdvice[active] ?? streamedAdvice[active] ?? null;
  const armState: AutoArmInstrumentState | null = autoArm[active] ?? null;

  useEffect(() => {
    if (armState?.state !== 'ARMED') return;
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [armState?.state]);

  const fireAutoArm = async (executionId: number) => {
    setAutoArmBusy(prev => ({ ...prev, [executionId]: true }));
    try {
      await api.fireAutoArm(executionId);
    } catch (err) {
      console.warn('auto-arm fire failed', err);
    } finally {
      setAutoArmBusy(prev => ({ ...prev, [executionId]: false }));
    }
  };

  const cancelAutoArm = async (executionId: number) => {
    setAutoArmBusy(prev => ({ ...prev, [executionId]: true }));
    try {
      await api.cancelAutoArm(executionId);
    } catch (err) {
      console.warn('auto-arm cancel failed', err);
    } finally {
      setAutoArmBusy(prev => ({ ...prev, [executionId]: false }));
    }
  };

  const askAi = async () => {
    setAskingAi((prev) => ({ ...prev, [active]: true }));
    try {
      const result = await api.askQuantAiAdvice(active);
      setManualAdvice((prev) => ({ ...prev, [active]: result }));
    } catch (err) {
      console.warn('quant Ask AI failed', err);
    } finally {
      setAskingAi((prev) => ({ ...prev, [active]: false }));
    }
  };

  const shortGates = (snapshot?.gates ?? []).filter((g) => SHORT_GATES.includes(g.gate));
  const longGates = (snapshot?.gates ?? []).filter((g) => LONG_GATES.includes(g.gate));

  return (
    <section className="rounded-lg border border-slate-700 bg-slate-900 p-4 text-slate-100">
      <header className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <h2 className="text-lg font-semibold">Quant 7-Gates</h2>
          <span
            className={`text-xs px-2 py-0.5 rounded ${connected ? 'bg-emerald-700' : 'bg-slate-700'}`}
            title={connected ? 'WebSocket connected' : 'WebSocket disconnected'}
          >
            {connected ? 'live' : 'offline'}
          </span>
        </div>
        <div className="flex gap-1">
          {QUANT_INSTRUMENTS.map((instr) => (
            <button
              key={instr}
              type="button"
              onClick={() => setActive(instr)}
              className={`px-2 py-1 text-xs rounded ${
                instr === active
                  ? 'bg-slate-700 text-white'
                  : 'bg-slate-800 text-slate-400 hover:bg-slate-700'
              }`}
            >
              {instr}
            </button>
          ))}
        </div>
      </header>

      {snapshot ? (
        <>
          <div className="flex items-center justify-between mb-3 text-sm text-slate-300">
            <div className="flex items-center gap-3">
              <span>
                px <span className="font-mono">{formatPrice(snapshot.price, active)}</span>{' '}
                <span className="text-slate-500">[{snapshot.priceSource || '—'}]</span>
              </span>
              <span className="text-slate-400">
                Δjour{' '}
                <span className="font-mono">
                  {formatDayMove(snapshot.dayMove, active)}
                </span>
              </span>
            </div>
            <span className="text-xs text-slate-500">{snapshot.scanTime ?? '—'}</span>
          </div>

          {/* Microstructure Live Telemetry Dashboard OR Compact threshold status bar */}
          {showTelemetry ? (
            <QuantTelemetryDashboard
              gates={snapshot.gates}
              active={active}
              onClose={() => setShowTelemetry(false)}
            />
          ) : (
            INSTRUMENT_THRESHOLDS[active] && (
              <div className="mb-4 flex flex-wrap items-center justify-between gap-2 text-[10px] text-slate-400 font-mono bg-slate-950/45 rounded-lg p-2 border border-slate-800/50">
                <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
                  <span className="text-slate-500 font-semibold uppercase tracking-wider">Parameters ({active}):</span>
                  <div className="flex items-center gap-1.5 bg-slate-900/60 px-2 py-0.5 rounded border border-slate-800/30">
                    <span className="text-slate-500">Stable Band:</span>
                    <span className="text-slate-300 font-bold">{INSTRUMENT_THRESHOLDS[active].stableBand}</span>
                  </div>
                  <div className="flex items-center gap-1.5 bg-slate-900/60 px-2 py-0.5 rounded border border-slate-800/30">
                    <span className="text-slate-500">Strong Delta:</span>
                    <span className="text-sky-400 font-bold">±{INSTRUMENT_THRESHOLDS[active].strongDelta}</span>
                  </div>
                  <div className="flex items-center gap-1.5 bg-slate-900/60 px-2 py-0.5 rounded border border-slate-800/30">
                    <span className="text-slate-500">High Delta:</span>
                    <span className="text-violet-400 font-bold">±{INSTRUMENT_THRESHOLDS[active].highDelta}</span>
                  </div>
                </div>
                <button
                  type="button"
                  onClick={() => setShowTelemetry(true)}
                  className="px-2 py-0.5 rounded bg-slate-800 hover:bg-slate-700 text-[10px] border border-slate-700 text-slate-300 hover:text-white transition-colors font-mono"
                >
                  📊 Expand Telemetry
                </button>
              </div>
            )
          )}

          <div className="mb-3 flex items-center gap-2">
            <button
              type="button"
              onClick={() => setManualModalDirection('LONG')}
              className="px-3 py-1.5 text-xs font-semibold rounded bg-emerald-700 hover:bg-emerald-600 text-white transition-colors"
              title="Place a manual LONG order — independent of auto-arm threshold"
            >
              🟢 BUY
            </button>
            <button
              type="button"
              onClick={() => setManualModalDirection('SHORT')}
              className="px-3 py-1.5 text-xs font-semibold rounded bg-red-700 hover:bg-red-600 text-white transition-colors"
              title="Place a manual SHORT order — independent of auto-arm threshold"
            >
              🔴 SELL
            </button>
            {lastManualExecutionId !== null && (
              <span className="ml-2 text-xs text-emerald-300">
                Order placed — execution #{lastManualExecutionId}
              </span>
            )}
          </div>

          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
            <DirectionSection
              label="SHORT"
              score={snapshot.score}
              finalScore={snapshot.finalScore}
              blocked={snapshot.shortBlocked}
              setup7_7={snapshot.shortSetup7_7}
              alert6_7={snapshot.shortAlert6_7}
              scoreModifier={snapshot.structuralScoreModifier}
              blocks={snapshot.structuralBlocks}
              warnings={snapshot.structuralWarnings}
              gates={shortGates}
              entry={snapshot.entry}
              sl={snapshot.sl}
              tp1={snapshot.tp1}
              tp2={snapshot.tp2}
              patternAction={patternActionFor(narration?.pattern, 'SHORT')}
              patternLabel={narration?.pattern?.label ?? null}
              pattern={narration?.pattern}
              instrument={active}
              advisorSlot={
                <>
                  <QuantAdvisorBadge advice={advice} loading={askingAi[active]} />
                  <button
                    type="button"
                    onClick={askAi}
                    disabled={askingAi[active]}
                    className="ml-auto px-2 py-0.5 text-xs rounded bg-slate-800 hover:bg-slate-700 border border-slate-600 disabled:opacity-50 transition-colors"
                    title="Demande un second avis IA basé sur la mémoire long-terme + multi-instrument"
                  >
                    Ask AI
                  </button>
                </>
              }
            />

            <DirectionSection
              label="LONG"
              score={snapshot.longScore}
              finalScore={snapshot.longFinalScore}
              blocked={snapshot.longBlocked}
              setup7_7={snapshot.longSetup7_7}
              alert6_7={snapshot.longAlert6_7}
              scoreModifier={snapshot.longStructuralScoreModifier}
              blocks={snapshot.longStructuralBlocks}
              warnings={snapshot.longStructuralWarnings}
              gates={longGates}
              entry={snapshot.longEntry}
              sl={snapshot.longSl}
              tp1={snapshot.longTp1}
              tp2={snapshot.longTp2}
              patternAction={patternActionFor(narration?.pattern, 'LONG')}
              patternLabel={narration?.pattern?.label ?? null}
              pattern={narration?.pattern}
              instrument={active}
            />
          </div>

          {armState && armState.state !== 'IDLE' && (
            <AutoArmCard
              state={armState}
              now={now}
              busy={armState.armed ? Boolean(autoArmBusy[armState.armed.executionId]) : false}
              onFire={fireAutoArm}
              onCancel={cancelAutoArm}
            />
          )}

          <details className="mt-3 text-xs">
            <summary className="cursor-pointer text-slate-400 hover:text-slate-200 transition-colors">
              Narration markdown {narration?.pattern ? `· ${narration.pattern.label}` : ''}
            </summary>
            <div className="mt-2 p-2 bg-slate-950 rounded border border-slate-800">
              <QuantNarrationPanel narration={narration} />
            </div>
          </details>
        </>
      ) : (
        <p className="text-sm text-slate-400">No snapshot yet. The scheduler runs every 60 seconds.</p>
      )}
      <QuantManualTradeModal
        open={manualModalDirection !== null}
        instrument={active}
        direction={manualModalDirection ?? 'LONG'}
        snapshot={snapshot}
        onClose={() => setManualModalDirection(null)}
        onPlaced={(executionId) => setLastManualExecutionId(executionId)}
      />
    </section>
  );
}

/**
 * Auto-arm state badge with countdown + Fire / Cancel buttons. Lives below
 * the SHORT setup card. The state is driven entirely by the WebSocket
 * stream — the buttons fire-and-forget against the REST endpoints; the
 * actual state change comes back through the stream.
 */
function AutoArmCard(props: {
  state: AutoArmInstrumentState;
  now: number;
  busy: boolean;
  onFire: (executionId: number) => void;
  onCancel: (executionId: number) => void;
}): JSX.Element {
  const { state, now, busy, onFire, onCancel } = props;
  const armed = state.armed;

  if (state.state !== 'ARMED' || armed === null) {
    const tone =
      state.state === 'CANCELLED' ? 'border-slate-700 bg-slate-900 text-slate-300' :
      state.state === 'EXPIRED' ? 'border-slate-700 bg-slate-900 text-slate-400' :
      'border-emerald-700 bg-emerald-950/40 text-emerald-200';
    return (
      <div className={`mt-4 rounded border ${tone} p-3 text-sm`}>
        <div className="font-semibold">Auto-arm — {state.state.replace(/_/g, ' ').toLowerCase()}</div>
        {state.lastReason && <div className="text-xs opacity-80 mt-1">{state.lastReason}</div>}
      </div>
    );
  }

  const autoSubmitAtMs = armed.autoSubmitAt ? Date.parse(armed.autoSubmitAt) : null;
  const expiresAtMs = armed.expiresAt ? Date.parse(armed.expiresAt) : null;
  const secondsUntilSubmit = autoSubmitAtMs !== null ? Math.max(0, Math.floor((autoSubmitAtMs - now) / 1000)) : null;
  const secondsUntilExpire = expiresAtMs !== null ? Math.max(0, Math.floor((expiresAtMs - now) / 1000)) : null;

  return (
    <div className="mt-4 rounded border border-yellow-600 bg-yellow-950/40 p-3 text-sm animate-pulse">
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <div className="flex items-center gap-2">
          <span className="text-yellow-300 text-base">🟡</span>
          <span className="font-bold text-yellow-100">
            ARMED — {armed.direction ?? '—'}
          </span>
        </div>
        {secondsUntilSubmit !== null ? (
          <span className="text-xs text-yellow-200">
            Auto-submit in <span className="font-mono font-bold">{secondsUntilSubmit}s</span>
          </span>
        ) : (
          <span className="text-xs text-yellow-200">
            Manual fire required (auto-submit disabled)
            {secondsUntilExpire !== null && (
              <> · expires in <span className="font-mono">{secondsUntilExpire}s</span></>
            )}
          </span>
        )}
      </div>

      <div className="grid grid-cols-4 gap-2 text-xs font-mono mt-2">
        <div>
          <span className="text-yellow-400">ENTRY</span>
          <br />{armed.entry ?? '—'}
        </div>
        <div>
          <span className="text-yellow-400">SL</span>
          <br />{armed.stopLoss ?? '—'}
        </div>
        <div>
          <span className="text-yellow-400">TP1</span>
          <br />{armed.takeProfit1 ?? '—'}
        </div>
        <div>
          <span className="text-yellow-400">TP2</span>
          <br />{armed.takeProfit2 ?? '—'}
        </div>
      </div>

      {armed.reasoning && (
        <div className="text-xs text-yellow-200/80 mt-2">{armed.reasoning}</div>
      )}

      <div className="mt-3 flex gap-2">
        <button
          type="button"
          onClick={() => onFire(armed.executionId)}
          disabled={busy}
          className="px-3 py-1 text-xs rounded bg-emerald-700 hover:bg-emerald-600 disabled:opacity-50 text-white font-semibold transition-colors"
        >
          🔥 FIRE NOW
        </button>
        <button
          type="button"
          onClick={() => onCancel(armed.executionId)}
          disabled={busy}
          className="px-3 py-1 text-xs rounded bg-red-700 hover:bg-red-600 disabled:opacity-50 text-white font-semibold transition-colors"
        >
          ✕ CANCEL
        </button>
      </div>
    </div>
  );
}
