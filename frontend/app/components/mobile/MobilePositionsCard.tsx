'use client';

import { useState } from 'react';
import { useActivePositions } from '@/app/hooks/useActivePositions';
import type { ActivePositionView } from '@/app/lib/api';

const WORKING = new Set(['PENDING_ENTRY_SUBMISSION', 'ENTRY_SUBMITTED', 'ENTRY_PARTIALLY_FILLED']);
const OPEN = new Set(['ACTIVE', 'VIRTUAL_EXIT_TRIGGERED']);
const CLOSING = new Set(['EXIT_SUBMITTED']);

function num(v: number | string | null | undefined): number | null {
  const n = typeof v === 'string' ? parseFloat(v) : v;
  return n == null || Number.isNaN(n) ? null : n;
}

function fmtUsd(v: number | null) {
  if (v == null) return '—';
  return `${v >= 0 ? '+' : '−'}$${Math.abs(v).toFixed(2)}`;
}

function SideBadge({ direction }: { direction: string }) {
  const long = direction === 'LONG';
  return (
    <span className={`text-[11px] font-medium rounded-md px-2 py-0.5 border ${
      long
        ? 'text-emerald-300 bg-emerald-950 border-emerald-800'
        : 'text-rose-300 bg-rose-950 border-rose-800'
    }`}>
      {long ? 'Long' : 'Short'} {''}
    </span>
  );
}

function StepDot({ on }: { on: boolean }) {
  return <span className={`w-2 h-2 rounded-full flex-shrink-0 ${on ? 'bg-emerald-400' : 'bg-zinc-700'}`} />;
}

/**
 * Mobile orders & positions manager (Portf tab). Live state comes from
 * useActivePositions (/topic/positions push + 5s heartbeat). Cancelling a
 * working entry is one tap (safe direction); closing an open position takes
 * an inline confirmation — the close goes out as a marketable limit.
 */
