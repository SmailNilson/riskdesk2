'use client';

import { useMemo } from 'react';
import { useQuant7GatesSimulations } from '@/app/hooks/useQuant7GatesSimulations';
import type { Quant7GatesSimulationView } from '@/app/lib/api';

/**
 * Live tracker for the Quant 7-Gates simulation harness.
 *
 * <p>Renders open + closed simulated trades produced by the backend
 * {@code Quant7GatesSimulationService}. Each row shows direction, entry, SL,
 * TP1, TP2, current/exit price, and live P&L. Closed trades are bucketed at
 * the bottom for an at-a-glance read of the gate set's edge.
 *
 * <p>Entry rule (driven server-side): pattern HIGH confidence + Δ Confirmed
 * + Abs Bull (LONG) / Abs Bear (SHORT) + flow TRADE. Exit rule: flow AVOID
 * or SL/TP touched.
 */
export default function Quant7GatesSimulationPanel() {
  const { rows, stats, connected } = useQuant7GatesSimulations();

  const { open, closed } = useMemo(() => {
    const o: Quant7GatesSimulationView[] = [];
    const c: Quant7GatesSimulationView[] = [];
    for (const r of rows) {
      (r.status === 'OPEN' ? o : c).push(r);
    }
    return { open: o, closed: c };
  }, [rows]);

  return (
    <section className="rounded-lg border border-slate-700 bg-slate-900 p-4 text-slate-100">
      <header className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <h2 className="text-lg font-semibold">Quant 7-Gates Simulation</h2>
          <span
            className={`text-xs px-2 py-0.5 rounded ${connected ? 'bg-emerald-700' : 'bg-slate-700'}`}
            title={connected ? 'WebSocket connected' : 'WebSocket disconnected'}
          >
            {connected ? 'live' : 'offline'}
          </span>
        </div>
        <div className="text-[10px] text-slate-500 font-mono">
          Entry: Abs Bull/Bear · Δ Confirmed · flow TRADE · HIGH conf — Exit: flow AVOID / SL / TP
        </div>
      </header>

      <StatsStrip stats={stats} openCount={open.length} />

      <div className="mt-3 space-y-3">
        <Section label="Open positions" rows={open} emptyHint="No open trades — waiting for the next qualified setup." />
        <Section label="Recently closed" rows={closed.slice(0, 20)} emptyHint="No closed trades yet." closed />
      </div>
    </section>
  );
}

function StatsStrip({
  stats,
  openCount,
}: {
  stats: ReturnType<typeof useQuant7GatesSimulations>['stats'];
  openCount: number;
}) {
  const closedCount = stats?.closedCount ?? 0;
  const wins = stats?.wins ?? 0;
  const losses = stats?.losses ?? 0;
  const winRate = stats?.winRatePct ?? null;
  const netUsd = stats?.netUsd ?? 0;

  return (
    <div className="grid grid-cols-2 md:grid-cols-4 lg:grid-cols-6 gap-2 border border-slate-800 rounded bg-slate-950/60 p-2">
      <StatCell label="Open" value={String(openCount)} tone="text-amber-400" />
      <StatCell label="Closed" value={String(closedCount)} tone="text-slate-200" />
      <StatCell label="Wins" value={String(wins)} tone="text-emerald-400" />
      <StatCell label="Losses" value={String(losses)} tone="text-rose-400" />
      <StatCell
        label="Win rate"
        value={winRate != null ? `${winRate.toFixed(0)}%` : '—'}
        tone={winRateTone(winRate)}
      />
      <StatCell
        label="Net P&L"
        value={`${netUsd >= 0 ? '+' : ''}$${Math.abs(netUsd).toFixed(0)}`}
        tone={netUsd >= 0 ? 'text-emerald-400' : 'text-rose-400'}
      />
    </div>
  );
}

function StatCell({ label, value, tone }: { label: string; value: string; tone: string }) {
  return (
    <div className="flex flex-col">
      <span className="text-[9px] uppercase tracking-wider text-slate-500">{label}</span>
      <span className={`font-mono text-sm font-semibold ${tone}`}>{value}</span>
    </div>
  );
}

function winRateTone(pct: number | null): string {
  if (pct == null) return 'text-slate-400';
  if (pct >= 60) return 'text-emerald-400';
  if (pct >= 45) return 'text-amber-400';
  return 'text-rose-400';
}

function Section({
  label,
  rows,
  emptyHint,
  closed,
}: {
  label: string;
  rows: Quant7GatesSimulationView[];
  emptyHint: string;
  closed?: boolean;
}) {
  return (
    <div>
      <div className="text-[10px] font-semibold text-slate-500 uppercase tracking-wider mb-1.5">
        {label} {rows.length > 0 && <span className="text-slate-600">· {rows.length}</span>}
      </div>
      {rows.length === 0 ? (
        <div className="rounded border border-slate-800 bg-slate-950/40 p-3 text-xs text-slate-500 italic">
          {emptyHint}
        </div>
      ) : (
        <div className="space-y-2">
          {rows.map(r => (
            <TradeCard key={r.id} row={r} closed={closed} />
          ))}
        </div>
      )}
    </div>
  );
}

