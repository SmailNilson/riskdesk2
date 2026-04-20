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
  children: React.ReactNode;
}

const STORAGE_PREFIX = 'riskdesk.layout.zone.';

export default function CollapsibleZone({
  id,
  title,
  side,
  defaultCollapsed = false,
  children,
}: CollapsibleZoneProps) {
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

  const toggle = () => setCollapsed(c => !c);

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
        className={`${baseClasses} w-10 min-w-[2.5rem] items-center py-2 gap-2 select-none`}
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
          className="text-[10px] tracking-widest uppercase text-zinc-500 font-semibold"
          style={{ writingMode: 'vertical-rl', transform: 'rotate(180deg)' }}
        >
          {title}
        </div>
      </aside>
    );
  }

  return (
    <aside
      className={`${baseClasses} w-full lg:w-[340px] lg:min-w-[320px] lg:max-w-[420px]`}
      aria-label={title}
    >
      <div className="flex items-center justify-between px-3 py-2 border-b border-zinc-800">
        <span className="text-[11px] tracking-widest uppercase text-zinc-400 font-semibold">
          {title}
        </span>
        <button
          type="button"
          onClick={toggle}
          title={`Collapse ${title}`}
          className="text-xs text-zinc-500 hover:text-zinc-200 px-1"
        >
          {chevron}
        </button>
      </div>
      <div className="flex flex-col gap-3 p-3 overflow-y-auto">{children}</div>
    </aside>
  );
}
