'use client';

import { useEffect, useState } from 'react';
import { api } from '@/app/lib/api';
import { useQuantStream } from '@/app/hooks/useQuantStream';
import {
  GATE_LABELS,
  QUANT_INSTRUMENTS,
  type QuantInstrument,
  type QuantSnapshotView,
} from './types';

function scoreClass(score: number): string {
  if (score >= 7) return 'bg-emerald-600 text-white';
  if (score >= 6) return 'bg-amber-500 text-white';
  if (score >= 4) return 'bg-slate-600 text-white';
  return 'bg-slate-700 text-slate-300';
}

function formatPrice(p: number | null): string {
  if (p === null || Number.isNaN(p)) return '—';
  return p.toFixed(2);
}

/**
 * Live view of the Quant 7-Gates evaluator. Subscribes to the per-instrument
 * snapshot WebSocket topic and renders pass/fail for each gate plus the
 * suggested SHORT setup levels when {@code score ≥ 6}.
 */
export default function QuantGatePanel() {
  const [active, setActive] = useState<QuantInstrument>('MNQ');
  const { snapshots, connected } = useQuantStream(QUANT_INSTRUMENTS);
  const [bootstrap, setBootstrap] = useState<Record<string, QuantSnapshotView>>({});

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
                instr === active ? 'bg-slate-700 text-white' : 'bg-slate-800 text-slate-400 hover:bg-slate-700'
              }`}
            >
              {instr}
            </button>
          ))}
        </div>
      </header>

      {snapshot ? (
        <>
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center gap-3">
              <span className={`px-2 py-1 rounded text-sm font-bold ${scoreClass(snapshot.score)}`}>
                {snapshot.score}/7
              </span>
              <span className="text-sm text-slate-300">
                px <span className="font-mono">{formatPrice(snapshot.price)}</span>{' '}
                <span className="text-slate-500">[{snapshot.priceSource || '—'}]</span>
              </span>
              <span className="text-sm text-slate-400">
                Δjour <span className="font-mono">{snapshot.dayMove >= 0 ? '+' : ''}{snapshot.dayMove.toFixed(0)}pts</span>
              </span>
            </div>
            <span className="text-xs text-slate-500">{snapshot.scanTime ?? '—'}</span>
          </div>

          <ul className="space-y-1 text-sm font-mono">
            {snapshot.gates.map((g) => (
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

          {snapshot.score >= 6 && snapshot.entry !== null && snapshot.sl !== null && (
            <div className="mt-4 rounded border border-amber-700 bg-amber-950/40 p-3 text-sm">
              <div className="font-semibold mb-1 flex items-center gap-2">
                {snapshot.shortSetup7_7 ? (
                  <span className="text-emerald-400">🔔 SHORT 7/7 — full setup</span>
                ) : (
                  <span className="text-amber-400">⚠️ SETUP 6/7 — early warning</span>
                )}
              </div>
              <div className="grid grid-cols-4 gap-2 text-xs font-mono">
                <div><span className="text-slate-400">ENTRY</span><br />{formatPrice(snapshot.entry)}</div>
                <div><span className="text-slate-400">SL</span><br />{formatPrice(snapshot.sl)}</div>
                <div><span className="text-slate-400">TP1</span><br />{formatPrice(snapshot.tp1)}</div>
                <div><span className="text-slate-400">TP2</span><br />{formatPrice(snapshot.tp2)}</div>
              </div>
            </div>
          )}
        </>
      ) : (
        <p className="text-sm text-slate-400">No snapshot yet. The scheduler runs every 60 seconds.</p>
      )}
    </section>
  );
}
