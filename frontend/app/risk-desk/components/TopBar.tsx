'use client';

import { useEffect, useState } from 'react';
import { Segmented, StatusDot } from '../lib/atoms';
import { Instrument } from '../lib/data';
import { fmt } from '../lib/format';

// Timezone options offered in the TopBar selector. The selected zone drives
// the Clock display so traders outside ET get a wall-clock that matches their
// physical location while the rest of the app keeps using ET for session math.
export interface TzEntry {
  tz: string;
  label: string;
}
export const TZ_OPTIONS: TzEntry[] = [
  { tz: 'America/New_York', label: 'NY' },
  { tz: 'UTC', label: 'UTC' },
  { tz: 'Europe/Paris', label: 'Paris' },
  { tz: 'Europe/London', label: 'London' },
  { tz: 'Asia/Tokyo', label: 'Tokyo' },
];

function Logo() {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        paddingRight: 16,
        borderRight: '1px solid var(--line)',
        height: '100%',
      }}
    >
      <svg width="22" height="22" viewBox="0 0 22 22" fill="none">
        <rect x="1" y="1" width="20" height="20" rx="4" stroke="var(--accent)" strokeWidth="1.5" />
        <path
          d="M5 14 L9 9 L12 12 L17 6"
          stroke="var(--accent)"
          strokeWidth="1.5"
          fill="none"
          strokeLinejoin="round"
          strokeLinecap="round"
        />
        <circle cx="17" cy="6" r="1.5" fill="var(--accent)" />
      </svg>
      <div style={{ display: 'flex', flexDirection: 'column', lineHeight: 1 }}>
        <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--ink-0)', letterSpacing: '-0.01em' }}>RiskDesk</span>
        <span
          style={{
            fontSize: 9,
            color: 'var(--ink-3)',
            letterSpacing: '0.12em',
            textTransform: 'uppercase',
            marginTop: 1,
          }}
        >
          Trader Terminal
        </span>
      </div>
    </div>
  );
}

function Clock({ tz }: { tz: TzEntry }) {
  const [t, setT] = useState<Date | null>(null);
  // Defer to mount so SSR/CSR don't drift
  useEffect(() => {
    setT(new Date());
    const i = setInterval(() => setT(new Date()), 1000);
    return () => clearInterval(i);
  }, []);
  const time = t ? t.toLocaleTimeString('en-US', { hour12: false, timeZone: tz.tz }) : '— —:—';
  const date = t
    ? t.toLocaleDateString('en-US', { month: 'short', day: '2-digit', timeZone: tz.tz })
    : '—';
  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', lineHeight: 1.1 }}>
      <span className="mono" style={{ fontSize: 12, color: 'var(--ink-0)', fontWeight: 600 }}>
        {time}
      </span>
      <span style={{ fontSize: 10, color: 'var(--ink-3)', fontFamily: 'var(--font-mono)' }}>
        {date} · {tz.label}
      </span>
    </div>
  );
}

function TimezoneSelector({
  value,
  onChange,
}: {
  value: TzEntry;
  onChange: (tz: TzEntry) => void;
}) {
  return (
    <select
      value={value.tz}
      onChange={(e) => {
        const next = TZ_OPTIONS.find((z) => z.tz === e.target.value) ?? TZ_OPTIONS[0];
        onChange(next);
      }}
      title="Timezone for the clock display"
      style={{
        height: 24,
        padding: '0 6px',
        background: 'var(--s2)',
        border: '1px solid var(--line)',
        borderRadius: 4,
        color: 'var(--ink-2)',
        fontSize: 10,
        fontFamily: 'var(--font-mono)',
        cursor: 'pointer',
      }}
    >
      {TZ_OPTIONS.map((z) => (
        <option key={z.tz} value={z.tz}>
          {z.label}
        </option>
      ))}
    </select>
  );
}

function PurgeButton({
  instrument,
  onPurge,
}: {
  instrument: string;
  onPurge: (sym: string) => Promise<{ purged?: number; error?: string }>;
}) {
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const handle = async () => {
    if (busy) return;
    if (
      !window.confirm(
        `Purge all ${instrument} candles (all timeframes) and trigger IBKR re-backfill?\n\nThis is destructive — existing DB history for ${instrument} will be wiped.`
      )
    )
      return;
    setBusy(true);
    setMsg(null);
    const res = await onPurge(instrument);
    setBusy(false);
    if (res.error) {
      setMsg(`Error`);
    } else {
      setMsg(`Purged ${res.purged ?? 0}`);
    }
    setTimeout(() => setMsg(null), 6000);
  };
  return (
    <button
      type="button"
      onClick={handle}
      disabled={busy}
      title={`Wipe all ${instrument} candles and re-backfill from IBKR`}
      style={{
        height: 24,
        padding: '0 8px',
        background: 'var(--s2)',
        border: '1px solid var(--line)',
        borderRadius: 4,
        color: msg
          ? 'var(--up)'
          : busy
          ? 'var(--ink-3)'
          : 'var(--ink-2)',
        fontSize: 10,
        fontFamily: 'var(--font-mono)',
        cursor: busy ? 'wait' : 'pointer',
        letterSpacing: '0.04em',
      }}
    >
      {busy ? 'PURGING…' : msg ?? `PURGE ${instrument}`}
    </button>
  );
}

