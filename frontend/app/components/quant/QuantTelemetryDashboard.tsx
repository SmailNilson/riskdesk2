'use client';

import { useEffect, useState } from 'react';
import { useQuantStream } from '@/app/hooks/useQuantStream';
import { api } from '@/app/lib/api';
import type { QuantInstrument, QuantSnapshotView, QuantTelemetryView } from './types';

// ── Helpers ────────────────────────────────────────────────────────────────

/**
 * Backend scanTime is a java ZonedDateTime string, e.g.
 * "2026-06-10T14:00:00-04:00[America/New_York]" — JS Date cannot parse the
 * bracketed zone id, so strip it before parsing.
 */
function parseScanTime(scanTime: string | null | undefined): number | null {
  if (!scanTime) return null;
  const t = new Date(scanTime.replace(/\[.*\]$/, '')).getTime();
  return Number.isNaN(t) ? null : t;
}

function secondsSince(epochMs: number | null): number | null {
  if (epochMs === null) return null;
  return Math.max(0, (Date.now() - epochMs) / 1000);
}

function formatAge(seconds: number): string {
  if (seconds < 60) return `il y a ${Math.round(seconds)}s`;
  if (seconds < 3600) return `il y a ${Math.floor(seconds / 60)} min`;
  return `il y a ${Math.floor(seconds / 3600)}h`;
}

/** Snapshot older than this is flagged stale on the header status dot. */
const SNAPSHOT_STALE_SEC = 90;
/** A/D events older than this grey out the veto card (matches the backend 10-min window). */
const AD_EVENT_STALE_SEC = 600;

/** Amber chip shown on the CVD / Balance cards when the delta gates abstained (feed down). */
function DegradedChip() {
  return (
    <span className="text-[8px] font-mono font-extrabold px-1.5 py-0.5 rounded border bg-amber-950/80 text-amber-300 border-amber-700/80">
      FLUX DÉGRADÉ (ticks)
    </span>
  );
}

interface QuantTelemetryDashboardProps {
  active: QuantInstrument;
  onClose: () => void;
}

/**
 * Live microstructure telemetry dashboard. Reads the structured `telemetry`
 * object published with every Quant 7-Gates snapshot (WS + REST) — the old
 * regex-parsing of the French gate `reason` strings is gone, along with the
 * fabrications it forced (frontend-only ±200/±400 delta bands, a "/ 16" cap
 * on the unbounded n8 counter, a BEAR-dominance fallback, and a silent 50/50
 * balance bar during feed outages).
 */
