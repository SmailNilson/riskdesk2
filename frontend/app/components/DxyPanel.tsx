'use client';

import { useEffect, useState } from 'react';
import { api, DxyHealthView, DxySnapshotView } from '@/app/lib/api';

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

    void loadLatest();
    void loadHistory();
    const latestTimer = setInterval(loadLatest, 5000);
    const historyTimer = setInterval(loadHistory, 60000);

    return () => {
      cancelled = true;
      clearInterval(latestTimer);
      clearInterval(historyTimer);
    };
  }, []);

  const points = sparklinePoints(history.map(row => row.dxyValue));
  const latestValue = latest?.dxyValue ?? null;
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
    </section>
  );
}
