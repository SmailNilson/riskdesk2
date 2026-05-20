'use client';

import { useMemo, useState } from 'react';
import { useActivePositions } from '@/app/hooks/useActivePositions';
import type { ActivePositionView } from '@/app/lib/api';

function toNumber(v: number | string | null | undefined): number | null {
  if (v === null || v === undefined) return null;
  const n = typeof v === 'number' ? v : Number(v);
  return Number.isFinite(n) ? n : null;
}

function formatPrice(v: number | string | null | undefined, decimals = 2): string {
  const n = toNumber(v);
  return n === null ? '—' : n.toFixed(decimals);
}

function formatPnL(v: number | string | null | undefined, decimals = 2): string {
  const n = toNumber(v);
  if (n === null) return '—';
  const sign = n >= 0 ? '+' : '';
  return `${sign}${n.toFixed(decimals)}`;
}

const ACTIVE_STATUS_LABEL: Record<string, string> = {
  PENDING_ENTRY_SUBMISSION: 'PENDING',
  ENTRY_SUBMITTED: 'SUBMITTED',
  ENTRY_PARTIALLY_FILLED: 'PARTIAL',
  ACTIVE: 'ACTIVE',
  VIRTUAL_EXIT_TRIGGERED: 'EXIT TRIG',
  EXIT_SUBMITTED: 'EXITING',
  CLOSED: 'CLOSED',
  CANCELLED: 'CANCELLED',
  REJECTED: 'REJECTED',
  FAILED: 'FAILED',
};

const PENDING_STATUSES = new Set(['PENDING_ENTRY_SUBMISSION', 'ENTRY_SUBMITTED', 'ENTRY_PARTIALLY_FILLED']);
const TERMINAL_STATUSES = new Set(['CLOSED', 'CANCELLED', 'REJECTED', 'FAILED']);

interface ConfirmCloseState {
  position: ActivePositionView;
}

/**
 * Live view of every currently-open trade execution. Subscribes to
 * {@code /topic/positions} via {@link useActivePositions} and renders one
 * card per row with PnL color coding and a Close button.
 *
 * The panel is purely additive — it does not touch existing review/auto-arm
 * flows. PnL values are rendered straight from the server snapshot; the
 * backend recomputes them on every 5s heartbeat.
 */
export default function ActivePositionsPanel(): JSX.Element {
  const { positions, loading, error, close, connected } = useActivePositions();
  const [confirmClose, setConfirmClose] = useState<ConfirmCloseState | null>(null);
  const [closingId, setClosingId] = useState<number | null>(null);

  // Hide already-terminal rows that may briefly appear in the WS payload
  // before the next snapshot evicts them.
  const visible = useMemo(
    () => positions.filter((p) => !TERMINAL_STATUSES.has(p.status)),
    [positions]
  );

  const totalPnl = useMemo(
    () => visible.reduce((sum, p) => sum + (toNumber(p.pnlDollars) ?? 0), 0),
    [visible]
  );

  const onConfirmClose = async () => {
    if (!confirmClose) return;
    const id = confirmClose.position.executionId;
    setClosingId(id);
    setConfirmClose(null);
    try {
      await close(id);
    } finally {
      setClosingId(null);
    }
  };

  return (
    <section className="rounded-lg border border-zinc-700 bg-zinc-900 p-4 text-zinc-100">
      <header className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <h2 className="text-lg font-semibold">Active Positions</h2>
          <span className="text-xs text-zinc-400">({visible.length})</span>
          <span
            className={`text-xs px-2 py-0.5 rounded ${connected ? 'bg-emerald-700' : 'bg-zinc-700'}`}
            title={connected ? 'WebSocket connected' : 'WebSocket disconnected'}
          >
            {connected ? 'live' : 'offline'}
          </span>
        </div>
        {visible.length > 0 && (
          <span
            className={`text-sm font-mono ${
              totalPnl > 0 ? 'text-emerald-400' : totalPnl < 0 ? 'text-rose-400' : 'text-zinc-400'
            }`}
            title="Sum of dollar PnL across all open positions"
          >
            Total {formatPnL(totalPnl)} $
          </span>
        )}
      </header>

      {loading && positions.length === 0 ? (
        <p className="text-sm text-zinc-500">Loading active positions…</p>
      ) : error && positions.length === 0 ? (
        <p className="text-sm text-rose-400">Error loading positions: {error}</p>
      ) : visible.length === 0 ? (
        <p className="text-sm text-zinc-500">No active positions.</p>
      ) : (
        <ul className="space-y-2">
          {visible.map((p) => (
            <PositionCard
              key={p.executionId}
              position={p}
              busy={closingId === p.executionId}
              onClose={() => setConfirmClose({ position: p })}
            />
          ))}
        </ul>
      )}

      {confirmClose && (
        <ConfirmCloseModal
          position={confirmClose.position}
          onConfirm={onConfirmClose}
          onCancel={() => setConfirmClose(null)}
        />
      )}
    </section>
  );
}

