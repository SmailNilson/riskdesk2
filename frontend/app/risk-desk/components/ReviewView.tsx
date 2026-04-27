'use client';

import { Fragment } from 'react';
import { Chip, Panel, SectionLabel, Sparkline, StatusDot } from '../lib/atoms';
import { Backtest, Trailing } from '../lib/data';

function BacktestPanel({ b }: { b: Backtest }) {
  return (
    <Panel
      title="Backtest · 30d"
      right={<Chip kind="accent">PF {b.profitFactor.toFixed(2)}</Chip>}
    >
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr', gap: 10 }}>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)' }}>Trades</div>
          <div className="mono" style={{ fontSize: 16, color: 'var(--ink-0)' }}>
            {b.trades}
          </div>
        </div>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)' }}>Win rate</div>
          <div className="mono" style={{ fontSize: 16, color: 'var(--up)' }}>
            {Math.round(b.winRate * 100)}%
          </div>
        </div>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)' }}>Avg RR</div>
          <div className="mono" style={{ fontSize: 16, color: 'var(--ink-0)' }}>
            {b.avgRR.toFixed(2)}R
          </div>
        </div>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)' }}>Sharpe</div>
          <div className="mono" style={{ fontSize: 16, color: 'var(--ink-0)' }}>
            {b.sharpe.toFixed(2)}
          </div>
        </div>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)' }}>Expectancy</div>
          <div className="mono" style={{ fontSize: 16, color: 'var(--up)' }}>
            {b.expectancy.toFixed(2)}R
          </div>
        </div>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)' }}>Max DD</div>
          <div className="mono" style={{ fontSize: 16, color: 'var(--down)' }}>
            ${b.maxDD}
          </div>
        </div>
        <div style={{ gridColumn: 'span 2' }}>
          <div style={{ fontSize: 10, color: 'var(--ink-3)' }}>Best setup</div>
          <div style={{ fontSize: 12, color: 'var(--ink-0)', fontWeight: 500 }}>{b.bestSetup}</div>
        </div>
      </div>
      <SectionLabel>Equity curve · cumulative R</SectionLabel>
      <Sparkline values={b.equity} color="var(--accent)" w={420} h={64} fill />
      <div style={{ display: 'flex', justifyContent: 'space-between' }}>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)' }}>Best hour</div>
          <div className="mono" style={{ color: 'var(--up)' }}>
            {b.bestHour}
          </div>
        </div>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)' }}>Worst hour</div>
          <div className="mono" style={{ color: 'var(--down)' }}>
            {b.worstHour}
          </div>
        </div>
      </div>
    </Panel>
  );
}