export default function QuantTelemetryDashboard({ active, onClose }: QuantTelemetryDashboardProps) {
  const { snapshots, connected } = useQuantStream();

  // REST seed: the WS topic only emits on the next 60s scan, so without a
  // seed the dashboard shows '—' for up to 90s after page load.
  const [seeds, setSeeds] = useState<Record<string, QuantSnapshotView>>({});
  useEffect(() => {
    let cancelled = false;
    api
      .getQuantSnapshot(active)
      .then(snap => {
        if (!cancelled && snap) setSeeds(prev => ({ ...prev, [active]: snap }));
      })
      .catch(() => { /* best-effort: WS will fill in */ });
    return () => { cancelled = true; };
  }, [active]);

  // Heartbeat: re-render so the status dot and ages stay honest even when no
  // new WS message arrives (e.g. a stalled scheduler).
  const [, setHeartbeat] = useState(0);
  useEffect(() => {
    const id = setInterval(() => setHeartbeat(t => t + 1), 10_000);
    return () => clearInterval(id);
  }, []);

  const snapshot: QuantSnapshotView | undefined = snapshots[active] ?? seeds[active];
  const telemetry: QuantTelemetryView | null = snapshot?.telemetry ?? null;

  const scanAgeSec = secondsSince(parseScanTime(snapshot?.scanTime));
  const fresh = scanAgeSec !== null && scanAgeSec < SNAPSHOT_STALE_SEC;
  const dotColor = !connected ? 'bg-red-500' : fresh ? 'bg-emerald-500' : 'bg-amber-500';
  const dotPing = connected && fresh;
  const dotTitle = !connected
    ? 'WebSocket déconnecté'
    : fresh
      ? `Live — scan ${Math.round(scanAgeSec ?? 0)}s`
      : scanAgeSec !== null
        ? `Stale — dernier scan ${formatAge(scanAgeSec)}`
        : 'En attente du premier scan';

  return (
    <div className="mb-4 bg-slate-950/40 p-4 rounded-xl border border-slate-800/60 backdrop-blur-md relative shadow-lg">
      <div className="flex items-center justify-between mb-3 border-b border-slate-800/40 pb-2">
        <div className="flex items-center gap-2">
          <div className="relative flex h-2 w-2" title={dotTitle}>
            {dotPing && (
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
            )}
            <span className={`relative inline-flex rounded-full h-2 w-2 ${dotColor}`}></span>
          </div>
          <span className="text-[11px] font-bold text-slate-200 tracking-wider uppercase">LOB Microstructure Telemetry ({active})</span>
          {scanAgeSec !== null && (
            <span className={`text-[8px] font-mono ${fresh ? 'text-slate-500' : 'text-amber-400'}`}>
              scan {formatAge(scanAgeSec)}
            </span>
          )}
        </div>
        <button
          type="button"
          onClick={onClose}
          className="text-slate-500 hover:text-slate-300 text-[10px] font-mono hover:bg-slate-800/60 px-1.5 py-0.5 rounded transition-colors"
        >
          ✕ Collapse
        </button>
      </div>

      {telemetry ? (
        <TelemetryGrid telemetry={telemetry} />
      ) : (
        <div className="flex items-center justify-center h-[72px] text-center">
          <p className="text-[10px] text-slate-500 font-mono">
            En attente de la télémétrie structurée (premier scan ≤ 60s)…
          </p>
        </div>
      )}
    </div>
  );
}

function TelemetryGrid({ telemetry }: { telemetry: QuantTelemetryView }) {
  return (
    <div className="grid grid-cols-1 md:grid-cols-2 gap-3.5">
      <CvdCard telemetry={telemetry} />
      <BalanceCard telemetry={telemetry} />
      <AbsorptionCard telemetry={telemetry} />
      <VetoCard telemetry={telemetry} />
    </div>
  );
}

// ── Widget 1: rolling delta (was mislabelled "CVD Momentum") ───────────────

