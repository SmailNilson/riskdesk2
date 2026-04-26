'use client';

/**
 * SimulationDashboard — Phase 2 UI for the `trade_simulations` aggregate.
 *
 * <p>Surfaces the read model exposed by {@code SimulationController}:
 * <ul>
 *   <li>Top strip: aggregated counters (total / WIN / LOSS / MISSED / CANCELLED /
 *       win-rate %) plus average MFE (bestFavorablePrice) across resolved rows.</li>
 *   <li>Scrollable list: one card per simulation, sorted newest first, with a
 *       status badge, instrument/action/reviewType labels, relative timestamps,
 *       drawdown/MFE figures, and an optional trailing-stop block.</li>
 * </ul>
 *
 * <p>This panel is read-only. It does NOT link back to MentorSignalPanel (Phase 3
 * will migrate review-side simulation consumers). The reviewId is shown as a
 * plain mono label so operators can cross-reference manually.
 *
 * <p>Dark theme palette is aligned with {@link TradeDecisionPanel}: emerald for
 * wins, rose for losses, zinc for neutral/pending, amber for pending-entry.
 */

import { useMemo } from 'react';
import { useSimulations } from '@/app/hooks/useSimulations';
import type { TradeSimulationView } from '@/app/lib/api';

interface SimulationDashboardProps {
  instrument?: string;
}

interface SummaryStats {
  total: number;
  win: number;
  loss: number;
  missed: number;
  cancelled: number;
  pending: number;
  active: number;
  winRatePct: number | null;
  avgMfe: number | null;
}

function computeStats(sims: TradeSimulationView[]): SummaryStats {
  let win = 0;
  let loss = 0;
  let missed = 0;
  let cancelled = 0;
  let pending = 0;
  let active = 0;
  let mfeSum = 0;
  let mfeCount = 0;

  for (const sim of sims) {
    switch (sim.simulationStatus) {
      case 'WIN': win++; break;
      case 'LOSS': loss++; break;
      case 'MISSED': missed++; break;
      case 'CANCELLED': cancelled++; break;
      case 'PENDING_ENTRY': pending++; break;
      case 'ACTIVE': active++; break;
      default: break;
    }
    if (sim.bestFavorablePrice != null && Number.isFinite(sim.bestFavorablePrice)) {
      mfeSum += sim.bestFavorablePrice;
      mfeCount++;
    }
  }

  const resolved = win + loss;
  const winRatePct = resolved > 0 ? (win / resolved) * 100 : null;
  const avgMfe = mfeCount > 0 ? mfeSum / mfeCount : null;

  return {
    total: sims.length,
    win,
    loss,
    missed,
    cancelled,
    pending,
    active,
    winRatePct,
    avgMfe,
  };
}

export default function SimulationDashboard({ instrument }: SimulationDashboardProps) {
  const { simulations, connected } = useSimulations();

  const visible = useMemo(() => {
    if (!instrument) return simulations;
    return simulations.filter(s => s.instrument === instrument);
  }, [simulations, instrument]);

  const stats = useMemo(() => computeStats(visible), [visible]);

  return (
    <div className="p-4 space-y-3 text-sm max-h-[720px] overflow-y-auto">
      {/* Header row */}
      <div className="flex items-center justify-between">
        <div className="text-[10px] uppercase tracking-widest text-zinc-500">
          Simulations{instrument ? ` / ${instrument}` : ''} - {visible.length} tracked
        </div>
        <div className="flex items-center gap-2">
          <span
            className={`inline-block h-2 w-2 rounded-full ${
              connected ? 'bg-emerald-500' : 'bg-zinc-600'
            }`}
            title={connected ? 'WebSocket connected' : 'WebSocket disconnected'}
          />
          <span className="text-[10px] text-zinc-500">
            {connected ? 'LIVE' : 'OFFLINE'}
          </span>
        </div>
      </div>

      {/* Summary strip */}
      <SummaryStrip stats={stats} />

      {/* Empty state */}
      {visible.length === 0 && (
        <div className="p-4 text-zinc-500 text-xs border border-zinc-800 rounded bg-zinc-900/50">
          {instrument
            ? `No simulations recorded for ${instrument} yet.`
            : 'No simulations recorded yet.'}
        </div>
      )}

      {/* Cards */}
      {visible.length > 0 && (
        <div className="space-y-2">
          {visible.map(sim => (
            <SimulationCard key={`${sim.reviewType}-${sim.reviewId}-${sim.id}`} sim={sim} />
          ))}
        </div>
      )}
    </div>
  );
}

