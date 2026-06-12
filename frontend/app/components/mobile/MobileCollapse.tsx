'use client';

import { ReactNode, useState } from 'react';
import { ChevronDownIcon } from './TabIcons';

interface Props {
  title: string;
  titleClassName?: string;
  subtitle?: string;
  defaultOpen?: boolean;
  children: ReactNode;
}

/**
 * Collapsed-by-default wrapper for secondary mobile panels. Children are only
 * MOUNTED while open, so a collapsed panel never fetches or subscribes.
 */
export default function MobileCollapse({ title, titleClassName, subtitle, defaultOpen = false, children }: Props) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div className="rounded-lg border border-zinc-800 bg-zinc-900">
      <button
        onClick={() => setOpen(v => !v)}
        aria-expanded={open}
        className="w-full flex items-center gap-2 px-3 py-3 min-h-[48px] text-left"
      >
        <span className={`text-[13px] font-semibold ${titleClassName ?? 'text-zinc-200'}`}>{title}</span>
        {subtitle && (
          <span className="text-[11px] text-zinc-500 border border-zinc-800 rounded-md px-2 py-0.5">{subtitle}</span>
        )}
        <ChevronDownIcon className={`ml-auto text-zinc-500 transition-transform ${open ? 'rotate-180' : ''}`} />
      </button>
      {open && <div className="px-2 pb-2">{children}</div>}
    </div>
  );
}
