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

export interface RailTabsProps {
  /** localStorage key suffix: riskdesk.layout.rail-tabs.<id> */
  id: string;
  tabs: TabDef[];
  defaultTab?: string;
  /** Affects style polish only (border accents). Behavior identical. */
  side?: 'left' | 'right';
  className?: string;
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
 * Compact tab strip for left/right rail use.
 *
 * Same mount-everything contract as CockpitTabs: every child is rendered
 * in the DOM and toggled with `style={{ display: ... }}`. This keeps
 * heavy-state panels (sockets, charts, scroll positions) alive across
 * tab switches.
 */
export default function RailTabs({
  id,
  tabs,
  defaultTab,
  side,
  className,
  onTabChange,
}: RailTabsProps): JSX.Element {
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
    'rail-tabs',
    id,
    fallback,
    validKeys,
  );

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

  const tabIdFor = (key: string) => `rail-tab-${id}-${key}`;
  const panelIdFor = (key: string) => `rail-panel-${id}-${key}`;

  const colCount = Math.max(tabs.length, 1);
  // Tailwind needs literal class names — switch on the column count.
  const gridColsClass = (() => {
    switch (colCount) {
      case 1:
        return 'grid-cols-1';
      case 2:
        return 'grid-cols-2';
      case 3:
        return 'grid-cols-3';
      case 4:
        return 'grid-cols-4';
      case 5:
        return 'grid-cols-5';
      case 6:
        return 'grid-cols-6';
      default:
        return 'grid-cols-6';
    }
  })();

  const sidePolishClass =
    side === 'left'
      ? 'border-l border-zinc-900/0'
      : side === 'right'
        ? 'border-r border-zinc-900/0'
        : '';

  return (
    <div
      className={`flex flex-col h-full min-h-0 ${sidePolishClass} ${className ?? ''}`}
    >
      <div
        role="tablist"
        aria-orientation="horizontal"
        className={`grid ${gridColsClass} bg-zinc-950 border-b border-zinc-800 sticky top-0 z-10`}
      >
        {tabs.map((tab) => {
          const isActive = tab.key === effectiveActive;
          const isDisabled = !!tab.disabled;
          let stateClass: string;
          if (isDisabled) {
            stateClass = 'text-zinc-700 cursor-not-allowed';
          } else if (isActive) {
            stateClass = 'text-white border-b-2 border-emerald-400';
          } else {
            stateClass = 'text-zinc-500 hover:text-zinc-300';
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
              className={`text-[10px] uppercase tracking-widest py-2 text-center ${stateClass}`}
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
