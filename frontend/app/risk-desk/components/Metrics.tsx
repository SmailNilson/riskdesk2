'use client';

import { ReactNode } from 'react';
import { BarGauge, StatusDot } from '../lib/atoms';
import { Portfolio, Rollover, WatchItem } from '../lib/data';
import { fmt } from '../lib/format';

function MetricCell({
  label,
  value,
  sub,
  tone,
  mini,
}: {
  label: string;
  value: ReactNode;
  sub?: ReactNode;
  tone?: 'up' | 'down' | 'warn';
  mini?: ReactNode;
}) {
  const color =
    tone === 'up' ? 'var(--up)' : tone === 'down' ? 'var(--down)' : tone === 'warn' ? 'var(--warn)' : 'var(--ink-0)';
  return (
    <div className="metric-cell">
      <span className="label">{label}</span>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
        <span className="value" style={{ color }}>
          {value}
        </span>
        {sub && <span style={{ fontSize: 10, color: 'var(--ink-3)', fontFamily: 'var(--font-mono)' }}>{sub}</span>}
      </div>
      {mini}
    </div>
  );
}

export function Ticker({ items }: { items: WatchItem[] }) {
  return (
    <div className="ticker">
      {items.map((w) => (
        <div key={w.sym} className="ticker-item">
          <StatusDot kind={w.chg >= 0 ? 'up' : 'down'} size={4} />
          <span className="sym">{w.sym}</span>
          <span className="px">
            {typeof w.px === 'number' && w.px < 10 ? w.px.toFixed(3) : w.px.toLocaleString('en-US')}
          </span>
          <span
            className={w.chg >= 0 ? 'up' : 'down'}
            style={{ fontSize: 11, fontFamily: 'var(--font-mono)' }}
          >
            {fmt.signed(w.chg, Math.abs(w.chg) < 1 ? 3 : 2)}
          </span>
        </div>
      ))}
    </div>
  );
}

export function MetricsBar({ portfolio, watchlist }: { portfolio: Portfolio; watchlist: WatchItem[] }) {
  const p = portfolio;
  const ddPct = Math.min(1, Math.abs(p.dayDD / (p.dayDDLimit || 1)));
  return (
    <div className="metrics-strip">
      <MetricCell label="Unrealized" value={fmt.money(p.unreal)} tone={p.unreal >= 0 ? 'up' : 'down'} />
      <MetricCell label="Today P&L" value={fmt.money(p.realToday)} tone={p.realToday >= 0 ? 'up' : 'down'} sub="real" />
      <MetricCell label="Total P&L" value={fmt.money(p.totalPnL)} tone={p.totalPnL >= 0 ? 'up' : 'down'} />
      <MetricCell
        label="Exposure"
        value={fmt.moneyAbs(p.exposure)}
        sub={`${Math.round((p.exposure / (p.exposureCap || 1)) * 100)}% cap`}
        mini={
          <div style={{ marginTop: 4, width: '100%' }}>
            <BarGauge value={p.exposure} max={p.exposureCap || 1} color="var(--accent)" />
          </div>
        }
      />
      <MetricCell label="Margin Used" value={fmt.moneyAbs(p.margin)} sub={`${Math.round(p.marginPct * 100)}%`} />
      <MetricCell
        label="Day DD"
        value={fmt.money(p.dayDD)}
        tone={ddPct > 0.66 ? 'down' : ddPct > 0.33 ? 'warn' : undefined}
        sub={`/ ${fmt.moneyAbs(p.dayDDLimit)}`}
        mini={
          <div style={{ marginTop: 4, width: '100%' }}>
            <BarGauge
              value={ddPct}
              color={ddPct > 0.66 ? 'var(--down)' : ddPct > 0.33 ? 'var(--warn)' : 'var(--up)'}
            />
          </div>
        }
      />
      <MetricCell label="Buying Pwr" value={fmt.moneyAbs(p.buyingPower)} />
      <MetricCell label="Week" value={fmt.money(p.weekPnL)} tone={p.weekPnL >= 0 ? 'up' : 'down'} />
      <MetricCell label="Month" value={fmt.money(p.monthPnL)} tone={p.monthPnL >= 0 ? 'up' : 'down'} />
      <Ticker items={watchlist} />
    </div>
  );
}

export function RolloverBanner({ rollover, onDismiss }: { rollover: Rollover; onDismiss: () => void }) {
  return (
    <div className="rollover-banner">
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
        <svg width="14" height="14" viewBox="0 0 14 14">
          <path d="M7 1 L13 12 L1 12 Z" stroke="var(--warn)" strokeWidth="1.4" fill="none" />
          <circle cx="7" cy="9.5" r="0.8" fill="var(--warn)" />
          <line x1="7" y1="5" x2="7" y2="8" stroke="var(--warn)" strokeWidth="1.4" strokeLinecap="round" />
        </svg>
        <span
          style={{
            color: 'var(--warn)',
            fontWeight: 600,
            letterSpacing: '0.04em',
            textTransform: 'uppercase',
            fontSize: 10,
          }}
        >
          Rollover
        </span>
      </span>
      <span style={{ color: 'var(--ink-1)' }}>
        <strong>{rollover.symbol}</strong> · front {rollover.front} expires in <strong>{rollover.daysToExpiry} days</strong> ·
        OI shift to {rollover.next} at <strong>{Math.round(rollover.openInterestShift * 100)}%</strong> · basis{' '}
        <span className="mono">
          {rollover.basis >= 0 ? '+' : ''}
          {rollover.basis.toFixed(2)}
        </span>
      </span>
      <span style={{ flex: 1 }} />
      <span style={{ color: 'var(--ink-2)' }}>{rollover.recommended}</span>
      <button type="button" className="btn btn-sm">
        Plan roll
      </button>
      <button type="button" className="btn btn-sm btn-ghost" onClick={onDismiss}>
        Dismiss
      </button>
    </div>
  );
}