function InstrumentTabs({
  instruments,
  active,
  onSelect,
}: {
  instruments: Instrument[];
  active: string;
  onSelect: (sym: string) => void;
}) {
  return (
    <div className="inst-tabs" role="tablist">
      {instruments.map((i) => (
        <button
          key={i.sym}
          type="button"
          role="tab"
          className="inst-tab"
          aria-selected={active === i.sym}
          onClick={() => onSelect(i.sym)}
        >
          <div className="row1">
            <span>{i.sym}</span>
          </div>
          <div className="row2">
            <span>{i.sym === 'E6' ? fmt.pxFx(i.last) : fmt.px(i.last, 2)}</span>
            <span className={i.chg >= 0 ? 'up' : 'down'}>{fmt.signed(i.chg, i.sym === 'E6' ? 4 : 2)}</span>
          </div>
        </button>
      ))}
      <button type="button" className="inst-tab add-tab">
        +
      </button>
    </div>
  );
}

export type ViewMode = 'setup' | 'execute' | 'review';

function ViewSwitcher({ value, onChange }: { value: ViewMode; onChange: (v: ViewMode) => void }) {
  const views: Array<{ v: ViewMode; l: string; k: string }> = [
    { v: 'setup', l: 'Setup', k: '1' },
    { v: 'execute', l: 'Execute', k: '2' },
    { v: 'review', l: 'Review', k: '3' },
  ];
  return (
    <div className="view-tabs">
      {views.map((v) => (
        <button
          key={v.v}
          type="button"
          className="view-tab"
          aria-pressed={value === v.v}
          onClick={() => onChange(v.v)}
        >
          <span>{v.l}</span>
          <span className="num">⌘{v.k}</span>
        </button>
      ))}
    </div>
  );
}

function ConnectionPill({ connected, latencyMs }: { connected: boolean; latencyMs: number }) {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        padding: '0 10px',
        height: 24,
        borderRadius: 12,
        background: 'var(--s2)',
        border: '1px solid var(--line)',
      }}
    >
      <StatusDot kind={connected ? 'up' : 'down'} pulse={connected} />
      <span
        style={{
          fontSize: 10,
          fontFamily: 'var(--font-mono)',
          color: 'var(--ink-2)',
          letterSpacing: '0.04em',
        }}
      >
        IBKR · {connected ? `${latencyMs}ms` : 'offline'}
      </span>
    </div>
  );
}

function SearchBar() {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 6,
        padding: '0 8px',
        height: 26,
        width: 32,
        background: 'var(--s2)',
        border: '1px solid var(--line)',
        borderRadius: 4,
        justifyContent: 'center',
      }}
      title="Search ⌘K"
    >
      <svg width="13" height="13" viewBox="0 0 16 16" fill="none">
        <circle cx="7" cy="7" r="5" stroke="var(--ink-3)" strokeWidth="1.5" />
        <path d="M11 11 L14 14" stroke="var(--ink-3)" strokeWidth="1.5" strokeLinecap="round" />
      </svg>
    </div>
  );
}

interface TopBarProps {
  instruments: Instrument[];
  instrument: string;
  onInstrument: (sym: string) => void;
  view: ViewMode;
  onView: (v: ViewMode) => void;
  tf: string;
  onTf: (tf: string) => void;
  connected: boolean;
  latencyMs: number;
  timezone: TzEntry;
  onTimezone: (tz: TzEntry) => void;
  onPurge: (sym: string) => Promise<{ purged?: number; error?: string }>;
}

export function TopBar({
  instruments,
  instrument,
  onInstrument,
  view,
  onView,
  tf,
  onTf,
  connected,
  latencyMs,
  timezone,
  onTimezone,
  onPurge,
}: TopBarProps) {
  return (
    <div className="topbar">
      <Logo />
      <InstrumentTabs instruments={instruments} active={instrument} onSelect={onInstrument} />
      <div style={{ flex: 1 }} />
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, paddingRight: 10, borderRight: '1px solid var(--line)' }}>
        <Segmented value={tf} options={['1m', '5m', '10m', '1H', '4H', 'D']} onChange={onTf} />
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, padding: '0 10px', borderRight: '1px solid var(--line)' }}>
        <ViewSwitcher value={view} onChange={onView} />
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '0 8px', borderRight: '1px solid var(--line)' }}>
        <PurgeButton instrument={instrument} onPurge={onPurge} />
        <TimezoneSelector value={timezone} onChange={onTimezone} />
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '0 12px' }}>
        <SearchBar />
        <ConnectionPill connected={connected} latencyMs={latencyMs} />
        <Clock tz={timezone} />
      </div>
    </div>
  );
}
