'use client';

import { useEffect, useState } from 'react';
import {
  api,
  DxyHealthView,
  DxySnapshotView,
  FxComponentContributionView,
} from '@/app/lib/api';

function formatTimestamp(value: string | null) {
  if (!value) {
    return '—';
  }
  return new Date(value).toLocaleString();
}

function sparklinePoints(values: number[]) {
  if (values.length < 2) {
    return '';
  }
  const width = 280;
  const height = 64;
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  return values.map((value, index) => {
    const x = (index / (values.length - 1)) * width;
    const y = height - ((value - min) / span) * height;
    return `${x},${y}`;
  }).join(' ');
}

export default function DxyPanel() {
  const [latest, setLatest] = useState<DxySnapshotView | null>(null);
  const [health, setHealth] = useState<DxyHealthView | null>(null);
  const [history, setHistory] = useState<DxySnapshotView[]>([]);
  const [breakdown, setBreakdown] = useState<FxComponentContributionView[]>([]);
  const [breakdownOpen, setBreakdownOpen] = useState(false);

  useEffect(() => {
    let cancelled = false;

    const loadLatest = async () => {
      try {
        const [latestSnapshot, healthSnapshot] = await Promise.all([
          api.getDxyLatest(),
          api.getDxyHealth(),
        ]);
        if (!cancelled) {
          setLatest(latestSnapshot);
          setHealth(healthSnapshot);
        }
      } catch {
        if (!cancelled) {
          setLatest(null);
        }
      }
    };

    const loadHistory = async () => {
      try {
        const to = new Date();
        const from = new Date(Date.now() - 24 * 60 * 60 * 1000);
        const rows = await api.getDxyHistory(from.toISOString(), to.toISOString());
        if (!cancelled) {
          setHistory(rows);
        }
      } catch {
        if (!cancelled) {
          setHistory([]);
        }
      }
    };

    // DXY breakdown: which of the 6 FX pairs is actually driving the index
    // today. 404 is expected when no session baseline exists yet (e.g. pre-
    // open), so failures degrade silently to an empty table.
    const loadBreakdown = async () => {
      try {
        const rows = await api.getDxyBreakdown();
        if (!cancelled) {
          setBreakdown(rows);
        }
      } catch {
        if (!cancelled) {
          setBreakdown([]);
        }
      }
    };

    void loadLatest();
    void loadHistory();
    void loadBreakdown();
    const latestTimer = setInterval(loadLatest, 5000);
    const historyTimer = setInterval(loadHistory, 60000);
    const breakdownTimer = setInterval(loadBreakdown, 30000);

    return () => {
      cancelled = true;
      clearInterval(latestTimer);
      clearInterval(historyTimer);
      clearInterval(breakdownTimer);
    };
  }, []);

  const points = sparklinePoints(history.map(row => row.dxyValue));
  const latestValue = latest?.dxyValue ?? null;

  // Use backend-computed % change (baseline = IBKR close or session-open) to match TradingView.
  // Falls back to history-based calculation only when the API field is absent.
  const pctChange: number | null = (() => {
    if (latest?.changePercent != null) return latest.changePercent;
    if (history.length < 2) return null;
    const oldest = history[0].dxyValue;
    const newest = history[history.length - 1].dxyValue;
    if (!oldest) return null;
    return ((newest - oldest) / oldest) * 100;
  })();

  const trend: 'BULLISH' | 'BEARISH' | 'FLAT' | null = pctChange === null
    ? null
    : pctChange > 0 ? 'BULLISH' : pctChange < 0 ? 'BEARISH' : 'FLAT';

  const trendColor = trend === 'BULLISH'
    ? 'text-emerald-400'
    : trend === 'BEARISH'
      ? 'text-red-400'
      : 'text-zinc-400';

  const trendArrow = trend === 'BULLISH' ? '▲' : trend === 'BEARISH' ? '▼' : '—';

  const statusColor = health?.status === 'UP'
    ? 'text-emerald-300 bg-emerald-950/40 border-emerald-800/60'
    : health?.status === 'DEGRADED'
      ? 'text-amber-300 bg-amber-950/40 border-amber-800/60'
      : 'text-red-300 bg-red-950/40 border-red-800/60';

  return (
    <section className="rounded-xl border border-zinc-800 bg-zinc-900/70 p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="text-[11px] uppercase tracking-[0.18em] text-zinc-500">Synthetic DXY</div>
          <div className="mt-1 flex items-end gap-3">
            <div className="text-3xl font-semibold text-white">
              {latestValue != null ? latestValue.toFixed(4) : '—'}
            </div>
            {trend !== null && (
              <div className={`flex items-center gap-1 text-sm font-medium ${trendColor}`}>
                <span>{trendArrow}</span>
                <span>{trend}</span>
                {pctChange !== null && (
                  <span className="text-xs font-normal opacity-80">
                    ({pctChange >= 0 ? '+' : ''}{pctChange.toFixed(2)}% 24h)
                  </span>
                )}
              </div>
            )}
            <div className="text-xs text-zinc-500">
              Updated {formatTimestamp(latest?.timestamp ?? health?.latestTimestamp ?? null)}
            </div>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2 text-xs">
          <span className={`rounded-full border px-2.5 py-1 font-medium ${statusColor}`}>
            {health?.status ?? 'DOWN'}
          </span>
          <span className="rounded-full border border-zinc-700 px-2.5 py-1 text-zinc-300">
            {latest?.source ?? health?.source ?? 'UNAVAILABLE'}
          </span>
          <span className="rounded-full border border-zinc-700 px-2.5 py-1 text-zinc-400">
            skew {health?.maxSkewSeconds ?? 0}s
          </span>
        </div>
      </div>

      <div className="mt-4 grid gap-4 lg:grid-cols-[1.6fr_1fr]">
        <div className="rounded-lg border border-zinc-800 bg-zinc-950/60 p-3">
          <div className="mb-2 text-xs text-zinc-500">24h history</div>
          {points ? (
            <svg viewBox="0 0 280 64" className="h-20 w-full">
              <polyline
                fill="none"
                stroke="#34d399"
                strokeWidth="2"
                strokeLinejoin="round"
                strokeLinecap="round"
                points={points}
              />
            </svg>
          ) : (
            <div className="h-20 text-sm text-zinc-600">No persisted DXY history yet.</div>
          )}
        </div>

        <div className="rounded-lg border border-zinc-800 bg-zinc-950/60 p-3">
          <div className="mb-2 text-xs text-zinc-500">Components</div>
          <div className="space-y-2">
            {(health?.components ?? []).map(component => (
              <div key={component.pair} className="flex items-center justify-between text-sm">
                <span className="text-zinc-400">{component.pair}</span>
                <div className="text-right">
                  <div className="font-mono text-zinc-100">
                    {component.effectivePrice != null ? component.effectivePrice.toFixed(5) : '—'}
                  </div>
                  <div className="text-[11px] text-zinc-500">
                    {component.pricingMethod ?? component.status}
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* ── Per-pair contribution breakdown (collapsed by default) ───────────
          Uses the backend-computed weighted impact vs today's session baseline.
          Sorted by |weightedImpact| desc so the driver sits at the top. */}
      <div className="mt-4">
        <button
          type="button"
          onClick={() => setBreakdownOpen(open => !open)}
          className="flex w-full items-center justify-between rounded-md border border-zinc-800 bg-zinc-950/40 px-3 py-2 text-xs text-zinc-300 hover:border-zinc-700 hover:text-zinc-100 transition-colors"
        >
          <span className="font-medium uppercase tracking-wider text-[11px]">
            Breakdown · which FX pair is moving DXY
          </span>
          <span className="text-zinc-500">{breakdownOpen ? '▾' : '▸'}</span>
        </button>
        {breakdownOpen && (
          <div className="mt-2 rounded-lg border border-zinc-800 bg-zinc-950/60 p-3">
            {breakdown.length === 0 ? (
              <div className="text-[11px] text-zinc-600">
                No breakdown available yet — waiting for a session baseline.
              </div>
            ) : (
              <table className="w-full text-xs">
                <thead>
                  <tr className="text-[10px] uppercase tracking-wider text-zinc-500">
                    <th className="pb-1 text-left font-medium">FX pair</th>
                    <th className="pb-1 text-right font-medium">Price</th>
                    <th className="pb-1 text-right font-medium">% chg</th>
                    <th className="pb-1 text-right font-medium">Weight</th>
                    <th className="pb-1 text-right font-medium">Impact</th>
                  </tr>
                </thead>
                <tbody>
                  {breakdown.map(row => {
                    const impactColor = row.impactDirection === 'BULLISH_DXY'
                      ? 'text-emerald-400'
                      : row.impactDirection === 'BEARISH_DXY'
                        ? 'text-red-400'
                        : 'text-zinc-400';
                    return (
                      <tr key={row.pair} className="border-t border-zinc-900">
                        <td className="py-1 text-zinc-300 font-mono">{row.pair}</td>
                        <td className="py-1 text-right font-mono text-zinc-100">
                          {row.currentRate != null ? Number(row.currentRate).toFixed(5) : '—'}
                        </td>
                        <td className={`py-1 text-right font-mono ${
                          Number(row.pctChange) > 0 ? 'text-emerald-400' :
                          Number(row.pctChange) < 0 ? 'text-red-400' : 'text-zinc-400'
                        }`}>
                          {row.pctChange != null
                            ? `${Number(row.pctChange) >= 0 ? '+' : ''}${Number(row.pctChange).toFixed(3)}%`
                            : '—'}
                        </td>
                        <td className="py-1 text-right font-mono text-zinc-500">
                          {row.dxyWeight != null ? `${(Number(row.dxyWeight) * 100).toFixed(1)}%` : '—'}
                        </td>
                        <td className={`py-1 text-right font-mono ${impactColor}`}>
                          {row.weightedImpact != null
                            ? `${Number(row.weightedImpact) >= 0 ? '+' : ''}${Number(row.weightedImpact).toFixed(4)}`
                            : '—'}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )}
          </div>
        )}
      </div>
    </section>
  );
}