export default function MobilePositionsCard({ onNewOrder }: { onNewOrder: () => void }) {
  const { positions, loading, error, close } = useActivePositions();
  const [confirmId, setConfirmId] = useState<number | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  const working = positions.filter(p => WORKING.has(p.status));
  const opened = positions.filter(p => OPEN.has(p.status));
  const closing = positions.filter(p => CLOSING.has(p.status));

  const doClose = async (p: ActivePositionView) => {
    setBusyId(p.executionId);
    await close(p.executionId);
    setBusyId(null);
    setConfirmId(null);
  };

  return (
    <div className="flex flex-col gap-3">
      {error && (
        <p className="text-[11px] text-red-400 bg-red-950/50 border border-red-900 rounded-lg px-3 py-2">{error}</p>
      )}

      {working.length > 0 && (
        <div className="rounded-lg border border-zinc-800 bg-zinc-900">
          <div className="flex items-center justify-between px-3 py-2.5 border-b border-zinc-800">
            <span className="text-[13px] font-semibold text-zinc-200">Ordres en cours</span>
            <span className="text-[11px] text-zinc-500">{working.length}</span>
          </div>
          {working.map(p => {
            const presented = p.status !== 'PENDING_ENTRY_SUBMISSION';
            return (
              <div key={p.executionId} className="px-3 py-2.5 border-b border-zinc-800/60 last:border-b-0">
                <div className="flex items-center gap-2">
                  <SideBadge direction={p.direction} />
                  <span className="font-mono tabular-nums text-[13px] text-white">
                    {p.instrument} @ {num(p.entryPrice)?.toFixed(p.instrument === 'E6' ? 5 : 2) ?? '—'} × {p.quantity ?? 1}
                  </span>
                  <span className="text-[11px] text-amber-400 ml-auto">en attente</span>
                </div>
                <div className="flex items-center gap-1.5 mt-2.5">
                  <StepDot on />
                  <span className="text-[11px] text-zinc-500">Soumis</span>
                  <span className={`flex-1 h-px ${presented ? 'bg-emerald-400' : 'bg-zinc-800'}`} />
                  <StepDot on={presented} />
                  <span className="text-[11px] text-zinc-500">Présenté</span>
                  <span className="flex-1 h-px bg-zinc-800" />
                  <StepDot on={false} />
                  <span className="text-[11px] text-zinc-500">Exécuté</span>
                  {p.closable && (
                    <button
                      onClick={() => doClose(p)}
                      disabled={busyId === p.executionId}
                      className="ml-2.5 min-h-[36px] px-3 rounded-lg border border-rose-800 text-xs font-medium text-rose-300 disabled:opacity-50"
                    >
                      {busyId === p.executionId ? '…' : 'Annuler'}
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}

      <div className="rounded-lg border border-zinc-800 bg-zinc-900">
        <div className="flex items-center justify-between px-3 py-2.5 border-b border-zinc-800">
          <span className="text-[13px] font-semibold text-zinc-200">Positions</span>
          <span className="text-[11px] text-zinc-500">
            {loading ? '…' : `${opened.length} ouverte${opened.length > 1 ? 's' : ''}`}
          </span>
        </div>

        {!loading && opened.length === 0 && closing.length === 0 && (
          <p className="px-3 py-4 text-xs text-zinc-600">Aucune position ouverte.</p>
        )}

        {opened.map(p => {
          const pnl = num(p.pnlDollars);
          const dec = p.instrument === 'E6' ? 5 : 2;
          return (
            <div key={p.executionId} className="px-3 py-2.5 border-b border-zinc-800/60 last:border-b-0">
              <div className="flex items-center gap-2">
                <span className="text-[13px] font-semibold text-white">{p.instrument}</span>
                <SideBadge direction={p.direction} />
                <span className="font-mono tabular-nums text-[11px] text-zinc-500">
                  entrée {num(p.entryPrice)?.toFixed(dec) ?? '—'} × {p.quantity ?? 1}
                </span>
                <span className={`font-mono tabular-nums text-sm font-semibold ml-auto ${
                  pnl == null ? 'text-zinc-400' : pnl >= 0 ? 'text-emerald-400' : 'text-red-400'
                }`}>{fmtUsd(pnl)}</span>
              </div>
              <div className="flex items-center gap-3 mt-2">
                <span className="text-[11px] text-zinc-500">
                  SL <span className="font-mono tabular-nums text-red-400">{num(p.stopLoss)?.toFixed(dec) ?? '—'}</span>
                </span>
                <span className="text-[11px] text-zinc-500">
                  TP <span className="font-mono tabular-nums text-emerald-400">{num(p.takeProfit1)?.toFixed(dec) ?? '—'}</span>
                </span>
                {p.status === 'VIRTUAL_EXIT_TRIGGERED' && (
                  <span className="text-[11px] text-amber-400">sortie déclenchée</span>
                )}
                {p.closable && confirmId !== p.executionId && (
                  <button
                    onClick={() => setConfirmId(p.executionId)}
                    className="ml-auto min-h-[36px] px-3 rounded-lg border border-rose-800 bg-rose-950 text-xs font-medium text-rose-300"
                  >Clôturer</button>
                )}
              </div>
              {confirmId === p.executionId && (
                <div className="flex items-center gap-2 mt-2.5 bg-zinc-950 rounded-lg px-3 py-2">
                  <span className="text-[11px] text-zinc-400 flex-1">
                    Clôture en limite marketable — confirmer ?
                  </span>
                  <button
                    onClick={() => doClose(p)}
                    disabled={busyId === p.executionId}
                    className="min-h-[36px] px-3 rounded-lg bg-rose-500 text-xs font-semibold text-rose-950 disabled:opacity-50"
                  >{busyId === p.executionId ? '…' : 'Confirmer'}</button>
                  <button
                    onClick={() => setConfirmId(null)}
                    className="min-h-[36px] px-3 rounded-lg border border-zinc-700 text-xs text-zinc-300"
                  >Garder</button>
                </div>
              )}
            </div>
          );
        })}

        {closing.map(p => (
          <div key={p.executionId} className="flex items-center gap-2 px-3 py-2.5 border-b border-zinc-800/60 last:border-b-0">
            <span className="text-[13px] font-semibold text-white">{p.instrument}</span>
            <SideBadge direction={p.direction} />
            <span className="text-[11px] text-amber-400 ml-auto">clôture en cours…</span>
          </div>
        ))}
      </div>

      <button
        onClick={onNewOrder}
        className="flex items-center justify-center gap-2 min-h-[48px] rounded-lg border border-dashed border-zinc-700 text-[13px] font-medium text-emerald-400 active:scale-[0.98] transition-transform"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><path d="M5 12h14" /><path d="M12 5v14" /></svg>
        Nouvel ordre
      </button>
    </div>
  );
}
