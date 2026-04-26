'use client';

import { useEffect, useState, useCallback } from 'react';
import { api, LiveVerdictView, LiveTradeScenario, LiveFactor } from '@/app/lib/api';

interface Props {
  instrument: string;
  timeframe: string;
  /** Polling interval in ms; default 15s. */
  refreshInterval?: number;
}

const POLL_DEFAULT = 15_000;

/**
 * Live tri-layer analysis panel.
 *
 * Polls /api/analysis/live/{instrument}/{tf} and renders the verdict block
 * mirroring the manual Claude-style analysis: directional bias with confidence,
 * 3-layer score breakdown (Structure/OrderFlow/Momentum), bull/bear factor
 * lists, contradictions, and probabilistic trade scenarios.
 */
export function LiveAnalysisPanel({ instrument, timeframe, refreshInterval = POLL_DEFAULT }: Props) {
  const [verdict, setVerdict] = useState<LiveVerdictView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  // PR #270 review fix: pull scheduler config once so we can short-circuit with
  // a clear "not scanned" message for timeframes the scheduler is not configured
  // to cover, instead of polling /latest forever.
  const [scanConfig, setScanConfig] = useState<{
    schedulerEnabled: boolean;
    instruments: string[];
    timeframes: string[];
  } | null>(null);

  useEffect(() => {
    let cancelled = false;
    api.getAnalysisScanConfig()
      .then(c => { if (!cancelled) setScanConfig(c); })
      .catch(() => { /* leave null — fall back to "loading" rather than wrong info */ });
    return () => { cancelled = true; };
  }, []);

  // PR #270 round-4 review fix: schedulerEnabled gates only background WRITES.
  // /api/analysis/latest still serves persisted verdicts when the scheduler is
  // paused (troubleshooting / data freeze), and operators must keep visibility
  // on what was already captured. So isScanned only checks pair coverage; the
  // schedulerEnabled flag drives a separate banner below.
  const isScanned: boolean | null = scanConfig === null
    ? null
    : (scanConfig.instruments.includes(instrument)
        && scanConfig.timeframes.includes(timeframe));
  const schedulerPaused = scanConfig !== null && !scanConfig.schedulerEnabled;

  // Poll the read-only /latest endpoint so verdict_records does not grow with
  // the number of open dashboards. The scheduler is the single authoritative
  // writer of new verdict rows.
  const fetchVerdict = useCallback(async () => {
    setLoading(true);
    try {
      const v = await api.getLatestAnalysis(instrument, timeframe);
      setVerdict(v);
      setError(null);
    } catch (e) {
      const msg = (e as Error).message;
      // 404 during cold start is expected — surface a friendlier hint.
      // (When schedulerPaused is true and we get 404, it really means
      // "no historical verdicts exist yet" rather than "warming up", so the
      // banner above clarifies the actual situation.)
      setError(msg.includes('404') ? 'No verdict persisted yet for this pair.' : msg);
    } finally {
      setLoading(false);
    }
  }, [instrument, timeframe]);

  // Reset displayed verdict when instrument/timeframe changes so we don't
  // briefly show stale data from the previous context under a new header.
  useEffect(() => {
    setVerdict(null);
    setError(null);
  }, [instrument, timeframe]);

  useEffect(() => {
    if (isScanned === false) return; // skip polling when this pair is excluded
    fetchVerdict();
    const id = setInterval(fetchVerdict, refreshInterval);
    return () => clearInterval(id);
  }, [fetchVerdict, refreshInterval, isScanned]);

  if (isScanned === false) {
    return (
      <div className="bg-zinc-900 border border-amber-900/50 rounded-lg p-4">
        <div className="text-amber-300 text-sm">
          Live analysis is not configured to scan {instrument} · {timeframe}.
        </div>
        <div className="text-zinc-500 text-xs mt-1">
          Add this pair to <code className="text-zinc-400">riskdesk.analysis.instruments</code>
          {' / '}<code className="text-zinc-400">timeframes</code> to enable.
        </div>
      </div>
    );
  }
  if (!verdict && error) {
    return (
      <div className="bg-zinc-900 border border-red-900 rounded-lg p-4">
        <div className="text-red-400 text-sm">Live analysis error: {error}</div>
      </div>
    );
  }
  if (!verdict) {
    return (
      <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-4">
        <div className="text-zinc-500 text-sm">Loading live analysis…</div>
      </div>
    );
  }

  return (
    <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-4 flex flex-col gap-3">
      {schedulerPaused && (
        <div className="text-[11px] text-amber-400 bg-amber-900/20 border border-amber-900/40 rounded px-2 py-1">
          ⏸ Scheduler paused — showing latest persisted verdict; data is not being refreshed.
        </div>
      )}
      {!schedulerPaused && verdict.expired && (
        // PR #270 round-6: scheduler is supposed to be running yet validUntil
        // already lapsed — almost certainly a stall (ingestion outage, IBKR
        // disconnect, scheduler exception loop). Make it loud so operators
        // don't trade off a frozen verdict.
        <div className="text-[11px] text-red-300 bg-red-900/20 border border-red-900/40 rounded px-2 py-1">
          ⚠ Verdict expired {formatExpiredAge(verdict.expiredForSeconds)} ago —
          scheduler may be stalled. Do not act on this signal.
        </div>
      )}
      <Header verdict={verdict} loading={loading} />
      <ScoreBars verdict={verdict} />
      <FactorLists verdict={verdict} />
      <Scenarios verdict={verdict} />
    </div>
  );
}

