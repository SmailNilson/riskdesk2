'use client';

import { useEffect, useState } from 'react';
import { Delta, Segmented, StatusDot, Tone } from '../lib/atoms';
import { Instrument, Portfolio, Ticker as TickerT } from '../lib/data';

export type ConnectionState = 'LIVE' | 'MARKET CLOSED' | 'OFFLINE';

interface HeaderProps {
  instrumentSym: string;
  setInstrument: (sym: string) => void;
  instruments: Instrument[];
  timeframe: string;
  setTimeframe: (tf: string) => void;
  timeframes: string[];
  theme: 'dark' | 'light';
  setTheme: (t: 'dark' | 'light') => void;
  connection: ConnectionState;
  setConnection: (c: ConnectionState) => void;
  scenario: string;
  setScenario: (s: string) => void;
}

export function Header({
  instrumentSym,
  setInstrument,
  instruments,
  timeframe,
  setTimeframe,
  timeframes,
  theme,
  setTheme,
  connection,
  setConnection,
  scenario,
  setScenario,
}: HeaderProps) {
  const [now, setNow] = useState<Date | null>(null);

  // Defer clock until mounted to prevent SSR/CSR mismatch
  useEffect(() => {
    setNow(new Date());
    const id = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(id);
  }, []);

  const pad = (n: number) => String(n).padStart(2, '0');
  let timeStr = '— —';
  if (now) {
    const etHours = ((now.getUTCHours() - 4) + 24) % 24;
    timeStr = `${pad(etHours)}:${pad(now.getUTCMinutes())}:${pad(now.getUTCSeconds())}`;
  }

  const connTone: Tone =
    connection === 'LIVE' ? 'pos' : connection === 'MARKET CLOSED' ? 'warn' : 'neg';

  const cycleConnection = () => {
    const next: Record<ConnectionState, ConnectionState> = {
      LIVE: 'MARKET CLOSED',
      'MARKET CLOSED': 'OFFLINE',
      OFFLINE: 'LIVE',
    };
    setConnection(next[connection]);
  };

  return (
    <header
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 14,
        padding: '10px 18px',
        borderBottom: '1px solid var(--hair)',
        background: 'var(--bg-1)',
        flexShrink: 0,
        position: 'relative',
        zIndex: 10,
      }}
    >
      {/* Logo */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <div
          style={{
            width: 22,
            height: 22,
            borderRadius: 5,
            background: 'var(--accent)',
            display: 'grid',
            placeItems: 'center',
            color: 'var(--bg-0)',
            fontWeight: 800,
            fontSize: 13,
            fontFamily: 'var(--font-mono)',
          }}
        >
          R
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', lineHeight: 1.1 }}>
          <span style={{ fontSize: 13, fontWeight: 700, letterSpacing: '-0.01em' }}>RiskDesk</span>
          <span className="label" style={{ fontSize: 8.5, color: 'var(--fg-3)' }}>
            Trader Terminal · v0.4
          </span>
        </div>
      </div>

      <div style={{ width: 1, height: 28, background: 'var(--hair)' }} />

      {/* Instrument tabs */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 2 }}>
        {instruments
          .filter((i) => i.sym !== 'DXY')
          .map((inst) => {
            const active = instrumentSym === inst.sym;
            return (
              <button
                key={inst.sym}
                type="button"
                onClick={() => setInstrument(inst.sym)}
                title={`${inst.name} · ${inst.asset}`}
                style={{
                  padding: '6px 11px',
                  fontSize: 11.5,
                  fontWeight: 600,
                  background: active ? 'var(--bg-3)' : 'transparent',
                  color: active ? 'var(--fg-0)' : 'var(--fg-2)',
                  border: '1px solid',
                  borderColor: active ? 'var(--hair)' : 'transparent',
                  borderRadius: 5,
                  cursor: 'pointer',
                  fontFamily: 'var(--font-mono)',
                  letterSpacing: '0.04em',
                  transition: 'all 120ms',
                }}
                onMouseEnter={(e) => {
                  if (!active) (e.currentTarget as HTMLButtonElement).style.color = 'var(--fg-0)';
                }}
                onMouseLeave={(e) => {
                  if (!active) (e.currentTarget as HTMLButtonElement).style.color = 'var(--fg-2)';
                }}
              >
                {inst.sym}
              </button>
            );
          })}
      </div>

      <div style={{ width: 1, height: 22, background: 'var(--hair-soft)' }} />

      {/* Timeframe */}
      <Segmented value={timeframe} onChange={setTimeframe} options={timeframes} size="sm" />

      {/* Search */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          padding: '5px 10px',
          marginLeft: 4,
          border: '1px solid var(--hair)',
          borderRadius: 6,
          minWidth: 220,
          flex: '0 1 280px',
          background: 'var(--bg-0)',
          fontSize: 11,
          color: 'var(--fg-3)',
        }}
      >
        <svg width="11" height="11" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6">
          <circle cx="7" cy="7" r="5" />
          <path d="M11 11l3 3" />
        </svg>
        <span style={{ flex: 1 }}>Jump to instrument, alert, review… </span>
        <kbd
          style={{
            fontFamily: 'var(--font-mono)',
            fontSize: 9.5,
            padding: '1px 5px',
            background: 'var(--bg-2)',
            border: '1px solid var(--hair)',
            borderRadius: 3,
            color: 'var(--fg-2)',
          }}
        >
          ⌘K
        </kbd>
      </div>

      <div style={{ flex: 1 }} />

      {/* Connection / clock */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <button
          type="button"
          onClick={cycleConnection}
          title="Click to cycle connection state"
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            padding: '4px 9px',
            border: '1px solid var(--hair)',
            background: 'var(--bg-0)',
            borderRadius: 5,
            cursor: 'pointer',
          }}
        >
          <StatusDot tone={connTone} pulse={connection === 'LIVE'} />
          <span className="label" style={{ fontSize: 9.5 }}>
            {connection}
          </span>
        </button>

        <span className="num" style={{ fontSize: 11, color: 'var(--fg-2)' }}>
          {timeStr} ET
        </span>

        <div style={{ width: 1, height: 22, background: 'var(--hair-soft)' }} />

        <select
          value={scenario}
          onChange={(e) => setScenario(e.target.value)}
          style={{
            padding: '4px 8px',
            border: '1px solid var(--hair)',
            background: 'var(--bg-0)',
            borderRadius: 5,
            fontSize: 10.5,
            color: 'var(--fg-1)',
            cursor: 'pointer',
          }}
        >
          <option value="live">Scenario: Live</option>
          <option value="quiet">Scenario: Quiet</option>
          <option value="risk">Scenario: Risk Event</option>
          <option value="armed">Scenario: Armed</option>
        </select>

        <button
          type="button"
          onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}
          style={{
            padding: '4px 9px',
            border: '1px solid var(--hair)',
            background: 'var(--bg-0)',
            borderRadius: 5,
            color: 'var(--fg-1)',
            fontSize: 12,
            cursor: 'pointer',
          }}
          title="Toggle theme"
        >
          {theme === 'dark' ? '☾' : '☀'}
        </button>
      </div>
    </header>
  );
}