function CvdCard({ telemetry }: { telemetry: QuantTelemetryView }) {
  const { delta, deltaAbstain, deltaHistory, deltaThreshold } = telemetry;
  // Bar scale: at least 2× the real decision boundary so the threshold tick
  // sits mid-half; grows with the data so the bar never clips.
  const histMax = deltaHistory.reduce((m, v) => Math.max(m, Math.abs(v)), 0);
  const scale = Math.max(2 * deltaThreshold, Math.abs(delta ?? 0), histMax);
  const thresholdPct = (deltaThreshold / scale) * 50; // % of full width, from center

  return (
    <div className="flex flex-col gap-2 p-3 bg-slate-900/40 rounded-lg border border-slate-800/40">
      <div className="flex justify-between items-center">
        <div className="flex items-center gap-1.5">
          <svg className="w-3.5 h-3.5 text-cyan-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M13 10V3L4 14h7v7l9-11h-7z" />
          </svg>
          <span className="text-[11px] font-bold text-slate-300 font-sans">
            CVD Δ glissant 5 min
            <span className="text-slate-500 font-normal"> (échantillon / scan 60s)</span>
          </span>
        </div>
        <div className="flex items-center gap-2">
          {deltaHistory.length > 0 && !deltaAbstain && (
            <div className="flex items-center gap-1">
              <span className="text-[8px] text-slate-500 font-mono">Trend:</span>
              <div className="flex items-end gap-[2px] h-4 px-1 bg-slate-950/60 rounded border border-slate-800/40">
                {deltaHistory.map((val, idx) => {
                  const heightPercent = Math.min(100, (Math.abs(val) / scale) * 100);
                  const barBg = val >= 0 ? 'bg-cyan-500/75' : 'bg-rose-500/75';
                  return (
                    <div
                      key={idx}
                      className={`w-1 rounded-t transition-all duration-300 ${barBg}`}
                      style={{ height: `${Math.max(20, heightPercent)}%` }}
                      title={`Scan t-${deltaHistory.length - 1 - idx}: ${val >= 0 ? '+' : ''}${val}`}
                    />
                  );
                })}
              </div>
            </div>
          )}
          {deltaAbstain ? (
            <DegradedChip />
          ) : (
            <span className={`text-[10px] font-mono font-extrabold px-1.5 py-0.5 rounded border ${
              delta !== null && Math.abs(delta) >= deltaThreshold
                ? delta >= 0
                  ? 'bg-cyan-950/80 text-cyan-300 border-cyan-700/80 shadow-[0_0_6px_rgba(34,211,238,0.25)]'
                  : 'bg-rose-950/80 text-rose-300 border-rose-700/80 shadow-[0_0_6px_rgba(244,63,94,0.25)]'
                : 'bg-slate-950/60 text-slate-400 border-slate-800/60'
            }`}>
              {delta !== null ? `Δ = ${delta >= 0 ? '+' : ''}${delta.toFixed(0)}` : 'Δ = —'}
            </span>
          )}
        </div>
      </div>

      <div className="relative mt-1">
        <div className="h-1.5 w-full bg-slate-950 rounded-full border border-slate-800/50 overflow-hidden flex">
          <div className="w-1/2 h-full flex justify-end">
            {!deltaAbstain && delta !== null && delta < 0 && (
              <div
                className={`h-full transition-all duration-500 ${
                  Math.abs(delta) >= deltaThreshold
                    ? 'bg-gradient-to-l from-red-600 to-rose-500 shadow-[0_0_8px_#f43f5e]'
                    : 'bg-gradient-to-l from-rose-400/80 to-slate-800'
                }`}
                style={{ width: `${Math.min(100, (Math.abs(delta) / scale) * 100)}%` }}
              />
            )}
          </div>
          <div className="w-[1px] h-full bg-slate-700 z-10" />
          <div className="w-1/2 h-full flex justify-start">
            {!deltaAbstain && delta !== null && delta > 0 && (
              <div
                className={`h-full transition-all duration-500 ${
                  delta >= deltaThreshold
                    ? 'bg-gradient-to-r from-cyan-500 to-indigo-500 shadow-[0_0_8px_#06b6d4]'
                    : 'bg-gradient-to-r from-cyan-400/80 to-slate-800'
                }`}
                style={{ width: `${Math.min(100, (delta / scale) * 100)}%` }}
              />
            )}
          </div>
        </div>

        {/* Real G3/L3 decision-boundary ticks at ±deltaThreshold. */}
        <div className="absolute inset-0 top-0 h-2.5 pointer-events-none">
          <div className="absolute w-[1px] h-2.5 bg-rose-400/60" style={{ left: `${50 - thresholdPct}%` }} />
          <div className="absolute w-[1px] h-2.5 bg-cyan-400/60" style={{ left: `${50 + thresholdPct}%` }} />
        </div>

        <div className="relative h-3 text-[7px] text-slate-500 font-mono mt-1">
          <span className="absolute left-0">-{scale.toFixed(0)}</span>
          <span
            className="absolute text-rose-400/70 font-semibold"
            style={{ left: `${50 - thresholdPct}%`, transform: 'translateX(-50%)' }}
          >
            -{deltaThreshold.toFixed(0)} (seuil G3)
          </span>
          <span className="absolute font-bold text-slate-600" style={{ left: '50%', transform: 'translateX(-50%)' }}>0</span>
          <span
            className="absolute text-cyan-400/70 font-semibold"
            style={{ left: `${50 + thresholdPct}%`, transform: 'translateX(-50%)' }}
          >
            +{deltaThreshold.toFixed(0)} (seuil L3)
          </span>
          <span className="absolute right-0">+{scale.toFixed(0)}</span>
        </div>
      </div>
    </div>
  );
}

