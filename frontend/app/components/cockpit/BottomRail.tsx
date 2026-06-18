'use client';

import { useEffect, useMemo, useRef, useState } from 'react';

export interface AlertItem {
  key: string;
  severity: 'INFO' | 'WARNING' | 'DANGER';
  instrument: string | null;
  category: string;
  message?: string;
  timestamp: string; // ISO
}

export interface PositionItem {
  instrument: string;
  side: 'LONG' | 'SHORT';
  qty: number;
  avgPrice: number;
  unrealizedPnl: number | null;
  status?: 'OPEN' | 'WORKING'; // WORKING = unfilled limit
}

export interface FillItem {
  timestamp: string;
  instrument: string;
  side: 'LONG' | 'SHORT' | 'FLAT' | 'STOP' | 'TARGET';
  qty: number;
  price: number;
  pnl?: number | null;
}

export interface BottomRailProps {
  alerts: AlertItem[];
  positions: PositionItem[];
  fills: FillItem[];
  defaultExpanded?: boolean;
  onSnoozeAlert?: (key: string) => void;
  onClearAlerts?: () => void;
  className?: string;
}

const LS_KEY = 'riskdesk.layout.bottom-rail.expanded';

function fmtUsd(v: number | null | undefined): string {
  if (v == null) return '—';
  return `${v >= 0 ? '+' : '-'}$${Math.abs(v).toFixed(2)}`;
}

function fmtPrice(v: number, i: string): string {
  const d = i === 'E6' ? 5 : i === 'DXY' ? 3 : i === 'MGC' ? 1 : 2;
  return v.toLocaleString('en-US', { minimumFractionDigits: d, maximumFractionDigits: d });
}

function pnlColor(v: number | null | undefined): string {
  if (v == null) return 'text-zinc-400';
  return v >= 0 ? 'text-emerald-400' : 'text-red-400';
}

function fmtTime(ts: string): string {
  try {
    const d = new Date(ts);
    if (Number.isNaN(d.getTime())) return '--:--:--';
    return d.toLocaleTimeString('en-US', { hour12: false });
  } catch {
    return '--:--:--';
  }
}

interface AlertGroup {
  groupKey: string;
  instrument: string | null;
  category: string;
  count: number;
  hasDanger: boolean;
  firstKey: string;
}

function groupAlerts(alerts: AlertItem[]): AlertGroup[] {
  const map = new Map<string, AlertGroup>();
  for (const a of alerts) {
    const groupKey = `${a.instrument ?? 'GLOBAL'}::${a.category}`;
    const existing = map.get(groupKey);
    if (existing) {
      existing.count += 1;
      if (a.severity === 'DANGER') existing.hasDanger = true;
    } else {
      map.set(groupKey, {
        groupKey,
        instrument: a.instrument,
        category: a.category,
        count: 1,
        hasDanger: a.severity === 'DANGER',
        firstKey: a.key,
      });
    }
  }
  return Array.from(map.values());
}

