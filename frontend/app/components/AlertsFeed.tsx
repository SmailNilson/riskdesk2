'use client';

import { useState } from 'react';
import { AlertMessage } from '@/app/hooks/useWebSocket';
import { api } from '@/app/lib/api';

interface Props { alerts: AlertMessage[] }

const SEV_CHIP = {
  INFO:    'border-blue-700/60 bg-blue-950/60 text-blue-300',
  WARNING: 'border-amber-600/60 bg-amber-950/60 text-amber-300',
  DANGER:  'border-red-600/70 bg-red-950/70 text-red-300',
};

const SEV_DOT = {
  INFO:    'bg-blue-400',
  WARNING: 'bg-amber-400',
  DANGER:  'bg-red-400 animate-pulse',
};

const SEV_LABEL = {
  INFO: 'text-blue-500',
  WARNING: 'text-amber-500',
  DANGER: 'text-red-500',
};

const SNOOZE_SECONDS = 30 * 60; // 30 minutes

export default function AlertsFeed({ alerts }: Props) {
  // Optimistic client-side filter: keys we hid locally after snooze/clear.
  // WebSocket ingestion keeps feeding the parent state — we only hide from this view.
  const [hiddenKeys, setHiddenKeys] = useState<Set<string>>(new Set());
  const [clearedAt, setClearedAt] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const visible = alerts.filter((a) => {
    if (clearedAt != null) {
      const t = Date.parse(a.timestamp);
      if (!Number.isNaN(t) && t <= clearedAt) return false;
    }
    if (a.key && hiddenKeys.has(a.key)) return false;
    return true;
  });

  async function handleSnooze(a: AlertMessage) {
    if (!a.key) {
      setError('Alert has no key — cannot snooze');
      window.setTimeout(() => setError(null), 3000);
      return;
    }
    const key = a.key;
    // Optimistic hide
    setHiddenKeys((prev) => {
      const next = new Set(prev);
      next.add(key);
      return next;
    });
    try {
      await api.snoozeAlert({ key, durationSeconds: SNOOZE_SECONDS });
    } catch (err) {
      // Revert
      setHiddenKeys((prev) => {
        const next = new Set(prev);
        next.delete(key);
        return next;
      });
      setError(err instanceof Error ? err.message : 'Snooze failed');
      window.setTimeout(() => setError(null), 4000);
    }
  }

  async function handleClearAll() {
    const previousCutoff = clearedAt;
    const now = Date.now();
    // Optimistic clear
    setClearedAt(now);
    try {
      await api.clearRecentAlerts();
    } catch (err) {
      // Revert
      setClearedAt(previousCutoff);
      setError(err instanceof Error ? err.message : 'Clear failed');
      window.setTimeout(() => setError(null), 4000);
    }
  }

  return (
    <div className="fixed bottom-0 left-0 right-0 z-50 bg-zinc-950/95 backdrop-blur border-t border-zinc-800 flex items-center gap-0">
      {/* Label */}
      <div className="flex-shrink-0 flex items-center gap-2 px-3 border-r border-zinc-800 h-full self-stretch">
        <span className="text-[9px] font-bold uppercase tracking-widest text-zinc-500">Alerts</span>
        {visible.length > 0 && (
          <span className="text-[9px] bg-zinc-800 text-zinc-400 rounded px-1">{visible.length}</span>
        )}
        {visible.length > 0 && (
          <button
            type="button"
            onClick={handleClearAll}
            className="text-[9px] uppercase tracking-wider text-zinc-500 hover:text-zinc-200 border border-zinc-800 hover:border-zinc-600 rounded px-1.5 py-0.5 transition-colors"
            title="Clear all recent alerts"
          >
            Clear all
          </button>
        )}
      </div>

      {/* Scrollable alert chips */}
      <div className="flex-1 overflow-x-auto overflow-y-hidden">
        {visible.length === 0 ? (
          <p className="text-[10px] text-zinc-600 px-4 py-2.5">
            {error ? <span className="text-red-400">{error}</span> : 'Aucune alerte'}
          </p>
        ) : (
          <div className="flex items-center gap-2 px-3 py-2 w-max">
            {error && (
              <div className="flex-shrink-0 text-[10px] text-red-400 bg-red-950/60 border border-red-700/60 rounded px-2 py-1">
                {error}
              </div>
            )}
            {visible.map((a, i) => (
              <div
                key={a.key ? `${a.key}-${a.timestamp}` : `${i}-${a.timestamp}`}
                className={`flex-shrink-0 flex items-center gap-1.5 border rounded px-2 py-1 text-[10px] ${SEV_CHIP[a.severity]}`}
              >
                <span className={`w-1.5 h-1.5 rounded-full flex-shrink-0 ${SEV_DOT[a.severity]}`} />
                <span className={`font-semibold ${SEV_LABEL[a.severity]}`}>{a.category}</span>
                {a.instrument && (
                  <span className="bg-zinc-700/60 text-zinc-400 text-[9px] px-1 rounded">{a.instrument}</span>
                )}
                <span className="text-zinc-300 max-w-[220px] truncate">{a.message}</span>
                <span className="text-[9px] text-zinc-600 ml-1 flex-shrink-0">
                  {new Date(a.timestamp).toLocaleTimeString()}
                </span>
                {a.key && (
                  <button
                    type="button"
                    onClick={() => handleSnooze(a)}
                    className="ml-1 flex-shrink-0 text-[9px] uppercase tracking-wider text-zinc-500 hover:text-zinc-200 border border-zinc-700/60 hover:border-zinc-500 rounded px-1 py-0.5 transition-colors"
                    title={`Snooze this alert for 30 minutes (${a.key})`}
                  >
                    Snooze 30m
                  </button>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