// ── Widget 2: order flow balance ───────────────────────────────────────────

function BalanceCard({ telemetry }: { telemetry: QuantTelemetryView }) {
  const { buyPct, buyAbstain, bearishLimitPct, bullishLimitPct } = telemetry;

  return (
    <div className="flex flex-col gap-2 p-3 bg-slate-900/40 rounded-lg border border-slate-800/40">
      <div className="flex justify-between items-center">
        <div className="flex items-center gap-1.5">
          <svg className="w-3.5 h-3.5 text-emerald-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M3 6l3 1m0 0l-3 9a5.002 5.002 0 006.001 0M6 7l3 9M6 7l6-2m6 2l3-1m-3 1l-3 9a5.002 5.002 0 006.001 0M18 7l3 9m-3-9l-6-2m0-2v2m0 16V5m0 16H9m3 0h3" />
          </svg>
          <span className="text-[11px] font-bold text-slate-300 font-sans">Order Flow Balance</span>
        </div>
        {buyAbstain ? (
          <DegradedChip />
        ) : (
          <div className="flex gap-1.5">
            <span className="text-[9px] font-mono font-bold bg-emerald-950/60 text-emerald-400 border border-emerald-900/40 px-1 rounded">
              B: {buyPct !== null ? `${buyPct.toFixed(1)}%` : '—'}
            </span>
            <span className="text-[9px] font-mono font-bold bg-rose-950/60 text-rose-400 border border-rose-900/40 px-1 rounded">
              S: {buyPct !== null ? `${(100 - buyPct).toFixed(1)}%` : '—'}
            </span>
          </div>
        )}
      </div>

      <div className="relative mt-1">
        <div className="h-1.5 w-full bg-slate-950 rounded-full border border-slate-800/50 overflow-hidden flex">
          {/* No fill on abstain — a 50/50 bar would imply balance with no data. */}
          {!buyAbstain && buyPct !== null && (
            <>
              <div
                className="h-full bg-gradient-to-r from-emerald-600 to-emerald-500 transition-all duration-500"
                style={{ width: `${buyPct}%` }}
              />
              <div className="h-full bg-gradient-to-r from-rose-500 to-rose-600 transition-all duration-500 flex-1" />
            </>
          )}
        </div>

        {/* Tick marks at the real G4 / L4 limits. */}
        <div className="absolute inset-0 top-0 h-2.5 pointer-events-none">
          <div className="absolute w-[1px] h-2.5 bg-rose-400/60" style={{ left: `${bearishLimitPct}%` }} />
          <div className="absolute w-[1px] h-2.5 bg-emerald-400/60" style={{ left: `${bullishLimitPct}%` }} />
        </div>

        {/* Labels anchored AT the limit ticks (absolute within this relative row). */}
        <div className="relative h-3 text-[7px] text-slate-500 font-mono mt-1">
          <span className="absolute left-0">0%</span>
          <span
            className="absolute text-rose-400/70 font-semibold"
            style={{ left: `${bearishLimitPct}%`, transform: 'translateX(-100%)' }}
          >
            Bearish Limit ({bearishLimitPct.toFixed(0)}%)&nbsp;
          </span>
          <span
            className="absolute text-emerald-400/70 font-semibold"
            style={{ left: `${bullishLimitPct}%` }}
          >
            &nbsp;Bullish Limit ({bullishLimitPct.toFixed(0)}%)
          </span>
          <span className="absolute right-0">100%</span>
        </div>
      </div>
    </div>
  );
}

// ── Widget 3: absorption events (3-min window — NOT LOB walls) ─────────────