// ── Summary strip ────────────────────────────────────────────────────────────

function SummaryStrip({ stats }: { stats: SummaryStats }) {
  return (
    <div className="grid grid-cols-2 md:grid-cols-4 lg:grid-cols-8 gap-2 border border-zinc-800 rounded bg-zinc-900/60 p-2">
      <StatCell label="Total" value={String(stats.total)} tone="text-zinc-200" />
      <StatCell label="Win" value={String(stats.win)} tone="text-emerald-400" />
      <StatCell label="Loss" value={String(stats.loss)} tone="text-rose-400" />
      <StatCell label="Missed" value={String(stats.missed)} tone="text-zinc-400" />
      <StatCell label="Cancelled" value={String(stats.cancelled)} tone="text-zinc-500" />
      <StatCell label="Active" value={String(stats.active + stats.pending)} tone="text-amber-400" />
      <StatCell
        label="Win rate"
        value={stats.winRatePct != null ? `${stats.winRatePct.toFixed(0)}%` : '-'}
        tone={winRateTone(stats.winRatePct)}
      />
      <StatCell
        label="Avg MFE"
        value={stats.avgMfe != null ? formatPrice(stats.avgMfe) : '-'}
        tone="text-sky-400"
      />
    </div>
  );
}

function StatCell({ label, value, tone }: { label: string; value: string; tone: string }) {
  return (
    <div className="flex flex-col">
      <span className="text-[9px] uppercase tracking-wider text-zinc-500">{label}</span>
      <span className={`font-mono text-sm font-semibold ${tone}`}>{value}</span>
    </div>
  );
}

function winRateTone(pct: number | null): string {
  if (pct == null) return 'text-zinc-400';
  if (pct >= 60) return 'text-emerald-400';
  if (pct >= 45) return 'text-amber-400';
  return 'text-rose-400';
}

// ── Simulation card ──────────────────────────────────────────────────────────

function SimulationCard({ sim }: { sim: TradeSimulationView }) {
  const hasTrailing = sim.trailingStopResult != null;
  return (
    <div className="border border-zinc-800 rounded bg-zinc-900/60 p-3 space-y-2">
      {/* Top row: status + instrument + action + review type + timestamps */}
      <div className="flex items-center gap-2 flex-wrap">
        <StatusBadge status={sim.simulationStatus} />
        <span className="font-mono text-xs text-zinc-200">{sim.instrument}</span>
        <ActionBadge action={sim.action} />
        <ReviewTypeBadge type={sim.reviewType} />
        <span className="ml-auto text-[10px] font-mono text-zinc-500">
          #{sim.reviewId}
        </span>
      </div>

      {/* Timestamps row */}
      <div className="flex items-center gap-3 flex-wrap text-[10px] font-mono text-zinc-500">
        <span>
          <span className="text-zinc-600">created:</span>{' '}
          <span className="text-zinc-300">{formatRelativeTime(sim.createdAt)}</span>
        </span>
        {sim.activationTime && (
          <span>
            <span className="text-zinc-600">activated:</span>{' '}
            <span className="text-zinc-300">{formatRelativeTime(sim.activationTime)}</span>
          </span>
        )}
        {sim.resolutionTime && (
          <span>
            <span className="text-zinc-600">resolved:</span>{' '}
            <span className="text-zinc-300">{formatRelativeTime(sim.resolutionTime)}</span>
          </span>
        )}
      </div>

      {/* Metrics grid */}
      <div className="border border-zinc-700 rounded p-2 font-mono text-[11px] grid grid-cols-2 sm:grid-cols-4 gap-2">
        <MetricCell
          label="Max DD"
          value={formatPoints(sim.maxDrawdownPoints)}
          tone="text-rose-400"
        />
        <MetricCell
          label="Best MFE"
          value={formatPrice(sim.bestFavorablePrice)}
          tone="text-emerald-400"
        />
        <MetricCell
          label="Sim ID"
          value={`#${sim.id}`}
          tone="text-zinc-400"
        />
        <MetricCell
          label="Action"
          value={sim.action || '-'}
          tone="text-zinc-300"
        />
      </div>

      {/* Trailing block */}
      {hasTrailing && (
        <div className="border border-sky-800 bg-sky-950/30 rounded p-2 text-[11px] font-mono">
          <div className="text-[10px] uppercase tracking-wider text-sky-400 mb-1">
            Trailing stop
          </div>
          <div className="flex items-center gap-3 flex-wrap">
            <span>
              <span className="text-zinc-500">result:</span>{' '}
              <span className={trailingTone(sim.trailingStopResult)}>
                {sim.trailingStopResult ?? '-'}
              </span>
            </span>
            <span>
              <span className="text-zinc-500">exit:</span>{' '}
              <span className="text-zinc-200">
                {formatPrice(sim.trailingExitPrice)}
              </span>
            </span>
          </div>
        </div>
      )}
    </div>
  );
}