function TrailingPanel({ t }: { t: Trailing }) {
  return (
    <Panel
      title="Trailing-Stop Comparison"
      right={<Chip kind="accent">recommended ATR×1.2</Chip>}
    >
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: '1fr 70px 70px 80px 80px',
          gap: 0,
          fontSize: 11,
        }}
      >
        {['Mode', 'Win%', 'Avg RR', 'PF', 'Expectancy'].map((h) => (
          <div
            key={h}
            style={{
              fontSize: 10,
              color: 'var(--ink-3)',
              letterSpacing: '0.06em',
              textTransform: 'uppercase',
              padding: '4px 6px',
              borderBottom: '1px solid var(--line)',
              textAlign: h === 'Mode' ? 'left' : 'right',
              fontWeight: 600,
            }}
          >
            {h}
          </div>
        ))}
        {t.modes.map((m) => (
          <Fragment key={m.name}>
            <div
              style={{
                padding: '8px 6px',
                display: 'flex',
                alignItems: 'center',
                gap: 6,
                borderBottom: '1px solid var(--line)',
                background: m.active ? 'var(--accent-glow)' : 'transparent',
              }}
            >
              {m.active && <StatusDot kind="accent" />}
              <span
                style={{
                  color: m.active ? 'var(--accent)' : 'var(--ink-1)',
                  fontWeight: m.active ? 600 : 400,
                }}
              >
                {m.name}
              </span>
            </div>
            <div
              style={{
                padding: '8px 6px',
                textAlign: 'right',
                fontFamily: 'var(--font-mono)',
                borderBottom: '1px solid var(--line)',
              }}
            >
              {Math.round(m.winRate * 100)}%
            </div>
            <div
              style={{
                padding: '8px 6px',
                textAlign: 'right',
                fontFamily: 'var(--font-mono)',
                borderBottom: '1px solid var(--line)',
              }}
            >
              {m.avgRR.toFixed(2)}
            </div>
            <div
              style={{
                padding: '8px 6px',
                textAlign: 'right',
                fontFamily: 'var(--font-mono)',
                borderBottom: '1px solid var(--line)',
              }}
            >
              {m.profitFactor.toFixed(2)}
            </div>
            <div
              style={{
                padding: '8px 6px',
                textAlign: 'right',
                fontFamily: 'var(--font-mono)',
                borderBottom: '1px solid var(--line)',
                color: 'var(--up)',
              }}
            >
              {m.expectancy.toFixed(2)}R
            </div>
          </Fragment>
        ))}
      </div>
      <div
        style={{
          padding: 8,
          background: 'var(--accent-glow)',
          borderRadius: 4,
          fontSize: 11,
          color: 'var(--ink-1)',
          border: '1px solid color-mix(in oklch, var(--accent) 30%, transparent)',
        }}
      >
        <strong style={{ color: 'var(--accent)' }}>→</strong> {t.recommendation}
      </div>
    </Panel>
  );
}

function SimulationPanel() {
  const sims = [
    { name: 'Current plan', rr: 2.11, prob: 0.62, ev: 0.65, action: 'active' as const },
    { name: 'Tighter SL (10t)', rr: 3.02, prob: 0.48, ev: 0.46, action: 'alt' as const },
    { name: 'Wider TP (50t)', rr: 2.78, prob: 0.41, ev: 0.34, action: 'alt' as const },
    { name: 'Half size', rr: 2.11, prob: 0.62, ev: 0.32, action: 'alt' as const },
    { name: 'Skip + watch', rr: 0.0, prob: 0.5, ev: 0.0, action: 'alt' as const },
  ];
  return (
    <Panel title="Simulation · What-If" subtitle="based on 30d distribution">
      {sims.map((s, i) => (
        <div
          key={i}
          style={{
            display: 'grid',
            gridTemplateColumns: '1fr 60px 60px 80px',
            alignItems: 'center',
            gap: 8,
            padding: '6px 8px',
            borderBottom: '1px solid var(--line)',
            background: s.action === 'active' ? 'var(--accent-glow)' : 'transparent',
          }}
        >
          <div
            style={{
              fontSize: 12,
              color: s.action === 'active' ? 'var(--accent)' : 'var(--ink-1)',
              fontWeight: s.action === 'active' ? 600 : 400,
            }}
          >
            {s.name}
          </div>
          <div className="mono" style={{ fontSize: 11, textAlign: 'right' }}>
            {s.rr.toFixed(2)}R
          </div>
          <div className="mono" style={{ fontSize: 11, textAlign: 'right' }}>
            {Math.round(s.prob * 100)}%
          </div>
          <div
            className="mono"
            style={{
              fontSize: 11,
              textAlign: 'right',
              color: s.ev > 0.5 ? 'var(--up)' : s.ev > 0 ? 'var(--ink-1)' : 'var(--ink-3)',
            }}
          >
            EV {s.ev.toFixed(2)}R
          </div>
        </div>
      ))}
    </Panel>
  );
}

export function ReviewView({ backtest, trailing }: { backtest: Backtest; trailing: Trailing }) {
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: '1.2fr 1fr',
        gap: 12,
        padding: 12,
      }}
    >
      <BacktestPanel b={backtest} />
      <SimulationPanel />
      <div style={{ gridColumn: '1 / -1' }}>
        <TrailingPanel t={trailing} />
      </div>
    </div>
  );
}
