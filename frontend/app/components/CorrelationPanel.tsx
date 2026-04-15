'use client';

import { useCallback, useEffect, useState } from 'react';
import { api, CorrelationSignal, CorrelationStatus } from '@/app/lib/api';

function formatTime(timestamp: string): string {
  try {
    return new Date(timestamp).toLocaleTimeString(undefined, {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
  } catch {
    return timestamp;
  }
}

/**
 * Infer the leader's implied side from the payload.
 * ONIMS fires when the leader breaks above resistance (LONG) or below (SHORT).
 * The follower is inversely correlated by definition.
 */
function inferLeaderSide(sig: CorrelationSignal): 'LONG' | 'SHORT' | null {
  if (sig.leaderBreakout == null || sig.leaderResistance == null) return null;
  if (sig.leaderBreakout > sig.leaderResistance) return 'LONG';
  if (sig.leaderBreakout < sig.leaderResistance) return 'SHORT';
  return null;
}
function inverse(side: 'LONG' | 'SHORT' | null): 'LONG' | 'SHORT' | null {
  return side === 'LONG' ? 'SHORT' : side === 'SHORT' ? 'LONG' : null;
}
function sideColor(side: string | null): string {
  return side === 'LONG' ? 'text-emerald-400' : side === 'SHORT' ? 'text-red-400' : 'text-zinc-500';
}

export default function CorrelationPanel() {
  const [status, setStatus] = useState<CorrelationStatus | null>(null);
  const [history, setHistory] = useState<CorrelationSignal[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    try {
      const [s, h] = await Promise.all([
        api.getCorrelationStatus().catch(() => null),
        api.getCorrelationHistory().catch(() => [] as CorrelationSignal[]),
      ]);
      setStatus(s);
      setHistory(h ?? []);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load correlation state');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
    const id = setInterval(load, 30_000); // refresh every 30s
    return () => clearInterval(id);
  }, [load]);

  // Hide the panel entirely when the engine is disabled or returns nothing useful
  if (loading) return null;
  if (!status && history.length === 0 && !error) return null;

  return (
    <section className="bg-zinc-900 rounded-lg border border-zinc-800 p-3">
      <div className="flex items-center justify-between mb-2">
        <h3 className="text-[10px] font-semibold uppercase tracking-widest text-zinc-500">
          ONIMS — Oil/Nasdaq Correlation
        </h3>
        {status && (
          <div className="flex items-center gap-2">
            <span className={`text-[10px] px-1.5 py-0.5 rounded font-medium ${
              status.blackoutActive
                ? 'bg-amber-900/50 text-amber-300'
                : 'bg-emerald-900/50 text-emerald-300'
            }`}>
              {status.blackoutActive ? 'BLACKOUT' : 'ACTIVE'}
            </span>
            <span className="text-[10px] text-zinc-500 font-mono">{status.engineState}</span>
          </div>
        )}
      </div>

      {error && <div className="text-xs text-red-400 py-1">{error}</div>}

      {status && (
        <div className="grid grid-cols-3 gap-2 mb-2 text-[10px]">
          <div className="rounded bg-zinc-950/60 p-1.5">
            <div className="text-zinc-500 uppercase tracking-widest">VIX</div>
            <div className="text-zinc-200 font-mono">
              {status.cachedVixPrice != null ? status.cachedVixPrice.toFixed(2) : '—'}
            </div>
          </div>
          <div className="rounded bg-zinc-950/60 p-1.5">
            <div className="text-zinc-500 uppercase tracking-widest">VIX Thresh.</div>
            <div className="text-zinc-200 font-mono">{status.vixThreshold.toFixed(1)}</div>
          </div>
          <div className="rounded bg-zinc-950/60 p-1.5">
            <div className="text-zinc-500 uppercase tracking-widest">Blackout Dur.</div>
            <div className="text-zinc-200 font-mono">{status.blackoutDurationMins}m</div>
          </div>
        </div>
      )}

      <div>
        <div className="text-[10px] uppercase tracking-widest text-zinc-500 mb-1">
          Recent Signals ({history.length})
        </div>
        {history.length === 0 ? (
          <div className="text-xs text-zinc-600 py-1">No confirmed signals</div>
        ) : (
          <div className="space-y-0.5 max-h-40 overflow-y-auto">
            {history.slice(0, 20).map((sig, i) => {
              const leaderSide = inferLeaderSide(sig);
              const followerSide = inverse(leaderSide);
              return (
                <div
                  key={`${sig.confirmedAt}-${i}`}
                  className="flex items-center gap-2 text-xs font-mono py-0.5 border-b border-zinc-800/40"
                  title={sig.message}
                >
                  <span className="text-zinc-600 text-[10px]">{formatTime(sig.confirmedAt)}</span>
                  <span className="text-zinc-500">{sig.leaderInstrument}</span>
                  <span className={sideColor(leaderSide)}>{leaderSide ?? '—'}</span>
                  <span className="text-zinc-600">/</span>
                  <span className="text-zinc-500">{sig.followerInstrument}</span>
                  <span className={sideColor(followerSide)}>{followerSide ?? '—'}</span>
                  <span className="text-zinc-600 text-[10px] ml-auto">
                    lag {sig.lagSeconds}s
                  </span>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </section>
  );
}
