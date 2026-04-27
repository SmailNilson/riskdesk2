'use client';

import { Chip } from '../lib/atoms';
import { AlertItem } from '../lib/data';

export function AlertsRail({ alerts }: { alerts: AlertItem[] }) {
  const openCount = alerts.filter((a) => a.sev === 'warn' || a.sev === 'alert').length;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          padding: '10px 14px',
          borderBottom: '1px solid var(--line)',
        }}
      >
        <span
          style={{
            fontSize: 12,
            fontWeight: 700,
            color: 'var(--ink-0)',
            letterSpacing: '0.02em',
          }}
        >
          Alerts
        </span>
        <Chip kind="warn">{openCount} open</Chip>
        <span style={{ flex: 1 }} />
        <button type="button" className="btn btn-sm btn-ghost">
          Filter
        </button>
      </div>
      <div style={{ overflowY: 'auto', flex: 1 }}>
        {alerts.map((a, i) => {
          const sevColor =
            a.sev === 'alert'
              ? 'var(--down)'
              : a.sev === 'warn'
              ? 'var(--warn)'
              : a.sev === 'ok'
              ? 'var(--up)'
              : 'var(--info)';
          return (
            <div key={i} className="alert-row">
              <span
                style={{
                  width: 4,
                  height: 4,
                  borderRadius: 2,
                  background: sevColor,
                  marginTop: 5,
                  flexShrink: 0,
                }}
              />
              <span className="time">{a.t}</span>
              <span className="body">
                <span
                  style={{
                    color: sevColor,
                    fontFamily: 'var(--font-mono)',
                    fontSize: 9,
                    fontWeight: 700,
                    letterSpacing: '0.06em',
                    marginRight: 6,
                  }}
                >
                  {a.src}
                </span>
                <span style={{ color: 'var(--ink-1)' }}>{a.msg}</span>
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
