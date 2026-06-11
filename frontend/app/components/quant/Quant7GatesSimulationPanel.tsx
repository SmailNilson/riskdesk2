'use client';

import { useMemo } from 'react';
import { computeStats, useQuant7GatesSimulations } from '@/app/hooks/useQuant7GatesSimulations';
import { useQuantSimExecState } from '@/app/hooks/useQuantSimExecState';
import type { Quant7GatesSimulationStats, Quant7GatesSimulationView, QuantSimExecState } from '@/app/lib/api';

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
  const { state: execState, error: execError, busy: execBusy, setEnabled } = useQuantSimExecState();

  const { open, closed } = useMemo(() => {
    const o: Quant7GatesSimulationView[] = [];
    const c: Quant7GatesSimulationView[] = [];
    for (const r of rows) {
      (r.status === 'OPEN' ? o : c).push(r);
    }
    // The incoming list is globally ordered by openedAt, so a long-running
    // trade that just closed would otherwise be pushed off the bottom by
    // newer-opened but older-closed rows. Re-order closed by closedAt
    // (fallback openedAt) before the 20-row slice in render so "recently
    // closed" matches actual exit recency — same key the backend uses for
    // history eviction, so the two views agree.
    c.sort((a, b) => {
      const ka = Date.parse(a.closedAt ?? a.openedAt);
      const kb = Date.parse(b.closedAt ?? b.openedAt);
      const diff = kb - ka;
      return Number.isFinite(diff) ? diff : 0;
    });
    return { open: o, closed: c };
  }, [rows]);

  // Per-instrument breakdown — each market is judged on its own P&L; the
  // global strip alone lets a winner mask (or fake) a loser in the blend.
  const perInstrument = useMemo(() => {
    const grouped = new Map<string, Quant7GatesSimulationView[]>();
    for (const r of rows) {
      const list = grouped.get(r.instrument);
      if (list) list.push(r);
      else grouped.set(r.instrument, [r]);
    }
    return Array.from(grouped.entries())
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([instrument, instrRows]) => ({ instrument, stats: computeStats(instrRows) }));
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
          Entry: Abs Bull/Bear · Δ Confirmed · flow TRADE · HIGH conf · HTF 1h — Exit: SL / TP (ATR) / EOD flat
        </div>
      </header>

      <StatsStrip stats={stats} openCount={open.length} />

      {perInstrument.length > 1 && (
        <div className="mt-2 space-y-1">
          {perInstrument.map(({ instrument, stats: s }) => (
            <InstrumentStatsRow key={instrument} instrument={instrument} stats={s} />
          ))}
        </div>
      )}

      <AutoIbkrControls state={execState} error={execError} busy={execBusy} onToggle={setEnabled} />

      <div className="mt-3 space-y-3">
        <Section label="Open positions" rows={open}
          emptyHint="No open trades — waiting for the next qualified setup." />
        <Section label="Recently closed" rows={closed.slice(0, 20)} emptyHint="No closed trades yet." closed />
      </div>
    </section>
  );
}

/**
 * Auto-IBKR mirror controls. One toggle per allowlisted instrument (MNQ, MCL —
 * the only net-positive instruments; MGC/6E can't route). A live order needs
 * BOTH the master flag and the instrument toggle, so when the master flag is off
 * the toggles render disabled with a hint.
 */
function AutoIbkrControls({
  state,
  error,
  busy,
  onToggle,
}: {
  state: QuantSimExecState | null;
  error: string | null;
  busy: string | null;
  onToggle: (instrument: string, enabled: boolean) => void;
}) {
  if (!state) {
    return (
      <div className="mt-2 text-[10px] font-mono text-slate-600">
        {error ? `Auto-IBKR unavailable: ${error}` : 'Loading Auto-IBKR state…'}
      </div>
    );
  }
  const masterOff = !state.masterEnabled;
  return (
    <div className="mt-2 flex flex-wrap items-center gap-2 rounded border border-slate-800 bg-slate-950/60 p-2">
      <span className="text-[10px] font-semibold uppercase tracking-wider text-slate-400">Auto-IBKR</span>
      {masterOff && (
        <span
          className="text-[9px] font-mono text-amber-400"
          title="riskdesk.quant.sim-exec.enabled is false — toggles have no effect until the master flag is on"
        >
          master OFF
        </span>
      )}
      {state.allowlist.map(instr => {
        const on = state.toggles[instr] ?? false;
        const pending = busy === instr;
        return (
          <button
            key={instr}
            type="button"
            disabled={pending}
            onClick={() => onToggle(instr, !on)}
            title={masterOff
              ? `${instr}: armed=${on} (master flag OFF — no live orders)`
              : `${instr}: ${on ? 'mirroring to IBKR — click to disarm' : 'paper only — click to arm live orders'}`}
            className={`px-2 py-0.5 rounded text-[10px] font-mono font-bold border transition-colors ${
              pending ? 'opacity-50 cursor-wait ' : ''
            }${
              on && !masterOff
                ? 'border-emerald-600 bg-emerald-950/40 text-emerald-300'
                : on && masterOff
                  ? 'border-amber-700 bg-amber-950/30 text-amber-300'
                  : 'border-slate-700 bg-slate-900/60 text-slate-400'
            }`}
          >
            {instr} {on ? 'ON' : 'OFF'}
          </button>
        );
      })}
      {error && <span className="text-[9px] font-mono text-rose-400">{error}</span>}
      <span className="basis-full text-[9px] font-mono text-slate-600">
        ON arms a live IBKR order for the next qualifying setup on that instrument — it is an arming
        state, not a per-row position indicator.
      </span>
    </div>
  );
}