function MetricCell({ label, value, tone }: { label: string; value: string; tone: string }) {
  return (
    <div>
      <div className="text-[9px] text-zinc-500 uppercase tracking-wider">{label}</div>
      <div className={tone}>{value}</div>
    </div>
  );
}

// ── Badges ───────────────────────────────────────────────────────────────────

function StatusBadge({ status }: { status: string }) {
  return (
    <span
      className={`px-2 py-0.5 rounded text-[10px] font-semibold uppercase tracking-wider ${statusTone(
        status,
      )}`}
    >
      {status}
    </span>
  );
}

function statusTone(status: string): string {
  switch (status) {
    case 'WIN':
      return 'bg-emerald-600 text-white';
    case 'LOSS':
      return 'bg-rose-600 text-white';
    case 'MISSED':
      return 'bg-zinc-600 text-zinc-100';
    case 'CANCELLED':
      return 'bg-zinc-700 text-zinc-300';
    case 'ACTIVE':
      return 'bg-sky-600 text-white';
    case 'PENDING_ENTRY':
      return 'bg-amber-600 text-white';
    default:
      return 'bg-zinc-600 text-white';
  }
}

function ActionBadge({ action }: { action: string }) {
  const upper = (action ?? '').toUpperCase();
  const isLong = upper === 'LONG' || upper === 'BUY';
  const isShort = upper === 'SHORT' || upper === 'SELL';
  if (!isLong && !isShort) {
    return (
      <span className="font-mono text-[10px] px-2 py-0.5 rounded border border-zinc-600 text-zinc-400">
        {action || '-'}
      </span>
    );
  }
  return (
    <span
      className={`font-mono text-[10px] px-2 py-0.5 rounded border ${
        isLong
          ? 'border-emerald-500 text-emerald-400'
          : 'border-rose-500 text-rose-400'
      }`}
    >
      {upper}
    </span>
  );
}

function ReviewTypeBadge({ type }: { type: 'SIGNAL' | 'AUDIT' }) {
  const tone =
    type === 'SIGNAL'
      ? 'bg-zinc-800 text-zinc-300 border-zinc-600'
      : 'bg-indigo-950 text-indigo-300 border-indigo-700';
  return (
    <span
      className={`font-mono text-[9px] uppercase tracking-wider px-1.5 py-0.5 rounded border ${tone}`}
    >
      {type}
    </span>
  );
}

function trailingTone(result: string | null): string {
  switch (result) {
    case 'TRAILING_WIN':
      return 'text-emerald-400';
    case 'TRAILING_BE':
      return 'text-zinc-300';
    case 'TRAILING_LOSS':
      return 'text-rose-400';
    default:
      return 'text-zinc-400';
  }
}

// ── Formatting helpers ───────────────────────────────────────────────────────

function formatRelativeTime(iso: string | null): string {
  if (!iso) return '-';
  const then = Date.parse(iso);
  if (Number.isNaN(then)) return iso;
  const deltaMs = Date.now() - then;
  const s = Math.round(deltaMs / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.round(h / 24);
  return `${d}d ago`;
}

function formatPrice(v: number | null): string {
  if (v == null || !Number.isFinite(v)) return '-';
  return Math.abs(v) >= 1000 ? v.toFixed(2) : v.toFixed(4);
}

function formatPoints(v: number | null): string {
  if (v == null || !Number.isFinite(v)) return '-';
  return `${v.toFixed(2)} pts`;
}
