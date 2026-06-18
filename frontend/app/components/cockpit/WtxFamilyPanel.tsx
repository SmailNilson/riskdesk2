'use client';

/**
 * Wrapper that gathers the four WTX variants + WTX-RSI behind sub-tabs so they no
 * longer stack vertically in the right rail. Each underlying panel keeps its own
 * polling and local state — we mount all five and toggle visibility via inline
 * `display` so React state (collapse, draft edits, busy flags) survives tab
 * switches.
 *
 * This file is a pure wrapper: it never reimplements panel logic and never
 * redefines the panel prop types — those live on the underlying panels.
 */

import { useEffect, useId, useMemo, useState } from 'react';
import WtxStrategyPanel from '@/app/components/WtxStrategyPanel';
import WtxRsiStrategyPanel from '@/app/components/WtxRsiStrategyPanel';
import type {
  WtxSignalView,
  WtxRsiSignalView,
  WtxRsiStrategyStateView,
} from '@/app/lib/api';

export type WtxFamilyTab = '5m' | '10m' | '10m-z35' | '10m-z40' | 'rsi';

const TAB_KEYS: WtxFamilyTab[] = ['5m', '10m', '10m-z35', '10m-z40', 'rsi'];
const TAB_LABELS: Record<WtxFamilyTab, string> = {
  '5m': '5m',
  '10m': '10m',
  '10m-z35': 'Z35',
  '10m-z40': 'Z40',
  rsi: 'WTX-RSI',
};

function storageKeyFor(instrument: string): string {
  return `riskdesk.cockpit.wtx-family.${instrument}.tab`;
}

function isValidTab(value: string | null): value is WtxFamilyTab {
  return value !== null && (TAB_KEYS as readonly string[]).includes(value);
}

export interface WtxFamilyPanelProps {
  instrument: string;
  wtx5mSignals: WtxSignalView[];
  wtx10mSignals: WtxSignalView[];
  wtxZ35Signals: WtxSignalView[];
  wtxZ40Signals: WtxSignalView[];
  wtxRsiSignals: WtxRsiSignalView[];
  wtxRsiLiveState?: WtxRsiStrategyStateView | null;
  /** Override default starting sub-tab. */
  defaultTab?: WtxFamilyTab;
  className?: string;
}

/**
 * Sub-tabbed wrapper around the four WTX timeframe variants + the WTX+RSI panel.
 *
 * All five children stay mounted (visibility toggled via `display: none`) so
 * heavy panel state — open positions, busy edits, REST polls in flight — is not
 * lost on every tab switch. The active tab persists per instrument in
 * `localStorage`, so MNQ / MCL / MGC / 6E each remember independently.
 */
export default function WtxFamilyPanel({
  instrument,
  wtx5mSignals,
  wtx10mSignals,
  wtxZ35Signals,
  wtxZ40Signals,
  wtxRsiSignals,
  wtxRsiLiveState,
  defaultTab = '5m',
  className,
}: WtxFamilyPanelProps) {
  const reactId = useId();
  // Stable per-instrument id namespace for aria-controls / aria-labelledby. The
  // useId() prefix keeps things unique when the wrapper is rendered more than
  // once on the page (e.g. mobile + desktop layouts in the same tree).
  const idFor = useMemo(
    () => (tab: WtxFamilyTab) => ({
      tabId: `wtx-family-tab-${reactId}-${instrument}-${tab}`,
      panelId: `wtx-family-panel-${reactId}-${instrument}-${tab}`,
    }),
    [reactId, instrument],
  );

  const [activeTab, setActiveTab] = useState<WtxFamilyTab>(defaultTab);

  // Restore persisted tab when the instrument changes. We re-read on every
  // instrument switch so MNQ / MCL / MGC / 6E each have their own memory; we
  // fall back to `defaultTab` when nothing is stored or the stored value is
  // stale (e.g. an old key from a renamed tab).
  useEffect(() => {
    if (typeof window === 'undefined') return;
    try {
      const stored = window.localStorage.getItem(storageKeyFor(instrument));
      setActiveTab(isValidTab(stored) ? stored : defaultTab);
    } catch {
      // localStorage unavailable (private mode / quota) — silently keep default.
      setActiveTab(defaultTab);
    }
  }, [instrument, defaultTab]);

  // Persist on every tab change. A try/catch protects against quota errors so
  // a full localStorage doesn't break tab switching.
  useEffect(() => {
    if (typeof window === 'undefined') return;
    try {
      window.localStorage.setItem(storageKeyFor(instrument), activeTab);
    } catch {
      // Persistence is best-effort; ignore.
    }
  }, [instrument, activeTab]);

  const containerClass = `border border-zinc-800 rounded-lg bg-zinc-900/50 overflow-hidden${
    className ? ` ${className}` : ''
  }`;

  return (
    <div className={containerClass}>
      <div
        role="tablist"
        aria-label={`WTX family panels for ${instrument}`}
        className="flex gap-1 bg-zinc-950 border-b border-zinc-800 px-3 py-2"
      >
        {TAB_KEYS.map(tab => {
          const active = tab === activeTab;
          const { tabId, panelId } = idFor(tab);
          return (
            <button
              key={tab}
              id={tabId}
              type="button"
              role="tab"
              aria-selected={active}
              aria-controls={panelId}
              tabIndex={active ? 0 : -1}
              onClick={() => setActiveTab(tab)}
              className={`text-[11px] font-medium px-2.5 py-1 rounded transition-colors ${
                active
                  ? 'bg-zinc-700 text-white'
                  : 'bg-zinc-800 text-zinc-400 hover:text-zinc-200'
              }`}
            >
              {TAB_LABELS[tab]}
            </button>
          );
        })}
      </div>

      {/* All five panels stay mounted; only the active one is visible. This
          preserves each panel's local state (collapsed flag, draft edits,
          REST poll cadence) across sub-tab switches. */}
      <div className="p-3 space-y-3">
        {TAB_KEYS.map(tab => {
          const { tabId, panelId } = idFor(tab);
          const visible = tab === activeTab;
          const style = visible ? undefined : { display: 'none' as const };
          return (
            <div
              key={tab}
              id={panelId}
              role="tabpanel"
              aria-labelledby={tabId}
              style={style}
            >
              {tab === '5m' && (
                <WtxStrategyPanel
                  instrument={instrument}
                  timeframe="5m"
                  liveSignals={wtx5mSignals}
                />
              )}
              {tab === '10m' && (
                <WtxStrategyPanel
                  instrument={instrument}
                  timeframe="10m"
                  liveSignals={wtx10mSignals}
                />
              )}
              {tab === '10m-z35' && (
                <WtxStrategyPanel
                  instrument={instrument}
                  timeframe="10m-z35"
                  displayName="top-train-Z35"
                  liveSignals={wtxZ35Signals}
                />
              )}
              {tab === '10m-z40' && (
                <WtxStrategyPanel
                  instrument={instrument}
                  timeframe="10m-z40"
                  displayName="top-train-Z40"
                  liveSignals={wtxZ40Signals}
                />
              )}
              {tab === 'rsi' && (
                <WtxRsiStrategyPanel
                  instrument={instrument}
                  timeframe="5m"
                  liveSignals={wtxRsiSignals}
                  liveState={wtxRsiLiveState ?? null}
                />
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
