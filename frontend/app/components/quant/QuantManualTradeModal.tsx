'use client';

import { useEffect, useMemo, useState } from 'react';
import { api } from '@/app/lib/api';
import type { ManualTradeRequest, QuantInstrument, QuantSnapshotView } from './types';

interface QuantManualTradeModalProps {
  open: boolean;
  instrument: QuantInstrument;
  direction: 'LONG' | 'SHORT';
  snapshot: QuantSnapshotView | null;
  onClose: () => void;
  onPlaced: (executionId: number) => void;
}

/**
 * Manual trade ticket modal — pre-fills entry/SL/TP from the snapshot's
 * suggested plan (per direction) and lets the operator edit any value
 * before submitting via POST /api/quant/manual-trade/{instrument}.
 *
 * Independent of auto-arm: works regardless of score or structural
 * blocks. Shows an inline warning when the operator picks a direction
 * that has structural blocks active (without preventing submission —
 * the operator owns the decision).
 */
export default function QuantManualTradeModal(props: QuantManualTradeModalProps) {
  const { open, instrument, direction, snapshot, onClose, onPlaced } = props;
  const livePrice = snapshot?.price ?? null;

  const initialEntry = direction === 'LONG' ? snapshot?.longEntry ?? null : snapshot?.entry ?? null;
  const initialSl = direction === 'LONG' ? snapshot?.longSl ?? null : snapshot?.sl ?? null;
  const initialTp1 = direction === 'LONG' ? snapshot?.longTp1 ?? null : snapshot?.tp1 ?? null;
  const initialTp2 = direction === 'LONG' ? snapshot?.longTp2 ?? null : snapshot?.tp2 ?? null;

  const [entryType, setEntryType] = useState<'MARKET' | 'LIMIT'>('LIMIT');
  const [entry, setEntry] = useState<string>('');
  const [stopLoss, setStopLoss] = useState<string>('');
  const [tp1, setTp1] = useState<string>('');
  const [tp2, setTp2] = useState<string>('');
  const [quantity, setQuantity] = useState<number>(1);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Reset form when modal opens or direction changes — pre-fill from snapshot.
  useEffect(() => {
    if (!open) return;
    setEntryType('LIMIT');
    setEntry(initialEntry !== null ? initialEntry.toFixed(2) : (livePrice ?? 0).toFixed(2));
    setStopLoss(initialSl !== null ? initialSl.toFixed(2) : '');
    setTp1(initialTp1 !== null ? initialTp1.toFixed(2) : '');
    setTp2(initialTp2 !== null ? initialTp2.toFixed(2) : '');
    setQuantity(1);
    setError(null);
  }, [open, direction, initialEntry, initialSl, initialTp1, initialTp2, livePrice]);

  const blocked = direction === 'LONG' ? snapshot?.longBlocked : snapshot?.shortBlocked;
  const blocks = direction === 'LONG' ? snapshot?.longStructuralBlocks ?? [] : snapshot?.structuralBlocks ?? [];

  const numericEntry = Number.parseFloat(entry);
  const numericSl = Number.parseFloat(stopLoss);
  const numericTp1 = Number.parseFloat(tp1);
  const numericTp2 = Number.parseFloat(tp2);

  const { riskPoints, rrTp1, rrTp2 } = useMemo(() => {
    if (!Number.isFinite(numericEntry) || !Number.isFinite(numericSl)) {
      return { riskPoints: null, rrTp1: null, rrTp2: null };
    }
    const risk = Math.abs(numericEntry - numericSl);
    if (risk === 0) return { riskPoints: 0, rrTp1: null, rrTp2: null };
    const reward1 = Number.isFinite(numericTp1) ? Math.abs(numericTp1 - numericEntry) : null;
    const reward2 = Number.isFinite(numericTp2) ? Math.abs(numericTp2 - numericEntry) : null;
    return {
      riskPoints: risk,
      rrTp1: reward1 !== null ? reward1 / risk : null,
      rrTp2: reward2 !== null ? reward2 / risk : null,
    };
  }, [numericEntry, numericSl, numericTp1, numericTp2]);

  if (!open) return null;

  const slDelta = Number.isFinite(numericSl) && Number.isFinite(numericEntry) ? (numericSl - numericEntry) : null;
  const tp1Delta = Number.isFinite(numericTp1) && Number.isFinite(numericEntry) ? (numericTp1 - numericEntry) : null;
  const tp2Delta = Number.isFinite(numericTp2) && Number.isFinite(numericEntry) ? (numericTp2 - numericEntry) : null;

  const submit = async () => {
    setError(null);
    if (!Number.isFinite(numericSl)) {
      setError('Stop loss is required');
      return;
    }
    if (!Number.isFinite(numericTp1)) {
      setError('Take profit 1 is required');
      return;
    }
    if (entryType === 'LIMIT' && !Number.isFinite(numericEntry)) {
      setError('Entry price is required for LIMIT orders');
      return;
    }
    const payload: ManualTradeRequest = {
      direction,
      entryType,
      entryPrice: entryType === 'LIMIT' ? numericEntry : null,
      stopLoss: numericSl,
      takeProfit1: numericTp1,
      takeProfit2: Number.isFinite(numericTp2) ? numericTp2 : null,
      quantity: Math.max(1, Math.floor(quantity)),
    };
    setBusy(true);
    try {
      const result = await api.submitManualTrade(instrument, payload);
      onPlaced(result.id);
      onClose();
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Submission failed';
      setError(message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/70"
      onClick={onClose}
    >
      <div
        className="bg-slate-900 border border-slate-700 rounded-lg p-5 w-full max-w-md text-slate-100 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-base font-semibold">
            Trade Ticket — {instrument}{' '}
            <span className={direction === 'LONG' ? 'text-emerald-400' : 'text-red-400'}>
              {direction}
            </span>
          </h3>
          <button
            type="button"
            onClick={onClose}
            className="text-slate-400 hover:text-slate-200 text-lg leading-none"
            aria-label="Close"
          >
            ✕
          </button>
        </div>

        <div className="text-xs text-slate-400 mb-3">
          Live price: <span className="font-mono text-slate-200">
            {livePrice !== null ? livePrice.toFixed(2) : '—'}
          </span>
        </div>

        {blocked && (
          <div className="mb-3 rounded border border-amber-700 bg-amber-950/40 p-2 text-xs text-amber-200">
            ⚠️ Structural blocks active for {direction}
            {blocks.length > 0 && ` (${blocks.map((b) => b.code).join(', ')})`}.
            Your trade will still go through — operator override.
          </div>
        )}

        <div className="space-y-3 text-sm">
          <div>
            <label className="block text-xs text-slate-400 mb-1">Order type</label>
            <div className="flex gap-3">
              <label className="flex items-center gap-1 cursor-pointer">
                <input
                  type="radio"
                  name="entry-type"
                  value="MARKET"
                  checked={entryType === 'MARKET'}
                  onChange={() => setEntryType('MARKET')}
                  disabled={busy}
                />
                <span>Market</span>
              </label>
              <label className="flex items-center gap-1 cursor-pointer">
                <input
                  type="radio"
                  name="entry-type"
                  value="LIMIT"
                  checked={entryType === 'LIMIT'}
                  onChange={() => setEntryType('LIMIT')}
                  disabled={busy}
                />
                <span>Limit</span>
              </label>
            </div>
          </div>

          <div>
            <label className="block text-xs text-slate-400 mb-1">Entry</label>
            <input
              type="number"
              step="0.01"
              value={entry}
              onChange={(e) => setEntry(e.target.value)}
              disabled={busy || entryType === 'MARKET'}
              className="w-full bg-slate-800 border border-slate-700 rounded px-2 py-1 font-mono text-sm disabled:opacity-50"
            />
            {entryType === 'MARKET' && (
              <div className="text-xs text-slate-500 mt-1">
                MARKET will use the live price ({livePrice !== null ? livePrice.toFixed(2) : '—'}).
              </div>
            )}
          </div>

          <div>
            <label className="block text-xs text-slate-400 mb-1">Quantity</label>
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => setQuantity((q) => Math.max(1, q - 1))}
                disabled={busy}
                className="px-2 py-1 bg-slate-800 border border-slate-700 rounded hover:bg-slate-700 disabled:opacity-50"
              >
                −
              </button>
              <input
                type="number"
                min={1}
                value={quantity}
                onChange={(e) => setQuantity(Math.max(1, Number.parseInt(e.target.value, 10) || 1))}
                disabled={busy}
                className="w-16 bg-slate-800 border border-slate-700 rounded px-2 py-1 font-mono text-sm text-center"
              />
              <button
                type="button"
                onClick={() => setQuantity((q) => q + 1)}
                disabled={busy}
                className="px-2 py-1 bg-slate-800 border border-slate-700 rounded hover:bg-slate-700 disabled:opacity-50"
              >
                +
              </button>
            </div>
          </div>

          <div className="grid grid-cols-[1fr_auto] gap-x-2 gap-y-2 items-center">
            <div>
              <label className="block text-xs text-slate-400 mb-1">Stop Loss</label>
              <input
                type="number"
                step="0.01"
                value={stopLoss}
                onChange={(e) => setStopLoss(e.target.value)}
                disabled={busy}
                className="w-full bg-slate-800 border border-slate-700 rounded px-2 py-1 font-mono text-sm"
              />
            </div>
            <div className="text-xs text-slate-500 font-mono pt-5">
              {slDelta !== null ? (slDelta >= 0 ? `+${slDelta.toFixed(2)}` : slDelta.toFixed(2)) : '—'}
            </div>

            <div>
              <label className="block text-xs text-slate-400 mb-1">Take Profit 1</label>
              <input
                type="number"
                step="0.01"
                value={tp1}
                onChange={(e) => setTp1(e.target.value)}
                disabled={busy}
                className="w-full bg-slate-800 border border-slate-700 rounded px-2 py-1 font-mono text-sm"
              />
            </div>
            <div className="text-xs text-slate-500 font-mono pt-5">
              {tp1Delta !== null ? (tp1Delta >= 0 ? `+${tp1Delta.toFixed(2)}` : tp1Delta.toFixed(2)) : '—'}
            </div>

            <div>
              <label className="block text-xs text-slate-400 mb-1">Take Profit 2 (optional)</label>
              <input
                type="number"
                step="0.01"
                value={tp2}
                onChange={(e) => setTp2(e.target.value)}
                disabled={busy}
                className="w-full bg-slate-800 border border-slate-700 rounded px-2 py-1 font-mono text-sm"
              />
            </div>
            <div className="text-xs text-slate-500 font-mono pt-5">
              {tp2Delta !== null ? (tp2Delta >= 0 ? `+${tp2Delta.toFixed(2)}` : tp2Delta.toFixed(2)) : '—'}
            </div>
          </div>

          <div className="rounded bg-slate-950/60 border border-slate-700 p-2 text-xs space-y-1">
            <div>
              Risk:{' '}
              <span className="font-mono text-slate-200">
                {riskPoints !== null ? `${riskPoints.toFixed(2)} pts` : '—'}
              </span>
            </div>
            <div className="flex gap-3">
              <span>
                R/R 1:{' '}
                <span className="font-mono text-slate-200">{rrTp1 !== null ? rrTp1.toFixed(2) : '—'}</span>
              </span>
              <span>
                R/R 2:{' '}
                <span className="font-mono text-slate-200">{rrTp2 !== null ? rrTp2.toFixed(2) : '—'}</span>
              </span>
            </div>
          </div>

          {error && (
            <div className="rounded border border-red-700 bg-red-950/40 p-2 text-xs text-red-200">
              {error}
            </div>
          )}

          <div className="flex gap-2 pt-2">
            <button
              type="button"
              onClick={submit}
              disabled={busy}
              className={`flex-1 px-3 py-2 text-sm rounded font-semibold text-white ${
                direction === 'LONG'
                  ? 'bg-emerald-700 hover:bg-emerald-600'
                  : 'bg-red-700 hover:bg-red-600'
              } disabled:opacity-50`}
            >
              {busy ? 'Submitting…' : 'Submit Order'}
            </button>
            <button
              type="button"
              onClick={onClose}
              disabled={busy}
              className="px-3 py-2 text-sm rounded bg-slate-800 hover:bg-slate-700 border border-slate-600 disabled:opacity-50"
            >
              Cancel
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
