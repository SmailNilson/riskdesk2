'use client';

import { useCallback, useEffect, useState } from 'react';
import { api, TrailingStopStats } from '@/app/lib/api';

const PERIODS = [7, 14, 30] as const;

function fmtPct(v: number): string {
  const sign = v > 0 ? '+' : '';
  return `${sign}${(v * 100).toFixed(1)}%`;
}

function fmtPnl(v: number): string {
  const sign = v > 0 ? '+' : '';
  return `${sign}${v.toFixed(2)}`;
}

function deltaColor(v: number): string {
  if (v > 0) return 'text-emerald-400';
  if (v < 0) return 'text-red-400';
  return 'text-zinc-400';
}

export default function TrailingStopStatsPanel() {
  const [days, setDays] = useState<number>(7);
  const [stats, setStats] = useState<TrailingStopStats | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (selectedDays: number) => {
    setLoading(true);
    setError(null);
    try {
      const result = await api.getTrailingStats(selectedDays);
      setStats(result);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load trailing stats');
      setStats(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(days); }, [load, days]);

  return (
    <section className="bg-zinc-900 rounded-lg border border-zinc-800 p-3">
      <div className="flex items-center justify-between mb-2">
        <h3 className="text-[10px] font-semibold uppercase tracking-widest text-zinc-500">
          Trailing Stop Stats
        </h3>
        <div className="flex rounded-md overflow-hidden border border-zinc-800">
          {PERIODS.map(p => (
            <button
              key={p}
              onClick={() => setDays(p)}
              className={`px-2 py-0.5 text-[10px] font-medium transition-colors ${
                days === p
                  ? 'bg-zinc-700 text-white'
                  : 'text-zinc-500 hover:text-zinc-300'
              }`}
            >{p}d</button>
          ))}
        </div>
      </div>

      {loading && !stats && <div className="text-xs text-zinc-500 py-2">Loading…</div>}
      {error && <div className="text-xs text-red-400 py-2">{error}</div>}

      {stats && (
        <div className="grid grid-cols-2 gap-3">
          {/* Fixed SL/TP */}
          <div className="rounded bg-zinc-950/60 p-2">
            <div className="text-[10px] uppercase tracking-widest text-zinc-500 mb-1">Fixed SL/TP</div>
            <div className="flex justify-between text-xs py-0.5">
              <span className="text-zinc-500">Trades</span>
              <span className="text-zinc-200 font-mono">{stats.fixedSLTP.trades}</span>
            </div>
            <div className="flex justify-between text-xs py-0.5">
              <span className="text-zinc-500">Wins</span>
              <span className="text-zinc-200 font-mono">{stats.fixedSLTP.wins}</span>
            </div>
            <div className="flex justify-between text-xs py-0.5">
              <span className="text-zinc-500">Win Rate</span>
              <span className="text-zinc-200 font-mono">{fmtPct(stats.fixedSLTP.winRate)}</span>
            </div>
            <div className="flex justify-between text-xs py-0.5">
              <span className="text-zinc-500">Net PnL</span>
              <span className={`font-mono ${stats.fixedSLTP.netPnl >= 0 ? 'text-emerald-400' : 'text-red-400'}`}>
                {fmtPnl(stats.fixedSLTP.netPnl)}
              </span>
            </div>
          </div>

          {/* Trailing Stop */}
          <div className="rounded bg-zinc-950/60 p-2">
            <div className="text-[10px] uppercase tracking-widest text-zinc-500 mb-1">Trailing Stop</div>
            <div className="flex justify-between text-xs py-0.5">
              <span className="text-zinc-500">Trades</span>
              <span className="text-zinc-200 font-mono">{stats.trailingStop.trades}</span>
            </div>
            <div className="flex justify-between text-xs py-0.5">
              <span className="text-zinc-500">Wins</span>
              <span className="text-zinc-200 font-mono">{stats.trailingStop.wins}</span>
            </div>
            <div className="flex justify-between text-xs py-0.5">
              <span className="text-zinc-500">Win Rate</span>
              <span className="text-zinc-200 font-mono">{fmtPct(stats.trailingStop.winRate)}</span>
            </div>
            <div className="flex justify-between text-xs py-0.5">
              <span className="text-zinc-500">Net PnL</span>
              <span className={`font-mono ${stats.trailingStop.netPnl >= 0 ? 'text-emerald-400' : 'text-red-400'}`}>
                {fmtPnl(stats.trailingStop.netPnl)}
              </span>
            </div>
          </div>

          {/* Improvement summary — spans full width */}
          <div className="col-span-2 rounded border border-zinc-800 p-2 flex items-center gap-4">
            <span className="text-[10px] uppercase tracking-widest text-zinc-500">Trailing vs Fixed</span>
            <div className="flex items-center gap-2 text-xs">
              <span className="text-zinc-500">WinRate Δ</span>
              <span className={`font-mono ${deltaColor(stats.improvement.winRateDelta)}`}>
                {fmtPct(stats.improvement.winRateDelta)}
              </span>
            </div>
            <div className="flex items-center gap-2 text-xs">
              <span className="text-zinc-500">PnL Δ</span>
              <span className={`font-mono ${deltaColor(stats.improvement.pnlDelta)}`}>
                {fmtPnl(stats.improvement.pnlDelta)}
              </span>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}