function ChevronIcon({ up }: { up: boolean }) {
  return (
    <svg
      width="12"
      height="12"
      viewBox="0 0 12 12"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
      style={{ transform: up ? 'rotate(180deg)' : 'none', transition: 'transform 120ms' }}
    >
      <path d="M3 4.5L6 7.5L9 4.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function SideBadge({ side, status }: { side: 'LONG' | 'SHORT'; status?: 'OPEN' | 'WORKING' }) {
  if (status === 'WORKING') {
    return (
      <span className="bg-zinc-800 text-zinc-400 text-[10px] px-1.5 py-0.5 rounded">WORKING</span>
    );
  }
  if (side === 'LONG') {
    return (
      <span className="bg-emerald-500/15 text-emerald-400 text-[10px] px-1.5 py-0.5 rounded">LONG</span>
    );
  }
  return (
    <span className="bg-red-500/15 text-red-400 text-[10px] px-1.5 py-0.5 rounded">SHORT</span>
  );
}

export default function BottomRail({
  alerts,
  positions,
  fills,
  defaultExpanded = false,
  onSnoozeAlert,
  onClearAlerts,
  className,
}: BottomRailProps) {
  const [expanded, setExpanded] = useState<boolean>(defaultExpanded);
  const hydratedRef = useRef<boolean>(false);

  // Hydrate from localStorage on mount
  useEffect(() => {
    if (typeof window === 'undefined') return;
    try {
      const stored = window.localStorage.getItem(LS_KEY);
      if (stored === 'true') setExpanded(true);
      else if (stored === 'false') setExpanded(false);
    } catch {
      // ignore
    }
    hydratedRef.current = true;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Persist (skip until after hydrate to avoid clobbering stored value with default)
  useEffect(() => {
    if (typeof window === 'undefined') return;
    if (!hydratedRef.current) return;
    try {
      window.localStorage.setItem(LS_KEY, expanded ? 'true' : 'false');
    } catch {
      // ignore
    }
  }, [expanded]);

  const alertGroups = useMemo(() => groupAlerts(alerts), [alerts]);

  const positionsOpenCount = useMemo(
    () => positions.filter((p) => (p.status ?? 'OPEN') === 'OPEN').length,
    [positions],
  );
  const positionsWorkingCount = useMemo(
    () => positions.filter((p) => p.status === 'WORKING').length,
    [positions],
  );

  const containerClass = [
    'sticky bottom-0 z-30 bg-zinc-900 border-t border-zinc-800',
    className ?? '',
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <div className={containerClass} style={{ minHeight: expanded ? 220 : 56 }}>
      {/* Header strip */}
      <div className="grid grid-cols-[1fr_1fr_1fr] gap-3 px-3 py-2">
        <div className="flex flex-col">
          <span className="text-[10px] uppercase tracking-widest text-zinc-500">
            ALERTS · {alerts.length} ACTIVE
          </span>
        </div>
        <div className="flex flex-col">
          <span className="text-[10px] uppercase tracking-widest text-zinc-500">
            POSITIONS · {positionsOpenCount} OPEN · {positionsWorkingCount} WORKING
          </span>
        </div>
        <div className="flex flex-col">
          <div className="flex items-center justify-between gap-2">
            <span className="text-[10px] uppercase tracking-widest text-zinc-500">
              RECENT FILLS · {fills.length}
            </span>
            <button
              type="button"
              onClick={() => setExpanded((e) => !e)}
              aria-expanded={expanded}
              aria-label={expanded ? 'Collapse bottom rail' : 'Expand bottom rail'}
              className="inline-flex items-center justify-center w-5 h-5 rounded text-zinc-500 hover:text-zinc-300"
            >
              <ChevronIcon up={!expanded} />
            </button>
          </div>
        </div>
      </div>

      {/* Expanded content */}
      {expanded && (
        <div className="grid grid-cols-[1fr_1fr_1fr] gap-3 px-3 pb-3">
          {/* Alerts column */}
          <div className="flex flex-col">
            {alertGroups.length === 0 ? (
              <div className="text-[11px] text-zinc-600">Aucune alerte</div>
            ) : (
              <>
                <div className="flex flex-wrap gap-1.5">
                  {alertGroups.map((g) => (
                    <button
                      type="button"
                      key={g.groupKey}
                      onClick={() => onSnoozeAlert?.(g.firstKey)}
                      className="bg-zinc-800 border border-zinc-800 text-zinc-300 text-[11px] px-2 py-1 rounded inline-flex items-center gap-1.5 hover:border-zinc-700"
                    >
                      {g.hasDanger && (
                        <span
                          className="w-1.5 h-1.5 rounded-full bg-fuchsia-500 animate-pulse"
                          aria-hidden="true"
                        />
                      )}
                      <span>
                        {(g.instrument ?? 'GLOBAL')} · {g.category} ×{g.count}
                      </span>
                    </button>
                  ))}
                </div>
                {onClearAlerts && (
                  <div className="mt-2">
                    <button
                      type="button"
                      onClick={onClearAlerts}
                      className="text-zinc-500 hover:text-zinc-300 text-[11px]"
                    >
                      Tout ignorer
                    </button>
                  </div>
                )}
              </>
            )}
          </div>

          {/* Positions column */}
          <div className="flex flex-col gap-1">
            {positions.length === 0 ? (
              <div className="text-[11px] text-zinc-600">Aucune position</div>
            ) : (
              positions.map((p, idx) => (
                <div
                  key={`${p.instrument}-${p.side}-${idx}`}
                  className="flex items-center gap-2 font-mono tabular-nums text-[12px] leading-relaxed"
                >
                  <SideBadge side={p.side} status={p.status} />
                  <span className="text-zinc-300">
                    {p.instrument} {p.qty} @{fmtPrice(p.avgPrice, p.instrument)}
                  </span>
                  <span className={`ml-auto ${pnlColor(p.unrealizedPnl)}`}>
                    {fmtUsd(p.unrealizedPnl)}
                  </span>
                </div>
              ))
            )}
          </div>

          {/* Recent fills column */}
          <div className="flex flex-col gap-1">
            {fills.length === 0 ? (
              <div className="text-[11px] text-zinc-600">Aucun fill récent</div>
            ) : (
              fills.map((f, idx) => (
                <div
                  key={`${f.timestamp}-${f.instrument}-${idx}`}
                  className="font-mono tabular-nums text-[11px] leading-relaxed text-zinc-300 flex items-center gap-2"
                >
                  <span>
                    {fmtTime(f.timestamp)} {f.instrument} {f.side} {f.qty} @
                    {fmtPrice(f.price, f.instrument)}
                  </span>
                  {f.pnl != null && (
                    <span className={`ml-auto ${pnlColor(f.pnl)}`}>{fmtUsd(f.pnl)}</span>
                  )}
                </div>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  );
}
