'use client';

import { Fragment, ReactNode, useMemo, useState } from 'react';

/**
 * Groups a newest-first signal list into per-day collapsible sections.
 *
 * Day bucketing uses the browser-local calendar day so it stays consistent with
 * the HH:mm clock the signal cards already render (which also use local time).
 * The newest day is expanded by default, older days collapsed — a user toggle
 * overrides that default per day.
 */
interface Props<T> {
  signals: T[];
  /** ISO timestamp accessor used for day bucketing + ordering. */
  getTs: (s: T) => string;
  /** Stable React key accessor. */
  getKey: (s: T) => string;
  renderSignal: (s: T) => ReactNode;
  /** Accent used on the day-header hover state. */
  accent?: 'cyan' | 'fuchsia';
  emptyLabel?: string;
}

const ACCENTS = {
  cyan: 'hover:border-cyan-700 hover:text-cyan-300',
  fuchsia: 'hover:border-fuchsia-700 hover:text-fuchsia-300',
} as const;

function isSameDay(a: Date, b: Date): boolean {
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  );
}

function dayLabel(date: Date): string {
  const now = new Date();
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  const dm = date.toLocaleDateString('fr-FR', { day: 'numeric', month: 'long' });
  if (isSameDay(date, now)) return `Aujourd'hui · ${dm}`;
  if (isSameDay(date, yesterday)) return `Hier · ${dm}`;
  return dm;
}

export default function DayGroupedSignals<T>({
  signals,
  getTs,
  getKey,
  renderSignal,
  accent = 'cyan',
  emptyLabel = 'Aucun signal',
}: Props<T>) {
  // Per-day open/closed overrides keyed by day bucket. Absence = use the
  // index-based default (newest day open, the rest closed).
  const [overrides, setOverrides] = useState<Record<string, boolean>>({});

  const groups = useMemo(() => {
    const out: { key: string; label: string; items: T[] }[] = [];
    for (const sig of signals) {
      const d = new Date(getTs(sig));
      const key = `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
      const last = out[out.length - 1];
      if (last && last.key === key) {
        last.items.push(sig);
      } else {
        out.push({ key, label: dayLabel(d), items: [sig] });
      }
    }
    return out;
  }, [signals, getTs]);

  if (groups.length === 0) {
    return <p className="text-[10px] text-zinc-600 italic">{emptyLabel}</p>;
  }

  return (
    <div className="space-y-1.5">
      {groups.map((g, i) => {
        const open = overrides[g.key] ?? i === 0;
        return (
          <Fragment key={g.key}>
            <button
              type="button"
              onClick={() => setOverrides(o => ({ ...o, [g.key]: !open }))}
              aria-expanded={open}
              className={`w-full flex items-center justify-between rounded border border-zinc-800 bg-zinc-950/40 px-2 py-1 text-[10px] font-medium text-zinc-400 transition-colors ${ACCENTS[accent]}`}
            >
              <span className="flex items-center gap-1.5">
                <span className="text-zinc-600">{open ? '▾' : '▸'}</span>
                <span className="capitalize">{g.label}</span>
                <span className="text-zinc-600">({g.items.length})</span>
              </span>
            </button>
            {open && g.items.map(sig => (
              <Fragment key={getKey(sig)}>{renderSignal(sig)}</Fragment>
            ))}
          </Fragment>
        );
      })}
    </div>
  );
}
