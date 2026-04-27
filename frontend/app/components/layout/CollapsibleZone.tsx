'use client';

import { useEffect, useState } from 'react';

interface CollapsibleZoneProps {
  /** localStorage key suffix — full key is `riskdesk.layout.zone.<id>`. */
  id: string;
  /** Title shown in the header bar (expanded) or vertically (collapsed). */
  title: string;
  /** Which side this zone sits on — controls chevron direction. */
  side: 'left' | 'right';
  /** Initial collapsed state before localStorage has been read. */
  defaultCollapsed?: boolean;
  /**
   * Optional controlled mode. When both are provided, the zone is fully
   * driven by the parent. This lets the parent mirror the state into the
   * grid's template-columns so track widths follow the actual zone state.
   */
  collapsed?: boolean;
  onCollapsedChange?: (next: boolean) => void;
  children: React.ReactNode;
}

const STORAGE_PREFIX = 'riskdesk.layout.zone.';

/**
 * Hook that manages a zone's collapsed state in localStorage. Exported so the
 * Dashboard can read the same state used by CollapsibleZone to compute the
 * grid's `grid-template-columns` — without this, the zone shrinks visually
 * while the grid track keeps its old width.
 */
export function useCollapsibleZoneState(id: string, defaultCollapsed = false) {
  const [collapsed, setCollapsed] = useState<boolean>(defaultCollapsed);
  const [hydrated, setHydrated] = useState(false);

  // Hydrate from localStorage on mount (client-only, avoids SSR mismatch).
  useEffect(() => {
    try {
      const raw = window.localStorage.getItem(STORAGE_PREFIX + id);
      if (raw === 'true') setCollapsed(true);
      else if (raw === 'false') setCollapsed(false);
    } catch {
      /* ignore storage errors (private mode, etc.) */
    }
    setHydrated(true);
  }, [id]);

  // Persist on change (only after hydration to avoid overwriting stored value).
  useEffect(() => {
    if (!hydrated) return;
    try {
      window.localStorage.setItem(STORAGE_PREFIX + id, String(collapsed));
    } catch {
      /* ignore */
    }
  }, [collapsed, hydrated, id]);

  return { collapsed, setCollapsed, hydrated };
}

export default function CollapsibleZone({
  id,
  title,
  side,
  defaultCollapsed = false,
  collapsed: controlledCollapsed,
  onCollapsedChange,
  children,
}: CollapsibleZoneProps) {
  const isControlled = controlledCollapsed !== undefined;
  const internal = useCollapsibleZoneState(id, defaultCollapsed);
  const collapsed = isControlled ? controlledCollapsed : internal.collapsed;

  const toggle = () => {
    const next = !collapsed;
    if (isControlled) {
      onCollapsedChange?.(next);
    } else {
      internal.setCollapsed(next);
    }
  };

  // Chevron glyph: collapsed left zone shows ► (expand right), expanded shows ◄ (collapse left).
  const chevron = collapsed
    ? side === 'left'
      ? '►'
      : '◄'
    : side === 'left'
      ? '◄'
      : '►';

  const baseClasses =
    'bg-zinc-900/40 border border-zinc-800 rounded-lg transition-all duration-200 flex flex-col';

  if (collapsed) {
    return (
      <aside
        className={`${baseClasses} w-full lg:w-10 lg:min-w-[2.5rem] items-center py-2 gap-2 select-none`}
        aria-label={`${title} (collapsed)`}
      >
        <button
          type="button"
          onClick={toggle}
          title={`Expand ${title}`}
          className="text-xs text-zinc-400 hover:text-white w-full py-1"
        >
          {chevron}
        </button>
        <div
          className="hidden lg:block text-[10px] tracking-widest uppercase text-zinc-500 font-semibold"
          style={{ writingMode: 'vertical-rl', transform: 'rotate(180deg)' }}
        >
          {title}
        </div>
      </aside>
    );
  }

  return (
    <aside
      className={`${baseClasses} w-full min-w-0`}
      aria-label={title}
    >
      <div className="flex items-center justify-between px-3 py-2 border-b border-zinc-800">
        <span className="text-[11px] tracking-widest uppercase text-zinc-400 font-semibold truncate">
          {title}
        </span>
        <button
          type="button"
          onClick={toggle}
          title={`Collapse ${title}`}
          className="text-xs text-zinc-500 hover:text-zinc-200 px-1 shrink-0"
        >
          {chevron}
        </button>
      </div>
      <div className="flex flex-col gap-3 p-3 overflow-y-auto overflow-x-hidden">{children}</div>
    </aside>
  );
}
