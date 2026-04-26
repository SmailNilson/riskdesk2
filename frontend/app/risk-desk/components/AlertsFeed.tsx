'use client';

import { CSSProperties, useMemo, useState } from 'react';
import { Chip, Segmented, StatusDot } from '../lib/atoms';
import { AlertItem, Instrument } from '../lib/data';
import { useRiskDeskData } from '../lib/RiskDeskContext';

type Filter = 'ALL' | 'DANGER' | 'WARNING' | 'INFO';

interface AlertsFeedProps {
  // Accepted so the shell can pass per-instrument context, even if not used yet.
  instrument: Instrument;
}

export function AlertsFeed(props: AlertsFeedProps) {
  void props.instrument;
  const D = useRiskDeskData();
  // Live data flows through the context (REST seed + /topic/alerts WebSocket).
  // Local state only tracks ephemeral UI: filter, paused, pinned, dismissed.
  const live = D.alerts;
  const [filter, setFilter] = useState<Filter>('ALL');
  const [paused, setPaused] = useState(false);
  const [pinned, setPinned] = useState<Set<string>>(new Set());
  const [dismissed, setDismissed] = useState<Set<string>>(new Set());

  const items = useMemo(() => live.filter((a) => !dismissed.has(a.id)), [live, dismissed]);

  const filtered = useMemo(() => {
    if (filter === 'ALL') return items;
    return items.filter((a) => a.sev === filter);
  }, [items, filter]);

  const counts = useMemo(
    () => ({
      ALL: items.length,
      DANGER: items.filter((a) => a.sev === 'DANGER').length,
      WARNING: items.filter((a) => a.sev === 'WARNING').length,
      INFO: items.filter((a) => a.sev === 'INFO').length,
    }),
    [items]
  );

  return (
    <section
      style={{
        borderTop: '1px solid var(--hair)',
        background: 'var(--bg-1)',
        padding: '8px 12px',
        flexShrink: 0,
        maxHeight: 180,
        display: 'flex',
        flexDirection: 'column',
        gap: 8,
      }}
    >
      <header style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span className="label" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <StatusDot tone="warn" pulse={!paused} size={5} />
          Alerts Feed
        </span>
        <Segmented
          value={filter}
          onChange={setFilter}
          size="xs"
          options={[
            { value: 'ALL', label: `All · ${counts.ALL}` },
            { value: 'DANGER', label: `Danger · ${counts.DANGER}` },
            { value: 'WARNING', label: `Warning · ${counts.WARNING}` },
            { value: 'INFO', label: `Info · ${counts.INFO}` },
          ]}
        />
        <span style={{ flex: 1 }} />
        <span style={{ fontSize: 10, color: 'var(--fg-3)', fontFamily: 'var(--font-mono)' }}>
          {paused ? 'PAUSED' : 'LIVE'} · {filtered.length} shown
        </span>
        <button type="button" onClick={() => setPaused((p) => !p)} style={feedBtn}>
          {paused ? '▶ Resume' : '⏸ Pause'}
        </button>
        <button
          type="button"
          onClick={() => setDismissed(new Set(live.map((a) => a.id)))}
          style={feedBtn}
        >
          Clear
        </button>
      </header>

      <div style={{ flex: 1, overflowY: 'auto', display: 'flex', flexDirection: 'column' }}>
        {filtered.length === 0 && (
          <div style={{ padding: 12, textAlign: 'center', color: 'var(--fg-3)', fontSize: 11 }}>
            No alerts match this filter.
          </div>
        )}
        {filtered.map((a, idx) => (
          <AlertRow
            key={a.id}
            a={a}
            fresh={!!a.fresh && idx < 2}
            pinned={pinned.has(a.id)}
            onPin={() =>
              setPinned((prev) => {
                const n = new Set(prev);
                if (n.has(a.id)) n.delete(a.id);
                else n.add(a.id);
                return n;
              })
            }
          />
        ))}
      </div>
    </section>
  );
}

function AlertRow({
  a,
  fresh,
  pinned,
  onPin,
}: {
  a: AlertItem;
  fresh: boolean;
  pinned: boolean;
  onPin: () => void;
}) {
  const tone = a.sev === 'DANGER' ? 'neg' : a.sev === 'WARNING' ? 'warn' : 'info';
  const sevColor = `var(--${tone})`;
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 10,
        padding: '5px 6px',
        borderBottom: '1px solid var(--hair-soft)',
        background: fresh ? 'color-mix(in oklab, var(--accent) 8%, transparent)' : 'transparent',
        animation: fresh ? 'rd-flash 1.6s ease-out' : 'none',
      }}
    >
      <span
        style={{
          width: 56,
          fontSize: 9.5,
          color: 'var(--fg-3)',
          fontFamily: 'var(--font-mono)',
          flexShrink: 0,
        }}
      >
        {fmtFeedTime(a.t)}
      </span>
      <span
        style={{
          width: 64,
          fontSize: 9.5,
          color: sevColor,
          textTransform: 'uppercase',
          letterSpacing: '0.06em',
          fontWeight: 700,
          flexShrink: 0,
        }}
      >
        ▍{a.sev}
      </span>
      <span
        style={{
          width: 110,
          fontSize: 9.5,
          color: 'var(--fg-2)',
          fontFamily: 'var(--font-mono)',
          letterSpacing: '0.02em',
          flexShrink: 0,
        }}
      >
        {a.cat}
      </span>
      {a.instrument && (
        <Chip tone="mute" mono>
          {a.instrument}
          {a.tf ? `·${a.tf}` : ''}
        </Chip>
      )}
      <span style={{ flex: 1, fontSize: 11.5, color: 'var(--fg-1)', lineHeight: 1.4 }}>{a.msg}</span>
      <button
        type="button"
        onClick={onPin}
        title={pinned ? 'Unpin' : 'Pin'}
        style={{
          ...feedBtn,
          padding: '2px 6px',
          color: pinned ? 'var(--accent)' : 'var(--fg-3)',
        }}
      >
        {pinned ? '★' : '☆'}
      </button>
    </div>
  );
}

const feedBtn: CSSProperties = {
  padding: '3px 8px',
  border: '1px solid var(--hair)',
  background: 'var(--bg-2)',
  color: 'var(--fg-1)',
  borderRadius: 4,
  fontSize: 10.5,
  fontWeight: 500,
  cursor: 'pointer',
  fontFamily: 'inherit',
};

function fmtFeedTime(t: number): string {
  const d = new Date(t);
  return d.toLocaleTimeString('en-US', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  });
}