interface PositionCardProps {
  position: ActivePositionView;
  busy: boolean;
  onClose: () => void;
}

function PositionCard({ position, busy, onClose }: PositionCardProps): JSX.Element {
  const dollars = toNumber(position.pnlDollars);
  const points = toNumber(position.pnlPoints);
  const pnlColor =
    dollars === null ? 'text-zinc-400' : dollars > 0 ? 'text-emerald-400' : dollars < 0 ? 'text-rose-400' : 'text-zinc-300';
  const isPending = PENDING_STATUSES.has(position.status);
  const statusColor = isPending ? 'text-amber-300' : 'text-zinc-200';
  const directionColor = position.direction === 'LONG' ? 'text-emerald-400' : 'text-rose-400';
  const decimals = position.instrument === 'E6' ? 5 : 2;

  return (
    <li className="rounded border border-zinc-800 bg-zinc-950/60 p-3">
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <div className="flex items-center gap-2">
          <span className="font-bold text-zinc-100">{position.instrument}</span>
          <span className={`font-bold text-xs ${directionColor}`}>{position.direction}</span>
          <span className="text-xs text-zinc-400">{position.quantity ?? 1}ct</span>
          <span className={`text-xs ${statusColor}`}>
            {isPending && (
              <span className="inline-block w-2 h-2 rounded-full bg-amber-400 mr-1 animate-pulse" />
            )}
            {ACTIVE_STATUS_LABEL[position.status] ?? position.status}
          </span>
        </div>
        <div className={`text-sm font-mono ${pnlColor}`}>
          {points !== null ? `${formatPnL(points, 2)} pts` : '—'}{' '}
          {dollars !== null ? `(${formatPnL(dollars)} $)` : ''}
        </div>
      </div>

      <div className="mt-2 grid grid-cols-4 gap-2 text-xs font-mono text-zinc-300">
        <div>
          <span className="text-zinc-500">Entry</span>
          <br />
          {formatPrice(position.entryPrice, decimals)}
        </div>
        <div>
          <span className="text-zinc-500">Curr</span>
          <br />
          {formatPrice(position.currentPrice, decimals)}
        </div>
        <div>
          <span className="text-zinc-500">SL</span>
          <br />
          {formatPrice(position.stopLoss, decimals)}
        </div>
        <div>
          <span className="text-zinc-500">TP</span>
          <br />
          {formatPrice(position.takeProfit1, decimals)}
        </div>
      </div>

      {position.statusReason && (
        <div className="mt-1 text-[10px] text-zinc-500 truncate" title={position.statusReason}>
          {position.statusReason}
        </div>
      )}

      <div className="mt-2 flex justify-end">
        <button
          type="button"
          onClick={onClose}
          disabled={busy || !position.closable}
          className="px-3 py-1 text-xs rounded bg-rose-700 hover:bg-rose-600 disabled:opacity-40 disabled:cursor-not-allowed text-white font-semibold"
          title={
            position.closable
              ? 'Close at market (with confirmation)'
              : 'Position is in a terminal state — nothing to close'
          }
        >
          {busy ? 'Closing…' : 'Close'}
        </button>
      </div>
    </li>
  );
}

interface ConfirmCloseModalProps {
  position: ActivePositionView;
  onConfirm: () => void;
  onCancel: () => void;
}

function ConfirmCloseModal({ position, onConfirm, onCancel }: ConfirmCloseModalProps): JSX.Element {
  const decimals = position.instrument === 'E6' ? 5 : 2;
  return (
    <div
      role="dialog"
      aria-modal="true"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/70"
      onClick={onCancel}
    >
      <div
        className="rounded-lg border border-zinc-700 bg-zinc-900 p-5 max-w-sm w-full mx-4"
        onClick={(e) => e.stopPropagation()}
      >
        <h3 className="text-lg font-semibold text-zinc-100 mb-2">
          Close {position.instrument} {position.direction}?
        </h3>
        <p className="text-sm text-zinc-300 mb-1">
          {position.quantity ?? 1} contract{(position.quantity ?? 1) === 1 ? '' : 's'} at market
        </p>
        <p className="text-xs text-zinc-400 mb-4">
          Estimated exit:{' '}
          <span className="font-mono text-zinc-200">{formatPrice(position.currentPrice, decimals)}</span>
        </p>
        <div className="flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            className="px-3 py-1 text-xs rounded border border-zinc-700 text-zinc-300 hover:bg-zinc-800"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={onConfirm}
            className="px-3 py-1 text-xs rounded bg-rose-700 hover:bg-rose-600 text-white font-semibold"
          >
            Confirm Close
          </button>
        </div>
      </div>
    </div>
  );
}