// ───────────────────────────────────────────────────
// Metrics strip — beneath header

interface MetricsStripProps {
  portfolio: Portfolio;
}

export function MetricsStrip({ portfolio }: MetricsStripProps) {
  const items: Array<
    | { label: string; value: number; kind: 'money'; emphasize?: boolean }
    | { label: string; value: number; kind: 'raw' }
    | { label: string; value: number; kind: 'exposure' }
    | { label: string; value: number; kind: 'pct' }
  > = [
    { label: 'Unrealized', value: portfolio.unrealized, kind: 'money' },
    { label: 'Today Realized', value: portfolio.todayRealized, kind: 'money' },
    { label: 'Total P&L', value: portfolio.total, kind: 'money', emphasize: true },
    { label: 'Open', value: portfolio.openCount, kind: 'raw' },
    { label: 'Exposure', value: portfolio.exposure, kind: 'exposure' },
    { label: 'Margin', value: portfolio.marginPct, kind: 'pct' },
    { label: 'Day DD', value: portfolio.dayDD, kind: 'money' },
  ];

  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'stretch',
        borderBottom: '1px solid var(--hair)',
        background: 'var(--bg-1)',
        flexShrink: 0,
      }}
    >
      {items.map((it, i) => {
        const emphasize = 'emphasize' in it && it.emphasize;
        return (
          <div
            key={it.label}
            style={{
              display: 'flex',
              flexDirection: 'column',
              padding: '8px 18px',
              minWidth: 110,
              borderRight: i === items.length - 1 ? 'none' : '1px solid var(--hair-soft)',
              flex: '0 0 auto',
              background: emphasize ? 'var(--bg-2)' : 'transparent',
            }}
          >
            <span className="label" style={{ marginBottom: 3 }}>
              {it.label}
            </span>
            {it.kind === 'money' && (
              <Delta value={it.value} decimals={2} prefix="$" size={emphasize ? 16 : 14} />
            )}
            {it.kind === 'raw' && (
              <span className="num" style={{ fontSize: 14, fontWeight: 600 }}>
                {it.value}
              </span>
            )}
            {it.kind === 'exposure' && (
              <span className="num" style={{ fontSize: 14, fontWeight: 600 }}>
                ${(it.value / 1000).toFixed(1)}k
              </span>
            )}
            {it.kind === 'pct' && (
              <span
                className="num"
                style={{
                  fontSize: 14,
                  fontWeight: 600,
                  color:
                    it.value > 80 ? 'var(--neg)' : it.value > 60 ? 'var(--warn)' : 'var(--pos)',
                }}
              >
                {it.value.toFixed(1)}%
              </span>
            )}
          </div>
        );
      })}

      <div style={{ flex: 1 }} />

      {/* Right side — risk gauge */}
      <div
        style={{
          display: 'flex',
          flexDirection: 'column',
          padding: '8px 18px',
          borderLeft: '1px solid var(--hair-soft)',
          minWidth: 220,
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
          <span className="label">Daily Loss Limit</span>
          <span className="num" style={{ fontSize: 10.5, color: 'var(--fg-2)' }}>
            <span style={{ color: 'var(--neg)' }}>−$126</span>
            <span style={{ color: 'var(--fg-3)' }}> / </span>
            <span>−$800</span>
          </span>
        </div>
        <div
          style={{
            position: 'relative',
            height: 5,
            background: 'var(--bg-2)',
            borderRadius: 2,
            overflow: 'hidden',
          }}
        >
          <div style={{ position: 'absolute', inset: 0, left: 0, width: '16%', background: 'var(--warn)' }} />
          <div
            style={{
              position: 'absolute',
              left: '16%',
              top: -1,
              bottom: -1,
              width: 1,
              background: 'var(--fg-1)',
            }}
          />
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 3 }}>
          <span style={{ fontSize: 9.5, color: 'var(--fg-3)' }}>16% used</span>
          <span style={{ fontSize: 9.5, color: 'var(--fg-3)' }}>Target +$600</span>
        </div>
      </div>
    </div>
  );
}