function StatsStrip({
  stats,
  openCount,
}: {
  stats: ReturnType<typeof useQuant7GatesSimulations>['stats'];
  openCount: number;
}) {
  const closedCount = stats.closedCount;
  const wins = stats.wins;
  const losses = stats.losses;
  const winRate = stats.winRatePct;
  const netUsd = stats.netUsd;

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
        value={formatSignedUsd(netUsd)}
        tone={netUsd >= 0 ? 'text-emerald-400' : 'text-rose-400'}
      />
    </div>
  );
}

/**
 * One-line per-instrument slice of the global strip. Same reducer
 * ({@code computeStats}) over the instrument's own rows, so the slices always
 * sum to the global strip above them.
 */
function InstrumentStatsRow({
  instrument,
  stats,
}: {
  instrument: string;
  stats: Quant7GatesSimulationStats;
}) {
  const winRate = stats.winRatePct;
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded border border-slate-800 bg-slate-950/40 px-2 py-1 font-mono text-[10px]">
      <span className="w-10 font-bold text-slate-200">{instrument}</span>
      <span className="text-amber-400">{stats.openCount} open</span>
      <span className="text-slate-400">{stats.closedCount} closed</span>
      <span>
        <span className="text-emerald-400">{stats.wins}W</span>
        <span className="text-slate-600"> / </span>
        <span className="text-rose-400">{stats.losses}L</span>
      </span>
      <span className={winRateTone(winRate)}>
        {winRate != null ? `WR ${winRate.toFixed(0)}%` : 'WR —'}
      </span>
      <span className={`ml-auto font-semibold ${stats.netUsd >= 0 ? 'text-emerald-400' : 'text-rose-400'}`}>
        {formatSignedUsd(stats.netUsd)}
      </span>
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
        <PriceSourceBadge source={row.priceSource} label="entry" />
        {row.exitPriceSource && row.exitPriceSource !== row.priceSource && (
          <PriceSourceBadge source={row.exitPriceSource} label={closed ? 'exit' : 'mark'} />
        )}
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
            <span className="ml-2">({formatSignedUsd(pnlUsd)})</span>
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

/**
 * Provenance pill — tells the operator whether the row's prices come from a
 * real IBKR tick ({@code LIVE_PUSH}) or a degraded DB fallback. During a
 * feed outage every other indicator looks normal, so without this badge it's
 * easy to misread a fallback-driven simulation as live signal.
 *
 * <p>{@code label} optionally distinguishes the entry source from the
 * mark-to-market / exit source — relevant when entry was live but a later
 * mark or close happened during a feed outage (or vice versa).
 */
function PriceSourceBadge({ source, label }: { source: string | undefined; label?: string }) {
  if (!source) return null;
  const live = source === 'LIVE_PUSH';
  const tone = live
    ? 'border-emerald-700 bg-emerald-950/30 text-emerald-300'
    : 'border-amber-700 bg-amber-950/30 text-amber-300';
  return (
    <span
      className={`px-1.5 py-0.5 rounded text-[9px] font-mono font-semibold border ${tone}`}
      title={live ? `Live IBKR tick (${label ?? 'price'})` : `Price source (${label ?? 'price'}): ${source}`}
    >
      {label ? `${label}: ${source}` : source}
    </span>
  );
}

function statusToneFor(status: Quant7GatesSimulationView['status']): string {
  switch (status) {
    case 'OPEN':              return 'border-amber-700 bg-amber-950/30 text-amber-300';
    case 'CLOSED_TP1':        return 'border-emerald-700 bg-emerald-950/30 text-emerald-300';
    case 'CLOSED_TP2':        return 'border-emerald-600 bg-emerald-950/40 text-emerald-200';
    case 'CLOSED_SL':         return 'border-rose-700 bg-rose-950/30 text-rose-300';
    case 'CLOSED_FLOW_AVOID': return 'border-slate-600 bg-slate-900/60 text-slate-300';
    case 'CLOSED_EOD':        return 'border-sky-700 bg-sky-950/30 text-sky-300';
  }
}

/**
 * Signed USD formatter that preserves the minus sign on losses.
 *
 * <p>Without this, the previous {@code Math.abs(...)} pattern made a -$420
 * loss render as "$420" with only the surrounding text colour indicating
 * direction — confusing in raw read and outright wrong when copy-pasted
 * outside the dashboard. Returns "+$N" or "-$N" with no decimals.
 */
function formatSignedUsd(value: number): string {
  if (!Number.isFinite(value)) return '—';
  const sign = value >= 0 ? '+' : '-';
  return `${sign}$${Math.abs(value).toFixed(0)}`;
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