function formatExpiredAge(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h`;
  return `${Math.floor(seconds / 86400)}d`;
}

function Header({ verdict, loading }: { verdict: LiveVerdictView; loading: boolean }) {
  const { bias, currentPrice, instrument, timeframe } = verdict;
  const color =
    bias.primary === 'LONG' ? 'text-emerald-400' :
    bias.primary === 'SHORT' ? 'text-red-400' : 'text-zinc-400';
  const confColor =
    bias.confidence >= 70 ? 'text-emerald-300' :
    bias.confidence >= 40 ? 'text-yellow-300' : 'text-zinc-500';
  return (
    <div className="flex items-center justify-between">
      <div>
        <div className="text-sm text-zinc-400">
          {instrument} · {timeframe}
          {loading && <span className="ml-2 text-xs text-zinc-600">…</span>}
        </div>
        <div className="flex items-baseline gap-3 mt-1">
          <div className={`text-2xl font-bold ${color}`}>{bias.primary}</div>
          <div className={`text-lg font-mono ${confColor}`}>conf {bias.confidence}</div>
          <div className="text-zinc-500 text-sm">@ {currentPrice}</div>
        </div>
      </div>
      <div className="text-right text-xs text-zinc-500">
        engine v{verdict.scoringEngineVersion}
        <br />
        {new Date(verdict.decisionTimestamp).toLocaleTimeString()}
      </div>
    </div>
  );
}

function ScoreBars({ verdict }: { verdict: LiveVerdictView }) {
  return (
    <div className="grid grid-cols-3 gap-3">
      <ScoreBar label="Structure (50%)" value={verdict.bias.structureScore} />
      <ScoreBar label="Order Flow (30%)" value={verdict.bias.orderFlowScore} />
      <ScoreBar label="Momentum (20%)" value={verdict.bias.momentumScore} />
    </div>
  );
}

function ScoreBar({ label, value }: { label: string; value: number }) {
  const pct = Math.abs(value);
  const color = value > 0 ? 'bg-emerald-500' : value < 0 ? 'bg-red-500' : 'bg-zinc-600';
  return (
    <div className="bg-zinc-800/50 rounded p-2">
      <div className="text-xs text-zinc-400 mb-1">{label}</div>
      <div className="h-2 bg-zinc-700 rounded overflow-hidden flex">
        {value < 0 && <div className={`${color} h-full`} style={{ width: `${pct}%` }} />}
        {value >= 0 && (
          <>
            <div className="flex-1" />
            <div className={`${color} h-full`} style={{ width: `${pct}%` }} />
          </>
        )}
      </div>
      <div className="text-xs font-mono text-zinc-500 mt-1">{value.toFixed(1)}</div>
    </div>
  );
}

function FactorLists({ verdict }: { verdict: LiveVerdictView }) {
  const { bullishFactors, bearishFactors, contradictions, standAsideReason } = verdict.bias;
  return (
    <div className="grid grid-cols-2 gap-3">
      <FactorList title="Bullish factors" factors={bullishFactors} polarity="bull" />
      <FactorList title="Bearish factors" factors={bearishFactors} polarity="bear" />
      {contradictions.length > 0 && (
        <div className="col-span-2 mt-1 text-xs">
          <div className="text-orange-400 font-semibold mb-1">⚠ Contradictions ({contradictions.length})</div>
          <ul className="space-y-0.5 text-zinc-400">
            {contradictions.map((c, i) => (
              <li key={i}>· {c.description}</li>
            ))}
          </ul>
        </div>
      )}
      {standAsideReason && (
        <div className="col-span-2 text-xs text-zinc-500 italic">Stand aside: {standAsideReason}</div>
      )}
    </div>
  );
}

function FactorList({ title, factors, polarity }: { title: string; factors: LiveFactor[]; polarity: 'bull' | 'bear' }) {
  const headerColor = polarity === 'bull' ? 'text-emerald-400' : 'text-red-400';
  return (
    <div className="text-xs">
      <div className={`${headerColor} font-semibold mb-1`}>{title} ({factors.length})</div>
      {factors.length === 0 ? (
        <div className="text-zinc-600 italic">none</div>
      ) : (
        <ul className="space-y-0.5 text-zinc-300">
          {factors.slice(0, 6).map((f, i) => (
            <li key={i} className="flex items-start gap-1">
              <span className="text-zinc-500 shrink-0">{f.layer}:</span>
              <span className="truncate">{f.description}</span>
              <span className="ml-auto text-zinc-500 shrink-0 font-mono">{f.strength.toFixed(0)}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function Scenarios({ verdict }: { verdict: LiveVerdictView }) {
  return (
    <div>
      <div className="text-xs text-zinc-400 font-semibold mb-1">Scenarios</div>
      <div className="space-y-1">
        {verdict.scenarios.map((s, i) => (
          <ScenarioRow key={i} scenario={s} />
        ))}
      </div>
    </div>
  );
}

function ScenarioRow({ scenario }: { scenario: LiveTradeScenario }) {
  const dirColor =
    scenario.direction === 'LONG' ? 'text-emerald-400' :
    scenario.direction === 'SHORT' ? 'text-red-400' : 'text-zinc-500';
  const probPct = Math.round(scenario.probability * 100);
  return (
    <div className="bg-zinc-800/40 rounded p-2 text-xs">
      <div className="flex items-center gap-2">
        <span className="font-mono text-zinc-500">{probPct}%</span>
        <span className="font-semibold text-zinc-200">{scenario.name}</span>
        <span className={`ml-1 ${dirColor}`}>{scenario.direction}</span>
        {scenario.entry !== null && (
          <span className="ml-auto font-mono text-zinc-400">
            @ {scenario.entry} · SL {scenario.stopLoss} · TP {scenario.takeProfit1}
            {' · '}R:R {scenario.rewardRiskRatio.toFixed(1)}
          </span>
        )}
      </div>
      <div className="text-zinc-500 mt-0.5 truncate">▶ {scenario.triggerCondition}</div>
    </div>
  );
}