// ───────────────────────────────────────────────────
// Ticker — horizontal pill row

interface TickerProps {
  tickers: TickerT[];
  instrumentSym: string;
  onSelect: (sym: string) => void;
}

export function Ticker({ tickers, instrumentSym, onSelect }: TickerProps) {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 0,
        padding: '0 18px',
        borderBottom: '1px solid var(--hair-soft)',
        background: 'var(--bg-0)',
        flexShrink: 0,
        overflowX: 'auto',
        height: 36,
      }}
    >
      <span className="label" style={{ marginRight: 12, color: 'var(--fg-3)' }}>
        WATCHLIST
      </span>
      {tickers.map((t) => {
        const active = t.sym === instrumentSym;
        const dec = t.sym === 'E6' ? 5 : t.sym === 'DXY' ? 3 : 2;
        return (
          <button
            type="button"
            key={t.sym}
            onClick={() => t.sym !== 'DXY' && onSelect(t.sym)}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              padding: '0 14px',
              height: '100%',
              background: active ? 'var(--bg-2)' : 'transparent',
              border: 'none',
              borderBottomWidth: 2,
              borderBottomStyle: 'solid',
              borderBottomColor: active ? 'var(--accent)' : 'transparent',
              cursor: t.sym === 'DXY' ? 'default' : 'pointer',
              color: 'inherit',
            }}
          >
            <span
              className="num"
              style={{
                fontSize: 10.5,
                color: 'var(--fg-2)',
                fontWeight: 600,
                letterSpacing: '0.04em',
              }}
            >
              {t.sym}
            </span>
            <span className="num" style={{ fontSize: 12, fontWeight: 600 }}>
              {t.price.toFixed(dec)}
            </span>
            <Delta value={t.chgPct} decimals={2} suffix="%" size={10.5} />
          </button>
        );
      })}
    </div>
  );
}