function AbsorptionCard({ telemetry }: { telemetry: QuantTelemetryView }) {
  const { absorptionN8: n8, absorptionDominance, absorptionMaxScore, absorptionMinN8: minN8 } = telemetry;
  // n8 is unbounded — scale the bar instead of inventing a cap.
  const scale = Math.max(2 * minN8, n8);
  const fillPct = Math.min(100, (n8 / scale) * 100);
  const thresholdPct = (minN8 / scale) * 100;
  const engaged = n8 >= minN8;

  const domColor = absorptionDominance === 'BULL'
    ? 'text-emerald-400'
    : absorptionDominance === 'BEAR'
      ? 'text-rose-400'
      : 'text-slate-300';
  const barColor = absorptionDominance === 'BULL'
    ? (engaged ? 'bg-gradient-to-r from-emerald-600 to-emerald-400 shadow-[0_0_4px_#34d399]' : 'bg-emerald-600/60')
    : absorptionDominance === 'BEAR'
      ? (engaged ? 'bg-gradient-to-r from-rose-700 to-rose-500 shadow-[0_0_4px_#f43f5e]' : 'bg-rose-700/60')
      : 'bg-slate-500/60';

  return (
    <div className="flex flex-col gap-2 p-3 bg-slate-900/40 rounded-lg border border-slate-800/40">
      <div className="flex justify-between items-center">
        <div className="flex items-center gap-1.5">
          <svg className="w-3.5 h-3.5 text-indigo-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z" />
          </svg>
          <span className="text-[11px] font-bold text-slate-300 font-sans">
            Absorption (3 min)
            <span className="text-slate-500 font-normal"> — événements score ≥ 8</span>
          </span>
        </div>
        {engaged && (
          <span className={`text-[8px] font-mono font-extrabold px-1.5 py-0.5 rounded shadow-[0_0_6px_rgba(99,102,241,0.25)] animate-pulse ${
            absorptionDominance === 'BULL'
              ? 'bg-emerald-950/80 text-emerald-300 border border-emerald-800/50'
              : absorptionDominance === 'BEAR'
                ? 'bg-red-950/80 text-red-300 border border-red-800/50'
                : 'bg-slate-950/80 text-slate-300 border border-slate-700/50'
          }`}>
            🛡️ {absorptionDominance === 'MIX' ? 'ABS MIX' : `${absorptionDominance} ABS`}
          </span>
        )}
      </div>

      <div className="flex flex-col gap-1 mt-0.5">
        <div className="flex justify-between items-center text-[9px] text-slate-400 font-mono mb-0.5">
          <span>
            Événements (n8): <strong className="text-slate-200 font-semibold">{n8}</strong>
            {absorptionMaxScore !== null && (
              <span className="text-slate-500"> · max score {absorptionMaxScore.toFixed(1)}</span>
            )}
          </span>
          <span className="text-slate-500">
            Dominance: <strong className={domColor}>{absorptionDominance ?? '—'}</strong>
          </span>
        </div>

        <div className="relative h-2.5 bg-slate-950 rounded border border-slate-800/50 overflow-hidden">
          <div
            className={`h-full transition-all duration-300 ${barColor}`}
            style={{ width: `${fillPct}%` }}
          />
          {/* Gate threshold marker (G1/L1 engage at n8 ≥ minN8). */}
          <div
            className="absolute inset-y-0 w-[2px] bg-indigo-400 shadow-[0_0_4px_#818cf8] z-10"
            style={{ left: `${thresholdPct}%` }}
          />
        </div>

        <div className="relative h-3 text-[7px] text-slate-500 font-mono mt-0.5">
          <span className="absolute left-0">0</span>
          <span
            className="absolute text-indigo-400/70 font-semibold"
            style={{ left: `${thresholdPct}%`, transform: 'translateX(-50%)' }}
          >
            Seuil gate ({minN8})
          </span>
          <span className="absolute right-0">{scale}</span>
        </div>
      </div>
    </div>
  );
}

// ── Widget 4: A/D directional veto ─────────────────────────────────────────