function TradeCard({ row, closed }: { row: Quant7GatesSimulationView; closed?: boolean }) {
  const isLong = row.direction === 'LONG';
  const dirTone = isLong ? 'border-emerald-700 text-emerald-300 bg-emerald-950/30'
                         : 'border-rose-700 text-rose-300 bg-rose-950/30';
  const pnlPts = row.pnlPoints ?? 0;
  const pnlUsd = row.pnlUsd ?? 0;
  const pnlTone = pnlPts > 0 ? 'text-emerald-400' : pnlPts < 0 ? 'text-rose-400' : 'text-slate-400';
  const statusTone = statusToneFor(row.status);
  const livePrice = closed ? (row.exitPrice ?? row.entryPrice) : (row.exitPrice ?? row.entryPrice);

  return (
    <div className="rounded border border-slate-800 bg-slate-950/40 p-2.5 space-y-2">
      <div className="flex items-center gap-2 flex-wrap">
        <span className={`px-2 py-0.5 rounded text-[10px] font-mono font-bold border ${dirTone}`}>
          {isLong ? '▲ LONG' : '▼ SHORT'}
        </span>
        <span className="font-mono text-xs text-slate-200">{row.instrument}</span>
        <span className={`px-1.5 py-0.5 rounded text-[9px] font-mono font-semibold border ${statusTone}`}>
          {row.status.replace('CLOSED_', '').replace(/_/g, ' ')}
        </span>
        <span className="ml-auto text-[10px] font-mono text-slate-500" title={row.openedAt}>
          #{row.id} · {formatRelative(row.openedAt)}
        </span>
      </div>

      {/* Trade plan: ENTRY · SL · TP1 · TP2 — same vocabulary as PLAYBOOK */}
      <div className="grid grid-cols-4 gap-2 text-center font-mono text-xs">
        <PricePill label="ENTRY" value={row.entryPrice} tone="text-slate-100" />
        <PricePill label="SL"    value={row.stopLoss}   tone="text-rose-400" />
        <PricePill label="TP1"   value={row.takeProfit1} tone="text-emerald-400" />
        <PricePill label="TP2"   value={row.takeProfit2} tone="text-emerald-500/70" />
      </div>

      {/* P&L + exit details */}
      <div className="flex items-center justify-between text-[11px] font-mono">
        <div className="text-slate-400">
          {closed
            ? (
                <>
                  Exit <span className="text-slate-200">{formatPrice(row.exitPrice ?? null)}</span>
                  {row.closedAt && <span className="text-slate-600 ml-2">· {formatRelative(row.closedAt)}</span>}
                </>
              )
            : (
                <>
                  Spot <span className="text-slate-200">{formatPrice(livePrice)}</span>
                </>
              )}
        </div>
        <div className={pnlTone}>
          {pnlPts >= 0 ? '+' : ''}{pnlPts.toFixed(2)} pts
          {row.pnlUsd != null && (
            <span className="ml-2">({pnlUsd >= 0 ? '+' : ''}${Math.abs(pnlUsd).toFixed(0)})</span>
          )}
        </div>
      </div>

      {(row.entryReason || row.exitReason) && (
        <div className="text-[10px] font-mono text-slate-500 leading-snug">
          <div>
            <span className="text-slate-600">entry:</span> {row.entryReason}
          </div>
          {row.exitReason && (
            <div>
              <span className="text-slate-600">exit:</span> {row.exitReason}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function PricePill({ label, value, tone }: { label: string; value: number; tone: string }) {
  return (
    <div className="rounded bg-slate-900/60 px-2 py-1">
      <div className="text-[9px] text-slate-500 uppercase tracking-wider">{label}</div>
      <div className={`text-sm font-semibold ${tone}`}>{formatPrice(value)}</div>
    </div>
  );
}

function statusToneFor(status: Quant7GatesSimulationView['status']): string {
  switch (status) {
    case 'OPEN':              return 'border-amber-700 bg-amber-950/30 text-amber-300';
    case 'CLOSED_TP1':        return 'border-emerald-700 bg-emerald-950/30 text-emerald-300';
    case 'CLOSED_TP2':        return 'border-emerald-600 bg-emerald-950/40 text-emerald-200';
    case 'CLOSED_SL':         return 'border-rose-700 bg-rose-950/30 text-rose-300';
    case 'CLOSED_FLOW_AVOID': return 'border-slate-600 bg-slate-900/60 text-slate-300';
  }
}

function formatPrice(v: number | null): string {
  if (v == null || !Number.isFinite(v)) return '—';
  if (Math.abs(v) < 10) return v.toFixed(5);
  if (Math.abs(v) < 1000) return v.toFixed(2);
  return v.toFixed(2);
}

function formatRelative(iso: string): string {
  const then = Date.parse(iso);
  if (!Number.isFinite(then)) return iso;
  const s = Math.max(0, Math.round((Date.now() - then) / 1000));
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.round(h / 24)}d ago`;
}
