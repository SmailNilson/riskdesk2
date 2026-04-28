'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { api, ExternalSetupSummary } from '@/app/lib/api';

const POLL_MS = 5_000;

function fmtPrice(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toLocaleString('en-US', { maximumFractionDigits: 4 });
}

function rrRatio(s: ExternalSetupSummary): number | null {
  if (!s.stopLoss || !s.takeProfit1) return null;
  const risk = Math.abs(s.entry - s.stopLoss);
  const reward = Math.abs(s.takeProfit1 - s.entry);
  if (risk <= 0) return null;
  return reward / risk;
}

function secondsUntil(iso: string, now: number): number {
  const target = new Date(iso).getTime();
  return Math.max(0, Math.floor((target - now) / 1000));
}

function fmtCountdown(sec: number): string {
  if (sec <= 0) return '00:00';
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
}

function tone(direction: 'LONG' | 'SHORT'): string {
  return direction === 'LONG' ? 'text-emerald-400' : 'text-rose-400';
}

function confidenceBadge(conf: ExternalSetupSummary['confidence']): string {
  if (conf === 'HIGH') return 'bg-emerald-900/40 text-emerald-300 ring-emerald-700/40';
  if (conf === 'MEDIUM') return 'bg-amber-900/40 text-amber-300 ring-amber-700/40';
  return 'bg-zinc-800 text-zinc-300 ring-zinc-700';
}

export default function ExternalSetupPanel() {
  const [setups, setSetups] = useState<ExternalSetupSummary[]>([]);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [pendingAction, setPendingAction] = useState<number | null>(null);
  const [now, setNow] = useState<number>(Date.now());

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      const list = await api.listExternalSetups('PENDING', 50);
      setSetups(list);
      setError(null);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      setError(msg);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload();
    const id = setInterval(reload, POLL_MS);
    return () => clearInterval(id);
  }, [reload]);

  // Tick the local clock every second so countdowns refresh smoothly.
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);

  const sorted = useMemo(
    () => [...setups].sort((a, b) =>
      new Date(b.submittedAt).getTime() - new Date(a.submittedAt).getTime()),
    [setups]
  );

  const validate = async (s: ExternalSetupSummary) => {
    if (pendingAction !== null) return;
    const confirmed = window.confirm(
      `Valider et armer le setup ?\n\n` +
      `${s.instrument} ${s.direction} entry=${fmtPrice(s.entry)} ` +
      `SL=${fmtPrice(s.stopLoss)} TP1=${fmtPrice(s.takeProfit1)}\n\n` +
      `L'exécution sera dispatchée vers IBKR avec la quantité par défaut.`
    );
    if (!confirmed) return;

    setPendingAction(s.id);
    try {
      await api.validateExternalSetup(s.id, { validatedBy: 'ui' });
      await reload();
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      window.alert(`Validation échouée : ${msg}`);
    } finally {
      setPendingAction(null);
    }
  };

  const reject = async (s: ExternalSetupSummary) => {
    if (pendingAction !== null) return;
    const reason = window.prompt(`Rejeter le setup ${s.instrument} ${s.direction} — raison ?`);
    if (reason === null) return;

    setPendingAction(s.id);
    try {
      await api.rejectExternalSetup(s.id, { reason, rejectedBy: 'ui' });
      await reload();
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      window.alert(`Rejet échoué : ${msg}`);
    } finally {
      setPendingAction(null);
    }
  };

  return (
    <div className="rounded-lg border border-zinc-800 bg-zinc-950/50 p-4">
      <div className="mb-3 flex items-center justify-between">
        <div>
          <h3 className="text-sm font-semibold text-zinc-100">External Setups</h3>
          <p className="text-xs text-zinc-500">
            Setups proposés par le détecteur Claude — en attente de validation manuelle.
          </p>
        </div>
        <button
          type="button"
          onClick={reload}
          disabled={loading}
          className="rounded border border-zinc-700 px-2 py-1 text-xs text-zinc-300 hover:bg-zinc-800 disabled:opacity-50"
        >
          {loading ? '…' : 'Refresh'}
        </button>
      </div>

      {error && (
        <div className="mb-3 rounded border border-rose-900 bg-rose-950/40 px-3 py-2 text-xs text-rose-300">
          {error}
        </div>
      )}

      {sorted.length === 0 && !error && (
        <div className="rounded border border-dashed border-zinc-800 px-3 py-6 text-center text-xs text-zinc-500">
          Aucun setup en attente.
        </div>
      )}

      <div className="space-y-2">
        {sorted.map((s) => {
          const remaining = secondsUntil(s.expiresAt, now);
          const expired = remaining <= 0;
          const rr = rrRatio(s);
          const isPending = pendingAction === s.id;

          return (
            <div
              key={s.id}
              className={`rounded border px-3 py-2 ${expired ? 'border-zinc-800 bg-zinc-900/40 opacity-60' : 'border-zinc-700 bg-zinc-900/60'}`}
            >
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <span className="font-mono text-xs text-zinc-400">#{s.id}</span>
                  <span className="text-sm font-medium text-zinc-100">{s.instrument}</span>
                  <span className={`text-sm font-semibold ${tone(s.direction)}`}>{s.direction}</span>
                  <span className={`rounded px-1.5 py-0.5 text-[10px] font-medium uppercase ring-1 ${confidenceBadge(s.confidence)}`}>
                    {s.confidence}
                  </span>
                </div>
                <span className={`font-mono text-xs ${expired ? 'text-rose-400' : 'text-zinc-300'}`}>
                  TTL {fmtCountdown(remaining)}
                </span>
              </div>

              <div className="mt-1 grid grid-cols-3 gap-x-3 text-[11px] text-zinc-300 font-mono">
                <span>Entry <strong>{fmtPrice(s.entry)}</strong></span>
                <span>SL <span className="text-rose-300">{fmtPrice(s.stopLoss)}</span></span>
                <span>TP1 <span className="text-emerald-300">{fmtPrice(s.takeProfit1)}</span></span>
                {s.takeProfit2 != null && (
                  <span className="col-span-3">TP2 <span className="text-emerald-200">{fmtPrice(s.takeProfit2)}</span></span>
                )}
                {rr !== null && (
                  <span className="col-span-3 text-zinc-400">R:R <strong>{rr.toFixed(2)}</strong></span>
                )}
              </div>

              {s.triggerLabel && (
                <div className="mt-1 text-[11px] text-zinc-500" title={s.triggerLabel}>
                  {s.triggerLabel}
                </div>
              )}

              <div className="mt-2 flex justify-end gap-2">
                <button
                  type="button"
                  onClick={() => reject(s)}
                  disabled={isPending || expired}
                  className="rounded border border-rose-900 bg-rose-950/40 px-2 py-1 text-xs text-rose-200 hover:bg-rose-900/40 disabled:opacity-50"
                >
                  ❌ Rejeter
                </button>
                <button
                  type="button"
                  onClick={() => validate(s)}
                  disabled={isPending || expired}
                  className="rounded border border-emerald-700 bg-emerald-950/40 px-2 py-1 text-xs text-emerald-200 hover:bg-emerald-900/40 disabled:opacity-50"
                >
                  {isPending ? '…' : '✅ Valider'}
                </button>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