function VetoCard({ telemetry }: { telemetry: QuantTelemetryView }) {
  const {
    adType, adConfidence, adDistThreshold, adAccuThreshold,
    adLongBlocked, adShortBlocked, adEventAgeSeconds,
  } = telemetry;

  const threshold = adType === 'DISTRIBUTION' ? adDistThreshold : adAccuThreshold;
  const anyBlocked = adLongBlocked || adShortBlocked;
  const expired = adEventAgeSeconds !== null && adEventAgeSeconds > AD_EVENT_STALE_SEC;

  return (
    <div className={`flex flex-col gap-2 p-3 rounded-lg border transition-all duration-500 ${
      expired
        ? 'bg-slate-900/30 border-slate-800/30 opacity-50'
        : anyBlocked
          ? 'bg-amber-950/15 border-amber-800/60 shadow-[0_0_10px_rgba(217,119,6,0.12)]'
          : 'bg-slate-900/40 border-slate-800/40'
    }`}>
      <div className="flex justify-between items-center">
        <div className="flex items-center gap-1.5">
          <svg className="w-3.5 h-3.5 text-amber-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
          </svg>
          <span className="text-[11px] font-bold text-slate-300 font-sans">A/D Veto directionnel</span>
        </div>
        <div className="flex gap-1">
          {adLongBlocked && (
            <span className="text-[8px] font-mono font-extrabold bg-red-950 text-red-400 border border-red-800/50 px-1.5 py-0.5 rounded animate-pulse">
              🚫 bloque LONG
            </span>
          )}
          {adShortBlocked && (
            <span className="text-[8px] font-mono font-extrabold bg-red-950 text-red-400 border border-red-800/50 px-1.5 py-0.5 rounded animate-pulse">
              🚫 bloque SHORT
            </span>
          )}
          {!anyBlocked && adType !== null && (
            <span className="text-[8px] font-mono font-bold bg-slate-950 text-emerald-400 border border-emerald-900/40 px-1.5 py-0.5 rounded">
              ✅ PASS
            </span>
          )}
        </div>
      </div>

      {adType !== null ? (
        <div className="flex flex-col gap-1 mt-0.5">
          <div className="flex justify-between items-center text-[9px] font-mono">
            <span className="text-slate-400">
              Événement: <strong className={adType === 'ACCUMULATION' ? 'text-purple-400' : 'text-amber-400'}>{adType}</strong>
              {adEventAgeSeconds !== null && (
                <span className={expired ? 'text-amber-400' : 'text-slate-500'}>
                  {' '}· {formatAge(adEventAgeSeconds)}{expired ? ' (expiré)' : ''}
                </span>
              )}
            </span>
            <span className="text-slate-400">Conf: <strong className="text-slate-200">{adConfidence ?? '—'}%</strong></span>
          </div>

          <div className="relative h-1.5 w-full bg-slate-950 rounded-full border border-slate-800/50 overflow-hidden mt-0.5">
            <div
              className={`h-full transition-all duration-500 ${
                anyBlocked
                  ? 'bg-gradient-to-r from-amber-600 to-red-600'
                  : 'bg-gradient-to-r from-purple-600 to-indigo-500'
              }`}
              style={{ width: `${adConfidence ?? 0}%` }}
            />
            <div
              className="absolute inset-y-0 w-[2px] bg-red-500 shadow-[0_0_4px_#ef4444] z-10"
              style={{ left: `${threshold}%` }}
            />
          </div>

          {/* `relative` here is load-bearing: the veto-limit label is absolute
              and must anchor to THIS row, not the whole dashboard. */}
          <div className="relative flex justify-between text-[7px] text-slate-500 font-mono mt-0.5 px-0.5">
            <span>0%</span>
            <span
              className="absolute text-red-400/70 font-semibold"
              style={{ left: `${threshold}%`, transform: 'translateX(-50%)' }}
            >
              Veto Limit ({threshold}%)
            </span>
            <span>100%</span>
          </div>

          <p className="text-[8px] text-slate-500 font-mono leading-tight mt-0.5">
            {adType === 'DISTRIBUTION'
              ? 'Une DISTRIBUTION ≥ seuil ne bloque que la voie LONG (L5).'
              : 'Une ACCUMULATION ≥ seuil ne bloque que la voie SHORT (G5).'}
          </p>
        </div>
      ) : (
        <div className="flex flex-col items-center justify-center h-[34px] text-center px-1">
          <p className="text-[9px] text-slate-500 font-mono leading-tight">
            Aucun événement accumulation / distribution dans la fenêtre 10 min.
          </p>
        </div>
      )}
    </div>
  );
}
