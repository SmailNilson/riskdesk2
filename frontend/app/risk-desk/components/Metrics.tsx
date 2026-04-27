'use client';

import { ReactNode, useState } from 'react';
import { BarGauge, StatusDot } from '../lib/atoms';
import { Portfolio, Rollover, RolloverInstrument, WatchItem } from '../lib/data';
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

// Pick the instrument with the highest urgency: prefer CRITICAL > WARNING > STABLE,
// then the smallest daysToExpiry. Falls back to the mock Rollover when no live data.
function pickPrimaryRollover(details: RolloverInstrument[]): RolloverInstrument | null {
  if (!details.length) return null;
  const sev = (s: string) => (s === 'CRITICAL' ? 2 : s === 'WARNING' ? 1 : 0);
  return [...details].sort((a, b) => {
    const d = sev(b.status) - sev(a.status);
    if (d !== 0) return d;
    return a.daysToExpiry - b.daysToExpiry;
  })[0];
}

export function RolloverBanner({
  rollover,
  rolloverDetails,
  onDismiss,
  onConfirm,
}: {
  rollover: Rollover;
  rolloverDetails: RolloverInstrument[];
  onDismiss: () => void;
  onConfirm: (instrument: string, contractMonth: string) => Promise<boolean>;
}) {
  const live = pickPrimaryRollover(rolloverDetails);
  const [planTarget, setPlanTarget] = useState<RolloverInstrument | null>(null);
  const [newMonth, setNewMonth] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [confirmMsg, setConfirmMsg] = useState<string | null>(null);

  const symbol = live?.instrument ?? rollover.symbol;
  const front = live?.contractMonth ?? rollover.front;
  const daysToExpiry = live?.daysToExpiry ?? rollover.daysToExpiry;
  const oiAction = live?.oiAction;
  const recommended =
    oiAction === 'RECOMMEND_ROLL'
      ? `OI crossover — roll ${live?.currentMonth ?? front} → ${live?.nextMonth ?? ''}`
      : oiAction === 'NO_ACTION'
      ? 'OI stable — no roll yet'
      : rollover.recommended;
  const tone =
    live?.status === 'CRITICAL'
      ? 'var(--down)'
      : live?.status === 'WARNING' || daysToExpiry <= 7
      ? 'var(--warn)'
      : 'var(--ink-2)';

  const startConfirm = () => {
    if (!live) return;
    setPlanTarget(live);
    setNewMonth(live.nextMonth ?? '');
    setConfirmMsg(null);
  };
  const submitConfirm = async () => {
    if (!planTarget || !newMonth) return;
    setConfirming(true);
    setConfirmMsg(null);
    const ok = await onConfirm(planTarget.instrument, newMonth);
    setConfirming(false);
    if (ok) {
      setConfirmMsg(`✓ ${planTarget.instrument} → ${newMonth}`);
      setTimeout(() => {
        setPlanTarget(null);
        setConfirmMsg(null);
      }, 1500);
    } else {
      setConfirmMsg('error');
    }
  };

  return (
    <div className="rollover-banner">
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
        <svg width="14" height="14" viewBox="0 0 14 14">
          <path d="M7 1 L13 12 L1 12 Z" stroke={tone} strokeWidth="1.4" fill="none" />
          <circle cx="7" cy="9.5" r="0.8" fill={tone} />
          <line x1="7" y1="5" x2="7" y2="8" stroke={tone} strokeWidth="1.4" strokeLinecap="round" />
        </svg>
        <span
          style={{
            color: tone,
            fontWeight: 600,
            letterSpacing: '0.04em',
            textTransform: 'uppercase',
            fontSize: 10,
          }}
        >
          Rollover {live?.status === 'CRITICAL' ? '· CRITICAL' : live?.status === 'WARNING' ? '· WARNING' : ''}
        </span>
      </span>

      {planTarget ? (
        <>
          <span style={{ color: 'var(--ink-1)' }}>
            Confirm rollover <strong>{planTarget.instrument}</strong> · current{' '}
            <span className="mono">{planTarget.contractMonth ?? '—'}</span> → new month
          </span>
          <input
            value={newMonth}
            onChange={(e) => setNewMonth(e.target.value.toUpperCase())}
            placeholder="e.g. JUN26"
            disabled={confirming}
            style={{
              width: 90,
              height: 24,
              padding: '0 8px',
              background: 'var(--s2)',
              border: '1px solid var(--line)',
              borderRadius: 4,
              color: 'var(--ink-1)',
              fontFamily: 'var(--font-mono)',
              fontSize: 11,
            }}
          />
          <button
            type="button"
            className="btn btn-sm"
            onClick={submitConfirm}
            disabled={confirming || !newMonth}
            style={{ background: 'var(--accent)', color: 'var(--accent-deep, #022)' }}
          >
            {confirming ? '…' : 'Confirm'}
          </button>
          <button
            type="button"
            className="btn btn-sm btn-ghost"
            onClick={() => setPlanTarget(null)}
            disabled={confirming}
          >
            Cancel
          </button>
          {confirmMsg && (
            <span className="mono" style={{ fontSize: 10, color: confirmMsg.startsWith('✓') ? 'var(--up)' : 'var(--down)' }}>
              {confirmMsg}
            </span>
          )}
        </>
      ) : (
        <>
          <span style={{ color: 'var(--ink-1)' }}>
            <strong>{symbol}</strong> · front {front} expires in{' '}
            <strong>{daysToExpiry} days</strong>
            {live?.currentOI != null && live?.nextOI != null && (
              <>
                {' '}
                · OI{' '}
                <span className="mono">
                  {live.currentOI.toLocaleString('en-US')} → {live.nextOI.toLocaleString('en-US')}
                </span>
              </>
            )}
            {!live && (
              <>
                {' '}
                · OI shift {Math.round(rollover.openInterestShift * 100)}% · basis{' '}
                <span className="mono">
                  {rollover.basis >= 0 ? '+' : ''}
                  {rollover.basis.toFixed(2)}
                </span>
              </>
            )}
          </span>
          {/* All instrument statuses, condensed pills. */}
          {rolloverDetails.length > 1 && (
            <div style={{ display: 'flex', gap: 4 }}>
              {rolloverDetails.map((r) => {
                const c =
                  r.status === 'CRITICAL'
                    ? 'var(--down)'
                    : r.status === 'WARNING'
                    ? 'var(--warn)'
                    : 'var(--ink-3)';
                return (
                  <span
                    key={r.instrument}
                    title={`${r.instrument} · ${r.contractMonth ?? '—'} · ${r.daysToExpiry}d · ${r.status}`}
                    style={{
                      fontSize: 9,
                      fontFamily: 'var(--font-mono)',
                      padding: '1px 5px',
                      borderRadius: 3,
                      border: `1px solid ${c}`,
                      color: c,
                    }}
                  >
                    {r.instrument} {r.daysToExpiry}d
                  </span>
                );
              })}
            </div>
          )}
          <span style={{ flex: 1 }} />
          <span style={{ color: 'var(--ink-2)', fontSize: 11 }}>{recommended}</span>
          <button
            type="button"
            className="btn btn-sm"
            onClick={startConfirm}
            disabled={!live}
          >
            Plan roll
          </button>
          <button type="button" className="btn btn-sm btn-ghost" onClick={onDismiss}>
            Dismiss
          </button>
        </>
      )}
    </div>
  );
}
