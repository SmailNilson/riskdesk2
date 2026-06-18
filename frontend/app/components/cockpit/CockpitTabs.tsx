'use client';

import React, { useCallback, useMemo, useRef } from 'react';
import { usePersistedTab } from './_tabsPersistence';

export interface TabDef {
  key: string;
  label: string;
  content: React.ReactNode;
  /** Optional badge for unread/new counts (shown as small chip). */
  badge?: number | string | null;
  /** Optional disabled state. */
  disabled?: boolean;
}

export interface CockpitTabsProps {
  /** localStorage key suffix: riskdesk.layout.cockpit-tabs.<id> */
  id: string;
  tabs: TabDef[];
  /** Falls back to first non-disabled tab if not provided / invalid. */
  defaultTab?: string;
  className?: string;
  /** Optional onTabChange callback. */
  onTabChange?: (key: string) => void;
}

function renderBadge(badge: TabDef['badge']): React.ReactNode {
  if (badge === null || badge === undefined) return null;
  const isNumber = typeof badge === 'number';
  if (isNumber && (badge as number) <= 0) return null;
  const baseClass =
    'ml-1.5 inline-flex items-center justify-center min-w-[18px] h-[18px] text-[10px] font-medium px-1 rounded';
  const colorClass =
    isNumber && (badge as number) > 0
      ? 'bg-red-500 text-white'
      : 'bg-zinc-800 text-zinc-300';
  return <span className={`${baseClass} ${colorClass}`}>{badge}</span>;
}

/**
 * Central-zone horizontal tab router (Price / Flow / Setup style).
 *
 * Critically: all tab contents stay mounted simultaneously and are toggled
 * via `style={{ display: ... }}` so that heavy chart / WebSocket / state
 * (lightweight-charts in particular) survives tab switches.
 */
export default function CockpitTabs({
  id,
  tabs,
  defaultTab,
  className,
  onTabChange,
}: CockpitTabsProps): JSX.Element {
  const validKeys = useMemo(() => tabs.map((t) => t.key), [tabs]);

  const firstEnabled = useMemo(
    () => tabs.find((t) => !t.disabled)?.key ?? tabs[0]?.key ?? '',
    [tabs],
  );

  const fallback = useMemo(() => {
    if (defaultTab && validKeys.includes(defaultTab)) {
      const candidate = tabs.find((t) => t.key === defaultTab);
      if (candidate && !candidate.disabled) return defaultTab;
    }
    return firstEnabled;
  }, [defaultTab, validKeys, tabs, firstEnabled]);

  const [active, setActive] = usePersistedTab(
    'cockpit-tabs',
    id,
    fallback,
    validKeys,
  );

  // Guard: if the persisted active points at a now-disabled tab, drift to first enabled.
  const effectiveActive = useMemo(() => {
    const found = tabs.find((t) => t.key === active);
    if (found && !found.disabled) return active;
    return firstEnabled;
  }, [active, tabs, firstEnabled]);

  const tabRefs = useRef<Record<string, HTMLButtonElement | null>>({});

  const activate = useCallback(
    (key: string) => {
      const found = tabs.find((t) => t.key === key);
      if (!found || found.disabled) return;
      setActive(key);
      onTabChange?.(key);
    },
    [tabs, setActive, onTabChange],
  );

  const enabledKeys = useMemo(
    () => tabs.filter((t) => !t.disabled).map((t) => t.key),
    [tabs],
  );

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLButtonElement>, key: string) => {
      if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
      e.preventDefault();
      if (enabledKeys.length === 0) return;
      const idx = enabledKeys.indexOf(key);
      // If current focus is on a disabled tab, anchor at 0 for forward / end for back.
      const baseIdx = idx === -1 ? (e.key === 'ArrowRight' ? -1 : 0) : idx;
      const delta = e.key === 'ArrowRight' ? 1 : -1;
      const nextIdx =
        (baseIdx + delta + enabledKeys.length) % enabledKeys.length;
      const nextKey = enabledKeys[nextIdx];
      activate(nextKey);
      const btn = tabRefs.current[nextKey];
      btn?.focus();
    },
    [enabledKeys, activate],
  );

  const tabIdFor = (key: string) => `cockpit-tab-${id}-${key}`;
  const panelIdFor = (key: string) => `cockpit-panel-${id}-${key}`;

  return (
    <div className={`flex flex-col h-full min-h-0 ${className ?? ''}`}>
      <div
        role="tablist"
        aria-orientation="horizontal"
        className="flex items-end gap-4 px-3 border-b border-zinc-800 bg-zinc-900"
      >
        {tabs.map((tab) => {
          const isActive = tab.key === effectiveActive;
          const isDisabled = !!tab.disabled;
          let stateClass: string;
          if (isDisabled) {
            stateClass = 'border-transparent text-zinc-700 cursor-not-allowed';
          } else if (isActive) {
            stateClass = 'border-emerald-400 text-white';
          } else {
            stateClass =
              'border-transparent text-zinc-500 hover:text-zinc-300';
          }
          return (
            <button
              key={tab.key}
              ref={(el) => {
                tabRefs.current[tab.key] = el;
              }}
              type="button"
              role="tab"
              id={tabIdFor(tab.key)}
              aria-selected={isActive}
              aria-controls={panelIdFor(tab.key)}
              aria-disabled={isDisabled || undefined}
              tabIndex={isActive ? 0 : -1}
              disabled={isDisabled}
              onClick={() => activate(tab.key)}
              onKeyDown={(e) => handleKeyDown(e, tab.key)}
              className={`text-sm py-2 px-1 -mb-px border-b-2 transition-colors ${stateClass}`}
            >
              <span>{tab.label}</span>
              {renderBadge(tab.badge)}
            </button>
          );
        })}
      </div>
      <div className="flex-1 min-h-0 overflow-auto">
        {tabs.map((tab) => (
          <div
            key={tab.key}
            role="tabpanel"
            id={panelIdFor(tab.key)}
            aria-labelledby={tabIdFor(tab.key)}
            style={{ display: tab.key === effectiveActive ? 'block' : 'none' }}
          >
            {tab.content}
          </div>
        ))}
      </div>
    </div>
  );
}
