'use client';

import type { QuantGateView, QuantInstrument } from './types';

interface FrontendThresholds {
  stableBand: string;
  strongDelta: number;
  highDelta: number;
}

export const INSTRUMENT_THRESHOLDS: Record<string, FrontendThresholds> = {
  MNQ: { stableBand: '5.0 pts', strongDelta: 200, highDelta: 400 },
  MGC: { stableBand: '1.0 pts', strongDelta: 50, highDelta: 100 },
  MCL: { stableBand: '0.10 pts', strongDelta: 50, highDelta: 100 },
  E6:  { stableBand: '0.00050 px', strongDelta: 50, highDelta: 100 },
};

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

  if (g5 && g5.reason) {
    const match = g5.reason.match(/ACCU\s+(\d+)%\s+vs\s+seuil=(\d+)%/);
    if (match) {
      return {
        type: 'ACCUMULATION',
        conf: parseInt(match[1], 10),
        threshold: parseInt(match[2], 10),
        status: g5.reason.includes('BLOQUE') ? 'BLOQUE' : 'PASS',
      };
    }
  }

  if (l5 && l5.reason) {
    const match = l5.reason.match(/DIST\s+(\d+)%\s+vs\s+seuil=(\d+)%/);
    if (match) {
      return {
        type: 'DISTRIBUTION',
        conf: parseInt(match[1], 10),
        threshold: parseInt(match[2], 10),
        status: l5.reason.includes('BLOQUE') ? 'BLOQUE' : 'PASS',
      };
    }
  }

  return { type: null, conf: null, threshold: null, status: 'INACTIVE' };
}

interface QuantTelemetryDashboardProps {
  gates: QuantGateView[];
  active: QuantInstrument;
  onClose: () => void;
}

/**
 * Live microstructure telemetry dashboard — visualizes CVD delta, order flow balance,
 * passive iceberg walls, and structural vetos directly from 7-Gates reasons.
 */
export default function QuantTelemetryDashboard({ gates, active, onClose }: QuantTelemetryDashboardProps) {
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
