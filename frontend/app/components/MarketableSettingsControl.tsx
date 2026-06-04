'use client';

import { useCallback, useEffect, useState } from 'react';
import { api, MarketableSettingsView } from '../lib/api';

const POLL_MS = 5_000;

/**
 * GLOBAL, operator-controlled marketable-execution policy (exits + reverse open) — like the Auto-IBKR
 * toggle, but execution-core-wide (applies to every strategy). Flips live via
 * PUT /api/execution/marketable-settings; no redeploy. Polled every 5s to stay in sync.
 */
export default function MarketableSettingsControl() {
  const [settings, setSettings] = useState<MarketableSettingsView | null>(null);
  const [busy, setBusy] = useState(false);
  const [draftTicks, setDraftTicks] = useState('');

  const fetchSettings = useCallback(async () => {
    const s = await api.getMarketableSettings();
    if (s) {
      setSettings(s);
      setDraftTicks(String(s.crossTicks));
    }
  }, []);

  useEffect(() => {
    void fetchSettings();
    const id = setInterval(() => void fetchSettings(), POLL_MS);
    return () => clearInterval(id);
  }, [fetchSettings]);

  const update = useCallback(async (patch: Partial<MarketableSettingsView>) => {
    setBusy(true);
    try {
      const updated = await api.updateMarketableSettings(patch);
      if (updated) {
        setSettings(updated);
        setDraftTicks(String(updated.crossTicks));
      }
    } finally {
      setBusy(false);
    }
  }, []);

  if (!settings) return null;

  const closeOn = settings.closeEnabled;
  const revOn = settings.reverseOpenEnabled;

  const toggleClass = (on: boolean) =>
    `rounded px-1.5 py-0.5 text-[10px] font-semibold transition-colors disabled:opacity-50 ${
      on
        ? 'border border-emerald-700/70 bg-emerald-950/40 text-emerald-300 hover:bg-emerald-950/60'
        : 'border border-zinc-700 text-zinc-500 hover:border-zinc-500 hover:text-zinc-300'
    }`;

  const commitTicks = () => {
    const n = parseInt(draftTicks, 10);
    if (!Number.isNaN(n) && n >= 0 && n !== settings.crossTicks) {
      void update({ crossTicks: n });
    } else {
      setDraftTicks(String(settings.crossTicks)); // revert invalid / unchanged input
    }
  };

  return (
    <div
      className="flex items-center gap-1 rounded-lg border border-zinc-800 px-2 py-1 bg-zinc-900"
      title="Marketable execution pricing (GLOBAL — all strategies). Exits and the reverse open cross the live price by cross-ticks so they fill immediately instead of resting at the passive limit."
    >
      <span className="text-[10px] text-zinc-600 select-none">⚡ Mkt</span>
      <button
        type="button"
        onClick={() => void update({ closeEnabled: !closeOn })}
        disabled={busy}
        aria-pressed={closeOn}
        title={`Marketable exits (close / flatten / reverse-close) ${closeOn ? 'ON — click to disable (revert to passive limit)' : 'OFF — click to enable'}`}
        className={toggleClass(closeOn)}
      >
        Exit {closeOn ? 'ON' : 'OFF'}
      </button>
      <button
        type="button"
        onClick={() => void update({ reverseOpenEnabled: !revOn })}
        disabled={busy}
        aria-pressed={revOn}
        title={`Marketable reverse open ${revOn ? 'ON — click to disable (reverse open stays passive)' : 'OFF — click to enable'}`}
        className={toggleClass(revOn)}
      >
        Rev {revOn ? 'ON' : 'OFF'}
      </button>
      <input
        type="number"
        min={0}
        value={draftTicks}
        disabled={busy}
        onChange={e => setDraftTicks(e.target.value)}
        onBlur={commitTicks}
        onKeyDown={e => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur(); }}
        title="cross-ticks — worst-case slippage cap (ticks crossed through the touch)"
        className="w-9 bg-transparent text-[10px] text-zinc-300 outline-none text-center border-b border-zinc-700 focus:border-emerald-600"
      />
    </div>
  );
}
