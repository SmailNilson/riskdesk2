'use client';

import { useEffect, useRef, useState } from 'react';
import { api, ReplayReport } from '@/app/lib/api';

interface Props {
  instrument: string;
  timeframe: string;
}

const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * Backtest replay form — re-scores persisted snapshots with candidate weights.
 * <p>
 * Inputs: date range + (structure, orderFlow, momentum) weights summing to 1.0.
 * Output: agreement ratio with original verdicts + direction distribution.
 * <p>
 * Use it to grid-search weight combinations against your recorded data: pick
 * the ones that agree most with profitable outcomes once you have outcome
 * tracking wired (out of scope for this PR).
 */
export function AnalysisReplayPanel({ instrument, timeframe }: Props) {
  const [structure, setStructure] = useState(0.5);
  const [orderFlow, setOrderFlow] = useState(0.3);
  const [momentum, setMomentum]   = useState(0.2);
  const [days, setDays] = useState(7);
  const [loading, setLoading] = useState(false);
  const [report, setReport] = useState<ReplayReport | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Round-4: request token drops stale responses if context changes.
  // Round-5: also reset loading so the spinner doesn't stick when guard skips finally.
  // Round-6: tokens must ALSO bump on weights/window changes — otherwise the
  // response for the OLD weights still passes the token check and renders under
  // the new sliders, mislabeling tuning results. We bump on every param change
  // and reset loading (same fix as round-5 for stuck spinner). Report is only
  // cleared on instrument/timeframe changes (context change = different market);
  // tweaking weights/days keeps the previous report visible until the user Runs
  // again — that's intentional, otherwise the panel flickers on every slider tick.
  const requestTokenRef = useRef(0);

  // Any input that affects the replay request → invalidate in-flight responses
  // and unstick the spinner. Cheap (token is a number, not state).
  useEffect(() => {
    requestTokenRef.current += 1;
    setLoading(false);
  }, [instrument, timeframe, structure, orderFlow, momentum, days]);

  // Context change (different market) → also clear last shown report/error so
  // we don't display previous-instrument metrics under the new header.
  useEffect(() => {
    setReport(null);
    setError(null);
  }, [instrument, timeframe]);

  const total = structure + orderFlow + momentum;
  const balanced = Math.abs(total - 1.0) < 0.01;

  const runReplay = async () => {
    if (!balanced) {
      setError(`Weights must sum to 1.0 (currently ${total.toFixed(2)})`);
      return;
    }
    const myToken = ++requestTokenRef.current;
    const myInstrument = instrument;
    const myTimeframe = timeframe;
    setLoading(true);
    setError(null);
    try {
      const to = new Date();
      const from = new Date(to.getTime() - days * DAY_MS);
      const res = await api.replayAnalysis({
        instrument: myInstrument,
        timeframe: myTimeframe,
        from: from.toISOString(),
        to: to.toISOString(),
        structure,
        orderFlow,
        momentum,
      });
      // Drop the response if a newer request started OR if context changed
      // mid-flight — the user is no longer looking at this combination.
      if (myToken !== requestTokenRef.current) return;
      setReport(res);
    } catch (e) {
      if (myToken !== requestTokenRef.current) return;
      setError((e as Error).message);
    } finally {
      if (myToken === requestTokenRef.current) setLoading(false);
    }
  };

  return (
    <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-4 flex flex-col gap-3">
      <div className="text-sm font-semibold text-zinc-200">
        Backtest replay — {instrument} · {timeframe}
      </div>

      <div className="grid grid-cols-3 gap-2 text-xs">
        <WeightSlider label="Structure" value={structure} onChange={setStructure} color="bg-emerald-500" />
        <WeightSlider label="Order Flow" value={orderFlow} onChange={setOrderFlow} color="bg-cyan-500" />
        <WeightSlider label="Momentum"  value={momentum}  onChange={setMomentum}  color="bg-orange-500" />
      </div>
      <div className={`text-xs font-mono ${balanced ? 'text-zinc-500' : 'text-red-400'}`}>
        sum {total.toFixed(2)} {balanced ? '✓' : '(must equal 1.0)'}
      </div>

      <div className="flex items-center gap-2">
        <label className="text-xs text-zinc-400">Window (days):</label>
        <input
          type="number"
          value={days}
          min={1}
          max={90}
          onChange={e => setDays(parseInt(e.target.value || '7'))}
          className="bg-zinc-800 text-zinc-200 text-xs px-2 py-1 rounded w-16"
        />
        <button
          onClick={runReplay}
          disabled={!balanced || loading}
          className="ml-auto px-3 py-1 rounded bg-indigo-700 hover:bg-indigo-600 disabled:bg-zinc-800 disabled:text-zinc-500 text-xs font-semibold"
        >
          {loading ? 'Running…' : 'Run replay'}
        </button>
      </div>

      {error && <div className="text-xs text-red-400">{error}</div>}

      {report && <ReplayResults report={report} />}
    </div>
  );
}

function WeightSlider({ label, value, onChange, color }: {
  label: string; value: number; onChange: (v: number) => void; color: string;
}) {
  return (
    <div>
      <div className="flex items-baseline justify-between">
        <span className="text-zinc-400">{label}</span>
        <span className="font-mono text-zinc-200">{(value * 100).toFixed(0)}%</span>
      </div>
      <input
        type="range" min={0} max={100} step={5}
        value={value * 100}
        onChange={e => onChange(parseInt(e.target.value) / 100)}
        className="w-full"
      />
      <div className="h-1 bg-zinc-700 rounded overflow-hidden">
        <div className={`${color} h-full`} style={{ width: `${value * 100}%` }} />
      </div>
    </div>
  );
}

function ReplayResults({ report }: { report: ReplayReport }) {
  const agreementPct = (report.agreementRatio * 100).toFixed(1);
  return (
    <div className="bg-zinc-800/40 rounded p-3 text-xs space-y-1">
      <div className="flex items-baseline justify-between">
        <span className="text-zinc-400">Total snapshots:</span>
        <span className="font-mono text-zinc-200">{report.totalSnapshots}</span>
      </div>
      <div className="flex items-baseline justify-between">
        <span className="text-zinc-400">Agreement w/ original:</span>
        <span className={`font-mono ${report.agreementRatio >= 0.7 ? 'text-emerald-400'
          : report.agreementRatio >= 0.5 ? 'text-yellow-400' : 'text-red-400'}`}>
          {report.agreementCount}/{report.totalSnapshots} ({agreementPct}%)
        </span>
      </div>
      <div className="flex items-baseline justify-between">
        <span className="text-zinc-400">Actionable verdicts:</span>
        <span className="font-mono text-zinc-200">{report.actionableCount}</span>
      </div>
      <div className="border-t border-zinc-700 pt-1 mt-1">
        <div className="text-zinc-500 mb-1">Direction distribution (replayed)</div>
        <div className="space-y-0.5">
          {Object.entries(report.directionDistribution).map(([dir, count]) => (
            <div key={dir} className="flex items-baseline justify-between">
              <span className={
                dir === 'LONG' ? 'text-emerald-400' :
                dir === 'SHORT' ? 'text-red-400' : 'text-zinc-500'
              }>{dir}</span>
              <span className="font-mono text-zinc-300">
                {count} ({((count / report.totalSnapshots) * 100).toFixed(1)}%)
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
